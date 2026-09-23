package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/** CPOP 的手算 rank、真实关键路径及确定性规划契约；不启动 CloudSim。 */
class LocalCpopPlanningAlgorithmTest {

    private static final double EPSILON = 1.0e-9;
    private static final double BOOTSTRAP = 110.1;

    @Test
    void chainRanksIncludePredecessorComputeAndIncomingCommunication() {
        Task entry = new Task(1, 2);
        Task middle = new Task(2, 3);
        Task exit = new Task(3, 5);
        connect(entry, middle, 7.0);
        connect(middle, exit, 11.0);
        LocalCpopPlanningAlgorithm planner = planner(Arrays.asList(exit, entry, middle), 8, 3);

        planner.run();

        // 最长路径 = 2 + 7 + 3 + 11 + 5 = 28；向下 rank 不包含本任务成本。
        assertRanks(planner, entry, 28.0, 0.0, 28.0);
        assertRanks(planner, middle, 19.0, 9.0, 28.0);
        assertRanks(planner, exit, 5.0, 23.0, 28.0);
        assertEquals(Arrays.asList(1, 2, 3), planner.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(3), planner.getCriticalProcessorVmId());
        // 关键路径同 VM 执行，rank 中的平均通信成本不进入其实际本地传输时间。
        assertEquals(BOOTSTRAP, entry.getStaticScheduleStartTime(), EPSILON);
        assertEquals(BOOTSTRAP + 2.0, middle.getStaticScheduleStartTime(), EPSILON);
        assertEquals(BOOTSTRAP + 5.0, exit.getStaticScheduleStartTime(), EPSILON);
        assertEquals(BOOTSTRAP + 10.0, planner.plannedFinishOf(exit), EPSILON);
        assertThrows(UnsupportedOperationException.class,
                () -> planner.getCriticalPathTaskIds().add(Integer.valueOf(99)));
    }

