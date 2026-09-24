package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 阶段 1 验收：v4 证据读取成功；v2/v3 schema、缺失 manifest、损坏 sidecar、
 * 歧义目录全部以 {@code EVIDENCE_INVALID} 拒绝且原因可读。
 */
class RerunEvidenceReaderTest {

    /** 一次真实小规模仿真写出的 v4 证据目录，供全部用例复制后篡改。 */
    private static Path fixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixture() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-evidence-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);
    }

    @Test
    void readsValidV4Evidence() throws Exception {
        Path run = copyFixture();
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        assertEquals(run.resolve("result.manifest.json"), evidence.getManifestPath());
        assertEquals(run.resolve("result.metrics.json"), evidence.getMetricsPath());
        assertEquals(run.resolve("result.events.jsonl"), evidence.getEventsPath());
        assertEquals("workflowsim-experiment-manifest-v4",
                evidence.getManifest().get("schema").getAsString());
        assertEquals(evidence.getManifest().getAsJsonObject("events").get("eventCount").getAsInt(),
                evidence.getEventCount());
        assertTrue(evidence.getEventCount() > 0);
        assertTrue(Files.isRegularFile(evidence.getMetricsPath()));
        assertTrue(Files.isRegularFile(evidence.getEventsPath()));
    }

    @Test
    void rejectsV3SchemaWithExplicitReason() throws Exception {
        Path run = copyFixture();
        rewriteSchema(run.resolve("result.manifest.json"), "workflowsim-experiment-manifest-v3");
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("v4"), failure.getMessage());
        assertTrue(failure.getMessage().contains("workflowsim-experiment-manifest-v3"),
                failure.getMessage());
    }

    @Test
    void rejectsV2Schema() throws Exception {
        Path run = copyFixture();
        rewriteSchema(run.resolve("result.manifest.json"), "workflowsim-experiment-manifest-v2");
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
    }

    @Test
    void rejectsUnknownSchema() throws Exception {
        Path run = copyFixture();
        rewriteSchema(run.resolve("result.manifest.json"), "something-else-v9");
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("something-else-v9"));
    }

    @Test
    void rejectsDirectoryWithoutManifest() throws Exception {
        Path empty = Files.createDirectories(tempDir.resolve("empty"));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(empty));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("No manifest"));
    }

    @Test
    void rejectsMissingEventsSidecar() throws Exception {
        Path run = copyFixture();
        Files.delete(run.resolve("result.events.jsonl"));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("Evidence structure validation failed"),
                failure.getMessage());
    }

    @Test
    void rejectsTamperedEventsSidecarByHash() throws Exception {
        Path run = copyFixture();
        Path events = run.resolve("result.events.jsonl");
        String content = new String(Files.readAllBytes(events), StandardCharsets.UTF_8);
        // 只改文件不改 manifest：应先命中 artifacts 记录的 SHA-256 校验。
        String firstLine = content.split("\n", 2)[0];
        Files.write(events, (content + firstLine + "\n").getBytes(StandardCharsets.UTF_8));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"), failure.getMessage());
    }

    @Test
    void rejectsEventCountMismatch() throws Exception {
        Path run = copyFixture();
        Path events = run.resolve("result.events.jsonl");
        Path manifest = run.resolve("result.manifest.json");
        String content = new String(Files.readAllBytes(events), StandardCharsets.UTF_8);
        // 删除最后一条事件（序列前缀仍连续），再把 manifest 声明的 eventCount
        // 加大 1：穿过哈希层与序列层后，命中计数一致性检查。
        String[] lines = content.split("\n");
        StringBuilder truncated = new StringBuilder();
        for (int i = 0; i < lines.length - 1; i++) {
            truncated.append(lines[i]).append('\n');
        }
        byte[] tampered = truncated.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(events, tampered);
        JsonObject root = JsonParser.parseString(
                new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)).getAsJsonObject();
        root.getAsJsonObject("events").addProperty("eventCount", lines.length);
        root.getAsJsonArray("artifacts").forEach(element -> {
            JsonObject artifact = element.getAsJsonObject();
            if ("events".equals(artifact.get("role").getAsString())) {
                artifact.addProperty("sha256", sha256Hex(tampered));
                artifact.addProperty("sizeBytes", tampered.length);
            }
        });
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(manifest, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("Event count mismatch"), failure.getMessage());
    }

    @Test
    void rejectsBrokenEventSequence() throws Exception {
        Path run = copyFixture();
        Path events = run.resolve("result.events.jsonl");
        Path manifest = run.resolve("result.manifest.json");
        String content = new String(Files.readAllBytes(events), StandardCharsets.UTF_8);
        // 追加一条重复事件行并同步哈希：序列连续性检查应在计数检查之前拒绝。
        String firstLine = content.split("\n", 2)[0];
        byte[] tampered = (content + firstLine + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(events, tampered);
        JsonObject root = JsonParser.parseString(
                new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)).getAsJsonObject();
        root.getAsJsonArray("artifacts").forEach(element -> {
            JsonObject artifact = element.getAsJsonObject();
            if ("events".equals(artifact.get("role").getAsString())) {
                artifact.addProperty("sha256", sha256Hex(tampered));
                artifact.addProperty("sizeBytes", tampered.length);
            }
        });
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(manifest, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("sequence mismatch"), failure.getMessage());
    }

    @Test
    void rejectsAmbiguousManifests() throws Exception {
        Path run = copyFixture();
        Files.move(run.resolve("result.manifest.json"), run.resolve("first.manifest.json"));
        Files.copy(run.resolve("first.manifest.json"), run.resolve("second.manifest.json"));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunEvidenceReader.read(run));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("Ambiguous"));
        assertEquals(2, failure.getDetails().size());
    }

    @Test
    void prefersStandardManifestNameWhenOthersPresent() throws Exception {
        Path run = copyFixture();
        // 存在标准名时直接使用它，其余 manifest 副本不构成歧义。
        Files.copy(run.resolve("result.manifest.json"), run.resolve("backup.manifest.json"));
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        assertEquals(run.resolve("result.manifest.json"), evidence.getManifestPath());
    }

    private Path copyFixture() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("run"));
        try (Stream<Path> files = Files.walk(fixtureRun)) {
            files.filter(Files::isRegularFile).forEach(source -> {
                try {
                    Files.copy(source, run.resolve(source.getFileName()));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        return run;
    }

    private static void rewriteSchema(Path manifest, String schema) throws Exception {
        JsonObject root = JsonParser.parseString(
                new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)).getAsJsonObject();
        root.addProperty("schema", schema);
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(manifest, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
