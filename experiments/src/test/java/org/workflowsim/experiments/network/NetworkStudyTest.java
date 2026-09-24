package org.workflowsim.experiments.network;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import static org.junit.jupiter.api.Assertions.*;

class NetworkStudyTest {
    @Test void protocolQualifiesInputsAndAvoidsDeterministicPseudoReplication(@TempDir Path output) throws Exception {
        NetworkStudyPlan full = NetworkStudyPlan.create("full", datasets(), output.resolve("full"));
        assertEquals(7, full.getWorkflows().size());
        assertEquals(Arrays.asList(4, 16), full.getVmCounts());
        assertEquals(504, full.getRunCount());
        assertTrue(full.asMap().get("excludedInputs").toString().contains("inspiral-1000"));
        assertTrue(full.asMap().get("excludedInputs").toString().contains("CONFLICTING_FILE_SIZE"));
        assertEquals(1, full.seeds(Parameters.PlanningAlgorithm.LOCAL_CPOP).size());
        assertEquals(5, full.seeds(Parameters.PlanningAlgorithm.PSO).size());
        assertEquals(36, NetworkStudyPlan.create("smoke", datasets(), output.resolve("small")).getRunCount());
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.create("unknown", datasets(), output));
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.layeredWorkflow(1));
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.movement("unknown"));
    }

    @Test void peftComparisonVariantReusesFrozenMatrixWithThreeDeterministicPlanners(@TempDir Path output) throws Exception {
        NetworkStudyPlan full = NetworkStudyPlan.create("full", true, datasets(), output.resolve("peft-full"));
        assertEquals(NetworkStudyPlan.PEFT_COMPARISON_PROTOCOL, full.getProtocol());
        assertEquals("peft-comparison-r12-v1", full.getProtocol());
        assertEquals(7, full.getWorkflows().size());
        assertEquals(Arrays.asList(4, 16), full.getVmCounts());
        assertEquals(126, full.getRunCount());
        assertEquals(Arrays.asList(Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.LOCAL_CPOP,
                Parameters.PlanningAlgorithm.LOCAL_PEFT), full.getPlanners());
        assertEquals(1, full.seeds(Parameters.PlanningAlgorithm.LOCAL_PEFT).size());
        assertEquals(11L, full.seeds(Parameters.PlanningAlgorithm.LOCAL_PEFT).get(0));
        Map<String, Object> protocol = full.asMap();
        assertEquals("peft-comparison-r12-v1", protocol.get("protocol"));
        assertTrue(protocol.get("inference").toString().contains("HOLM_TWO_PLANNERS"));
        assertEquals(18, NetworkStudyPlan.create("smoke", true, datasets(), output.resolve("peft-small")).getRunCount());
        // r10 协议保持冻结：默认变体的常量与运行数不受 S5 影响
        NetworkStudyPlan r10 = NetworkStudyPlan.create("full", datasets(), output.resolve("r10"));
        assertEquals(NetworkStudyPlan.PROTOCOL, r10.getProtocol());
        assertEquals(504, r10.getRunCount());
        assertTrue(r10.asMap().get("inference").toString().contains("HOLM_THREE_PLANNERS"));
    }

    @Test void sensitivityVariantDeclaresResponseSurfaceMatrix(@TempDir Path output) throws Exception {
        NetworkStudyPlan full = NetworkStudyPlan.create("full", NetworkStudyPlan.StudyVariant.SENSITIVITY_R13,
                datasets(), output.resolve("r13-full"));
        assertEquals("sensitivity-response-r13-v1", full.getProtocol());
        assertEquals(NetworkStudyPlan.SENSITIVITY_PROTOCOL, full.getProtocol());
        assertEquals(7, full.getWorkflows().size());
        assertEquals(Arrays.asList(4, 8, 16, 32), full.getVmCounts());
        assertEquals(5, full.getNetworks().size());
        // 主块 4 VM数 × 5 网络 + 异构块 2 VM数 × 3 异构等级 = 26 条件
        assertEquals(26, full.getConditions().size());
        assertEquals(546, full.getRunCount());
        assertEquals(3, full.getPlanners().size());
        assertEquals(11L, full.seeds(Parameters.PlanningAlgorithm.LOCAL_PEFT).get(0));
        Map<String, Object> protocol = full.asMap();
        assertTrue(protocol.get("inference").toString().contains("HOLM_TWO_PLANNERS"));
        assertTrue(protocol.containsKey("conditions"));
        assertTrue(protocol.containsKey("heterogeneityMipsPatterns"));
        assertEquals(0.5, ((Number) protocol.get("midLinkMbPerSecond")).doubleValue(), 1e-12);
        assertEquals(5.0, ((Number) protocol.get("fastLinkMbPerSecond")).doubleValue(), 1e-12);
        assertEquals(26, ((List<?>) protocol.get("conditions")).size());
        NetworkStudyPlan smoke = NetworkStudyPlan.create("smoke", NetworkStudyPlan.StudyVariant.SENSITIVITY_R13,
                datasets(), output.resolve("r13-smoke"));
        assertEquals(5, smoke.getConditions().size());
        assertEquals(30, smoke.getRunCount());
        assertTrue(smoke.asMap().containsKey("conditions"));
        // R13 不影响冻结的 r10/S5 矩阵
        assertEquals(504, NetworkStudyPlan.create("full", datasets(), output.resolve("r10")).getRunCount());
        assertEquals(126, NetworkStudyPlan.create("full", true, datasets(), output.resolve("s5")).getRunCount());
        assertFalse(NetworkStudyPlan.create("full", datasets(), output.resolve("r10b")).asMap().containsKey("conditions"));
        assertFalse(NetworkStudyPlan.create("full", true, datasets(), output.resolve("s5b")).asMap().containsKey("conditions"));
        assertEquals(NetworkStudyPlan.StudyVariant.R10, NetworkStudyPlan.variantArgument(null));
        assertEquals(NetworkStudyPlan.StudyVariant.PEFT_COMPARISON, NetworkStudyPlan.variantArgument("peft-comparison"));
        assertEquals(NetworkStudyPlan.StudyVariant.SENSITIVITY_R13, NetworkStudyPlan.variantArgument("sensitivity-r13"));
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.variantArgument("unknown"));
    }

    @Test void heterogeneousPlatformsUseDeclaredDeterministicMipsPatterns() {
        assertEquals(1000.0, NetworkStudyPlan.vmMips(3, NetworkStudyPlan.HOMOGENEOUS), 1e-12);
        assertEquals(1000.0, NetworkStudyPlan.vmMips(0, NetworkStudyPlan.HET_MILD), 1e-12);
        assertEquals(500.0, NetworkStudyPlan.vmMips(1, NetworkStudyPlan.HET_MILD), 1e-12);
        assertEquals(2000.0, NetworkStudyPlan.vmMips(0, NetworkStudyPlan.HET_STRONG), 1e-12);
        assertEquals(1000.0, NetworkStudyPlan.vmMips(1, NetworkStudyPlan.HET_STRONG), 1e-12);
        assertEquals(500.0, NetworkStudyPlan.vmMips(2, NetworkStudyPlan.HET_STRONG), 1e-12);
        assertEquals(2000.0, NetworkStudyPlan.vmMips(3, NetworkStudyPlan.HET_STRONG), 1e-12);
        assertEquals(500.0, NetworkStudyPlan.vmMips(1, NetworkStudyPlan.HET_EXTREME), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.vmMips(0, "unknown"));

        org.workflowsim.platform.PlatformProfile strong = NetworkStudyPlan.platform(4, "fat-tree-constrained",
                NetworkStudyPlan.HET_STRONG);
        assertEquals("network-study-4-fat-tree-constrained-HET_STRONG", strong.getName());
        assertEquals(4, strong.getVms().size());
        assertEquals(2000.0, strong.getVms().get(0).getMips(), 1e-12);
        assertEquals(1000.0, strong.getVms().get(1).getMips(), 1e-12);
        assertEquals(500.0, strong.getVms().get(2).getMips(), 1e-12);
        assertEquals(2000.0, strong.getVms().get(3).getMips(), 1e-12);
        assertEquals(4, strong.getNetworkTopology().getK());
        assertEquals(0.125, strong.getNetworkTopology().getLinkBandwidthMbPerSecond(), 1e-12);
        assertEquals(org.workflowsim.platform.PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                strong.getVms().get(2).getSchedulerMode());

        org.workflowsim.platform.PlatformProfile large = NetworkStudyPlan.platform(32, "fat-tree-fast",
                NetworkStudyPlan.HOMOGENEOUS);
        assertEquals("network-study-32-fat-tree-fast", large.getName());
        assertEquals(32, large.getVms().size());
        assertEquals(8, large.getNetworkTopology().getK());
        assertEquals(5.0, large.getNetworkTopology().getLinkBandwidthMbPerSecond(), 1e-12);
        assertNull(NetworkStudyPlan.platform(8, "endpoint", NetworkStudyPlan.HET_MILD).getNetworkTopology());

        assertEquals(0.5, NetworkStudyPlan.fatTreeLinkMbPerSecond("fat-tree-mid"), 1e-12);
        assertEquals(5.0, NetworkStudyPlan.fatTreeLinkMbPerSecond("fat-tree-fast"), 1e-12);
        assertEquals(0.125, NetworkStudyPlan.fatTreeLinkMbPerSecond("fat-tree-constrained"), 1e-12);
        assertEquals(1.25, NetworkStudyPlan.fatTreeLinkMbPerSecond("fat-tree-wide"), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> NetworkStudyPlan.fatTreeLinkMbPerSecond("endpoint"));
        assertNotNull(NetworkStudyPlan.movement("fat-tree-mid"));
        assertNotNull(NetworkStudyPlan.movement("fat-tree-fast"));
        assertEquals(4, NetworkStudyPlan.fatTreeK(16));
        assertEquals(8, NetworkStudyPlan.fatTreeK(32));
    }

    @SuppressWarnings("unchecked")
    @Test void heterogeneityStratifiesComparisonsOnlyWhenRunsCarryIt() {
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        runs.add(hetRow("real", "CLASSIC_DAX", NetworkStudyPlan.HOMOGENEOUS, "LOCAL_HEFT", 100));
        runs.add(hetRow("real", "CLASSIC_DAX", NetworkStudyPlan.HOMOGENEOUS, "LOCAL_PEFT", 95));
        runs.add(hetRow("real", "CLASSIC_DAX", NetworkStudyPlan.HET_STRONG, "LOCAL_HEFT", 100));
        runs.add(hetRow("real", "CLASSIC_DAX", NetworkStudyPlan.HET_STRONG, "LOCAL_PEFT", 80));
        Map<String, Object> result = NetworkStudySummary.summarize(runs);
        List<Map<String, Object>> comparisons = (List<Map<String, Object>>) result.get("comparisons");
        assertEquals(2, comparisons.size());
        assertEquals(NetworkStudyPlan.HOMOGENEOUS, comparisons.get(0).get("heterogeneity"));
        assertEquals(5.0, (Double) comparisons.get(0).get("medianImprovementPercent"), 1e-12);
        assertEquals(NetworkStudyPlan.HET_STRONG, comparisons.get(1).get("heterogeneity"));
        assertEquals(20.0, (Double) comparisons.get(1).get("medianImprovementPercent"), 1e-12);
        // 无异构字段的记录不能引入该键：冻结协议的汇总保持逐字节一致
        Map<String, Object> plain = NetworkStudySummary.summarize(Arrays.asList(
                row("real", "CLASSIC_DAX", "LOCAL_HEFT", 100), row("real", "CLASSIC_DAX", "LOCAL_PEFT", 90)));
        List<Map<String, Object>> plainComparisons = (List<Map<String, Object>>) plain.get("comparisons");
        assertFalse(plainComparisons.get(0).containsKey("heterogeneity"));
        assertFalse(((List<Map<String, Object>>) plain.get("aggregates")).get(0).containsKey("heterogeneity"));
    }

    private static Map<String, Object> hetRow(String dag, String population, String heterogeneity,
            String planner, double seconds) {
        Map<String, Object> r = row(dag, population, planner, seconds);
        r.put("heterogeneity", heterogeneity);
        return r;
    }

    @Test void exactSignTestAndHolmRespectTiesAndFamilySize() {
        assertEquals(0.03125, NetworkStudySummary.signTest(6, 0), 1e-12);
        assertEquals(1.0, NetworkStudySummary.signTest(3, 3), 1e-12);
        assertNull(NetworkStudySummary.signTest(0, 0));
        List<Map<String, Object>> family = new ArrayList<Map<String, Object>>();
        for (Double p : Arrays.asList(0.02, 0.01, 0.04)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("pValue", p); family.add(row);
        }
        NetworkStudySummary.applyHolm(family);
        assertEquals(0.04, (Double) family.get(0).get("holmAdjustedPValue"), 1e-12);
        assertEquals(0.03, (Double) family.get(1).get("holmAdjustedPValue"), 1e-12);
        assertEquals(0.04, (Double) family.get(2).get("holmAdjustedPValue"), 1e-12);
    }

    @SuppressWarnings("unchecked")
    @Test void repeatsCollapseWithinDagAndSyntheticPopulationIsSeparate() {
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        runs.add(row("real", "CLASSIC_DAX", "LOCAL_HEFT", 100));
        runs.add(row("real", "CLASSIC_DAX", "PSO", 70));
        runs.add(row("real", "CLASSIC_DAX", "PSO", 90));
        runs.add(row("synthetic", "SYNTHETIC", "LOCAL_HEFT", 10));
        runs.add(row("synthetic", "SYNTHETIC", "PSO", 20));
        Map<String, Object> result = NetworkStudySummary.summarize(runs);
        List<Map<String, Object>> comparisons = (List<Map<String, Object>>) result.get("comparisons");
        assertEquals(2, comparisons.size());
        assertEquals(1, comparisons.get(0).get("dagPairs"));
        assertEquals(20.0, (Double) comparisons.get(0).get("medianImprovementPercent"), 1e-12);
        assertEquals(-100.0, (Double) comparisons.get(1).get("medianImprovementPercent"), 1e-12);
        runs.get(0).put("status", "FAILED");
        assertTrue(((List<?>) NetworkStudySummary.summarize(runs).get("comparisons")).isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test void candidatesAreDerivedFromPresentedPlannersInFirstAppearanceOrder() {
        List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
        runs.add(row("real", "CLASSIC_DAX", "LOCAL_HEFT", 100));
        runs.add(row("real", "CLASSIC_DAX", "LOCAL_CPOP", 95));
        runs.add(row("real", "CLASSIC_DAX", "LOCAL_PEFT", 90));
        Map<String, Object> result = NetworkStudySummary.summarize(runs);
        List<Map<String, Object>> comparisons = (List<Map<String, Object>>) result.get("comparisons");
        assertEquals(2, comparisons.size());
        assertEquals("LOCAL_CPOP", comparisons.get(0).get("candidate"));
        assertEquals("LOCAL_PEFT", comparisons.get(1).get("candidate"));
        assertEquals("LOCAL_HEFT", comparisons.get(1).get("baseline"));
        assertEquals(10.0, (Double) comparisons.get(1).get("medianImprovementPercent"), 1e-12);
        // 单 DAG 对比 signTest p=1.0；Holm 族大小随候选数自动为 2
        assertEquals(1.0, (Double) comparisons.get(1).get("pValue"), 1e-12);
        assertEquals(1.0, (Double) comparisons.get(1).get("holmAdjustedPValue"), 1e-12);
    }

    private static Map<String, Object> row(String dag, String population, String planner, double seconds) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("workflowId", dag); r.put("family", dag); r.put("population", population);
        r.put("planner", planner); r.put("vmCount", 4); r.put("network", "endpoint");
        r.put("status", "COMPLETED_SUCCESSFULLY"); r.put("makespanSeconds", seconds); return r;
    }
    static Path datasets() { return Paths.get(System.getProperty("workflowsim.datasetRoot")); }
}
