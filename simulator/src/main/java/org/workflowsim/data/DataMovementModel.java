package org.workflowsim.data;

/**
 * 一次仿真运行使用的不可变文件传输时延契约。
 *
 * <p>默认值严格保留 WorkflowSim 的历史传输计算。固定端点选项刻意采用
 * Job 内串行、Job 间无争用的抽象模型；它不表示分组网络、排队、共享链路
 * 竞争，也不表示经过现实数据校准的存储系统。</p>
 */
public final class DataMovementModel {

    public enum Kind {
        /** 历史共享/本地文件传输计算。 */
        LEGACY_WORKFLOWSIM_V1,
        /** 固定端点链路、逐文件附加时延且不存在共享争用。 */
        FIXED_ENDPOINT_NO_CONTENTION_V1,
        /**
         * 论文语义的执行前传输延迟模型（Topcuoglu TPDS 2002 类列表调度的
         * 通信模型）：计算 Job 的输入传输在其数据就绪（全部父任务完成）时开始，
         * 可与目标 VM 的忙碌期重叠；VM 只被计算 MI 占用，传输不再折算进执行
         * 信封。传输秒数沿用 {@link #LEGACY_WORKFLOWSIM_V1} 的带宽规则
         * （SOURCE→VM 取目标 VM 带宽、VM→VM 取 {@code min(bw)}、副本本地
         * 零传输）。
         */
        PRE_EXECUTION_TRANSFER_DELAY_V1,
        /**
         * 链路争用版执行前传输延迟模型：传输窗口语义与
         * {@link #PRE_EXECUTION_TRANSFER_DELAY_V1} 相同（数据就绪时开始、可与目标
         * VM 忙碌期重叠），但同一 VM 端点上同时活动的传输公平共享该端点的带宽
         * 容量（流体公平共享模型），并发传输互相减速。传输组在所属 Job 数据就绪
         * 时刻统一开始（不追溯父任务更早完成时点的部分传输进度），因此本模型的
         * 完成时刻不早于无争用模型的对应值。规划器侧 AST 仍按无争用速率估计，
         * 规划与执行在并发负载下预期出现可解释的偏差。
         */
        PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,
        /**
         * Fat-tree 拓扑感知链路争用版执行前传输延迟模型：传输窗口语义与
         * {@link #PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1} 相同，但争用
         * 域从 VM 端点推广为确定性路由路径上的每条共享链路（Al-Fares k-Pod
         * Fat-tree，流级 max-min 公平共享）；端点与链路容量同时生效、速率取
         * 全部占用资源份额的最小值。要求平台通过
         * {@code PlatformProfile.networkTopology(...)} 声明拓扑（
         * {@code org.workflowsim.network.NetworkTopologySpec}）。外部输入
         * （SOURCE）流量 v1 不经过拓扑，只占用目标 VM 端点。原理与设计见
         * {@code docs/research/FAT_TREE_PRINCIPLES.md} 与
         * {@code docs/research/FAT_TREE_DESIGN.md}。
         */
        PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1
    }

