package org.workflowsim.experiments.workbench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small user configurations and two-task inputs shared by the workbench tests. */
final class WorkbenchTestSupport {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Pattern PAYLOAD = Pattern.compile(
            "<script id=\"payload\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL);

    private WorkbenchTestSupport() { }

    static Path workflow(Path directory, String name, boolean dependent) throws IOException {
        String firstFiles = dependent ? "<uses file=\"edge\" link=\"output\" size=\"1000000\"/>" : "";
        String secondFiles = dependent ? "<uses file=\"edge\" link=\"input\" size=\"1000000\"/>" : "";
        String edge = dependent ? "<child ref=\"b\"><parent ref=\"a\"/></child>" : "";
        return text(directory.resolve(name), "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\" version=\"3.3\" name=\"tiny\">"
                + "<job id=\"a\" name=\"first\" runtime=\"1.0\">" + firstFiles + "</job>"
                + "<job id=\"b\" name=\"second\" runtime=\"0.5\">" + secondFiles + "</job>"
                + edge + "</adag>\n");
    }

    static JsonObject configuration(String workflow, int vmCount) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "workflowsim-workbench-v1");
        root.addProperty("name", "tiny workflow experiment");
        JsonArray paths = new JsonArray(); paths.add(workflow); root.add("workflowPaths", paths);
        JsonObject platform = new JsonObject();
        JsonArray mips = new JsonArray();
        for (int id = 0; id < vmCount; id++) { mips.add(1000.0); }
        platform.add("vmMips", mips); root.add("platform", platform);
        root.add("simulation", new JsonObject());
        algorithms(root, algorithm("fcfs", "FCFS", null));
        JsonArray seeds = new JsonArray(); seeds.add(17L); root.add("seeds", seeds);
        return root;
    }

    static JsonObject algorithm(String id, String scheduler, String planner) {
        JsonObject value = new JsonObject(); value.addProperty("id", id);
        if (scheduler != null) { value.addProperty("scheduler", scheduler); }
        if (planner != null) { value.addProperty("planner", planner); }
        return value;
    }

    static void algorithms(JsonObject root, JsonObject... candidates) {
        JsonArray values = new JsonArray();
        for (JsonObject candidate : candidates) { values.add(candidate); }
        root.add("algorithms", values);
    }

    static JsonObject localConfiguration(String workflow, int vmCount, boolean fatTree) {
        JsonObject root = configuration(workflow, vmCount);
        algorithms(root, algorithm("local-heft", null, "LOCAL_HEFT"));
        JsonObject simulation = root.getAsJsonObject("simulation");
        simulation.addProperty("fileSystem", "LOCAL");
        simulation.addProperty("dataMovementModel", fatTree
                ? "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"
                : "PRE_EXECUTION_TRANSFER_DELAY_V1");
        if (fatTree) { root.getAsJsonObject("platform").add("networkTopology", topology()); }
        return root;
    }

    static JsonObject topology() {
        JsonObject value = new JsonObject();
        value.addProperty("k", 4); value.addProperty("linkBandwidthMbPerSecond", 1.0);
        return value;
    }

    static JsonArray costs(int taskCount, int vmCount, double seconds) {
        JsonArray values = new JsonArray();
        for (int task = 1; task <= taskCount; task++) {
            for (int vm = 0; vm < vmCount; vm++) {
                JsonObject entry = new JsonObject();
                entry.addProperty("taskId", task); entry.addProperty("vmId", vm);
                entry.addProperty("executionSeconds", seconds); values.add(entry);
            }
        }
        return values;
    }

    static Path json(Path target, JsonObject value) throws IOException {
        return text(target, JSON.toJson(value));
    }

    static Path text(Path target, String value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.write(target, value.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    static String text(Path source) throws IOException {
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    static JsonObject object(Path path) throws IOException {
        return JsonParser.parseString(text(path)).getAsJsonObject();
    }

    static JsonArray array(Path path) throws IOException {
        return JsonParser.parseString(text(path)).getAsJsonArray();
    }

    static JsonObject payload(Path report) throws IOException {
        Matcher match = PAYLOAD.matcher(text(report));
        if (!match.find()) { throw new AssertionError("Report lacks its embedded JSON payload: " + report); }
        return JsonParser.parseString(match.group(1)).getAsJsonObject();
    }
}
