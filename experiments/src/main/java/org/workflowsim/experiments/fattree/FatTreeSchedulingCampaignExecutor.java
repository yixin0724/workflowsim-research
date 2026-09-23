package org.workflowsim.experiments.fattree;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.PairedWilcoxonSignificance;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Fat-tree × 调度联合实验执行器（campaign Phase B 主入口）。
 *
 * <p>执行协议见 {@code docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md}：
 * 主矩阵 10 DAG × 4 规划器 × 3 数据移动模型 = 120 次运行；3 主机敏感性 OFAT
 * 扫描 10 DAG × 2 通信感知规划器 × 6 拓扑变体 = 120 次运行；4 主机结构
 * 敏感性扫描（激活 k/超收敛/放置轴判别力）= 120 次运行。全部运行使用同一
 * seed（91）、同一 3 主机同质平台，配对统计沿 DAG 维度（Wilcoxon 符号秩）。</p>
 *
 * <p>Montage/Sipht 家族被排除：原始 DAX 含同名文件的不一致尺寸声明（如
 * Montage 的 fit.txt），被 LOCAL 通信感知规划族的严格副本校验拒绝——兼容性
 * 分类由 {@code FatTreeCampaignDagCompatibilityIntegrationTest} 锁定。</p>
 *
 * <p><b>用法：</b>{@code main(<datasets-root>, <output-dir>)}；无参数时默认使用
 * 当前工作目录下的 {@code datasets} 与 {@code fattree-campaign-output/run-<时间戳>}。
 * 输出 {@code campaign-results.json}（schema
 * {@code workflowsim-fattree-campaign-v1}）与 {@code campaign-results.md}。</p>
 *
 * <p>确定性：相同数据集根下复跑产出逐位一致的 makespan（由
 * {@code FatTreeCampaignGoldenIntegrationTest} 锁定抽样）。</p>
 */
public final class FatTreeSchedulingCampaignExecutor {

    static final String RESULTS_JSON_FILE_NAME = "campaign-results.json";
    static final String RESULTS_MD_FILE_NAME = "campaign-results.md";
    static final String RESULTS_SCHEMA = "workflowsim-fattree-campaign-v1";
    static final long FIXED_SEED = 91L;

    /** campaign 对照规划器：两个通信感知 + 两个通信无感知基线（探测验证合法）。 */
    static final List<Parameters.PlanningAlgorithm> PLANNERS = Collections.unmodifiableList(
            Arrays.asList(
                    Parameters.PlanningAlgorithm.LOCAL_HEFT,
                    Parameters.PlanningAlgorithm.LOCAL_CPOP,
                    Parameters.PlanningAlgorithm.RANDOM,
                    Parameters.PlanningAlgorithm.PSO));

    /** 三级数据移动模型：约束域 V1 ⊂ R2  R6（弱单调性不变量的基础）。 */
    static final List<DataMovementModel> MODELS = Collections.unmodifiableList(Arrays.asList(
            DataMovementModel.preExecutionTransferDelayV1(),
            DataMovementModel.preExecutionTransferDelayWithContentionV1(),
            DataMovementModel.fatTreeContentionV1()));

    /**
     * R8 再标定（2026-09-16，用户授权执行）：campaign 链路带宽基线。
     *
     * <p>取 0.125 MB/s = VM 端点带宽（1 MB/s）的 1/8，即 8:1 接入超收敛。
     * 依据：① F1 8 单位 bug 修复前，声明 1.0 MB/s 的链路实际就运行在
     * 0.125——再标定恢复 R7 分析所处的真实物理区间（诚实单位下的对账锚点）；
     * ② 审计实测（COMPREHENSIVE_AUDIT_R8.md §3.3 N-2）：链路 ≥ 端点时链路层
     * 永不束缚单流，R6 ≡ R2 逐位相等、全部拓扑敏感性轴退化，对称供给下
     * campaign 的拓扑轴没有判别力；③ simulator 慢链路探针证明链路 &lt; 端点
     * 时争用模型正确生效。</p>
     */
    static final double BASELINE_LINK_BANDWIDTH_MB = 0.125;

    /**
     * A4 带宽比轴 = 基线 ×10 = 1.25 MB/s &gt; 端点带宽 ⇒ 链路非束缚 ⇒
     * 预期精确收敛回 R2——恢复该轴"链路远宽于端点时收敛回端点主导"的设计语义。
     */
    static final double A4_LINK_BANDWIDTH_MB = BASELINE_LINK_BANDWIDTH_MB * 10.0;

