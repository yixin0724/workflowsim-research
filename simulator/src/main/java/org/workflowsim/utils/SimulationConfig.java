package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * 一次串行 WorkflowSim 实验的不可变配置。
 *
 * <p>WorkflowSim 与 CloudSim 保留历史静态状态，因此此对象必须通过
 * {@link SimulationSession} 应用；它不表示同一 JVM 支持并发仿真。配置中显式记录
 * 输入、资源规模、算法、随机种子、时间换算、故障和数据移动模型，供运行清单追溯。</p>
 */
public final class SimulationConfig {

    private final List<String> workflowPaths;
    private final List<Double> workflowArrivalSeconds;
    private final int vmCount;
    private final OverheadModelConfig overheadModel;
    private final ClusteringParameters clusteringParameters;
    private final SchedulingAlgorithm schedulingAlgorithm;
    private final PlanningAlgorithm planningAlgorithm;
    private final String reduceMethod;
    private final long deadline;
    private final ReplicaCatalog.FileSystem fileSystem;
    private final long randomSeed;
    private final double runtimeScale;
    private final double runtimeReferenceMips;
    private final double cloudSimMinEventIntervalSeconds;
    private final CostModel costModel;
    private final FailureModelConfig failureModel;
    private final DataMovementModel dataMovementModel;
    private final TaskCostMatrix taskCostMatrix;

    private SimulationConfig(Builder builder) {
        this.workflowPaths = Collections.unmodifiableList(new ArrayList<>(builder.workflowPaths));
        this.workflowArrivalSeconds = Collections.unmodifiableList(
                new ArrayList<>(builder.workflowArrivalSeconds));
        this.vmCount = builder.vmCount;
        this.overheadModel = builder.overheadModel;
        this.clusteringParameters = builder.clusteringParameters;
        this.schedulingAlgorithm = builder.schedulingAlgorithm;
        this.planningAlgorithm = builder.planningAlgorithm;
        this.reduceMethod = builder.reduceMethod;
        this.deadline = builder.deadline;
        this.fileSystem = builder.fileSystem;
        this.randomSeed = builder.randomSeed;
        this.runtimeScale = builder.runtimeScale;
        this.runtimeReferenceMips = builder.runtimeReferenceMips;
        this.cloudSimMinEventIntervalSeconds = builder.cloudSimMinEventIntervalSeconds;
        this.costModel = builder.costModel;
        this.failureModel = builder.failureModel;
        this.dataMovementModel = builder.dataMovementModel;
        this.taskCostMatrix = builder.taskCostMatrix;
    }

    /**
     * 从单个工作流输入创建构建器。
     *
     * @param workflowPath 工作流输入文件路径
     * @param vmCount 可用虚拟机数量
     * @return 新的配置构建器
     */
    public static Builder builder(String workflowPath, int vmCount) {
        return new Builder(Collections.singletonList(workflowPath), vmCount);
    }

    public static Builder builder(List<String> workflowPaths, int vmCount) {
        return new Builder(workflowPaths, vmCount);
    }

    /** @return 不可修改的工作流输入文件路径列表 */
    public List<String> getWorkflowPaths() {
        return workflowPaths;
    }

