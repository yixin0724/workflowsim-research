package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Independently declared legal cells, not a matrix inferred from whichever configurations pass.
 * Scaling every byte demand and bandwidth by two preserves transfer times, not monetary totals.
 */
class ConfigurationMetamorphicIntegrationTest {
    private static final int[] VM_IDS = {3, 8, 12};
    private static final double[] MIPS = {500, 1000, 1500};
    private static final long[] WORK_MI = {1250, 2000, 1000, 1750, 500, 2250};
    private static final int[][] EDGES = {{1, 3}, {2, 3}, {2, 4}, {3, 5}, {4, 5}, {5, 6}};
    private static final double EPSILON = 1e-7;
    private enum Movement { LEGACY, FIXED, PRE_EXECUTION, ENDPOINT, FAT_TREE }
    @TempDir Path directory;
    private boolean oldLogDisabled;

    @BeforeEach void quiet() { oldLogDisabled = Log.isDisabled(); Log.disable(); }
    @AfterEach void restore() { Log.setDisabled(oldLogDisabled); }

    static Stream<Cell> legalCells() { return declaredCells().stream(); }

    private static List<Cell> declaredCells() {
        List<Cell> cells = new ArrayList<>();
        Parameters.SchedulingAlgorithm[] online = {
            Parameters.SchedulingAlgorithm.FCFS, Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
            Parameters.SchedulingAlgorithm.READY_BATCH_MCT, Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
            Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN, Parameters.SchedulingAlgorithm.DATA
        };
        for (Parameters.SchedulingAlgorithm scheduler : online) {
            for (ReplicaCatalog.FileSystem fs : ReplicaCatalog.FileSystem.values()) {
                if (scheduler == Parameters.SchedulingAlgorithm.DATA && fs == ReplicaCatalog.FileSystem.SHARED) continue;
                for (Movement model : new Movement[] {Movement.LEGACY, Movement.FIXED}) {
                    cells.add(new Cell(Parameters.PlanningAlgorithm.INVALID, scheduler, fs, model, false));
                }
            }
        }
        Parameters.PlanningAlgorithm[] independent = {
            Parameters.PlanningAlgorithm.STATIC_OLB, Parameters.PlanningAlgorithm.STATIC_MET,
            Parameters.PlanningAlgorithm.STATIC_MCT, Parameters.PlanningAlgorithm.STATIC_MINMIN,
            Parameters.PlanningAlgorithm.STATIC_MAXMIN, Parameters.PlanningAlgorithm.STATIC_SUFFERAGE,
            Parameters.PlanningAlgorithm.STATIC_ROUND_ROBIN
        };
        List<Parameters.PlanningAlgorithm> mapping = new ArrayList<>(Arrays.asList(independent));
        mapping.add(Parameters.PlanningAlgorithm.RANDOM); mapping.add(Parameters.PlanningAlgorithm.PSO);
        for (Parameters.PlanningAlgorithm planner : mapping) {
            for (ReplicaCatalog.FileSystem fs : ReplicaCatalog.FileSystem.values()) {
                for (Movement model : Movement.values()) {
                    if (model == Movement.FAT_TREE && fs == ReplicaCatalog.FileSystem.SHARED) continue;
                    cells.add(new Cell(planner, Parameters.SchedulingAlgorithm.STATIC, fs, model,
                            Arrays.asList(independent).contains(planner)));
                }
            }
        }
        for (Parameters.PlanningAlgorithm planner : new Parameters.PlanningAlgorithm[] {
                Parameters.PlanningAlgorithm.LOCAL_HEFT, Parameters.PlanningAlgorithm.LOCAL_CPOP,
                Parameters.PlanningAlgorithm.LOCAL_PEFT}) {
            for (Movement model : new Movement[] {Movement.PRE_EXECUTION, Movement.ENDPOINT, Movement.FAT_TREE}) {
                cells.add(new Cell(planner, Parameters.SchedulingAlgorithm.STATIC,
                        ReplicaCatalog.FileSystem.LOCAL, model, false));
            }
        }
        for (Parameters.PlanningAlgorithm planner : new Parameters.PlanningAlgorithm[] {
                Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT, Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP,
                Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS, Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF,
                Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT}) {
            cells.add(new Cell(planner, Parameters.SchedulingAlgorithm.STATIC,
                    ReplicaCatalog.FileSystem.SHARED, Movement.LEGACY, false));
        }
        return cells;
    }

    @Test void declaredMatrixHasAll117DistinctLegalCells() {
        List<Cell> cells = declaredCells();
        assertEquals(117, cells.size());
        assertEquals(117, cells.stream().map(Cell::toString).distinct().count());
    }

