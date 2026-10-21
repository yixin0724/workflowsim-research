package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * C04：LOCAL_HEFT 的论文算例锚与分配不变量（审查工程 Phase 1 文献黄金表审计）。
 *
 * <p>数据源：Topcuoglu, Hariri &amp; Wu（IEEE TPDS 2002）十任务、三处理器算例的
 * 成本矩阵与边权——与 {@code LocalCpopPlanningAlgorithmTest} 的论文表同源；期望
 * rank 值为矩阵与边权的独立手算（HEFT 向上 rank：w̄ + max(c̄ + rank_u(child))），
 * 不在测试中复写递推算法本身。</p>
 *
 * <p>分配断言分层（军规：契约与守恒律，非实现镜像）：
 * ①论文表 rank 逐任务精确锚定；②首任务（最高 rank 的入口任务）的 VM 选择与
 * 完成时刻可平凡手算（EST=0、三 VM 成本 14/16/9 → 最小 EFT=vm2@9）；③全部任务
 * 的规划区间满足两条守恒律——<b>同一 VM 上的预留区间互不重叠</b>、
 * <b>跨 VM 依赖的起始不早于 父完成＋边通信</b>（同 VM 依赖不早于父完成）。</p>
 */
class LocalHeftPlanningAlgorithmPaperTableTest {

    private static final double EPSILON = 1.0e-9;
    /** 根 stage-in Job（110 MI）在 1 MIPS VM 上折算 110 秒 + 最小事件间隔 0.1。 */
    private static final double BOOTSTRAP = 110.1;

