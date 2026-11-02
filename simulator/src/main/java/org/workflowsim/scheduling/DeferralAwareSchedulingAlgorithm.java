package org.workflowsim.scheduling;

/**
 * C1（2026-11-02）：可延后策略的调度面。
 *
 * <p>在线调度器通常"有就绪作业就派发"；碳感知延后引擎则会把作业<b>压到更干净的
 * 时间窗</b>再跑。压后必须显式：策略在本轮选择不派发任何作业时，必须给出一个
 * <b>未来的唤醒时刻</b>，由 {@link org.workflowsim.WorkflowScheduler} 排一个
 * {@code CLOUDLET_UPDATE} 事件把时钟推进过去。没有唤醒时刻又不派发 = 死锁，
 * 调度器会显式失败而不是静默挂起（守则：失败必须显式）。</p>
 */
public interface DeferralAwareSchedulingAlgorithm {

    /**
     * @return 下一次应当重新调度的绝对仿真时刻；{@link Double#POSITIVE_INFINITY}
     *     表示本轮没有延后、无需唤醒
     */
    double getNextWakeSeconds();

    /** @return 本轮被压后的就绪作业数（用于调度周期记录与证据） */
    int getDeferredJobCount();
}
