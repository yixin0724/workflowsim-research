package org.workflowsim.planning;

import java.util.ArrayList;
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
 *       {@code r_d(t) = max_{child}(c̄_edge + r_d(child))}（出口为 0）；</li>
 *   <li>关键路径：从最高优先级的入口任务出发，每步走优先级最大的子任务
 *       （平局取较小任务 ID），直至出口；</li>
 *   <li>关键路径处理器 {@code p_CP} = 使关键路径任务计算秒数之和最小的 VM
 *       （平局取较小 VM ID）；</li>
 *   <li>就绪队列调度：每步取就绪任务中优先级最大者（平局取较小任务 ID）；关键路径
 *       任务绑定 {@code p_CP}（仍用插入式最早开始搜索），其余任务在全部 VM 中取
 *       最小插入式 EFT（平局取较小 VM ID）。</li>
 * </ol>
 *
 * <p><b>通信建模与执行语义</b>与 {@link LocalHeftPlanningAlgorithm} 完全一致（共享
 * {@link AbstractLocalCommPlanningAlgorithm}）：LOCAL stage-in 传输逐位镜像、传输
 * 折算 MI 计入执行信封、副本状态按调度顺序演进。适用前提与边界声明亦同——特别地，
 * 论文允许传输与处理器忙碌期重叠，本平台信封语义不允许，二者的 EFT 比较可能在个别
 * 任务上翻转（HEFT 复现中的 t6 即为一例），这是已记录的刻意平台边界。</p>
 */
public final class LocalCpopPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    /** 任务 → 向下 rank。 */
    private final Map<Task, Double> downwardRanks = new HashMap<Task, Double>();

    public LocalCpopPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_CPOP", context);
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        for (Task task : tasks) {
            downwardRank(task);
        }

        LinkedHashSet<Task> criticalPath = walkCriticalPath(tasks);
        CondorVM cpVm = criticalPathVm(criticalPath);

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

    private double priorityOf(Task task) {
        return upwardRankOf(task) + downwardRanks.get(task).doubleValue();
    }

    /** 向下 rank：r_d(t) = max_{child}(c̄ + r_d(child))，出口任务为 0。 */
    private double downwardRank(Task task) {
        Double cached = downwardRanks.get(task);
        if (cached != null) {
            return cached.doubleValue();
        }
        double rank = 0.0;
        for (Task child : task.getChildList()) {
            double via = meanCommunicationSeconds(task, child) + downwardRank(child);
            if (via > rank) {
                rank = via;
            }
        }
        downwardRanks.put(task, Double.valueOf(rank));
        return rank;
    }

    /** 从最高优先级入口任务出发，每步取优先级最大的子任务（平局取较小任务 ID）。 */
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
        LinkedHashSet<Task> criticalPath = new LinkedHashSet<Task>();
        Task current = entry;
        criticalPath.add(current);
        while (!current.getChildList().isEmpty()) {
            Task next = null;
            for (Task child : current.getChildList()) {
                if (next == null || priorityOf(child) > priorityOf(next)
                        || (Double.compare(priorityOf(child), priorityOf(next)) == 0
                                && child.getCloudletId() < next.getCloudletId())) {
                    next = child;
                }
            }
            if (!criticalPath.add(next)) {
                throw new IllegalStateException("LOCAL_CPOP critical path revisited task "
                        + next.getCloudletId() + "; the DAG validator should have rejected cycles");
            }
            current = next;
        }
        return criticalPath;
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
