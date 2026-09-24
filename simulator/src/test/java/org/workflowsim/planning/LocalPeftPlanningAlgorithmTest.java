package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
     * 出口 OCT ≡ 0；同 VM 放置的零通信项使 min_p' 命中 p'=p；重边只抬高被
     * 淘汰的候选，不进入最终值。
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

        assertEquals(0.0, planner.optimisticCostOf(t3, fast), EPSILON, "出口 OCT ≡ 0");
        assertEquals(0.0, planner.optimisticCostOf(t3, slow), EPSILON);
        // OCT(t2, p) = w(t2, p) + min_p'(0 + c)；p'=p 时 c=0。
        assertEquals(1.0, planner.optimisticCostOf(t2, fast), EPSILON);
        assertEquals(2.0, planner.optimisticCostOf(t2, slow), EPSILON);
        // OCT(t1, fast) = 2 + min(1+0, 2+10) = 3；OCT(t1, slow) = 4 + min(1+10, 2+0) = 6。
        assertEquals(3.0, planner.optimisticCostOf(t1, fast), EPSILON);
        assertEquals(6.0, planner.optimisticCostOf(t1, slow), EPSILON);
        assertEquals(4.5, planner.priorityOf(t1), EPSILON, "rank_o = OCT 全 VM 平均");
        assertEquals(1.5, planner.priorityOf(t2), EPSILON);
        assertEquals(0.0, planner.priorityOf(t3), EPSILON);
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
     * 菱形 t1→{t2a,t2b}→t3：t2b 的 OCT 惩罚（1-0.5）不足以抵消其更早的 EFT
     * （慢 VM 空闲），EFT+OCT 联合目标仍选慢 VM；t3 汇聚后回到快 VM。
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

        assertEquals(3.5, planner.priorityOf(t1), EPSILON);
        assertEquals(3.0, planner.priorityOf(t2a), EPSILON);
        assertEquals(0.75, planner.priorityOf(t2b), EPSILON);
        assertEquals(0.0, planner.priorityOf(t3), EPSILON);

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

    /** 单 VM 平台：min_p' 退化为单点，调度仍成立。 */
    @Test
    void singleVmDegenerateCaseStillSchedules() {
        Task t1 = new Task(1, 3);
        Task t2 = new Task(2, 5);
        connect(t1, t2, 2.0);
        CondorVM only = vm(3, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2), only);
        planner.run();
        double stageIn = planner.stageInFinishTime();

        assertEquals(3.0, planner.optimisticCostOf(t1, only), EPSILON, "同 VM 零通信");
        assertEquals(0.0, planner.optimisticCostOf(t2, only), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(3, t2.getVmId());
        assertEquals(stageIn + 3.0, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 8.0, planner.plannedFinishOf(t2), EPSILON);
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
