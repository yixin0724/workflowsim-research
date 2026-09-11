/**
 * Copyright 2012-2013University Of Southern California
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
package org.workflowsim.utils;

/**
 * 任务聚类所需的参数。
 *
 * <p>{@code clustersNum} 与 {@code clustersSize} 由具体聚类策略解释；通常只配置其中
 * 一个。{@code code} 仅供平衡聚类的研究变体选择具体策略。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ClusteringParameters {

    /** 每层聚类作业数量。 */
    private final int clusters_num;
    /** 每个聚类作业包含的任务数。 */
    private final int clusters_size;

    /** 支持的聚类方法。 */
    public enum ClusteringMethod {

        HORIZONTAL, VERTICAL, NONE, BLOCK, BALANCED
    }
    /** 平衡聚类变体代码。 */
    private final String code;
    /** 选定的聚类方法。 */
    private final ClusteringMethod method;

    /** @return 平衡聚类变体代码；其他方法下通常为 {@code null} */
    public String getCode() {
        return code;
    }

    /** @return 每层聚类作业数量 */
    public int getClustersNum() {
        return clusters_num;
    }

    /** @return 每个聚类作业的任务数 */
    public int getClustersSize() {
        return clusters_size;
    }

    /** @return 聚类方法 */
    public ClusteringMethod getClusteringMethod() {
        return method;
    }

    /**
     * 创建聚类参数。
     *
     * @param cNum 每层聚类作业数量
     * @param cSize 每个聚类作业的任务数
     * @param method 聚类方法
     * @param code 平衡聚类变体代码
     */
    public ClusteringParameters(int cNum, int cSize, ClusteringMethod method, String code) {
        this.clusters_num = cNum;
        this.clusters_size = cSize;
        this.method = method;
        this.code = code;
    }
}
