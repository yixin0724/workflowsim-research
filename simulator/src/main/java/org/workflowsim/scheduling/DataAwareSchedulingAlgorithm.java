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

import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 数据局部性基线：将 ready Job 分派给尚未记录其所需真实输入的字节数最少的空闲 VM。
 *
 * <p>候选 VM 按 ID 升序遍历，因此相同非本地字节数时保留较小 VM ID。该策略刻意只衡量
 * ReplicaCatalog 中的副本局部性，不是网络时间模型；它不预测带宽、拓扑、争用或缓存替换。</p>
 *
 * <p><strong>文件系统模式限制：</strong>局部性判定按 VM ID 匹配副本站点
 * （{@code Integer.toString(vmId)}），这只在 {@code FileSystem.LOCAL} 模式下成立。
 * SHARED 模式的副本按数据中心名注册，与 VM ID 永不匹配，本算法会退化为
 * first-fit-idle（所有 VM 得分相同，恒选最小 ID 空闲 VM）。标准研究入口
 * （{@code SimulationConfig.Builder.build()}）已拒绝 DATA + SHARED 组合；直接
 * API 使用时由 {@link #run()} 打印一次性告警。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class DataAwareSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    /** 只告警一次的标记：SHARED 模式下局部性判定无效。 */
    private boolean sharedModeWarningShown = false;

    public DataAwareSchedulingAlgorithm() {
        super();
    }

    @Override
    public void run() {
        if (ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.SHARED
                && !sharedModeWarningShown) {
            sharedModeWarningShown = true;
            Log.printLine("Warning: DataAwareSchedulingAlgorithm requires LOCAL filesystem mode; "
                    + "under SHARED mode replica sites are datacenter names that never match VM ids, "
                    + "so locality scoring is ineffective and dispatch degenerates to first-fit-idle");
        }
        validateCloudletCompatibility();
        
        int size = getCloudletList().size();

        for (int i = 0; i < size; i++) {

            Cloudlet cloudlet = (Cloudlet) getCloudletList().get(i);

            CondorVM closestVm = null;
            double minimumNonLocalBytes = Double.MAX_VALUE;
            List<CondorVM> vms = sortedVmsById();
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    Job job = (Job)cloudlet;
                    double nonLocalBytes = dataTransferTime(job.getFileList(), cloudlet, vm.getId());
                    if(nonLocalBytes < minimumNonLocalBytes){
                        minimumNonLocalBytes = nonLocalBytes;
                        closestVm = vm;
                    }
                    
                }
            }

            if(closestVm!=null){
                closestVm.setState(WorkflowSimTags.VM_STATUS_BUSY);
                cloudlet.setVmId(closestVm.getId());
                getScheduledList().add(cloudlet);
            }
        }
    }

    /**
     * 计算一个 ready Job 的局部性分值。
     *
     * <p>为保持源码兼容仍沿用历史方法名；返回值是未驻留真实输入的字节数，而不是传输时间。</p>
     *
     * @param requiredFiles 需要检查的输入/输出文件
     * @param cl 待处理 Job
     * @param vmId 候选 VM ID
     * @return 候选 VM 未记录为本地副本的真实输入字节数
     */

    protected double dataTransferTime(List<FileItem> requiredFiles, Cloudlet cl, int vmId)  {
        double time = 0.0;

        for (FileItem file : requiredFiles) {
            // 只计入真实输入，输出文件不构成该 Job 的阶段输入负担。
            if (file.isRealInputFile(requiredFiles)) {
                List<String> siteList = ReplicaCatalog.getStorageList(file.getName());

                boolean hasFile = false;
                if (siteList != null) {
                    for (String site : siteList) {
                        if(site.equals(Integer.toString(vmId))){
                            hasFile = true;
                            break;
                        }
                    }
                }
                if(!hasFile){
                    time += file.getSize() ;
                }
            }
        }
        return time;
    }

}
