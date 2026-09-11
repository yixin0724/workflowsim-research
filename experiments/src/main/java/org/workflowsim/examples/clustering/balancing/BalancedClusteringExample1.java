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
package org.workflowsim.examples.clustering.balancing;

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
import org.workflowsim.examples.clustering.HorizontalClusteringExample1;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 使用运行时间均衡方法的历史均衡水平聚类教学示例。
 *
 * <p>该示例说明如何选择旧版均衡策略并将其传入聚类器。它不代表经过校准的资源均衡模型，
 * 也不是当前标准研究 runner 的实验入口。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Dec 29, 2013
 */
public class BalancedClusteringExample1 extends HorizontalClusteringExample1 {

    // ======================== 静态示例入口 ========================
    /**
     * 运行单数据中心、单存储的历史均衡聚类场景。
     */
    public static void main(String[] args) {

        try {
            ExampleCliSupport.begin("balanced-clustering");
            Map<String, String> options = ExampleCliSupport.parseValueOptions(args,
                    "-c", "-d", "-l", "-q", "-e", "-p", "-i");
            String code = ExampleCliSupport.allowedCharacters("-c",
                    ExampleCliSupport.optionOrDefault(options, "-c", "i"), "vcridh");
            String daxPath = ExampleCliSupport.requireDax(
                    ExampleCliSupport.optionOrDefault(options, "-d", ExampleCliSupport.DEFAULT_DAX_PATH));
            double c_delay = ExampleCliSupport.nonNegativeFinite("-l",
                    ExampleCliSupport.optionOrDefault(options, "-l", "0"));
            double q_delay = ExampleCliSupport.nonNegativeFinite("-q",
                    ExampleCliSupport.optionOrDefault(options, "-q", "0"));
            double e_delay = ExampleCliSupport.nonNegativeFinite("-e",
                    ExampleCliSupport.optionOrDefault(options, "-e", "0"));
            double p_delay = ExampleCliSupport.nonNegativeFinite("-p",
                    ExampleCliSupport.optionOrDefault(options, "-p", "0"));
            int interval = ExampleCliSupport.nonNegativeInt("-i",
                    ExampleCliSupport.optionOrDefault(options, "-i", "0"));

            // 第一步：初始化历史 WorkflowSim 静态参数。
            /**
             * 请求创建的 VM 数；若数据中心或 Host 资源不足，实际可创建数量可能更少。
             */
            int vmNum = 20; // VM 数量。
            /**
             * 该场景由调度器决定映射，因此禁用规划器，避免其覆盖调度结果。
             */
            Parameters.SchedulingAlgorithm sch_method = Parameters.SchedulingAlgorithm.DATA;
            Parameters.PlanningAlgorithm pln_method = Parameters.PlanningAlgorithm.INVALID;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.LOCAL;

            /**
             * 聚类、队列、提交后处理和引擎延迟按层级传入；不研究的开销以零值表示。
             */
            Map<Integer, DistributionGenerator> clusteringDelay = new HashMap();
            Map<Integer, DistributionGenerator> queueDelay = new HashMap();
            Map<Integer, DistributionGenerator> postscriptDelay = new HashMap();
            Map<Integer, DistributionGenerator> engineDelay = new HashMap();
            /** 此示例应用假定最多包含 11 个水平层级。 */
            int maxLevel = 11;
            for (int level = 0; level < maxLevel; level++) {
                if (c_delay != 0.0) {
                    DistributionGenerator cluster_delay = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, c_delay, 1.0);
                    clusteringDelay.put(level, cluster_delay);
                }
                if (q_delay != 0.0) {
                    DistributionGenerator queue_delay = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, q_delay, 1.0);
                    queueDelay.put(level, queue_delay);
                }
                if (p_delay != 0.0) {
                    DistributionGenerator postscript_delay = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, p_delay, 1.0);
                    postscriptDelay.put(level, postscript_delay);
                }
                if (e_delay != 0.0) {
                    DistributionGenerator engine_delay = new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, e_delay, 1.0);
                    engineDelay.put(level, engine_delay);
                }
            }

            OverheadParameters op = new OverheadParameters(interval, engineDelay, queueDelay, postscriptDelay, clusteringDelay, 0);

            /** 选择均衡聚类。 */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.BALANCED;
            /**
             * code 的兼容取值：{@code r} 为水平运行时间均衡，{@code d} 为水平距离均衡，
             * {@code i} 为水平影响因子均衡，{@code h} 为水平随机均衡；其他值沿用
             * 历史普通水平聚类路径。
             */
            ClusteringParameters cp = new ClusteringParameters(20, 0, method, code);

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
            throw ExampleCliSupport.failure("balanced-clustering", e);
        } finally {
            ExampleCliSupport.end();
        }
    }
}