    @ParameterizedTest(name = "byte/bandwidth scaling: {0}")
    @MethodSource("legalCells")
    void commonDataScalePreservesMappingTimingAndComputeWork(Cell cell) throws Exception {
        SimulationReport original = run(cell, 1);
        SimulationReport doubled = run(cell, 2);
        assertPhysicalInvariants(original, cell.independent);
        assertPhysicalInvariants(doubled, cell.independent);
        assertNear(original.getMakespan(), doubled.getMakespan(), "simulation end");
        Map<Integer, SimulationReport.TaskOutcome> before = taskOutcomes(original);
        Map<Integer, SimulationReport.TaskOutcome> after = taskOutcomes(doubled);
        assertEquals(before.keySet(), after.keySet());
        for (Integer id : before.keySet()) {
            SimulationReport.TaskOutcome a = before.get(id), b = after.get(id);
            assertEquals(a.getVmId(), b.getVmId(), "same costs must preserve mapping for Task " + id);
            assertEquals(a.getEffectiveExecutionLengthMi(), b.getEffectiveExecutionLengthMi());
            assertNear(a.getStartTime(), b.getStartTime(), "task start " + id);
            assertNear(a.getFinishTime(), b.getFinishTime(), "task finish " + id);
        }
        SimulationMetrics a = original.getMetrics(), b = doubled.getMetrics();
        assertNear(a.getTotalModeledDataTransferSeconds(), b.getTotalModeledDataTransferSeconds(), "nominal transfer seconds");
        assertNear(2.0 * a.getTotalModeledRequiredInputBytes(), b.getTotalModeledRequiredInputBytes(), "logical input bytes");
        assertNear(2.0 * a.getTotalModeledDeclaredFileBytes(), b.getTotalModeledDeclaredFileBytes(), "declared file bytes");
        assertNear(a.getTotalModeledCpuEnvelopeCost(), b.getTotalModeledCpuEnvelopeCost(), "CPU envelope cost");
        assertNear(2.0 * a.getTotalModeledDeclaredFileBandwidthCost(), b.getTotalModeledDeclaredFileBandwidthCost(), "bandwidth cost scales, not invariant");
    }

