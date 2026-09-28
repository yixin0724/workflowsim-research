package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Four joint-model cells, twelve runs: base A/B/C/D, reverse-order exact replay, then deadline observers.
 * These are replay/causality properties, not independent statistical samples or cross-cell performance claims.
 * WED has no per-batch sample ledger: replay includes its trajectory, but does not independently reconstruct
 * its random draws or identify WED alone from ready-to-decision waiting. Queue/post samples are recorded.
 */
class StochasticArrivalInteractionIntegrationTest {
    private static final long[] RAW_MI = {500L, 1000L, 750L};
    // Independently calculated from the explicit seconds table and VM MIPS: no production cost helper.
    private static final long[][] MATRIX_MI = {{750L, 2500L}, {1500L, 1000L}, {1000L, 1750L}};
    private static final Set<Integer> SOURCE_IDS = new HashSet<Integer>(Arrays.asList(1, 2, 3, 4, 5, 6));

    @TempDir Path directory;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void jointStochasticArrivalModelReplaysAndDeadlineRemainsOnlyAnObserver() throws Exception {
        Path input = directory.resolve("two-roots-and-join.dax");
        Files.write(input, ("<adag version=\"3.3\">"
                + "<job id=\"a\" runtime=\"0.5\"/><job id=\"b\" runtime=\"1\"/>"
                + "<job id=\"c\" runtime=\"0.75\"/>"
                + "<child ref=\"c\"><parent ref=\"a\"/><parent ref=\"b\"/></child>"
                + "</adag>").getBytes(StandardCharsets.UTF_8));
        List<Cell> cells = Arrays.asList(
                new Cell("A", Arrays.asList(0.5, 0.75), false, 5L, 1L),
                new Cell("B", Arrays.asList(0.5, 0.75), true, 22L, 30L),
                new Cell("C", Arrays.asList(2.0, 4.0), false, 22L, 1L),
                new Cell("D", Arrays.asList(2.0, 4.0), true, 5L, 30L));
        PlatformProfile platform = platform();
        SimulationRunner runner = new SimulationRunner();
        Map<String, SimulationConfig> configs = new LinkedHashMap<String, SimulationConfig>();
        Map<String, SimulationReport> baselines = new LinkedHashMap<String, SimulationReport>();
        Map<String, Integer> baselineRetryCounts = new LinkedHashMap<String, Integer>();
        int baselineRetries = 0;
        int runs = 0;
        Log.disable();

        for (Cell cell : cells) {
            SimulationConfig config = configuration(input, cell);
            configs.put(cell.id, config);
            SimulationReport report = runner.run(config, platform);
            runs++;
            int retries = assertReport(report, cell, 0L, cell.id + " base");
            baselineRetryCounts.put(cell.id, retries);
            baselineRetries += retries;
            baselines.put(cell.id, report);
        }
        // Interleave different matrices/arrivals/seeds before replay, rather than only testing adjacent runs.
        for (int index = cells.size() - 1; index >= 0; index--) {
            Cell cell = cells.get(index);
            SimulationReport replay = runner.run(configs.get(cell.id), platform);
            runs++;
            assertReport(replay, cell, 0L, cell.id + " replay");
            assertEquals(trajectory(baselines.get(cell.id)), trajectory(replay), cell.id + " exact replay");
        }
        for (Cell cell : cells) {
            SimulationReport observed = runner.run(configs.get(cell.id).toBuilder()
                    .deadline(cell.deadline).build(), platform);
            runs++;
            assertReport(observed, cell, cell.deadline, cell.id + " deadline observer");
            assertEquals(trajectory(baselines.get(cell.id)), trajectory(observed),
                    cell.id + " deadline must not change decisions, arrivals, overhead samples, or retries");
        }
        assertEquals(12, runs);
        // No per-cell failure-count golden. Only base runs count toward actual interaction coverage.
        assertTrue(baselineRetries > 0, "No base cell exercised a retry: " + baselineRetryCounts
                + "; report this coverage gap rather than treating empty lineage checks as verification");
        System.out.println("STOCHASTIC_ARRIVAL_INTERACTION runs=" + runs
                + " baseRetryCounts=" + baselineRetryCounts);
    }

