package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 与 WorkflowSim 受控 LOCAL 文件系统执行模型对齐的通信感知 CPOP 静态 DAG 规划器。
 *
 * <p><b>算法</b>（Critical-Path-on-a-Processor，与 HEFT 同出 Topcuoglu, Hariri &amp;
 * Wu, IEEE TPDS 2002）：</p>
 * <ol>
 *   <li>优先级 = 向上 rank + 向下 rank：{@code r_u(t) = w̄_t + max_child(c̄ + r_u)}，
 *       {@code r_d(t) = max_parent(r_d(parent) + w̄_parent + c̄_parent,t)}（入口为 0）；</li>
 *   <li>关键路径：从最高优先级的入口任务出发，沿保持关键路径长度且满足向上 rank
 *       递推等式的边走到出口；多条等长路径取较小任务 ID；</li>
 *   <li>关键路径处理器 {@code p_CP} = 使关键路径任务计算秒数之和最小的 VM
 *       （平局取较小 VM ID）；</li>
 *   <li>就绪队列调度：每步取就绪任务中优先级最大者（平局取较小任务 ID）；关键路径
 *       任务绑定 {@code p_CP}（仍用插入式最早开始搜索），其余任务在全部 VM 中取
 *       最小插入式 EFT（平局取较小 VM ID）。</li>
 * </ol>
 *
 * <p><b>通信建模与执行语义</b>与 {@link LocalHeftPlanningAlgorithm} 完全一致（共享
 * {@link AbstractLocalCommPlanningAlgorithm}）：LOCAL stage-in 传输逐位镜像、传输为
 * 执行前网络延迟（可与 VM 忙碌期重叠，VM 只被计算占用，论文 AST 语义）、副本状态按
 * 调度顺序演进。适用前提与边界声明亦同。</p>
 */
