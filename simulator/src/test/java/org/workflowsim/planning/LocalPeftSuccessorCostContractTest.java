package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.PlanningRegressionFixtures.connect;
import static org.workflowsim.planning.PlanningRegressionFixtures.file;
import static org.workflowsim.planning.PlanningRegressionFixtures.localPlanner;
import static org.workflowsim.planning.PlanningRegressionFixtures.projectedTasks;
import static org.workflowsim.planning.PlanningRegressionFixtures.vms;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

/** Independent arithmetic from PEFT Eq. (7): successor compute cost, exit zero. */
class LocalPeftSuccessorCostContractTest {
    @Test
    void exitIsZeroAndTheTableContainsSuccessorRatherThanCurrentCompute() throws Exception {
        List<Task> tasks = projectedTasks(new double[][] {{10.0, 1.0}, {1.0, 100.0}});
        connect(tasks.get(0), tasks.get(1));
        tasks.get(0).addFile(file("edge", 10_000_000.0, FileType.OUTPUT));
        tasks.get(1).addFile(file("edge", 10_000_000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        LocalPeftPlanningAlgorithm planner = planner(tasks, machines);
        planner.run();
        assertEquals(0.0, planner.optimisticCostOf(tasks.get(1), machines.get(0)), 0.0);
        assertEquals(0.0, planner.optimisticCostOf(tasks.get(1), machines.get(1)), 0.0);
        // root@VM3 -> min(1, 100+10)=1; root@VM8 -> min(1+10,100)=11.
        assertEquals(1.0, planner.optimisticCostOf(tasks.get(0), machines.get(0)), 0.0);
        assertEquals(11.0, planner.optimisticCostOf(tasks.get(0), machines.get(1)), 0.0);
        // OEFT candidates are (10+1)=11 and (1+11)=12, so do not pick fastest-current VM8.
        assertEquals(3, tasks.get(0).getVmId());
        assertEquals(3, tasks.get(1).getVmId());
        assertEquals(121.1, planner.plannedFinishOf(tasks.get(1)), 1e-9);
    }

    @Test
    void dependencyReadySelectionStillMattersWithTheCorrectSuccessorRecurrence() throws Exception {
        List<Task> tasks = projectedTasks(new double[][] {{1.0, 2.0}, {1.0, 1.0}, {1.0, 1000.0}});
        connect(tasks.get(0), tasks.get(1));
        connect(tasks.get(1), tasks.get(2));
        tasks.get(1).addFile(file("edge", 100_000_000.0, FileType.OUTPUT));
        tasks.get(2).addFile(file("edge", 100_000_000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        LocalPeftPlanningAlgorithm planner = planner(tasks, machines);
        planner.run();
        // exit=[0,0]; middle=[min(1,1100),min(101,1000)]=[1,101].
        // The zero-communication root edge can pick middle@VM3: root=[2,2].
        assertEquals(2.0, planner.priorityOf(tasks.get(0)), 0.0);
        assertEquals(51.0, planner.priorityOf(tasks.get(1)), 0.0);
        assertTrue(planner.priorityOf(tasks.get(0)) < planner.priorityOf(tasks.get(1)));
        for (int i = 0; i < 3; i++) {
            assertEquals(3, tasks.get(i).getVmId());
            assertEquals(110.1 + i, tasks.get(i).getStaticScheduleStartTime(), 1e-9);
        }
        assertEquals(113.1, planner.plannedFinishOf(tasks.get(2)), 1e-9);
    }

    @Test
    void branchingOctIsMaximumOfSuccessorMinimaNotTheirSumOrCurrentWork() throws Exception {
        List<Task> tasks = projectedTasks(new double[][] {{10.0, 20.0}, {3.0, 8.0}, {5.0, 2.0}});
        connect(tasks.get(0), tasks.get(1));
        connect(tasks.get(0), tasks.get(2));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        LocalPeftPlanningAlgorithm planner = planner(tasks, machines);
        planner.run();
        // Control edges cost zero: max(min(3,8), min(5,2))=3 on each processor.
        for (CondorVM machine : machines) {
            assertEquals(3.0, planner.optimisticCostOf(tasks.get(0), machine), 0.0);
            assertEquals(0.0, planner.optimisticCostOf(tasks.get(1), machine), 0.0);
            assertEquals(0.0, planner.optimisticCostOf(tasks.get(2), machine), 0.0);
        }
    }

    private static LocalPeftPlanningAlgorithm planner(List<Task> tasks, List<CondorVM> machines) {
        return (LocalPeftPlanningAlgorithm) localPlanner(PlanningAlgorithm.LOCAL_PEFT, tasks, machines);
    }
}
