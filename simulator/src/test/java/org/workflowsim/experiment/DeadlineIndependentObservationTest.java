package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** Independent deadline arithmetic and observer-only trajectory properties. */
class DeadlineIndependentObservationTest {
    private static final double EPS = 1.0e-9;

    @TempDir Path directory;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void deadlineIsAnObserverForANonzeroArrivalIncludingTheExactBoundary() throws Exception {
        Path input = dax("arrival", "<job id=\"a\" runtime=\"1\"/>");
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .workflowArrivalSeconds(Collections.singletonList(10.0)).randomSeed(31L).build();
        SimulationReport baseline = run(config);

        // Stage-in is ready before t=10. One second of compute ends at t=11,
        // not at one second on an arrival-relative deadline clock.
        assertEquals(11.0, baseline.getSimulationEndSeconds(), EPS);
        assertEquals(1.0, baseline.getWorkflowOutcomes().get(0).getFlowTimeSeconds(), EPS);
        assertDeadline(baseline.getMetrics(), "NOT_REQUESTED", false, 0.0, 0.0);
        for (long deadline : new long[] {2L, 10L, 11L, 12L}) {
            SimulationReport observed = run(config.toBuilder().deadline(deadline).build());
            assertEquals(trajectory(baseline), trajectory(observed),
                    "Changing an observation threshold must not alter execution, arrivals, or decisions");
            assertDeadline(observed.getMetrics(), deadline >= 11L ? "MET" : "MISSED_LATE",
                    deadline >= 11L, deadline - 11.0, Math.max(0.0, 11.0 - deadline));
        }
    }

    @Test
    void staggeredInputsShareAnAbsoluteDeadlineNotPerWorkflowFlowTime() throws Exception {
        Path input = dax("staggered", "<job id=\"a\" runtime=\"1\"/>");
        SimulationConfig config = SimulationConfig.builder(
                Arrays.asList(input.toString(), input.toString()), 1)
                .workflowArrivalSeconds(Arrays.asList(10.0, 20.0)).build();
        SimulationReport baseline = run(config);
        SimulationReport observed = run(config.toBuilder().deadline(20L).build());

        assertEquals(trajectory(baseline), trajectory(observed));
        assertEquals(21.0, observed.getSimulationEndSeconds(), EPS);
        assertEquals(2, observed.getMetrics().getLogicalTaskCount());
        assertEquals(11.0, observed.getWorkflowOutcomes().get(0).getLastSuccessFinishSecond(), EPS);
        assertEquals(21.0, observed.getWorkflowOutcomes().get(1).getLastSuccessFinishSecond(), EPS);
        assertEquals(1.0, observed.getWorkflowOutcomes().get(0).getFlowTimeSeconds(), EPS);
        assertEquals(1.0, observed.getWorkflowOutcomes().get(1).getFlowTimeSeconds(), EPS);
        assertDeadline(observed.getMetrics(), "MISSED_LATE", false, -1.0, 1.0);
    }

    @Test
    void deadlineObservationDoesNotChangeFailureAndRetryTrajectory() throws Exception {
        Path input = dax("retry", "<job id=\"a\" runtime=\"1\">"
                + "<uses name=\"external\" link=\"input\" size=\"20000000\"/></job>");
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .randomSeed(5L).failureModel(failureModel()).build();
        SimulationReport baseline = run(config);
        assertTrue(baseline.getMetrics().getFailedComputeJobOutcomeCount() > 0,
                "The fixed fixture must exercise a real failed attempt");
        assertTrue(baseline.getMetrics().getRetryJobCreatedCount() > 0);
        assertTrue(baseline.isWorkflowCompletedSuccessfully());
        assertEquals(1, baseline.getMetrics().getLogicalTaskCount());
        assertFalse(baseline.getMetrics().isControlledSharedStorageCriticalPathReferenceAvailable(),
                "Failure-model runs are outside the controlled reference scope, not reference defects");

        for (long deadline : new long[] {1L, 10_000L}) {
            SimulationReport observed = run(config.toBuilder().deadline(deadline).build());
            assertEquals(trajectory(baseline), trajectory(observed),
                    "Deadline observation must not consume randomness or change retry lineage");
            double end = baseline.getSimulationEndSeconds();
            assertDeadline(observed.getMetrics(), end <= deadline ? "MET" : "MISSED_LATE",
                    end <= deadline, deadline - end, Math.max(0.0, end - deadline));
        }
    }

