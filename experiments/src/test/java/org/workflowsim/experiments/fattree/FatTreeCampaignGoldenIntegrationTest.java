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
 * campaign 黄金值与结构性契约（HEFT 论文例 + cybershake 翻转子集）。
 *
 * <p>锁定内容（campaign 配置 = 无显式成本矩阵、成本由 DAX runtime × VM MIPS
 * 推导、3 主机同质平台、seed 91；与 simulator 探测 IT 的论文成本矩阵 fixture
 * 是不同实验装置，黄金值不可互换）：
 * <ul>
 * <li>论文例 12 组合（4 规划器 × 3 模型）实测黄金 makespan；</li>
 * <li>逐 DAG 排名翻转事实：cybershake-n50 V1 第一名 LOCAL_HEFT → R2/R6
 * LOCAL_PSO；cybershake-n100 V1 LOCAL_CPOP → R2/R6 PSO；论文例三模型下
 * LOCAL_HEFT 始终第一（campaign 配置下平均排名不翻转，翻转出现在逐 DAG
 * 粒度——这是结果文档的核心结论之一）；</li>
 * <li>弱单调性诊断：论文例子集严格成立（R8 审计后 R6 ≡ R2，c==b 情形）；子集内
 * 任何交叉必须为噪声级（相对偏差 ≤ 1e-4）；</li>
 * <li>确定性：同配置双跑主矩阵逐位一致；</li>
 * <li>3 主机敏感性：基线拓扑 ≡ 主矩阵 R6；全部拓扑变体（结构轴 + 带宽轴 A4）
 * 实测恒等并收敛于 R2——R8 审计（F1 单位修复）后 campaign 声明链路带宽 ≥ 端点
 * 带宽，链路层永不束缚流；</li>
 * <li>4 主机结构块：全部变体（含 A4）仍恒等——"模型确实生效"的判别证据由
 * simulator 慢链路探针（FatTreeContentionIntegrationTest，0.25 MB/s）承担。</li>
 * </ul></p>
 *
 * <p>黄金值来源：R8 审计重录 campaign 正式运行（2026-09-16，
 * experiments/studies/fattree-scheduling-campaign 工件，F1/F2 修复后）。修复前
 * R6 列黄金值（5738.1 等）产自链路被 ÷8 单位 bug 压到端点 1/8 的旧物理。</p>
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
        assertMakespan(results, HEFT_EXAMPLE, HEFT, R6, 5195.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, V1, 5145.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R2, 5205.1);
        assertMakespan(results, HEFT_EXAMPLE, CPOP, R6, 5205.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, V1, 6159.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R2, 6281.1);
        assertMakespan(results, HEFT_EXAMPLE, RANDOM, R6, 6281.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, V1, 6132.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R2, 6254.1);
        assertMakespan(results, HEFT_EXAMPLE, PSO, R6, 6254.1);
        // R8 审计（F1 单位修复）后 R6 与 R2 逐位相等：campaign 声明链路带宽
        // 1 MB/s == VM 端点带宽，链路层永不束缚单流。修复前同一声明值链路只有
        // 端点的 1/8（÷8 单位 bug），R6 列曾为 5738.1/5854.1/7206.1/7262.1。
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
        assertEquals(CPOP, winners.get(V1).get(CYBERSHAKE_N100));
        assertEquals(PSO, winners.get(R2).get(CYBERSHAKE_N100));
        assertEquals(PSO, winners.get(R6).get(CYBERSHAKE_N100));
        for (String model : new String[] {V1, R2, R6}) {
            assertEquals(HEFT, winners.get(model).get(HEFT_EXAMPLE),
                    model + ": 论文例第一名应始终为 LOCAL_HEFT");
        }
        // 黄金 makespan（翻转的直接数值证据，秒）。R8 审计后 N50 PSO 的 R6 与
        // R2 逐位相等（链路=端点带宽时链路层无附加争用；修复前 R6 为 587921.6099）。
        assertMakespan(results, CYBERSHAKE_N50, PSO, R2, 587924.0247);
        assertMakespan(results, CYBERSHAKE_N50, PSO, R6, 587924.0247);
        assertMakespan(results, CYBERSHAKE_N50, HEFT, R2, 588230.4408);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R2, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, PSO, R6, 1189487.4983);
        assertMakespan(results, CYBERSHAKE_N100, CPOP, V1, 1112323.2714);
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
     * 3 主机敏感性：基线 ≡ 主矩阵 R6；全部拓扑变体（A1/A2/A3/A5 与带宽轴 A4）
     * 实测恒等且收敛于主矩阵 R2——R8 审计后链路 ≥ 端点带宽，链路层退化，
     * "A4 收敛回 R2"从带宽效应退化为全轴恒等的特例。
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
     * 4 主机结构块：R8 审计（F1 单位修复）后全部拓扑变体（含带宽轴 A4）实测
     * 恒等——campaign 声明链路带宽 ≥ 端点带宽，链路层永不束缚任何流，结构轴
     * 与带宽轴同时退化。"模型确实生效"的判别证据由 simulator 侧慢链路探针承担
     * （FatTreeContentionIntegrationTest：链路 0.25 MB/s &lt; 端点时 makespan
     * 588.1 &gt; R2 284.1）。修复前 A4（链路 10×）曾严格降低 makespan
     * （5718.1→5186.1 / 5574.1→5168.1），那是链路被 ÷8 后成为瓶颈的产物。
     */
    @Test
    void structuralBlockLocksFourHostFullDegeneracyAtSymmetricProvisioning() throws Exception {
        Log.disable();
        FatTreeSchedulingCampaignExecutor.CampaignResults results = runSubset(false, true);
        assertEquals(12, results.structural.size(),
                "结构敏感性记录数 = 2 规划器 × 6 拓扑变体（仅论文例）");
        for (String planner : new String[] {HEFT, CPOP}) {
            double baseline = results.structuralMakespan(HEFT_EXAMPLE, planner, BASELINE);
            for (String variant : new String[] {"A1-k4-oversub2x", "A2-k8-full",
                    "A3-k8-oversub2x", "A5-k4-full-same-edge-pairs", A4}) {
                assertEquals(baseline,
                        results.structuralMakespan(HEFT_EXAMPLE, planner, variant), 1.0e-9,
                        planner + " × " + variant
                                + ": 链路≥端点带宽时全部拓扑变体（含 A4）必须恒等");
            }
        }
        assertEquals(5186.1, results.structuralMakespan(HEFT_EXAMPLE, HEFT, BASELINE), 1.0e-6);
        assertEquals(5168.1, results.structuralMakespan(HEFT_EXAMPLE, CPOP, BASELINE), 1.0e-6);
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
