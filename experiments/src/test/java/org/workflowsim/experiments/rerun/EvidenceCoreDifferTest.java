package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiments.rerun.EvidenceCoreDiffer.DiffResult;
import org.workflowsim.experiments.rerun.EvidenceCoreDiffer.Divergence;

/**
 * 阶段 5 验收：核心量逐位比对。同一证据自比零分歧；篡改核心量（manifest 内嵌
 * metrics、configuration 种子、事件 simulationTime、事件总数、result 数组长度）
 * 必须精确检出并给出 JSON 指针；白名单易变量（provenance/runtime/artifacts 哈希/
 * 绝对路径/事件墙钟属性）豁免且仅记录，不误伤判定；身份信息单独提取。
 */
class EvidenceCoreDifferTest {

    private static Path fixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixture() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-differ-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);
    }

    @Test
    void identicalEvidenceReportsNoCoreDivergence() throws Exception {
        RerunEvidence first = RerunEvidenceReader.read(fixtureRun);
        RerunEvidence second = RerunEvidenceReader.read(fixtureRun);

        DiffResult result = EvidenceCoreDiffer.compare(first, second);
        assertTrue(result.isIdenticalCore());
        assertTrue(result.getCoreDivergences().isEmpty());
        assertTrue(result.getVolatileNoted().isEmpty());
        assertTrue(result.isCodeIdentityMatching());
        assertEquals(result.getOriginalAlgorithmContract(), result.getRerunAlgorithmContract());
    }

    @Test
    void detectsTamperedCoreManifestFieldsWithPointers() throws Exception {
        RerunEvidence clean = RerunEvidenceReader.read(fixtureRun);
        JsonObject manifest = readManifest(fixtureRun);
        // 篡改两个核心量：内嵌 metrics 的 makespan 与配置种子。
        manifest.getAsJsonObject("metrics")
                .addProperty("makespanSeconds", 9999.5);
        manifest.getAsJsonObject("configuration")
                .addProperty("rootSeed", 92L);
        RerunEvidence tampered = inMemoryManifest(fixtureRun, manifest);

        DiffResult result = EvidenceCoreDiffer.compare(tampered, clean);
        assertFalse(result.isIdenticalCore());
        List<String> pointers = pointersOf(result.getCoreDivergences());
        assertTrue(pointers.contains("/metrics/makespanSeconds"), pointers.toString());
        assertTrue(pointers.contains("/configuration/rootSeed"), pointers.toString());
        assertEquals(2, result.getCoreDivergences().size(), result.getCoreDivergences().toString());
        // 分歧记录双侧值。
        Divergence seed = find(result.getCoreDivergences(), "/configuration/rootSeed");
        assertEquals("92", seed.getOriginalValue());
        assertEquals("91", seed.getRerunValue());
    }

    @Test
    void exemptsVolatileWhitelistFieldsAndNotesThem() throws Exception {
        RerunEvidence clean = RerunEvidenceReader.read(fixtureRun);
        JsonObject manifest = readManifest(fixtureRun);
        manifest.getAsJsonObject("provenance").getAsJsonObject("core")
                .addProperty("sourceTreeSha256", "aaaa");
        manifest.getAsJsonObject("provenance").getAsJsonObject("execution")
                .addProperty("workingDirectory", "/elsewhere");
        manifest.getAsJsonObject("runtime")
                .addProperty("osName", "Plan9");
        manifest.getAsJsonArray("artifacts").get(0).getAsJsonObject()
                .addProperty("sha256", "bbbb");
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .addProperty("path", "/somewhere/else.dax");
        manifest.getAsJsonObject("result").getAsJsonArray("workflowOutcomes")
                .get(0).getAsJsonObject().addProperty("path", "/somewhere/else.dax");
        RerunEvidence tampered = inMemoryManifest(fixtureRun, manifest);

        DiffResult result = EvidenceCoreDiffer.compare(tampered, clean);
        assertTrue(result.isIdenticalCore(), result.getCoreDivergences().toString());
        List<String> noted = pointersOf(result.getVolatileNoted());
        assertTrue(noted.contains("/provenance/core/sourceTreeSha256"), noted.toString());
        assertTrue(noted.contains("/runtime/osName"), noted.toString());
        assertTrue(noted.contains("/artifacts/0/sha256"), noted.toString());
        assertTrue(noted.contains("/inputs/0/path"), noted.toString());
        assertTrue(noted.contains("/result/workflowOutcomes/0/path"), noted.toString());
        assertFalse(result.isCodeIdentityMatching());
    }

    @Test
    void eventWallClockAttributesExemptedButCoreEventFieldsEnforced() throws Exception {
        RerunEvidence clean = RerunEvidenceReader.read(fixtureRun);
        List<String> lines = Files.readAllLines(fixtureRun.resolve("result.events.jsonl"),
                StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty());

        // 只改墙钟属性：零分歧。
        List<String> clockOnly = new ArrayList<String>();
        for (String line : lines) {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            JsonObject attributes = event.getAsJsonObject("attributes");
            if (attributes == null) {
                attributes = new JsonObject();
                event.add("attributes", attributes);
            }
            attributes.addProperty("decisionElapsedNanos", 12345L);
            attributes.addProperty("planningDecisionElapsedNanos", 67890L);
            clockOnly.add(event.toString());
        }
        Path clockOnlyPath = tempDir.resolve("events-clock-only.jsonl");
        Files.write(clockOnlyPath, clockOnly, StandardCharsets.UTF_8);
        DiffResult exempted = EvidenceCoreDiffer.compare(clean,
                inMemoryEvents(fixtureRun, clockOnlyPath));
        assertTrue(exempted.isIdenticalCore(), exempted.getCoreDivergences().toString());

        // 同时改 simulationTime：必须检出。
        List<String> tampered = new ArrayList<String>();
        for (int i = 0; i < clockOnly.size(); i++) {
            JsonObject event = JsonParser.parseString(clockOnly.get(i)).getAsJsonObject();
            event.addProperty("simulationTime",
                    event.get("simulationTime").getAsDouble() + 0.001);
            tampered.add(event.toString());
        }
        Path tamperedPath = tempDir.resolve("events-tampered.jsonl");
        Files.write(tamperedPath, tampered, StandardCharsets.UTF_8);
        DiffResult caught = EvidenceCoreDiffer.compare(clean,
                inMemoryEvents(fixtureRun, tamperedPath));
        assertFalse(caught.isIdenticalCore());
        assertTrue(pointersOf(caught.getCoreDivergences()).contains("/events/0/simulationTime"),
                caught.getCoreDivergences().toString());
        assertEquals(lines.size(), caught.getCoreDivergences().size());
    }

    @Test
    void detectsEventCountDivergence() throws Exception {
        RerunEvidence clean = RerunEvidenceReader.read(fixtureRun);
        List<String> lines = Files.readAllLines(fixtureRun.resolve("result.events.jsonl"),
                StandardCharsets.UTF_8);
        assertTrue(lines.size() > 1);
        Path truncated = tempDir.resolve("events-truncated.jsonl");
        Files.write(truncated, lines.subList(0, lines.size() - 1), StandardCharsets.UTF_8);

        DiffResult result = EvidenceCoreDiffer.compare(clean,
                inMemoryEvents(fixtureRun, truncated));
        assertFalse(result.isIdenticalCore());
        assertTrue(pointersOf(result.getCoreDivergences()).contains("/events/eventCount"),
                result.getCoreDivergences().toString());
    }

    @Test
    void detectsResultArrayLengthDivergence() throws Exception {
        RerunEvidence clean = RerunEvidenceReader.read(fixtureRun);
        JsonObject manifest = readManifest(fixtureRun);
        JsonArray tasks = manifest.getAsJsonObject("result").getAsJsonArray("tasks");
        int size = tasks.size();
        tasks.remove(size - 1);
        RerunEvidence tampered = inMemoryManifest(fixtureRun, manifest);

        DiffResult result = EvidenceCoreDiffer.compare(tampered, clean);
        assertFalse(result.isIdenticalCore());
        assertTrue(pointersOf(result.getCoreDivergences()).contains("/result/tasks"),
                result.getCoreDivergences().toString());
        Divergence length = find(result.getCoreDivergences(), "/result/tasks");
        assertEquals("array length " + (size - 1), length.getOriginalValue());
        assertEquals("array length " + size, length.getRerunValue());
    }

    @Test
    void relocatedWorkflowPathsAreVolatileButInputFingerprintsRemainCore() throws Exception {
        RerunEvidence original = RerunEvidenceReader.read(fixtureRun);
        JsonObject moved = readManifest(fixtureRun);
        String relocated = tempDir.resolve("relocated/fixture.dax").toString();
        moved.getAsJsonObject("configuration").getAsJsonArray("workflowPaths")
                .set(0, new com.google.gson.JsonPrimitive(relocated));
        moved.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("path", relocated);
        moved.getAsJsonObject("result").getAsJsonArray("workflowOutcomes")
                .get(0).getAsJsonObject().addProperty("path", relocated);

        DiffResult unchanged = EvidenceCoreDiffer.compare(original, inMemoryManifest(fixtureRun, moved));
        assertTrue(unchanged.isIdenticalCore(), unchanged.getCoreDivergences().toString());
        assertTrue(pointersOf(unchanged.getVolatileNoted()).contains("/configuration/workflowPaths/0"));

        moved.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("sha256",
                "0000000000000000000000000000000000000000000000000000000000000000");
        DiffResult differentInput = EvidenceCoreDiffer.compare(original, inMemoryManifest(fixtureRun, moved));
        assertFalse(differentInput.isIdenticalCore());
        assertTrue(pointersOf(differentInput.getCoreDivergences()).contains("/inputs/0/sha256"));

        JsonObject additionalPath = readManifest(fixtureRun);
        additionalPath.getAsJsonObject("configuration").getAsJsonArray("workflowPaths").add(relocated);
        DiffResult differentCount = EvidenceCoreDiffer.compare(original,
                inMemoryManifest(fixtureRun, additionalPath));
        assertTrue(pointersOf(differentCount.getCoreDivergences()).contains("/configuration/workflowPaths"));
    }

    @Test
    void adjacentLargeIntegerSeedsRemainDistinctAfterJsonParsing() throws Exception {
        long[][] pairs = {
                {9007199254740992L, 9007199254740993L},
                {Long.MAX_VALUE - 1, Long.MAX_VALUE},
                {Long.MIN_VALUE, Long.MIN_VALUE + 1}
        };
        for (long[] pair : pairs) {
            JsonObject first = readManifest(fixtureRun);
            JsonObject second = readManifest(fixtureRun);
            first.getAsJsonObject("configuration").addProperty("rootSeed", pair[0]);
            second.getAsJsonObject("configuration").addProperty("rootSeed", pair[1]);
            // Reparse both sides, as the public evidence reader does. Comparing two in-memory
            // Long primitives alone would miss Gson's parsed-number double coercion.
            first = JsonParser.parseString(first.toString()).getAsJsonObject();
            second = JsonParser.parseString(second.toString()).getAsJsonObject();

            DiffResult result = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                    inMemoryManifest(fixtureRun, second));

            assertFalse(result.isIdenticalCore(), pair[0] + " must differ from " + pair[1]);
            assertEquals(1, result.getCoreDivergences().size());
            Divergence seed = find(result.getCoreDivergences(), "/configuration/rootSeed");
            assertEquals(Long.toString(pair[0]), seed.getOriginalValue());
            assertEquals(Long.toString(pair[1]), seed.getRerunValue());
        }
    }

    @Test
    void numericEqualityHasNoFloatingToleranceButIgnoresEquivalentDecimalNotation() throws Exception {
        JsonObject first = readManifest(fixtureRun);
        JsonObject second = readManifest(fixtureRun);
        first.getAsJsonObject("metrics").add("makespanSeconds", JsonParser.parseString("1000.0"));
        second.getAsJsonObject("metrics").add("makespanSeconds", JsonParser.parseString("1e3"));
        assertTrue(EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second)).isIdenticalCore());

        second.getAsJsonObject("metrics").addProperty("makespanSeconds", Math.nextUp(1000.0));
        DiffResult changed = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second));
        assertFalse(changed.isIdenticalCore());
        assertTrue(pointersOf(changed.getCoreDivergences()).contains("/metrics/makespanSeconds"));
    }

    @Test
    void slashContainingRootKeysCannotImpersonateVolatilePaths() throws Exception {
        JsonObject first = readManifest(fixtureRun);
        JsonObject second = readManifest(fixtureRun);
        String[] keys = {"configuration/algorithmContract", "provenance/extra", "configuration/workflowPaths/0"};
        for (String key : keys) {
            first.addProperty(key, "before");
            second.addProperty(key, "after");
        }

        DiffResult result = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second));

        assertFalse(result.isIdenticalCore(), "literal root member names must not become nested whitelist paths");
        assertEquals(keys.length, result.getCoreDivergences().size());
        List<String> pointers = pointersOf(result.getCoreDivergences());
        assertTrue(pointers.contains("/configuration~1algorithmContract"), pointers.toString());
        assertTrue(pointers.contains("/provenance~1extra"), pointers.toString());
        assertTrue(pointers.contains("/configuration~1workflowPaths~10"), pointers.toString());
        assertTrue(result.getVolatileNoted().isEmpty(), result.getVolatileNoted().toString());
    }

    @Test
    void coreMemberPointersEscapeTildeBeforeSlash() throws Exception {
        JsonObject first = readManifest(fixtureRun);
        JsonObject second = readManifest(fixtureRun);
        first.addProperty("evidence~1/tag~", 1);
        second.addProperty("evidence~1/tag~", 2);

        DiffResult result = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second));

        assertFalse(result.isIdenticalCore());
        assertEquals(1, result.getCoreDivergences().size());
        assertEquals("/evidence~01~1tag~0", result.getCoreDivergences().get(0).getPointer());
        assertTrue(result.getVolatileNoted().isEmpty());
    }

    @Test
    void volatileSubtreeNotesAlsoUseEscapedMemberPointers() throws Exception {
        JsonObject first = readManifest(fixtureRun);
        JsonObject second = readManifest(fixtureRun);
        first.getAsJsonObject("provenance").addProperty("extra~/trace", "before");
        second.getAsJsonObject("provenance").addProperty("extra~/trace", "after");

        DiffResult result = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second));

        assertTrue(result.isIdenticalCore());
        assertEquals(1, result.getVolatileNoted().size());
        assertEquals("/provenance/extra~0~1trace", result.getVolatileNoted().get(0).getPointer());
    }

    @Test
    void actualNestedAlgorithmContractRemainsAnExplicitVolatileIdentity() throws Exception {
        JsonObject first = readManifest(fixtureRun);
        JsonObject second = readManifest(fixtureRun);
        first.getAsJsonObject("configuration").getAsJsonObject("algorithmContract")
                .addProperty("auditLabel", "before");
        second.getAsJsonObject("configuration").getAsJsonObject("algorithmContract")
                .addProperty("auditLabel", "after");

        DiffResult result = EvidenceCoreDiffer.compare(inMemoryManifest(fixtureRun, first),
                inMemoryManifest(fixtureRun, second));

        assertTrue(result.isIdenticalCore(), result.getCoreDivergences().toString());
        assertEquals(1, result.getVolatileNoted().size());
        assertEquals("/configuration/algorithmContract", result.getVolatileNoted().get(0).getPointer());
        assertFalse(result.getOriginalAlgorithmContract().equals(result.getRerunAlgorithmContract()));
    }

    // ---- helpers ----

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(new String(
                Files.readAllBytes(run.resolve("result.manifest.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 用内存 manifest 替换磁盘内容构造证据（绕过 reader 的落盘哈希语义）。 */
    private static RerunEvidence inMemoryManifest(Path run, JsonObject manifest) throws Exception {
        int eventCount = Files.readAllLines(run.resolve("result.events.jsonl"),
                StandardCharsets.UTF_8).size();
        return new RerunEvidence(run, run.resolve("result.manifest.json"),
                run.resolve("result.metrics.json"), run.resolve("result.events.jsonl"),
                eventCount, manifest);
    }

    /** 用替换后的 events 文件构造证据。 */
    private static RerunEvidence inMemoryEvents(Path run, Path eventsFile) throws Exception {
        JsonObject manifest = readManifest(run);
        int eventCount = Files.readAllLines(eventsFile, StandardCharsets.UTF_8).size();
        return new RerunEvidence(run, run.resolve("result.manifest.json"),
                run.resolve("result.metrics.json"), eventsFile, eventCount, manifest);
    }

    private static List<String> pointersOf(List<Divergence> divergences) {
        List<String> pointers = new ArrayList<String>();
        for (Divergence divergence : divergences) {
            pointers.add(divergence.getPointer());
        }
        return pointers;
    }

    private static Divergence find(List<Divergence> divergences, String pointer) {
        for (Divergence divergence : divergences) {
            if (divergence.getPointer().equals(pointer)) {
                return divergence;
            }
        }
        throw new AssertionError("No divergence at pointer " + pointer);
    }
}
