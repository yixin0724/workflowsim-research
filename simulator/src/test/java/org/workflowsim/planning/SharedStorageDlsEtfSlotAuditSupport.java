package org.workflowsim.planning;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Test-only access to the existing private slot helper and real scheduling kernels. */
final class SharedStorageDlsEtfSlotAuditSupport {
    static final double BOOTSTRAP = 0.11 + 0.1;
    static final int STORAGE_MB_PER_SECOND = 8;

    private SharedStorageDlsEtfSlotAuditSupport() {
    }

    static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1000L, 10000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    static PlanningContext context(SharedStorageDagPlanner.Strategy strategy, List<CondorVM> vms) {
        PlatformProfile.Builder platform = PlatformProfile.builder("shared-dls-etf-slot-audit");
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            platform.addHost(new PlatformProfile.HostSpec(index, vm.getNumberOfPes(), vm.getMips(),
                    4096, 10000L, 1000000L));
            platform.addVm(new PlatformProfile.VmSpec(vm.getId(), vm.getMips(), vm.getNumberOfPes(),
                    512, 1000L, 10000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            platform.pinVmToHost(vm.getId(), index);
        }
        platform.storage(new PlatformProfile.StorageSpec(1000000L, STORAGE_MB_PER_SECOND));
        Parameters.PlanningAlgorithm algorithm = strategy == SharedStorageDagPlanner.Strategy.DLS
                ? Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS
                : Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF;
        SimulationConfig config = SimulationConfig.builder("shared-dls-etf-slot-audit.dax", vms.size())
                .planningAlgorithm(algorithm).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .cloudSimMinEventIntervalSeconds(0.1).build();
        return new PlanningContext(config, platform.build());
    }

    static BasePlanningAlgorithm publicPlanner(SharedStorageDagPlanner.Strategy strategy,
            List<Task> tasks, List<CondorVM> vms) {
        PlanningContext context = context(strategy, vms);
        BasePlanningAlgorithm planner = strategy == SharedStorageDagPlanner.Strategy.DLS
                ? new SharedStorageDlsPlanningAlgorithm(context)
                : new SharedStorageEtfPlanningAlgorithm(context);
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        return planner;
    }

    static SharedStorageDagPlanTrace runPublic(BasePlanningAlgorithm planner) throws Exception {
        planner.run();
        return planner instanceof SharedStorageDlsPlanningAlgorithm
                ? ((SharedStorageDlsPlanningAlgorithm) planner).getLastPlanTrace()
                : ((SharedStorageEtfPlanningAlgorithm) planner).getLastPlanTrace();
    }

    static List<Task> independentTasks(int count, boolean distinctDurations) {
        List<Task> tasks = new ArrayList<Task>();
        for (int index = 0; index < count; index++) {
            tasks.add(new Task(7 + 3 * index, distinctDurations ? 1000L * (index + 1) : 1000L));
        }
        return tasks;
    }

    static List<Task> chain(int count) {
        List<Task> tasks = independentTasks(count, false);
        for (int index = 1; index < count; index++) {
            tasks.get(index - 1).addChild(tasks.get(index));
            tasks.get(index).addParent(tasks.get(index - 1));
        }
        return tasks;
    }

    static CountingReservations rawSchedule(double[][] intervals) throws Exception {
        Class<?> type = Class.forName(SharedStorageDagPlanner.class.getName() + "$Reservation");
        Constructor<?> constructor = type.getDeclaredConstructor(double.class, double.class);
        constructor.setAccessible(true);
        CountingReservations result = new CountingReservations();
        for (double[] interval : intervals) {
            result.add(constructor.newInstance(interval[0], interval[1]));
        }
        return result;
    }

    static double rawSlot(List<?> schedule, double ready, double duration) throws Exception {
        return ((Double) invoke(null, "earliestStart",
                new Class<?>[] {List.class, double.class, double.class}, schedule, ready, duration)).doubleValue();
    }

    /** Frozen c62c6b8 slot semantics, including zero-width and sub-ULP boundaries. */
    static double originalSlot(double[][] sortedIntervals, double ready, double duration) {
        double start = ready;
        for (double[] interval : sortedIntervals) {
            if (start + duration <= interval[0]) {
                return start;
            }
            start = Math.max(start, interval[1]);
        }
        return start;
    }

    static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static Long optionalBits(Double value) {
        return value == null ? null : Long.valueOf(bits(value.doubleValue()));
    }

    /** Complete plan projection, not just makespan or VM assignments. */
    static List<Object> snapshot(SharedStorageDagPlanTrace trace) {
        List<Object> values = new ArrayList<Object>();
        values.add(trace.getStrategy());
        values.add(bits(trace.getStageInFinishSeconds()));
        values.add(new ArrayList<Integer>(trace.getTaskPlans().keySet()));
        values.add(trace.getCriticalPathTaskIds());
        values.add(trace.getCriticalProcessorVmId());
        for (SharedStorageDagPlanTrace.TaskPlan plan : trace.getTaskPlans().values()) {
            values.add(plan.getTaskId());
            values.add(plan.getVmId());
            values.add(bits(plan.getUpwardRankSeconds()));
            values.add(optionalBits(plan.getCpopDownwardRankSeconds()));
            values.add(optionalBits(plan.getCpopPrioritySeconds()));
            values.add(optionalBits(plan.getDlsDynamicLevelAtSelection()));
            values.add(plan.getDlsSelectionOrder());
            values.add(optionalBits(plan.getEtfEarliestStartAtSelection()));
            values.add(plan.getEtfSelectionOrder());
            values.add(optionalBits(plan.getPeftRankOctSeconds()));
            values.add(optionalBits(plan.getPeftOptimisticCostAtSelectedVmSeconds()));
            values.add(optionalBits(plan.getPeftEftPlusOptimisticCostSeconds()));
            values.add(plan.getPeftSelectionOrder());
            values.add(bits(plan.getPlannedStartSeconds()));
            values.add(bits(plan.getPlannedFinishSeconds()));
            values.add(bits(plan.getPlannedDataStageInSeconds()));
            values.add(bits(plan.getPlannedComputeStartSeconds()));
        }
        return values;
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments)
            throws Exception {
        Method method = SharedStorageDagPlanner.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Counts actual iterator passes/next calls in the unchanged slot helper. ArrayList.sort uses
     * its array, not this iterator: sorting is deliberately NOT claimed as measured slot work.
     */
    static final class CountingReservations extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        long scans;
        long visits;
        long sorts;

        @Override
        public Iterator<Object> iterator() {
            scans++;
            final Iterator<Object> source = super.iterator();
            return new Iterator<Object>() {
                @Override
                public boolean hasNext() {
                    return source.hasNext();
                }

                @Override
                public Object next() {
                    Object value = source.next();
                    visits++;
                    return value;
                }

                @Override
                public void remove() {
                    source.remove();
                }
            };
        }

        @Override
        public void sort(Comparator<? super Object> comparator) {
            sorts++;
            super.sort(comparator);
        }
    }

    /**
     * Real scheduleDls/scheduleEtf execution after bounded reflective preparation, NOT a public
     * run() count and NOT a replay of an emulated number of helper calls. Kernel fixtures supply
     * tasks in forward topological/ID order; public-run tests separately cover normal preparation.
     */
    static final class Kernel {
        final SharedStorageDagPlanner.Strategy strategy;
        final List<Task> tasks;
        final List<CondorVM> vms;
        final List<CountingReservations> counters = new ArrayList<CountingReservations>();
        private final Object planner;
        private int artificialReservations;

        @SuppressWarnings("unchecked")
        Kernel(SharedStorageDagPlanner.Strategy strategy, List<Task> tasks, List<CondorVM> vms)
                throws Exception {
            this.strategy = strategy;
            this.tasks = tasks;
            this.vms = vms;
            Constructor<?> constructor = SharedStorageDagPlanner.class.getDeclaredConstructor(
                    List.class, List.class, PlanningContext.class, SharedStorageDagPlanner.Strategy.class);
            constructor.setAccessible(true);
            planner = constructor.newInstance(tasks, vms, context(strategy, vms), strategy);
            invoke(planner, "populateExecutionTimes", new Class<?>[] {List.class, List.class}, tasks, vms);
            for (int index = tasks.size() - 1; index >= 0; index--) {
                invoke(planner, "upwardRank", new Class<?>[] {Task.class}, tasks.get(index));
            }
            Map<CondorVM, List<Object>> reservations = (Map<CondorVM, List<Object>>) field(planner, "reservations");
            for (CondorVM vm : vms) {
                CountingReservations schedule = new CountingReservations();
                reservations.put(vm, schedule);
                counters.add(schedule);
            }
        }

        SharedStorageDagPlanTrace schedule() throws Exception {
            invoke(planner, strategy == SharedStorageDagPlanner.Strategy.DLS ? "scheduleDls" : "scheduleEtf",
                    new Class<?>[] {List.class, List.class, double.class}, tasks, vms, BOOTSTRAP);
            return (SharedStorageDagPlanTrace) invoke(planner, "trace",
                    new Class<?>[] {List.class, double.class, List.class, CondorVM.class},
                    tasks, BOOTSTRAP, Collections.emptyList(), null);
        }

        double candidateMetric(Task task, CondorVM vm, double ready) throws Exception {
            Object candidate = invoke(planner,
                    strategy == SharedStorageDagPlanner.Strategy.DLS ? "nextDlsCandidate" : "nextEtfCandidate",
                    new Class<?>[] {List.class, List.class, Set.class, double.class},
                    Collections.singletonList(task), Collections.singletonList(vm), Collections.emptySet(), ready);
            if (candidate == null || field(candidate, "task") != task || field(candidate, "vm") != vm) {
                throw new AssertionError("Single compatible root/VM query must select that pair");
            }
            return ((Double) field(candidate,
                    strategy == SharedStorageDagPlanner.Strategy.DLS ? "dynamicLevel" : "earliestStart")).doubleValue();
        }

        void reserve(CondorVM vm, double start, double finish) throws Exception {
            Task marker = new Task(Integer.MAX_VALUE - artificialReservations++, 1L);
            invoke(planner, "reserve", new Class<?>[] {Task.class, CondorVM.class, double.class, double.class},
                    marker, vm, start, finish);
        }

        long visits() {
            long result = 0L;
            for (CountingReservations counter : counters) {
                result += counter.visits;
            }
            return result;
        }

        long scans() {
            long result = 0L;
            for (CountingReservations counter : counters) {
                result += counter.scans;
            }
            return result;
        }
    }
}
