package org.workflowsim.rl;

import java.util.List;
import org.workflowsim.experiment.SimulationReport;

/**
 * 一个 RL episode 的完整结果：仿真证据报告 + episode 统计（奖励、步数、决策轨迹）。
 *
 * <p>奖励契约：{@code reward = −makespan}（秒）。episode 是完整的一次工作流仿真，
 * 奖励只在终局给出，不做逐步塑形——makespan 是该轨道唯一被回归覆盖的目标量。</p>
 */
public final class RlEpisodeResult {

    private final SimulationReport report;
    private final List<RlDecision> decisions;
    private final int stepCount;

    RlEpisodeResult(SimulationReport report, List<RlDecision> decisions, int stepCount) {
        this.report = report;
        this.decisions = decisions;
        this.stepCount = stepCount;
    }

    /** @return 本次 episode 的完整仿真证据报告 */
    public SimulationReport getReport() {
        return report;
    }

    /** @return episode makespan（秒） */
    public double getMakespan() {
        return report.getMakespan();
    }

    /** @return episode 奖励 = −makespan */
    public double getReward() {
        return -report.getMakespan();
    }

    /** @return 策略查询次数（每次 ready-batch 调度更新计一步） */
    public int getStepCount() {
        return stepCount;
    }

    /** @return 成功分派的决策轨迹（插入序，不可变） */
    public List<RlDecision> getDecisions() {
        return decisions;
    }
}
