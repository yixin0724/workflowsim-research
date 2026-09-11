package org.workflowsim.planning;

/**
 * 与 WorkflowSim 受控共享存储执行模型对齐的 DLS 静态 DAG 规划器。
 *
 * <p>每轮从依赖已满足的 Task-VM 对中选择动态层最大者：
 * {@code dynamicLevel = upwardRank - earliestInsertionStart}。同一动态层按较小 Task ID、
 * 再按较小 VM ID 打破平局；决策轨迹记录每轮的动态层和选择顺序。</p>
 *
 * <p>它不引入互连拓扑或链路保留，因此只是该受控模型下的 DLS 适配，而非通用网络感知 DLS。</p>
 */
public final class SharedStorageDlsPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageDlsPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_DLS requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context,
                SharedStorageDagPlanner.Strategy.DLS);
    }

    /** 返回最近一次成功规划的不可变决策轨迹。 */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_DLS has not completed a plan");
        }
        return lastPlanTrace;
    }
}
