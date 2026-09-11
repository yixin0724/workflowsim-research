package org.workflowsim.planning;

/**
 * 与 WorkflowSim 受控共享存储执行模型对齐的 CPOP 静态 DAG 规划器。
 *
 * <p>以 {@code r_u + r_d} 作为优先级，确定一条关键路径，并将该路径强制映射到总模型时长
 * 最小的兼容 VM。相同优先级、路径分支和处理器代价均按较小 ID 打破平局；非关键 Task
 * 使用与 HEFT 相同的保留区插入分配。</p>
 *
 * <p>仅描述受控共享存储模型，不代表网络拓扑、共享链路争用或真实处理器绑定。</p>
 */
public final class SharedStorageCpopPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageCpopPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_CPOP requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context,
                SharedStorageDagPlanner.Strategy.CPOP);
    }

    /** 返回最近一次成功规划的不可变决策轨迹。 */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_CPOP has not completed a plan");
        }
        return lastPlanTrace;
    }
}
