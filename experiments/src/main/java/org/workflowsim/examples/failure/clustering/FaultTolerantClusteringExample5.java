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

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
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
 * 供遗留参数扫描调用的动态重聚类容错教学示例。
 *
 * <p>本类保留可返回 makespan 的历史入口，便于理解原始参数实验代码。它没有冻结实验
 * 计划、种子和证据工件，因此不能作为标准研究实验入口或统计结论来源。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 31, 2013
 */
public class FaultTolerantClusteringExample5 extends FaultTolerantClusteringExample1 {

    private static final String EXAMPLE_ID = "fault-clustering-dynamic";
    /** 固定教学种子下的低频共享故障时钟尺度，不代表真实故障率。 */
    private static final double DEFAULT_FAILURE_INTERVAL_SCALE = 150000.0;

    /**
     * 运行带动态重聚类的历史教学情景。
     *
     * <p>支持的 option 为 {@code -c}（SR/DR/NOOP/DC）、{@code -d}（DAX 路径）、
     * {@code -b}（运行时间缩放的十分之一）、{@code -q}/{@code -s}（队列 Gamma 参数）、
     * {@code -w}（队列先验强度）、{@code -p}（故障尺度）、{@code -t}（故障先验强度）
     * 和 {@code -r}（根种子）。所有数值均须为有限正数。</p>
     *
     * @param args 旧式单字符 option 与值组成的参数序列
     */
    public static void main(String[] args) {
        double makespan = runInternal(args, true);
        Log.printLine("EXAMPLE_MAKESPAN id=" + EXAMPLE_ID + " value=" + makespan);
    }

    /**
     * 兼容旧参数扫描代码的 makespan 入口。
     *
     * <p>错误不再伪装为 {@code 0.0}，而是通过未检查异常向调用方传播。批量调用方应
     * 自己建立种子计划、统计口径和结果工件。</p>
     *
     * @param args 与 {@link #main(String[])} 相同的 option 序列
     * @return 本次模型运行的 makespan
     */
    public static double main2(String[] args) {
        return runInternal(args, false);
    }

    private static double runInternal(String[] args, boolean reportCompletion) {
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
                    "SR", "DR", "NOOP", "DC");
            double runtimeScaleTenths = ExampleCliSupport.positiveFinite("-b",
                    ExampleCliSupport.optionOrDefault(options, "-b", "10"));
            double queuePriorWeight = ExampleCliSupport.positiveFinite("-w",
                    ExampleCliSupport.optionOrDefault(options, "-w", "10"));
            double queueScale = ExampleCliSupport.positiveFinite("-q",
                    ExampleCliSupport.optionOrDefault(options, "-q", "50"));
            double queueShape = ExampleCliSupport.positiveFinite("-s",
                    ExampleCliSupport.optionOrDefault(options, "-s", "2"));
            double failureScale = ExampleCliSupport.positiveFinite("-p",
                    ExampleCliSupport.optionOrDefault(options, "-p",
                            Double.toString(
                                    DEFAULT_FAILURE_INTERVAL_SCALE)));
            double failurePriorWeight = ExampleCliSupport.positiveFinite("-t",
                    ExampleCliSupport.optionOrDefault(options, "-t", "30"));
            double queuePriorScale = ExampleCliSupport.positiveFiniteProduct("-w * -q * -s",
                    queuePriorWeight, queueScale, queueShape);
            double failurePriorScale = ExampleCliSupport.positiveFiniteProduct("-t * -p",
                    failurePriorWeight, failureScale);

            FailureParameters.FTCMonitor monitor = FailureParameters.FTCMonitor.MONITOR_VM_JOB;
            FailureParameters.FTCFailure failureMode = FailureParameters.FTCFailure.FAILURE_VM_JOB;
            FailureParameters.FTCluteringAlgorithm strategy = strategyFor(clustering);
            int maxLevel = 11;

            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            // 历史 E5 使用一个共享全局故障时钟；它不是 VM-深度独立故障模型。
            DistributionGenerator failureGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.WEIBULL,
                    failureScale, 0.78, failurePriorWeight, failurePriorScale, 0.78,
                    "example.fault-clustering-dynamic.legacy-shared-clock");
            for (int level = 0; level < maxLevel; level++) {
                for (int vmId = 0; vmId < vmNum; vmId++) {
                    failureGenerators[vmId][level] = failureGenerator;
                }
            }

            Map<Integer, DistributionGenerator> queueDelay = new HashMap<Integer, DistributionGenerator>();
            DistributionGenerator queueGenerator = new DistributionGenerator(
                    DistributionGenerator.DistributionFamily.GAMMA, queueScale, queueShape,
                    queuePriorWeight, queuePriorScale, queueShape,
                    "example.fault-clustering-dynamic.queue");
            for (int level = 0; level < maxLevel; level++) {
                queueDelay.put(level, queueGenerator);
            }

            OverheadParameters overhead = new OverheadParameters(0,
                    new HashMap<Integer, DistributionGenerator>(), queueDelay,
                    new HashMap<Integer, DistributionGenerator>(),
                    new HashMap<Integer, DistributionGenerator>(), 0);
            ClusteringParameters clusteringParameters = new ClusteringParameters(vmNum, 0,
                    ClusteringParameters.ClusteringMethod.HORIZONTAL, null);

            FailureParameters.init(strategy, monitor, failureMode, failureGenerators);
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
            if (jobs == null || jobs.isEmpty()) {
                throw new IllegalStateException("Fault-tolerant clustering scenario produced no jobs");
            }
            double makespan = printJobList2(jobs);
            if (reportCompletion) {
                ExampleCliSupport.reportCompletion(jobs);
            }
            return makespan;
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
        throw new IllegalArgumentException("Unsupported fault-tolerant clustering strategy: " + clustering);
    }

    /**
     * 输出 Job 摘要并返回列表中最大的完成时间。
     *
     * @param list 已完成的 Job 列表
     * @return 本次模型运行的 makespan
     */
    protected static double printJobList2(List<Job> list) {
        int size = list.size();
        double makespan = 0;
        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + indent + "Time" + indent
                + "Start Time" + indent + "Finish Time" + indent + "Depth");

        DecimalFormat formatter = new DecimalFormat("###.##");
        for (Job job : list) {
            Log.print(indent + job.getCloudletId() + indent + indent);
            if (job.getFinishTime() > makespan) {
                makespan = job.getFinishTime();
            }
            if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS");
            } else if (job.getCloudletStatus() == Cloudlet.FAILED) {
                Log.print("FAILED");
            } else {
                Log.print("STATUS_" + job.getCloudletStatus());
            }
            Log.printLine(indent + indent + job.getResourceId() + indent + indent + indent
                    + job.getVmId() + indent + indent + indent
                    + formatter.format(job.getActualCPUTime()) + indent + indent
                    + formatter.format(job.getExecStartTime()) + indent + indent + indent
                    + formatter.format(job.getFinishTime()) + indent + indent + indent
                    + job.getDepth());
        }
        return makespan;
    }
}