    @Test
    void paperRanksSelectTheHandDerivedCriticalPathAndProcessor() {
        double[][] costs = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
        };
        int[][] edges = {
            {1, 2, 18}, {1, 3, 12}, {1, 4, 9}, {1, 5, 11}, {1, 6, 14},
            {2, 8, 19}, {2, 9, 16}, {3, 7, 23}, {4, 8, 27}, {4, 9, 23},
            {5, 9, 13}, {6, 8, 15}, {7, 10, 17}, {8, 10, 11}, {9, 10, 13}
        };
        Task[] tasks = new Task[costs.length];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = new Task(i + 1, 1);
            Map<Integer, Double> projection = new LinkedHashMap<Integer, Double>();
            for (int vmId = 0; vmId < 3; vmId++) {
                projection.put(Integer.valueOf(vmId), Double.valueOf(costs[i][vmId]));
            }
            tasks[i].setVmExecutionCostSeconds(projection);
        }
        for (int[] edge : edges) {
            connect(tasks[edge[0] - 1], tasks[edge[1] - 1], edge[2]);
        }
        List<Task> reversed = new ArrayList<Task>(Arrays.asList(tasks));
        Collections.reverse(reversed);
        LocalCpopPlanningAlgorithm planner = planner(reversed, 2, 0, 1);

        // 来自给定矩阵与边权的独立手算；不在测试中复写 rank 的递归算法。
        double[] upward = {108, 77, 80, 80, 69, 190.0 / 3.0,
            128.0 / 3.0, 107.0 / 3.0, 133.0 / 3.0, 44.0 / 3.0};
        double[] downward = {0, 31, 25, 22, 24, 27,
            187.0 / 3.0, 200.0 / 3.0, 191.0 / 3.0, 280.0 / 3.0};
        double[] priority = {108, 108, 105, 102, 93, 271.0 / 3.0,
            105, 307.0 / 3.0, 108, 108};
        int[] vmByTask = {1, 1, 0, 2, 1, 2, 0, 2, 1, 1};
        double[] relativeStarts = {0, 16, 28, 25, 35, 42, 39, 54, 65, 79};
        double[] relativeFinishes = {16, 35, 39, 42, 48, 51, 46, 68, 77, 86};
        // 第二次复用同一实例：rank、预留区间和已演进的跨 VM 文件副本必须重建。
        for (int run = 0; run < 2; run++) {
            planner.run();
            assertEquals(Arrays.asList(1, 2, 9, 10), planner.getCriticalPathTaskIds());
            // CP 计算成本 vm0=66、vm1=54、vm2=63，故选择 vm1。
            assertEquals(Integer.valueOf(1), planner.getCriticalProcessorVmId());
            for (int i = 0; i < tasks.length; i++) {
                assertRanks(planner, tasks[i], upward[i], downward[i], priority[i]);
                assertEquals(vmByTask[i], tasks[i].getVmId(), "task " + (i + 1));
                assertEquals(BOOTSTRAP + relativeStarts[i],
                        tasks[i].getStaticScheduleStartTime(), EPSILON, "task " + (i + 1));
                assertEquals(BOOTSTRAP + relativeFinishes[i],
                        planner.plannedFinishOf(tasks[i]), EPSILON, "task " + (i + 1));
            }
        }
    }

    @Test
    void equalCriticalPathsChooseOnePathAndUseStableTaskAndVmIds() {
        Task entry = new Task(10, 2);
        Task lowerIdBranch = new Task(20, 4);
        Task higherIdBranch = new Task(30, 4);
        Task exit = new Task(40, 3);
        // 故意先登记高 ID 分支，不能由相邻表顺序决定关键路径。
        connect(entry, higherIdBranch, 0.0);
        connect(entry, lowerIdBranch, 0.0);
        connect(higherIdBranch, exit, 0.0);
        connect(lowerIdBranch, exit, 0.0);
        LocalCpopPlanningAlgorithm planner = planner(
                Arrays.asList(exit, higherIdBranch, entry, lowerIdBranch), 8, 3);

        planner.run();

        assertRanks(planner, entry, 9.0, 0.0, 9.0);
        assertRanks(planner, lowerIdBranch, 7.0, 2.0, 9.0);
        assertRanks(planner, higherIdBranch, 7.0, 2.0, 9.0);
        assertRanks(planner, exit, 3.0, 6.0, 9.0);
        assertEquals(Arrays.asList(10, 20, 40), planner.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(3), planner.getCriticalProcessorVmId());
        assertEquals(3, lowerIdBranch.getVmId());
        assertEquals(8, higherIdBranch.getVmId(),
                "另一个等长分支应按 EFT 使用空闲 VM，不应把所有同优先级节点都固定到关键 VM");

        planner.setTaskList(Arrays.asList(lowerIdBranch, entry, exit, higherIdBranch));
        planner.setVmList(Arrays.asList(vm(3), vm(8)));
        planner.run();
        assertEquals(Arrays.asList(10, 20, 40), planner.getCriticalPathTaskIds());
        assertEquals(Integer.valueOf(3), planner.getCriticalProcessorVmId());
        assertEquals(8, higherIdBranch.getVmId());
    }

    @Test
    void rejectsAShortcutBetweenNodesOnDifferentCriticalPaths() {
        Task entry = new Task(1, 1);
        Task firstBranch = new Task(2, 1);
        Task shortcutTarget = new Task(3, 1);
        Task secondBranch = new Task(4, 1);
        Task exit = new Task(5, 1);
        // 两条最长路径均为 100：1-2-5 = 1+8+1+89+1；
        // 1-4-3-5 = 1+38+1+38+1+20+1。五个节点的优先级都为 100。
        connect(entry, firstBranch, 8.0);
        connect(firstBranch, exit, 89.0);
        connect(entry, secondBranch, 38.0);
        connect(secondBranch, shortcutTarget, 38.0);
        connect(shortcutTarget, exit, 20.0);
        // 若只检查 successor 的优先级，较小 ID=3 会被选中，得到长度仅为 33 的捷径。
        connect(firstBranch, shortcutTarget, 1.0);
        List<Task> tasks = Arrays.asList(exit, shortcutTarget, secondBranch, firstBranch, entry);
        LocalCpopPlanningAlgorithm planner = planner(tasks, 8, 3);

        planner.run();

        for (Task task : tasks) {
            assertEquals(100.0, planner.priorityOf(task), EPSILON);
        }
        assertEquals(91.0, planner.upwardRankOf(firstBranch), EPSILON);
        assertEquals(22.0, planner.upwardRankOf(shortcutTarget), EPSILON);
        assertEquals(Arrays.asList(1, 2, 5), planner.getCriticalPathTaskIds());
        assertFalse(planner.getCriticalPathTaskIds().contains(Integer.valueOf(3)));
        assertFalse(planner.getCriticalPathTaskIds().contains(Integer.valueOf(4)));
    }

    @Test
    void singleVmFileEdgesHaveZeroCommunicationAndFiniteRanks() {
        Task entry = new Task(1, 2);
        Task middle = new Task(2, 3);
        Task exit = new Task(3, 5);
        connect(entry, middle, 7.0);
        connect(middle, exit, 11.0);
        LocalCpopPlanningAlgorithm planner = planner(Arrays.asList(exit, entry, middle), 9);

        planner.run();

        assertEquals(0.0, planner.meanCommunicationSeconds(entry, middle), 0.0);
        assertEquals(0.0, planner.meanCommunicationSeconds(middle, exit), 0.0);
        assertRanks(planner, entry, 10.0, 0.0, 10.0);
        assertRanks(planner, middle, 8.0, 2.0, 10.0);
        assertRanks(planner, exit, 5.0, 5.0, 10.0);
        for (Task task : Arrays.asList(entry, middle, exit)) {
            assertTrue(Double.isFinite(planner.priorityOf(task)));
            assertEquals(9, task.getVmId());
        }
        assertEquals(Arrays.asList(1, 2, 3), planner.getCriticalPathTaskIds());
        assertEquals(BOOTSTRAP + 10.0, planner.plannedFinishOf(exit), EPSILON);
    }

    @Test
    void repeatedRunRecomputesRanksCostsAndChangedFileSizes() {
        Task entry = new Task(1, 2);
        Task exit = new Task(2, 3);
        connect(entry, exit, 7.0);
        LocalCpopPlanningAlgorithm planner = planner(Arrays.asList(entry, exit), 3, 8);
        planner.run();
        assertRanks(planner, entry, 12.0, 0.0, 12.0);
        assertRanks(planner, exit, 3.0, 9.0, 12.0);
        List<Integer> firstPath = planner.getCriticalPathTaskIds();

        entry.setCloudletLength(4L);
        entry.getFileList().get(0).setSize(11_000_000.0);
        exit.getFileList().get(0).setSize(11_000_000.0);
        planner.run();

        assertRanks(planner, entry, 18.0, 0.0, 18.0);
        assertRanks(planner, exit, 3.0, 15.0, 18.0);
        assertEquals(BOOTSTRAP + 4.0, exit.getStaticScheduleStartTime(), EPSILON);
        assertEquals(BOOTSTRAP + 7.0, planner.plannedFinishOf(exit), EPSILON);
        assertEquals(Arrays.asList(1, 2), firstPath,
                "已返回的路径快照不应在后续 run 中被清空或修改");
    }

    private static void assertRanks(LocalCpopPlanningAlgorithm planner, Task task,
            double upward, double downward, double priority) {
        String subject = "task " + task.getCloudletId();
        assertEquals(upward, planner.upwardRankOf(task), EPSILON, subject + " upward rank");
        assertEquals(downward, planner.downwardRankOf(task), EPSILON, subject + " downward rank");
        assertEquals(priority, planner.priorityOf(task), EPSILON, subject + " priority");
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

    private static LocalCpopPlanningAlgorithm planner(List<Task> tasks, int... vmIds) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-cpop-unit");
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (int index = 0; index < vmIds.length; index++) {
            int vmId = vmIds[index];
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0,
                    2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(vmId, 1.0, 1, 512,
                    1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            vms.add(vm(vmId));
        }
        SimulationConfig config = SimulationConfig.builder("cpop-unit.dax", vmIds.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalCpopPlanningAlgorithm planner = new LocalCpopPlanningAlgorithm(
                new PlanningContext(config, platform.build()));
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        return planner;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1.0, 1, 512, 1L, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }
}