    private static int assertReport(SimulationReport report, Cell cell, long deadline, String context) {
        Map<Integer, SimulationEvent> ready = events(report, SimulationEventType.JOB_READY, context);
        Map<Integer, SimulationEvent> decisions = events(report, SimulationEventType.SCHEDULING_DECISION, context);
        Map<Integer, SimulationEvent> dispatched = events(report, SimulationEventType.JOB_DISPATCHED, context);
        Map<Integer, SimulationEvent> returned = events(report, SimulationEventType.JOB_RETURNED, context);
        Map<Integer, SimulationEvent> failed = events(report, SimulationEventType.JOB_FAILED, context);
        Map<Integer, SimulationEvent> retries = events(report, SimulationEventType.RETRY_JOB_CREATED, context);
        Map<Integer, SimulationEvent> transfers = events(report, SimulationEventType.DATA_STAGE_IN_MODELED, context);
        Map<Integer, SimulationEvent> executions = events(report, SimulationEventType.TASK_EXECUTION_MODELED, context);
        Map<Integer, SimulationReport.JobOutcome> jobs = new LinkedHashMap<Integer, SimulationReport.JobOutcome>();
        Set<Integer> computeIds = new HashSet<Integer>();
        Set<Integer> failedIds = new HashSet<Integer>();
        Map<Integer, Double> earliestSuccess = new LinkedHashMap<Integer, Double>();
        Map<Integer, Integer> initialVmByTask = new LinkedHashMap<Integer, Integer>();
        double queueSum = 0.0;
        double postSum = 0.0;
        double end = report.getSimulationEndSeconds();

        for (SimulationReport.JobOutcome job : report.getJobs()) {
            int id = job.getJobId();
            assertNull(jobs.put(id, job), context + " duplicate Job ID " + id);
            SimulationEvent readyEvent = required(ready, id, context);
            SimulationEvent decision = required(decisions, id, context);
            SimulationEvent dispatch = required(dispatched, id, context);
            SimulationEvent returnEvent = required(returned, id, context);
            double queue = number(dispatch, "queueDelaySeconds", context);
            double post = number(returnEvent, "postDelaySeconds", context);
            queueSum += queue;
            postSum += post;
            assertEquals(queue, number(decision, "queueDelaySeconds", context), 0.0, context);
            assertEquals(job.getFinishTime(), returnEvent.getSimulationTime(), 0.0, context);
            assertEquals(job.getStatus(), integer(returnEvent, "jobStatus", context), context);
            assertNotBefore(decision.getSimulationTime(), readyEvent.getSimulationTime(), context);
            assertNotBefore(job.getStartTime(), decision.getSimulationTime() + queue, context);
            assertNotBefore(end, returnEvent.getSimulationTime() + post, context);
            assertEquals(job.getTaskIds(), readyEvent.getTaskIds(), context);

            if (job.getClassType() == Parameters.ClassType.STAGE_IN.value) {
                assertEquals(Cloudlet.SUCCESS, job.getStatus(), context);
                assertTrue(job.getTaskIds().isEmpty(), context + " stage-in is not a source Task");
                continue;
            }
            assertEquals(Parameters.ClassType.COMPUTE.value, job.getClassType(), context);
            computeIds.add(id);
            assertEquals(1, job.getTaskCount(), context);
            assertEquals(1, job.getTaskIds().size(), context);
            int taskId = job.getTaskIds().get(0);
            sourceRow(taskId, context);
            assertTrue(job.getVmId() == 0 || job.getVmId() == 1, context);
            assertNotBefore(readyEvent.getSimulationTime(), cell.arrivals.get((taskId - 1) / 3), context);
            if (!retries.containsKey(id)) {
                assertNull(initialVmByTask.put(taskId, job.getVmId()), context + " duplicate initial attempt");
                assertFalse(readyEvent.getAttributes().containsKey("retryOfFailedJobId"), context);
            }
            if (job.getStatus() == Cloudlet.FAILED) {
                failedIds.add(id);
            } else {
                assertEquals(Cloudlet.SUCCESS, job.getStatus(), context);
                Double previous = earliestSuccess.get(taskId);
                if (previous == null || job.getFinishTime() < previous) {
                    earliestSuccess.put(taskId, job.getFinishTime());
                }
            }
        }
        assertEquals(jobs.keySet(), ready.keySet(), context);
        assertEquals(jobs.keySet(), decisions.keySet(), context);
        assertEquals(jobs.keySet(), dispatched.keySet(), context);
        assertEquals(jobs.keySet(), returned.keySet(), context);
        assertEquals(computeIds, transfers.keySet(), context);
        assertEquals(computeIds, executions.keySet(), context);
        assertEquals(failedIds, failed.keySet(), context);
        assertEquals(SOURCE_IDS, initialVmByTask.keySet(), context);
        assertEquals(SOURCE_IDS, earliestSuccess.keySet(), context + " completion is over source Tasks");
        assertTrue(queueSum > 0.0, context + " configured queue overhead must be observed");
        assertTrue(postSum > 0.0, context + " configured post overhead must be observed");

        Set<Integer> taskAttemptJobs = new HashSet<Integer>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            SimulationReport.JobOutcome job = jobs.get(task.getJobId());
            assertNotNull(job, context);
            assertTrue(computeIds.contains(task.getJobId()), context);
            assertTrue(taskAttemptJobs.add(task.getJobId()), context + " duplicate Task attempt");
            int row = sourceRow(task.getTaskId(), context);
            assertEquals(Collections.singletonList(task.getTaskId()), job.getTaskIds(), context);
            assertEquals(job.getVmId(), task.getVmId(), context);
            assertEquals(initialVmByTask.get(task.getTaskId()).intValue(), task.getVmId(),
                    context + " STATIC retries must retain their VM mapping");
            assertEquals(job.getStatus(), task.getJobStatus(), context);
            if (job.getStatus() == Cloudlet.SUCCESS) {
                assertEquals(Cloudlet.SUCCESS, task.getTaskStatus(), context);
            }
            long expectedMi = cell.matrix ? MATRIX_MI[row][task.getVmId()] : RAW_MI[row];
            assertEquals(RAW_MI[row], task.getLengthMi(), context + " source MI");
            assertEquals(expectedMi, task.getEffectiveExecutionLengthMi(), context + " effective attempt MI");
            SimulationEvent execution = required(executions, task.getJobId(), context);
            assertEquals(task.getTaskId(), integer(execution, "taskId", context), context);
            assertEquals(RAW_MI[row], (long) integer(execution, "taskLengthMi", context), context);
            assertEquals(expectedMi, (long) integer(execution, "effectiveExecutionLengthMi", context), context);
            assertEquals(task.getStartTime(), number(execution, "taskStartTime", context), 0.0, context);
            assertEquals(task.getFinishTime(), number(execution, "taskFinishTime", context), 0.0, context);
            SimulationEvent transfer = required(transfers, task.getJobId(), context);
            assertEquals(0.0, number(transfer, "requiredFileBytes", context), 0.0, context);
            assertEquals(0, integer(transfer, "modeledTransferFileCount", context), context);
            assertEquals(0.0, number(transfer, "modeledTransferSeconds", context), 0.0, context);
            assertEquals("LEGACY_WORKFLOWSIM_V1", transfer.getAttributes().get("dataMovementModel"), context);
        }
        assertEquals(computeIds, taskAttemptJobs, context);