    /**
     * @return 每个工作流输入的提交时刻（模拟秒），与 {@link #getWorkflowPaths()}
     *         一一对应；全部为 0.0 时行为与单时刻提交逐位一致
     */
    public List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds;
    }

    /** @return 可用虚拟机数量 */
    public int getVmCount() {
        return vmCount;
    }

    /** @return 可复现的调度与执行开销模型 */
    public OverheadModelConfig getOverheadModel() {
        return overheadModel;
    }

    /** @return 任务聚类参数 */
    public ClusteringParameters getClusteringParameters() {
        return clusteringParameters;
    }

    /** @return 在线调度或静态派发算法 */
    public SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }

    /** @return 离线规划算法；未启用时为 {@code INVALID} */
    public PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }

    /** @return 作业规约方法；未指定时为 {@code null} */
    public String getReduceMethod() {
        return reduceMethod;
    }

    /** @return 工作流截止时间；0 表示未请求截止时间 */
    public long getDeadline() {
        return deadline;
    }

    /** @return 副本目录使用的共享或本地文件系统模型 */
    public ReplicaCatalog.FileSystem getFileSystem() {
        return fileSystem;
    }

    /** @return 本次实验的根随机种子 */
    public long getRandomSeed() {
        return randomSeed;
    }

    /** @return 输入运行时间转换到模型长度后的缩放系数 */
    public double getRuntimeScale() {
        return runtimeScale;
    }

    /** @return 将以秒表示的输入运行时间转换为 MI 时使用的参考 MIPS */
    public double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    /** @return 本次运行显式设置的 CloudSim 最小事件间隔，单位为模拟秒 */
    public double getCloudSimMinEventIntervalSeconds() {
        return cloudSimMinEventIntervalSeconds;
    }

    /** @return 数据中心级或虚拟机级成本模型 */
    public CostModel getCostModel() {
        return costModel;
    }

    /** @return 故障模型配置 */
    public FailureModelConfig getFailureModel() {
        return failureModel;
    }

    /** @return 本次运行采用的显式文件传输时间模型 */
    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    /** @return 可选的任务×VM 异构执行成本矩阵；未配置时为 {@code null} */
    public TaskCostMatrix getTaskCostMatrix() {
        return taskCostMatrix;
    }

    /**
     * 返回以当前全部配置值初始化的构建器。
     *
     * <p>实验活动可据此显式修改每个重复的种子，而不依赖可变的全局
     * {@link Parameters} 状态。</p>
     *
     * @return 预填当前全部配置值的构建器
     */
    public Builder toBuilder() {
        return new Builder(workflowPaths, vmCount)
                .workflowArrivalSeconds(workflowArrivalSeconds)
                .overheadModel(overheadModel)
                .clusteringParameters(clusteringParameters)
                .schedulingAlgorithm(schedulingAlgorithm)
                .planningAlgorithm(planningAlgorithm)
                .reduceMethod(reduceMethod)
                .deadline(deadline)
                .fileSystem(fileSystem)
                .randomSeed(randomSeed)
                .runtimeScale(runtimeScale)
                .runtimeReferenceMips(runtimeReferenceMips)
                .cloudSimMinEventIntervalSeconds(cloudSimMinEventIntervalSeconds)
                .costModel(costModel)
                .failureModel(failureModel)
                .dataMovementModel(dataMovementModel)
                .taskCostMatrix(taskCostMatrix);
    }

    /**
     * 返回仅根随机种子不同的等价配置。
     *
     * <p>根种子本身不能保证 common random numbers；后者还要求随机模型按稳定事件键
     * 分配随机性。</p>
     *
     * @param value 新的根随机种子
     * @return 仅种子不同的不可变配置
     */
    public SimulationConfig withRandomSeed(long value) {
        return toBuilder().randomSeed(value).build();
    }

    /**
     * 配置构建器，默认生成无开销的确定性运行配置。
     *
     * <p>构建时会检查算法组合、输入、数值范围以及共享存储 DAG 规划器的模型前提。
     * 不满足前提时立即失败，而不是静默改变实验语义。</p>
     */
    public static final class Builder {

        private final List<String> workflowPaths;
        private List<Double> workflowArrivalSeconds;
        private final int vmCount;
        private OverheadModelConfig overheadModel = OverheadModelConfig.none();
        private ClusteringParameters clusteringParameters = new ClusteringParameters(
                0, 0, ClusteringParameters.ClusteringMethod.NONE, null);
        private SchedulingAlgorithm schedulingAlgorithm = SchedulingAlgorithm.FCFS;
        private PlanningAlgorithm planningAlgorithm = PlanningAlgorithm.INVALID;
        private String reduceMethod;
        private long deadline;
        private ReplicaCatalog.FileSystem fileSystem = ReplicaCatalog.FileSystem.SHARED;
        private long randomSeed;
        private double runtimeScale = 1.0;
        private double runtimeReferenceMips = 1000.0;
        private double cloudSimMinEventIntervalSeconds = 0.1;
        private CostModel costModel = CostModel.DATACENTER;
        private FailureModelConfig failureModel = FailureModelConfig.disabled();
        private DataMovementModel dataMovementModel = DataMovementModel.legacyWorkflowsimV1();
        private TaskCostMatrix taskCostMatrix;

        private Builder(List<String> workflowPaths, int vmCount) {
            if (workflowPaths == null || workflowPaths.isEmpty()) {
                throw new IllegalArgumentException("At least one workflow input is required");
            }
            this.workflowPaths = new ArrayList<>(workflowPaths);
            // R5 默认：全部工作流在 t=0 提交——与历史单时刻提交逐位一致。
            this.workflowArrivalSeconds = new ArrayList<>(
                    Collections.nCopies(workflowPaths.size(), 0.0));
            this.vmCount = vmCount;
        }

        /**
         * 使用旧式可变开销参数构建模型。
         *
         * @deprecated 请使用 {@link #overheadModel(OverheadModelConfig)}。该兼容桥只提取
         * 分布族及其参数，并在会话中按本次运行的根种子重新抽样。
         * @param value 旧式开销参数
         * @return 当前构建器
         */
        @Deprecated
        public Builder overheadParameters(OverheadParameters value) {
            this.overheadModel = OverheadModelConfig.fromLegacy(value);
            return this;
        }

        /** @param value 开销模型 @return 当前构建器 */
        public Builder overheadModel(OverheadModelConfig value) {
            this.overheadModel = value;
            return this;
        }

        /** @param value 聚类参数 @return 当前构建器 */
        public Builder clusteringParameters(ClusteringParameters value) {
            this.clusteringParameters = value;
            return this;
        }

        /** @param value 在线调度或静态派发算法 @return 当前构建器 */
        public Builder schedulingAlgorithm(SchedulingAlgorithm value) {
            this.schedulingAlgorithm = value;
            return this;
        }

        /** @param value 离线规划算法 @return 当前构建器 */
        public Builder planningAlgorithm(PlanningAlgorithm value) {
            this.planningAlgorithm = value;
            return this;
        }

        /** @param value 作业规约方法 @return 当前构建器 */
        public Builder reduceMethod(String value) {
            this.reduceMethod = value;
            return this;
        }

        /** @param value 截止时间；0 表示未请求 @return 当前构建器 */
        public Builder deadline(long value) {
            this.deadline = value;
            return this;
        }

        /** @param value 文件系统模型 @return 当前构建器 */
        public Builder fileSystem(ReplicaCatalog.FileSystem value) {
            this.fileSystem = value;
            return this;
        }

        /** @param value 根随机种子 @return 当前构建器 */
        public Builder randomSeed(long value) {
            this.randomSeed = value;
            return this;
        }

        /** @param value 运行时间缩放系数 @return 当前构建器 */
        public Builder runtimeScale(double value) {
            this.runtimeScale = value;
            return this;
        }

        /** @param value 参考吞吐量，单位为 MI/s @return 当前构建器 */
        public Builder runtimeReferenceMips(double value) {
            this.runtimeReferenceMips = value;
            return this;
        }

        /** @param value CloudSim 最小事件间隔，单位为模拟秒 @return 当前构建器 */
        public Builder cloudSimMinEventIntervalSeconds(double value) {
            this.cloudSimMinEventIntervalSeconds = value;
            return this;
        }

        /** @param value 成本模型 @return 当前构建器 */
        public Builder costModel(CostModel value) {
            this.costModel = value;
            return this;
        }

        /** @param value 故障模型 @return 当前构建器 */
        public Builder failureModel(FailureModelConfig value) {
            this.failureModel = value;
            return this;
        }

        /** @param value 文件数据移动模型 @return 当前构建器 */
        public Builder dataMovementModel(DataMovementModel value) {
            this.dataMovementModel = value;
            return this;
        }

        /**
         * 设置可选的任务×VM 异构执行成本矩阵（论文复现支撑）。
         *
         * @param value 任务×VM 执行成本矩阵；null 表示未配置（保持 MI/mips 缩放）
         * @return 当前构建器
         */
        public Builder taskCostMatrix(TaskCostMatrix value) {
            this.taskCostMatrix = value;
            return this;
        }

        /**
         * R5：设置每个工作流输入的提交时刻（动态到达）。
         *
         * @param value 与输入路径一一对应的提交时刻列表（模拟秒）
         * @return 当前构建器
         */
        public Builder workflowArrivalSeconds(List<Double> value) {
            if (value == null) {
                throw new IllegalArgumentException("Workflow arrival seconds cannot be null; "
                        + "use an explicit list of zeros for simultaneous submission");
            }
            this.workflowArrivalSeconds = new ArrayList<>(value);
            return this;
        }

        /**
         * 校验全部模型前提并创建不可变配置。
         *
         * @return 可用于 {@link SimulationSession} 的配置
         * @throws IllegalArgumentException 当输入、数值或算法组合不合法时
         */
        public SimulationConfig build() {
            for (String path : workflowPaths) {
                if (path == null || path.trim().isEmpty()) {
                    throw new IllegalArgumentException("Workflow input paths cannot be empty");
                }
            }
            if (workflowArrivalSeconds == null
                    || workflowArrivalSeconds.size() != workflowPaths.size()) {
                throw new IllegalArgumentException("Workflow arrival seconds must cover every "
                        + "workflow input path");
            }
            for (Double arrivalSecond : workflowArrivalSeconds) {
                if (arrivalSecond == null || !Double.isFinite(arrivalSecond.doubleValue())
                        || arrivalSecond < 0.0) {
                    throw new IllegalArgumentException("Workflow arrival seconds must be finite "
                            + "and non-negative");
                }
            }
            boolean hasNonZeroArrival = false;
            for (Double arrivalSecond : workflowArrivalSeconds) {
                if (arrivalSecond > 0.0) {
                    hasNonZeroArrival = true;
                    break;
                }
            }
            // R5：到达门控按原始任务编号归属工作流；聚类会生成新 Job ID，
            // 无法可靠归属，故非零提交时刻与非 NONE 聚类互斥（显式拒绝而非静默失效）。
            if (hasNonZeroArrival
                    && clusteringParameters.getClusteringMethod()
                            != ClusteringParameters.ClusteringMethod.NONE) {
                throw new IllegalArgumentException("Non-zero workflow arrival seconds require "
                        + "clustering method NONE: clustered jobs no longer carry the original "
                        + "task ids used for arrival gating");
            }
            if (vmCount <= 0) {
                throw new IllegalArgumentException("VM count must be positive");
            }
            if (overheadModel == null || clusteringParameters == null
                    || schedulingAlgorithm == null || planningAlgorithm == null
                    || fileSystem == null || costModel == null || failureModel == null
                    || dataMovementModel == null) {
                throw new IllegalArgumentException("Simulation configuration contains a required null value");
            }
            if (deadline < 0L) {
                throw new IllegalArgumentException("Deadline must be zero (not requested) or a positive simulated second");
            }
            if (!isPositiveFinite(runtimeScale) || !isPositiveFinite(runtimeReferenceMips)
                    || !isPositiveFinite(cloudSimMinEventIntervalSeconds)) {
                throw new IllegalArgumentException("Runtime scale, reference MIPS, and CloudSim minimum "
                        + "event interval must be finite and positive");
            }
            if (planningAlgorithm != PlanningAlgorithm.INVALID
                    && schedulingAlgorithm != SchedulingAlgorithm.STATIC) {
                throw new IllegalArgumentException("A planning algorithm assigns VM mappings and requires "
                        + "SchedulingAlgorithm.STATIC dispatch");
            }
            if (planningAlgorithm == PlanningAlgorithm.INVALID
                    && schedulingAlgorithm == SchedulingAlgorithm.STATIC) {
                throw new IllegalArgumentException("SchedulingAlgorithm.STATIC requires a planning algorithm "
                        + "that assigns VM mappings");
            }
            // PLAT-7：DATA 算法的局部性判定按 VM ID 匹配副本站点，而 SHARED 模式
            // 的副本按数据中心名注册，两者永不匹配——DATA 会静默退化为 first-fit-idle，
            // 产生误导性的"数据局部性"实验结果，故在配置层直接拒绝。
            if (schedulingAlgorithm == SchedulingAlgorithm.DATA
                    && fileSystem == ReplicaCatalog.FileSystem.SHARED) {
                throw new IllegalArgumentException("SchedulingAlgorithm.DATA requires ReplicaCatalog.FileSystem.LOCAL; "
                        + "under SHARED storage its replica sites are datacenter names that never match "
                        + "VM ids, so it would silently degenerate to first-fit-idle");
            }
            if (schedulingAlgorithm == SchedulingAlgorithm.INVALID) {
                throw new IllegalArgumentException("SchedulingAlgorithm.INVALID is not a runnable configuration; "
                        + "select an explicit online scheduler or STATIC with a planning algorithm");
            }
            if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                    || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT) {
                if (fileSystem != ReplicaCatalog.FileSystem.SHARED) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires shared storage");
                }
                if (clusteringParameters.getClusteringMethod()
                        != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(planningAlgorithm + " requires OverheadModelConfig.none()");
                }
                if (!dataMovementModel.isLegacyWorkflowsimV1()) {
                    throw new IllegalArgumentException(planningAlgorithm + " currently requires "
                            + "DataMovementModel.legacyWorkflowsimV1() so planning and execution "
                            + "transfer estimates remain aligned");
                }
            }
            // 任务×VM 成本矩阵由 STATIC 派发路径折算（MI = 秒数 × vm.mips），在线
            // 调度器不做折算会被静默忽略；折算还要求作业与逻辑任务一一对应（NONE 聚类）。
            if (taskCostMatrix != null) {
                if (schedulingAlgorithm != SchedulingAlgorithm.STATIC) {
                    throw new IllegalArgumentException("A task cost matrix is applied only by the STATIC "
                            + "dispatch path; online schedulers would silently ignore it");
                }
                if (clusteringParameters.getClusteringMethod()
                        != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException("A task cost matrix requires clustering method NONE "
                            + "so every Job maps to exactly one logical Task");
                }
                // R8 审计修复（算法通道 P1-1）：SharedStorageDagPlanner 的规划侧
                // 执行时间只用原始 DAX 长度，不消费成本矩阵投影；运行时派发路径
                // 却按矩阵折算 MI——两侧语义静默分叉会产生错误的规划顺序而无任何
                // 报错。在配置层直接拒绝该组合。
                if (planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_HEFT
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_CPOP
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_DLS
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_ETF
                        || planningAlgorithm == PlanningAlgorithm.SHARED_STORAGE_PEFT) {
                    throw new IllegalArgumentException("A task cost matrix cannot be combined with "
                            + planningAlgorithm + ": the shared-storage planning track estimates "
                            + "execution times from raw task lengths and does not consume the matrix, "
                            + "while the STATIC dispatch path would fold it in at runtime "
                            + "(silent planning/runtime divergence)");
                }
            }
            // LOCAL_HEFT/LOCAL_CPOP 的规划侧 AST（按父任务并行传输、传输与 VM 忙碌期
            // 重叠）逐位镜像运行时 preExecutionTransferDelayV1 的执行前传输延迟模型，
            // 模型前提在配置层提前强制（规划器运行时还会再次校验）。链路争用模型
            // （R2）同样可用于本轨道：规划侧仍按无争用 AST 估计，运行期并发传输公平
            // 共享 VM 端点带宽，两者在并发负载下的可解释偏差由文档声明。
            if (planningAlgorithm == PlanningAlgorithm.LOCAL_HEFT
                    || planningAlgorithm == PlanningAlgorithm.LOCAL_CPOP) {
                String label = planningAlgorithm.name();
                if (fileSystem != ReplicaCatalog.FileSystem.LOCAL) {
                    throw new IllegalArgumentException(label + " requires ReplicaCatalog.FileSystem.LOCAL; "
                            + "inter-task communication is only modeled under the LOCAL file system");
                }
                if (clusteringParameters.getClusteringMethod()
                        != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(label + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(label + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(label + " requires OverheadModelConfig.none()");
                }
                if (!dataMovementModel.isPreExecutionTransferDelayV1()
                        && !dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                        && !dataMovementModel.isFatTreeContentionV1()) {
                    throw new IllegalArgumentException(label + " requires "
                            + "DataMovementModel.preExecutionTransferDelayV1() (planning AST and "
                            + "runtime transfer delays bit-aligned), "
                            + "preExecutionTransferDelayWithContentionV1() (runtime endpoint link "
                            + "contention, documented planner/execution divergence under "
                            + "concurrency), or fatTreeContentionV1() (runtime fat-tree path link "
                            + "contention, same divergence policy)");
                }
            }
            // 执行前传输延迟（含链路争用模型）在任务就绪时按目标 VM 估计与登记副本，
            // 要求规划器在规划阶段完成静态 VM 映射；INVALID 规划层在释放时刻没有目标 VM。
            if ((dataMovementModel.isPreExecutionTransferDelayV1()
                    || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                    || dataMovementModel.isFatTreeContentionV1())
                    && planningAlgorithm == PlanningAlgorithm.INVALID) {
                throw new IllegalArgumentException(dataMovementModel.getKind() + " requires a "
                        + "planning algorithm that assigns static VM mappings (e.g. LOCAL_HEFT, "
                        + "LOCAL_CPOP, RANDOM); the INVALID planning layer leaves the destination "
                        + "VM unknown at release time");
            }
            // Fat-tree 链路争用模型：VM→VM 传输沿确定性路由占用共享链路，前提是
            // LOCAL 文件系统（VM→VM 通信被建模）、静态映射（路径就绪期可知）、
            // NONE 聚类与无故障/无开销（与 LOCAL_HEFT 轨道同一组前提）。平台侧
            // 拓扑声明的存在性由标准运行器校验（PlatformProfile.networkTopology）。
            if (dataMovementModel.isFatTreeContentionV1()) {
                if (fileSystem != ReplicaCatalog.FileSystem.LOCAL) {
                    throw new IllegalArgumentException(dataMovementModel.getKind()
                            + " requires ReplicaCatalog.FileSystem.LOCAL; inter-VM communication "
                            + "along fat-tree paths is only modeled under the LOCAL file system");
                }
                if (clusteringParameters.getClusteringMethod()
                        != ClusteringParameters.ClusteringMethod.NONE) {
                    throw new IllegalArgumentException(dataMovementModel.getKind()
                            + " requires clustering method NONE");
                }
                if (failureModel.isEnabled()) {
                    throw new IllegalArgumentException(dataMovementModel.getKind()
                            + " requires the failure model to be disabled");
                }
                if (!isNoOverhead(overheadModel)) {
                    throw new IllegalArgumentException(dataMovementModel.getKind()
                            + " requires OverheadModelConfig.none()");
                }
            }
            return new SimulationConfig(this);
        }

        private static boolean isPositiveFinite(double value) {
            return value > 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
        }

        private static boolean isNoOverhead(OverheadModelConfig value) {
            return value.getWorkflowEngineDelayInterval() == 0 && value.getBandwidth() == 0.0
                    && value.getWorkflowEngineDelays().isEmpty() && value.getQueueDelays().isEmpty()
                    && value.getPostDelays().isEmpty() && value.getClusteringDelays().isEmpty();
        }
    }
}

