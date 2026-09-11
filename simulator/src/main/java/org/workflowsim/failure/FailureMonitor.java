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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;

/**
 * 收集失败记录，并按监控粒度估计后续重聚类使用的任务失败率。
 *
 * <p>记录仅属于当前模拟会话；调用方必须先初始化监视器，再写入或分析记录。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class FailureMonitor {

    /**
     * 按 VM 标识索引的失败记录。
     */
    protected static Map<Integer, List<FailureRecord>> vm2record;
    /**
     * 按任务深度（历史代码中也称 type）索引的失败记录。
     */
    protected static Map<Integer, List<FailureRecord>> type2record;
    /** 按 VM 标识和任务深度复合键索引的失败记录。 */
    protected static Map<String, List<FailureRecord>> vmDepth2record;
    /**
     * 按 Job 标识索引的失败记录。
     */
    protected static Map<Integer, FailureRecord> jobid2record;
    /**
     * 当前会话的全部失败记录。
     */
    protected static List<FailureRecord> recordList;
    /**
     * Job 索引映射，供旧版故障恢复路径使用。
     */
    public static Map index2job;

    /**
     * 初始化当前模拟运行的失败记录容器。
     */
    public static void init() {
        vm2record = new HashMap<>();
        type2record = new HashMap<>();
        vmDepth2record = new HashMap<>();
        jobid2record = new HashMap<>();
        recordList = new ArrayList<>();
    }

    /** 清除已结束模拟运行持有的全部监控记录。 */
    public static void reset() {
        vm2record = null;
        type2record = null;
        vmDepth2record = null;
        jobid2record = null;
        recordList = null;
        index2job = null;
    }

    private static void requireInitialized() {
        if (vm2record == null || type2record == null || vmDepth2record == null
                || jobid2record == null || recordList == null) {
            throw new IllegalStateException("FailureMonitor is not initialized for this simulation run");
        }
    }

    /**
     * 根据观测到的失败率计算建议的聚类因子。
     *
     * @param d 延迟开销
     * @param a 观测到的任务失败率
     * @param t 任务运行时间
     * @return 建议的聚类因子
     */
    protected static double getK(double d, double a, double t) {
        double k = (-d + Math.sqrt(d * d - 4 * d / Math.log(1 - a))) / (2 * t);
        return k;
    }

    /**
     * 依据指定失败记录及当前监控模式返回建议的聚类因子。
     *
     * @param record 当前恢复请求对应的失败记录
     * @return 建议的聚类因子
     */
    public static int getClusteringFactor(FailureRecord record) {

        double d = record.delayLength;

        double t = record.length;
        double a = 0.0;
        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_JOB:
            /**
             * MONITOR_JOB 在此旧实现中按全局记录处理。
             */
            case MONITOR_ALL:
                a = analyze(0, record.depth);
                break;
            case MONITOR_VM:
                a = analyze(0, record.vmId);
                break;
            case MONITOR_VM_JOB:
                a = analyzeVmDepth(record.vmId, record.depth);
                break;
        }

        if (a <= 0.0) {
            return record.allTaskNum;
        } else {
            double k = getK(d, a, t);

            if (k <= 1) {
                k = 1; // 聚类因子至少为 1。
            }

            return (int) k;
        }
    }

    /**
     * 接收调度代理提交的失败记录，并按当前监控模式建立索引。
     *
     * @param record 待写入的失败记录
     */
    public static void postFailureRecord(FailureRecord record) {
        requireInitialized();

        if (record.workflowId < 0 || record.jobId < 0 || record.vmId < 0) {
            throw new IllegalArgumentException("Failure record must identify a non-negative workflow, job, and VM");
        }

        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_VM:

                if (!vm2record.containsKey(record.vmId)) {
                    vm2record.put(record.vmId, new ArrayList<>());
                }
                vm2record.get(record.vmId).add(record);

                break;
            case MONITOR_JOB:

                if (!type2record.containsKey(record.depth)) {
                    type2record.put(record.depth, new ArrayList<>());
                }
                type2record.get(record.depth).add(record);

                break;
            case MONITOR_VM_JOB:

                String key = vmDepthKey(record.vmId, record.depth);
                if (!vmDepth2record.containsKey(key)) {
                    vmDepth2record.put(key, new ArrayList<FailureRecord>());
                }
                vmDepth2record.get(key).add(record);

                break;
            case MONITOR_NONE:
                break;
        }

        recordList.add(record);
    }

    /**
     * 根据当前记录集合计算观测到的任务失败率。
     *
     * @param workflowId 当前版本未使用，保留以兼容旧接口
     * @param type VM 标识或任务深度，取决于监控模式
     * @return 任务失败率
     */
    public static double analyze(int workflowId, int type) {
        requireInitialized();

        /**
         * MONITOR_ALL 按整个工作流的全部 Job 汇总。
         */
        int sumFailures = 0;
        int sumJobs = 0;
        switch (FailureParameters.getMonitorMode()) {
            case MONITOR_ALL:

                for (FailureRecord record : recordList) {
                    sumFailures += record.failedTasksNum;
                    sumJobs += record.allTaskNum;
                }

                break;

            case MONITOR_JOB:

                if (type2record.containsKey(type)) {
                    for (FailureRecord record : type2record.get(type)) {

                        sumFailures += record.failedTasksNum;
                        sumJobs += record.allTaskNum;
                    }
                }

                break;
            case MONITOR_VM:

                if (vm2record.containsKey(type)) {
                    for (FailureRecord record : vm2record.get(type)) {

                        sumFailures += record.failedTasksNum;
                        sumJobs += record.allTaskNum;
                    }
                }

                break;
            case MONITOR_VM_JOB:
                throw new IllegalStateException("MONITOR_VM_JOB requires analyzeVmDepth(vmId, depth)");
        }


        if (sumFailures == 0) {
            return 0;
        }
        double alpha = (double) ((double) sumFailures / (double) sumJobs);
        return alpha;
    }

    /**
     * 按 VM 标识和 DAG 深度计算观测失败率。
     *
     * @param vmId VM 标识
     * @param depth DAG 深度
     * @return 对应复合分组的失败任务占比；尚无记录时为 0
     */
    public static double analyzeVmDepth(int vmId, int depth) {
        requireInitialized();
        if (vmId < 0 || depth < 0) {
            throw new IllegalArgumentException("VM ID and task depth must be non-negative");
        }
        if (FailureParameters.getMonitorMode()
                != FailureParameters.FTCMonitor.MONITOR_VM_JOB) {
            throw new IllegalStateException("analyzeVmDepth requires MONITOR_VM_JOB");
        }

        int failures = 0;
        int tasks = 0;
        List<FailureRecord> records = vmDepth2record.get(vmDepthKey(vmId, depth));
        if (records == null) {
            return 0.0;
        }
        for (FailureRecord record : records) {
            failures += record.failedTasksNum;
            tasks += record.allTaskNum;
        }
        return failures == 0 ? 0.0 : (double) failures / (double) tasks;
    }

    private static String vmDepthKey(int vmId, int depth) {
        return vmId + ":" + depth;
    }
}