        Set<Integer> failedParents = new HashSet<Integer>();
        Set<Integer> retriedTasks = new HashSet<Integer>();
        for (Map.Entry<Integer, SimulationEvent> entry : retries.entrySet()) {
            int retryId = entry.getKey();
            SimulationEvent created = entry.getValue();
            int parentId = integer(created, "failedJobId", context);
            SimulationReport.JobOutcome retry = jobs.get(retryId);
            SimulationReport.JobOutcome parent = jobs.get(parentId);
            assertNotNull(retry, context);
            assertNotNull(parent, context);
            assertTrue(retryId != parentId, context);
            assertTrue(failedParents.add(parentId), context + " NOOP creates one retry per failed Job");
            assertEquals(Cloudlet.FAILED, parent.getStatus(), context);
            assertTrue(computeIds.contains(parentId) && computeIds.contains(retryId), context);
            assertEquals(parent.getTaskIds(), retry.getTaskIds(), context);
            assertEquals(parent.getVmId(), retry.getVmId(), context);
            assertEquals(retry.getTaskIds(), created.getTaskIds(), context);
            SimulationEvent retryReady = required(ready, retryId, context);
            assertEquals(parentId, integer(retryReady, "retryOfFailedJobId", context), context);
            assertNotBefore(retryReady.getSimulationTime(), created.getSimulationTime(), context);
            SimulationEvent parentReturn = required(returned, parentId, context);
            assertNotBefore(created.getSimulationTime(), parentReturn.getSimulationTime()
                    + number(parentReturn, "postDelaySeconds", context), context);
            retriedTasks.addAll(retry.getTaskIds());
        }
        assertEquals(failedIds, failedParents, context);

