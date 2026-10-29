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
package org.workflowsim;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚类后的作业（Job）。
 *
 * <p>{@code Job} 继承 {@link Task}，并保存组成它的原始任务。{@link ClusteringEngine}
 * 将任务合并为作业后，作业运行长度通常等于其中任务运行长度之和；具体的聚类策略和
 * 文件传输估计仍由聚类引擎负责。</p>
 *
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Job extends Task {

    /** 构成该聚类作业的原始任务列表。 */
    private List<Task> taskList;

    /**
     * BAT-D2（2026-10-29）：本 Job 被派发时由调度器确定的 DVFS 频档 MIPS。
     *
     * <p>不可变的"派发时刻指派"：null = v1 无频档模式（平台未声明频档，或本 Job 不是
     * 计算 Job）；非 null = 该 Job 的 [startTime, finishTime) 区间按此档速度执行。
     * 由 {@code WorkflowScheduler} 在 VM 指派后写入，经 {@code JobOutcome.fromJob}
     * 进入证据链；运行前由报告捕获冻结。</p>
     */
    private Double tierMips;

    /**
     * 创建一个聚类作业。
     *
     * @param jobId 作业唯一编号
     * @param jobLength 待执行的作业长度，单位为百万条指令（MI）
     */
    public Job(final int jobId, final long jobLength) {

        super(jobId, jobLength);
        this.taskList = new ArrayList<>();
    }

    /** @return 组成该作业的原始任务列表 */
    public List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * BAT-D2：返回本 Job 派发时指派的频档 MIPS。
     *
     * @return 指派档的 MIPS；null = v1 无频档模式（v1 兼容与 stage-in Job）
     */
    public Double getTierMips() {
        return this.tierMips;
    }

    /**
     * BAT-D2：记录派发时刻的频档指派（每 Job 恰一次；调度器派发路径专用）。
     *
     * @param tierMips 指派档的 MIPS；必须为正有限值
     * @throws IllegalArgumentException 当值非正或非有限时抛出
     */
    public void setTierMips(final Double tierMips) {
        if (tierMips == null || !(tierMips.doubleValue() > 0.0) || tierMips.isNaN() || tierMips.isInfinite()) {
            throw new IllegalArgumentException("Frequency tier mips must be positive and finite: " + tierMips);
        }
        this.tierMips = tierMips;
    }

    /**
     * 替换组成该作业的原始任务列表。
     *
     * @param list 新的原始任务列表
     */
    public void setTaskList(List list) {
        this.taskList = list;
    }

    /**
     * 向作业追加一组原始任务。
     *
     * @param list 要追加的任务列表
     */
    public void addTaskList(List list) {
        this.taskList.addAll(list);
    }

    /**
     * 返回该作业的父任务列表。
     *
     * @return 父任务列表
     */
    @Override
    public List getParentList() {
        return super.getParentList();
    }
}
