/*
 * 
 *   Copyright 2012-2013 University Of Southern California
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
package org.workflowsim.clustering;

import java.util.ArrayList;

/**
 * 聚类过程使用的任务列表及其层级信息容器。
 *
 * <p>与普通 {@link ArrayList} 相比，该类型额外保存同一批任务对应的 DAG 深度，
 * 供按层聚类算法进行分组。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class AbstractArrayList {

    /**
     * 当前层的任务列表。
     */
    private final ArrayList taskList;
    /**
     * 当前层的 DAG 深度。
     */
    private final int depth;

    /**
     * 创建任务列表及其 DAG 深度的容器。
     *
     * @param taskList 任务列表
     * @param depth 这些任务所在的 DAG 深度
     */
    public AbstractArrayList(ArrayList taskList, int depth) {
        this.taskList = taskList;
        this.hasChecked = false;
        this.depth = depth;
    }

    /**
     * 返回任务列表。
     *
     * @return 任务列表
     */
    public ArrayList getArrayList() {
        return this.taskList;
    }

    /**
     * 返回这些任务所在的 DAG 深度。
     *
     * @return DAG 深度
     */
    public int getDepth() {
        return this.depth;
    }
    /**
     * 聚类遍历使用的已检查标记。
     */
    public boolean hasChecked;
}
