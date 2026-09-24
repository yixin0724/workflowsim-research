package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * LOCAL 轨道基类扩展（PEFT 基础设施）契约测试：按 VM 对的通信秒数与乐观
 * （EFT + OCT）分配；不启动 CloudSim。
 *
 * <p>通过 {@link LocalHeftPlanningAlgorithm} 作为 {@code AbstractLocalCommPlanningAlgorithm}
 * 的载体实例调用包内方法。fixture 约定与 {@code LocalCpopPlanningAlgorithmTest} 一致：
 * VM mips=1.0，{@code connect(parent, child, c)} 生成 c×10⁶ 字节文件，bw=1 时跨 VM
 * 传输恰为 c 秒。</p>
 */
class LocalCommPlanningExtensionsTest {

    private static final double EPSILON = 1.0e-9;

    @Test
    void communicationSecondsAreZeroOnSameVmAndMatchEdgeWeightAcrossUniformVms() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        Task sibling = new Task(3, 4);
        connect(entry, exit, 7.0);
        connect(entry, sibling, 0.0);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(
                Arrays.asList(entry, exit, sibling), first, second);
        planner.prepare();

        assertEquals(7.0, planner.communicationSeconds(entry, exit, first, second), EPSILON);
        assertEquals(7.0, planner.communicationSeconds(entry, exit, second, first), EPSILON);
        assertEquals(0.0, planner.communicationSeconds(entry, exit, first, first), EPSILON,
                "同 VM 传输为零");
        assertEquals(0.0, planner.communicationSeconds(entry, sibling, first, second), EPSILON,
                "边上无共享文件时通信秒数为零");
    }

    @Test
    void communicationSecondsUseSlowerEndpointBandwidth() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 8.0);
        CondorVM slow = vm(3, 2L);
        CondorVM fast = vm(8, 4L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), slow, fast);
        planner.prepare();

        // 8×10⁶ 字节 / (10⁶ × min(2, 4)) = 4.0 秒；方向对称。
        assertEquals(4.0, planner.communicationSeconds(entry, exit, slow, fast), EPSILON);
        assertEquals(4.0, planner.communicationSeconds(entry, exit, fast, slow), EPSILON);
        assertEquals(4.0, planner.meanCommunicationSeconds(entry, exit), EPSILON,
                "按对约定应与既有跨对平均约定同源（均匀对时相等）");
    }

    @Test
    void allocateOptimisticAddsOctToFinishBeforeChoosingVm() {
        Task entry = new Task(1, 4);
        Task exit = new Task(2, 2);
        connect(entry, exit, 0.0);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        // 对照组：EFT 平局下 allocate 取较小 VM ID（既有行为不变）。
        double entryFinish = planner.allocate(entry, null, stageIn);
        assertEquals(3, entry.getVmId());
        assertEquals(stageIn + 4.0, entryFinish, EPSILON);

        Map<CondorVM, Double> oct = new LinkedHashMap<CondorVM, Double>();
        oct.put(first, Double.valueOf(100.0));
        oct.put(second, Double.valueOf(0.0));
        double exitFinish = planner.allocateOptimistic(exit, oct, stageIn);

        assertEquals(8, exit.getVmId(), "OCT 惩罚应把任务从较小 ID VM 上移开");
        assertEquals(stageIn + 6.0, exitFinish, EPSILON, "返回值是 EFT 完成时刻，不含 OCT 项");
        assertEquals(stageIn + 4.0, exit.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 6.0, planner.plannedFinishOf(exit), EPSILON);
    }

    @Test
    void allocateOptimisticTiesChooseSmallerVmId() {
        Task entry = new Task(1, 2);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(
                Collections.singletonList(entry), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        Map<CondorVM, Double> oct = new LinkedHashMap<CondorVM, Double>();
        oct.put(first, Double.valueOf(5.0));
        oct.put(second, Double.valueOf(5.0));
        planner.allocateOptimistic(entry, oct, stageIn);

        assertEquals(3, entry.getVmId(), "评分平局取较小 VM ID");
        assertEquals(stageIn + 2.0, planner.plannedFinishOf(entry), EPSILON);
    }

    @Test
    void allocateOptimisticRequiresOctForEveryCandidateVm() {
        Task entry = new Task(1, 2);
        CondorVM first = vm(3, 1L);
        CondorVM second = vm(8, 1L);
        LocalHeftPlanningAlgorithm planner = planner(
                Collections.singletonList(entry), first, second);
        planner.prepare();
        double stageIn = planner.stageInFinishTime();

        assertThrows(IllegalArgumentException.class,
                () -> planner.allocateOptimistic(entry, null, stageIn));

        Map<CondorVM, Double> partial = new LinkedHashMap<CondorVM, Double>();
        partial.put(first, Double.valueOf(1.0));
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> planner.allocateOptimistic(entry, partial, stageIn));
        assertTrue(missing.getMessage().contains("missing OCT"));
    }

    private static void connect(Task parent, Task child, double communicationSeconds) {
        parent.addChild(child);
        child.addParent(parent);
        if (communicationSeconds > 0.0) {
            String name = "edge-" + parent.getCloudletId() + "-" + child.getCloudletId();
            FileItem output = new FileItem(name, communicationSeconds * 1_000_000.0);
            output.setType(Parameters.FileType.OUTPUT);
            parent.addFile(output);
            FileItem input = new FileItem(name, communicationSeconds * 1_000_000.0);
            input.setType(Parameters.FileType.INPUT);
            child.addFile(input);
        }
    }

    private static LocalHeftPlanningAlgorithm planner(List<Task> tasks, CondorVM... vms) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-ext-unit");
        for (int index = 0; index < vms.length; index++) {
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0,
                    2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(vms[index].getId(), vms[index].getMips(),
                    1, 512, vms[index].getBw(), 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        SimulationConfig config = SimulationConfig.builder("ext-unit.dax", vms.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalHeftPlanningAlgorithm planner = new LocalHeftPlanningAlgorithm(
                new PlanningContext(config, platform.build()));
        planner.setTaskList(new ArrayList<Task>(tasks));
        planner.setVmList(new ArrayList<CondorVM>(Arrays.asList(vms)));
        return planner;
    }

    private static CondorVM vm(int id, long bw) {
        return new CondorVM(id, 0, 1.0, 1, 512, bw, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }
}
