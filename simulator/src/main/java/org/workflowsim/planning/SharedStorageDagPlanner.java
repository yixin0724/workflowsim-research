package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Consts;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.utils.SimulationTiming;

/**
 * 维护的共享存储静态 DAG 规划器的共用计算与执行模型对齐逻辑。
 *
 * <p>所有策略都按 Task/VM ID 取得稳定输入顺序，先验证 DAG 和受控规划上下文，再使用
 * 兼容 VM 上的执行时长、有效 stage-in 时长和每 VM 保留区计算计划。这里的时间是模型内
 * 估计量，不是生产执行 trace 或网络校准排程。</p>
 */
final class SharedStorageDagPlanner {

    enum Strategy {
        HEFT,
        CPOP,
        DLS,
        ETF,
        PEFT
    }

    private static final long STAGE_IN_JOB_LENGTH_MI = 110L;
    private static final double RANK_TOLERANCE = 1.0e-9;

    private final PlanningContext context;
    private final Strategy strategy;
    private final List<Task> sourceTasks;
    private final List rawVms;
    private final Map<Task, Map<CondorVM, Double>> executionTimes =
            new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Map<CondorVM, Double>> effectiveStageInTimes =
            new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Double> upwardRanks = new HashMap<Task, Double>();
    private final Map<Task, Double> downwardRanks = new HashMap<Task, Double>();
    private final Map<Task, Double> dlsDynamicLevels = new HashMap<Task, Double>();
    private final Map<Task, Integer> dlsSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Double> etfEarliestStarts = new HashMap<Task, Double>();
    private final Map<Task, Integer> etfSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Map<CondorVM, Double>> peftOptimisticCosts =
            new HashMap<Task, Map<CondorVM, Double>>();
    private final Map<Task, Double> peftRankOct = new HashMap<Task, Double>();
    private final Map<Task, Double> peftSelectionScores = new HashMap<Task, Double>();
    private final Map<Task, Integer> peftSelectionOrders = new HashMap<Task, Integer>();
    private final Map<Task, Double> starts = new HashMap<Task, Double>();
    private final Map<Task, Double> finishes = new HashMap<Task, Double>();
    private final Map<CondorVM, List<Reservation>> reservations =
            new HashMap<CondorVM, List<Reservation>>();

    private SharedStorageDagPlanner(List<Task> sourceTasks, List rawVms,
            PlanningContext context, Strategy strategy) {
        this.sourceTasks = sourceTasks;
        this.rawVms = rawVms;
        this.context = context;
        this.strategy = strategy;
    }

    static SharedStorageDagPlanTrace plan(List<Task> sourceTasks, List rawVms, PlanningContext context,
            Strategy strategy) {
        if (context == null || strategy == null) {
            throw new IllegalArgumentException("Shared-storage DAG planning requires context and strategy");
        }
        return new SharedStorageDagPlanner(sourceTasks, rawVms, context, strategy).run();
    }

    private SharedStorageDagPlanTrace run() {
        context.validateSharedStorageStaticDag();
        List<Task> tasks = sortedTasks();
        List<CondorVM> vms = sortedVms();
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        populateExecutionTimes(tasks, vms);
        for (CondorVM vm : vms) {
            reservations.put(vm, new ArrayList<Reservation>());
        }
        for (Task task : tasks) {
            upwardRank(task);
        }

        // stage-in Job 从零时刻开始，完成时刻遵循 WorkflowDatacenter 相同的最小事件和
        // 安全量子规则；工作流引擎收到完成事件后再经过一个内核间隔释放根计算 Job。
        double stageInFinish = SimulationTiming.earliestCloudletCompletionTime(0.0,
                STAGE_IN_JOB_LENGTH_MI / vms.get(0).getMips(),
                context.getCloudSimMinEventIntervalSeconds())
                + context.getCloudSimMinEventIntervalSeconds();
        List<Task> criticalPath = Collections.emptyList();
        CondorVM criticalProcessor = null;
        if (strategy == Strategy.HEFT) {
            scheduleHeft(tasks, vms, stageInFinish);
        } else if (strategy == Strategy.CPOP) {
            CpopSelection selection = scheduleCpop(tasks, vms, stageInFinish);
            criticalPath = selection.criticalPath;
            criticalProcessor = selection.criticalProcessor;
        } else if (strategy == Strategy.DLS) {
            scheduleDls(tasks, vms, stageInFinish);
        } else if (strategy == Strategy.ETF) {
            scheduleEtf(tasks, vms, stageInFinish);
        } else {
            schedulePeft(tasks, vms, stageInFinish);
        }
        return trace(tasks, stageInFinish, criticalPath, criticalProcessor);
    }

