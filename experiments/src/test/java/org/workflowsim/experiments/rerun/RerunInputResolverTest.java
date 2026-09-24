package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.rerun.RerunInputs.ResolvedInput;

/**
 * 阶段 2 验收：三级输入定位顺序正确；命中即核对 sha256+sizeBytes；不符即终止
 * （绝不静默降级或替换）；全部落空时报告 {@code INPUT_UNRESOLVED} 并列出全部
 * 尝试过的候选路径。
 */
class RerunInputResolverTest {

    private static Path fixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixture() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-inputs-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);
    }

    @Test
    void resolvesRecordedAbsolutePathDirectly() throws Exception {
        Path run = copyFixtureTo("plain");
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        JsonObject manifest = evidence.getManifest();
        String recorded = inputPath(manifest);
        RerunInputs inputs = RerunInputResolver.resolve(manifest, run, tempDir);
        assertEquals(1, inputs.size());
        ResolvedInput resolved = inputs.get(0);
        assertEquals(recorded, resolved.getRecordedPath());
        assertEquals(Paths.get(recorded), resolved.getResolvedPath());
        assertEquals(2, resolved.getTier());
        assertEquals(manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .get("sha256").getAsString(), resolved.getSha256());
    }

    @Test
    void prefersStudyInputsDirectory() throws Exception {
        Path run = studyLayoutRun();
        // 记录路径指向不存在的位置；同名文件放在 <study>/inputs/ 下。
        Path studyInput = run.getParent().getParent().resolve("inputs")
                .resolve("reproducibility-workflow.dax");
        Files.createDirectories(studyInput.getParent());
        Files.copy(Paths.get(inputPath(readManifest(run))), studyInput);
        rewriteInputPath(run, "/nonexistent/original-machine/reproducibility-workflow.dax");
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunInputs inputs = RerunInputResolver.resolve(evidence.getManifest(), run, tempDir);
        assertEquals(studyInput, inputs.get(0).getResolvedPath());
        assertEquals(1, inputs.get(0).getTier());
    }

    @Test
    void rebasesAgainstRecordedWorkingDirectory() throws Exception {
        Path run = copyFixtureTo("rebase");
        // 伪造原机器根：记录路径 = fakeRoot/datasets/fixture.dax，fakeRoot 不存在；
        // 重定位根下准备同内容文件，并把 workingDirectory 改写为 fakeRoot。
        Path rebasingRoot = Files.createDirectories(tempDir.resolve("rebase-root"));
        Path relocated = rebasingRoot.resolve("datasets/fixture.dax");
        Files.createDirectories(relocated.getParent());
        Files.copy(Paths.get(inputPath(readManifest(run))), relocated);
        String fakeRoot = tempDir.resolve("fake-original-root").toString();
        JsonObject manifest = readManifest(run);
        rewriteInputPathIn(manifest, fakeRoot + "/datasets/fixture.dax");
        manifest.getAsJsonObject("provenance").getAsJsonObject("execution")
                .addProperty("workingDirectory", fakeRoot);
        writeManifest(run, manifest);
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunInputs inputs = RerunInputResolver.resolve(evidence.getManifest(), run, rebasingRoot);
        assertEquals(relocated, inputs.get(0).getResolvedPath());
        assertEquals(3, inputs.get(0).getTier());
    }

    @Test
    void hashMismatchTerminatesWithoutSubstitution() throws Exception {
        Path run = copyFixtureTo("hash-mismatch");
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .addProperty("sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        writeManifest(run, manifest);
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunInputResolver.resolve(evidence.getManifest(), run, tempDir));
        assertEquals(RerunVerdict.INPUT_HASH_MISMATCH, failure.getVerdict());
        assertTrue(failure.getMessage().contains("Refusing to substitute"), failure.getMessage());
        assertTrue(failure.getDetails().stream().anyMatch(d -> d.startsWith("expected sha256=0000")),
                failure.getDetails().toString());
    }

    @Test
    void tierOneHashMismatchDoesNotFallThroughToTierTwo() throws Exception {
        // study inputs/ 下有同名但内容不同的文件；记录的绝对路径本身完好。
        // 契约：tier 1 命中即校验，不符即终止，不允许静默改用 tier 2。
        Path run = studyLayoutRun();
        Path studyInput = run.getParent().getParent().resolve("inputs")
                .resolve("reproducibility-workflow.dax");
        Files.createDirectories(studyInput.getParent());
        Files.write(studyInput, "corrupted content".getBytes(StandardCharsets.UTF_8));
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunInputResolver.resolve(evidence.getManifest(), run, tempDir));
        assertEquals(RerunVerdict.INPUT_HASH_MISMATCH, failure.getVerdict());
        assertTrue(failure.getMessage().contains(studyInput.toString()), failure.getMessage());
    }

    @Test
    void unresolvedListsEveryCandidateTier() throws Exception {
        Path run = studyLayoutRun();
        String fakeRoot = tempDir.resolve("another-fake-root").toString();
        JsonObject manifest = readManifest(run);
        rewriteInputPathIn(manifest, fakeRoot + "/datasets/missing.dax");
        manifest.getAsJsonObject("provenance").getAsJsonObject("execution")
                .addProperty("workingDirectory", fakeRoot);
        writeManifest(run, manifest);
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunInputResolver.resolve(evidence.getManifest(), run, tempDir));
        assertEquals(RerunVerdict.INPUT_UNRESOLVED, failure.getVerdict());
        assertEquals(3, failure.getDetails().size(), failure.getDetails().toString());
        assertTrue(failure.getDetails().get(0).startsWith("tier1: "));
        assertTrue(failure.getDetails().get(1).startsWith("tier2: "));
        assertTrue(failure.getDetails().get(2).startsWith("tier3: "));
        assertTrue(failure.getDetails().get(0).endsWith("inputs" + java.io.File.separator
                + "missing.dax"), failure.getDetails().get(0));
    }

    @Test
    void missingWorkingDirectorySkipsTierThree() throws Exception {
        // 直接驱动 resolver（不经 reader）：v4 校验器强制 workingDirectory 存在，
        // 此用例验证 resolver 自身对缺失字段的防御行为。
        JsonObject manifest = minimalManifest("/no/such/file.dax", null);
        Path run = Files.createDirectories(tempDir.resolve("defensive"));
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunInputResolver.resolve(manifest, run, tempDir));
        assertEquals(RerunVerdict.INPUT_UNRESOLVED, failure.getVerdict());
        assertEquals(1, failure.getDetails().size());
        assertTrue(failure.getDetails().get(0).startsWith("tier2: "));
    }

    @Test
    void missingRequiredInputFieldIsEvidenceInvalid() {
        JsonObject manifest = minimalManifest("/tmp/whatever.dax", "/tmp");
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject().remove("sha256");
        Path run = tempDir.resolve("whatever");
        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> RerunInputResolver.resolve(manifest, run, tempDir));
        assertEquals(RerunVerdict.EVIDENCE_INVALID, failure.getVerdict());
        assertTrue(failure.getMessage().contains("sha256"));
    }

    // ---- helpers ----

    private Path copyFixtureTo(String name) throws Exception {
        Path run = Files.createDirectories(tempDir.resolve(name));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    /** 构造 {@code <study>/runs/<run>} 布局并复制证据。 */
    private Path studyLayoutRun() throws Exception {
        Path study = Files.createDirectories(tempDir.resolve("study-" + System.nanoTime()));
        Path run = Files.createDirectories(study.resolve("runs/fixture-run"));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    private static String inputPath(JsonObject manifest) {
        return manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .get("path").getAsString();
    }

    private JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(new String(
                Files.readAllBytes(run.resolve("result.manifest.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private void writeManifest(Path run, JsonObject manifest) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(run.resolve("result.manifest.json"),
                gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    /** 同步改写 inputs/workflowPaths/workflowOutcomes 三处路径（v4 交叉校验要求）。 */
    private void rewriteInputPath(Path run, String newPath) throws Exception {
        JsonObject manifest = readManifest(run);
        rewriteInputPathIn(manifest, newPath);
        writeManifest(run, manifest);
    }

    private static void rewriteInputPathIn(JsonObject manifest, String newPath) {
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .addProperty("path", newPath);
        JsonArray workflowPaths = manifest.getAsJsonObject("configuration")
                .getAsJsonArray("workflowPaths");
        workflowPaths.set(0, new com.google.gson.JsonPrimitive(newPath));
        manifest.getAsJsonObject("result").getAsJsonArray("workflowOutcomes")
                .get(0).getAsJsonObject().addProperty("path", newPath);
    }

    private static JsonObject minimalManifest(String path, String workingDirectory) {
        JsonObject input = new JsonObject();
        input.addProperty("path", path);
        input.addProperty("sha256", "abc123");
        input.addProperty("sizeBytes", 10L);
        JsonArray inputs = new JsonArray();
        inputs.add(input);
        JsonObject manifest = new JsonObject();
        manifest.add("inputs", inputs);
        JsonObject provenance = new JsonObject();
        JsonObject execution = new JsonObject();
        if (workingDirectory != null) {
            execution.addProperty("workingDirectory", workingDirectory);
        }
        provenance.add("execution", execution);
        manifest.add("provenance", provenance);
        return manifest;
    }
}