    @Test
    void successfulRetryCompletesSourceTasksButTerminalTailStillDeterminesDeadline() throws Exception {
        Task first = new Task(1, 2000L);
        first.setCloudletStatus(Cloudlet.FAILED); // The original source attempt need not become successful.
        Task second = new Task(2, 1000L);
        first.addChild(second);
        second.addParent(first);
        List<Task> sources = Arrays.asList(first, second);
        List<SimulationReport.JobOutcome> jobs = Arrays.asList(
                job(50, Cloudlet.SUCCESS, Parameters.ClassType.STAGE_IN.value, 0.0, 0.11),
                job(70, Cloudlet.FAILED, Parameters.ClassType.COMPUTE.value, 10.0, 12.0, 1),
                job(90, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value, 12.0, 14.0, 1),
                job(71, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value, 14.0, 15.0, 2));
        List<SimulationReport.TaskOutcome> attempts = Arrays.asList(
                attempt(1, 70, Cloudlet.FAILED, 2000L, 10.0, 12.0),
                attempt(1, 90, Cloudlet.SUCCESS, 2000L, 12.0, 14.0),
                attempt(2, 71, Cloudlet.SUCCESS, 1000L, 14.0, 15.0));
        List<SimulationEvent> events = Collections.singletonList(new SimulationEvent(
                0L, 12.0, SimulationEventType.RETRY_JOB_CREATED, 90, 0,
                Parameters.ClassType.COMPUTE.value, Collections.singletonList(1),
                Collections.<String, Object>singletonMap("failedJobId", 70)));
        SimulationConfig config = SimulationConfig.builder("unused.dax", 1)
                .workflowArrivalSeconds(Collections.singletonList(10.0))
                .failureModel(failureModel())
                .overheadModel(OverheadModelConfig.builder().postDelays(Collections.singletonMap(
                        0, DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,
                                5.0, 1.0))).build())
                .deadline(18L).build();
        SimulationMetrics late = metrics(config, 20.0, sources, jobs, attempts, events);

        assertEquals(2, late.getLogicalTaskCount());
        assertEquals(2, late.getSuccessfullyCompletedLogicalTaskCount());
        assertEquals(3, late.getLogicalTaskAttemptCount());
        assertEquals(1, late.getRetriedLogicalTaskCount());
        assertEquals(15.0, late.getLogicalTaskCompletionSeconds().doubleValue(), 0.0);
        assertEquals(5.0, late.getTerminalLifecycleTailSeconds().doubleValue(), 0.0);
        assertTrue(late.isWorkflowCompletedSuccessfully());
        assertDeadline(late, "MISSED_LATE", false, -2.0, 2.0);

