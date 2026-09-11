package org.workflowsim.experiments.reference.p7;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 面向抽象调度器比较的冻结 P7 确定性基线矩阵。
 *
 * <p>该目录有意排除故障、开销、聚类、本地存储和规划算法。初始 DAX 样本仅限可解析的
 * Epigenomics 输入；它不是跨工作流家族结果集、真实平台回放，也不是经校准的成本或网络实验。</p>
 */
public final class P7BaselineMatrix {

    public static final long ROOT_SEED = 20260901L;
    public static final double RUNTIME_REFERENCE_MIPS = 1000.0;
    public static final double RUNTIME_SCALE = 1.0;
    /** 冻结的 CloudSim 内核节拍；改变它会改变模型中的任务开始时间。 */
    public static final double CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS = 0.1;
    public static final int SMALL_VM_COUNT = 10;
    public static final int LARGE_VM_COUNT = 50;

    private static final List<Parameters.SchedulingAlgorithm> SCHEDULING_ALGORITHMS =
            Collections.unmodifiableList(Arrays.asList(
                    Parameters.SchedulingAlgorithm.FCFS,
                    Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MCT,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                    Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN));

    private static final List<Workload> WORKLOADS = Collections.unmodifiableList(Arrays.asList(
            new Workload("epigenomics-n100", "Epigenomics", 100, SMALL_VM_COUNT,
                    "dax/epigenomics/n100/Epigenomics_100.dax",
                    "374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36"),
            new Workload("epigenomics-n997", "Epigenomics", 997, LARGE_VM_COUNT,
                    "dax/epigenomics/n997/Epigenomics_997.dax",
                    "2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628")));

    private static final Set<String> BASELINE_CELL_KEYS = buildBaselineCellKeys();

    private P7BaselineMatrix() {
    }

    /**
     * 返回 P7 基线协议允许使用的调度算法。
     *
     * @return 不可变的维护中调度算法列表
     */
    public static List<Parameters.SchedulingAlgorithm> schedulingAlgorithms() {
        return SCHEDULING_ALGORITHMS;
    }

    /**
     * 返回冻结 P7 基线必须覆盖的场景/调度器单元集合。
     *
     * <p>这个集合不依赖本地数据集路径，供索引校验器判定某份工件是否真的是完整的
     * P7 基线，而不是只执行了部分组合的开发性选择运行。</p>
     *
     * @return 不可变的 {@code scenarioId + NUL + schedulingAlgorithm} 单元键集合
     */
    static Set<String> baselineCellKeys() {
        return BASELINE_CELL_KEYS;
    }

