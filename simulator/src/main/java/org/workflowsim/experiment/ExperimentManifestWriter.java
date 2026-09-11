package org.workflowsim.experiment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/** 仅在调用方显式指定输出路径时，将报告写为 manifest。 */
public final class ExperimentManifestWriter {

    static final String SCHEMA_V3 = "workflowsim-experiment-manifest-v3";

    private ExperimentManifestWriter() {
    }

    /**
     * 将一份报告写为独立 JSON manifest。
     *
     * <p>此重载不生成 metrics/events sidecar；需要完整可校验的证据包时，应使用
     * {@link ExperimentArtifactWriter#write(SimulationReport, Path, String)}。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @param outputFile manifest 输出文件
     * @throws IOException 当 manifest 无法写入时抛出
     * @throws IllegalArgumentException 当报告或输出路径为空时抛出
     */
    public static void writeJson(SimulationReport report, Path outputFile) throws IOException {
        writeJson(report, outputFile, (ExperimentEvidenceContext) null);
    }

    /**
     * 将一份报告与可选的 reference/study 身份写为独立 JSON manifest。
     *
     * <p>未提供 {@code evidenceContext} 时，manifest 仍是 v3 格式，但 provenance 中的
     * {@code study} 字段为 {@code null}。这适合普通仿真运行，不能将其误认为已声明协议的
     * 正式研究证据。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @param outputFile manifest 输出文件
     * @param evidenceContext 可选的 reference 或 study 研究身份
     * @throws IOException 当 manifest 无法写入时抛出
     * @throws IllegalArgumentException 当报告或输出路径为空时抛出
     */
    public static void writeJson(SimulationReport report, Path outputFile,
            ExperimentEvidenceContext evidenceContext) throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        writeJson(report, outputFile, Collections.<Map<String, Object>>emptyList(), evidenceContext);
    }

    static void writeJson(SimulationReport report, Path outputFile,
            List<Map<String, Object>> artifacts) throws IOException {
        writeJson(report, outputFile, artifacts, null);
    }

    static void writeJson(SimulationReport report, Path outputFile,
            List<Map<String, Object>> artifacts, ExperimentEvidenceContext evidenceContext)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        ExperimentArtifactWriter.writeJson(outputFile, toManifest(report, artifacts, evidenceContext));
    }

    static Map<String, Object> toManifest(SimulationReport report,
            List<Map<String, Object>> artifacts) {
        return toManifest(report, artifacts, null);
    }

    static Map<String, Object> toManifest(SimulationReport report,
            List<Map<String, Object>> artifacts, ExperimentEvidenceContext evidenceContext) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", SCHEMA_V3);
        manifest.put("configuration", configuration(report.getConfig()));
        manifest.put("platform", platform(report.getPlatform()));
        manifest.put("inputs", inputs(report));
        manifest.put("workflowProfile", report.getWorkflowProfile());
        manifest.put("result", result(report));
        manifest.put("metrics", report.getMetrics());
        manifest.put("events", eventSummary(report));
        manifest.put("artifacts", new ArrayList<Map<String, Object>>(artifacts));
        manifest.put("provenance", ExperimentProvenance.capture(evidenceContext));
        manifest.put("runtime", runtime());
        return manifest;
    }

    private static Map<String, Object> runtime() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("javaVersion", System.getProperty("java.version"));
        values.put("javaRuntimeVersion", System.getProperty("java.runtime.version"));
        values.put("javaVendor", System.getProperty("java.vendor"));
        values.put("javaVmName", System.getProperty("java.vm.name"));
        values.put("osName", System.getProperty("os.name"));
        values.put("osArch", System.getProperty("os.arch"));
        return values;
    }

    private static Map<String, Object> configuration(SimulationConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vmCount", config.getVmCount());
        values.put("schedulingAlgorithm", config.getSchedulingAlgorithm().name());
        values.put("planningAlgorithm", config.getPlanningAlgorithm().name());
        values.put("algorithmContract", AlgorithmCatalog.forConfiguration(config));
        values.put("reduceMethod", config.getReduceMethod());
        values.put("deadline", config.getDeadline());
        values.put("deadlineSemantics",
                "OBSERVATION_ONLY_FROM_SIMULATION_TIME_ZERO_AGAINST_SIMULATION_END_SECONDS_"
                + "NO_SCHEDULING_EFFECT");
        values.put("fileSystem", config.getFileSystem().name());
        values.put("rootSeed", config.getRandomSeed());
        values.put("runtimeScale", config.getRuntimeScale());
        values.put("runtimeReferenceMips", config.getRuntimeReferenceMips());
        values.put("cloudSimMinEventIntervalSeconds", config.getCloudSimMinEventIntervalSeconds());
        values.put("costModel", config.getCostModel().name());
        values.put("modeledProcessingCostScope",
                "ABSTRACT_COST_UNITS;CPU_ENVELOPE_MAY_INCLUDE_MODELED_STAGE_IN;"
                + "DECLARED_FILE_BYTES_ALL_JOB_FILE_ITEMS;DECIMAL_MB_1_000_000_BYTES;"
                + "NO_INTERNAL_BILLING_ROUNDING;"
                + "MEMORY_AND_STORAGE_PRICES_ARE_DECLARED_BUT_NOT_CHARGED_BY_THIS_MODEL");
        values.put("dataMovementModel", dataMovementModel(config.getDataMovementModel()));
        values.put("overheadModel", overheadModel(config.getOverheadModel()));
        values.put("clustering", clustering(config));
        values.put("failureModel", failureModel(config.getFailureModel()));
        return values;
    }

    private static Map<String, Object> dataMovementModel(DataMovementModel model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", model.getKind().name());
        values.put("accessLinkBandwidthMbPerSecond", model.getAccessLinkBandwidthMbPerSecond());
        values.put("accessLinkLatencySeconds", model.getAccessLinkLatencySeconds());
        values.put("sourceEndpointBandwidthMbPerSecond",
                model.getSourceEndpointBandwidthMbPerSecond());
        values.put("contentionSemantics", model.isLegacyWorkflowsimV1()
                ? "LEGACY_IMPLEMENTATION_DEFINED_NO_EXPLICIT_CONTENTION_MODEL"
                : "SERIAL_PER_JOB_NO_SHARED_LINK_CONTENTION");
        return values;
    }

    private static Map<String, Object> overheadModel(OverheadModelConfig model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("workflowEngineDelayInterval", model.getWorkflowEngineDelayInterval());
        values.put("bandwidth", model.getBandwidth());
        values.put("workflowEngineDelays", delayMap(model.getWorkflowEngineDelays()));
        values.put("queueDelays", delayMap(model.getQueueDelays()));
        values.put("postDelays", delayMap(model.getPostDelays()));
        values.put("clusteringDelays", delayMap(model.getClusteringDelays()));
        return values;
    }

    private static Map<String, Object> clustering(SimulationConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("method", config.getClusteringParameters().getClusteringMethod().name());
        values.put("clustersNum", config.getClusteringParameters().getClustersNum());
        values.put("clustersSize", config.getClusteringParameters().getClustersSize());
        values.put("code", config.getClusteringParameters().getCode());
        return values;
    }

    private static Map<String, Object> delayMap(Map<Integer, DistributionSpec> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec> entry : values.entrySet()) {
            DistributionSpec spec = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("family", spec.getFamily().name());
            item.put("scale", spec.getScale());
            item.put("shape", spec.getShape());
            item.put("priorShape", spec.getPriorShape());
            item.put("priorScale", spec.getPriorScale());
            item.put("likelihoodPrior", spec.getLikelihoodPrior());
            result.put(Integer.toString(entry.getKey()), item);
        }
        return result;
    }

    private static Map<String, Object> failureModel(FailureModelConfig model) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("clusteringAlgorithm", model.getClusteringAlgorithm().name());
        values.put("monitorMode", model.getMonitorMode().name());
        values.put("generatorMode", model.getGeneratorMode().name());
        values.put("distributionFamily", model.getDistributionFamily().name());
        values.put("maxTotalRetryJobs", model.getMaxTotalRetryJobs());
        values.put("generatorAddressing", model.usesVmIdKeyedGeneratorRows()
                ? "VM_ID_KEYED_ROWS" : "DENSE_VM_ID_MATRIX");
        List<List<Map<String, Object>>> matrix = new ArrayList<>();
        for (DistributionSpec[] row : model.getGeneratorSpecs()) {
            matrix.add(failureGeneratorRow(row));
        }
        values.put("generators", matrix);
        Map<String, List<Map<String, Object>>> keyedRows = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec[]> entry : model.getGeneratorSpecsByVmId().entrySet()) {
            keyedRows.put(Integer.toString(entry.getKey().intValue()),
                    failureGeneratorRow(entry.getValue()));
        }
        values.put("generatorsByVmId", keyedRows);
        return values;
    }

    private static List<Map<String, Object>> failureGeneratorRow(DistributionSpec[] specs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (DistributionSpec spec : specs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("family", spec.getFamily().name());
            item.put("scale", spec.getScale());
            item.put("shape", spec.getShape());
            item.put("priorShape", spec.getPriorShape());
            item.put("priorScale", spec.getPriorScale());
            item.put("likelihoodPrior", spec.getLikelihoodPrior());
            items.add(item);
        }
        return items;
    }

    private static Map<String, Object> platform(PlatformProfile profile) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", profile.getName());
        values.put("hostCount", profile.getHosts().size());
        values.put("vmCount", profile.getVms().size());
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (PlatformProfile.HostSpec host : profile.getHosts()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", host.getId());
            value.put("pes", host.getPes());
            value.put("mipsPerPe", host.getMipsPerPe());
            value.put("ramMb", host.getRamMb());
            value.put("bandwidth", host.getBandwidth());
            value.put("storageMb", host.getStorageMb());
            hosts.add(value);
        }
        List<Map<String, Object>> vms = new ArrayList<>();
        for (PlatformProfile.VmSpec vm : profile.getVms()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", vm.getId());
            value.put("mips", vm.getMips());
            value.put("pes", vm.getPes());
            value.put("ramMb", vm.getRamMb());
            value.put("bandwidth", vm.getBandwidth());
            value.put("imageSizeMb", vm.getImageSizeMb());
            value.put("vmm", vm.getVmm());
            value.put("schedulerMode", vm.getSchedulerMode().name());
            value.put("pinnedHostId", profile.getPinnedVmHostIds().get(vm.getId()));
            value.put("preflightHostId", profile.getVmHostAssignments().get(vm.getId()));
            value.put("costs", vm.hasCosts() ? costs(vm.getCosts()) : null);
            vms.add(value);
        }
        values.put("hosts", hosts);
        values.put("vms", vms);
        Map<String, Object> storage = new LinkedHashMap<>();
        storage.put("capacityMb", profile.getStorage().getCapacityMb());
        storage.put("maxTransferRateMbPerSecond", profile.getStorage().getMaxTransferRateMbPerSecond());
        values.put("storage", storage);
        values.put("costs", costs(profile.getCosts()));
        return values;
    }

    private static Map<String, Object> costs(PlatformProfile.CostSpec value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cpuPerSecond", value.getCpuPerSecond());
        result.put("memory", value.getMemory());
        result.put("storage", value.getStorage());
        result.put("bandwidth", value.getBandwidth());
        return result;
    }

    private static List<Map<String, Object>> inputs(SimulationReport report) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < report.getInputs().size(); index++) {
            SimulationReport.InputArtifact artifact = report.getInputs().get(index);
            WorkflowInputReport inputReport = report.getInputReports().get(index);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", artifact.getPath());
            value.put("sha256", artifact.getSha256());
            value.put("sizeBytes", artifact.getSizeBytes());
            value.put("format", inputReport.getFormat().name());
            value.put("declaredVersion", inputReport.getDeclaredVersion());
            value.put("taskCount", inputReport.getTaskCount());
            List<Map<String, Object>> normalizations = new ArrayList<>();
            for (WorkflowInputReport.Normalization normalization : inputReport.getNormalizations()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", normalization.getKind().name());
                item.put("subject", normalization.getSubject());
                item.put("originalValue", normalization.getOriginalValue());
                item.put("normalizedValue", normalization.getNormalizedValue());
                normalizations.add(item);
            }
            value.put("normalizations", normalizations);
            values.add(value);
        }
        return values;
    }

    private static Map<String, Object> result(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("makespan", report.getMakespan());
        values.put("simulationEndSeconds", report.getSimulationEndSeconds());
        values.put("logicalTaskCompletionStatus", report.getLogicalTaskCompletionStatus());
        values.put("logicalTaskCompletionSeconds", report.getLogicalTaskCompletionSeconds());
        values.put("terminalLifecycleTailSeconds", report.getTerminalLifecycleTailSeconds());
        values.put("workflowCompletedSuccessfully", report.isWorkflowCompletedSuccessfully());
        values.put("totalJobs", report.getTotalJobs());
        values.put("successfulJobs", report.getSuccessfulJobs());
        values.put("failedJobs", report.getFailedJobs());
        values.put("vmSummaries", report.getVmSummaries().values());
        values.put("jobs", report.getJobs());
        values.put("tasks", report.getTasks());
        values.put("actualVmHostAssignments", report.getActualVmHostAssignments());
        values.put("sharedStorageDagPlanTrace", report.getSharedStorageDagPlanTrace());
        return values;
    }

    private static Map<String, Object> eventSummary(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schema", "workflowsim-simulation-events-v1");
        values.put("eventCount", report.getEvents().size());
        if (!report.getEvents().isEmpty()) {
            values.put("firstSequence", report.getEvents().get(0).getSequence());
            values.put("lastSequence", report.getEvents().get(report.getEvents().size() - 1).getSequence());
        }
        return values;
    }
}
