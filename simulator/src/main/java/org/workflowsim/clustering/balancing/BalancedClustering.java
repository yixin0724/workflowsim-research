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
package org.workflowsim.clustering.balancing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.Task;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.clustering.TaskSet;
import org.workflowsim.clustering.balancing.methods.ChildAwareHorizontalClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalDistanceBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalImpactBalancing;
import org.workflowsim.clustering.balancing.methods.HorizontalRandomClustering;
import org.workflowsim.clustering.balancing.methods.HorizontalRuntimeBalancing;
import org.workflowsim.clustering.balancing.methods.VerticalBalancing;
import org.workflowsim.clustering.balancing.metrics.DistanceVariance;
import org.workflowsim.clustering.balancing.metrics.HorizontalRuntimeVariance;
import org.workflowsim.clustering.balancing.metrics.ImpactFactorVariance;
import org.workflowsim.clustering.balancing.metrics.PipelineRuntimeVariance;
import org.workflowsim.utils.Parameters;

/**
 * 均衡聚类的公共实现：在保持任务依赖关系的同时，按不同启发式平衡作业长度、
 * 影响因子或图距离。
 *
 * <p>具体均衡方法由 {@code ClusteringParameters.code} 选择。该实现描述的是抽象
 * 聚类策略，不代表真实资源负载均衡或平台利用率预测。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BalancedClustering extends BasicClustering {

    /**
     * 每层期望生成的聚类作业数。
     */
    private final int clusterNum;
    /**
     * 逻辑任务到所属任务集合的映射。
     */
    private final Map<Task, TaskSet> mTask2TaskSet;
    /**
     * 任务集合到其计算深度的缓存。
     */
    private final Map<TaskSet, Integer> mTaskSet2Depth;

    /**
     * 创建均衡聚类器。
     *
     * @param clusterNum 每层聚类作业数（{@code clusters.num}）
     */
    public BalancedClustering(int clusterNum) {
        super();
        this.clusterNum = clusterNum;
        this.mTask2TaskSet = new HashMap<>();
        mTaskSet2Depth = new HashMap<>();
    }

    /**
     * 清除所有任务集合的遍历标记。
     *
     */
    public void cleanTaskSetChecked() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            set.hasChecked = false;
        }
    }

    /**
     * 将影响因子沿父集合方向递归分摊。
     *
     * @param set 起始任务集合
     * @param impact 要加入的影响因子
     */
    private void addImpact(TaskSet set, double impact) {
        /*
         * 从当前集合向其父集合传播影响因子。
         */
        set.setImpactFafctor(set.getImpactFactor() + impact);
        int size = set.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (TaskSet parent : set.getParentList()) {
                addImpact(parent, avg);
            }
        }
    }

    /**
     * 计算并输出当前任务集合的均衡指标。
     */
    public void printMetrics() {
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        for (TaskSet set : mTask2TaskSet.values()) {
            set.setImpactFafctor(0.0);
        }

        int maxDepth = 0;
        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            if (depth > maxDepth) {
                maxDepth = depth;
            }
        }
        List<TaskSet> exits = map.get(maxDepth);
        double avg = 1.0 / exits.size();
        for (TaskSet set : exits) {
            // 旧版可直接赋值影响因子；当前路径通过递归传播影响。
            addImpact(set, avg);
        }

        for (Entry<Integer, List<TaskSet>> entry : map.entrySet()) {
            int depth = entry.getKey();
            List<TaskSet> list = entry.getValue();
            /**
             * 同层运行时间方差。
             */
            double hrv = new HorizontalRuntimeVariance().getMetric(list);
            /**
             * 影响因子方差。
             */
            double ifv = new ImpactFactorVariance().getMetric(list);
            /**
             * 流水线运行时间方差。
             */
            double prv = new PipelineRuntimeVariance().getMetric(list);
            /**
             * 图距离方差。
             */
            double dv = new DistanceVariance().getMetric(list);
            Log.printLine("HRV " + depth + " " + list.size()
                    + " " + hrv + "\nIFV " + depth + " "
                    + list.size() + " " + ifv + "\nPRV " + depth
                    + " " + list.size() + " " + prv + "\nDV " + depth + " " + list.size() + " " + dv);

        }
    }

    /**
     * 返回按计算深度分组的当前任务集合。
     *
     * @return 每层的任务集合列表
     */
    public Map<Integer, List<TaskSet>> getCurrentTaskSetAtLevels() {
        // 计算前后都恢复 hasChecked，避免状态泄漏到下一个均衡步骤。
        Map<Integer, List<TaskSet>> map = new HashMap<>();
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                int depth = getDepth(set);
                if (!map.containsKey(depth)) {
                    map.put(depth, new ArrayList<>());
                }
                List list = map.get(depth);
                list.add(set);

            }
        }
        mTaskSet2Depth.clear();
        // 必须恢复遍历标记，供后续算法复用。
        cleanTaskSetChecked();
        return map;
    }

    /**
     * 递归计算任务集合的 DAG 深度，并缓存结果。
     *
     * @param set 任务集合
     * @return DAG 深度
     */
    private int getDepth(TaskSet set) {
        if (mTaskSet2Depth.containsKey(set)) {
            return mTaskSet2Depth.get(set);
        } else {
            int depth = 0;
            for (TaskSet parent : set.getParentList()) {
                int curDepth = getDepth(parent);
                if (curDepth > depth) {
                    depth = curDepth;
                }
            }
            depth++;
            mTaskSet2Depth.put(set, depth);
            return depth;

        }

    }

    /**
     * 判断一个任务是否为另一个任务的祖先。
     *
     * @param ancestor 候选祖先任务
     * @param set 候选后代任务
     * @return 是否存在祖先关系
     */
    private boolean check(Task ancestor, Task set) {
        if (ancestor == null || set == null) {
            return false;
        }
        if (ancestor == set) {
            return true;
        }
        for (Task parent : set.getParentList()) {
            if (check(ancestor, parent)) {
                return true;
            } else {
                //parent.hasChecked = true;
            }
        }
        return false;
    }
    /**
     * 临时移除的边，用于聚类完成后恢复原图。
     */
    private final Map<Task, Task> mRecover = new HashMap<>();

    /**
     * 暂时移除会形成跨层祖先关系的子边，并记录以便恢复。
     */
    private void remove() {

        for (Task set : this.getTaskList()) {
            if (set.getChildList().size() >= 2) {
                for (int i = 0; i < set.getChildList().size(); i++) {
                    Task children = (Task) set.getChildList().get(i);
                    for (int j = i + 1; j < set.getChildList().size(); j++) {
                        Task another = (Task) set.getChildList().get(j);
                        // 只检查深度不同的两个兄弟候选，避免无效递归。
                        if (children.getDepth() > another.getDepth()) {
                            if (check(another, children)) {
                                // 暂时移除该边，聚类结束后恢复。
                                set.getChildList().remove(children);
                                children.getParentList().remove(set);
                                i--;
                                mRecover.put(set, children);
                                //cleanTaskSetChecked();
                                break;
                            } else {
                                //cleanTaskSetChecked();
                            }
                        }
                        if (another.getDepth() > children.getDepth()) {
                            if (check(children, another)) {
                                set.getChildList().remove(another);
                                another.getParentList().remove(set);
                                i--;
                                mRecover.put(set, another);
                                //cleanTaskSetChecked();
                                break;
                            } else {
                                //cleanTaskSetChecked();
                            }
                        }
                    }

                }
            }

        }
    }

    /**
     * 恢复此前暂时移除的依赖边。
     */
    private void recover() {
        for (Entry<Task, Task> entry : mRecover.entrySet()) {
            Task set = entry.getKey();
            Task children = entry.getValue();
            set.getChildList().add(children);
            children.getParentList().add(set);
        }
    }

    @Override
    public void run() {

        if (clusterNum > 0) {
            for (Task task : getTaskList()) {
                TaskSet set = new TaskSet();
                set.addTask(task);
                mTask2TaskSet.put(task, set);
            }
        }

        remove();
        updateTaskSetDependencies();

        printMetrics();
        String code = Parameters.getClusteringParameters().getCode();
        Map<Integer, List<TaskSet>> map = getCurrentTaskSetAtLevels();
        if (code != null) {
            for (char c : code.toCharArray()) {

                switch (c) {
                    case 'v':
                        VerticalBalancing v = new VerticalBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        v.run();
                        break;
                    case 'c':
                        ChildAwareHorizontalClustering ch =
                                new ChildAwareHorizontalClustering(map, this.mTask2TaskSet, this.clusterNum);
                        ch.run();
                        updateTaskSetDependencies();
                        break;
                    case 'r':
                        HorizontalRuntimeBalancing r =
                                new HorizontalRuntimeBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        r.run();
                        updateTaskSetDependencies();
                        break;
                    case 'i':
                        HorizontalImpactBalancing i =
                                new HorizontalImpactBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        i.run();
                        break;
                    case 'd':
                        HorizontalDistanceBalancing d =
                                new HorizontalDistanceBalancing(map, this.mTask2TaskSet, this.clusterNum);
                        d.run();
                        break;
                    case 'h':
                        HorizontalRandomClustering h =
                                new HorizontalRandomClustering(map, this.mTask2TaskSet, this.clusterNum);
                        h.run();
                        break;
                    default:
                        break;
                }
            }
            printMetrics();
        }

        printOut();

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                addTasks2Job(set.getTaskList());
            }
        }
        // 清除本轮遍历标记，避免状态泄漏到后续聚类步骤。
        cleanTaskSetChecked();


        updateDependencies();
        addClustDelay();

        recover();
    }

    /**
     * 输出任务集合中的聚类诊断信息。
     */
    private void printOut() {
        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;

                Log.printLine("Job");
                for (Task task : set.getTaskList()) {
                    Log.printLine("Task " + task.getCloudletId() + " " + task.getImpact() + " " + task.getCloudletLength());
                }
            }
        }
        // 在方法退出前恢复遍历标记。
        cleanTaskSetChecked();
    }

    /**
     * 从逻辑任务依赖重建任务集合的父子关系。
     */
    private void updateTaskSetDependencies() {

        Collection<TaskSet> sets = mTask2TaskSet.values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
                set.getChildList().clear();
                set.getParentList().clear();
                for (Task task : set.getTaskList()) {
                    for (Task parent : task.getParentList()) {
                        TaskSet parentSet = mTask2TaskSet.get(parent);
                        if (!set.getParentList().contains(parentSet) && set != parentSet) {
                            set.getParentList().add(parentSet);
                        }
                    }
                    for (Task child : task.getChildList()) {
                        TaskSet childSet = mTask2TaskSet.get(child);
                        if (!set.getChildList().contains(childSet) && set != childSet) {
                            set.getChildList().add(childSet);
                        }
                    }
                }
            }
        }
        // 在方法退出前恢复遍历标记。
        cleanTaskSetChecked();
    }
}
