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

/**
 * 调度策略的最小接口。
 *
 * <p>调用方在每个调度事件提供当前 ready Job 与 VM 列表，策略只返回本轮实际分派的
 * Job；接口本身不定义离线计划、未来可用时间或现实队列语义。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public interface SchedulingAlgorithmInterface {

    /** 设置当前 ready Job 列表。 */
    public void setCloudletList(List list);

    /** 设置当前 VM 列表。 */
    public void setVmList(List list);

    /** 返回当前 ready Job 列表。 */
    public List getCloudletList();

    /** 返回当前 VM 列表。 */
    public List getVmList();

    /** 执行一次调度更新。 */
    public void run() throws Exception;

    /** 返回本轮已分派的 Job。 */
    public List getScheduledList();
}
