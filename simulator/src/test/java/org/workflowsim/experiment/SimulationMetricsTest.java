package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationMetricsTest {

    @Test
    void calculatesOutcomeRetryDelayCostAndUnionIntervalMetricsFromRecordedEvidence() {
        List<SimulationReport.JobOutcome> jobs = Arrays.asList(
                job(10, 0, Cloudlet.SUCCESS, Parameters.ClassType.STAGE_IN.value,
                        0.0, 0.11, 0.11, 1.0, Collections.<Integer>emptyList()),
                job(11, 0, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        0.11, 2.11, 2.0, 3.0, Arrays.asList(1)),
                job(12, 1, Cloudlet.FAILED, Parameters.ClassType.COMPUTE.value,
                        0.11, 1.11, 1.0, 2.0, Arrays.asList(2)),
                job(13, 0, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        1.11, 3.11, 2.0, 4.0, Arrays.asList(2)));
        List<SimulationReport.TaskOutcome> tasks = Arrays.asList(
                task(1, 11, 0, Cloudlet.SUCCESS, 0.11, 2.11),
                task(2, 12, 1, Cloudlet.FAILED, 0.11, 1.11),
                task(2, 13, 0, Cloudlet.SUCCESS, 1.11, 3.11));

        SimulationMetrics metrics = SimulationMetrics.calculate(3.11, jobs, summaries(), events(),
                tasks, Collections.<Task>emptyList(), controlledConfig(), platform());

        assertEquals(4, metrics.getJobOutcomeCount());
        assertEquals(3, metrics.getComputeJobOutcomeCount());
        assertEquals(1, metrics.getStageInJobOutcomeCount());
        assertEquals(3, metrics.getSuccessfulJobOutcomeCount());
        assertEquals(1, metrics.getFailedJobOutcomeCount());
        assertEquals(2.0 / 3.0, metrics.getSuccessfulComputeJobOutcomeRate(), 1.0e-12);
        assertEquals(3.0 / 3.11, metrics.getComputeJobOutcomeThroughputPerSecond(), 1.0e-12);
        assertEquals(5.0 / 3.0, metrics.getMeanComputeJobRunTimeSeconds(), 1.0e-12);

        // 任一包含该任务的重试 Job 成功时，该逻辑 Task 即被视为完成。
        assertEquals(2, metrics.getLogicalTaskCount());
        assertEquals(2, metrics.getSuccessfullyCompletedLogicalTaskCount());
        assertEquals(0, metrics.getLogicalTasksNotYetSuccessfullyCompletedCount());
        assertEquals(1.0, metrics.getSuccessfulLogicalTaskCompletionRate(), 1.0e-12);
        assertTrue(metrics.isAllLogicalTasksCompletedSuccessfully());
        assertTrue(metrics.isWorkflowCompletedSuccessfully());
        assertEquals("COMPLETED_SUCCESSFULLY", metrics.getLogicalTaskCompletionStatus());
        assertEquals(3.11, metrics.getLogicalTaskCompletionSeconds().doubleValue(), 1.0e-12);
        assertEquals(0.0, metrics.getTerminalLifecycleTailSeconds().doubleValue(), 1.0e-12);

        assertEquals(4, metrics.getCompletedJobAttemptCount());
        assertEquals(3, metrics.getCompletedComputeAttemptCount());
        assertEquals(2, metrics.getInitialComputeJobOutcomeCount());
        assertEquals(1, metrics.getRetryJobCreatedCount());
        assertEquals(1, metrics.getCompletedRetryComputeJobOutcomeCount());
        assertEquals(3, metrics.getLogicalTaskAttemptCount());
        assertEquals(1, metrics.getRetriedLogicalTaskCount());
        assertEquals(1.0, metrics.getFailedComputeAttemptEnvelopeSeconds(), 1.0e-12);
        assertEquals(2.0, metrics.getFailedComputeAttemptModeledProcessingCost(), 1.0e-12);
        assertEquals(4.0, metrics.getRetryComputeAttemptModeledProcessingCost(), 1.0e-12);

        // Job 11 贡献 0.01 秒 ready-to-decision 延迟和零 decision-to-start 延迟。
        // Job 12 的决策发生在开始之后，因此不能计入 decision-to-start 延迟。
        assertEquals(2, metrics.getReadyToDecisionObservationCount());
        assertEquals(0.055, metrics.getMeanComputeReadyToDecisionDelaySeconds(), 1.0e-12);
        assertEquals(1, metrics.getDecisionToStartObservationCount());
        assertEquals(0.0, metrics.getMeanComputeDecisionToStartDelaySeconds(), 1.0e-12);
        assertEquals(2, metrics.getSchedulingCycleCount());
        assertEquals(7L, metrics.getTotalSchedulingDecisionWallClockNanos());
        assertEquals(1, metrics.getExplicitPlannerDecisionObservationCount());
        assertEquals(11L, metrics.getTotalPlanningDecisionWallClockNanos());
        assertEquals(2, metrics.getDataStageInModelObservationCount());
        assertEquals(3, metrics.getModeledDataTransferFileCount());
        assertEquals(4.0, metrics.getTotalModeledDataTransferSeconds(), 1.0e-12);
        assertEquals(30.0, metrics.getTotalModeledRequiredInputBytes(), 1.0e-12);
        assertEquals(3, metrics.getModeledRequiredInputDemandFileCount());
        assertEquals(30.0, metrics.getTotalModeledRequiredInputDemandBytes(), 1.0e-12);
        assertEquals(2.0, metrics.getMeanModeledDataTransferSeconds(), 1.0e-12);
        assertEquals(10.0, metrics.getTotalModeledProcessingCost(), 1.0e-12);
        assertEquals(10.0, metrics.getTotalModeledCpuEnvelopeCost(), 1.0e-12);
        assertEquals(0.0, metrics.getTotalModeledDeclaredFileBandwidthCost(), 0.0);
        assertEquals(0.0, metrics.getTotalModeledDeclaredFileBytes(), 0.0);

        SimulationMetrics.VmMetrics firstVm = metrics.getVmMetrics().get(0);
        SimulationMetrics.VmMetrics secondVm = metrics.getVmMetrics().get(1);
        assertEquals(3.11, firstVm.getModeledBusyIntervalSeconds(), 1.0e-12);
        assertEquals(1.0, firstVm.getModeledIntervalUtilization(), 1.0e-12);
        assertEquals(1.0, secondVm.getModeledBusyIntervalSeconds(), 1.0e-12);
        assertEquals(4.11, metrics.getTotalVmModeledBusyIntervalSeconds(), 1.0e-12);
        assertEquals(4.11 / (3.11 * 2.0), metrics.getMeanVmModeledIntervalUtilization(), 1.0e-12);
        assertEquals(1.055 / 2.055, metrics.getVmModeledBusyTimeCoefficientOfVariation(),
                1.0e-12);
        assertFalse(metrics.isControlledSharedStorageCriticalPathReferenceAvailable());
        assertEquals(3, metrics.getExactTaskTimingObservationCount());
        assertEquals(0, metrics.getModeledApproximateTaskTimingObservationCount());
    }

    @Test
    void definesZeroForEmptyRatiosInsteadOfProducingNanOrInfinity() {
        SimulationMetrics metrics = SimulationMetrics.calculate(0.0,
                Collections.<SimulationReport.JobOutcome>emptyList(),
                Collections.<Integer, SimulationReport.VmSummary>emptyMap(),
                Collections.<SimulationEvent>emptyList(),
                Collections.<SimulationReport.TaskOutcome>emptyList(),
                Collections.<Task>emptyList(), controlledConfig(), platform());

        assertEquals(0.0, metrics.getSuccessfulComputeJobOutcomeRate(), 0.0);
        assertEquals(0.0, metrics.getComputeJobOutcomeThroughputPerSecond(), 0.0);
        assertEquals(0.0, metrics.getSuccessfulLogicalTaskCompletionRate(), 0.0);
        assertEquals(0.0, metrics.getMeanVmModeledIntervalUtilization(), 0.0);
        assertFalse(metrics.isAllLogicalTasksCompletedSuccessfully());
        assertEquals("NO_LOGICAL_TASKS", metrics.getLogicalTaskCompletionStatus());
        assertNull(metrics.getLogicalTaskCompletionSeconds());
        assertNull(metrics.getTerminalLifecycleTailSeconds());
    }

    @Test
    void separatesSuccessfulLogicalCompletionFromSimulationEndTime() {
        List<SimulationReport.JobOutcome> jobs = Collections.singletonList(
                job(10, 0, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        0.0, 4.0, 4.0, 1.0, Collections.singletonList(1)));
        List<SimulationReport.TaskOutcome> tasks = Collections.singletonList(
                task(1, 10, 0, Cloudlet.SUCCESS, 0.0, 4.0));
        List<Task> sourceTasks = Collections.singletonList(new Task(1, 4000));

        SimulationMetrics metrics = SimulationMetrics.calculate(7.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks, controlledConfig(),
                platform());

        assertEquals(7.0, metrics.getMakespanSeconds(), 0.0);
        assertEquals(7.0, metrics.getSimulationEndSeconds(), 0.0);
        assertEquals("COMPLETED_SUCCESSFULLY", metrics.getLogicalTaskCompletionStatus());
        assertEquals(4.0, metrics.getLogicalTaskCompletionSeconds().doubleValue(), 0.0);
        assertEquals(3.0, metrics.getTerminalLifecycleTailSeconds().doubleValue(), 0.0);
    }

    @Test
    void aggregatesCpuAndDeclaredFileCostComponentsWithoutChangingTheirTotal() {
        SimulationReport.JobOutcome job = new SimulationReport.JobOutcome(10, 0,
                Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                0.0, 0.0, 1.0, 1.0, 1.25, 0.75, 500_000.0, 1,
                Collections.singletonList(1));
        SimulationMetrics metrics = SimulationMetrics.calculate(1.0,
                Collections.singletonList(job), summaries(),
                Collections.<SimulationEvent>emptyList(), Collections.singletonList(
                        task(1, 10, 0, Cloudlet.SUCCESS, 0.0, 1.0)),
                Collections.singletonList(new Task(1, 1000)), controlledConfig(), platform());

        assertEquals(2.0, metrics.getTotalModeledProcessingCost(), 1.0e-12);
        assertEquals(1.25, metrics.getTotalModeledCpuEnvelopeCost(), 1.0e-12);
        assertEquals(0.75, metrics.getTotalModeledDeclaredFileBandwidthCost(), 1.0e-12);
        assertEquals(500_000.0, metrics.getTotalModeledDeclaredFileBytes(), 1.0e-12);
    }

    @Test
    void deadlineObservationDistinguishesNotRequestedMetLateAndIncompleteRuns() {
        List<SimulationReport.JobOutcome> successfulJobs = Collections.singletonList(
                job(10, 0, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        0.0, 4.0, 4.0, 1.0, Collections.singletonList(1)));
        List<SimulationReport.TaskOutcome> successfulTasks = Collections.singletonList(
                task(1, 10, 0, Cloudlet.SUCCESS, 0.0, 4.0));
        List<Task> sourceTasks = Collections.singletonList(new Task(1, 4000));

        SimulationMetrics notRequested = SimulationMetrics.calculate(4.0, successfulJobs, summaries(),
                Collections.<SimulationEvent>emptyList(), successfulTasks, sourceTasks,
                controlledConfig(), platform());
        assertFalse(notRequested.isDeadlineObservationEnabled());
        assertEquals("NOT_REQUESTED", notRequested.getDeadlineObservationOutcome());
        assertFalse(notRequested.isDeadlineMet());
        assertEquals(0.0, notRequested.getDeadlineSlackSeconds(), 0.0);
        assertEquals(0.0, notRequested.getDeadlineTardinessSeconds(), 0.0);

        SimulationMetrics met = SimulationMetrics.calculate(4.0, successfulJobs, summaries(),
                Collections.<SimulationEvent>emptyList(), successfulTasks, sourceTasks,
                controlledConfig(4L), platform());
        assertTrue(met.isDeadlineObservationEnabled());
        assertEquals("MET", met.getDeadlineObservationOutcome());
        assertTrue(met.isDeadlineMet());
        assertEquals(0.0, met.getDeadlineSlackSeconds(), 0.0);
        assertEquals(0.0, met.getDeadlineTardinessSeconds(), 0.0);

        SimulationMetrics late = SimulationMetrics.calculate(4.0, successfulJobs, summaries(),
                Collections.<SimulationEvent>emptyList(), successfulTasks, sourceTasks,
                controlledConfig(3L), platform());
        assertEquals("MISSED_LATE", late.getDeadlineObservationOutcome());
        assertFalse(late.isDeadlineMet());
        assertEquals(-1.0, late.getDeadlineSlackSeconds(), 0.0);
        assertEquals(1.0, late.getDeadlineTardinessSeconds(), 0.0);

        List<SimulationReport.JobOutcome> failedJobs = Collections.singletonList(
                job(10, 0, Cloudlet.FAILED, Parameters.ClassType.COMPUTE.value,
                        0.0, 2.0, 2.0, 1.0, Collections.singletonList(1)));
        List<SimulationReport.TaskOutcome> failedTasks = Collections.singletonList(
                task(1, 10, 0, Cloudlet.FAILED, 0.0, 2.0));
        SimulationMetrics incomplete = SimulationMetrics.calculate(2.0, failedJobs, summaries(),
                Collections.<SimulationEvent>emptyList(), failedTasks, sourceTasks,
                controlledConfig(4L), platform());
        assertEquals("MISSED_INCOMPLETE_WORKFLOW", incomplete.getDeadlineObservationOutcome());
        assertFalse(incomplete.isDeadlineMet());
        assertEquals(2.0, incomplete.getDeadlineSlackSeconds(), 0.0);
        assertEquals(0.0, incomplete.getDeadlineTardinessSeconds(), 0.0);
        assertEquals("SIMULATION_END_SECONDS", incomplete.getDeadlineObservationTimeBasis());
        assertEquals("INCOMPLETE_LOGICAL_TASKS", incomplete.getLogicalTaskCompletionStatus());
        assertNull(incomplete.getLogicalTaskCompletionSeconds());
    }

    @Test
    void rejectsRetryEventsThatCannotBeMatchedToFailureEvidence() {
        List<SimulationReport.JobOutcome> jobs = Collections.singletonList(
                job(13, 0, Cloudlet.SUCCESS, Parameters.ClassType.COMPUTE.value,
                        0.0, 1.0, 1.0, 1.0, Collections.singletonList(1)));
        List<SimulationReport.TaskOutcome> tasks = Collections.singletonList(
                task(1, 13, 0, Cloudlet.SUCCESS, 0.0, 1.0));

        assertThrows(IllegalStateException.class, () -> SimulationMetrics.calculate(1.0, jobs,
                summaries(), Collections.singletonList(event(0, 0.0,
                        SimulationEventType.RETRY_JOB_CREATED, 13,
                        Collections.<String, Object>emptyMap())), tasks,
                Collections.singletonList(new Task(1, 1000)), controlledConfig(), platform()));
    }

    private static SimulationReport.JobOutcome job(int id, int vmId, int status, int classType,
            double start, double finish, double cpu, double cost, List<Integer> taskIds) {
        return new SimulationReport.JobOutcome(id, vmId, status, classType, start, start, finish,
                cpu, cost, taskIds.size(), taskIds);
    }

    private static SimulationReport.TaskOutcome task(int taskId, int jobId, int vmId,
            int jobStatus, double start, double finish) {
        return new SimulationReport.TaskOutcome(taskId, jobId, vmId, jobStatus, jobStatus,
                0, 1000L, start, finish);
    }

    private static Map<Integer, SimulationReport.VmSummary> summaries() {
        Map<Integer, SimulationReport.VmSummary> values =
                new LinkedHashMap<Integer, SimulationReport.VmSummary>();
        values.put(0, new SimulationReport.VmSummary(0, 3, 2, 0, 4.11, 3.11));
        values.put(1, new SimulationReport.VmSummary(1, 1, 0, 1, 1.0, 1.11));
        return values;
    }

    private static List<SimulationEvent> events() {
        return Arrays.asList(
                event(0, 0.0, SimulationEventType.PLANNING_COMPLETED, null,
                        SimulationEventRecorder.attributes("explicitPlannerDecision", true,
                                "planningDecisionElapsedNanos", 11L)),
                event(1, 0.10, SimulationEventType.JOB_READY, 11,
                        Collections.<String, Object>emptyMap()),
                event(2, 0.11, SimulationEventType.SCHEDULING_CYCLE, null,
                        SimulationEventRecorder.attributes("decisionElapsedNanos", 3L)),
                event(3, 0.11, SimulationEventType.SCHEDULING_DECISION, 11,
                        Collections.<String, Object>emptyMap()),
                event(4, 0.10, SimulationEventType.JOB_READY, 12,
                        Collections.<String, Object>emptyMap()),
                event(5, 0.20, SimulationEventType.SCHEDULING_DECISION, 12,
                        Collections.<String, Object>emptyMap()),
                event(6, 1.11, SimulationEventType.SCHEDULING_CYCLE, null,
                        SimulationEventRecorder.attributes("decisionElapsedNanos", 4L)),
                event(7, 0.20, SimulationEventType.DATA_STAGE_IN_MODELED, 11,
                        SimulationEventRecorder.attributes("modeledTransferSeconds", 1.0,
                                "requiredFileBytes", 10.0,
                                "modeledTransferFileCount", 1)),
                event(8, 0.30, SimulationEventType.DATA_STAGE_IN_MODELED, 12,
                        SimulationEventRecorder.attributes("modeledTransferSeconds", 3.0,
                                "requiredFileBytes", 20.0,
                                "modeledTransferFileCount", 2)),
                event(9, 1.11, SimulationEventType.RETRY_JOB_CREATED, 13,
                        SimulationEventRecorder.attributes("failedJobId", 12)));
    }

    private static SimulationEvent event(long sequence, double time, SimulationEventType type,
            Integer jobId, Map<String, Object> attributes) {
        return new SimulationEvent(sequence, time, type, jobId, null, null,
                Collections.<Integer>emptyList(), attributes);
    }

    private static SimulationConfig controlledConfig() {
        return controlledConfig(0L);
    }

    private static SimulationConfig controlledConfig(long deadline) {
        return SimulationConfig.builder("workflow.dax", 1).deadline(deadline).build();
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("metric-unit-platform")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }
}
