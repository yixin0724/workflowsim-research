package org.workflowsim.experiments.network;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Streams an explicit study to independent v4 evidence bundles, then validates its summary. */
public final class NetworkStudyExecutor {
    public static final String INDEX = "network-study.json";
    private NetworkStudyExecutor() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: NetworkStudyExecutor <smoke|full> <datasets-root> <new-output-directory> "
                            + "[r10|peft-comparison|sensitivity-r13]");
        }
        NetworkStudyPlan.StudyVariant variant = NetworkStudyPlan.variantArgument(args.length == 4 ? args[3] : null);
        Path result = execute(args[0], variant, Paths.get(args[1]), Paths.get(args[2]));
        System.out.println("NETWORK_STUDY PASSED " + result);
    }

    public static Path execute(String mode, Path datasets, Path directory) throws Exception {
        return execute(mode, NetworkStudyPlan.StudyVariant.R10, datasets, directory);
    }

    public static Path execute(String mode, boolean peftComparison, Path datasets, Path directory) throws Exception {
        return execute(mode, peftComparison ? NetworkStudyPlan.StudyVariant.PEFT_COMPARISON
                : NetworkStudyPlan.StudyVariant.R10, datasets, directory);
    }

    public static Path execute(String mode, NetworkStudyPlan.StudyVariant variant, Path datasets, Path directory)
            throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        if (Files.exists(root)) { throw new IOException("Study output already exists: " + root); }
        Files.createDirectories(root);
        NetworkStudyPlan plan = NetworkStudyPlan.create(mode, variant, datasets.toAbsolutePath().normalize(),
                root.resolve("inputs"));
        Path protocol = root.resolve("protocol.json");
        ExperimentArtifactWriter.writeJson(protocol, plan.asMap());
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(plan.getProtocol())
                .artifact("org.workflowsim", "network-study", "1.0")
                .driver(NetworkStudyExecutor.class, "org/workflowsim/experiments/network")
                .protocol(plan.getProtocol(), protocol)
                .datasetRoot(datasets.toAbsolutePath().normalize()).build();
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        boolean disabled = Log.isDisabled();
        Log.disable();
        try {
            boolean heterogeneityAxis = NetworkStudyPlan.SENSITIVITY_PROTOCOL.equals(plan.getProtocol());
            for (NetworkStudyPlan.WorkflowCase workflow : plan.getWorkflows()) {
                for (NetworkStudyPlan.Condition condition : plan.getConditions()) {
                    int vmCount = condition.vmCount;
                    String network = condition.network;
                    for (Parameters.PlanningAlgorithm planner : plan.getPlanners()) {
                        for (long seed : plan.seeds(planner)) {
                            String runId = workflow.id + "-v" + vmCount + "-" + network
                                    + (heterogeneityAxis ? "-" + condition.heterogeneity : "")
                                    + "-" + planner + "-s" + seed;
                            Map<String, Object> record = new LinkedHashMap<String, Object>();
                            record.put("runId", runId); record.put("workflowId", workflow.id);
                            record.put("family", workflow.family); record.put("population", workflow.population);
                            record.put("vmCount", vmCount); record.put("network", network);
                            if (heterogeneityAxis) { record.put("heterogeneity", condition.heterogeneity); }
                            record.put("planner", planner.name()); record.put("seed", seed);
                            try {
                                SimulationConfig config = SimulationConfig.builder(workflow.path.toString(), vmCount)
                                        .planningAlgorithm(planner).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(seed)
                                        .dataMovementModel(NetworkStudyPlan.movement(network)).build();
                                SimulationReport report = new SimulationRunner().run(config,
                                        NetworkStudyPlan.platform(vmCount, network, condition.heterogeneity));
                                if (!report.isWorkflowCompletedSuccessfully()) { throw new IllegalStateException("Incomplete workflow"); }
                                if (!workflow.sha256.equals(report.getInputs().get(0).getSha256())) {
                                    throw new IllegalStateException("Study input changed after protocol was frozen");
                                }
                                ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                                        report, root.resolve("runs").resolve(runId), "result", context);
                                ExperimentArtifactValidator.validate(artifacts.getManifest());
                                record.put("status", "COMPLETED_SUCCESSFULLY");
                                record.put("manifest", root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
                                record.put("inputSha256", report.getInputs().get(0).getSha256());
                                record.put("taskCount", report.getWorkflowProfile().getTaskCount());
                                record.put("makespanSeconds", report.getMakespan());
                                record.put("logicalCompletionSeconds", report.getLogicalTaskCompletionSeconds());
                                record.put("meanWaitingSeconds", report.getMetrics().getMeanComputeTotalWaitingTimeSeconds());
                                record.put("p95WaitingSeconds", report.getMetrics().getP95ComputeTotalWaitingTimeSeconds());
                                record.put("meanVmUtilization", report.getMetrics().getMeanVmModeledIntervalUtilization());
                            } catch (Exception failure) {
                                record.put("status", "FAILED");
                                record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                            }
                            records.add(record);
                        }
                    }
                }
                System.out.println("[network-study] " + workflow.id + ": " + records.size() + "/" + plan.getRunCount());
                writeIndex(root, plan, records);
            }
        } finally {
            Log.setDisabled(disabled);
            writeIndex(root, plan, records);
        }
        NetworkStudyValidator.validate(root.resolve(INDEX));
        return root.resolve(INDEX);
    }

    private static void writeIndex(Path root, NetworkStudyPlan plan, List<Map<String, Object>> records) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-network-study-v1");
        index.put("plan", plan.asMap()); index.put("runs", records);
        Map<String, Object> summary = NetworkStudySummary.summarize(records);
        if (records.size() != plan.getRunCount()) { summary.put("status", "RUNNING_OR_INTERRUPTED"); }
        index.put("summary", summary);
        ExperimentArtifactWriter.writeJson(root.resolve(INDEX), index);
        Files.write(root.resolve("results.md"), markdown(plan, summary).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static String markdown(NetworkStudyPlan plan, Map<String, Object> summary) {
        boolean peft = NetworkStudyPlan.PEFT_COMPARISON_PROTOCOL.equals(plan.getProtocol());
        boolean sensitivity = NetworkStudyPlan.SENSITIVITY_PROTOCOL.equals(plan.getProtocol());
        StringBuilder out = new StringBuilder(sensitivity
                ? "# 敏感性响应面研究（R13）\n\n"
                : peft ? "# PEFT 对比研究（S5，r10 冻结矩阵）\n\n" : "# 网络受限调度研究 R10\n\n");
        out.append("协议：").append(plan.getProtocol()).append("；计划 ").append(plan.getRunCount())
                .append(" 次；已执行 ").append(summary.get("runCount")).append(" 次；状态 ").append(summary.get("status")).append("。\n\n")
                .append(sensitivity
                        ? "三个列表规划器（HEFT/CPOP/PEFT）均确定性，每条件只运行一次（seed 11）；基线为LOCAL_HEFT，候选为LOCAL_CPOP/LOCAL_PEFT。响应面沿VM数（4/8/16/32）×链路带宽（端点1 MB/s；fat-tree 0.125/0.5/1.25/5 MB/s）展开，异构块在fat-tree-constrained上加入MILD/STRONG/EXTREME三种确定性VM MIPS混合。VM=32时fat-tree取k=8（k=4仅容纳16台主机），跨VM数的比较受此影响，仅作描述性解读。机制检验（预登记）：S5在同构VM上发现PEFT的OCT前瞻项退化、与HEFT大量精确打平；本研究预测该差异只在异构VM条件下被激活。所有曲面均为描述性，不做参数拟合。经典DAX与合成负载分开，固定样本的探索性结论不能外推真实云。改善百分比为正表示候选比HEFT更快。\n\n"
                        : peft
                        ? "三个列表规划器（HEFT/CPOP/PEFT）均确定性，每条件只运行一次（seed 11）；基线为LOCAL_HEFT，候选为LOCAL_CPOP/LOCAL_PEFT。先在每个DAG内汇总种子，再按DAG配对。经典DAX与合成负载分开，固定样本的探索性结论不能外推真实云。改善百分比为正表示候选比HEFT更快。平台VM同构（1000 MIPS），PEFT的OCT前瞻项在各VM上相同，与HEFT的差异只来自任务排序。\n\n"
                        : "HEFT/CPOP每个条件只运行一次；RANDOM/PSO使用声明的独立种子。先在每个DAG内汇总种子，再按DAG配对。经典DAX与合成负载分开，固定样本的探索性结论不能外推真实云。改善百分比为正表示候选比HEFT更快。\n\n")
                .append(sensitivity
                        ? "| 来源 | VM | 网络 | 异构度 | 候选 | DAG数 | 胜/平/负 | 中位改善% | 原始p | Holm p |\n|---|---:|---|---|---|---:|---|---:|---:|---:|\n"
                        : "| 来源 | VM | 网络 | 候选 | DAG数 | 胜/平/负 | 中位改善% | 原始p | Holm p |\n|---|---:|---|---|---:|---|---:|---:|---:|\n");
        for (Map<String, Object> row : (List<Map<String, Object>>) summary.get("comparisons")) {
            out.append("| ").append(row.get("population")).append(" | ").append(row.get("vmCount"))
                    .append(" | ").append(row.get("network"));
            if (sensitivity) { out.append(" | ").append(row.get("heterogeneity")); }
            out.append(" | ").append(row.get("candidate"))
                    .append(" | ").append(row.get("dagPairs")).append(" | ").append(row.get("wins")).append('/')
                    .append(row.get("ties")).append('/').append(row.get("losses")).append(" | ")
                    .append(format(row.get("medianImprovementPercent"))).append(" | ").append(format(row.get("pValue")))
                    .append(" | ").append(format(row.get("holmAdjustedPValue"))).append(" |\n");
        }
        out.append(sensitivity
                ? "\n精确双侧符号检验。Holm按同来源/VM/网络/异构度的两候选比较族调整。小样本显著性能力有限，应同时看效果幅度和逐DAG结果。端点1 MB/s，fat-tree链路0.125/0.5/1.25/5 MB/s，同构VM 1000 MIPS、异构按声明的MIPS模式，均为模型假设；所有网络变体均采用就绪时开始传输，避免混入不同传输起点。\n"
                : peft
                ? "\n精确双侧符号检验。Holm按同来源/VM/网络的两候选比较族调整。小样本显著性能力有限，应同时看效果幅度和逐DAG结果。链路为0.125/1.25 MB/s，端点1 MB/s，VM1000 MIPS，均为模型假设；三个网络变体均采用就绪时开始传输，避免混入不同传输起点。\n"
                : "\n精确双侧符号检验，不把多个seed当作多个DAG。Holm按同来源/VM/网络的三候选比较族调整。小样本显著性能力有限，应同时看效果幅度和逐DAG结果。链路为0.125/1.25 MB/s，端点1 MB/s，VM1000 MIPS，均为模型假设；三个网络变体均采用就绪时开始传输，避免混入不同传输起点。\n");
        return out.toString();
    }
    private static String format(Object value) {
        return value == null ? "不可用" : String.format(java.util.Locale.ROOT, "%.6f", ((Number) value).doubleValue());
    }
}
