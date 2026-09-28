package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Fixed-size, planner-only depth probes. No CloudSim run, growing stress loop or timing winner.
 * The small-stack worker makes depth, rather than a machine's default stack size, the subject.
 * Its timeout is only a containment guard; correctness comes from the known chain suffix sums.
 */
class PlanningDagDepthAuditTest {
    private static final int DEPTH = 4096;
    private static final int VM_ID = 7;
    private static final double BOOTSTRAP = 0.21;
    private static final double EPSILON = 1.0e-8;

    @Test
    void iterativeValidatorAcceptsBothIdDirectionsAtDepth4096() throws Exception {
        for (boolean reverseIds : new boolean[] {false, true}) {
            List<Task> path = chain(reverseIds);
            onBoundedStack("validator-" + reverseIds, () -> {
                WorkflowDagValidator.validateAndAssignDepths(path);
                for (int index = 0; index < path.size(); index++) {
                    assertEquals(index + 1, path.get(index).getDepth());
                }
            });
        }
    }

    @Test
    void localUpwardRanksAcceptForwardIdsAtDepth4096() throws Exception {
        List<Task> path = chain(false);
        LocalHeftPlanningAlgorithm planner = new LocalHeftPlanningAlgorithm(
                context(PlanningAlgorithm.LOCAL_HEFT, true));
        inputs(planner, path);
        // prepare() isolates the common LOCAL upward-rank pass from reservation insertion.
        onBoundedStack("local-upward", () -> {
            planner.prepare();
            for (int index = 0; index < path.size(); index++) {
                assertEquals(DEPTH - index, planner.upwardRankOf(path.get(index)), 0.0);
            }
        });
    }

    @Test
    void localCpopDownwardRanksAcceptReverseIdsAtDepth4096() throws Exception {
        List<Task> path = chain(true);
        LocalCpopPlanningAlgorithm planner = new LocalCpopPlanningAlgorithm(
                context(PlanningAlgorithm.LOCAL_CPOP, true));
        inputs(planner, path);
        // Reverse IDs prewarm upward ranks from the exit, exposing the separate parent walk.
        onBoundedStack("local-downward", () -> {
            planner.run();
            assertLocalChain(planner, path);
            for (int index = 0; index < path.size(); index++) {
                assertEquals(index, planner.downwardRankOf(path.get(index)), 0.0);
                assertEquals(DEPTH, planner.priorityOf(path.get(index)), 0.0);
                assertEquals(path.get(index).getCloudletId(),
                        planner.getCriticalPathTaskIds().get(index).intValue());
            }
            assertEquals(Integer.valueOf(VM_ID), planner.getCriticalProcessorVmId());
        });
    }

    @Test
    void localPeftOctAcceptsDepth4096AfterReverseIdRanksArePrepared() throws Exception {
        List<Task> path = chain(true);
        LocalPeftPlanningAlgorithm planner = new LocalPeftPlanningAlgorithm(
                context(PlanningAlgorithm.LOCAL_PEFT, true));
        inputs(planner, path);
        onBoundedStack("local-oct", () -> {
            planner.prepare();
            // Calling the package diagnostic on the root isolates OCT from the upward pass.
            assertEquals(DEPTH - 1, planner.priorityOf(path.get(0)), 0.0);
            CondorVM vm = planner.vms().get(0);
            for (int index = 0; index < path.size(); index++) {
                // PEFT counts successor work, never current work, and the exit is exactly zero.
                assertEquals(DEPTH - index - 1,
                        planner.optimisticCostOf(path.get(index), vm), 0.0);
            }
        });
    }

    @Test
    void sharedUpwardRanksAcceptForwardIdsAtDepth4096() throws Exception {
        List<Task> path = chain(false);
        SharedStorageHeftPlanningAlgorithm planner = new SharedStorageHeftPlanningAlgorithm(
                context(PlanningAlgorithm.SHARED_STORAGE_HEFT, false));
        inputs(planner, path);
        // Every shared DAG strategy enters this same upward-rank implementation.
        onBoundedStack("shared-upward", () -> {
            planner.run();
            assertSharedChain(planner.getLastPlanTrace(), path);
        });
    }

    @Test
    void sharedCpopDownwardRanksAcceptReverseIdsAtDepth4096() throws Exception {
        List<Task> path = chain(true);
        SharedStorageCpopPlanningAlgorithm planner = new SharedStorageCpopPlanningAlgorithm(
                context(PlanningAlgorithm.SHARED_STORAGE_CPOP, false));
        inputs(planner, path);
        onBoundedStack("shared-downward", () -> {
            planner.run();
            SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
            assertSharedChain(trace, path);
            for (int index = 0; index < path.size(); index++) {
                SharedStorageDagPlanTrace.TaskPlan task = trace.getTaskPlan(path.get(index).getCloudletId());
                assertEquals(index, task.getCpopDownwardRankSeconds(), 0.0);
                assertEquals(DEPTH, task.getCpopPrioritySeconds(), 0.0);
                assertEquals(path.get(index).getCloudletId(), trace.getCriticalPathTaskIds().get(index).intValue());
            }
            assertEquals(Integer.valueOf(VM_ID), trace.getCriticalProcessorVmId());
        });
    }

