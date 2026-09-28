package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.SimulationConstants;
import org.workflowsim.utils.SimulationTiming;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * LOCAL 文件系统通信感知静态 DAG 规划器（Topcuoglu 族列表调度）的共享机制基类。
 *
 * <p>承载 LOCAL_HEFT、LOCAL_CPOP 与 LOCAL_PEFT 的共用无争用估计：任务×兼容 VM
 * 的整数 MI 成本折算、向上 rank、带可用时刻的副本目录、按父分组的执行前传输与
 * 插入式计算预留。输入传输与 VM 忙碌期可重叠；外部输入从依赖就绪时点传输；输入
 * 副本在整个 hold 完成时可用，输出副本在计算完成时可用。候选只读取其依赖就绪
 * 时点已经可用的已规划副本，不把分配顺序误当作时间顺序。</p>
 *
 * <p>这是基于当前部分计划的估计，不完整重放运行期事件。同刻事件次序、后规划任务
 * 更早产生副本、短计算完成钳制以及争用模型仍可能使计划与运行时不同；详细边界见
 * {@link LocalHeftPlanningAlgorithm}。</p>
 *
 * <p><b>适用前提</b>（由 {@link PlanningContext#validateLocalStaticDag()} 强制）：
 * LOCAL 文件系统、NONE 聚类、无故障、无建模开销、{@code preExecutionTransferDelayV1}
 * 数据移动模型与 SPACE_SHARED VM。子类的 {@code run()} 先调 {@link #prepare()}，
 * 再按各自的优先级规则依次 {@link #allocate}。</p>
 */
abstract class AbstractLocalCommPlanningAlgorithm extends BasePlanningAlgorithm {

    private final PlanningContext context;
    /** 算法标签（如 "LOCAL_HEFT"），用于诊断消息。 */
    private final String label;

    /** vmId → VM。 */
    private final Map<Integer, CondorVM> vmById = new LinkedHashMap<Integer, CondorVM>();
    /** 按 VM ID 升序的 VM 列表（{@link #prepare()} 后可用）。 */
    private final List<CondorVM> vms = new ArrayList<CondorVM>();
    /** 任务 → (兼容 VM → 整数 MI 对应的有效计算秒数)。 */
    private final Map<Task, Map<CondorVM, Double>> computeSecondsByVm =
            new HashMap<Task, Map<CondorVM, Double>>();
    /** 文件名 → (站点 → 副本最早可用时刻)，站点为 SOURCE 或 VM ID 字符串。 */
    private final Map<String, Map<String, Double>> replicas =
            new LinkedHashMap<String, Map<String, Double>>();
    /** 文件名 → 大小（字节）。 */
    private final Map<String, Double> fileSizes = new LinkedHashMap<String, Double>();
    /** 任务 → 向上 rank。 */
    private final Map<Task, Double> upwardRanks = new HashMap<Task, Double>();
    /** VM → 已预留区间。 */
    private final Map<CondorVM, List<Reservation>> reservations = new LinkedHashMap<CondorVM, List<Reservation>>();
    /** 任务 → 计划完成时刻。 */
    private final Map<Task, Double> finishes = new HashMap<Task, Double>();

    AbstractLocalCommPlanningAlgorithm(String label, PlanningContext context) {
        if (context == null) {
            throw new IllegalArgumentException(label
                    + " requires a PlanningContext from SimulationRunner");
        }
        this.label = label;
        this.context = context;
    }

    /** 算法标签，用于子类诊断消息。 */
    final String label() {
        return label;
    }

    /**
     * 前置校验 + 确定性排序 + 成本/副本装配 + 全量向上 rank 计算。
     *
     * @return 按任务 ID 升序的任务列表
     */
    final List<Task> prepare() {
        context.validateLocalStaticDag();
        List<Task> tasks = sortedTasks();
        sortedVms();
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        // 同一规划器可再次运行；rank、已完成状态及演进后的副本都只属于上一次计划。
        computeSecondsByVm.clear();
        replicas.clear();
        fileSizes.clear();
        upwardRanks.clear();
        finishes.clear();
        populateCosts(tasks);
        populateReplicasAndSizes(tasks);
        for (Task task : tasks) {
            upwardRank(task);
        }
        return tasks;
    }

    /** 按 VM ID 升序的 VM 列表（{@link #prepare()} 之后可用）。 */
    final List<CondorVM> vms() {
        return vms;
    }

    /** 任务的向上 rank（{@link #prepare()} 之后可用）。 */
    final double upwardRankOf(Task task) {
        return upwardRanks.get(task).doubleValue();
    }

    /** 任务已计划的完成时刻；未计划返回 null。 */
    final Double plannedFinishOf(Task task) {
        return finishes.get(task);
    }

    /** 任务在指定 VM 上的有效计算秒数；PE 不兼容时返回正无穷。 */
    final double computeSecondsOn(Task task, CondorVM vm) {
        Double seconds = computeSecondsByVm.get(task).get(vm);
        return seconds == null ? Double.POSITIVE_INFINITY : seconds.doubleValue();
    }

    final boolean isCompatible(Task task, CondorVM vm) {
        return task.getNumberOfPes() <= vm.getNumberOfPes();
    }

    /**
     * 根任务等待模型 stage-in Job：与 SHARED_STORAGE_* 规划器一致，stage-in Job
     * 在 fallback VM（最小 VM ID）上执行 110 MI，完成时刻经最小事件间隔规则对齐，
     * 引擎收到完成事件后再经过一个内核间隔释放根计算 Job。
     */
    final double stageInFinishTime() {
        return SimulationTiming.earliestCloudletCompletionTime(0.0,
                SimulationConstants.STAGE_IN_JOB_LENGTH_MI / vms.get(0).getMips(),
                context.getCloudSimMinEventIntervalSeconds())
                + context.getCloudSimMinEventIntervalSeconds();
    }

    /** 任务的就绪时刻 = 全部父任务计划完成时刻的最大值；根任务 = stage-in 完成时刻。 */
    final double readyTime(Task task, double stageInFinish) {
        double readyTime = task.getParentList().isEmpty() ? stageInFinish : 0.0;
        for (Task parent : task.getParentList()) {
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(label + " priority order did not schedule parent "
                        + parent.getCloudletId() + " before task " + task.getCloudletId());
            }
            if (parentFinish.doubleValue() > readyTime) {
                readyTime = parentFinish.doubleValue();
            }
        }
        return readyTime;
    }

    /**
     * 以依赖就绪时点的可见副本估算整个输入 hold 的完成时刻。
     *
     * <p>保留无争用运行模型的按父分组语义：同父文件串行累加，不同父任务并行，
     * 候选到达时刻为 {@code finish(pred) + Σ c_file(pred, vm)}。外部输入从本任务
     * 依赖就绪时刻开始传输；stage-in 的平台级登记不表示根任务目标 VM 已有文件。
     * 正 hold 按同一最小事件间隔钳制，输入副本到整个 hold 完成后才对后续估计可见。</p>
     *
     * @param task 待分配任务
     * @param vm 目标 VM
     * @param dependencyReady 全部父任务已完成的时刻；根任务为 bootstrap 时刻
     * @return 全部输入 hold 完成、计算可派发的时刻，不含 VM 排队
     */
    private double dataArrivalOn(Task task, CondorVM vm, double dependencyReady) {
        double arrival = dependencyReady;
        LinkedHashSet<String> attributedNames = new LinkedHashSet<String>();
        for (Task parent : task.getParentList()) {
            LinkedHashSet<String> parentOutputs = new LinkedHashSet<String>();
            for (FileItem file : parent.getFileList()) {
                if (file.getType() == FileType.OUTPUT) {
                    parentOutputs.add(file.getName());
                }
            }
            double secondsFromParent = 0.0;
            boolean hasFilesFromParent = false;
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())
                        && parentOutputs.contains(file.getName())) {
                    hasFilesFromParent = true;
                    attributedNames.add(file.getName());
                    secondsFromParent += fileStageInSeconds(file, vm, dependencyReady);
                }
            }
            if (!hasFilesFromParent) {
                continue;
            }
            Double parentFinish = finishes.get(parent);
            if (parentFinish == null) {
                throw new IllegalStateException(label + " priority order did not schedule parent "
                        + parent.getCloudletId() + " before task " + task.getCloudletId());
            }
            arrival = Math.max(arrival, parentFinish.doubleValue() + secondsFromParent);
        }
        double externalSeconds = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())
                    && !attributedNames.contains(file.getName())) {
                externalSeconds += fileStageInSeconds(file, vm, dependencyReady);
            }
        }
        arrival = Math.max(arrival, dependencyReady + externalSeconds);
        double hold = arrival - dependencyReady;
        return hold > 0.0
                ? dependencyReady + Math.max(hold, context.getCloudSimMinEventIntervalSeconds())
                : dependencyReady;
    }

    /**
     * 将任务分配到最早完成的候选 VM（插入式 EFT），并演进副本状态。
     *
     * <p>执行前传输延迟语义：数据到达时刻为每个父任务并行传输的最晚到达；VM 预留区间
     * 只覆盖计算（传输可与 VM 忙碌期重叠）。开始时刻为
     * {@code max(avail[vm], dataArrival)} 经插入式搜索。</p>
     *
     * @param pinnedVm 非 null 时只考虑该 VM（CPOP 的关键路径处理器绑定）；
     *                 null 时在全部 VM 中取最小 EFT，平局取较小 VM ID
     * @return 计划完成时刻
     */
    final double allocate(Task task, CondorVM pinnedVm, double stageInFinish) {
        return allocateInternal(task, pinnedVm, stageInFinish, null);
    }

    /**
     * 将任务分配到乐观完成时间最小的 VM（插入式 {@code EFT + OCT(t, vm)}），
     * 并演进副本状态。
     *
     * <p>除评分函数外与 {@link #allocate(Task, CondorVM, double)} 完全一致：相同的
     * 可派发时刻、数据到达时刻、插入式最早开始搜索、预留与副本演进语义；返回的仍是
     * EFT 完成时刻（不含 OCT 项）。评分平局取较小 VM ID。</p>
     *
     * @param octPerVm 任务在全部 VM 上的 OCT 值；缺任一 VM 抛
     *                 {@link IllegalStateException}
     * @return 计划完成时刻（EFT，不含 OCT）
     */
    final double allocateOptimistic(Task task, Map<CondorVM, Double> octPerVm, double stageInFinish) {
        if (octPerVm == null) {
            throw new IllegalArgumentException(label + " requires per-VM OCT values for task "
                    + task.getCloudletId());
        }
        return allocateInternal(task, null, stageInFinish, octPerVm);
    }

    private double allocateInternal(Task task, CondorVM pinnedVm, double stageInFinish,
            Map<CondorVM, Double> octPerVm) {
        // 运行时镜像：Job 在全部父任务返回后才被释放，因此可派发时刻为
        // max(全部父完成时刻, 数据到达时刻)。
        double dispatchable = readyTime(task, stageInFinish);
        List<CondorVM> candidates = pinnedVm != null
                ? Collections.singletonList(pinnedVm) : vms;
        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedInputReady = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        double selectedScore = Double.POSITIVE_INFINITY;
        for (CondorVM vm : candidates) {
            if (!isCompatible(task, vm)) {
                continue;
            }
            double arrival = dataArrivalOn(task, vm, dispatchable);
            double duration = computeSecondsOn(task, vm);
            double start = earliestStart(reservations.get(vm), arrival, duration);
            double finish = start + duration;
            double score = finish;
            if (octPerVm != null) {
                Double oct = octPerVm.get(vm);
                if (oct == null) {
                    throw new IllegalStateException(label + " is missing OCT for task "
                            + task.getCloudletId() + " on VM " + vm.getId());
                }
                score = finish + oct.doubleValue();
            }
            if (score < selectedScore || (Double.compare(score, selectedScore) == 0
                    && selectedVm != null && vm.getId() < selectedVm.getId())) {
                selectedVm = vm;
                selectedStart = start;
                selectedInputReady = arrival;
                selectedFinish = finish;
                selectedScore = score;
            }
        }
        if (selectedVm == null) {
            throw new IllegalStateException(label + " cannot map task " + task.getCloudletId()
                    + "; no candidate VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish, selectedInputReady);
        return selectedFinish;
    }

    private void populateCosts(List<Task> tasks) {
        for (Task task : tasks) {
            Map<CondorVM, Double> perVm = new LinkedHashMap<CondorVM, Double>();
            for (CondorVM vm : vms) {
                if (isCompatible(task, vm)) {
                    perVm.put(vm, Double.valueOf(TaskExecutionModel.executionSeconds(
                            task, vm.getId(), vm.getMips())));
                }
            }
            if (perVm.isEmpty()) {
                throw new IllegalArgumentException(label + " cannot plan task " + task.getCloudletId()
                        + "; no compatible VM has sufficient processing elements");
            }
            computeSecondsByVm.put(task, perVm);
        }
    }

    private void populateReplicasAndSizes(List<Task> tasks) {
        // 全局 OUTPUT 名称集合：决定哪些 INPUT 是真实外部输入（解析期由 ClusteringEngine
        // 注册到 SOURCE），哪些是中间文件（仅由产出任务的完成注册）。
        LinkedHashSet<String> outputNames = new LinkedHashSet<String>();
        for (Task task : tasks) {
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.OUTPUT) {
                    outputNames.add(file.getName());
                }
                Double previous = fileSizes.put(file.getName(), Double.valueOf(file.getSize()));
                if (previous != null && Double.compare(previous.doubleValue(), file.getSize()) != 0) {
                    throw new IllegalStateException("File '" + file.getName()
                            + "' has inconsistent size declarations: " + previous + " vs "
                            + file.getSize());
                }
            }
        }
        for (Task task : tasks) {
            for (FileItem file : task.getFileList()) {
                Map<String, Double> sites = replicas.get(file.getName());
                if (sites == null) {
                    sites = new LinkedHashMap<String, Double>();
                    replicas.put(file.getName(), sites);
                }
                if (file.getType() == FileType.INPUT && !outputNames.contains(file.getName())) {
                    // SOURCE 自始可用；到目标 VM 的传输仍须等消费 Job 依赖就绪后开始。
                    sites.put(Parameters.SOURCE, Double.valueOf(0.0));
                }
            }
        }
    }

    private double upwardRank(Task task) {
        Double cached = upwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double rank = meanComputeSeconds(task);
        if (!task.getChildList().isEmpty()) {
            double bestChild = 0.0;
            for (Task child : task.getChildList()) {
                double via = meanCommunicationSeconds(task, child) + upwardRank(child);
                if (via > bestChild) {
                    bestChild = via;
                }
            }
            rank += bestChild;
        }
        upwardRanks.put(task, Double.valueOf(rank));
        return rank;
    }

    /** 任务在全部兼容 VM 上有效计算秒数的平均值（w̄），不把不可行候选的无穷值纳入 rank。 */
    final double meanComputeSeconds(Task task) {
        double total = 0.0;
        Map<CondorVM, Double> perVm = computeSecondsByVm.get(task);
        for (Double seconds : perVm.values()) {
            total += seconds.doubleValue();
        }
        return total / perVm.size();
    }

    /**
     * 返回父子任务之间全部传输文件在 PE 兼容的有序跨 VM 对上的平均通信秒数。
     *
     * <p>平均仅覆盖不同 VM 的有序对（同 VM 传输为零，由运行时副本局部性处理，
     * 与 HEFT 论文的 c̄_ij 约定一致）；跨 VM 对按
     * {@code bytes / (1e6 × min(bw_i, bw_j))}。均匀带宽下退化为边权本身。</p>
     */
    final double meanCommunicationSeconds(Task parent, Task child) {
        double bytes = communicationBytes(parent, child);
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (Map.Entry<Integer, CondorVM> first : vmById.entrySet()) {
            for (Map.Entry<Integer, CondorVM> second : vmById.entrySet()) {
                if (first.getKey().equals(second.getKey())
                        || !isCompatible(parent, first.getValue())
                        || !isCompatible(child, second.getValue())) {
                    continue;
                }
                double minBw = Math.min(first.getValue().getBw(), second.getValue().getBw());
                total += bytes / 1.0e6 / minBw;
                pairs++;
            }
        }
        // 单 VM 时不存在跨处理器通信，避免 0/0 使 rank 变成 NaN。
        return pairs == 0 ? 0.0 : total / pairs;
    }

    /**
     * 父任务全部传输文件到子任务在指定 VM 对之间的通信秒数（OCT 递推用）。
     *
     * <p>与 {@link #meanCommunicationSeconds} 同约定：跨 VM 传输率取
     * {@code min(bw_from, bw_to)}，无共享文件或同 VM 为零。与副本状态无关——OCT 是
     * 调度前静态量，只按任务边上的字节数与端点带宽计算，不做副本局部性减免。</p>
     */
    final double communicationSeconds(Task parent, Task child, CondorVM from, CondorVM to) {
        if (from.getId() == to.getId()) {
            return 0.0;
        }
        double bytes = communicationBytes(parent, child);
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        return bytes / 1.0e6 / Math.min(from.getBw(), to.getBw());
    }

    /** 父任务 OUTPUT 中被子任务 INPUT 消费的文件总字节数。 */
    private double communicationBytes(Task parent, Task child) {
        double bytes = 0.0;
        LinkedHashSet<String> parentOutputs = new LinkedHashSet<String>();
        for (FileItem file : parent.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                parentOutputs.add(file.getName());
            }
        }
        for (FileItem file : child.getFileList()) {
            if (file.getType() == FileType.INPUT && parentOutputs.contains(file.getName())) {
                bytes += file.getSize();
            }
        }
        return bytes;
    }

    /**
     * 在任务依赖就绪时可见的副本中选最快来源；尚未到达的目标副本不能按本地零成本计。
     * 已规划任务的可用时刻只读，不因候选试算提前登记副本。
     */
    private double fileStageInSeconds(FileItem file, CondorVM vm, double dependencyReady) {
        Map<String, Double> sites = replicas.get(file.getName());
        if (sites == null || sites.isEmpty()) {
            throw new IllegalStateException("Required input file '" + file.getName()
                    + "' has no registered replica");
        }
        String vmSite = Integer.toString(vm.getId());
        double maxBwth = 0.0;
        for (Map.Entry<String, Double> replica : sites.entrySet()) {
            if (replica.getValue().doubleValue() > dependencyReady) {
                continue;
            }
            String site = replica.getKey();
            if (site.equals(vmSite)) {
                return 0.0;
            }
            double bwth;
            if (site.equals(Parameters.SOURCE)) {
                bwth = vm.getBw();
            } else {
                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Replica site '" + site
                            + "' is neither the source nor a VM identifier", e);
                }
                CondorVM sourceVm = vmById.get(Integer.valueOf(sourceVmId));
                if (sourceVm == null) {
                    throw new IllegalStateException("Replica site VM " + sourceVmId
                            + " is unavailable for input file '" + file.getName() + "'");
                }
                bwth = Math.min(vm.getBw(), sourceVm.getBw());
            }
            if (bwth > maxBwth) {
                maxBwth = bwth;
            }
        }
        if (!(maxBwth > 0.0)) {
            throw new IllegalStateException("Required input file '" + file.getName()
                    + "' has no usable replica at dependency-ready time " + dependencyReady);
        }
        return file.getSize() / 1.0e6 / maxBwth;
    }

    private void reserve(Task task, CondorVM vm, double start, double finish, double inputReady) {
        reservations.get(vm).add(new Reservation(start, finish));
        Collections.sort(reservations.get(vm), new Comparator<Reservation>() {
            @Override
            public int compare(Reservation first, Reservation second) {
                int byStart = Double.compare(first.start, second.start);
                return byStart != 0 ? byStart : Double.compare(first.finish, second.finish);
            }
        });
        finishes.put(task, Double.valueOf(finish));
        task.setVmId(vm.getId());
        task.setStaticScheduleStartTime(start);
        // 输入在整个 hold 完成时登记，可能早于计算开始；输出仅在计算完成后可用。
        // 分配顺序与实际时间顺序不同，必须保留时间戳而非把集合插入视为即时可读。
        String vmSite = Integer.toString(vm.getId());
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())) {
                registerReplica(file.getName(), vmSite, inputReady);
            } else if (file.getType() == FileType.OUTPUT) {
                registerReplica(file.getName(), vmSite, finish);
            }
        }
    }

    private void registerReplica(String fileName, String site, double availableAt) {
        Map<String, Double> sites = replicas.get(fileName);
        Double previous = sites.get(site);
        if (previous == null || availableAt < previous.doubleValue()) {
            sites.put(site, Double.valueOf(availableAt));
        }
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

    private List<Task> sortedTasks() {
        if (getTaskList() == null || getTaskList().isEmpty()) {
            throw new IllegalArgumentException(label + " requires at least one task");
        }
        List<Task> tasks = new ArrayList<Task>(getTaskList());
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        return tasks;
    }

    private void sortedVms() {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalArgumentException(label + " requires at least one VM");
        }
        vms.clear();
        for (Object object : getVmList()) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(label + " requires CondorVM instances");
            }
            vms.add((CondorVM) object);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        vmById.clear();
        reservations.clear();
        for (CondorVM vm : vms) {
            vmById.put(Integer.valueOf(vm.getId()), vm);
            reservations.put(vm, new ArrayList<Reservation>());
        }
    }

    /** 规划期的 VM 占用区间。 */
    private static final class Reservation {

        private final double start;
        private final double finish;

        private Reservation(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }
}
