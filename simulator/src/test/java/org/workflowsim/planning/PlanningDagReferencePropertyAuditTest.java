package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Independent small-graph decisions, not golden makespans or a runtime metric oracle.
 * Expectations use integer-index adjacency, iterative topological DP, exhaustive critical paths
 * (at most 12 nodes), and enumeration of possible reservation starts rather than planner helpers.
 * Edge files have one producer and one consumer, so LOCAL arrival has a direct pairwise oracle;
 * replica reuse and external-input timing remain the subject of their dedicated regressions.
 */
class PlanningDagReferencePropertyAuditTest {
    private static final int CASES = 24;
    private static final double EPSILON = 1.0e-9;
    // The minimum-ID VM is always 1000 MIPS: 110 MI bootstrap, then one 0.1 s release interval.
    private static final double BOOTSTRAP = 0.11 + 0.1;
    private static final int STORAGE_MB_PER_SECOND = 8;

    @Test
    void localHeftMatchesIndependentDagReference() throws Exception {
        check(PlanningAlgorithm.LOCAL_HEFT);
    }

    @Test
    void localCpopMatchesIndependentDagReference() throws Exception {
        check(PlanningAlgorithm.LOCAL_CPOP);
    }

    @Test
    void localPeftMatchesIndependentSuccessorCostDagReference() throws Exception {
        check(PlanningAlgorithm.LOCAL_PEFT);
    }

    @Test
    void sharedHeftMatchesIndependentDagReference() throws Exception {
        check(PlanningAlgorithm.SHARED_STORAGE_HEFT);
    }

    @Test
    void sharedCpopMatchesIndependentDagReference() throws Exception {
        check(PlanningAlgorithm.SHARED_STORAGE_CPOP);
    }

    @Test
    void sharedDlsMatchesIndependentReadyPairReference() throws Exception {
        check(PlanningAlgorithm.SHARED_STORAGE_DLS);
    }

    @Test
    void sharedEtfMatchesIndependentReadyPairReference() throws Exception {
        check(PlanningAlgorithm.SHARED_STORAGE_ETF);
    }

    @Test
    void sharedPeftMatchesIndependentSuccessorCostDagReference() throws Exception {
        check(PlanningAlgorithm.SHARED_STORAGE_PEFT);
    }

    private static void check(PlanningAlgorithm algorithm) throws Exception {
        for (int number = 0; number < CASES; number++) {
            Fixture fixture = new Fixture(number);
            String subject = algorithm + " seed=" + fixture.seed + " case=" + number;
            Reference expected = reference(fixture, algorithm);
            Actual original = run(fixture, algorithm, false);
            assertReference(fixture, algorithm, expected, original, subject);
            Actual permuted = run(fixture, algorithm, true);
            assertReference(fixture, algorithm, expected, permuted, subject + " permuted");
            // IDs, not input-list order, are the deterministic tie contract for these planners.
            assertEquals(snapshot(original), snapshot(permuted), subject + " exact permutation replay");
        }
    }

