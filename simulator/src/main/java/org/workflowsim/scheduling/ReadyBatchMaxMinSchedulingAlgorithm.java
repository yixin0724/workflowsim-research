package org.workflowsim.scheduling;

import org.cloudbus.cloudsim.Cloudlet;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * 面向 WorkflowSim 在线 ready-batch 生命周期的 Max-Min 调度器。
 *
 * <p>它只考虑当前 ready Job 和空闲兼容 VM，不是为独立任务批次维护未来 VM 可用时间的
 * 静态 Max-Min 映射器。</p>
 *
 * <p>算法定义:</p>
 * <ol>
 *   <li>对每个未调度任务,计算其在所有机器上的最早完成时间(ECT)</li>
 *   <li>取每个任务的最小 ECT</li>
 *   <li>从所有任务的最小 ECT 中,选全局最大者(先调度大任务)</li>
 *   <li>将该任务分配到对应机器</li>
 *   <li>重复直到所有任务被分配或本次更新中没有空闲 VM</li>
 * </ol>
 *
 * <p>与 Min-Min 的区别仅在第 3 步：Max-Min 选最大，Min-Min 选最小。相同任务最小 ECT
 * 时选择较小 VM ID；相同全局最大 ECT 时选择较小 Job ID。Max-Min 倾向于先调度大任务，
 * 使小任务可在后续空闲资源上填充。</p>
 *
 * <p>参考: Braun, T.D., et al. "A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems." JPDC 2001.</p>
 */
public class ReadyBatchMaxMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {
            double globalMaxEct = -1.0; // Max-Min 选最大
            int globalMaxTaskIdx = -1;
            int globalMaxVmIdx = -1;

            for (int t = 0; t < taskCount; t++) {
                if (scheduled[t]) {
                    continue;
                }
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(t);
                double minEctForTask = Double.MAX_VALUE;
                int bestVmForTask = -1;

                for (int v = 0; v < vms.size(); v++) {
                    CondorVM vm = vms.get(v);
                    if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                        continue;
                    }
                    double ect = (double) cloudlet.getCloudletLength() / vm.getMips();
                    if (ect < minEctForTask) {
                        minEctForTask = ect;
                        bestVmForTask = v;
                    }
                }

                // Max-Min:从各任务的最小 ECT 中选最大
                if (bestVmForTask != -1 && (minEctForTask > globalMaxEct
                        || (Double.compare(minEctForTask, globalMaxEct) == 0
                        && globalMaxTaskIdx != -1
                        && cloudlet.getCloudletId() < ((Cloudlet) getCloudletList()
                                .get(globalMaxTaskIdx)).getCloudletId()))) {
                    globalMaxEct = minEctForTask;
                    globalMaxTaskIdx = t;
                    globalMaxVmIdx = bestVmForTask;
                }
            }

            if (globalMaxTaskIdx == -1) {
                break;
            }

            Cloudlet selectedCloudlet = (Cloudlet) getCloudletList().get(globalMaxTaskIdx);
            CondorVM selectedVm = vms.get(globalMaxVmIdx);
            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            selectedCloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(selectedCloudlet);
            scheduled[globalMaxTaskIdx] = true;
            scheduledCount++;
        }
    }
}
