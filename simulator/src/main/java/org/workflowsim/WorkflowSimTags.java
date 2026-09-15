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

/**
 * WorkflowSim 自定义事件标签。
 *
 * <p>这些值不属于 CloudSim 的 {@code CloudSimTags}，用于工作流引擎、调度器与
 * 数据中心之间的内部事件通信。标签值必须保持稳定，以避免与 CloudSim 事件编号冲突。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowSimTags {

    /** 工作流专用事件编号的起始值。 */
    private static final int BASE = 1000;
    /** 虚拟机准备状态，当前保留但未使用。 */
    public static final int VM_STATUS_READY = BASE + 2;
    /** 虚拟机忙碌状态；调度器不应向该虚拟机提交新作业。 */
    public static final int VM_STATUS_BUSY = BASE + 3;
    /** 虚拟机空闲状态；可以接收新作业。 */
    public static final int VM_STATUS_IDLE = BASE + 4;
    public static final int START_SIMULATION = BASE + 0;
    public static final int JOB_SUBMIT = BASE + 1;
    public static final int CLOUDLET_UPDATE = BASE + 5;
    public static final int CLOUDLET_CHECK = BASE + 6;
    /**
     * 执行前传输延迟模型下，引擎在数据就绪时启动输入传输，传输完成后用本标签
     * 释放作业进入派发流程（见 {@link org.workflowsim.data.DataMovementModel}
     * 的 {@code PRE_EXECUTION_TRANSFER_DELAY_V1}）。
     */
    public static final int JOB_STAGE_IN_COMPLETE = BASE + 7;
    /**
     * 链路争用数据移动模型（{@code PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1}）
     * 下，引擎向自己发送的流体传输推进检查事件：按当前时钟积分推进所有活动传输，
     * 结算已完成的传输组并在仍有活动传输时重排下一次检查。
     */
    public static final int TRANSFER_CONTENTION_CHECK = BASE + 8;

    /**
     * R5 动态到达：多工作流错峰提交时，引擎向自己发送的到达重扫事件。
     * 在最早未到达工作流的提交时刻触发一次幂等就绪扫描，释放该时刻已到达
     * 工作流的根作业。
     */
    public static final int WORKFLOW_ARRIVAL_SCAN = BASE + 9;

    /** 禁止实例化常量类。 */
    private WorkflowSimTags() {
        throw new UnsupportedOperationException("WorkflowSim Tags cannot be instantiated");
    }
}
