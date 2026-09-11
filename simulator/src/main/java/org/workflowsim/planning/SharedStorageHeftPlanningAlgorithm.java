package org.workflowsim.planning;

/**
 * 与 WorkflowSim 受控共享存储执行模型对齐的 HEFT 静态 DAG 规划器。
 *
 * <p>按向上 rank {@code r_u} 降序选择 Task，再以保留区插入的最早完成时间（EFT）选择
 * 兼容 VM。相同 rank 选较小 Task ID，相同 EFT 选较小 VM ID。rank 使用兼容 VM 的平均
 * 模型时长，其中包含本模型的共享存储 stage-in。</p>
 *
 * <p>仅适用于共享存储、无聚类、无故障、无建模开销及 {@code SPACE_SHARED} VM；它不是
 * 拓扑感知、链路争用感知或现实平台校准的 HEFT。</p>
 */
public final class SharedStorageHeftPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    private SharedStorageDagPlanTrace lastPlanTrace;

    public SharedStorageHeftPlanningAlgorithm(PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException("SHARED_STORAGE_HEFT requires a PlanningContext from SimulationRunner");
        }
        this.context = context;
    }

    @Override
    public void run() {
        lastPlanTrace = null;
        lastPlanTrace = SharedStorageDagPlanner.plan(getTaskList(), getVmList(), context,
                SharedStorageDagPlanner.Strategy.HEFT);
    }

    /** 返回最近一次成功规划的不可变决策轨迹。 */
    public SharedStorageDagPlanTrace getLastPlanTrace() {
        if (lastPlanTrace == null) {
            throw new IllegalStateException("SHARED_STORAGE_HEFT has not completed a plan");
        }
        return lastPlanTrace;
    }
}
