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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;

/**
 * WorkflowSim 历史全局参数仓库。
 *
 * <p>该类保留为与既有 WorkflowSim API 兼容的静态状态入口。新实验代码应优先通过
 * {@link SimulationConfig} 与 {@link SimulationSession} 建立显式会话边界，避免在同一
 * JVM 内泄漏配置、随机流或副本状态。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Parameters {

    
    /** 在线（局部）调度算法。 */

    public enum SchedulingAlgorithm {

        // 适配 WorkflowSim 事件生命周期的在线就绪批次变体。
        READY_BATCH_MINMIN, READY_BATCH_MAXMIN, READY_BATCH_MCT, READY_BATCH_ROUNDROBIN,
        // 原始实现已改名，以下枚举值仅保留二进制/配置兼容性。
        /** @deprecated 实际行为是 SPT-fastest-idle,非经典 Min-Min。用 READY_BATCH_MINMIN 替代 */
        @Deprecated MINMIN,
        /** @deprecated 实际行为是 LJF-fastest-idle,非经典 Max-Min。用 READY_BATCH_MAXMIN 替代 */
        @Deprecated MAXMIN,
        /** @deprecated 实际行为是贪心最快 VM,非经典 MCT。用 READY_BATCH_MCT 替代 */
        @Deprecated MCT,
        /** @deprecated vmIndex 死代码,实际 First-Fit-Idle。用 READY_BATCH_ROUNDROBIN 替代 */
        @Deprecated ROUNDROBIN,
        // 以下枚举值保留其现有调度语义。
        DATA, STATIC, FCFS,
        /**
         * R4 RL 轨道：策略驱动的在线调度。每次 ready-batch 更新向经
         * {@code RlPolicyRegistry} 注册的 {@code RlPolicy} 请求 Job→VM 动作；
         * 必须通过 {@code RlEnvironment.runEpisode} 运行（规划层 INVALID），
         * 直接用 {@code SimulationRunner} 运行会因缺少注册策略而显式失败。
         */
        RL_POLICY,
        INVALID
    }
    
    /** 离线（全局）规划算法。 */
    public enum PlanningAlgorithm{
        INVALID, RANDOM,
        /**
         * 面向独立任务集合的离线基线算法。它们拒绝含依赖边的工作流；DAG 工作流应使用
         * 专用 DAG 规划器。
         */
        STATIC_OLB, STATIC_MET, STATIC_MCT, STATIC_MINMIN, STATIC_MAXMIN,
        STATIC_SUFFERAGE, STATIC_ROUND_ROBIN,
        /**
         * 静态 DAG 映射算法，其估计器刻意与当前“共享存储、无聚类”的 WorkflowSim 执行
         * 模型对齐。
         */
        SHARED_STORAGE_HEFT, SHARED_STORAGE_CPOP, SHARED_STORAGE_DLS, SHARED_STORAGE_ETF,
        SHARED_STORAGE_PEFT,
        /**
         * PSO（粒子群优化）映射规划器——论文复现实现（Pandey et al., AINA 2010；
         * 参考开源实现 meysamhit/workflowsim-pso）。适应度为 0.8·成本 + 0.2·makespan
         * 的顺序负载模型（忽略依赖边，忠实保留自参考实现）；运行期必须搭配
         * {@code SchedulingAlgorithm.STATIC} 执行映射。
         */
        PSO,
        /**
         * 通信感知 HEFT 静态 DAG 规划器（Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002），
         * 对齐 LOCAL 文件系统执行模型：任务间数据传输按运行时历史规则逐位镜像
         * （SOURCE→VM 带宽、VM 间 min(bw)、副本演进）；传输建模为执行前网络延迟，
         * 可与 VM 忙碌期重叠、VM 只被计算占用（论文 AST 语义）。
         * 需要 LOCAL 文件系统、NONE 聚类、无故障/开销、
         * {@code preExecutionTransferDelayV1} 数据移动模型与
         * {@code SchedulingAlgorithm.STATIC} 派发。
         */
        LOCAL_HEFT,
        /**
         * 通信感知 CPOP 静态 DAG 规划器（与 HEFT 同出 Topcuoglu, Hariri &amp; Wu,
         * IEEE TPDS 2002）：优先级 = 向上 rank + 向下 rank，关键路径任务绑定使关键
         * 路径计算总秒数最小的 VM，其余任务取插入式最小 EFT。通信建模、执行语义与
         * 适用前提与 {@link #LOCAL_HEFT} 完全一致。
         */
        LOCAL_CPOP,
        /**
         * 通信感知 PEFT 静态 DAG 规划器（Arabnejad &amp; Barbosa, IEEE TPDS 2014）：
         * 优先级 = 乐观成本表 OCT 在全部 VM 上的平均值降序，VM 选择最小化
         * {@code EFT + OCT(t, vm)}。OCT 递推使用真实的按 VM 对 LOCAL 通信成本
         * （c &gt; 0，与 SHARED_STORAGE_PEFT 的无链路模型退化形相对）。通信建模、
         * 执行语义与适用前提与 {@link #LOCAL_HEFT} 完全一致。
         */
        LOCAL_PEFT
    }
    
    /** 工作流文件类型。 */
    public enum FileType{
        NONE(0), INPUT(1), OUTPUT(2);
        public final int value;
        private FileType(int fType){
            this.value = fType;
        }
    }
    
    /** 任务执行阶段类型。 */
    public enum ClassType{
        STAGE_IN(1), COMPUTE(2), STAGE_OUT(3), CLEAN_UP(4);
        public final int value;
        private ClassType(int cType){
            this.value = cType;
        }
    }
    
    /**
     * 成本模型。
     *
     * <p>{@code DATACENTER} 使用数据中心级成本，{@code VM} 使用虚拟机级成本。</p>
     */
    public enum CostModel{
        DATACENTER(1), VM(2);
        public final int value;
        private CostModel(int model){
            this.value = model;
        }
    }
    
    /** 源主机（提交主机）的逻辑名称。 */
    public static String SOURCE = "source";
    
    public static final int BASE = 0;
    
    /** 当前调度模式。 */
    private static SchedulingAlgorithm schedulingAlgorithm;
    
    /** 当前规划模式。 */
    private static PlanningAlgorithm planningAlgorithm;
    
    /** 当前作业规约模式。 */
    private static String reduceMethod;
    /** 可用虚拟机数量。 */
    private static int vmNum;
    /** 单个 DAX 或 JSON 工作流输入的物理路径。 */
    private static String daxPath;
    
    /** 多个 DAX 或 JSON 工作流输入的物理路径。 */
    private static List<String> daxPaths;
    /**
     * 外部运行时间文件路径。
     *
     * <p>历史格式为 {@code ID1 1.0 ID2 2.0 ...}。若 DAX 已声明任务运行时间，则无需
     * 配置该文件。</p>
     */
    private static String runtimePath;
    /**
     * 外部数据大小文件路径。
     *
     * <p>历史格式为 {@code DATA1 1000 DATA2 2000 ...}。若 DAX 已声明文件大小，则无需
     * 配置该文件。</p>
     */
    private static String datasizePath;
    /** 工具包版本号。 */
    private static final String version = "1.1.0";
    /** 历史版本说明。 */
    private static final String note = " supports planning algorithm at Nov 9, 2013";
    /** 当前会话的旧式开销参数。 */
    private static OverheadParameters oParams;
    /** 当前会话的聚类参数。 */
    private static ClusteringParameters cParams;
    /** 工作流截止时间；0 表示未请求截止时间。 */
    private static long deadline;
    
    /** 虚拟机之间的逻辑带宽矩阵。 */
    private static double[][] bandwidths;
    
    
    /** 最大 DAG 深度，由解析/配置路径初始化，供 {@code FailureGenerator} 使用。 */
    private static int maxDepth;
    
    /** 未设置字符串参数时的历史占位值。 */
    private static final String INVALID = "Invalid";
    
    /** 输入运行时间转换为模型任务长度后的缩放系数。 */
    private static double runtime_scale = 1.0;

    /** 将以秒表示的工作流运行时间转换为 CloudSim MI 时使用的参考吞吐量。 */
    private static double runtimeReferenceMips = 1000.0;

    /**
     * 所有 WorkflowSim 自有随机组件的根种子。
     *
     * <p>默认值使不变配置可重复运行，但研究实验仍应显式设置种子并记录到结果清单中。</p>
     */
    private static long randomSeed = 0L;
    
    /** 默认使用与 CloudSim 一致的数据中心级成本模型。 */
    private static CostModel costModel = CostModel.DATACENTER;

    /**
     * R5 动态到达：每个工作流输入的提交时刻（模拟秒），与输入路径一一对应。
     *
     * <p>{@code null} 或全零表示全部输入在 t=0 提交（历史行为）。</p>
     */
    private static List<Double> workflowArrivalSeconds;
    
    /**
     * 初始化单输入工作流的历史全局参数。
     *
     * <p>新代码优先使用 {@link SimulationSession}。该方法仍会重置自有随机流分配，
     * 因此不应在同一会话中间调用。</p>
     *
     * @param vm 虚拟机数量
     * @param dax DAX 或 JSON 工作流路径
     * @param runtime 可选的外部运行时间文件路径
     * @param datasize 可选的外部数据大小文件路径
     * @param op 开销参数
     * @param cp 聚类参数
     * @param scheduler 调度模式
     * @param planner 规划模式
     * @param rMethod 作业规约模式
     * @param dl 工作流截止时间
     */
    public static void init(
            int vm, String dax, String runtime, String datasize,
            OverheadParameters op, ClusteringParameters cp,
            SchedulingAlgorithm scheduler, PlanningAlgorithm planner, String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = dax;
        daxPaths = null;
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }
    
    /**
     * 初始化多输入工作流的历史全局参数。
     *
     * @param vm 虚拟机数量
     * @param dax DAX 或 JSON 工作流路径列表
     * @param runtime 可选的外部运行时间文件路径
     * @param datasize 可选的外部数据大小文件路径
     * @param op 开销参数
     * @param cp 聚类参数
     * @param scheduler 调度模式
     * @param planner 规划模式
     * @param rMethod 作业规约模式
     * @param dl 工作流截止时间
     */
    public static void init(
            int vm, List<String> dax, String runtime, String datasize,
            OverheadParameters op, ClusteringParameters cp,
            SchedulingAlgorithm scheduler, PlanningAlgorithm planner, String rMethod,
            long dl) {

        cParams = cp;
        vmNum = vm;
        daxPath = null;
        daxPaths = dax == null ? null : new ArrayList<>(dax);
        runtimePath = runtime;
        datasizePath = datasize;

        oParams = op;
        schedulingAlgorithm = scheduler;
        planningAlgorithm = planner;
        reduceMethod = rMethod;
        deadline = dl;
        maxDepth = 0;
        SimulationRandom.reset(randomSeed);
    }

    /** @return 当前会话的旧式开销参数 */
    public static OverheadParameters getOverheadParams() {
        return oParams;
    }

    

    /** @return 当前作业规约模式；未设置时返回历史占位值 {@code Invalid} */
    public static String getReduceMethod() {
        if(reduceMethod!=null){
            return reduceMethod;
        }else{
            return INVALID;
        }
    }

   

    /** @return 单个 DAX 或 JSON 工作流路径；多输入配置时为 {@code null} */
    public static String getDaxPath() {
        return daxPath;
    }

    /** @return 可选的外部运行时间文件路径 */
    public static String getRuntimePath() {
        return runtimePath;
    }

    /** @return 可选的外部数据大小文件路径 */
    public static String getDatasizePath() {
        return datasizePath;
    }

    
    /** @return 当前可用虚拟机数量 */
    public static int getVmNum() {
        return vmNum;
    }

    
    /** @return 当前成本模型 */
    public static CostModel getCostModel(){
        return costModel;
    }
    
    /**
     * 设置当前可用虚拟机数量。
     *
     * @param num 虚拟机数量
     */
    public static void setVmNum(int num) {
        vmNum = num;
    }

    /** @return 当前聚类参数 */
    public static ClusteringParameters getClusteringParameters() {
        return cParams;
    }

    /** @return 当前调度算法 */
    public static SchedulingAlgorithm getSchedulingAlgorithm() {
        return schedulingAlgorithm;
    }
    
    /** @return 当前规划算法 */
    public static PlanningAlgorithm getPlanningAlgorithm() {
        return planningAlgorithm;
    }
    /** @return 工具包版本号 */
    public static String getVersion(){
        return version;
    }

    public static void printVersion() {
        Log.printLine("WorkflowSim Version: " + version);
        Log.printLine("Change Note: " + note);
    }
    /** @return 当前工作流截止时间；0 表示未请求 */
    public static long getDeadline(){
    	return deadline;
    }
    
    /** @return 当前记录的最大 DAG 深度 */
    public static int getMaxDepth(){
        return maxDepth;
    }
    
    /**
     * 设置当前记录的最大 DAG 深度。
     *
     * @param depth 最大深度
     */
    public static void setMaxDepth(int depth){
        maxDepth = depth;
    }
    
    /**
     * 设置运行时间缩放系数。
     *
     * @param scale 有限正数缩放系数
     */
    public static void setRuntimeScale(double scale){
        if (scale <= 0.0 || Double.isInfinite(scale) || Double.isNaN(scale)) {
            throw new IllegalArgumentException("Runtime scale must be finite and positive");
        }
        runtime_scale = scale;
    }
    
    /**
     * 设置成本模型。
     *
     * @param model 数据中心级或虚拟机级成本模型
     */
    public static void setCostModel(CostModel model){
        if (model == null) {
            throw new IllegalArgumentException("Cost model cannot be null");
        }
        costModel = model;
    }
    
    /** @return 当前运行时间缩放系数 */
    public static double getRuntimeScale(){
        return runtime_scale;
    }

    /**
     * 设置解析器将秒级运行时间转换为 MI 时使用的参考 VM 吞吐量。
     *
     * @param mips 有限正数参考吞吐量，单位为 MI/s
     */
    public static void setRuntimeReferenceMips(double mips) {
        if (mips <= 0.0 || Double.isInfinite(mips) || Double.isNaN(mips)) {
            throw new IllegalArgumentException("Runtime reference MIPS must be finite and positive");
        }
        runtimeReferenceMips = mips;
    }

    /** @return 秒到 MI 转换使用的参考吞吐量，单位为 MI/s */
    public static double getRuntimeReferenceMips() {
        return runtimeReferenceMips;
    }

    /**
     * 设置 WorkflowSim 自有随机模型使用的根种子。
     *
     * <p>应在构建开销或故障分布前调用；每次调用都会启动新的命名随机流上下文。</p>
     *
     * @param seed 一次实验的根种子
     */
    public static void setRandomSeed(long seed) {
        randomSeed = seed;
        SimulationRandom.reset(seed);
    }

    /** @return 当前实验配置的根种子 */
    public static long getRandomSeed() {
        return randomSeed;
    }

    /**
     * 将全部 WorkflowSim 自有全局参数恢复为默认值。
     *
     * <p>本方法不重置 CloudSim 内核；需要完整串行运行边界时应使用
     * {@link SimulationSession}。</p>
     */
    public static void reset() {
        SOURCE = "source";
        schedulingAlgorithm = SchedulingAlgorithm.INVALID;
        planningAlgorithm = PlanningAlgorithm.INVALID;
        reduceMethod = null;
        vmNum = 0;
        daxPath = null;
        daxPaths = null;
        runtimePath = null;
        datasizePath = null;
        oParams = null;
        cParams = null;
        deadline = 0L;
        bandwidths = null;
        maxDepth = 0;
        runtime_scale = 1.0;
        runtimeReferenceMips = 1000.0;
        randomSeed = 0L;
        costModel = CostModel.DATACENTER;
        workflowArrivalSeconds = null;
        SimulationRandom.reset(randomSeed);
    }
    
    /** @return 不可修改的多输入路径列表；单输入配置时为 {@code null} */
    public static List<String> getDAXPaths() {
        return daxPaths == null ? null : Collections.unmodifiableList(daxPaths);
    }

    /**
     * 设置每个工作流输入的提交时刻（模拟秒），与输入路径一一对应。
     *
     * @param arrivalSeconds 每个输入的提交时刻；{@code null} 表示全部 t=0
     */
    public static void setWorkflowArrivalSeconds(List<Double> arrivalSeconds) {
        workflowArrivalSeconds = arrivalSeconds == null
                ? null : new ArrayList<>(arrivalSeconds);
    }

    /** @return 每个输入的提交时刻列表（模拟秒）；未配置时为 {@code null} */
    public static List<Double> getWorkflowArrivalSeconds() {
        return workflowArrivalSeconds == null
                ? null : Collections.unmodifiableList(workflowArrivalSeconds);
    }
}