    /** 论文成本矩阵：costs[task][vm]，单位秒（成本投影 × 1 MIPS VM）。 */
    private static final double[][] COSTS = {
        {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
        {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
    };

    /** 论文边权：{父(1 起), 子(1 起), 平均通信秒}。 */
    private static final int[][] EDGES = {
        {1, 2, 18}, {1, 3, 12}, {1, 4, 9}, {1, 5, 11}, {1, 6, 14},
        {2, 8, 19}, {2, 9, 16}, {3, 7, 23}, {4, 8, 27}, {4, 9, 23},
        {5, 9, 13}, {6, 8, 15}, {7, 10, 17}, {8, 10, 11}, {9, 10, 13}
    };

    @Test
    void paperTableUpwardRanksMatchIndependentHandDerivation() {
        Fixture fixture = paperFixture();

        fixture.planner.run();

        // HEFT 向上 rank 的独立手算（与 CPOP 测试共用同一论文表，公式独立推导）。
        double[] upward = {108, 77, 80, 80, 69, 190.0 / 3.0, 128.0 / 3.0, 107.0 / 3.0, 133.0 / 3.0, 44.0 / 3.0};
        for (int i = 0; i < fixture.tasks.length; i++) {
            assertEquals(
                    upward[i],
                    fixture.planner.upwardRankOf(fixture.tasks[i]),
                    EPSILON,
                    "论文表任务 " + (i + 1) + " 的向上 rank 必须等于手推值");
        }
    }

    @Test
    void highestRankEntryTaskTakesTriviallyDerivedEarliestFinishProcessor() {
        Fixture fixture = paperFixture();
        Task entry = fixture.tasks[0];

        fixture.planner.run();

        // 入口任务 EST=0；三 VM 成本 14/16/9 → 最小 EFT 在 vm2，finish=9（平凡手算）。
        assertEquals(2, entry.getVmId(), "入口任务应选择最小 EFT 的 vm2（成本 9 秒）");
        assertEquals(BOOTSTRAP, entry.getStaticScheduleStartTime(), EPSILON, "入口任务的计划开始应为根 stage-in 完成时刻");
        assertEquals(BOOTSTRAP + 9.0, fixture.planner.plannedFinishOf(entry), EPSILON, "入口任务在 vm2 上的计划完成应为 9 秒");
    }

    @Test
    void plannedIntervalsNeverOverlapPerProcessorAndHonourCommunication() {
        Fixture fixture = paperFixture();

        fixture.planner.run();

        // ① 同 VM 区间互不重叠（按开始排序，前一区间完成不得晚于后一区间开始）。
        Map<Integer, List<double[]>> perVm = new LinkedHashMap<Integer, List<double[]>>();
        for (Task task : fixture.tasks) {
            double start = task.getStaticScheduleStartTime();
            double finish = fixture.planner.plannedFinishOf(task);
            assertTrue(finish > start, "任务 " + task.getCloudletId() + " 规划区间非空");
            List<double[]> intervals = perVm.get(task.getVmId());
            if (intervals == null) {
                intervals = new ArrayList<double[]>();
                perVm.put(task.getVmId(), intervals);
            }
            intervals.add(new double[] {start, finish});
        }
        for (Map.Entry<Integer, List<double[]>> entry : perVm.entrySet()) {
            List<double[]> intervals = entry.getValue();
            Collections.sort(intervals, (a, b) -> Double.compare(a[0], b[0]));
            for (int i = 1; i < intervals.size(); i++) {
                assertTrue(
                        intervals.get(i)[0] >= intervals.get(i - 1)[1] - EPSILON,
                        "vm" + entry.getKey() + " 上预留区间重叠: "
                                + Arrays.toString(intervals.get(i - 1)) + " 与 "
                                + Arrays.toString(intervals.get(i)));
            }
        }
        // ② 依赖就绪：start(t) ≥ finish(p) +（跨 VM 时边通信秒）。
        for (int[] edge : EDGES) {
            Task parent = fixture.tasks[edge[0] - 1];
            Task child = fixture.tasks[edge[1] - 1];
            double childStart = child.getStaticScheduleStartTime();
            double parentFinish = fixture.planner.plannedFinishOf(parent);
            if (parent.getVmId() == child.getVmId()) {
                assertTrue(childStart >= parentFinish - EPSILON, "同 VM 依赖 " + edge[0] + "→" + edge[1] + " 的子任务过早开始");
            } else {
                assertTrue(
                        childStart >= parentFinish + edge[2] - EPSILON,
                        "跨 VM 依赖 " + edge[0] + "→" + edge[1] + " 的子任务开始早于父完成+通信");
            }
        }
    }

    // ---- 夹具 ----

    private static final class Fixture {
        final LocalHeftPlanningAlgorithm planner;
        final Task[] tasks;

        Fixture(LocalHeftPlanningAlgorithm planner, Task[] tasks) {
            this.planner = planner;
            this.tasks = tasks;
        }
    }

    private static Fixture paperFixture() {
        Task[] tasks = new Task[COSTS.length];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = new Task(i + 1, 1);
            Map<Integer, Double> projection = new LinkedHashMap<Integer, Double>();
            for (int vmId = 0; vmId < 3; vmId++) {
                projection.put(Integer.valueOf(vmId), Double.valueOf(COSTS[i][vmId]));
            }
            tasks[i].setVmExecutionCostSeconds(projection);
        }
        for (int[] edge : EDGES) {
            connect(tasks[edge[0] - 1], tasks[edge[1] - 1], edge[2]);
        }
        // 故意逆序登记：HEFT 的调度序必须由 rank 递推恢复，与输入顺序无关。
        List<Task> reversed = new ArrayList<Task>(Arrays.asList(tasks));
        Collections.reverse(reversed);
        return new Fixture(planner(reversed, 2, 0, 1), tasks);
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

    private static LocalHeftPlanningAlgorithm planner(List<Task> tasks, int... vmIds) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-heft-paper");
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (int index = 0; index < vmIds.length; index++) {
            int vmId = vmIds[index];
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0, 2048, 10_000L, 1_000_000L));
            platform.addVm(new PlatformProfile.VmSpec(
                    vmId, 1.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            vms.add(vm(vmId));
        }
        SimulationConfig config = SimulationConfig.builder("heft-paper.dax", vmIds.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        LocalHeftPlanningAlgorithm planner =
                new LocalHeftPlanningAlgorithm(new PlanningContext(config, platform.build()));
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        return planner;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1.0, 1, 512, 1L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
