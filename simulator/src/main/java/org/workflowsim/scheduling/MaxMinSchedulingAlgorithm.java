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
 * 原名 MaxMinSchedulingAlgorithm。
 *
 * <p>注意:本实现并非经典 Max-Min(Braun et al. 2001)。实际行为是
 * LJF(Longest Job First)- fastest-idle 变体:先选最长任务,
 * 再选最快的空闲 VM。ready-batch Max-Min 请参考 {@link ReadyBatchMaxMinSchedulingAlgorithm}。</p>
 *
 * <p><strong>兼容边界：</strong>仅保留给历史源码兼容；标准研究入口拒绝该标签，
 * 不得以此类结果声称经典 Max-Min。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 * @deprecated 行为与经典 Max-Min 不一致,已改名 {@link LjfFastestIdleSchedulingAlgorithm}。
 *             ready-batch 实现见 {@link ReadyBatchMaxMinSchedulingAlgorithm}。
 */
@Deprecated
public class MaxMinSchedulingAlgorithm extends LjfFastestIdleSchedulingAlgorithm {

    public MaxMinSchedulingAlgorithm() {
        super();
    }
}
