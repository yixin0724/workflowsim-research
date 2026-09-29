package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.BOOTSTRAP;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.STORAGE_MB_PER_SECOND;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.bits;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.originalSlot;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.publicPlanner;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.rawSchedule;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.rawSlot;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.runPublic;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.snapshot;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.planning.SharedStorageDagPlanner.Strategy;
import org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.Kernel;
import org.workflowsim.utils.Parameters;

/** Exact slot semantics and independent small-plan expectations for the DLS/ETF query-reuse audit. */
class SharedStorageDlsEtfSlotSemanticsAuditTest {
    @Test
    void leadingInternalTinyAndTouchingGapsKeepTheOriginalComparisons() throws Exception {
        double[][] gaps = {{4.0, 6.0}, {10.0, 12.0}};
        checkSlot(gaps, 0.0, 4.0, 0.0);
        checkSlot(gaps, 0.0, Math.nextUp(4.0), 6.0);
        checkSlot(gaps, 0.0, Math.nextUp(Math.nextUp(4.0)), 12.0);
        checkSlot(gaps, 3.0, 1.0, 3.0);
        checkSlot(gaps, 4.0, 1.0, 6.0);
        checkSlot(gaps, 5.0, 1.0, 6.0);
        checkSlot(gaps, 6.0, 4.0, 6.0);
        checkSlot(gaps, 7.0, 3.0, 7.0);
        checkSlot(gaps, 9.0, 2.0, 12.0);
        checkSlot(gaps, 12.0, 1.0, 12.0);
        checkSlot(gaps, 13.0, 1.0, 13.0);
        checkSlot(new double[0][2], -0.0, 1.0, -0.0);
        checkSlot(new double[0][2], +0.0, 1.0, +0.0);

        double[][] tinyGap = {{1.0, 2.0}, {Math.nextUp(2.0), 3.0}};
        checkSlot(tinyGap, 2.0, Math.ulp(2.0), 2.0);
        checkSlot(tinyGap, 2.0, 2.0 * Math.ulp(2.0), 3.0);
        checkSlot(new double[][] {{1.0, 2.0}, {2.0, 3.0}}, 2.0, 1.0, 3.0);
    }

    @Test
    void zeroWidthAndRoundedZeroProgressMustNotBeCollapsedIntoAnOccupiedUnion() throws Exception {
        checkSlot(new double[][] {{5.0, 5.0}}, 4.0, 2.0, 5.0);
        checkSlot(new double[][] {{5.0, 5.0}}, 5.0, 0.0, 5.0);
        double[][] touching = {{1.0, 2.0}, {2.0, 3.0}};
        checkSlot(touching, 2.0, 0.0, 2.0);
        double subUlp = Math.scalb(1.0, -53);
        assertTrue(subUlp > 0.0);
        assertEquals(bits(2.0), bits(2.0 + subUlp));
        checkSlot(touching, 2.0, subUlp, 2.0);
        // Checking progress only at the original readiness would miss this later rounded stop.
        double large = Math.scalb(1.0, 53);
        checkSlot(new double[][] {{0.0, large}, {large, 2.0 * large}}, 0.0, 1.0, large);
    }

