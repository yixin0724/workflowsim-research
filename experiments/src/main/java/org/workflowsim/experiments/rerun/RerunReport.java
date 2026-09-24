package org.workflowsim.experiments.rerun;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * D2 阶段 6：rerun 差异比对报告（契约 §报告格式）。
 *
 * <p>机器可读形态为 {@code rerun-report.json}（schema
 * {@code workflowsim-rerun-report-v1}），人类可读形态为 {@code rerun-report.md}。
 * 字段与契约一一对应：{@code verdict}、{@code inputResolution}、
 * {@code coreDivergences}、{@code volatileFieldsNoted}、{@code codeIdentityNote}
 * 与两侧 {@code sourceTreeSha256}；失败路径额外携带 {@code failureReason} 与
 * {@code failureDetails}（如 INPUT_UNRESOLVED 的候选路径清单）。</p>
 *
 * <p>本类只做数据承载与序列化，不做 verdict 裁决。</p>
 */
public final class RerunReport {

    /** 报告 schema 标识。 */
    public static final String SCHEMA_V1 = "workflowsim-rerun-report-v1";

    private static final Gson JSON = new GsonBuilder()
            .setPrettyPrinting().serializeNulls().create();

    private final String schema = SCHEMA_V1;
    private final String verdict;
    private final int exitCode;
    private final String runDirectory;
    private final String outputDirectory;
    private final String failureReason;
    private final List<String> failureDetails;
    private final List<InputResolution> inputResolution;
    private final List<DivergenceEntry> coreDivergences;
    private final List<DivergenceEntry> volatileFieldsNoted;
    private final String codeIdentityNote;
    private final String originalSourceTreeSha256;
    private final String rerunSourceTreeSha256;
    private final String originalAlgorithmContract;
    private final String rerunAlgorithmContract;

    RerunReport(Builder builder) {
        this.verdict = builder.verdict.name();
        this.exitCode = builder.verdict.getExitCode();
        this.runDirectory = builder.runDirectory;
        this.outputDirectory = builder.outputDirectory;
        this.failureReason = builder.failureReason;
        this.failureDetails = immutable(builder.failureDetails);
        this.inputResolution = immutable(builder.inputResolution);
        this.coreDivergences = immutable(builder.coreDivergences);
        this.volatileFieldsNoted = immutable(builder.volatileFieldsNoted);
        this.codeIdentityNote = builder.codeIdentityNote;
        this.originalSourceTreeSha256 = builder.originalSourceTreeSha256;
        this.rerunSourceTreeSha256 = builder.rerunSourceTreeSha256;
        this.originalAlgorithmContract = builder.originalAlgorithmContract;
        this.rerunAlgorithmContract = builder.rerunAlgorithmContract;
    }

    public RerunVerdict getVerdict() {
        return RerunVerdict.valueOf(verdict);
    }

    public int getExitCode() {
        return exitCode;
    }

