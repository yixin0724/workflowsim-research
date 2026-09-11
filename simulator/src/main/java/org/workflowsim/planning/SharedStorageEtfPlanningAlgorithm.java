package org.workflowsim.planning;

/**
 * 与 WorkflowSim 受控共享存储执行模型对齐的 Earliest Task First（ETF）静态 DAG 规划器。
 *
 * <p>每轮从依赖已满足的 Task-VM 对中选择保留区最早开始时间最小者；相同开始时间先选静态
 * b-level 较高的 Task，再按较小 Task ID 与 VM ID 打破平局。决策轨迹记录该开始时间和
 * 选择顺序。</p>
 *
 * <p>该实现没有网络拓扑、链路争用或通信路由建模，不能作为网络感知 ETF 的实验结论。</p>
 */
public final class SharedStorageEtfPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageEtfPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_ETF requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context,
                SharedStorageDagPlanner.Strategy.ETF);
    }

    /** 返回最近一次成功规划的不可变决策轨迹。 */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_ETF has not completed a plan");
        }
        return lastPlanTrace;
    }
}
