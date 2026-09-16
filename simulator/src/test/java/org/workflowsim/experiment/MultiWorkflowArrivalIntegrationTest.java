package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/**
 * R5 多工作流并发与动态到达的端到端验收：错峰提交门控、每工作流流时证据、
 * WORKFLOW_ARRIVED 事件、确定性，以及全零到达与历史单时刻提交的行为一致性。
 *
 * <p>fixture 与 HEFT 论文例同源（{@code /dax/heft-paper-example.dax}，10 任务，
 * 在线 FCFS 轨道，3 台 mips=1.0 VM 共享）。两份相同输入经多输入命名空间隔离
 * （workflow-0/workflow-1）合并为一个 20 任务 DAG；到达门控按任务编号区间归属。</p>
 */
class MultiWorkflowArrivalIntegrationTest {

    /** 全零到达（默认）黄金 makespan：两份 10 任务 HEFT 例并发共享 3 VM。 */
    private static final double SIMULTANEOUS_GOLDEN_MAKESPAN = 8118.1;
    /**
     * 错峰到达 [0, 50] 黄金 makespan（R8 审计 P0-1 修复后重录 2026-09-16）。
     * 修复前统一 stage-in Job（ID=N）被错误归属到最后工作流、门控到 50 秒，
     * 早到工作流被静默推迟 50 秒，该黄金值曾污染锁定为 8168.0。修复后
     * stage-in 为 t=0 平台准备、各根 Job 按自身归属门控：3 VM 全程饱和下
     * 晚到 50 秒不改变总 makespan，故与同时提交逐位相等。
     */
    private static final double STAGGERED_GOLDEN_MAKESPAN = 8118.1;
    /** 错峰到达下工作流 1 的黄金流时（完成 − 提交时刻 50）。 */
    private static final double STAGGERED_GOLDEN_FLOW_W1 = 8068.1;

    @Test
    void simultaneousArrivalsShareVmsAndReportPerWorkflowOutcomes() throws Exception {
        SimulationReport report = run(Arrays.asList(0.0, 0.0));
        assertEquals(SIMULTANEOUS_GOLDEN_MAKESPAN, report.getMakespan(), 0.0,
                "全零到达黄金 makespan");
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(2, outcomes.size(), "两个输入各产生一份每工作流结果");
        assertEquals(10, outcomes.get(0).getTaskCount());
        assertEquals(10, outcomes.get(1).getTaskCount());
        assertEquals(1, outcomes.get(0).getFirstTaskId());
        assertEquals(10, outcomes.get(0).getLastTaskId());
        assertEquals(11, outcomes.get(1).getFirstTaskId());
        assertEquals(20, outcomes.get(1).getLastTaskId());
        assertEquals(0.0, outcomes.get(0).getArrivalSecond(), 0.0);
        assertEquals(0.0, outcomes.get(1).getArrivalSecond(), 0.0);
        // 全零到达时流时等于最大成功完成时刻。
        assertEquals(outcomes.get(0).getLastSuccessFinishSecond(),
                outcomes.get(0).getFlowTimeSeconds(), 0.0);
        assertTrue(outcomes.get(0).getFlowTimeSeconds() > 0.0);
        assertTrue(outcomes.get(1).getFlowTimeSeconds() > 0.0);
    }

