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
        if (args.length != 3) { throw new IllegalArgumentException("Usage: NetworkStudyExecutor <smoke|full> <datasets-root> <new-output-directory>"); }
        Path result = execute(args[0], Paths.get(args[1]), Paths.get(args[2]));
        System.out.println("NETWORK_STUDY PASSED " + result);
    }

    public static Path execute(String mode, Path datasets, Path directory) throws Exception {
        Path root = directory.toAbsolutePath().normalize();
        if (Files.exists(root)) { throw new IOException("Study output already exists: " + root); }
        Files.createDirectories(root);
        NetworkStudyPlan plan = NetworkStudyPlan.create(mode, datasets.toAbsolutePath().normalize(), root.resolve("inputs"));
        Path protocol = root.resolve("protocol.json");
        ExperimentArtifactWriter.writeJson(protocol, plan.asMap());
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(NetworkStudyPlan.PROTOCOL)
                .artifact("org.workflowsim", "network-study", "1.0")
                .driver(NetworkStudyExecutor.class, "org/workflowsim/experiments/network")
                .protocol(NetworkStudyPlan.PROTOCOL, protocol)
                .datasetRoot(datasets.toAbsolutePath().normalize()).build();
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        boolean disabled = Log.isDisabled();
        Log.disable();
        try {
            for (NetworkStudyPlan.WorkflowCase workflow : plan.getWorkflows()) {
                for (int vmCount : plan.getVmCounts()) {
                    for (String network : NetworkStudyPlan.NETWORKS) {
                        for (Parameters.PlanningAlgorithm planner : NetworkStudyPlan.PLANNERS) {
                            for (long seed : plan.seeds(planner)) {
                                String runId = workflow.id + "-v" + vmCount + "-" + network + "-" + planner + "-s" + seed;
                                Map<String, Object> record = new LinkedHashMap<String, Object>();
                                record.put("runId", runId); record.put("workflowId", workflow.id);
                                record.put("family", workflow.family); record.put("population", workflow.population);
                                record.put("vmCount", vmCount); record.put("network", network);
                                record.put("planner", planner.name()); record.put("seed", seed);
                                try {
                                    SimulationConfig config = SimulationConfig.builder(workflow.path.toString(), vmCount)
                                            .planningAlgorithm(planner).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                                            .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(seed)
                                            .dataMovementModel(NetworkStudyPlan.movement(network)).build();
                                    SimulationReport report = new SimulationRunner().run(config, NetworkStudyPlan.platform(vmCount, network));
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
        StringBuilder out = new StringBuilder("# 网络受限调度研究 R10\n\n");
        out.append("协议：").append(NetworkStudyPlan.PROTOCOL).append("；计划 ").append(plan.getRunCount())
                .append(" 次；已执行 ").append(summary.get("runCount")).append(" 次；状态 ").append(summary.get("status")).append("。\n\n")
                .append("HEFT/CPOP每个条件只运行一次；RANDOM/PSO使用声明的独立种子。先在每个DAG内汇总种子，再按DAG配对。经典DAX与合成负载分开，固定样本的探索性结论不能外推真实云。改善百分比为正表示候选比HEFT更快。\n\n")
                .append("| 来源 | VM | 网络 | 候选 | DAG数 | 胜/平/负 | 中位改善% | 原始p | Holm p |\n|---|---:|---|---|---:|---|---:|---:|---:|\n");
        for (Map<String, Object> row : (List<Map<String, Object>>) summary.get("comparisons")) {
            out.append("| ").append(row.get("population")).append(" | ").append(row.get("vmCount"))
                    .append(" | ").append(row.get("network")).append(" | ").append(row.get("candidate"))
                    .append(" | ").append(row.get("dagPairs")).append(" | ").append(row.get("wins")).append('/')
                    .append(row.get("ties")).append('/').append(row.get("losses")).append(" | ")
                    .append(format(row.get("medianImprovementPercent"))).append(" | ").append(format(row.get("pValue")))
                    .append(" | ").append(format(row.get("holmAdjustedPValue"))).append(" |\n");
        }
        out.append("\n精确双侧符号检验，不把多个seed当作多个DAG。Holm按同来源/VM/网络的三候选比较族调整。小样本显著性能力有限，应同时看效果幅度和逐DAG结果。链路为0.125/1.25 MB/s，端点1 MB/s，VM1000 MIPS，均为模型假设；三个网络变体均采用就绪时开始传输，避免混入不同传输起点。\n");
        return out.toString();
    }
    private static String format(Object value) {
        return value == null ? "不可用" : String.format(java.util.Locale.ROOT, "%.6f", ((Number) value).doubleValue());
    }
}
