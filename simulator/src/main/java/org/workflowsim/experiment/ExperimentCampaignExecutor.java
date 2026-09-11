package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.List;

/** 通过标准运行器顺序执行已经声明的 campaign。 */
public final class ExperimentCampaignExecutor {

    /**
     * 按 cell 声明顺序和种子计划顺序运行全部重复。
     *
     * <p>WorkflowSim 保留 CloudSim 静态状态，标准运行器不承诺 JVM 内并发，
     * 因此这里刻意采用顺序执行。</p>
     *
     * @param plan 已通过构造约束校验的不可变实验计划
     * @return 包含每个已执行 cell/repetition 的不可变结果
     * @throws Exception 当任一次标准仿真运行失败时抛出
     * @throws IllegalArgumentException 当 {@code plan} 为空时抛出
     */
    public ExperimentCampaignResult execute(ExperimentPlan plan) throws Exception {
        if (plan == null) {
            throw new IllegalArgumentException("Experiment plan is required");
        }
        SimulationRunner runner = new SimulationRunner();
        List<ExperimentCampaignResult.Run> runs = new ArrayList<ExperimentCampaignResult.Run>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            List<Long> seeds = cell.getSeedPlan().getSeeds();
            for (int replication = 0; replication < seeds.size(); replication++) {
                long seed = seeds.get(replication).longValue();
                SimulationReport report = runner.run(cell.getConfig().withRandomSeed(seed),
                        cell.getPlatform());
                runs.add(new ExperimentCampaignResult.Run(cell, replication, seed, report));
            }
        }
        return new ExperimentCampaignResult(plan, runs);
    }
}
