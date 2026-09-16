package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * R9 运行时路径探测：开销 × 故障 × 成本模型在标准 {@link SimulationRunner} 上的
 * 组合开关最小 fixture。
 *
 * <p>随机模型集成测试已分别锁定故障重试与队列开销的单通道路径；本类补齐三个
 * 组合缺口：① 后处理延迟（{@code OverheadParameters.getPostDelay} 在
 * WorkflowScheduler 作业返回路径的消费）端到端行为；② 故障模型与队列开销叠加时
 * 重试谱系与延迟采样仍逐位可重放；③ VM 级成本计费与后处理开销叠加时计费口径
 * 不被开销扰动。全部断言基于固定种子下的实测运行。</p>
 */
class RuntimeModelCombinationProbeIntegrationTest {

    private static final long OVERHEAD_SEED = 77L;
    /**
     * 组合配置的实测种子：队列开销会平移执行窗口，随机模型测试的 20260902 在
     * 叠加开销后不再触发失效；20260904 经 51 种子扫描实测触发 3 次失败/3 次重试
     * （makespan 5.600810343783252）。
     */
    private static final long FAILURE_SEED = 20260904L;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void seededPostDelayOverheadExtendsJobReturnTimingAndReplaysExactly() throws Exception {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(0, DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig noOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .build();
        SimulationConfig withOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport baseline = runner.run(noOverhead,
                PlatformProfiles.homogeneousLocal("post-delay-baseline", 1));
        SimulationReport first = runner.run(withOverhead,
                PlatformProfiles.homogeneousLocal("post-delay-probe", 1));
        SimulationReport second = runner.run(withOverhead,
                PlatformProfiles.homogeneousLocal("post-delay-probe", 1));

        List<Double> firstDelays = postDelays(first);
        assertFalse(firstDelays.isEmpty(), "每个返回的 compute 作业都应记录后处理延迟");
        for (Double delay : firstDelays) {
            assertTrue(delay.doubleValue() > 0.0, "深度 0 默认项必须抽出正延迟");
        }
        for (Double delay : postDelays(baseline)) {
            assertEquals(0.0, delay.doubleValue(), 0.0, "无开销配置的后处理延迟必须为零");
        }
        assertTrue(first.getMakespan() > baseline.getMakespan(),
                "后处理延迟延后作业回传，makespan 必须增大");
        assertEquals(postDelays(first), postDelays(second), "固定种子下延迟采样必须逐位重放");
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
    }

    @Test
    void failureModelCombinesWithQueueOverheadAndRemainsReplayable() throws Exception {
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(0, DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig combined = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(FAILURE_SEED)
                .failureModel(failureModel)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport first = runner.run(combined,
                PlatformProfiles.homogeneousLocal("failure-overhead", 1));
        SimulationReport second = runner.run(combined,
                PlatformProfiles.homogeneousLocal("failure-overhead", 1));

        assertTrue(first.getFailedJobs() > 0, "固定种子 fixture 必须触发至少一次重试");
        assertEquals(3, first.getFailedJobs(), "种子扫描实测黄金值：3 个失败 compute 作业");
        assertEquals(3, first.getMetrics().getRetryJobCreatedCount(),
                "种子扫描实测黄金值：3 次重试");
        assertEquals(5.600810343783252, first.getMakespan(), 1.0e-9,
                "种子扫描实测黄金值：组合配置 makespan");
        for (Double delay : queueDelays(first)) {
            assertTrue(delay.doubleValue() > 0.0, "故障与开销叠加时队列延迟仍必须生效");
        }
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        assertEquals(first.getMetrics().getRetryJobCreatedCount(),
                second.getMetrics().getRetryJobCreatedCount());
        assertEquals(queueDelays(first), queueDelays(second));
    }

    @Test
    void vmCostModelCombinesWithPostDelayOverheadWithoutDistortingBilling() throws Exception {
        PlatformProfile platform = pricedPlatform();
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .postDelays(Collections.singletonMap(0, DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0)))
                .build();
        SimulationConfig vmCostOnly = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .costModel(Parameters.CostModel.VM)
                .build();
        SimulationConfig vmCostWithOverhead = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(OVERHEAD_SEED)
                .costModel(Parameters.CostModel.VM)
                .overheadModel(overhead)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport costOnly = runner.run(vmCostOnly, platform);
        SimulationReport withOverhead = runner.run(vmCostWithOverhead, platform);

        assertEquals(costOnly.getJobs().size(), withOverhead.getJobs().size());
        // 后处理开销只延后回传，不改变作业在 VM 上的 CPU 占用与计费速率。
        for (SimulationReport.JobOutcome job : withOverhead.getJobs()) {
            assertEquals(job.getCpuTime() * 5.0, job.getModeledProcessingCost(), 1.0e-12,
                    "VM 计费速率必须保持 5.0，不受开销模型影响");
        }
        for (Double delay : postDelays(withOverhead)) {
            assertTrue(delay.doubleValue() > 0.0);
        }
        assertTrue(withOverhead.getMakespan() > costOnly.getMakespan());
    }

    private static PlatformProfile pricedPlatform() {
        PlatformProfile.CostSpec vmCosts = new PlatformProfile.CostSpec(5.0, 0.0, 0.0, 0.0);
        PlatformProfile.CostSpec datacenterCosts = new PlatformProfile.CostSpec(2.0, 0.0, 0.0, 0.0);
        return PlatformProfile.builder("priced-probe-platform")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED, vmCosts))
                .costs(datacenterCosts)
                .build();
    }

    private static String workflow() throws Exception {
        URL url = RuntimeModelCombinationProbeIntegrationTest.class.getResource(
                "/wfcommons/minimum-runtime.json");
        if (url == null) {
            throw new IllegalStateException("Missing WfCommons test fixture");
        }
        return Paths.get(url.toURI()).toString();
    }

    private static List<Double> postDelays(SimulationReport report) {
        List<Double> delays = new ArrayList<Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_RETURNED) {
                delays.add(((Number) event.getAttributes().get("postDelaySeconds")).doubleValue());
            }
        }
        return delays;
    }

    private static List<Double> queueDelays(SimulationReport report) {
        List<Double> delays = new ArrayList<Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_DISPATCHED) {
                delays.add(((Number) event.getAttributes().get("queueDelaySeconds")).doubleValue());
            }
        }
        return delays;
    }
}
