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

import java.util.Iterator;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * 先来先服务（FCFS）在线调度器。
 *
 * <p>按传入 ready Job 的顺序扫描，并为每个 Job 选择按 VM ID 升序找到的首个空闲兼容 VM。
 * 一旦没有可用 VM 即结束本轮，因此它不会为未来 VM 空闲时间建立预测或保留。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FCFSSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** 按当前 ready 顺序执行一次 FCFS 分派。 */
    @Override
    public void run() {
        validateCloudletCompatibility();
        List<CondorVM> vms = sortedVmsById();

        for (Iterator it = getCloudletList().iterator(); it.hasNext();) {
            Cloudlet cloudlet = (Cloudlet) it.next();
            boolean stillHasVm = false;
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    stillHasVm = true;
                    vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                    cloudlet.setVmId(vm.getId());
                    getScheduledList().add(cloudlet);
                    break;
                }
            }
            // 当前没有空闲兼容 VM，剩余 Job 留待后续调度事件。
            if (!stillHasVm) {
                break;
            }
        }
    }
}
