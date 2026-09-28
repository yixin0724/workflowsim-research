package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.PlanningRegressionFixtures.EPSILON;
import static org.workflowsim.planning.PlanningRegressionFixtures.computeJob;
import static org.workflowsim.planning.PlanningRegressionFixtures.config;
import static org.workflowsim.planning.PlanningRegressionFixtures.connect;
import static org.workflowsim.planning.PlanningRegressionFixtures.file;
import static org.workflowsim.planning.PlanningRegressionFixtures.localPlanner;
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
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

/** ALG-02/03: file-availability times are independent of planning selection order. */
class LocalDataAvailabilityPlanningTest {

    @TempDir
    Path directory;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void heftCannotInsertAConsumerBeforeItsInputReplicaArrives() throws Exception {
        assertSharedInputAvailability(PlanningAlgorithm.LOCAL_HEFT);
    }

    @Test
    void cpopCannotInsertAConsumerBeforeItsInputReplicaArrives() throws Exception {
        assertSharedInputAvailability(PlanningAlgorithm.LOCAL_CPOP);
    }

    @Test
    void peftCannotInsertAConsumerBeforeItsInputReplicaArrives() throws Exception {
        assertSharedInputAvailability(PlanningAlgorithm.LOCAL_PEFT);
    }

    @Test
    void heftRootWaitsForExternalInputAfterBootstrap() throws Exception {
        assertRootExternalInput(PlanningAlgorithm.LOCAL_HEFT);
    }

    @Test
    void cpopRootWaitsForExternalInputAfterBootstrap() throws Exception {
        assertRootExternalInput(PlanningAlgorithm.LOCAL_CPOP);
    }

    @Test
    void peftRootWaitsForExternalInputAfterBootstrap() throws Exception {
        assertRootExternalInput(PlanningAlgorithm.LOCAL_PEFT);
    }

    @Test
    void heftNonRootExternalTransferStartsAtDependencyReadiness() throws Exception {
        assertNonRootExternalInput(PlanningAlgorithm.LOCAL_HEFT);
    }

    @Test
    void cpopNonRootExternalTransferStartsAtDependencyReadiness() throws Exception {
        assertNonRootExternalInput(PlanningAlgorithm.LOCAL_CPOP);
    }

    @Test
    void peftNonRootExternalTransferStartsAtDependencyReadiness() throws Exception {
        assertNonRootExternalInput(PlanningAlgorithm.LOCAL_PEFT);
    }

