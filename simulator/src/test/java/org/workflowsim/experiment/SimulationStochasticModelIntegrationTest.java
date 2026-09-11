package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.RetryLimitExceededException;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationStochasticModelIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void seededFailureModelProducesReplayableRetriesAndTerminalTaskOutcomes() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(20260902L)
                .failureModel(failureModel)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport first = runner.run(config,
                PlatformProfiles.homogeneousLocal("failure-retry", 1));
        SimulationReport second = runner.run(config,
                PlatformProfiles.homogeneousLocal("failure-retry", 1));

        assertTrue(first.getFailedJobs() > 0, "The fixed-seed fixture must exercise a retry");
        assertTrue(first.getMetrics().getFailedComputeJobOutcomeCount() > 0);
        assertTrue(first.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertTrue(hasTaskStatus(first, Cloudlet.FAILED));
        assertTrue(hasTaskStatus(first, Cloudlet.SUCCESS));
        assertTrue(hasEvent(first, SimulationEventType.RETRY_JOB_CREATED));
        assertRetryJobsRemainComputeAttempts(first);
        assertUniqueJobIds(first);
        assertRetryMetricsMatchRecordedEvidence(first);
        assertEquals("COMPLETED_SUCCESSFULLY",
                first.getMetrics().getLogicalTaskCompletionStatus());
        assertTrue(first.getMetrics().getLogicalTaskCompletionSeconds() != null);
        assertTrue(first.getSimulationEndSeconds()
                >= first.getMetrics().getLogicalTaskCompletionSeconds().doubleValue());
        assertEquals(jobStatusFingerprint(first), jobStatusFingerprint(second));
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        assertEquals(first.getMetrics().getRetryJobCreatedCount(),
                second.getMetrics().getRetryJobCreatedCount());
        assertEquals(first.getMetrics().getFailedComputeAttemptEnvelopeSeconds(),
                second.getMetrics().getFailedComputeAttemptEnvelopeSeconds(), 0.0);
    }

    @Test
    void seededQueueOverheadChangesDispatchTimingAndReplaysExactly() throws Exception {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(0, DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig noOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(77L)
                .build();
        SimulationConfig withOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(77L)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport baseline = runner.run(noOverhead,
                PlatformProfiles.homogeneousLocal("overhead-baseline", 1));
        SimulationReport first = runner.run(withOverhead,
                PlatformProfiles.homogeneousLocal("overhead-delayed", 1));
        SimulationReport second = runner.run(withOverhead,
                PlatformProfiles.homogeneousLocal("overhead-delayed", 1));

        List<Double> firstDelays = queueDelays(first);
        assertFalse(firstDelays.isEmpty());
        for (Double delay : firstDelays) {
            assertTrue(delay.doubleValue() > 0.0);
        }
        assertTrue(first.getMakespan() > baseline.getMakespan());
        assertTrue(first.getMetrics().getMeanComputeDecisionToStartDelaySeconds() > 0.0);
        assertEquals(firstDelays, queueDelays(second));
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
    }

    @Test
    void retriedParentReleasesItsChildOnlyAfterTheSuccessfulRetryReturns() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        SimulationConfig config = SimulationConfig.builder(dependencyWorkflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(20260902L)
                .failureModel(failureModel)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("failure-dependency", 1));

        TreeSet<Integer> sourceTaskIds = new TreeSet<Integer>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            sourceTaskIds.add(task.getTaskId());
        }
        assertEquals(2, sourceTaskIds.size());
        int rootTaskId = sourceTaskIds.first();
        int childTaskId = sourceTaskIds.last();
        long retrySequence = sequenceOfRetryForTask(report, rootTaskId);
        long successfulParentReturn = sequenceOfSuccessfulReturnForTask(report, rootTaskId);
        long childReady = sequenceOfReadyForTask(report, childTaskId);

        // 以事件序号而非绝对仿真时刻做判定基准：重试创建必须先于成功返回，子任务随后才可就绪。
        assertTrue(retrySequence >= 0, "The fixed seed must retry the parent task");
        assertTrue(successfulParentReturn > retrySequence);
        assertTrue(childReady > successfulParentReturn,
                "A child must wait for the successful parent retry, not only its failed attempt");
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    @Test
    void finiteRetryBudgetFailsFastAndRemainsReplayable() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(reproducibilityWorkflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(20260902L)
                .failureModel(failureModel)
                .build();

        Log.disable();
        RetryLimitExceededException first = assertThrows(RetryLimitExceededException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("retry-budget-first", 1)));
        RetryLimitExceededException second = assertThrows(RetryLimitExceededException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("retry-budget-second", 1)));

        assertEquals(1, first.getCreatedRetryJobs());
        assertEquals(1, first.getRequestedRetryJobs());
        assertEquals(1, first.getMaxTotalRetryJobs());
        assertEquals(20260902L, first.getRootSeed());
        assertEquals(first.getMessage(), second.getMessage());
    }

    private static boolean hasTaskStatus(SimulationReport report, int status) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskStatus() == status) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEvent(SimulationReport report, SimulationEventType type) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static List<String> jobStatusFingerprint(SimulationReport report) {
        List<String> result = new ArrayList<String>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            result.add(job.getJobId() + ":" + job.getStatus() + ":" + job.getTaskIds());
        }
        return result;
    }

    private static List<Double> queueDelays(SimulationReport report) {
        List<Double> result = new ArrayList<Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.SCHEDULING_DECISION) {
                Object value = event.getAttributes().get("queueDelaySeconds");
                if (!(value instanceof Number)) {
                    throw new AssertionError("Scheduling decision does not expose numeric queue delay");
                }
                result.add(((Number) value).doubleValue());
            }
        }
        return result;
    }

    private static void assertUniqueJobIds(SimulationReport report) {
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            assertTrue(seen.add(job.getJobId()), "Duplicate Job ID " + job.getJobId());
        }
    }

    /** 重试 attempt 必须保留计算类别，否则其运行时间和结果会从 compute 指标中漏计。 */
    private static void assertRetryJobsRemainComputeAttempts(SimulationReport report) {
        Set<Integer> retryIds = new HashSet<Integer>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.RETRY_JOB_CREATED
                    && event.getJobId() != null) {
                retryIds.add(event.getJobId());
            }
        }
        assertFalse(retryIds.isEmpty(), "The fixed-seed fixture must create retry Jobs");
        int matched = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (retryIds.contains(job.getJobId())) {
                matched++;
                assertEquals(Parameters.ClassType.COMPUTE.value, job.getClassType(),
                        "Retry Job " + job.getJobId() + " lost its compute class type");
            }
        }
        assertEquals(retryIds.size(), matched, "Every retry creation event must have a Job outcome");
        assertTrue(report.getMetrics().getComputeJobOutcomeCount() >= matched,
                "Retry compute attempts must be included in compute outcome metrics");
    }

    /** 所有 retry 指标必须能一一回溯到已记录的 retry 创建事件和完成 Job evidence。 */
    private static void assertRetryMetricsMatchRecordedEvidence(SimulationReport report) {
        int retryEvents = 0;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.RETRY_JOB_CREATED) {
                retryEvents++;
            }
        }
        SimulationMetrics metrics = report.getMetrics();
        assertEquals(retryEvents, metrics.getRetryJobCreatedCount());
        assertEquals(retryEvents, metrics.getCompletedRetryComputeJobOutcomeCount());
        assertEquals(metrics.getComputeJobOutcomeCount(),
                metrics.getInitialComputeJobOutcomeCount()
                + metrics.getCompletedRetryComputeJobOutcomeCount());
        assertTrue(metrics.getRetriedLogicalTaskCount() > 0);
        assertEquals(metrics.getFailedComputeJobOutcomeCount(),
                countFailedComputeJobs(report));
        assertTrue(metrics.getFailedComputeAttemptEnvelopeSeconds() > 0.0);
        assertTrue(metrics.getFailedComputeAttemptModeledProcessingCost() > 0.0);
    }

    private static int countFailedComputeJobs(SimulationReport report) {
        int result = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getStatus() == Cloudlet.FAILED) {
                result++;
            }
        }
        return result;
    }

    private static long sequenceOfRetryForTask(SimulationReport report, int taskId) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.RETRY_JOB_CREATED
                    && event.getTaskIds().contains(taskId)) {
                return event.getSequence();
            }
        }
        return -1L;
    }

    private static long sequenceOfSuccessfulReturnForTask(SimulationReport report, int taskId) {
        long result = -1L;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_RETURNED
                    && event.getTaskIds().contains(taskId)
                    && Integer.valueOf(Cloudlet.SUCCESS).equals(event.getAttributes().get("jobStatus"))) {
                result = event.getSequence();
            }
        }
        return result;
    }

    private static long sequenceOfReadyForTask(SimulationReport report, int taskId) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_READY && event.getTaskIds().contains(taskId)) {
                return event.getSequence();
            }
        }
        return -1L;
    }

    private static String workflow() throws Exception {
        URL url = SimulationStochasticModelIntegrationTest.class.getResource(
                "/wfcommons/minimum-runtime.json");
        if (url == null) {
            throw new IllegalStateException("Missing WfCommons test fixture");
        }
        return Paths.get(url.toURI()).toString();
    }

    private static String dependencyWorkflow() throws Exception {
        URL url = SimulationStochasticModelIntegrationTest.class.getResource(
                "/wfcommons/retry-dependency.json");
        if (url == null) {
            throw new IllegalStateException("Missing WfCommons dependency fixture");
        }
        return Paths.get(url.toURI()).toString();
    }

    private static String reproducibilityWorkflow() throws Exception {
        URL url = SimulationStochasticModelIntegrationTest.class.getResource(
                "/dax/reproducibility-workflow.dax");
        if (url == null) {
            throw new IllegalStateException("Missing DAX retry-budget fixture");
        }
        return Paths.get(url.toURI()).toString();
    }
}
