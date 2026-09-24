package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 与 WorkflowSim 受控 LOCAL 文件系统执行模型对齐的通信感知 PEFT 静态 DAG 规划器。
 *
 * <p><b>算法</b>（Arabnejad &amp; Barbosa, IEEE TPDS 2014, Predict Earliest Finish
 * Time）：对每个任务与 VM 计算乐观成本表
 * {@code OCT(t, p) = w(t,p) + max_{child}( min_{p'}( OCT(child, p') + c̄(t, child, p, p') ))}，
 * 出口任务 {@code OCT(t_exit, p) = w̄_exit}（出口任务在全部 VM 上的平均计算秒数，
 * 论文原文约定，对所有 VM 一致）；任务优先级为 OCT 在全部 VM 上的平均值（论文 rank_o）
 * 降序，平局取较小任务 ID；VM 选择最小化乐观目标 {@code EFT(t,p) + OCT(t,p)}，
 * 平局取较小 VM ID。与 HEFT 的纯贪心 EFT 选择不同，OCT 项把任务以下子 DAG 的
 * 乐观后续成本纳入当前 VM 决策，且选择过程只做一次前向扫描（无回溯）。</p>
 *
 * <p><b>通信建模</b>——与 {@link SharedStoragePeftPlanningAlgorithm}（共享存储轨道，
 * 无链路模型，通信项 c ≡ 0 的退化 PEFT）的关键区别：OCT 递推使用真实的按 VM 对
 * LOCAL 通信成本 {@code c̄(t, child, p, p') = bytes / (1e6 × min(bw_p, bw_p'))}
 * （p = p' 时为零，与论文同处理器零通信约定一致），字节数取任务边上父 OUTPUT ∩
 * 子 INPUT 的文件大小之和。执行层的 stage-in 估算、副本状态演进与 AST 语义与
 * {@link LocalHeftPlanningAlgorithm} 逐位一致——两者共享
 * {@link AbstractLocalCommPlanningAlgorithm} 的执行模型镜像，本类只实现优先级与
 * 选择规则。</p>
 *
 * <p><b>适用前提</b>：LOCAL 文件系统、NONE 聚类、无故障、无建模开销、
 * {@code preExecutionTransferDelayV1} 数据移动模型与 SPACE_SHARED VM；由
 * {@link PlanningContext#validateLocalStaticDag()} 强制。规划器写出的每任务 VM
 * 映射与计划开始时间由 {@code StaticSchedulePlan} 强制为运行时的每 VM 派发顺序。</p>
 *
 * <p><b>边界声明</b>：OCT 是调度前静态量——按 VM 对无争用带宽计算，不做副本局部性
 * 减免（运行期 stage-in 可利用已演进的副本，实际传输可能快于 OCT 估计）；不建模
 * 链路争用、网络拓扑或多工作流并发传输（同 LOCAL_HEFT 边界）；运行时最小事件间隔
 * 钳制与完成事件规则带来的微小漂移同样适用。rank_o 降序在极端异构成本下理论上
 * 不保证拓扑序（子任务 OCT 跨 VM 落差可超过父任务平均计算成本）；此时基类
 * {@code readyTime} 以明确的 {@link IllegalStateException} 快速失败，而不是产出
 * 前驱未定的不一致调度。它是抽象模型上的 PEFT，不是真实平台校准。</p>
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
        for (Task task : priority) {
            allocateOptimistic(task, octByTaskVm.get(task), stageInFinish);
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
            double oct;
            if (task.getChildList().isEmpty()) {
                // 论文出口条件：OCT(t_exit, p) = w̄_exit（平均计算成本，逐 VM 一致）。
                oct = meanComputeSeconds(task);
            } else {
                double worstChild = 0.0;
                for (Task child : task.getChildList()) {
                    Map<CondorVM, Double> childOct = octPerVm(child);
                    double bestPlacement = Double.POSITIVE_INFINITY;
                    for (CondorVM childVm : vms()) {
                        double candidate = childOct.get(childVm).doubleValue()
                                + communicationSeconds(task, child, vm, childVm);
                        if (candidate < bestPlacement) {
                            bestPlacement = candidate;
                        }
                    }
                    if (bestPlacement > worstChild) {
                        worstChild = bestPlacement;
                    }
                }
                oct = computeSecondsOn(task, vm) + worstChild;
            }
            perVm.put(vm, Double.valueOf(oct));
        }
        octByTaskVm.put(task, perVm);
        return perVm;
    }

    /** 任务优先级 = OCT 在全部 VM 上的平均值（论文 rank_o）；run() 中可用。 */
    final double priorityOf(Task task) {
        Map<CondorVM, Double> perVm = octPerVm(task);
        double total = 0.0;
        for (Double value : perVm.values()) {
            total += value.doubleValue();
        }
        return total / perVm.size();
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
