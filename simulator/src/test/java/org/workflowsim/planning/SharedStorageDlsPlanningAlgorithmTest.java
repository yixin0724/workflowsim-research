package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SharedStorageDlsPlanningAlgorithmTest {

    @Test
    void choosesTheMaximumDynamicLevelTaskVmPairAtEachSelection() {
        Task root = new Task(1, 1000);
        Task independent = new Task(2, 1000);
        Task child = new Task(3, 1000);
        connect(root, child);

        SharedStorageDlsPlanningAlgorithm planner = planner(Arrays.asList(child, independent, root),
                Arrays.asList(vm(1, 2000.0), vm(0, 1000.0)), mixedMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals("DLS", trace.getStrategy());
        assertEquals(0.21, trace.getStageInFinishSeconds(), 1.0e-12);

        // 静态 b-level：root 为 1.5 秒，其余 Task 为 0.75 秒。DLS 先选 root：
        // 1.5 - 0.21 = 1.29；随后选择 VM 1 上的独立 Task：0.75 - 0.21 = 0.54。
        // 子 Task 必须等 root 至 1.21，故动态层为 0.75 - 1.21 = -0.46。
        assertEquals(1.5, trace.getTaskPlan(1).getUpwardRankSeconds(), 1.0e-12);
        assertEquals(1.29, trace.getTaskPlan(1).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(0.54, trace.getTaskPlan(2).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(-0.46, trace.getTaskPlan(3).getDlsDynamicLevelAtSelection(), 1.0e-12);
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(1).getDlsSelectionOrder());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(2).getDlsSelectionOrder());
        assertEquals(Integer.valueOf(3), trace.getTaskPlan(3).getDlsSelectionOrder());
        assertEquals(0, trace.getTaskPlan(1).getVmId());
        assertEquals(1, trace.getTaskPlan(2).getVmId());
        assertEquals(0, trace.getTaskPlan(3).getVmId());
        assertNull(trace.getTaskPlan(1).getCpopPrioritySeconds());
    }

    @Test
    void resolvesEqualDynamicLevelsByTaskThenVmId() {
        Task higherId = new Task(8, 1000);
        Task lowerId = new Task(2, 1000);

        SharedStorageDlsPlanningAlgorithm planner = planner(Arrays.asList(higherId, lowerId),
                Arrays.asList(vm(7, 1000.0), vm(3, 1000.0)), equalMipsPlatform());
        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Integer.valueOf(1), trace.getTaskPlan(2).getDlsSelectionOrder());
        assertEquals(3, trace.getTaskPlan(2).getVmId());
        assertEquals(Integer.valueOf(2), trace.getTaskPlan(8).getDlsSelectionOrder());
    }

    private static SharedStorageDlsPlanningAlgorithm planner(List<Task> tasks,
            List<CondorVM> vms, PlatformProfile platform) {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageDlsPlanningAlgorithm result = new SharedStorageDlsPlanningAlgorithm(
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
        return PlatformProfile.builder("shared-storage-dls-mixed")
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
        return PlatformProfile.builder("shared-storage-dls-ties")
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
