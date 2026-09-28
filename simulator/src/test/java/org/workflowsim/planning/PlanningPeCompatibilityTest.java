package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.PlanningRegressionFixtures.EPSILON;
import static org.workflowsim.planning.PlanningRegressionFixtures.connect;
import static org.workflowsim.planning.PlanningRegressionFixtures.file;
import static org.workflowsim.planning.PlanningRegressionFixtures.localPlanner;
import static org.workflowsim.planning.PlanningRegressionFixtures.vms;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.SimulationRandom;

/** Direct-API PE feasibility: a faster but incompatible VM is never a valid mapping. */
class PlanningPeCompatibilityTest {

    @AfterEach
    void resetRandom() {
        SimulationRandom.reset(0L);
    }

    @Test
    void heftExcludesTheFasterIncompatibleVm() throws Exception {
        assertLocalCompatibility(PlanningAlgorithm.LOCAL_HEFT);
    }

    @Test
    void cpopExcludesTheFasterIncompatibleVm() throws Exception {
        assertLocalCompatibility(PlanningAlgorithm.LOCAL_CPOP);
    }

    @Test
    void peftExcludesTheFasterIncompatibleVm() throws Exception {
        assertLocalCompatibility(PlanningAlgorithm.LOCAL_PEFT);
    }

    @Test
    void localPlannersFailFastWhenNoCompatibleVmExists() {
        assertAll("LOCAL infeasible domain",
                () -> assertLocalRejection(PlanningAlgorithm.LOCAL_HEFT),
                () -> assertLocalRejection(PlanningAlgorithm.LOCAL_CPOP),
                () -> assertLocalRejection(PlanningAlgorithm.LOCAL_PEFT));
    }

    @Test
    void localRanksAndOctAverageOnlyCompatibleCandidateCosts() throws Exception {
        for (PlanningAlgorithm algorithm : Arrays.asList(PlanningAlgorithm.LOCAL_HEFT,
                PlanningAlgorithm.LOCAL_CPOP, PlanningAlgorithm.LOCAL_PEFT)) {
            Task root = new Task(1, 2L);
            Task child = new Task(2, 3L);
            child.setNumberOfPes(2);
            connect(root, child);
            root.addFile(file("f", 1000000.0, FileType.OUTPUT));
            child.addFile(file("f", 1000000.0, FileType.INPUT));
            AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm,
                    Arrays.asList(root, child), vms(2.0, 1, 1.0, 2));
            planner.run();
            assertEquals(8, child.getVmId(), algorithm.name());
            // Only VM8 can execute child: its compute mean is 3/1=3 seconds, not (1.5+3)/2.
            assertEquals(3.0, planner.meanComputeSeconds(child), EPSILON, algorithm.name());
            assertTrue(Double.isFinite(planner.upwardRankOf(root)), algorithm.name());
            assertTrue(Double.isFinite(planner.upwardRankOf(child)), algorithm.name());
            if (planner instanceof LocalPeftPlanningAlgorithm) {
                LocalPeftPlanningAlgorithm peft = (LocalPeftPlanningAlgorithm) planner;
                assertEquals(0.0, peft.priorityOf(child), EPSILON,
                        "exit OCT is zero; incompatible entries must not pollute the mean");
                assertTrue(Double.isFinite(peft.priorityOf(root)));
            }
            if (planner instanceof LocalCpopPlanningAlgorithm) {
                // The entire critical chain must fit its pinned processor, including its 2PE child.
                assertEquals(8, root.getVmId());
            }
        }
    }

    @Test
    void psoKeepsParticlesWithinTheCompatibleDomainAcrossIterations() {
        SimulationRandom.reset(7L);
        List<Task> tasks = Arrays.asList(new Task(1, 100L), new Task(2, 30L), new Task(3, 10L));
        for (Task task : tasks) {
            task.setNumberOfPes(2);
        }
        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(tasks);
        planner.setVmList(vms(2.0, 1, 1.0, 2));
        planner.run();
        for (Task task : tasks) {
            assertEquals(8, task.getVmId(), "the only feasible VM is8 for task " + task.getCloudletId());
        }
        // VM8 sequential load=140; price=.001; .8*.14+.2*140=28.112.
        assertEquals(28.112, planner.getLastBestFitness(), EPSILON);
        assertEquals(100, planner.getLastIterationCount());
    }

    @Test
    void psoRejectsAWorkflowWithoutACompatibleCandidate() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(3);
        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 1.0, 2));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, planner::run);
        assertTrue(failure.getMessage().contains("compatible"), failure.getMessage());
    }

    @Test
    void psoFitnessMarksAnIncompatibleAssignmentAsInfeasible() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        List<CondorVM> machines = vms(2.0, 1, 1.0, 2);
        assertEquals(Double.POSITIVE_INFINITY, PsoFitnessFunction.evaluate(new int[] {0},
                Collections.singletonList(task), machines, 0.8));
        assertEquals(20.08, PsoFitnessFunction.evaluate(new int[] {1},
                Collections.singletonList(task), machines, 0.8), EPSILON);
    }

    private static void assertLocalCompatibility(PlanningAlgorithm algorithm) throws Exception {
        Task task = new Task(71, 100L);
        task.setNumberOfPes(2);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm,
                Collections.singletonList(task), vms(2.0, 1, 1.0, 2));
        planner.run();
        assertEquals(8, task.getVmId());
        assertEquals(55.1, task.getStaticScheduleStartTime(), EPSILON);
        assertEquals(155.1, planner.plannedFinishOf(task).doubleValue(), EPSILON,
                "100 MI per PE /1 MIPS is100s, not200s");
    }

    private static void assertLocalRejection(PlanningAlgorithm algorithm) {
        Task task = new Task(71, 100L);
        task.setNumberOfPes(3);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm,
                Collections.singletonList(task), vms(2.0, 1, 1.0, 2));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, planner::run);
        assertTrue(failure.getMessage().contains("compatible"), failure.getMessage());
    }
}
