package org.workflowsim.reclustering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.FailureRecord;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.FileType;

class ReclusteringEngineTest {

    @AfterEach
    void resetFailureParameters() {
        FailureParameters.reset();
        FailureMonitor.reset();
    }

    @Test
    void noopRetryCopiesTaskMetadataWithoutRewritingFailedAttempt() throws Exception {
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_NONE,
                FailureParameters.FTCFailure.FAILURE_NONE, null);
        Job failed = new Job(40, 500L);
        assertTrue(failed.setClassType(ClassType.COMPUTE.value));
        failed.setUserId(7);
        Task source = new Task(12, 500L);
        source.setUserId(7);
        source.setNumberOfPes(2);
        source.setDepth(3);
        source.setPriority(11);
        source.setImpact(1.5);
        source.setType("analysis");
        source.setStaticScheduleStartTime(2.5);
        source.setCloudletStatus(Cloudlet.FAILED);
        // 文件列表容器必须复制，但 FileItem 本身仍是工作流模型中的共享元数据。
        FileItem file = new FileItem("input", 1024.0);
        file.setType(FileType.INPUT);
        source.addFile(file);
        source.addRequiredFile("input");
        failed.addTaskList(java.util.Collections.singletonList(source));

        Job child = new Job(41, 100L);
        failed.addChild(child);
        List<Job> retries = ReclusteringEngine.process(failed, 99);

