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

/**
 * 原名 MCTSchedulingAlgorithm。
 *
 * <p>注意:本实现并非经典 MCT(Maheswaran et al. 1999)。实际行为是
 * 贪心最快 VM:对每个任务选单 PE 速度 getMips() 最大的空闲 VM,
 * 不考虑任务长度。ready-batch MCT 请参考 {@link ReadyBatchMCTSchedulingAlgorithm}。</p>
 *
 * <p><strong>兼容边界：</strong>仅保留给历史源码兼容；标准研究入口拒绝该标签，
 * 不能将其结果解释为经典 MCT。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @deprecated 行为与经典 MCT 不一致,已改名 {@link FastestVmSchedulingAlgorithm}。
 *             ready-batch 实现见 {@link ReadyBatchMCTSchedulingAlgorithm}。
 */
@Deprecated
public class MCTSchedulingAlgorithm extends FastestVmSchedulingAlgorithm {

    public MCTSchedulingAlgorithm() {
        super();
    }
}
