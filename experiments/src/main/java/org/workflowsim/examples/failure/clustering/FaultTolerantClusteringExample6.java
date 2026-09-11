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
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 允许选择水平或垂直聚类方式的历史动态重聚类教学示例。
 *
 * <p>该类通过旧版单字符参数组合故障、开销和聚类设置，用于兼容性探索。它不是标准
 * 研究实验入口，参数扫描结果也不能在没有冻结种子与工件的情况下作统计解释。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 31, 2013
 */
public class FaultTolerantClusteringExample6 extends FaultTolerantClusteringExample1 {

    private static final String EXAMPLE_ID = "fault-clustering-options";
    /** 固定教学种子下的低频共享故障时钟尺度，不代表真实故障率。 */
    private static final double DEFAULT_FAILURE_INTERVAL_SCALE = 75000.0;

    /**
     * 运行可选聚类方式的历史容错场景。
     *
     * <p>支持 {@code -c}（SR/DR/NOOP/DC/VR）、{@code -d}、{@code -b}、
     * {@code -q}/{@code -s}（队列 Gamma 参数）、{@code -w}（队列先验强度）、
     * {@code -p}（故障尺度）、{@code -t}（故障先验强度）和 {@code -r}（根种子）。
     * {@code -b} 的值除以十后作为运行时间缩放系数，其余数值为有限正数。</p>
     *
     * @param args 旧式单字符 option 与值组成的参数序列
     */
    public static void main(String[] args) {
        try {
            Map<String, String> options = ExampleCliSupport.parseValueOptions(args,
                    "-c", "-d", "-b", "-w", "-q", "-s", "-p", "-t", "-r");
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
            double queuePriorScale = ExampleCliSupport.positiveFiniteProduct("-w * -q",
                    queuePriorWeight, queueScale);
            double failurePriorScale = ExampleCliSupport.positiveFiniteProduct("-t * -p",
                    failurePriorWeight, failureScale);

            ClusteringParameters.ClusteringMethod clusteringMethod = "VR".equals(clustering)
                    ? ClusteringParameters.ClusteringMethod.VERTICAL
                    : ClusteringParameters.ClusteringMethod.HORIZONTAL;
            FailureParameters.FTCluteringAlgorithm strategy = strategyFor(clustering);
            int maxLevel = 11;

            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            // 历史 E6 使用一个共享全局故障时钟；它不是 VM-深度独立故障模型。
            DistributionGenerator failureGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.WEIBULL,
                    failureScale, 0.78, failurePriorWeight, failurePriorScale, 0.78,
                    "example.fault-clustering-options.legacy-shared-clock");
            for (int level = 0; level < maxLevel; level++) {
                for (int vmId = 0; vmId < vmNum; vmId++) {
                    failureGenerators[vmId][level] = failureGenerator;
                }
            }

            Map<Integer, DistributionGenerator> queueDelay = new HashMap<Integer, DistributionGenerator>();
            DistributionGenerator queueGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.GAMMA, queueScale, queueShape,
                    queuePriorWeight, queuePriorScale, queueShape,
                    "example.fault-clustering-options.queue");
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
