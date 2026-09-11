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
package org.workflowsim.examples;

import java.io.File;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerDynamicWorkload;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.Job;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 使用 {@code CloudletSchedulerDynamicWorkload} 作为 VM 本地调度器的历史教学示例。
 *
 * <p>本示例直接配置历史静态 API，仅用于理解动态工作负载调度器与 WorkflowSim 的连接方式；
 * 它不是标准研究实验入口，也不提供可审计的实验配置或工件。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Oct 13, 2013
 */
public class DynamicWorkloadExample1 extends WorkflowSimBasicExample1 {

    protected static List<CondorVM> createVM(int userId, int vms) {

        // 创建 VM 容器，随后提交给工作流引擎。
        LinkedList<CondorVM> list = new LinkedList<>();

        // VM 的抽象资源规格。
        long size = 10000; // 镜像大小（MB）
        int ram = 512; // VM 内存（MB）
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; // CPU 核数
        String vmm = "Xen"; // 虚拟机监控器名称

        // 创建使用动态工作负载调度器的 VM。
        CondorVM[] vm = new CondorVM[vms];

        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(i, userId, mips * ratio, pesNumber, ram, bw, size, vmm, new CloudletSchedulerDynamicWorkload(mips * ratio, pesNumber));
            list.add(vm[i]);
        }

        return list;
    }

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的历史动态工作负载场景。
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("dynamic-workload");
            // 第一步：配置历史 WorkflowSim 静态参数。

            /**
             * 数据中心或 Host 容量不足时，实际创建的 VM 数可能少于声明值。
             */
            int vmNum = 20; // VM 数量
            /**
             * 默认使用项目内工作流路径；从其他工作目录执行时需要调整。
             */
            String daxPath = ExampleCliSupport.singleDax(args, ExampleCliSupport.DEFAULT_DAX_PATH);
            File daxFile = new File(daxPath);
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }

            /**
             * 历史 HEFT 规划需要静态调度层，避免调度阶段覆盖规划结果。
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.STATIC;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.HEFT;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /**
             * 不注入额外开销。
             */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * 不进行任务聚类。
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * 初始化历史全局参数与文件目录。
             */
            Parameters.init(vmNum, daxPath, null,
                    null, op, cp, sch_method, pln_method,
                    null, 0);
            ReplicaCatalog.init(file_system);

            // 在创建任何 CloudSim 实体之前完成初始化。
            int num_user = 1;   // Grid 用户数
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;  // 是否记录事件追踪

            // 初始化 CloudSim。
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /**
             * 创建包含一个调度器的工作流规划器。
             */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /**
             * 取得与规划器关联的工作流执行引擎。
             */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * 创建 VM 列表；VM 的 userId 对应其调度器 ID。
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum());

            /**
             * 将 VM 列表提交给工作流执行引擎。
             */
            wfEngine.submitVmList(vmlist0, 0);

            /**
             * 将数据中心绑定到调度器。
             */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);

            CloudSim.startSimulation();
            List<Job> outputList0 = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList0);
            ExampleCliSupport.reportCompletion(outputList0);
        } catch (Exception e) {
            throw ExampleCliSupport.failure("dynamic-workload", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
