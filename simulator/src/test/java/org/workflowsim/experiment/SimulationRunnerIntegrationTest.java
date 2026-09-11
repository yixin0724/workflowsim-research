package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationRunnerIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void repeatedDaxRunsProduceEquivalentAuditableReports() throws Exception {
        String workflow = resourcePath("/dax/reproducibility-workflow.dax");
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(20260901L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("runner-dax-platform", 3);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport first = runner.run(config, platform);
        SimulationReport second = runner.run(config, platform);

        assertEquivalent(first, second);
        assertEquals(taskFingerprint(first), taskFingerprint(second));
        assertEquals(logicalEventFingerprint(first), logicalEventFingerprint(second));
        assertEquals(logicalMetricsFingerprint(first), logicalMetricsFingerprint(second));
        assertEquals(first.getVmSummaries().keySet(), second.getVmSummaries().keySet());
        assertEquals(64, first.getInputs().get(0).getSha256().length());
        assertEquals(WorkflowInputReport.Format.DAX_XML,
                first.getInputReports().get(0).getFormat());
        assertEquals(first.getTotalJobs(), first.getSuccessfulJobs());
        assertEquals(0, first.getFailedJobs());
        assertEquals(platform.getVms().size(), first.getVmSummaries().size());
    }

    @Test
    void wfCommonsRunProducesAnOptInManifest(@TempDir Path tempDirectory) throws Exception {
        String workflow = resourcePath("/wfcommons/minimum-runtime.json");
        SimulationConfig config = SimulationConfig.builder(workflow, 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(7L)
                .deadline(100L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("runner-json-platform", 1);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        assertEquivalent(report, runner.run(config, platform));
        assertEquivalent(report, runner.run(config, platform));
        Path manifest = tempDirectory.resolve("manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);

        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(manifest));
        assertTrue(contents.contains("workflowsim-experiment-manifest-v3"));
        assertTrue(contents.contains("WFCOMMONS_JSON"));
        assertTrue(contents.contains("TASK_RUNTIME_FLOORED_TO_MINIMUM"));
        assertTrue(contents.contains("runtimeReferenceMips"));
        assertTrue(contents.contains("overheadModel"));
        assertTrue(contents.contains("javaRuntimeVersion"));
        assertTrue(contents.contains("failureModel"));
        assertTrue(contents.contains("maxTotalRetryJobs"));
        assertTrue(contents.contains("generatorAddressing"));
        assertTrue(contents.contains("generatorsByVmId"));
        assertTrue(contents.contains("algorithmContract"));
        assertTrue(contents.contains("deadlineSemantics"));
        assertTrue(contents.contains("SIMULATION_END_SECONDS"));
        assertTrue(contents.contains("logicalTaskCompletionStatus"));
        assertTrue(contents.contains("terminalLifecycleTailSeconds"));
        assertTrue(contents.contains("DECLARED_FILE_BYTES_ALL_JOB_FILE_ITEMS"));
        assertTrue(contents.contains("maxTransferRateMbPerSecond"));
        assertTrue(contents.contains("cpuPerSecond"));
        assertTrue(contents.contains("classType"));
        assertTrue(contents.contains("taskIds"));
        assertTrue(contents.contains("sourceTreeSha256"));
        assertTrue(contents.contains("metrics"));
        assertTrue(contents.contains("workflowProfile"));
        assertEquals(1, report.getInputReports().size());
        assertFalse(report.getJobs().isEmpty());
        assertEquals(1, report.getWorkflowProfile().getTaskCount());
        assertEquals(0, report.getWorkflowProfile().getEdgeCount());
        assertTrue(report.getMetrics().isDeadlineObservationEnabled());
        assertTrue(report.getMetrics().isDeadlineMet());
        assertEquals(report.getMakespan(), report.getSimulationEndSeconds(), 0.0);
        assertEquals("COMPLETED_SUCCESSFULLY", report.getLogicalTaskCompletionStatus());
        assertTrue(report.getLogicalTaskCompletionSeconds() != null);
    }

    @Test
    void staticIndependentMctMappingReachesTaskOutcomes() throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/independent-tasks.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MCT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .runtimeReferenceMips(1000.0)
                .runtimeScale(1.0)
                .build();
        PlatformProfile platform = PlatformProfile.builder("static-independent-mct")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);
        Map<Integer, Integer> vmByTaskId = new TreeMap<Integer, Integer>();
        for (SimulationReport.TaskOutcome outcome : report.getTasks()) {
            vmByTaskId.put(outcome.getTaskId(), outcome.getVmId());
        }

        // Task ID 对应 DAX 声明顺序。MCT 先将 3 秒任务映射到 1000 MIPS，
        // 再将 1 秒任务映射到 500 MIPS，最后按 VM ID 打破 4 秒任务的并列。
        assertEquals(0, vmByTaskId.get(1).intValue());
        assertEquals(1, vmByTaskId.get(2).intValue());
        assertEquals(0, vmByTaskId.get(3).intValue());
        assertEquals(3, report.getTasks().size());
        assertEquals(3, report.getMetrics().getExactTaskTimingObservationCount());
        assertEquals(0, report.getMetrics().getModeledApproximateTaskTimingObservationCount());
        Map<Integer, SimulationReport.JobOutcome> jobById = new TreeMap<Integer, SimulationReport.JobOutcome>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            jobById.put(job.getJobId(), job);
        }
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            SimulationReport.JobOutcome job = jobById.get(task.getJobId());
            assertTrue(task.hasExactJobTiming());
            assertEquals(Cloudlet.SUCCESS, task.getTaskStatus());
            assertEquals(job.getStartTime(), task.getStartTime(), 0.0);
            assertEquals(job.getFinishTime(), task.getFinishTime(), 0.0);
        }
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertEquals(0, report.getFailedJobs());
    }

    @Test
    void configuredKernelCadenceChangesTinyWorkflowCompletionTime() throws Exception {
        String workflow = resourcePath("/wfcommons/minimum-runtime.json");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("cadence-platform", 1);
        SimulationConfig shortInterval = SimulationConfig.builder(workflow, 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .cloudSimMinEventIntervalSeconds(0.1)
                .build();
        SimulationConfig longInterval = SimulationConfig.builder(workflow, 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .cloudSimMinEventIntervalSeconds(0.25)
                .build();

        Log.disable();
        SimulationReport shortRun = new SimulationRunner().run(shortInterval, platform);
        SimulationReport longRun = new SimulationRunner().run(longInterval, platform);

        assertEquals(shortRun.getTotalJobs(), shortRun.getSuccessfulJobs());
        assertEquals(longRun.getTotalJobs(), longRun.getSuccessfulJobs());
        assertTrue(longRun.getMakespan() > shortRun.getMakespan());
    }

    @Test
    void platformProfileRejectsAnUnresolvableHostPlacementBeforeSimulationStarts() throws Exception {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> PlatformProfile.builder("fragmented-placement")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0,
                        1024, 10_000L, 1_000_000L))
                .addHost(new PlatformProfile.HostSpec(1, 2, 1000.0,
                        512, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 2, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 2, 1024,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build());
        assertTrue(exception.getMessage().contains("No deterministic host placement"));
    }

    @Test
    void reportAndManifestRecordTheActualPinnedVmHostPlacement(@TempDir Path tempDirectory)
            throws Exception {
        PlatformProfile platform = PlatformProfile.builder("pinned-placement-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0,
                        2048, 2_000L, 1_000_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0,
                        1024, 1_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(1, 1)
                .build();
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/wfcommons/minimum-runtime.json"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(platform.getVmHostAssignments(), report.getActualVmHostAssignments());
        assertEquals(Integer.valueOf(0), report.getActualVmHostAssignments().get(0));
        assertEquals(Integer.valueOf(1), report.getActualVmHostAssignments().get(1));
        Path manifest = tempDirectory.resolve("pinned-placement.json");
        ExperimentManifestWriter.writeJson(report, manifest);
        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("actualVmHostAssignments"));
        assertTrue(contents.contains("pinnedHostId"));
        assertTrue(contents.contains("preflightHostId"));
    }

    @Test
    void staticDagPlannerAlignsWithTwoCapacityProvisionedVmsOnOneHost() throws Exception {
        PlatformProfile platform = PlatformProfile.builder("collocated-static-vms")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(Integer.valueOf(0), report.getActualVmHostAssignments().get(0));
        assertEquals(Integer.valueOf(0), report.getActualVmHostAssignments().get(1));
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "ETF");
    }

    @Test
    void sharedStorageHeftProducesACompleteStaticDagExecution(@TempDir Path tempDirectory) throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-heft-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(5, report.getTasks().size());
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertEquals(0, report.getFailedJobs());
        assertEquals(5, report.getMetrics().getLogicalTaskCount());
        assertEquals(5, report.getMetrics().getSuccessfullyCompletedLogicalTaskCount());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertTrue(report.getMetrics().isControlledSharedStorageCriticalPathReferenceAvailable());
        assertTrue(report.getMetrics().getControlledSharedStorageScheduleLengthRatio() >= 1.0);
        assertTrue(report.getMetrics().getMeanVmModeledIntervalUtilization() >= 0.0);
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "HEFT");
        Path manifest = tempDirectory.resolve("heft-manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);
        String manifestContents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(manifestContents.contains("sharedStorageDagPlanTrace"));
        assertTrue(manifestContents.contains("cloudSimMinEventIntervalSeconds"));
        assertTrue(manifestContents.contains("\"HEFT\""));
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertTrue(task.getVmId() == 0 || task.getVmId() == 1);
        }
        boolean observedPlanning = false;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.PLANNING_COMPLETED
                    && "SHARED_STORAGE_HEFT".equals(event.getAttributes().get("planningAlgorithm"))) {
                observedPlanning = true;
            }
        }
        assertTrue(observedPlanning);
    }

    @Test
    void sharedStorageCpopProducesACompleteStaticDagExecution() throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-cpop-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(5, report.getTasks().size());
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "CPOP");
        boolean observedPlanning = false;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.PLANNING_COMPLETED
                    && "SHARED_STORAGE_CPOP".equals(event.getAttributes().get("planningAlgorithm"))) {
                observedPlanning = true;
            }
        }
        assertTrue(observedPlanning);
    }

    @Test
    void sharedStorageDlsPublishesAnAuditablePlanAlignedWithExecution(@TempDir Path tempDirectory)
            throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-dls-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "DLS");
        for (SharedStorageDagPlanTrace.TaskPlan plan : report.getSharedStorageDagPlanTrace()
                .getTaskPlans().values()) {
            assertTrue(plan.getDlsDynamicLevelAtSelection() != null);
            assertTrue(plan.getDlsSelectionOrder() != null);
        }
        Path manifest = tempDirectory.resolve("dls-manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);
        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("SHARED_STORAGE_DLS"));
        assertTrue(contents.contains("dlsDynamicLevelAtSelection"));
        assertTrue(contents.contains("dlsSelectionOrder"));
    }

    @Test
    void sharedStorageEtfPublishesAnAuditablePlanAlignedWithExecution(@TempDir Path tempDirectory)
            throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-etf-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "ETF");
        for (SharedStorageDagPlanTrace.TaskPlan plan : report.getSharedStorageDagPlanTrace()
                .getTaskPlans().values()) {
            assertTrue(plan.getEtfEarliestStartAtSelection() != null);
            assertTrue(plan.getEtfSelectionOrder() != null);
        }
        Path manifest = tempDirectory.resolve("etf-manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);
        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("SHARED_STORAGE_ETF"));
        assertTrue(contents.contains("etfEarliestStartAtSelection"));
        assertTrue(contents.contains("etfSelectionOrder"));
    }

    @Test
    void sharedStoragePeftPublishesAnAuditablePlanAlignedWithExecution(@TempDir Path tempDirectory)
            throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-peft-integration")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 2_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "PEFT");
        assertEquals(1, report.getMetrics().getExplicitPlannerDecisionObservationCount());
        assertTrue(report.getMetrics().getTotalPlanningDecisionWallClockNanos() >= 0L);
        for (SharedStorageDagPlanTrace.TaskPlan plan : report.getSharedStorageDagPlanTrace()
                .getTaskPlans().values()) {
            assertTrue(plan.getPeftRankOctSeconds() != null);
            assertTrue(plan.getPeftOptimisticCostAtSelectedVmSeconds() != null);
            assertTrue(plan.getPeftEftPlusOptimisticCostSeconds() != null);
            assertTrue(plan.getPeftSelectionOrder() != null);
        }
        Path manifest = tempDirectory.resolve("peft-manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);
        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("SHARED_STORAGE_PEFT"));
        assertTrue(contents.contains("peftRankOctSeconds"));
        assertTrue(contents.contains("peftEftPlusOptimisticCostSeconds"));
        assertTrue(contents.contains("peftSelectionOrder"));
    }

    @Test
    void sharedStorageStaticPlanIncludesTheKernelSafetyQuantumForFastStageIn() throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/p7-shared-storage.dax"), 1)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();
        PlatformProfile platform = PlatformProfile.builder("shared-storage-fast-stage-in")
                .addHost(new PlatformProfile.HostSpec(0, 1, 2000.0,
                        1024, 1_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 2000.0, 1, 512,
                        1_000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        assertEquals(0.21, report.getSharedStorageDagPlanTrace().getStageInFinishSeconds(), 1.0e-12);
        SharedStorageDagPlanTrace.TaskPlan plan = report.getSharedStorageDagPlanTrace()
                .getTaskPlans().values().iterator().next();
        assertEquals(1.5, plan.getPlannedDataStageInSeconds(), 1.0e-12);
        assertEquals(1.71, plan.getPlannedComputeStartSeconds(), 1.0e-12);
        assertStaticDagPlanMatchesExecutedTaskStarts(report, "HEFT");
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }

    private static void assertEquivalent(SimulationReport expected, SimulationReport actual) {
        assertEquals(expected.getMakespan(), actual.getMakespan(), 0.0);
        assertEquals(expected.getSuccessfulJobs(), actual.getSuccessfulJobs());
        assertEquals(expected.getFailedJobs(), actual.getFailedJobs());
        assertEquals(expected.getInputs().get(0).getSha256(), actual.getInputs().get(0).getSha256());
        assertEquals(jobFingerprint(expected), jobFingerprint(actual));
        assertEquals(expected.getVmSummaries().keySet(), actual.getVmSummaries().keySet());
    }

    private static void assertStaticDagPlanMatchesExecutedTaskStarts(SimulationReport report,
            String expectedStrategy) {
        SharedStorageDagPlanTrace trace = report.getSharedStorageDagPlanTrace();
        assertTrue(trace != null, "A maintained static DAG planner must publish its trace");
        assertEquals(expectedStrategy, trace.getStrategy());
        assertEquals(report.getTasks().size(), trace.getTaskPlans().size());
        Map<Integer, Double> plannedStarts = new TreeMap<Integer, Double>();
        Map<Integer, Double> actualStarts = new TreeMap<Integer, Double>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            SharedStorageDagPlanTrace.TaskPlan planned = trace.getTaskPlan(task.getTaskId());
            assertTrue(planned != null, "Missing static plan entry for task " + task.getTaskId());
            assertEquals(planned.getVmId(), task.getVmId(), "Task " + task.getTaskId()
                    + " in Job " + task.getJobId() + " planned for VM " + planned.getVmId()
                    + " but reported VM " + task.getVmId());
            plannedStarts.put(task.getTaskId(), planned.getPlannedComputeStartSeconds());
            actualStarts.put(task.getTaskId(), task.getStartTime());
        }
        assertEquals(plannedStarts, actualStarts);
    }

    private static String jobFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            result.append(job.getJobId()).append('|').append(job.getVmId()).append('|')
                    .append(job.getStatus()).append('|').append(job.getStartTime()).append('|')
                    .append(job.getFinishTime()).append('|').append(job.getCpuTime()).append('|')
                    .append(job.getTaskCount()).append('\n');
        }
        return result.toString();
    }

    private static String taskFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            result.append(task.getTaskId()).append('|').append(task.getJobId()).append('|')
                    .append(task.getVmId()).append('|').append(task.getTaskStatus()).append('|')
                    .append(task.getStartTime()).append('|').append(task.getFinishTime()).append('\n');
        }
        return result.toString();
    }

    /** 确定性重放有意排除墙钟决策耗时。 */
    private static String logicalEventFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationEvent event : report.getEvents()) {
            Map<String, Object> attributes = new TreeMap<String, Object>(event.getAttributes());
            attributes.remove("decisionElapsedNanos");
            attributes.remove("planningDecisionElapsedNanos");
            result.append(event.getSequence()).append('|').append(event.getSimulationTime()).append('|')
                    .append(event.getType()).append('|').append(event.getJobId()).append('|')
                    .append(event.getVmId()).append('|').append(event.getTaskIds()).append('|')
                    .append(attributes).append('\n');
        }
        return result.toString();
    }

    private static String logicalMetricsFingerprint(SimulationReport report) {
        SimulationMetrics metrics = report.getMetrics();
        StringBuilder result = new StringBuilder();
        result.append(metrics.getMakespanSeconds()).append('|')
                .append(metrics.getComputeJobOutcomeCount()).append('|')
                .append(metrics.getSuccessfulComputeJobOutcomeRate()).append('|')
                .append(metrics.getMeanComputeJobRunTimeSeconds()).append('|')
                .append(metrics.getMeanComputeReadyToDecisionDelaySeconds()).append('|')
                .append(metrics.getMeanComputeDecisionToStartDelaySeconds()).append('|')
                .append(metrics.getVmModeledBusyTimeCoefficientOfVariation()).append('|');
        for (SimulationMetrics.VmMetrics vm : metrics.getVmMetrics().values()) {
            result.append(vm.getVmId()).append('|').append(vm.getJobOutcomeCount()).append('|')
                    .append(vm.getReportedCpuTimeSeconds()).append('|')
                    .append(vm.getModeledBusyIntervalSeconds()).append('|')
                    .append(vm.getModeledIntervalUtilization()).append('|')
                    .append(vm.getReportedCpuTimeOverMakespan()).append('\n');
        }
        return result.toString();
    }
}
