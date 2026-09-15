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
 * 活动传输之间公平分配容量，未注册容量的资源（如 SOURCE）不设上限。传输有效
 * 速率取 {@code min(名义速率, 各占用资源份额)}，字节余量按当前速率随事件
 * 时间线性积分。</p>
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

        /** 本次推进中完成（字节余量归零）的传输 ID，按插入顺序排列。 */
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
        if (now < lastAdvanceTime) {
            throw new IllegalArgumentException("Time moved backwards: " + now
                    + " < " + lastAdvanceTime);
        }
        double elapsed = now - lastAdvanceTime;
        if (elapsed > 0.0) {
            for (Transfer transfer : activeTransfers.values()) {
                transfer.remainingBytes -= transfer.rateBytesPerSecond * elapsed;
                if (transfer.remainingBytes < 0.0) {
                    transfer.remainingBytes = 0.0;
                }
            }
            lastAdvanceTime = now;
        }
        List<Long> completed = new ArrayList<Long>();
        for (Map.Entry<Long, Transfer> entry : activeTransfers.entrySet()) {
            if (entry.getValue().completed()) {
                completed.add(entry.getKey());
            }
        }
        for (Long id : completed) {
            activeTransfers.remove(id);
        }
        recomputeRates();
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

    /** 按当前资源占用重算每个活动传输的有效速率（公平共享，速率 = 各占用资源份额最小值）。 */
    private void recomputeRates() {
        // 每个资源键的活动传输计数（传输对其占用的每个资源各计一份）。
        Map<String, Integer> endpointLoad = new LinkedHashMap<String, Integer>();
        for (Transfer transfer : activeTransfers.values()) {
            for (String resource : transfer.occupiedResources) {
                countEndpoint(endpointLoad, resource);
            }
        }
        for (Transfer transfer : activeTransfers.values()) {
            double rate = transfer.nominalRateBytesPerSecond;
            for (String resource : transfer.occupiedResources) {
                rate = Math.min(rate, endpointShare(endpointLoad, resource));
            }
            transfer.rateBytesPerSecond = rate;
        }
    }

    private void countEndpoint(Map<String, Integer> endpointLoad, String endpoint) {
        if (!endpointCapacitiesBytesPerSecond.containsKey(endpoint)) {
            return;
        }
        Integer current = endpointLoad.get(endpoint);
        endpointLoad.put(endpoint, current == null ? 1 : current + 1);
    }

    private double endpointShare(Map<String, Integer> endpointLoad, String endpoint) {
        Double capacity = endpointCapacitiesBytesPerSecond.get(endpoint);
        if (capacity == null) {
            return Double.POSITIVE_INFINITY;
        }
        Integer load = endpointLoad.get(endpoint);
        return capacity / (double) (load == null ? 1 : load);
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
