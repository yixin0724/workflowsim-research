package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/** Hand-derived references, never expected values obtained from production timing/cost helpers. */
class ControlledSharedStorageIndependentReferenceTest {
    private static final double EPS = 1.0e-9;
    private static final String CONTROLLED_SCOPE =
            "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED";

    @TempDir Path directory;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void rawSharedChainIncludesBootstrapAndEachTasksOwnInputs() throws Exception {
        Path input = dax("raw-chain", "<job id=\"a\" runtime=\"1\">"
                + "<uses name=\"edge\" link=\"output\" size=\"8000000\"/></job>"
                + "<job id=\"b\" runtime=\"2\">"
                + "<uses name=\"edge\" link=\"input\" size=\"8000000\"/>"
                + "<uses name=\"external\" link=\"input\" size=\"2000000\"/>"
                + "<uses name=\"unused-output\" link=\"output\" size=\"90000000\"/></job>"
                + "<child ref=\"b\"><parent ref=\"a\"/></child>");
        SimulationReport report = run(SimulationConfig.builder(input.toString(), 1).build(),
                oneVmPlatform());

        // Bootstrap max(110/1000, .1 + .01) + .1 = .21.
        // A takes 1s; B takes 2s + (8MB + 2MB)/20MBps = 2.5s.
        assertReference(report, 3.71);
        assertEquals(2, report.getMetrics().getLogicalTaskCount());
    }

    @Test
    void matrixShorterThanRawWorkCannotProduceAnOverstatedLowerBound() throws Exception {
        Path input = dax("short-matrix", "<job id=\"a\" runtime=\"100\"/>");
        SimulationReport report = run(matrixConfig(input, 1.0), oneVmPlatform());

        assertEquals(1.0, computeJob(report).getExecutionTime(), EPS);
        assertEquals(100_000L, report.getTasks().get(0).getLengthMi());
        assertEquals(1000L, report.getTasks().get(0).getEffectiveExecutionLengthMi());
        // Source work is deliberately 100s; the authoritative matrix executes 1s.
        assertReference(report, 1.21);
    }

    @Test
    void matrixLongerThanRawWorkAlsoDefinesTheReference() throws Exception {
        Path input = dax("long-matrix", "<job id=\"a\" runtime=\"1\"/>");
        SimulationReport report = run(matrixConfig(input, 20.0), oneVmPlatform());

        assertEquals(20.0, computeJob(report).getExecutionTime(), EPS);
        assertEquals(1000L, report.getTasks().get(0).getLengthMi());
        assertEquals(20_000L, report.getTasks().get(0).getEffectiveExecutionLengthMi());
        assertReference(report, 20.21);
    }

    @Test
    void matrixReferenceMinimizesRoundedCostOverPeCompatibleVms() {
        Task task = new Task(1, 100_000L);
        task.setNumberOfPes(2);
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(7, 0.25); // Fast CPU, but only one PE: not a candidate for this task.
        costs.put(42, 4.0);
        costs.put(99, 1.00048828125);
        task.setVmExecutionCostSeconds(costs);
        TaskCostMatrix matrix = TaskCostMatrix.builder()
                .put(1, 7, 0.25).put(1, 42, 4.0).put(1, 99, 1.00048828125).build();
        SimulationConfig config = SimulationConfig.builder("unused.dax", 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(matrix).build();
        PlatformProfile platform = PlatformProfile.builder("independent-matrix-reference")
                .addHost(new PlatformProfile.HostSpec(0, 5, 4096.0, 8192, 10_000L, 1_000_000L))
                .addVm(vm(7, 4096.0, 1)).addVm(vm(42, 2048.0, 2)).addVm(vm(99, 1024.0, 2))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Collections.singletonList(task), config, platform);

        // 1.00048828125 * 1024 is exactly 1024.5 MI in binary: round to 1025.
        // The slower compatible VM wins: 1025/1024 = 1.0009765625s, not 4s.
        // The 1-PE bootstrap may still use the fastest VM: .11 + .1 = .21s.
        assertTrue(reference.isAvailable());
        assertEquals(1.2109765625, reference.getCriticalPathLowerBoundSeconds(), EPS);
        assertEquals(1, reference.getSourceTaskCount());
        assertEquals(100_000L, task.getCloudletLength(), "Reference observation must not rewrite source work");
    }

