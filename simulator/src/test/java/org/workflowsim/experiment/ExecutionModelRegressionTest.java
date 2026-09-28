package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/** Independent work-conservation regressions: expected values come from the input model. */
class ExecutionModelRegressionTest {
    @TempDir Path directory;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void submissionInsideDatacenterCadenceNeverReceivesPreArrivalCpuWork() throws Exception {
        Path input = single("one-second", 1.0, false);
        for (double arrival : new double[] {0.25, 0.26, 0.30, 0.31, 0.32, 0.50, 1.30}) {
            SimulationConfig config = SimulationConfig.builder(
                    Arrays.asList(input.toString(), input.toString()), 2)
                    .workflowArrivalSeconds(Arrays.asList(0.0, arrival)).build();
            SimulationReport report = run(config, platform(1000.0, 1000.0));
            SimulationReport.JobOutcome second = jobForTask(report, 2);
            assertEquals(arrival, second.getStartTime(), 1e-9, "The other VM is idle");
            // CloudSim quantizes remaining work to MI: allow one MI, not the 0.1s cadence.
            assertTrue(second.getExecutionTime() >= 1.0 - 0.001 - 1e-9,
                    "Arrival " + arrival + " received CPU work from before submission: "
                            + second.getExecutionTime());
            assertTrue(report.getLogicalTaskCompletionSeconds() >= arrival + 1.0 - 0.001 - 1e-9);
        }
    }

    @Test
    void staggeredChainCannotBeatItsDependencyComputeLowerBound() throws Exception {
        Path first = single("first", 1.0, false);
        Path chain = write("chain", "<job id=\"b\" runtime=\"1\"/>"
                + "<job id=\"c\" runtime=\"1\"/>"
                + "<child ref=\"c\"><parent ref=\"b\"/></child>");
        for (double arrival : new double[] {0.25, 0.50}) {
            SimulationConfig config = SimulationConfig.builder(
                    Arrays.asList(first.toString(), chain.toString()), 2)
                    .workflowArrivalSeconds(Arrays.asList(0.0, arrival)).build();
            SimulationReport report = run(config, platform(1000.0, 1000.0));
            assertTrue(report.getLogicalTaskCompletionSeconds() >= arrival + 2.0 - 0.002 - 1e-9,
                    "A two-second dependency chain cannot finish before arrival + its work");
            for (SimulationReport.JobOutcome job : computeJobs(report)) {
                assertTrue(job.getExecutionTime() >= 1.0 - 0.001 - 1e-9,
                        "Every one-second attempt must receive its own CPU work");
            }
        }
    }

    @Test
    void matrixControlsTaskWindowAndWorkflowFlowTimeWithoutChangingDeclaredLength() throws Exception {
        for (double[] times : new double[][] {{10.0, 2.0}, {1.0, 20.0}}) {
            Path input = single("matrix-" + (int) times[0], times[0], false);
            SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                    .taskCostMatrix(TaskCostMatrix.builder().put(1, 0, times[1]).build()).build();
            SimulationReport report = run(config, platform(1000.0));
            SimulationReport.JobOutcome job = computeJobs(report).get(0);
            SimulationReport.TaskOutcome task = report.getTasks().get(0);
            assertEquals(times[1], job.getExecutionTime(), 1e-9);
            assertEquals(times[1], task.getFinishTime() - task.getStartTime(), 1e-9,
                    "The failure-observation and task-evidence window must use the matrix work");
            assertEquals((long) (times[0] * 1000.0), task.getLengthMi(),
                    "Keep declared input work distinct from effective per-attempt work");
            assertEquals(job.getStartTime(), task.getStartTime(), 1e-9);
            assertEquals(job.getFinishTime(), task.getFinishTime(), 1e-9);
            assertEquals(job.getFinishTime(), report.getWorkflowOutcomes().get(0).getFlowTimeSeconds(), 1e-9);
            assertTrue(task.getFinishTime() <= report.getMakespan() + 1e-9);
        }
    }

    @Test
    void sharedRetryStartsFromComputeWorkInsteadOfPreviouslyInjectedStageIn() throws Exception {
        SimulationConfig config = SimulationConfig.builder(single("shared-retry", 1.0, true).toString(), 1)
                .randomSeed(5L).failureModel(failures(2.0)).build();
        SimulationReport report = run(config, platform(1000.0));
        assertTrue(report.getFailedJobs() > 0, "The fixed input must actually exercise retry");
        for (SimulationReport.JobOutcome job : computeJobs(report)) {
            assertEquals(2.0, job.getExecutionTime(), 1e-9,
                    "Each attempt is one second of compute plus one second of SHARED stage-in");
        }
        assertEquals(1, report.getMetrics().getLogicalTaskCount());
        assertTrue(report.isWorkflowCompletedSuccessfully());
    }

