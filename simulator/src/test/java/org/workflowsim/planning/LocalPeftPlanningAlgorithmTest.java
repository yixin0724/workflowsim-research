package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
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
 * LOCAL_PEFT（Arabnejad &amp; Barbosa, IEEE TPDS 2014）通信感知 PEFT 规划器单测：
 * OCT 递推数值、rank_o 优先级与插入式 EFT+OCT 分配；不启动 CloudSim。
 *
 * <p>fixture 约定：VM bw=1 时 {@code connect(parent, child, c)} 生成 c×10⁶ 字节文件，
 * 跨 VM 传输恰为 c 秒；mips 异构 VM 使 OCT 在不同 VM 上取不同值。
 * stageIn = 110 MI stage-in Job + 引擎间隔（按最小 VM ID 的 mips 折算，
 * mips=2.0 时为 55.1、mips=1.0 时为 110.1），断言一律经
 * {@code planner.stageInFinishTime()} 取值。</p>
 */
class LocalPeftPlanningAlgorithmTest {

    private static final double EPSILON = 1.0e-9;

    /**
     * 链 t1→t2→t3（边 t1→t2 = 10 秒，t2→t3 无文件）在异构 VM 上的 OCT 递推：
     * 出口 OCT = w̄_exit（论文约定）；同 VM 放置的零通信项使 min_p' 命中 p'=p；
     * 重边只抬高被淘汰的候选，不进入最终值。
     */
    @Test
    void octTableFollowsThePaperRecurrenceOnAChain() {
        Task t1 = new Task(1, 4);
        Task t2 = new Task(2, 2);
        Task t3 = new Task(3, 2);
        connect(t1, t2, 10.0);
        connect(t2, t3, 0.0);
        CondorVM fast = vm(3, 1L, 2.0);
        CondorVM slow = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2, t3), fast, slow);
        planner.run();

        // 出口 OCT = w̄_exit = (1 + 2) / 2 = 1.5（论文约定，逐 VM 一致）。
        assertEquals(1.5, planner.optimisticCostOf(t3, fast), EPSILON, "出口 OCT = w̄_exit");
        assertEquals(1.5, planner.optimisticCostOf(t3, slow), EPSILON);
        // OCT(t2, p) = w(t2, p) + min_p'(1.5 + c)；边无文件 c≡0 → 两放置同值。
        assertEquals(2.5, planner.optimisticCostOf(t2, fast), EPSILON);
        assertEquals(3.5, planner.optimisticCostOf(t2, slow), EPSILON);
        // OCT(t1, fast) = 2 + min(2.5+0, 3.5+10) = 4.5；
        // OCT(t1, slow) = 4 + min(2.5+10, 3.5+0) = 7.5。
        assertEquals(4.5, planner.optimisticCostOf(t1, fast), EPSILON);
        assertEquals(7.5, planner.optimisticCostOf(t1, slow), EPSILON);
        assertEquals(6.0, planner.priorityOf(t1), EPSILON, "rank_o = OCT 全 VM 平均");
        assertEquals(3.0, planner.priorityOf(t2), EPSILON);
        assertEquals(1.5, planner.priorityOf(t3), EPSILON);
    }

    /** 链 fixture 端到端：优先级降序分配，全部落在快速 VM 上，makespan = stageIn+4。 */
    @Test
    void chainSchedulesByMeanOctPriorityAndEftPlusOct() {
        Task t1 = new Task(1, 4);
        Task t2 = new Task(2, 2);
        Task t3 = new Task(3, 2);
        connect(t1, t2, 10.0);
        connect(t2, t3, 0.0);
        CondorVM fast = vm(3, 1L, 2.0);
        CondorVM slow = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2, t3), fast, slow);
        planner.run();
        double stageIn = planner.stageInFinishTime();

        assertEquals(3, t1.getVmId());
        assertEquals(stageIn, t1.getStaticScheduleStartTime(), EPSILON);
        assertEquals(3, t2.getVmId(), "EFT+OCT 均偏好快速 VM");
        assertEquals(stageIn + 2.0, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(3, t3.getVmId());
        assertEquals(stageIn + 3.0, t3.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t3), EPSILON);
    }

    /**
     * 菱形 t1→{t2a,t2b}→t3：t2b 的 OCT 劣势（2.0/2.5 对 t2a 的 3.5/5.5）不足以
     * 抵消其更早的 EFT（慢 VM 空闲），EFT+OCT 联合目标仍选慢 VM；t3 汇聚后回到
     * 快 VM。出口 w̄_exit = (1+2)/2 = 1.5。
     */
    @Test
    void diamondEftPlusOctJointObjectivePlacesMiddleBranchOffTheFastVm() {
        Task t1 = new Task(1, 2);
        Task t2a = new Task(2, 4);
        Task t2b = new Task(3, 1);
        Task t3 = new Task(4, 2);
        connect(t1, t2a, 0.0);
        connect(t1, t2b, 0.0);
        connect(t2a, t3, 1.0);
        connect(t2b, t3, 1.0);
        CondorVM fast = vm(3, 1L, 2.0);
        CondorVM slow = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(
                Arrays.asList(t1, t2a, t2b, t3), fast, slow);
        planner.run();
        double stageIn = planner.stageInFinishTime();

        assertEquals(5.0, planner.priorityOf(t1), EPSILON);
        assertEquals(4.5, planner.priorityOf(t2a), EPSILON);
        assertEquals(2.25, planner.priorityOf(t2b), EPSILON);
        assertEquals(1.5, planner.priorityOf(t3), EPSILON);

        assertEquals(3, t1.getVmId());
        assertEquals(3, t2a.getVmId());
        assertEquals(stageIn + 1.0, t2a.getStaticScheduleStartTime(), EPSILON);
        assertEquals(8, t2b.getVmId(), "EFT 优势大于 OCT 惩罚 → 慢 VM");
        assertEquals(stageIn + 1.0, t2b.getStaticScheduleStartTime(), EPSILON);
        // t3 在快 VM：数据到达 stageIn+3（t2a 本地、t2b 传输 1 秒），快 VM 恰在
        // stageIn+3 空闲（t2a 占至该时刻，t2b 在慢 VM）→ 立即开始。
        assertEquals(3, t3.getVmId());
        assertEquals(stageIn + 3.0, t3.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t3), EPSILON);
    }

    /** 单 VM 平台：min_p' 退化为单点（同 VM 零通信），调度仍成立。 */
    @Test
    void singleVmDegenerateCaseStillSchedules() {
        Task t1 = new Task(1, 3);
        Task t2 = new Task(2, 5);
        connect(t1, t2, 2.0);
        CondorVM only = vm(3, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2), only);
        planner.run();
        double stageIn = planner.stageInFinishTime();

        // OCT(t2, only) = w̄_exit = 5；OCT(t1, only) = 3 + min_p'(5 + c)= 3 + 5 = 8。
        assertEquals(8.0, planner.optimisticCostOf(t1, only), EPSILON, "同 VM 零通信");
        assertEquals(5.0, planner.optimisticCostOf(t2, only), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(3, t2.getVmId());
        assertEquals(stageIn + 3.0, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 8.0, planner.plannedFinishOf(t2), EPSILON);
    }

    /**
     * 论文算例 OCT/rank_o 表逐项复现（Arabnejad &amp; Barbosa, IEEE TPDS 2014 §IV
     * 算例；与 Topcuoglu TPDS 2002 同一 10 任务 DAG 与计算成本矩阵，即
     * {@code heft-paper-example.dax}）。出口 OCT = w̄_exit = 44/3；30 个 OCT 值、
     * 10 个 rank_o 值与论文算例表一致（n1=61、n2=48、n3=40、n4=44、n5=43、
     * n6=37.33、n7=25.67、n8=24.67、n9=31.33、n10=14.67），由此得到的选择顺序
     * n1,n2,n4,n5,n3,n6,n9,n7,n8,n10 与论文一致；VM 映射与论文调度（makespan 76，
     * 优于 HEFT 的 80）在规划层同样复现。VM mips=1.0 时计算成本矩阵的整数秒数
     * 经 populateCosts 折算为整 MI 无舍入损失。
     */
    @Test
    void paperExampleOctTableMatchesPublishedValues() {
        double[][] cost = {
                {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
                {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}};
        int[][] edges = {
                {1, 2, 18}, {1, 3, 12}, {1, 4, 9}, {1, 5, 11}, {1, 6, 14},
                {2, 8, 19}, {2, 9, 16}, {3, 7, 23}, {4, 8, 27}, {4, 9, 23},
                {5, 9, 13}, {6, 8, 15}, {7, 10, 17}, {8, 10, 11}, {9, 10, 13}};
        Task[] tasks = new Task[11];
        for (int id = 1; id <= 10; id++) {
            tasks[id] = new Task(id, 100);
            Map<Integer, Double> perVm = new HashMap<Integer, Double>();
            for (int vm = 0; vm < 3; vm++) {
                perVm.put(Integer.valueOf(vm), Double.valueOf(cost[id - 1][vm]));
            }
            tasks[id].setVmExecutionCostSeconds(perVm);
        }
        for (int[] edge : edges) {
            connect(tasks[edge[0]], tasks[edge[1]], edge[2]);
        }
        CondorVM p1 = vm(0, 1L, 1.0);
        CondorVM p2 = vm(1, 1L, 1.0);
        CondorVM p3 = vm(2, 1L, 1.0);
        List<Task> taskList = new ArrayList<Task>();
        for (int id = 1; id <= 10; id++) {
            taskList.add(tasks[id]);
        }
        LocalPeftPlanningAlgorithm planner = planner(taskList, p1, p2, p3);
        planner.run();

        // 论文算例 OCT 表（精确分数；十进制近似见 Javadoc）。
        double[][] publishedOct = {
                {},
                {179.0 / 3.0, 185.0 / 3.0, 185.0 / 3.0},
                {137.0 / 3.0, 137.0 / 3.0, 158.0 / 3.0},
                {98.0 / 3.0, 128.0 / 3.0, 134.0 / 3.0},
                {137.0 / 3.0, 104.0 / 3.0, 155.0 / 3.0},
                {134.0 / 3.0, 119.0 / 3.0, 134.0 / 3.0},
                {98.0 / 3.0, 125.0 / 3.0, 113.0 / 3.0},
                {65.0 / 3.0, 89.0 / 3.0, 77.0 / 3.0},
                {59.0 / 3.0, 77.0 / 3.0, 86.0 / 3.0},
                {98.0 / 3.0, 80.0 / 3.0, 104.0 / 3.0},
                {44.0 / 3.0, 44.0 / 3.0, 44.0 / 3.0}};
        double[] publishedRankO = {
                0.0, 61.0, 48.0, 40.0, 44.0, 43.0,
                112.0 / 3.0, 77.0 / 3.0, 74.0 / 3.0, 94.0 / 3.0, 44.0 / 3.0};
        CondorVM[] vms = {p1, p2, p3};
        for (int id = 1; id <= 10; id++) {
            for (int vm = 0; vm < 3; vm++) {
                assertEquals(publishedOct[id][vm],
                        planner.optimisticCostOf(tasks[id], vms[vm]), EPSILON,
                        "OCT(n" + id + ", p" + (vm + 1) + ")");
            }
            assertEquals(publishedRankO[id], planner.priorityOf(tasks[id]), EPSILON,
                    "rank_o(n" + id + ")");
        }

        // 论文调度映射：n1,n2,n6→p3；n3,n5,n7,n8→p1；n4,n9,n10→p2。
        int[] paperVm = {-1, 2, 2, 0, 1, 0, 2, 0, 0, 1, 1};
        for (int id = 1; id <= 10; id++) {
            assertEquals(paperVm[id], tasks[id].getVmId(), "n" + id + " 的 VM 映射");
        }
        double stageIn = planner.stageInFinishTime();
        assertEquals(110.1, stageIn, EPSILON, "mips=1.0 时引导偏移与论文全栈复现一致");
        // 论文调度区间（相对 stageIn；makespan 76）。
        double[] paperStarts = {0.0, 0.0, 9.0, 32.0, 18.0, 20.0, 27.0, 43.0, 53.0, 45.0, 69.0};
        double[] paperFinishes = {0.0, 9.0, 27.0, 43.0, 26.0, 32.0, 36.0, 50.0, 58.0, 57.0, 76.0};
        for (int id = 1; id <= 10; id++) {
            assertEquals(stageIn + paperStarts[id],
                    tasks[id].getStaticScheduleStartTime(), EPSILON,
                    "n" + id + " 计划开始（相对 stageIn）");
            assertEquals(stageIn + paperFinishes[id],
                    planner.plannedFinishOf(tasks[id]), EPSILON,
                    "n" + id + " 计划完成（相对 stageIn）");
        }
        double makespan = 0.0;
        for (int id = 1; id <= 10; id++) {
            makespan = Math.max(makespan, planner.plannedFinishOf(tasks[id]));
        }
        assertEquals(stageIn + 76.0, makespan, EPSILON,
                "论文 makespan 76（相对 stageIn；优于同算例 HEFT 的 80）");
    }

    /** 独立同权任务：优先级平局取较小任务 ID 先分配，第二任务让位另一 VM。 */
    @Test
    void independentTiedTasksFallBackToTaskIdThenVmId() {
        Task t1 = new Task(1, 4);
        Task t2 = new Task(2, 4);
        CondorVM first = vm(3, 1L, 1.0);
        CondorVM second = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2), first, second);
        planner.run();
        double stageIn = planner.stageInFinishTime();

        assertEquals(3, t1.getVmId(), "较小任务 ID 先分配并取较小 VM ID");
        assertEquals(8, t2.getVmId(), "第二个任务的更早 EFT 在空闲 VM 上");
        assertEquals(stageIn, t1.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t1), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t2), EPSILON);
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

    private static LocalPeftPlanningAlgorithm planner(List<Task> tasks, CondorVM... vms) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-peft-unit");
        for (int index = 0; index < vms.length; index++) {
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0,
                    2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(vms[index].getId(), vms[index].getMips(),
                    1, 512, vms[index].getBw(), 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        SimulationConfig config = SimulationConfig.builder("peft-unit.dax", vms.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalPeftPlanningAlgorithm planner = new LocalPeftPlanningAlgorithm(
                new PlanningContext(config, platform.build()));
        planner.setTaskList(new ArrayList<Task>(tasks));
        planner.setVmList(new ArrayList<CondorVM>(Arrays.asList(vms)));
        return planner;
    }

    private static CondorVM vm(int id, long bw, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, bw, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }
}
