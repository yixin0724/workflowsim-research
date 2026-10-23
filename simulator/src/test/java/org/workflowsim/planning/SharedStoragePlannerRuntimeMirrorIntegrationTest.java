package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * SHARED-family planning-to-execution mirror, all five planners (2026-10-23, A3 authority close-out).
 *
 * <p>Contract under test: the SHARED_STORAGE planning track computes each task's shared-storage
 * input latency and converts it once into the execution envelope — the same effective-MI
 * construction the legacy runtime uses (docs/algorithms/CONTRACTS.md, SHARED computation
 * envelope). A complete {@link SimulationRunner} run must therefore reproduce an independent
 * re-parse plus re-plan's mapping and task windows bit-exactly, for HEFT, CPOP, DLS, ETF, and PEFT:</p>
 * <ol>
 *   <li>standard run: SHARED + legacy data movement + STATIC + SHARED_STORAGE_DLS/ETF;</li>
 *   <li>independent re-parse and fresh planner instance over the same DAX and platform;</li>
 *   <li>per-task assertions: VM binding, planned start, planned finish — zero tolerance.</li>
 * </ol>
 *
 * <p>If this mirror cannot hold for a semantics reason rather than a defect, the failure mode
 * itself is the finding: the family's honest invariant must then be documented and locked at
 * the level that holds, never silently widened.</p>
 */
class SharedStoragePlannerRuntimeMirrorIntegrationTest {

    private static final double ZERO_TOLERANCE = 0.0;

    private boolean logWasDisabled;

    @TempDir
    Path tempDir;