    private static Reference reference(Fixture f, PlanningAlgorithm algorithm) {
        boolean local = local(algorithm);
        Reference r = new Reference(f.n, f.m);
        int[] order = topologicalOrder(f);
        for (int task = 0; task < f.n; task++) {
            long inputBytes = 0L;
            for (int parent = 0; parent < f.n; parent++) {
                inputBytes += f.bytes[parent][task];
            }
            double inputSeconds = inputBytes / 1.0e6 / STORAGE_MB_PER_SECOND;
            int count = 0;
            for (int vm = 0; vm < f.m; vm++) {
                if (!f.compatible(task, vm)) {
                    r.work[task][vm] = Double.POSITIVE_INFINITY;
                    r.oct[task][vm] = Double.POSITIVE_INFINITY;
                    continue;
                }
                if (local) {
                    long mi = f.projected ? Math.round(f.costSeconds[task][vm] * f.mips[vm])
                            : f.lengthMi[task];
                    r.work[task][vm] = mi / f.mips[vm];
                } else {
                    // SHARED permits no task matrix and truncates its input delay to integer MI.
                    long inputMi = (long) (inputSeconds * f.mips[vm]);
                    r.work[task][vm] = (f.lengthMi[task] + inputMi) / f.mips[vm];
                    r.stageIn[task][vm] = inputMi / f.mips[vm];
                }
                r.meanWork[task] += r.work[task][vm];
                count++;
            }
            r.meanWork[task] /= count;
        }
        // Only arrays from the generated declaration feed these expectations. No Task depths,
        // TaskExecutionModel, planner rank/OCT getter, or production scheduling helper is used.
        for (int index = order.length - 1; index >= 0; index--) {
            int task = order[index];
            double tail = 0.0;
            for (int child = 0; child < f.n; child++) {
                if (f.edge[task][child]) {
                    tail = Math.max(tail, r.upward[child] + meanCommunication(f, local, task, child));
                }
            }
            r.upward[task] = r.meanWork[task] + tail;
            int count = 0;
            for (int vm = 0; vm < f.m; vm++) {
                if (!f.compatible(task, vm)) {
                    continue;
                }
                double worst = 0.0;
                for (int child = 0; child < f.n; child++) {
                    if (!f.edge[task][child]) {
                        continue;
                    }
                    double best = Double.POSITIVE_INFINITY;
                    for (int nextVm = 0; nextVm < f.m; nextVm++) {
                        if (f.compatible(child, nextVm)) {
                            best = Math.min(best, r.oct[child][nextVm] + r.work[child][nextVm]
                                    + communication(f, local, task, child, vm, nextVm));
                        }
                    }
                    worst = Math.max(worst, best);
                }
                r.oct[task][vm] = worst; // No children => zero, not current-task work.
                r.rankOct[task] += worst;
                count++;
            }
            r.rankOct[task] /= count;
        }
        for (int task : order) {
            for (int parent = 0; parent < f.n; parent++) {
                if (f.edge[parent][task]) {
                    r.downward[task] = Math.max(r.downward[task], r.downward[parent]
                            + r.meanWork[parent] + meanCommunication(f, local, parent, task));
                }
            }
        }
        if (kind(algorithm, "CPOP")) {
            r.path = longestPath(f, r, local);
            double best = Double.POSITIVE_INFINITY;
            for (int vm = 0; vm < f.m; vm++) {
                double total = 0.0;
                for (int task : r.path) {
                    total += r.work[task][vm];
                }
                if (total < best) {
                    best = total;
                    r.criticalVm = vm;
                }
            }
            assertTrue(r.criticalVm >= 0, "Generated CPOP path must have a compatible VM");
        }
        List<List<Interval>> reservations = new ArrayList<List<Interval>>();
        for (int vm = 0; vm < f.m; vm++) {
            reservations.add(new ArrayList<Interval>());
        }
        for (int step = 0; step < f.n; step++) {
            Choice selected = null;
            if (kind(algorithm, "DLS") || kind(algorithm, "ETF")) {
                for (int task = 0; task < f.n; task++) {
                    if (!ready(f, r, task)) {
                        continue;
                    }
                    for (int vm = 0; vm < f.m; vm++) {
                        if (!f.compatible(task, vm)) {
                            continue;
                        }
                        double start = firstGap(reservations.get(vm), dependencyReady(f, r, task), r.work[task][vm]);
                        Choice candidate = new Choice(task, vm, start, start + r.work[task][vm]);
                        if (selected == null || betterPair(f, r, algorithm, candidate, selected)) {
                            selected = candidate;
                        }
                    }
                }
            } else {
                int task = -1;
                for (int candidate = 0; candidate < f.n; candidate++) {
                    if (ready(f, r, candidate) && (task < 0
                            || higherPriority(f, r, algorithm, candidate, task))) {
                        task = candidate;
                    }
                }
                assertTrue(task >= 0, "Independent reference must have a ready task");
                double best = Double.POSITIVE_INFINITY;
                for (int vm = 0; vm < f.m; vm++) {
                    if (!f.compatible(task, vm)
                            || (r.path.contains(task) && vm != r.criticalVm)) {
                        continue;
                    }
                    double arrival = dataReady(f, r, local, task, vm);
                    double start = firstGap(reservations.get(vm), arrival, r.work[task][vm]);
                    double finish = start + r.work[task][vm];
                    double score = finish + (kind(algorithm, "PEFT") ? r.oct[task][vm] : 0.0);
                    if (score < best) {
                        best = score;
                        selected = new Choice(task, vm, start, finish);
                    }
                }
            }
            assertTrue(selected != null, "Independent reference must select a compatible pair");
            int task = selected.task;
            r.vm[task] = selected.vm;
            r.start[task] = selected.start;
            r.finish[task] = selected.finish;
            r.selectionOrder[task] = step + 1;
            reservations.get(selected.vm).add(new Interval(selected.start, selected.finish));
        }
        return r;
    }

