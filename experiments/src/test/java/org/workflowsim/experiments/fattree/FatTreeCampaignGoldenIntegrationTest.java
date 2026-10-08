package org.workflowsim.experiments.fattree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.Parameters;

/**
 * 当前模型下的 campaign 抽样数值、重复运行和结构性契约回归。
 *
 * <p>装置使用 DAX 归一化工作量、无显式成本矩阵、seed 91 和声明的 3/4 主机平台；
 * 不与 simulator 的论文成本矩阵夹具混用。以下数值、排名、单调和恒等断言仅适用于
 * 各测试的输入及参数，不能推出所有 DAG 的不变量或完整历史研究的新排名。</p>
 *
 * <p>历史参数与保留结果来源见
 * {@code experiments/studies/fattree-scheduling-campaign/RETENTION.md}。
 * 本测试维护当前回归值，不覆盖该目录中的历史数值工件。</p>
 */
class FatTreeCampaignGoldenIntegrationTest {

    private static final String V1 =
            DataMovementModel.preExecutionTransferDelayV1().getKind().name();
    private static final String R2 =
            DataMovementModel.preExecutionTransferDelayWithContentionV1().getKind().name();
    private static final String R6 =
            DataMovementModel.fatTreeContentionV1().getKind().name();
    private static final String HEFT = Parameters.PlanningAlgorithm.LOCAL_HEFT.name();
    private static final String CPOP = Parameters.PlanningAlgorithm.LOCAL_CPOP.name();
    private static final String RANDOM = Parameters.PlanningAlgorithm.RANDOM.name();
    private static final String PSO = Parameters.PlanningAlgorithm.PSO.name();
    private static final String HEFT_EXAMPLE = "heft-paper-example";
    private static final String CYBERSHAKE_N50 = "cybershake-n50";
    private static final String CYBERSHAKE_N100 = "cybershake-n100";
    private static final String BASELINE = "baseline-k4-full";
    private static final String A4 = "A4-k4-full-link10x";

