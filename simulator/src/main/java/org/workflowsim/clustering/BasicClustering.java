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
package org.workflowsim.clustering;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.FileType;

/**
 * 默认的无聚类策略：每个逻辑任务映射为一个独立作业。
 *
 * <p>该实现还负责把逻辑任务依赖、文件引用、静态映射时间和聚类开销转换为作业级
 * 表示，是其他聚类策略的公共基础。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class BasicClustering implements ClusteringInterface {

    /**
     * 待转换的逻辑任务列表。
     */
    private List<Task> taskList;
    /**
     * 聚类后生成的作业列表。
     */
    private final List<Job> jobList;
    /**
     * 逻辑任务到生成作业的映射。
     */
    private final Map mTask2Job;
    /**
     * 聚类后所有作业引用的文件。
     */
    private final List<FileItem> allFileList;
    /**
     * 临时加入的虚拟根任务。
     */
    private Task root;
    /**
     * 下一个生成作业的 ID。
     */
    private int idIndex;

    /**
     * 返回聚类后收集的文件引用。
     *
     * @return 文件引用列表
     */
    @Override
    public final List<FileItem> getTaskFiles() {
        return this.allFileList;
    }

    /**
     * 创建无聚类策略实例。
     */
    public BasicClustering() {
        this.jobList = new ArrayList<>();
        this.taskList = new ArrayList<>();
        this.mTask2Job = new HashMap<>();
        this.allFileList = new ArrayList<>();
        this.idIndex = 0;
        this.root = null;
    }

    /**
     * 设置待转换的逻辑任务列表。
     *
     * @param list 逻辑任务列表
     */
    @Override
    public final void setTaskList(List<Task> list) {
        this.taskList = list;
    }

    /**
     * 返回已生成的作业列表。
     *
     * @return 作业列表
     */
    @Override
    public final List<Job> getJobList() {
        return this.jobList;
    }

    /**
     * 返回待转换的逻辑任务列表。
     *
     * @return 逻辑任务列表
     */
    @Override
    public final List<Task> getTaskList() {
        return this.taskList;
    }

    /**
     * 返回逻辑任务到作业的映射。
     *
     * @return 任务到作业的映射
     */
    public final Map getTask2Job() {
        return this.mTask2Job;
    }

    /**
     * 将每个逻辑任务转换为独立作业，并重建作业级依赖关系。
     */
    @Override
    public void run() {
        getTask2Job().clear();
        for (Task task : getTaskList()) {
            List<Task> list = new ArrayList<>();
            list.add(task);
            Job job = addTasks2Job(list);
            job.setVmId(task.getVmId());
            if (!Double.isNaN(task.getStaticScheduleStartTime())) {
                job.setStaticScheduleStartTime(task.getStaticScheduleStartTime());
            }
            getTask2Job().put(task, job);
        }
        /**
         * 将逻辑任务的父子边投影为作业的父子边。
         */
        updateDependencies();

    }

    /**
     * 将单个逻辑任务加入新作业。
     *
     * @param task 逻辑任务
     * @return 新建作业
     */
    protected final Job addTasks2Job(Task task) {
        List<Task> tasks = new ArrayList<>();
        tasks.add(task);
        return addTasks2Job(tasks);
    }

    /**
     * 将一组逻辑任务合并为一个新作业。
     *
     * @param taskList 逻辑任务列表
     * @return 新建作业；输入为空时返回 {@code null}
     */
    protected final Job addTasks2Job(List<Task> taskList) {
        if (taskList != null && !taskList.isEmpty()) {
            int length = 0;

            int userId = 0;
            int priority = 0;
            int depth = 0;
            // CloudSim 在构造后固定输入/输出文件大小，因此先以长度占位创建作业。
            Job job = new Job(idIndex, length/*, inputFileSize, outputFileSize*/);
            job.setClassType(ClassType.COMPUTE.value);
            for (Task task : taskList) {
                length += task.getCloudletLength();

                userId = task.getUserId();
                priority = task.getPriority();
                depth = task.getDepth();
                List<FileItem> fileList = task.getFileList();
                job.getTaskList().add(task);

                getTask2Job().put(task, job);
                for (FileItem file : fileList) {
                    boolean hasFile = job.getFileList().contains(file);
                    if (!hasFile) {
                        job.getFileList().add(file);
                        if (file.getType() == FileType.INPUT) {
                            // 供后续 stage-in 作业使用。
                            if (!this.allFileList.contains(file)) {
                                this.allFileList.add(file);
                            }
                        } else if (file.getType() == FileType.OUTPUT) {
                            this.allFileList.add(file);
                        }
                    }
                }
                for (String fileName : task.getRequiredFiles()) {
                    if (!job.getRequiredFiles().contains(fileName)) {
                        job.getRequiredFiles().add(fileName);
                    }
                }
            }

            job.setCloudletLength(length);
            job.setUserId(userId);
            job.setDepth(depth);
            job.setPriority(priority);

            idIndex++;
            getJobList().add(job);
            return job;
        }

        return null;
    }

    /**
     * 为每个生成作业加入声明的聚类延迟；默认延迟为零。
     */
    public void addClustDelay() {

        for (Job job : getJobList()) {
            double delay = Parameters.getOverheadParams().getClustDelay(job);
            delay *= 1000; // 与历史工作流解析阶段使用的换算比例一致
            long length = job.getCloudletLength();
            length += (long) delay;
            job.setCloudletLength(length);
        }
    }

    /**
     * 根据逻辑任务依赖更新作业级父子关系。
     */
    protected final void updateDependencies() {
        for (Task task : getTaskList()) {
            Job job = (Job) getTask2Job().get(task);
            for (Task parentTask : task.getParentList()) {
                Job parentJob = (Job) getTask2Job().get(parentTask);
                if (!job.getParentList().contains(parentJob) && parentJob != job) { // 避免重复边和自环
                    job.addParent(parentJob);
                }
            }
            for (Task childTask : task.getChildList()) {
                Job childJob = (Job) getTask2Job().get(childTask);
                if (!job.getChildList().contains(childJob) && childJob != job) { // 避免重复边和自环
                    job.addChild(childJob);
                }
            }
        }
        getTask2Job().clear();
        getTaskList().clear();
    }
    /*
     * 添加一个虚拟根任务，使所有原始根任务都成为它的子节点。
     * 调用本方法后必须调用 {@link #clean()} 移除临时根节点。
     */

    public Task addRoot() {

        if (root == null) {
            // 使用不与当前任务列表冲突的临时 ID。
            root = new Task(taskList.size() + 1, 0/*,0,0*/);
            for (Task node : taskList) {
                if (node.getParentList().isEmpty()) {
                    node.addParent(root);
                    root.addChild(node);
                }
            }
            taskList.add(root);

        }
        return root;
    }

    /**
     * 删除此前加入的虚拟根任务，并恢复原始根任务关系。
     */
    public void clean() {
        if (root != null) {
            for (int i = 0; i < root.getChildList().size(); i++) {
                Task node = (Task) root.getChildList().get(i);
                node.getParentList().remove(root);
                root.getChildList().remove(node);
                i--;
            }
            taskList.remove(root);
        }
    }
}
