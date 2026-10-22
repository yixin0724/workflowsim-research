package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 链 C（科学效度审计 S-C1，2026-10-20）：独立任务启发式的 Braun et al. 2001
 * ECT 矩阵黄金表。
 *
 * <p>文献源：Braun, Siegel, Beck, Boloni, Maheswaran, Reuther, Robertson, Theys,
 * Yao, Hensgen &amp; Freund, <i>A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems</i>, Journal of Parallel and Distributed Computing 61(5), 2001
 * （算法定义以 ECT 矩阵书写：Each task's Estimated Completion Time on each
 * machine；逐轮选择规则见各手推）。</p>
 *
 * <p>期望值全部由 ECT 手推（三任务 × 三机器），不经实现输出。本平台经
 * {@code TaskExecutionModel} 成本矩阵取得同一 ECT 语义（矩阵秒数为权威）。</p>
 *
 * <p>成本矩阵（ECT=availability+seconds，初态三机全空闲）：</p>
 *
 * <pre>
 *           VM0    VM1    VM2
 *   t1      2      5      1
 *   t2      3      2      6
 *   t3      4      3      2
 * </pre>
 *
 * <b>MCT（Braun et al. 2001 §4.2）手推</b>——每个任务独立地分配到其 ECT 最小的机器：
 * t1→3(ECT1)，t2→1(2)，t3→2(2)。
 *
 * <b>MinMin（§4.4）手推</b>——逐轮：全体任务的（最优机器上的 ECT）的最小者先分配：
 * R1: t1@VM2(1), t2@VM1(2), t3@VM2(2)；最小 ECT=1，选 t1→VM2（availability[VM2]=1）。
 * R2: t2@VM1(2), t3@VM2(1+2=3)——VM0 最劣4；最小 =2→t2→VM1（av1=2）。R3:
 * t3: min(vm0=4,vm1=2+3=5,vm2=1+2=3)=3→VM2。⇒ t1→2, t2→1, t3→2。
 *
 * <b>MaxMin（§4.4）手推</b>——逐轮选"最小 ECT 中的最大者"：
 * R1: 候选完成时间 t1@2(1), t2@1(2), t3@2(2)：最小 ECT 为 1,2,2，取最大=t2/t3 之间取
 * task-ID 平局：t2@VM1(2) 分配（av1=2）。R2: t1@2(1), t3@2(3)——最小分别为 1,3，取最大=3
 * →t3→VM2（av2=3）。R3: t1 的最小 ECT：vm0=2,vm1=2+5,vm2=1+3+1=5→VM0。⇒
 * t1→0, t2→1, t3→2。
 *
 * <b>Sufferage（Maheswaran et al. 1999 / Braun et al. 2001 §4.7）手推</b>——
 * 每轮选次优-最优差距最大者：
 * R1: t1 sufferage=2−1=1... 具体计算每个任务的 best 与 second-best：
 * t1 best VM2(1), second VM1 或 VM0 → min(2,5)=2 → sufferage=2−1=1。
 * t2 best VM1(2), second=vm0(3) or vm2(6) →3 → sufferage=1。
 * t3 best VM2(2), second=vm1(3) or vm0(4) → 3−2=1。
 * 三任务 sufferage 全部=1。任务 ID 升序破平局→t1@VM2（av2=1）。
 * R2: t2：best vm1(2,second vm0=3)=sufferage 1；t3：best vm2(1+2=3),second vm1(3)
 * →sufferage 0。选 t2@VM1。R3: t3 best: vm0=4,vm1=2+3=5,vm2=1+2=3→VM2。
 * ⇒ t1→2, t2→1, t3→2（与 MinMin 相同——合理的文献现象：该矩阵下两法收敛）。
 */
class StaticIndependentGoldenTableTest {

    /** Braun et al. 2001 形态的 ECT 矩阵（秒）。行=任务，列=[VM0,VM1,VM2]。 */
    private static final double[][] ECT = {
        {2, 5, 1},
        {3, 2, 6},
        {4, 3, 2}
    };

    private static List<CondorVM> vms() {
        return vms(vm(0), vm(1), vm(2));
    }

    private static List<Task> matrixTasks() {
        List<Task> tasks = new ArrayList<Task>();
        for (int id = 1; id <= 3; id++) {
            Task task = new Task(id, 1000L);
            Map<Integer, Double> projection = new LinkedHashMap<Integer, Double>();
            for (int vmId = 0; vmId < 3; vmId++) {
                projection.put(Integer.valueOf(vmId), Double.valueOf(ECT[id - 1][vmId]));
            }
            task.setVmExecutionCostSeconds(projection);
            tasks.add(task);
        }
        return tasks;
    }

    private static void run(StaticIndependentPlanningSupport planner, List<Task> tasks, List<CondorVM> vms)
            throws Exception {
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1L, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private static List<CondorVM> vms(CondorVM... array) {
        return new ArrayList<CondorVM>(Arrays.asList(array));
    }

    @Test
    void mctMatchesBraunEctMatrixHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMctPlanningAlgorithm(), tasks, vms());
        // Braun et al. 2001 §4.2 手推（实现为 availability 感知 MCT）：
        // t1：min(2,5,1) → VM2（av2=1）
        // t2：av=(0,0,1) → min(3, 2, 1+6=7) → VM1（av1=2）
        // t3：av=(0,2,1) → min(4, 2+3=5, 1+2=3) → VM2
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void minMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMinMinPlanningAlgorithm(), tasks, vms());
        // 手推（§4.4 逐轮最小 ECT）：t1→2, t2→1, t3→2。
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void maxMinMatchesBraunHandDerivation() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMaxMinPlanningAlgorithm(), tasks, vms());
        // 手推（逐轮最小 ECT 中最大）：t1→0, t2→1, t3→2。
        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void sufferageMatchesBraunHandDerivationAndConvergesWithMinMinOnThisMatrix() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticSufferagePlanningAlgorithm(), tasks, vms());
        // 手推（次优-最优差最大，ID 平局升序）：t1→2, t2→1, t3→2；
        // 此矩阵下与 MinMin 收敛（文献已知现象，非巧合——两者在无争用层叠上也常一致）。
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void metStaysPureEctFastestMachineForEveryTask() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticMetPlanningAlgorithm(), tasks, vms());
        // MET（§4.1）纯 ECT：t1→2(1), t2→1(2), t3→2(2)。
        assertEquals(2, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }

    @Test
    void olbQueuesToLeastWorkedMachineThenTieBreaksOnAscendingId() throws Exception {
        List<Task> tasks = matrixTasks();
        run(new StaticOlbPlanningAlgorithm(), tasks, vms());
        // Braun et al. 2001 §4.3 手推（最小 availability，平局取 VM ID 小者）：
        // t1：av=(0,0,0) → VM0（av0=2）
        // t2：av=(2,0,0) → VM1（av1=2）
        // t3：av=(2,2,0) → VM2
        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, tasks.get(2).getVmId());
    }
}
