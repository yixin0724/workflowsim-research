package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.workflowsim.Task;

/**
 * 与 WorkflowSim 受控 LOCAL 文件系统执行模型对齐的通信感知 HEFT 静态 DAG 规划器。
 *
 * <p><b>算法</b>（Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002）：按向上 rank
 * {@code r_u(t) = w̄_t + max_{child}(c̄_edge + r_u(child))} 降序选择任务；对每个任务
 * 在兼容 VM 上计算插入式最早完成时间（EFT，允许填入空闲间隙），选最小 EFT，平局取
 * 较小 VM ID；rank 平局取较小任务 ID。</p>
 *
 * <p><b>通信建模</b>：每个真实输入文件累加 {@code size / (1e6 × maxBwth)}；
 * SOURCE→VM 取目标带宽，VM→VM 取双方较小带宽；在任务依赖就绪时已经可用的目标
 * 副本免传输，其他可见副本取最快来源。副本记录带最早可用时刻：输入在整个传输
 * hold 完成时可用，输出在计划计算完成时可用；计划分配本身不使未来副本提前可读。</p>
 *
 * <p><b>执行语义对齐</b>：与论文的 AST（Actual Start Time）语义一致——输入传输是
 * <em>执行前网络延迟</em>，不是 VM 工作：每个父任务的文件在其完成时刻开始传输
 * （父任务间并行），可与目标 VM 忙碌期重叠；任务的计划开始时刻 =
 * {@code max(VM 可插入空闲时刻, 输入就绪时刻)}，VM 只被计算 MI 占用。
 * 父任务文件保留 {@code parentFinish + Σc_files_from_pred} 的无争用估计；外部输入
 * （包括根任务）从本 Job 的依赖就绪时点开始传输。正 hold 使用运行模型的最小事件
 * 间隔钳制。计算成本统一通过 {@code TaskExecutionModel} 折算：有矩阵时
 * {@code round(seconds * mips) / mips}，否则使用原始单 PE MI/mips。</p>
 *
 * <p><b>适用前提</b>：LOCAL 文件系统、NONE 聚类、无故障、无建模开销、
 * {@code preExecutionTransferDelayV1} 数据移动模型与 SPACE_SHARED VM；由
 * {@link PlanningContext#validateLocalStaticDag()} 强制。规划器写出的每任务 VM 映射与
 * 计划开始时间由 {@code StaticSchedulePlan} 强制为运行时的每 VM 派发顺序。</p>
 *
 * <p><b>边界声明</b>：不建模链路争用或完整事件队列；副本只由当前已规划任务提供，
 * 后规划任务更早产生副本、同刻事件次序、短计算的完成规则及并发争用都可能使
 * 计划与运行时不同。它是抽象模型上的 HEFT，不是真实平台校准。LOCAL_CPOP 与
 * LOCAL_PEFT 共用 {@link AbstractLocalCommPlanningAlgorithm} 的估计机制。</p>
 */
public final class LocalHeftPlanningAlgorithm extends AbstractLocalCommPlanningAlgorithm {

    public LocalHeftPlanningAlgorithm(PlanningContext context) {
        super("LOCAL_HEFT", context);
    }

    @Override
    public void run() {
        List<Task> tasks = prepare();
        List<Task> priority = new ArrayList<Task>(tasks);
        Collections.sort(priority, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                int byRank = Double.compare(upwardRankOf(second), upwardRankOf(first));
                return byRank != 0 ? byRank
                        : Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        double stageInFinish = stageInFinishTime();
        for (Task task : priority) {
            allocate(task, null, stageInFinish);
        }
    }
}
