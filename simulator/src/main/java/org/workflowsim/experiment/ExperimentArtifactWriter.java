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
     * {@code <runId>.events.jsonl}；ON时另写独立network-ledger JSON。
     *
     * <p>只有对应新文件完整写入后，才会原子替换同名已有文件。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @param outputDirectory 存放既有三件套和可选网络账本的输出目录
     * @param runId 文件名安全的运行标识
     * @return 已写入工件的绝对路径；OFF的网络账本路径为null
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
     * @param outputDirectory 存放既有三件套和可选网络账本的输出目录
     * @param runId 文件名安全的运行标识
     * @param evidenceContext 可选的 reference 或 study 研究身份
     * @return 已写入工件的绝对路径；OFF的网络账本路径为null
     * @throws IOException 当目录或工件无法写入时抛出
     * @throws IllegalArgumentException 当报告、输出目录或运行标识不合法时抛出
     */
    public static ExperimentArtifacts write(SimulationReport report, Path outputDirectory,
            String runId, ExperimentEvidenceContext evidenceContext) throws IOException {
        if (report == null || outputDirectory == null) {
            throw new IllegalArgumentException("Report and output directory are required");
        }
        validateRunId(runId);
        if(report.getConfig().getDataMovementModel().isCoherentStorageDataflowV3())throw new UnsupportedOperationException("Storage V3 kernel capture is available, but its dedicated artifact codec/context is not enabled yet; no files were written");
        if(report.getPlatform().getSourceStorage()!=null)throw new IOException("sourceStorage is not used by the declared legacy/V2 model");
        org.workflowsim.data.NetworkEvidenceConfig option=report.getConfig().getNetworkEvidenceConfig();
        boolean group=option.getMode()==org.workflowsim.data.NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,file=option.getMode()==org.workflowsim.data.NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2;
        if(group!=(report.getNetworkEvidence()!=null)||file!=(report.getFileLifecycleEvidence()!=null))throw new IOException("Network capture disagrees with recording configuration");
        if(report.getConfig().getDataMovementModel().isCoherentFileDataflowV2()!=(report.getDataflowPlan()!=null))throw new IOException("V2 core file plan disagrees with model");
        com.google.gson.JsonObject networkDocument=null,fileDocument=null;
        try{org.workflowsim.data.NetworkEvidenceConfigCodec.requireCompatible(report.getConfig().getDataMovementModel(),option);
            if(group)networkDocument=org.workflowsim.data.NetworkLedgerCodec.document(report.getNetworkEvidence());
            if(file)fileDocument=org.workflowsim.data.v2.FileLifecycleCodec.document(report.getFileLifecycleEvidence());
        }catch(IllegalArgumentException|IllegalStateException invalid){throw new IOException("Invalid versioned network evidence before export",invalid);}
        if(group)NetworkLedgerContextValidator.validateReport(report);
        if(file)FileLifecycleContextValidator.validateReport(report,org.workflowsim.data.v2.FileLifecycleCodec.decodeDocument(fileDocument));
        else if(report.getConfig().getDataMovementModel().isCoherentFileDataflowV2())FileLifecycleContextValidator.validatePlanContext(GSON.toJsonTree(ExperimentManifestWriter.fileLifecycleContextSnapshot(report)).getAsJsonObject());
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
        Path networkLedger=null;
        if(networkDocument!=null){
            networkLedger=directory.resolve(runId+".network-ledger.json");
            writeJson(networkLedger,networkDocument);
            artifacts.add(artifact(org.workflowsim.data.NetworkLedgerCodec.ARTIFACT_ROLE,networkLedger));
        }
        Path fileLifecycle=null;
        if(fileDocument!=null){fileLifecycle=directory.resolve(runId+".file-lifecycle.json");writeJson(fileLifecycle,fileDocument);artifacts.add(artifact(org.workflowsim.data.v2.FileLifecycleCodec.ARTIFACT_ROLE,fileLifecycle));}
        ExperimentManifestWriter.writeJson(report, manifest, artifacts, evidenceContext);
        return new ExperimentArtifacts(manifest, metrics, events, networkLedger,fileLifecycle);
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

    /** {@link #write} 生成的既有工件路径和可选网络账本路径。 */
    public static final class ExperimentArtifacts {
        private final Path manifest;
        private final Path metrics;
        private final Path events;
        private final Path networkLedger;
        private final Path fileLifecycle;

        private ExperimentArtifacts(Path manifest, Path metrics, Path events, Path networkLedger,Path fileLifecycle) {
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.networkLedger = networkLedger;this.fileLifecycle=fileLifecycle;
        }

        public Path getManifest() { return manifest; }
        public Path getMetrics() { return metrics; }
        public Path getEvents() { return events; }
        /** @return network sidecar path, or null when recording was OFF */
        public Path getNetworkLedger() { return networkLedger; }
        /** @return V2 file lifecycle sidecar, null for OFF/V1 */
        public Path getFileLifecycle(){return fileLifecycle;}
    }
}
