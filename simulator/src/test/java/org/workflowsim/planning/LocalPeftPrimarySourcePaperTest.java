package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Primary-source PEFT regression: DOI 10.1109/TPDS.2013.57, author's open thesis Chapter 3.
 * Figure 1: printed p64 / PDF76; Table 5: p72 / PDF84; Table 6: p74 / PDF86.
 * Source and transcription details accompany /dax/peft-paper-example.dax.
 * This is not the older HEFT-origin ten-task cost/communication fixture.
 */
class LocalPeftPrimarySourcePaperTest {

    private static final double EPSILON = 1.0e-9;
    // 110 MI / 1 MIPS, followed by the explicit 0.1s engine release interval.
    private static final double BOOTSTRAP = 110.1;

    // Figure 1 rows T1..T10, columns P1/P2/P3 mapped here to VM IDs 0/1/2.
    private static final double[][] COMPUTE = {
        {22, 21, 36}, {22, 18, 18}, {32, 27, 43}, {7, 10, 4}, {29, 27, 35},
        {26, 17, 24}, {14, 25, 30}, {29, 23, 36}, {15, 21, 8}, {13, 16, 33}
    };
    private static final int[][] EDGES = {
        {1, 2, 17}, {1, 3, 31}, {1, 4, 29}, {1, 5, 13}, {1, 6, 7},
        {2, 8, 3}, {2, 9, 30}, {3, 7, 16}, {4, 8, 11}, {4, 9, 7},
        {5, 9, 57}, {6, 8, 5}, {7, 10, 9}, {8, 10, 42}, {9, 10, 7}
    };

    // Table 5 is a literal external oracle, not evaluated by a production helper.
    private static final double[][] PAPER_OCT = {
        {64, 68, 86}, {42, 39, 42}, {27, 41, 43}, {42, 39, 50}, {28, 37, 28},
        {42, 39, 44}, {13, 16, 22}, {13, 16, 33}, {13, 16, 20}, {0, 0, 0}
    };
    private static final double[] PAPER_RANK_OCT = {
        218.0 / 3.0, 41, 37, 131.0 / 3.0, 31, 125.0 / 3.0, 17, 62.0 / 3.0, 49.0 / 3.0, 0
    };
    private static final double[] PAPER_RANK_U = {
        169, 343.0 / 3.0, 308.0 / 3.0, 110, 389.0 / 3.0,
        358.0 / 3.0, 158.0 / 3.0, 92, 127.0 / 3.0, 62.0 / 3.0
    };

    // Table 6 selected tasks/processors and intervals (selected EFT minus Figure 1 compute).
    private static final List<Integer> PAPER_SELECTION_ORDER =
            Arrays.asList(1, 4, 6, 2, 3, 5, 8, 7, 9, 10);
    private static final int[] PEFT_VM = {0, 0, 0, 0, 2, 1, 0, 1, 2, 1};
    private static final double[] PEFT_START = {0, 29, 51, 22, 35, 29, 83, 54, 81, 106};
    private static final double[] PEFT_FINISH = {22, 51, 83, 29, 70, 46, 97, 77, 89, 122};

    // Independently reconstructed from Figure 1 and HEFT rank/EFT rules;
    // the terminal 133 agrees with the Figure 2 caption. Not a simulator-generated golden.
    private static final int[] HEFT_VM = {1, 0, 1, 2, 1, 2, 1, 0, 2, 0};
    private static final double[] HEFT_START = {0, 38, 48, 52, 21, 28, 75, 67, 105, 120};
    private static final double[] HEFT_FINISH = {21, 60, 75, 56, 48, 52, 100, 96, 113, 133};

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void allOctCellsAndBothRankColumnsMatchTheAuthorTableFive() {
        PlanningRun run = plan();
        for (int task = 0; task < COMPUTE.length; task++) {
            Task node = run.tasks.get(task);
            for (int processor = 0; processor < 3; processor++) {
                assertEquals(PAPER_OCT[task][processor],
                        run.planner.optimisticCostOf(node, run.machines.get(processor)), EPSILON,
                        "Table 5 OCT(T" + (task + 1) + ",P" + (processor + 1) + ")");
            }
            assertEquals(PAPER_RANK_OCT[task], run.planner.priorityOf(node), EPSILON,
                    "Table 5 rank_oct(T" + (task + 1) + ")");
            assertEquals(PAPER_RANK_U[task], run.planner.upwardRankOf(node), EPSILON,
                    "Table 5 rank_u(T" + (task + 1) + ")");
        }
    }

