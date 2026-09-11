package org.workflowsim.utils;

/**
 * WorkflowSim 模拟器的模型常量定义。
 *
 * <p>此类集中存放模拟器核心逻辑中使用的魔法数字，每个常量都附有明确的物理含义、
 * 单位说明和使用场景。将常量集中定义有助于：</p>
 * <ul>
 *   <li>提高代码可读性 - 常量名称说明其物理含义</li>
 *   <li>便于统一调整 - 修改模型参数时只需改一处</li>
 *   <li>增强可追溯性 - 科研人员能快速理解模型假设</li>
 * </ul>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 创建 stage-in Job
 * Job stageInJob = new Job(jobId, SimulationConstants.STAGE_IN_JOB_LENGTH_MI);
 *
 * // 检查是否在初始化阶段
 * if (CloudSim.clock() < SimulationConstants.INITIAL_PROCESSING_THRESHOLD_SECONDS) {
 *     // 初始化逻辑
 * }
 * }</pre>
 *
 * @since WorkflowSim 1.0
 */
public final class SimulationConstants {

    /**
     * stage-in Job 的固定长度，单位为 MI（Million Instructions，百万指令）。
     *
     * <p><strong>物理含义：</strong>此值代表一个虚拟的"数据传输任务"在 CloudSim
     * 计算模型中的指令数量。实际的传输时间由 {@link org.workflowsim.data.DataMovementModel}
     * 计算，但 CloudSim 要求每个 Cloudlet 必须有非零长度。</p>
     *
     * <p><strong>历史约束：</strong>CloudSim 要求 Cloudlet 长度至少为 110 MI，
     * 这是其内部数值精度和调度器兼容性要求。小于此值可能导致调度器行为异常。</p>
     *
     * <p><strong>使用场景：</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.ClusteringEngine} - 创建 stage-in Job</li>
     *   <li>{@link org.workflowsim.WorkflowDatacenter} - 验证 Job 类型</li>
     * </ul>
     *
     * <p><strong>注意：</strong>此常量不应用于计算实际传输时间，传输时间应使用
     * {@code DataMovementModel} 基于文件大小和带宽计算。</p>
     */
    public static final long STAGE_IN_JOB_LENGTH_MI = 110L;

    /**
     * 初始处理循环的时间阈值，单位为模拟秒。
     *
     * <p><strong>物理含义：</strong>在仿真开始后的前 0.111 秒内，数据中心处于
     * "初始化阶段"。在此阶段，某些处理逻辑（如资源更新）会被跳过，以避免与
     * CloudSim 内核的初始事件调度产生冲突。</p>
     *
     * <p><strong>历史背景：</strong>CloudSim 在时间 0.1 发送初始的 VM 创建和资源
     * 特征请求事件。0.111 的阈值确保这些初始事件已经处理完毕，数据中心才开始
     * 常规的周期性更新。</p>
     *
     * <p><strong>使用场景：</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.WorkflowDatacenter#processOtherEvent} -
     *       判断是否跳过初始阶段的处理循环</li>
     * </ul>
     *
     * <p><strong>模型限制：</strong>这是 CloudSim 内核兼容性要求，不代表真实系统的
     * 初始化时间。修改此值需要谨慎测试，确保不会破坏 CloudSim 的事件顺序。</p>
     */
    public static final double INITIAL_PROCESSING_THRESHOLD_SECONDS = 0.111;

    /**
     * 最小有意义的任务运行时间，单位为 MI。
     *
     * <p><strong>物理含义：</strong>任务的 runtime（以秒为单位）乘以 MIPS 和缩放系数后，
     * 如果小于此值，则会被向上取整到此最小值。这避免了数值精度问题和调度器对
     * 零长度任务的不确定行为。</p>
     *
     * <p><strong>使用场景：</strong></p>
     * <ul>
     *   <li>{@link org.workflowsim.WfCommonsJsonParser} - 转换 WfCommons JSON 的
     *       {@code runtimeInSeconds} 字段时的下界</li>
     *   <li>任务长度计算公式：{@code max(MIN_TASK_LENGTH_MI,
     *       floor(runtimeInSeconds * runtimeReferenceMips * runtimeScale))}</li>
     * </ul>
     *
     * <p><strong>注意：</strong>此最小值是模型假设，不代表真实任务的最短执行时间。
     * 它的主要目的是保持数值稳定性。</p>
     */
    public static final long MIN_TASK_LENGTH_MI = 100L;

    /**
     * 私有构造函数，防止实例化。
     */
    private SimulationConstants() {
        throw new AssertionError("SimulationConstants 是常量类，不应被实例化");
    }
}
