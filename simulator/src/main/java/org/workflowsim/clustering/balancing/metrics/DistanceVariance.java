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
package org.workflowsim.clustering.balancing.metrics;

import java.util.LinkedList;
import java.util.List;
import org.workflowsim.clustering.TaskSet;

/**
 * 同层任务集合之间后继图距离的离散度指标。
 * 
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class DistanceVariance implements BalancingMetric {

    /**
     * 计算任务集合两两后继图距离的离散度。
     *
     * @param list 待检查的任务集合
     * @return 图距离离散度；集合不足两个时为零
     */
    @Override
    public double getMetric(List<TaskSet> list) {
        if (list == null || list.size() <= 1) {
            return 0.0;
        }
        double sum = 0;
        int size = list.size();
        int[] distances = new int[size * (size - 1) / 2];
        int index = 0;
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                TaskSet taskA = list.get(i);
                TaskSet taskB = list.get(j);
                int distance = calDistance(taskA, taskB);
                distances[index] = distance;
                index++;
                sum += distance;
            }
        }
        double mean = sum / list.size();
        sum = 0.0;
        for (int i = 0; i < index; i++) {
            int distance = distances[i];
            sum += Math.pow(distance - mean, 2);
        }

        return Math.sqrt(sum / list.size());
    }
    
    /**
     * 计算两个同层任务集合到共同后继的图距离。
     * 水平聚类假定两个集合位于同一 DAG 层，不适用于任意跨层任务对。
     *
     * @param taskA 第一个同层任务集合
     * @param taskB 第二个同层任务集合
     * @return 偶数形式的后继图距离
     */
    private int calDistance(TaskSet taskA, TaskSet taskB) {
        if (taskA == null || taskB == null || taskA == taskB) {
            return 0;
        }
        LinkedList<TaskSet> listA = new LinkedList<>();
        LinkedList<TaskSet> listB = new LinkedList<>();
        int distance = 0;
        listA.add(taskA);
        listB.add(taskB);

        if (taskA.getTaskList().isEmpty() || taskB.getTaskList().isEmpty()) {
            return 0;
        }
        do {

            LinkedList<TaskSet> copyA = (LinkedList) listA.clone();
            listA.clear();
            for (TaskSet set : copyA) {
                for (TaskSet child : set.getChildList()) {
                    if (!listA.contains(child)) {
                        listA.add(child);
                    }
                }
            }
            LinkedList<TaskSet> copyB = (LinkedList) listB.clone();
            listB.clear();
            for (TaskSet set : copyB) {
                for (TaskSet child : set.getChildList()) {
                    if (!listB.contains(child)) {
                        listB.add(child);
                    }
                }
            }

            for (TaskSet set : listA) {
                if (listB.contains(set)) {
                    return distance * 2;
                }
            }

            distance++;
        } while (!listA.isEmpty() && !listB.isEmpty());
        return distance * 2;
    }
}
