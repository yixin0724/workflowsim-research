package org.workflowsim.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 端点公平共享的流体链路争用模型（R2）。
 *
 * <p>每个传输由占用资源键集合（端点或链路，字符串键）、字节量与无争用名义
 * 速率描述；已注册容量的资源（VM 端点为 {@code vm.getBw()} 折算字节/秒，
 * Fat-tree 链路见 {@code org.workflowsim.network.FatTreeTopology}）在其全部
 * 活动传输之间采用 progressive filling 的最大最小公平分配，受名义速率上限和
 * 所占全部资源容量共同约束；未注册资源（如 SOURCE）不设上限。某流受其他瓶颈
 * 限制时，其未使用份额继续分配给其他流。积分会在每个内部完成时点重分配速率，
 * 即使调用方一次推进跨过多个完成时点，也不会延迟容量回收。</p>
 *
 * <p>双端点重载是资源集重载在 {@code [source, destination]} 上的特例，行为
 * 逐位一致。资源集内重复键按出现次数重复计数（调用方传入的路径键集合应
 * 无重复）。</p>
 *
 * <p>本类不依赖 CloudSim：调用方在传输开始时 {@link #addTransfer}，并在每次
 * 时钟推进点调用 {@link #advance} 获取完成的传输与下一次预测完成时刻。状态
 * 迭代按插入顺序进行，全部计算为确定性。</p>
 *
 * <p>诚实边界：这是抽象流体模型——没有分组、丢包、排队时延、链路拓扑或协议
 * 行为；端点容量是对 VM 网络接口的公平共享近似。它回答的是"并发传输互相
 * 减速多少"这类调度权衡问题，不回答真实网络的可观测细节。</p>
 */
public final class TransferContentionEngine {

    /** Always-available completion clocks for the explicitly checked V2 service path, not a trace record. */
    public static final class CompletionObservation {
        private final long transferId;
        private final double effectiveTime,observedTime,remainingAfterService;
        private CompletionObservation(long id,double effective,double observed,double remaining){transferId=id;effectiveTime=effective;observedTime=observed;remainingAfterService=remaining;}
        /** @return completed caller identity */ public long getTransferId(){return transferId;}
        /** @return internal fluid settlement time */ public double getEffectiveTime(){return effectiveTime;}
        /** @return outer advance observation */ public double getObservedTime(){return observedTime;}
        /** @return pre-settlement numerical balance residual, not traffic */ public double getRemainingAfterService(){return remainingAfterService;}
    }

    /** 一次 {@link #advance} 调用的结算结果。 */
    public static final class AdvanceResult {
        private final List<Long> completedTransferIds;
        private final Double nextCompletionTime;
        private final List<CompletionObservation> completionObservations;

        private AdvanceResult(List<Long> completedTransferIds, Double nextCompletionTime) {
            this(completedTransferIds,nextCompletionTime,Collections.<CompletionObservation>emptyList());
        }
        private AdvanceResult(List<Long> completedTransferIds, Double nextCompletionTime,List<CompletionObservation> observations) {
            this.completedTransferIds = completedTransferIds;
            this.nextCompletionTime = nextCompletionTime;
            this.completionObservations=Collections.unmodifiableList(observations);
        }
        /** @return immutable checked-V2 completion clocks; empty for original constructors */
        public List<CompletionObservation> getCompletionObservations(){return completionObservations;}

        /** 本次推进中完成的传输 ID：按完成时点排列，同刻按插入顺序排列。 */
        public List<Long> getCompletedTransferIds() {
            return completedTransferIds;
        }

        /**
         * 剩余活动传输的最早预测完成时刻（绝对仿真时间）；无活动传输时为
         * {@code null}。
         */
        public Double getNextCompletionTime() {
            return nextCompletionTime;
        }
    }

    /** 一个活动传输的流体状态。 */
    private static final class Transfer {
        private final double bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private double remainingBytes;
        private double rateBytesPerSecond;
        private double coherentAdmissionTime;

        // Enabled-only primitive bookkeeping; OFF allocates no per-flow trace object.
        private long traceAdmissionOrdinal;
        private double tracePreviousRate;
        private double traceRemainingBefore;
        private double traceRemainingAfterService;

        private Transfer(double bytes, List<String> occupiedResources,
                double nominalRateBytesPerSecond) {
            this.bytes = bytes;
            this.occupiedResources = occupiedResources;
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.remainingBytes = bytes;
            this.rateBytesPerSecond = nominalRateBytesPerSecond;
        }

        private boolean completed() {
            // A fixed absolute byte tolerance can exceed a small flow's entire payload.
            // Keep relative round-off protection, account for representable spacing, and
            // never classify an untouched positive flow as completed (including subnormals).
            double tolerance = Math.min(bytes * 0.5,
                    Math.max(1.0e-9 * bytes, 4.0 * Math.ulp(bytes)));
            return remainingBytes <= tolerance;
        }
    }

    /** Allocated only for a positive record budget; never calls user code. */
    private static final class TraceState {
        private final int maxEvents;
        private final List<TransferTraceEvent> events = new ArrayList<TransferTraceEvent>();
        private long droppedCount;
        private long nextAdmissionOrdinal = 1L;
        private double intervalStart;

        private TraceState(int maxEvents) { this.maxEvents = maxEvents; }

        // Call only for true events. Reserve before DTO construction or resource copying.
        private long reserveSequence() {
            if (events.size() == maxEvents) {
                droppedCount++;
                return 0L;
            }
            return events.size() + 1L;
        }

        private void capacity(String resource, double capacity, double engineTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.capacity(sequence, engineTime, resource, capacity));
            }
        }

        private void start(long id, Transfer transfer, double now) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.start(sequence, now, id, transfer.traceAdmissionOrdinal,
                        transfer.bytes, transfer.occupiedResources, transfer.nominalRateBytesPerSecond,
                        transfer.rateBytesPerSecond));
            }
        }

        private void rateChange(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.rateChange(sequence, effectiveTime, observedTime, id,
                        transfer.traceAdmissionOrdinal, transfer.tracePreviousRate, transfer.rateBytesPerSecond));
            }
        }

        private void service(long id, Transfer transfer, double effectiveTime, double observedTime, double elapsed) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.serviceSegment(sequence, intervalStart, effectiveTime, observedTime,
                        id, transfer.traceAdmissionOrdinal, elapsed, transfer.rateBytesPerSecond,
                        transfer.traceRemainingBefore, transfer.traceRemainingAfterService));
            }
        }

        private void complete(long id, Transfer transfer, double effectiveTime, double observedTime) {
            long sequence = reserveSequence();
            if (sequence != 0L) {
                events.add(TransferTraceEvent.complete(sequence, effectiveTime, observedTime,
                        id, transfer.traceAdmissionOrdinal, transfer.traceRemainingAfterService));
            }
        }
    }

    /** 端点容量表：未注册的端点视为容量无限。 */
    private final Map<String, Double> endpointCapacitiesBytesPerSecond = new LinkedHashMap<String, Double>();
    /** 活动传输表，按插入顺序迭代保证确定性。 */
    private final Map<Long, Transfer> activeTransfers = new LinkedHashMap<Long, Transfer>();
    private double lastAdvanceTime = 0.0;
    private final TraceState traceState;
    private final boolean coherentChecked;
    private final boolean shareResources;

    /** Construct the original, history-free engine with capture disabled. */
    public TransferContentionEngine() { this(0); }

    /**
     * Construct an engine with optional bounded internal trace capture.
     * A positive budget retains a deterministic prefix. Exactly filling it is
     * still COMPLETE; only a subsequent true event makes capture TRUNCATED.
     * Once full, true events are counted without allocating their DTOs or trace
     * resource copies. The budget bounds records, not bytes: START resource
     * lists and caller-retained snapshots can use additional memory.
     *
     * @param maxTraceEvents zero disables capture; positive values bound retained events
     * @throws IllegalArgumentException if the budget is negative
     */
    public TransferContentionEngine(int maxTraceEvents) { this(maxTraceEvents,false,true); }

    private TransferContentionEngine(int budget,boolean checked,boolean sharing){
        if(budget<0)throw new IllegalArgumentException("Trace event budget cannot be negative: "+budget);
        traceState=budget==0?null:new TraceState(budget);coherentChecked=checked;shareResources=sharing;
    }

    /**
     * Checked transactional V2 service, without optional history. Only inter-flow sharing differs
     * between modes; isolated service still enforces each route's single-flow bottlenecks.
     * @param sharing whether physical resources are shared between flows
     * @return independent checked service engine
     */
    public static TransferContentionEngine coherentV2(boolean sharing){return new TransferContentionEngine(0,true,sharing);}

    /** @return current committed service clock without advancing it */
    public double getCurrentTime(){return lastAdvanceTime;}

    /** @return deep independent service-state copy; immutable retained trace DTOs may be shared */
    public TransferContentionEngine fork(){
        TransferContentionEngine copy=new TransferContentionEngine(traceState==null?0:traceState.maxEvents,coherentChecked,shareResources);
        copy.endpointCapacitiesBytesPerSecond.putAll(endpointCapacitiesBytesPerSecond);copy.lastAdvanceTime=lastAdvanceTime;
        for(Map.Entry<Long,Transfer> entry:activeTransfers.entrySet()){
            Transfer old=entry.getValue(),fresh=new Transfer(old.bytes,new ArrayList<String>(old.occupiedResources),old.nominalRateBytesPerSecond);
            fresh.remainingBytes=old.remainingBytes;fresh.rateBytesPerSecond=old.rateBytesPerSecond;fresh.coherentAdmissionTime=old.coherentAdmissionTime;fresh.traceAdmissionOrdinal=old.traceAdmissionOrdinal;
            fresh.tracePreviousRate=old.tracePreviousRate;fresh.traceRemainingBefore=old.traceRemainingBefore;fresh.traceRemainingAfterService=old.traceRemainingAfterService;
            copy.activeTransfers.put(entry.getKey(),fresh);
        }
        if(traceState!=null){copy.traceState.events.addAll(traceState.events);copy.traceState.droppedCount=traceState.droppedCount;copy.traceState.nextAdmissionOrdinal=traceState.nextAdmissionOrdinal;copy.traceState.intervalStart=traceState.intervalStart;}
        return copy;
    }

    private void commitChecked(TransferContentionEngine staged){
        // Only the new history-free checked path commits staged transitions. V1 never enters here.
        activeTransfers.clear();activeTransfers.putAll(staged.activeTransfers);lastAdvanceTime=staged.lastAdvanceTime;
    }

    /**
     * Return a deeply immutable point-in-time trace view. Capture completeness
     * does not indicate that every active transfer has finished. This query
     * neither advances the clock nor emits an event.
     *
     * @return trace status, immutable event prefix, dropped count, and known engine time
     */
    public TransferTraceSnapshot getTraceSnapshot() {
        if (traceState == null) {
            return new TransferTraceSnapshot(TransferTraceSnapshot.Status.DISABLED,
                    Collections.<TransferTraceEvent>emptyList(), 0L, lastAdvanceTime);
        }
        return new TransferTraceSnapshot(traceState.droppedCount == 0L
                ? TransferTraceSnapshot.Status.COMPLETE : TransferTraceSnapshot.Status.TRUNCATED,
                traceState.events, traceState.droppedCount, lastAdvanceTime);
    }

    /**
     * Read a live admission's actual ordinal without advancing, copying history or emitting records.
     * Available after a successful admission even when its START was beyond the retained prefix.
     * @param transferId caller's currently active transfer ID
     * @return actual positive engine ordinal, not the caller ID
     * @throws IllegalStateException if capture is disabled or the transfer is no longer active
     */
    public long getActiveTransferAdmissionOrdinal(long transferId) {
        if (traceState == null) {
            throw new IllegalStateException("Admission ordinals require enabled transfer trace capture");
        }
        Transfer transfer = activeTransfers.get(transferId);
        if (transfer == null) {
            throw new IllegalStateException("No active transfer admission for id: " + transferId);
        }
        return transfer.traceAdmissionOrdinal;
    }

    /**
     * 注册一个端点的容量。
     *
     * @param endpoint 端点键（如 {@code "VM:3"}）
     * @param capacityBytesPerSecond 容量，字节/秒，必须为正有限值
     */
    public void setEndpointCapacity(String endpoint, double capacityBytesPerSecond) {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("Endpoint key cannot be null or empty");
        }
        if (!(capacityBytesPerSecond > 0.0) || Double.isInfinite(capacityBytesPerSecond)) {
            throw new IllegalArgumentException("Endpoint capacity must be positive and finite: "
                    + capacityBytesPerSecond);
        }
        if (!activeTransfers.isEmpty()) {
            throw new IllegalStateException("Cannot change capacity while transfers are active");
        }
        if(coherentChecked&&capacityBytesPerSecond<Double.MIN_NORMAL)throw unsupported("Subnormal physical capacity");
        endpointCapacitiesBytesPerSecond.put(endpoint, capacityBytesPerSecond);
        if (traceState != null) {
            traceState.capacity(endpoint, capacityBytesPerSecond, lastAdvanceTime);
        }
    }

    /**
     * 登记一个新的传输并积分推进既有活动传输到 {@code now}。
     *
     * @param transferId 调用方保证唯一且不复用的传输 ID
     * @param bytes 传输字节量，必须为正
     * @param sourceEndpoint 源端点键（未注册容量视为无限）
     * @param destinationEndpoint 目标端点键
     * @param nominalRateBytesPerSecond 无争用名义速率，字节/秒，必须为正有限值
     * @param now 当前仿真时间；不得早于上次推进时间
     * @return 登记后的结算结果（新传输此刻尚未完成，除非积分恰好清空既有传输）
     */
    public AdvanceResult addTransfer(long transferId, long bytes, String sourceEndpoint,
            String destinationEndpoint, double nominalRateBytesPerSecond, double now) {
        return addTransfer(transferId, (double) bytes, sourceEndpoint, destinationEndpoint,
                nominalRateBytesPerSecond, now);
    }

    /**
     * Register a finite positive, possibly fractional byte demand on two endpoints.
     *
     * @param transferId unique transfer identifier
     * @param bytes finite positive modeled bytes; no integer rounding is applied
     * @param sourceEndpoint source resource key
     * @param destinationEndpoint destination resource key
     * @param nominalRateBytesPerSecond finite positive nominal rate
     * @param now current simulation time
     * @return completed earlier transfers and the next predicted completion
     */
    public AdvanceResult addTransfer(long transferId, double bytes, String sourceEndpoint,
            String destinationEndpoint, double nominalRateBytesPerSecond, double now) {
        if (sourceEndpoint == null || destinationEndpoint == null) {
            throw new IllegalArgumentException("Transfer endpoints cannot be null");
        }
        return addTransfer(transferId, bytes,
                java.util.Arrays.asList(sourceEndpoint, destinationEndpoint),
                nominalRateBytesPerSecond, now);
    }

    /**
     * 登记一个占用任意资源集（端点 + 链路键）的传输并积分推进既有活动传输到
     * {@code now}。Fat-tree 拓扑感知争用模型使用本重载（资源集 = 两端点 +
     * 确定性路由的全部链路键，见 {@code FatTreeTopology#route}）。
     *
     * @param transferId 调用方保证唯一且不复用的传输 ID
     * @param bytes 传输字节量，必须为正
     * @param occupiedResources 占用资源键列表（非 null，可为空 = 无争用约束；
     *        重复键按出现次数重复计数）
     * @param nominalRateBytesPerSecond 无争用名义速率，字节/秒，必须为正有限值
     * @param now 当前仿真时间；不得早于上次推进时间
     * @return 登记后的结算结果
     */
    public AdvanceResult addTransfer(long transferId, long bytes, List<String> occupiedResources,
            double nominalRateBytesPerSecond, double now) {
        return addTransfer(transferId, (double) bytes, occupiedResources, nominalRateBytesPerSecond, now);
    }

    /**
     * Register a finite positive, possibly fractional byte demand on a resource set.
     *
     * @param transferId unique transfer identifier
     * @param bytes finite positive modeled bytes
     * @param occupiedResources resource keys; an empty list has no shared capacity constraint
     * @param nominalRateBytesPerSecond finite positive nominal rate
     * @param now current simulation time
     * @return completed earlier transfers and the next predicted completion
     */
    public AdvanceResult addTransfer(long transferId, double bytes, List<String> occupiedResources,
            double nominalRateBytesPerSecond, double now) {
        if(coherentChecked){TransferContentionEngine staged=fork();AdvanceResult result=staged.addInternal(transferId,bytes,occupiedResources,nominalRateBytesPerSecond,now);commitChecked(staged);return result;}
        return addInternal(transferId,bytes,occupiedResources,nominalRateBytesPerSecond,now);
    }

    private AdvanceResult addInternal(long transferId,double bytes,List<String> occupiedResources,double nominalRateBytesPerSecond,double now){
        if (activeTransfers.containsKey(transferId)) {
            throw new IllegalArgumentException("Duplicate transfer id: " + transferId);
        }
        if (!(bytes > 0.0) || !Double.isFinite(bytes)) {
            throw new IllegalArgumentException("Transfer bytes must be positive and finite: " + bytes);
        }
        if (!(nominalRateBytesPerSecond > 0.0) || Double.isInfinite(nominalRateBytesPerSecond)) {
            throw new IllegalArgumentException("Nominal rate must be positive and finite: "
                    + nominalRateBytesPerSecond);
        }
        double nominalDuration = bytes / nominalRateBytesPerSecond;
        if (!(nominalDuration > 0.0) || !Double.isFinite(nominalDuration)) {
            throw new IllegalArgumentException("Positive transfer duration is not representable: " + nominalDuration);
        }
        if (occupiedResources == null) {
            throw new IllegalArgumentException("Occupied resources cannot be null");
        }
        for (String resource : occupiedResources) {
            if (resource == null) {
                throw new IllegalArgumentException("Occupied resource keys cannot contain null");
            }
        }
        if(coherentChecked){
            if(nominalRateBytesPerSecond<Double.MIN_NORMAL||occupiedResources.isEmpty())throw unsupported("Positive service requires a normal nominal rate and physical path");
            for(String resource:occupiedResources)if(!endpointCapacitiesBytesPerSecond.containsKey(resource))throw unsupported("Unknown physical resource: "+resource);
            double finish=now+nominalDuration;if(!Double.isFinite(finish)||finish<=now)throw unsupported("Unrepresentable absolute isolated finish");
        }
        AdvanceResult result = advanceInternal(now);
        activeTransfers.put(transferId, new Transfer(bytes,
                new ArrayList<String>(occupiedResources), nominalRateBytesPerSecond));
        if(coherentChecked)activeTransfers.get(transferId).coherentAdmissionTime=now;
        if (traceState != null) {
            activeTransfers.get(transferId).traceAdmissionOrdinal = traceState.nextAdmissionOrdinal++;
            captureCurrentRates();
        }
        recomputeRates();
        if (traceState != null) {
            Transfer admitted = activeTransfers.get(transferId);
            traceState.start(transferId, admitted, now);
            recordRateChanges(now, admitted);
        }
        return new AdvanceResult(result.getCompletedTransferIds(), earliestCompletion(now),result.getCompletionObservations());
    }

    /**
     * 积分推进全部活动传输到 {@code now}，结算完成的传输并重算剩余传输的速率。
     *
     * @param now 当前仿真时间；不得早于上次推进时间
     * @return 完成的传输 ID 列表与剩余传输的最早预测完成时刻
     */
    public AdvanceResult advance(double now) {
        if(coherentChecked){TransferContentionEngine staged=fork();AdvanceResult result=staged.advanceInternal(now);commitChecked(staged);return result;}
        return advanceInternal(now);
    }

    private AdvanceResult advanceInternal(double now){
        if (!Double.isFinite(now) || now < lastAdvanceTime) {
            throw new IllegalArgumentException("Time must be finite and not move backwards: " + now
                    + " < " + lastAdvanceTime);
        }
        List<Long> completed = new ArrayList<Long>();
        List<CompletionObservation> observations=coherentChecked?new ArrayList<CompletionObservation>():Collections.<CompletionObservation>emptyList();
        if(coherentChecked)checkCoherentState();
        while (!activeTransfers.isEmpty() && lastAdvanceTime < now) {
            double nextDuration = Double.POSITIVE_INFINITY;
            Transfer earliest = null;
            for (Transfer transfer : activeTransfers.values()) {
                double duration = transfer.remainingBytes / transfer.rateBytesPerSecond;
                if (duration < nextDuration) {
                    nextDuration = duration;
                    earliest = transfer;
                }
            }
            double elapsed = Math.min(now - lastAdvanceTime, nextDuration);
            if(coherentChecked&&(!(elapsed>0)||!Double.isFinite(elapsed)||lastAdvanceTime+elapsed<=lastAdvanceTime))throw unsupported("Service step cannot advance the finite binary64 clock");
            if (traceState != null) { traceState.intervalStart = lastAdvanceTime; }
            for (Transfer transfer : activeTransfers.values()) {
                if (traceState != null) { transfer.traceRemainingBefore = transfer.remainingBytes; }
                if(coherentChecked&&!Double.isFinite(transfer.rateBytesPerSecond*elapsed))throw unsupported("Service rate-area multiplication overflow");
                transfer.remainingBytes = Math.max(0.0,
                        transfer.remainingBytes - transfer.rateBytesPerSecond * elapsed);
                if (traceState != null||coherentChecked) { transfer.traceRemainingAfterService = transfer.remainingBytes; }
            }
            if (earliest != null && elapsed >= nextDuration) {
                // Round-off in subtraction must not leave the actual earliest flow alive.
                earliest.remainingBytes = 0.0;
            }
            lastAdvanceTime = Math.min(now, lastAdvanceTime + elapsed);
            if (traceState != null && elapsed > 0.0) {
                // All services precede completions; retain elapsed even if the two clocks round equal.
                for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
                    traceState.service(entry.getKey(), entry.getValue(), lastAdvanceTime, now, elapsed);
                }
            }
            List<Long> settled = new ArrayList<Long>();
            for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
                if (entry.getValue().completed()) { settled.add(entry.getKey()); }
            }
            if (traceState != null) {
                for (Long id : settled) {
                    traceState.complete(id, activeTransfers.get(id), lastAdvanceTime, now);
                }
            }
            if(coherentChecked)for(Long id:settled){Transfer transfer=activeTransfers.get(id);checkCoherentCompletion(transfer,lastAdvanceTime);observations.add(new CompletionObservation(id,lastAdvanceTime,now,transfer.traceRemainingAfterService));}
            for (Long id : settled) { activeTransfers.remove(id); }
            completed.addAll(settled);
            if (!settled.isEmpty()) {
                if (traceState != null) { captureCurrentRates(); }
                recomputeRates();
                if (traceState != null) { recordRateChanges(now, null); }
            }
            if (elapsed == 0.0 && settled.isEmpty()) {
                throw new IllegalStateException("Transfer integration made no progress");
            }
        }
        lastAdvanceTime = now;
        if(coherentChecked)checkCoherentState();
        return new AdvanceResult(completed, earliestCompletion(now),observations);
    }

    /** 当前活动传输数量。 */
    public int activeTransferCount() {
        return activeTransfers.size();
    }

    /**
     * 返回指定活动传输的当前有效速率（字节/秒），用于测试与证据记录。
     *
     * @param transferId 活动传输 ID
     * @return 当前有效速率
     */
    public double currentRateBytesPerSecond(long transferId) {
        Transfer transfer = activeTransfers.get(transferId);
        if (transfer == null) {
            throw new IllegalArgumentException("Unknown active transfer: " + transferId);
        }
        return transfer.rateBytesPerSecond;
    }

    /** Enabled-only: remember final rates before the untouched solver runs. */
    private void captureCurrentRates() {
        for (Transfer transfer : activeTransfers.values()) {
            transfer.tracePreviousRate = transfer.rateBytesPerSecond;
        }
    }

    /** Called only after successful final-rate validation; START covers the new admission. */
    private void recordRateChanges(double observedTime, Transfer admitted) {
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            Transfer transfer = entry.getValue();
            if (transfer != admitted && transfer.tracePreviousRate != transfer.rateBytesPerSecond) {
                traceState.rateChange(entry.getKey(), transfer, lastAdvanceTime, observedTime);
            }
        }
    }

    /** 残余容量和未冻结流占用数；同一资源的重复键按出现次数计费。 */
    private static final class FillResource {
        private double remainingCapacity;
        private long growingOccupancy;
        private double incrementLimit;
        private FillResource(double capacity) { remainingCapacity = capacity; }
    }

    /** Progressive filling：回收已受其他瓶颈限制的流所留下的容量。 */
    private void recomputeRates() {
        if(coherentChecked&&!shareResources){
            for(Transfer transfer:activeTransfers.values()){
                double rate=transfer.nominalRateBytesPerSecond;Map<String,Integer> weights=new LinkedHashMap<String,Integer>();
                for(String key:transfer.occupiedResources){Integer count=weights.get(key);weights.put(key,count==null?1:count+1);}
                for(Map.Entry<String,Integer> entry:weights.entrySet())rate=Math.min(rate,endpointCapacitiesBytesPerSecond.get(entry.getKey())/entry.getValue());
                transfer.rateBytesPerSecond=rate;
            }
            checkCoherentState();return;
        }
        List<Transfer> growing = new ArrayList<Transfer>(activeTransfers.values());
        Map<String, FillResource> resources = new LinkedHashMap<String, FillResource>();
        for (Transfer transfer : growing) {
            transfer.rateBytesPerSecond = 0.0;
            for (String key : transfer.occupiedResources) {
                Double capacity = endpointCapacitiesBytesPerSecond.get(key);
                if (capacity != null && !resources.containsKey(key)) {
                    resources.put(key, new FillResource(capacity));
                }
            }
        }
        while (!growing.isEmpty()) {
            for (FillResource resource : resources.values()) {
                resource.growingOccupancy = 0L;
            }
            double delta = Double.POSITIVE_INFINITY;
            for (Transfer transfer : growing) {
                delta = Math.min(delta, transfer.nominalRateBytesPerSecond
                        - transfer.rateBytesPerSecond);
                for (String key : transfer.occupiedResources) {
                    FillResource resource = resources.get(key);
                    if (resource != null) { resource.growingOccupancy++; }
                }
            }
            for (FillResource resource : resources.values()) {
                resource.incrementLimit = resource.growingOccupancy == 0L
                        ? Double.POSITIVE_INFINITY
                        : resource.remainingCapacity / resource.growingOccupancy;
                delta = Math.min(delta, resource.incrementLimit);
            }
            List<Transfer> survivors = new ArrayList<Transfer>();
            for (Transfer transfer : growing) {
                boolean frozen = transfer.nominalRateBytesPerSecond
                        - transfer.rateBytesPerSecond <= delta;
                for (String key : transfer.occupiedResources) {
                    FillResource resource = resources.get(key);
                    if (resource != null && resource.incrementLimit <= delta) {
                        frozen = true;
                    }
                }
                transfer.rateBytesPerSecond = Math.min(transfer.nominalRateBytesPerSecond,
                        transfer.rateBytesPerSecond + delta);
                if (!frozen) { survivors.add(transfer); }
            }
            for (FillResource resource : resources.values()) {
                resource.remainingCapacity = Math.max(0.0, resource.remainingCapacity
                        - delta * resource.growingOccupancy);
            }
            if (survivors.size() == growing.size()) {
                throw new IllegalStateException("Max-min solver made no progress");
            }
            growing = survivors;
        }
        for (Transfer transfer : activeTransfers.values()) {
            if (!(transfer.rateBytesPerSecond > 0.0) || !Double.isFinite(transfer.rateBytesPerSecond)) {
                throw new IllegalStateException("Effective transfer rate must remain positive and finite");
            }
        }
        if(coherentChecked)checkCoherentState();
    }

    /** Checked V2 invariants use active state, never the optional history buffer. */
    private void checkCoherentState(){
        Map<String,BigDecimal> totals=new LinkedHashMap<String,BigDecimal>();Map<String,Long> terms=new LinkedHashMap<String,Long>();
        for(Transfer transfer:activeTransfers.values()){
            double rate=transfer.rateBytesPerSecond,remaining=transfer.remainingBytes;
            if(!Double.isFinite(rate)||rate<Double.MIN_NORMAL||rate>transfer.nominalRateBytesPerSecond||!Double.isFinite(remaining)||remaining<=0||remaining>transfer.bytes)throw unsupported("Invalid active flow balance or normal allocation");
            double duration=remaining/rate,finish=lastAdvanceTime+duration;
            if(!Double.isFinite(duration)||duration<=0||!Double.isFinite(finish)||finish<=lastAdvanceTime)throw unsupported("Active completion prediction is not representable");
            Map<String,BigDecimal> single=new LinkedHashMap<String,BigDecimal>();Map<String,Long> singleTerms=new LinkedHashMap<String,Long>();
            for(String key:transfer.occupiedResources){
                Double cap=endpointCapacitiesBytesPerSecond.get(key);if(cap==null||!Double.isFinite(cap)||cap<Double.MIN_NORMAL)throw unsupported("Unregistered or unsupported physical capacity");
                addRate(single,singleTerms,key,rate);if(shareResources)addRate(totals,terms,key,rate);
            }
            for(String key:single.keySet())checkCapacity(single.get(key),endpointCapacitiesBytesPerSecond.get(key),singleTerms.get(key));
        }
        if(shareResources)for(String key:totals.keySet())checkCapacity(totals.get(key),endpointCapacitiesBytesPerSecond.get(key),terms.get(key));
    }
    private static void checkCoherentCompletion(Transfer transfer,double effective){
        double residual=transfer.traceRemainingAfterService,tolerance=Math.min(transfer.bytes*.5,Math.max(transfer.bytes*1e-9,4*Math.ulp(transfer.bytes)));
        if(!Double.isFinite(residual)||residual<0||residual>tolerance||effective<=transfer.coherentAdmissionTime)throw unsupported("Invalid numerical completion settlement");
        double lower=transfer.coherentAdmissionTime+(transfer.bytes-residual)/transfer.nominalRateBytesPerSecond;
        double allowed=Math.min(8*Math.max(Math.ulp(transfer.coherentAdmissionTime),Math.max(Math.ulp(effective),Math.ulp(lower))),1e-12*Math.max(transfer.coherentAdmissionTime,Math.max(effective,lower)));
        if(!Double.isFinite(lower)||(lower>effective&&lower-effective>allowed))throw unsupported("Completion violates isolated-rate scalar lower bound");
    }
    private static void addRate(Map<String,BigDecimal> totals,Map<String,Long> terms,String key,double rate){
        BigDecimal previous=totals.get(key);totals.put(key,(previous==null?BigDecimal.ZERO:previous).add(new BigDecimal(rate)));
        Long count=terms.get(key);terms.put(key,count==null?1:Math.addExact(count,1));
    }
    private static void checkCapacity(BigDecimal sum,double capacity,long count){
        BigDecimal cap=new BigDecimal(capacity);BigDecimal tolerance=new BigDecimal(Math.ulp(capacity)).multiply(BigDecimal.valueOf(8)).multiply(BigDecimal.valueOf(count+1)).min(cap.multiply(new BigDecimal("1e-12")));
        if(sum.compareTo(cap.add(tolerance))>0)throw unsupported("Allocation exceeds the declared scalar capacity profile");
    }
    private static IllegalArgumentException unsupported(String message){return new IllegalArgumentException("Unsupported coherent V2 service state: "+message);}

    /** 剩余活动传输的最早预测完成时刻；无活动传输返回 null。 */
    private Double earliestCompletion(double now) {
        Double earliest = null;
        for (Transfer transfer : activeTransfers.values()) {
            double predicted = now + transfer.remainingBytes / transfer.rateBytesPerSecond;
            if (earliest == null || predicted < earliest) {
                earliest = predicted;
            }
        }
        return earliest;
    }
}