    private List<Task> sortedTasks() {
        if (sourceTasks == null || sourceTasks.isEmpty()) {
            throw new IllegalArgumentException(strategy + " requires at least one task");
        }
        List<Task> tasks = new ArrayList<Task>(sourceTasks);
        Collections.sort(tasks, taskIdComparator());
        return tasks;
    }

    private List<CondorVM> sortedVms() {
        if (rawVms == null || rawVms.isEmpty()) {
            throw new IllegalArgumentException(strategy + " requires at least one VM");
        }
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (Object object : rawVms) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(strategy + " requires CondorVM instances");
            }
            CondorVM vm = (CondorVM) object;
            if (vm.getMips() <= 0.0 || Double.isNaN(vm.getMips()) || Double.isInfinite(vm.getMips())) {
                throw new IllegalArgumentException(strategy + " requires finite positive VM MIPS");
            }
            vms.add(vm);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return vms;
    }

    private void populateExecutionTimes(List<Task> tasks, List<CondorVM> vms) {
        for (Task task : tasks) {
            double transferSeconds = taskTransferSeconds(task);
            Map<CondorVM, Double> byVm = new HashMap<CondorVM, Double>();
            Map<CondorVM, Double> stageInByVm = new HashMap<CondorVM, Double>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() > vm.getNumberOfPes()) {
                    byVm.put(vm, Double.POSITIVE_INFINITY);
                    stageInByVm.put(vm, Double.POSITIVE_INFINITY);
                    continue;
                }
                long transferMi = (long) (vm.getMips() * transferSeconds);
                // CloudSim 多 PE 任务并行执行：实际时间 = (length + transferMi/mips) / capacity
                // 而非串行 (totalLength + transferMi) / mips。使用单 PE 长度与运行时一致。
                byVm.put(vm, (task.getCloudletLength() + transferMi) / vm.getMips());
                stageInByVm.put(vm, transferMi / vm.getMips());
            }
            executionTimes.put(task, byVm);
            effectiveStageInTimes.put(task, stageInByVm);
        }
    }

    private double taskTransferSeconds(Task task) {
        double seconds = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList())) {
                seconds += file.getSize() / (double) Consts.MILLION
                        / context.getSharedStorageTransferRateMbPerSecond();
            }
        }
        return seconds;
    }

    private void scheduleHeft(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        List<Task> ranked = new ArrayList<Task>(tasks);
        Collections.sort(ranked, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byRank = Double.compare(upwardRanks.get(second), upwardRanks.get(first));
                return byRank != 0 ? byRank
                        : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        for (Task task : ranked) {
            allocate(task, vms, stageInFinish, null);
        }
    }

    private CpopSelection scheduleCpop(List<Task> tasks, List<CondorVM> vms,
            double stageInFinish) {
        for (Task task : tasks) {
            downwardRank(task);
        }
        List<Task> criticalPath = identifyCriticalPath(tasks);
        CondorVM criticalProcessor = selectCriticalProcessor(criticalPath, vms);
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            Task task = nextCpopReadyTask(tasks, scheduled);
            if (task == null) {
                throw new IllegalStateException("CPOP could not find a dependency-ready task");
            }
            allocate(task, vms, stageInFinish,
                    criticalPath.contains(task) ? criticalProcessor : null);
            scheduled.add(task);
        }
        return new CpopSelection(criticalPath, criticalProcessor);
    }

    /**
     * 受控模型下的 DLS 适配。
     *
     * <p>动态层为依赖已满足 Task-VM 对的静态 b-level（本实现的向上 rank）减去最早插入
     * 开始时间。本共享存储模型没有互连拓扑或链路保留，故不向该估计加入通信项。</p>
     */
    private void scheduleDls(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            DlsCandidate selected = nextDlsCandidate(tasks, vms, scheduled, stageInFinish);
            if (selected == null) {
                throw new IllegalStateException("DLS could not find a dependency-ready Task-VM pair");
            }
            allocate(selected.task, vms, stageInFinish, selected.vm);
            dlsDynamicLevels.put(selected.task, selected.dynamicLevel);
            dlsSelectionOrders.put(selected.task, scheduled.size() + 1);
            scheduled.add(selected.task);
        }
    }

    private DlsCandidate nextDlsCandidate(List<Task> tasks, List<CondorVM> vms,
            Set<Task> scheduled, double stageInFinish) {
        DlsCandidate selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            double readyTime = readyTime(task, stageInFinish);
            for (CondorVM vm : vms) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                double earliestStart = earliestStart(reservations.get(vm), readyTime, duration);
                DlsCandidate candidate = new DlsCandidate(task, vm,
                        upwardRanks.get(task).doubleValue() - earliestStart);
                if (selected == null || candidate.isBetterThan(selected)) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * 受控模型下的 ETF 适配。
     *
     * <p>ETF 选择依赖已满足 Task-VM 对中最早插入开始时间最小者；相同开始时间先按静态
     * b-level，再按稳定的 Task/VM ID 打破平局。</p>
     */
    private void scheduleEtf(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            EtfCandidate selected = nextEtfCandidate(tasks, vms, scheduled, stageInFinish);
            if (selected == null) {
                throw new IllegalStateException("ETF could not find a dependency-ready Task-VM pair");
            }
            allocate(selected.task, vms, stageInFinish, selected.vm);
            etfEarliestStarts.put(selected.task, selected.earliestStart);
            etfSelectionOrders.put(selected.task, scheduled.size() + 1);
            scheduled.add(selected.task);
        }
    }

    private EtfCandidate nextEtfCandidate(List<Task> tasks, List<CondorVM> vms,
            Set<Task> scheduled, double stageInFinish) {
        EtfCandidate selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            double readyTime = readyTime(task, stageInFinish);
            for (CondorVM vm : vms) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                double earliestStart = earliestStart(reservations.get(vm), readyTime, duration);
                EtfCandidate candidate = new EtfCandidate(task, vm, earliestStart,
                        upwardRanks.get(task).doubleValue());
                if (selected == null || candidate.isBetterThan(selected)) {
                    selected = candidate;
                }
            }
        }
        return selected;
    }

    /**
     * 受控模型下的 PEFT 适配。
     *
     * <p>OCT 记录从某 Task 的后继到出口 Task 的乐观代价。本维护的共享存储模型没有链路
     * 模型，因此原始算法的通信项为零。</p>
     */
    private void schedulePeft(List<Task> tasks, List<CondorVM> vms, double stageInFinish) {
        Set<Task> scheduled = new HashSet<Task>();
        while (scheduled.size() < tasks.size()) {
            Task selected = nextPeftReadyTask(tasks, vms, scheduled);
            if (selected == null) {
                throw new IllegalStateException("PEFT could not find a dependency-ready task");
            }
            allocatePeft(selected, vms, stageInFinish);
            peftSelectionOrders.put(selected, scheduled.size() + 1);
            scheduled.add(selected);
        }
    }

    private Task nextPeftReadyTask(List<Task> tasks, List<CondorVM> vms, Set<Task> scheduled) {
        Task selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            // 即使只有一个 ready Task 也计算其 rank，使轨迹保留完整的审计证据。
            double taskRank = peftRank(task, vms);
            if (selected == null || taskRank > peftRank(selected, vms)
                    || (Double.compare(taskRank, peftRank(selected, vms)) == 0
                    && task.getCloudletId() < selected.getCloudletId())) {
                selected = task;
            }
        }
        return selected;
    }

    private void allocatePeft(Task task, List<CondorVM> vms, double stageInFinish) {
        double readyTime = readyTime(task, stageInFinish);
        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        double selectedScore = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double duration = executionTimes.get(task).get(vm).doubleValue();
            if (Double.isInfinite(duration)) {
                continue;
            }
            double start = earliestStart(reservations.get(vm), readyTime, duration);
            double finish = start + duration;
            double score = finish + optimisticCost(task, vm, vms);
            if (score < selectedScore || (Double.compare(score, selectedScore) == 0
                    && (selectedVm == null || vm.getId() < selectedVm.getId()))) {
                selectedVm = vm;
                selectedStart = start;
                selectedFinish = finish;
                selectedScore = score;
            }
        }
        if (selectedVm == null) {
            throw new IllegalArgumentException("PEFT cannot map task " + task.getCloudletId()
                    + "; no compatible VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish);
        peftSelectionScores.put(task, selectedScore);
    }

    /**
     * {@code OCT(task, vm) = max_child min_successorVm(duration(child, successorVm)
     * + OCT(child, successorVm))}；本无拓扑共享存储模型省略的通信项为零。
     */
    private double optimisticCost(Task task, CondorVM vm, List<CondorVM> vms) {
        Map<CondorVM, Double> byVm = peftOptimisticCosts.get(task);
        if (byVm == null) {
            byVm = new HashMap<CondorVM, Double>();
            peftOptimisticCosts.put(task, byVm);
        }
        Double cached = byVm.get(vm);
        if (cached != null) {
            return cached.doubleValue();
        }
        double successorMaximum = 0.0;
        for (Task child : task.getChildList()) {
            double childMinimum = Double.POSITIVE_INFINITY;
            for (CondorVM childVm : vms) {
                double duration = executionTimes.get(child).get(childVm).doubleValue();
                if (Double.isInfinite(duration)) {
                    continue;
                }
                childMinimum = Math.min(childMinimum,
                        duration + optimisticCost(child, childVm, vms));
            }
            if (Double.isInfinite(childMinimum)) {
                throw new IllegalArgumentException("PEFT cannot map successor task "
                        + child.getCloudletId() + "; no compatible VM");
            }
            successorMaximum = Math.max(successorMaximum, childMinimum);
        }
        byVm.put(vm, successorMaximum);
        return successorMaximum;
    }

    private double peftRank(Task task, List<CondorVM> vms) {
        Double cached = peftRankOct.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double total = 0.0;
        int count = 0;
        for (CondorVM vm : vms) {
            if (!Double.isInfinite(executionTimes.get(task).get(vm).doubleValue())) {
                total += optimisticCost(task, vm, vms);
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException("PEFT cannot rank task " + task.getCloudletId()
                    + "; no compatible VM");
        }
        double result = total / count;
        peftRankOct.put(task, result);
        return result;
    }

    private double upwardRank(Task task) {
        Double cached = upwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double childMaximum = 0.0;
        for (Task child : task.getChildList()) {
            childMaximum = Math.max(childMaximum, upwardRank(child));
        }
        double result = averageExecutionTime(task) + childMaximum;
        upwardRanks.put(task, result);
        return result;
    }

    private double downwardRank(Task task) {
        Double cached = downwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double parentMaximum = 0.0;
        for (Task parent : task.getParentList()) {
            parentMaximum = Math.max(parentMaximum,
                    downwardRank(parent) + averageExecutionTime(parent));
        }
        downwardRanks.put(task, parentMaximum);
        return parentMaximum;
    }

    private double averageExecutionTime(Task task) {
        double total = 0.0;
        int count = 0;
        for (Double duration : executionTimes.get(task).values()) {
            if (!Double.isInfinite(duration.doubleValue())) {
                total += duration.doubleValue();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(strategy + " cannot map task "
                    + task.getCloudletId() + "; no compatible VM");
        }
        return total / count;
    }

    private List<Task> identifyCriticalPath(List<Task> tasks) {
        Task current = null;
        double target = Double.NEGATIVE_INFINITY;
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty()) {
                continue;
            }
            double priority = cpopPriority(task);
            if (priority > target || (Double.compare(priority, target) == 0
                    && current != null && task.getCloudletId() < current.getCloudletId())) {
                current = task;
                target = priority;
            }
        }
        if (current == null) {
            throw new IllegalStateException("CPOP requires a DAG entry task");
        }
        List<Task> path = new ArrayList<Task>();
        while (true) {
            path.add(current);
            Task next = null;
            for (Task child : current.getChildList()) {
                if (!sameRank(cpopPriority(child), target)) {
                    continue;
                }
                if (next == null || upwardRanks.get(child).doubleValue() > upwardRanks.get(next).doubleValue()
                        || (Double.compare(upwardRanks.get(child), upwardRanks.get(next)) == 0
                        && child.getCloudletId() < next.getCloudletId())) {
                    next = child;
                }
            }
            if (next == null) {
                if (!current.getChildList().isEmpty()) {
                    throw new IllegalStateException("CPOP could not continue its critical path");
                }
                return path;
            }
            current = next;
        }
    }

    private CondorVM selectCriticalProcessor(List<Task> criticalPath, List<CondorVM> vms) {
        CondorVM selected = null;
        double selectedCost = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double total = 0.0;
            for (Task task : criticalPath) {
                double duration = executionTimes.get(task).get(vm).doubleValue();
                if (Double.isInfinite(duration)) {
                    total = Double.POSITIVE_INFINITY;
                    break;
                }
                total += duration;
            }
            if (total < selectedCost || (Double.compare(total, selectedCost) == 0
                    && selected != null && vm.getId() < selected.getId())) {
                selected = vm;
                selectedCost = total;
            }
        }
        if (selected == null || Double.isInfinite(selectedCost)) {
            throw new IllegalArgumentException("CPOP cannot assign the critical path to one compatible VM");
        }
        return selected;
    }

    private Task nextCpopReadyTask(List<Task> tasks, Set<Task> scheduled) {
        Task selected = null;
        for (Task task : tasks) {
            if (scheduled.contains(task) || !scheduled.containsAll(task.getParentList())) {
                continue;
            }
            if (selected == null || cpopPriority(task) > cpopPriority(selected)
                    || (Double.compare(cpopPriority(task), cpopPriority(selected)) == 0
                    && task.getCloudletId() < selected.getCloudletId())) {
                selected = task;
            }
        }
        return selected;
    }

    private double cpopPriority(Task task) {
        return upwardRanks.get(task).doubleValue() + downwardRanks.get(task).doubleValue();
    }

    private static boolean sameRank(double first, double second) {
        return Math.abs(first - second) <= RANK_TOLERANCE * Math.max(1.0, Math.abs(second));
    }

    private void allocate(Task task, List<CondorVM> vms, double stageInFinish,
            CondorVM forcedVm) {
        double readyTime = readyTime(task, stageInFinish);

        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            if (forcedVm != null && vm != forcedVm) {
                continue;
            }
            double duration = executionTimes.get(task).get(vm).doubleValue();
            if (Double.isInfinite(duration)) {
                continue;
            }
            double start = earliestStart(reservations.get(vm), readyTime, duration);
            double finish = start + duration;
            if (finish < selectedFinish || (Double.compare(finish, selectedFinish) == 0
                    && selectedVm != null && vm.getId() < selectedVm.getId())) {
                selectedVm = vm;
                selectedStart = start;
                selectedFinish = finish;
            }
        }
        if (selectedVm == null) {
            throw new IllegalArgumentException(strategy + " cannot map task "
                    + task.getCloudletId() + "; no compatible VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish);
    }

    private void reserve(Task task, CondorVM vm, double start, double finish) {
        reservations.get(vm).add(new Reservation(start, finish));
        Collections.sort(reservations.get(vm), new Comparator<Reservation>() {
            @Override
            public int compare(Reservation first, Reservation second) {
                int byStart = Double.compare(first.start, second.start);
                return byStart != 0 ? byStart : Double.compare(first.finish, second.finish);
            }
        });
        starts.put(task, start);
        finishes.put(task, finish);
        task.setVmId(vm.getId());
        task.setStaticScheduleStartTime(start);
    }

    private double readyTime(Task task, double stageInFinish) {
        double readyTime = task.getParentList().isEmpty() ? stageInFinish : 0.0;
        for (Task parent : task.getParentList()) {
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(strategy + " priority order did not schedule parent "
                        + parent.getCloudletId());
            }
            readyTime = Math.max(readyTime, parentFinish.doubleValue());
        }
        return readyTime;
    }

    private static double earliestStart(List<Reservation> schedule, double readyTime, double duration) {
        double start = readyTime;
        for (Reservation reservation : schedule) {
            if (start + duration <= reservation.start) {
                return start;
            }
            start = Math.max(start, reservation.finish);
        }
        return start;
    }

    private static Comparator<Task> taskIdComparator() {
        return new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        };
    }

    private SharedStorageDagPlanTrace trace(List<Task> tasks, double stageInFinish,
            List<Task> criticalPath, CondorVM criticalProcessor) {
        Map<Integer, SharedStorageDagPlanTrace.TaskPlan> plans =
                new java.util.LinkedHashMap<Integer, SharedStorageDagPlanTrace.TaskPlan>();
        for (Task task : tasks) {
            Double downward = strategy == Strategy.CPOP ? downwardRanks.get(task) : null;
            Double priority = strategy == Strategy.CPOP ? cpopPriority(task) : null;
            Double dynamicLevel = strategy == Strategy.DLS ? dlsDynamicLevels.get(task) : null;
            Integer selectionOrder = strategy == Strategy.DLS ? dlsSelectionOrders.get(task) : null;
            Double etfEarliestStart = strategy == Strategy.ETF ? etfEarliestStarts.get(task) : null;
            Integer etfSelectionOrder = strategy == Strategy.ETF ? etfSelectionOrders.get(task) : null;
            Double peftRank = strategy == Strategy.PEFT ? peftRankOct.get(task) : null;
            Double peftOct = strategy == Strategy.PEFT
                    ? peftOptimisticCosts.get(task).get(vmById(task.getVmId())) : null;
            Double peftScore = strategy == Strategy.PEFT ? peftSelectionScores.get(task) : null;
            Integer peftSelectionOrder = strategy == Strategy.PEFT ? peftSelectionOrders.get(task) : null;
            plans.put(task.getCloudletId(), new SharedStorageDagPlanTrace.TaskPlan(
                    task.getCloudletId(), task.getVmId(), upwardRanks.get(task), downward,
                    priority, dynamicLevel, selectionOrder, etfEarliestStart, etfSelectionOrder,
                    peftRank, peftOct, peftScore, peftSelectionOrder,
                    starts.get(task), finishes.get(task),
                    effectiveStageInTimes.get(task).get(vmById(task.getVmId()))));
        }
        List<Integer> criticalTaskIds = new ArrayList<Integer>();
        for (Task task : criticalPath) {
            criticalTaskIds.add(task.getCloudletId());
        }
        return new SharedStorageDagPlanTrace(strategy.name(), stageInFinish, plans,
                criticalTaskIds, criticalProcessor == null ? null : criticalProcessor.getId());
    }

    private CondorVM vmById(int vmId) {
        for (CondorVM vm : reservations.keySet()) {
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalStateException("Static DAG plan has no VM " + vmId);
    }

    private static final class CpopSelection {
        private final List<Task> criticalPath;
        private final CondorVM criticalProcessor;

        private CpopSelection(List<Task> criticalPath, CondorVM criticalProcessor) {
            this.criticalPath = criticalPath;
            this.criticalProcessor = criticalProcessor;
        }
    }

    private static final class DlsCandidate {
        private final Task task;
        private final CondorVM vm;
        private final double dynamicLevel;

        private DlsCandidate(Task task, CondorVM vm, double dynamicLevel) {
            this.task = task;
            this.vm = vm;
            this.dynamicLevel = dynamicLevel;
        }

        private boolean isBetterThan(DlsCandidate other) {
            int byDynamicLevel = Double.compare(dynamicLevel, other.dynamicLevel);
            if (byDynamicLevel != 0) {
                return byDynamicLevel > 0;
            }
            int byTaskId = Integer.compare(task.getCloudletId(), other.task.getCloudletId());
            return byTaskId != 0 ? byTaskId < 0 : vm.getId() < other.vm.getId();
        }
    }

    private static final class EtfCandidate {
        private final Task task;
        private final CondorVM vm;
        private final double earliestStart;
        private final double staticBLevel;

        private EtfCandidate(Task task, CondorVM vm, double earliestStart, double staticBLevel) {
            this.task = task;
            this.vm = vm;
            this.earliestStart = earliestStart;
            this.staticBLevel = staticBLevel;
        }

        private boolean isBetterThan(EtfCandidate other) {
            int byEarliestStart = Double.compare(earliestStart, other.earliestStart);
            if (byEarliestStart != 0) {
                return byEarliestStart < 0;
            }
            int byStaticBLevel = Double.compare(staticBLevel, other.staticBLevel);
            if (byStaticBLevel != 0) {
                return byStaticBLevel > 0;
            }
            int byTaskId = Integer.compare(task.getCloudletId(), other.task.getCloudletId());
            return byTaskId != 0 ? byTaskId < 0 : vm.getId() < other.vm.getId();
        }
    }

    private static final class Reservation {
        private final double start;
        private final double finish;

        private Reservation(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }
}
