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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.CondorVM;

/**
 * 调度器基类，集中保存就绪 Job、VM 和本轮已分派 Job。
 *
 * <p>具体策略应继承本类，但它不是可直接使用的调度算法。维护轨道通过
 * {@link #validateCloudletCompatibility()} 拒绝没有足够 PE 的 Job，并通过
 * {@link #sortedVmsById()} 为等代价 VM 选择提供稳定的 VM-ID 顺序。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public abstract class BaseSchedulingAlgorithm implements SchedulingAlgorithmInterface {

    /** 当前事件已就绪、等待本策略处理的 Job 列表。 */
    private List<? extends Cloudlet> cloudletList;
    /** 当前可见的 VM 列表。 */
    private List<? extends Vm> vmList;
    /** 本次 {@link #run()} 已经分派的 Job。 */
    private List< Cloudlet> scheduledList;

    /** 初始化空的本轮分派结果。 */
    public BaseSchedulingAlgorithm() {
        this.scheduledList = new ArrayList();
    }

    /**
     * 设置当前 ready Job 列表；内部复制列表以稳定候选集合。
     *
     * <p><strong>防御性复制：</strong>调用方（调度器）传入的是其内部就绪队列的
     * 真实引用。部分历史算法（如 {@link FirstFitIdleSchedulingAlgorithm}）会在
     * {@link #run()} 内对 {@link #getCloudletList()} 排序；若不复制，排序残留会
     * 泄漏回调度器队列，破坏跨调度周期的到达序（FCFS 语义）。与
     * {@link #setVmList(List)} 保持一致的复制语义。
     */
    @Override
    public void setCloudletList(List list) {
        this.cloudletList = new ArrayList(list);
    }

    /** 设置当前 VM 列表；内部复制列表以稳定候选集合。 */
    @Override
    public void setVmList(List list) {
        this.vmList = new ArrayList(list);
    }

    /** 返回当前 ready Job 列表。 */
    @Override
    public List getCloudletList() {
        return this.cloudletList;
    }

    /** 返回当前 VM 列表。 */
    @Override
    public List getVmList() {
        return this.vmList;
    }

    /** 执行一次在线调度更新。 */
    @Override
    public abstract void run() throws Exception;

    /** 返回本轮被分派的 Job 列表。 */
    @Override
    public List getScheduledList() {
        return this.scheduledList;
    }

    /** 在任一已声明 VM 都不具备足够 PE 时，拒绝永远无法执行的 ready Job。 */
    protected final void validateCloudletCompatibility() {
        for (Object item : getCloudletList()) {
            Cloudlet cloudlet = (Cloudlet) item;
            if (!hasCompatibleVm(cloudlet)) {
                throw new IllegalArgumentException("Ready job " + cloudlet.getCloudletId()
                        + " requires " + cloudlet.getNumberOfPes()
                        + " processing element(s), but no declared VM is compatible");
            }
        }
    }

    protected final boolean isCompatible(Cloudlet cloudlet, CondorVM vm) {
        return cloudlet.getNumberOfPes() <= vm.getNumberOfPes();
    }

    protected final boolean hasCompatibleVm(Cloudlet cloudlet) {
        for (Object item : getVmList()) {
            if (isCompatible(cloudlet, (CondorVM) item)) {
                return true;
            }
        }
        return false;
    }

    /** 为等代价 VM 选择提供稳定的候选顺序：按 VM ID 升序。 */
    protected final List<CondorVM> sortedVmsById() {
        List<CondorVM> result = new ArrayList<CondorVM>();
        for (Object item : getVmList()) {
            result.add((CondorVM) item);
        }
        Collections.sort(result, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return result;
    }
}
