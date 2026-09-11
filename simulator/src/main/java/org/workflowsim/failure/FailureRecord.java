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
package org.workflowsim.failure;

/**
 * 一条任务执行失败观测记录，供监控器估计失败率和恢复策略使用。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureRecord {

    /**
     * 该记录对应任务的运行时间长度。
     */
    public double length;
    /**
     * 失败任务数量。
     */
    public int failedTasksNum;
    /**
     * 失败任务的 DAG 深度（旧版部分路径也将其作为 type）。
     */
    public int depth; // 旧版部分路径将深度作为 type。
    /**
     * 该记录覆盖的全部任务数（包括成功与失败）。
     */
    public int allTaskNum;
    /**
     * 发生失败的 VM 标识。
     */
    public int vmId;
    /**
     * 发生失败的 Job 标识。
     */
    public int jobId;
    /**
     * 工作流标识（当前旧接口中对应 user id）。
     */
    public int workflowId;
    /**
     * 恢复时考虑的延迟开销。
     */
    public double delayLength;

    /**
     * 创建失败观测记录。
     *
     * @param length 任务运行时间长度
     * @param tasks 失败任务数量
     * @param depth 失败任务的 DAG 深度
     * @param all 覆盖的全部任务数量
     * @param vm 发生失败的 VM 标识
     * @param job 发生失败的 Job 标识
     * @param workflow 发生失败的工作流标识
     */
    public FailureRecord(double length, int tasks, int depth, int all, int vm, int job, int workflow) {
        this.length = length;
        this.failedTasksNum = tasks;
        this.depth = depth;
        this.allTaskNum = all;
        this.vmId = vm;
        this.jobId = job;
        this.workflowId = workflow;
    }
}
