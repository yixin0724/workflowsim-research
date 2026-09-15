package org.workflowsim.rl;

import java.util.HashSet;
import java.util.Set;

/**
 * 基线策略：每个就绪 Job 选择预计最早完成（ECT = length / mips）的空闲兼容 VM。
 *
 * <p>这是 RL 轨道的参考策略，用于验证环境闭环与给出可比较的下界基线：</p>
 * <ul>
 *   <li>按观测中的 Job 到达序逐个决策；同 Job 多 VM 等 ECT 选较小 VM ID；</li>
 *   <li>同一次更新内每台 VM 至多接收一个 Job（与 ready-batch 调度器契约一致）：
 *       已被本轮占用的 VM 视为不可用，后续 Job 顺延次优 VM；</li>
 *   <li>无空闲兼容 VM 的 Job 返回 {@link RlPolicy#NO_ASSIGNMENT}。</li>
 * </ul>
 */
public final class EarliestFinishGreedyPolicy implements RlPolicy {

    @Override
    public int[] selectVms(RlObservation observation) {
        int jobCount = observation.getReadyJobs().size();
        int[] actions = new int[jobCount];
        Set<Integer> reservedVmIndices = new HashSet<Integer>();
        for (int j = 0; j < jobCount; j++) {
            RlObservation.JobView job = observation.getReadyJobs().get(j);
            double bestEct = Double.MAX_VALUE;
            int bestVmIndex = NO_ASSIGNMENT;
            for (int v = 0; v < observation.getVms().size(); v++) {
                RlObservation.VmView vm = observation.getVms().get(v);
                if (!vm.isIdle() || reservedVmIndices.contains(v) || vm.getPes() < job.getPes()) {
                    continue;
                }
                double ect = (double) job.getTotalLength() / vm.getMips();
                if (ect < bestEct) {
                    bestEct = ect;
                    bestVmIndex = v;
                }
            }
            actions[j] = bestVmIndex;
            if (bestVmIndex != NO_ASSIGNMENT) {
                reservedVmIndices.add(bestVmIndex);
            }
        }
        return actions;
    }
}