    @Test
    void anIndependentTransferCanStillUseAGapBeforeAnEarlierSelectedConsumer() {
        Task root = projectedTask(1, 100L, 1.0, 1.0);
        Task twoFileConsumer = projectedTask(2, 100L, 2.0, 2.0);
        Task oneFileConsumer = projectedTask(3, 100L, 1.0, 1.0);
        connect(root, twoFileConsumer);
        connect(root, oneFileConsumer);
        root.addFile(file("f", 10000000.0, FileType.OUTPUT));
        root.addFile(file("g", 20000000.0, FileType.OUTPUT));
        twoFileConsumer.addFile(file("f", 10000000.0, FileType.INPUT));
        twoFileConsumer.addFile(file("g", 20000000.0, FileType.INPUT));
        oneFileConsumer.addFile(file("f", 10000000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(PlanningAlgorithm.LOCAL_HEFT,
                Arrays.asList(root, twoFileConsumer, oneFileConsumer), machines);
        planner.prepare();
        planner.allocate(root, machines.get(0), 110.1);
        planner.allocate(twoFileConsumer, machines.get(1), 110.1);
        planner.allocate(oneFileConsumer, machines.get(1), 110.1);

        // R finishes at111.1. A needs (10+20)MB ->141.1; B independently needs only10MB ->121.1.
        // Fixing future replicas must not prohibit this valid gap insertion or charge A's30s to B.
        assertEquals(141.1, twoFileConsumer.getStaticScheduleStartTime(), EPSILON);
        assertEquals(143.1, planner.plannedFinishOf(twoFileConsumer).doubleValue(), EPSILON);
        assertEquals(121.1, oneFileConsumer.getStaticScheduleStartTime(), EPSILON);
        assertEquals(122.1, planner.plannedFinishOf(oneFileConsumer).doubleValue(), EPSILON);
    }

    @Test
    void inputReplicasBecomeUsableAtHoldCompletionNotAtConsumerComputeStart() {
        Task blocker = projectedTask(1, 100L, 70.0, 70.0);
        Task firstConsumer = projectedTask(2, 100L, 20.0, 20.0);
        Task gate = projectedTask(3, 100L, 15.0, 15.0);
        Task laterConsumer = projectedTask(4, 100L, 1.0, 1.0);
        connect(gate, laterConsumer);
        blocker.addFile(file("blocker-input", 20000000.0, FileType.INPUT));
        firstConsumer.addFile(file("shared-external", 10000000.0, FileType.INPUT));
        laterConsumer.addFile(file("shared-external", 10000000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(PlanningAlgorithm.LOCAL_HEFT,
                Arrays.asList(blocker, firstConsumer, gate, laterConsumer), machines);
        planner.prepare();
        planner.allocate(blocker, machines.get(1), 110.1);
        planner.allocate(firstConsumer, machines.get(1), 110.1);
        planner.allocate(gate, machines.get(0), 110.1);
        planner.allocate(laterConsumer, machines.get(1), 110.1);

        // VM8 has a [130.1,200.1] reservation. First consumer's input arrives at120.1,
        // but its20s compute cannot fit before130.1, so compute starts at200.1.
        // Later consumer becomes ready at125.1 and uses the already arrived input,
        // fitting [125.1,126.1] rather than waiting another10s or for first compute.
        assertEquals(130.1, blocker.getStaticScheduleStartTime(), EPSILON);
        assertEquals(200.1, firstConsumer.getStaticScheduleStartTime(), EPSILON);
        assertEquals(220.1, planner.plannedFinishOf(firstConsumer).doubleValue(), EPSILON);
        assertEquals(125.1, laterConsumer.getStaticScheduleStartTime(), EPSILON);
        assertEquals(126.1, planner.plannedFinishOf(laterConsumer).doubleValue(), EPSILON);
    }

    @Test
    void rootTransferHoldHonorsTheMinimumEventInterval() throws Exception {
        double[][] costs = {{2.0, 100.0}};
        Task root = projectedTasks(costs).get(0);
        root.addFile(file("tiny-external", 10000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(PlanningAlgorithm.LOCAL_HEFT,
                Collections.singletonList(root), machines);
        planner.run();
        // 0.01s transfer is held for the configured 0.1s minimum, then2s computation.
        assertEquals(110.2, root.getStaticScheduleStartTime(), EPSILON);
        assertEquals(112.2, planner.plannedFinishOf(root).doubleValue(), EPSILON);

        Path input = workflow(directory, "tiny-external.dax",
                "<job id=\"root\" name=\"root\" runtime=\"100\">"
                + "<uses file=\"tiny-external\" link=\"input\" size=\"10000\"/></job>");
        Log.disable();
        SimulationReport report = new SimulationRunner().run(
                config(input.toString(), PlanningAlgorithm.LOCAL_HEFT, costs, true), platform(machines));
        assertEquals(110.2, computeJob(report, 1).getStartTime(), EPSILON);
        assertEquals(112.2, computeJob(report, 1).getFinishTime(), EPSILON);
    }

    @Test
    void aRootWithoutInputsStillStartsAtTheBootstrapBoundary() throws Exception {
        Task root = projectedTask(1, 100L, 2.0, 100.0);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(PlanningAlgorithm.LOCAL_HEFT,
                Collections.singletonList(root), vms(1.0, 1, 1.0, 1));
        planner.run();
        assertEquals(110.1, root.getStaticScheduleStartTime(), EPSILON);
        assertEquals(112.1, planner.plannedFinishOf(root).doubleValue(), EPSILON);
    }

    @Test
    void peftManifestDescribesDependencyReadySelectionNotTheObsoleteFailure() {
        String contract = org.workflowsim.experiment.AlgorithmCatalog.forConfiguration(
                config("planning-regression.dax", PlanningAlgorithm.LOCAL_PEFT, null, true))
                .get("planner").toString();
        assertTrue(contract.contains("dependency-ready"), contract);
        assertFalse(contract.contains("a child outranking its parent fails fast"), contract);
    }

    private void assertSharedInputAvailability(PlanningAlgorithm algorithm) throws Exception {
        double[][] costs = {{1.0, 10000.0}, {5.0, 20000.0}, {10000.0, 2.0}, {1000.0, 1.0}};
        List<Task> tasks = projectedTasks(costs);
        Task root = tasks.get(0);
        for (int child = 1; child < tasks.size(); child++) {
            connect(root, tasks.get(child));
        }
        root.addFile(file("f", 10000000.0, FileType.OUTPUT));
        tasks.get(2).addFile(file("f", 10000000.0, FileType.INPUT));
        tasks.get(3).addFile(file("f", 10000000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm, tasks, machines);
        planner.run();
        // R=1s after110.1; f needs10s to VM8. A(2s) is selected first, then B(1s).
        int[] expectedVms = {3, 3, 8, 8};
        double[] expectedStarts = {110.1, 111.1, 121.1, 123.1};
        double[] expectedFinishes = {111.1, 116.1, 123.1, 124.1};
        assertPlan(planner, tasks, expectedVms, expectedStarts, expectedFinishes);

        Path input = workflow(directory, "future-replica-" + algorithm.name() + ".dax",
                "<job id=\"R\" name=\"R\" runtime=\"100\">"
                + "<uses file=\"f\" link=\"output\" size=\"10000000\"/></job>\n"
                + "<job id=\"C\" name=\"C\" runtime=\"100\"/>\n"
                + "<job id=\"A\" name=\"A\" runtime=\"100\">"
                + "<uses file=\"f\" link=\"input\" size=\"10000000\"/></job>\n"
                + "<job id=\"B\" name=\"B\" runtime=\"100\">"
                + "<uses file=\"f\" link=\"input\" size=\"10000000\"/></job>\n"
                + "<child ref=\"C\"><parent ref=\"R\"/></child>\n"
                + "<child ref=\"A\"><parent ref=\"R\"/></child>\n"
                + "<child ref=\"B\"><parent ref=\"R\"/></child>");
        assertRuntime(input, algorithm, costs, machines, expectedVms, expectedStarts, expectedFinishes, 124.1);
    }

    private void assertRootExternalInput(PlanningAlgorithm algorithm) throws Exception {
        double[][] costs = {{2.0, 100.0}};
        List<Task> tasks = projectedTasks(costs);
        tasks.get(0).addFile(file("external", 10000000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm, tasks, machines);
        planner.run();
        // Stage-in registers a datacenter location, not f@VM3:110.1+10s transfer+2s compute.
        int[] expectedVms = {3};
        double[] expectedStarts = {120.1};
        double[] expectedFinishes = {122.1};
        assertPlan(planner, tasks, expectedVms, expectedStarts, expectedFinishes);
        Path input = workflow(directory, "external-root-" + algorithm.name() + ".dax",
                "<job id=\"root\" name=\"root\" runtime=\"100\">"
                + "<uses file=\"external\" link=\"input\" size=\"10000000\"/></job>");
        assertRuntime(input, algorithm, costs, machines, expectedVms, expectedStarts, expectedFinishes, 122.1);
    }

    private void assertNonRootExternalInput(PlanningAlgorithm algorithm) throws Exception {
        double[][] costs = {{2.0, 200.0}, {3.0, 100.0}};
        List<Task> tasks = projectedTasks(costs);
        connect(tasks.get(0), tasks.get(1));
        tasks.get(1).addFile(file("external", 10000000.0, FileType.INPUT));
        List<CondorVM> machines = vms(1.0, 1, 1.0, 1);
        AbstractLocalCommPlanningAlgorithm planner = localPlanner(algorithm, tasks, machines);
        planner.run();
        // Parent finishes112.1; external input starts then, not at simulated zero or bootstrap.
        int[] expectedVms = {3, 3};
        double[] expectedStarts = {110.1, 122.1};
        double[] expectedFinishes = {112.1, 125.1};
        assertPlan(planner, tasks, expectedVms, expectedStarts, expectedFinishes);
        Path input = workflow(directory, "external-child-" + algorithm.name() + ".dax",
                "<job id=\"root\" name=\"root\" runtime=\"100\"/>\n"
                + "<job id=\"child\" name=\"child\" runtime=\"100\">"
                + "<uses file=\"external\" link=\"input\" size=\"10000000\"/></job>\n"
                + "<child ref=\"child\"><parent ref=\"root\"/></child>");
        assertRuntime(input, algorithm, costs, machines, expectedVms, expectedStarts, expectedFinishes, 125.1);
    }

    private static void assertPlan(AbstractLocalCommPlanningAlgorithm planner, List<Task> tasks,
            int[] expectedVms, double[] expectedStarts, double[] expectedFinishes) {
        for (int index = 0; index < tasks.size(); index++) {
            Task task = tasks.get(index);
            String message = planner.label() + " task " + task.getCloudletId();
            assertEquals(expectedVms[index], task.getVmId(), message);
            assertEquals(expectedStarts[index], task.getStaticScheduleStartTime(), EPSILON, message);
            assertEquals(expectedFinishes[index], planner.plannedFinishOf(task).doubleValue(), EPSILON, message);
            assertEquals(100L, task.getCloudletLength(), "source MI must remain unchanged");
        }
    }

    private static void assertRuntime(Path input, PlanningAlgorithm algorithm, double[][] costs,
            List<CondorVM> machines, int[] expectedVms, double[] expectedStarts,
            double[] expectedFinishes, double expectedMakespan) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(
                config(input.toString(), algorithm, costs, true), platform(machines));
        for (int index = 0; index < expectedVms.length; index++) {
            SimulationReport.JobOutcome job = computeJob(report, index + 1);
            String message = algorithm.name() + " task " + (index + 1);
            assertEquals(expectedVms[index], job.getVmId(), message);
            assertEquals(expectedStarts[index], job.getStartTime(), EPSILON, message);
            assertEquals(expectedFinishes[index], job.getFinishTime(), EPSILON, message);
        }
        assertEquals(expectedMakespan, report.getMakespan(), EPSILON, algorithm.name());
    }
}
