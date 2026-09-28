package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Synthetic and HEFT-origin regressions for the independently verified PEFT Eq. (7).
 * Expected OCT uses successor compute cost and exit zero; no CloudSim execution here.
 * The actual PEFT article Figure 1/Table 5/Table 6 is covered separately by
 * {@link LocalPeftPrimarySourcePaperTest}, not by the HEFT-origin fixture below.
 */
class LocalPeftPlanningAlgorithmTest {

    private static final double EPSILON = 1.0e-9;

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

        // Exit=[0,0]; t2=min(1+0,2+0)=[1,1], because its outgoing edge is free.
        assertEquals(0.0, planner.optimisticCostOf(t3, fast), EPSILON);
        assertEquals(0.0, planner.optimisticCostOf(t3, slow), EPSILON);
        assertEquals(1.0, planner.optimisticCostOf(t2, fast), EPSILON);
        assertEquals(1.0, planner.optimisticCostOf(t2, slow), EPSILON);
        // t1@fast=min(1+1,2+1+10)=2; t1@slow=min(1+1+10,2+1)=3.
        assertEquals(2.0, planner.optimisticCostOf(t1, fast), EPSILON);
        assertEquals(3.0, planner.optimisticCostOf(t1, slow), EPSILON);
        assertEquals(2.5, planner.priorityOf(t1), EPSILON);
        assertEquals(1.0, planner.priorityOf(t2), EPSILON);
        assertEquals(0.0, planner.priorityOf(t3), EPSILON);
    }

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
        // Bootstrap=110/2 + .1=55.1; compute costs on the fast VM are2,1,1.
        double stageIn = 55.1;
        assertEquals(3, t1.getVmId());
        assertEquals(stageIn, t1.getStaticScheduleStartTime(), EPSILON);
        assertEquals(3, t2.getVmId());
        assertEquals(stageIn + 2.0, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(3, t3.getVmId());
        assertEquals(stageIn + 3.0, t3.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t3), EPSILON);
    }

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
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2a, t2b, t3), fast, slow);
        planner.run();
        double stageIn = 55.1;

        // Both middle tasks have OCT=[1,2], independent of their own compute length.
        // Root OCT=max(min(2+1,4+2),min(.5+1,1+2))=3 on either VM.
        assertEquals(3.0, planner.priorityOf(t1), EPSILON);
        assertEquals(1.5, planner.priorityOf(t2a), EPSILON);
        assertEquals(1.5, planner.priorityOf(t2b), EPSILON);
        assertEquals(0.0, planner.priorityOf(t3), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(3, t2a.getVmId());
        assertEquals(stageIn + 1.0, t2a.getStaticScheduleStartTime(), EPSILON);
        // B: fast score=3.5+1=4.5, slow score=2+2=4 (relative to bootstrap).
        assertEquals(8, t2b.getVmId());
        assertEquals(stageIn + 1.0, t2b.getStaticScheduleStartTime(), EPSILON);
        assertEquals(3, t3.getVmId());
        assertEquals(stageIn + 3.0, t3.getStaticScheduleStartTime(), EPSILON);
        assertEquals(stageIn + 4.0, planner.plannedFinishOf(t3), EPSILON);
    }

    @Test
    void singleVmDegenerateCaseStillSchedules() {
        Task t1 = new Task(1, 3);
        Task t2 = new Task(2, 5);
        connect(t1, t2, 2.0);
        CondorVM only = vm(3, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2), only);
        planner.run();
        // No interprocessor transfer: root OCT=successor5, exit OCT=0.
        assertEquals(5.0, planner.optimisticCostOf(t1, only), EPSILON);
        assertEquals(0.0, planner.optimisticCostOf(t2, only), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(3, t2.getVmId());
        assertEquals(113.1, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(118.1, planner.plannedFinishOf(t2), EPSILON);
    }

    /**
     * Retains the older HEFT-origin input, not the PEFT article's Figure 1.
     * All expectations below were independently recomputed with successor-cost Eq. (7).
     * For example, OCT(n9,p0)=min(21,7+13,16+13)=20 and every exit cell is0.
     */
    @Test
    void heftOriginFixtureMatchesIndependentlyComputedSuccessorOctAndIntervals() {
        double[][] cost = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}
        };
        int[][] edges = {
            {1, 2, 18}, {1, 3, 12}, {1, 4, 9}, {1, 5, 11}, {1, 6, 14},
            {2, 8, 19}, {2, 9, 16}, {3, 7, 23}, {4, 8, 27}, {4, 9, 23},
            {5, 9, 13}, {6, 8, 15}, {7, 10, 17}, {8, 10, 11}, {9, 10, 13}
        };
        Task[] tasks = new Task[11];
        for (int id = 1; id <= 10; id++) {
            tasks[id] = new Task(id, 100);
            Map<Integer, Double> perVm = new HashMap<Integer, Double>();
            for (int vm = 0; vm < 3; vm++) {
                perVm.put(Integer.valueOf(vm), Double.valueOf(cost[id - 1][vm]));
            }
            tasks[id].setVmExecutionCostSeconds(perVm);
        }
        for (int[] edge : edges) connect(tasks[edge[0]], tasks[edge[1]], edge[2]);
        CondorVM p1 = vm(0, 1L, 1.0);
        CondorVM p2 = vm(1, 1L, 1.0);
        CondorVM p3 = vm(2, 1L, 1.0);
        List<Task> taskList = new ArrayList<Task>();
        for (int id = 1; id <= 10; id++) taskList.add(tasks[id]);
        LocalPeftPlanningAlgorithm planner = planner(taskList, p1, p2, p3);

        double[][] expectedOct = {
            {}, {48, 38, 53}, {35, 19, 35}, {28, 22, 27}, {38, 19, 36},
            {32, 19, 32}, {23, 18, 30}, {21, 7, 16}, {18, 7, 16}, {20, 7, 16}, {0, 0, 0}
        };
        double[] expectedRanks = {0, 139.0 / 3.0, 89.0 / 3.0, 77.0 / 3.0, 31,
            83.0 / 3.0, 71.0 / 3.0, 44.0 / 3.0, 41.0 / 3.0, 43.0 / 3.0, 0};
        int[] expectedVm = {-1, 1, 1, 0, 1, 2, 0, 0, 0, 1, 1};
        double[] expectedStarts = {0, 0, 24, 28, 16, 27, 39, 52, 62, 50, 78};
        double[] expectedFinishes = {0, 16, 43, 39, 24, 37, 52, 59, 67, 62, 85};
        CondorVM[] machines = {p1, p2, p3};
        // Reuse the same planner to retain cache/reservation/replica reset coverage.
        for (int run = 0; run < 2; run++) {
            planner.run();
            for (int id = 1; id <= 10; id++) {
                for (int vm = 0; vm < 3; vm++) {
                    assertEquals(expectedOct[id][vm], planner.optimisticCostOf(tasks[id], machines[vm]),
                            EPSILON, "HEFT-origin OCT n" + id + " VM" + vm);
                }
                assertEquals(expectedRanks[id], planner.priorityOf(tasks[id]), EPSILON);
                assertEquals(expectedVm[id], tasks[id].getVmId());
                assertEquals(110.1 + expectedStarts[id], tasks[id].getStaticScheduleStartTime(), EPSILON);
                assertEquals(110.1 + expectedFinishes[id], planner.plannedFinishOf(tasks[id]), EPSILON);
            }
            assertEquals(195.1, planner.plannedFinishOf(tasks[10]), EPSILON,
                    "independent Eq7 result85 plus bootstrap, not PEFT article makespan122");
        }
    }

    @Test
    void independentTiedTasksFallBackToTaskIdThenVmId() {
        Task t1 = new Task(1, 4);
        Task t2 = new Task(2, 4);
        CondorVM first = vm(3, 1L, 1.0);
        CondorVM second = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2), first, second);
        planner.run();
        assertEquals(0.0, planner.priorityOf(t1), EPSILON);
        assertEquals(0.0, planner.priorityOf(t2), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(8, t2.getVmId());
        assertEquals(110.1, t1.getStaticScheduleStartTime(), EPSILON);
        assertEquals(110.1, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(114.1, planner.plannedFinishOf(t1), EPSILON);
        assertEquals(114.1, planner.plannedFinishOf(t2), EPSILON);
    }

    @Test
    void heterogeneousRankOInversionSchedulesByReadyListDiscipline() {
        Task t1 = new Task(1, 2);
        Task t2 = new Task(2, 2);
        Task t3 = new Task(3, 40);
        connect(t1, t2, 0.0);
        connect(t2, t3, 10.0);
        CondorVM fast = vm(3, 1L, 2.0);
        CondorVM slow = vm(8, 1L, 1.0);
        LocalPeftPlanningAlgorithm planner = planner(Arrays.asList(t1, t2, t3), fast, slow);
        planner.run();
        // Correct Eq7: exit=[0,0]; middle=[20,30]; root=min(1+20,2+30)=[21,21].
        // This replaces the old31<34.5 signature, which was specific to the incorrect recurrence.
        assertEquals(21.0, planner.priorityOf(t1), EPSILON);
        assertEquals(25.0, planner.priorityOf(t2), EPSILON);
        assertEquals(0.0, planner.priorityOf(t3), EPSILON);
        assertTrue(planner.priorityOf(t1) < planner.priorityOf(t2));
        assertEquals(21.0, planner.optimisticCostOf(t1, fast), EPSILON);
        assertEquals(21.0, planner.optimisticCostOf(t1, slow), EPSILON);
        assertEquals(3, t1.getVmId());
        assertEquals(3, t2.getVmId());
        assertEquals(3, t3.getVmId());
        assertEquals(55.1, t1.getStaticScheduleStartTime(), EPSILON);
        assertEquals(56.1, t2.getStaticScheduleStartTime(), EPSILON);
        assertEquals(57.1, t3.getStaticScheduleStartTime(), EPSILON);
        assertEquals(77.1, planner.plannedFinishOf(t3), EPSILON);
    }

    private static void connect(Task parent, Task child, double communicationSeconds) {
        parent.addChild(child);
        child.addParent(parent);
        if (communicationSeconds > 0.0) {
            String name = "edge-" + parent.getCloudletId() + "-" + child.getCloudletId();
            FileItem output = new FileItem(name, communicationSeconds * 1000000.0);
            FileItem input = new FileItem(name, communicationSeconds * 1000000.0);
            output.setType(Parameters.FileType.OUTPUT);
            input.setType(Parameters.FileType.INPUT);
            parent.addFile(output);
            child.addFile(input);
        }
    }

    private static LocalPeftPlanningAlgorithm planner(List<Task> tasks, CondorVM... machines) {
        PlatformProfile.Builder platform = PlatformProfile.builder("local-peft-unit");
        for (int index = 0; index < machines.length; index++) {
            platform.addHost(new PlatformProfile.HostSpec(index, 2, 2.0, 2048, 10000L, 1000000L));
            platform.addVm(new PlatformProfile.VmSpec(machines[index].getId(), machines[index].getMips(),
                    1, 512, machines[index].getBw(), 10000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        SimulationConfig config = SimulationConfig.builder("peft-unit.dax", machines.length)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1()).build();
        LocalPeftPlanningAlgorithm planner = new LocalPeftPlanningAlgorithm(new PlanningContext(config, platform.build()));
        planner.setTaskList(new ArrayList<Task>(tasks));
        planner.setVmList(new ArrayList<CondorVM>(Arrays.asList(machines)));
        return planner;
    }

    private static CondorVM vm(int id, long bw, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, bw, 10000L, "Xen", new CloudletSchedulerSpaceShared());
    }
}
