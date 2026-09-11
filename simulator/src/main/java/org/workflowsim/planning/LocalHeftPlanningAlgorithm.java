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
 * 在全部 VM 上计算插入式最早完成时间（EFT，允许填入空闲间隙），选最小 EFT，平局取
 * 较小 VM ID；rank 平局取较小任务 ID。</p>
 *
 * <p><b>通信建模</b>——本规划器的关键差异点：任务间数据传输按 LOCAL 文件系统运行时的
 * 历史规则逐位镜像（{@code WorkflowDatacenter.legacyProcessDataStageInForComputeJob}）：
 * 每个真实输入文件独立累加 {@code size / (1e6 × maxBwth)}；{@code SOURCE} 站点传输率取
 * 目标 VM 带宽；VM 间传输率取 {@code min(bw_src, bw_dst)}；文件已在目标 VM 上则零传输；
 * 同一文件多个副本取最快来源；stage-in 后副本目录增加目标 VM。副本状态按调度顺序演进，
 * 与运行时派发顺序一致。</p>
 *
 * <p><b>执行语义对齐</b>：传输时间折算为整数 MI 计入作业执行信封
 * （{@code totalMi = (long)(computeMi + mips × transferSeconds)}，镜像 CloudSim
 * {@code cloudletSubmit} 的截断行为），因此规划的任务时长 = 传输 + 计算；就绪时刻 =
 * 全部父任务计划完成时刻的最大值（根任务等待模型 stage-in Job 完成加一个内核间隔），
 * 与运行时的事件链一致。任务×VM 异构成本矩阵存在时按矩阵秒数折算 MI
 * （{@code Math.round}，镜像 STATIC 派发折算）；缺省时沿用解析期 MI/mips 缩放。</p>
 *
 * <p><b>适用前提</b>：LOCAL 文件系统、NONE 聚类、无故障、无建模开销、
 * {@code legacyWorkflowsimV1} 数据移动模型与 SPACE_SHARED VM；由
 * {@link PlanningContext#validateLocalStaticDag()} 强制。规划器写出的每任务 VM 映射与
 * 计划开始时间由 {@code StaticSchedulePlan} 强制为运行时的每 VM 派发顺序。</p>
 *
 * <p><b>边界声明</b>：不建模链路争用、网络拓扑或多工作流并发传输；计划-运行时对齐在
 * 任务时长 ≥ 最小事件间隔 + 完成保护量（0.11 秒）时成立，更短的任务会被运行时完成事件
 * 规则推后。它是抽象模型上的 HEFT，不是真实平台校准。同论文的 CPOP 算法由
 * {@link LocalCpopPlanningAlgorithm} 实现，两规划器共享
 * {@link AbstractLocalCommPlanningAlgorithm} 的执行模型镜像。</p>
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
