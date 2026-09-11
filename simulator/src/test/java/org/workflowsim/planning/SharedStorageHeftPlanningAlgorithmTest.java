package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SharedStorageHeftPlanningAlgorithmTest {

    @Test
    void accountsForStageInAndPerTaskSharedStorageInputTime() {
        Task root = new Task(1, 1000);
        Task middle = new Task(2, 1000);
        Task tail = new Task(3, 1000);
        connect(root, middle);
        connect(middle, tail);

        FileItem output = file("middle-input", 20_000_000.0, Parameters.FileType.OUTPUT);
        FileItem input = file("middle-input", 20_000_000.0, Parameters.FileType.INPUT);
        root.addFile(output);
        middle.addFile(input);

        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = oneVmPlatform(20);
        SharedStorageHeftPlanningAlgorithm planner = new SharedStorageHeftPlanningAlgorithm(
                new PlanningContext(config, platform));
        planner.setTaskList(Arrays.asList(root, middle, tail));
        planner.setVmList(Arrays.asList(vm(0, 1000.0)));

        planner.run();

        // 模型生成的 110 MI stage-in Job 先执行；middle Task 随后包含 1 秒 CPU 和
        // 20 MB / 20 MB/s 的共享存储输入。
        assertEquals(0, root.getVmId());
        assertEquals(0.21, root.getStaticScheduleStartTime(), 1.0e-12);
        assertEquals(1.21, middle.getStaticScheduleStartTime(), 1.0e-12);
        assertEquals(3.21, tail.getStaticScheduleStartTime(), 1.0e-12);
    }

    @Test
    void exposesUpwardRanksAndUsesReservationGapInsertionUnderTheDeclaredModel() {
        Task root = new Task(1, 1000);
        Task constrainedChild = new Task(2, 10_000);
        constrainedChild.setNumberOfPes(2);
        Task independent = new Task(3, 1000);
        connect(root, constrainedChild);

        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        SharedStorageHeftPlanningAlgorithm planner = new SharedStorageHeftPlanningAlgorithm(
                new PlanningContext(config, twoVmPlatform()));
        planner.setTaskList(Arrays.asList(root, constrainedChild, independent));
        planner.setVmList(Arrays.asList(vm(1, 1000.0, 2), vm(0, 1000.0, 1)));

        planner.run();

        SharedStorageDagPlanTrace trace = planner.getLastPlanTrace();
        assertEquals("HEFT", trace.getStrategy());
        assertEquals(0.21, trace.getStageInFinishSeconds(), 1.0e-12);
        // CloudSim SpaceShared 对多 PE Cloudlet 并行执行（墙钟时间 = length/capacity，
        // 与 PE 数无关）；因此双 PE 子 Task 10,000 MI 在 1000 MIPS 上耗时 10 秒，
        // 即 r_u(root) = 1 + 10。
        assertEquals(11.0, trace.getTaskPlan(1).getUpwardRankSeconds(), 1.0e-12);
        assertEquals(10.0, trace.getTaskPlan(2).getUpwardRankSeconds(), 1.0e-12);
        assertEquals(1.0, trace.getTaskPlan(3).getUpwardRankSeconds(), 1.0e-12);

        assertEquals(0, trace.getTaskPlan(1).getVmId());
        assertEquals(1, trace.getTaskPlan(2).getVmId());
        assertEquals(1, trace.getTaskPlan(3).getVmId());
        assertEquals(1.21, trace.getTaskPlan(2).getPlannedStartSeconds(), 1.0e-12);
        // Task 3 的 rank 低于 Task 2、后被考虑，但可插入 VM 1 上 Task 2 尚未依赖就绪前的
        // 保留空隙。
        assertEquals(0.21, trace.getTaskPlan(3).getPlannedStartSeconds(), 1.0e-12);
        assertEquals(1.21, trace.getTaskPlan(3).getPlannedFinishSeconds(), 1.0e-12);
        assertNull(trace.getTaskPlan(1).getDlsDynamicLevelAtSelection());
        assertNull(trace.getTaskPlan(1).getDlsSelectionOrder());
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static FileItem file(String name, double size, Parameters.FileType type) {
        FileItem file = new FileItem(name, size);
        file.setType(type);
        return file;
    }

    private static CondorVM vm(int id, double mips) {
        return vm(id, mips, 1);
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static PlatformProfile oneVmPlatform(int transferRate) {
        return PlatformProfile.builder("shared-storage-heft-unit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, transferRate))
                .build();
    }

    private static PlatformProfile twoVmPlatform() {
        return PlatformProfile.builder("shared-storage-heft-insertion-unit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1000L, 100_000L))
                .addHost(new PlatformProfile.HostSpec(1, 2, 1000.0,
                        1024, 1000L, 100_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 2, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(0, 0)
                .pinVmToHost(1, 1)
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }
}