    /** 论文例 12 组合黄金 makespan（秒，campaign 配置）。 */
    @Test
    void paperExampleGoldenMakespansMatchCampaignConfiguration() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, V1, 5123.1);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, R2, 5195.1);
        assertMakespan(results, HEFT_EXAMPLE, HEFT, R6, 5738.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, V1, 5141.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R2, 5212.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R6, 5902.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, V1, 6159.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R2, 6281.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R6, 7206.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, V1, 6132.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R2, 6254.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R6, 7262.1);
        // R10沿用R8的0.125/1.25 MB/s参数；CPOP和争用列按修正后的实际执行锁定。
    }

    /** 争用把 cybershake 的逐 DAG 第一名翻转为 PSO；论文例保持 HEFT。 */
    @Test
    void contentionFlipsCybershakeWinnersToPso() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        Map<String, Map<String, String>> winners = results.perDagWinners();
        assertEquals(HEFT, winners.get(V1).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N50));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N50));
        // The R10-era core chose PSO here. Isolated old/new core controls show that
        // time-aware LOCAL data planning changes HEFT's mapping/schedule and restores
        // a V1-to-contention change of winner. Do not preserve a bug-dependent ranking.
        assertEquals(HEFT, winners.get(V1).get(CYBERSHAKE_N100));
        assertMakespan(results, CYBERSHAKE_N100, HEFT, V1, 1111837.479876);
        assertMakespan(results, CYBERSHAKE_N100, PSO, V1, 1112378.121047);
        assertTrue(results.makespan(CYBERSHAKE_N100, HEFT, V1)
                < results.makespan(CYBERSHAKE_N100, PSO, V1));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N100));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N100));
        for (String model : new String[] {V1, R2, R6}) {
            assertEquals(HEFT, winners.get(model).get(HEFT_EXAMPLE),
                    model + ": 论文例第一名应始终为 LOCAL_HEFT");
        }
        // 黄金 makespan（翻转的直接数值证据，秒）。再标定后 N50 PSO 的 R6 恢复
        // 束缚链路的小幅减速（587921.6099 &lt; R2 587924.0247，噪声级交叉已入
        // 弱单调诊断）；N100 PSO 的 R6 恰与 R2 相等（链路层对该映射无附加约束）。
        assertMakespan(results, CYBERSHAKE_N50, PSO, R2, 587924.0247);
        assertMakespan(results, CYBERSHAKE_N50, PSO, R6, 587921.1503);
        assertMakespan(results, CYBERSHAKE_N50, HEFT, R2, 588010.008326);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R2, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R6, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, CPOP, V1, 1155577.5672);
    }

    /** 弱单调性：论文例子集严格成立；子集内交叉必须为噪声级。 */
    @Test
    void weakMonotonicityStrictOnPaperExampleAndNoiseLevelElsewhere() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, false);
        for (String planner : new String[] {HEFT, CPOP, RANDOM, PSO}) {
            double a = results.makespan(HEFT_EXAMPLE, planner, V1);
            double b = results.makespan(HEFT_EXAMPLE, planner, R2);
            double c = results.makespan(HEFT_EXAMPLE, planner, R6);
            assertTrue(b >= a && c >= b,
                    planner + ": 论文例上必须严格弱单调 " + a + "/" + b + "/" + c);
        }
        for (Map<String, Object> violation : results.monotonicityDiagnostics()) {
            double deviation = ((Double) violation.get("maxRelativeDeviation")).doubleValue();
            assertTrue(deviation <= 1.0e-4,
                    "子集内弱单调性交叉必须为噪声级（≤1e-4 相对偏差）: " + violation);
        }
    }

    /** 确定性：同配置双跑，主矩阵记录逐位一致。 */
    @Test
    void subsetRunsAreDeterministic() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults first = runSubset(false, false);
        FatTreeSchedulingCampaignExecutor.CampaignResults second = runSubset(false, false);
        assertEquals(first.mainMatrix, second.mainMatrix, "双跑主矩阵必须逐位一致");
    }

    /**
     * 3 主机敏感性：基线 ≡ 主矩阵 R6；结构轴（A1/A2/A3/A5）实测恒等
     * （3 主机平台任意并发流必共享端点主机——物理退化，非链路参数效应）；
     * 带宽比轴 A4（链路 1.25 MB/s &gt; 端点 1 MB/s，非束缚）精确收敛回
     * 主矩阵 R2——再标定后该轴恢复"链路远宽于端点时收敛回端点主导"的设计语义。
     */
    @Test
    void sensitivityLocksDegenerateStructureAxesAndConvergingBandwidthAxis() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(true, false);
        assertEquals(36, results.mainMatrix.size(), "主矩阵记录数 = 3 DAG × 12 组合");
        assertEquals(12, results.sensitivity.size(),
                "敏感性记录数 = 2 规划器 × 6 拓扑变体（仅论文例）");
        for (String planner : new String[] {HEFT, CPOP}) {
            double baseline = results.sensitivityMakespan(HEFT_EXAMPLE, planner, BASELINE);
            assertEquals(results.makespan(HEFT_EXAMPLE, planner, R6), baseline, 1.0e-9,
                    planner + ": 敏感性基线拓扑必须与主矩阵 R6 运行恒等");
            for (String variant : new String[] {"A1-k4-oversub2x", "A2-k8-full",
                    "A3-k8-oversub2x", "A5-k4-full-same-edge-pair"}) {
                assertEquals(baseline,
                        results.sensitivityMakespan(HEFT_EXAMPLE, planner, variant), 1.0e-9,
                        planner + " × " + variant + ": 3 主机下结构轴应退化恒等");
            }
            assertEquals(results.makespan(HEFT_EXAMPLE, planner, R2),
                    results.sensitivityMakespan(HEFT_EXAMPLE, planner, A4), 1.0e-9,
                    planner + ": A4（链路 10×）应精确收敛回主矩阵 R2 值");
        }
    }

    /**
     * 4 主机结构块：结构轴（A1/A2/A3/A5）恒等——交叉流从未并发经过差异链路；
     * 带宽轴 A4（链路 1.25 MB/s 非束缚）严格降低 makespan，与 simulator 慢链路
     * 探针（FatTreeContentionIntegrationTest，0.25 MB/s → 588.1 &gt; 284.1）共同
     * 证明链路争用模型在束缚链路下正确生效。R8 再标定后该契约恢复原设计形态。
     */
    @Test
    void structuralBlockLocksFourHostDegeneracyAndBandwidthEffect() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, true);
        assertEquals(12, results.structural.size(),
                "结构敏感性记录数 = 2 规划器 × 6 拓扑变体（仅论文例）");
        for (String planner : new String[] {HEFT, CPOP}) {
            double baseline = results.structuralMakespan(HEFT_EXAMPLE, planner, BASELINE);
            for (String variant : new String[] {"A1-k4-oversub2x", "A2-k8-full",
                    "A3-k8-oversub2x", "A5-k4-full-same-edge-pairs"}) {
                assertEquals(baseline,
                        results.structuralMakespan(HEFT_EXAMPLE, planner, variant), 1.0e-9,
                        planner + " × " + variant + ": 4 主机下结构轴仍恒等");
            }
            assertTrue(results.structuralMakespan(HEFT_EXAMPLE, planner, A4) < baseline,
                    planner + ": A4（链路 1.25 MB/s 非束缚）应严格降低 makespan");
        }
        assertEquals(5718.1, results.structuralMakespan(HEFT_EXAMPLE, HEFT, BASELINE), 1.0e-6);
        assertEquals(5718.1, results.structuralMakespan(HEFT_EXAMPLE, CPOP, BASELINE), 1.0e-6);
        assertEquals(5186.1, results.structuralMakespan(HEFT_EXAMPLE, HEFT, A4), 1.0e-6);
        assertEquals(5186.1, results.structuralMakespan(HEFT_EXAMPLE, CPOP, A4), 1.0e-6);
    }

    /** 敏感性/结构块仅跑论文例（其余 DAG 由全量 campaign 覆盖），主矩阵跑子集。 */
    private static FatTreeSchedulingCampaignExecutor.CampaignResults runSubset(
            boolean includeSensitivity, boolean includeStructural) throws Exception {
        Set<String> wanted = new HashSet<String>(Arrays.asList(
                HEFT_EXAMPLE, CYBERSHAKE_N50, CYBERSHAKE_N100));
        List<FatTreeSchedulingCampaignExecutor.DagScenario> subset =
                new ArrayList<FatTreeSchedulingCampaignExecutor.DagScenario>();
        List<FatTreeSchedulingCampaignExecutor.DagScenario> sensitivitySubset =
                new ArrayList<FatTreeSchedulingCampaignExecutor.DagScenario>();
        for (FatTreeSchedulingCampaignExecutor.DagScenario dag
                : FatTreeSchedulingCampaignExecutor.discoverCoreDags(datasetsRoot())) {
            if (wanted.contains(dag.id)) {
                subset.add(dag);
            }
            if (HEFT_EXAMPLE.equals(dag.id)) {
                sensitivitySubset.add(dag);
            }
        }
        assertEquals(3, subset.size(), "子集必须包含论文例 + cybershake-n50/n100");
        assertEquals(1, sensitivitySubset.size(), "核心 DAG 集必须包含 HEFT 论文例");
        FatTreeSchedulingCampaignExecutor.CampaignResults main =
                FatTreeSchedulingCampaignExecutor.runCampaign(
                        datasetsRoot(), subset, false, false);
        if (includeSensitivity) {
            FatTreeSchedulingCampaignExecutor.CampaignResults withSensitivity =
                    FatTreeSchedulingCampaignExecutor.runCampaign(
                            datasetsRoot(), sensitivitySubset, true, false);
            main.sensitivity.clear();
            main.sensitivity.addAll(withSensitivity.sensitivity);
        }
        if (includeStructural) {
            FatTreeSchedulingCampaignExecutor.CampaignResults withStructural =
                    FatTreeSchedulingCampaignExecutor.runCampaign(
                            datasetsRoot(), sensitivitySubset, false, true);
            main.structural.clear();
            main.structural.addAll(withStructural.structural);
        }
        return main;
    }

    private static void assertMakespan(
            FatTreeSchedulingCampaignExecutor.CampaignResults results,
            String dax, String planner, String modelKind, double expected) {
        assertEquals(expected, results.makespan(dax, planner, modelKind),
                expected > 1000.0 ? 1.0e-3 : 1.0e-6,
                dax + " × " + planner + " × " + modelKind + " 黄金 makespan");
    }

    private static java.nio.file.Path datasetsRoot() {
        return Paths.get("..", "datasets").toAbsolutePath().normalize();
    }
}
