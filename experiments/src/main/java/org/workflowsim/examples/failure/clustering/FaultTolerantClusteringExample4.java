/**
 * Copyright 2013-2014 University Of Southern California
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

import java.io.File;
import java.util.Calendar;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.Job;
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
 * 按 VM 索引建模任务故障的历史容错聚类教学示例。
 *
 * <p>它与 {@code FaultTolerantClusteringExample1} 对照，展示 VM 维度的故障生成
 * 假设。它不是标准研究实验入口，也不能用于主张真实 VM 故障率。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 2, 2014
 */
public class FaultTolerantClusteringExample4 extends FaultTolerantClusteringExample1 {

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的 VM 维度故障场景。
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("fault-clustering-vm");
            // 第一步：配置历史 WorkflowSim 静态参数。
            /** 请求创建的 VM 数；资源不足时实际可创建数量可能更少。 */
            int vmNum = 20; // VM 数量。
            /** 请按本机数据集位置调整 DAX 路径。 */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }
            /* 容错参数。 */
            /** MONITOR_JOB 按任务深度归类，MONITOR_VM 按 VM 标识归类，MONITOR_ALL 汇总，MONITOR_NONE 不记录。 */
            FailureParameters.FTCMonitor ftc_monitor = FailureParameters.FTCMonitor.MONITOR_VM;
            /** FTCFailure 决定生成器按何种粒度选取失效到达分布。 */
            FailureParameters.FTCFailure ftc_failure = FailureParameters.FTCFailure.FAILURE_VM;
            /** 此历史示例选择基于估计器的动态容错重聚类策略。 */
            FailureParameters.FTCluteringAlgorithm ftc_method = FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DR;
            /** 生成器矩阵按 VM 和工作流层级构建。 */
            int maxLevel = 11;
            DistributionGenerator[][] failureGenerators = new DistributionGenerator[vmNum][maxLevel];
            /** 按 VM 建模失效到达过程；每个 VM 持有独立分布实例。 */
            for (int vmId = 0; vmId < vmNum; vmId++) {
                DistributionGenerator generator = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL,
                        ExampleCliSupport.LEGACY_CLUSTERING_DEMO_FAILURE_INTERVAL_SCALE, 1.0, 30, 300, 0.78);
                for (int level = 0; level < maxLevel; level++) {
                    failureGenerators[vmId][level] = generator;
                }
            }
            /** 该场景由 MINMIN 调度器决定映射，因此禁用规划器。 */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /** 不注入额外开销。 */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /** 常规任务聚类关闭；容错恢复策略在失败后另行处理。 */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /** 初始化旧版故障状态、静态参数与文件目录。 */
            FailureParameters.init(ftc_method, ftc_monitor, ftc_failure, failureGenerators);
            Parameters.init(vmNum, daxPath, null,
                    null, op, cp, sch_method, pln_method,
                    null, 0);
            ReplicaCatalog.init(file_system);

            FailureMonitor.init();
            FailureGenerator.init();

            // 在创建模拟实体前初始化 CloudSim。
            int num_user = 1;   // 模拟用户数。
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;  // 是否记录 CloudSim 事件轨迹。

            // 初始化 CloudSim 事件引擎。
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /** 创建一个包含单调度器的工作流规划器。 */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /** 获取对应的工作流执行引擎。 */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /** 创建 VM 列表；VM 的 userId 对应管理它的调度器标识。 */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /** 将 VM 列表提交给工作流执行引擎。 */
            wfEngine.submitVmList(vmlist0, 0);

            /** 将数据中心绑定给该调度器。 */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("fault-clustering-vm", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
