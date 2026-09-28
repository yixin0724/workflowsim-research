package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** A prepared cost generation must not reuse another generation's OCT diagnostics. */
class PlanningDiagnosticLifecycleTest {
    @Test
    void reprepareAfterSourceWorkChangeRebuildsSuccessorCosts() {
        Task root = new Task(1, 1000L), child = new Task(2, 1000L);
        root.addChild(child); child.addParent(root);
        LocalPeftPlanningAlgorithm planner = planner(root, child, vm());
        planner.prepare();
        assertEquals(1.0, planner.priorityOf(root), 0.0);
        child.setCloudletLength(2000L);
        planner.prepare();
        assertEquals(2.0, planner.upwardRankOf(child), 0.0);
        assertEquals(2.0, planner.priorityOf(root), 0.0,
                "OCT must use the newly prepared 2000-MI successor, not cached old work");
    }

    @Test
    void reprepareAfterReplacingEquivalentVmObjectsDoesNotKeepOldVmKeys() {
        Task root = new Task(1, 1000L), child = new Task(2, 1000L);
        root.addChild(child); child.addParent(root);
        CondorVM oldVm = vm();
        LocalPeftPlanningAlgorithm planner = planner(root, child, oldVm);
        planner.prepare();
        // priorityOf is the lazy computation entry; optimisticCostOf only inspects an existing table.
        assertEquals(1.0, planner.priorityOf(root), 0.0);
        assertEquals(1.0, planner.optimisticCostOf(root, oldVm), 0.0);
        CondorVM replacement = vm();
        planner.setVmList(Collections.singletonList(replacement));
        planner.prepare();
        assertEquals(1.0, planner.computeSecondsOn(child, replacement), 0.0);
        assertEquals(1.0, planner.priorityOf(root), 0.0);
        assertEquals(1.0, planner.optimisticCostOf(root, replacement), 0.0);
    }

    private static CondorVM vm() {
        return new CondorVM(7, 0, 1000.0, 1, 512, 1000L, 10000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static LocalPeftPlanningAlgorithm planner(Task root, Task child, CondorVM machine) {
        SimulationConfig config = SimulationConfig.builder("not-parsed.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1()).build();
        PlatformProfile platform = PlatformProfile.builder("prepared-cost-lifecycle")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 100000L))
                .addVm(new PlatformProfile.VmSpec(7, 1000.0, 1, 512, 1000L, 10000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(7, 0).build();
        LocalPeftPlanningAlgorithm planner = new LocalPeftPlanningAlgorithm(new PlanningContext(config, platform));
        planner.setTaskList(Arrays.asList(root, child));
        planner.setVmList(Collections.singletonList(machine));
        return planner;
    }
}
