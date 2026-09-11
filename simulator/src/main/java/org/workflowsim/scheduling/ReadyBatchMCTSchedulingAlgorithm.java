package org.workflowsim.scheduling;

import org.cloudbus.cloudsim.Cloudlet;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * 面向 WorkflowSim 在线 ready-batch 生命周期的 MCT 调度器。
 *
 * <p>对当前 ready Job，计算每个空闲兼容 VM 的
 * {@code ECT = cloudletLength / vmMips}，选择最小者；VM 候选按 ID 升序，因而相等 ECT
 * 时选择较小 VM ID。一次更新中每台 VM 至多接收一个 Job。离线静态 MCT 还需要维护机器的
 * 暂定可用时间，本实现不声称具有该语义。</p>
 *
 * <p>参考: Maheswaran, M., et al. "Dynamic Mapping of a Class of Independent
 * Tasks onto Heterogeneous Computing Systems." JPDC 1999.</p>
 */
public class ReadyBatchMCTSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);
            CondorVM bestVm = null;
            double minCompletionTime = Double.MAX_VALUE;

            for (CondorVM vm : vms) {
                if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                    continue;
                }
                // 当前事件时刻 VM 已空闲，ECT 退化为执行长度除以 VM MIPS。
                double completionTime = (double) cloudlet.getCloudletLength() / vm.getMips();
                if (completionTime < minCompletionTime) {
                    minCompletionTime = completionTime;
                    bestVm = vm;
                }
            }

            if (bestVm == null) {
                break; // 无空闲 VM
            }

            bestVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(bestVm.getId());
            getScheduledList().add(cloudlet);
        }
    }
}
