package org.workflowsim.experiments.rerun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * D2 阶段 5：核心量逐位差异比对器。
 *
 * <p>契约（docs/experiments/RERUN_DIFF_CONTRACT.md §比对规则）三类字段：</p>
 * <ul>
 *   <li><b>核心量</b>——manifest 中除显式豁免外的全部字段、metrics 全部字段
 *       （除两个墙钟纳秒总量）、events 全部字段（除 attributes 中的本机耗时键）。
 *       比对方式为序列化值的<b>精确相等</b>，浮点不设 epsilon：需要容差才能通过的
 *       情形都是未修复的非确定性来源，应修源头而不是放宽比较。</li>
 *   <li><b>易变量</b>——显式枚举的白名单（provenance/runtime 全子树、artifacts 哈希、
 *       输入与产出绝对路径、墙钟纳秒字段）。白名单外的任何字段一律按核心量比对；
 *       新增豁免必须走契约修订，不允许实现时随手放宽。</li>
 *   <li><b>身份信息</b>——{@code provenance.core.sourceTreeSha256} 与
 *       {@code configuration.algorithmContract}，不参与判定，单独提取供报告醒目展示。</li>
 * </ul>
 *
 * <p>本类不做 verdict 裁决与落盘，只产出结构化差异结果；verdict 由阶段 6 的
 * 报告执行器结合输入定位结果确定。</p>
 */
public final class EvidenceCoreDiffer {