    /**
     * 3 主机敏感性拓扑变体（OFAT）：基线 + 5 个单轴变体，全部 R6 模型。
     *
     * <p>实测边界：3 主机下任意两条并发流必共享一个端点主机，路由变体
     * （k、超收敛、放置）改变共享链路集合但不改变每条链路上的流重叠集合，
     * 公平份额速率画像不变——结构轴在 3 主机平台退化（实测全部恒等）；
     * 带宽比轴（A4）有效。结构轴的判别力由 4 主机结构块
     * （{@link #structuralVariants()}）提供。</p>
     */
    static final List<TopologyVariant> SENSITIVITY_VARIANTS =
            Collections.unmodifiableList(Arrays.asList(
                    new TopologyVariant("baseline-k4-full",
                            NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB)),
                    new TopologyVariant("A1-k4-oversub2x",
                            NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, 2)),
                    new TopologyVariant("A2-k8-full",
                            NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB)),
                    new TopologyVariant("A3-k8-oversub2x",
                            NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB, 8)),
                    new TopologyVariant("A4-k4-full-link10x",
                            NetworkTopologySpec.fatTree(4, A4_LINK_BANDWIDTH_MB)),
                    new TopologyVariant("A5-k4-full-same-edge-pair",
                            NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, null,
                                    sameEdgePairPlacements()))));

    /**
     * 4 主机结构敏感性变体：与 3 主机轴相同，但 A5 放置覆盖 4 台主机
     * （{0→0,1→0,2→2,3→2}：两个同 edge 主机对，跨 2 Pod）。4 主机允许
     * 不共享端点的交叉流（如 0→2 与 1→3），使 k/超收敛/放置轴获得判别力。
     */
    static List<TopologyVariant> structuralVariants() {
        Map<Integer, Integer> pairPlacements = new LinkedHashMap<Integer, Integer>();
        pairPlacements.put(Integer.valueOf(0), Integer.valueOf(0));
        pairPlacements.put(Integer.valueOf(1), Integer.valueOf(0));
        pairPlacements.put(Integer.valueOf(2), Integer.valueOf(2));
        pairPlacements.put(Integer.valueOf(3), Integer.valueOf(2));
        return Collections.unmodifiableList(Arrays.asList(
                new TopologyVariant("baseline-k4-full",
                        NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB)),
                new TopologyVariant("A1-k4-oversub2x",
                        NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, 2)),
                new TopologyVariant("A2-k8-full",
                        NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB)),
                new TopologyVariant("A3-k8-oversub2x",
                        NetworkTopologySpec.fatTree(8, BASELINE_LINK_BANDWIDTH_MB, 8)),
                new TopologyVariant("A4-k4-full-link10x",
                        NetworkTopologySpec.fatTree(4, A4_LINK_BANDWIDTH_MB)),
                new TopologyVariant("A5-k4-full-same-edge-pairs",
                        NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB, null,
                                pairPlacements))));
    }

    private FatTreeSchedulingCampaignExecutor() {
    }

    /**
     * 同 edge 热点放置：host0/host1 显式放 edge 全局索引 0（k=4 时每 edge
     * 至多 k/2=2 台主机，故 3 台无法全部同 edge），host2 放索引 2（pod1/edge0）。
     * 对照默认轮转放置（索引 0,1,2：跨 2 Pod 3 edge）。
     */
    private static Map<Integer, Integer> sameEdgePairPlacements() {
        Map<Integer, Integer> placements = new LinkedHashMap<Integer, Integer>();
        placements.put(Integer.valueOf(0), Integer.valueOf(0));
        placements.put(Integer.valueOf(1), Integer.valueOf(0));
        placements.put(Integer.valueOf(2), Integer.valueOf(2));
        return placements;
    }

    /**
     * 命令行入口。
     *
     * @param args {@code <datasets-root> <output-dir>}；留空则使用默认路径
     * @throws Exception 当任一次运行不健康、参数或写出失败时
     */
    public static void main(String[] args) throws Exception {
        Path datasetsRoot;
        Path outputDir;
        if (args == null || args.length == 0) {
            Path projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            datasetsRoot = projectRoot.resolve("datasets");
            outputDir = projectRoot.resolve("fattree-campaign-output")
                    .resolve("run-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                            .format(new java.util.Date()));
            System.out.println("[campaign] 未提供参数，使用默认路径：");
            System.out.println("[campaign]   数据集根 = " + datasetsRoot);
            System.out.println("[campaign]   输出目录 = " + outputDir);
        } else if (args.length == 2) {
            datasetsRoot = Paths.get(args[0]).toAbsolutePath().normalize();
            outputDir = Paths.get(args[1]).toAbsolutePath().normalize();
        } else {
            throw new IllegalArgumentException(
                    "Usage: FatTreeSchedulingCampaignExecutor <datasets-root> <output-dir>");
        }
        Log.disable();
        List<DagScenario> dags = discoverCoreDags(datasetsRoot);
        System.out.println("[campaign] 发现 " + dags.size() + " 个核心 DAG");
        CampaignResults results = runCampaign(datasetsRoot, dags, true, true);
        Files.createDirectories(outputDir);
        Path json = outputDir.resolve(RESULTS_JSON_FILE_NAME);
        Path markdown = outputDir.resolve(RESULTS_MD_FILE_NAME);
        ExperimentArtifactWriter.writeJson(json, results.toJson());
        Files.write(markdown, results.toMarkdown().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("[campaign] 完成：主矩阵 " + results.mainMatrix.size()
                + " 次运行 + 3 主机敏感性 " + results.sensitivity.size()
                + " 次运行 + 4 主机结构敏感性 " + results.structural.size() + " 次运行");
        System.out.println("[campaign] 工件：" + json + " , " + markdown);
        System.out.println(results.toMarkdown());
    }

    /**
     * 发现 10 个核心 DAG（3 家族 × 3 规模 + HEFT 论文例）。
     *
     * <p>Montage/Sipht 不入选：其原始 DAX 含同名文件的不一致尺寸声明，被
     * LOCAL 通信感知规划族（campaign 的两个主角规划器所属家族）拒绝。</p>
     */
    static List<DagScenario> discoverCoreDags(Path datasetsRoot) throws IOException {
        String[][] families = {
                {"epigenomics", "n24", "n46", "n100"},
                {"cybershake", "n30", "n50", "n100"},
                {"inspiral", "n30", "n50", "n100"}};
        List<DagScenario> dags = new ArrayList<DagScenario>();
        dags.add(new DagScenario("heft-paper-example", "heft", "n10",
                datasetsRoot.resolve("dax/heft/heft-paper-example.dax")));
        for (String[] family : families) {
            for (int scaleIndex = 1; scaleIndex < family.length; scaleIndex++) {
                String familyName = family[0];
                String scale = family[scaleIndex];
                Path dir = datasetsRoot.resolve("dax").resolve(familyName).resolve(scale);
                dags.add(new DagScenario(familyName + "-" + scale, familyName, scale,
                        singleDax(dir)));
            }
        }
        return Collections.unmodifiableList(dags);
    }

    private static Path singleDax(Path dir) throws IOException {
        Path found = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.dax")) {
            for (Path candidate : stream) {
                if (found != null) {
                    throw new IllegalStateException("Multiple .dax files in " + dir);
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new IllegalStateException("No .dax file in " + dir);
        }
        return found;
    }

    /**
     * 执行 campaign（IT 可用 DAG 子集与关闭扫描复用）。
     *
     * @param datasetsRoot 数据集根（当前实现仅用于标签一致性，DAX 路径来自 dags）
     * @param dags 参与的 DAG 场景
     * @param includeSensitivity 是否执行 3 主机敏感性 OFAT 扫描
     * @param includeStructural 是否执行 4 主机结构敏感性扫描
     * @return 主矩阵 + 敏感性 + 结构敏感性 + 统计 + 排名的完整结果
     * @throws Exception 当任一次运行不健康时
     */
    static CampaignResults runCampaign(Path datasetsRoot, List<DagScenario> dags,
            boolean includeSensitivity, boolean includeStructural) throws Exception {
        SimulationRunner runner = new SimulationRunner();
        List<Map<String, Object>> mainMatrix = new ArrayList<Map<String, Object>>();
        long startedAt = System.nanoTime();
        for (DagScenario dag : dags) {
            for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                for (DataMovementModel model : MODELS) {
                    boolean fatTree = model.isFatTreeContentionV1();
                    SimulationConfig config = baseConfig(dag, planner, model, 3);
                    PlatformProfile platform = platform(3, fatTree
                            ? NetworkTopologySpec.fatTree(4, BASELINE_LINK_BANDWIDTH_MB) : null);
                    SimulationReport report = runner.run(config, platform);
                    assertHealthy(report, "main:" + dag.id + ":" + planner + ":" + model.getKind());
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("family", dag.family);
                    record.put("scale", dag.scale);
                    record.put("planner", planner.name());
                    record.put("movementModel", model.getKind().name());
                    record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                    record.put("totalJobs", Integer.valueOf(report.getTotalJobs()));
                    mainMatrix.add(record);
                }
            }
        }
        List<Parameters.PlanningAlgorithm> sensitivePlanners = Arrays.asList(
                Parameters.PlanningAlgorithm.LOCAL_HEFT,
                Parameters.PlanningAlgorithm.LOCAL_CPOP);
        List<Map<String, Object>> sensitivity = new ArrayList<Map<String, Object>>();
        if (includeSensitivity) {
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                    for (TopologyVariant variant : SENSITIVITY_VARIANTS) {
                        SimulationConfig config = baseConfig(dag, planner, MODELS.get(2), 3);
                        SimulationReport report = runner.run(config, platform(3, variant.spec));
                        assertHealthy(report,
                                "sens:" + dag.id + ":" + planner + ":" + variant.id);
                        Map<String, Object> record = new LinkedHashMap<String, Object>();
                        record.put("dax", dag.id);
                        record.put("planner", planner.name());
                        record.put("variant", variant.id);
                        record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                        sensitivity.add(record);
                    }
                }
            }
        }
        List<Map<String, Object>> structural = new ArrayList<Map<String, Object>>();
        if (includeStructural) {
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                    for (TopologyVariant variant : structuralVariants()) {
                        SimulationConfig config = baseConfig(dag, planner, MODELS.get(2), 4);
                        SimulationReport report = runner.run(config, platform(4, variant.spec));
                        assertHealthy(report,
                                "struct:" + dag.id + ":" + planner + ":" + variant.id);
                        Map<String, Object> record = new LinkedHashMap<String, Object>();
                        record.put("dax", dag.id);
                        record.put("planner", planner.name());
                        record.put("variant", variant.id);
                        record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                        structural.add(record);
                    }
                }
            }
        }
        CampaignResults results = new CampaignResults(dags, mainMatrix, sensitivity, structural);
        System.out.println("[campaign] 运行耗时 "
                + (System.nanoTime() - startedAt) / 1_000_000_000.0 + " s");
        return results;
    }

    private static SimulationConfig baseConfig(DagScenario dag,
            Parameters.PlanningAlgorithm planner, DataMovementModel model, int vmCount) {
        return SimulationConfig
                .builder(dag.daxPath.toString(), vmCount)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(model)
                .randomSeed(FIXED_SEED)
                .build();
    }

    /** 同质平台（vmCount 主机 = vmCount VM，mips 1.0、每 VM 带宽 1 MB/s）；拓扑声明可选（R6 必需）。 */
    private static PlatformProfile platform(int vmCount, NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("campaign-platform");
        for (int id = 0; id < vmCount; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        if (topology != null) {
            builder.networkTopology(topology);
        }
        return builder.build();
    }

    private static void assertHealthy(SimulationReport report, String label) {
        if (!"COMPLETED_SUCCESSFULLY".equals(
                report.getMetrics().getLogicalTaskCompletionStatus())) {
            throw new IllegalStateException(label + ": 逻辑任务完成状态异常 "
                    + report.getMetrics().getLogicalTaskCompletionStatus());
        }
        if (report.getTotalJobs() != report.getSuccessfulJobs()) {
            throw new IllegalStateException(label + ": Job 未全部成功");
        }
        if (!(report.getMakespan() > 0.0) || !Double.isFinite(report.getMakespan())) {
            throw new IllegalStateException(label + ": makespan 非法 " + report.getMakespan());
        }
    }

    // ---- 结果聚合 ----

    /** 单个 DAG 场景（家族 + 规模 + DAX 路径）。 */
    static final class DagScenario {
        final String id;
        final String family;
        final String scale;
        final Path daxPath;

        DagScenario(String id, String family, String scale, Path daxPath) {
            this.id = id;
            this.family = family;
            this.scale = scale;
            this.daxPath = daxPath;
        }
    }

    /** 敏感性拓扑变体。 */
    static final class TopologyVariant {
        final String id;
        final NetworkTopologySpec spec;

        TopologyVariant(String id, NetworkTopologySpec spec) {
            this.id = id;
            this.spec = spec;
        }
    }

    /** campaign 完整结果：主矩阵、敏感性、结构敏感性、配对统计与排名汇总。 */
    static final class CampaignResults {
        final List<DagScenario> dags;
        final List<Map<String, Object>> mainMatrix;
        final List<Map<String, Object>> sensitivity;
        final List<Map<String, Object>> structural;

        CampaignResults(List<DagScenario> dags, List<Map<String, Object>> mainMatrix,
                List<Map<String, Object>> sensitivity, List<Map<String, Object>> structural) {
            this.dags = dags;
            this.mainMatrix = mainMatrix;
            this.sensitivity = sensitivity;
            this.structural = structural;
        }

        /** 主矩阵中 (planner, modelKind) 的逐 DAG makespan 序列（按 dags 顺序）。 */
        List<Double> makespans(String planner, String modelKind) {
            List<Double> values = new ArrayList<Double>();
            for (DagScenario dag : dags) {
                values.add(Double.valueOf(makespan(dag.id, planner, modelKind)));
            }
            return values;
        }

        double makespan(String dax, String planner, String modelKind) {
            for (Map<String, Object> record : mainMatrix) {
                if (dax.equals(record.get("dax")) && planner.equals(record.get("planner"))
                        && modelKind.equals(record.get("movementModel"))) {
                    return ((Double) record.get("makespanSeconds")).doubleValue();
                }
            }
            throw new IllegalStateException("Missing main-matrix record "
                    + dax + "/" + planner + "/" + modelKind);
        }

        double sensitivityMakespan(String dax, String planner, String variant) {
            return scanMakespan(sensitivity, dax, planner, variant, "sensitivity");
        }

        double structuralMakespan(String dax, String planner, String variant) {
            return scanMakespan(structural, dax, planner, variant, "structural");
        }

        private static double scanMakespan(List<Map<String, Object>> records, String dax,
                String planner, String variant, String block) {
            for (Map<String, Object> record : records) {
                if (dax.equals(record.get("dax")) && planner.equals(record.get("planner"))
                        && variant.equals(record.get("variant"))) {
                    return ((Double) record.get("makespanSeconds")).doubleValue();
                }
            }
            throw new IllegalStateException("Missing " + block + " record "
                    + dax + "/" + planner + "/" + variant);
        }

        /**
         * 弱单调性诊断（V1 ≤ R2 ≤ R6，逐 DAG × 规划器）。
         *
         * <p>R6 的每流速率确实受 R2 约束域的超集限制（min(端点份额, 链路份额)
         * ≤ 端点份额），但完成时刻一旦分化，并发流格局随之漂移，makespan 级
         * 弱单调性不再是定理——实测存在噪声级交叉（PSO × epigenomics-n100：
         * R6 低于 R2 约 0.0075%）。诊断如实记录每个违反与最大相对偏差，作为
         * 数据报告而非失败。</p>
         */
        List<Map<String, Object>> monotonicityDiagnostics() {
            List<Map<String, Object>> violations = new ArrayList<Map<String, Object>>();
            String v1 = DataMovementModel.preExecutionTransferDelayV1().getKind().name();
            String r2 = DataMovementModel.preExecutionTransferDelayWithContentionV1()
                    .getKind().name();
            String r6 = DataMovementModel.fatTreeContentionV1().getKind().name();
            for (DagScenario dag : dags) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    double a = makespan(dag.id, planner.name(), v1);
                    double b = makespan(dag.id, planner.name(), r2);
                    double c = makespan(dag.id, planner.name(), r6);
                    if (b >= a - 1.0e-9 && c >= b - 1.0e-9) {
                        continue;
                    }
                    Map<String, Object> record = new LinkedHashMap<String, Object>();
                    record.put("dax", dag.id);
                    record.put("planner", planner.name());
                    record.put("v1MakespanSeconds", Double.valueOf(a));
                    record.put("r2MakespanSeconds", Double.valueOf(b));
                    record.put("r6MakespanSeconds", Double.valueOf(c));
                    double maxDeviation = 0.0;
                    if (b < a) {
                        maxDeviation = Math.max(maxDeviation, (a - b) / b);
                    }
                    if (c < b) {
                        maxDeviation = Math.max(maxDeviation, (b - c) / c);
                    }
                    record.put("maxRelativeDeviation", Double.valueOf(maxDeviation));
                    violations.add(record);
                }
            }
            return violations;
        }

        /**
         * 严格弱单调性断言（V1 ≤ R2 ≤ R6）。仅适用于已实证成立的子集
         * （如 HEFT 论文例，由 {@code FatTreeCampaignGoldenIntegrationTest}
         * 使用）；全集上存在噪声级交叉，用 {@link #monotonicityDiagnostics()}。
         */
        void assertWeakMonotonicity() {
            List<Map<String, Object>> violations = monotonicityDiagnostics();
            if (!violations.isEmpty()) {
                throw new IllegalStateException("弱单调性破坏: " + violations);
            }
        }

        /** 每个模型下按平均 makespan 的规划器排名（升序）。 */
        Map<String, List<String>> averageRankings() {
            Map<String, List<String>> rankings = new LinkedHashMap<String, List<String>>();
            for (DataMovementModel model : MODELS) {
                List<String> order = new ArrayList<String>();
                List<Double> means = new ArrayList<Double>();
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    List<Double> values = makespans(planner.name(), model.getKind().name());
                    double mean = 0.0;
                    for (Double value : values) {
                        mean += value.doubleValue();
                    }
                    mean /= values.size();
                    int position = 0;
                    while (position < order.size()
                            && means.get(position).doubleValue() <= mean) {
                        position++;
                    }
                    order.add(position, planner.name());
                    means.add(position, Double.valueOf(mean));
                }
                rankings.put(model.getKind().name(), order);
            }
            return rankings;
        }

        /** 逐 DAG 逐模型的第一名（makespan 最小规划器）。 */
        Map<String, Map<String, String>> perDagWinners() {
            Map<String, Map<String, String>> winners = new LinkedHashMap<String, Map<String, String>>();
            for (DataMovementModel model : MODELS) {
                Map<String, String> perDag = new LinkedHashMap<String, String>();
                for (DagScenario dag : dags) {
                    String best = null;
                    double bestMakespan = Double.MAX_VALUE;
                    for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                        double value = makespan(dag.id, planner.name(), model.getKind().name());
                        if (value < bestMakespan) {
                            bestMakespan = value;
                            best = planner.name();
                        }
                    }
                    perDag.put(dag.id, best);
                }
                winners.put(model.getKind().name(), perDag);
            }
            return winners;
        }

        /** 同模型内规划器对比（基线 LOCAL_HEFT）+ 跨模型对比（基线 V1），全部 DAG 配对。 */
        List<Map<String, Object>> pairedStatistics() {
            List<Map<String, Object>> statistics = new ArrayList<Map<String, Object>>();
            String v1 = DataMovementModel.preExecutionTransferDelayV1().getKind().name();
            String r2 = DataMovementModel.preExecutionTransferDelayWithContentionV1()
                    .getKind().name();
            String r6 = DataMovementModel.fatTreeContentionV1().getKind().name();
            String baselinePlanner = Parameters.PlanningAlgorithm.LOCAL_HEFT.name();
            for (DataMovementModel model : MODELS) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    if (planner.name().equals(baselinePlanner)) {
                        continue;
                    }
                    statistics.add(statRecord("planner@" + model.getKind().name(),
                            baselinePlanner, planner.name(),
                            makespans(baselinePlanner, model.getKind().name()),
                            makespans(planner.name(), model.getKind().name())));
                }
            }
            for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                statistics.add(statRecord("model-r2-vs-v1@" + planner.name(),
                        v1, r2, makespans(planner.name(), v1), makespans(planner.name(), r2)));
                statistics.add(statRecord("model-r6-vs-r2@" + planner.name(),
                        r2, r6, makespans(planner.name(), r2), makespans(planner.name(), r6)));
                statistics.add(statRecord("model-r6-vs-v1@" + planner.name(),
                        v1, r6, makespans(planner.name(), v1), makespans(planner.name(), r6)));
            }
            return statistics;
        }

        /** 敏感性对比：每个变体 vs 基线（同规划器，R6 模型，DAG 配对）。 */
        List<Map<String, Object>> sensitivityStatistics() {
            return scanStatistics(SENSITIVITY_VARIANTS, "sensitivity");
        }

        /** 4 主机结构敏感性对比：每个变体 vs 基线（同规划器，R6 模型，DAG 配对）。 */
        List<Map<String, Object>> structuralStatistics() {
            return scanStatistics(structuralVariants(), "structural");
        }

        private List<Map<String, Object>> scanStatistics(
                List<TopologyVariant> variants, String block) {
            List<Map<String, Object>> statistics = new ArrayList<Map<String, Object>>();
            List<Parameters.PlanningAlgorithm> sensitivePlanners = Arrays.asList(
                    Parameters.PlanningAlgorithm.LOCAL_HEFT,
                    Parameters.PlanningAlgorithm.LOCAL_CPOP);
            for (Parameters.PlanningAlgorithm planner : sensitivePlanners) {
                for (TopologyVariant variant : variants) {
                    if (variant.id.equals("baseline-k4-full")) {
                        continue;
                    }
                    List<Double> baseline = new ArrayList<Double>();
                    List<Double> candidate = new ArrayList<Double>();
                    for (DagScenario dag : dags) {
                        baseline.add(Double.valueOf(scanMakespan(
                                "sensitivity".equals(block) ? sensitivity : structural,
                                dag.id, planner.name(), "baseline-k4-full", block)));
                        candidate.add(Double.valueOf(scanMakespan(
                                "sensitivity".equals(block) ? sensitivity : structural,
                                dag.id, planner.name(), variant.id, block)));
                    }
                    statistics.add(statRecord(block + "@" + planner.name(),
                            "baseline-k4-full", variant.id, baseline, candidate));
                }
            }
            return statistics;
        }

        private static Map<String, Object> statRecord(String axis, String baselineLabel,
                String candidateLabel, List<Double> baseline, List<Double> candidate) {
            PairedWilcoxonSignificance significance =
                    PairedWilcoxonSignificance.evaluate(baseline, candidate);
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("axis", axis);
            record.put("baseline", baselineLabel);
            record.put("candidate", candidateLabel);
            record.put("pairedSampleCount", Integer.valueOf(significance.getPairedSampleCount()));
            record.put("effectiveSampleCount", Integer.valueOf(significance.getEffectiveSampleCount()));
            record.put("medianDifferenceSeconds", significance.getMedianDifference());
            record.put("status", significance.getStatus());
            record.put("pValue", significance.getPValue());
            record.put("significantAtAlpha005",
                    Boolean.valueOf(significance.isSignificantAtDefaultAlpha()));
            return record;
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<String, Object>();
            json.put("schema", RESULTS_SCHEMA);
            json.put("generatedAt", java.time.OffsetDateTime.now().toString());
            json.put("fixedSeed", Long.valueOf(FIXED_SEED));
            json.put("vmCount", Integer.valueOf(3));
            json.put("planners", Arrays.asList(
                    Parameters.PlanningAlgorithm.LOCAL_HEFT.name(),
                    Parameters.PlanningAlgorithm.LOCAL_CPOP.name(),
                    Parameters.PlanningAlgorithm.RANDOM.name(),
                    Parameters.PlanningAlgorithm.PSO.name()));
            json.put("movementModels", Arrays.asList(
                    MODELS.get(0).getKind().name(),
                    MODELS.get(1).getKind().name(),
                    MODELS.get(2).getKind().name()));
            json.put("mainMatrix", mainMatrix);
            json.put("sensitivity", sensitivity);
            json.put("structuralSensitivity", structural);
            json.put("weakMonotonicityViolations", monotonicityDiagnostics());
            json.put("averageRankings", averageRankings());
            json.put("perDagWinners", perDagWinners());
            json.put("pairedStatistics", pairedStatistics());
            if (!sensitivity.isEmpty()) {
                json.put("sensitivityStatistics", sensitivityStatistics());
            }
            if (!structural.isEmpty()) {
                json.put("structuralSensitivityStatistics", structuralStatistics());
            }
            return json;
        }

        String toMarkdown() {
            StringBuilder md = new StringBuilder();
            md.append("# Fat-tree × 调度 campaign 实测结果（自动生成）\n\n");
            md.append("- seed=").append(FIXED_SEED).append("，3 主机同质平台，")
                    .append(dags.size()).append(" 个 DAG\n");
            md.append("- 主矩阵 ").append(mainMatrix.size()).append(" 次运行；3 主机敏感性 ")
                    .append(sensitivity.size()).append(" 次运行；4 主机结构敏感性 ")
                    .append(structural.size()).append(" 次运行\n\n");
            md.append("## 主矩阵 makespan（秒）\n\n");
            md.append("| DAG |");
            for (DataMovementModel model : MODELS) {
                for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                    md.append(' ').append(shortPlanner(planner)).append(' ')
                            .append(shortModel(model)).append(" |");
                }
            }
            md.append('\n').append("| --- |");
            for (int i = 0; i < MODELS.size() * PLANNERS.size(); i++) {
                md.append(" --- |");
            }
            md.append('\n');
            for (DagScenario dag : dags) {
                md.append("| ").append(dag.id).append(" |");
                for (DataMovementModel model : MODELS) {
                    for (Parameters.PlanningAlgorithm planner : PLANNERS) {
                        md.append(' ')
                                .append(format(makespan(dag.id, planner.name(),
                                        model.getKind().name())))
                                .append(" |");
                    }
                }
                md.append('\n');
            }
            md.append("\n## 弱单调性诊断（V1 ≤ R2 ≤ R6）\n\n");
            List<Map<String, Object>> violations = monotonicityDiagnostics();
            if (violations.isEmpty()) {
                md.append("无违反。\n");
            } else {
                md.append("存在 ").append(violations.size())
                        .append(" 处跨模型次序交叉；以下如实报告幅度，原因需逐场景分析。跨模型单调不是一般定理：\n\n");
                md.append("| DAG | 规划器 | V1 | R2 | R6 | 最大相对偏差 |\n");
                md.append("| --- | --- | --- | --- | --- | --- |\n");
                for (Map<String, Object> record : violations) {
                    md.append("| ").append(record.get("dax"))
                            .append(" | ").append(record.get("planner"))
                            .append(" | ").append(format((Double) record.get("v1MakespanSeconds")))
                            .append(" | ").append(format((Double) record.get("r2MakespanSeconds")))
                            .append(" | ").append(format((Double) record.get("r6MakespanSeconds")))
                            .append(" | ").append(format((Double) record.get("maxRelativeDeviation")))
                            .append(" |\n");
                }
            }
            md.append("\n## 平均排名（makespan 升序）\n\n");
            for (Map.Entry<String, List<String>> entry : averageRankings().entrySet()) {
                md.append("- ").append(entry.getKey()).append(": ")
                        .append(String.join(" < ", entry.getValue())).append('\n');
            }
            md.append("\n## 逐 DAG 第一名\n\n| DAG |");
            for (DataMovementModel model : MODELS) {
                md.append(' ').append(shortModel(model)).append(" |");
            }
            md.append("\n| --- | --- | --- | --- |\n");
            Map<String, Map<String, String>> winners = perDagWinners();
            for (DagScenario dag : dags) {
                md.append("| ").append(dag.id).append(" |");
                for (DataMovementModel model : MODELS) {
                    md.append(' ').append(shortPlanner(Parameters.PlanningAlgorithm.valueOf(
                            winners.get(model.getKind().name()).get(dag.id)))).append(" |");
                }
                md.append('\n');
            }
            md.append("\n## 配对 Wilcoxon（跨 DAG）\n\n");
            md.append("| 轴 | 基线 | 候选 | n(有效) | 中位差(s) | 状态 | p | 显著 |\n");
            md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
            appendStatRows(md, pairedStatistics());
            if (!sensitivity.isEmpty()) {
                md.append("\n## 敏感性配对 Wilcoxon（3 主机，变体 vs 基线拓扑）\n\n");
                md.append("| 轴 | 基线 | 候选 | n(有效) | 中位差(s) | 状态 | p | 显著 |\n");
                md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                appendStatRows(md, sensitivityStatistics());
            }
            if (!structural.isEmpty()) {
                md.append("\n## 4 主机结构敏感性 makespan（秒，R6 模型）\n\n");
                md.append("| DAG | 规划器 |");
                for (TopologyVariant variant : structuralVariants()) {
                    md.append(' ').append(variant.id).append(" |");
                }
                md.append('\n').append("| --- | --- |");
                for (int i = 0; i < structuralVariants().size(); i++) {
                    md.append(" --- |");
                }
                md.append('\n');
                for (DagScenario dag : dags) {
                    for (Parameters.PlanningAlgorithm planner : Arrays.asList(
                            Parameters.PlanningAlgorithm.LOCAL_HEFT,
                            Parameters.PlanningAlgorithm.LOCAL_CPOP)) {
                        md.append("| ").append(dag.id)
                                .append(" | ").append(shortPlanner(planner)).append(" |");
                        for (TopologyVariant variant : structuralVariants()) {
                            md.append(' ')
                                    .append(format(structuralMakespan(
                                            dag.id, planner.name(), variant.id)))
                                    .append(" |");
                        }
                        md.append('\n');
                    }
                }
                md.append("\n## 结构敏感性配对 Wilcoxon（4 主机，变体 vs 基线拓扑）\n\n");
                md.append("| 轴 | 基线 | 候选 | n(有效) | 中位差(s) | 状态 | p | 显著 |\n");
                md.append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
                appendStatRows(md, structuralStatistics());
            }
            return md.toString();
        }

        private static void appendStatRows(StringBuilder md, List<Map<String, Object>> stats) {
            for (Map<String, Object> record : stats) {
                md.append("| ").append(record.get("axis"))
                        .append(" | ").append(record.get("baseline"))
                        .append(" | ").append(record.get("candidate"))
                        .append(" | ").append(record.get("pairedSampleCount"))
                        .append('(').append(record.get("effectiveSampleCount")).append(')')
                        .append(" | ").append(format((Double) record.get("medianDifferenceSeconds")))
                        .append(" | ").append(record.get("status"))
                        .append(" | ").append(format((Double) record.get("pValue")))
                        .append(" | ").append(record.get("significantAtAlpha005"))
                        .append(" |\n");
            }
        }

        private static String format(Double value) {
            return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
        }

        private static String shortPlanner(Parameters.PlanningAlgorithm planner) {
            switch (planner) {
                case LOCAL_HEFT: return "HEFT";
                case LOCAL_CPOP: return "CPOP";
                case RANDOM: return "RAND";
                case PSO: return "PSO";
                default: return planner.name();
            }
        }

        private static String shortModel(DataMovementModel model) {
            if (model.isFatTreeContentionV1()) {
                return "R6";
            }
            String kind = model.getKind().name();
            return kind.endsWith("CONTENTION_V1") && !kind.contains("FAT_TREE") ? "R2" : "V1";
        }
    }
}