    @Test
    void readySelectionAndMappingMatchTheAuthorTableSix() {
        PlanningRun run = plan();
        // Observe public mapping writes on test Tasks, without a production trace API.
        assertEquals(PAPER_SELECTION_ORDER, run.selectionOrder);
        for (int task = 0; task < COMPUTE.length; task++) {
            assertEquals(PEFT_VM[task], run.tasks.get(task).getVmId(), "Table 6 T" + (task + 1));
        }
    }

    @Test
    void plannedIntervalsMatchTheAuthorTableSixWithExplicitBootstrap() {
        PlanningRun run = plan();
        for (int task = 0; task < COMPUTE.length; task++) {
            Task node = run.tasks.get(task);
            assertEquals(BOOTSTRAP + PEFT_START[task], node.getStaticScheduleStartTime(), EPSILON,
                    "Table 6 start T" + (task + 1));
            assertEquals(BOOTSTRAP + PEFT_FINISH[task], run.planner.plannedFinishOf(node), EPSILON,
                    "Table 6 finish T" + (task + 1));
            assertEquals(100L, node.getCloudletLength(), "source MI must not be rewritten");
        }
    }

    @Test
    void runnerPeftReproducesPaperMakespan122AndEveryTaskInterval() throws Exception {
        SimulationReport report = run(Parameters.PlanningAlgorithm.LOCAL_PEFT);
        assertRuntime(report, PEFT_VM, PEFT_START, PEFT_FINISH, 122.0);
    }

    @Test
    void runnerHeftIsAnIndependentSameInputControlAtPaperMakespan133() throws Exception {
        SimulationReport report = run(Parameters.PlanningAlgorithm.LOCAL_HEFT);
        assertRuntime(report, HEFT_VM, HEFT_START, HEFT_FINISH, 133.0);
    }

    private static PlanningRun plan() {
        List<Integer> order = new ArrayList<Integer>();
        List<Task> tasks = new ArrayList<Task>();
        for (int index = 0; index < COMPUTE.length; index++) {
            Task task = new TrackingTask(index + 1, order);
            Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
            for (int vm = 0; vm < 3; vm++) {
                costs.put(Integer.valueOf(vm), Double.valueOf(COMPUTE[index][vm]));
            }
            task.setVmExecutionCostSeconds(costs);
            tasks.add(task);
        }
        for (int[] edge : EDGES) {
            Task parent = tasks.get(edge[0] - 1);
            Task child = tasks.get(edge[1] - 1);
            parent.addChild(child);
            child.addParent(parent);
            String name = "peft-e" + edge[0] + "-" + edge[1];
            FileItem output = new FileItem(name, edge[2] * 1000000.0);
            output.setType(Parameters.FileType.OUTPUT);
            parent.addFile(output);
            FileItem input = new FileItem(name, edge[2] * 1000000.0);
            input.setType(Parameters.FileType.INPUT);
            child.addFile(input);
        }
        List<CondorVM> machines = Arrays.asList(vm(0), vm(1), vm(2));
        LocalPeftPlanningAlgorithm planner = new LocalPeftPlanningAlgorithm(new PlanningContext(
                configuration("peft-paper-unit.dax", Parameters.PlanningAlgorithm.LOCAL_PEFT), platform()));
        List<Task> reversed = new ArrayList<Task>(tasks);
        Collections.reverse(reversed);
        planner.setTaskList(reversed);
        planner.setVmList(Arrays.asList(machines.get(2), machines.get(0), machines.get(1)));
        planner.run();
        return new PlanningRun(planner, tasks, machines, order);
    }

    private static SimulationReport run(Parameters.PlanningAlgorithm algorithm) throws Exception {
        URL input = LocalPeftPrimarySourcePaperTest.class.getResource("/dax/peft-paper-example.dax");
        if (input == null) {
            throw new IllegalStateException("Missing primary-source PEFT fixture");
        }
        Log.disable();
        return new SimulationRunner().run(configuration(Paths.get(input.toURI()).toString(), algorithm),
                platform());
    }

