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
package org.workflowsim.examples.clustering;

import java.io.File;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.Job;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.examples.WorkflowSimBasicExample1;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 以 {@code clusters.size} 指定聚类强度的历史水平聚类教学示例。
 *
 * <p>它与按 {@code clusters.num} 配置的 {@code HorizontalClusteringExample2} 对照，
 * 用于理解旧版水平聚类参数。任务聚类不在当前标准研究 runner 的支持范围内，因此本类
 * 不是标准研究实验入口。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class HorizontalClusteringExample1 extends WorkflowSimBasicExample1 {

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的历史水平聚类场景。
     */
    public static void main(String[] args) {

       try {
            ExampleCliSupport.begin("horizontal-clustering-size");
            // 第一步：配置历史 WorkflowSim 静态参数。
            /**
             * 请求创建的 VM 数；若数据中心或 Host 资源不足，实际可创建数量可能更少。
             */
            int vmNum = 20; // VM 数量。
            /**
             * 请按本机数据集位置调整 DAX 路径。
             */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if(!daxFile.exists()){
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }

            /**
             * 该场景由 MINMIN 调度器决定映射，因此禁用规划器，避免其覆盖调度结果。
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /**
             * 水平聚类需要提供每层的聚类延迟映射；若不研究该开销，应使用零值分布，
             * 而不是传入 {@code null}。
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            /**
             * 此 Montage 示例假定最多包含 11 个水平层级。
             */
            int maxLevel = 11;
            for (int level = 0; level < maxLevel; level++ ){
                DistributionGenerator cluster_delay = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 1.0);
                clusteringDelay.put(level, cluster_delay); // 为每层安装相同的聚类延迟分布。
            }
            // 将聚类延迟装入开销参数。
            OverheadParameters op = new OverheadParameters(0, null, null, null, clusteringDelay, 0);
            
            /**
             * 选择水平聚类。
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.HORIZONTAL;
            /**
             * {@code clusters.num} 与 {@code clusters.size} 只能指定一个：前者表示每层的
             * 聚类 Job 数，后者表示每个聚类 Job 的任务数；二者乘积对应每层任务总数。
             * 此处指定 {@code clusters.size = 2}，即每个 Job 包含两个任务。
             */
            ClusteringParameters cp = new ClusteringParameters(0, 2, method, null);
            
            /**
             * 初始化历史静态参数与文件副本目录。
             */
            Parameters.init(vmNum, daxPath, null,
                    null, op, cp, sch_method, pln_method,
                    null, 0);
            ReplicaCatalog.init(file_system);

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
            throw ExampleCliSupport.failure("horizontal-clustering-size", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
