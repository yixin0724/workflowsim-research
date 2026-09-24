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