    @Test
    void staggeredArrivalGatesSecondWorkflowAndReportsFlowTime() throws Exception {
        SimulationReport report = run(Arrays.asList(0.0, 50.0));
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(STAGGERED_GOLDEN_MAKESPAN, report.getMakespan(), 0.0,
                "错峰到达黄金 makespan");
        assertEquals(50.0, outcomes.get(1).getArrivalSecond(), 0.0);
        assertEquals(STAGGERED_GOLDEN_FLOW_W1, outcomes.get(1).getFlowTimeSeconds(), 0.0,
                "工作流 1 黄金流时");
        assertEquals(outcomes.get(1).getLastSuccessFinishSecond() - 50.0,
                outcomes.get(1).getFlowTimeSeconds(), 0.0, "流时 = 最大完成 − 提交时刻");
        // 到达门控：工作流 1 的任何任务都不得在提交时刻之前开始。
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            if (task.getTaskId() >= 11 && task.getTaskId() <= 20) {
                assertTrue(task.getStartTime() >= 50.0,
                        "任务 " + task.getTaskId() + " 在到达时刻前开始: " + task.getStartTime());
            }
        }
        // 晚到不会提前：错峰 makespan 不小于同时提交。
        SimulationReport simultaneous = run(Arrays.asList(0.0, 0.0));
        assertTrue(report.getMakespan() >= simultaneous.getMakespan(),
                "错峰到达不得早于同时提交完成");
        // R8 审计回归（P0-1）：早到不得被推迟——双向门控。工作流 0 的最后成功
        // 完成时刻必须与同时提交下逐位一致（修复前 stage-in 被扣到 50 秒，
        // 早到工作流被静默推迟，此断言即其回归锁）。
        assertEquals(simultaneous.getWorkflowOutcomes().get(0).getLastSuccessFinishSecond(),
                outcomes.get(0).getLastSuccessFinishSecond(), 0.0,
                "早到工作流不得被晚到工作流的到达时刻推迟");
    }

    @Test
    void arrivalIsDeterministicAndEmitsArrivalEvidence() throws Exception {
        SimulationReport first = run(Arrays.asList(0.0, 50.0));
        SimulationReport second = run(Arrays.asList(0.0, 50.0));
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0, "确定性");
        assertEquals(first.getWorkflowOutcomes().get(1).getFlowTimeSeconds(),
                second.getWorkflowOutcomes().get(1).getFlowTimeSeconds(), 0.0);
        // WORKFLOW_ARRIVED 证据：每个工作流恰好一次，提交时刻与配置一致。
        int arrived0 = 0;
        int arrived1 = 0;
        for (SimulationEvent event : first.getEvents()) {
            if (event.getType() == SimulationEventType.WORKFLOW_ARRIVED) {
                Object index = event.getAttributes().get("workflowIndex");
                Object arrival = event.getAttributes().get("arrivalSecond");
                if (Integer.valueOf(0).equals(index)) {
                    arrived0++;
                    assertEquals(0.0, ((Number) arrival).doubleValue(), 0.0);
                    assertTrue(event.getSimulationTime() >= 0.0);
                } else if (Integer.valueOf(1).equals(index)) {
                    arrived1++;
                    assertEquals(50.0, ((Number) arrival).doubleValue(), 0.0);
                    assertTrue(event.getSimulationTime() >= 50.0,
                            "到达证据时刻不得早于提交时刻");
                }
            }
        }
        assertEquals(1, arrived0, "工作流 0 恰好一条到达证据");
        assertEquals(1, arrived1, "工作流 1 恰好一条到达证据");
    }

    @Test
    void singleInputZeroArrivalKeepsHistoricalTraceWithoutArrivalEvents() throws Exception {
        SimulationConfig config = SimulationConfig.builder(workflowPath(), 3).build();
        SimulationReport report = new SimulationRunner().run(config, paperPlatform());
        for (SimulationEvent event : report.getEvents()) {
            assertFalse(event.getType() == SimulationEventType.WORKFLOW_ARRIVED,
                    "单输入全零到达不得产生新事件（历史轨迹逐位一致）");
        }
        List<SimulationReport.WorkflowOutcome> outcomes = report.getWorkflowOutcomes();
        assertEquals(1, outcomes.size());
        assertEquals(0.0, outcomes.get(0).getArrivalSecond(), 0.0);
        assertEquals(10, outcomes.get(0).getTaskCount());
        assertEquals(outcomes.get(0).getLastSuccessFinishSecond(),
                outcomes.get(0).getFlowTimeSeconds(), 0.0);
    }

    private static SimulationReport run(List<Double> arrivals) throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig
                .builder(Arrays.asList(workflowPath(), workflowPath()), 3)
                .workflowArrivalSeconds(arrivals)
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    /** 与 HEFT 论文复现同构的平台：3 台 VM，mips=1.0。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("multi-arrival-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static String workflowPath() throws Exception {
        java.net.URL url = MultiWorkflowArrivalIntegrationTest.class
                .getResource("/dax/heft-paper-example.dax");
        if (url == null) {
            throw new IllegalStateException("Missing test resource /dax/heft-paper-example.dax");
        }
        return Paths.get(url.toURI()).toString();
    }
}
