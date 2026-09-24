package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D2 阶段 6 验收（历史证据级，契约 §验收标准 1/2）：对
 * {@code output/network-study-r10-final/runs/} 中至少一个经典 DAX run 与一个
 * 合成 run 完整复跑 → IDENTICAL_CORE；复制历史证据后篡改 manifest 核心量 →
 * DIVERGED 且指针精确。历史证据目录不在 git 中，缺失时整类跳过（assumeTrue）。
 */
class RerunDiffExecutorIntegrationTest {

    private static Path classicRun;
    private static Path syntheticRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void locateHistoricalEvidence() throws Exception {
        Path runsRoot = findStudyRunsRoot();
        assumeTrue(runsRoot != null,
                "output/network-study-r10-final/runs 不存在，跳过历史证据集成验收");
        List<String> names = sortedRunNames(runsRoot);
        for (String name : names) {
            Path run = runsRoot.resolve(name);
            JsonObject manifest = tryReadManifest(run);
            if (manifest == null) {
                continue;
            }
            String inputPath = manifest.getAsJsonArray("inputs").get(0)
                    .getAsJsonObject().get("path").getAsString();
            String fileName = Paths.get(inputPath).getFileName().toString();
            if (syntheticRun == null && fileName.startsWith("layered-")) {
                syntheticRun = run;
            } else if (classicRun == null && inputPath.contains("datasets/dax")) {
                classicRun = run;
            }
            if (classicRun != null && syntheticRun != null) {
                break;
            }
        }
        assumeTrue(classicRun != null, "未找到使用经典 datasets/dax 输入的历史 run");
        assumeTrue(syntheticRun != null, "未找到使用合成 layered-* 输入的历史 run");
    }

    @Test
    void classicHistoricalRunReproducesIdenticalCore() throws Exception {
        Path out = tempDir.resolve("classic-out");
        RerunReport report = RerunDiffExecutor.execute(classicRun, out);

        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), describe(report));
        assertTrue(Files.exists(out.resolve("rerun/result.manifest.json")));
        assertTrue(Files.exists(out.resolve(RerunDiffExecutor.REPORT_JSON_NAME)));
        assertTrue(Files.exists(out.resolve(RerunDiffExecutor.REPORT_MARKDOWN_NAME)));
        // 原始 run 产生于 rerun 功能之前：身份哈希必须存在，note 必须醒目给出。
        assertNotNull(report.getOriginalSourceTreeSha256());
        assertNotNull(report.getRerunSourceTreeSha256());
        assertNotNull(report.getCodeIdentityNote());
        assertEquals(1, report.getInputResolution().size());
        assertTrue(report.getInputResolution().get(0).isVerified());
    }

    @Test
    void syntheticHistoricalRunReproducesIdenticalCore() throws Exception {
        Path out = tempDir.resolve("synthetic-out");
        RerunReport report = RerunDiffExecutor.execute(syntheticRun, out);

        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), describe(report));
        assertTrue(Files.exists(out.resolve("rerun/result.manifest.json")));
        assertEquals(1, report.getInputResolution().size());
        assertTrue(report.getInputResolution().get(0).isVerified());
        // 合成 run 的输入位于 study inputs/ 目录：命中第一级候选。
        assertEquals(1, report.getInputResolution().get(0).getTier());
    }

    @Test
    void tamperingHistoricalManifestReportsDivergedAtExactPointer() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("tampered-historic"));
        RerunTestSupport.copyEvidenceFiles(classicRun, run);
        double makespan = readManifest(run).getAsJsonObject("metrics")
                .get("makespanSeconds").getAsDouble();
        tamperMakespanConsistently(run, makespan * 2.0);

        RerunReport report = RerunDiffExecutor.execute(run,
                tempDir.resolve("tampered-historic-out"));

        assertEquals(RerunVerdict.DIVERGED, report.getVerdict(), describe(report));
        assertEquals(1, report.getCoreDivergences().size(), describe(report));
        assertEquals("/metrics/makespanSeconds",
                report.getCoreDivergences().get(0).getPointer());
        assertNotNull(report.getCoreDivergences().get(0).getOriginal());
        assertNotNull(report.getCoreDivergences().get(0).getRerun());
    }

    // ---- helpers ----

    /**
     * 一致性篡改 makespan：同步改写 manifest 内联 metrics、metrics sidecar 与
     * artifacts 哈希/大小，使阶段 1 读取器的跨文件校验通过，篡改才能到达差异
     * 比对环节（只改 manifest 会被 EVIDENCE_INVALID 提前拦截）。
     */
    private static void tamperMakespanConsistently(Path run, double newMakespan)
            throws Exception {
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("metrics").addProperty("makespanSeconds", newMakespan);

        Path sidecar = run.resolve("result.metrics.json");
        JsonObject document = JsonParser.parseString(new String(
                Files.readAllBytes(sidecar), StandardCharsets.UTF_8)).getAsJsonObject();
        document.getAsJsonObject("metrics").addProperty("makespanSeconds", newMakespan);
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        byte[] sidecarBytes = gson.toJson(document).getBytes(StandardCharsets.UTF_8);
        Files.write(sidecar, sidecarBytes);

        String sha = sha256Hex(sidecarBytes);
        for (com.google.gson.JsonElement element : manifest.getAsJsonArray("artifacts")) {
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

    private static Path findStudyRunsRoot() {
        Path current = Paths.get(".").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("output/network-study-r10-final/runs");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    private static List<String> sortedRunNames(Path runsRoot) throws Exception {
        List<String> names = new ArrayList<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(runsRoot)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    private static JsonObject tryReadManifest(Path run) {
        Path manifestPath = run.resolve("result.manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return null;
        }
        try {
            return JsonParser.parseString(new String(
                    Files.readAllBytes(manifestPath), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    private static String describe(RerunReport report) {
        StringBuilder text = new StringBuilder("verdict=")
                .append(report.getVerdict())
                .append(" reason=").append(report.getFailureReason());
        for (RerunReport.DivergenceEntry entry : report.getCoreDivergences()) {
            text.append("\n  ").append(entry.getPointer()).append(": ")
                    .append(entry.getOriginal()).append(" -> ").append(entry.getRerun());
        }
        return text.toString();
    }

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(new String(
                Files.readAllBytes(run.resolve("result.manifest.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void writeManifest(Path run, JsonObject manifest) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(run.resolve("result.manifest.json"),
                gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }
}
