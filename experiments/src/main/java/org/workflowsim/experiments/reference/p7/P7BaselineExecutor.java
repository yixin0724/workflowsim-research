package org.workflowsim.experiments.reference.p7;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * P7 确定性基线批量执行器 - 生成冻结调度器对比基线的主入口。
 *
 * <p>执行完整 P7 基线矩阵（20 个 DAX 场景 × 6 个调度算法 = 120 次确定性仿真），
 * 生成标准化实验证据包（manifest/metrics/events）和索引文件。支持无参数运行（使用项目根目录下的
 * {@code datasets} 和自动创建的 {@code p7-output/run-<时间戳>} 输出目录）。
 *
 * <p><b>用法：</b>
 * <ul>
 * <li>无参数：{@code main()} - 使用默认路径（IDEA 右键运行）</li>
 * <li>指定路径：{@code main(<dataset-root>, <output-dir>)} - 自定义路径</li>
 * </ul>
 *
 * @see P7BaselineMatrix 基线场景与算法定义
 * @see P7EvidenceIndexValidator 生成的索引验证器
 */
public final class P7BaselineExecutor {

    static final String BASELINE_INDEX_FILE_NAME = "p7-baseline-index.json";
    static final String BASELINE_INDEX_SCHEMA = "workflowsim-p7-baseline-index-v3";
    static final String BASELINE_INDEX_KIND = "FROZEN_FULL_BASELINE";
    static final String SELECTION_INDEX_FILE_NAME = "p7-reference-selection-index.json";
    static final String SELECTION_INDEX_SCHEMA = "workflowsim-p7-reference-selection-index-v1";
    static final String SELECTION_INDEX_KIND = "PARTIAL_SELECTION_NOT_P7_BASELINE";
    static final String PROTOCOL_REFERENCE = P7ReferenceIdentity.PROTOCOL_FILE_NAME;

    private P7BaselineExecutor() {
    }

