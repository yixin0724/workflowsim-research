package org.workflowsim.scheduling;

import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * 面向 WorkflowSim 在线 ready-batch 生命周期的 Min-Min 调度器。
 *
 * <p>它只考虑当前 ready Job 和空闲兼容 VM；一次更新中每台 VM 至多接收一个 Job，
 * 不等同于维护未来机器可用时间的独立任务静态 Min-Min 映射器。</p>
 *
 * <p>算法定义:</p>
 * <ol>
 *   <li>对每个未调度任务,计算其在所有机器上的最早完成时间(ECT = readyTime + length/mips)</li>
 *   <li>取每个任务的最小 ECT</li>
 *   <li>从所有任务的最小 ECT 中,选全局最小者</li>
 *   <li>将该任务分配到对应机器</li>
 *   <li>重复直到所有任务被分配或本次更新中没有空闲 VM</li>
 * </ol>
 *
 * <p>参考: Braun, T.D., et al. "A Comparison of Eleven Static Heuristics for
 * Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing
 * Systems." JPDC 2001.</p>
 *
 * <p>ECT 只在当前事件时刻空闲的 VM 上计算；相同任务 ECT 选择较小 VM ID，
 * 全局相同最小 ECT 选择较小 Job ID。静态映射器则必须维护 VM 的暂定可用时间。</p>
 */
public class ReadyBatchMinMinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int taskCount = getCloudletList().size();
        boolean[] scheduled = new boolean[taskCount];
        int scheduledCount = 0;

        while (scheduledCount < taskCount) {
            // 对当前就绪集,计算每个任务在每个空闲 VM 上的 ECT
            double globalMinEct = Double.MAX_VALUE;
            int globalMinTaskIdx = -1;
            int globalMinVmIdx = -1;

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
                    // ECT = length / mips (假设 readyTime = 0,因为 VM 空闲)
                    double ect = (double) cloudlet.getCloudletLength() / vm.getMips();
                    if (ect < minEctForTask) {
                        minEctForTask = ect;
                        bestVmForTask = v;
                    }
                }

                // 该任务的最小 ECT 参与全局比较
                if (bestVmForTask != -1 && (minEctForTask < globalMinEct
                        || (Double.compare(minEctForTask, globalMinEct) == 0
                                && globalMinTaskIdx != -1
                                && cloudlet.getCloudletId() < ((Cloudlet) getCloudletList()
                                        .get(globalMinTaskIdx)).getCloudletId()))) {
                    globalMinEct = minEctForTask;
                    globalMinTaskIdx = t;
                    globalMinVmIdx = bestVmForTask;
                }
            }

            if (globalMinTaskIdx == -1) {
                break; // 无空闲 VM 或无未调度任务
            }

            // 调度选中的任务到选中的 VM
            Cloudlet selectedCloudlet = (Cloudlet) getCloudletList().get(globalMinTaskIdx);
            CondorVM selectedVm = vms.get(globalMinVmIdx);
            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            selectedCloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(selectedCloudlet);
            scheduled[globalMinTaskIdx] = true;
            scheduledCount++;
        }
    }
}
