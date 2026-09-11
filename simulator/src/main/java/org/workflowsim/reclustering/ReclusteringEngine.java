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
package org.workflowsim.reclustering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.FailureRecord;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;

/**
 * 在 Job 失败后创建一个或多个重试 Job 的旧版重聚类引擎。
 *
 * <p>这里的重试策略延续 WorkflowSim 的兼容性行为，并依赖
 * {@link FailureParameters}、{@link FailureMonitor} 及开销参数中的抽象模型。
 * 它不是新版标准实验入口，也不能单独证明真实系统中的恢复效果。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public class ReclusteringEngine {

    /**
     * 从失败 Job 创建一个新的重试 Job。
     *
     * @param id 新 Job 标识
     * @param job 失败的 Job
     * @param length 新 Job 的计算长度
     * @param taskList 重试任务列表
     * @param updateDep 是否将重试 Job 写入原有依赖关系
     * @return 新建的重试 Job
     */
    private static Job createJob(int id, Job job, long length, List taskList, boolean updateDep) {
        if (job == null) {
            throw new IllegalArgumentException("Cannot recreate a null failed job");
        }
        Job newJob = new Job(id, length);
        newJob.setUserId(job.getUserId());
        if (!newJob.setClassType(job.getClassType())) {
            throw new IllegalStateException("Failed Job " + job.getCloudletId()
                    + " has no executable class type to preserve for retry");
        }
        newJob.setVmId(-1);
        try {
            newJob.setCloudletStatus(Cloudlet.CREATED);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialize retry Job " + id, exception);
        }

        // DC/DR 会增量构建重试 Job，因此初始占位任务列表可为 null；
        // 每个真正可执行的重试 Job 都必须持有任务副本。
        newJob.setTaskList(taskList == null ? new ArrayList<Task>() : copyTasks(taskList));
        populateRetryFileMetadata(newJob);
        newJob.setDepth(job.getDepth());
        if (updateDep) {
            newJob.setChildList(job.getChildList());
            newJob.setParentList(job.getParentList());
            for (Iterator it = job.getChildList().iterator(); it.hasNext();) {
                Job cJob = (Job) it.next();
                cJob.addParent(newJob);
            }
        }
        return newJob;

    }

    /**
     * 根据当前容错聚类算法创建失败 Job 的重试 Job 列表。
     *
     * @param job 失败的 Job
     * @param id 第一个重试 Job 的标识
     * @return 新建重试 Job 列表
     */
    public static List<Job> process(Job job, int id) {
        if (job == null) {
            throw new IllegalArgumentException("Cannot process a null failed job");
        }
        if (id < 0) {
            throw new IllegalArgumentException("Retry job ID must be non-negative");
        }
        if (job.getTaskList() == null || job.getTaskList().isEmpty()) {
            throw new IllegalArgumentException("Failed Job " + job.getCloudletId()
                    + " has no tasks to recreate");
        }
        List jobList = new ArrayList();

        switch (FailureParameters.getFTCluteringAlgorithm()) {
                case FTCLUSTERING_NOOP:

                    jobList.add(createJob(id, job, job.getCloudletLength(), job.getTaskList(), true));
                    // 重试 Job 尚未提交，无需处理失败 Job 的提交状态。
                    break;
                /**
                 * 动态重聚类。
                 */
                case FTCLUSTERING_DC:
                    jobList = DCReclustering(jobList, job, id, job.getTaskList());
                    break;
                /**
                 * 选择性重聚类。
                 */
                case FTCLUSTERING_SR:
                    jobList = SRReclustering(jobList, job, id);

                    break;
                /**
                 * 基于估计器的动态重聚类。
                 */
                case FTCLUSTERING_DR:
                    jobList = DRReclustering(jobList, job, id, job.getTaskList());
                    break;
                /**
                 * 按 DAG 深度分块重聚类。
                 */
                case FTCLUSTERING_BLOCK:
                    jobList = BlockReclustering(jobList, job, id);
                    break;
                /**
                 * 按 DAG 深度二分重聚类。
                 */
                case FTCLUSTERING_VERTICAL:
                    jobList = VerticalReclustering(jobList, job, id);
                    break;
            default:
                throw new IllegalStateException("Unsupported fault-tolerant clustering algorithm: "
                        + FailureParameters.getFTCluteringAlgorithm());
        }
        return jobList;
    }

    /**
     * 重试是一次新的执行尝试。复制任务标识和建模元数据，但绝不与失败尝试共享
     * 可变状态或计时信息，避免污染已保留的失败证据。
     */
    private static List<Task> copyTasks(List taskList) {
        if (taskList == null) {
            throw new IllegalArgumentException("Failed job has no task list to recreate");
        }
        List<Task> copies = new ArrayList<>();
        for (Object rawTask : taskList) {
            if (!(rawTask instanceof Task)) {
                throw new IllegalArgumentException("Failed job task list contains a non-Task value");
            }
            Task source = (Task) rawTask;
            Task copy = new Task(source.getCloudletId(), source.getCloudletLength());
            copy.setUserId(source.getUserId());
            copy.setNumberOfPes(source.getNumberOfPes());
            copy.setDepth(source.getDepth());
            copy.setPriority(source.getPriority());
            copy.setImpact(source.getImpact());
            copy.setType(source.getType());
            copy.setFileList(new ArrayList<>(source.getFileList()));
            for (String requiredFile : source.getRequiredFiles()) {
                copy.addRequiredFile(requiredFile);
            }
            copy.setParentList(new ArrayList<>(source.getParentList()));
            copy.setChildList(new ArrayList<>(source.getChildList()));
            if (!Double.isNaN(source.getStaticScheduleStartTime())) {
                copy.setStaticScheduleStartTime(source.getStaticScheduleStartTime());
            }
            copies.add(copy);
        }
        return copies;
    }

    /**
     * 从重试任务副本重建 Job 级文件和 required-file 并集。
     *
     * <p>重试 Job 可能只包含原聚类 Job 的一部分任务，不能照搬原 Job 的完整文件列表。
     * 重新聚合后的 compute Job 会继续进入既有的数据阶段输入模型，由该模型根据副本目录决定
     * 是否需要实际传输。</p>
     */
    private static void populateRetryFileMetadata(Job retry) {
        for (Task task : retry.getTaskList()) {
            for (FileItem file : task.getFileList()) {
                if (!retry.getFileList().contains(file)) {
                    retry.getFileList().add(file);
                }
            }
            for (String requiredFile : task.getRequiredFiles()) {
                retry.addRequiredFile(requiredFile);
            }
        }
    }

    /**
     * 按固定上限将候选任务稳定地切分为重试 Job。
     *
     * <p>DC 和 DR 共用此路径，从而保证每个候选任务恰好进入一个输出 Job。这里先在加入当前
     * 任务前关闭已满的簇，避免旧实现中恰好位于簇边界的任务被遗漏。{@code failedOnly}
     * 为 {@code true} 时只保留状态为 {@link Cloudlet#FAILED} 的任务。</p>
     *
     * @param job 失败的源 Job
     * @param firstId 第一个新 Job 标识
     * @param candidates 按原有顺序的候选任务
     * @param clusterSize 每个重试 Job 至多包含的任务数量，必须为正
     * @param failedOnly 是否只重试失败任务
     * @return 连续编号、已重建文件元数据并已连接依赖边的重试 Job
     */
    static List<Job> partitionRetryTasks(Job job, int firstId, List<Task> candidates,
            int clusterSize, boolean failedOnly) {
        if (job == null || candidates == null) {
            throw new IllegalArgumentException("Retry partition requires a Job and candidate tasks");
        }
        if (firstId < 0 || clusterSize <= 0) {
            throw new IllegalArgumentException("Retry Job ID must be non-negative and cluster size positive");
        }

        List<Job> retries = new ArrayList<Job>();
        List<Task> currentTasks = new ArrayList<Task>();
        long currentLength = 0L;
        int nextId = firstId;
        for (Task task : candidates) {
            if (task == null) {
                throw new IllegalArgumentException("Retry candidate task cannot be null");
            }
            if (failedOnly && task.getCloudletStatus() != Cloudlet.FAILED) {
                continue;
            }
            if (currentTasks.size() == clusterSize) {
                retries.add(createJob(nextId++, job, currentLength, currentTasks, false));
                currentTasks = new ArrayList<Task>();
                currentLength = 0L;
            }
            if (task.getCloudletLength() > Long.MAX_VALUE - currentLength) {
                throw new IllegalStateException("Retry Job length overflow for failed Job "
                        + job.getCloudletId());
            }
            currentTasks.add(task);
            currentLength += task.getCloudletLength();
        }
        if (!currentTasks.isEmpty()) {
            retries.add(createJob(nextId, job, currentLength, currentTasks, false));
        }
        if (!retries.isEmpty()) {
            updateDependencies(job, retries);
        }
        return retries;
    }

    /**
     * 按任务的 DAG 深度划分任务列表。
     *
     * @param list 待划分的任务列表
     * @return 以 DAG 深度为键的任务映射
     */
    private static Map<Integer, List<Task>> getDepthMap(List<Task> list) {
        Map<Integer, List<Task>> map = new HashMap<>();

        for (Task task : list) {
            int depth = task.getDepth();
            if (!map.containsKey(depth)) {
                map.put(depth, new ArrayList<>());
            }
            List<Task> dl = map.get(depth);
            if (!dl.contains(task)) {
                dl.add(task);
            }
        }

        return map;
    }
    // 动态重聚类及按层恢复策略共用的辅助逻辑。

    /**
     * 获取深度映射中的最小层级，供动态和按层恢复策略使用。
     *
     * @param map 以 DAG 深度为键的任务映射
     * @return 映射中的最小键；空映射返回 -1
     */
    private static int getMin(Map<Integer, List<Task>> map) {
        if (map != null && !map.isEmpty()) {
            int min = Integer.MAX_VALUE;
            for (int value : map.keySet()) {
                if (value < min) {
                    min = value;
                }
            }
            return min;
        }
        return -1;
    }

    /**
     * 判断任务列表中是否存在失败任务。
     *
     * @param list 待检查的任务列表
     * @return 存在失败任务时为 {@code true}
     */
    private static boolean checkFailed(List<Task> list) {
        boolean all = false;
        for (Task task : list) {
            if (task.getCloudletStatus() == Cloudlet.FAILED) {
                all = true;
                break;
            }
        }
        return all;
    }

    /**
     * 按 DAG 深度将失败 Job 的任务二分后重聚类。
     *
     * @param jobList 累积的重试 Job 列表
     * @param job 失败的 Job
     * @param id 下一个可分配的 Job 标识
     * @return 更新后的重试 Job 列表
     */
    private static List<Job> VerticalReclustering(List<Job> jobList, Job job, int id) {
        Map<Integer, List<Task>> map = getDepthMap(job.getTaskList());
        
        /**
         * 只有一个 DAG 层级时退化为动态重聚类。
         */
        if (map.size() == 1) {

            jobList = DCReclustering(jobList, job, id, job.getTaskList());

            return jobList;
        }
        int min = getMin(map);
        int max = min + map.size() - 1;
        int mid = (min + max) / 2;
        List listUp = new ArrayList<>();
        List listDown = new ArrayList<>();
        for (int i = min; i < min + map.size(); i++) {
            List<Task> list = map.get(i);
            if (i <= mid) {
                listUp.addAll(list);
            } else {
                listDown.addAll(list);
            }
        }
        List<Job> newUpList = DCReclustering(new ArrayList(), job, id, listUp);
        id += newUpList.size();
        jobList.addAll(newUpList);
        jobList.addAll(DCReclustering(new ArrayList(), job, id, listDown));
        return jobList;

    }

    /**
     * 对每个包含失败任务的 DAG 深度块分别执行动态重聚类。
     *
     * @param jobList 累积的重试 Job 列表
     * @param job 失败的 Job
     * @param id 下一个可分配的 Job 标识
     * @return 更新后的重试 Job 列表
     */
    private static List BlockReclustering(List jobList, Job job, int id) {
        Map map = getDepthMap(job.getTaskList());
        if (map.size() == 1) {
            jobList = DRReclustering(jobList, job, id, job.getTaskList());
            return jobList;
        }
        // 对每个深度块分别处理。
        int min = getMin(map);
        for (int i = min; i < min + map.size(); i++) {

            List list = (List) map.get(i);

            if (checkFailed(list)) {
                // 包含失败任务的深度块必须独立恢复。
                List newList = DRReclustering(new ArrayList(), job, id, list);
                id = newList.size() + id; // 后续重试 Job 继续使用未占用的标识。
                jobList.addAll(newList);
            } else {
                // 此深度块没有失败任务，无需创建重试 Job。
            }
        }


        return jobList;
    }

    /**
     * 使用监控器建议的聚类因子执行动态重聚类。
     *
     * @param jobList 累积的重试 Job 列表
     * @param job 失败的 Job
     * @param id 下一个可分配的 Job 标识
     * @param allTaskList 候选重试任务列表
     * @return 更新后的重试 Job 列表
     */
    private static List<Job> DCReclustering(List<Job> jobList, Job job, int id, List<Task> allTaskList) {

        Task firstTask = allTaskList.get(0);
        // FailureRecord 的 length 是估计的单任务运行时间，不是整个 Job 的长度。
        // 该历史公式把任务长度、聚类开销和深度相关除数结合起来。
        OverheadParameters overhead = Parameters.getOverheadParams();
        double clusteringDelay = overhead == null ? 0.0 : overhead.getClustDelay(job);
        double taskLength = (double) firstTask.getCloudletLength() / 1000
                + clusteringDelay / getDividend(job.getDepth());
        FailureRecord record = new FailureRecord(taskLength, 0, job.getDepth(), allTaskList.size(),
                job.getVmId(), job.getCloudletId(), job.getUserId());
        record.delayLength = getCumulativeDelay(job.getDepth());
        int suggestedK = FailureMonitor.getClusteringFactor(record);

        // 估计器返回 0 表示没有有限推荐值；退化为一个覆盖全部候选任务的重试簇。
        int clusterSize = suggestedK > 0 ? suggestedK : Math.max(1, allTaskList.size());
        jobList.addAll(partitionRetryTasks(job, id, allTaskList, clusterSize, false));
        return jobList;

    }

    /**
     * 仅为失败任务创建一个重试 Job。
     *
     * @param jobList 累积的重试 Job 列表
     * @param job 失败的 Job
     * @param id 新重试 Job 的标识
     * @return 更新后的重试 Job 列表
     */
    private static List<Job> SRReclustering(List<Job> jobList, Job job, int id) {
        List<Task> newTaskList = new ArrayList<>();
        long length = 0;
        for (Task task : job.getTaskList()) {
            if (task.getCloudletStatus() == Cloudlet.FAILED) {
                newTaskList.add(task);
                length += task.getCloudletLength();
            } else {
            }
        }
        jobList.add(createJob(id, job, length, newTaskList, true));
        return jobList;
    }

    /**
     * 返回旧版动态重聚类公式使用的深度相关除数。
     *
     * <p>这些常量仅覆盖历史实现中的少数深度，缺少实验校准依据；保留它们
     * 是为了兼容旧行为，而不是作为通用平台模型。</p>
     *
     * @param depth DAG 深度
     * @return 对应的历史除数，未知深度返回 1
     */
    private static int getDividend(int depth) {
        int dividend = 1;
        switch (depth) {
            case 1:
                dividend = 78;
                break;
            case 2:
                dividend = 229;
                break;
            case 5:
                dividend = 64;
                break;
            default:
                Log.printLine("Eroor");
                break;
        }
        return dividend;
    }

    /**
     * 累加某一 DAG 深度可用的队列、工作流引擎和提交后处理延迟均值。
     * @param depth DAG 深度
     * @return 累积延迟
     */
    private static double getCumulativeDelay(int depth){
        double delay = 0.0;
        OverheadParameters overhead = Parameters.getOverheadParams();
        if (overhead == null) {
            return delay;
        }
        if(overhead.getQueueDelay()!=null && 
                overhead.getQueueDelay().containsKey(depth)){
            delay += overhead.getQueueDelay().get(depth).getMLEMean();
        }
        if(overhead.getWEDDelay()!=null &&
                overhead.getWEDDelay().containsKey(depth)){
            delay += overhead.getWEDDelay().get(depth).getMLEMean();
        }
        if(overhead.getPostDelay()!=null &&
                overhead.getPostDelay().containsKey(depth)){
            delay += overhead.getPostDelay().get(depth).getMLEMean();
        }
        return delay;
    }
    
    /**
     * 返回某一深度最先可用的开销分布先验，用于旧版动态重聚类估计器。
     *
     * <p>此选择顺序是历史兼容性规则，不表示多个开销来源的统计合并。</p>
     *
     * @param depth DAG 深度
     * @return 选中的先验；没有对应开销时为 0
     */
    private static double getOverheadLikelihoodPrior(int depth){
        double prior = 0.0;
        OverheadParameters overhead = Parameters.getOverheadParams();
        if (overhead == null) {
            return prior;
        }

        if(overhead.getQueueDelay()!=null && 
                overhead.getQueueDelay().containsKey(depth)){
            prior = overhead.getQueueDelay().get(depth).getLikelihoodPrior();
        }else
        if(overhead.getWEDDelay()!=null &&
                overhead.getWEDDelay().containsKey(depth)){
            prior = overhead.getWEDDelay().get(depth).getMLEMean();
        }else
        if(overhead.getPostDelay()!=null &&
                overhead.getPostDelay().containsKey(depth)){
            prior = overhead.getPostDelay().get(depth).getMLEMean();
        }
        return prior;
    }
    
    /**
     * 根据失效到达时间和开销参数估计簇大小后，仅重试失败任务。
     *
     * @param jobList 累积的重试 Job 列表
     * @param job 失败的 Job
     * @param id 下一个可分配的 Job 标识
     * @param allTaskList 候选重试任务列表
     * @return 更新后的重试 Job 列表
     */
    private static List DRReclustering(List<Job> jobList, Job job, int id, List<Task> allTaskList) {
        Task firstTask = allTaskList.get(0);
      
        // 旧版 DR 路径只用首个任务长度估计单任务运行时间；这里刻意不叠加簇开销。
        double taskLength = (double) firstTask.getCloudletLength() / 1000 ;//+ Parameters.getOverheadParams().getClustDelay(job) / getDividend(job.getDepth());
        
        // 历史实现曾通过 FailureMonitor 计算 suggestedK；现保留直接估计路径。
        
        double phi_ts = getOverheadLikelihoodPrior(job.getDepth());
        double delay = getCumulativeDelay(job.getDepth());
        int suggestedK; // 历史实现可改由 FailureMonitor 提供建议值。
        
        double theta = FailureParameters.getGeneratorForFailureScope(
                job.getVmId(), job.getDepth()).getMLEMean();
        
        double phi_gamma = FailureParameters.getGeneratorForFailureScope(
                job.getVmId(), job.getDepth()).getLikelihoodPrior();
        suggestedK = ClusteringSizeEstimator.estimateK(taskLength, delay, 
                theta, phi_gamma, phi_ts);

        Log.printLine("t=" + taskLength +" d=" + delay + " theta=" + theta + " k=" + suggestedK);
        // 估计器没有有限建议时，仍严格只重试失败任务并将它们置于一个重试簇。
        int clusterSize = suggestedK > 0 ? suggestedK : Math.max(1, allTaskList.size());
        jobList.addAll(partitionRetryTasks(job, id, allTaskList, clusterSize, true));
        return jobList;
    }
    
    private static void updateDependencies (Job job, List<Job> jobList) {
        // 每个重试 Job 都引用同一组依赖边；不能在循环内重写整个子节点列表，
        // 否则后续重试 Job 会覆盖先前建立的依赖关系。
        List<Task> parents = job.getParentList();
        List<Task> children = job.getChildList();
        for (Job rawJob : jobList) { 
            rawJob.setChildList(children);
            rawJob.setParentList(parents);
        }
        for (Task parent : parents) {
            parent.getChildList().addAll(jobList);
        }
        for (Task childTask : children) {
            Job childJob = (Job) childTask;
            childJob.getParentList().addAll(jobList);
        }
    }
}
