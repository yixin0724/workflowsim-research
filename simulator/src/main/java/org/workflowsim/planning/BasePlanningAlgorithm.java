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
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;

/**
 * 规划器基类，保存待映射 Task、VM 和数据中心集合。
 *
 * <p>具体规划策略应继承本类；本类不产生映射或执行顺序。规划器的输出语义由具体实现决定：
 * 可以只是 Task-to-VM 映射，也可以是受控静态 DAG 的完整保留计划。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public abstract class BasePlanningAlgorithm implements PlanningAlgorithmInterface {

    /** 待规划的 Task 列表。 */
    private List<Task> tasktList;
    /** 规划时可用的 VM 列表。 */
    private List<? extends Vm> vmList;

    /** 可见的数据中心列表。 */
    private List<? extends Datacenter> datacenterList;
    /** 创建规划器基类。 */
    public BasePlanningAlgorithm() {
    }

    /** 设置待规划 Task 列表。 */
    @Override
    public void setTaskList(List list) {
        this.tasktList = list;
    }

    /** 设置 VM 列表；内部复制列表以稳定候选集合。 */
    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    /** 返回待规划 Task 列表。 */
    @Override
    public List<Task> getTaskList() {
        return this.tasktList;
    }

    /** 返回 VM 列表。 */
    @Override
    public List getVmList() {
        return this.vmList;
    }

    /** 返回数据中心列表。 */
    public List getDatacenterList(){
        return this.datacenterList;
    }
    
    /** 设置数据中心列表。 */
    public void setDatacenterList(List list){
        this.datacenterList = list;
    }
    
    /** 执行一次离线规划。 */
    public abstract void run() throws Exception;

    
}