    /**
     * 命令行入口。
     *
     * <p>用法：{@code P7BaselineExecutor <absolute-dataset-root> <absolute-empty-output-directory>}。
     * 输出目录必须为空；执行结束后其中包含索引及每次运行的可校验证据工件。</p>
     *
     * <p>不带参数运行时（例如在 IDE 中直接 Run），默认使用当前工作目录下的
     * {@code datasets} 作为数据集根，并在 {@code p7-output/} 下创建带时间戳的空输出目录。</p>
     *
     * @param args 绝对数据集根和绝对空输出目录；留空则使用上述默认值
     * @throws Exception 当参数、输入契约、仿真执行或证据写入与校验失败时
     */
    public static void main(String[] args) throws Exception {
        if (args == null || args.length == 0) {
            // IDE 直接运行支持：默认从项目根目录推导数据集与输出目录。
            Path projectRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            Path defaultDatasets = projectRoot.resolve("datasets");
            Path defaultOutput = projectRoot.resolve("p7-output")
                    .resolve("run-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                            .format(new java.util.Date()));
            args = new String[]{defaultDatasets.toString(), defaultOutput.toString()};
            System.out.println("[P7] 未提供参数，使用默认路径：");
            System.out.println("[P7]   数据集根目录 = " + args[0]);
            System.out.println("[P7]   输出目录     = " + args[1]);
        }
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: P7BaselineExecutor "
                    + "<absolute-dataset-root> <absolute-empty-output-directory>");
        }
        Path datasetRoot = ReferenceDatasetRoot.require(Paths.get(args[0]));
        Path outputDirectory = Paths.get(args[1]);
        Log.disable();
        List<RunRecord> records;
        try {
            records = executeBaseline(datasetRoot, outputDirectory);
        } finally {
            Log.enable();
        }
        System.out.println("P7 baseline completed " + records.size() + " deterministic run(s) in "
                + outputDirectory.toAbsolutePath().normalize());
    }

    /**
     * 执行完整、冻结的 P7 基线矩阵。
     *
     * <p>该方法固定执行协议定义的四个场景和五个调度器组合，并只写出
     * {@value #BASELINE_INDEX_FILE_NAME}。随后校验器会确认索引恰好包含整个 4 x 5
     * 矩阵，因此该工件可以被称为 P7 基线。</p>
     *
     * @param datasetRoot 显式、绝对的 {@code datasets} 目录
     * @param outputDirectory 必须为空或不存在的绝对输出目录
     * @return 含相对工件路径和关键结果的不可变运行记录列表
     * @throws Exception 当目录条件、场景输入、仿真或工件校验不满足要求时
     */
    public static List<RunRecord> executeBaseline(Path datasetRoot, Path outputDirectory)
            throws Exception {
        Path normalizedDatasetRoot = ReferenceDatasetRoot.require(datasetRoot);
        return executeInternal(normalizedDatasetRoot, outputDirectory,
                P7BaselineMatrix.scenarios(normalizedDatasetRoot), P7BaselineMatrix.schedulingAlgorithms(),
                BASELINE_INDEX_FILE_NAME, BASELINE_INDEX_SCHEMA, BASELINE_INDEX_KIND);
    }

    /**
     * 执行给定的 P7 场景和调度算法子集，以支持开发和冒烟验证。
     *
     * <p>该方法会写出 {@value #SELECTION_INDEX_FILE_NAME}，并以
     * {@value #SELECTION_INDEX_KIND} 标记结果。即使选择恰好包含当前完整矩阵，调用方
     * 也不应将该输出当作冻结基线；正式 P7 基线只能通过 {@link #executeBaseline(Path, Path)}
     * 或命令行入口生成。</p>
     *
     * @param datasetRoot 显式、绝对的 {@code datasets} 目录
     * @param outputDirectory 必须为空或不存在的绝对输出目录
     * @param scenarios 要执行的冻结 P7 场景
     * @param algorithms 每个场景要评估的维护中调度算法
     * @return 含相对工件路径和关键结果的不可变运行记录列表
     * @throws Exception 当目录条件、场景输入、仿真或工件校验不满足要求时
     */
    public static List<RunRecord> executeSelection(Path datasetRoot, Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms) throws Exception {
        return executeInternal(datasetRoot, outputDirectory, scenarios, algorithms,
                SELECTION_INDEX_FILE_NAME, SELECTION_INDEX_SCHEMA, SELECTION_INDEX_KIND);
    }

    /**
     * 兼容旧调用方的子集执行入口。
     *
     * @deprecated 请使用 {@link #executeBaseline(Path, Path)} 生成完整冻结基线，或使用
     *     {@link #executeSelection(Path, Path, List, List)} 明确生成非基线的选择运行。
     */
    @Deprecated
    public static List<RunRecord> execute(Path datasetRoot, Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms) throws Exception {
        return executeSelection(datasetRoot, outputDirectory, scenarios, algorithms);
    }

    private static List<RunRecord> executeInternal(Path datasetRoot, Path outputDirectory,
            List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms, String indexFileName,
            String indexSchema, String indexKind) throws Exception {
        if (datasetRoot == null || outputDirectory == null || scenarios == null || algorithms == null
                || scenarios.isEmpty() || algorithms.isEmpty()) {
            throw new IllegalArgumentException("Dataset root, output directory, scenarios, and algorithms are required");
        }
        Path normalizedDatasetRoot = ReferenceDatasetRoot.require(datasetRoot);
        validateSelection(scenarios, algorithms);
        Path normalizedOutput = prepareEmptyOutputDirectory(outputDirectory);
        ExperimentEvidenceContext evidenceContext = P7ReferenceIdentity.context(normalizedDatasetRoot);
        List<RunRecord> records = new ArrayList<RunRecord>();
        SimulationRunner runner = new SimulationRunner();
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            PlatformProfile platform = P7BaselineMatrix.baselinePlatform(scenario);
            for (Parameters.SchedulingAlgorithm algorithm : algorithms) {
                SimulationConfig config = P7BaselineMatrix.baselineConfig(
                        scenario, algorithm, normalizedDatasetRoot);
                SimulationReport report = runner.run(config, platform);
                verifyReport(scenario, report);
                Path scenarioDirectory = normalizedOutput.resolve(scenario.getId());
                String runId = algorithm.name().toLowerCase(Locale.ROOT);
                ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                        report, scenarioDirectory, runId, evidenceContext);
                ExperimentArtifactValidator.validate(artifacts.getManifest());
                records.add(RunRecord.from(scenario, algorithm,
                        normalizedOutput.relativize(artifacts.getManifest()),
                        normalizedOutput.relativize(artifacts.getMetrics()),
                        normalizedOutput.relativize(artifacts.getEvents()), report));
            }
        }
        Path index = normalizedOutput.resolve(indexFileName);
        writeIndex(index, records, evidenceContext, indexSchema, indexKind);
        P7EvidenceIndexValidator.validate(index);
        return Collections.unmodifiableList(new ArrayList<RunRecord>(records));
    }

    private static void validateSelection(List<P7BaselineMatrix.Scenario> scenarios,
            List<Parameters.SchedulingAlgorithm> algorithms) {
        Set<String> selectedCells = new HashSet<String>();
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            if (scenario == null || !P7BaselineMatrix.isBaselineScenarioId(scenario.getId())) {
                throw new IllegalArgumentException("Scenario is outside the P7 reference matrix");
            }
            for (Parameters.SchedulingAlgorithm algorithm : algorithms) {
                if (algorithm == null || !P7BaselineMatrix.isBaselineAlgorithmName(algorithm.name())) {
                    throw new IllegalArgumentException("Scheduling algorithm is outside the P7 reference matrix");
                }
                String cell = P7BaselineMatrix.cellKey(scenario.getId(), algorithm.name());
                if (!selectedCells.add(cell)) {
                    throw new IllegalArgumentException("P7 selection contains duplicate cell: "
                            + scenario.getId() + "/" + algorithm.name());
                }
            }
        }
    }

    private static Path prepareEmptyOutputDirectory(Path outputDirectory) throws IOException {
        if (!outputDirectory.isAbsolute()) {
            throw new IllegalArgumentException("P7 output directory must be absolute: " + outputDirectory);
        }
        Path normalized = outputDirectory.normalize();
        if (Files.exists(normalized)) {
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("P7 output path is not a directory: " + normalized);
            }
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(normalized)) {
                if (entries.iterator().hasNext()) {
                    throw new IllegalArgumentException("P7 output directory must be empty: " + normalized);
                }
            }
        } else {
            Files.createDirectories(normalized);
        }
        return normalized;
    }

    private static void verifyReport(P7BaselineMatrix.Scenario scenario, SimulationReport report) {
        if (report.getInputs().size() != 1 || report.getInputReports().size() != 1) {
            throw new IllegalStateException("P7 baseline scenarios require one parsed input");
        }
        if (!scenario.getExpectedWorkflowSha256().equals(report.getInputs().get(0).getSha256())) {
            throw new IllegalStateException("P7 workflow hash mismatch for " + scenario.getId());
        }
        if (scenario.getExpectedTaskCount() != report.getInputReports().get(0).getTaskCount()) {
            throw new IllegalStateException("P7 task-count mismatch for " + scenario.getId());
        }
        if (report.getFailedJobs() != 0 || report.getSuccessfulJobs() != report.getTotalJobs()) {
            throw new IllegalStateException("P7 failure-disabled run did not complete every job: "
                    + scenario.getId());
        }
    }

    private static void writeIndex(Path indexFile, List<RunRecord> records,
            ExperimentEvidenceContext evidenceContext, String schema, String kind) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", schema);
        index.put("kind", kind);
        index.put("protocol", PROTOCOL_REFERENCE);
        index.put("referenceIdentity", evidenceContext.asMap());
        index.put("rootSeed", P7BaselineMatrix.ROOT_SEED);
        index.put("runtimeReferenceMips", P7BaselineMatrix.RUNTIME_REFERENCE_MIPS);
        index.put("runtimeScale", P7BaselineMatrix.RUNTIME_SCALE);
        index.put("cloudSimMinEventIntervalSeconds",
                P7BaselineMatrix.CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS);
        index.put("runCount", records.size());
        index.put("runs", records);
        ExperimentArtifactWriter.writeJson(indexFile, index);
    }

    /** 含相对 manifest 路径的不可变机器可读运行摘要。 */
    public static final class RunRecord {

        private final String scenarioId;
        private final String workflowFamily;
        private final int expectedTaskCount;
        private final String platformVariant;
        private final String schedulingAlgorithm;
        private final String workflowSha256;
        private final String manifest;
        private final String metrics;
        private final String events;
        private final double makespan;
        private final int totalJobs;
        private final int successfulJobs;
        private final int failedJobs;

        private RunRecord(String scenarioId, String workflowFamily, int expectedTaskCount,
                String platformVariant, String schedulingAlgorithm, String workflowSha256,
                String manifest, String metrics, String events, double makespan, int totalJobs, int successfulJobs,
                int failedJobs) {
            this.scenarioId = scenarioId;
            this.workflowFamily = workflowFamily;
            this.expectedTaskCount = expectedTaskCount;
            this.platformVariant = platformVariant;
            this.schedulingAlgorithm = schedulingAlgorithm;
            this.workflowSha256 = workflowSha256;
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.makespan = makespan;
            this.totalJobs = totalJobs;
            this.successfulJobs = successfulJobs;
            this.failedJobs = failedJobs;
        }

        private static RunRecord from(P7BaselineMatrix.Scenario scenario,
                Parameters.SchedulingAlgorithm algorithm, Path manifest, Path metrics, Path events,
                SimulationReport report) {
            return new RunRecord(scenario.getId(), scenario.getWorkflowFamily(),
                    scenario.getExpectedTaskCount(), scenario.getPlatformVariant().name(),
                    algorithm.name(), report.getInputs().get(0).getSha256(), manifest.toString(),
                    metrics.toString(), events.toString(),
                    report.getMakespan(), report.getTotalJobs(), report.getSuccessfulJobs(),
                    report.getFailedJobs());
        }

        public String getScenarioId() { return scenarioId; }
        public String getWorkflowFamily() { return workflowFamily; }
        public int getExpectedTaskCount() { return expectedTaskCount; }
        public String getPlatformVariant() { return platformVariant; }
        public String getSchedulingAlgorithm() { return schedulingAlgorithm; }
        public String getWorkflowSha256() { return workflowSha256; }
        public String getManifest() { return manifest; }
        public String getMetrics() { return metrics; }
        public String getEvents() { return events; }
        public double getMakespan() { return makespan; }
        public int getTotalJobs() { return totalJobs; }
        public int getSuccessfulJobs() { return successfulJobs; }
        public int getFailedJobs() { return failedJobs; }
    }
}
