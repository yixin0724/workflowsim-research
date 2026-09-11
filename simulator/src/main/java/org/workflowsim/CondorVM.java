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
package org.workflowsim;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Vm;

/**
 * WorkflowSim 的虚拟机（VM）模型。
 *
 * <p>该类在 CloudSim {@link Vm} 的资源属性之外保存工作流调度所需的空闲/忙碌状态和
 * 资源计费参数。它不自行模拟宿主机级 CPU、网络或存储资源争用。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class CondorVM extends Vm {

    /**
     * 虚拟机状态，只能使用 {@link WorkflowSimTags} 中的 IDLE、READY（当前保留）或
     * BUSY 标签。
     */
    private int state;

    /** 内存资源单价。 */
    private double costPerMem = 0.0;

    /** 带宽资源单价。 */
    private double costPerBW = 0.0;

    /** 存储资源单价。 */
    private double costPerStorage = 0.0;

    /** CPU 资源单价。 */
    private double cost = 0.0;

    /**
     * 创建一个未显式配置费用的虚拟机。
     *
     * @param id 虚拟机唯一编号
     * @param userId 虚拟机所属用户编号
     * @param mips 每秒百万条指令处理能力
     * @param numberOfPes CPU 处理单元数
     * @param ram 内存容量
     * @param bw 带宽容量
     * @param size 存储容量
     * @param vmm 虚拟机监控器名称
     * @param cloudletScheduler Cloudlet 调度策略
     */
    public CondorVM(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            CloudletScheduler cloudletScheduler) {
        super(id, userId, mips, numberOfPes, ram, bw, size, vmm, cloudletScheduler);
        /*
         * 会话开始时所有虚拟机均可接收作业。
         */
        setState(WorkflowSimTags.VM_STATUS_IDLE);
    }

    /**
     * 创建一个带资源计费参数的虚拟机。
     *
     * @param id 虚拟机唯一编号
     * @param userId 虚拟机所属用户编号
     * @param mips 每秒百万条指令处理能力
     * @param numberOfPes CPU 处理单元数
     * @param ram 内存容量
     * @param bw 带宽容量
     * @param size 存储容量
     * @param vmm 虚拟机监控器名称
     * @param cost CPU 单价
     * @param costPerMem 内存单价
     * @param costPerStorage 存储单价
     * @param costPerBW 带宽单价
     * @param cloudletScheduler Cloudlet 调度策略
     */
    public CondorVM(
            int id,
            int userId,
            double mips,
            int numberOfPes,
            int ram,
            long bw,
            long size,
            String vmm,
            double cost,
            double costPerMem,
            double costPerStorage,
            double costPerBW,
            CloudletScheduler cloudletScheduler) {
        this(id, userId, mips, numberOfPes, ram, bw, size, vmm, cloudletScheduler);
        this.cost = cost;
        this.costPerBW = costPerBW;
        this.costPerMem = costPerMem;
        this.costPerStorage = costPerStorage;
    }

    /** @return CPU 资源单价 */
    public double getCost() {
        return this.cost;
    }

    /** @return 带宽资源单价 */
    public double getCostPerBW() {
        return this.costPerBW;
    }

    /** @return 存储资源单价 */
    public double getCostPerStorage() {
        return this.costPerStorage;
    }

    /** @return 内存资源单价 */
    public double getCostPerMem() {
        return this.costPerMem;
    }

    /**
     * 设置虚拟机状态。
     *
     * @param tag {@link WorkflowSimTags} 中定义的虚拟机状态标签
     */
    public final void setState(int tag) {
        this.state = tag;
    }

    /** @return 当前虚拟机状态标签 */
    public final int getState() {
        return this.state;
    }
}
