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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;

/**
 * 水平聚类策略：合并同一 DAG 深度的任务。
 *
 * <p>可通过目标作业数或每个作业的任务数控制聚类强度。分组前使用带命名流的伪随机
 * 置换，以便随机性随会话种子受控。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class HorizontalClustering extends BasicClustering {

    /**
     * 每个 DAG 层期望生成的聚类作业数。
     */
    private final int clusterNum;
    /**
     * 每个聚类作业期望包含的任务数。
     */
    private final int clusterSize;
    /**
     * DAG 深度到该层任务列表的映射。
     */
    private final Map<Integer, List> mDepth2Task;

    /**
     * 创建水平聚类器；{@code clusterNum} 与 {@code clusterSize} 至少应指定一个。
     *
     * @param clusterNum 每层聚类作业数（{@code clusters.num}）
     * @param clusterSize 每个聚类作业的任务数（{@code clusters.size}）
     */
    public HorizontalClustering(int clusterNum, int clusterSize) {
        super();
        this.clusterNum = clusterNum;
        this.clusterSize = clusterSize;
        this.mDepth2Task = new HashMap<>();

    }

    /**
     * 按层分组任务、执行水平聚类，并重建作业依赖和延迟。
     */
    @Override
    public void run() {
        if (clusterNum > 0 || clusterSize > 0) {
            for (Iterator it = getTaskList().iterator(); it.hasNext();) {
                Task task = (Task) it.next();
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
        /**
         * 优先按 {@code clusters.num} 分组。
         */
        if (clusterNum > 0) {
            bundleClustering();
            /**
             * 否则按 {@code clusters.size} 分组。
             */
        } else if (clusterSize > 0) {
            collapseClustering();
        }

        updateDependencies();
        addClustDelay();
    }

    /**
     * 将每层任务合并为固定数量的作业。
     */
    private void bundleClustering() {

        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();

            Collections.shuffle(list, SimulationRandom.newJavaRandom("clustering.horizontal.bundle"));
            Collections.shuffle(list, SimulationRandom.newJavaRandom("clustering.horizontal.bundle"));

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
                addTasks2Job(list.subList(start, end + 1));
            }


        }
    }

    /**
     * 将固定数量的任务合并为一个作业。
     */
    private void collapseClustering() {
        for (Map.Entry<Integer, List> pairs : mDepth2Task.entrySet()) {
            List list = pairs.getValue();

            Collections.shuffle(list, SimulationRandom.newJavaRandom("clustering.horizontal.collapse"));
            Collections.shuffle(list, SimulationRandom.newJavaRandom("clustering.horizontal.collapse"));

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
                Job job = addTasks2Job(list.subList(start, end + 1));
            } while (end < num - 1);

        }
    }
}
