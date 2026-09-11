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
package org.workflowsim.clustering;

import java.util.List;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;

/**
 * 所有任务聚类策略的统一接口。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public interface ClusteringInterface {

    /**
     * 设置待聚类的逻辑任务列表。
     *
     * @param list 待聚类任务
     */
    public void setTaskList(List<Task> list);

    /**
     * 返回聚类后生成的作业列表。
     *
     * @return 聚类作业列表
     */
    public List<Job> getJobList();

    /**
     * 返回当前逻辑任务列表。
     *
     * @return 逻辑任务列表
     */
    public List<Task> getTaskList();

    /**
     * 执行聚类，并生成作业及其依赖关系。
     */
    public void run();

    /**
     * 返回聚类过程收集的任务文件引用。
     *
     * @return 任务文件列表
     */
    public List<FileItem> getTaskFiles();
}