    private SimulationReport run(Cell cell, int dataScale) throws Exception {
        Path input = directory.resolve("input-scale-" + dataScale + ".dax");
        Files.write(input, workflow(cell.independent, dataScale).getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), VM_IDS.length)
                .planningAlgorithm(cell.planner).schedulingAlgorithm(cell.scheduler)
                .fileSystem(cell.fileSystem).dataMovementModel(model(cell.movement, dataScale))
                .randomSeed(20260928L).build();
        PlatformProfile.Builder platform = PlatformProfile.builder("property-scale-" + dataScale)
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20 * dataScale));
        for (int i = 0; i < VM_IDS.length; i++) {
            platform.addHost(new PlatformProfile.HostSpec(VM_IDS[i], 2, 3000, 2048,
                    10000L * dataScale, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(VM_IDS[i], MIPS[i], 1, 512,
                    (i + 1L) * dataScale, 10000, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            platform.pinVmToHost(VM_IDS[i], VM_IDS[i]);
        }
        if (cell.movement == Movement.FAT_TREE) platform.networkTopology(NetworkTopologySpec.fatTree(4, 0.25 * dataScale));
        return new SimulationRunner().run(config, platform.build());
    }

    private static DataMovementModel model(Movement kind, int scale) {
        switch (kind) {
            case LEGACY: return DataMovementModel.legacyWorkflowsimV1();
            case FIXED: return DataMovementModel.fixedEndpointNoContention(2.0 * scale, 0.125, 4.0 * scale);
            case PRE_EXECUTION: return DataMovementModel.preExecutionTransferDelayV1();
            case ENDPOINT: return DataMovementModel.preExecutionTransferDelayWithContentionV1();
            case FAT_TREE: return DataMovementModel.fatTreeContentionV1();
            default: throw new AssertionError(kind);
        }
    }

    private static String workflow(boolean independent, int scale) {
        StringBuilder xml = new StringBuilder("<adag version=\"2.1\">");
        for (int i = 0; i < WORK_MI.length; i++) {
            int task = i + 1;
            xml.append("<job id=\"t").append(task).append("\" runtime=\"").append(WORK_MI[i] / 1000.0).append("\">");
            if (independent || task <= 2 || task == 4) {
                double bytes = task == 2 ? 0.5 : 1_000_000.0 + task * 250_000.0;
                file(xml, "external-" + task, "input", bytes * scale);
            }
            if (!independent) {
                for (int[] edge : EDGES) {
                    double bytes = edge[0] == 1 ? 0.5 : 2_000_000.0;
                    if (edge[1] == task) file(xml, "e" + edge[0] + "-" + edge[1], "input", bytes * scale);
                    if (edge[0] == task) file(xml, "e" + edge[0] + "-" + edge[1], "output", bytes * scale);
                }
            }
            xml.append("</job>");
        }
        if (!independent) {
            for (int[] edge : EDGES) xml.append("<child ref=\"t").append(edge[1]).append("\"><parent ref=\"t")
                    .append(edge[0]).append("\"/></child>");
        }
        return xml.append("</adag>").toString();
    }

    private static void file(StringBuilder xml, String name, String link, double bytes) {
        xml.append("<uses file=\"").append(name).append("\" link=\"").append(link)
                .append("\" size=\"").append(bytes).append("\"/>");
    }

    private static Map<Integer, SimulationReport.TaskOutcome> taskOutcomes(SimulationReport report) {
        Map<Integer, SimulationReport.TaskOutcome> values = new HashMap<>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertEquals(null, values.put(task.getTaskId(), task), "no retry in this matrix");
        }
        return values;
    }

    private static void assertPhysicalInvariants(SimulationReport report, boolean independent) {
        assertEquals("COMPLETED_SUCCESSFULLY", report.getMetrics().getLogicalTaskCompletionStatus());
        Map<Integer, SimulationReport.TaskOutcome> tasks = taskOutcomes(report);
        assertEquals(WORK_MI.length, tasks.size());
        Map<Integer, SimulationReport.JobOutcome> jobs = new HashMap<>();
        for (SimulationReport.JobOutcome job : report.getJobs()) jobs.put(job.getJobId(), job);
        for (int id = 1; id <= WORK_MI.length; id++) {
            SimulationReport.TaskOutcome task = tasks.get(id);
            assertEquals(WORK_MI[id - 1], task.getLengthMi());
            assertEquals(WORK_MI[id - 1], task.getEffectiveExecutionLengthMi());
            int machine = -1;
            for (int i = 0; i < VM_IDS.length; i++) if (VM_IDS[i] == task.getVmId()) machine = i;
            assertTrue(machine >= 0, "mapping must use a declared non-contiguous VM ID");
            double seconds = WORK_MI[id - 1] / MIPS[machine];
            SimulationReport.JobOutcome job = jobs.get(task.getJobId());
            assertTrue(job.getFinishTime() - job.getStartTime() + 1.0 / MIPS[machine] + EPSILON >= seconds,
                    "independent CPU lower bound for Task " + id);
            assertNear(seconds, task.getFinishTime() - task.getStartTime(), "effective Task work " + id);
            assertTrue(task.getStartTime() + EPSILON >= job.getStartTime());
            assertTrue(task.getFinishTime() <= job.getFinishTime() + 1.0 / MIPS[machine] + EPSILON);
        }
        if (!independent) {
            for (int[] edge : EDGES) {
                SimulationReport.JobOutcome parent = jobs.get(tasks.get(edge[0]).getJobId());
                SimulationReport.JobOutcome child = jobs.get(tasks.get(edge[1]).getJobId());
                assertTrue(child.getStartTime() + EPSILON >= parent.getFinishTime(), "parent must finish before child");
            }
        }
    }

    private static void assertNear(double expected, double actual, String message) {
        assertEquals(expected, actual, EPSILON + 1e-12 * Math.max(Math.abs(expected), Math.abs(actual)), message);
    }

    private static final class Cell {
        private final Parameters.PlanningAlgorithm planner;
        private final Parameters.SchedulingAlgorithm scheduler;
        private final ReplicaCatalog.FileSystem fileSystem;
        private final Movement movement;
        private final boolean independent;
        Cell(Parameters.PlanningAlgorithm planner, Parameters.SchedulingAlgorithm scheduler,
                ReplicaCatalog.FileSystem fileSystem, Movement movement, boolean independent) {
            this.planner = planner; this.scheduler = scheduler; this.fileSystem = fileSystem;
            this.movement = movement; this.independent = independent;
        }
        @Override public String toString() { return (planner == Parameters.PlanningAlgorithm.INVALID ? scheduler : planner)
                + "/" + fileSystem + "/" + movement + (independent ? "/independent" : "/DAG"); }
    }
}
