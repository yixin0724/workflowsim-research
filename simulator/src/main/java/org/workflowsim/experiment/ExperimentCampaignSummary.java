package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;

/**
 * 对一个 campaign 的描述性统计汇总，并显式限定可作出的统计解释。
 */
public final class ExperimentCampaignSummary {

    private static final double CONFIDENCE_LEVEL = 0.95;

    private final List<CellSummary> cells;
    private final List<ComparisonSummary> comparisons;

    private ExperimentCampaignSummary(List<CellSummary> cells, List<ComparisonSummary> comparisons) {
        this.cells = Collections.unmodifiableList(new ArrayList<CellSummary>(cells));
        this.comparisons = Collections.unmodifiableList(new ArrayList<ComparisonSummary>(comparisons));
    }

    static ExperimentCampaignSummary calculate(ExperimentPlan plan,
            List<ExperimentCampaignResult.Run> runs) {
        Map<String, List<ExperimentCampaignResult.Run>> runsByCell =
                new LinkedHashMap<String, List<ExperimentCampaignResult.Run>>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            runsByCell.put(cell.getId(), new ArrayList<ExperimentCampaignResult.Run>());
        }
        for (ExperimentCampaignResult.Run run : runs) {
            List<ExperimentCampaignResult.Run> cellRuns = runsByCell.get(run.getCell().getId());
            if (cellRuns == null) {
                throw new IllegalArgumentException("Campaign result contains a run not declared by its plan");
            }
            cellRuns.add(run);
        }

        List<CellSummary> cellSummaries = new ArrayList<CellSummary>();
        Map<String, CellSummary> baselines = new HashMap<String, CellSummary>();
        Map<String, List<CellSummary>> candidatesByGroup = new LinkedHashMap<String, List<CellSummary>>();
        for (ExperimentPlan.Cell cell : plan.getCells()) {
            List<ExperimentCampaignResult.Run> cellRuns = runsByCell.get(cell.getId());
            if (cellRuns.size() != cell.getSeedPlan().getReplicationCount()) {
                throw new IllegalArgumentException("Campaign result count differs from seed plan for " + cell.getId());
            }
            CellSummary summary = CellSummary.from(cell, cellRuns);
            cellSummaries.add(summary);
            List<CellSummary> grouped = candidatesByGroup.get(cell.getComparisonGroup());
            if (grouped == null) {
                grouped = new ArrayList<CellSummary>();
                candidatesByGroup.put(cell.getComparisonGroup(), grouped);
            }
            grouped.add(summary);
            if (cell.isBaseline()) {
                baselines.put(cell.getComparisonGroup(), summary);
            }
        }

