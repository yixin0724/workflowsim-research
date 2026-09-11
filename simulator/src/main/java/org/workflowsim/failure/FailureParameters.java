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
package org.workflowsim.failure;

import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

/**
 * 旧版故障注入和故障容错聚类 API 的会话级参数门面。
 *
 * <p>研究实验应通过 {@link FailureModelConfig} 和 {@code SimulationSession}
 * 安装配置，而不是跨运行直接保留本类的可变静态状态。</p>
 * @author chenweiwei
 */
public class FailureParameters {

    /**
     * 任务失效生成器矩阵。第一维为 VM 标识，第二维为任务深度；使用
     * {@code FAILURE_JOB} 时第一维仅使用 0，使用 {@code FAILURE_VM} 时第二维仅使用 0。
     *
     * @pre 0.0<= value <= 1.0
     */
    private static DistributionGenerator[][] generators;
    /** 现代 VM 级配置的稀疏生成器行，以真实 VM ID 为键。 */
    private static Map<Integer, DistributionGenerator[]> generatorsByVmId;
    /**
     * 容错聚类算法。
     */
    public enum FTCluteringAlgorithm {

        FTCLUSTERING_DC, FTCLUSTERING_SR, FTCLUSTERING_DR, FTCLUSTERING_NOOP,
        FTCLUSTERING_BLOCK, FTCLUSTERING_VERTICAL
    }
    /*
     * 容错监控模式。
     */

    public enum FTCMonitor {

        MONITOR_NONE, MONITOR_ALL, MONITOR_VM, MONITOR_JOB, MONITOR_VM_JOB
    }
    /*
     * 失效生成器模式。
     */

    public enum FTCFailure {

        FAILURE_NONE, FAILURE_ALL, FAILURE_VM, FAILURE_JOB, FAILURE_VM_JOB
    }

    /**
     * 当前容错聚类方法。
     */
    private static FTCluteringAlgorithm FTClusteringAlgorithm = FTCluteringAlgorithm.FTCLUSTERING_NOOP;
    /**
     * 当前容错聚类监控模式。
     */
    private static FTCMonitor monitorMode = FTCMonitor.MONITOR_NONE;
    /**
     * 当前失效生成模式。
     */
    private static FTCFailure failureMode = FTCFailure.FAILURE_NONE;
    
    /**
     * 失效到达时间使用的分布族。
     */
    private static DistributionFamily distribution = DistributionFamily.WEIBULL;
    /** -1 表示仅遗留静态 API 使用的无上限重试行为。 */
    private static int maxTotalRetryJobs = -1;
    /** 现代配置安装的根随机种子，供 fail-fast 诊断关联重现实验。 */
    private static long rootSeed;
    /**
     * 旧版无效返回值标记。
     */
    private static final int INVALID = -1;

    /**
     * 初始化当前模拟运行的故障参数。
     *
     * @param fMethod 容错聚类算法
     * @param monitor 容错监控模式
     * @param failure 失效生成器模式
     * @param failureGenerators 失效生成器矩阵
     */
    public static void init(FTCluteringAlgorithm fMethod, FTCMonitor monitor, 
            FTCFailure failure, DistributionGenerator[][] failureGenerators) {
        initInternal(fMethod, monitor, failure, failureGenerators, null, -1, 0L);
    }

