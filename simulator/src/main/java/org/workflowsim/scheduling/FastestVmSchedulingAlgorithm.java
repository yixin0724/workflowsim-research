/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.scheduling;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * Fastest-VM: 对每个任务,贪心分配到当前最大 MIPS 的空闲 VM。
 *
 * <p>原名为 MCTSchedulingAlgorithm,但其行为不符合 Maheswaran et al. 1999 的经典
 * MCT 定义(对每个任务,计算 length/mips 的完成时间,分配到完成时间最小的机器)。
 * 本实现不考虑任务长度,仅比较 VM 的单 PE 速度 {@code getMips()},与经典 MCT 不同。</p>
 *
 * <p><strong>MIPS 度量说明：</strong>作业固定占用 1 个 PE（{@code Task} 构造器
 * pesNumber=1），实际执行速率只取决于单 PE MIPS。历史实现曾比较
 * {@code getCurrentRequestedTotalMips()}（= mips × PE 数），在多 PE 异构
 * VM 配置下会系统性选错 VM（如 2PE@500 总 MIPS=1000 胜过 1PE@800，
 * 但作业在前者实际只以 500 MIPS 运行）。现已统一为 {@code getMips()}，
 * 与 {@link ReadyBatchMCTSchedulingAlgorithm} 的度量一致。</p>
 *
 * <p>ready-batch MCT 请参考 {@link ReadyBatchMCTSchedulingAlgorithm}。</p>
 *
 * <p><strong>平局与兼容检查注记（复核轮 2026-09 确认）：</strong>本类遍历原始
 * {@code vmList}，同 MIPS 平局时选择取决于传入顺序（未按 VM ID 排序），且不做
 * PE 兼容检查——与维护轨道的 FCFS/ReadyBatch 系列（{@code sortedVmsById} +
 * 兼容检查）不一致。作业固定 1 PE、VM 恒 ≥1 PE 时兼容检查不可达；平局顺序
 * 差异仅影响异构同速 VM 的边缘场景，且本类不在标准研究入口内，故不修复。</p>
 *
 * <p><strong>兼容边界：</strong>本类仅为历史直接 API 保留；标准研究入口应使用
 * {@link ReadyBatchMCTSchedulingAlgorithm}，不能把本类结果称为经典 MCT 证据。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @see ReadyBatchMCTSchedulingAlgorithm
 */
public class FastestVmSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    public FastestVmSchedulingAlgorithm() {
        super();
    }

    @Override
    public void run() {
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);
            int vmSize = getVmList().size();
            CondorVM firstIdleVm = null;

            for (int j = 0; j < vmSize; j++) {
                CondorVM vm = (CondorVM) getVmList().get(j);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    firstIdleVm = vm;
                    break;
                }
            }
            if (firstIdleVm == null) {
                break;
            }

            for (int j = 0; j < vmSize; j++) {
                CondorVM vm = (CondorVM) getVmList().get(j);
                if ((vm.getState() == WorkflowSimTags.VM_STATUS_IDLE)
                        && (vm.getMips() > firstIdleVm.getMips())) {
                    firstIdleVm = vm;
                }
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(cloudlet);
            Log.printLine("Schedules " + cloudlet.getCloudletId() + " with "
                    + cloudlet.getCloudletLength() + " to VM " + firstIdleVm.getId()
                    + " with " + firstIdleVm.getMips() + " MIPS/PE");
        }
    }
}
