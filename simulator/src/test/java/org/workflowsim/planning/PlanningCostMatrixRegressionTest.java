package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.PlanningRegressionFixtures.EPSILON;
import static org.workflowsim.planning.PlanningRegressionFixtures.computeJob;
import static org.workflowsim.planning.PlanningRegressionFixtures.config;
import static org.workflowsim.planning.PlanningRegressionFixtures.platform;
import static org.workflowsim.planning.PlanningRegressionFixtures.projectedTask;
import static org.workflowsim.planning.PlanningRegressionFixtures.projectedTasks;
import static org.workflowsim.planning.PlanningRegressionFixtures.vms;
import static org.workflowsim.planning.PlanningRegressionFixtures.workflow;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.SimulationRandom;

/** ALG-01: independent arithmetic oracles, deliberately opposed to raw-MI ordering. */
class PlanningCostMatrixRegressionTest {

    @TempDir
    Path directory;

    @AfterEach
    void restoreState() {
        Log.enable();
        SimulationRandom.reset(0L);
    }

    @Test
    void timeAwareIndependentPlannersUseTheMatrixInsteadOfRawLength() {
        // Raw MI=100 prefers VM3 (2 MIPS); matrix [100,1] seconds prefers VM8.
        assertAll("matrix-aware independent mappings",
                () -> assertMatrixChoice(new StaticMetPlanningAlgorithm()),
                () -> assertMatrixChoice(new StaticMctPlanningAlgorithm()),
                () -> assertMatrixChoice(new StaticMinMinPlanningAlgorithm()),
                () -> assertMatrixChoice(new StaticMaxMinPlanningAlgorithm()),
                () -> assertMatrixChoice(new StaticSufferagePlanningAlgorithm()));
    }

    @Test
    void olbUpdatesAvailabilityUsingMatrixDurations() {
        double[][] costs = {{100.0, 1.0}, {1.0, 1.0}, {100.0, 1.0}};
        List<Task> tasks = projectedTasks(costs);
        StaticOlbPlanningAlgorithm planner = new StaticOlbPlanningAlgorithm();
        planner.setTaskList(tasks);
        planner.setVmList(vms(1.0, 1, 1.0, 1));
        planner.run();

        // First ID tie -> VM3, next task -> idle VM8; availability is then [100,1].
        assertEquals(3, tasks.get(0).getVmId());
        assertEquals(8, tasks.get(1).getVmId());
        assertEquals(8, tasks.get(2).getVmId(), "100 > 1: the third task must use VM8");
    }

