package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ExperimentCampaignSummaryTest {

    @Test
    void independentReplicationsProduceAStudentTMeanInterval() {
        ExperimentCampaignSummary.MetricSummary summary = ExperimentCampaignSummary.MetricSummary.from(
                Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0), Double.valueOf(3.0)),
                RandomizationDesign.INDEPENDENT_REPLICATIONS);

        assertEquals(3, summary.getSampleSize());
        assertEquals(2.0, summary.getMean(), 0.0);
        assertEquals(1.0, summary.getSampleStandardDeviation(), 1.0e-12);
        assertTrue(summary.getMeanConfidenceInterval().isAvailable());
        assertEquals("AVAILABLE_STUDENT_T_MEAN_INTERVAL",
                summary.getMeanConfidenceInterval().getStatus());
        assertTrue(summary.getMeanConfidenceInterval().getLower().doubleValue() < 2.0);
        assertTrue(summary.getMeanConfidenceInterval().getUpper().doubleValue() > 2.0);
    }

    @Test
    void deterministicAndCommonRootPlansDoNotClaimSamplingInference() {
        ExperimentCampaignSummary.MetricSummary deterministic =
                ExperimentCampaignSummary.MetricSummary.from(Arrays.asList(Double.valueOf(7.0)),
                        RandomizationDesign.DETERMINISTIC);
        ExperimentCampaignSummary.MetricSummary commonRoot =
                ExperimentCampaignSummary.MetricSummary.from(Arrays.asList(Double.valueOf(7.0),
                        Double.valueOf(8.0)),
                        RandomizationDesign.COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN);

        assertFalse(deterministic.getMeanConfidenceInterval().isAvailable());
        assertNull(deterministic.getMeanConfidenceInterval().getLower());
        assertFalse(commonRoot.getMeanConfidenceInterval().isAvailable());
    }

    @Test
    void workflowRunCompletionUsesWilsonOnlyForIndependentReplications() {
        ExperimentCampaignSummary.WorkflowRunCompletionSummary independent =
                ExperimentCampaignSummary.WorkflowRunCompletionSummary.from(3, 3, 2, 1, 0,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);

        assertEquals(3, independent.getDeclaredRunCount());
        assertEquals(3, independent.getEligibleWorkflowRunCount());
        assertEquals(2, independent.getSuccessfullyCompletedWorkflowRunCount());
        assertEquals(1, independent.getIncompleteWorkflowRunCount());
        assertEquals(0, independent.getNoLogicalTaskRunCount());
        assertEquals(2.0 / 3.0, independent.getSuccessfulWorkflowRunRate().doubleValue(), 1.0e-12);
        assertTrue(independent.getWilsonConfidenceInterval().isAvailable());
        assertEquals("WILSON_SCORE", independent.getWilsonConfidenceInterval().getMethod());
        assertEquals("AVAILABLE_WILSON_SCORE_PROPORTION_INTERVAL",
                independent.getWilsonConfidenceInterval().getStatus());
        assertTrue(independent.getWilsonConfidenceInterval().getLower().doubleValue()
                < independent.getSuccessfulWorkflowRunRate().doubleValue());
        assertTrue(independent.getWilsonConfidenceInterval().getUpper().doubleValue()
                > independent.getSuccessfulWorkflowRunRate().doubleValue());

        ExperimentCampaignSummary.ProportionConfidenceInterval zero =
                ExperimentCampaignSummary.ProportionConfidenceInterval.forWilson(0, 3,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);
        ExperimentCampaignSummary.ProportionConfidenceInterval all =
                ExperimentCampaignSummary.ProportionConfidenceInterval.forWilson(3, 3,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);
        assertTrue(zero.isAvailable());
        assertTrue(all.isAvailable());
        assertTrue(zero.getLower().doubleValue() >= 0.0 && zero.getUpper().doubleValue() <= 1.0);
        assertTrue(all.getLower().doubleValue() >= 0.0 && all.getUpper().doubleValue() <= 1.0);

        ExperimentCampaignSummary.WorkflowRunCompletionSummary deterministic =
                ExperimentCampaignSummary.WorkflowRunCompletionSummary.from(1, 1, 1, 0, 0,
                        RandomizationDesign.DETERMINISTIC);
        assertFalse(deterministic.getWilsonConfidenceInterval().isAvailable());
        assertEquals("UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS",
                deterministic.getWilsonConfidenceInterval().getStatus());

        ExperimentCampaignSummary.WorkflowRunCompletionSummary noEligible =
                ExperimentCampaignSummary.WorkflowRunCompletionSummary.from(2, 0, 0, 0, 2,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);
        assertNull(noEligible.getSuccessfulWorkflowRunRate());
        assertEquals("UNAVAILABLE_NO_ELIGIBLE_WORKFLOW_RUNS",
                noEligible.getWilsonConfidenceInterval().getStatus());
    }

    @Test
    void logicalCompletionTimeIsSummarizedOnlyForSuccessfulWorkflowRuns() {
        ExperimentCampaignSummary.ConditionalMetricSummary available =
                ExperimentCampaignSummary.ConditionalMetricSummary.from(2,
                        Arrays.asList(Double.valueOf(4.0), Double.valueOf(6.0)), 0,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);
        assertEquals("SUCCESSFUL_WORKFLOW_RUNS_ONLY", available.getPopulation());
        assertEquals("AVAILABLE_SUCCESSFUL_WORKFLOW_RUNS_ONLY", available.getStatus());
        assertEquals(2, available.getCompletionTimeObservationCount());
        assertEquals(5.0, available.getSummary().getMean(), 0.0);

        ExperimentCampaignSummary.ConditionalMetricSummary unavailable =
                ExperimentCampaignSummary.ConditionalMetricSummary.from(0,
                        java.util.Collections.<Double>emptyList(), 0,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS);
        assertEquals("UNAVAILABLE_NO_SUCCESSFUL_WORKFLOW_RUNS", unavailable.getStatus());
        assertNull(unavailable.getSummary());

        assertThrows(IllegalStateException.class,
                () -> ExperimentCampaignSummary.ConditionalMetricSummary.from(1,
                        java.util.Collections.<Double>emptyList(), 1,
                        RandomizationDesign.INDEPENDENT_REPLICATIONS));
    }
}
