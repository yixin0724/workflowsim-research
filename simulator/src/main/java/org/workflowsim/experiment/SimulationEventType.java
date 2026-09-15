package org.workflowsim.experiment;

/**
 * 一次 {@link SimulationRunner} 运行产生事件的稳定词表。
 *
 * <p>事件流描述模拟器中的建模生命周期，不表示生产平台发生过等价事件。</p>
 *
 * <p><strong>PLAT-19：SCHEDULING_DECISION 与 JOB_DISPATCHED 的分工。</strong>
 * 两者在同一模拟时刻为同一作业成对记录，都携带 {@code queueDelaySeconds}，
 * 但承担不同职责，有意不合并：</p>
 * <ul>
 * <li>{@code SCHEDULING_DECISION} —— 算法决策证据，被 {@link SimulationMetrics}
 * 消费用于计算就绪→决策、决策→开始两段延迟；重复记录属于事件流破坏（fail-fast）。</li>
 * <li>{@code JOB_DISPATCHED} —— 派发审计条目，证明调度结果已进入事件队列
 * （含派发延迟），当前仅供 events.jsonl 证据链与外部分析使用。</li>
 * </ul>
 */
public enum SimulationEventType {
    WORKFLOW_PARSED,
    /** R5 动态到达：某个工作流输入在其配置提交时刻变为可调度（根作业解除到达门控）。 */
    WORKFLOW_ARRIVED,
    PLANNING_COMPLETED,
    JOBS_CLUSTERED,
    STAGE_IN_JOB_CREATED,
    JOB_READY,
    SCHEDULING_CYCLE,
    /** 算法为作业选定 VM 的决策点；SimulationMetrics 消费，重复即事件流破坏。 */
    SCHEDULING_DECISION,
    /** 作业进入派发事件队列的审计点；仅证据链用途，与 SCHEDULING_DECISION 同时刻成对出现。 */
    JOB_DISPATCHED,
    DATA_STAGE_IN_MODELED,
    TASK_EXECUTION_MODELED,
    JOB_RETURNED,
    JOB_FAILED,
    RETRY_JOB_CREATED
}
