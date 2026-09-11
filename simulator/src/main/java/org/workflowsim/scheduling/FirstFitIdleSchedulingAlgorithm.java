/**
 * Copyright 2013-2014 University Of Southern California
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

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * First-Fit-Idle: 按 cloudletId 排序,按 vmId 排序,每个任务分配第一个空闲 VM。
 *
 * <p>原名为 RoundRobinSchedulingAlgorithm,但声明的 vmIndex 是死代码,
 * 实际行为并非 Round-Robin(持续轮转)。本实现是 First-Fit-Idle。</p>
 *
 * <p>ready-batch Round-Robin 请参考 {@link ReadyBatchRoundRobinSchedulingAlgorithm}。</p>
 *
 * <p><strong>兼容边界：</strong>本类仅保留给历史直接 API 调用；标准
 * {@code SimulationRunner} 不接受其历史标签作为研究证据入口。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date May 12, 2014
 * @see ReadyBatchRoundRobinSchedulingAlgorithm
 */
public class FirstFitIdleSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() {
        int size = getCloudletList().size();
        Collections.sort(getCloudletList(), new CloudletListComparator());
        List vmList = getVmList();
        Collections.sort(vmList, new VmListComparator());
        for (int j = 0; j < size; j++) {
            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(j);
            int vmSize = vmList.size();
            CondorVM firstIdleVm = null;
            for (int l = 0; l < vmSize; l++) {
                CondorVM vm = (CondorVM) vmList.get(l);
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                    firstIdleVm = vm;
                    break;
                }
            }
            if (firstIdleVm == null) {
                break;
            }
            firstIdleVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(firstIdleVm.getId());
            getScheduledList().add(cloudlet);
        }
    }

    /** 按 VM ID 升序排序。 */
    public class VmListComparator implements Comparator<CondorVM> {
        @Override
        public int compare(CondorVM v1, CondorVM v2) {
            return Integer.compare(v1.getId(), v2.getId());
        }
    }

    /** 按 Cloudlet ID 升序排序。 */
    public class CloudletListComparator implements Comparator<Cloudlet> {
        @Override
        public int compare(Cloudlet c1, Cloudlet c2) {
            return Integer.compare(c1.getCloudletId(), c2.getCloudletId());
        }
    }
}