    private static boolean higherPriority(Fixture f, Reference r, PlanningAlgorithm algorithm,
            int first, int second) {
        double firstValue = priority(r, algorithm, first);
        double secondValue = priority(r, algorithm, second);
        int comparison = Double.compare(firstValue, secondValue);
        return comparison != 0 ? comparison > 0 : f.ids[first] < f.ids[second];
    }

    private static double priority(Reference r, PlanningAlgorithm algorithm, int task) {
        if (kind(algorithm, "CPOP")) {
            return r.upward[task] + r.downward[task];
        }
        return kind(algorithm, "PEFT") ? r.rankOct[task] : r.upward[task];
    }

    private static boolean betterPair(Fixture f, Reference r, PlanningAlgorithm algorithm,
            Choice first, Choice second) {
        int comparison;
        if (kind(algorithm, "DLS")) {
            comparison = Double.compare(r.upward[first.task] - first.start,
                    r.upward[second.task] - second.start);
            if (comparison != 0) {
                return comparison > 0;
            }
        } else {
            comparison = Double.compare(first.start, second.start);
            if (comparison != 0) {
                return comparison < 0;
            }
            comparison = Double.compare(r.upward[first.task], r.upward[second.task]);
            if (comparison != 0) {
                return comparison > 0;
            }
        }
        return f.ids[first.task] != f.ids[second.task]
                ? f.ids[first.task] < f.ids[second.task] : f.vmIds[first.vm] < f.vmIds[second.vm];
    }

    private static boolean ready(Fixture f, Reference r, int task) {
        if (r.selectionOrder[task] != 0) {
            return false;
        }
        for (int parent = 0; parent < f.n; parent++) {
            if (f.edge[parent][task] && r.selectionOrder[parent] == 0) {
                return false;
            }
        }
        return true;
    }

    private static double dependencyReady(Fixture f, Reference r, int task) {
        double value = 0.0;
        boolean root = true;
        for (int parent = 0; parent < f.n; parent++) {
            if (f.edge[parent][task]) {
                root = false;
                value = Math.max(value, r.finish[parent]);
            }
        }
        return root ? BOOTSTRAP : value;
    }

    private static double dataReady(Fixture f, Reference r, boolean local, int task, int vm) {
        double dependency = dependencyReady(f, r, task);
        double arrival = dependency;
        if (local) {
            for (int parent = 0; parent < f.n; parent++) {
                if (f.edge[parent][task]) {
                    arrival = Math.max(arrival, r.finish[parent]
                            + communication(f, true, parent, task, r.vm[parent], vm));
                }
            }
            double hold = arrival - dependency;
            arrival = hold > 0.0 ? dependency + Math.max(0.1, hold) : dependency;
        }
        return arrival;
    }

    /** Every feasible first gap starts at readiness or an existing interval's end. */
    private static double firstGap(List<Interval> intervals, double ready, double duration) {
        TreeSet<Double> candidates = new TreeSet<Double>();
        candidates.add(ready);
        for (Interval interval : intervals) {
            candidates.add(Math.max(ready, interval.finish));
        }
        for (double candidate : candidates) {
            boolean clear = true;
            for (Interval interval : intervals) {
                if (!(candidate + duration <= interval.start || candidate >= interval.finish)) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return candidate;
            }
        }
        throw new AssertionError("Finite reservations must leave a tail gap");
    }

    private static double meanCommunication(Fixture f, boolean local, int parent, int child) {
        if (!local || f.bytes[parent][child] == 0L) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (int from = 0; from < f.m; from++) {
            for (int to = 0; to < f.m; to++) {
                if (from != to && f.compatible(parent, from) && f.compatible(child, to)) {
                    total += communication(f, true, parent, child, from, to);
                    pairs++;
                }
            }
        }
        return pairs == 0 ? 0.0 : total / pairs;
    }

