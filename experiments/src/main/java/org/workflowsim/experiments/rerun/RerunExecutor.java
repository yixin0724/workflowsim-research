package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentArtifactWriter.ExperimentArtifacts;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.exception.SimulationExecutionException;

/**
 * D2 阶段 4：完整复跑流水线——读取 v4 证据 → 三级定位输入 → 重建配置/平台 →
 * 重新仿真 → 在 {@code <output>/rerun/} 写出新三件套。
 *
 * <p>本类只负责"执行"，不做任何比对：核心量差异判定由阶段 5 的
 * {@code EvidenceCoreDiffer} 完成。所有失败路径沿用既有 verdict 语义：
 * {@code EVIDENCE_INVALID}/{@code INPUT_UNRESOLVED}/{@code INPUT_HASH_MISMATCH}/
 * {@code RECONSTRUCTION_REJECTED} 经 {@link RerunFailureException} 抛出；仿真
 * 自身的执行失败（如 {@code RetryLimitExceededException}）原样上抛，不伪装成
 * 证据问题。</p>
 *
 * <p>输出目录必须为空或不存在，避免把 rerun 产物混入既有研究目录。</p>
 */
public final class RerunExecutor {

    /** 输出目录内固定放置新三件套的子目录名（契约 §报告格式）。 */
    public static final String RERUN_DIRECTORY_NAME = "rerun";

    /** rerun 产物的 runId，与历史证据保持同构（result.*）。 */
    public static final String RERUN_RUN_ID = "result";

    private RerunExecutor() {
    }

    /**
     * 从 run 目录执行完整复跑流水线。
     *
     * @param runDirectory 含 result.manifest.json 三件套的历史 run 目录
     * @param outputDirectory 输出根目录（须为空或不存在）
     * @return 本次复跑的证据、输入定位与新三件套路径
     * @throws RerunFailureException 证据、输入或重建阶段失败（携带 verdict）
     * @throws IOException 文件系统读写失败
     * @throws SimulationExecutionException 仿真执行失败
     */
    public static RerunExecution execute(Path runDirectory, Path outputDirectory)
            throws RerunFailureException, IOException, SimulationExecutionException {
        return execute(RerunEvidenceReader.read(runDirectory), outputDirectory);
    }

    /**
     * 从已通过结构校验的证据对象执行复跑流水线（供上层复用已读取的证据）。
     *
     * @param evidence 已通过 {@link RerunEvidenceReader} 校验的证据
     * @param outputDirectory 输出根目录（须为空或不存在）
     * @return 本次复跑的证据、输入定位与新三件套路径
     * @throws RerunFailureException 输入或重建阶段失败（携带 verdict）
     * @throws IOException 文件系统读写失败
     * @throws SimulationExecutionException 仿真执行失败
     */
    public static RerunExecution execute(RerunEvidence evidence, Path outputDirectory)
            throws RerunFailureException, IOException, SimulationExecutionException {
        if (evidence == null || outputDirectory == null) {
            throw new IllegalArgumentException("Evidence and output directory are required");
        }
        Path absoluteOutput = outputDirectory.toAbsolutePath().normalize();
        refuseNonEmptyDirectory(absoluteOutput);
        RerunInputs inputs = RerunInputResolver.resolve(evidence);
        ManifestConfigRebuilder.RebuiltConfiguration rebuilt =
                ManifestConfigRebuilder.rebuild(evidence, inputs);
        SimulationReport report =
                new SimulationRunner().run(rebuilt.getConfig(), rebuilt.getPlatform());
        ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report,
                absoluteOutput.resolve(RERUN_DIRECTORY_NAME), RERUN_RUN_ID);
        return new RerunExecution(evidence, inputs, artifacts);
    }

    /** 输出目录已存在时必须为空；绝不静默覆盖或混写既有产物。 */
    private static void refuseNonEmptyDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalStateException(
                    "Output path exists but is not a directory: " + directory);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            if (stream.iterator().hasNext()) {
                throw new IllegalStateException(
                        "Output directory must be empty or nonexistent: " + directory);
            }
        }
    }

    /** 一次成功复跑的完整结果：原证据、输入定位明细与新三件套路径。 */
    public static final class RerunExecution {

        private final RerunEvidence evidence;
        private final RerunInputs inputs;
        private final ExperimentArtifacts artifacts;

        RerunExecution(RerunEvidence evidence, RerunInputs inputs,
                ExperimentArtifacts artifacts) {
            this.evidence = evidence;
            this.inputs = inputs;
            this.artifacts = artifacts;
        }

        /** 原始（历史）证据快照。 */
        public RerunEvidence getEvidence() {
            return evidence;
        }

        /** 输入定位明细（每个输入的命中层级、路径与哈希核对结果）。 */
        public RerunInputs getInputs() {
            return inputs;
        }

        /** 新三件套的绝对路径。 */
        public ExperimentArtifacts getArtifacts() {
            return artifacts;
        }

        /** 新三件套所在目录（{@code <output>/rerun/}）。 */
        public Path getRerunDirectory() {
            return artifacts.getManifest().getParent();
        }
    }
}
