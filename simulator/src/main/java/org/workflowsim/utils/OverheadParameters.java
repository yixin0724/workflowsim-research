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
package org.workflowsim.utils;

import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.Job;

/**
 * 历史执行路径使用的可变开销参数集合。
 *
 * <p>新代码应优先在 {@link SimulationConfig} 中使用不可变的
 * {@link OverheadModelConfig}，由会话按根种子创建本类。各延迟映射以任务或作业深度为键，
 * 键 {@code 0} 表示缺少显式深度项时的默认值。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class OverheadParameters {

    /** 工作流引擎延迟采样间隔。 */
    private final int WED_INTERVAL;
    /** 旧开销路径使用的逻辑带宽。 */
    private final double bandwidth;
    /** 任务深度到工作流引擎延迟生成器的映射。 */
    private final Map<Integer, DistributionGenerator> WED_DELAY;
    /** 任务深度到队列延迟生成器的映射。 */
    private final Map<Integer, DistributionGenerator> QUEUE_DELAY;
    /** 任务深度到后处理延迟生成器的映射。 */
    private final Map<Integer, DistributionGenerator> POST_DELAY;
    /** 任务深度到聚类延迟生成器的映射。 */
    private final Map<Integer, DistributionGenerator> CLUST_DELAY;

    /**
     * 创建旧式开销参数。
     *
     * @param wed_interval 工作流引擎延迟采样间隔
     * @param wed_delay 工作流引擎延迟生成器
     * @param queue_delay 队列延迟生成器
     * @param post_delay 后处理延迟生成器
     * @param cluster_delay 聚类延迟生成器
     * @param bandwidth 逻辑带宽
     */
    public OverheadParameters(int wed_interval,
            Map<Integer, DistributionGenerator> wed_delay,
            Map<Integer, DistributionGenerator> queue_delay,
            Map<Integer, DistributionGenerator> post_delay,
            Map<Integer, DistributionGenerator> cluster_delay,
            double bandwidth) {
        this.WED_INTERVAL = wed_interval;
        this.WED_DELAY = wed_delay;
        this.QUEUE_DELAY = queue_delay;
        this.POST_DELAY = post_delay;
        this.CLUST_DELAY = cluster_delay;
        this.bandwidth = bandwidth;

    }

    /** @return 逻辑带宽 */
    public double getBandwidth() {
        return this.bandwidth;
    }

    /** @return 工作流引擎延迟采样间隔 */
    public int getWEDInterval() {
        return this.WED_INTERVAL;
    }

    /** @return 任务深度到队列延迟生成器的映射 */
    public Map<Integer, DistributionGenerator> getQueueDelay() {
        return this.QUEUE_DELAY;
    }

    /** @return 任务深度到后处理延迟生成器的映射 */
    public Map<Integer, DistributionGenerator> getPostDelay() {
        return this.POST_DELAY;
    }

    /** @return 任务深度到工作流引擎延迟生成器的映射 */
    public Map<Integer, DistributionGenerator> getWEDDelay() {
        return this.WED_DELAY;
    }

    /** @return 任务深度到聚类延迟生成器的映射 */
    public Map<Integer, DistributionGenerator> getClustDelay() {
        return this.CLUST_DELAY;
    }

    /**
     * 按作业深度抽取聚类延迟。
     *
     * @param cl 聚类后的作业 Cloudlet
     * @return 对应深度的延迟；没有专用项时使用深度 0 默认项或 {@code 0.0}
     */
    public double getClustDelay(Cloudlet cl) {
        double delay = 0.0;
        if(this.CLUST_DELAY == null){
            return delay;
        }
        if (cl != null) {
            Job job = (Job) cl;

            if (this.CLUST_DELAY.containsKey(job.getDepth())) {
                delay = this.CLUST_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.CLUST_DELAY.containsKey(0)) {
                delay = this.CLUST_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }


        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * 按作业深度抽取队列延迟。
     *
     * @param cl 聚类后的作业 Cloudlet
     * @return 对应深度的延迟；没有专用项时使用深度 0 默认项或 {@code 0.0}
     */
    public double getQueueDelay(Cloudlet cl) {
        double delay = 0.0;

        if(this.QUEUE_DELAY == null){
            return delay;
        }
        if (cl != null) {
            Job job = (Job) cl;

            if (this.QUEUE_DELAY.containsKey(job.getDepth())) {
                delay = this.QUEUE_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.QUEUE_DELAY.containsKey(0)) {
                delay = this.QUEUE_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }


        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * 按作业深度抽取后处理延迟。
     *
     * @param job 聚类后的作业
     * @return 对应深度的延迟；没有专用项时使用深度 0 默认项或 {@code 0.0}
     */
    public double getPostDelay(Job job) {
        double delay = 0.0;

        if(this.POST_DELAY == null){
            return delay;
        }
        if (job != null) {

            if (this.POST_DELAY.containsKey(job.getDepth())) {
                delay = this.POST_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.POST_DELAY.containsKey(0)) {
                // 深度 0 是历史配置中的默认延迟项。
                delay = this.POST_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            Log.printLine("Not yet supported");
        }
        return delay;
    }

    /**
     * 根据批次中第一个作业的深度抽取工作流引擎延迟。
     *
     * @param list 待提交的作业列表
     * @return 对应深度的延迟；空列表或未配置延迟时为 {@code 0.0}
     */
    public double getWEDDelay(List list) {
        double delay = 0.0;

        if(this.WED_DELAY == null){
            return delay;
        }
        if (!list.isEmpty()) {
            Job job = (Job) list.get(0);
            if (this.WED_DELAY.containsKey(job.getDepth())) {
                delay = this.WED_DELAY.get(job.getDepth()).getNextSample();
            } else if (this.WED_DELAY.containsKey(0)) {
                delay = this.WED_DELAY.get(0).getNextSample();
            } else {
                delay = 0.0;
            }

        } else {
            // 空批次没有可用于选择深度的作业，历史语义是零延迟。
        }
        return delay;
    }
}
