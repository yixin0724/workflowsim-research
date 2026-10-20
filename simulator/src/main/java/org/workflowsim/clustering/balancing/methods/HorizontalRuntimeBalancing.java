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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.utils.SimulationRandom;

/**
 * 按运行时间进行水平负载均衡的聚类方法。
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalRuntimeBalancing extends BalancingMethod {

    /**
     * 创建运行时间均衡方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public HorizontalRuntimeBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 将每层任务按长度从长到短放入当前长度最小的目标集合。
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {
            // 通过受控随机置换打破同长度候选的原始输入顺序。
            Collections.shuffle(taskList,
                    SimulationRandom.newJavaRandom("clustering.balancing.runtime"));
            Collections.shuffle(taskList,
                    SimulationRandom.newJavaRandom("clustering.balancing.runtime"));

            if (taskList.size() > getClusterNum()) {
                List<TaskSet> jobList = new ArrayList<>();
                for (int i = 0; i < getClusterNum(); i++) {
                    jobList.add(new TaskSet());
                }
                sortListDecreasing(taskList);
                for (TaskSet set : taskList) {
                    // 先按当前作业长度升序排列，首个集合即当前最短候选。
                    sortListIncreasing(jobList);
                    TaskSet job = (TaskSet) jobList.get(0);
                    job.addTask(set.getTaskList());
                    // 更新每个逻辑任务的所属集合映射。
                    for (Task task : set.getTaskList()) {
                        getTaskMap().put(task, job); // 依赖关系由调用方统一重建
                    }

                }

                taskList.clear(); // 原集合已被新的均衡集合替代
            } else {
                // 候选数不超过目标数时无需合并。
            }

        }
    }
    /**
     * 按作业长度升序排列任务集合。
     *
     * @param taskList 待排序的任务集合
     */
    private void sortListIncreasing(List<TaskSet> taskList) {
        Collections.sort(taskList, RUNTIME_INCREASING);
    }

    /**
     * 按作业长度降序排列任务集合。
     *
     * @param taskList 待排序的任务集合
     */
    private void sortListDecreasing(List<TaskSet> taskList) {
        Collections.sort(taskList, RUNTIME_DECREASING);
    }

    /**
     * 升序比较器：按 {@link TaskSet#getJobRuntime()} 升序。
     *
     * <p>审查工程 F-005：历史实现 {@code (int)(t1-t2)} 对 long 差窄化取低 32 位，
     * 差恰为 2^32 倍数的两个集合会被误判"相等"（稳定排序保持错误顺序）。
     * 该语义由 {@code BalancingComparatorContractTest} 按守恒律锁定；
     * 本字段是供其直接审计的单一实现。</p>
     */
    static final Comparator<TaskSet> RUNTIME_INCREASING = new Comparator<TaskSet>() {
        @Override
        public int compare(TaskSet t1, TaskSet t2) {
            // F-005 修复（2026-10-19）：Long.compare 全域正确，杜绝 (int)(long 差) 的
            // 2^32 回绕塌缩（差为 2^32 倍数被误判相等）。
            return Long.compare(t1.getJobRuntime(), t2.getJobRuntime());
        }
    };

    /** 降序比较器：与 {@link #RUNTIME_INCREASING} 同一排序标准的反向。 */
    static final Comparator<TaskSet> RUNTIME_DECREASING = new Comparator<TaskSet>() {
        @Override
        public int compare(TaskSet t1, TaskSet t2) {
            // F-005 修复（2026-10-19）：同上，全域正确的反向比较。
            return Long.compare(t2.getJobRuntime(), t1.getJobRuntime());
        }
    };
}