    @Test
    void candidateQueriesDistinguishExactReadinessAndDurationKeys() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            Task shortTask = new Task(7, 250L);
            Task longTask = new Task(13, 2000L);
            CondorVM machine = vm(7, 1000.0, 1);
            Kernel kernel = new Kernel(strategy, Arrays.asList(shortTask, longTask),
                    Collections.singletonList(machine));
            kernel.reserve(machine, 1.0, 2.0);
            assertCandidate(kernel, shortTask, machine, 0.0, 0.25, 0.0);
            assertCandidate(kernel, longTask, machine, 0.0, 2.0, 2.0);
            assertCandidate(kernel, shortTask, machine, 0.0, 0.25, 0.0);
            assertCandidate(kernel, shortTask, machine, 0.5, 0.25, 0.5);
            assertCandidate(kernel, shortTask, machine, Math.nextUp(0.5), 0.25, Math.nextUp(0.5));
            assertCandidate(kernel, shortTask, machine, -0.0, 0.25, -0.0);
            assertCandidate(kernel, shortTask, machine, +0.0, 0.25, +0.0);
        }
    }

    @Test
    void interleavedVmQueriesAndOutOfOrderInsertionsDoNotReuseStaleSlots() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            Task task = new Task(7, 1000L);
            CondorVM first = vm(7, 1000.0, 1);
            CondorVM second = vm(29, 1000.0, 1);
            Kernel kernel = new Kernel(strategy, Collections.singletonList(task), Arrays.asList(first, second));
            assertCandidate(kernel, task, first, 0.0, 1.0, 0.0);
            assertCandidate(kernel, task, second, 0.0, 1.0, 0.0);
            kernel.reserve(first, 4.0, 6.0);
            assertCandidate(kernel, task, first, 0.0, 1.0, 0.0);
            kernel.reserve(first, 0.0, 1.0);
            assertCandidate(kernel, task, first, 0.0, 1.0, 1.0);
            assertCandidate(kernel, task, second, 0.0, 1.0, 0.0);
            kernel.reserve(first, 1.0, 2.0);
            kernel.reserve(second, 0.0, 3.0);
            assertCandidate(kernel, task, second, 0.0, 1.0, 3.0);
            assertCandidate(kernel, task, first, 0.0, 1.0, 2.0);
            kernel.reserve(first, 2.0, 4.0);
            assertCandidate(kernel, task, first, 0.0, 1.0, 6.0);
            assertCandidate(kernel, task, second, 0.0, 1.0, 3.0);
            assertCandidate(kernel, task, first, 0.0, 1.0, 6.0);
        }
    }

    @Test
    void repeatedSubUlpCandidateQueriesKeepTheOriginalInternalBoundary() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            Task task = new Task(7, 1L);
            CondorVM machine = vm(7, Math.scalb(1.0, 53), 1);
            Kernel kernel = new Kernel(strategy, Collections.singletonList(task), Collections.singletonList(machine));
            kernel.reserve(machine, 1.0, 2.0);
            kernel.reserve(machine, 2.0, 3.0);
            double rank = Math.scalb(1.0, -53);
            assertCandidate(kernel, task, machine, 2.0, rank, 2.0);
            assertCandidate(kernel, task, machine, 2.0, rank, 2.0);
            kernel.reserve(machine, 3.0, 4.0);
            assertCandidate(kernel, task, machine, 2.0, rank, 2.0);
            assertCandidate(kernel, task, machine, 3.0, rank, 3.0);
        }
    }

    @Test
    void boundedSmallPlansMatchIndependentEndpointsAndFrozenOriginalSlotDecisions() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            for (int number = 0; number < 12; number++) {
                Fixture fixture = new Fixture(number);
                Reference expected = reference(fixture, strategy);
                SharedStorageDagPlanTrace original = runAndCheck(fixture, strategy, expected, false);
                SharedStorageDagPlanTrace reversed = runAndCheck(fixture, strategy, expected, true);
                assertEquals(snapshot(original), snapshot(reversed), strategy + " fixture=" + number + " permutation");
            }
        }
    }

    @Test
    void repeatedPublicRunsAndSameIdReplacementsStartFreshAndKeepOldTracesImmutable() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            Fixture fixture = new Fixture(3);
            List<Task> tasks = fixture.tasks(false);
            BasePlanningAlgorithm planner = publicPlanner(strategy, tasks, fixture.vms(false));
            SharedStorageDagPlanTrace original = runPublic(planner);
            assertReference(fixture, strategy, reference(fixture, strategy), original);
            List<Object> saved = snapshot(original);
            assertEquals(saved, snapshot(runPublic(planner)), strategy + " identical repeat");

            long oldLength = fixture.mi[0];
            fixture.mi[0] += 1000L;
            tasks.get(0).setCloudletLength(fixture.mi[0]);
            planner.setVmList(fixture.vms(true)); // Same IDs/specifications, new VM object identities.
            SharedStorageDagPlanTrace changed = runPublic(planner);
            assertReference(fixture, strategy, reference(fixture, strategy), changed);
            assertNotEquals(saved, snapshot(changed), "Changed source work must be visible");
            assertEquals(saved, snapshot(original), "A later run must not mutate its predecessor's trace");

            boolean oldEdge = fixture.edges[0][1];
            fixture.edges[0][1] = !oldEdge; // Both alternatives remain acyclic.
            planner.setTaskList(fixture.tasks(true)); // New Task objects with the same IDs.
            planner.setVmList(fixture.vms(false));
            assertReference(fixture, strategy, reference(fixture, strategy), runPublic(planner));

            fixture.mi[0] = oldLength;
            fixture.edges[0][1] = oldEdge;
            planner.setTaskList(fixture.tasks(false));
            planner.setVmList(fixture.vms(true));
            assertEquals(saved, snapshot(runPublic(planner)), strategy + " A/B/A fresh-generation replay");
        }
    }

    private static void checkSlot(double[][] intervals, double ready, double duration, double expected)
            throws Exception {
        assertEquals(bits(expected), bits(originalSlot(intervals, ready, duration)), "Explicit original boundary oracle");
        assertEquals(bits(expected), bits(rawSlot(rawSchedule(intervals), ready, duration)),
                "ready=" + Double.toHexString(ready) + " duration=" + Double.toHexString(duration));
    }

    private static void assertCandidate(Kernel kernel, Task task, CondorVM machine,
            double ready, double rank, double expectedStart) throws Exception {
        double expected = kernel.strategy == Strategy.DLS ? rank - expectedStart : expectedStart;
        assertEquals(bits(expected), bits(kernel.candidateMetric(task, machine, ready)),
                kernel.strategy + " task=" + task.getCloudletId() + " vm=" + machine.getId()
                        + " ready=" + Double.toHexString(ready));
    }

    private static SharedStorageDagPlanTrace runAndCheck(Fixture fixture, Strategy strategy,
            Reference expected, boolean reverse) throws Exception {
        List<Task> tasks = fixture.tasks(reverse);
        List<CondorVM> vms = fixture.vms(reverse);
        List<Task> taskOrder = new ArrayList<Task>(tasks);
        List<CondorVM> vmOrder = new ArrayList<CondorVM>(vms);
        SharedStorageDagPlanTrace trace = runPublic(publicPlanner(strategy, tasks, vms));
        assertEquals(taskOrder, tasks, "Caller task order is not a scheduling scratch buffer");
        assertEquals(vmOrder, vms, "Caller VM order is not a scheduling scratch buffer");
        assertReference(fixture, strategy, expected, trace);
        for (Task task : tasks) {
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(task.getCloudletId());
            assertEquals(plan.getVmId(), task.getVmId());
            assertEquals(bits(plan.getPlannedStartSeconds()), bits(task.getStaticScheduleStartTime()));
        }
        return trace;
    }

    /** Array declarations, independent rank DP and exhaustive ready-pair/endpoint enumeration. */
    private static Reference reference(Fixture fixture, Strategy strategy) {
        Reference result = new Reference(fixture.n, fixture.vmIds.length);
        for (int task = 0; task < fixture.n; task++) {
            double transferSeconds = fixture.inputBytes[task] / 1000000.0 / STORAGE_MB_PER_SECOND;
            double total = 0.0;
            int compatible = 0;
            for (int machine = 0; machine < fixture.vmIds.length; machine++) {
                if (fixture.pes[task] > fixture.vmPes[machine]) {
                    result.work[task][machine] = Double.POSITIVE_INFINITY;
                    continue;
                }
                long inputMi = (long) (fixture.mips[machine] * transferSeconds);
                result.work[task][machine] = (fixture.mi[task] + inputMi) / fixture.mips[machine];
                result.stageIn[task][machine] = inputMi / fixture.mips[machine];
                total += result.work[task][machine];
                compatible++;
            }
            result.rank[task] = total / compatible;
        }
        // Fixture indices, unlike task IDs, are a declared topological order. No production depth,
        // rank helper, execution-cost getter or optimized slot helper produces these expectations.
        for (int task = fixture.n - 1; task >= 0; task--) {
            double tail = 0.0;
            for (int child = task + 1; child < fixture.n; child++) {
                if (fixture.edges[task][child]) {
                    tail = Math.max(tail, result.rank[child]);
                }
            }
            result.rank[task] += tail;
        }
        List<List<double[]>> reservations = new ArrayList<List<double[]>>();
        for (int machine = 0; machine < fixture.vmIds.length; machine++) {
            reservations.add(new ArrayList<double[]>());
        }
        for (int step = 1; step <= fixture.n; step++) {
            int selectedTask = -1;
            int selectedVm = -1;
            double selectedStart = 0.0;
            for (int task = 0; task < fixture.n; task++) {
                if (result.order[task] != 0) {
                    continue;
                }
                boolean root = true;
                boolean ready = true;
                double release = 0.0;
                for (int parent = 0; parent < task; parent++) {
                    if (fixture.edges[parent][task]) {
                        root = false;
                        ready &= result.order[parent] != 0;
                        release = Math.max(release, result.finish[parent]);
                    }
                }
                if (!ready) {
                    continue;
                }
                if (root) {
                    release = BOOTSTRAP;
                }
                for (int machine = 0; machine < fixture.vmIds.length; machine++) {
                    if (Double.isInfinite(result.work[task][machine])) {
                        continue;
                    }
                    List<double[]> calendar = reservations.get(machine);
                    double start = endpointSlot(calendar, release, result.work[task][machine]);
                    double[][] ordered = calendar.toArray(new double[calendar.size()][]);
                    Arrays.sort(ordered, Comparator.comparingDouble((double[] interval) -> interval[0])
                            .thenComparingDouble(interval -> interval[1]));
                    assertEquals(bits(originalSlot(ordered, release, result.work[task][machine])), bits(start),
                            "Independent endpoint oracle must agree with frozen original slots on these normal costs");
                    if (selectedTask < 0 || better(fixture, result, strategy,
                            task, machine, start, selectedTask, selectedVm, selectedStart)) {
                        selectedTask = task;
                        selectedVm = machine;
                        selectedStart = start;
                    }
                }
            }
            assertTrue(selectedTask >= 0, "Declared acyclic graph must leave a compatible ready pair");
            result.vm[selectedTask] = selectedVm;
            result.order[selectedTask] = step;
            result.start[selectedTask] = selectedStart;
            result.finish[selectedTask] = selectedStart + result.work[selectedTask][selectedVm];
            reservations.get(selectedVm).add(new double[] {selectedStart, result.finish[selectedTask]});
        }
        return result;
    }

    private static double endpointSlot(List<double[]> intervals, double ready, double duration) {
        TreeSet<Double> candidates = new TreeSet<Double>();
        candidates.add(ready);
        for (double[] interval : intervals) {
            candidates.add(Math.max(ready, interval[1]));
        }
        for (double candidate : candidates) {
            boolean free = true;
            for (double[] interval : intervals) {
                if (!(candidate + duration <= interval[0] || candidate >= interval[1])) {
                    free = false;
                    break;
                }
            }
            if (free) {
                return candidate;
            }
        }
        throw new AssertionError("Finite small schedules must have a tail gap");
    }

    private static boolean better(Fixture fixture, Reference result, Strategy strategy,
            int task, int machine, double start, int selectedTask, int selectedVm, double selectedStart) {
        int primary = strategy == Strategy.DLS
                ? Double.compare(result.rank[task] - start, result.rank[selectedTask] - selectedStart)
                : Double.compare(selectedStart, start);
        if (primary != 0) {
            return primary > 0;
        }
        if (strategy == Strategy.ETF) {
            int rank = Double.compare(result.rank[task], result.rank[selectedTask]);
            if (rank != 0) {
                return rank > 0;
            }
        }
        return fixture.ids[task] != fixture.ids[selectedTask]
                ? fixture.ids[task] < fixture.ids[selectedTask] : fixture.vmIds[machine] < fixture.vmIds[selectedVm];
    }

    private static void assertReference(Fixture fixture, Strategy strategy, Reference expected,
            SharedStorageDagPlanTrace trace) {
        assertEquals(strategy.name(), trace.getStrategy());
        assertEquals(bits(BOOTSTRAP), bits(trace.getStageInFinishSeconds()));
        assertEquals(fixture.n, trace.getTaskPlans().size());
        assertTrue(trace.getCriticalPathTaskIds().isEmpty());
        assertNull(trace.getCriticalProcessorVmId());
        List<Integer> ids = new ArrayList<Integer>();
        for (int id : fixture.ids) {
            ids.add(id);
        }
        Collections.sort(ids);
        assertEquals(ids, new ArrayList<Integer>(trace.getTaskPlans().keySet()));
        for (int task = 0; task < fixture.n; task++) {
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(fixture.ids[task]);
            String subject = strategy + " task=" + fixture.ids[task];
            int selectedVm = expected.vm[task];
            assertEquals(fixture.ids[task], plan.getTaskId(), subject);
            assertEquals(fixture.vmIds[selectedVm], plan.getVmId(), subject + " VM");
            assertTrue(fixture.pes[task] <= fixture.vmPes[selectedVm], subject + " compatible VM");
            assertEquals(bits(expected.rank[task]), bits(plan.getUpwardRankSeconds()), subject + " rank");
            assertEquals(bits(expected.start[task]), bits(plan.getPlannedStartSeconds()), subject + " start");
            assertEquals(bits(expected.finish[task]), bits(plan.getPlannedFinishSeconds()), subject + " finish");
            assertEquals(bits(expected.stageIn[task][selectedVm]), bits(plan.getPlannedDataStageInSeconds()), subject + " stage-in");
            assertEquals(bits(expected.start[task] + expected.stageIn[task][selectedVm]),
                    bits(plan.getPlannedComputeStartSeconds()), subject + " compute start");
            if (strategy == Strategy.DLS) {
                assertEquals(Integer.valueOf(expected.order[task]), plan.getDlsSelectionOrder(), subject + " DLS order");
                assertEquals(bits(expected.rank[task] - expected.start[task]),
                        bits(plan.getDlsDynamicLevelAtSelection().doubleValue()), subject + " DLS metric");
                assertNull(plan.getEtfEarliestStartAtSelection());
                assertNull(plan.getEtfSelectionOrder());
            } else {
                assertEquals(Integer.valueOf(expected.order[task]), plan.getEtfSelectionOrder(), subject + " ETF order");
                assertEquals(bits(expected.start[task]), bits(plan.getEtfEarliestStartAtSelection().doubleValue()), subject + " ETF metric");
                assertNull(plan.getDlsDynamicLevelAtSelection());
                assertNull(plan.getDlsSelectionOrder());
            }
            assertNull(plan.getCpopDownwardRankSeconds());
            assertNull(plan.getCpopPrioritySeconds());
            assertNull(plan.getPeftRankOctSeconds());
            assertNull(plan.getPeftOptimisticCostAtSelectedVmSeconds());
            assertNull(plan.getPeftEftPlusOptimisticCostSeconds());
            assertNull(plan.getPeftSelectionOrder());
        }
    }

    private static final class Reference {
        final double[][] work;
        final double[][] stageIn;
        final double[] rank;
        final double[] start;
        final double[] finish;
        final int[] vm;
        final int[] order;

        Reference(int tasks, int machines) {
            work = new double[tasks][machines];
            stageIn = new double[tasks][machines];
            rank = new double[tasks];
            start = new double[tasks];
            finish = new double[tasks];
            vm = new int[tasks];
            order = new int[tasks];
        }
    }

    private static final class Fixture {
        final int n;
        final int[] ids;
        final long[] mi;
        final int[] pes;
        final double[] inputBytes;
        final boolean[][] edges;
        final int[] vmIds = {3, 11, 29};
        final double[] mips = {1000.0, 2000.0, 500.0};
        final int[] vmPes = {1, 1, 2};

        Fixture(int number) {
            n = 5 + number % 4;
            ids = Arrays.copyOf(new int[] {43, 7, 61, 13, 97, 19, 31, 73}, n);
            mi = new long[n];
            pes = new int[n];
            inputBytes = new double[n];
            edges = new boolean[n][n];
            Random random = new Random(0xc62c6b8L + number * 104729L);
            for (int task = 0; task < n; task++) {
                mi[task] = 250L * (1 + random.nextInt(8));
                pes[task] = (task + number) % 4 == 0 ? 2 : 1;
                inputBytes[task] = (task + number) % 3 == 0 ? 1000000.5 : 0.0;
                for (int child = task + 1; child < n; child++) {
                    edges[task][child] = random.nextInt(5) == 0;
                }
            }
            edges[0][2] = true;
            edges[0][3] = true;
            edges[2][4] = true;
            edges[3][4] = true;
        }

        List<Task> tasks(boolean reverse) {
            List<Task> tasks = new ArrayList<Task>();
            for (int task = 0; task < n; task++) {
                Task value = new Task(ids[task], mi[task]);
                value.setNumberOfPes(pes[task]);
                if (inputBytes[task] > 0.0) {
                    FileItem input = new FileItem("slot-audit-external-" + ids[task], inputBytes[task]);
                    input.setType(Parameters.FileType.INPUT);
                    value.addFile(input);
                }
                tasks.add(value);
            }
            for (int parent = 0; parent < n; parent++) {
                for (int child = parent + 1; child < n; child++) {
                    if (edges[parent][child]) {
                        tasks.get(parent).addChild(tasks.get(child));
                        tasks.get(child).addParent(tasks.get(parent));
                    }
                }
            }
            if (reverse) {
                Collections.reverse(tasks);
                for (Task task : tasks) {
                    Collections.reverse(task.getParentList());
                    Collections.reverse(task.getChildList());
                }
            }
            return tasks;
        }

        List<CondorVM> vms(boolean reverse) {
            List<CondorVM> result = new ArrayList<CondorVM>();
            for (int machine = 0; machine < vmIds.length; machine++) {
                result.add(vm(vmIds[machine], mips[machine], vmPes[machine]));
            }
            if (reverse) {
                Collections.reverse(result);
            }
            return result;
        }
    }
}
