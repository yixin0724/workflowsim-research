/*
 * 
 *   Copyright 2012-2013 University Of Southern California
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */
package org.workflowsim.clustering.balancing.methods;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * 所有均衡聚类方法的公共基类。
 * 
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BalancingMethod {

    /** 逻辑任务到任务集合的映射。 */
    private final Map<Task, TaskSet> taskMap;
    
    /**
     * 按 DAG 层组织的任务集合。
     */
    private final Map<Integer, List<TaskSet>> levelMap;
    /** 每层目标聚类作业数。 */
    private final int clusterNum;

    /**
     * 创建均衡方法的公共状态。
     *
     * @param levelMap 每层的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public BalancingMethod(Map levelMap, Map taskMap, int clusterNum) {
        this.taskMap = taskMap;
        this.levelMap = levelMap;
        this.clusterNum = clusterNum;
    }

    /**
     * 返回逻辑任务到任务集合的映射。
     *
     * @return 任务映射
     */
    public Map<Task, TaskSet> getTaskMap() {
        return this.taskMap;
    }

    /**
     * 返回按层组织的任务集合。
     *
     * @return 层级映射
     */
    public Map<Integer, List<TaskSet>> getLevelMap() {
        return this.levelMap;
    }

    /**
     * 返回每层目标聚类作业数。
     *
     * @return {@code clusters.num}
     */
    public int getClusterNum() {
        return this.clusterNum;
    }
    
    /**
     * 将 {@code tail} 中全部任务合并到 {@code head}，并同步更新任务映射和
     * 相邻集合关系。该操作可被垂直均衡策略复用。
     *
     * @param tail 待合并并清空的集合
     * @param head 接收任务的集合
     */
    public void addTaskSet2TaskSet(TaskSet tail, TaskSet head) {
        head.addTask(tail.getTaskList());
        head.getParentList().remove(tail);
        // 任务映射必须与合并后的集合保持一致。
        for (Task task : tail.getTaskList()) {
            getTaskMap().put(task, head);
        }
        /*
         * 同层合并可直接累加影响因子；垂直均衡通常不以影响因子作为决策依据，
         * 但仍保持该值的一致性。
         */
        head.setImpactFafctor(head.getImpactFactor() + tail.getImpactFactor());
        for (TaskSet taskSet : tail.getParentList()) {
            taskSet.getChildList().remove(tail);
            if (!taskSet.getChildList().contains(head)) {
                taskSet.getChildList().add(head);
            }
            if (!head.getParentList().contains(taskSet)) {
                head.getParentList().add(taskSet);
            }
        }

        for (TaskSet taskSet : tail.getChildList()) {
            taskSet.getParentList().remove(tail);
            if (!taskSet.getParentList().contains(head)) {
                taskSet.getParentList().add(head);
            }
            if (!head.getChildList().contains(taskSet)) {
                head.getChildList().add(taskSet);
            }
        }
        tail.getTaskList().clear();
        tail.getChildList().clear();
        tail.getParentList().clear();
    }
    /**
     * 基类不提供具体均衡策略，子类必须覆盖此方法。
     */
    public void run() {
        throw (new RuntimeException("Should not use this function"));
    }

    /**
     * 清除所有任务集合的遍历标记。
     */
    public void cleanTaskSetChecked() {
        Collection sets = getTaskMap().values();
        for (Iterator it = sets.iterator(); it.hasNext();) {
            TaskSet set = (TaskSet) it.next();
            set.hasChecked = false;
        }
    }
}
