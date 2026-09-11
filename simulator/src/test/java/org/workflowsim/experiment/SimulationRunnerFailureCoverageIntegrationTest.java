package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/** 验证标准运行器在派发前执行故障矩阵覆盖校验，并支持真实 VM ID 键控配置。 */
class SimulationRunnerFailureCoverageIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void missingVmDepthCoordinateFailsDuringParsedPreDispatchValidation() throws Exception {
        FailureModelConfig model = FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM_JOB)
                .generatorSpecs(new DistributionSpec[][]{
                    {spec(), spec(), spec(), spec()},
                    {spec(), spec(), spec()}
                })
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .failureModel(model)
                .build();

        Log.disable();
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("missing-vm-depth", 2)));

        assertTrue(exception.getMessage().contains("before job dispatch"));
        assertTrue(exception.getMessage().contains("mode=FAILURE_VM_JOB"));
        assertTrue(exception.getMessage().contains("required=[vm=1, depth=3]"));
    }

    @Test
    void vmIdKeyedFailureRowsRunOnANonContiguousPlatform() throws Exception {
        Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
        rows.put(Integer.valueOf(10), new DistributionSpec[]{spec()});
        rows.put(Integer.valueOf(20), new DistributionSpec[]{spec()});
        FailureModelConfig model = FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .generatorSpecsByVmId(rows)
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(20260903L)
                .failureModel(model)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, nonContiguousPlatform());

        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        Map<String, Object> manifest = ExperimentManifestWriter.toManifest(report,
                java.util.Collections.<Map<String, Object>>emptyList());
        Map<String, Object> configuration = (Map<String, Object>) manifest.get("configuration");
        Map<String, Object> failure = (Map<String, Object>) configuration.get("failureModel");
        assertEquals("VM_ID_KEYED_ROWS", failure.get("generatorAddressing"));
        assertTrue(((java.util.List<?>) failure.get("generators")).isEmpty());
        Map<String, Object> keyedRows = (Map<String, Object>) failure.get("generatorsByVmId");
        assertEquals(java.util.Arrays.asList("10", "20"),
                new java.util.ArrayList<String>(keyedRows.keySet()));
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertTrue(task.getVmId() == 10 || task.getVmId() == 20);
        }
    }

    @Test
    void standardRunnerRejectsMonitorModesThatDoNotChangeNoopRecovery() {
        FailureModelConfig model = FailureModelConfig.builder()
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{spec()}})
                .maxTotalRetryJobs(1)
                .build();
        SimulationConfig config = SimulationConfig.builder("fixture.dax", 1)
                .failureModel(model)
                .build();

        SimulationConfigurationException exception = assertThrows(SimulationConfigurationException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("monitor-contract", 1)));
        assertTrue(exception.getMessage().contains("MONITOR_NONE"));
    }

    private static DistributionSpec spec() {
        // 极低的抽象故障强度仅让测试覆盖 generator 选择，不依赖 retry 结果。
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0);
    }

    private static PlatformProfile nonContiguousPlatform() {
        return PlatformProfile.builder("non-contiguous-failure-vms")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addHost(new PlatformProfile.HostSpec(1, 2, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(20, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(10, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build();
    }

    private static String workflow() throws Exception {
        URL url = SimulationRunnerFailureCoverageIntegrationTest.class.getResource(
                "/dax/reproducibility-workflow.dax");
        if (url == null) {
            throw new IllegalStateException("Missing DAX failure coverage fixture");
        }
        return Paths.get(url.toURI()).toString();
    }
}
