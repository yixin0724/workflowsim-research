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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
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
 * 同时提交多个 DAX 工作流的历史教学示例。
 *
 * <p>本类使用历史静态参数传入多个输入路径，适合说明旧版多工作流装配方式；它不是
 * 标准研究实验入口，也不替代冻结输入、种子计划和证据工件的 campaign 协议。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Nov 9, 2014
 */
public class WorkflowSimMultipleWorkflowsExample1 {

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

        // 按相同规格创建 VM。
        CondorVM[] vm = new CondorVM[vms];

        for (int i = 0; i < vms; i++) {
            double ratio = 1.0;
            vm[i] = new CondorVM(i, userId, mips * ratio, pesNumber, ram, bw, size, vmm, new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }

        return list;
    }

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的多工作流历史场景。
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("multiple-workflows");
            // 第一步：配置历史 WorkflowSim 静态参数。

            /**
             * 数据中心或 Host 容量不足时，实际创建的 VM 数可能少于声明值。
             */
            int vmNum = 20; // VM 数量
            /**
             * 默认使用项目内相对路径；从其他工作目录执行时需要调整。
             */
            List<String> daxPaths = ExampleCliSupport.multipleDax(args,
                    ExampleCliSupport.DEFAULT_MULTIPLE_DAX_PATHS, 3);

            /**
             * 历史 MINMIN 调度在调度层决策，因此关闭规划器以避免覆盖其结果。
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.MINMIN;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

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
            Parameters.init(vmNum, daxPaths, null,
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
            throw ExampleCliSupport.failure("multiple-workflows", e);
        } finally {
            ExampleCliSupport.end();
        }
    }

    protected static WorkflowDatacenter createDatacenter(String name) {

        // 构建数据中心：先创建一个或多个 Host 的列表。
        List<Host> hostList = new ArrayList<>();

        // 每个 Host 由一个或多个 PE（CPU 核）组成，因此先构造 PE 列表。
        for (int i = 1; i <= 20; i++) {
            List<Pe> peList1 = new ArrayList<>();
            int mips = 2000;
            // 创建并加入 PE；多核 Host 需要相应数量的 PE。
            peList1.add(new Pe(0, new PeProvisionerSimple(mips))); // PE 标识与 MIPS 额定值。
            peList1.add(new Pe(1, new PeProvisionerSimple(mips)));

            int hostId = 0;
            int ram = 2048; // Host 内存（MB）。
            long storage = 1000000; // Host 存储容量。
            int bw = 10000;
            hostList.add(
                    new Host(
                            hostId,
                            new RamProvisionerSimple(ram),
                            new BwProvisionerSimple(bw),
                            storage,
                            peList1,
                            new VmSchedulerTimeShared(peList1))); // 本示例的 Host 调度器。
            hostId++;
        }

        // 描述数据中心的体系结构、操作系统、Host 列表、时区和抽象成本。
        String arch = "x86";      // 系统体系结构。
        String os = "Linux";          // 操作系统。
        String vmm = "Xen";
        double time_zone = 10.0;         // 资源所在时区。
        double cost = 3.0;              // 抽象计算成本。
        double costPerMem = 0.05;		// 抽象内存成本。
        double costPerStorage = 0.1;	// 抽象存储成本。
        double costPerBw = 0.1;			// 抽象带宽成本。
        LinkedList<Storage> storageList = new LinkedList<>();	// 本示例不建模 SAN 设备。
        WorkflowDatacenter datacenter = null;
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);
        // 最后创建共享存储对象。
        /**
         * 数据中心内部的最大传输速率（MB/s）。该历史数值不构成真实存储系统校准依据。
         */
        int maxTransferRate = 15; // 可按研究平台契约显式替换。
        try {
            HarddriveStorage s1 = new HarddriveStorage(name, 1e12);
            s1.setMaxTransferRate(maxTransferRate);
            storageList.add(s1);
            datacenter = new WorkflowDatacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }

    /**
     * 输出作业结果表。
     *
     * @param list 待输出的作业列表
     */
    protected static void printJobList(List<Job> list) {
        int size = list.size();
        Job job;
        String indent = "    ";
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + indent
                + "Time" + indent + "Start Time" + indent + "Finish Time" + indent + "Depth");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            job = list.get(i);
            Log.print(indent + job.getCloudletId() + indent + indent);
            if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS");
                Log.printLine(indent + indent + job.getResourceId() + indent + indent + indent + job.getVmId()
                        + indent + indent + indent + dft.format(job.getActualCPUTime())
                        + indent + indent + dft.format(job.getExecStartTime()) + indent + indent + indent
                        + dft.format(job.getFinishTime()) + indent + indent + indent + job.getDepth());
            } else if (job.getCloudletStatus() == Cloudlet.FAILED) {
                Log.print("FAILED");
                Log.printLine(indent + indent + job.getResourceId() + indent + indent + indent + job.getVmId()
                        + indent + indent + indent + dft.format(job.getActualCPUTime())
                        + indent + indent + dft.format(job.getExecStartTime()) + indent + indent + indent
                        + dft.format(job.getFinishTime()) + indent + indent + indent + job.getDepth());
            }
        }
    }
}
