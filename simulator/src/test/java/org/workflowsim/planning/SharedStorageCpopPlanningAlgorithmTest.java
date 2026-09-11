package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SharedStorageCpopPlanningAlgorithmTest {

    @Test
    void mapsTheIdentifiedCriticalPathToTheLowestCostProcessor() {
        Task root = new Task(1, 1000);
        Task criticalMiddle = new Task(2, 4000);
        Task tail = new Task(3, 1000);
        Task side = new Task(4, 500);
        connect(root, criticalMiddle);
        connect(criticalMiddle, tail);
        connect(root, side);

        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageCpopPlanningAlgorithm planner = new SharedStorageCpopPlanningAlgorithm(
                new PlanningContext(config, platform()));
        planner.setTaskList(Arrays.asList(root, criticalMiddle, tail, side));
        planner.setVmList(Arrays.asList(vm(0, 1000.0), vm(1, 2000.0)));

        planner.run();

        // root -> criticalMiddle -> tail 是 CPOP 关键路径，VM 1 对整条路径的模型执行
        // 总代价最小，因而被选为关键处理器。
        assertEquals(1, root.getVmId());
        assertEquals(1, criticalMiddle.getVmId());
        assertEquals(1, tail.getVmId());
        // stage-in 按最小 VM ID 分派；随后 CPOP 在选定的关键处理器上启动关键路径。
        assertEquals(0.21, root.getStaticScheduleStartTime(), 1.0e-12);
        assertEquals(0.71, criticalMiddle.getStaticScheduleStartTime(), 1.0e-12);
        assertEquals(2.71, tail.getStaticScheduleStartTime(), 1.0e-12);

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        // 在 1000/2000 MIPS 上平均的模型时长依次为 0.75、3.0、0.75、0.375 秒；
        // 关键链的优先级为 4.5。
        assertEquals(4.5, trace.getTaskPlan(1).getUpwardRankSeconds(), 1.0e-12);
        assertEquals(0.75, trace.getTaskPlan(2).getCpopDownwardRankSeconds(), 1.0e-12);
        assertEquals(4.5, trace.getTaskPlan(3).getCpopPrioritySeconds(), 1.0e-12);
        assertEquals(Arrays.asList(1, 2, 3), trace.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(1), trace.getCriticalProcessorVmId());
        assertNull(trace.getTaskPlan(1).getDlsDynamicLevelAtSelection());
        assertNull(trace.getTaskPlan(1).getDlsSelectionOrder());
    }

    @Test
    void usesTaskAndVmIdsToResolveEquivalentCpopCriticalPathsDeterministically() {
        Task firstRoot = new Task(1, 1000);
        Task secondRoot = new Task(2, 1000);
        Task firstTail = new Task(3, 1000);
        Task secondTail = new Task(4, 1000);
        connect(firstRoot, firstTail);
        connect(secondRoot, secondTail);

        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageCpopPlanningAlgorithm planner = new SharedStorageCpopPlanningAlgorithm(
                new PlanningContext(config, equalMipsPlatform()));
        planner.setTaskList(Arrays.asList(secondTail, firstTail, secondRoot, firstRoot));
        planner.setVmList(Arrays.asList(vm(8, 1000.0), vm(3, 1000.0)));

        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals(Arrays.asList(1, 3), trace.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(3), trace.getCriticalProcessorVmId());
        assertEquals(3, trace.getTaskPlan(1).getVmId());
        assertEquals(3, trace.getTaskPlan(3).getVmId());
        assertEquals(8, trace.getTaskPlan(2).getVmId());
        assertEquals(8, trace.getTaskPlan(4).getVmId());
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static CondorVM vm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile platform() {
        return PlatformProfile.builder("shared-storage-cpop-unit")
                .addHost(new PlatformProfile.HostSpec(0, 2, 4000.0,
                        2048, 10_000L, 1_000_000L))
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
        return PlatformProfile.builder("shared-storage-cpop-tie-unit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(3, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(8, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }
}
