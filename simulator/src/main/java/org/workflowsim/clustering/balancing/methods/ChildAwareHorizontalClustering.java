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
import org.workflowsim.clustering.AbstractArrayList;
import org.workflowsim.clustering.TaskSet;

/**
 * 子节点感知的水平均衡方法。
 *
 * <p>当同层两个任务集合共享可合并的父集合时，该策略将其合并，并向后续层传播
 * 检查状态。</p>
 * 
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ChildAwareHorizontalClustering extends BalancingMethod {

    /**
     * 创建子节点感知的水平均衡方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public ChildAwareHorizontalClustering(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 从任务集合数量最多的层开始执行子节点感知合并。
     */
    @Override
    public void run() {
        Map<Integer, List<TaskSet>> map = getLevelMap();
        Map<List<TaskSet>, AbstractArrayList> tmpMap = new HashMap();
        for (Map.Entry entry : map.entrySet()) {
            int depth = (Integer) entry.getKey();
            ArrayList<TaskSet> list = (ArrayList) entry.getValue();
            AbstractArrayList abList = new AbstractArrayList(list, depth);
            tmpMap.put(list, abList);
        }
        List<AbstractArrayList> abList = new ArrayList(tmpMap.values());
        sortMap(abList);
        for (AbstractArrayList list : abList) {
            if (!list.hasChecked) {
                boolean hasClustered = CHBcheckLevel(list.getArrayList());
                //Log.printLine("Depth:"+list.getDepth());
                if (hasClustered) {
                    list.hasChecked = true;
                    // 继续检查后续层，传播本次合并带来的关系变化。
                    int depth = list.getDepth();

                    int i = depth + 1;
                    while (map.containsKey(i)) {
                        List<TaskSet> tsList = map.get(i);
                        CHBcheckLevel(tsList);
                        AbstractArrayList tsAbList = tmpMap.get(tsList);
                        tsAbList.hasChecked = true;
                        i++;
                    }
                }
            }
        }
        // 在方法退出前恢复任务集合的遍历标记。
        cleanTaskSetChecked();
    }

    /**
     * 按任务集合大小降序排列层列表。
     *
     * @param list 待排序的层列表
     */
    private void sortMap(List<AbstractArrayList> list) {
        Collections.sort(list, new Comparator<AbstractArrayList>() {
            @Override
            public int compare(AbstractArrayList l1, AbstractArrayList l2) {

                return (int) (l2.getArrayList().size() - l1.getArrayList().size());
            }
        });

    }

    /**
     * 处理同一层的任务集合，并尝试合并共享父集合的候选项。
     *
     * @param taskList 待处理的任务集合
     * @return 是否执行过合并
     */
    private boolean CHBcheckLevel(List<TaskSet> taskList) {
        boolean hasClustered = false;
        for (TaskSet setA : taskList) {
            setA.hasChecked = false; // 确保本层从干净的遍历状态开始
        }
        for (int i = 0; i < taskList.size(); i++) {
            TaskSet setA =  taskList.get(i);
            if (!setA.hasChecked) {
                for (int j = i + 1; j < taskList.size(); j++) {
                    TaskSet setB = taskList.get(j);
                    if (!setB.hasChecked) {

                        TaskSet kid = CHBhasOneParent(setA, setB);
                        if (kid != null) {
                            if (true) { // 历史实现未额外施加运行时间约束
                                setA.hasChecked = true; // 标记已参与本轮处理
                                setB.hasChecked = true;
                                addTaskSet2TaskSet(setA, setB);
                                hasClustered = true;
                            }
                        }
                    }
                }
            }
        }
        return hasClustered;
    }

    /**
     * 判断两个任务集合是否拥有同一个唯一子集合。
     *
     * @param setA 第一个待比较集合
     * @param setB 第二个待比较集合
     * @return 共享的唯一子集合；不存在时返回 {@code null}
     */
    private TaskSet CHBhasOnlyChild(TaskSet setA, TaskSet setB) {

        if (setA.getChildList().size() == 1 && setB.getChildList().size() == 1) {
            TaskSet kidA = setA.getChildList().get(0);
            TaskSet kidB = setB.getChildList().get(0);
            if (kidA.equals(kidB)) {
                return kidA;
            }
        }
        return null;
    }

    /**
     * 判断两个任务集合是否拥有同一个唯一父集合。
     *
     * @param setA 第一个待比较集合
     * @param setB 第二个待比较集合
     * @return 共享的唯一父集合；不存在时返回 {@code null}
     */
    private TaskSet CHBhasOnlyParent(TaskSet setA, TaskSet setB) {

        if (setA.getParentList().size() == 1 && setB.getParentList().size() == 1) {
            TaskSet kidA = setA.getParentList().get(0);
            TaskSet kidB = setB.getParentList().get(0);
            if (kidA.equals(kidB)) {
                return kidA;
            }
        }
        return null;
    }
    
    /**
     * 查找两个任务集合的任意共同父集合。
     *
     * @param setA 第一个待比较集合
     * @param setB 第二个待比较集合
     * @return 共同父集合；不存在时返回 {@code null}
     */
    private TaskSet CHBhasOneParent(TaskSet setA, TaskSet setB) {
        for (TaskSet parentA : setA.getParentList()) {
            for (TaskSet parentB : setB.getParentList()) {
                if (parentA.equals(parentB)) {
                    return parentA;
                }
            }
        }
        return null;
    }
}
