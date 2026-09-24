package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * D2 阶段 6 验收（夹具级）：{@link RerunDiffExecutor} 端到端——每个 verdict 一个
 * 手工构造的小样本（契约 §测试要求），报告双格式落盘，退出码与 verdict 一一对应。
 */
class RerunDiffExecutorTest {

    private static Path fixtureRun;
    private static Path failureFixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixtures() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-diff-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);

        // 故障模型夹具：WEIBULL(5000,1.0)+预算 16 可正常完成；
        // 篡改 scale→0.1 后复跑必然耗尽预算（用于"执行阶段失败"映射用例）。
        failureFixtureRun = Files.createTempDirectory("rerun-diff-failure-fixture");
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                        DistributionFamily.WEIBULL, 5000.0, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        SimulationConfig config = SimulationConfig.builder(
                RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax"), 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(91L)
                .failureModel(failure)
                .build();
        RerunTestSupport.generateEvidence(failureFixtureRun, config,
                PlatformProfiles.homogeneousLocal("rerun-failure-fixture", 3));
    }

    @Test
    void identicalCoreEndToEndWritesBothReportsAndRerunTriple() throws Exception {
        Path run = copyFixtureTo("roundtrip");
        Path out = tempDir.resolve("roundtrip-out");

        RerunReport report = RerunDiffExecutor.execute(run, out);

        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict());
        assertEquals(0, report.getExitCode());
        assertTrue(Files.exists(out.resolve("rerun/result.manifest.json")));
        assertTrue(Files.exists(out.resolve("rerun/result.metrics.json")));
        assertTrue(Files.exists(out.resolve("rerun/result.events.jsonl")));
        Path jsonPath = out.resolve(RerunDiffExecutor.REPORT_JSON_NAME);
        Path mdPath = out.resolve(RerunDiffExecutor.REPORT_MARKDOWN_NAME);
        assertTrue(Files.exists(jsonPath));
        assertTrue(Files.exists(mdPath));

        JsonObject json = JsonParser.parseString(read(jsonPath)).getAsJsonObject();
        assertEquals(RerunReport.SCHEMA_V1, json.get("schema").getAsString());
        assertEquals("IDENTICAL_CORE", json.get("verdict").getAsString());
        assertEquals(0, json.get("exitCode").getAsInt());
        assertEquals(0, json.getAsJsonArray("coreDivergences").size());

        // inputResolution：夹具 dax 走记录绝对路径层（tier 2），sha256 核对通过。
        JsonArray inputs = json.getAsJsonArray("inputResolution");
        assertEquals(1, inputs.size());
        JsonObject entry = inputs.get(0).getAsJsonObject();
        assertTrue(entry.get("verified").getAsBoolean());
        assertEquals(2, entry.get("tier").getAsInt());
        assertEquals(entry.get("recordedPath").getAsString(),
                entry.get("resolvedPath").getAsString());

        // 代码身份：同一源码树复跑，两侧哈希相同且非空。
        String originalHash = json.get("originalSourceTreeSha256").getAsString();
        String rerunHash = json.get("rerunSourceTreeSha256").getAsString();
        assertFalse(originalHash.isEmpty());
        assertEquals(originalHash, rerunHash);
        assertTrue(json.get("codeIdentityNote").getAsString().contains("一致"));
        assertEquals(originalHash, report.getOriginalSourceTreeSha256());
        assertTrue(report.getInputResolution().get(0).isVerified());

        String md = read(mdPath);
        assertTrue(md.contains("IDENTICAL_CORE"));
        assertTrue(md.contains("代码身份"));
        assertTrue(md.contains("核心量逐位一致"));
    }

    @Test
    void tamperedManifestMakespanReportsDivergedWithExactPointer() throws Exception {
        Path run = copyFixtureTo("tampered");
        double original = readManifest(run).getAsJsonObject("metrics")
                .get("makespanSeconds").getAsDouble();
        tamperMakespanConsistently(run, original + 1.0);

        RerunReport report = RerunDiffExecutor.execute(run, tempDir.resolve("tampered-out"));

        assertEquals(RerunVerdict.DIVERGED, report.getVerdict());
        assertEquals(1, report.getExitCode());
        // 分歧不妨碍复跑本身：rerun 三件套仍然落盘。
        assertTrue(Files.exists(tempDir.resolve("tampered-out/rerun/result.manifest.json")));
        assertEquals(1, report.getCoreDivergences().size());
        assertEquals("/metrics/makespanSeconds",
                report.getCoreDivergences().get(0).getPointer());

        JsonObject json = JsonParser.parseString(read(tempDir.resolve("tampered-out")
                .resolve(RerunDiffExecutor.REPORT_JSON_NAME))).getAsJsonObject();
        assertEquals("DIVERGED", json.get("verdict").getAsString());
        JsonArray divergences = json.getAsJsonArray("coreDivergences");
        assertEquals(1, divergences.size());
        JsonObject divergence = divergences.get(0).getAsJsonObject();
        assertEquals("/metrics/makespanSeconds", divergence.get("pointer").getAsString());
        assertNotNull(divergence.get("original").getAsString());
        assertNotNull(divergence.get("rerun").getAsString());
    }

    @Test
    void corruptedInputFileReportsHashMismatch() throws Exception {
        Path run = copyFixtureTo("hash-mismatch");
        Path corrupted = Files.createDirectories(tempDir.resolve("corrupted-inputs"))
                .resolve("reproducibility-workflow.dax");
        byte[] bytes = Files.readAllBytes(Paths.get(
                RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax")));
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(corrupted, bytes);
        rewriteInputPath(run, corrupted.toString());

        RerunReport report = RerunDiffExecutor.execute(run,
                tempDir.resolve("hash-mismatch-out"));

        assertEquals(RerunVerdict.INPUT_HASH_MISMATCH, report.getVerdict());
        assertEquals(3, report.getExitCode());
        assertFalse(Files.exists(tempDir.resolve("hash-mismatch-out/rerun")));
        assertTrue(Files.exists(tempDir.resolve("hash-mismatch-out")
                .resolve(RerunDiffExecutor.REPORT_JSON_NAME)));
        assertTrue(report.getFailureReason().contains("sha256"));
        assertFalse(report.getFailureDetails().isEmpty());
    }

    @Test
    void missingInputReportsUnresolvedWithCandidatePaths() throws Exception {
        Path run = copyFixtureTo("unresolved");
        rewriteInputPath(run, "/nonexistent-root/datasets/missing.dax");

        RerunReport report = RerunDiffExecutor.execute(run,
                tempDir.resolve("unresolved-out"));

        assertEquals(RerunVerdict.INPUT_UNRESOLVED, report.getVerdict());
        assertEquals(2, report.getExitCode());
        assertTrue(report.getFailureReason().contains("missing.dax"));
        List<String> details = report.getFailureDetails();
        assertFalse(details.isEmpty());
        boolean listsTier2Candidate = false;
        for (String detail : details) {
            if (detail.startsWith("tier2:") && detail.contains("missing.dax")) {
                listsTier2Candidate = true;
            }
        }
        assertTrue(listsTier2Candidate, details.toString());
        assertFalse(Files.exists(tempDir.resolve("unresolved-out/rerun")));
        assertTrue(Files.exists(tempDir.resolve("unresolved-out")
                .resolve(RerunDiffExecutor.REPORT_MARKDOWN_NAME)));
    }

    @Test
    void unknownAlgorithmLabelReportsReconstructionRejected() throws Exception {
        Path run = copyFixtureTo("bad-enum");
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("configuration")
                .addProperty("schedulingAlgorithm", "NOT_AN_ALGORITHM");
        writeManifest(run, manifest);

        RerunReport report = RerunDiffExecutor.execute(run, tempDir.resolve("bad-enum-out"));

        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict());
        assertEquals(4, report.getExitCode());
        assertTrue(report.getFailureReason().contains("NOT_AN_ALGORITHM"));
        assertFalse(Files.exists(tempDir.resolve("bad-enum-out/rerun")));
        // 失败路径仍保留原始侧身份信息，但无法比较代码身份。
        assertNotNull(report.getOriginalSourceTreeSha256());
        assertNull(report.getRerunSourceTreeSha256());
        assertNull(report.getCodeIdentityNote());
        assertTrue(report.getInputResolution().isEmpty());
    }

    @Test
    void legacySchemaRejectedAsEvidenceInvalid() throws Exception {
        Path run = copyFixtureTo("v3-schema");
        JsonObject manifest = readManifest(run);
        manifest.addProperty("schema", "workflowsim-experiment-manifest-v3");
        writeManifest(run, manifest);

        RerunReport report = RerunDiffExecutor.execute(run, tempDir.resolve("v3-out"));

        assertEquals(RerunVerdict.EVIDENCE_INVALID, report.getVerdict());
        assertEquals(5, report.getExitCode());
        assertNotNull(report.getFailureReason());
        assertFalse(Files.exists(tempDir.resolve("v3-out/rerun")));
        assertTrue(Files.exists(tempDir.resolve("v3-out")
                .resolve(RerunDiffExecutor.REPORT_JSON_NAME)));
    }

    @Test
    void nonEmptyOutputDirectoryRefusedBeforeAnyWork() throws Exception {
        Path run = copyFixtureTo("nonempty");
        Path out = Files.createDirectories(tempDir.resolve("nonempty-out"));
        Files.write(out.resolve("stray.txt"), "keep".getBytes(StandardCharsets.UTF_8));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RerunDiffExecutor.execute(run, out));

        assertTrue(e.getMessage().contains("must be empty"), e.getMessage());
        List<Path> remaining = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(out)) {
            for (Path p : stream) {
                remaining.add(p);
            }
        }
        assertEquals(1, remaining.size(), remaining.toString());
        assertEquals("stray.txt", remaining.get(0).getFileName().toString());
    }

    @Test
    void simulationStageFailureMapsToReconstructionRejected() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("sim-failure"));
        RerunTestSupport.copyEvidenceFiles(failureFixtureRun, run);
        JsonObject manifest = readManifest(run);
        // WEIBULL scale 5000.0 → 0.1：重建仍能通过，但复跑必然耗尽重试预算。
        rewriteDistributionScale(manifest.getAsJsonObject("configuration")
                .getAsJsonObject("failureModel"), 0.1);
        writeManifest(run, manifest);

        RerunReport report = RerunDiffExecutor.execute(run,
                tempDir.resolve("sim-failure-out"));

        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict());
        assertTrue(report.getFailureReason().contains("模拟执行阶段"),
                report.getFailureReason());
        assertFalse(Files.exists(tempDir.resolve("sim-failure-out/rerun")));
    }

    // ---- helpers ----

    private Path copyFixtureTo(String name) throws Exception {
        Path run = Files.createDirectories(tempDir.resolve(name));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    /**
     * 一致性篡改 makespan：同步改写 manifest 内联 metrics、metrics sidecar 与
     * artifacts 哈希/大小。阶段 1 读取器的跨文件校验因此仍然通过，篡改才会到达
     * 差异比对环节——只改 manifest 会被 EVIDENCE_INVALID 提前拦截。
     */
    private static void tamperMakespanConsistently(Path run, double newMakespan)
            throws Exception {
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("metrics").addProperty("makespanSeconds", newMakespan);

        Path sidecar = run.resolve("result.metrics.json");
        JsonObject document = JsonParser.parseString(read(sidecar)).getAsJsonObject();
        document.getAsJsonObject("metrics").addProperty("makespanSeconds", newMakespan);
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        byte[] sidecarBytes = gson.toJson(document).getBytes(StandardCharsets.UTF_8);
        Files.write(sidecar, sidecarBytes);

        String sha = sha256Hex(sidecarBytes);
        for (JsonElement element : manifest.getAsJsonArray("artifacts")) {
            JsonObject item = element.getAsJsonObject();
            if ("metrics".equals(item.get("role").getAsString())) {
                item.addProperty("sha256", sha);
                item.addProperty("sizeBytes", sidecarBytes.length);
            }
        }
        writeManifest(run, manifest);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static void rewriteDistributionScale(JsonElement element, double scale) {
        if (element instanceof JsonArray) {
            for (JsonElement item : element.getAsJsonArray()) {
                rewriteDistributionScale(item, scale);
            }
        } else if (element instanceof JsonObject) {
            JsonObject object = element.getAsJsonObject();
            for (java.util.Map.Entry<String, JsonElement> entry : object.entrySet()) {
                rewriteDistributionScale(entry.getValue(), scale);
            }
            if (object.has("scale")) {
                object.addProperty("scale", scale);
            }
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(read(run.resolve("result.manifest.json")))
                .getAsJsonObject();
    }

    private static void writeManifest(Path run, JsonObject manifest) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(run.resolve("result.manifest.json"),
                gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    /** 同步改写 inputs/workflowPaths/workflowOutcomes 三处路径（v4 交叉校验要求）。 */
    private static void rewriteInputPath(Path run, String newPath) throws Exception {
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .addProperty("path", newPath);
        JsonArray workflowPaths = manifest.getAsJsonObject("configuration")
                .getAsJsonArray("workflowPaths");
        workflowPaths.set(0, new JsonPrimitive(newPath));
        manifest.getAsJsonObject("result").getAsJsonArray("workflowOutcomes")
                .get(0).getAsJsonObject().addProperty("path", newPath);
        writeManifest(run, manifest);
    }
}
