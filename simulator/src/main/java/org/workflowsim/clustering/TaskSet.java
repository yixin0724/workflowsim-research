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

import java.util.ArrayList;
import java.util.List;
import org.workflowsim.Task;

/**
 * 聚类算法内部使用的一组逻辑任务。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class TaskSet {

    /**
     * 此集合中的逻辑任务。
     */
    private final List<Task> taskList;
    /**
     * 此任务集合的父集合。
     */
    private final List<TaskSet> parentList;
    /**
     * 此任务集合的子集合。
     */
    private final List<TaskSet> childList;
    /**
     * 遍历时使用的已检查标记。
     */
    public boolean hasChecked;
    /**
     * 聚类启发式使用的影响因子。
     */
    private double impactFactor;

    /**
     * 创建空的任务集合。
     */
    public TaskSet() {
        this.taskList = new ArrayList<>();
        this.parentList = new ArrayList<>();
        this.childList = new ArrayList<>();
        this.hasChecked = false;
        this.impactFactor = 0.0;
    }

    /**
     * 返回影响因子。
     *
     * @return 影响因子
     */
    public double getImpactFactor() {
        return this.impactFactor;
    }

    /**
     * 设置影响因子。
     *
     * @param factor 影响因子
     */
    public void setImpactFafctor(double factor) {
        this.impactFactor = factor;
    }

    /**
     * 返回父任务集合。
     *
     * @return 父任务集合
     */
    public List<TaskSet> getParentList() {
        return this.parentList;
    }

    /**
     * 返回子任务集合。
     *
     * @return 子任务集合
     */
    public List<TaskSet> getChildList() {
        return this.childList;
    }

    /**
     * 返回集合中的逻辑任务。
     *
     * @return 逻辑任务列表
     */
    public List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * 向此集合加入一个任务。
     *
     * @param task 要加入的任务
     */
    public void addTask(Task task) {
        this.taskList.add(task);
    }

    /**
     * 向此集合加入一批任务。
     *
     * @param list 要加入的任务列表
     */
    public void addTask(List<Task> list) {
        this.taskList.addAll(list);
    }

    /**
     * 返回此集合的作业长度，即所有任务长度之和。
     *
     * @return 作业长度
     */
    public long getJobRuntime() {
        long runtime = 0;
        for (Task task : taskList) {
            runtime += task.getCloudletLength();
        }
        return runtime;
    }
}
