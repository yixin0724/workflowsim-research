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

/**
 * LOCAL 文件系统通信感知静态 DAG 规划器（Topcuoglu 族列表调度）的共享机制基类。
 *
 * <p>承载 {@link LocalHeftPlanningAlgorithm} 与 {@link LocalCpopPlanningAlgorithm}
 * 共用的执行模型镜像：任务×VM 计算 MI 折算（成本矩阵秒数 {@code Math.round} 或
 * 解析期 MI）、副本状态装配与按调度顺序演进、向上 rank
 * {@code r_u(t) = w̄_t + max_child(c̄ + r_u(child))}、运行时 LOCAL stage-in 传输
 * 逐位镜像估算、执行前传输延迟的 AST 公式
 * {@code AST(t, vm) = max(avail[vm], max_pred(finish(pred) + c_pred(vm)))}（传输
 * 与 VM 忙碌期重叠，VM 预留区间只覆盖计算）、插入式最早开始搜索与 VM 预留管理、
 * 根任务的模型 stage-in Job 引导时刻。语义与边界的完整叙述见
 * {@link LocalHeftPlanningAlgorithm} 类文档。</p>
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
    /** 任务 → (VM → 计算 MI)。 */
    private final Map<Task, Map<CondorVM, Long>> computeMiByVm = new HashMap<Task, Map<CondorVM, Long>>();
    /** 文件名 → 副本站点集合（"source" 或 VM ID 字符串）。 */
    private final Map<String, LinkedHashSet<String>> replicas = new LinkedHashMap<String, LinkedHashSet<String>>();
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

    /** 任务在指定 VM 上的计算秒数（矩阵/解析期 MI 折算后）。 */
    final double computeSecondsOn(Task task, CondorVM vm) {
        return computeMiByVm.get(task).get(vm).longValue() / vm.getMips();
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
     * 任务在某 VM 上的数据到达时刻（论文 AST 的数据就绪分量）。
     *
     * <p>每个父任务的文件在其计划完成时刻开始传输；同一父任务的多个文件串行累加，
     * 不同父任务并行传输，到达相互独立。数据到达时刻为最晚的
     * {@code finish(pred) + Σ c_file(pred, vm)}。未由任何父任务产生的外部输入
     * （SOURCE 副本）自始可用，其到达候选为传输秒数本身。根任务无输入时到达时刻
     * 即 stage-in 完成时刻。</p>
     *
     * @param task 待分配任务
     * @param vm 目标 VM
     * @param stageInFinish 根任务就绪时刻
     * @return 全部输入文件到达目标 VM 的最晚时刻
     */
    private double dataArrivalOn(Task task, CondorVM vm, double stageInFinish) {
        if (task.getParentList().isEmpty()) {
            // 根任务：模型 stage-in Job 已把外部输入搬到平台；计算 Job 自身无输入传输。
            return stageInFinish;
        }
        double arrival = 0.0;
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
                    secondsFromParent += fileStageInSeconds(file, vm);
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
        // 未由任何父任务产生的外部输入（SOURCE 副本）自始可用。
        double externalSeconds = 0.0;
        boolean hasExternal = false;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())
                    && !attributedNames.contains(file.getName())) {
                hasExternal = true;
                externalSeconds += fileStageInSeconds(file, vm);
            }
        }
        if (hasExternal) {
            arrival = Math.max(arrival, externalSeconds);
        }
        return Math.max(arrival, stageInFinish);
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
        // 运行时镜像：Job 在全部父任务返回后才被释放，因此可派发时刻为
        // max(全部父完成时刻, 数据到达时刻)。
        double dispatchable = readyTime(task, stageInFinish);
        List<CondorVM> candidates = pinnedVm != null
                ? Collections.singletonList(pinnedVm) : vms;
        CondorVM selectedVm = null;
        double selectedStart = 0.0;
        double selectedFinish = Double.POSITIVE_INFINITY;
        for (CondorVM vm : candidates) {
            double arrival = dataArrivalOn(task, vm, stageInFinish);
            long computeMi = computeMiByVm.get(task).get(vm).longValue();
            double duration = computeMi / vm.getMips();
            double start = earliestStart(reservations.get(vm), Math.max(dispatchable, arrival), duration);
            double finish = start + duration;
            if (finish < selectedFinish || (Double.compare(finish, selectedFinish) == 0
                    && selectedVm != null && vm.getId() < selectedVm.getId())) {
                selectedVm = vm;
                selectedStart = start;
                selectedFinish = finish;
            }
        }
        if (selectedVm == null) {
            throw new IllegalStateException(label + " cannot map task " + task.getCloudletId()
                    + "; no candidate VM");
        }
        reserve(task, selectedVm, selectedStart, selectedFinish);
        return selectedFinish;
    }

    private void populateCosts(List<Task> tasks) {
        for (Task task : tasks) {
            Map<CondorVM, Long> perVm = new LinkedHashMap<CondorVM, Long>();
            for (CondorVM vm : vms) {
                Double costSeconds = task.getVmExecutionCostSeconds(vm.getId());
                long mi = costSeconds != null
                        ? Math.round(costSeconds.doubleValue() * vm.getMips())
                        : task.getCloudletLength();
                if (mi <= 0L) {
                    throw new IllegalStateException(label + " cannot plan task "
                            + task.getCloudletId() + " on VM " + vm.getId()
                            + ": converted compute MI must be positive");
                }
                perVm.put(vm, Long.valueOf(mi));
            }
            computeMiByVm.put(task, perVm);
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
                if (file.getType() == FileType.INPUT && !outputNames.contains(file.getName())) {
                    // 真实外部输入：解析期注册到 SOURCE 站点。
                    replicas.put(file.getName(), new LinkedHashSet<String>(
                            Collections.singletonList(Parameters.SOURCE)));
                } else if (!replicas.containsKey(file.getName())) {
                    replicas.put(file.getName(), new LinkedHashSet<String>());
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

    /** 任务在全部 VM 上计算秒数的平均值（w̄）。 */
    final double meanComputeSeconds(Task task) {
        double total = 0.0;
        Map<CondorVM, Long> perVm = computeMiByVm.get(task);
        for (Map.Entry<CondorVM, Long> entry : perVm.entrySet()) {
            total += entry.getValue().longValue() / entry.getKey().getMips();
        }
        return total / perVm.size();
    }

    /**
     * 返回父子任务之间全部传输文件在全部有序跨 VM 对上的平均通信秒数。
     *
     * <p>平均仅覆盖不同 VM 的有序对（同 VM 传输为零，由运行时副本局部性处理，
     * 与 HEFT 论文的 c̄_ij 约定一致）；跨 VM 对按
     * {@code bytes / (1e6 × min(bw_i, bw_j))}。均匀带宽下退化为边权本身。</p>
     */
    final double meanCommunicationSeconds(Task parent, Task child) {
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
        if (!(bytes > 0.0)) {
            return 0.0;
        }
        double total = 0.0;
        int pairs = 0;
        for (Map.Entry<Integer, CondorVM> first : vmById.entrySet()) {
            for (Map.Entry<Integer, CondorVM> second : vmById.entrySet()) {
                if (first.getKey().equals(second.getKey())) {
                    continue;
                }
                double minBw = Math.min(first.getValue().getBw(), second.getValue().getBw());
                total += bytes / 1.0e6 / minBw;
                pairs++;
            }
        }
        return total / pairs;
    }

    /**
     * 镜像运行时 LOCAL stage-in 规则估算单个文件到目标 VM 的传输秒数。
     *
     * <p>取全部副本站点中的最快传输率；文件已在目标 VM 上则零传输。副本状态在此
     * 只读，提交后由 {@link #reserve} 演进。</p>
     */
    private double fileStageInSeconds(FileItem file, CondorVM vm) {
        LinkedHashSet<String> sites = replicas.get(file.getName());
        if (sites == null || sites.isEmpty()) {
            throw new IllegalStateException("Required input file '" + file.getName()
                    + "' of task " + file.getName() + " has no registered replica");
        }
        String vmSite = Integer.toString(vm.getId());
        boolean requiredFileStagein = true;
        double maxBwth = 0.0;
        for (String site : sites) {
            if (site.equals(vmSite)) {
                requiredFileStagein = false;
                break;
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
        if (requiredFileStagein && maxBwth > 0.0) {
            return file.getSize() / 1.0e6 / maxBwth;
        }
        return 0.0;
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
        finishes.put(task, Double.valueOf(finish));
        task.setVmId(vm.getId());
        task.setStaticScheduleStartTime(start);
        // 副本演进：镜像运行时——任务完成后 OUTPUT 注册到本 VM（LOCAL），
        // stage-in 消费过的 INPUT 增加本 VM 副本。
        String vmSite = Integer.toString(vm.getId());
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.INPUT && file.isRealInputFile(task.getFileList())) {
                replicas.get(file.getName()).add(vmSite);
            } else if (file.getType() == FileType.OUTPUT) {
                replicas.get(file.getName()).add(vmSite);
            }
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