    public String getRunDirectory() {
        return runDirectory;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    /** 失败原因（成功结论为 null）。 */
    public String getFailureReason() {
        return failureReason;
    }

    /** 失败附加细节（如候选路径清单）；成功结论为空列表。 */
    public List<String> getFailureDetails() {
        return failureDetails;
    }

    public List<InputResolution> getInputResolution() {
        return inputResolution;
    }

    public List<DivergenceEntry> getCoreDivergences() {
        return coreDivergences;
    }

    public List<DivergenceEntry> getVolatileFieldsNoted() {
        return volatileFieldsNoted;
    }

    /** 代码身份说明；两侧哈希任一未知时为 null。 */
    public String getCodeIdentityNote() {
        return codeIdentityNote;
    }

    public String getOriginalSourceTreeSha256() {
        return originalSourceTreeSha256;
    }

    public String getRerunSourceTreeSha256() {
        return rerunSourceTreeSha256;
    }

    public String getOriginalAlgorithmContract() {
        return originalAlgorithmContract;
    }

    public String getRerunAlgorithmContract() {
        return rerunAlgorithmContract;
    }

    /** 机器可读 JSON（pretty，含 null 键，便于跨版本 diff）。 */
    public String toJson() {
        return JSON.toJson(this);
    }

    /** 人类可读 markdown 摘要。 */
    public String toMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# Rerun 差异比对报告\n\n");
        md.append("- **verdict: `").append(verdict).append("`**（退出码 ")
                .append(exitCode).append("）\n");
        md.append("- 原始 run：`").append(runDirectory).append("`\n");
        md.append("- 输出目录：`").append(outputDirectory).append("`\n\n");

        md.append("## 代码身份\n\n");
        if (codeIdentityNote == null) {
            md.append("（未取得两侧源码树哈希，无法比较）\n\n");
        } else {
            md.append(codeIdentityNote).append("\n\n");
            md.append("- 原始 sourceTreeSha256：`").append(originalSourceTreeSha256)
                    .append("`\n");
            md.append("- 当前 sourceTreeSha256：`").append(rerunSourceTreeSha256)
                    .append("`\n\n");
        }
        if (originalAlgorithmContract != null || rerunAlgorithmContract != null) {
            boolean same = originalAlgorithmContract != null
                    && originalAlgorithmContract.equals(rerunAlgorithmContract);
            md.append("- algorithmContract：")
                    .append(same ? "两侧一致" : "两侧不同或缺失，见 JSON 报告字段")
                    .append("\n\n");
        }

        if (failureReason != null) {
            md.append("## 失败原因\n\n");
            md.append("```\n").append(failureReason).append("\n```\n\n");
            if (!failureDetails.isEmpty()) {
                md.append("细节：\n\n");
                for (String detail : failureDetails) {
                    md.append("- `").append(detail).append("`\n");
                }
                md.append("\n");
            }
        }

        md.append("## 输入定位（").append(inputResolution.size()).append(" 项）\n\n");
        if (inputResolution.isEmpty()) {
            md.append("（未进入输入定位或定位失败，见失败原因）\n\n");
        } else {
            md.append("| # | 记录路径 | 命中路径 | 层级 | sha256 核对 |\n");
            md.append("|---|---|---|---|---|\n");
            for (int i = 0; i < inputResolution.size(); i++) {
                InputResolution entry = inputResolution.get(i);
                md.append("| ").append(i).append(" | `").append(entry.recordedPath)
                        .append("` | `").append(entry.resolvedPath)
                        .append("` | ").append(entry.tier)
                        .append(" | ").append(entry.verified ? "通过" : "未通过")
                        .append(" |\n");
            }
            md.append("\n");
        }

        md.append("## 核心量分歧（").append(coreDivergences.size()).append(" 处）\n\n");
        appendDivergences(md, coreDivergences, "核心量逐位一致，无任何分歧。");
        md.append("## 易变量差异（仅记录，")
                .append(volatileFieldsNoted.size()).append(" 处）\n\n");
        appendDivergences(md, volatileFieldsNoted, "（白名单内字段两侧取值一致）");
        return md.toString();
    }

    private static void appendDivergences(StringBuilder md, List<DivergenceEntry> entries,
            String emptyNote) {
        if (entries.isEmpty()) {
            md.append(emptyNote).append("\n\n");
            return;
        }
        for (DivergenceEntry entry : entries) {
            md.append("- `").append(entry.pointer).append("`：`")
                    .append(entry.original).append("` → `")
                    .append(entry.rerun).append("`\n");
        }
        md.append("\n");
    }

    /** 从 JSON 文本解析报告（测试与下游工具消费用）。 */
    public static JsonObject parseJson(String json) {
        return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    }

