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

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * LJF-Fastest-Idle: 选最长任务(Longest Job First),分配到最快的空闲 VM。
 *
 * <p>原名为 MaxMinSchedulingAlgorithm,但其行为不符合 Braun et al. 2001 的经典
 * Max-Min 定义(从所有任务的最小 ECT 中选全局最大者)。
 * 本实现是 LJF 变体,与经典 Max-Min 不同。</p>
 *
 * <p>ready-batch Max-Min 请参考 {@link ReadyBatchMaxMinSchedulingAlgorithm}。</p>
 *
 * <p><strong>MIPS 度量说明：</strong>作业固定占用 1 个 PE，"最快 VM"按单 PE 速度
 * {@code getMips()} 比较（历史实现用 {@code getCurrentRequestedTotalMips()}
 * 在多 PE 异构配置下会系统性选错 VM），与 ReadyBatch 系列度量一致。</p>
 *
 * <p><strong>排序键与平局注记（复核轮 2026-09 确认）：</strong>任务排序键是 MI
 * （{@code getCloudletLength()}）而非估算执行时间——异构 VM 下与教科书 LJF
 * 略有差别；"最快空闲 VM"遍历原始 {@code vmList}，同 MIPS 平局取决于传入顺序。
 * 作业固定 1 PE 时差异不可达，本类不在标准研究入口内，故不修复。</p>
 *
 * <p><strong>兼容边界：</strong>它仅供历史直接 API 探索，标准研究入口不接受历史
 * {@code MAXMIN} 标签；不得将其结果表述为经典 Max-Min。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @see ReadyBatchMaxMinSchedulingAlgorithm
 */
public class LjfFastestIdleSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    public LjfFastestIdleSchedulingAlgorithm() {
        super();
    }

    private final List<Boolean> hasChecked = new ArrayList<>();

    @Override
    public void run() {
        int size = getCloudletList().size();
        hasChecked.clear();
        for (int t = 0; t < size; t++) {
            hasChecked.add(false);
        }
        for (int i = 0; i < size; i++) {
            int maxIndex = 0;
            Cloudlet maxCloudlet = null;
            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (!hasChecked.get(j)) {
                    maxCloudlet = cloudlet;
                    maxIndex = j;
                    break;
                }
            }
            if (maxCloudlet == null) {
                break;
            }

            for (int j = 0; j < size; j++) {
                Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
                if (hasChecked.get(j)) {
                    continue;
                }
                long length = cloudlet.getCloudletLength();
                if (length > maxCloudlet.getCloudletLength()) {
                    maxCloudlet = cloudlet;
                    maxIndex = j;
                }
            }
            hasChecked.set(maxIndex, true);

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
                        && vm.getMips() > firstIdleVm.getMips()) {
                    firstIdleVm = vm;
                }
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            maxCloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(maxCloudlet);
            Log.printLine("Schedules " + maxCloudlet.getCloudletId() + " with "
                    + maxCloudlet.getCloudletLength() + " to VM " + firstIdleVm.getId()
                    + " with " + firstIdleVm.getMips() + " MIPS/PE");
        }
    }
}
