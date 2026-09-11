package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class DataMovementIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void fixedEndpointModelChangesOnlyTheDeclaredStageInTimingAndEvidence(@TempDir Path output)
            throws Exception {
        String workflow = resourcePath("/dax/p7-shared-storage.dax");
        PlatformProfile platform = oneVmPlatform();
        SimulationConfig defaultLegacy = SimulationConfig.builder(workflow, 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationConfig explicitLegacy = defaultLegacy.toBuilder()
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .build();
        SimulationConfig fixed = defaultLegacy.toBuilder()
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.5, 10.0))
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport defaultReport = runner.run(defaultLegacy, platform);
        SimulationReport legacyReport = runner.run(explicitLegacy, platform);
        SimulationReport fixedReport = runner.run(fixed, platform);

        // 30 MB 输入在历史共享存储模型中按 20 MB/s 为 1.5 秒；固定端点模型受 10 MB/s
        // 访问链路限制，并加入 0.5 秒延迟，故数学判定基准为 3.5 秒。
        assertEquals(legacyTransferSeconds(defaultReport), legacyTransferSeconds(legacyReport), 0.0);
        assertEquals(defaultReport.getMakespan(), legacyReport.getMakespan(), 0.0);
        assertEquals(1.5, legacyTransferSeconds(defaultReport), 1.0e-12);
        SimulationEvent fixedTransfer = transferEvent(fixedReport);
        assertEquals(3.5, fixedTransfer.getAttributes().get("modeledTransferSeconds"));
        assertEquals("FIXED_ENDPOINT_NO_CONTENTION_V1",
                transferEvent(fixedReport).getAttributes().get("dataMovementModel"));
        assertEquals(1, ((Number) transferEvent(fixedReport).getAttributes()
                .get("modeledTransferFileCount")).intValue());
        assertEquals(30_000_000.0, ((Number) transferEvent(fixedReport).getAttributes()
                .get("requiredFileBytes")).doubleValue(), 0.0);
        SimulationEvent taskExecution = taskExecutionEvent(fixedReport,
                fixedTransfer.getTaskIds().get(0).intValue());
        assertEquals(fixedTransfer.getSimulationTime() + 3.5,
                ((Number) taskExecution.getAttributes().get("taskStartTime")).doubleValue(), 1.0e-12);
        assertEquals(3.5, ((Number) taskExecution.getAttributes()
                .get("modeledStageInSecondsBeforeTask")).doubleValue(), 1.0e-12);
        assertEquals("MODEL_DERIVED_COMPUTE_WINDOW", taskExecution.getAttributes()
                .get("taskTimingScope"));
        SimulationReport.TaskOutcome task = taskOutcome(fixedReport,
                fixedTransfer.getTaskIds().get(0).intValue());
        assertFalse(task.hasExactJobTiming());
        assertEquals(((Number) taskExecution.getAttributes().get("taskStartTime")).doubleValue(),
                task.getStartTime(), 1.0e-12);
        assertEquals(((Number) taskExecution.getAttributes().get("taskFinishTime")).doubleValue(),
                task.getFinishTime(), 1.0e-12);
        assertTrue(fixedReport.getMakespan() > defaultReport.getMakespan());
        assertEquals(1, fixedReport.getMetrics().getDataStageInModelObservationCount());
        assertEquals(1, fixedReport.getMetrics().getModeledDataTransferFileCount());
        assertEquals(3.5, fixedReport.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-12);
        assertEquals(30_000_000.0, fixedReport.getMetrics().getTotalModeledRequiredInputBytes(), 0.0);

        Path manifest = output.resolve("fixed.manifest.json");
        ExperimentManifestWriter.writeJson(fixedReport, manifest);
        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("FIXED_ENDPOINT_NO_CONTENTION_V1"));
        assertTrue(contents.contains("SERIAL_PER_JOB_NO_SHARED_LINK_CONTENTION"));
    }

    @Test
    void sharedStorageDagPlannersRejectADataMovementModelTheyDoNotEstimate() {
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.0, 10.0))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.0, 10.0))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.0, 10.0))
                .build());
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.0, 10.0))
                .build());
    }

    @Test
    void fixedEndpointLocalModelUsesTheParentVmReplicaWhenTheChildMovesToAnotherVm()
            throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/local-data-transfer.dax"), 2)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.5, 10.0))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, twoVmPlatform());

        // 子 Job 跨 VM 读取父任务副本时，端点带宽仍受 10 MB/s 访问链路限制：0.5 + 30/10 = 3.5 秒。
        SimulationEvent childTransfer = largestTransferEvent(report);
        assertEquals(3.5, ((Number) childTransfer.getAttributes()
                .get("modeledTransferSeconds")).doubleValue(), 1.0e-12);
        assertEquals(1, childTransfer.getTaskIds().size());
        assertEquals(1, report.getMetrics().getModeledDataTransferFileCount());
        assertEquals(3.5, report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-12);
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    @Test
    void failedParentAttemptNeverBecomesTheChildsReadableLocalReplica() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        SimulationConfig config = SimulationConfig.builder(
                resourcePath("/wfcommons/retry-local-output-transfer.json"), 2)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN)
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(10.0, 0.5, 10.0))
                .randomSeed(20260902L)
                .failureModel(failureModel)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, twoVmPlatform());
        SimulationEvent retryEvent = firstEvent(report, SimulationEventType.RETRY_JOB_CREATED);
        int failedParentJobId = ((Number) retryEvent.getAttributes().get("failedJobId")).intValue();
        SimulationReport.JobOutcome failedParent = jobOutcome(report, failedParentJobId);
        SimulationReport.JobOutcome successfulRetry = jobOutcome(report, retryEvent.getJobId().intValue());
        SimulationEvent childTransfer = transferEventWithRequiredBytes(report, 30_000_000.0);
        SimulationReport.JobOutcome child = jobOutcome(report, childTransfer.getJobId().intValue());

        assertEquals(org.cloudbus.cloudsim.Cloudlet.FAILED, failedParent.getStatus());
        assertEquals(org.cloudbus.cloudsim.Cloudlet.SUCCESS, successfulRetry.getStatus());
        assertNotEquals(failedParent.getVmId(), successfulRetry.getVmId(),
                "The fixture requires a failed attempt and successful retry on different VMs");
        assertEquals(failedParent.getVmId(), child.getVmId(),
                "The child intentionally returns to the failed attempt VM to expose replica pollution");
        assertNotEquals(successfulRetry.getVmId(), child.getVmId());

        // 失败 parent 曾在 child VM 上执行，但只有成功 retry 的输出可读，故 child 需要跨
        // VM 获取 30 MB：0.5 + 30 / 10 = 3.5 秒；错误目录污染时该值会被低估为零。
        assertEquals(3.5, ((Number) childTransfer.getAttributes()
                .get("modeledTransferSeconds")).doubleValue(), 1.0e-12);
        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully());
    }

    private static double legacyTransferSeconds(SimulationReport report) {
        return ((Number) transferEvent(report).getAttributes().get("modeledTransferSeconds")).doubleValue();
    }

    private static SimulationEvent transferEvent(SimulationReport report) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED) {
                return event;
            }
        }
        throw new AssertionError("Missing DATA_STAGE_IN_MODELED event");
    }

    private static SimulationEvent largestTransferEvent(SimulationReport report) {
        SimulationEvent result = null;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != SimulationEventType.DATA_STAGE_IN_MODELED) {
                continue;
            }
            if (result == null || ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue()
                    > ((Number) result.getAttributes().get("modeledTransferSeconds")).doubleValue()) {
                result = event;
            }
        }
        if (result == null) {
            throw new AssertionError("Missing DATA_STAGE_IN_MODELED event");
        }
        return result;
    }

    private static SimulationEvent transferEventWithRequiredBytes(SimulationReport report, double bytes) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED
                    && Double.compare(bytes, ((Number) event.getAttributes()
                            .get("requiredFileBytes")).doubleValue()) == 0) {
                return event;
            }
        }
        throw new AssertionError("Missing DATA_STAGE_IN_MODELED event for " + bytes + " bytes");
    }

    private static SimulationEvent firstEvent(SimulationReport report, SimulationEventType type) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == type) {
                return event;
            }
        }
        throw new AssertionError("Missing event " + type);
    }

    private static SimulationReport.JobOutcome jobOutcome(SimulationReport report, int jobId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getJobId() == jobId) {
                return job;
            }
        }
        throw new AssertionError("Missing JobOutcome " + jobId);
    }

    private static SimulationEvent taskExecutionEvent(SimulationReport report, int taskId) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.TASK_EXECUTION_MODELED
                    && event.getTaskIds().contains(Integer.valueOf(taskId))) {
                return event;
            }
        }
        throw new AssertionError("Missing TASK_EXECUTION_MODELED event for task " + taskId);
    }

    private static SimulationReport.TaskOutcome taskOutcome(SimulationReport report, int taskId) {
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() == taskId) {
                return task;
            }
        }
        throw new AssertionError("Missing TaskOutcome for task " + taskId);
    }

    private static PlatformProfile oneVmPlatform() {
        return PlatformProfile.builder("data-movement-one-vm")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0,
                        1024, 1_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1_000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
    }

    private static PlatformProfile twoVmPlatform() {
        return PlatformProfile.builder("data-movement-two-vm")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1_000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512,
                        1_000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = DataMovementIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
