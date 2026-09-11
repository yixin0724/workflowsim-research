package org.workflowsim.scheduling;

import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * 面向 WorkflowSim 在线 ready-batch 生命周期的轮转调度器。
 *
 * <p>维护一个 VM 游标，每次分配后移到下一台 VM 并循环。策略实例由
 * {@code WorkflowScheduler} 在一次仿真内持有，因而游标跨 ready-batch 保持；扫描时跳过
 * 忙碌或 PE 不兼容的 VM，直到本轮找不到候选为止。</p>
 *
 * <p>与历史 {@code RoundRobinSchedulingAlgorithm} 的区别是：后者声明了 {@code vmIndex}
 * 却从未使用，实际是 First-Fit-Idle；本实现才使用持久游标轮转。</p>
 */
public class ReadyBatchRoundRobinSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** VM 游标，跨 {@link #run()} 调用保持。 */
    private int vmCursor = 0;

    @Override
    public void run() {
        validateCloudletCompatibility();
        int size = getCloudletList().size();
        List<CondorVM> vmList = sortedVmsById();
        int vmCount = vmList.size();

        if (vmCount == 0) {
            return;
        }
        // 防御性归一化游标，防止 VM 列表规模变化时越界
        vmCursor = vmCursor % vmCount;

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);

            // 从游标位置开始找空闲 VM
            CondorVM selectedVm = null;
            int checked = 0;
            while (checked < vmCount) {
                CondorVM vm = vmList.get(vmCursor);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    selectedVm = vm;
                    break;
                }
                vmCursor = (vmCursor + 1) % vmCount;
                checked++;
            }

            if (selectedVm == null) {
                break; // 无空闲 VM
            }

            selectedVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(selectedVm.getId());
            getScheduledList().add(cloudlet);

            // 分配成功后推进游标，确保下一次从后继 VM 开始轮转。
            vmCursor = (vmCursor + 1) % vmCount;
        }
    }
}