        assertEquals(1, retries.size());
        Job retry = retries.get(0);
        Task retryTask = retry.getTaskList().get(0);
        assertEquals(99, retry.getCloudletId());
        assertEquals(ClassType.COMPUTE.value, retry.getClassType());
        assertNotSame(source, retryTask);
        assertEquals(Cloudlet.FAILED, source.getCloudletStatus());
        assertEquals(Cloudlet.CREATED, retryTask.getCloudletStatus());
        assertEquals(source.getCloudletId(), retryTask.getCloudletId());
        assertEquals(source.getNumberOfPes(), retryTask.getNumberOfPes());
        assertEquals(source.getDepth(), retryTask.getDepth());
        assertEquals(source.getPriority(), retryTask.getPriority());
        assertEquals(source.getImpact(), retryTask.getImpact(), 0.0);
        assertEquals(source.getType(), retryTask.getType());
        assertEquals(source.getStaticScheduleStartTime(), retryTask.getStaticScheduleStartTime(), 0.0);
        assertNotSame(source.getFileList(), retryTask.getFileList());
        assertSame(file, retryTask.getFileList().get(0));
        assertTrue(retryTask.getRequiredFiles().contains("input"));
        assertSame(file, retry.getFileList().get(0));
        assertTrue(retry.getRequiredFiles().contains("input"));
        assertSame(retry, child.getParentList().get(child.getParentList().size() - 1));
    }

    @Test
    void partitionedRetriesKeepEveryTaskAndOnlyTheFilesOfTheirOwnTaskSubset() throws Exception {
        Task first = task(1, 100L, Cloudlet.FAILED);
        Task second = task(2, 200L, Cloudlet.FAILED);
        Task third = task(3, 300L, Cloudlet.FAILED);
        Task fourth = task(4, 400L, Cloudlet.FAILED);
        Task fifth = task(5, 500L, Cloudlet.FAILED);
        Job failed = computeJob(40, first, second, third, fourth, fifth);
        Job child = new Job(41, 100L);
        failed.addChild(child);

        List<Job> retries = ReclusteringEngine.partitionRetryTasks(failed, 100,
                failed.getTaskList(), 2, false);

        assertEquals(3, retries.size());
        assertEquals(Arrays.asList(100, 101, 102), jobIds(retries));
        assertEquals(Arrays.asList(1, 2), taskIds(retries.get(0)));
        assertEquals(Arrays.asList(3, 4), taskIds(retries.get(1)));
        assertEquals(Arrays.asList(5), taskIds(retries.get(2)));
        assertEquals(Arrays.asList(300L, 700L, 500L), jobLengths(retries));
        assertEquals(Arrays.asList("input-1", "input-2"), fileNames(retries.get(0)));
        assertEquals(Arrays.asList("input-3", "input-4"), fileNames(retries.get(1)));
        assertEquals(Arrays.asList("input-5"), fileNames(retries.get(2)));
        for (Job retry : retries) {
            assertEquals(ClassType.COMPUTE.value, retry.getClassType());
            assertTrue(child.getParentList().contains(retry));
            for (Task copied : retry.getTaskList()) {
                assertEquals(Cloudlet.CREATED, copied.getCloudletStatus());
            }
        }
        assertEquals(Cloudlet.FAILED, second.getCloudletStatus());
    }

    @Test
    void failedOnlyPartitionAtKOneDoesNotDropBoundaryTasks() throws Exception {
        Task first = task(1, 100L, Cloudlet.FAILED);
        Task second = task(2, 200L, Cloudlet.SUCCESS);
        Task third = task(3, 300L, Cloudlet.FAILED);
        Task fourth = task(4, 400L, Cloudlet.FAILED);
        Job failed = computeJob(40, first, second, third, fourth);

        List<Job> retries = ReclusteringEngine.partitionRetryTasks(failed, 200,
                failed.getTaskList(), 1, true);

        assertEquals(Arrays.asList(200, 201, 202), jobIds(retries));
        assertEquals(Arrays.asList(1), taskIds(retries.get(0)));
        assertEquals(Arrays.asList(3), taskIds(retries.get(1)));
        assertEquals(Arrays.asList(4), taskIds(retries.get(2)));
        assertEquals(Arrays.asList(100L, 300L, 400L), jobLengths(retries));
    }

    @Test
    void dcProcessUsesTheLosslessPartitionPathWhenFailureMonitorSelectsKOne()
            throws Exception {
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DC,
                FailureParameters.FTCMonitor.MONITOR_ALL,
                FailureParameters.FTCFailure.FAILURE_NONE, null);
        FailureMonitor.init();
        // 零开销且已观测到失败时，旧 DC 公式的最小合法因子为 1。
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 1, 1, 3, 0, 0, 7));
        Job failed = computeJob(40,
                task(1, 100L, Cloudlet.FAILED),
                task(2, 200L, Cloudlet.FAILED),
                task(3, 300L, Cloudlet.FAILED));

        List<Job> retries = ReclusteringEngine.process(failed, 300);

        assertEquals(Arrays.asList(300, 301, 302), jobIds(retries));
        assertEquals(Arrays.asList(1), taskIds(retries.get(0)));
        assertEquals(Arrays.asList(2), taskIds(retries.get(1)));
        assertEquals(Arrays.asList(3), taskIds(retries.get(2)));
    }

    private static Job computeJob(int id, Task... tasks) {
        Job job = new Job(id, 1L);
        assertTrue(job.setClassType(ClassType.COMPUTE.value));
        job.setUserId(7);
        job.setVmId(0);
        job.setDepth(1);
        job.addTaskList(Arrays.asList(tasks));
        return job;
    }

    private static Task task(int id, long length, int status) throws Exception {
        Task task = new Task(id, length);
        task.setUserId(7);
        task.setDepth(1);
        task.setCloudletStatus(status);
        FileItem file = new FileItem("input-" + id, 1024.0);
        file.setType(FileType.INPUT);
        task.addFile(file);
        task.addRequiredFile(file.getName());
        return task;
    }

    private static List<Integer> jobIds(List<Job> jobs) {
        List<Integer> result = new ArrayList<Integer>();
        for (Job job : jobs) {
            result.add(job.getCloudletId());
        }
        return result;
    }

    private static List<Integer> taskIds(Job job) {
        List<Integer> result = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            result.add(task.getCloudletId());
        }
        return result;
    }

    private static List<Long> jobLengths(List<Job> jobs) {
        List<Long> result = new ArrayList<Long>();
        for (Job job : jobs) {
            result.add(job.getCloudletLength());
        }
        return result;
    }

    private static List<String> fileNames(Job job) {
        List<String> result = new ArrayList<String>();
        for (FileItem file : job.getFileList()) {
            result.add(file.getName());
        }
        return result;
    }
}