    private static final DataMovementModel LEGACY = new DataMovementModel(
            Kind.LEGACY_WORKFLOWSIM_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY = new DataMovementModel(
            Kind.PRE_EXECUTION_TRANSFER_DELAY_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION =
            new DataMovementModel(
                    Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1, 0.0, 0.0, 0.0);
    private static final DataMovementModel FAT_TREE_CONTENTION = new DataMovementModel(
            Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1, 0.0, 0.0, 0.0);

    private final Kind kind;
    private final double accessLinkBandwidthMbPerSecond;
    private final double accessLinkLatencySeconds;
    private final double sourceEndpointBandwidthMbPerSecond;

    private DataMovementModel(Kind kind, double accessLinkBandwidthMbPerSecond,
            double accessLinkLatencySeconds, double sourceEndpointBandwidthMbPerSecond) {
        this.kind = kind;
        this.accessLinkBandwidthMbPerSecond = accessLinkBandwidthMbPerSecond;
        this.accessLinkLatencySeconds = accessLinkLatencySeconds;
        this.sourceEndpointBandwidthMbPerSecond = sourceEndpointBandwidthMbPerSecond;
    }

    /**
     * 返回 P10 以前仿真行为所使用的严格兼容默认模型。
     *
     * @return 历史 WorkflowSim 传输模型的共享不可变实例
     */
    public static DataMovementModel legacyWorkflowsimV1() {
        return LEGACY;
    }

    /**
     * 返回论文语义的执行前传输延迟模型。
     *
     * <p>计算 Job 的输入传输在数据就绪时开始并可与 VM 忙碌期重叠；VM 仅被计算占用。
     * 这是 LOCAL_HEFT/LOCAL_CPOP 复现 Topcuoglu TPDS 2002 调度语义所需的通信模型。</p>
     *
     * @return 执行前传输延迟模型的共享不可变实例
     */
    public static DataMovementModel preExecutionTransferDelayV1() {
        return PRE_EXECUTION_TRANSFER_DELAY;
    }

    /**
     * 返回链路争用版执行前传输延迟模型。
     *
     * <p>传输窗口语义与 {@link #preExecutionTransferDelayV1()} 相同；差异在于同一
     * VM 端点上并发的传输公平共享该端点带宽，互相减速（流体公平共享模型）。
     * VM 端点容量取各自 {@code vm.getBw()}；SOURCE 端点不设容量上限。</p>
     *
     * @return 链路争用模型的共享不可变实例
     */
    public static DataMovementModel preExecutionTransferDelayWithContentionV1() {
        return PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION;
    }

    /**
     * 返回 Fat-tree 拓扑感知链路争用版执行前传输延迟模型。
     *
     * <p>传输窗口语义与 {@link #preExecutionTransferDelayWithContentionV1()}
     * 相同；差异在于争用域推广为 Fat-tree 确定性路由路径上的每条共享链路 +
     * VM 端点（流级 max-min 公平共享）。平台必须通过
     * {@code PlatformProfile.Builder.networkTopology(...)} 声明拓扑。</p>
     *
     * @return Fat-tree 链路争用模型的共享不可变实例
     */
    public static DataMovementModel fatTreeContentionV1() {
        return FAT_TREE_CONTENTION;
    }

    /**
     * 创建不含拓扑的固定端点模型。
     *
     * <p>每个非本地文件传输都在所属 Job 内串行处理，其耗时为固定时延加上
     * 文件大小除以瓶颈速率；不同 Job 的同时传输不会竞争容量。</p>
     *
     * @param accessLinkBandwidthMbPerSecond 目标端点接入链路的每次传输速率上限，单位 MB/s
     * @param accessLinkLatencySeconds 每个非本地文件传输附加的固定时延，单位秒
     * @param sourceEndpointBandwidthMbPerSecond 外部源端点的传输速率上限，单位 MB/s
     * @return 具有给定抽象端点参数的不可变数据移动模型
     * @throws IllegalArgumentException 当带宽非正/非有限，或时延为负/非有限时抛出
     */
    public static DataMovementModel fixedEndpointNoContention(
            double accessLinkBandwidthMbPerSecond, double accessLinkLatencySeconds,
            double sourceEndpointBandwidthMbPerSecond) {
        if (!positiveFinite(accessLinkBandwidthMbPerSecond)
                || !nonNegativeFinite(accessLinkLatencySeconds)
                || !positiveFinite(sourceEndpointBandwidthMbPerSecond)) {
            throw new IllegalArgumentException("Fixed endpoint data movement requires positive finite access and "
                    + "source bandwidths and a finite non-negative latency");
        }
        return new DataMovementModel(Kind.FIXED_ENDPOINT_NO_CONTENTION_V1,
                accessLinkBandwidthMbPerSecond, accessLinkLatencySeconds,
                sourceEndpointBandwidthMbPerSecond);
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static boolean nonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    public Kind getKind() { return kind; }
    public double getAccessLinkBandwidthMbPerSecond() { return accessLinkBandwidthMbPerSecond; }
    public double getAccessLinkLatencySeconds() { return accessLinkLatencySeconds; }
    public double getSourceEndpointBandwidthMbPerSecond() { return sourceEndpointBandwidthMbPerSecond; }
    public boolean isLegacyWorkflowsimV1() { return kind == Kind.LEGACY_WORKFLOWSIM_V1; }
    public boolean isPreExecutionTransferDelayV1() { return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_V1; }
    public boolean isPreExecutionTransferDelayWithContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;
    }
    public boolean isFatTreeContentionV1() {
        return kind == Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1;
    }
}
