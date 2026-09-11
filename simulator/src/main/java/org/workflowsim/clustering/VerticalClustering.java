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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * 垂直聚类策略：沿同一依赖流水线合并任务。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class VerticalClustering extends BasicClustering {

    /* 允许探索的最大 DAG 深度。 */
    private final int mDepth;
    /* 任务遍历检查点。 */
    private final Map<Integer, Boolean> mHasChecked;

    /**
     * 创建垂直聚类器。
     *
     * @param depth 允许探索的最大 DAG 深度
     */
    public VerticalClustering(int depth) {
        super();
        this.mDepth = depth;
        this.mHasChecked = new HashMap<>();

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
     * 遍历 DAG 的唯一依赖链，生成垂直聚类作业。
     */
    @Override
    public void run() {
        if (mDepth > 0) {
            /**
             * Montage 的历史输入可能含重复边，按既有 reducer 规则预先消除。
             */
            if (Parameters.getReduceMethod().equals("montage")) {
                removeDuplicateMontage();
            }
            Task root = super.addRoot();
            Task node;
            List<Task> taskList = new ArrayList<>();
            Stack<Task> stack = new Stack<>();
            stack.push(root);
            while (!stack.empty()) {
                node = (Task) stack.pop();
                if (!getCheck(node.getCloudletId())) {
                    setCheck(node.getCloudletId());

                    int pNum = node.getParentList().size();
                    int cNum = node.getChildList().size();

                    for (Task cNode : node.getChildList()) {
                        stack.push(cNode);
                    }

                    if (pNum == 0) {
                        // 虚拟根节点不参与聚类。
                    } else if (pNum > 1) {
                        if (cNum > 1 || cNum == 0) {

                            if (!taskList.isEmpty()) {
                                addTasks2Job(taskList);
                                taskList.clear();
                            }
                            taskList.add(node);
                            addTasks2Job(taskList);
                            taskList.clear();

                        } else { // cNum == 1
                            // 在分支边界截断并开始新的聚类链。

                            if (!taskList.isEmpty()) {
                                addTasks2Job(taskList);
                                taskList.clear();
                            }
                            if (!taskList.contains(node)) {
                                taskList.add(node);
                            }
                        }
                    } else { // pNum == 1
                        if (cNum > 1 || cNum == 0) {
                            // 单父节点分支在此结束当前聚类链。
                            taskList.add(node);
                            addTasks2Job(taskList);
                            taskList.clear();
                        } else {
                            // 单父单子节点继续并入当前聚类链。
                            if (!taskList.contains(node)) {
                                taskList.add(node);
                            }
                        }
                    }

                } else {
                    if (!taskList.isEmpty()) {
                        addTasks2Job(taskList);
                        taskList.clear();
                    }
                }
            }
        }
        mHasChecked.clear();
        super.clean();        
        updateDependencies();
        addClustDelay();
    }

    /**
     * 按历史 {@code reducer.method=montage} 规则去除 Montage 工作流的重复依赖边。
     */
    public void removeDuplicateMontage() {

        List jobList = this.getTaskList();
        for (Object jobList1 : jobList) {
            Task node = (Task) jobList1;
            String name = node.getType();
            switch (name) {
                case "mBackground":
                    // 移除 mBackground 指向 mProjectPP 的重复父边。
                    
                    for (int j = 0; j < node.getParentList().size(); j++) {
                        
                        Task parent = (Task) node.getParentList().get(j);
                        if (parent.getType().equals("mProjectPP")) {
                            j--;
                            node.getParentList().remove(parent);
                            parent.getChildList().remove(node);
                        }
                    }   break;
                case "mAdd":
                    for (int j = 0; j < node.getParentList().size(); j++) {
                        
                        Task parent = (Task) node.getParentList().get(j);
                        String pName = parent.getType();
                        if (pName.equals("mBackground") || pName.equals("mShrink")) {
                            j--;
                            node.getParentList().remove(parent);
                            parent.getChildList().remove(node);
                    }
                }   break;
            }
        }
    }
}
