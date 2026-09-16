package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.rl.EarliestFinishGreedyPolicy;
import org.workflowsim.rl.RlDecision;
import org.workflowsim.rl.RlEnvironment;
import org.workflowsim.rl.RlEpisodeResult;
import org.workflowsim.rl.RlPolicy;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * R4 RL 轨道的端到端 episode 验收：环境闭环、确定性、奖励契约、决策轨迹恒等
 * 与错误路径显式失败。
 *
 * <p>fixture 与 HEFT 论文例同源（{@code /dax/heft-paper-example.dax}，3 台
 * mips=1.0 VM），但走在线调度轨道（规划层 INVALID、默认 SHARED 文件系统、
 * 遗留数据移动模型）：RL_POLICY 与 FCFS/MINMIN 同属 ready-batch 在线层，
 * 不依赖任何静态映射前提。</p>
 */
class RlEnvironmentEpisodeIntegrationTest {

    /**
     * 贪心基线在论文例 fixture 上的 episode makespan（确定性黄金值）。
     *
     * <p>该值与静态 HEFT 复现的 190.1 不可比：本 episode 走在线调度轨道
     * （INVALID 规划、遗留数据移动模型、默认 SHARED 文件系统），DAX 运行时直接
     * 折算 MI，无成本矩阵引导——它锁定的是 RL 环境的闭环行为，不是论文成绩。</p>
     */
    private static final double GREEDY_EPISODE_GOLDEN_MAKESPAN = 5116.1;

    @Test
    void rlEpisodeIsDeterministicAndRewardIsNegativeMakespan() throws Exception {
        RlEpisodeResult first = runEpisode(new EarliestFinishGreedyPolicy());
        RlEpisodeResult second = runEpisode(new EarliestFinishGreedyPolicy());
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0,
                "确定性策略的 episode 必须逐位可复现");
        assertEquals(GREEDY_EPISODE_GOLDEN_MAKESPAN, first.getMakespan(), 0.0,
                "贪心 episode 黄金 makespan");
        assertEquals(-first.getMakespan(), first.getReward(), 0.0, "奖励契约 reward = −makespan");
        assertTrue(first.getStepCount() >= 1, "至少一次策略查询");
        assertNotNull(first.getReport(), "episode 必须携带完整证据报告");
    }

    @Test
    void decisionTraceMatchesFinalReportAssignments() throws Exception {
        RlEpisodeResult episode = runEpisode(new EarliestFinishGreedyPolicy());
        SimulationReport report = episode.getReport();
        // 每个 Job 恰好被分派一次，且决策轨迹与报告中的最终 VM 分配逐一致。
        assertEquals(report.getTotalJobs(), episode.getDecisions().size(),
                "决策轨迹必须覆盖每个 Job 恰好一次");
        Set<Integer> seen = new HashSet<Integer>();
        for (RlDecision decision : episode.getDecisions()) {
            assertTrue(seen.add(decision.getJobId()),
                    "Job " + decision.getJobId() + " 被重复分派");
            SimulationReport.JobOutcome outcome = jobById(report, decision.getJobId());
            assertEquals(decision.getVmId(), outcome.getVmId(),
                    "Job " + decision.getJobId() + " 的决策 VM 必须等于最终分配");
            assertTrue(decision.getTime() >= 0.0, "决策时刻非负");
        }
    }

    @Test
    void rlPolicyWithoutEnvironmentFailsExplicitly() throws Exception {
        // 绕过 RlEnvironment 直接用 SimulationRunner 运行 RL_POLICY：注册表无策略，
        // 必须显式失败并指向正确入口。R8 审计修复（P1-7）后配置类异常不再被
        // 包装成"调度算法失败"的 IllegalStateException，而是以
        // SimulationConfigurationException 直达调用方。
        SimulationConfigurationException failure = assertThrows(
                SimulationConfigurationException.class,
                () -> new SimulationRunner().run(rlConfig(), paperPlatform()));
        assertTrue(chainMentions(failure, SimulationConfigurationException.class, "RlEnvironment"),
                "失败链必须指向 RlEnvironment.runEpisode，实际: " + failure);
    }

    @Test
    void environmentRejectsNonRlSchedulingConfiguration() throws Exception {
        SimulationConfig fcfs = SimulationConfig.builder(workflowPath(), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationConfigurationException rejected = assertThrows(
                SimulationConfigurationException.class,
                () -> new RlEnvironment().runEpisode(fcfs, paperPlatform(),
                        new EarliestFinishGreedyPolicy()));
        assertTrue(rejected.getMessage().contains("RL_POLICY"), rejected.getMessage());
    }

    @Test
    void policyContractViolationsAbortTheEpisode() {
        // 动作数组长度错误：契约违反必须中止（调度器包为统一 ISE，真实原因在 cause 链）。
        IllegalStateException wrongLength = assertThrows(IllegalStateException.class,
                () -> new RlEnvironment().runEpisode(rlConfig(), paperPlatform(),
                        observation -> new int[0]));
        assertTrue(chainMentions(wrongLength, IllegalStateException.class, "action(s)"),
                wrongLength.toString());
        // 动作越界：显式失败。
        RlPolicy outOfRange = observation -> {
            int[] actions = new int[observation.getReadyJobs().size()];
            java.util.Arrays.fill(actions, observation.getVms().size());
            return actions;
        };
        IllegalStateException range = assertThrows(IllegalStateException.class,
                () -> new RlEnvironment().runEpisode(rlConfig(), paperPlatform(), outOfRange));
        assertTrue(chainMentions(range, IllegalStateException.class, "out-of-range"),
                range.toString());
        // 永不分配：停滞由平台看门狗显式拦截，而非静默残缺报告。
        SimulationExecutionException stalled = assertThrows(SimulationExecutionException.class,
                () -> new RlEnvironment().runEpisode(rlConfig(), paperPlatform(),
                        observation -> {
                            int[] actions = new int[observation.getReadyJobs().size()];
                            java.util.Arrays.fill(actions, RlPolicy.NO_ASSIGNMENT);
                            return actions;
                        }));
        assertTrue(stalled.getMessage().contains("terminated")
                || stalled.getMessage().contains("stalled"), stalled.getMessage());
    }

    /** cause 链中是否存在指定类型且消息包含关键词的异常。 */
    private static boolean chainMentions(Throwable top, Class<? extends Throwable> type,
            String keyword) {
        Throwable current = top;
        while (current != null) {
            if (type.isInstance(current) && current.getMessage() != null
                    && current.getMessage().contains(keyword)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RlEpisodeResult runEpisode(RlPolicy policy) throws Exception {
        Log.disable();
        return new RlEnvironment().runEpisode(rlConfig(), paperPlatform(), policy);
    }

    private static SimulationConfig rlConfig() throws Exception {
        return SimulationConfig.builder(workflowPath(), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.RL_POLICY)
                .build();
    }

    /** 与 HEFT 论文复现同构的平台：3 台 VM，mips=1.0。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("rl-episode-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static SimulationReport.JobOutcome jobById(SimulationReport report, int jobId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getJobId() == jobId) {
                return job;
            }
        }
        throw new AssertionError("Report has no job " + jobId);
    }

    private static String workflowPath() throws Exception {
        java.net.URL url = RlEnvironmentEpisodeIntegrationTest.class
                .getResource("/dax/heft-paper-example.dax");
        if (url == null) {
            throw new IllegalStateException("Missing test resource /dax/heft-paper-example.dax");
        }
        return Paths.get(url.toURI()).toString();
    }
}