    /** metrics 中豁免的本机墙钟纳秒字段（契约逐项点名，禁止扩散）。 */
    public static final Set<String> VOLATILE_METRIC_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "totalSchedulingDecisionWallClockNanos",
                    "totalPlanningDecisionWallClockNanos")));

    /** 事件 attributes 中豁免的本机耗时键（REPRODUCIBILITY 契约声明排除在指纹外）。 */
    public static final Set<String> VOLATILE_EVENT_ATTRIBUTE_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList(
                    "decisionElapsedNanos",
                    "planningDecisionElapsedNanos")));

    private static final List<String> VOLATILE_SUBTREE_PREFIXES = Collections.unmodifiableList(
            Arrays.asList("/provenance", "/runtime"));

    private static final List<Pattern> VOLATILE_POINTER_PATTERNS = Collections.unmodifiableList(
            Arrays.asList(
                    // 身份声明：随代码演化合法变化，不参与核心量判定，单独提取报告。
                    Pattern.compile("/configuration/algorithmContract"),
                    Pattern.compile("/inputs/\\d+/path"),
                    Pattern.compile("/result/workflowOutcomes/\\d+/path"),
                    Pattern.compile("/artifacts/\\d+/(sha256|sizeBytes)"),
                    Pattern.compile("/metrics/(totalSchedulingDecisionWallClockNanos"
                            + "|totalPlanningDecisionWallClockNanos)"),
                    Pattern.compile("/events/\\d+/attributes/"
                            + "(decisionElapsedNanos|planningDecisionElapsedNanos)")));

    /** 单侧值序列化后的最大记录长度，超过即截断。 */
    private static final int MAX_VALUE_CHARS = 120;

    private static final Gson COMPACT = new GsonBuilder().serializeNulls().create();

    private EvidenceCoreDiffer() {
    }

    /**
     * 比对原始证据与 rerun 新证据的全部核心量。
     *
     * @param original 原始 run 的证据（manifest 已解析，sidecar 文件可读）
     * @param rerun rerun 新产出的证据
     * @return 结构化差异结果（核心量分歧清单、易变量差异记录、双侧身份信息）
     * @throws IOException sidecar 文件不可读
     */
    public static DiffResult compare(RerunEvidence original, RerunEvidence rerun)
            throws IOException {
        if (original == null || rerun == null) {
            throw new IllegalArgumentException("Original and rerun evidence are required");
        }
        List<Divergence> core = new ArrayList<Divergence>();
        List<Divergence> volatileNoted = new ArrayList<Divergence>();

        compareJson("", original.getManifest(), rerun.getManifest(), false, core, volatileNoted);
        compareJson("", readJson(original.getMetricsPath()), readJson(rerun.getMetricsPath()),
                false, core, volatileNoted);
        compareEvents(original.getEventsPath(), rerun.getEventsPath(), core, volatileNoted);

        return new DiffResult(core, volatileNoted,
                sourceTreeSha256(original.getManifest()), sourceTreeSha256(rerun.getManifest()),
                algorithmContract(original.getManifest()), algorithmContract(rerun.getManifest()));
    }

    // ---- manifest 与 metrics 的通用递归比对 ----

    private static void compareJson(String pointer, JsonElement original, JsonElement rerun,
            boolean volatileScope, List<Divergence> core, List<Divergence> volatileNoted) {
        if (volatileScope || isVolatileSubtreeRoot(pointer)) {
            // 易变子树：递归到叶子，仅记录差异，不参与核心量判定。
            if (original != null && rerun != null
                    && original.isJsonObject() && rerun.isJsonObject()) {
                JsonObject a = original.getAsJsonObject();
                JsonObject b = rerun.getAsJsonObject();
                Set<String> keys = new TreeSet<String>();
                keys.addAll(a.keySet());
                keys.addAll(b.keySet());
                for (String key : keys) {
                    compareJson(pointer + "/" + key, a.get(key), b.get(key),
                            true, core, volatileNoted);
                }
                return;
            }
            if (original != null && rerun != null
                    && original.isJsonArray() && rerun.isJsonArray()) {
                JsonArray a = original.getAsJsonArray();
                JsonArray b = rerun.getAsJsonArray();
                if (a.size() != b.size()) {
                    volatileNoted.add(new Divergence(pointer,
                            "array length " + a.size(), "array length " + b.size()));
                }
                for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                    compareJson(pointer + "/" + i, a.get(i), b.get(i),
                            true, core, volatileNoted);
                }
                return;
            }
            if (!nullSafeEquals(original, rerun)) {
                volatileNoted.add(new Divergence(pointer, describe(original), describe(rerun)));
            }
            return;
        }
        if (isVolatileLeaf(pointer)) {
            if (!nullSafeEquals(original, rerun)) {
                volatileNoted.add(new Divergence(pointer, describe(original), describe(rerun)));
            }
            return;
        }
        if (original == null || rerun == null) {
            if (!nullSafeEquals(original, rerun)) {
                core.add(new Divergence(pointer, describe(original), describe(rerun)));
            }
            return;
        }
        if (original.isJsonObject() && rerun.isJsonObject()) {
            JsonObject a = original.getAsJsonObject();
            JsonObject b = rerun.getAsJsonObject();
            Set<String> keys = new TreeSet<String>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String key : keys) {
                compareJson(pointer + "/" + key, a.get(key), b.get(key),
                        false, core, volatileNoted);
            }
            return;
        }
        if (original.isJsonArray() && rerun.isJsonArray()) {
            JsonArray a = original.getAsJsonArray();
            JsonArray b = rerun.getAsJsonArray();
            if (a.size() != b.size()) {
                core.add(new Divergence(pointer, "array length " + a.size(),
                        "array length " + b.size()));
            }
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                compareJson(pointer + "/" + i, a.get(i), b.get(i), false, core, volatileNoted);
            }
            return;
        }
        if (!nullSafeEquals(original, rerun)) {
            core.add(new Divergence(pointer, describe(original), describe(rerun)));
        }
    }

    // ---- events 逐行比对（行数即事件总数） ----

    private static void compareEvents(Path originalEvents, Path rerunEvents,
            List<Divergence> core, List<Divergence> volatileNoted) throws IOException {
        List<String> originalLines = Files.readAllLines(originalEvents, StandardCharsets.UTF_8);
        List<String> rerunLines = Files.readAllLines(rerunEvents, StandardCharsets.UTF_8);
        if (originalLines.size() != rerunLines.size()) {
            core.add(new Divergence("/events/eventCount",
                    String.valueOf(originalLines.size()), String.valueOf(rerunLines.size())));
        }
        for (int i = 0; i < Math.min(originalLines.size(), rerunLines.size()); i++) {
            JsonElement a = JsonParser.parseString(originalLines.get(i));
            JsonElement b = JsonParser.parseString(rerunLines.get(i));
            compareJson("/events/" + i, a, b, false, core, volatileNoted);
        }
    }

    // ---- 豁免白名单判定（契约 §易变量，显式枚举） ----

    /** 整个子树豁免（provenance/runtime）；子树内递归到叶子仅做记录。 */
    private static boolean isVolatileSubtreeRoot(String pointer) {
        for (String prefix : VOLATILE_SUBTREE_PREFIXES) {
            if (pointer.equals(prefix) || pointer.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 精确枚举的叶子豁免（路径、哈希、墙钟纳秒、身份声明）。 */
    private static boolean isVolatileLeaf(String pointer) {
        for (Pattern pattern : VOLATILE_POINTER_PATTERNS) {
            if (pattern.matcher(pointer).matches()) {
                return true;
            }
        }
        return false;
    }

    // ---- 身份信息提取（不参与判定，供报告醒目展示） ----

    private static String sourceTreeSha256(JsonObject manifest) {
        JsonElement value = manifest;
        for (String key : Arrays.asList("provenance", "core", "sourceTreeSha256")) {
            if (!(value instanceof JsonObject)
                    || !((JsonObject) value).has(key)) {
                return null;
            }
            value = ((JsonObject) value).get(key);
        }
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String algorithmContract(JsonObject manifest) {
        JsonObject configuration = manifest.getAsJsonObject("configuration");
        if (configuration == null || !configuration.has("algorithmContract")) {
            return null;
        }
        JsonElement value = configuration.get("algorithmContract");
        return value.isJsonNull() ? null : COMPACT.toJson(value);
    }

    // ---- 小工具 ----

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static boolean nullSafeEquals(JsonElement a, JsonElement b) {
        if (a == null) {
            return b == null;
        }
        if (b == null) {
            return false;
        }
        return a.equals(b);
    }

    /** 序列化值描述：null 缺失显式标注，超长截断并记录总长度。 */
    private static String describe(JsonElement value) {
        if (value == null) {
            return "<missing>";
        }
        String text = COMPACT.toJson(value);
        if (text.length() <= MAX_VALUE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_VALUE_CHARS) + "…[truncated:" + text.length() + "]";
    }

    /** 一条差异：JSON 指针 + 双侧值（已截断）。 */
    public static final class Divergence {

        private final String pointer;
        private final String originalValue;
        private final String rerunValue;

        Divergence(String pointer, String originalValue, String rerunValue) {
            this.pointer = pointer;
            this.originalValue = originalValue;
            this.rerunValue = rerunValue;
        }

        /** 分歧字段的 JSON 指针（如 {@code /result/makespanSeconds}）。 */
        public String getPointer() {
            return pointer;
        }

        public String getOriginalValue() {
            return originalValue;
        }

        public String getRerunValue() {
            return rerunValue;
        }

        @Override
        public String toString() {
            return pointer + ": " + originalValue + " -> " + rerunValue;
        }
    }

    /** 比对结果：核心量分歧、易变量差异记录、双侧身份信息。 */
    public static final class DiffResult {

        private final List<Divergence> coreDivergences;
        private final List<Divergence> volatileNoted;
        private final String originalSourceTreeSha256;
        private final String rerunSourceTreeSha256;
        private final String originalAlgorithmContract;
        private final String rerunAlgorithmContract;

        DiffResult(List<Divergence> coreDivergences, List<Divergence> volatileNoted,
                String originalSourceTreeSha256, String rerunSourceTreeSha256,
                String originalAlgorithmContract, String rerunAlgorithmContract) {
            this.coreDivergences = Collections.unmodifiableList(
                    new ArrayList<Divergence>(coreDivergences));
            this.volatileNoted = Collections.unmodifiableList(
                    new ArrayList<Divergence>(volatileNoted));
            this.originalSourceTreeSha256 = originalSourceTreeSha256;
            this.rerunSourceTreeSha256 = rerunSourceTreeSha256;
            this.originalAlgorithmContract = originalAlgorithmContract;
            this.rerunAlgorithmContract = rerunAlgorithmContract;
        }

        /** 核心量分歧清单（空 = 全部逐位一致）。 */
        public List<Divergence> getCoreDivergences() {
            return coreDivergences;
        }

        /** 核心量是否全部逐位一致。 */
        public boolean isIdenticalCore() {
            return coreDivergences.isEmpty();
        }

        /** 白名单内取值发生变化的易变字段记录（仅记录，不参与判定）。 */
        public List<Divergence> getVolatileNoted() {
            return volatileNoted;
        }

        public String getOriginalSourceTreeSha256() {
            return originalSourceTreeSha256;
        }

        public String getRerunSourceTreeSha256() {
            return rerunSourceTreeSha256;
        }

        /** 代码树哈希是否一致（rerun 侧哈希即当前代码的哈希）。 */
        public boolean isCodeIdentityMatching() {
            return originalSourceTreeSha256 != null
                    && originalSourceTreeSha256.equals(rerunSourceTreeSha256);
        }

        public String getOriginalAlgorithmContract() {
            return originalAlgorithmContract;
        }

        public String getRerunAlgorithmContract() {
            return rerunAlgorithmContract;
        }
    }
}
