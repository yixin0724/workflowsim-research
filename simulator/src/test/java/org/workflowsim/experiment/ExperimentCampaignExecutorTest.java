package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class ExperimentCampaignExecutorTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void executesADeclaredComparisonAndWritesAValidatedIndex(@TempDir Path output)
            throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("campaign-platform", 2);
        SeedPlan seeds = SeedPlan.deterministic(20260902L);
        ExperimentPlan plan = ExperimentPlan.builder("campaign-smoke")
                .addCell(cell("fcfs", "baseline", true, workflow, platform, seeds,
                        Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell("mct", "ready-batch-mct", false, workflow, platform, seeds,
                        Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .build();

        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);

        assertEquals(2, result.getRuns().size());
        assertEquals(2, result.getSummary().getCells().size());
        assertEquals(1, result.getSummary().getComparisons().size());
        ExperimentCampaignSummary.ComparisonSummary comparison =
                result.getSummary().getComparisons().get(0);
        assertEquals(1, comparison.getMatchedRootSeedCount());
        assertEquals("DESCRIPTIVE_ONLY_DETERMINISTIC", comparison.getInferenceStatus());
        assertTrue(comparison.getMeanMakespanRatio() != null);
        assertTrue(comparison.getBaselineRelativeMakespanSpeedup() != null);
        assertEquals(1.0 / comparison.getMeanMakespanRatio().doubleValue(),
                comparison.getBaselineRelativeMakespanSpeedup().doubleValue(), 1.0e-12);
        assertTrue(comparison.getMakespanImprovementPercent() != null);
        assertTrue(comparison.getMeanModeledProcessingCostRatio() != null);
        assertEquals(0.0, comparison.getSuccessfulWorkflowRunRateDelta().doubleValue(), 0.0);

        ExperimentCampaignSummary.CellSummary firstCell = result.getSummary().getCells().get(0);
        ExperimentCampaignSummary.WorkflowRunCompletionSummary workflowCompletion =
                firstCell.getWorkflowRunCompletion();
        assertEquals(1, workflowCompletion.getDeclaredRunCount());
        assertEquals(1, workflowCompletion.getEligibleWorkflowRunCount());
        assertEquals(1, workflowCompletion.getSuccessfullyCompletedWorkflowRunCount());
        assertEquals(1.0, workflowCompletion.getSuccessfulWorkflowRunRate().doubleValue(), 0.0);
        assertTrue(!workflowCompletion.getWilsonConfidenceInterval().isAvailable());
        assertEquals("UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS",
                workflowCompletion.getWilsonConfidenceInterval().getStatus());
        assertEquals(1, firstCell.getSuccessfulWorkflowLogicalCompletionSeconds()
                .getCompletionTimeObservationCount());

        ExperimentCampaignArtifactWriter.CampaignArtifacts artifacts =
                ExperimentCampaignArtifactWriter.write(result, output);
        assertTrue(Files.isRegularFile(artifacts.getIndex()));
        assertEquals(2, artifacts.getRuns().size());
        assertEquals(2, ExperimentCampaignValidator.validate(artifacts.getIndex()).getRunCount());
        String index = new String(Files.readAllBytes(artifacts.getIndex()), StandardCharsets.UTF_8);
        assertTrue(index.contains("workflowsim-experiment-campaign-index-v1"));
        assertTrue(index.contains("workflowProfile"));
        assertTrue(index.contains("DESCRIPTIVE_ONLY_DETERMINISTIC"));
        assertTrue(index.contains("baselineRelativeMakespanSpeedup"));
        assertTrue(index.contains("workflowRunCompletion"));
        assertTrue(index.contains("successfulWorkflowLogicalCompletionSeconds"));
        assertTrue(index.contains("WILSON_SCORE"));

        Files.write(output.resolve(artifacts.getRuns().get(0).getManifest()),
                "{}".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class,
                () -> ExperimentCampaignValidator.validate(artifacts.getIndex()));
    }

    @Test
    void independentReplicationsRemainUnpairedInTheCampaignSummary() throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("independent-campaign", 2);
        SeedPlan seeds = SeedPlan.derived(RandomizationDesign.INDEPENDENT_REPLICATIONS,
                20260902L, 2);
        ExperimentPlan plan = ExperimentPlan.builder("independent-campaign")
                .addCell(cell("fcfs", "baseline", true, workflow, platform, seeds,
                        Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell("mct", "ready-batch-mct", false, workflow, platform, seeds,
                        Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .build();

        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);

        assertEquals(4, result.getRuns().size());
        ExperimentCampaignSummary.ComparisonSummary comparison = result.getSummary()
                .getComparisons().get(0);
        assertEquals(0, comparison.getMatchedRootSeedCount());
        assertNull(comparison.getMeanMatchedMakespanDeltaSeconds());
        assertEquals("UNPAIRED_INDEPENDENT_REPLICATIONS", comparison.getInferenceStatus());
        assertTrue(result.getSummary().getCells().get(0).getMakespanSeconds()
                .getMeanConfidenceInterval().isAvailable());
        for (ExperimentCampaignSummary.CellSummary cell : result.getSummary().getCells()) {
            assertEquals(2, cell.getWorkflowRunCompletion().getEligibleWorkflowRunCount());
            assertTrue(cell.getWorkflowRunCompletion().getWilsonConfidenceInterval().isAvailable());
            assertEquals(2, cell.getSuccessfulWorkflowLogicalCompletionSeconds()
                    .getCompletionTimeObservationCount());
        }
    }

    private static ExperimentPlan.Cell cell(String id, String candidate, boolean baseline,
            String workflow, PlatformProfile platform, SeedPlan seeds,
            Parameters.SchedulingAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder(workflow, 2)
                .schedulingAlgorithm(algorithm)
                .build();
        return new ExperimentPlan.Cell(id, "shared-workflow", candidate, baseline, config,
                platform, seeds, Collections.singletonMap("purpose", "smoke"));
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = ExperimentCampaignExecutorTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