public final class LocalCpopPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    private static final double RANK_TOLERANCE = 1.0e-9;

    /** 任务 → 向下 rank。 */
    private final Map<Task, Double> downwardRanks = new HashMap<Task, Double>();
    private List<Integer> criticalPathTaskIds = Collections.emptyList();
    private Integer criticalProcessorVmId;

    public LocalCpopPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_CPOP", context);
    }

    @Override
    public void run() {
        downwardRanks.clear();
        criticalPathTaskIds = Collections.emptyList();
        criticalProcessorVmId = null;
        List<Task> tasks = prepare();
        for (Task task : tasks) {
            downwardRank(task);
        }

        LinkedHashSet<Task> criticalPath = walkCriticalPath(tasks);
        CondorVM cpVm = criticalPathVm(criticalPath);
        List<Integer> pathIds = new ArrayList<Integer>();
        for (Task task : criticalPath) {
            pathIds.add(Integer.valueOf(task.getCloudletId()));
        }
        criticalPathTaskIds = Collections.unmodifiableList(pathIds);
        criticalProcessorVmId = Integer.valueOf(cpVm.getId());

        // 就绪队列调度：未调度且全部父任务已调度的任务中，优先级最大者先行。
        double stageInFinish = stageInFinishTime();
        List<Task> pending = new ArrayList<Task>(tasks);
        int scheduled = 0;
        while (scheduled < tasks.size()) {
            Task next = null;
            for (Task task : pending) {
                if (task == null || !isReady(task)) {
                    continue;
                }
                if (next == null || priorityOf(task) > priorityOf(next)
                        || (Double.compare(priorityOf(task), priorityOf(next)) == 0
                                && task.getCloudletId() < next.getCloudletId())) {
                    next = task;
                }
            }
            if (next == null) {
                throw new IllegalStateException(
                        "LOCAL_CPOP found no ready task; the DAG validator should have "
                                + "rejected cyclic workflows");
            }
            allocate(next, criticalPath.contains(next) ? cpVm : null, stageInFinish);
            pending.set(pending.indexOf(next), null);
            scheduled++;
        }
    }

    private boolean isReady(Task task) {
        if (plannedFinishOf(task) != null) {
            return false;
        }
        for (Task parent : task.getParentList()) {
            if (plannedFinishOf(parent) == null) {
                return false;
            }
        }
        return true;
    }

    /** 已计算的向下 rank；包内只读诊断，不触发计算。 */
    double downwardRankOf(Task task) {
        return downwardRanks.get(task).doubleValue();
    }

    /** 已计算的 CPOP 优先级；包内只读诊断。 */
    double priorityOf(Task task) {
        return upwardRankOf(task) + downwardRankOf(task);
    }

    /** 最近一次所选关键路径的不可变任务 ID 快照。 */
    List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    /** 最近一次所选关键处理器；尚未选择时为 null。 */
    Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

    /** 向下 rank：前驱的向下 rank、计算成本与入边通信成本之和的最大值；入口为 0。 */
    private double downwardRank(Task task) {
        Double cached = downwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double rank = 0.0;
        for (Task parent : task.getParentList()) {
            double via = downwardRank(parent) + meanComputeSeconds(parent)
                    + meanCommunicationSeconds(parent, task);
            if (via > rank) {
                rank = via;
            }
        }
        downwardRanks.put(task, Double.valueOf(rank));
        return rank;
    }

    /** 从最高优先级入口任务出发，沿最长路径上的实际边前进，平局取较小任务 ID。 */
    private LinkedHashSet<Task> walkCriticalPath(List<Task> tasks) {
        Task entry = null;
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty()) {
                continue;
            }
            if (entry == null || priorityOf(task) > priorityOf(entry)
                    || (Double.compare(priorityOf(task), priorityOf(entry)) == 0
                            && task.getCloudletId() < entry.getCloudletId())) {
                entry = task;
            }
        }
        if (entry == null) {
            throw new IllegalStateException("LOCAL_CPOP requires at least one entry task; "
                    + "the DAG validator should have rejected cyclic workflows");
        }
        double criticalPathLength = priorityOf(entry);
        LinkedHashSet<Task> criticalPath = new LinkedHashSet<Task>();
        Task current = entry;
        criticalPath.add(current);
        while (!current.getChildList().isEmpty()) {
            Task next = null;
            for (Task child : current.getChildList()) {
                // 两节点可能分别位于不同的等长关键路径上；优先级相等并不足以
                // 保证连接它们的边也在关键路径上，还必须满足向上 rank 递推等式。
                double via = meanComputeSeconds(current)
                        + meanCommunicationSeconds(current, child) + upwardRankOf(child);
                if (!sameRank(priorityOf(child), criticalPathLength)
                        || !sameRank(upwardRankOf(current), via)) {
                    continue;
                }
                if (next == null || child.getCloudletId() < next.getCloudletId()) {
                    next = child;
                }
            }
            if (next == null) {
                throw new IllegalStateException("LOCAL_CPOP could not continue its critical path "
                        + "from task " + current.getCloudletId());
            }
            if (!criticalPath.add(next)) {
                throw new IllegalStateException("LOCAL_CPOP critical path revisited task "
                        + next.getCloudletId() + "; the DAG validator should have rejected cycles");
            }
            current = next;
        }
        return criticalPath;
    }

    private static boolean sameRank(double first, double second) {
        double scale = Math.max(1.0, Math.max(Math.abs(first), Math.abs(second)));
        return Math.abs(first - second) <= RANK_TOLERANCE * scale;
    }

    /** 关键路径处理器：使关键路径任务计算秒数之和最小的 VM，平局取较小 VM ID。 */
    private CondorVM criticalPathVm(LinkedHashSet<Task> criticalPath) {
        CondorVM best = null;
        double bestTotal = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms()) {
            double total = 0.0;
            for (Task task : criticalPath) {
                total += computeSecondsOn(task, vm);
            }
            if (total < bestTotal) {
                best = vm;
                bestTotal = total;
            }
        }
        if (best == null) {
            throw new IllegalStateException("LOCAL_CPOP cannot select a critical-path VM");
        }
        return best;
    }
}
