package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskExecutionModel;

/** Mandatory current-model end-to-end reruns; no ignored historical output is required by CI. */
class RerunDiffExecutorIntegrationTest {
    @TempDir static Path fixtures;
    @TempDir Path temporary;
    private static Path classicRun;
    private static Path syntheticRun;

    @BeforeAll
    static void generateCurrentModelEvidence() throws Exception {
        classicRun = RerunTestSupport.generateEvidence(fixtures.resolve("classic"));
        Path study = fixtures.resolve("synthetic-study");
        Path input = study.resolve("inputs/layered-mini.dax");
        Files.createDirectories(input.getParent());
        Files.write(input, ("<adag version=\"2.1\">"
                + "<job id=\"a\" name=\"source\" runtime=\"1.0\"/>"
                + "<job id=\"b\" name=\"middle\" runtime=\"2.0\"/>"
                + "<job id=\"c\" name=\"sink\" runtime=\"0.5\"/>"
                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                + "<child ref=\"c\"><parent ref=\"b\"/></child></adag>")
                .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS).randomSeed(91L).build();
        syntheticRun = RerunTestSupport.generateEvidence(study.resolve("runs/synthetic"), config,
                PlatformProfiles.homogeneousLocal("rerun-synthetic-fixture", 3));
    }

    @Test
    void currentModelClassicFixtureReproducesIdenticalCore() throws Exception {
        Path output = temporary.resolve("classic-out");
        RerunReport report = RerunDiffExecutor.execute(classicRun, output);

        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertTrue(Files.exists(output.resolve("rerun/result.manifest.json")));
        assertTrue(Files.exists(output.resolve(RerunDiffExecutor.REPORT_JSON_NAME)));
        assertTrue(Files.exists(output.resolve(RerunDiffExecutor.REPORT_MARKDOWN_NAME)));
        assertNotNull(report.getOriginalSourceTreeSha256());
        assertEquals(report.getOriginalSourceTreeSha256(), report.getRerunSourceTreeSha256());
        assertEquals(2, report.getInputResolution().get(0).getTier());
        assertTrue(report.getInputResolution().get(0).isVerified());
        assertEquals(TaskExecutionModel.EXECUTION_SEMANTICS,
                readManifest(classicRun).getAsJsonObject("configuration").get("executionSemantics").getAsString());
    }

    @Test
    void currentModelSyntheticFixtureUsesRetainedStudyInput() throws Exception {
        RerunReport report = RerunDiffExecutor.execute(syntheticRun, temporary.resolve("synthetic-out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(1, report.getInputResolution().size());
        assertEquals(1, report.getInputResolution().get(0).getTier());
        assertTrue(report.getInputResolution().get(0).isVerified());
    }

    @Test
    void absentHistoricalExecutionRevisionIsReadableButNotClaimedEquivalentToCurrentModel() throws Exception {
        Path run = Files.createDirectories(temporary.resolve("historical-wire-shape"));
        RerunTestSupport.copyEvidenceFiles(classicRun, run);
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("configuration").remove("executionSemantics");
        Files.write(run.resolve("result.manifest.json"), new GsonBuilder().serializeNulls().setPrettyPrinting()
                .create().toJson(manifest).getBytes(StandardCharsets.UTF_8));

        RerunReport report = RerunDiffExecutor.execute(run, temporary.resolve("revision-out"));

        assertEquals(RerunVerdict.DIVERGED, report.getVerdict(), report.toJson());
        assertTrue(report.getCoreDivergences().stream()
                .anyMatch(change -> "/configuration/executionSemantics".equals(change.getPointer())));
        assertEquals(TaskExecutionModel.EXECUTION_SEMANTICS,
                readManifest(temporary.resolve("revision-out/rerun")).getAsJsonObject("configuration")
                        .get("executionSemantics").getAsString());
    }

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(run.resolve("result.manifest.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
