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
package org.workflowsim.examples.cost;

import java.io.File;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.Job;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.ExampleCliSupport;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 输出按 VM 计算的历史成本模型结果的教学示例。
 *
 * <p>它与按数据中心计费的示例对照，用于理解旧版成本字段；这些值是模型观察量，
 * 不是云服务商价格或账单预测，也不是标准研究实验入口。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 6, 2014
 */
public class WorkflowSimCostExample2 extends WorkflowSimCostExample1 {

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的按 VM 成本场景。
     */
    public static void main(String[] args) {
        try {
            ExampleCliSupport.begin("cost-vm");
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
            /**
             * 该场景由 MINMIN 调度器决定映射，因此禁用规划器。规划和调度在这条历史路径中
             * 不是两个可以同时独立优化的映射阶段。
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /** 选择按 VM 计费的历史成本模型；默认模型按数据中心计费。 */
            Parameters.setCostModel(Parameters.CostModel.VM);

            /** 不注入额外开销。 */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /** 不执行任务聚类。 */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /** 初始化历史静态参数与文件副本目录。 */
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
            throw ExampleCliSupport.failure("cost-vm", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    protected static List<CondorVM> createVM(int userId, int vms) {

        // 创建 VM 容器，随后提交给工作流执行引擎。
        LinkedList<CondorVM> list = new LinkedList<>();
        // VM 的抽象资源规格。
        long size = 10000; // 镜像大小（MB）
        int ram = 512; // VM 内存（MB）
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // CPU 核数
        String vmm = "Xen"; // 虚拟机监控器名称

        // 创建带抽象单价字段的 VM。
        CondorVM[] vm = new CondorVM[vms];
        double cost = 3.0;              // 抽象计算单价。
        double costPerMem = 0.05;		// 抽象内存单价。
        double costPerStorage = 0.1;	// 抽象存储单价。
        double costPerBw = 0.1;			// 抽象带宽单价。
        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(i, userId, mips * ratio, pesNumber, ram, bw, size, vmm,
                    cost, costPerMem, costPerStorage, costPerBw, new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }
        return list;
    }
}