    @Test
    void sharedPeftOctAcceptsReverseIdsAtDepth4096() throws Exception {
        List<Task> path = chain(true);
        SharedStoragePeftPlanningAlgorithm planner = new SharedStoragePeftPlanningAlgorithm(
                context(PlanningAlgorithm.SHARED_STORAGE_PEFT, false));
        inputs(planner, path);
        // Upward ranks are already shallow; the first ready task still asks for the root OCT.
        onBoundedStack("shared-oct", () -> {
            planner.run();
            SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
            assertSharedChain(trace, path);
            for (int index = 0; index < path.size(); index++) {
                SharedStorageDagPlanTrace.TaskPlan task = trace.getTaskPlan(path.get(index).getCloudletId());
                assertEquals(DEPTH - index - 1, task.getPeftRankOctSeconds(), 0.0);
                assertEquals(DEPTH - index - 1, task.getPeftOptimisticCostAtSelectedVmSeconds(), 0.0);
                assertEquals(Integer.valueOf(index + 1), task.getPeftSelectionOrder());
            }
        });
    }

    private static List<Task> chain(boolean reverseIds) {
        List<Task> path = new ArrayList<Task>();
        for (int index = 0; index < DEPTH; index++) {
            Task task = new Task(reverseIds ? DEPTH - index : index + 1, 1000L);
            if (index > 0) {
                Task parent = path.get(index - 1);
                parent.addChild(task);
                task.addParent(parent);
            }
            path.add(task);
        }
        return path;
    }

    private static void inputs(BasePlanningAlgorithm planner, List<Task> path) {
        planner.setTaskList(new ArrayList<Task>(path));
        planner.setVmList(Collections.singletonList(new CondorVM(VM_ID, 0, 1000.0, 1,
                512, 1000L, 10000L, "Xen", new CloudletSchedulerSpaceShared())));
    }

    private static PlanningContext context(PlanningAlgorithm algorithm, boolean local) {
        PlatformProfile platform = PlatformProfile.builder("planning-depth-audit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(VM_ID, 1000.0, 1, 512, 1000L, 10000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(VM_ID, 0)
                .build();
        SimulationConfig.Builder config = SimulationConfig.builder("planning-depth-audit.dax", 1)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .cloudSimMinEventIntervalSeconds(0.1)
                .fileSystem(local ? ReplicaCatalog.FileSystem.LOCAL : ReplicaCatalog.FileSystem.SHARED);
        if (local) {
            config.dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
        }
        return new PlanningContext(config.build(), platform);
    }

    private static void assertLocalChain(AbstractLocalCommPlanningAlgorithm planner, List<Task> path) {
        for (int index = 0; index < path.size(); index++) {
            Task task = path.get(index);
            assertEquals(VM_ID, task.getVmId());
            assertEquals(DEPTH - index, planner.upwardRankOf(task), 0.0);
            assertEquals(BOOTSTRAP + index, task.getStaticScheduleStartTime(), EPSILON);
            assertEquals(BOOTSTRAP + index + 1, planner.plannedFinishOf(task), EPSILON);
        }
    }

    private static void assertSharedChain(SharedStorageDagPlanTrace trace, List<Task> path) {
        assertEquals(BOOTSTRAP, trace.getStageInFinishSeconds(), EPSILON);
        for (int index = 0; index < path.size(); index++) {
            Task source = path.get(index);
            SharedStorageDagPlanTrace.TaskPlan task = trace.getTaskPlan(source.getCloudletId());
            assertEquals(VM_ID, task.getVmId());
            assertEquals(DEPTH - index, task.getUpwardRankSeconds(), 0.0);
            assertEquals(BOOTSTRAP + index, task.getPlannedStartSeconds(), EPSILON);
            assertEquals(BOOTSTRAP + index + 1, task.getPlannedFinishSeconds(), EPSILON);
            assertEquals(task.getPlannedStartSeconds(), source.getStaticScheduleStartTime(), 0.0);
        }
    }

    private static void onBoundedStack(String name, Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(null, () -> {
            try {
                action.run();
            } catch (Throwable problem) {
                failure.set(problem);
            }
        }, "planning-depth-audit-" + name, 256L * 1024L);
        // Stack size is a JVM hint. Fixed input size is the bound even if a JVM ignores the hint.
        worker.setDaemon(true);
        worker.start();
        worker.join(30000L);
        if (worker.isAlive()) {
            worker.interrupt();
        }
        assertFalse(worker.isAlive(), name + " exceeded the 30-second containment guard");
        assertNull(failure.get(), () -> failureSummary(name, failure.get()));
    }

    private static String failureSummary(String name, Throwable failure) {
        if (failure == null) {
            return name;
        }
        StringBuilder message = new StringBuilder(name).append(": ").append(failure);
        StackTraceElement[] frames = failure.getStackTrace();
        for (int index = 0; index < Math.min(8, frames.length); index++) {
            message.append("\n  at ").append(frames[index]);
        }
        return message.toString();
    }
}