    private static double communication(Fixture f, boolean local, int parent, int child,
            int from, int to) {
        return !local || from == to ? 0.0
                : f.bytes[parent][child] / 1.0e6 / Math.min(f.bandwidth[from], f.bandwidth[to]);
    }

    private static int[] topologicalOrder(Fixture f) {
        int[] incoming = new int[f.n];
        for (int parent = 0; parent < f.n; parent++) {
            for (int child = 0; child < f.n; child++) {
                if (f.edge[parent][child]) {
                    incoming[child]++;
                }
            }
        }
        Deque<Integer> ready = new ArrayDeque<Integer>();
        for (int task = 0; task < f.n; task++) {
            if (incoming[task] == 0) {
                ready.addLast(task);
            }
        }
        int[] result = new int[f.n];
        int visited = 0;
        while (!ready.isEmpty()) {
            int parent = ready.removeFirst();
            result[visited++] = parent;
            for (int child = 0; child < f.n; child++) {
                if (f.edge[parent][child] && --incoming[child] == 0) {
                    ready.addLast(child);
                }
            }
        }
        assertEquals(f.n, visited, "Generated declaration must be acyclic");
        return result;
    }

    /** Exhaustive path enumeration is deliberately independent of the production greedy walk. */
    private static List<Integer> longestPath(Fixture f, Reference r, boolean local) {
        int entry = -1;
        for (int task = 0; task < f.n; task++) {
            boolean root = true;
            for (int parent = 0; parent < f.n; parent++) {
                root &= !f.edge[parent][task];
            }
            if (root && (entry < 0 || r.upward[task] > r.upward[entry]
                    || (Double.compare(r.upward[task], r.upward[entry]) == 0 && f.ids[task] < f.ids[entry]))) {
                entry = task;
            }
        }
        List<List<Integer>> paths = new ArrayList<List<Integer>>();
        enumeratePaths(f, entry, new ArrayList<Integer>(), paths);
        List<Integer> selected = null;
        for (List<Integer> path : paths) {
            double cost = 0.0;
            for (int index = 0; index < path.size(); index++) {
                int task = path.get(index);
                cost += r.meanWork[task];
                if (index > 0) {
                    cost += meanCommunication(f, local, path.get(index - 1), task);
                }
            }
            if (Math.abs(cost - r.upward[entry]) <= EPSILON * Math.max(1.0, r.upward[entry])
                    && (selected == null || earlierPath(f, r, local, path, selected))) {
                selected = path;
            }
        }
        assertTrue(selected != null, "At least one maximum entry-to-exit path must exist");
        return selected;
    }

    private static void enumeratePaths(Fixture f, int task, List<Integer> prefix, List<List<Integer>> paths) {
        prefix.add(task);
        boolean exit = true;
        for (int child = 0; child < f.n; child++) {
            if (f.edge[task][child]) {
                exit = false;
                enumeratePaths(f, child, prefix, paths);
            }
        }
        if (exit) {
            paths.add(new ArrayList<Integer>(prefix));
        }
        prefix.remove(prefix.size() - 1);
    }

    private static boolean earlierPath(Fixture f, Reference r, boolean local,
            List<Integer> first, List<Integer> second) {
        for (int index = 1; index < Math.min(first.size(), second.size()); index++) {
            int a = first.get(index);
            int b = second.get(index);
            if (!local && Double.compare(r.upward[a], r.upward[b]) != 0) {
                return r.upward[a] > r.upward[b];
            }
            if (f.ids[a] != f.ids[b]) {
                return f.ids[a] < f.ids[b];
            }
        }
        return first.size() < second.size();
    }

