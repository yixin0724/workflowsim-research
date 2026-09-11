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
package org.workflowsim.failure;

import java.util.IdentityHashMap;
import java.util.Map;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.workflowsim.utils.SimulationRandom;
import org.workflowsim.utils.DistributionGenerator;

/**
 * 在任务的执行时间窗内，根据已配置的失效到达时间样本判定 Job 是否失败。
 *
 * <p>本类依赖 {@link FailureParameters} 中由单次模拟会话安装的状态；它实现
 * 的是抽象失效模型，并不意味着已按真实云平台故障数据完成校准。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureGenerator {

    /**
     * 当现有样本尚未覆盖任务开始时间时，按需扩展分布样本；扩展次数受上限约束，
     * 以避免极高失效率导致无界采样。
     */
    private static final int maxFailureSizeExtension = 50;
    /** 每个生成器独立记录的样本缓存扩展次数，避免多个故障流相互消耗安全额度。 */
    private static final Map<DistributionGenerator, Integer> failureSizeExtensions
            = new IdentityHashMap<DistributionGenerator, Integer>();
    private static final boolean hasChangeTime = false;
    /**
     * 按当前配置的分布族创建随机分布。
     *
     * @param alpha 分布参数 alpha
     * @param beta 分布参数 beta
     * @return 对应的连续随机分布
     */
    protected static RealDistribution getDistribution(double alpha, double beta) {
        RealDistribution distribution = null;
        switch (FailureParameters.getFailureDistribution()) {
            case LOGNORMAL:
                distribution = new LogNormalDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"), 1.0 / alpha, beta,
                        LogNormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case WEIBULL:
                distribution = new WeibullDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"), beta, 1.0 / alpha,
                        WeibullDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case GAMMA:
                distribution = new GammaDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"), beta, 1.0 / alpha,
                        GammaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case NORMAL:
                // NORMAL 分布中 beta 为标准差，1.0 / alpha 为均值。
                distribution = new NormalDistribution(
                        SimulationRandom.newApacheRandom("failure.distribution"), 1.0 / alpha, beta,
                        NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            default:
                break;
        }
        return distribution;
    }

    protected static void initFailureSamples() {
    }

    /**
     * 初始化一次模拟运行的失效生成计数器。
     */
    public static void init() {
        failureSizeExtensions.clear();
        initFailureSamples();
    }

    /** 清除已结束模拟运行持有的失效生成计数器。 */
    public static void reset() {
        failureSizeExtensions.clear();
    }

    protected static boolean checkFailureStatus(Task task, int vmId) throws Exception {


        if (FailureParameters.getFailureGeneratorMode()
                == FailureParameters.FTCFailure.FAILURE_NONE) {
            return false;
        }
        // 故障判定与 DR 重聚类共享同一坐标解释，避免模式名称被不同路径重新解释。
        DistributionGenerator generator = FailureParameters.getGeneratorForFailureScope(
                vmId, task.getDepth());
        
        double start = task.getExecStartTime();
        double end = task.getTaskFinishTime();
        
        
        if (Double.isNaN(start) || Double.isInfinite(start) || Double.isNaN(end)
                || Double.isInfinite(end) || end < start) {
            throw new IllegalStateException("Task " + task.getCloudletId()
                    + " has an invalid failure-observation window [" + start + ", " + end + "]");
        }

        ensureUnconsumedArrivalCoverage(generator, start);
        double[] samples = generator.getCumulativeSamples();
        for (int sampleId = generator.getConsumedSampleCount(); sampleId < samples.length; sampleId++) {
            if (samples[sampleId] < start) {
                // 空闲期内已过期的 arrival 仍必须只消费一次。
                generator.consumeSamplesThrough(sampleId);
                continue;
            }
            if (end < samples[sampleId]) {
                // 下一个失效到达时间已在本任务结束之后，因此本任务不失败。
                return false;
            }
            if (start <= samples[sampleId]) {
                // 失效到达时间落在本任务执行区间内。
                // 消费命中的累计 arrival 本身，而不是错误地消费游标处的另一段原始间隔。
                generator.consumeSamplesThrough(sampleId);
                return true;
            }
        }

        throw new IllegalStateException("Failure arrival cache has no unconsumed sample after coverage");
    }

    /** 将单个故障流扩展到同时覆盖当前时间和未消费游标。 */
    private static void ensureUnconsumedArrivalCoverage(DistributionGenerator generator, double start)
            throws Exception {
        double[] samples = generator.getCumulativeSamples();
        while (generator.getConsumedSampleCount() >= samples.length
                || samples[samples.length - 1] < start) {
            generator.extendSamples();
            samples = generator.getCumulativeSamples();
            int extensions = extensionCount(generator) + 1;
            failureSizeExtensions.put(generator, extensions);
            if (extensions >= maxFailureSizeExtension) {
                throw new Exception("Failure arrival sample extension limit "
                        + maxFailureSizeExtension + " exceeded while covering task start " + start);
            }
        }
    }

    private static int extensionCount(DistributionGenerator generator) {
        Integer value = failureSizeExtensions.get(generator);
        return value == null ? 0 : value.intValue();
    }

    /**
     * 对 Job 内的任务逐个判定失效，并同步更新任务与 Job 的状态。
     *
     * @param job
     * @return {@code true} 表示至少一个任务失败
     */
    public static boolean generate(Job job) {
        boolean jobFailed = false;
        if (FailureParameters.getFailureGeneratorMode() == FailureParameters.FTCFailure.FAILURE_NONE) {
            // 即使没有启用失效到达流，完成的 compute Job 也需要为其逻辑 Task 建立明确的
            // 成功状态。数据中心据此按 Task 提交输出副本，避免把“未写状态”误判为失败。
            try {
                for (Task task : job.getTaskList()) {
                    task.setCloudletStatus(Cloudlet.SUCCESS);
                }
                job.setCloudletStatus(Cloudlet.SUCCESS);
                return false;
            } catch (Exception exception) {
                throw new IllegalStateException("Could not mark failure-disabled job "
                        + job.getCloudletId() + " as successful", exception);
            }
        }
        try {

            for (Task task : job.getTaskList()) {
                int failedTaskSum = 0;
                if (checkFailureStatus(task, job.getVmId())) {
                    // 当前任务的执行时间窗包含一次失效到达。
                    jobFailed = true;
                    failedTaskSum++;
                    task.setCloudletStatus(Cloudlet.FAILED);
                } else {
                    task.setCloudletStatus(Cloudlet.SUCCESS);
                }
                FailureRecord record = new FailureRecord(0, failedTaskSum, task.getDepth(), 1, job.getVmId(), task.getCloudletId(), job.getUserId());
                FailureMonitor.postFailureRecord(record);
            }

            if (jobFailed) {
                job.setCloudletStatus(Cloudlet.FAILED);
            } else {
                job.setCloudletStatus(Cloudlet.SUCCESS);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failure generation failed for job " + job.getCloudletId()
                    + " on VM " + job.getVmId(), exception);
        }
        return jobFailed;
    }
}