        List<ComparisonSummary> comparisonSummaries = new ArrayList<ComparisonSummary>();
        for (Map.Entry<String, List<CellSummary>> entry : candidatesByGroup.entrySet()) {
            CellSummary baseline = baselines.get(entry.getKey());
            for (CellSummary candidate : entry.getValue()) {
                if (!candidate.isBaseline()) {
                    comparisonSummaries.add(ComparisonSummary.from(baseline, candidate));
                }
            }
        }
        return new ExperimentCampaignSummary(cellSummaries, comparisonSummaries);
    }

    /**
     * 返回每个声明 cell 的描述性汇总。
     *
     * @return 按计划声明顺序排列的不可变 cell 汇总列表
     */
    public List<CellSummary> getCells() { return cells; }
    /**
     * 返回每个非基线候选方案相对于其声明基线的比较汇总。
     *
     * @return 不可变比较汇总列表
     */
    public List<ComparisonSummary> getComparisons() { return comparisons; }

    /** 一个已声明 cell 的聚合观测值。 */
    public static final class CellSummary {
        private final String cellId;
        private final String comparisonGroup;
        private final String candidateId;
        private final boolean baseline;
        private final RandomizationDesign randomizationDesign;
        private final List<Long> rootSeeds;
        private final MetricSummary makespanSeconds;
        private final MetricSummary modeledProcessingCost;
        private final MetricSummary successfulLogicalTaskCompletionRate;
        /** PLAT-10：核心性能指标的 replication 层聚合（等待/减速比/利用率/公平性）。 */
        private final MetricSummary meanComputeTotalWaitingTimeSeconds;
        /**
         * 真实减速比的 replication 层聚合。
         *
         * <p>减速比语义恒 ≥1.0，无观测运行的 0.0 是非法样本；因此仅聚合
         * {@code trueSlowdownObservationCount > 0} 的运行，全部运行都无观测时
         * 本字段为 {@code null}（而不是用 0.0 污染均值与置信区间）。</p>
         */
        private final MetricSummary meanComputeTrueSlowdown;
        /** 贡献了真实减速比样本的运行数（其余运行无观测，被排除在聚合外）。 */
        private final int trueSlowdownObservationRunCount;
        private final MetricSummary meanVmModeledIntervalUtilization;
        private final MetricSummary vmUtilizationJainFairnessIndex;
        private final WorkflowRunCompletionSummary workflowRunCompletion;
        private final ConditionalMetricSummary successfulWorkflowLogicalCompletionSeconds;

        private CellSummary(String cellId, String comparisonGroup, String candidateId, boolean baseline,
                RandomizationDesign randomizationDesign, List<Long> rootSeeds,
                MetricSummary makespanSeconds, MetricSummary modeledProcessingCost,
                MetricSummary successfulLogicalTaskCompletionRate,
                MetricSummary meanComputeTotalWaitingTimeSeconds,
                MetricSummary meanComputeTrueSlowdown,
                int trueSlowdownObservationRunCount,
                MetricSummary meanVmModeledIntervalUtilization,
                MetricSummary vmUtilizationJainFairnessIndex,
                WorkflowRunCompletionSummary workflowRunCompletion,
                ConditionalMetricSummary successfulWorkflowLogicalCompletionSeconds) {
            this.cellId = cellId;
            this.comparisonGroup = comparisonGroup;
            this.candidateId = candidateId;
            this.baseline = baseline;
            this.randomizationDesign = randomizationDesign;
            this.rootSeeds = Collections.unmodifiableList(new ArrayList<Long>(rootSeeds));
            this.makespanSeconds = makespanSeconds;
            this.modeledProcessingCost = modeledProcessingCost;
            this.successfulLogicalTaskCompletionRate = successfulLogicalTaskCompletionRate;
            this.meanComputeTotalWaitingTimeSeconds = meanComputeTotalWaitingTimeSeconds;
            this.meanComputeTrueSlowdown = meanComputeTrueSlowdown;
            this.trueSlowdownObservationRunCount = trueSlowdownObservationRunCount;
            this.meanVmModeledIntervalUtilization = meanVmModeledIntervalUtilization;
            this.vmUtilizationJainFairnessIndex = vmUtilizationJainFairnessIndex;
            this.workflowRunCompletion = workflowRunCompletion;
            this.successfulWorkflowLogicalCompletionSeconds =
                    successfulWorkflowLogicalCompletionSeconds;
        }

        private static CellSummary from(ExperimentPlan.Cell cell,
                List<ExperimentCampaignResult.Run> runs) {
            List<Long> seeds = new ArrayList<Long>();
            List<Double> makespans = new ArrayList<Double>();
            List<Double> costs = new ArrayList<Double>();
            List<Double> completionRates = new ArrayList<Double>();
            List<Double> meanWaitingTimes = new ArrayList<Double>();
            List<Double> meanTrueSlowdowns = new ArrayList<Double>();
            List<Double> meanUtilizations = new ArrayList<Double>();
            List<Double> jainIndexes = new ArrayList<Double>();
            int eligibleWorkflowRuns = 0;
            int successfullyCompletedWorkflowRuns = 0;
            int incompleteWorkflowRuns = 0;
            int noLogicalTaskRuns = 0;
            List<Double> successfulWorkflowCompletionTimes = new ArrayList<Double>();
            for (ExperimentCampaignResult.Run run : runs) {
                if (run.getCell() != cell) {
                    throw new IllegalArgumentException("Campaign result cell identity is inconsistent");
                }
                seeds.add(Long.valueOf(run.getSeed()));
                makespans.add(Double.valueOf(run.getReport().getMakespan()));
                costs.add(Double.valueOf(run.getReport().getMetrics().getTotalModeledProcessingCost()));
                completionRates.add(Double.valueOf(run.getReport().getMetrics()
                        .getSuccessfulLogicalTaskCompletionRate()));
                SimulationMetrics metrics = run.getReport().getMetrics();
                // PLAT-10：核心性能指标进入 replication 层聚合，campaign 对比不再只有
                // makespan/cost/完成率三个维度。等待/利用率/公平性无观测时的 0.0/1.0
                // 是合法取值（口径与单运行一致）；但真实减速比语义恒 ≥1.0，无观测运行
                // 的 0.0 是非法样本，必须排除而不是填充，否则会系统性拉低 replication
                // 均值并污染置信区间。
                meanWaitingTimes.add(Double.valueOf(
                        metrics.getMeanComputeTotalWaitingTimeSeconds()));
                if (metrics.getTrueSlowdownObservationCount() > 0) {
                    meanTrueSlowdowns.add(Double.valueOf(metrics.getMeanComputeTrueSlowdown()));
                }
                meanUtilizations.add(Double.valueOf(
                        metrics.getMeanVmModeledIntervalUtilization()));
                jainIndexes.add(Double.valueOf(metrics.getVmUtilizationJainFairnessIndex()));
                if (metrics.getLogicalTaskCount() == 0) {
                    noLogicalTaskRuns++;
                    continue;
                }
                eligibleWorkflowRuns++;
                if (metrics.isWorkflowCompletedSuccessfully()) {
                    successfullyCompletedWorkflowRuns++;
                    Double completion = run.getReport().getLogicalTaskCompletionSeconds();
                    if (completion == null || !"COMPLETED_SUCCESSFULLY".equals(
                            run.getReport().getLogicalTaskCompletionStatus())) {
                        throw new IllegalStateException("Successful workflow run has no logical "
                                + "completion-time evidence: " + cell.getId() + "/"
                                + run.getReplicationIndex());
                    }
                    successfulWorkflowCompletionTimes.add(completion);
                } else {
                    incompleteWorkflowRuns++;
                }
            }
            WorkflowRunCompletionSummary workflowRunCompletion =
                    WorkflowRunCompletionSummary.from(runs.size(), eligibleWorkflowRuns,
                            successfullyCompletedWorkflowRuns, incompleteWorkflowRuns,
                            noLogicalTaskRuns, cell.getSeedPlan().getRandomizationDesign());
            ConditionalMetricSummary successfulWorkflowCompletion =
                    ConditionalMetricSummary.from(successfullyCompletedWorkflowRuns,
                            successfulWorkflowCompletionTimes, 0,
                            cell.getSeedPlan().getRandomizationDesign());
            return new CellSummary(cell.getId(), cell.getComparisonGroup(), cell.getCandidateId(),
                    cell.isBaseline(), cell.getSeedPlan().getRandomizationDesign(), seeds,
                    MetricSummary.from(makespans, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(costs, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(completionRates, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(meanWaitingTimes, cell.getSeedPlan().getRandomizationDesign()),
                    meanTrueSlowdowns.isEmpty() ? null : MetricSummary.from(meanTrueSlowdowns,
                            cell.getSeedPlan().getRandomizationDesign()),
                    meanTrueSlowdowns.size(),
                    MetricSummary.from(meanUtilizations, cell.getSeedPlan().getRandomizationDesign()),
                    MetricSummary.from(jainIndexes, cell.getSeedPlan().getRandomizationDesign()),
                    workflowRunCompletion, successfulWorkflowCompletion);
        }

        public String getCellId() { return cellId; }
        public String getComparisonGroup() { return comparisonGroup; }
        public String getCandidateId() { return candidateId; }
        public boolean isBaseline() { return baseline; }
        public RandomizationDesign getRandomizationDesign() { return randomizationDesign; }
        public List<Long> getRootSeeds() { return rootSeeds; }
        public MetricSummary getMakespanSeconds() { return makespanSeconds; }
        public MetricSummary getModeledProcessingCost() { return modeledProcessingCost; }
        public MetricSummary getSuccessfulLogicalTaskCompletionRate() {
            return successfulLogicalTaskCompletionRate;
        }
        /** @return 每次运行平均总等待时间（秒）的 replication 层汇总 */
        public MetricSummary getMeanComputeTotalWaitingTimeSeconds() {
            return meanComputeTotalWaitingTimeSeconds;
        }
        /**
         * 返回每次运行平均真实减速比的 replication 层汇总。
         *
         * <p>仅聚合 {@code trueSlowdownObservationCount > 0} 的运行（减速比语义恒
         * ≥1.0，无观测运行的 0.0 不得混入）。当 cell 内全部运行都无观测时返回
         * {@code null}，调用方应按 {@link #getTrueSlowdownObservationRunCount()}
         * 判断可用性。</p>
         *
         * @return 真实减速比 replication 汇总；无可用观测时为 {@code null}
         */
        public MetricSummary getMeanComputeTrueSlowdown() {
            return meanComputeTrueSlowdown;
        }
        /** @return cell 内贡献了真实减速比样本的运行数（{@code 0..replicationCount}） */
        public int getTrueSlowdownObservationRunCount() {
            return trueSlowdownObservationRunCount;
        }
        /** @return 每次运行平均 VM 利用率的 replication 层汇总 */
        public MetricSummary getMeanVmModeledIntervalUtilization() {
            return meanVmModeledIntervalUtilization;
        }
        /** @return 每次运行 VM 利用率 Jain 公平性指数的 replication 层汇总 */
        public MetricSummary getVmUtilizationJainFairnessIndex() {
            return vmUtilizationJainFairnessIndex;
        }
        /**
         * 返回 replication 层面的完整工作流成功率及其适用的比例区间。
         *
         * <p>它与 {@link #getSuccessfulLogicalTaskCompletionRate()} 的任务级完成比例不同。</p>
         *
         * @return 工作流运行完成汇总
         */
        public WorkflowRunCompletionSummary getWorkflowRunCompletion() {
            return workflowRunCompletion;
        }
        /**
         * 返回仅以完整成功工作流为条件的逻辑完成时间汇总。
         *
         * @return 成功工作流条件完成时间汇总
         */
        public ConditionalMetricSummary getSuccessfulWorkflowLogicalCompletionSeconds() {
            return successfulWorkflowLogicalCompletionSeconds;
        }
    }

    /**
     * 候选方案减去基线方案的 makespan 比较。
     * 在随机抽样尚未按事件键控之前，系统有意不提供配对推断主张。
     */
    public static final class ComparisonSummary {
        private final String comparisonGroup;
        private final String baselineCellId;
        private final String candidateCellId;
        private final int matchedRootSeedCount;
        private final Double meanMatchedMakespanDeltaSeconds;
        private final double baselineMeanMakespanSeconds;
        private final double candidateMeanMakespanSeconds;
        private final Double meanMakespanRatio;
        private final Double baselineRelativeMakespanSpeedup;
        private final Double makespanImprovementPercent;
        private final double baselineMeanModeledProcessingCost;
        private final double candidateMeanModeledProcessingCost;
        private final Double meanModeledProcessingCostRatio;
        private final double successfulLogicalTaskCompletionRateDelta;
        private final Double successfulWorkflowRunRateDelta;
        private final String inferenceStatus;

        private ComparisonSummary(String comparisonGroup, String baselineCellId, String candidateCellId,
                int matchedRootSeedCount, Double meanMatchedMakespanDeltaSeconds,
                double baselineMeanMakespanSeconds, double candidateMeanMakespanSeconds,
                Double meanMakespanRatio, Double baselineRelativeMakespanSpeedup,
                Double makespanImprovementPercent, double baselineMeanModeledProcessingCost,
                double candidateMeanModeledProcessingCost, Double meanModeledProcessingCostRatio,
                double successfulLogicalTaskCompletionRateDelta,
                Double successfulWorkflowRunRateDelta,
                String inferenceStatus) {
            this.comparisonGroup = comparisonGroup;
            this.baselineCellId = baselineCellId;
            this.candidateCellId = candidateCellId;
            this.matchedRootSeedCount = matchedRootSeedCount;
            this.meanMatchedMakespanDeltaSeconds = meanMatchedMakespanDeltaSeconds;
            this.baselineMeanMakespanSeconds = baselineMeanMakespanSeconds;
            this.candidateMeanMakespanSeconds = candidateMeanMakespanSeconds;
            this.meanMakespanRatio = meanMakespanRatio;
            this.baselineRelativeMakespanSpeedup = baselineRelativeMakespanSpeedup;
            this.makespanImprovementPercent = makespanImprovementPercent;
            this.baselineMeanModeledProcessingCost = baselineMeanModeledProcessingCost;
            this.candidateMeanModeledProcessingCost = candidateMeanModeledProcessingCost;
            this.meanModeledProcessingCostRatio = meanModeledProcessingCostRatio;
            this.successfulLogicalTaskCompletionRateDelta = successfulLogicalTaskCompletionRateDelta;
            this.successfulWorkflowRunRateDelta = successfulWorkflowRunRateDelta;
            this.inferenceStatus = inferenceStatus;
        }

        private static ComparisonSummary from(CellSummary baseline, CellSummary candidate) {
            double baselineMakespan = baseline.getMakespanSeconds().getMean();
            double candidateMakespan = candidate.getMakespanSeconds().getMean();
            double baselineCost = baseline.getModeledProcessingCost().getMean();
            double candidateCost = candidate.getModeledProcessingCost().getMean();
            Double makespanRatio = ratio(candidateMakespan, baselineMakespan);
            Double speedup = ratio(baselineMakespan, candidateMakespan);
            Double improvementPercent = baselineMakespan == 0.0 ? null
                    : Double.valueOf(100.0 * (baselineMakespan - candidateMakespan)
                            / baselineMakespan);
            Double costRatio = ratio(candidateCost, baselineCost);
            double completionRateDelta = candidate.getSuccessfulLogicalTaskCompletionRate().getMean()
                    - baseline.getSuccessfulLogicalTaskCompletionRate().getMean();
            Double workflowSuccessRateDelta = difference(
                    candidate.getWorkflowRunCompletion().getSuccessfulWorkflowRunRate(),
                    baseline.getWorkflowRunCompletion().getSuccessfulWorkflowRunRate());
            if (baseline.getRandomizationDesign() == RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ComparisonSummary(baseline.getComparisonGroup(), baseline.getCellId(),
                        candidate.getCellId(), 0, null, baselineMakespan, candidateMakespan,
                        makespanRatio, speedup, improvementPercent, baselineCost, candidateCost,
                        costRatio, completionRateDelta, workflowSuccessRateDelta,
                        "UNPAIRED_INDEPENDENT_REPLICATIONS");
            }
            Map<Long, Double> baselineBySeed = valuesBySeed(baseline);
            Map<Long, Double> candidateBySeed = valuesBySeed(candidate);
            List<Double> deltas = new ArrayList<Double>();
            for (Map.Entry<Long, Double> entry : candidateBySeed.entrySet()) {
                Double reference = baselineBySeed.get(entry.getKey());
                if (reference != null) {
                    deltas.add(Double.valueOf(entry.getValue().doubleValue() - reference.doubleValue()));
                }
            }
            Double meanDelta = deltas.isEmpty() ? null : Double.valueOf(mean(deltas));
            String status = baseline.getRandomizationDesign() == RandomizationDesign.DETERMINISTIC
                    ? "DESCRIPTIVE_ONLY_DETERMINISTIC"
                    : "DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN";
            return new ComparisonSummary(baseline.getComparisonGroup(), baseline.getCellId(),
                    candidate.getCellId(), deltas.size(), meanDelta, baselineMakespan, candidateMakespan,
                    makespanRatio, speedup, improvementPercent, baselineCost, candidateCost,
                    costRatio, completionRateDelta, workflowSuccessRateDelta, status);
        }

        private static Double ratio(double numerator, double denominator) {
            return denominator == 0.0 ? null : Double.valueOf(numerator / denominator);
        }

        private static Double difference(Double candidate, Double baseline) {
            return candidate == null || baseline == null ? null
                    : Double.valueOf(candidate.doubleValue() - baseline.doubleValue());
        }

        private static Map<Long, Double> valuesBySeed(CellSummary summary) {
            Map<Long, Double> values = new HashMap<Long, Double>();
            List<Long> seeds = summary.getRootSeeds();
            List<Double> makespans = summary.getMakespanSeconds().getSamples();
            for (int index = 0; index < seeds.size(); index++) {
                values.put(seeds.get(index), makespans.get(index));
            }
            return values;
        }

        public String getComparisonGroup() { return comparisonGroup; }
        public String getBaselineCellId() { return baselineCellId; }
        public String getCandidateCellId() { return candidateCellId; }
        public int getMatchedRootSeedCount() { return matchedRootSeedCount; }
        public Double getMeanMatchedMakespanDeltaSeconds() { return meanMatchedMakespanDeltaSeconds; }
        public double getBaselineMeanMakespanSeconds() { return baselineMeanMakespanSeconds; }
        public double getCandidateMeanMakespanSeconds() { return candidateMeanMakespanSeconds; }
        /** 候选方案平均 makespan 除以基线平均 makespan；基线为零时返回 {@code null}。 */
        public Double getMeanMakespanRatio() { return meanMakespanRatio; }
        /**
         * 已声明基线的平均 makespan 除以候选方案平均 makespan；候选方案为零时返回
         * {@code null}。该值不是串行加速比。
         */
        public Double getBaselineRelativeMakespanSpeedup() {
            return baselineRelativeMakespanSpeedup;
        }
        /**
         * 相对已声明基线的 makespan 降低百分比；基线 makespan 为零时返回 {@code null}。
         * 此值仅为描述统计，并非效应量估计。
         */
        public Double getMakespanImprovementPercent() {
            return makespanImprovementPercent;
        }
        public double getBaselineMeanModeledProcessingCost() {
            return baselineMeanModeledProcessingCost;
        }
        public double getCandidateMeanModeledProcessingCost() {
            return candidateMeanModeledProcessingCost;
        }
        /** 候选方案平均建模成本除以基线平均成本；基线为零时返回 {@code null}。 */
        public Double getMeanModeledProcessingCostRatio() { return meanModeledProcessingCostRatio; }
        public double getSuccessfulLogicalTaskCompletionRateDelta() {
            return successfulLogicalTaskCompletionRateDelta;
        }
        /**
         * 返回候选方案减去基线方案的 workflow-run 完整成功率。
         *
         * <p>这是描述性差异，不附带两组比例差的显著性或区间推断。</p>
         *
         * @return 两个 cell 都存在可定义 workflow-run 成功率时的差值；否则为 {@code null}
         */
        public Double getSuccessfulWorkflowRunRateDelta() {
            return successfulWorkflowRunRateDelta;
        }
        public String getInferenceStatus() { return inferenceStatus; }
    }

    /**
     * replication 层面的完整逻辑工作流完成汇总。
     *
     * <p>它与每次运行内部的逻辑 Task 完成比例是不同 estimand：这里的一次成功要求该运行中
     * 全部逻辑 Task 都由成功 compute Job 完成。</p>
     */
    public static final class WorkflowRunCompletionSummary {
        private final int declaredRunCount;
        private final int eligibleWorkflowRunCount;
        private final int successfullyCompletedWorkflowRunCount;
        private final int incompleteWorkflowRunCount;
        private final int noLogicalTaskRunCount;
        private final Double successfulWorkflowRunRate;
        private final ProportionConfidenceInterval wilsonConfidenceInterval;

        private WorkflowRunCompletionSummary(int declaredRunCount, int eligibleWorkflowRunCount,
                int successfullyCompletedWorkflowRunCount, int incompleteWorkflowRunCount,
                int noLogicalTaskRunCount, Double successfulWorkflowRunRate,
                ProportionConfidenceInterval wilsonConfidenceInterval) {
            this.declaredRunCount = declaredRunCount;
            this.eligibleWorkflowRunCount = eligibleWorkflowRunCount;
            this.successfullyCompletedWorkflowRunCount = successfullyCompletedWorkflowRunCount;
            this.incompleteWorkflowRunCount = incompleteWorkflowRunCount;
            this.noLogicalTaskRunCount = noLogicalTaskRunCount;
            this.successfulWorkflowRunRate = successfulWorkflowRunRate;
            this.wilsonConfidenceInterval = wilsonConfidenceInterval;
        }

        static WorkflowRunCompletionSummary from(int declaredRunCount, int eligibleWorkflowRunCount,
                int successfullyCompletedWorkflowRunCount, int incompleteWorkflowRunCount,
                int noLogicalTaskRunCount, RandomizationDesign design) {
            if (declaredRunCount < 0 || eligibleWorkflowRunCount < 0
                    || successfullyCompletedWorkflowRunCount < 0 || incompleteWorkflowRunCount < 0
                    || noLogicalTaskRunCount < 0
                    || declaredRunCount != eligibleWorkflowRunCount + noLogicalTaskRunCount
                    || eligibleWorkflowRunCount != successfullyCompletedWorkflowRunCount
                            + incompleteWorkflowRunCount) {
                throw new IllegalArgumentException("Workflow-run completion counts are inconsistent");
            }
            Double rate = eligibleWorkflowRunCount == 0 ? null : Double.valueOf(
                    ((double) successfullyCompletedWorkflowRunCount) / eligibleWorkflowRunCount);
            return new WorkflowRunCompletionSummary(declaredRunCount, eligibleWorkflowRunCount,
                    successfullyCompletedWorkflowRunCount, incompleteWorkflowRunCount,
                    noLogicalTaskRunCount, rate, ProportionConfidenceInterval.forWilson(
                            successfullyCompletedWorkflowRunCount, eligibleWorkflowRunCount, design));
        }

        public int getDeclaredRunCount() { return declaredRunCount; }
        /** @return 具有至少一个逻辑 Task、可定义成功/未完成结果的运行数 */
        public int getEligibleWorkflowRunCount() { return eligibleWorkflowRunCount; }
        public int getSuccessfullyCompletedWorkflowRunCount() {
            return successfullyCompletedWorkflowRunCount;
        }
        public int getIncompleteWorkflowRunCount() { return incompleteWorkflowRunCount; }
        /** @return 没有可定义逻辑工作流的运行数，不计为成功或失败 */
        public int getNoLogicalTaskRunCount() { return noLogicalTaskRunCount; }
        /**
         * 返回模型运行层面的完整工作流成功率。
         *
         * @return 成功数除以 eligible 运行数；没有 eligible 运行时为 {@code null}
         */
        public Double getSuccessfulWorkflowRunRate() { return successfulWorkflowRunRate; }
        /** @return 仅在独立重复设计下可作推断解释的 Wilson score 区间 */
        public ProportionConfidenceInterval getWilsonConfidenceInterval() {
            return wilsonConfidenceInterval;
        }
    }

    /** 只对满足明确条件的运行样本进行汇总，避免用失败运行的仿真结束时间填补。 */
    public static final class ConditionalMetricSummary {
        private final String population;
        private final int successfulWorkflowRunCount;
        private final int completionTimeObservationCount;
        private final int unavailableCompletionTimeCount;
        private final MetricSummary summary;
        private final String status;

        private ConditionalMetricSummary(String population, int successfulWorkflowRunCount,
                int completionTimeObservationCount, int unavailableCompletionTimeCount,
                MetricSummary summary, String status) {
            this.population = population;
            this.successfulWorkflowRunCount = successfulWorkflowRunCount;
            this.completionTimeObservationCount = completionTimeObservationCount;
            this.unavailableCompletionTimeCount = unavailableCompletionTimeCount;
            this.summary = summary;
            this.status = status;
        }

        static ConditionalMetricSummary from(int successfulWorkflowRunCount,
                List<Double> completionTimes, int unavailableCompletionTimeCount,
                RandomizationDesign design) {
            if (successfulWorkflowRunCount < 0 || unavailableCompletionTimeCount < 0
                    || completionTimes == null
                    || successfulWorkflowRunCount != completionTimes.size()
                            + unavailableCompletionTimeCount) {
                throw new IllegalArgumentException("Successful workflow completion-time evidence is inconsistent");
            }
            if (unavailableCompletionTimeCount != 0) {
                throw new IllegalStateException("Successful workflow runs cannot lack logical completion time");
            }
            if (completionTimes.isEmpty()) {
                return new ConditionalMetricSummary("SUCCESSFUL_WORKFLOW_RUNS_ONLY",
                        successfulWorkflowRunCount, 0, 0, null,
                        "UNAVAILABLE_NO_SUCCESSFUL_WORKFLOW_RUNS");
            }
            return new ConditionalMetricSummary("SUCCESSFUL_WORKFLOW_RUNS_ONLY",
                    successfulWorkflowRunCount, completionTimes.size(), 0,
                    MetricSummary.from(completionTimes, design),
                    "AVAILABLE_SUCCESSFUL_WORKFLOW_RUNS_ONLY");
        }

        /** @return 固定为 {@code SUCCESSFUL_WORKFLOW_RUNS_ONLY} */
        public String getPopulation() { return population; }
        public int getSuccessfulWorkflowRunCount() { return successfulWorkflowRunCount; }
        public int getCompletionTimeObservationCount() { return completionTimeObservationCount; }
        public int getUnavailableCompletionTimeCount() { return unavailableCompletionTimeCount; }
        /** @return 条件样本汇总；没有成功工作流运行时为 {@code null} */
        public MetricSummary getSummary() { return summary; }
        public String getStatus() { return status; }
    }

    /** workflow-run 二元成功率的 Wilson score 区间，或其不可用原因。 */
    public static final class ProportionConfidenceInterval {
        private final boolean available;
        private final double confidenceLevel;
        private final Double lower;
        private final Double upper;
        private final String status;
        private final String method;

        private ProportionConfidenceInterval(boolean available, Double lower, Double upper,
                String status) {
            this.available = available;
            this.confidenceLevel = CONFIDENCE_LEVEL;
            this.lower = lower;
            this.upper = upper;
            this.status = status;
            this.method = "WILSON_SCORE";
        }

        static ProportionConfidenceInterval forWilson(int successes, int observations,
                RandomizationDesign design) {
            if (successes < 0 || observations < 0 || successes > observations) {
                throw new IllegalArgumentException("Wilson interval success counts are inconsistent");
            }
            if (observations == 0) {
                return new ProportionConfidenceInterval(false, null, null,
                        "UNAVAILABLE_NO_ELIGIBLE_WORKFLOW_RUNS");
            }
            if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ProportionConfidenceInterval(false, null, null,
                        "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS");
            }
            double proportion = ((double) successes) / observations;
            double z = new NormalDistribution().inverseCumulativeProbability(
                    0.5 + CONFIDENCE_LEVEL / 2.0);
            double zSquared = z * z;
            double denominator = 1.0 + zSquared / observations;
            double center = (proportion + zSquared / (2.0 * observations)) / denominator;
            double halfWidth = z * Math.sqrt((proportion * (1.0 - proportion)
                    + zSquared / (4.0 * observations)) / observations) / denominator;
            return new ProportionConfidenceInterval(true,
                    Double.valueOf(Math.max(0.0, center - halfWidth)),
                    Double.valueOf(Math.min(1.0, center + halfWidth)),
                    "AVAILABLE_WILSON_SCORE_PROPORTION_INTERVAL");
        }

        public boolean isAvailable() { return available; }
        public double getConfidenceLevel() { return confidenceLevel; }
        public Double getLower() { return lower; }
        public Double getUpper() { return upper; }
        public String getStatus() { return status; }
        public String getMethod() { return method; }
    }

    /** 样本汇总及其适用条件被明确标注的 95% 均值置信区间。 */
    public static final class MetricSummary {
        private final List<Double> samples;
        private final int sampleSize;
        private final double mean;
        private final double sampleStandardDeviation;
        private final double minimum;
        private final double maximum;
        private final ConfidenceInterval meanConfidenceInterval;

        private MetricSummary(List<Double> samples, double mean, double sampleStandardDeviation,
                double minimum, double maximum, ConfidenceInterval meanConfidenceInterval) {
            this.samples = Collections.unmodifiableList(new ArrayList<Double>(samples));
            this.sampleSize = samples.size();
            this.mean = mean;
            this.sampleStandardDeviation = sampleStandardDeviation;
            this.minimum = minimum;
            this.maximum = maximum;
            this.meanConfidenceInterval = meanConfidenceInterval;
        }

        static MetricSummary from(List<Double> values, RandomizationDesign design) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("A metric summary requires at least one sample");
            }
            double sum = 0.0;
            double minimum = Double.POSITIVE_INFINITY;
            double maximum = Double.NEGATIVE_INFINITY;
            for (Double boxed : values) {
                if (boxed == null || Double.isNaN(boxed.doubleValue())
                        || Double.isInfinite(boxed.doubleValue())) {
                    throw new IllegalArgumentException("Metric samples must be finite");
                }
                sum += boxed.doubleValue();
                minimum = Math.min(minimum, boxed.doubleValue());
                maximum = Math.max(maximum, boxed.doubleValue());
            }
            double mean = sum / values.size();
            double squaredDifferenceSum = 0.0;
            for (Double value : values) {
                double difference = value.doubleValue() - mean;
                squaredDifferenceSum += difference * difference;
            }
            double standardDeviation = values.size() < 2 ? 0.0
                    : Math.sqrt(squaredDifferenceSum / (values.size() - 1));
            ConfidenceInterval interval = ConfidenceInterval.forMean(values.size(), mean,
                    standardDeviation, design);
            return new MetricSummary(values, mean, standardDeviation, minimum, maximum, interval);
        }

        public List<Double> getSamples() { return samples; }
        public int getSampleSize() { return sampleSize; }
        public double getMean() { return mean; }
        public double getSampleStandardDeviation() { return sampleStandardDeviation; }
        public double getMinimum() { return minimum; }
        public double getMaximum() { return maximum; }
        public ConfidenceInterval getMeanConfidenceInterval() { return meanConfidenceInterval; }
    }

    /** 均值置信区间，或说明其不可用原因的机器可读状态。 */
    public static final class ConfidenceInterval {
        private final boolean available;
        private final double confidenceLevel;
        private final Double lower;
        private final Double upper;
        private final String status;

        private ConfidenceInterval(boolean available, Double lower, Double upper, String status) {
            this.available = available;
            this.confidenceLevel = CONFIDENCE_LEVEL;
            this.lower = lower;
            this.upper = upper;
            this.status = status;
        }

        private static ConfidenceInterval forMean(int sampleSize, double mean, double standardDeviation,
                RandomizationDesign design) {
            if (design != RandomizationDesign.INDEPENDENT_REPLICATIONS) {
                return new ConfidenceInterval(false, null, null,
                        "UNAVAILABLE_RANDOMIZATION_DESIGN_IS_NOT_INDEPENDENT_REPLICATIONS");
            }
            if (sampleSize < 2) {
                return new ConfidenceInterval(false, null, null,
                        "UNAVAILABLE_FEWER_THAN_TWO_REPLICATIONS");
            }
            double critical = new TDistribution(sampleSize - 1)
                    .inverseCumulativeProbability(0.5 + CONFIDENCE_LEVEL / 2.0);
            double margin = critical * standardDeviation / Math.sqrt(sampleSize);
            return new ConfidenceInterval(true, Double.valueOf(mean - margin),
                    Double.valueOf(mean + margin), "AVAILABLE_STUDENT_T_MEAN_INTERVAL");
        }

        public boolean isAvailable() { return available; }
        public double getConfidenceLevel() { return confidenceLevel; }
        public Double getLower() { return lower; }
        public Double getUpper() { return upper; }
        public String getStatus() { return status; }
    }

    private static double mean(List<Double> values) {
        double total = 0.0;
        for (Double value : values) {
            total += value.doubleValue();
        }
        return total / values.size();
    }
}
