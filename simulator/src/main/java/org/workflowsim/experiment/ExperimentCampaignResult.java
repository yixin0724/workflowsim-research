package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 已执行全部声明 cell 后形成的不可变内存结果。 */
public final class ExperimentCampaignResult {

    private final ExperimentPlan plan;
    private final List<Run> runs;
    private final ExperimentCampaignSummary summary;

    ExperimentCampaignResult(ExperimentPlan plan, List<Run> runs) {
        if (plan == null || runs == null || runs.size() != plan.getRunCount()) {
            throw new IllegalArgumentException("Campaign result must contain every declared run exactly once");
        }
        this.plan = plan;
        this.runs = Collections.unmodifiableList(new ArrayList<Run>(runs));
        validateRuns(plan, this.runs);
        this.summary = ExperimentCampaignSummary.calculate(plan, this.runs);
    }

    public ExperimentPlan getPlan() { return plan; }
    public List<Run> getRuns() { return runs; }
    public ExperimentCampaignSummary getSummary() { return summary; }

    private static void validateRuns(ExperimentPlan plan, List<Run> runs) {
        Set<String> plannedCellIds = new HashSet<String>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            plannedCellIds.add(cell.getId());
        }
        Set<String> seen = new HashSet<String>();
        for (Run run : runs) {
            String cellId = run.getCell().getId();
            if (!plannedCellIds.contains(cellId)) {
                throw new IllegalArgumentException("Campaign result has a run for undeclared cell " + cellId);
            }
            List<Long> seeds = run.getCell().getSeedPlan().getSeeds();
            if (run.getReplicationIndex() >= seeds.size()
                    || seeds.get(run.getReplicationIndex()).longValue() != run.getSeed()
                    || run.getReport().getConfig().getRandomSeed() != run.getSeed()) {
                throw new IllegalArgumentException("Campaign run seed does not match its declared cell seed plan");
            }
            if (!seen.add(cellId + "\u0000" + run.getReplicationIndex())) {
                throw new IllegalArgumentException("Campaign result has duplicate cell/replication run "
                        + cellId + "/" + run.getReplicationIndex());
            }
        }
    }

    /** 带有来源 cell 与复制身份的不可变单次运行报告。 */
    public static final class Run {
        private final ExperimentPlan.Cell cell;
        private final int replicationIndex;
        private final long seed;
        private final SimulationReport report;

        Run(ExperimentPlan.Cell cell, int replicationIndex, long seed, SimulationReport report) {
            if (cell == null || replicationIndex < 0 || report == null) {
                throw new IllegalArgumentException("Cell, non-negative replication index, and report are required");
            }
            this.cell = cell;
            this.replicationIndex = replicationIndex;
            this.seed = seed;
            this.report = report;
        }

        public ExperimentPlan.Cell getCell() { return cell; }
        public int getReplicationIndex() { return replicationIndex; }
        public long getSeed() { return seed; }
        public SimulationReport getReport() { return report; }
    }
}