        SimulationMetrics atBoundary = metrics(config.toBuilder().deadline(20L).build(),
                20.0, sources, jobs, attempts, events);
        assertDeadline(atBoundary, "MET", true, 0.0, 0.0);
    }

    @Test
    void anUnattemptedSourceTaskPreventsSuccessEvenWhenAllReturnedJobsSucceeded() {
        List<Task> sources = Arrays.asList(new Task(1, 1000L), new Task(2, 1000L));
        List<SimulationReport.JobOutcome> jobs = Arrays.asList(
                job(10, Cloudlet.SUCCESS, Parameters.ClassType.STAGE_IN.value, 0.0, 0.11),
                job(11, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value, 1.0, 2.0, 1));
        List<SimulationReport.TaskOutcome> attempts = Collections.singletonList(
                attempt(1, 11, Cloudlet.SUCCESS, 1000L, 1.0, 2.0));
        SimulationMetrics incomplete = metrics(SimulationConfig.builder("unused.dax", 1)
                .deadline(10L).build(), 5.0, sources, jobs, attempts,
                Collections.<SimulationEvent>emptyList());

        // Stage-in plus one compute success is two successful Jobs, but only one of two source Tasks.
        assertEquals(2, incomplete.getSuccessfulJobOutcomeCount());
        assertEquals(2, incomplete.getLogicalTaskCount());
        assertEquals(1, incomplete.getSuccessfullyCompletedLogicalTaskCount());
        assertNull(incomplete.getLogicalTaskCompletionSeconds());
        assertNull(incomplete.getTerminalLifecycleTailSeconds());
        assertDeadline(incomplete, "MISSED_INCOMPLETE_WORKFLOW", false, 5.0, 0.0);

        SimulationMetrics alsoLate = metrics(SimulationConfig.builder("unused.dax", 1)
                .deadline(4L).build(), 5.0, sources, jobs, attempts,
                Collections.<SimulationEvent>emptyList());
        assertDeadline(alsoLate, "MISSED_INCOMPLETE_WORKFLOW", false, -1.0, 1.0);
    }

    @Test
    void emptyLogicalEvidenceIsNotVacuouslyADeadlineSuccess() {
        SimulationMetrics empty = metrics(SimulationConfig.builder("unused.dax", 1)
                .deadline(10L).build(), 0.0, Collections.<Task>emptyList(),
                Collections.<SimulationReport.JobOutcome>emptyList(),
                Collections.<SimulationReport.TaskOutcome>emptyList(),
                Collections.<SimulationEvent>emptyList());
        assertEquals("NO_LOGICAL_TASKS", empty.getLogicalTaskCompletionStatus());
        assertDeadline(empty, "MISSED_INCOMPLETE_WORKFLOW", false, 10.0, 0.0);
    }

    @Test
    void lowDeadlineComparisonKeepsTheExactDoubleBoundaryWithoutAnEpsilon() {
        assertDeadline(completedAt(4.0, 4L), "MET", true, 0.0, 0.0);
        // The successor of 4 is 4 + 2^-50; even this representable lateness is not MET.
        assertDeadline(completedAt(Math.nextUp(4.0), 4L), "MISSED_LATE", false,
                -0x1.0p-50, 0x1.0p-50);
    }

    @Test
    void longDeadlineRoundingUpCannotTurnOneSecondLateIntoMet() {
        // T is exactly 2^53 + 4. The long D is exactly T - 1, not rounded to T.
        assertDeadline(completedAt(9_007_199_254_740_996.0, 9_007_199_254_740_995L),
                "MISSED_LATE", false, -1.0, 1.0);
    }

    @Test
    void longDeadlineRoundingDownCannotEraseOneSecondOfPositiveSlack() {
        // Both integers are explicit: T=2^53 and D=2^53+1.
        assertDeadline(completedAt(9_007_199_254_740_992.0, 9_007_199_254_740_993L),
                "MET", true, 1.0, 0.0);
    }

    @Test
    void largestLongDeadlineIsOneSecondEarlierThanTheExactDoubleTwoToThe63() {
        // 0x1.0p63 is exactly 9223372036854775808, not the shortest decimal 9.223372036854776E18.
        // In particular, the exact difference is 1, not 0 or 193 seconds.
        assertDeadline(completedAt(0x1.0p63, Long.MAX_VALUE), "MISSED_LATE", false, -1.0, 1.0);
    }

    @Test
    void largestLongDeadlineRetainsItsLowBitsWhenTheRunIsEarly() {
        // The preceding binary64 value is 2^63 - 1024. (2^63 - 1) - T = 1023.
        assertDeadline(completedAt(Math.nextDown(0x1.0p63), Long.MAX_VALUE),
                "MET", true, 1023.0, 0.0);
    }

    @Test
    void absentDeadlineKeepsZeroObservationValuesEvenAtALargeClock() {
        assertDeadline(completedAt(0x1.0p63, 0L), "NOT_REQUESTED", false, 0.0, 0.0);
    }

    private static void assertDeadline(SimulationMetrics metrics, String outcome, boolean met,
            double slack, double tardiness) {
        assertAll(
                () -> assertEquals(!"NOT_REQUESTED".equals(outcome), metrics.isDeadlineObservationEnabled()),
                () -> assertEquals("SIMULATION_END_SECONDS", metrics.getDeadlineObservationTimeBasis()),
                () -> assertEquals(outcome, metrics.getDeadlineObservationOutcome()),
                () -> assertEquals(met, metrics.isDeadlineMet()),
                () -> assertEquals(slack, metrics.getDeadlineSlackSeconds(), 0.0),
                () -> assertEquals(tardiness, metrics.getDeadlineTardinessSeconds(), 0.0));
    }

    private static SimulationMetrics completedAt(double end, long deadline) {
        // A finite, late-arriving compute attempt. No large-clock simulation or event-cadence assumption.
        double duration = end >= 4096.0 ? 4096.0 : 1.0;
        double start = end - duration;
        long lengthMi = (long) (duration * 1000.0);
        SimulationConfig config = SimulationConfig.builder("unused.dax", 1)
                .workflowArrivalSeconds(Collections.singletonList(start)).deadline(deadline).build();
        return metrics(config, end, Collections.singletonList(new Task(1, lengthMi)),
                Collections.singletonList(job(1, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        start, end, 1)),
                Collections.singletonList(attempt(1, 1, Cloudlet.SUCCESS, lengthMi, start, end)),
                Collections.<SimulationEvent>emptyList());
    }

    private static SimulationMetrics metrics(SimulationConfig config, double end, List<Task> sources,
            List<SimulationReport.JobOutcome> jobs, List<SimulationReport.TaskOutcome> attempts,
            List<SimulationEvent> events) {
        int successes = 0;
        int failures = 0;
        double cpu = 0.0;
        double lastFinish = 0.0;
        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getStatus() == Cloudlet.SUCCESS) { successes++; }
            if (job.getStatus() == Cloudlet.FAILED) { failures++; }
            cpu += job.getCpuTime();
            lastFinish = Math.max(lastFinish, job.getFinishTime());
        }
        Map<Integer, SimulationReport.VmSummary> summaries = Collections.singletonMap(0,
                new SimulationReport.VmSummary(0, jobs.size(), successes, failures, cpu, lastFinish));
        return SimulationMetrics.calculate(end, jobs, summaries, events, attempts, sources, config, platform());
    }

    private static SimulationReport.JobOutcome job(int id, int status, int classType,
            double start, double finish, Integer... taskIds) {
        return new SimulationReport.JobOutcome(id, 0, status, classType, start, start, finish,
                finish - start, 0.0, taskIds.length, Arrays.asList(taskIds));
    }

    private static SimulationReport.TaskOutcome attempt(int taskId, int jobId, int status,
            long lengthMi, double start, double finish) {
        return new SimulationReport.TaskOutcome(taskId, jobId, 0, status, status, 0,
                lengthMi, start, finish);
    }

    private SimulationReport run(SimulationConfig config) throws Exception {
        Log.disable();
        return new SimulationRunner().run(config, platform());
    }

    private Path dax(String name, String body) throws Exception {
        Path path = directory.resolve(name + ".dax");
        Files.write(path, ("<adag version=\"3.3\">" + body + "</adag>").getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("independent-deadline-observation")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();
    }

    private static FailureModelConfig failureModel() {
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {{DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 2.0, 1.0)}})
                .maxTotalRetryJobs(32).build();
    }

    /** Keep execution evidence; exclude only the two documented local wall-clock measurements. */
    private static List<Object> trajectory(SimulationReport report) {
        List<Object> rows = new ArrayList<Object>();
        rows.add(Arrays.asList("end", report.getSimulationEndSeconds(),
                report.getLogicalTaskCompletionSeconds(), report.getLogicalTaskCompletionStatus(),
                report.getTerminalLifecycleTailSeconds()));
        rows.add(report.getActualVmHostAssignments());
        rows.add(report.getVmSummaries());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            rows.add(Arrays.asList("job", job.getJobId(), job.getVmId(), job.getStatus(), job.getClassType(),
                    job.getSubmissionTime(), job.getStartTime(), job.getFinishTime(), job.getCpuTime(),
                    job.getWaitingTime(), job.getExecutionTime(), job.getResponseTime(),
                    job.getModeledCpuEnvelopeCost(), job.getModeledDeclaredFileBandwidthCost(),
                    job.getModeledDeclaredFileBytes(), job.getModeledProcessingCost(),
                    job.getTaskCount(), job.getTaskIds()));
        }
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            rows.add(Arrays.asList("task", task.getTaskId(), task.getJobId(), task.getVmId(),
                    task.getJobStatus(), task.getTaskStatus(), task.getDepth(), task.getLengthMi(),
                    task.getEffectiveExecutionLengthMi(), task.getStartTime(), task.getFinishTime(),
                    task.hasExactJobTiming()));
        }
        for (SimulationReport.WorkflowOutcome workflow : report.getWorkflowOutcomes()) {
            rows.add(Arrays.asList("workflow", workflow.getIndex(), workflow.getPath(), workflow.getArrivalSecond(),
                    workflow.getFirstTaskId(), workflow.getLastTaskId(), workflow.getTaskCount(),
                    workflow.getLastSuccessFinishSecond(), workflow.getFlowTimeSeconds()));
        }
        for (SimulationReport.TaskNode task : report.getWorkflowGraph()) {
            rows.add(Arrays.asList("source", task.getTaskId(), task.getType(), task.getDepth(),
                    task.getParentIds(), task.getChildIds()));
        }
        for (SimulationEvent event : report.getEvents()) {
            Map<String, Object> attributes = new LinkedHashMap<String, Object>(event.getAttributes());
            attributes.remove("decisionElapsedNanos");
            attributes.remove("planningDecisionElapsedNanos");
            rows.add(Arrays.asList("event", event.getSequence(), event.getSimulationTime(), event.getType(),
                    event.getJobId(), event.getVmId(), event.getClassType(), event.getTaskIds(), attributes));
        }
        return rows;
    }
}