    @BeforeEach
    void disableSimulationLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLog() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void sharedStorageHeftPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.SHARED_STORAGE_HEFT);
    }

    @Test
    void sharedStorageCpopPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.SHARED_STORAGE_CPOP);
    }

    @Test
    void sharedStorageDlsPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.SHARED_STORAGE_DLS);
    }

    @Test
    void sharedStorageEtfPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.SHARED_STORAGE_ETF);
    }

    @Test
    void sharedStoragePeftPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.SHARED_STORAGE_PEFT);
    }

    /**
     * Generic mirror driver: full SimulationRunner run vs independent re-parse + re-plan;
     * per-task VM binding / planned start / planned finish must match with zero tolerance.
     */
    private void mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm algorithm) throws Exception {
        Path workflow = sharedDax();
        PlatformProfile platform = sharedPlatform();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261023L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "镜像基线要求仿真完整成功：" + algorithm);

        List<Task> reparsed = reparse(workflow);
        BasePlanningAlgorithm planner = createPlanner(algorithm, new PlanningContext(config, platform));
        planner.setTaskList(reparsed);
        List<org.workflowsim.CondorVM> vms = new ArrayList<org.workflowsim.CondorVM>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            vms.add(new org.workflowsim.CondorVM(
                    spec.getId(),
                    0,
                    spec.getMips(),
                    1,
                    512,
                    spec.getBandwidth(),
                    10_000L,
                    "Xen",
                    new org.cloudbus.cloudsim.CloudletSchedulerSpaceShared()));
        }
        planner.setVmList(vms);
        planner.run();

        List<SimulationReport.TaskOutcome> outcomes = report.getTasks();
        assertEquals(reparsed.size(), outcomes.size(), "任务数必须一致（同一 DAX）：" + algorithm);
        SharedStorageDagPlanTrace trace = lastPlanTrace(planner);
        for (SimulationReport.TaskOutcome outcome : outcomes) {
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(outcome.getTaskId());
            assertEquals(
                    plan.getVmId(), outcome.getVmId(), 0, algorithm + " 任务 " + outcome.getTaskId() + " 的 VM 绑定必须镜像");
            assertEquals(
                    plan.getPlannedComputeStartSeconds(),
                    outcome.getStartTime(),
                    ZERO_TOLERANCE,
                    algorithm + " 任务 " + outcome.getTaskId() + " 计划计算窗口开始必须逐位镜像");
            assertEquals(
                    plan.getPlannedFinishSeconds(),
                    outcome.getFinishTime(),
                    ZERO_TOLERANCE,
                    algorithm + " 任务 " + outcome.getTaskId() + " 计划信封完成必须逐位镜像");
        }
    }

    private static SharedStorageDagPlanTrace lastPlanTrace(BasePlanningAlgorithm planner) {
        if (planner instanceof SharedStorageDlsPlanningAlgorithm) {
            return ((SharedStorageDlsPlanningAlgorithm) planner).getLastPlanTrace();
        }
        if (planner instanceof SharedStorageEtfPlanningAlgorithm) {
            return ((SharedStorageEtfPlanningAlgorithm) planner).getLastPlanTrace();
        }
        if (planner instanceof SharedStorageHeftPlanningAlgorithm) {
            return ((SharedStorageHeftPlanningAlgorithm) planner).getLastPlanTrace();
        }
        if (planner instanceof SharedStorageCpopPlanningAlgorithm) {
            return ((SharedStorageCpopPlanningAlgorithm) planner).getLastPlanTrace();
        }
        if (planner instanceof SharedStoragePeftPlanningAlgorithm) {
            return ((SharedStoragePeftPlanningAlgorithm) planner).getLastPlanTrace();
        }
        throw new IllegalArgumentException("规划器未暴露计划轨迹: " + planner.getClass().getSimpleName());
    }

    private static BasePlanningAlgorithm createPlanner(PlanningAlgorithm algorithm, PlanningContext context) {
        switch (algorithm) {
            case SHARED_STORAGE_HEFT:
                return new SharedStorageHeftPlanningAlgorithm(context);
            case SHARED_STORAGE_CPOP:
                return new SharedStorageCpopPlanningAlgorithm(context);
            case SHARED_STORAGE_DLS:
                return new SharedStorageDlsPlanningAlgorithm(context);
            case SHARED_STORAGE_ETF:
                return new SharedStorageEtfPlanningAlgorithm(context);
            case SHARED_STORAGE_PEFT:
                return new SharedStoragePeftPlanningAlgorithm(context);
            default:
                throw new IllegalArgumentException("镜像驱动不支持规划算法 " + algorithm);
        }
    }

    private List<Task> reparse(Path workflow) {
        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
        org.workflowsim.utils.Parameters.reset();
        org.workflowsim.utils.Parameters.init(
                2, workflow.toString(), null, null, null, null, SchedulingAlgorithm.STATIC, null, null, 0L);
        org.workflowsim.WorkflowParser parser = new org.workflowsim.WorkflowParser(0);
        parser.parse();
        return parser.getTaskList();
    }

    private static Task findById(List<Task> tasks, int taskId) {
        for (Task task : tasks) {
            if (task.getCloudletId() == taskId) {
                return task;
            }
        }
        throw new IllegalArgumentException("未找到任务 " + taskId);
    }

    // ---- 夹具 ----

    /** Four-task two-layer DAG with inter-layer files for the shared-storage latency model. */
    private Path sharedDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        dax.append("  <job id=\"t1\" name=\"a\" runtime=\"60\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"output\" size=\"8000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t2\" name=\"b\" runtime=\"50\">\n");
        dax.append("    <uses name=\"out-2.bin\" link=\"output\" size=\"6000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t3\" name=\"c\" runtime=\"40\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"input\" size=\"8000000\"/>\n");
        dax.append("    <uses name=\"out-3.bin\" link=\"output\" size=\"7000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t4\" name=\"d\" runtime=\"30\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"input\" size=\"8000000\"/>\n");
        dax.append("    <uses name=\"out-2.bin\" link=\"input\" size=\"6000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n");
        dax.append("</adag>\n");
        Path path = Paths.get(tempDir.toFile().getAbsolutePath(), "shared-mirror.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile sharedPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("shared-mirror-platform");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        return builder.build();
    }
}
