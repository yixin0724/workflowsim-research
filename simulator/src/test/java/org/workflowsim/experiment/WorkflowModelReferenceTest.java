package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class WorkflowModelReferenceTest {

    @Test
    void controlledReferenceIncludesStageInAndSharedStorageInputDelay() {
        Task root = new Task(1, 1000);
        Task child = new Task(2, 2000);
        root.addChild(child);
        child.addParent(root);
        root.addFile(file("root-output", 20_000_000.0, Parameters.FileType.OUTPUT));
        child.addFile(file("root-output", 20_000_000.0, Parameters.FileType.INPUT));

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Arrays.asList(root, child), controlledConfig(), heterogeneousPlatform());

        assertTrue(reference.isAvailable());
        assertEquals(WorkflowModelReference.CONTROLLED_SHARED_STORAGE_SCOPE, reference.getScope());
        // max(110 MI / 2000 MIPS, 0.1 秒事件间隔 + 0.01 秒安全量子)
        // 再加一个 0.1 秒 Workflow Engine 根任务释放间隔，
        // 加 1000 MI / 2000 MIPS，
        // 加 (2000 MI + 2000 MI 数据 stage-in) / 2000 MIPS。
        assertEquals(2.71, reference.getCriticalPathLowerBoundSeconds(), 1.0e-12);
        assertEquals(2, reference.getSourceTaskCount());
    }

    @Test
    void referenceIsUnavailableRatherThanMislabelledOutsideItsModelScope() {
        SimulationConfig localStorage = SimulationConfig.builder("workflow.dax", 2)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build();

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Arrays.asList(new Task(1, 1000)), localStorage, heterogeneousPlatform());

        assertFalse(reference.isAvailable());
        assertEquals(WorkflowModelReference.UNAVAILABLE_SCOPE, reference.getScope());
        assertEquals(0.0, reference.getCriticalPathLowerBoundSeconds(), 0.0);
    }

    @Test
    void controlledReferenceUsesTheLatestParentAtAForkJoin() {
        Task fastParent = new Task(1, 1000);
        Task slowParent = new Task(2, 3000);
        Task join = new Task(3, 2000);
        connect(fastParent, join);
        connect(slowParent, join);

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Arrays.asList(fastParent, slowParent, join), controlledConfig(), oneFastVmPlatform());

        assertTrue(reference.isAvailable());
        // 根任务释放为 max(110/2000, 0.1+0.01)+0.1=0.21；3000 MI 父任务在 1.71 完成，
        // 汇合任务还需 2000/2000=1.0 秒。
        assertEquals(2.71, reference.getCriticalPathLowerBoundSeconds(), 1.0e-12);
        assertEquals(3, reference.getSourceTaskCount());
    }

    @Test
    void referenceIsUnavailableWhenNoVmCanRunARequiredPeCount() {
        Task twoPeTask = new Task(1, 1000);
        twoPeTask.setNumberOfPes(2);

        WorkflowModelReference.Reference reference = WorkflowModelReference.calculate(
                Arrays.asList(twoPeTask), controlledConfig(), heterogeneousPlatform());

        assertFalse(reference.isAvailable());
        assertEquals("UNAVAILABLE_NO_COMPATIBLE_VM", reference.getScope());
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static SimulationConfig controlledConfig() {
        return SimulationConfig.builder("workflow.dax", 2).build();
    }

    private static PlatformProfile heterogeneousPlatform() {
        return PlatformProfile.builder("reference-metric-platform")
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

    private static PlatformProfile oneFastVmPlatform() {
        return PlatformProfile.builder("reference-one-fast-vm-platform")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 2000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000L, 20))
                .build();
    }

    private static FileItem file(String name, double size, Parameters.FileType type) {
        FileItem item = new FileItem(name, size);
        item.setType(type);
        return item;
    }
}
