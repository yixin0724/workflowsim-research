package org.workflowsim.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class ExperimentArtifactValidatorTest {

    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void validatesGeneratedBundleAndRejectsAChangedSidecar(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("artifact-validator", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "validator-run");
        String manifest = new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"eventCount\""));
        assertTrue(manifest.contains("\"study\": null"));

        ExperimentArtifactValidator.ValidationResult result = ExperimentArtifactValidator.validate(
                artifacts.getManifest());
        assertEquals(report.getEvents().size(), result.getEventCount());

        Files.write(artifacts.getEvents(), "{}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    @Test
    void rejectsEventSequenceTamperingEvenWhenTheManifestChecksumIsRecomputed(@TempDir Path output)
            throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-sequence", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "sequence-validator-run");

        Gson gson = JSON;
        java.util.List<String> lines = Files.readAllLines(artifacts.getEvents(),
                StandardCharsets.UTF_8);
        JsonObject firstEvent = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        firstEvent.addProperty("sequence", 99L);
        lines.set(0, gson.toJson(firstEvent));
        Files.write(artifacts.getEvents(), lines, StandardCharsets.UTF_8);

        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray artifactEntries = manifest.getAsJsonArray("artifacts");
        for (int index = 0; index < artifactEntries.size(); index++) {
            JsonObject entry = artifactEntries.get(index).getAsJsonObject();
            if ("events".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getEvents()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getEvents()));
            }
        }
        Files.write(artifacts.getManifest(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("sequence mismatch"));
    }

    @Test
    void rejectsArtifactPathsThatEscapeTheBundleDirectory(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-path", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "path-validator-run");

        Gson gson = JSON;
        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonArray("artifacts").get(0).getAsJsonObject()
                .addProperty("path", "../escaped.metrics.json");
        Files.write(artifacts.getManifest(), gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("path is invalid"));
    }

    @Test
    void writesAndValidatesAnExplicitStudyIdentity(@TempDir Path output) throws Exception {
        Log.disable();
        Path datasetRoot = Files.createDirectory(output.resolve("datasets"));
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("validator-study")
                .artifact("org.workflowsim", "validator-study", "1.0")
                .driver(ExperimentArtifactValidatorTest.class, "org/workflowsim/experiment")
                .protocol("validator-study-protocol", null)
                .datasetRoot(datasetRoot)
                .build();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-context", 2));

        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output.resolve("bundle"), "context-run", context);
        String manifestText = new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8);
        assertTrue(manifestText.contains("workflowsim-experiment-manifest-v3"));
        assertTrue(manifestText.contains("validator-study"));
        assertEquals(report.getEvents().size(), ExperimentArtifactValidator.validate(
                artifacts.getManifest()).getEventCount());

        JsonObject manifest = JsonParser.parseString(manifestText).getAsJsonObject();
        manifest.getAsJsonObject("provenance").getAsJsonObject("study")
                .getAsJsonObject("dataset").addProperty("root", "relative-datasets");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
    }

    @Test
    void rejectsRelativeDatasetRootInStudyIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentEvidenceContext.builder("relative-root")
                .datasetRoot(Paths.get("datasets")));
    }

    @Test
    void rejectsV3ManifestWithoutAnExplicitStudyField(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-study-shape", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "missing-study-run");
        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.getAsJsonObject("provenance").remove("study");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("explicit study field"));
    }

    @Test
    void rejectsMetricsSidecarThatDiffersFromManifestEvenWithUpdatedChecksum(@TempDir Path output)
            throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-metrics", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "metrics-mismatch-run");

        JsonObject sidecar = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getMetrics()), StandardCharsets.UTF_8)).getAsJsonObject();
        sidecar.getAsJsonObject("metrics").addProperty("testOnlyTamper", 1);
        Files.write(artifacts.getMetrics(), JSON.toJson(sidecar).getBytes(StandardCharsets.UTF_8));

        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        for (int index = 0; index < manifest.getAsJsonArray("artifacts").size(); index++) {
            JsonObject entry = manifest.getAsJsonArray("artifacts").get(index).getAsJsonObject();
            if ("metrics".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getMetrics()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getMetrics()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("metrics sidecar"));
    }

    @Test
    void rejectsOutdatedMetricsSchemaAndMissingRequiredMetricFields(@TempDir Path output)
            throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-schema-v2", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "schema-v2-run");

        // 变体 1：v1 schema（旧字段名契约）必须被拒绝，不能静默通过。
        JsonObject sidecar = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getMetrics()), StandardCharsets.UTF_8)).getAsJsonObject();
        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v1");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException schemaException = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(schemaException.getMessage().contains("Unsupported metrics schema"));

        // 变体 2：schema 正确但缺少 v2 关键字段时必须被拒绝。
        sidecar.addProperty("schema", "workflowsim-simulation-metrics-v2");
        sidecar.getAsJsonObject("metrics").remove("meanComputeTrueSlowdown");
        rewriteMetricsSidecar(artifacts, sidecar);
        java.io.IOException fieldException = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(fieldException.getMessage().contains("meanComputeTrueSlowdown"));
    }

    /** 重写 metrics sidecar 并同步 manifest 中对应工件的校验和与大小。 */
    private static void rewriteMetricsSidecar(ExperimentArtifactWriter.ExperimentArtifacts artifacts,
            JsonObject sidecar) throws Exception {
        Files.write(artifacts.getMetrics(), JSON.toJson(sidecar).getBytes(StandardCharsets.UTF_8));
        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        for (int index = 0; index < manifest.getAsJsonArray("artifacts").size(); index++) {
            JsonObject entry = manifest.getAsJsonArray("artifacts").get(index).getAsJsonObject();
            if ("metrics".equals(entry.get("role").getAsString())) {
                entry.addProperty("sha256", ExperimentProvenance.fingerprint(artifacts.getMetrics()));
                entry.addProperty("sizeBytes", Files.size(artifacts.getMetrics()));
            }
        }
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsManifestMissingARequiredTopLevelSection(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(SimulationConfig.builder(
                resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build(), PlatformProfiles.homogeneousLocal("validator-top-level", 2));
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                output, "missing-runtime-run");
        JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(
                artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        manifest.remove("runtime");
        Files.write(artifacts.getManifest(), JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));

        java.io.IOException exception = assertThrows(java.io.IOException.class,
                () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        assertTrue(exception.getMessage().contains("runtime"));
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = ExperimentArtifactValidatorTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
