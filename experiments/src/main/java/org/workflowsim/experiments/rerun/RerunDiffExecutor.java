package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.failure.RetryLimitExceededException;

/**
 * D2 阶段 6：rerun 与差异比对的统一入口（契约 §命令与输入约定、§执行流程）。
 *
 * <pre>
 * mvn -pl :workflowsim-experiments -am compile exec:java \
 *   -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
 *   -Dexec.args="&lt;run-dir&gt; &lt;output-dir&gt;"
 * </pre>
 *
 * <p>执行流程严格按契约串联：读取并校验三件套（{@link RerunEvidenceReader}）→
 * 输入定位与哈希核对（{@link RerunInputResolver}）→ 配置重建
 * （{@link ManifestConfigRebuilder}）→ 标准路径复跑并落盘新三件套
 * （{@link RerunExecutor}）→ 核心量比对（{@link EvidenceCoreDiffer}）→ 生成
 * {@code rerun-report.json} + {@code rerun-report.md}。每一步失败 fail fast，
 * verdict 映射到独立退出码（见 {@link RerunVerdict}），可直接作为 CI 门禁。</p>
 *
 * <p>语义说明：模拟执行阶段本身失败（{@link SimulationExecutionException} 或
 * {@link RetryLimitExceededException}）同样按 {@code RECONSTRUCTION_REJECTED}
 * 报告——契约把该结论定义为"历史证据与当前代码语义不兼容"的合法暴露，运行期
 * 拒绝是其中一种形态，失败原因中会注明发生在执行阶段。</p>
 */
public final class RerunDiffExecutor {

    /** 机器可读报告文件名。 */
    public static final String REPORT_JSON_NAME = "rerun-report.json";

    /** 人类可读报告文件名。 */
    public static final String REPORT_MARKDOWN_NAME = "rerun-report.md";

    /** 命令行用法错误退出码（与全部 verdict 码不重叠）。 */
    static final int EXIT_USAGE = 64;

    /** 未预期异常退出码（与全部 verdict 码不重叠）。 */
    static final int EXIT_UNEXPECTED = 70;

    private RerunDiffExecutor() {
    }

    /**
     * CLI 入口：verdict 退出码 0-5；用法错误 64；未预期异常 70。
     *
     * @param args {@code <run-dir> <output-dir>}
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: RerunDiffExecutor <run-dir> <output-dir>");
            System.exit(EXIT_USAGE);
            return;
        }
        try {
            RerunReport report = execute(Paths.get(args[0]), Paths.get(args[1]));
            System.out.println("verdict: " + report.getVerdict().name()
                    + " (exit code " + report.getExitCode() + ")");
            System.out.println("report: " + Paths.get(args[1]).toAbsolutePath().normalize()
                    .resolve(REPORT_JSON_NAME));
            System.exit(report.getExitCode());
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("rerun refused: " + e.getMessage());
            System.exit(EXIT_USAGE);
        } catch (Exception e) {
            System.err.println("rerun failed unexpectedly: " + e);
            e.printStackTrace();
            System.exit(EXIT_UNEXPECTED);
        }
    }

    /**
     * 对单个 run 执行完整 rerun + 差异比对，并把报告写入输出目录。
     *
     * <p>失败路径（verdict 非 IDENTICAL/DIVERGED）不产生 {@code rerun/} 新三件套，
     * 但仍然写出两份报告，携带失败原因与候选路径等细节。</p>
     *
     * @param runDirectory 单个 run 的证据目录（含 v4 三件套）
     * @param outputDirectory 输出目录：必须不存在或为空
     * @return 完整报告对象（与落盘内容一致）
     * @throws IOException 读取证据或写报告失败
     * @throws IllegalArgumentException 入参为空
     * @throws IllegalStateException 输出目录已存在且非空（不覆盖任何已有文件）
     */
    public static RerunReport execute(Path runDirectory, Path outputDirectory)
            throws IOException {
        if (runDirectory == null || outputDirectory == null) {
            throw new IllegalArgumentException("Run directory and output directory are required");
        }
        Path absoluteRun = runDirectory.toAbsolutePath().normalize();
        Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
        requireEmptyOrNonexistent(absoluteOutput);

        RerunReport.Builder builder = new RerunReport.Builder()
                .runDirectory(absoluteRun.toString())
                .outputDirectory(absoluteOutput.toString());
        RerunEvidence evidence = null;
        RerunReport report;
        try {
            evidence = RerunEvidenceReader.read(absoluteRun);
            RerunExecutor.RerunExecution execution =
                    RerunExecutor.execute(evidence, absoluteOutput);
            RerunEvidence rerunEvidence =
                    RerunEvidenceReader.read(execution.getRerunDirectory());
            EvidenceCoreDiffer.DiffResult diff =
                    EvidenceCoreDiffer.compare(evidence, rerunEvidence);
            report = builder
                    .verdict(diff.isIdenticalCore()
                            ? RerunVerdict.IDENTICAL_CORE : RerunVerdict.DIVERGED)
                    .inputResolution(RerunReport.inputResolutionsOf(execution.getInputs()))
                    .divergences(diff)
                    .codeIdentityNote(identityNote(diff.getOriginalSourceTreeSha256(),
                            diff.getRerunSourceTreeSha256()))
                    .build();
        } catch (RerunFailureException e) {
            report = failureReport(builder, evidence, e.getVerdict(),
                    e.getMessage(), e.getDetails());
        } catch (SimulationExecutionException | RetryLimitExceededException e) {
            report = failureReport(builder, evidence, RerunVerdict.RECONSTRUCTION_REJECTED,
                    "模拟执行阶段被当前代码拒绝复现（历史证据与当前语义不兼容）: "
                            + e.getClass().getSimpleName() + ": " + e.getMessage(),
                    Collections.<String>emptyList());
        }
        writeReports(absoluteOutput, report);
        return report;
    }

    private static RerunReport failureReport(RerunReport.Builder builder,
            RerunEvidence evidence, RerunVerdict verdict, String reason,
            List<String> details) {
        if (evidence != null) {
            builder.originalIdentity(
                    EvidenceCoreDiffer.sourceTreeSha256(evidence.getManifest()),
                    EvidenceCoreDiffer.algorithmContract(evidence.getManifest()));
        }
        return builder.verdict(verdict).failure(reason, details).build();
    }

    private static String identityNote(String original, String rerun) {
        if (original == null || rerun == null) {
            return null;
        }
        if (original.equals(rerun)) {
            return "代码版本与原始运行一致（sourceTreeSha256 相同）。";
        }
        return "⚠ 代码版本与原始运行不同：核心量若有分歧，优先排查代码变更；"
                + "核心量一致则说明该证据对代码演化稳健。";
    }

    private static void requireEmptyOrNonexistent(Path output) throws IOException {
        if (!Files.exists(output)) {
            return;
        }
        if (!Files.isDirectory(output)) {
            throw new IllegalStateException("Output path exists but is not a directory: "
                    + output);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(output)) {
            if (stream.iterator().hasNext()) {
                throw new IllegalStateException(
                        "Output directory must be empty or nonexistent: " + output);
            }
        }
    }

    private static void writeReports(Path outputDirectory, RerunReport report)
            throws IOException {
        Files.createDirectories(outputDirectory);
        Files.write(outputDirectory.resolve(REPORT_JSON_NAME),
                report.toJson().getBytes(StandardCharsets.UTF_8));
        Files.write(outputDirectory.resolve(REPORT_MARKDOWN_NAME),
                report.toMarkdown().getBytes(StandardCharsets.UTF_8));
    }
}
