package org.workflowsim.planning;

import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 与受控执行模型一致的、仅由 {@code SimulationRunner} 提供的规划上下文。
 *
 * <p>维护的 shared-storage 静态 DAG 规划器必须通过本对象读取存储吞吐和最小事件间隔，
 * 并在规划前验证文件系统、聚类、故障、开销及 VM 调度器模式。它不会推断网络拓扑、链路争用
 * 或真实平台参数。</p>
 */
public final class PlanningContext {

    private final SimulationConfig config;
    private final PlatformProfile platform;

    public PlanningContext(SimulationConfig config, PlatformProfile platform) {
        if (config == null || platform == null) {
            throw new IllegalArgumentException("Planning configuration and platform are required");
        }
        this.config = config;
        this.platform = platform;
    }

    public int getSharedStorageTransferRateMbPerSecond() {
        return platform.getStorage().getMaxTransferRateMbPerSecond();
    }

    public double getCloudSimMinEventIntervalSeconds() {
        return config.getCloudSimMinEventIntervalSeconds();
    }

    /** 验证维护的静态 DAG 规划器实际估计的执行模型子集。 */
    public void validateSharedStorageStaticDag() {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.SHARED) {
            throw new IllegalArgumentException("Shared-storage static DAG planning requires ReplicaCatalog.FileSystem.SHARED");
        }
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new IllegalArgumentException("Shared-storage static DAG planning requires clustering method NONE");
        }
        if (config.getFailureModel().isEnabled()) {
            throw new IllegalArgumentException("Shared-storage static DAG planning requires the failure model to be disabled");
        }
        if (!isNoOverhead()) {
            throw new IllegalArgumentException("Shared-storage static DAG planning requires OverheadModelConfig.none()");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new IllegalArgumentException("Shared-storage static DAG planning requires SPACE_SHARED VMs");
            }
        }
    }

    /**
     * 验证 LOCAL 文件系统通信感知静态 DAG 规划器实际估计的执行模型子集。
     *
     * <p>LOCAL_HEFT/LOCAL_CPOP 的规划侧 AST（按父任务并行传输、传输与 VM 忙碌期重叠）
     * 逐位镜像运行时 preExecutionTransferDelayV1 的执行前传输延迟模型，因此
     * 要求 LOCAL 文件系统、NONE 聚类、无故障与无建模开销；数据移动模型可为
     * preExecutionTransferDelayV1（规划与执行逐位对齐）、链路争用模型
     * preExecutionTransferDelayWithContentionV1（规划侧仍按无争用 AST 估计，运行期
     * 并发传输公平共享 VM 端点带宽）或 Fat-tree 拓扑争用模型 fatTreeContentionV1
     * （运行期沿确定性路由公平共享路径链路 + 端点，同一偏差声明）。</p>
     */
    public void validateLocalStaticDag() {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.LOCAL) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires ReplicaCatalog.FileSystem.LOCAL");
        }
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires clustering method NONE");
        }
        if (config.getFailureModel().isEnabled()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires the failure model to be disabled");
        }
        if (!isNoOverhead()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires OverheadModelConfig.none()");
        }
        if (!config.getDataMovementModel().isPreExecutionTransferDelayV1()
                && !config.getDataMovementModel().isPreExecutionTransferDelayWithContentionV1()
                && !config.getDataMovementModel().isFatTreeContentionV1()) {
            throw new IllegalArgumentException("LOCAL static DAG planning requires "
                    + "DataMovementModel.preExecutionTransferDelayV1() (planning AST and runtime "
                    + "transfer delays bit-aligned), preExecutionTransferDelayWithContentionV1() "
                    + "(runtime endpoint link contention, documented divergence under concurrency), "
                    + "or fatTreeContentionV1() (runtime fat-tree path link contention, same "
                    + "divergence policy)");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new IllegalArgumentException("LOCAL static DAG planning requires SPACE_SHARED VMs");
            }
        }
    }

    private boolean isNoOverhead() {
        return config.getOverheadModel().getWorkflowEngineDelayInterval() == 0
                && config.getOverheadModel().getBandwidth() == 0.0
                && config.getOverheadModel().getWorkflowEngineDelays().isEmpty()
                && config.getOverheadModel().getQueueDelays().isEmpty()
                && config.getOverheadModel().getPostDelays().isEmpty()
                && config.getOverheadModel().getClusteringDelays().isEmpty();
    }
}
