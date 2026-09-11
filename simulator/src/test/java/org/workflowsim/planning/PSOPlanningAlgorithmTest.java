package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;

/**
 * PSO 规划器回归测试（论文复现轮）。
 *
 * <p>适应度期望值全部手算自参考实现（meysamhit/workflowsim-pso）的公式：
 * price = mips/1000；VM 负载 = 顺序执行时间之和；
 * fitness = 0.8·totalCost + 0.2·makespan。</p>
 */
class PSOPlanningAlgorithmTest {

    /** 期望容差。 */
    private static final double EPS = 1e-9;

    @Test
    void fitnessMatchesHandComputedValuesOnTwoByTwoLandscape() {
        List<Task> tasks = Arrays.asList(new Task(1, 1000), new Task(2, 2000));
        List<CondorVM> vms = Arrays.asList(vm(0, 1000.0), vm(1, 2000.0));

        // [0,0]: cost=1+2=3, makespan=3 → 0.8*3+0.2*3
        assertEquals(3.0, PsoFitnessFunction.evaluate(new int[]{0, 0}, tasks, vms, 0.8), EPS);
        // [1,1]: exec 0.5/1.0, cost=0.5*2+1.0*2=3, makespan=1.5
        assertEquals(0.8 * 3.0 + 0.2 * 1.5,
                PsoFitnessFunction.evaluate(new int[]{1, 1}, tasks, vms, 0.8), EPS);
        // [0,1]: cost=1.0*1+1.0*2=3, makespan=1.0（全局最优）
        assertEquals(0.8 * 3.0 + 0.2 * 1.0,
                PsoFitnessFunction.evaluate(new int[]{0, 1}, tasks, vms, 0.8), EPS);
        // [1,0]: cost=0.5*2+2.0*1=3, makespan=2.0
        assertEquals(0.8 * 3.0 + 0.2 * 2.0,
                PsoFitnessFunction.evaluate(new int[]{1, 0}, tasks, vms, 0.8), EPS);
    }

    @Test
    void convergesToGlobalOptimumOnTrivialLandscape() {
        SimulationRandom.reset(7L);
        List<Task> tasks = Arrays.asList(new Task(1, 1000), new Task(2, 2000));
        List<CondorVM> vms = Arrays.asList(vm(0, 1000.0), vm(1, 2000.0));

        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();

        assertEquals(PSOPlanningAlgorithm.MAX_ITERATIONS, planner.getLastIterationCount());
        assertEquals(0.8 * 3.0 + 0.2 * 1.0, planner.getLastBestFitness(), EPS);
        // 唯一全局最优：task1→vm0，task2→vm1
        assertEquals(0, tasks.get(0).getVmId());
        assertEquals(1, tasks.get(1).getVmId());
        assertEquals(2, planner.getLastMapping().size());
    }

    @Test
    void sameSeedProducesSameMappingRegardlessOfVmInputOrder() {
        SimulationRandom.reset(42L);
        List<Task> firstTasks = tasks();
        run(firstTasks, Arrays.asList(vm(9, 500.0), vm(2, 1000.0), vm(5, 1500.0)));

        SimulationRandom.reset(42L);
        List<Task> secondTasks = tasks();
        run(secondTasks, Arrays.asList(vm(5, 1500.0), vm(9, 500.0), vm(2, 1000.0)));

        assertEquals(vmAssignments(firstTasks), vmAssignments(secondTasks));
    }

    @Test
    void allTasksMappedToExistingVmIds() {
        SimulationRandom.reset(3L);
        List<Task> tasks = tasks();
        List<CondorVM> vms = Arrays.asList(vm(0, 800.0), vm(1, 1200.0), vm(2, 1000.0));
        run(tasks, vms);

        for (Task task : tasks) {
            assertTrue(task.getVmId() == 0 || task.getVmId() == 1 || task.getVmId() == 2,
                    "task " + task.getCloudletId() + " mapped to unknown VM " + task.getVmId());
        }
    }

    @Test
    void emptyInputIsNoOp() {
        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(java.util.Collections.<Task>emptyList());
        planner.setVmList(Arrays.asList(vm(0, 1000.0)));
        planner.run();

        assertTrue(Double.isNaN(planner.getLastBestFitness()));
        assertEquals(0, planner.getLastIterationCount());
        assertTrue(planner.getLastMapping().isEmpty());
    }

    private static List<Task> tasks() {
        return Arrays.asList(new Task(1, 1500), new Task(2, 800), new Task(3, 2200),
                new Task(4, 600), new Task(5, 1800));
    }

    private static List<Integer> vmAssignments(List<Task> tasks) {
        return Arrays.asList(tasks.get(0).getVmId(), tasks.get(1).getVmId(),
                tasks.get(2).getVmId(), tasks.get(3).getVmId(), tasks.get(4).getVmId());
    }

    private static void run(List<Task> tasks, List<CondorVM> vms) {
        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();
    }

    private static CondorVM vm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerTimeShared());
    }
}
