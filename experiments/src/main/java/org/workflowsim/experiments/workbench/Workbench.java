package org.workflowsim.experiments.workbench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;

/** One entry point for validating/running experiments, evidence reports and recoverable history. */
public final class Workbench {
    private Workbench() { }
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { throw new IllegalArgumentException("Usage: Workbench validate <config> | run <config> <output-root> | history <output-root> | report <manifest> <new-report.html>"); }
        switch (args[0]) {
            case "validate":
                requireLength(args, 2); WorkbenchConfig config = WorkbenchConfig.read(Paths.get(args[1]));
                config.validateInputs(); System.out.println("WORKBENCH_VALIDATED runs=" + config.getRunCount()); return;
            case "run":
                requireLength(args, 3); Path result = run(Paths.get(args[1]), Paths.get(args[2]));
                System.out.println("WORKBENCH_REPORT " + result.resolve("report.html"));
                if (!"COMPLETED_SUCCESSFULLY".equals(read(result.resolve("experiment.json")).get("status").getAsString())) {
                    throw new IllegalStateException("Experiment contains failed runs; see its report and history");
                }
                return;
            case "history":
                requireLength(args, 2); System.out.println("WORKBENCH_HISTORY " + history(Paths.get(args[1]))); return;
            case "report":
                requireLength(args, 3); Path target = Paths.get(args[2]);
                if (Files.exists(target)) { throw new IOException("Report output already exists: " + target); }
                JsonObject manifest = HtmlReports.validatedManifest(Paths.get(args[1]));
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("candidate", manifest.getAsJsonObject("configuration").get("planningAlgorithm").getAsString());
                row.put("seed", manifest.getAsJsonObject("configuration").get("rootSeed").getAsLong());
                row.put("status", manifest.getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString());
                row.put("manifest", manifest);
                HtmlReports.experiment("单次仿真实验", java.util.Collections.singletonList(row), target); return;
            default: throw new IllegalArgumentException("Unknown command: " + args[0]);
        }
    }

    public static Path run(Path configPath, Path outputRoot) throws Exception {
        WorkbenchConfig config = WorkbenchConfig.read(configPath);
        config.validateInputs();
        Path root = outputRoot.toAbsolutePath().normalize(); Files.createDirectories(root);
        String id = "experiment-" + UUID.randomUUID().toString();
        Path directory = root.resolve(id); Files.createDirectory(directory);
        ExperimentArtifactWriter.writeJson(directory.resolve("configuration.json"), config.getSource());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> display = new ArrayList<Map<String, Object>>();
        Map<String, Object> experiment = new LinkedHashMap<String, Object>();
        experiment.put("schema", "workflowsim-workbench-experiment-v1");
        experiment.put("id", id); experiment.put("name", config.getName());
        experiment.put("startedAt", Instant.now().toString()); experiment.put("plannedRuns", config.getRunCount());
        experiment.put("status", "RUNNING"); experiment.put("runs", rows);
        ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
        boolean disabled = Log.isDisabled(); Log.disable();
        try {
            Path datasetRoot = configPath.toAbsolutePath().getParent();
            ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("workbench-experiment")
                    .artifact("org.workflowsim", "workbench", "1.0")
                    .driver(Workbench.class, "org/workflowsim/experiments/workbench")
                    .protocol("workbench-configuration-v1", directory.resolve("configuration.json"))
                    .datasetRoot(datasetRoot).build();
            for (WorkbenchConfig.Candidate candidate : config.getCandidates()) {
                for (long seed : config.getSeeds()) {
                    String runId = candidate.id + "-s" + seed;
                    Map<String, Object> row = new LinkedHashMap<String, Object>();
                    row.put("candidate", candidate.id); row.put("seed", seed);
                    Map<String, Object> reportRow = new LinkedHashMap<String, Object>(row);
                    try {
                        SimulationReport report = new SimulationRunner().run(candidate.config.withRandomSeed(seed), config.getPlatform());
                        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                                report, directory.resolve("runs").resolve(runId), "result", context);
                        JsonObject manifest = HtmlReports.validatedManifest(artifacts.getManifest());
                        String status = report.isWorkflowCompletedSuccessfully() ? "COMPLETED_SUCCESSFULLY" : "INCOMPLETE";
                        row.put("status", status); row.put("manifest", directory.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
                        row.put("makespanSeconds", report.getMakespan());
                        reportRow.put("status", status); reportRow.put("manifest", manifest);
                    } catch (Exception failure) {
                        String message = message(failure);
                        row.put("status", "FAILED"); row.put("error", message);
                        reportRow.put("status", "FAILED"); reportRow.put("error", message);
                    }
                    rows.add(row); display.add(reportRow);
                    ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
                }
            }
        } finally {
            Log.setDisabled(disabled);
            int successful = 0;
            for (Map<String, Object> row : rows) { if ("COMPLETED_SUCCESSFULLY".equals(row.get("status"))) { successful++; } }
            experiment.put("successfulRuns", successful);
            experiment.put("status", rows.size() == config.getRunCount() && successful == rows.size()
                    ? "COMPLETED_SUCCESSFULLY" : "FAILED_OR_INCOMPLETE");
            experiment.put("finishedAt", Instant.now().toString());
            ExperimentArtifactWriter.writeJson(directory.resolve("experiment.json"), experiment);
            HtmlReports.experiment(config.getName(), display, directory.resolve("report.html"));
            history(root);
        }
        return directory;
    }

    public static Path history(Path rootPath) throws IOException {
        Path root = rootPath.toAbsolutePath().normalize(); Files.createDirectories(root);
        List<Path> directories = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(p -> !Files.isSymbolicLink(p) && Files.isDirectory(p) && p.getFileName().toString().startsWith("experiment-"))
                    .sorted(Comparator.comparing(Path::toString)).forEach(directories::add);
        }
        List<Map<String, Object>> records = new ArrayList<Map<String, Object>>();
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>WorkflowSim 实验历史</title><style>body{font:16px/1.7 system-ui;background:#f3f6f8;color:#142b40;max-width:1100px;margin:45px auto;padding:20px}table{background:white;width:100%;border-collapse:collapse}td,th{padding:15px;text-align:left;border-bottom:1px solid #dce5ea}a{color:#087f8c}.hint{color:#536b7c}</style><h1>实验历史</h1><p class=\"hint\">每次实验独立保留。运行中状态也可能表示进程被中断，请检查该实验记录。</p><table><tr><th>实验</th><th>状态</th><th>成功 / 计划</th><th>开始时间</th></tr>");
        for (Path directory : directories) {
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            String relative = directory.getFileName().toString();
            try {
                JsonObject experiment = read(directory.resolve("experiment.json"));
                String name = experiment.get("name").getAsString();
                String status = experiment.get("status").getAsString();
                String startedAt = experiment.get("startedAt").getAsString();
                int plannedRuns = experiment.get("plannedRuns").getAsInt();
                record.put("directory", relative); record.put("name", name); record.put("status", status);
                int successful = experiment.has("successfulRuns") ? experiment.get("successfulRuns").getAsInt() : 0;
                record.put("successfulRuns", successful); record.put("plannedRuns", experiment.get("plannedRuns").getAsInt());
                html.append("<tr><td>");
                if (Files.isRegularFile(directory.resolve("report.html"))) { html.append("<a href=\"").append(HtmlReports.escape(relative)).append("/report.html\">").append(HtmlReports.escape(name)).append("</a>"); }
                else { html.append(HtmlReports.escape(name)); }
                html.append("</td><td>").append(HtmlReports.escape(status)).append("</td><td>").append(successful).append(" / ")
                        .append(plannedRuns).append("</td><td>").append(HtmlReports.escape(startedAt)).append("</td></tr>");
            } catch (RuntimeException | IOException invalid) {
                record.put("directory", relative); record.put("status", "INVALID_RECORD");
                record.put("error", invalid.getMessage());
                html.append("<tr><td>").append(HtmlReports.escape(relative)).append("</td><td>记录损坏</td><td>—</td><td>—</td></tr>");
            }
            records.add(record);
        }
        ExperimentArtifactWriter.writeJson(root.resolve("history.json"), records);
        HtmlReports.writeAtomic(root.resolve("index.html"), html.append("</table></html>").toString());
        return root.resolve("index.html");
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static void requireLength(String[] args, int length) {
        if (args.length != length) { throw new IllegalArgumentException("Incorrect argument count for " + args[0]); }
    }
    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) { cause = cause.getCause(); }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
