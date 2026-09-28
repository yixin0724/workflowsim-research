package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** End-to-end fractional-byte evidence and hold-time checks, without a production-helper oracle. */
class FractionalTransferEvidenceTest {
    private static final double INPUT_BYTES = 0.5;
    private static final double MIN_HOLD_SECONDS = 0.1;
    private static final double TIME_EPSILON = 1.0e-9;

    private enum InputFormat { DAX, WFCOMMONS }
    private enum Track { LEGACY, NO_CONTENTION, ENDPOINT_CONTENTION, FAT_TREE_CONTENTION }

    @TempDir Path temporary;
    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreLogging() { Log.setDisabled(loggingWasDisabled); }

    @ParameterizedTest(name = "fractional demand {0}/{1}")
    @MethodSource("allTracks")
    void fractionalInputDemandSurvivesParserEventsAndMetrics(InputFormat format, Track track) throws Exception {
        Trace trace = run(format, track, INPUT_BYTES);
        assertEquals(1, trace.report.getWorkflowProfile().getTaskCount());
        assertEquals(0, trace.report.getWorkflowProfile().getEdgeCount());
        assertEquals(1, trace.report.getMetrics().getDataStageInModelObservationCount());
        assertEquals(1.0, number(trace.stageIn, "modeledTransferFileCount"), 0.0);
        assertAll(
                () -> assertEquals(INPUT_BYTES, trace.report.getWorkflowProfile().getTotalDistinctFileBytes(), 0.0),
                () -> assertEquals(INPUT_BYTES, trace.report.getWorkflowProfile().getTotalDistinctExternalInputBytes(), 0.0),
                () -> assertEquals(INPUT_BYTES, number(trace.stageIn, "requiredFileBytes"), 0.0,
                        "DATA_STAGE_IN_MODELED must not narrow accepted double bytes to long"),
                () -> assertEquals(INPUT_BYTES, trace.report.getMetrics().getTotalModeledRequiredInputBytes(), 0.0,
                        "the metric must retain the same fractional input demand"));
    }

    @ParameterizedTest(name = "stage-in creation demand {0}/{1}")
    @MethodSource("allTracks")
    void stageInCreationAlsoRetainsFractionalExternalInputBytes(InputFormat format, Track track) throws Exception {
        Trace trace = run(format, track, INPUT_BYTES);
        List<SimulationEvent> creation = trace.report.getEvents().stream()
                .filter(event -> event.getType() == SimulationEventType.STAGE_IN_JOB_CREATED)
                .collect(Collectors.toList());
        assertEquals(1, creation.size());
        assertEquals(1.0, number(creation.get(0), "inputFileCount"), 0.0);
        assertEquals(INPUT_BYTES, number(creation.get(0), "inputBytes"), 0.0,
                "The bootstrap event must preserve the same accepted fractional demand");
    }

    @ParameterizedTest(name = "positive transfer hold {0}/{1}")
    @MethodSource("preExecutionTracks")
    void everyPositivePreExecutionRootTransferReceivesTheMinimumHold(InputFormat format, Track track) throws Exception {
        Trace trace = run(format, track, INPUT_BYTES);
        // The fixture declares 0.5 bytes and a 1 MB/s destination endpoint. This hand calculation
        // is independent of estimateTransferSecondsForFiles and all production byte-sum helpers.
        assertEquals(0.5 / 1_000_000.0, number(trace.stageIn, "modeledTransferSeconds"), 1.0e-15);
        assertEquals(trace.ready.getSimulationTime(), trace.stageIn.getSimulationTime(), TIME_EPSILON);
        assertAll(
                () -> assertEquals(MIN_HOLD_SECONDS,
                        trace.decision.getSimulationTime() - trace.ready.getSimulationTime(), TIME_EPSILON,
                        "a positive sub-interval transfer cannot take the zero-transfer dispatch path"),
                () -> assertEquals(MIN_HOLD_SECONDS,
                        trace.job.getStartTime() - trace.ready.getSimulationTime(), TIME_EPSILON),
                () -> assertEquals(MIN_HOLD_SECONDS,
                        number(trace.taskExecution, "taskStartTime") - trace.stageIn.getSimulationTime(), TIME_EPSILON));
        if (track == Track.ENDPOINT_CONTENTION || track == Track.FAT_TREE_CONTENTION) {
            assertEquals(1.0, number(trace.stageIn, "contentionTransferGroupCount"), 0.0,
                    "the positive external input must create one transfer group");
        }
    }

    @ParameterizedTest(name = "zero transfer has no hold {0}/{1}")
    @MethodSource("preExecutionTracks")
    void zeroByteRootInputIsNotChargedAPositiveHold(InputFormat format, Track track) throws Exception {
        Trace trace = run(format, track, 0.0);
        assertAll(
                () -> assertEquals(0.0, number(trace.stageIn, "requiredFileBytes"), 0.0),
                () -> assertEquals(0.0, number(trace.stageIn, "modeledTransferSeconds"), 0.0),
                () -> assertEquals(0.0, trace.report.getMetrics().getTotalModeledRequiredInputBytes(), 0.0),
                () -> assertEquals(trace.ready.getSimulationTime(), trace.decision.getSimulationTime(), TIME_EPSILON),
                () -> assertEquals(trace.ready.getSimulationTime(), trace.job.getStartTime(), TIME_EPSILON),
                () -> assertEquals(trace.ready.getSimulationTime(), number(trace.taskExecution, "taskStartTime"), TIME_EPSILON));
        if (track == Track.ENDPOINT_CONTENTION || track == Track.FAT_TREE_CONTENTION) {
            assertEquals(0.0, number(trace.stageIn, "contentionTransferGroupCount"), 0.0);
        }
    }

