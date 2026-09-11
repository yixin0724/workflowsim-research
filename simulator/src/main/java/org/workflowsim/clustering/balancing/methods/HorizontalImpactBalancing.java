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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * 基于影响因子相近性的水平均衡聚类方法。
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalImpactBalancing extends BalancingMethod {

    /**
     * 创建基于影响因子的水平均衡方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public HorizontalImpactBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 对每层任务集合执行影响因子相近的合并。
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        for (List<TaskSet> taskList : map.values()) {
            process(taskList);
        }

    }

    /**
     * 按影响因子处理同层集合，并合并相近候选。
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
            if(clusters_size * getClusterNum() < taskList.size()){
                clusters_size ++;
            }
            sortListDecreasing(taskList);
            for (TaskSet set : taskList) {
                // 目标选择器优先选择影响因子相同的可容纳集合。
                TaskSet job = null;
                try{
                    job = getCandidateTastSet(jobList, set, clusters_size);
                }catch(Exception e) {
                    e.printStackTrace();
                }
                addTaskSet2TaskSet(set, job);
                job.addTask(set.getTaskList());
                job.setImpactFafctor(set.getImpactFactor());
                // 更新逻辑任务到新目标集合的映射。
                for (Task task : set.getTaskList()) {
                    getTaskMap().put(task, job); // 依赖关系由调用方统一重建
                }
            }
            taskList.clear();
        } 
    }

    /**
     * 按作业长度升序排列任务集合。
     *
     * @param taskList 待排序的任务集合
     */
    private void sortListIncreasing(List taskList) {
        Collections.sort(taskList, new Comparator<TaskSet>() {
            @Override
            public int compare(TaskSet t1, TaskSet t2) {
                // 升序：较短的集合排在前面。
                return (int) (t1.getJobRuntime() - t2.getJobRuntime());

            }
        });

    }
    /**
     * 按影响因子降序、再按作业长度排序任务集合。
     *
     * @param taskList 待排序的任务集合
     */
    private void sortListDecreasing(List taskList) {
        
        Collections.sort(taskList, new Comparator<TaskSet>() {
        @Override
        public int compare(TaskSet t1, TaskSet t2) {
            // 影响因子较高的集合优先处理。
            if(Math.abs(t2.getImpactFactor() - t1.getImpactFactor()) > 1.0e-8){
                if(t1.getImpactFactor() > t2.getImpactFactor()){
                    return 1;
                }else if(t1.getImpactFactor() < t2.getImpactFactor()){
                    return -1;
                }else{
                    return 0;
                }                        
            }
            else{
                if (t1.getJobRuntime() > t2.getJobRuntime()){
                    return 1;
                }else if (t1.getJobRuntime() < t2.getJobRuntime()) {
                    return -1;
                }else{
                    return 0;
                }
            }

        }
        });
    
    }
    
    private List<TaskSet> getNextPotentialTaskSets(List<TaskSet> taskList, 
                                            TaskSet checkSet, int clusters_size){

        Map<Double, List<TaskSet>> map = new HashMap<>();
        
        for (TaskSet set : taskList) {
                double factor = set.getImpactFactor();

                if(!map.containsKey(factor)){
                    map.put(factor, new ArrayList<>());
                }
                List<TaskSet> list = map.get(factor);
                if(!list.contains(set)){
                    list.add(set);
                }
        }
        List<TaskSet> returnList = new ArrayList<> ();
        List<TaskSet> mapSet = map.get(checkSet.getImpactFactor());
        if(mapSet!=null && !mapSet.isEmpty()){
            for(TaskSet set: mapSet){
                if(set.getTaskList().size() < clusters_size){
                    returnList.add(set);
                }
            }
        }
        
        if(returnList.isEmpty()){
            List<TaskSet> zeros = map.get(0.0);
            if(zeros!=null && !zeros.isEmpty())
            {
                returnList.addAll(zeros);
            }
        }
        
        if(returnList.isEmpty()){
            returnList.clear(); // 转而寻找空目标集合或下一组相近影响因子
            for (TaskSet set : taskList) {
                if(set.getTaskList().isEmpty()){
                    returnList.add(set);
                    return returnList;
                }
            }
            map.remove(checkSet.getImpactFactor());
            // 没有空集合时，逐步尝试下一个影响因子候选。
            while(returnList.isEmpty() ){
                
                List<Double> keys = new ArrayList(map.keySet());
                double min = Double.MAX_VALUE;
                
                double min_i = -1;
                for(double i: keys){
                    double distance = Math.abs(i - checkSet.getImpactFactor());
                    if (distance < min){
                        min = distance;
                        min_i = i;
                    }
                }
                if(min_i>=0){
                    for(TaskSet set: map.get(min_i)){
                        if(set.getTaskList().size() < clusters_size){
                            returnList.add(set);
                        }
                    }
                }else{
                    return null;
                }
                map.remove(min_i);
            }
        }
        return returnList;            

    }
    
    
    /**
     * 从可合并候选集合中选择运行时间最长的任务集合。
     *
     * @param taskList 当前层级的任务集合
     * @param checkSet 正在扩展的任务集合
     * @param clusters_size 单个聚类允许的最大任务数
     * @return 选中的候选集合；没有更优候选时返回列表首项
     */
    protected TaskSet getCandidateTastSet(List<TaskSet> taskList, 
                                            TaskSet checkSet, 
                                            int clusters_size) {
        
        
        
        List<TaskSet> potential = null;
        try{
            potential=getNextPotentialTaskSets(taskList, checkSet,  clusters_size);
        }catch (Exception e)
        {
            e.printStackTrace();
        }
        TaskSet task = null;
        long max = Long.MIN_VALUE;
        for(TaskSet set: potential){
            if(set.getJobRuntime() > max){
                max = set.getJobRuntime();
                task = set;
            }
        }

        if (task != null) {
            return task;
        } else {
            return taskList.get(0);
        }
    }
}