    /**
     * 使用现代会话配置初始化故障参数及其有限重试预算。
     *
     * @param fMethod 容错聚类算法
     * @param monitor 故障监控粒度
     * @param failure 故障生成器模式
     * @param failureGenerators 失效生成器矩阵
     * @param dist 分布族
     * @param retryBudget 最大 retry Job 总数；禁用模型为 0
     * @param seed 当前运行根种子
     */
    public static void init(FTCluteringAlgorithm fMethod, FTCMonitor monitor,
            FTCFailure failure, DistributionGenerator[][] failureGenerators,
            DistributionFamily dist, int retryBudget, long seed) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, failureGenerators, null, retryBudget, seed);
    }

    /**
     * 使用现代会话配置初始化按真实 VM ID 键控的故障生成器行。
     *
     * @param fMethod 容错聚类算法
     * @param monitor 故障监控粒度
     * @param failure 失效生成器模式，必须含 VM 维度
     * @param failureGeneratorsByVmId VM ID 到任务深度生成器行的映射
     * @param dist 分布族
     * @param retryBudget 最大 retry Job 总数
     * @param seed 当前运行根种子
     */
    public static void init(FTCluteringAlgorithm fMethod, FTCMonitor monitor,
            FTCFailure failure, Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId,
            DistributionFamily dist, int retryBudget, long seed) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, null, failureGeneratorsByVmId, retryBudget, seed);
    }

    private static void initInternal(FTCluteringAlgorithm fMethod, FTCMonitor monitor,
            FTCFailure failure, DistributionGenerator[][] failureGenerators,
            Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId,
            int retryBudget, long seed) {
        if (fMethod == null || monitor == null || failure == null) {
            throw new IllegalArgumentException("Failure configuration values cannot be null");
        }
        if (retryBudget < -1) {
            throw new IllegalArgumentException("Retry budget must be -1 (legacy unbounded) or non-negative");
        }
        if (failure == FTCFailure.FAILURE_NONE && retryBudget > 0) {
            throw new IllegalArgumentException("Disabled failure mode cannot define a positive retry budget");
        }
        if (failure != FTCFailure.FAILURE_NONE && retryBudget == 0) {
            throw new IllegalArgumentException("Enabled failure mode requires a positive retry budget or "
                    + "legacy unbounded mode");
        }
        validateGenerators(failure, failureGenerators, failureGeneratorsByVmId);
        FTClusteringAlgorithm = fMethod;
        monitorMode = monitor;
        failureMode = failure;
        generators = failureGenerators;
        generatorsByVmId = failureGeneratorsByVmId;
        maxTotalRetryJobs = retryBudget;
        rootSeed = seed;
    }

    /**
     * 使用指定分布族初始化当前模拟运行的故障参数。
     *
     * @param fMethod 容错聚类算法
     * @param monitor 容错监控模式
     * @param failure 失效生成器模式
     * @param failureGenerators 失效生成器矩阵
     * @param dist 失效到达时间的分布族
     */
    public static void init(FTCluteringAlgorithm fMethod, FTCMonitor monitor, 
            FTCFailure failure, DistributionGenerator[][] failureGenerators, 
            DistributionFamily dist) {
        if (dist == null) {
            throw new IllegalArgumentException("Failure distribution cannot be null");
        }
        distribution = dist;
        initInternal(fMethod, monitor, failure, failureGenerators, null, -1, 0L);
    }

    /** 将故障模型恢复为禁用且未配置的状态。 */
    public static void reset() {
        generators = null;
        generatorsByVmId = null;
        FTClusteringAlgorithm = FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        monitorMode = FTCMonitor.MONITOR_NONE;
        failureMode = FTCFailure.FAILURE_NONE;
        distribution = DistributionFamily.WEIBULL;
        maxTotalRetryJobs = -1;
        rootSeed = 0L;
    }
    /**
     * 获取当前运行的失效生成器矩阵。
     *
     * @return 失效生成器矩阵
     * @pre $none
     * @post $none
     */
    public static DistributionGenerator[][] getFailureGenerators() {
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("This failure model uses VM-ID-keyed generator rows, not a dense matrix");
        }
        return generators;
    }
    
    /**
     * 获取生成器矩阵第一维的长度。
     * @return 第一维长度
     */
    public static int getFailureGeneratorsMaxFirstIndex(){
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("VM-ID-keyed generator rows do not have a dense first dimension");
        }
        return generators.length;
    }
    
    /**
     * 获取生成器矩阵第二维的长度。
     * @return 第二维长度
     */
    public static int getFailureGeneratorsMaxSecondIndex(){
        requireGenerators();
        if (generatorsByVmId != null) {
            throw new IllegalStateException("VM-ID-keyed generator rows do not have a shared dense second dimension");
        }
        return generators[0].length;
    }
    

    /**
     * 按 VM 标识和任务深度获取对应的失效生成器。
     * @param vmIndex VM 标识
     * @param taskDepth 任务深度
     * @return 对应的失效生成器
     */
    public static DistributionGenerator getGenerator(int vmIndex, int taskDepth) {
        requireGenerators();
        if (generatorsByVmId != null) {
            DistributionGenerator[] row = generatorsByVmId.get(Integer.valueOf(vmIndex));
            if (vmIndex < 0 || row == null || taskDepth < 0 || taskDepth >= row.length) {
                throw missingGenerator(vmIndex, taskDepth);
            }
            return row[taskDepth];
        }
        if (vmIndex < 0 || vmIndex >= generators.length || taskDepth < 0
                || taskDepth >= generators[vmIndex].length) {
            throw missingGenerator(vmIndex, taskDepth);
        }
        return generators[vmIndex][taskDepth];
    }

    /**
     * 按当前失效模式解析一个任务实际应使用的生成器。
     *
     * <p>名称中含 {@code JOB} 的模式按 DAG depth 选择，而不是按 Job ID 选择。该方法
     * 是故障判定和 DR 估计共享的唯一坐标解释，防止两个路径各自使用不同矩阵下标。</p>
     *
     * @param vmId 任务尝试所在 VM 的真实 ID
     * @param taskDepth 任务的 DAG 深度
     * @return 当前失效范围对应的生成器
     */
    public static DistributionGenerator getGeneratorForFailureScope(int vmId, int taskDepth) {
        switch (failureMode) {
            case FAILURE_ALL:
                return getGenerator(0, 0);
            case FAILURE_JOB:
                return getGenerator(0, taskDepth);
            case FAILURE_VM:
                return getGenerator(vmId, 0);
            case FAILURE_VM_JOB:
                return getGenerator(vmId, taskDepth);
            case FAILURE_NONE:
            default:
                throw new IllegalStateException("Failure generator is unavailable because failure mode is "
                        + failureMode);
        }
    }

    /**
     * 在任何 Job 提交前，用解析出的实际深度和平台声明的 VM ID 校验故障生成器覆盖。
     *
     * <p>该方法不抽样也不推进游标。对于 {@code FAILURE_VM_JOB}，会校验所有 VM ID 与
     * 所有已观察深度的笛卡尔积，因为在线调度和重试都可能改变最终 VM 归属。</p>
     *
     * @param tasks 已通过 DAG 校验并已写入深度的任务
     * @param vmIds 平台声明的真实 VM ID
     */
    public static void validateRuntimeCoverage(Collection<Task> tasks,
            Collection<Integer> vmIds) {
        if (failureMode == FTCFailure.FAILURE_NONE) {
            return;
        }
        if (tasks == null || vmIds == null) {
            throw new IllegalArgumentException("Failure coverage validation requires tasks and VM IDs");
        }
        TreeSet<Integer> observedDepths = new TreeSet<Integer>();
        for (Task task : tasks) {
            if (task == null || task.getDepth() <= 0) {
                throw new IllegalStateException("Failure coverage validation requires parsed tasks with positive DAG depths");
            }
            observedDepths.add(Integer.valueOf(task.getDepth()));
        }
        TreeSet<Integer> declaredVmIds = new TreeSet<Integer>();
        for (Integer vmId : vmIds) {
            if (vmId == null || vmId.intValue() < 0) {
                throw new IllegalStateException("Failure coverage validation requires non-negative VM IDs");
            }
            declaredVmIds.add(vmId);
        }

        switch (failureMode) {
            case FAILURE_ALL:
                validateCoverageCoordinate(0, 0, declaredVmIds, observedDepths);
                return;
            case FAILURE_JOB:
                requireObservedDepths(observedDepths);
                for (Integer depth : observedDepths) {
                    validateCoverageCoordinate(0, depth.intValue(), declaredVmIds, observedDepths);
                }
                return;
            case FAILURE_VM:
                requireDeclaredVmIds(declaredVmIds);
                for (Integer vmId : declaredVmIds) {
                    validateCoverageCoordinate(vmId.intValue(), 0, declaredVmIds, observedDepths);
                }
                return;
            case FAILURE_VM_JOB:
                requireDeclaredVmIds(declaredVmIds);
                requireObservedDepths(observedDepths);
                for (Integer vmId : declaredVmIds) {
                    for (Integer depth : observedDepths) {
                        validateCoverageCoordinate(vmId.intValue(), depth.intValue(),
                                declaredVmIds, observedDepths);
                    }
                }
                return;
            case FAILURE_NONE:
            default:
                return;
        }
    }

    private static void validateCoverageCoordinate(int vmId, int depth,
            TreeSet<Integer> declaredVmIds, TreeSet<Integer> observedDepths) {
        try {
            getGenerator(vmId, depth);
        } catch (IllegalStateException exception) {
            throw new IllegalStateException("Failure generator coverage validation failed before job dispatch: "
                    + "mode=" + failureMode + ", required=[vm=" + vmId + ", depth=" + depth
                    + "], declaredVmIds=" + declaredVmIds + ", observedDepths=" + observedDepths,
                    exception);
        }
    }

    private static void requireDeclaredVmIds(TreeSet<Integer> vmIds) {
        if (vmIds.isEmpty()) {
            throw new IllegalStateException("Failure generator coverage validation requires at least one VM ID for "
                    + failureMode);
        }
    }

    private static void requireObservedDepths(TreeSet<Integer> depths) {
        if (depths.isEmpty()) {
            throw new IllegalStateException("Failure generator coverage validation requires at least one parsed task for "
                    + failureMode);
        }
    }

    private static IllegalStateException missingGenerator(int vmIndex, int taskDepth) {
        String addressing = generatorsByVmId == null ? "dense VM-ID matrix" : "VM-ID-keyed rows";
        return new IllegalStateException("Failure generator " + addressing + " does not cover vm=" + vmIndex
                + ", taskDepth=" + taskDepth + " for mode " + failureMode);
    }
    
    /**
     * 获取当前失效生成模式。
     *
     * @return 失效生成模式
     * @pre $none
     * @post $none
     */
    public static FTCFailure getFailureGeneratorMode() {
        return failureMode;
    }

    /**
     * 获取当前容错监控模式。
     *
     * @return 容错监控模式
     * @pre $none
     * @post $none
     */
    public static FTCMonitor getMonitorMode() {
        return monitorMode;
    }

    /**
     * 获取当前容错聚类方法。
     *
     * @return 容错聚类方法
     * @pre $none
     * @post $none
     */
    public static FTCluteringAlgorithm getFTCluteringAlgorithm() {
        return FTClusteringAlgorithm;
    }
    
    /**
     * 获取当前失效到达时间的分布族。
     * @return 分布族
     */
    public static DistributionFamily getFailureDistribution(){
        return distribution;
    }

    /** @return 当前运行允许的 retry Job 总数；-1 仅表示遗留 API 的无上限兼容模式 */
    public static int getMaxTotalRetryJobs() {
        return maxTotalRetryJobs;
    }

    /** @return 当前现代会话安装的根随机种子；遗留 API 未安装时为 0 */
    public static long getRootSeed() {
        return rootSeed;
    }

    private static void validateGenerators(FTCFailure failure,
            DistributionGenerator[][] failureGenerators,
            Map<Integer, DistributionGenerator[]> failureGeneratorsByVmId) {
        if (failure == FTCFailure.FAILURE_NONE) {
            if (failureGenerators != null && failureGenerators.length != 0) {
                throw new IllegalArgumentException("Disabled failure mode cannot define generators");
            }
            if (failureGeneratorsByVmId != null && !failureGeneratorsByVmId.isEmpty()) {
                throw new IllegalArgumentException("Disabled failure mode cannot define VM-ID-keyed generators");
            }
            return;
        }
        boolean hasDenseMatrix = failureGenerators != null && failureGenerators.length != 0;
        boolean hasVmIdKeyedRows = failureGeneratorsByVmId != null
                && !failureGeneratorsByVmId.isEmpty();
        if (hasDenseMatrix == hasVmIdKeyedRows) {
            throw new IllegalArgumentException("Enabled failure mode requires exactly one generator layout");
        }
        if (hasVmIdKeyedRows) {
            if (failure != FTCFailure.FAILURE_VM && failure != FTCFailure.FAILURE_VM_JOB) {
                throw new IllegalArgumentException("VM-ID-keyed generators require FAILURE_VM or FAILURE_VM_JOB");
            }
            for (Map.Entry<Integer, DistributionGenerator[]> entry : failureGeneratorsByVmId.entrySet()) {
                if (entry.getKey() == null || entry.getKey().intValue() < 0
                        || entry.getValue() == null || entry.getValue().length == 0) {
                    throw new IllegalArgumentException("VM-ID-keyed failure generator row is invalid");
                }
                for (int column = 0; column < entry.getValue().length; column++) {
                    if (entry.getValue()[column] == null) {
                        throw new IllegalArgumentException("Failure generator for VM " + entry.getKey()
                                + " at depth " + column + " is null");
                    }
                }
            }
            return;
        }
        for (int row = 0; row < failureGenerators.length; row++) {
            if (failureGenerators[row] == null || failureGenerators[row].length == 0) {
                throw new IllegalArgumentException("Failure generator row " + row + " is empty");
            }
            for (int column = 0; column < failureGenerators[row].length; column++) {
                if (failureGenerators[row][column] == null) {
                    throw new IllegalArgumentException("Failure generator [" + row + "][" + column + "] is null");
                }
            }
        }
    }

    private static void requireGenerators() {
        if (failureMode == FTCFailure.FAILURE_NONE) {
            throw new IllegalStateException("Failure generators are unavailable because failure mode is disabled");
        }
        boolean hasDenseMatrix = generators != null && generators.length != 0;
        boolean hasVmIdKeyedRows = generatorsByVmId != null && !generatorsByVmId.isEmpty();
        if (!hasDenseMatrix && !hasVmIdKeyedRows) {
            throw new IllegalStateException("Failure generators are not initialized for this simulation run");
        }
    }
}
