package org.workflowsim.planning;

/**
 * 与 WorkflowSim 受控共享存储执行模型对齐的 Predict Earliest Finish Time（PEFT）规划器。
 *
 * <p>先以兼容 VM 平均 optimistic cost table（OCT）最大的 ready Task 为优先级，再选择
 * 插入 {@code EFT + OCT} 最小的 VM。Task 优先级和 VM 目标相同时均选较小 ID。由于本模型
 * 没有链路模型，原始 PEFT 的跨处理器通信项在此为零。</p>
 *
 * <p>该实现保留后继任务乐观前瞻，不表示网络感知或真实平台校准的 PEFT。</p>
 */
public final class SharedStoragePeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStoragePeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_PEFT requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context,
                SharedStorageDagPlanner.Strategy.PEFT);
    }

    /** 返回最近一次成功规划的不可变决策轨迹。 */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_PEFT has not completed a plan");
        }
        return lastPlanTrace;
    }
}