    private Trace run(InputFormat format, Track track, double bytes) throws Exception {
        Path input = temporary.resolve(format == InputFormat.DAX ? "single-root.dax" : "single-root.json");
        Files.write(input, workflow(format, bytes).getBytes(StandardCharsets.UTF_8));
        PlatformProfile.Builder platform = PlatformProfile.builder("fractional-transfer-" + track)
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 1L, 10000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(0, 0);
        if (track == Track.FAT_TREE_CONTENTION) {
            platform.networkTopology(NetworkTopologySpec.fatTree(2, 1.0));
        }
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MET)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(model(track))
                .failureModel(FailureModelConfig.disabled())
                .overheadModel(OverheadModelConfig.none())
                .randomSeed(13L).runtimeScale(1.0).runtimeReferenceMips(1000.0)
                .cloudSimMinEventIntervalSeconds(MIN_HOLD_SECONDS)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform.build());
        assertTrue(report.isWorkflowCompletedSuccessfully());
        List<Integer> computeIds = report.getJobs().stream()
                .filter(job -> job.getClassType() == Parameters.ClassType.COMPUTE.value)
                .map(SimulationReport.JobOutcome::getJobId).collect(Collectors.toList());
        assertEquals(1, computeIds.size(), "the probe must contain exactly one compute Job");
        int computeId = computeIds.get(0);
        SimulationReport.JobOutcome compute = report.getJobs().stream()
                .filter(job -> job.getJobId() == computeId).findFirst().get();
        return new Trace(report, compute,
                onlyEvent(report, SimulationEventType.JOB_READY, computeId),
                onlyEvent(report, SimulationEventType.DATA_STAGE_IN_MODELED, computeId),
                onlyEvent(report, SimulationEventType.SCHEDULING_DECISION, computeId),
                onlyEvent(report, SimulationEventType.TASK_EXECUTION_MODELED, computeId));
    }

    private static Stream<Arguments> allTracks() {
        return Arrays.stream(InputFormat.values()).flatMap(format -> Arrays.stream(Track.values())
                .map(track -> Arguments.of(format, track)));
    }

    private static Stream<Arguments> preExecutionTracks() {
        return Arrays.stream(InputFormat.values()).flatMap(format -> Arrays.stream(Track.values())
                .filter(track -> track != Track.LEGACY).map(track -> Arguments.of(format, track)));
    }

    private static DataMovementModel model(Track track) {
        switch (track) {
            case LEGACY: return DataMovementModel.legacyWorkflowsimV1();
            case NO_CONTENTION: return DataMovementModel.preExecutionTransferDelayV1();
            case ENDPOINT_CONTENTION: return DataMovementModel.preExecutionTransferDelayWithContentionV1();
            case FAT_TREE_CONTENTION: return DataMovementModel.fatTreeContentionV1();
            default: throw new AssertionError(track);
        }
    }

    private static String workflow(InputFormat format, double bytes) {
        String size = Double.toString(bytes);
        if (format == InputFormat.DAX) {
            return "<adag version=\"2.1\"><job id=\"root\" name=\"root\" runtime=\"1.0\">"
                    + "<uses file=\"tiny-input.dat\" link=\"input\" size=\"" + size + "\"/>"
                    + "</job></adag>";
        }
        return "{\"schemaVersion\":\"1.5\",\"name\":\"fractional-transfer\",\"workflow\":{"
                + "\"specification\":{\"tasks\":[{\"id\":\"root\",\"name\":\"root\",\"parents\":[],\"children\":[],"
                + "\"inputFiles\":[\"tiny-input.dat\"],\"outputFiles\":[]}],"
                + "\"files\":[{\"id\":\"tiny-input.dat\",\"sizeInBytes\":" + size + "}]},"
                + "\"execution\":{\"tasks\":[{\"id\":\"root\",\"runtimeInSeconds\":1.0}]}}}";
    }

    private static SimulationEvent onlyEvent(SimulationReport report, SimulationEventType type, int jobId) {
        List<SimulationEvent> events = report.getEvents().stream()
                .filter(event -> event.getType() == type && Integer.valueOf(jobId).equals(event.getJobId()))
                .collect(Collectors.toList());
        assertEquals(1, events.size(), "expected one " + type + " event for compute Job " + jobId);
        return events.get(0);
    }

    private static double number(SimulationEvent event, String field) {
        Object value = event.getAttributes().get(field);
        assertTrue(value instanceof Number, "missing numeric " + field + " in " + event.getType());
        return ((Number) value).doubleValue();
    }

    private static final class Trace {
        private final SimulationReport report;
        private final SimulationReport.JobOutcome job;
        private final SimulationEvent ready;
        private final SimulationEvent stageIn;
        private final SimulationEvent decision;
        private final SimulationEvent taskExecution;

        private Trace(SimulationReport report, SimulationReport.JobOutcome job, SimulationEvent ready,
                SimulationEvent stageIn, SimulationEvent decision, SimulationEvent taskExecution) {
            this.report = report; this.job = job; this.ready = ready; this.stageIn = stageIn;
            this.decision = decision; this.taskExecution = taskExecution;
        }
    }
}
