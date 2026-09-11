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

/**
 * 原名 RoundRobinSchedulingAlgorithm。
 *
 * <p>注意:本实现并非经典 Round-Robin。声明的 vmIndex 是死代码,
 * 实际行为是 First-Fit-Idle(按 vmId 排序后从头找第一个空闲 VM)。
 * ready-batch Round-Robin 请参考 {@link ReadyBatchRoundRobinSchedulingAlgorithm}。</p>
 *
 * <p><strong>兼容边界：</strong>仅保留给历史源码兼容；标准研究入口拒绝该标签，
 * 不得以此类结果声称经典 Round-Robin。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date May 12, 2014
 * @deprecated vmIndex 死代码,实际 First-Fit-Idle,已改名
 *             {@link FirstFitIdleSchedulingAlgorithm}。
 *             ready-batch 实现见 {@link ReadyBatchRoundRobinSchedulingAlgorithm}。
 */
@Deprecated
public class RoundRobinSchedulingAlgorithm extends FirstFitIdleSchedulingAlgorithm {

    public RoundRobinSchedulingAlgorithm() {
        super();
    }
}
