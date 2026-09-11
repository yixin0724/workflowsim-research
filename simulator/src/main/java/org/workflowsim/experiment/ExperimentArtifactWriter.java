package org.workflowsim.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 为一次仿真运行写入完整、自描述的证据工件包。 */
public final class ExperimentArtifactWriter {

    /* v3 provenance 需要区分“未声明 study”和“字段未写出”，因此保留 null。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final Gson JSONL_GSON = new Gson();

    private ExperimentArtifactWriter() {
    }

    /**
     * 写入 {@code <runId>.manifest.json}、{@code <runId>.metrics.json} 和
     * {@code <runId>.events.jsonl}。
     *
     * <p>只有对应新文件完整写入后，才会原子替换同名已有文件。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @param outputDirectory 存放三个工件的输出目录
     * @param runId 文件名安全的运行标识
     * @return 三个已写入工件的绝对路径
     * @throws IOException 当目录或工件无法写入时抛出
     * @throws IllegalArgumentException 当报告、输出目录或运行标识不合法时抛出
     */
    public static ExperimentArtifacts write(SimulationReport report, Path outputDirectory,
            String runId) throws IOException {
        return write(report, outputDirectory, runId, null);
    }

    /**
     * 写入完整证据工件包，并将可选的 reference/study 身份写入 manifest。
     *
     * @param report 已完成仿真的不可变报告
     * @param outputDirectory 存放三个工件的输出目录
     * @param runId 文件名安全的运行标识
     * @param evidenceContext 可选的 reference 或 study 研究身份
     * @return 三个已写入工件的绝对路径
     * @throws IOException 当目录或工件无法写入时抛出
     * @throws IllegalArgumentException 当报告、输出目录或运行标识不合法时抛出
     */
    public static ExperimentArtifacts write(SimulationReport report, Path outputDirectory,
            String runId, ExperimentEvidenceContext evidenceContext) throws IOException {
        if (report == null || outputDirectory == null) {
            throw new IllegalArgumentException("Report and output directory are required");
        }
        validateRunId(runId);
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);

        Path metrics = directory.resolve(runId + ".metrics.json");
        Path events = directory.resolve(runId + ".events.jsonl");
        Path manifest = directory.resolve(runId + ".manifest.json");
        writeJson(metrics, metricsDocument(report));
        writeEvents(events, report.getEvents());

        List<Map<String, Object>> artifacts = new ArrayList<Map<String, Object>>();
        artifacts.add(artifact("metrics", metrics));
        artifacts.add(artifact("events", events));
        ExperimentManifestWriter.writeJson(report, manifest, artifacts, evidenceContext);
        return new ExperimentArtifacts(manifest, metrics, events);
    }

    /**
     * 将调用方组织的研究索引或摘要原子写为格式化 JSON。
     *
     * <p>reference/study 模块可用此方法写出自己的索引；它不替代
     * {@link #write(SimulationReport, Path, String)} 对单次仿真证据包的完整写入。</p>
     *
     * @param target 要写入的 JSON 文件
     * @param value 可由 Gson 序列化的值
     * @throws IOException 当文件无法写入或替换时抛出
     * @throws IllegalArgumentException 当输出路径为空时抛出
     */
    public static void writeJson(Path target, Object value) throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("JSON output path is required");
        }
        writeText(target, GSON.toJson(value));
    }

    private static void writeEvents(Path target, List<SimulationEvent> events) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        boolean completed = false;
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            for (SimulationEvent event : events) {
                writer.write(JSONL_GSON.toJson(event));
                writer.newLine();
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
        moveAtomically(temporary, absolute);
    }

    private static void writeText(Path target, String content) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, absolute.getFileName().toString(), ".tmp");
        boolean completed = false;
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporary);
            }
        }
        moveAtomically(temporary, absolute);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> metricsDocument(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        // v2：等待时间/减速比字段重命名（meanJobWaitingTimeSeconds →
        // meanJobVmQueueWaitingTimeSeconds、meanJobSlowdown → meanJobVmLevelSlowdown）
        // 并新增 meanComputeTotalWaitingTimeSeconds / meanComputeTrueSlowdown，
        // 属于破坏性契约变更，故升级 schema 版本。
        values.put("schema", "workflowsim-simulation-metrics-v2");
        values.put("metrics", report.getMetrics());
        return values;
    }

    private static Map<String, Object> artifact(String role, Path path) throws IOException {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("role", role);
        values.put("path", path.getFileName().toString());
        values.put("sha256", ExperimentProvenance.fingerprint(path));
        values.put("sizeBytes", Files.size(path));
        return values;
    }

    private static void validateRunId(String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("Run id must be a non-empty file-name-safe identifier");
        }
    }

    /** {@link #write} 生成的三个不可变证据工件路径。 */
    public static final class ExperimentArtifacts {
        private final Path manifest;
        private final Path metrics;
        private final Path events;

        private ExperimentArtifacts(Path manifest, Path metrics, Path events) {
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
        }

        public Path getManifest() { return manifest; }
        public Path getMetrics() { return metrics; }
        public Path getEvents() { return events; }
    }
}