    private static Set<String> buildBaselineCellKeys() {
        Set<String> result = new LinkedHashSet<String>();
        for (Workload workload : WORKLOADS) {
            for (PlatformVariant platformVariant : PlatformVariant.values()) {
                String scenarioId = workload.id + "-" + platformVariant.name().toLowerCase(Locale.ROOT);
                for (Parameters.SchedulingAlgorithm algorithm : SCHEDULING_ALGORITHMS) {
                    result.add(cellKey(scenarioId, algorithm.name()));
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 判断场景标识是否属于冻结 P7 矩阵。
     *
     * @param scenarioId 待检查的场景标识
     * @return 当且仅当该标识属于冻结矩阵时为 {@code true}
     */
    static boolean isBaselineScenarioId(String scenarioId) {
        if (scenarioId == null) {
            return false;
        }
        for (String cell : baselineCellKeys()) {
            int separator = cell.indexOf('\u0000');
            if (scenarioId.equals(cell.substring(0, separator))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断调度器名称是否属于冻结 P7 矩阵。
     *
     * @param algorithmName 待检查的枚举名称
     * @return 当且仅当该调度器被 P7 协议允许时为 {@code true}
     */
    static boolean isBaselineAlgorithmName(String algorithmName) {
        if (algorithmName == null) {
            return false;
        }
        for (Parameters.SchedulingAlgorithm algorithm : SCHEDULING_ALGORITHMS) {
            if (algorithm.name().equals(algorithmName)) {
                return true;
            }
        }
        return false;
    }

    /** 将场景和调度器组成不会与正常名称冲突的内部单元键。 */
    static String cellKey(String scenarioId, String algorithmName) {
        return scenarioId + "\u0000" + algorithmName;
    }

    /**
     * 按稳定场景标识返回冻结定义，不访问本地数据集。
     *
     * <p>只读索引校验器使用本方法将记录中的摘要字段与协议目录中的工作负载、平台变体
     * 和输入哈希契约交叉比对。</p>
     *
     * @param scenarioId P7 场景标识
     * @return 对应冻结场景；未知标识返回 {@code null}
     */
    static Scenario scenarioById(String scenarioId) {
        if (scenarioId == null) {
            return null;
        }
        for (Workload workload : WORKLOADS) {
            for (PlatformVariant platformVariant : PlatformVariant.values()) {
                Scenario candidate = new Scenario(workload, platformVariant);
                if (scenarioId.equals(candidate.getId())) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 为显式数据集根目录下的冻结工作负载构造平台变体场景。
     *
     * <p>数据集根必须是一个绝对目录。输入文件的存在性和哈希值由实际执行与报告校验负责。</p>
     *
     * @param datasetRoot 显式、绝对的 {@code datasets} 目录
     * @return 每个冻结工作负载与平台变体组合形成的不可变场景列表
     * @throws IllegalArgumentException 当数据集根不合法时
     */
    public static List<Scenario> scenarios(Path datasetRoot) {
        ReferenceDatasetRoot.require(datasetRoot);
        List<Scenario> scenarios = new ArrayList<>();
        for (Workload workload : WORKLOADS) {
            scenarios.add(new Scenario(workload, PlatformVariant.H0_HOMOGENEOUS));
            scenarios.add(new Scenario(workload, PlatformVariant.H1_HETEROGENEOUS));
        }
        return Collections.unmodifiableList(scenarios);
    }

    /**
     * 为一个 P7 场景创建冻结的、无故障且无开销的抽象仿真配置。
     *
     * @param scenario 已声明的工作负载与平台变体
     * @param schedulingAlgorithm P7 允许的维护中调度算法
     * @param datasetRoot 用于解析场景工作流相对路径的显式、绝对数据集根
     * @return 具有固定种子、运行时换算参数和 CloudSim 节拍的配置
     * @throws IllegalArgumentException 当参数为空或算法不属于 P7 基线时
     */
    public static SimulationConfig baselineConfig(Scenario scenario,
            Parameters.SchedulingAlgorithm schedulingAlgorithm, Path datasetRoot) {
        if (scenario == null || schedulingAlgorithm == null || datasetRoot == null) {
            throw new IllegalArgumentException("Scenario, scheduling algorithm, and dataset root are required");
        }
        if (!SCHEDULING_ALGORITHMS.contains(schedulingAlgorithm)) {
            throw new IllegalArgumentException("Scheduling algorithm is outside the P7 baseline: "
                    + schedulingAlgorithm);
        }
        return SimulationConfig.builder(scenario.resolveWorkflow(datasetRoot).toString(), scenario.getVmCount())
                .schedulingAlgorithm(schedulingAlgorithm)
                .planningAlgorithm(Parameters.PlanningAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(ROOT_SEED)
                .runtimeReferenceMips(RUNTIME_REFERENCE_MIPS)
                .runtimeScale(RUNTIME_SCALE)
                .cloudSimMinEventIntervalSeconds(CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS)
                .overheadModel(OverheadModelConfig.none())
                .clusteringParameters(new ClusteringParameters(0, 0,
                        ClusteringParameters.ClusteringMethod.NONE, null))
                .failureModel(FailureModelConfig.disabled())
                .costModel(Parameters.CostModel.DATACENTER)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .build();
    }

    /**
     * 构造与给定场景对应的冻结抽象平台。
     *
     * @param scenario 决定 VM 数量及同构或异构 MIPS 分配的场景
     * @return 已经容量预检的空间共享 VM 平台
     * @throws IllegalArgumentException 当场景为 {@code null} 时
     */
    public static PlatformProfile baselinePlatform(Scenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario is required");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("p7-" + scenario.getId());
        for (int id = 0; id < scenario.getVmCount(); id++) {
            double vmMips = scenario.getPlatformVariant() == PlatformVariant.H0_HOMOGENEOUS
                    ? 1000.0 : (id % 2 == 0 ? 500.0 : 1500.0);
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000.0,
                    2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, vmMips, 1, 512,
                    1000L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    /** P7 中冻结的同构与异构平台变体。 */
    public enum PlatformVariant {
        H0_HOMOGENEOUS,
        H1_HETEROGENEOUS
    }

    /** 一个冻结工作负载及其平台变体组成的不可变场景。 */
    public static final class Scenario {

        private final Workload workload;
        private final PlatformVariant platformVariant;

        private Scenario(Workload workload, PlatformVariant platformVariant) {
            this.workload = workload;
            this.platformVariant = platformVariant;
        }

        public String getId() {
            return workload.id + "-" + platformVariant.name().toLowerCase(Locale.ROOT);
        }

        public String getWorkflowFamily() {
            return workload.family;
        }

        public int getExpectedTaskCount() {
            return workload.expectedTaskCount;
        }

        public int getVmCount() {
            return workload.vmCount;
        }

        public String getRelativeWorkflowPath() {
            return workload.relativeWorkflowPath;
        }

        public String getExpectedWorkflowSha256() {
            return workload.expectedWorkflowSha256;
        }

        public PlatformVariant getPlatformVariant() {
            return platformVariant;
        }

        /**
         * 将场景的相对工作流路径解析为规范化绝对路径。
         *
         * @param datasetRoot 显式、绝对的 {@code datasets} 目录
         * @return 该场景工作流的规范化绝对路径
         * @throws IllegalArgumentException 当数据集根或输入文件不合法时
         */
        public Path resolveWorkflow(Path datasetRoot) {
            return ReferenceDatasetRoot.resolveFile(datasetRoot, workload.relativeWorkflowPath);
        }
    }

    private static final class Workload {

        private final String id;
        private final String family;
        private final int expectedTaskCount;
        private final int vmCount;
        private final String relativeWorkflowPath;
        private final String expectedWorkflowSha256;

        private Workload(String id, String family, int expectedTaskCount, int vmCount,
                String relativeWorkflowPath, String expectedWorkflowSha256) {
            this.id = id;
            this.family = family;
            this.expectedTaskCount = expectedTaskCount;
            this.vmCount = vmCount;
            this.relativeWorkflowPath = relativeWorkflowPath;
            this.expectedWorkflowSha256 = expectedWorkflowSha256;
        }
    }
}
