package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 与 WorkflowSim 受控 LOCAL 文件系统执行模型对齐的通信感知 PEFT 静态 DAG 规划器。
 *
 * <p><b>算法</b>（Arabnejad &amp; Barbosa, IEEE TPDS 2014, Eq. (7)）：
 * {@code OCT(t,p) = max_child min_p'(OCT(child,p') + w(child,p') + c(t,child,p,p'))}，
 * 出口 {@code OCT(exit,p) = 0}。OCT 从后继开始计成本，不含当前任务自身计算量。
 * 每轮在依赖就绪任务中选择兼容 VM 平均 OCT 最高者（rank_o），平局取较小 Task ID；
 * 分配给插入式 {@code EFT(t,p) + OCT(t,p)} 最小的兼容 VM，平局取较小 VM ID。</p>
 *
 * <p><b>一级来源</b>：作者公开博士论文第3章明确收录 DOI 10.1109/TPDS.2013.57，
 * 印刷 p71 Eq. (7) 与出口零条件、p73 Eq. (9) 和 Algorithm 1；
 * <a href="https://repositorio-aberto.up.pt/handle/10216/92290">University of Porto author source</a>。
 * 原先“自身 w + 后继 OCT、出口均值”的实现并非原 PEFT；其历史结果不能作为论文复现证据。</p>
 *
 * <p><b>通信建模</b>——与 {@link SharedStoragePeftPlanningAlgorithm}（共享存储轨道，
 * 无链路模型，通信项 c ≡ 0 的退化 PEFT）的关键区别：OCT 使用模型内按 VM 对的
 * LOCAL 通信成本 {@code c(t, child, p, p') = bytes / (1e6 × min(bw_p, bw_p'))}
 * （p = p' 时为零），字节数取父 OUTPUT ∩ 子 INPUT。原文按平均链路成本建模，
 * 异构端点带宽与副本局部性是本平台的显式适配，并非原文完整网络模型。
 * stage-in 与带时间戳副本使用和 {@link LocalHeftPlanningAlgorithm} 相同的
 * {@link AbstractLocalCommPlanningAlgorithm} 估计；本类只实现 OCT、优先级及选择规则。</p>
 *
 * <p><b>适用前提</b>：LOCAL 文件系统、NONE 聚类、无故障、无建模开销、
 * {@code preExecutionTransferDelayV1} 数据移动模型与 SPACE_SHARED VM；由
 * {@link PlanningContext#validateLocalStaticDag()} 强制。规划器写出的每任务 VM
 * 映射与计划开始时间由 {@code StaticSchedulePlan} 强制为运行时的每 VM 派发顺序。</p>
 *
 * <p><b>边界声明</b>：OCT 是调度前静态量——按 VM 对无争用带宽计算，不做副本局部性
 * 减免（运行期 stage-in 可利用已演进的副本，实际传输可能快于 OCT 估计）；不建模
 * 链路争用、网络拓扑或多工作流并发传输（同 LOCAL_HEFT 边界）；副本估计不完整重放
 * 事件顺序，短计算的完成规则仍可能造成偏差。rank_o 降序在异构成本下理论上不保证
 * 拓扑序（后继处理器最小化可使父 OCT 低于后继的跨 VM 平均值），因此分配采用论文的
 * 标准就绪表纪律：每轮在前驱均已分配的任务中取 rank_o 最高者（平局取较小任务 ID）。
 * 当 rank_o 序本身拓扑合法时（例如本次核对的论文算例，由既有单测逐位守护），就绪表
 * 选择与字面 rank_o 序逐项一致，调度不变。它是抽象模型上的 PEFT，不是真实平台校准。</p>
 */
public final class LocalPeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    /** 任务 → (VM → OCT)；每次 {@link #run()} 清空重建。 */
    private final Map<Task, Map<CondorVM, Double>> octByTaskVm =
            new HashMap<Task, Map<CondorVM, Double>>();

    public LocalPeftPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_PEFT", context);
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        octByTaskVm.clear();
        for (Task task : tasks) {
            octPerVm(task);
        }
        List<Task> priority = new ArrayList<Task>(tasks);
        Collections.sort(priority, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byOct = Double.compare(priorityOf(second), priorityOf(first));
                return byOct != 0 ? byOct
                        : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        double stageInFinish = stageInFinishTime();
        // 就绪表纪律：每轮在“前驱均已分配”的任务中取 rank_o 最高者。rank_o 降序
        // 在异构成本下不保证拓扑序（见类边界声明），字面顺序直接分配会让基类
        // readyTime 快速失败；当 rank_o 序本身拓扑合法（例如本次核对的论文算例）
        // 时，就绪表选择与字面 rank_o 序逐项一致，调度逐位不变。
        List<Task> remaining = new ArrayList<Task>(priority);
        Set<Task> allocated = new HashSet<Task>();
        while (!remaining.isEmpty()) {
            Task next = null;
            for (Task candidate : remaining) {
                if (allocated.containsAll(candidate.getParentList())) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                // prepare() 的深度校验已保证无环；此分支仅防御性兜底。
                throw new IllegalStateException(label() + " ready list exhausted with "
                        + remaining.size() + " unallocated tasks");
            }
            remaining.remove(next);
            allocated.add(next);
            allocateOptimistic(next, octByTaskVm.get(next), stageInFinish);
        }
    }

    /**
     * 任务在全部 VM 上的 OCT（记忆化后向递推；DAG 无环由 {@link #prepare()} 的
     * 深度校验保证）。
     */
    private Map<CondorVM, Double> octPerVm(Task task) {
        Map<CondorVM, Double> cached = octByTaskVm.get(task);
        if (cached != null) {
            return cached;
        }
        Map<CondorVM, Double> perVm = new LinkedHashMap<CondorVM, Double>();
        for (CondorVM vm : vms()) {
            if (!isCompatible(task, vm)) {
                perVm.put(vm, Double.valueOf(Double.POSITIVE_INFINITY));
                continue;
            }
            double oct;
            if (task.getChildList().isEmpty()) {
                // Arabnejad/Barbosa Eq. (7): no successor work remains at an exit.
                oct = 0.0;
            } else {
                double worstChild = 0.0;
                for (Task child : task.getChildList()) {
                    Map<CondorVM, Double> childOct = octPerVm(child);
                    double bestPlacement = Double.POSITIVE_INFINITY;
                    for (CondorVM childVm : vms()) {
                        if (!isCompatible(child, childVm)) {
                            continue;
                        }
                        double candidate = childOct.get(childVm).doubleValue()
                                + computeSecondsOn(child, childVm)
                                + communicationSeconds(task, child, vm, childVm);
                        if (candidate < bestPlacement) {
                            bestPlacement = candidate;
                        }
                    }
                    if (bestPlacement > worstChild) {
                        worstChild = bestPlacement;
                    }
                }
                oct = worstChild;
            }
            perVm.put(vm, Double.valueOf(oct));
        }
        octByTaskVm.put(task, perVm);
        return perVm;
    }

    /** 任务优先级 = OCT 在兼容 VM 上的平均值（rank_o）；run() 中可用。 */
    final double priorityOf(Task task) {
        Map<CondorVM, Double> perVm = octPerVm(task);
        double total = 0.0;
        int count = 0;
        for (Map.Entry<CondorVM, Double> entry : perVm.entrySet()) {
            if (isCompatible(task, entry.getKey())) {
                total += entry.getValue().doubleValue();
                count++;
            }
        }
        if (count == 0) {
            throw new IllegalArgumentException(label() + " cannot rank task " + task.getCloudletId()
                    + "; no compatible VM");
        }
        return total / count;
    }

    /** 任务在指定 VM 上的 OCT；未计算抛 {@link IllegalStateException}。 */
    final double optimisticCostOf(Task task, CondorVM vm) {
        Map<CondorVM, Double> perVm = octByTaskVm.get(task);
        if (perVm == null) {
            throw new IllegalStateException(label() + " has no OCT before run(); task "
                    + task.getCloudletId());
        }
        Double value = perVm.get(vm);
        if (value == null) {
            throw new IllegalStateException(label() + " has no OCT for task "
                    + task.getCloudletId() + " on VM " + vm.getId());
        }
        return value.doubleValue();
    }
}
