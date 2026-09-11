package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

class RandomPlanningAlgorithmTest {

    @AfterEach
    void resetParameters() {
        Parameters.reset();
    }

    @Test
    void sameSeedProducesTheSameMappingRegardlessOfVmInputOrder() throws Exception {
        Parameters.setRandomSeed(27L);
        List<Task> firstTasks = tasks();
        run(firstTasks, Arrays.asList(vm(9, 1), vm(2, 2), vm(5, 1)));

        Parameters.setRandomSeed(27L);
        List<Task> secondTasks = tasks();
        run(secondTasks, Arrays.asList(vm(5, 1), vm(9, 1), vm(2, 2)));

        assertEquals(vmIds(firstTasks), vmIds(secondTasks));
        assertEquals(2, firstTasks.get(1).getVmId());
    }

    @Test
    void neverMapsAHighPeTaskToAnIncompatibleVm() throws Exception {
        Parameters.setRandomSeed(5L);
        Task task = new Task(1, 1000);
        task.setNumberOfPes(2);

        run(Arrays.asList(task), Arrays.asList(vm(9, 1), vm(2, 2)));

        assertEquals(2, task.getVmId());
    }

    @Test
    void failsFastWhenNoCompatibleVmExists() {
        Task task = new Task(7, 1000);
        task.setNumberOfPes(2);
        RandomPlanningAlgorithm planner = new RandomPlanningAlgorithm();
        planner.setTaskList(Arrays.asList(task));
        planner.setVmList(Arrays.asList(vm(2, 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, planner::run);

        assertEquals("RANDOM cannot map task 7; no VM has sufficient processing elements",
                exception.getMessage());
    }

    private static List<Task> tasks() {
        Task first = new Task(1, 1000);
        Task second = new Task(2, 1000);
        second.setNumberOfPes(2);
        Task third = new Task(3, 1000);
        return Arrays.asList(first, second, third);
    }

    private static List<Integer> vmIds(List<Task> tasks) {
        return Arrays.asList(tasks.get(0).getVmId(), tasks.get(1).getVmId(), tasks.get(2).getVmId());
    }

    private static void run(List<Task> tasks, List<CondorVM> vms) throws Exception {
        RandomPlanningAlgorithm planner = new RandomPlanningAlgorithm();
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        planner.run();
    }

    private static CondorVM vm(int id, int pes) {
        return new CondorVM(id, 0, 1000.0, pes, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerTimeShared());
    }
}