    private static <T> List<T> immutable(List<T> list) {
        return list == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(list));
    }

    /** 单个输入的命中路径与哈希核对结果。 */
    public static final class InputResolution {

        private final String recordedPath;
        private final String resolvedPath;
        private final int tier;
        private final String sha256;
        private final long sizeBytes;
        private final boolean verified;

        InputResolution(String recordedPath, String resolvedPath, int tier,
                String sha256, long sizeBytes, boolean verified) {
            this.recordedPath = recordedPath;
            this.resolvedPath = resolvedPath;
            this.tier = tier;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
            this.verified = verified;
        }

        public String getRecordedPath() {
            return recordedPath;
        }

        public String getResolvedPath() {
            return resolvedPath;
        }

        /** 命中层级：1=study inputs/，2=记录绝对路径，3=workingDirectory 重定位。 */
        public int getTier() {
            return tier;
        }

        public String getSha256() {
            return sha256;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        /** sha256 与 sizeBytes 是否与 manifest 记录核对通过。 */
        public boolean isVerified() {
            return verified;
        }
    }

    /** 一条差异记录：JSON 指针 + 双侧值（超长已在 differ 内截断）。 */
    public static final class DivergenceEntry {

        private final String pointer;
        private final String original;
        private final String rerun;

        DivergenceEntry(String pointer, String original, String rerun) {
            this.pointer = pointer;
            this.original = original;
            this.rerun = rerun;
        }

        public String getPointer() {
            return pointer;
        }

        public String getOriginal() {
            return original;
        }

        public String getRerun() {
            return rerun;
        }
    }

    /** 报告构建器。 */
    static final class Builder {

        private RerunVerdict verdict;
        private String runDirectory;
        private String outputDirectory;
        private String failureReason;
        private List<String> failureDetails;
        private List<InputResolution> inputResolution;
        private List<DivergenceEntry> coreDivergences;
        private List<DivergenceEntry> volatileFieldsNoted;
        private String codeIdentityNote;
        private String originalSourceTreeSha256;
        private String rerunSourceTreeSha256;
        private String originalAlgorithmContract;
        private String rerunAlgorithmContract;

        Builder verdict(RerunVerdict value) {
            this.verdict = value;
            return this;
        }

        Builder runDirectory(String value) {
            this.runDirectory = value;
            return this;
        }

        Builder outputDirectory(String value) {
            this.outputDirectory = value;
            return this;
        }

        Builder failure(String reason, List<String> details) {
            this.failureReason = reason;
            this.failureDetails = details;
            return this;
        }

        Builder inputResolution(List<InputResolution> value) {
            this.inputResolution = value;
            return this;
        }

        Builder divergences(EvidenceCoreDiffer.DiffResult diff) {
            this.coreDivergences = entriesOf(diff.getCoreDivergences());
            this.volatileFieldsNoted = entriesOf(diff.getVolatileNoted());
            this.originalSourceTreeSha256 = diff.getOriginalSourceTreeSha256();
            this.rerunSourceTreeSha256 = diff.getRerunSourceTreeSha256();
            this.originalAlgorithmContract = diff.getOriginalAlgorithmContract();
            this.rerunAlgorithmContract = diff.getRerunAlgorithmContract();
            return this;
        }

        Builder codeIdentityNote(String value) {
            this.codeIdentityNote = value;
            return this;
        }

        Builder originalIdentity(String sourceTreeSha256, String algorithmContract) {
            this.originalSourceTreeSha256 = sourceTreeSha256;
            this.originalAlgorithmContract = algorithmContract;
            return this;
        }

        RerunReport build() {
            if (verdict == null || runDirectory == null || outputDirectory == null) {
                throw new IllegalStateException(
                        "Verdict, runDirectory and outputDirectory are required");
            }
            return new RerunReport(this);
        }

        private static List<DivergenceEntry> entriesOf(
                List<EvidenceCoreDiffer.Divergence> divergences) {
            List<DivergenceEntry> entries = new ArrayList<DivergenceEntry>();
            for (EvidenceCoreDiffer.Divergence divergence : divergences) {
                entries.add(new DivergenceEntry(divergence.getPointer(),
                        divergence.getOriginalValue(), divergence.getRerunValue()));
            }
            return entries;
        }
    }

    /** 供执行器把 resolver 结果转成报告条目。 */
    static List<InputResolution> inputResolutionsOf(RerunInputs inputs) {
        List<InputResolution> entries = new ArrayList<InputResolution>();
        if (inputs == null) {
            return entries;
        }
        for (RerunInputs.ResolvedInput input : inputs.getResolved()) {
            // resolver 返回即代表 sha256/sizeBytes 已核对通过（不符会抛异常）。
            entries.add(new InputResolution(input.getRecordedPath(),
                    input.getResolvedPath().toString(), input.getTier(),
                    input.getSha256(), input.getSizeBytes(), true));
        }
        return entries;
    }
}
