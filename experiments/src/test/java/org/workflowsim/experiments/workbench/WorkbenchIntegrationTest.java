package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithm;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithms;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.array;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.costs;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.object;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.payload;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.text;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.ExperimentArtifactValidator;

class WorkbenchIntegrationTest {
    @TempDir Path directory;
    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceSimulationLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void runProducesValidatedV4EvidenceOfflineHtmlAndHistoryForEveryCandidateAndSeed() throws Exception {
        JsonObject source = base();
        source.addProperty("name", "two schedulers, two seeds");
        algorithms(source, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));
        JsonArray seeds = new JsonArray(); seeds.add(17L); seeds.add(23L); source.add("seeds", seeds);
        Path config = json(directory.resolve("experiment-config.json"), source);
        Path root = directory.resolve("output");

        Path experiment = Workbench.run(config, root);

        assertEquals(source, object(experiment.resolve("configuration.json")), "preserve the user's submitted configuration");
        JsonObject record = object(experiment.resolve("experiment.json"));
        assertEquals("workflowsim-workbench-experiment-v1", record.get("schema").getAsString());
        assertEquals("COMPLETED_SUCCESSFULLY", record.get("status").getAsString());
        assertEquals(4, record.get("plannedRuns").getAsInt());
        assertEquals(4, record.get("successfulRuns").getAsInt());
        assertTrue(record.has("startedAt") && record.has("finishedAt"));
        JsonArray runs = record.getAsJsonArray("runs");
        assertEquals(4, runs.size());
        JsonObject displayed = payload(experiment.resolve("report.html"));
        assertEquals(source.get("name"), displayed.get("name"));
        assertEquals(4, displayed.getAsJsonArray("runs").size());
        Set<String> cells = new HashSet<String>();
        for (int index = 0; index < runs.size(); index++) {
            JsonObject run = runs.get(index).getAsJsonObject();
            String candidate = run.get("candidate").getAsString();
            long seed = run.get("seed").getAsLong();
            cells.add(candidate + ":" + seed);
            assertEquals("COMPLETED_SUCCESSFULLY", run.get("status").getAsString());
            String relative = run.get("manifest").getAsString();
            assertFalse(Paths.get(relative).isAbsolute());
            Path manifestPath = experiment.resolve(relative).normalize();
            assertTrue(manifestPath.startsWith(experiment));
            ExperimentArtifactValidator.ValidationResult validation = ExperimentArtifactValidator.validate(manifestPath);
            assertTrue(validation.getEventCount() > 0);
            assertTrue(Files.isRegularFile(validation.getMetrics()));
            assertTrue(Files.isRegularFile(validation.getEvents()));
            JsonObject manifest = object(manifestPath);
            assertEquals("workflowsim-experiment-manifest-v4", manifest.get("schema").getAsString());
            assertEquals("workflowsim-provenance-v3", manifest.getAsJsonObject("provenance").get("schema").getAsString());
            assertEquals("workbench-experiment", manifest.getAsJsonObject("provenance").getAsJsonObject("study").get("id").getAsString());
            JsonObject conditions = manifest.getAsJsonObject("configuration");
            assertEquals(seed, conditions.get("rootSeed").getAsLong());
            assertEquals("fcfs".equals(candidate) ? "FCFS" : "READY_BATCH_MINMIN",
                    conditions.get("schedulingAlgorithm").getAsString());
            assertEquals(0.0, conditions.getAsJsonArray("workflowArrivalSeconds").get(0).getAsDouble(), 0.0);
            assertTrue(conditions.get("taskCostMatrix").isJsonNull());
            assertTrue(manifest.getAsJsonObject("platform").get("networkTopology").isJsonNull());
            assertEquals(2, manifest.getAsJsonObject("workflowProfile").get("taskCount").getAsInt());
            assertEquals(1, manifest.getAsJsonObject("workflowProfile").get("edgeCount").getAsInt());
            assertEquals(manifest.getAsJsonObject("metrics"), object(validation.getMetrics()).getAsJsonObject("metrics"));
            assertEquals(run.get("makespanSeconds").getAsDouble(),
                    manifest.getAsJsonObject("metrics").get("makespanSeconds").getAsDouble(), 0.0);
            JsonObject displayedRun = displayed.getAsJsonArray("runs").get(index).getAsJsonObject();
            assertEquals(manifest, displayedRun.getAsJsonObject("manifest"), "HTML must display the validated evidence");
        }
        assertEquals(new HashSet<String>(Arrays.asList("fcfs:17", "fcfs:23", "minmin:17", "minmin:23")), cells);
        String html = text(experiment.resolve("report.html"));
        assertFalse(html.contains("@@TITLE@@"));
        assertFalse(html.contains("@@DATA@@"));
        assertFalse(html.contains("<script src="), "report must work offline without external scripts");
        assertTrue(html.contains("任务执行时间线"));
        assertTrue(html.contains("资源利用率"));
        JsonObject history = onlyHistoryRecord(root);
        assertEquals(experiment.getFileName().toString(), history.get("directory").getAsString());
        assertEquals("COMPLETED_SUCCESSFULLY", history.get("status").getAsString());
        assertEquals(4, history.get("successfulRuns").getAsInt());
        assertTrue(text(root.resolve("index.html")).contains(experiment.getFileName() + "/report.html"));
    }

    @Test
    void hostileTitleIsEscapedInReportAndHistoryButPreservedAsJsonData() throws Exception {
        JsonObject source = base();
        String title = "</title><script>alert('xss')</script>&\"'\u2028\u2029";
        source.addProperty("name", title);
        Path experiment = Workbench.run(json(directory.resolve("injection.json"), source), directory.resolve("output"));

        String html = text(experiment.resolve("report.html"));
        assertEquals(title, payload(experiment.resolve("report.html")).get("name").getAsString());
        assertFalse(html.contains("<script>alert('xss')</script>"));
        assertTrue(html.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"));
        assertTrue(html.contains("\\u003cscript\\u003e"), "the JSON script element must not contain executable markup");
        assertTrue(html.contains("\\u2028") && html.contains("\\u2029"));
        String history = text(directory.resolve("output/index.html"));
        assertFalse(history.contains("<script>alert('xss')</script>"));
        assertTrue(history.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"));
        assertEquals(title, onlyHistoryRecord(directory.resolve("output")).get("name").getAsString());
    }

    @Test
    void literalTemplateMarkersInTitleRemainUserData() throws Exception {
        JsonObject source = base(); source.addProperty("name", "@@TITLE@@ @@DATA@@");
        Path experiment = Workbench.run(json(directory.resolve("markers.json"), source), directory.resolve("marker-output"));
        assertEquals("@@TITLE@@ @@DATA@@", payload(experiment.resolve("report.html")).get("name").getAsString());
    }

    @Test
    void repeatingTheSameConfigurationCreatesANewDirectoryAndPreservesEveryEarlierFile() throws Exception {
        Path config = json(directory.resolve("config.json"), base());
        Path root = directory.resolve("output");
        Log.enable();
        Path first = Workbench.run(config, root);
        assertFalse(Log.isDisabled(), "restore the caller's enabled logging state");
        Map<Path, byte[]> previous = snapshot(first);
        Log.disable();

        Path second = Workbench.run(config, root);

        assertTrue(Log.isDisabled(), "restore the caller's disabled logging state");
        assertNotEquals(first, second);
        assertEquals(root.toAbsolutePath(), first.getParent());
        assertEquals(root.toAbsolutePath(), second.getParent());
        assertUnchanged(first, previous);
        ExperimentArtifactValidator.validate(manifest(first));
        ExperimentArtifactValidator.validate(manifest(second));
        Set<String> directories = new HashSet<String>();
        for (JsonElement row : array(root.resolve("history.json"))) {
            directories.add(row.getAsJsonObject().get("directory").getAsString());
        }
        assertEquals(new HashSet<String>(Arrays.asList(first.getFileName().toString(), second.getFileName().toString())), directories);
        assertEquals(2, array(root.resolve("history.json")).size());
    }

    @Test
    void runtimeFailuresKeepDiagnosticHtmlAndHistoryWithoutDamagingSuccessfulExperiments() throws Exception {
        Path root = directory.resolve("output");
        Path successful = Workbench.run(json(directory.resolve("good.json"), base()), root);
        Map<Path, byte[]> successfulFiles = snapshot(successful);
        JsonObject bad = failingConfiguration();
        JsonArray seeds = new JsonArray(); seeds.add(5L); seeds.add(6L); bad.add("seeds", seeds);
        Path config = json(directory.resolve("runtime-failure.json"), bad);
        assertDoesNotThrow(() -> WorkbenchConfig.read(config).validateInputs(),
                "positive matrix costs cover inputs; the rejection must occur when runtime MI rounds to zero");

        Path failed = Workbench.run(config, root);

        assertFailureRecord(failed, 2);
        assertUnchanged(successful, successfulFiles);
        ExperimentArtifactValidator.validate(manifest(successful));
        JsonArray history = array(root.resolve("history.json"));
        assertEquals(2, history.size());
        assertEquals("FAILED_OR_INCOMPLETE", historyRecord(history, failed).get("status").getAsString());
        assertEquals("COMPLETED_SUCCESSFULLY", historyRecord(history, successful).get("status").getAsString());
        assertTrue(text(root.resolve("index.html")).contains(failed.getFileName() + "/report.html"));
        assertTrue(Log.isDisabled());
    }

    @Test
    void cliValidatesRunsListsHistoryAndRegeneratesAReportWithoutOverwritingIt() throws Exception {
        Path config = json(directory.resolve("config.json"), base());
        Path root = directory.resolve("cli-output");
        String validation = outputOf(() -> Workbench.main(new String[]{"validate", config.toString()}));
        assertTrue(validation.contains("WORKBENCH_VALIDATED runs=1"));
        String emptyHistory = outputOf(() -> Workbench.main(new String[]{"history", root.toString()}));
        assertTrue(emptyHistory.contains("WORKBENCH_HISTORY"));
        assertEquals(0, array(root.resolve("history.json")).size());
        String runOutput = outputOf(() -> Workbench.main(new String[]{"run", config.toString(), root.toString()}));
        assertTrue(runOutput.contains("WORKBENCH_REPORT"));
        Path experiment = root.resolve(onlyHistoryRecord(root).get("directory").getAsString());
        Path target = directory.resolve("regenerated/report.html");

        Workbench.main(new String[]{"report", manifest(experiment).toString(), target.toString()});

        assertTrue(Files.isRegularFile(target));
        JsonArray rows = payload(target).getAsJsonArray("runs");
        assertEquals(1, rows.size());
        assertEquals(object(manifest(experiment)), rows.get(0).getAsJsonObject().getAsJsonObject("manifest"));
        byte[] before = Files.readAllBytes(target);
        IOException collision = assertThrows(IOException.class,
                () -> Workbench.main(new String[]{"report", manifest(experiment).toString(), target.toString()}));
        assertTrue(collision.getMessage().contains("already exists"));
        assertArrayEquals(before, Files.readAllBytes(target));
    }

    @Test
    void cliSignalsRuntimeFailureAfterPersistingTheFailedExperiment() throws Exception {
        Path config = json(directory.resolve("failing-config.json"), failingConfiguration());
        Path root = directory.resolve("failed-cli-output");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> outputOf(() -> Workbench.main(new String[]{"run", config.toString(), root.toString()})));

        assertTrue(failure.getMessage().contains("failed runs"));
        JsonObject history = onlyHistoryRecord(root);
        assertEquals("FAILED_OR_INCOMPLETE", history.get("status").getAsString());
        Path experiment = root.resolve(history.get("directory").getAsString());
        assertFailureRecord(experiment, 1);
        assertTrue(Files.isRegularFile(root.resolve("index.html")));
    }

    @Test
    void cliRejectsUnknownCommandsAndIncorrectArgumentCounts() {
        String[][] invalid = {
            {}, {"unknown"}, {"validate"}, {"validate", "config.json", "extra"},
            {"run", "config.json"}, {"run", "config.json", "output", "extra"},
            {"history"}, {"history", "output", "extra"},
            {"report", "manifest.json"}, {"report", "manifest.json", "report.html", "extra"}
        };
        for (String[] arguments : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Workbench.main(arguments), Arrays.toString(arguments));
        }
    }

    @Test
    void reportRejectsTamperedEvidenceBeforeCreatingAReplacementHtml() throws Exception {
        Path root = directory.resolve("output");
        Path experiment = Workbench.run(json(directory.resolve("config.json"), base()), root);
        Path manifest = manifest(experiment);
        ExperimentArtifactValidator.ValidationResult bundle = ExperimentArtifactValidator.validate(manifest);
        byte[] reportBefore = Files.readAllBytes(experiment.resolve("report.html"));
        byte[] historyBefore = Files.readAllBytes(root.resolve("history.json"));
        text(bundle.getMetrics(), "{}");
        Path target = directory.resolve("rejected.html");

        assertThrows(IOException.class,
                () -> Workbench.main(new String[]{"report", manifest.toString(), target.toString()}));

        assertFalse(Files.exists(target));
        assertArrayEquals(reportBefore, Files.readAllBytes(experiment.resolve("report.html")));
        assertArrayEquals(historyBefore, Files.readAllBytes(root.resolve("history.json")));
    }

    @Test
    void historyKeepsInterruptedAndCorruptRecordsVisibleAndIgnoresUnrelatedEntries() throws Exception {
        Path root = directory.resolve("history-only");
        text(root.resolve("experiment-corrupt/experiment.json"), "{not-json");
        JsonObject interrupted = new JsonObject();
        interrupted.addProperty("schema", "workflowsim-workbench-experiment-v1");
        interrupted.addProperty("name", "unfinished experiment"); interrupted.addProperty("status", "RUNNING");
        interrupted.addProperty("plannedRuns", 2); interrupted.addProperty("startedAt", "2026-01-01T00:00:00Z");
        json(root.resolve("experiment-running/experiment.json"), interrupted);
        Files.createDirectory(root.resolve("unrelated-directory"));
        text(root.resolve("experiment-not-a-directory"), "keep this file");

        Path index = Workbench.history(root);

        JsonArray records = array(root.resolve("history.json"));
        assertEquals(2, records.size());
        JsonObject damaged = historyRecord(records, root.resolve("experiment-corrupt"));
        assertEquals("INVALID_RECORD", damaged.get("status").getAsString());
        assertTrue(damaged.has("error"));
        JsonObject running = historyRecord(records, root.resolve("experiment-running"));
        assertEquals("RUNNING", running.get("status").getAsString());
        assertEquals(0, running.get("successfulRuns").getAsInt());
        String html = text(index);
        assertTrue(html.contains("记录损坏") && html.contains("unfinished experiment"));
        assertFalse(html.contains("experiment-running/report.html"), "do not link a report that was never written");
        assertEquals("keep this file", text(root.resolve("experiment-not-a-directory")));
    }

    @Test
    void historyDoesNotEmitHalfARowWhenStartedAtIsMissing() throws Exception {
        Path root = directory.resolve("incomplete-history");
        JsonObject broken = new JsonObject();
        broken.addProperty("name", "should-not-appear-as-success");
        broken.addProperty("status", "COMPLETED_SUCCESSFULLY");
        broken.addProperty("plannedRuns", 1);
        json(root.resolve("experiment-missing-time/experiment.json"), broken);
        String html = text(Workbench.history(root));
        assertFalse(html.contains("should-not-appear-as-success"));
        assertFalse(html.contains("COMPLETED_SUCCESSFULLY"));
        assertEquals(1, array(root.resolve("history.json")).size());
        assertEquals("INVALID_RECORD", array(root.resolve("history.json")).get(0).getAsJsonObject().get("status").getAsString());
    }

    private JsonObject base() throws Exception {
        workflow(directory, "workflow.dax", true);
        return configuration("workflow.dax", 2);
    }

    private JsonObject failingConfiguration() throws Exception {
        workflow(directory, "workflow.dax", true);
        JsonObject source = configuration("workflow.dax", 1);
        algorithms(source, algorithm("random", null, "RANDOM"));
        source.getAsJsonObject("simulation").add("taskCostMatrix", costs(2, 1, 1.0e-9));
        return source;
    }

    private static Path manifest(Path experiment) throws IOException {
        JsonObject first = object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject();
        return experiment.resolve(first.get("manifest").getAsString()).normalize();
    }

    private static JsonObject onlyHistoryRecord(Path root) throws IOException {
        JsonArray records = array(root.resolve("history.json"));
        assertEquals(1, records.size());
        return records.get(0).getAsJsonObject();
    }

    private static JsonObject historyRecord(JsonArray records, Path experiment) {
        String wanted = experiment.getFileName().toString();
        for (JsonElement value : records) {
            JsonObject row = value.getAsJsonObject();
            if (wanted.equals(row.get("directory").getAsString())) { return row; }
        }
        throw new AssertionError("Missing history entry for " + wanted);
    }

    private static void assertFailureRecord(Path experiment, int planned) throws IOException {
        JsonObject record = object(experiment.resolve("experiment.json"));
        assertEquals("FAILED_OR_INCOMPLETE", record.get("status").getAsString());
        assertEquals(planned, record.get("plannedRuns").getAsInt());
        assertEquals(0, record.get("successfulRuns").getAsInt());
        assertTrue(record.has("finishedAt"));
        assertTrue(Files.isRegularFile(experiment.resolve("configuration.json")));
        JsonArray runs = record.getAsJsonArray("runs");
        assertEquals(planned, runs.size(), "continue recording the remaining seeds after a run fails");
        for (JsonElement value : runs) {
            JsonObject row = value.getAsJsonObject();
            assertEquals("FAILED", row.get("status").getAsString());
            assertTrue(row.get("error").getAsString().contains("non-positive MI"));
            assertFalse(row.has("manifest"));
        }
        JsonArray displayed = payload(experiment.resolve("report.html")).getAsJsonArray("runs");
        assertEquals(planned, displayed.size());
        for (JsonElement value : displayed) {
            JsonObject row = value.getAsJsonObject();
            assertEquals("FAILED", row.get("status").getAsString());
            assertTrue(row.get("error").getAsString().contains("non-positive MI"));
            assertFalse(row.has("manifest"), "failed runs must not masquerade as successful evidence");
        }
    }

    private static Map<Path, byte[]> snapshot(Path root) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        Map<Path, byte[]> result = new LinkedHashMap<Path, byte[]>();
        for (Path file : files) { result.put(root.relativize(file), Files.readAllBytes(file)); }
        return result;
    }

    private static void assertUnchanged(Path root, Map<Path, byte[]> expected) throws IOException {
        assertEquals(expected.keySet(), snapshot(root).keySet());
        for (Map.Entry<Path, byte[]> file : expected.entrySet()) {
            assertArrayEquals(file.getValue(), Files.readAllBytes(root.resolve(file.getKey())), file.getKey().toString());
        }
    }

    private static String outputOf(ThrowingAction action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(captured, true, "UTF-8")) {
            System.setOut(stream); action.run();
        } finally { System.setOut(original); }
        return new String(captured.toByteArray(), StandardCharsets.UTF_8);
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }
}
