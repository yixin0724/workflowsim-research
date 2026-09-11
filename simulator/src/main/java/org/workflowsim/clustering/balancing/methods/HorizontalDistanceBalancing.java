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
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * 基于后继图距离的水平均衡聚类方法。
 *
 * <p>同层任务集合按到共同后继的距离选择候选目标，再以作业长度打破候选间的平局。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalDistanceBalancing extends HorizontalImpactBalancing {

    /**
     * 创建基于图距离的水平均衡方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public HorizontalDistanceBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 对每层任务集合执行基于图距离的合并。
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {
            process(taskList);
        }

    }

    /**
     * 预处理同层集合后，选择图距离最接近且容量允许的目标集合。
     *
     * @param taskList 待处理的同层任务集合
     */
    public void process(List<TaskSet> taskList) {

        if (taskList.size() > getClusterNum()) {
            List<TaskSet> jobList = new ArrayList<>();
            for (int i = 0; i < getClusterNum(); i++) {
                jobList.add(new TaskSet());
            }
            int clusters_size = taskList.size() / getClusterNum();
            if (clusters_size * getClusterNum() < taskList.size()) {
                clusters_size++;
            }
            // 此策略由距离预处理确定初始目标，而非按影响因子排序。
            preprocessing(taskList, jobList);

            for (TaskSet set : taskList) {
                // 候选选择器同时考虑图距离、容量与当前作业长度。
                TaskSet job = getCandidateTastSet(jobList, set, clusters_size);
                addTaskSet2TaskSet(set, job);
                job.addTask(set.getTaskList());
                job.setImpactFafctor(set.getImpactFactor());
                // 更新逻辑任务到新目标集合的映射。
                for (Task task : set.getTaskList()) {
                    getTaskMap().put(task, job); // 依赖关系由调用方统一重建
                    // 目标集合沿用当前策略定义的影响因子。
                }
            }
            taskList.clear(); // 原层集合已被目标作业集合替代
        }
    }

    /**
     * 按距离矩阵选择彼此最远的初始种子索引。
     *
     * @param distances 下三角距离矩阵
     * @param size 任务集合数量
     * @param num 所需种子数量
     * @return 种子任务集合的索引
     */
    private List<Integer> sortDistanceIncreasing(int[][] distances, int size, int num) {
        List<Integer> newList = new ArrayList<>();
        // 先选取距离最大的两个种子。
        int max = 0;
        int max_i = 0;
        int max_j = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < i; j++) {
                if (distances[i][j] > max) {
                    max = distances[i][j];
                    max_i = i;
                    max_j = j;
                }
            }
        }
        newList.add(max_i);
        newList.add(max_j);

        double max_dist = 0;
        max_i = 0;
        for (int id = 0; id < num - 2; id++) {
            max_dist = 0; // 每轮重新寻找与已选种子平均距离最大的候选
            for (int i = 0; i < size; i++) {
                double dist = getAvgDistance(i, newList, distances);
                if (max_dist < dist) {
                    max_dist = dist;
                    max_i = i;
                }
            }
            if (max_dist == max) {
                newList.add(max_i);
            }
        }
        return newList;
    }

    private double getAvgDistance(int id, List<Integer> list, int[][] distances) {
        if (list.isEmpty()) {
            double avg = 0.0;
            return avg;
        }
        double avg = 0.0;
        for (int nId : list) {
            if (nId > id) {
                avg += distances[nId][id];
            } else if (nId == id) {
                avg += 0.0;
            } else {
                avg += distances[id][nId];
            }
        }
        // 非空列表的平均距离。
        avg = avg / list.size();
        return avg;
    }

    private List<TaskSet> preprocessing(List<TaskSet> taskList, List<TaskSet> jobList) {
        int size = taskList.size();
        int[] record = new int[size];
        for (int i = 0; i < size; i++) {
            record[i] = -1;
        }
        int index_record = 0;

        int[][] distances = new int[size][size];

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < i; j++) {
                TaskSet setA = (TaskSet) taskList.get(i);
                TaskSet setB = (TaskSet) taskList.get(j);
                int distance = calDistance(setA, setB);

                distances[i][j] = distance;

            }
        }
        int job_index = 0;
        // 已被选为初始种子的索引记录在 record 中。
        List<Integer> idList = sortDistanceIncreasing(distances, size, jobList.size());
        for (int max_i : idList) {
            record[index_record] = max_i;
            index_record++;
            TaskSet set = (TaskSet) taskList.get(max_i);
            TaskSet job = jobList.get(job_index);
            addTaskSet2TaskSet(set, job);
            job.addTask(set.getTaskList());
            job.setImpactFafctor(set.getImpactFactor());
            // 更新逻辑任务到新目标集合的映射。
            for (Task task : set.getTaskList()) {
                getTaskMap().put(task, job); // 依赖关系由调用方统一重建
                // 保留策略定义的影响因子。
            }
            job_index++;
            if (job_index == jobList.size()) {
                break;
            }
        }

        /**
         * 先排序索引再逆序删除，避免移除时改变后续索引。
         */
        Arrays.sort(record);
        for (int i = size - 1; i >= 0 && record[i] >= 0; i--) {
            taskList.remove(record[i]);

        }
        return taskList;
    }

    private List<TaskSet> getNextPotentialTaskSets(List<TaskSet> taskList,
            TaskSet checkSet, int clusters_size) {
        int dis = Integer.MAX_VALUE;

        Map<Integer, List<TaskSet>> map = new HashMap<>();
        for (TaskSet set : taskList) {
            int distance = calDistance(checkSet, set);
            if (distance < dis) {
                dis = distance;
            }
            if (!map.containsKey(distance)) {
                map.put(distance, new ArrayList<>());
            }
            ArrayList<TaskSet> list = (ArrayList) map.get(distance);
            if (!list.contains(set)) {
                list.add(set);
            }
        }
        List returnList = new ArrayList<>();
        for (TaskSet set : map.get(dis)) {
            if (set.getTaskList().size() < clusters_size) {
                returnList.add(set);
            }
        }

        if (returnList.isEmpty()) {
            returnList.clear();
            for (TaskSet set : taskList) {
                if (set.getTaskList().isEmpty()) {
                    returnList.add(set);
                    return returnList;
                }
            }

            // 没有空集合时，逐步放宽到下一个最小距离层。
            while (returnList.isEmpty()) {
                map.remove(dis);
                List<Integer> keys = new ArrayList(map.keySet());
                int min = Integer.MAX_VALUE;
                for (int i : keys) {
                    if (min > i) {
                        min = i;
                    }
                }
                dis = min;

                for (TaskSet set : map.get(dis)) {
                    if (set.getTaskList().size() < clusters_size) {
                        returnList.add(set);
                    }
                }
            }
        }
        return returnList;
    }

    /**
     * 选择可容纳当前集合且图距离最小的候选目标。
     *
     * @param taskList 候选目标集合
     * @param checkSet 当前待合并集合
     * @param clusters_size 每个目标集合允许的最大任务数
     * @return 候选目标集合
     */
    protected TaskSet getCandidateTastSet(ArrayList<TaskSet> taskList,
            TaskSet checkSet,
            int clusters_size) {

        List<TaskSet> potential = getNextPotentialTaskSets(taskList, checkSet, clusters_size);
        TaskSet task = null;
        long min = Long.MAX_VALUE;
        for (TaskSet set : potential) {
            if (set.getJobRuntime() < min) {
                min = set.getJobRuntime();
                task = set;
            }
        }

        if (task != null) {
            return task;
        } else {
            return taskList.get(0);
        }
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
            return Integer.MAX_VALUE;
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
/*
 * 图距离及其两个集合索引的简单值对象。
 */

class Distance {

    int distance;
    int index_i;
    int index_j;

    public Distance(int distance, int index_i, int index_j) {
        this.distance = distance;
        this.index_i = index_i;
        this.index_j = index_j;
    }

    public int getIndexI() {
        return this.index_i;
    }

    public int getIndexJ() {
        return this.index_j;
    }

    public int getDistance() {
        return this.distance;
    }

}
