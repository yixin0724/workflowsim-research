package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SharedStoragePeftPlanningAlgorithmTest {

    @Test
    void ranksReadyTasksByOptimisticSuccessorCostAndSelectsMinimumEftPlusOct() {
        Task successorAwareRoot = new Task(1, 1_000);
        Task longRoot = new Task(2, 100_000);
        Task longSuccessor = new Task(3, 4_000);
        Task shortSuccessor = new Task(4, 1_000);
        connect(successorAwareRoot, longSuccessor);
        connect(longRoot, shortSuccessor);

        SharedStoragePeftPlanningAlgorithm planner = planner(
                Arrays.asList(shortSuccessor, longRoot, longSuccessor, successorAwareRoot),
                Arrays.asList(vm(1, 2_000.0), vm(0, 1_000.0)), mixedMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals("PEFT", trace.getStrategy());
        // OCT 不含当前 Task 的执行时间；Task 1 的后继乐观最快执行为 2.0 秒，而 Task 2
        // 为 0.5 秒，因此 Task 1 在 ready-list 平局中获胜，尽管 Task 2 本身更长。
        assertEquals(2.0, trace.getTaskPlan(1).getPeftRankOctSeconds(), 1.0e-12);
        assertEquals(0.5, trace.getTaskPlan(2).getPeftRankOctSeconds(), 1.0e-12);
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(1).getPeftSelectionOrder());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(2).getPeftSelectionOrder());

        // 初始 stage-in 释放在 0.21 秒完成。Task 1 在 VM 1 上的 EFT 为 0.71、OCT 为 2.0，
        // 因而 PEFT 选定目标值为 2.71。
        assertEquals(1, trace.getTaskPlan(1).getVmId());
        assertEquals(2.0, trace.getTaskPlan(1).getPeftOptimisticCostAtSelectedVmSeconds(),
                1.0e-12);
        assertEquals(2.71, trace.getTaskPlan(1).getPeftEftPlusOptimisticCostSeconds(), 1.0e-12);
        assertEquals(1, trace.getTaskPlan(2).getVmId());
        assertNull(trace.getTaskPlan(1).getEtfEarliestStartAtSelection());
        assertNull(trace.getTaskPlan(1).getDlsDynamicLevelAtSelection());
    }

    @Test
    void resolvesEqualOptimisticRanksAndScoresByTaskThenVmId() {
        Task higherId = new Task(8, 1_000);
        Task lowerId = new Task(2, 1_000);

        SharedStoragePeftPlanningAlgorithm planner = planner(Arrays.asList(higherId, lowerId),
                Arrays.asList(vm(7, 1_000.0), vm(3, 1_000.0)), equalMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(2).getPeftSelectionOrder());
        assertEquals(3, trace.getTaskPlan(2).getVmId());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(8).getPeftSelectionOrder());
        assertTrue(trace.getTaskPlan(2).getPeftEftPlusOptimisticCostSeconds() > 0.0);
    }

    private static SharedStoragePeftPlanningAlgorithm planner(List<Task> tasks,
            List<CondorVM> vms, PlatformProfile platform) {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStoragePeftPlanningAlgorithm result = new SharedStoragePeftPlanningAlgorithm(
                new PlanningContext(config, platform));
        result.setTaskList(tasks);
        result.setVmList(vms);
        return result;
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static CondorVM vm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile mixedMipsPlatform() {
        return PlatformProfile.builder("shared-storage-peft-mixed")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0,
                        1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 2000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 2000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }

    private static PlatformProfile equalMipsPlatform() {
        return PlatformProfile.builder("shared-storage-peft-ties")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(3, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(7, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }
}