    private static Actual run(Fixture f, PlanningAlgorithm algorithm, boolean permuted) throws Exception {
        Task[] tasks = new Task[f.n];
        TaskCostMatrix.Builder matrix = TaskCostMatrix.builder();
        for (int index = 0; index < f.n; index++) {
            tasks[index] = new Task(f.ids[index], f.lengthMi[index]);
            tasks[index].setNumberOfPes(f.pes[index]);
            if (local(algorithm) && f.projected) {
                Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
                for (int vm = 0; vm < f.m; vm++) {
                    costs.put(f.vmIds[vm], f.costSeconds[index][vm]);
                    matrix.put(f.ids[index], f.vmIds[vm], f.costSeconds[index][vm]);
                }
                tasks[index].setVmExecutionCostSeconds(costs);
            }
        }
        for (int parent = 0; parent < f.n; parent++) {
            for (int child = 0; child < f.n; child++) {
                if (f.edge[parent][child]) {
                    tasks[parent].addChild(tasks[child]);
                    tasks[child].addParent(tasks[parent]);
                    if (f.bytes[parent][child] > 0L) {
                        String name = "planning-audit-edge-" + parent + "-" + child;
                        tasks[parent].addFile(file(name, f.bytes[parent][child], FileType.OUTPUT));
                        tasks[child].addFile(file(name, f.bytes[parent][child], FileType.INPUT));
                    }
                }
            }
        }
        CondorVM[] vms = new CondorVM[f.m];
        PlatformProfile.Builder platform = PlatformProfile.builder("planning-property-audit");
        for (int vm = 0; vm < f.m; vm++) {
            vms[vm] = new CondorVM(f.vmIds[vm], 0, f.mips[vm], f.vmPes[vm], 512,
                    f.bandwidth[vm], 10000L, "Xen", new CloudletSchedulerSpaceShared());
            platform.addHost(new PlatformProfile.HostSpec(vm, f.vmPes[vm], f.mips[vm],
                    2048, 10000L, 1000000L));
            platform.addVm(new PlatformProfile.VmSpec(f.vmIds[vm], f.mips[vm], f.vmPes[vm],
                    512, f.bandwidth[vm], 10000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            platform.pinVmToHost(f.vmIds[vm], vm);
        }
        platform.storage(new PlatformProfile.StorageSpec(1000000L, STORAGE_MB_PER_SECOND));
        SimulationConfig.Builder config = SimulationConfig.builder("planning-property-audit.dax", f.m)
                .planningAlgorithm(algorithm).schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .cloudSimMinEventIntervalSeconds(0.1)
                .fileSystem(local(algorithm) ? ReplicaCatalog.FileSystem.LOCAL : ReplicaCatalog.FileSystem.SHARED);
        if (local(algorithm)) {
            config.dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
            if (f.projected) {
                config.taskCostMatrix(matrix.build());
            }
        }
        BasePlanningAlgorithm planner = planner(algorithm, new PlanningContext(config.build(), platform.build()));
        List<Task> taskInput = new ArrayList<Task>(Arrays.asList(tasks));
        List<CondorVM> vmInput = new ArrayList<CondorVM>(Arrays.asList(vms));
        if (permuted) {
            Random shuffle = new Random(f.seed ^ 0x5deece66dL);
            Collections.shuffle(taskInput, shuffle);
            Collections.shuffle(vmInput, shuffle);
            for (Task task : tasks) {
                Collections.shuffle(task.getParentList(), shuffle);
                Collections.shuffle(task.getChildList(), shuffle);
                Collections.shuffle(task.getFileList(), shuffle);
            }
        }
        List<Task> originalTaskOrder = new ArrayList<Task>(taskInput);
        List<CondorVM> originalVmOrder = new ArrayList<CondorVM>(vmInput);
        planner.setTaskList(taskInput);
        planner.setVmList(vmInput);
        planner.run();
        assertEquals(originalTaskOrder, taskInput, "Planner must not reorder caller tasks");
        assertEquals(originalVmOrder, vmInput, "Planner must not reorder caller VMs");
        return new Actual(planner, tasks, vms, local(algorithm) ? null : trace(planner));
    }

    private static void assertReference(Fixture f, PlanningAlgorithm algorithm, Reference r,
            Actual actual, String subject) {
        if (kind(algorithm, "CPOP")) {
            List<Integer> pathIds = new ArrayList<Integer>();
            for (int task : r.path) {
                pathIds.add(f.ids[task]);
            }
            List<Integer> actualPath = actual.trace == null
                    ? ((LocalCpopPlanningAlgorithm) actual.planner).getCriticalPathTaskIds()
                    : actual.trace.getCriticalPathTaskIds();
            Integer actualVm = actual.trace == null
                    ? ((LocalCpopPlanningAlgorithm) actual.planner).getCriticalProcessorVmId()
                    : actual.trace.getCriticalProcessorVmId();
            assertEquals(pathIds, actualPath, subject + " critical path");
            assertEquals(Integer.valueOf(f.vmIds[r.criticalVm]), actualVm, subject + " critical processor");
        }
        for (int task = 0; task < f.n; task++) {
            Task observed = actual.tasks[task];
            String item = subject + " task=" + f.ids[task];
            assertEquals(f.vmIds[r.vm[task]], observed.getVmId(), item + " mapping");
            assertTrue(f.compatible(task, r.vm[task]), item + " PE compatibility");
            assertEquals(r.start[task], observed.getStaticScheduleStartTime(), EPSILON, item + " start");
            if (actual.trace == null) {
                AbstractLocalCommPlanningAlgorithm local = (AbstractLocalCommPlanningAlgorithm) actual.planner;
                assertEquals(r.finish[task], local.plannedFinishOf(observed), EPSILON, item + " finish");
                assertEquals(r.upward[task], local.upwardRankOf(observed), EPSILON, item + " upward rank");
                if (kind(algorithm, "CPOP")) {
                    assertEquals(r.downward[task], ((LocalCpopPlanningAlgorithm) local).downwardRankOf(observed),
                            EPSILON, item + " downward rank");
                } else if (kind(algorithm, "PEFT")) {
                    LocalPeftPlanningAlgorithm peft = (LocalPeftPlanningAlgorithm) local;
                    assertEquals(r.rankOct[task], peft.priorityOf(observed), EPSILON, item + " rank OCT");
                    for (int vm = 0; vm < f.m; vm++) {
                        assertEquals(r.oct[task][vm], peft.optimisticCostOf(observed, actual.vms[vm]),
                                EPSILON, item + " OCT VM=" + f.vmIds[vm]);
                    }
                }
            } else {
                SharedStorageDagPlanTrace.TaskPlan plan = actual.trace.getTaskPlan(f.ids[task]);
                assertEquals(r.finish[task], plan.getPlannedFinishSeconds(), EPSILON, item + " finish");
                assertEquals(r.upward[task], plan.getUpwardRankSeconds(), EPSILON, item + " upward rank");
                assertEquals(r.stageIn[task][r.vm[task]], plan.getPlannedDataStageInSeconds(), EPSILON, item + " stage-in");
                if (kind(algorithm, "CPOP")) {
                    assertEquals(r.downward[task], plan.getCpopDownwardRankSeconds(), EPSILON, item + " downward rank");
                } else if (kind(algorithm, "DLS")) {
                    assertEquals(Integer.valueOf(r.selectionOrder[task]), plan.getDlsSelectionOrder(), item + " order");
                    assertEquals(r.upward[task] - r.start[task], plan.getDlsDynamicLevelAtSelection(), EPSILON, item + " level");
                } else if (kind(algorithm, "ETF")) {
                    assertEquals(Integer.valueOf(r.selectionOrder[task]), plan.getEtfSelectionOrder(), item + " order");
                    assertEquals(r.start[task], plan.getEtfEarliestStartAtSelection(), EPSILON, item + " earliest");
                } else if (kind(algorithm, "PEFT")) {
                    assertEquals(Integer.valueOf(r.selectionOrder[task]), plan.getPeftSelectionOrder(), item + " order");
                    assertEquals(r.rankOct[task], plan.getPeftRankOctSeconds(), EPSILON, item + " rank OCT");
                    assertEquals(r.oct[task][r.vm[task]], plan.getPeftOptimisticCostAtSelectedVmSeconds(), EPSILON, item + " OCT");
                    assertEquals(r.finish[task] + r.oct[task][r.vm[task]],
                            plan.getPeftEftPlusOptimisticCostSeconds(), EPSILON, item + " OEFT");
                }
            }
            assertTrue(Double.isFinite(observed.getStaticScheduleStartTime()), item + " finite plan");
            for (int parent = 0; parent < f.n; parent++) {
                if (f.edge[parent][task]) {
                    assertTrue(r.finish[parent] <= r.start[task], item + " parent precedence");
                }
            }
        }
        for (int a = 0; a < f.n; a++) {
            for (int b = a + 1; b < f.n; b++) {
                if (r.vm[a] == r.vm[b]) {
                    assertTrue(r.finish[a] <= r.start[b] || r.finish[b] <= r.start[a], subject + " VM nonoverlap");
                }
            }
        }
    }

    private static List<Object> snapshot(Actual actual) {
        List<Object> result = new ArrayList<Object>();
        if (actual.planner instanceof LocalCpopPlanningAlgorithm) {
            LocalCpopPlanningAlgorithm cpop = (LocalCpopPlanningAlgorithm) actual.planner;
            result.add(cpop.getCriticalPathTaskIds());
            result.add(cpop.getCriticalProcessorVmId());
        } else if (actual.trace != null) {
            result.add(actual.trace.getCriticalPathTaskIds());
            result.add(actual.trace.getCriticalProcessorVmId());
        }
        for (Task task : actual.tasks) {
            result.add(task.getVmId());
            result.add(task.getStaticScheduleStartTime());
            if (actual.trace == null) {
                AbstractLocalCommPlanningAlgorithm local = (AbstractLocalCommPlanningAlgorithm) actual.planner;
                result.add(local.plannedFinishOf(task));
                result.add(local.upwardRankOf(task));
                if (local instanceof LocalCpopPlanningAlgorithm) {
                    result.add(((LocalCpopPlanningAlgorithm) local).downwardRankOf(task));
                } else if (local instanceof LocalPeftPlanningAlgorithm) {
                    for (CondorVM vm : actual.vms) {
                        result.add(((LocalPeftPlanningAlgorithm) local).optimisticCostOf(task, vm));
                    }
                }
            } else {
                SharedStorageDagPlanTrace.TaskPlan p = actual.trace.getTaskPlan(task.getCloudletId());
                result.addAll(Arrays.asList(p.getPlannedFinishSeconds(), p.getUpwardRankSeconds(),
                        p.getCpopDownwardRankSeconds(), p.getCpopPrioritySeconds(), p.getDlsDynamicLevelAtSelection(),
                        p.getDlsSelectionOrder(), p.getEtfEarliestStartAtSelection(), p.getEtfSelectionOrder(),
                        p.getPeftRankOctSeconds(), p.getPeftOptimisticCostAtSelectedVmSeconds(),
                        p.getPeftEftPlusOptimisticCostSeconds(), p.getPeftSelectionOrder(), p.getPlannedDataStageInSeconds()));
            }
        }
        return result;
    }

    private static BasePlanningAlgorithm planner(PlanningAlgorithm algorithm, PlanningContext context) {
        switch (algorithm) {
            case LOCAL_HEFT: return new LocalHeftPlanningAlgorithm(context);
            case LOCAL_CPOP: return new LocalCpopPlanningAlgorithm(context);
            case LOCAL_PEFT: return new LocalPeftPlanningAlgorithm(context);
            case SHARED_STORAGE_HEFT: return new SharedStorageHeftPlanningAlgorithm(context);
            case SHARED_STORAGE_CPOP: return new SharedStorageCpopPlanningAlgorithm(context);
            case SHARED_STORAGE_DLS: return new SharedStorageDlsPlanningAlgorithm(context);
            case SHARED_STORAGE_ETF: return new SharedStorageEtfPlanningAlgorithm(context);
            case SHARED_STORAGE_PEFT: return new SharedStoragePeftPlanningAlgorithm(context);
            default: throw new AssertionError(algorithm);
        }
    }

    private static SharedStorageDagPlanTrace trace(BasePlanningAlgorithm planner) {
        if (planner instanceof SharedStorageHeftPlanningAlgorithm) return ((SharedStorageHeftPlanningAlgorithm) planner).getLastPlanTrace();
        if (planner instanceof SharedStorageCpopPlanningAlgorithm) return ((SharedStorageCpopPlanningAlgorithm) planner).getLastPlanTrace();
        if (planner instanceof SharedStorageDlsPlanningAlgorithm) return ((SharedStorageDlsPlanningAlgorithm) planner).getLastPlanTrace();
        if (planner instanceof SharedStorageEtfPlanningAlgorithm) return ((SharedStorageEtfPlanningAlgorithm) planner).getLastPlanTrace();
        return ((SharedStoragePeftPlanningAlgorithm) planner).getLastPlanTrace();
    }

    private static FileItem file(String name, long bytes, FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }

    private static boolean local(PlanningAlgorithm algorithm) {
        return algorithm.name().startsWith("LOCAL_");
    }

    private static boolean kind(PlanningAlgorithm algorithm, String suffix) {
        return algorithm.name().endsWith("_" + suffix);
    }

    private static final class Fixture {
        final long seed;
        final int n;
        final int m;
        final boolean projected;
        final int[] ids;
        final long[] lengthMi;
        final int[] pes;
        final int[] vmIds;
        final double[] mips;
        final int[] vmPes;
        final long[] bandwidth;
        final double[][] costSeconds;
        final boolean[][] edge;
        final long[][] bytes;

        Fixture(int number) {
            seed = 0x1b6baa8L + 104729L * number;
            Random random = new Random(seed);
            n = number < 2 ? number + 1 : 3 + random.nextInt(10);
            m = 1 + number % 3;
            projected = ((number / 4) & 1) != 0;
            ids = new int[n];
            lengthMi = new long[n];
            pes = new int[n];
            vmIds = new int[m];
            mips = new double[m];
            vmPes = new int[m];
            bandwidth = new long[m];
            costSeconds = new double[n][m];
            edge = new boolean[n][n];
            bytes = new long[n][n];
            List<Integer> labels = new ArrayList<Integer>();
            for (int task = 0; task < n; task++) labels.add(10 + 7 * task);
            Collections.shuffle(labels, random);
            for (int vm = 0; vm < m; vm++) {
                vmIds[vm] = 3 + 11 * vm;
                mips[vm] = 1000.0 * (1 << vm);
                vmPes[vm] = vm == m - 1 ? 4 : 1 << vm;
                bandwidth[vm] = 1L << vm;
            }
            for (int task = 0; task < n; task++) {
                ids[task] = labels.get(task);
                lengthMi[task] = 1000L * (1 + random.nextInt(20));
                pes[task] = random.nextBoolean() ? 1 : 2;
                for (int vm = 0; vm < m; vm++) {
                    costSeconds[task][vm] = 0.5 + random.nextInt(48) / 4.0;
                }
            }
            for (int parent = 0; parent < n; parent++) {
                for (int child = parent + 1; child < n; child++) {
                    int shape = number % 4;
                    edge[parent][child] = shape == 0 ? child == parent + 1
                            : shape == 1 ? parent == 0 || child == n - 1
                            : shape == 2 && random.nextInt(4) == 0;
                    if (edge[parent][child] && random.nextBoolean()) {
                        bytes[parent][child] = 1000000L * (1 + random.nextInt(6));
                    }
                }
            }
        }

        boolean compatible(int task, int vm) {
            return pes[task] <= vmPes[vm];
        }
    }

    private static final class Reference {
        final double[][] work;
        final double[][] stageIn;
        final double[][] oct;
        final double[] meanWork;
        final double[] upward;
        final double[] downward;
        final double[] rankOct;
        final double[] start;
        final double[] finish;
        final int[] vm;
        final int[] selectionOrder;
        List<Integer> path = Collections.emptyList();
        int criticalVm = -1;

        Reference(int tasks, int vms) {
            work = new double[tasks][vms];
            stageIn = new double[tasks][vms];
            oct = new double[tasks][vms];
            meanWork = new double[tasks];
            upward = new double[tasks];
            downward = new double[tasks];
            rankOct = new double[tasks];
            start = new double[tasks];
            finish = new double[tasks];
            vm = new int[tasks];
            selectionOrder = new int[tasks];
        }
    }

    private static final class Actual {
        final BasePlanningAlgorithm planner;
        final Task[] tasks;
        final CondorVM[] vms;
        final SharedStorageDagPlanTrace trace;

        Actual(BasePlanningAlgorithm planner, Task[] tasks, CondorVM[] vms, SharedStorageDagPlanTrace trace) {
            this.planner = planner;
            this.tasks = tasks;
            this.vms = vms;
            this.trace = trace;
        }
    }

    private static class Interval {
        final double start;
        final double finish;

        Interval(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }

    private static final class Choice extends Interval {
        final int task;
        final int vm;

        Choice(int task, int vm, double start, double finish) {
            super(start, finish);
            this.task = task;
            this.vm = vm;
        }
    }
}