    @Test
    void rawForkJoinUsesLatestParentAndFastestCompatiblePerPeExecution() {
        Task twoPeParent = new Task(10, 2000L);
        twoPeParent.setNumberOfPes(2);
        Task otherParent = new Task(30, 1000L);
        Task join = new Task(20, 2000L); // IDs deliberately do not form a topological ordering.
        connect(twoPeParent, join);
        connect(otherParent, join);
        twoPeParent.addFile(file("left", 3_000_000.0, Parameters.FileType.OUTPUT));
        otherParent.addFile(file("right", 2_000_000.0, Parameters.FileType.OUTPUT));
        join.addFile(file("left", 3_000_000.0, Parameters.FileType.INPUT));
        join.addFile(file("right", 2_000_000.0, Parameters.FileType.INPUT));
        PlatformProfile platform = PlatformProfile.builder("independent-pe-reference")
                .addHost(new PlatformProfile.HostSpec(0, 3, 2000.0, 4096, 10_000L, 1_000_000L))
                .addVm(vm(0, 2000.0, 1)).addVm(vm(1, 1000.0, 2))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Arrays.asList(join, otherParent, twoPeParent),
                SimulationConfig.builder("unused.dax", 2).build(), platform);

        // Bootstrap .21; parents take 2000/1000=2 and 1000/2000=.5 seconds.
        // Join takes 2000/2000 + 5MB/20MBps = 1.25s. No PE multiplier in duration.
        assertTrue(reference.isAvailable());
        assertEquals(3.46, reference.getCriticalPathLowerBoundSeconds(), EPS);
        assertEquals(3, reference.getSourceTaskCount());
    }

    @Test
    void legacyInputMiIsTruncatedOnceAfterSummingTheRealInputs() {
        Task task = new Task(1, 1000L);
        task.addFile(file("a", 12_500.0, Parameters.FileType.INPUT));
        task.addFile(file("b", 12_500.0, Parameters.FileType.INPUT));
        task.addFile(file("output-only", 100_000_000.0, Parameters.FileType.OUTPUT));
        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Collections.singletonList(task), SimulationConfig.builder("unused.dax", 1).build(),
                oneVmPlatform());

        // (12500 + 12500)B / 20MBps = .00125s -> 1.25 added MI -> 1 MI.
        // Truncating each input separately would incorrectly add zero MI.
        assertTrue(reference.isAvailable());
        assertEquals(1.211, reference.getCriticalPathLowerBoundSeconds(), EPS);
    }

    @Test
    void preExecutionParallelInputsCannotAdvertiseTheLegacySerialInputBound() throws Exception {
        Path input = dax("parallel-parent-inputs", "<job id=\"a\" runtime=\"1\">"
                + "<uses name=\"left\" link=\"output\" size=\"200000000\"/></job>"
                + "<job id=\"b\" runtime=\"1\">"
                + "<uses name=\"right\" link=\"output\" size=\"200000000\"/></job>"
                + "<job id=\"join\" runtime=\"1\">"
                + "<uses name=\"left\" link=\"input\" size=\"200000000\"/>"
                + "<uses name=\"right\" link=\"input\" size=\"200000000\"/></job>"
                + "<child ref=\"join\"><parent ref=\"a\"/><parent ref=\"b\"/></child>");
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1()).build();
        SimulationReport report = run(config, oneVmPlatform());

        // Bootstrap .21; A/B finish at 1.21 and 2.21 on the single VM.
        // Their 10s parent-input transfers overlap: the last arrival is 12.21,
        // then the join computes for 1s. The legacy serial-input bound is 22.21.
        assertTrue(report.isWorkflowCompletedSuccessfully());
        assertEquals(13.21, report.getSimulationEndSeconds(), EPS,
                "The fixture must actually exercise overlapping per-parent inputs");
        assertSafeAvailability(report);
    }

    @Test
    void fixedEndpointControlRemainsLegalWithoutClaimingAUniversalCrossModelBound() throws Exception {
        Path input = dax("fixed-endpoint-control", "<job id=\"a\" runtime=\"1\">"
                + "<uses name=\"external\" link=\"input\" size=\"20000000\"/></job>");
        SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.25, 50.0))
                .build();
        SimulationReport report = run(config, oneVmPlatform());

        // Fixed endpoint: bottleneck min(20,10)=10MBps, latency .25s, compute 1s.
        assertEquals(3.25, computeJob(report).getExecutionTime(), EPS);
        assertSafeAvailability(report);
    }

    @Test
    void unselectedZeroMiAlternativeMakesOnlyTheOptionalReferenceUnavailable() {
        // CP002 regression guard: .0001s * 1000 MIPS = .1 MI, which rounds to zero.
        assertUnavailableForUnrepresentableAlternative(0.0001);
    }

    @Test
    void unselectedOverflowingAlternativesMakeOnlyTheOptionalReferenceUnavailable() {
        // 10^10s * 1000 MIPS = 10^13 MI: MI fits long, but 10^19 instructions do not.
        assertUnavailableForUnrepresentableAlternative(10_000_000_000.0);
        // Also cover the separate conversion overflow before the instruction-range check.
        assertUnavailableForUnrepresentableAlternative(Double.MAX_VALUE);
    }

    @Test
    void unselectedTransferEnvelopeMustBeRepresentableBeforePublishingAReference() {
        // 40MB / 20MBps = 2s of input; selected VM 1 has a valid (2000 + 2000) MI envelope.
        // Alternatives exercise instruction-budget overflow, checked-long addition overflow,
        // an out-of-long-range transfer cast, and a non-finite MIPS * transfer-seconds product.
        for (double alternativeMips : new double[] {10_000_000_000_000.0,
                Math.nextDown(0x1.0p62), 1.0e20, 1.0e308}) {
            Task task = new Task(1, 2000L);
            task.setVmId(1);
            task.addFile(file("external", 40_000_000.0, Parameters.FileType.INPUT));
            SimulationConfig config = SimulationConfig.builder("unused.dax", 2)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).build();
            PlatformProfile platform = PlatformProfile.builder("optional-reference-transfer-range")
                    .addHost(new PlatformProfile.HostSpec(0, 1, alternativeMips,
                            1024, 1000L, 1_000_000L))
                    .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0,
                            1024, 1000L, 1_000_000L))
                    .addVm(vm(0, alternativeMips, 1)).addVm(vm(1, 1000.0, 1))
                    .pinVmToHost(0, 0).pinVmToHost(1, 1)
                    .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();
            assertUnavailableReference(task, config, platform);
        }
    }

    @Test
    void selectedUnrepresentableMatrixCoordinatesStillFailDuringActualExecution() throws Exception {
        Path input = dax("selected-invalid-matrix", "<job id=\"a\" runtime=\"1\"/>");
        for (double seconds : new double[] {0.0001, 10_000_000_000.0, Double.MAX_VALUE}) {
            // With one VM, RANDOM must select this coordinate. Optional-reference degradation
            // must not catch or turn the actual execution failure into a successful report.
            SimulationConfig config = matrixConfig(input, seconds);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> run(config, oneVmPlatform()));
            assertTrue(failure.getMessage().contains("could not submit a workflow Job"),
                    "The selected invalid coordinate must fail during datacenter submission");
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            String expectedDiagnostic = seconds < 1.0 ? "non-positive MI"
                    : seconds == Double.MAX_VALUE ? "overflows compute MI" : "signed-long instructions";
            assertTrue(failure.getCause().getMessage().contains(expectedDiagnostic),
                    failure.getCause().getMessage());
        }
    }

    private static void assertUnavailableForUnrepresentableAlternative(double alternativeSeconds) {
        Task task = new Task(1, 1000L);
        task.setVmId(1); // Snapshot of a task assigned to the valid 1-second coordinate.
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(0, alternativeSeconds);
        costs.put(1, 1.0);
        task.setVmExecutionCostSeconds(costs);
        SimulationConfig config = SimulationConfig.builder("unused.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(TaskCostMatrix.builder().put(1, 0, alternativeSeconds)
                        .put(1, 1, 1.0).build()).build();
        PlatformProfile platform = PlatformProfile.builder("optional-reference-coordinate-range")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0, 2048, 10_000L, 1_000_000L))
                .addVm(vm(0, 1000.0, 1)).addVm(vm(1, 1000.0, 1))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();

        assertUnavailableReference(task, config, platform);
    }

    private static void assertUnavailableReference(Task task, SimulationConfig config, PlatformProfile platform) {
        int selectedVm = task.getVmId();
        long sourceMi = task.getCloudletLength();
        WorkflowModelReference.Reference reference = assertDoesNotThrow(() ->
                WorkflowModelReference.calculate(Collections.singletonList(task), config, platform),
                "An unusable alternative must not invalidate an otherwise completed selected coordinate");

        assertFalse(reference.isAvailable());
        assertEquals("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST", reference.getScope());
        assertEquals(0.0, reference.getCriticalPathLowerBoundSeconds(), 0.0,
                "Do not publish a fallback bound when a candidate reference envelope cannot be represented");
        assertEquals(selectedVm, task.getVmId(), "The observer must not change the actual mapping");
        assertEquals(sourceMi, task.getCloudletLength(), "The observer must not rewrite source work");
    }

    private static void assertReference(SimulationReport report, double expectedBound) {
        SimulationMetrics metrics = report.getMetrics();
        assertTrue(report.isWorkflowCompletedSuccessfully());
        assertTrue(metrics.isControlledSharedStorageCriticalPathReferenceAvailable());
        assertEquals(CONTROLLED_SCOPE, metrics.getControlledSharedStorageCriticalPathReferenceScope());
        assertAll(
                () -> assertEquals(expectedBound,
                        metrics.getControlledSharedStorageCriticalPathLowerBoundSeconds(), EPS),
                () -> assertEquals(report.getSimulationEndSeconds() / expectedBound,
                        metrics.getControlledSharedStorageScheduleLengthRatio(), EPS),
                () -> assertTrue(metrics.getControlledSharedStorageCriticalPathLowerBoundSeconds()
                                <= report.getSimulationEndSeconds() + EPS,
                        "An available reference must be a lower bound for this controlled fixture"));
    }

    private static void assertSafeAvailability(SimulationReport report) {
        SimulationMetrics metrics = report.getMetrics();
        if (metrics.isControlledSharedStorageCriticalPathReferenceAvailable()) {
            double bound = metrics.getControlledSharedStorageCriticalPathLowerBoundSeconds();
            assertTrue(bound > 0.0 && bound <= report.getSimulationEndSeconds() + EPS,
                    "Do not advertise a legacy serial-input estimate as a bound for a different transfer model: "
                            + bound + " > " + report.getSimulationEndSeconds());
            assertEquals(report.getSimulationEndSeconds() / bound,
                    metrics.getControlledSharedStorageScheduleLengthRatio(), EPS);
        } else {
            assertFalse(metrics.getControlledSharedStorageCriticalPathReferenceScope().equals(CONTROLLED_SCOPE));
            assertEquals(0.0, metrics.getControlledSharedStorageCriticalPathLowerBoundSeconds(), 0.0);
            assertEquals(0.0, metrics.getControlledSharedStorageScheduleLengthRatio(), 0.0);
        }
    }

    private static SimulationConfig matrixConfig(Path input, double seconds) {
        // RANDOM + SHARED + matrix is legal; the forbidden SHARED_STORAGE_* + matrix is not used.
        return SimulationConfig.builder(input.toString(), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(TaskCostMatrix.builder().put(1, 0, seconds).build()).build();
    }

    private SimulationReport run(SimulationConfig config, PlatformProfile platform) throws Exception {
        Log.disable();
        return new SimulationRunner().run(config, platform);
    }

    private Path dax(String name, String body) throws Exception {
        Path path = directory.resolve(name + ".dax");
        Files.write(path, ("<adag version=\"3.3\">" + body + "</adag>").getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static SimulationReport.JobOutcome computeJob(SimulationReport report) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                return job;
            }
        }
        throw new AssertionError("Missing compute outcome");
    }

    private static PlatformProfile oneVmPlatform() {
        return PlatformProfile.builder("independent-shared-reference")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 1_000_000L))
                .addVm(vm(0, 1000.0, 1))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20)).build();
    }

    private static PlatformProfile.VmSpec vm(int id, double mips, int pes) {
        return new PlatformProfile.VmSpec(id, mips, pes, 512, 1000L, 10_000L, "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED);
    }

    private static FileItem file(String name, double bytes, Parameters.FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }
}
