/*
 * 
 *  Copyright 2012-2013 University Of Southern California
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.utils.SimulationRandom;

/**
 * 随机水平聚类方法。
 *
 * <p>该方法在受控随机置换后以轮转方式把同层任务集合分配到目标聚类作业。</p>
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalRandomClustering extends BalancingMethod {

    /**
     * 创建随机水平聚类方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public HorizontalRandomClustering(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 置换每层候选集合后按轮转规则分配任务。
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {
            // 使用会话种子派生的随机流打破输入顺序。
            Collections.shuffle(taskList,
                    SimulationRandom.newJavaRandom("clustering.balancing.random"));
            Collections.shuffle(taskList,
                    SimulationRandom.newJavaRandom("clustering.balancing.random"));

            if (taskList.size() > getClusterNum()) {
                List<TaskSet> jobList = new ArrayList<>();
                for (int i = 0; i < getClusterNum(); i++) {
                    jobList.add(new TaskSet());
                }
                int index = 0;
                for (TaskSet set : taskList) {
                    // 轮转选择下一个目标集合。
                    TaskSet job = (TaskSet) jobList.get(index);
                    index ++ ;
                    if(index == getClusterNum()){
                        index = 0;
                    }
                    job.addTask(set.getTaskList());
                    // 更新每个逻辑任务的所属集合映射。
                    for (Task task : set.getTaskList()) {
                        getTaskMap().put(task, job); // 依赖关系由调用方统一重建
                    }

                }
                taskList.clear();
            } else {
                // 候选数不超过目标数时无需合并。
            }

        }
    }
    
}
