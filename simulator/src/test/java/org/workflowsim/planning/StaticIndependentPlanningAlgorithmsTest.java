package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

class StaticIndependentPlanningAlgorithmsTest {

    @Test
    void metUsesTheFastestCompatibleVmForEveryTask() throws Exception {
        Task first = task(1, 1000);
        Task second = task(2, 2000);

        run(new StaticMetPlanningAlgorithm(), Arrays.asList(first, second), vms(vm(7, 500), vm(3, 1000)));

        assertEquals(3, first.getVmId());
        assertEquals(3, second.getVmId());
    }

    @Test
    void mctAccountsForPredictedAvailability() throws Exception {
        Task first = task(1, 3000);
        Task second = task(2, 1000);

        run(new StaticMctPlanningAlgorithm(), Arrays.asList(first, second), vms(vm(0, 1000), vm(1, 500)));

        assertEquals(0, first.getVmId());
        assertEquals(1, second.getVmId());
    }

    @Test
    void olbChoosesTheFirstLeastLoadedCompatibleVm() throws Exception {
        Task first = task(1, 1000);
        Task second = task(2, 1000);

        run(new StaticOlbPlanningAlgorithm(), Arrays.asList(first, second), vms(vm(0, 1000), vm(1, 500)));

        assertEquals(0, first.getVmId());
        assertEquals(1, second.getVmId());
    }

    @Test
    void roundRobinUsesAscendingVmIdsRegardlessOfInputOrder() throws Exception {
        Task first = task(1, 1000);
        Task second = task(2, 1000);
        Task third = task(3, 1000);

        run(new StaticRoundRobinPlanningAlgorithm(), Arrays.asList(first, second, third),
                vms(vm(5, 1000), vm(2, 1000)));

        assertEquals(2, first.getVmId());
        assertEquals(5, second.getVmId());
        assertEquals(2, third.getVmId());
    }

    @Test
    void minMinAndMaxMinHaveTheirClassicalOppositeSelectionPolicies() throws Exception {
        Task minShort = task(1, 1);
        Task minLong = task(2, 10);
        run(new StaticMinMinPlanningAlgorithm(), Arrays.asList(minShort, minLong), vms(vm(0, 10), vm(1, 1)));
        assertEquals(0, minShort.getVmId());
        assertEquals(0, minLong.getVmId());

        Task maxShort = task(1, 1);
        Task maxLong = task(2, 10);
        run(new StaticMaxMinPlanningAlgorithm(), Arrays.asList(maxShort, maxLong), vms(vm(0, 10), vm(1, 1)));
        assertEquals(1, maxShort.getVmId());
        assertEquals(0, maxLong.getVmId());
    }

    @Test
    void sufferageSelectsTheTaskWithTheLargestAlternativePenalty() throws Exception {
        Task highPenalty = task(1, 20);
        Task lowPenalty = task(2, 3);

        run(new StaticSufferagePlanningAlgorithm(), Arrays.asList(highPenalty, lowPenalty),
                vms(vm(0, 10), vm(1, 2)));

        assertEquals(0, highPenalty.getVmId());
        assertEquals(1, lowPenalty.getVmId());
    }

    @Test
    void independentTaskAlgorithmsFailFastForDagInputs() {
        Task parent = task(1, 1000);
        Task child = task(2, 1000);
        parent.addChild(child);
        child.addParent(parent);
        StaticMinMinPlanningAlgorithm planner = new StaticMinMinPlanningAlgorithm();
        planner.setTaskList(Arrays.asList(parent, child));
        planner.setVmList(vms(vm(0, 1000)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, planner::run);

        assertEquals("STATIC_MINMIN supports independent tasks only; use a DAG planner for workflows with dependencies",
                exception.getMessage());
    }

    @Test
    void everyIndependentPlannerRespectsProcessingElementCompatibility() throws Exception {
        assertMapsTwoPeTaskToCompatibleVm(new StaticMetPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticMctPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticOlbPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticRoundRobinPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticMinMinPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticMaxMinPlanningAlgorithm());
        assertMapsTwoPeTaskToCompatibleVm(new StaticSufferagePlanningAlgorithm());
    }

    @Test
    void everyIndependentPlannerRejectsTasksWithoutACompatibleVm() {
        assertRejectsNoCompatibleVm(new StaticMetPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticMctPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticOlbPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticRoundRobinPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticMinMinPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticMaxMinPlanningAlgorithm());
        assertRejectsNoCompatibleVm(new StaticSufferagePlanningAlgorithm());
    }

    @Test
    void minMinAndMaxMinUseTaskAndVmIdsToBreakEqualCompletionTies() throws Exception {
        Task minHighId = task(9, 1000);
        Task minLowId = task(2, 1000);
        run(new StaticMinMinPlanningAlgorithm(), Arrays.asList(minHighId, minLowId),
                vms(vm(8, 1000), vm(3, 1000)));
        assertEquals(3, minLowId.getVmId());
        assertEquals(8, minHighId.getVmId());

        Task maxHighId = task(9, 1000);
        Task maxLowId = task(2, 1000);
        run(new StaticMaxMinPlanningAlgorithm(), Arrays.asList(maxHighId, maxLowId),
                vms(vm(8, 1000), vm(3, 1000)));
        assertEquals(3, maxLowId.getVmId());
        assertEquals(8, maxHighId.getVmId());
    }

    private static void assertMapsTwoPeTaskToCompatibleVm(BasePlanningAlgorithm algorithm)
            throws Exception {
        Task twoPeTask = task(7, 1000);
        twoPeTask.setNumberOfPes(2);

        run(algorithm, Arrays.asList(twoPeTask), vms(vm(0, 2000, 1), vm(4, 500, 2)));

        assertEquals(4, twoPeTask.getVmId());
    }

    private static void assertRejectsNoCompatibleVm(BasePlanningAlgorithm algorithm) {
        Task twoPeTask = task(7, 1000);
        twoPeTask.setNumberOfPes(2);
        algorithm.setTaskList(Arrays.asList(twoPeTask));
        algorithm.setVmList(vms(vm(0, 2000, 1)));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, algorithm::run);

        assertTrue(exception.getMessage().contains("no VM has sufficient processing elements"));
    }

    private static void run(BasePlanningAlgorithm algorithm, List<Task> tasks, List<CondorVM> vms)
            throws Exception {
        algorithm.setTaskList(tasks);
        algorithm.setVmList(vms);
        algorithm.run();
    }

    private static Task task(int id, long length) {
        return new Task(id, length);
    }

    private static List<CondorVM> vms(CondorVM... vms) {
        return Arrays.asList(vms);
    }

    private static CondorVM vm(int id, double mips) {
        return vm(id, mips, 1);
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1000, 10000, "Xen",
                new CloudletSchedulerTimeShared());
    }
}
