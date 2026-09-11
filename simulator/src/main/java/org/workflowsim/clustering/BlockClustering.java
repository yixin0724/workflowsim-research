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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Task;

/**
 * 分块聚类策略：同时沿同层与单分支方向收集任务。
 *
 * <p>任务先按 DAG 深度分组，再依据 {@code clusters.num} 或 {@code clusters.size}
 * 形成作业；沿着唯一父子链的任务会被一并收集。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BlockClustering extends BasicClustering {

    /**
     * 每个 DAG 层期望生成的聚类作业数。
     */
    private final int clusterNum;
    /**
     * 每个聚类作业期望包含的任务数。
     */
    private final int clusterSize;
    /**
     * 记录任务是否已被遍历的映射。
     */
    private final Map<Integer, Boolean> mHasChecked;
    /**
     * 按 DAG 深度保存任务的映射。
     */
    private final Map<Integer, List> mDepth2Task;

    /**
     * 创建分块聚类器。
     *
     * @param cNum 每层聚类作业数（{@code clusters.num}）
     * @param cSize 每个聚类作业的任务数（{@code clusters.size}）
     */
    public BlockClustering(int cNum, int cSize) {
        super();
        clusterNum = cNum;
        clusterSize = cSize;
        this.mHasChecked = new HashMap<>();
        this.mDepth2Task = new HashMap<>();
    }

    /**
     * 将任务标记为已遍历。
     *
     * @param index 任务 ID
     */
    private void setCheck(int index) {
        if (mHasChecked.containsKey(index)) {
            mHasChecked.remove(index);
        }
        mHasChecked.put(index, true);
    }

    /**
     * 判断任务是否已遍历。
     *
     * @param index 任务 ID
     * @return 任务是否已遍历
     */
    private boolean getCheck(int index) {
        if (mHasChecked.containsKey(index)) {
            return mHasChecked.get(index);
        }
        return false;
    }

    /**
     * 按配置执行分块聚类，并重建作业依赖和聚类延迟。
     */
    @Override
    public void run() {

        // 先按 DAG 深度归并任务。
        if (clusterNum > 0 || clusterSize > 0) {
            for (Task task : getTaskList()) {
                int depth = task.getDepth();
                if (!mDepth2Task.containsKey(depth)) {
                    mDepth2Task.put(depth, new ArrayList<>());
                }
                List list = mDepth2Task.get(depth);
                if (!list.contains(task)) {
                    list.add(task);
                }
            }
        }


        if (clusterNum > 0) {
            bundleClustering();
        } else if (clusterSize > 0) {
            collapseClustering();
        }

        mHasChecked.clear();
        super.clean();

        updateDependencies();
        addClustDelay();
    }

    /**
     * 从种子任务出发收集可沿唯一依赖链合并的候选任务。
     *
     * @param taskList 种子任务
     * @return 可合并的候选任务
     */
    private List searchList(List<Task> taskList) {
        List<Task> sucList = new ArrayList<>();
        for (Task task : taskList) {
            if (!getCheck(task.getCloudletId())) {
                setCheck(task.getCloudletId());
                sucList.add(task);
                // 只沿唯一父子关系继续收集后继任务。
                Task node = task;
                while (node != null) {
                    if (node.getChildList().size() == 1) {
                        Task child = node.getChildList().get(0);
                        if (!getCheck(child.getCloudletId()) && child.getParentList().size() == 1) {
                            setCheck(child.getCloudletId());
                            sucList.add(child);
                            node = child;
                        } else {
                            node = null;
                        }
                    } else {
                        node = null;
                    }
                }
            }
        }
        return sucList;
    }

    /**
     * 将每层任务合并为固定数量的作业。
     */
    private void bundleClustering() {

        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();
            int num = list.size();
            int avg_a = num / this.clusterNum;
            int avg_b = avg_a;
            if (avg_a * this.clusterNum < num) {
                avg_b++;
            }

            int mid = num - this.clusterNum * avg_a;
            if (avg_a <= 0) {
                avg_a = 1;
            }
            if (avg_b <= 0) {
                avg_b = 1;
            }
            int start = 0, end = -1;
            for (int i = 0; i < this.clusterNum; i++) {
                start = end + 1;
                if (i < mid) {
                    // 使用较大的分组大小处理余数。
                    end = start + avg_b - 1;
                } else {
                    // 使用常规分组大小。
                    end = start + avg_a - 1;
                }

                if (end >= num) {
                    end = num - 1;
                }
                if (end < start) {
                    break;
                }

                addTasks2Job(searchList(list.subList(start, end + 1)));
            }

        }

    }

    /**
     * 将固定数量的任务合并为一个作业。
     */
    private void collapseClustering() {
        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();
            int num = list.size();
            int avg = this.clusterSize;

            int start = 0;
            int end = 0;
            int i = 0;
            do {
                start = i * avg;
                end = start + avg - 1;
                i++;
                if (end >= num) {
                    end = num - 1;
                }
                addTasks2Job(searchList(list.subList(start, end + 1)));
            } while (end < num - 1);

        }
    }
}