    @Test
    void roundRobinIntentionallyRemainsCostOblivious() {
        Task task = projectedTask(1, 100L, 100.0, 1.0);
        StaticRoundRobinPlanningAlgorithm planner = new StaticRoundRobinPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 1.0, 1));
        planner.run();
        assertEquals(3, task.getVmId(), "round robin chooses the first compatible ID, not MET");
    }

    @Test
    void psoFitnessUsesTheMatrixForItsDeclaredCostAndLoadObjective() {
        List<Task> tasks = Collections.singletonList(projectedTask(1, 100L, 100.0, 1.0));
        List<CondorVM> machines = vms(2.0, 1, 1.0, 1);
        // VM3: .8 * (100 seconds * .002 price) + .2 * 100 seconds = 20.16.
        // VM8: .8 * (1 second * .001 price) + .2 * 1 second = .2008.
        assertEquals(20.16, PsoFitnessFunction.evaluate(new int[] {0}, tasks, machines, 0.8), EPSILON);
        assertEquals(0.2008, PsoFitnessFunction.evaluate(new int[] {1}, tasks, machines, 0.8), EPSILON);
    }

    @Test
    void psoOptimizesTheMatrixLandscapeRatherThanTheRawMiLandscape() {
        SimulationRandom.reset(7L);
        Task task = projectedTask(1, 100L, 100.0, 1.0);
        PSOPlanningAlgorithm planner = new PSOPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 1.0, 1));
        planner.run();
        assertEquals(8, task.getVmId());
        assertEquals(0.2008, planner.getLastBestFitness(), EPSILON);
        assertEquals(100L, task.getCloudletLength(), "planning must preserve source MI");
    }

    @Test
    void matrixRoundingUsesEffectiveMiBeforeComparingCandidates() {
        Task task = projectedTask(1, 100L, 1.01, 1.02);
        StaticMetPlanningAlgorithm planner = new StaticMetPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 3.0, 1));
        planner.run();
        // round(1.01*2)/2 = 1; round(1.02*3)/3 = 1, so ID3 wins the tie.
        assertEquals(3, task.getVmId());
        assertEquals(100L, task.getCloudletLength());
    }

    @Test
    void psoFitnessUsesRoundedEffectiveSecondsNotUnquantizedMatrixSeconds() {
        List<Task> tasks = Collections.singletonList(projectedTask(1, 100L, 1.24, 2.0));
        // VM3: round(1.24*2)=2 MI -> 1 second; .8*.002 + .2*1 = .2016.
        assertEquals(0.2016, PsoFitnessFunction.evaluate(new int[] {0}, tasks,
                vms(2.0, 1, 1.0, 1), 0.8), EPSILON);
    }

    @Test
    void aPresentButIncompleteMatrixDoesNotFallBackToRawMi() {
        Task task = new Task(1, 100L);
        task.setVmExecutionCostSeconds(Collections.singletonMap(Integer.valueOf(3), Double.valueOf(1.0)));
        StaticMetPlanningAlgorithm planner = new StaticMetPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 1.0, 1));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, planner::run);
        assertTrue(failure.getMessage().contains("8"), failure.getMessage());
    }

    @Test
    void noMatrixKeepsTheRawSinglePeLengthModel() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        StaticMetPlanningAlgorithm planner = new StaticMetPlanningAlgorithm();
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 2, 1.0, 2));
        planner.run();
        assertEquals(3, task.getVmId());
        // 2PE execute in parallel: 100/2=50 seconds, not totalLength/mips=100.
        assertEquals(10.08, PsoFitnessFunction.evaluate(new int[] {0},
                Collections.singletonList(task), vms(2.0, 2, 1.0, 2), 0.8), EPSILON);
    }

    @Test
    void standardRunnerExecutesTheMatrixPreferredVmForMetAndPso() throws Exception {
        Path input = workflow(directory, "matrix-single.dax",
                "<job id=\"only\" name=\"only\" runtime=\"100\"/>");
        double[][] costs = {{100.0, 1.0}};
        Log.disable();
        for (PlanningAlgorithm algorithm : Arrays.asList(PlanningAlgorithm.STATIC_MET, PlanningAlgorithm.PSO)) {
            SimulationReport report = new SimulationRunner().run(config(input.toString(), algorithm, costs, false),
                    platform(vms(2.0, 1, 1.0, 1)));
            SimulationReport.JobOutcome job = computeJob(report, 1);
            assertEquals(8, job.getVmId(), algorithm.name());
            assertEquals(55.1, job.getStartTime(), EPSILON, algorithm.name());
            assertEquals(56.1, job.getFinishTime(), EPSILON, algorithm.name());
            assertEquals(56.1, report.getMakespan(), EPSILON, algorithm.name());
        }
    }

    @Test
    void standardRunnerPreservesTheMatrixAwareOlbMapping() throws Exception {
        Path input = workflow(directory, "matrix-olb.dax",
                "<job id=\"first\" name=\"first\" runtime=\"100\"/>\n"
                + "<job id=\"second\" name=\"second\" runtime=\"100\"/>\n"
                + "<job id=\"third\" name=\"third\" runtime=\"100\"/>");
        double[][] costs = {{100.0, 1.0}, {1.0, 1.0}, {100.0, 1.0}};
        Log.disable();
        SimulationReport report = new SimulationRunner().run(
                config(input.toString(), PlanningAlgorithm.STATIC_OLB, costs, false),
                platform(vms(1.0, 1, 1.0, 1)));
        assertEquals(3, computeJob(report, 1).getVmId());
        assertEquals(8, computeJob(report, 2).getVmId());
        assertEquals(8, computeJob(report, 3).getVmId());
        assertEquals(210.1, report.getMakespan(), EPSILON);
    }

    private static void assertMatrixChoice(BasePlanningAlgorithm planner) throws Exception {
        Task task = projectedTask(1, 100L, 100.0, 1.0);
        planner.setTaskList(Collections.singletonList(task));
        planner.setVmList(vms(2.0, 1, 1.0, 1));
        planner.run();
        assertEquals(8, task.getVmId(), planner.getClass().getSimpleName());
        assertEquals(100L, task.getCloudletLength(), "planning must preserve source MI");
    }
}
