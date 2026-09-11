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
package org.workflowsim.examples.failure.clustering;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.PeriodicalDistributionGenerator;
import org.workflowsim.utils.PeriodicalSignal;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 使用周期性故障信号的历史动态重聚类教学示例。
 *
 * <p>该类将周期性信号、故障分布和重聚类路径组合为一个旧版可执行场景。所有参数都是
 * 抽象情景假设；它不是标准研究实验入口，也不能用于推断真实平台的周期性故障行为。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 31, 2013
 */
public class FaultTolerantClusteringExample7 extends FaultTolerantClusteringExample1 {

    private static final String EXAMPLE_ID = "fault-clustering-periodic";
    /** 周期性缓存耗尽后的兼容回退尺度及旧估计器先验基准，不代表真实故障率。 */
    private static final double DEFAULT_FAILURE_INTERVAL_SCALE = 100000.0;
    /** 固定教学种子下短间隔的高故障阶段尺度。 */
    private static final double DEFAULT_HIGH_FAILURE_INTERVAL_SCALE = 30000.0;
    /** 固定教学种子下长间隔的低故障阶段尺度。 */
    private static final double DEFAULT_LOW_FAILURE_INTERVAL_SCALE = 180000.0;

    /**
     * 运行带周期性两级失效间隔的容错场景。
     *
     * <p>支持 {@code -c}（SR/DR/NOOP/DC/VR）、{@code -d}、{@code -b}、
     * {@code -q}/{@code -s}（队列 Gamma 参数）、{@code -w}（队列先验强度）、
     * {@code -p}/{@code -t}（旧故障估计器参数）、{@code -e}（周期）、{@code -n}
     * （高故障阶段比例）、{@code -h}/{@code -l}（高/低故障阶段的 Weibull 间隔尺度）
     * 和 {@code -r}（根种子）。高故障阶段尺度必须小于低故障阶段尺度。周期性到达的
     * 实际抽样由 {@code -h}/{@code -l} 控制；{@code -p}/{@code -t} 保留为旧估计器
     * 先验及缓存耗尽后的兼容回退参数。</p>
     *
     * @param args 旧式单字符 option 与值组成的参数序列
     */
    public static void main(String[] args) {
        try {
            Map<String, String> options = ExampleCliSupport.parseValueOptions(args,
                    "-c", "-d", "-b", "-w", "-q", "-s", "-p", "-t", "-e", "-n",
                    "-h", "-l", "-r");
            long rootSeed = ExampleCliSupport.longValue("-r",
                    ExampleCliSupport.optionOrDefault(options, "-r",
                            Long.toString(ExampleCliSupport.LEGACY_DEMO_SEED)));
            ExampleCliSupport.begin(EXAMPLE_ID, rootSeed);

            int vmNum = 20;
            String daxPath = ExampleCliSupport.requireDax(ExampleCliSupport.optionOrDefault(
                    options, "-d", ExampleCliSupport.DEFAULT_DAX_PATH));
            String clustering = ExampleCliSupport.allowedValue("-c",
                    ExampleCliSupport.optionOrDefault(options, "-c", "DR"),
                    "SR", "DR", "NOOP", "DC", "VR");
            double runtimeScaleTenths = ExampleCliSupport.positiveFinite("-b",
                    ExampleCliSupport.optionOrDefault(options, "-b", "10"));
            double queuePriorWeight = ExampleCliSupport.positiveFinite("-w",
                    ExampleCliSupport.optionOrDefault(options, "-w", "3"));
            double queueScale = ExampleCliSupport.positiveFinite("-q",
                    ExampleCliSupport.optionOrDefault(options, "-q", "50"));
            double queueShape = ExampleCliSupport.positiveFinite("-s",
                    ExampleCliSupport.optionOrDefault(options, "-s", "3"));
            double failureScale = ExampleCliSupport.positiveFinite("-p",
                    ExampleCliSupport.optionOrDefault(options, "-p",
                            Double.toString(
                                    DEFAULT_FAILURE_INTERVAL_SCALE)));
            double failurePriorWeight = ExampleCliSupport.positiveFinite("-t",
                    ExampleCliSupport.optionOrDefault(options, "-t", "30"));
            double period = ExampleCliSupport.positiveFinite("-e",
                    ExampleCliSupport.optionOrDefault(options, "-e", "40000"));
            double highFailurePortion = ExampleCliSupport.openUnitInterval("-n",
                    ExampleCliSupport.optionOrDefault(options, "-n", "0.1"));
            double highFailureIntervalScale = ExampleCliSupport.positiveFinite("-h",
                    ExampleCliSupport.optionOrDefault(options, "-h",
                            Double.toString(DEFAULT_HIGH_FAILURE_INTERVAL_SCALE)));
            double lowFailureIntervalScale = ExampleCliSupport.positiveFinite("-l",
                    ExampleCliSupport.optionOrDefault(options, "-l",
                            Double.toString(DEFAULT_LOW_FAILURE_INTERVAL_SCALE)));
            if (highFailureIntervalScale >= lowFailureIntervalScale) {
                throw new IllegalArgumentException("-h must be smaller than -l so the high-failure phase "
                        + "has a shorter interval scale");
            }
            double queuePriorScale = ExampleCliSupport.positiveFiniteProduct("-w * -q",
                    queuePriorWeight, queueScale);
            double failurePriorScale = ExampleCliSupport.positiveFiniteProduct("-t * -p",
                    failurePriorWeight, failureScale);

            ClusteringParameters.ClusteringMethod clusteringMethod = "VR".equals(clustering)
                    ? ClusteringParameters.ClusteringMethod.VERTICAL
                    : ClusteringParameters.ClusteringMethod.HORIZONTAL;
            FailureParameters.FTCluteringAlgorithm strategy = strategyFor(clustering);
            int maxLevel = 11;
            // 为保留清晰语义，upper 为长间隔低故障阶段，lower 为短间隔高故障阶段。
            PeriodicalSignal signal = new PeriodicalSignal(period, lowFailureIntervalScale,
                    highFailureIntervalScale, highFailurePortion, false);

            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            // 历史 E7 使用一个共享全局故障时钟；它不是 VM-深度独立故障模型。
            DistributionGenerator failureGenerator = new PeriodicalDistributionGenerator(
                    DistributionGenerator.DistributionFamily.WEIBULL,
                    failureScale, 0.78, failurePriorWeight, failurePriorScale, 0.78,
                    signal, "example.fault-clustering-periodic.legacy-shared-clock");
            for (int level = 0; level < maxLevel; level++) {
                for (int vmId = 0; vmId < vmNum; vmId++) {
                    failureGenerators[vmId][level] = failureGenerator;
                }
            }

            Map<Integer, DistributionGenerator> queueDelay = new HashMap<Integer, DistributionGenerator>();
            DistributionGenerator queueGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.GAMMA, queueScale, queueShape,
                    queuePriorWeight, queuePriorScale, queueShape,
                    "example.fault-clustering-periodic.queue");
            for (int level = 0; level < maxLevel; level++) {
                queueDelay.put(level, queueGenerator);
            }

            OverheadParameters overhead = new OverheadParameters(0,
                    new HashMap<Integer, DistributionGenerator>(), queueDelay,
                    new HashMap<Integer, DistributionGenerator>(),
                    new HashMap<Integer, DistributionGenerator>(), 0);
            ClusteringParameters clusteringParameters = new ClusteringParameters(vmNum, 0,
                    clusteringMethod, null);

            FailureParameters.init(strategy, FailureParameters.FTCMonitor.MONITOR_VM_JOB,
                    FailureParameters.FTCFailure.FAILURE_VM_JOB, failureGenerators);
            Parameters.init(vmNum, daxPath, null, null, overhead, clusteringParameters,
                    Parameters.SchedulingAlgorithm.MINMIN, Parameters.PlanningAlgorithm.INVALID,
                    null, 0);
            Parameters.setRuntimeScale(runtimeScaleTenths / 10.0);
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
            FailureMonitor.init();
            FailureGenerator.init();

            CloudSim.init(1, Calendar.getInstance(), false);
            WorkflowDatacenter datacenter = createDatacenter("Datacenter_0");
            WorkflowPlanner planner = new WorkflowPlanner("planner_0", 1);
            WorkflowEngine engine = planner.getWorkflowEngine();
            List<CondorVM> virtualMachines = createVM(engine.getSchedulerId(0), Parameters.getVmNum());
            engine.submitVmList(virtualMachines, 0);
            engine.bindSchedulerDatacenter(datacenter.getId(), 0);

            CloudSim.startSimulation();
            List<Job> jobs = engine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(jobs);
            ExampleCliSupport.reportCompletion(jobs);
        } catch (Exception exception) {
            throw ExampleCliSupport.failure(EXAMPLE_ID, exception);
        } finally {
            ExampleCliSupport.end();
        }
    }

    private static FailureParameters.FTCluteringAlgorithm strategyFor(String clustering) {
        if ("SR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_SR;
        }
        if ("DR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DR;
        }
        if ("NOOP".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        }
        if ("DC".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DC;
        }
        if ("VR".equals(clustering)) {
            return FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_VERTICAL;
        }
        throw new IllegalArgumentException("Unsupported fault-tolerant clustering strategy: " + clustering);
    }
}
