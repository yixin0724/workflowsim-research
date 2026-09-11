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

class SharedStorageEtfPlanningAlgorithmTest {

    @Test
    void choosesTheEarliestTaskVmStartBeforeTheHigherStaticBLevel() {
        Task root = new Task(1, 1000);
        Task delayedHighLevel = new Task(2, 1000);
        Task independent = new Task(3, 1000);
        Task tail = new Task(4, 10_000);
        connect(root, delayedHighLevel);
        connect(delayedHighLevel, tail);

        SharedStorageEtfPlanningAlgorithm planner = planner(
                Arrays.asList(tail, independent, delayedHighLevel, root),
                Arrays.asList(vm(1, 2000.0), vm(0, 1000.0)), mixedMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals("ETF", trace.getStrategy());
        // 所有根 Task 的最早开始时间均为 0.21 秒时，root 以更高的静态 b-level 先被选择。
        // root 在 VM 0 保留后，Task 3 可在 VM 1 的 0.21 秒开始，而 Task 2 必须等到 root
        // 在 1.21 秒完成。因此 ETF 第二个选择 Task 3，尽管 Task 2 的静态 b-level 更大。
        assertTrue(trace.getTaskPlan(2).getUpwardRankSeconds()
                > trace.getTaskPlan(3).getUpwardRankSeconds());
        assertEquals(0.21, trace.getTaskPlan(1).getEtfEarliestStartAtSelection(), 1.0e-12);
        assertEquals(0.21, trace.getTaskPlan(3).getEtfEarliestStartAtSelection(), 1.0e-12);
        assertEquals(1.21, trace.getTaskPlan(2).getEtfEarliestStartAtSelection(), 1.0e-12);
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(1).getEtfSelectionOrder());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(3).getEtfSelectionOrder());
        assertEquals(Integer.valueOf(3), trace.getTaskPlan(2).getEtfSelectionOrder());
        assertEquals(0, trace.getTaskPlan(1).getVmId());
        assertEquals(1, trace.getTaskPlan(3).getVmId());
        assertNull(trace.getTaskPlan(1).getDlsDynamicLevelAtSelection());
    }

    @Test
    void resolvesEqualEarliestStartsByStaticBLevelThenTaskAndVmId() {
        Task higherId = new Task(8, 1000);
        Task lowerId = new Task(2, 1000);

        SharedStorageEtfPlanningAlgorithm planner = planner(Arrays.asList(higherId, lowerId),
                Arrays.asList(vm(7, 1000.0), vm(3, 1000.0)), equalMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(2).getEtfSelectionOrder());
        assertEquals(3, trace.getTaskPlan(2).getVmId());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(8).getEtfSelectionOrder());
    }

    private static SharedStorageEtfPlanningAlgorithm planner(List<Task> tasks,
            List<CondorVM> vms, PlatformProfile platform) {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageEtfPlanningAlgorithm result = new SharedStorageEtfPlanningAlgorithm(
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
        return PlatformProfile.builder("shared-storage-etf-mixed")
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
        return PlatformProfile.builder("shared-storage-etf-ties")
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