        SimulationMetrics metrics = report.getMetrics();
        assertEquals(6, metrics.getLogicalTaskCount(), context);
        assertEquals(6, metrics.getSuccessfullyCompletedLogicalTaskCount(), context);
        assertEquals(6, metrics.getInitialComputeJobOutcomeCount(), context);
        assertEquals(computeIds.size(), metrics.getComputeJobOutcomeCount(), context);
        assertEquals(computeIds.size(), metrics.getLogicalTaskAttemptCount(), context);
        assertEquals(failedIds.size(), metrics.getFailedComputeJobOutcomeCount(), context);
        assertEquals(retries.size(), metrics.getRetryJobCreatedCount(), context);
        assertEquals(retries.size(), metrics.getCompletedRetryComputeJobOutcomeCount(), context);
        assertEquals(retriedTasks.size(), metrics.getRetriedLogicalTaskCount(), context);
        double logicalCompletion = Collections.max(earliestSuccess.values());
        assertTrue(report.isWorkflowCompletedSuccessfully(), context);
        assertEquals("COMPLETED_SUCCESSFULLY", report.getLogicalTaskCompletionStatus(), context);
        assertEquals(logicalCompletion, report.getLogicalTaskCompletionSeconds().doubleValue(), 0.0, context);
        assertNotBefore(end, logicalCompletion, context);
        assertEquals(Math.max(0.0, end - logicalCompletion),
                report.getTerminalLifecycleTailSeconds().doubleValue(), 0.0, context);
        assertFalse(metrics.isControlledSharedStorageCriticalPathReferenceAvailable(), context);
        assertEquals(0.0, metrics.getControlledSharedStorageCriticalPathLowerBoundSeconds(), 0.0, context);
        assertEquals(0.0, metrics.getControlledSharedStorageScheduleLengthRatio(), 0.0, context);
        assertEquals(deadline > 0L, metrics.isDeadlineObservationEnabled(), context);
        assertEquals("SIMULATION_END_SECONDS", metrics.getDeadlineObservationTimeBasis(), context);
        String outcome = deadline == 0L ? "NOT_REQUESTED" : end <= deadline ? "MET" : "MISSED_LATE";
        assertEquals(outcome, metrics.getDeadlineObservationOutcome(), context);
        assertEquals("MET".equals(outcome), metrics.isDeadlineMet(), context);
        assertEquals(deadline == 0L ? 0.0 : deadline - end, metrics.getDeadlineSlackSeconds(), 0.0, context);
        assertEquals(deadline == 0L ? 0.0 : Math.max(0.0, end - deadline),
                metrics.getDeadlineTardinessSeconds(), 0.0, context);
        assertSourcesAndWorkflowArrivals(report, cell, context);
        return retries.size();
    }

    private static void assertSourcesAndWorkflowArrivals(SimulationReport report, Cell cell, String context) {
        Set<Integer> graphIds = new HashSet<Integer>();
        for (SimulationReport.TaskNode node : report.getWorkflowGraph()) {
            int row = sourceRow(node.getTaskId(), context);
            assertTrue(graphIds.add(node.getTaskId()), context);
            int first = ((node.getTaskId() - 1) / 3) * 3 + 1;
            assertEquals(row == 2 ? Arrays.asList(first, first + 1) : Collections.<Integer>emptyList(),
                    node.getParentIds(), context);
            assertEquals(row == 2 ? Collections.<Integer>emptyList() : Collections.singletonList(first + 2),
                    node.getChildIds(), context);
        }
        assertEquals(SOURCE_IDS, graphIds, context + " source graph excludes stage-in/retry nodes");
        assertEquals(6, report.getWorkflowProfile().getTaskCount(), context);
        assertEquals(2, report.getInputReports().size(), context);
        assertEquals(2, report.getWorkflowOutcomes().size(), context);
        Set<Integer> announced = new HashSet<Integer>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.WORKFLOW_ARRIVED) {
                int index = integer(event, "workflowIndex", context);
                assertTrue(index == 0 || index == 1, context);
                assertTrue(announced.add(index), context + " duplicate workflow arrival");
                assertEquals(cell.arrivals.get(index).doubleValue(), number(event, "arrivalSecond", context),
                        0.0, context);
                assertNotBefore(event.getSimulationTime(), cell.arrivals.get(index), context);
            }
        }
        assertEquals(new HashSet<Integer>(Arrays.asList(0, 1)), announced, context);
        for (int index = 0; index < 2; index++) {
            SimulationReport.WorkflowOutcome workflow = report.getWorkflowOutcomes().get(index);
            int first = index * 3 + 1;
            int last = first + 2;
            assertEquals(3, report.getInputReports().get(index).getTaskCount(), context);
            assertEquals(index, workflow.getIndex(), context);
            assertEquals(first, workflow.getFirstTaskId(), context);
            assertEquals(last, workflow.getLastTaskId(), context);
            assertEquals(3, workflow.getTaskCount(), context);
            assertEquals(cell.arrivals.get(index).doubleValue(), workflow.getArrivalSecond(), 0.0, context);
            double lastSuccess = Double.NEGATIVE_INFINITY;
            for (SimulationReport.TaskOutcome task : report.getTasks()) {
                if (task.getTaskId() >= first && task.getTaskId() <= last
                        && task.getTaskStatus() == Cloudlet.SUCCESS) {
                    lastSuccess = Math.max(lastSuccess, task.getFinishTime());
                }
            }
            assertTrue(Double.isFinite(lastSuccess), context);
            assertEquals(lastSuccess, workflow.getLastSuccessFinishSecond(), 0.0, context);
            assertEquals(lastSuccess - cell.arrivals.get(index), workflow.getFlowTimeSeconds(), 0.0, context);
        }
    }

    private static SimulationConfig configuration(Path input, Cell cell) {
        return SimulationConfig.builder(Arrays.asList(input.toString(), input.toString()), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .clusteringParameters(new ClusteringParameters(0, 0,
                        ClusteringParameters.ClusteringMethod.NONE, null))
                .runtimeReferenceMips(1000.0).runtimeScale(1.0).cloudSimMinEventIntervalSeconds(0.1)
                .costModel(Parameters.CostModel.DATACENTER).randomSeed(cell.seed)
                .workflowArrivalSeconds(cell.arrivals)
                .failureModel(FailureModelConfig.builder()
                        .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                        .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                        .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                        .generatorSpecs(new DistributionSpec[][] {{weibull(4.0, 1.0)}})
                        .maxTotalRetryJobs(32).build())
                .overheadModel(OverheadModelConfig.builder().workflowEngineDelayInterval(1)
                        .workflowEngineDelays(Collections.singletonMap(0, weibull(0.03, 2.0)))
                        .queueDelays(Collections.singletonMap(0, weibull(0.02, 2.0)))
                        .postDelays(Collections.singletonMap(0, weibull(0.04, 2.0))).build())
                .taskCostMatrix(cell.matrix ? matrix() : null).build();
    }

    private static TaskCostMatrix matrix() {
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        double[][] seconds = {{0.75, 1.25}, {1.5, 0.5}, {1.0, 0.875}};
        for (int taskId = 1; taskId <= 6; taskId++) {
            for (int vmId = 0; vmId <= 1; vmId++) {
                builder.put(taskId, vmId, seconds[(taskId - 1) % 3][vmId]);
            }
        }
        return builder.build();
    }

    private static DistributionSpec weibull(double scale, double shape) {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, scale, shape);
    }

    private static PlatformProfile platform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("stochastic-arrival-interaction");
        for (int id = 0; id < 2; id++) {
            double mips = id == 0 ? 1000.0 : 2000.0;
            builder.addHost(new PlatformProfile.HostSpec(id, 1, mips, 1024, 1000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, mips, 1, 512, 1000L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(id, id);
        }
        return builder.storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();
    }

    private static int sourceRow(int taskId, String context) {
        assertTrue(SOURCE_IDS.contains(taskId), context + " unexpected source Task " + taskId);
        return (taskId - 1) % 3;
    }

    private static Map<Integer, SimulationEvent> events(SimulationReport report, SimulationEventType type,
            String context) {
        Map<Integer, SimulationEvent> result = new LinkedHashMap<Integer, SimulationEvent>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == type) {
                assertNotNull(event.getJobId(), context + " missing Job ID for " + type);
                assertNull(result.put(event.getJobId(), event), context + " duplicate " + type);
            }
        }
        return result;
    }

    private static SimulationEvent required(Map<Integer, SimulationEvent> events, int id, String context) {
        SimulationEvent event = events.get(id);
        assertNotNull(event, context + " missing required event for Job " + id);
        return event;
    }

    private static double number(SimulationEvent event, String key, String context) {
        Object value = event.getAttributes().get(key);
        assertTrue(value instanceof Number, context + " missing numeric " + key);
        double number = ((Number) value).doubleValue();
        assertTrue(Double.isFinite(number) && number >= 0.0, context + " invalid " + key);
        return number;
    }

    private static int integer(SimulationEvent event, String key, String context) {
        double value = number(event, key, context);
        assertTrue(value <= Integer.MAX_VALUE && value == Math.rint(value), context + " invalid integer " + key);
        return (int) value;
    }

    private static void assertNotBefore(double actual, double earliest, String context) {
        assertTrue(Double.isFinite(actual) && Double.isFinite(earliest), context);
        double tolerance = 1.0e-8 + 4.0 * Math.ulp(Math.max(Math.abs(actual), Math.abs(earliest)));
        assertTrue(actual + tolerance >= earliest, context + " time " + actual + " precedes " + earliest);
    }

    /** Remove only the two documented host wall-clock samples, never stochastic/model evidence. */
    private static List<Object> trajectory(SimulationReport report) {
        List<Object> rows = new ArrayList<Object>();
        rows.add(Arrays.asList("end", report.getSimulationEndSeconds(), report.getLogicalTaskCompletionSeconds(),
                report.getLogicalTaskCompletionStatus(), report.getTerminalLifecycleTailSeconds()));
        rows.add(report.getActualVmHostAssignments());
        rows.add(report.getVmSummaries());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            rows.add(Arrays.asList("job", job.getJobId(), job.getVmId(), job.getStatus(), job.getClassType(),
                    job.getSubmissionTime(), job.getStartTime(), job.getFinishTime(), job.getCpuTime(),
                    job.getWaitingTime(), job.getExecutionTime(), job.getResponseTime(),
                    job.getModeledCpuEnvelopeCost(), job.getModeledDeclaredFileBandwidthCost(),
                    job.getModeledDeclaredFileBytes(), job.getModeledProcessingCost(), job.getTaskCount(), job.getTaskIds()));
        }
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            rows.add(Arrays.asList("task", task.getTaskId(), task.getJobId(), task.getVmId(), task.getJobStatus(),
                    task.getTaskStatus(), task.getDepth(), task.getLengthMi(), task.getEffectiveExecutionLengthMi(),
                    task.getStartTime(), task.getFinishTime(), task.hasExactJobTiming()));
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

    private static final class Cell {
        private final String id;
        private final List<Double> arrivals;
        private final boolean matrix;
        private final long seed;
        private final long deadline;

        private Cell(String id, List<Double> arrivals, boolean matrix, long seed, long deadline) {
            this.id = id;
            this.arrivals = arrivals;
            this.matrix = matrix;
            this.seed = seed;
            this.deadline = deadline;
        }
    }
}