    private static SimulationConfig configuration(String path, Parameters.PlanningAlgorithm algorithm) {
        TaskCostMatrix.Builder matrix = TaskCostMatrix.builder();
        for (int task = 0; task < COMPUTE.length; task++) {
            for (int vm = 0; vm < 3; vm++) {
                matrix.put(task + 1, vm, COMPUTE[task][vm]);
            }
        }
        return SimulationConfig.builder(path, 3)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .taskCostMatrix(matrix.build())
                .runtimeReferenceMips(1.0)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
    }

    private static PlatformProfile platform() {
        PlatformProfile.Builder result = PlatformProfile.builder("primary-source-peft-paper");
        for (int id = 0; id < 3; id++) {
            result.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10000L, 1000000L));
            result.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            result.pinVmToHost(id, id);
        }
        return result.build();
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1.0, 1, 512, 1L, 10000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static void assertRuntime(SimulationReport report, int[] vms, double[] starts,
            double[] finishes, double paperMakespan) {
        assertEquals(BOOTSTRAP + paperMakespan, report.getMakespan(), EPSILON);
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        int count = 0;
        Map<Integer, SimulationReport.JobOutcome> jobsByTask =
                new LinkedHashMap<Integer, SimulationReport.JobOutcome>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {
                continue;
            }
            assertEquals(1, job.getTaskIds().size());
            int task = job.getTaskIds().get(0).intValue() - 1;
            assertTrue(task >= 0 && task < COMPUTE.length);
            assertEquals(vms[task], job.getVmId(), "task " + (task + 1));
            assertEquals(BOOTSTRAP + starts[task], job.getStartTime(), EPSILON, "task " + (task + 1));
            assertEquals(BOOTSTRAP + finishes[task], job.getFinishTime(), EPSILON, "task " + (task + 1));
            assertTrue(!jobsByTask.containsKey(Integer.valueOf(task + 1)));
            jobsByTask.put(Integer.valueOf(task + 1), job);
            count++;
        }
        assertEquals(10, count);
        assertEquals(10, report.getTasks().size());
        boolean[] seen = new boolean[COMPUTE.length];
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            int index = task.getTaskId() - 1;
            assertTrue(index >= 0 && index < COMPUTE.length);
            assertTrue(!seen[index], "one logical TaskOutcome per paper task");
            seen[index] = true;
            SimulationReport.JobOutcome job = jobsByTask.get(Integer.valueOf(task.getTaskId()));
            assertEquals(job.getJobId(), task.getJobId());
            assertEquals(vms[index], task.getVmId());
            assertEquals(100L, task.getLengthMi(), "DAX placeholder source MI remains unchanged");
            // MIPS=1 and integral paper costs: effective MI is exactly the selected matrix entry.
            assertEquals((long) COMPUTE[index][vms[index]], task.getEffectiveExecutionLengthMi());
            assertEquals(BOOTSTRAP + starts[index], task.getStartTime(), EPSILON, "TaskOutcome start");
            assertEquals(BOOTSTRAP + finishes[index], task.getFinishTime(), EPSILON, "TaskOutcome finish");
            assertEquals(job.getStartTime(), task.getStartTime(), EPSILON, "Job/Task start alignment");
            assertEquals(job.getFinishTime(), task.getFinishTime(), EPSILON, "Job/Task finish alignment");
        }
    }

    private static final class TrackingTask extends Task {
        private final List<Integer> selectionOrder;

        TrackingTask(int id, List<Integer> selectionOrder) {
            super(id, 100L);
            this.selectionOrder = selectionOrder;
        }

        @Override
        public void setVmId(int vmId) {
            super.setVmId(vmId);
            if (selectionOrder != null && vmId >= 0) {
                selectionOrder.add(Integer.valueOf(getCloudletId()));
            }
        }
    }

    private static final class PlanningRun {
        private final LocalPeftPlanningAlgorithm planner;
        private final List<Task> tasks;
        private final List<CondorVM> machines;
        private final List<Integer> selectionOrder;

        PlanningRun(LocalPeftPlanningAlgorithm planner, List<Task> tasks,
                List<CondorVM> machines, List<Integer> selectionOrder) {
            this.planner = planner;
            this.tasks = tasks;
            this.machines = machines;
            this.selectionOrder = selectionOrder;
        }
    }
}
