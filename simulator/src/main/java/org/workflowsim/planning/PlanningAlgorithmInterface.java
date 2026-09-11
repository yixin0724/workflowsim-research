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

import java.util.List;

/**
 * 静态规划策略的最小接口。
 *
 * <p>接口只约定输入 Task/VM 集合和规划入口；它本身不承诺生成完整 DAG 执行顺序、
 * 网络校准结果或真实平台回放。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 18, 2013
 */
public interface PlanningAlgorithmInterface {

    /** 设置待规划 Task 列表。 */
    public void setTaskList(List list);

    /** 设置 VM 列表。 */
    public void setVmList(List list);

    /** 返回待规划 Task 列表。 */
    public List getTaskList();

    /** 返回 VM 列表。 */
    public List getVmList();

    /** 执行一次离线规划。 */
    public void run() throws Exception;


}
