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
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.UtilizationModelFull;

/**
 * WorkflowSim 的工作流任务模型。
 *
 * <p>该类扩展 CloudSim {@link Cloudlet}，并保存父子任务、数据文件、深度及研究算法
 * 所需的优先级/影响度信息。工作流引擎仅在所有父任务成功完成后才将任务释放给调度器。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Task extends Cloudlet {

    /** 父任务列表。 */
    private List<Task> parentList;
    /** 子任务列表。 */
    private List<Task> childList;
    /** 关联的输入、输出和中间文件列表。 */
    private List<FileItem> fileList;
    /**
     * 供研究型算法使用的优先级；核心执行路径不直接解释该值。
     *
     * <p><strong>现状说明</strong>：当前全部内置调度算法（FCFS、ReadyBatch 系列、
     * DATA、STATIC 及 legacy 变体）均<strong>不读取</strong>该字段；聚类层
     * （ClusteringEngine）将其统一置为 0。它仅供外部研究型算法扩展自行解释，
     * 设置该值不会影响内置算法的派发顺序。</p>
     */
    private int priority;
    /**
     * DAG 深度，即从任一根任务到该任务的最长路径节点数；由输入解析后的
     * {@link WorkflowDagValidator} 统一写入。
     */
    private int depth;
    /** 供研究型算法使用的任务影响度。 */
    private double impact;

    /** 输入工作流声明的任务类型或名称。 */
    private String type;

    /**
     * WorkflowSim 维护的任务完成时间；Cloudlet 本身不允许本项目直接更新其完成时间字段。
     */
    private double taskFinishTime;

    /**
     * 可选的静态计划开始时间。仅生成完整每 VM 执行顺序的规划器会写入该值；仅提供
     * VM 映射的计划保持为 {@link Double#NaN}。
     */
    private double staticScheduleStartTime;

    /**
     * 可选的任务×VM 异构执行成本投影（vmId → 执行秒数）。
     *
     * <p>由 {@code WorkflowPlanner} 在解析后从配置的 {@link org.workflowsim.utils.TaskCostMatrix}
     * 投影到每个任务；未配置成本矩阵时保持 {@code null}，平台维持 MI/mips 缩放行为。
     * 规划器读取该投影进行成本估计；STATIC 派发在作业提交前按
     * {@code MI = round(秒数 × vm.mips)} 折算作业长度。</p>
     */
    private java.util.Map<Integer, Double> vmExecutionCostSeconds;

    /**
     * 创建一个工作流任务。
     *
     * @param taskId 任务唯一编号
     * @param taskLength 待执行长度，单位为百万条指令（MI）
     */
    public Task(
            final int taskId,
            final long taskLength) {
        /*
         * 文件依赖由 fileList 单独表达，因此不使用 Cloudlet 的单个输入/输出文件大小字段。
         * CPU、内存和带宽利用率模型使用 CloudSim 的默认全利用率实现。
         */
        super(taskId, taskLength, 1, 0, 0, new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

        this.childList = new ArrayList<>();
        this.parentList = new ArrayList<>();
        this.fileList = new ArrayList<>();
        this.impact = 0.0;
        this.taskFinishTime = -1.0;
        this.staticScheduleStartTime = Double.NaN;
    }

    /**
     * 设置输入工作流声明的任务类型。
     *
     * @param type 任务类型或名称
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * 设置本任务在各 VM 上的异构执行成本投影。
     *
     * <p>调用方必须传入覆盖全部候选 VM 的映射；条目必须为正有限秒数。传入后深拷贝为
     * 不可变映射，防止规划期间被外部修改。</p>
     *
     * @param value vmId → 执行秒数映射；不可为 null 或空
     * @throws IllegalArgumentException 当映射为 null/空，或包含非正/非有限秒数时抛出
     */
    public void setVmExecutionCostSeconds(java.util.Map<Integer, Double> value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("VM execution cost projection must be non-empty");
        }
        java.util.Map<Integer, Double> copy = new java.util.LinkedHashMap<Integer, Double>();
        for (java.util.Map.Entry<Integer, Double> entry : value.entrySet()) {
            Double seconds = entry.getValue();
            if (seconds == null || !(seconds.doubleValue() > 0.0)
                    || seconds.isNaN() || seconds.isInfinite()) {
                throw new IllegalArgumentException("VM execution cost seconds must be positive finite: "
                        + "task " + getCloudletId() + " on VM " + entry.getKey() + " got " + seconds);
            }
            copy.put(entry.getKey(), seconds);
        }
        this.vmExecutionCostSeconds = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * 返回本任务在某 VM 上的异构执行成本秒数。
     *
     * @param vmId VM ID
     * @return 执行秒数；未配置成本投影时返回 null
     */
    public Double getVmExecutionCostSeconds(int vmId) {
        if (vmExecutionCostSeconds == null) {
            return null;
        }
        return vmExecutionCostSeconds.get(Integer.valueOf(vmId));
    }

    /**
     * 判断本任务是否携带异构执行成本投影。
     *
     * @return 携带时返回 true
     */
    public boolean hasVmExecutionCostSeconds() {
        return vmExecutionCostSeconds != null;
    }

    /** @return 输入工作流声明的任务类型或名称 */
    public String getType() {
        return type;
    }

    /**
     * 设置供研究型算法使用的优先级。
     *
     * @param priority 优先级值
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * 设置 DAG 深度。
     *
     * @param depth 从根任务开始计数的最长路径深度
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /** @return 供研究型算法使用的优先级 */
    public int getPriority() {
        return this.priority;
    }

    /** @return DAG 深度 */
    public int getDepth() {
        return this.depth;
    }

    /** @return 子任务列表 */
    public List<Task> getChildList() {
        return this.childList;
    }

    /**
     * 替换子任务列表。
     *
     * @param list 子任务列表
     */
    public void setChildList(List<Task> list) {
        this.childList = list;
    }

    /**
     * 替换父任务列表。
     *
     * @param list 父任务列表
     */
    public void setParentList(List<Task> list) {
        this.parentList = list;
    }

    /**
     * 向现有子任务列表追加任务。
     *
     * @param list 要追加的子任务列表
     */
    public void addChildList(List<Task> list) {
        this.childList.addAll(list);
    }

    /**
     * 向现有父任务列表追加任务。
     *
     * @param list 要追加的父任务列表
     */
    public void addParentList(List<Task> list) {
        this.parentList.addAll(list);
    }

    /** @return 父任务列表 */
    public List<Task> getParentList() {
        return this.parentList;
    }

    /**
     * 添加一个子任务。
     *
     * @param task 子任务
     */
    public void addChild(Task task) {
        this.childList.add(task);
    }

    /**
     * 添加一个父任务。
     *
     * @param task 父任务
     */
    public void addParent(Task task) {
        this.parentList.add(task);
    }

    /** @return 关联的输入、输出和中间文件列表 */
    public List<FileItem> getFileList() {
        return this.fileList;
    }

    /**
     * 添加一个关联文件。
     *
     * @param file 输入、输出或中间文件
     */
    public void addFile(FileItem file) {
        this.fileList.add(file);
    }

    /**
     * 替换关联文件列表。
     *
     * @param list 输入、输出和中间文件列表
     */
    public void setFileList(List<FileItem> list) {
        this.fileList = list;
    }

    /**
     * 设置任务影响度。
     *
     * @param impact 供研究型算法使用的影响度
     */
    public void setImpact(double impact) {
        this.impact = impact;
    }

    /** @return 任务影响度 */
    public double getImpact() {
        return this.impact;
    }

    /**
     * 保存静态计划中的非负抽象开始时间。
     *
     * @param value 非负且有限的开始时间
     */
    public void setStaticScheduleStartTime(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            throw new IllegalArgumentException("Static schedule start time must be finite and non-negative");
        }
        this.staticScheduleStartTime = value;
    }

    /**
     * 返回静态计划开始时间。
     *
     * @return 完整静态计划中的开始时间；仅有 VM 映射时为 {@link Double#NaN}
     */
    public double getStaticScheduleStartTime() {
        return this.staticScheduleStartTime;
    }

    /**
     * 设置 WorkflowSim 维护的任务完成时间。
     *
     * @param time 模拟时间单位下的完成时间
     */
    public void setTaskFinishTime(double time) {
        this.taskFinishTime = time;
    }

    /** @return WorkflowSim 维护的任务完成时间 */
    public double getTaskFinishTime() {
        return this.taskFinishTime;
    }

    /**
     * 计算任务处理成本。
     *
     * <p>成本近似为 CPU 执行成本加关联文件的带宽价格。所有关联文件的声明字节数以
     * 十进制 MB（{@code 1,000,000} bytes）连续换算后乘以带宽单价；不对每个文件
     * 向下截断到整 MB。该方法不代表现实云厂商完整计费规则，也不等同于已发生的数据移动
     * 交易或云服务商账单。</p>
     *
     * @return 任务总处理成本
     */
    @Override
    public double getProcessingCost() {
        return getModeledCpuEnvelopeCost() + getModeledDeclaredFileBandwidthCost();
    }

    /**
     * 返回 Job envelope 的 CPU 价格分量。
     *
     * <p>CloudSim 可能已将建模的 stage-in 秒数换算为附加 MI，因此该值不是纯原始工作流
     * CPU 工作量的现实计费。</p>
     *
     * @return {@code costPerSecond * actualCpuTime} 的抽象成本单位值
     */
    public double getModeledCpuEnvelopeCost() {
        return getCostPerSec() * getActualCPUTime();
    }

    /**
     * 返回当前 Task/Job 文件表中所有声明文件的字节数。
     *
     * <p>该值不按输入/输出、真实外部输入、缓存命中或副本位置过滤，因此是声明文件需求，
     * 不是实际网络或存储搬运字节数。</p>
     *
     * @return 声明文件字节当量的连续和
     */
    public double getModeledDeclaredFileBytes() {
        double declaredFileBytes = 0.0;
        for (FileItem file : getFileList()) {
            declaredFileBytes += file.getSize();
        }
        return declaredFileBytes;
    }

    /**
     * 返回按声明文件字节数计算的带宽价格分量。
     *
     * <p>计量单位为每 {@code 1,000,000} 字节一个十进制 MB；内部不做按文件、按 Job
     * 或货币展示精度的舍入。</p>
     *
     * @return {@code bandwidthPrice * declaredFileBytes / 1_000_000} 的抽象成本单位值
     */
    public double getModeledDeclaredFileBandwidthCost() {
        return costPerBw * getModeledDeclaredFileBytes() / (double) Consts.MILLION;
    }
}