    @Test
    void staticRetriesPreserveMappingAndEveryAttemptHasDataPreparationEvidence() throws Exception {
        Path input = single("mapped-retry", 1.0, true);
        List<DataMovementModel> models = Arrays.asList(DataMovementModel.preExecutionTransferDelayV1(),
                DataMovementModel.preExecutionTransferDelayWithContentionV1());
        for (DataMovementModel model : models) {
            SimulationConfig config = SimulationConfig.builder(input.toString(), 2)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MET)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model)
                    .randomSeed(22L).failureModel(failures(2.0)).build();
            SimulationReport report = run(config, platform(1000.0, 2000.0));
            assertTrue(report.getFailedJobs() > 0, "The fixed input must actually exercise retry");
            for (SimulationReport.JobOutcome job : computeJobs(report)) {
                assertEquals(1, job.getVmId(), "STATIC must preserve the selected fastest VM");
                assertEquals(0.5, job.getExecutionTime(), 1e-9);
                SimulationEvent transfer = null;
                for (SimulationEvent event : report.getEvents()) {
                    if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED
                            && Integer.valueOf(job.getJobId()).equals(event.getJobId())) {
                        transfer = event;
                    }
                }
                assertNotNull(transfer, "Data preparation cannot silently disappear for a retry");
            }
        }
    }

    @Test
    void rawWorkBeyondIntegerRangeIsNotTruncatedDuringJobCreation() throws Exception {
        Path input = single("large-raw-work", 3_000_000.0, false);
        SimulationReport report = run(SimulationConfig.builder(input.toString(), 1).build(),
                platform(1_000_000.0));
        // 3,000,000 input seconds * 1,000 reference MIPS = 3,000,000,000 MI.
        // At 1,000,000 MIPS that is 3,000 seconds; an int accumulator cannot hold the MI.
        assertEquals(3000.0, computeJobs(report).get(0).getExecutionTime(), 1e-9);
        assertEquals(3_000_000_000L, report.getTasks().get(0).getLengthMi());
    }

    @Test
    void zeroDataMovementDoesNotChangeConfiguredWorkflowEngineOverhead() throws Exception {
        for (int count : new int[] {1, 3}) {
            StringBuilder body = new StringBuilder();
            for (int i = 0; i < count; i++) {
                body.append("<job id=\"t").append(i).append("\" runtime=\"1\"/>");
            }
            Path input = write("no-data-" + count, body.toString());
            for (int interval : new int[] {0, 1, 2}) {
                org.workflowsim.utils.OverheadModelConfig overhead =
                        org.workflowsim.utils.OverheadModelConfig.builder()
                        .workflowEngineDelayInterval(interval)
                        .workflowEngineDelays(Collections.singletonMap(0, DistributionSpec.of(
                                DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 1.0)))
                        .build();
                Double expected = null;
                for (DataMovementModel model : Arrays.asList(DataMovementModel.legacyWorkflowsimV1(),
                        DataMovementModel.preExecutionTransferDelayV1(),
                        DataMovementModel.preExecutionTransferDelayWithContentionV1())) {
                    SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                            .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MET)
                            .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                            .fileSystem(ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model)
                            .overheadModel(overhead).randomSeed(7L).build();
                    SimulationReport report = run(config, platform(1000.0));
                    assertEquals(0.0, report.getMetrics().getTotalModeledDataTransferSeconds(), 0.0);
                    assertTrue(report.getMetrics().getMeanComputeReadyToDecisionDelaySeconds() > 0.0,
                            "A configured delay must apply to a partial/unsplit batch too");
                    if (expected == null) { expected = report.getMakespan(); }
                    assertEquals(expected.doubleValue(), report.getMakespan(), 1e-9,
                            "Zero data: network path must not change WED, interval=" + interval);
                }
            }
        }
    }

    private SimulationReport run(SimulationConfig config, PlatformProfile platform) throws Exception {
        Log.disable();
        return new SimulationRunner().run(config, platform);
    }

    private Path single(String name, double runtime, boolean externalInput) throws Exception {
        return write(name, "<job id=\"one\" runtime=\"" + runtime + "\">"
                + (externalInput ? "<uses name=\"external.dat\" link=\"input\" size=\"1000000\"/>" : "")
                + "</job>");
    }

    private Path write(String name, String body) throws Exception {
        Path path = directory.resolve(name + ".dax");
        Files.write(path, ("<adag version=\"3.3\">" + body + "</adag>").getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile platform(double... mips) {
        PlatformProfile.Builder builder = PlatformProfile.builder("execution-invariants");
        for (int id = 0; id < mips.length; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 1, mips[id], 1024, 1000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(id, mips[id], 1, 512, 1L, 10000L,
                    "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(id, id);
        }
        return builder.storage(new PlatformProfile.StorageSpec(1000000L, 1)).build();
    }

    private static FailureModelConfig failures(double scale) {
        return FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {{DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, scale, 1.0)}})
                .maxTotalRetryJobs(32).build();
    }

    private static List<SimulationReport.JobOutcome> computeJobs(SimulationReport report) {
        List<SimulationReport.JobOutcome> result = new ArrayList<>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) { result.add(job); }
        }
        return result;
    }

    private static SimulationReport.JobOutcome jobForTask(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : computeJobs(report)) {
            if (job.getTaskIds().equals(Collections.singletonList(taskId))) { return job; }
        }
        throw new AssertionError("Missing task " + taskId);
    }
}
