package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.experiment.ExperimentArtifactWriter;

/** Generate small, self-contained browser fixtures using the real public Workbench entry points. */
public final class WorkbenchBrowserFixtures {
    private WorkbenchBrowserFixtures() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) { throw new IllegalArgumentException("Usage: WorkbenchBrowserFixtures <checkout> <new-output>"); }
        Path checkout = Paths.get(args[0]).toAbsolutePath().normalize();
        Path output = Paths.get(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) { throw new IllegalArgumentException("Refusing existing browser-fixture output: " + output); }
        Files.createDirectories(output);
        List<Map<String, Object>> reports = new ArrayList<>();

        Path online = Workbench.run(checkout.resolve("experiments/configs/online-comparison.json"), output.resolve("online"));
        reports.add(describe("online", online));
        Path network = Workbench.run(checkout.resolve("experiments/configs/network-comparison.json"), output.resolve("network"));
        reports.add(describe("network", network));

        Path standalone = output.resolve("standalone-fcfs.html");
        Workbench.main(new String[] {"report", online.resolve("runs/fcfs-s42/result.manifest.json").toString(), standalone.toString()});
        Map<String, Object> standaloneSpec = new LinkedHashMap<>();
        standaloneSpec.put("name", "standalone-fcfs"); standaloneSpec.put("path", standalone.toString());
        standaloneSpec.put("candidates", Arrays.asList("FCFS")); standaloneSpec.put("seeds", Arrays.asList("42"));
        standaloneSpec.put("statuses", Arrays.asList("COMPLETED_SUCCESSFULLY")); reports.add(standaloneSpec);

        Path input = output.resolve("single.dax");
        Files.write(input, "<adag version=\"3.3\"><job id=\"one\" runtime=\"1\"/></adag>".getBytes(StandardCharsets.UTF_8));
        JsonObject exact = configuration(input, 1, "FCFS", false);
        exact.addProperty("name", "精确种子 </script><img src=x onerror=alert(1)>");
        JsonArray exactSeeds = new JsonArray();
        for (long seed : new long[] {9007199254740992L, 9007199254740993L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            exactSeeds.add(seed);
        }
        exact.add("seeds", exactSeeds);
        Path exactConfig = output.resolve("exact-seeds.json");
        ExperimentArtifactWriter.writeJson(exactConfig, exact);
        reports.add(describe("exact-seeds", Workbench.run(exactConfig, output.resolve("precise"))));

        JsonObject failed = configuration(input, 1, "RANDOM", true);
        failed.add("simulation", matrix(1));
        Path failedConfig = output.resolve("failed.json");
        ExperimentArtifactWriter.writeJson(failedConfig, failed);
        Map<String, Object> failedSpec = describe("all-failed", Workbench.run(failedConfig, output.resolve("failed")));
        if (((List<?>) failedSpec.get("statuses")).contains("COMPLETED_SUCCESSFULLY")) {
            throw new AssertionError("The zero-rounded matrix must fail every attempt");
        }
        reports.add(failedSpec);

        JsonObject mixed = configuration(input, 2, "RANDOM", true);
        mixed.add("simulation", matrix(2));
        JsonArray seeds = new JsonArray();
        for (int seed = 0; seed < 16; seed++) { seeds.add(seed); }
        mixed.add("seeds", seeds);
        Path mixedConfig = output.resolve("mixed.json");
        ExperimentArtifactWriter.writeJson(mixedConfig, mixed);
        Map<String, Object> mixedSpec = describe("mixed", Workbench.run(mixedConfig, output.resolve("mixed")));
        List<?> statuses = (List<?>) mixedSpec.get("statuses");
        if (!statuses.contains("FAILED") || !statuses.contains("COMPLETED_SUCCESSFULLY")) {
            throw new AssertionError("Fixed mixed fixture must exercise success and failure");
        }
        reports.add(mixedSpec);

        JsonObject large = configuration(checkout.resolve("datasets/dax/montage/n1000/Montage_1000.dax"), 4, "FCFS", false);
        Path largeConfig = output.resolve("large.json");
        ExperimentArtifactWriter.writeJson(largeConfig, large);
        reports.add(describe("large-dag", Workbench.run(largeConfig, output.resolve("large"))));

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("schema", "workflowsim-browser-fixtures-v1"); index.put("reports", reports);
        ExperimentArtifactWriter.writeJson(output.resolve("browser-fixtures.json"), index);
        System.out.println("BROWSER_FIXTURES " + output.resolve("browser-fixtures.json"));
    }

    private static JsonObject configuration(Path input, int count, String algorithm, boolean planner) {
        JsonObject config = new JsonObject(); config.addProperty("schema", "workflowsim-workbench-v1");
        config.addProperty("name", "Browser regression fixture");
        JsonArray inputs = new JsonArray(); inputs.add(input.toString()); config.add("workflowPaths", inputs);
        JsonObject platform = new JsonObject(); JsonArray mips = new JsonArray();
        for (int i = 0; i < count; i++) { mips.add(1000); }
        platform.add("vmMips", mips); config.add("platform", platform);
        JsonObject selected = new JsonObject(); selected.addProperty("id", "candidate");
        selected.addProperty(planner ? "planner" : "scheduler", algorithm);
        JsonArray candidates = new JsonArray(); candidates.add(selected); config.add("algorithms", candidates);
        JsonArray seeds = new JsonArray(); seeds.add(42); config.add("seeds", seeds); return config;
    }

    private static JsonObject matrix(int vmCount) {
        JsonObject simulation = new JsonObject(); JsonArray entries = new JsonArray();
        for (int id = 0; id < vmCount; id++) {
            JsonObject entry = new JsonObject(); entry.addProperty("taskId", 1); entry.addProperty("vmId", id);
            entry.addProperty("executionSeconds", id == 0 ? 0.0001 : 1.0); entries.add(entry);
        }
        simulation.add("taskCostMatrix", entries); return simulation;
    }

    private static Map<String, Object> describe(String name, Path experiment) throws Exception {
        JsonObject record = JsonParser.parseString(new String(Files.readAllBytes(experiment.resolve("experiment.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
        List<String> candidates = new ArrayList<>(), seeds = new ArrayList<>(), statuses = new ArrayList<>();
        for (com.google.gson.JsonElement element : record.getAsJsonArray("runs")) {
            JsonObject row = element.getAsJsonObject(); candidates.add(row.get("candidate").getAsString());
            seeds.add(row.get("seed").getAsString()); statuses.add(row.get("status").getAsString());
        }
        Map<String, Object> spec = new LinkedHashMap<>(); spec.put("name", name);
        spec.put("path", experiment.resolve("report.html").toString()); spec.put("candidates", candidates);
        spec.put("seeds", seeds); spec.put("statuses", statuses); return spec;
    }
}
