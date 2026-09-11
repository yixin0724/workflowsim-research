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
package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * 历史 Distributed HEFT（DHEFT）规划器。
 *
 * <p>相对于历史 HEFT，它按 VM 对带宽估计通信时间，而非使用平均通信代价。该逻辑保留用于
 * 兼容性探索，但不满足当前受控 shared-storage 静态 DAG 规划的执行语义，标准
 * {@code SimulationRunner} 不接受 {@code DHEFT} 标签，不能作为研究证据入口。</p>
 *
 * <p><strong>迁移指南：</strong>详见 {@code docs/algorithms/LEGACY_MIGRATION.md}。</p>
 *
 * <p><strong>已知实现限制（复核轮 2026-09 确认，不修复）：</strong>本类内部以
 * <b>VM ID / cloudlet ID 直接作数组下标</b>（{@code availableTime}、
 * {@code earliestFinishTime} 等按列表大小分配，却用 {@code vm.getId()} /
 * {@code cloudlet.getId()} 索引），当 ID 不是从 0 开始的连续整数时会产生错误映射
 * 甚至数组越界；且 {@code task.setVmId(minTimeIndex)} 写入的是列表下标而非 VM ID。
 * 因本类已被 {@code AlgorithmCatalog} 拒绝、不产生任何研究证据，该缺陷保持隔离，
 * 不做修复——直接调用本 API 者必须自行保证 ID 连续性约定。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Nov 10, 2013
 * @deprecated 使用 {@link SharedStorageHeftPlanningAlgorithm} 或其他
 *             {@code SHARED_STORAGE_*} 系列算法替代
 */
@Deprecated
public class DHEFTPlanningAlgorithm extends BasePlanningAlgorithm {

    /** 按历史 DHEFT 逻辑执行一次规划。 */
    @Override
    public void run() {

        List<Vm> vmList = getVmList();
        double [][] bandwidths = new double[vmList.size()][vmList.size()];
        
        for(int i = 0; i < vmList.size(); i++){
            for(int j = i ; j < vmList.size(); j++){
                bandwidths[i][j] = bandwidths [j][i] = Math.min(vmList.get(i).getBw(), vmList.get(j).getBw());
            }
        }
        for (Object vmObject : getVmList()) {
            Vm vm =  (Vm)vmObject;
            vm.getBw();
        }
        
        int vmNum = getVmList().size();
        int taskNum = getTaskList().size();
        double [] availableTime = new double[vmNum];
        // 历史数组索引假定 cloudlet ID 从 1 开始。
        double [][] earliestStartTime = new double[taskNum + 1][vmNum];
        double [][] earliestFinishTime = new double[taskNum + 1][vmNum];
        int [] allocation = new int[taskNum + 1];
        
        List<Task> taskList = new ArrayList(getTaskList());
        List<Task> readyList = new ArrayList<>();
        while(!taskList.isEmpty()){
            readyList.clear();
            for(Task task : taskList){
                boolean ready = true;
                for(Task parent: task.getParentList()){
                    if(taskList.contains(parent)){
                        ready = false;
                        break;
                    }
                }
                if(ready){
                    readyList.add(task);
                }
            }
            taskList.removeAll(readyList);
            // 依次规划当前所有依赖已满足的 Task。
            for(Task task: readyList){
                long [] fileSizes = new long[task.getParentList().size()];
                int parentIndex = 0;
                for(Task parent: task.getParentList()){
                    long fileSize = 0;
                    for(FileItem file : task.getFileList()){
                        if(file.getType()==Parameters.FileType.INPUT){
                            for(FileItem file2 : parent.getFileList()){
                                if(file2.getType() == Parameters.FileType.OUTPUT && file2.getName().equals(file.getName()))
                                {
                                    fileSize += file.getSize();
                                }
                            }
                        }
                    }
                    fileSizes[parentIndex] = fileSize;
                    parentIndex ++;
                }     
                
                double minTime = Double.MAX_VALUE;
                int minTimeIndex = 0;
                
                for(int vmIndex = 0; vmIndex < getVmList().size(); vmIndex++){
                    Vm vm = (Vm)getVmList().get(vmIndex);
                    double startTime = availableTime[vm.getId()];
                    parentIndex = 0;
                    for(Task parent: task.getParentList()){
                        int allocatedVmId = allocation[parent.getCloudletId()];
                        double actualFinishTime = earliestFinishTime[parent.getCloudletId()][allocatedVmId];
                        double communicationTime = fileSizes[parentIndex] / bandwidths[allocatedVmId][vm.getId()];
                        
                        if(actualFinishTime + communicationTime > startTime){
                            startTime = actualFinishTime + communicationTime;
                        }
                        parentIndex ++;
                    }
                    earliestStartTime[task.getCloudletId()][vm.getId()] = startTime;
                    double runtime = task.getCloudletLength() / vm.getMips();
                    earliestFinishTime[task.getCloudletId()][vm.getId()] = runtime + startTime;
                    
                    if(runtime + startTime < minTime){
                        minTime = runtime + startTime;
                        minTimeIndex = vmIndex;
                    }
                }
                
                allocation[task.getCloudletId()] = minTimeIndex;//we do not really need it use task.getVmId
                task.setVmId(minTimeIndex);
                availableTime[minTimeIndex] = minTime;
            }
        }
        
    }


}
