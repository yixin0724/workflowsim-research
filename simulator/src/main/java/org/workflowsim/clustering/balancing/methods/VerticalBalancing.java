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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.workflowsim.clustering.TaskSet;

/**
 * 均衡聚类框架中的垂直合并方法。
 *
 * <p>它在任务集合拥有唯一子集合时将父集合并入子集合，以便与其他均衡方法在同一
 * 任务集合表示上组合使用。</p>
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class VerticalBalancing extends BalancingMethod {

    /**
     * 创建垂直均衡方法。
     *
     * @param levelMap 按层组织的任务集合
     * @param taskMap 逻辑任务到任务集合的映射
     * @param clusterNum 每层目标聚类作业数
     */
    public VerticalBalancing(Map levelMap, Map taskMap, int clusterNum) {
        super(levelMap, taskMap, clusterNum);
    }

    /**
     * 合并拥有唯一子集合的任务集合。
     */
    @Override
    public void run() {
        Collection<TaskSet> sets = getTaskMap().values();
        for (TaskSet set : sets) {
            if (!set.hasChecked) {
                set.hasChecked = true;
            }
            // 检查当前集合能否安全并入其唯一子集合。
            List<TaskSet> list = set.getChildList();
            if (list.size() == 1) {
                //
                TaskSet child = list.get(0);
                List pList = child.getParentList();
                if (pList.size() == 1) {
                    // 将父集合合并到子集合，方向不可反转。
                    addTaskSet2TaskSet(set, child);
                }
            }
        }
        // 在方法退出前恢复遍历标记。
        cleanTaskSetChecked();
    }
}
