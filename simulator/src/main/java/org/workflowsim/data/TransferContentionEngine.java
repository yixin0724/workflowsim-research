package org.workflowsim.data;

import java.util.ArrayList;
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

    /** 一次 {@link #advance} 调用的结算结果。 */
    public static final class AdvanceResult {
        private final List<Long> completedTransferIds;
        private final Double nextCompletionTime;

        private AdvanceResult(List<Long> completedTransferIds, Double nextCompletionTime) {
            this.completedTransferIds = completedTransferIds;
            this.nextCompletionTime = nextCompletionTime;
        }

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
        private final long bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private double remainingBytes;
        private double rateBytesPerSecond;

        private Transfer(long bytes, List<String> occupiedResources,
                double nominalRateBytesPerSecond) {
            this.bytes = bytes;
            this.occupiedResources = occupiedResources;
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.remainingBytes = bytes;
            this.rateBytesPerSecond = nominalRateBytesPerSecond;
        }

        private boolean completed() {
            return remainingBytes <= Math.max(1.0e-9, 1.0e-9 * bytes);
        }
    }

    /** 端点容量表：未注册的端点视为容量无限。 */
    private final Map<String, Double> endpointCapacitiesBytesPerSecond = new LinkedHashMap<String, Double>();
    /** 活动传输表，按插入顺序迭代保证确定性。 */
    private final Map<Long, Transfer> activeTransfers = new LinkedHashMap<Long, Transfer>();
    private double lastAdvanceTime = 0.0;

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
        endpointCapacitiesBytesPerSecond.put(endpoint, capacityBytesPerSecond);
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
        if (activeTransfers.containsKey(transferId)) {
            throw new IllegalArgumentException("Duplicate transfer id: " + transferId);
        }
        if (bytes <= 0L) {
            throw new IllegalArgumentException("Transfer bytes must be positive: " + bytes);
        }
        if (!(nominalRateBytesPerSecond > 0.0) || Double.isInfinite(nominalRateBytesPerSecond)) {
            throw new IllegalArgumentException("Nominal rate must be positive and finite: "
                    + nominalRateBytesPerSecond);
        }
        if (occupiedResources == null) {
            throw new IllegalArgumentException("Occupied resources cannot be null");
        }
        for (String resource : occupiedResources) {
            if (resource == null) {
                throw new IllegalArgumentException("Occupied resource keys cannot contain null");
            }
        }
        AdvanceResult result = advance(now);
        activeTransfers.put(transferId, new Transfer(bytes,
                new ArrayList<String>(occupiedResources), nominalRateBytesPerSecond));
        recomputeRates();
        return new AdvanceResult(result.getCompletedTransferIds(), earliestCompletion(now));
    }

    /**
     * 积分推进全部活动传输到 {@code now}，结算完成的传输并重算剩余传输的速率。
     *
     * @param now 当前仿真时间；不得早于上次推进时间
     * @return 完成的传输 ID 列表与剩余传输的最早预测完成时刻
     */
    public AdvanceResult advance(double now) {
        if (!Double.isFinite(now) || now < lastAdvanceTime) {
            throw new IllegalArgumentException("Time must be finite and not move backwards: " + now
                    + " < " + lastAdvanceTime);
        }
        List<Long> completed = new ArrayList<Long>();
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
            for (Transfer transfer : activeTransfers.values()) {
                transfer.remainingBytes = Math.max(0.0,
                        transfer.remainingBytes - transfer.rateBytesPerSecond * elapsed);
            }
            if (earliest != null && elapsed >= nextDuration) {
                // Round-off in subtraction must not leave the actual earliest flow alive.
                earliest.remainingBytes = 0.0;
            }
            lastAdvanceTime = Math.min(now, lastAdvanceTime + elapsed);
            List<Long> settled = new ArrayList<Long>();
            for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
                if (entry.getValue().completed()) { settled.add(entry.getKey()); }
            }
            for (Long id : settled) { activeTransfers.remove(id); }
            completed.addAll(settled);
            if (!settled.isEmpty()) { recomputeRates(); }
            if (elapsed == 0.0 && settled.isEmpty()) {
                throw new IllegalStateException("Transfer integration made no progress");
            }
        }
        lastAdvanceTime = now;
        return new AdvanceResult(completed, earliestCompletion(now));
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

    /** 残余容量和未冻结流占用数；同一资源的重复键按出现次数计费。 */
    private static final class FillResource {
        private double remainingCapacity;
        private long growingOccupancy;
        private double incrementLimit;
        private FillResource(double capacity) { remainingCapacity = capacity; }
    }

    /** Progressive filling：回收已受其他瓶颈限制的流所留下的容量。 */
    private void recomputeRates() {
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
    }

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
