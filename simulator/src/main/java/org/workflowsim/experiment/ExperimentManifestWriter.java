package org.workflowsim.experiment;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.workflowsim.WorkflowInputReport;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.SimulationConfig;

/** 仅在调用方显式指定输出路径时，将报告写为 manifest。 */
public final class ExperimentManifestWriter {

    /** 上一代证据 manifest schema，供只读校验器兼容历史工件。 */
    static final String SCHEMA_V3 = "workflowsim-experiment-manifest-v3";
    /** 当前证据 manifest schema，包含完整到达、成本矩阵和拓扑声明。 */
    static final String SCHEMA_V4 = "workflowsim-experiment-manifest-v4";

    private ExperimentManifestWriter() {}

    /** Standalone manifests must not silently drop requested network evidence. */
    static void requireNetworkLedgerReference(SimulationReport report, List<Map<String, Object>> artifacts) {
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()
                && (!report.getConfig().getNetworkEvidenceConfig().isEnabled()
                        || report.getDataflowComputeRequests() == null)) {
            throw new UnsupportedOperationException(
                    "Online assignment certification requires complete lifecycle capture and frozen compute"
                            + "requests; use an enabled lifecycle mode");
        }
        org.workflowsim.data.DataMovementModel model = report.getConfig().getDataMovementModel();
        if (model.isCoherentStorageDataflowV3() != (report.getPlatform().getSourceStorage() != null)) {
            throw new IllegalArgumentException("sourceStorage presence differs from its declared model");
        }
        org.workflowsim.data.NetworkEvidenceConfig option = report.getConfig().getNetworkEvidenceConfig();
        org.workflowsim.data.NetworkEvidenceConfigCodec.requireCompatible(model, option);
        String expected = org.workflowsim.data.NetworkEvidenceConfigCodec.artifactRole(option);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String role : new String[] {"network-ledger", "file-lifecycle", "storage-lifecycle"}) {
            counts.put(role, 0);
        }
        for (Map<String, Object> artifact : artifacts) {
            Object role = artifact.get("role");
            if (counts.containsKey(role)) {
                counts.put((String) role, counts.get(role) + 1);
            }
        }
        if (expected != null && counts.get(expected) == 0) {
            throw new UnsupportedOperationException(
                    "Network evidence export requires a complete artifact bundle; use ExperimentArtifactWriter");
        }
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() > 1 || (count.getValue() > 0 && !count.getKey().equals(expected))) {
                throw new IllegalArgumentException(
                        "Network evidence artifact roles disagree with recording configuration");
            }
        }
        if (model.usesCoherentDataflowRuntime() != (report.getDataflowPlan() != null)) {
            throw new IllegalArgumentException("Coherent core file plan presence disagrees with model");
        }
    }

    /** Minimal no-I/O context for the same preflight checks used by artifact readers. */
    static Map<String, Object> networkContextSnapshot(SimulationReport report) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configuration", configuration(report));
        root.put("platform", platform(report.getPlatform()));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("simulationEndSeconds", report.getSimulationEndSeconds());
        result.put("jobs", report.getJobs());
        result.put("actualVmHostAssignments", report.getActualVmHostAssignments());
        root.put("result", result);
        return root;
    }

    /** No-I/O V2 context; uses the same frozen report rather than rereading a manifest path. */
    static Map<String, Object> fileLifecycleContextSnapshot(SimulationReport report) {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("configuration", configuration(report));
        root.put("platform", platform(report.getPlatform()));
        root.put("workflowGraph", report.getWorkflowGraph());
        root.put("dataflowPlan", report.getDataflowPlan());
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()) {
            root.put("dataflowComputeRequests", report.getDataflowComputeRequests());
        }
        root.put("result", result(report));
        return root;
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
     * @throws UnsupportedOperationException if ON network evidence is exported without a complete bundle reference
     */
    public static void writeJson(SimulationReport report, Path outputFile) throws IOException {
        writeJson(report, outputFile, (ExperimentEvidenceContext) null);
    }

    /**
     * 将一份报告与可选的 reference/study 身份写为独立 JSON manifest。
     *
     * <p>未提供 {@code evidenceContext} 时，manifest 仍是 v4 格式，但 provenance 中的
     * {@code study} 字段为 {@code null}。这适合普通仿真运行，不能将其误认为已声明协议的
     * 正式研究证据。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @param outputFile manifest 输出文件
     * @param evidenceContext 可选的 reference 或 study 研究身份
     * @throws IOException 当 manifest 无法写入时抛出
     * @throws IllegalArgumentException 当报告或输出路径为空时抛出
     * @throws UnsupportedOperationException if ON network evidence is exported without a complete bundle reference
     */
    public static void writeJson(SimulationReport report, Path outputFile, ExperimentEvidenceContext evidenceContext)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        writeJson(report, outputFile, Collections.<Map<String, Object>>emptyList(), evidenceContext);
    }

    static void writeJson(SimulationReport report, Path outputFile, List<Map<String, Object>> artifacts)
            throws IOException {
        writeJson(report, outputFile, artifacts, null);
    }

    static void writeJson(
            SimulationReport report,
            Path outputFile,
            List<Map<String, Object>> artifacts,
            ExperimentEvidenceContext evidenceContext)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output path are required");
        }
        requireNetworkLedgerReference(report, artifacts);
        ExperimentArtifactWriter.writeJson(outputFile, toManifest(report, artifacts, evidenceContext));
    }

    static Map<String, Object> toManifest(SimulationReport report, List<Map<String, Object>> artifacts) {
        return toManifest(report, artifacts, null);
    }

    static Map<String, Object> toManifest(
            SimulationReport report, List<Map<String, Object>> artifacts, ExperimentEvidenceContext evidenceContext) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", SCHEMA_V4);
        manifest.put("configuration", configuration(report));
        manifest.put("platform", platform(report.getPlatform()));
        manifest.put("inputs", inputs(report));
        manifest.put("workflowProfile", report.getWorkflowProfile());
        manifest.put("workflowGraph", report.getWorkflowGraph());
        if (report.getConfig().getDataMovementModel().usesCoherentDataflowRuntime()) {
            manifest.put("dataflowPlan", report.getDataflowPlan());
        }
        if (report.getConfig().getDataflowAssignmentConfig().isEnabled()) {
            manifest.put("dataflowComputeRequests", report.getDataflowComputeRequests());
        }
        manifest.put("result", result(report));
        manifest.put("metrics", report.getMetrics());
        // BAT-E2（2026-10-27）：energyMetrics section — 4 summary fields + per-VM map；
        // independently recomputable from the manifest's own vmMetrics busySeconds + platform power fields.
        Map<String, Object> energy = new LinkedHashMap<String, Object>();
        EnergyAccounting ea = report.getEnergyAccounting();
        energy.put("totalEnergyJoules", Double.valueOf(ea.getTotalEnergyJoules()));
        energy.put("meanVmEnergyJoules", Double.valueOf(ea.getMeanVmEnergyJoules()));
        energy.put("peakPowerWatts", Double.valueOf(ea.getPeakPowerWatts()));
        energy.put("energyMakespanProductJouleSeconds", Double.valueOf(ea.getEnergyMakespanProductJouleSeconds()));
        energy.put("vmEnergyJoules", ea.getVmEnergyJoules());
        manifest.put("energyMetrics", energy);
        // BAT-C2（2026-10-28）：carbonMetrics 条件导出——未声明两种碳模型时整节缺省
        // （合法缺省≠全零，与能量节的恒在语义相区分并已在契约文档声明）。
        CarbonAccounting ca = report.getCarbonAccounting();
        if (ca != null) {
            Map<String, Object> carbon = new LinkedHashMap<String, Object>();
            carbon.put("totalCarbonGrams", Double.valueOf(ca.getTotalCarbonGrams()));
            carbon.put("meanVmCarbonGrams", Double.valueOf(ca.getMeanVmCarbonGrams()));
            carbon.put("carbonMakespanProductGramSeconds", Double.valueOf(ca.getCarbonMakespanProductGramSeconds()));
            carbon.put("perVmCarbonGrams", ca.getPerVmCarbonGrams());
            carbon.put("totalCarbonExcessWattSeconds", Double.valueOf(ca.getTotalCarbonExcessWattSeconds()));
            carbon.put("windowExcessWattSeconds", ca.getWindowExcessWattSeconds());
            manifest.put("carbonMetrics", carbon);
        }
        manifest.put("events", eventSummary(report));
        manifest.put("artifacts", new ArrayList<Map<String, Object>>(artifacts));
        manifest.put("provenance", ExperimentProvenance.capture(evidenceContext));
        manifest.put("runtime", runtime());
        return manifest;
    }

    /**
     * Produce a fresh wire-format configuration snapshot without I/O or simulation.
     * The returned transport map may be edited; the immutable input configuration is not modified.
     *
     * @param config explicit simulation configuration
     * @return the same configuration representation used in new manifests
     */
    /**
     * BAT-R9（2026-10-27 第九轮审查）manifest 口径：配置基体 + 已解析输入路径覆写——
     * 与 inputs[].path 同源，ordered-input-paths 不变量对任何喂法按构造成立；
     * 全部遗留证据两数组本已同为绝对形式，语义零漂移。
     */
    private static Map<String, Object> configuration(SimulationReport report) {
        SimulationConfig config = report.getConfig();
        Map<String, Object> values = configuration(config);
        List<String> resolvedInputPaths = new ArrayList<String>();
        for (org.workflowsim.experiment.SimulationReport.InputArtifact input : report.getInputs()) {
            resolvedInputPaths.add(input.getPath());
        }
        values.put("workflowPaths", resolvedInputPaths);
        return values;
    }

    public static Map<String, Object> configurationSnapshot(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration is required");
        }
        // 快照口径 = legacy as-fed（该公开面服务配置比对，不入证据链第 ordered 项）。
        return configuration(config);
    }

    /**
     * Produce a fresh wire-format platform snapshot without creating CloudSim entities.
     *
     * @param profile validated platform description
     * @return the same platform representation used in new manifests
     */
    public static Map<String, Object> platformSnapshot(PlatformProfile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Platform is required");
        }
        return platform(profile);
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
        values.put("executionSemantics", org.workflowsim.utils.TaskExecutionModel.EXECUTION_SEMANTICS);
        values.put("workflowPaths", new ArrayList<String>(config.getWorkflowPaths()));
        values.put("workflowArrivalSeconds", new ArrayList<Double>(config.getWorkflowArrivalSeconds()));
        values.put("workflowArrivalSemantics", "PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO");
        values.put("taskCostMatrix", taskCostMatrix(config));
        values.put("schedulingAlgorithm", config.getSchedulingAlgorithm().name());
        // BAT-3（2026-10-26）：注册算轨标签同层记录；证据身份不归属到 INVALID 枚举，
        // 复跑比较以 identical contract 键继续按精确字段比较。
        values.put(
                "registeredSchedulingAlgorithm",
                config.getRegisteredSchedulingAlgorithmProvider() == null
                        ? null
                        : config.getRegisteredSchedulingAlgorithmProvider().label());
        values.put("planningAlgorithm", config.getPlanningAlgorithm().name());
        values.put("algorithmContract", AlgorithmCatalog.forConfiguration(config));
        values.put("reduceMethod", config.getReduceMethod());
        values.put("deadline", config.getDeadline());
        values.put(
                "deadlineSemantics",
                "OBSERVATION_ONLY_FROM_SIMULATION_TIME_ZERO_AGAINST_SIMULATION_END_SECONDS_" + "NO_SCHEDULING_EFFECT");
        values.put("fileSystem", config.getFileSystem().name());
        values.put("rootSeed", config.getRandomSeed());
        values.put("runtimeScale", config.getRuntimeScale());
        values.put("runtimeReferenceMips", config.getRuntimeReferenceMips());
        values.put("cloudSimMinEventIntervalSeconds", config.getCloudSimMinEventIntervalSeconds());
        values.put("costModel", config.getCostModel().name());
        values.put(
                "modeledProcessingCostScope",
                "ABSTRACT_COST_UNITS;CPU_ENVELOPE_MAY_INCLUDE_MODELED_STAGE_IN;"
                        + "DECLARED_FILE_BYTES_ALL_JOB_FILE_ITEMS;DECIMAL_MB_1_000_000_BYTES;"
                        + "NO_INTERNAL_BILLING_ROUNDING;"
                        + "MEMORY_AND_STORAGE_PRICES_ARE_DECLARED_BUT_NOT_CHARGED_BY_THIS_MODEL");
        values.put("dataMovementModel", dataMovementModel(config.getDataMovementModel()));
        if (config.getDataflowAssignmentConfig().isEnabled()) {
            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put(
                    "mode", config.getDataflowAssignmentConfig().getMode().name());
            assignment.put("policy", config.getDataflowAssignmentConfig().getPolicy());
            values.put("dataflowAssignment", assignment);
        }
        if (config.getNetworkEvidenceConfig().isEnabled()) {
            Map<String, Object> networkEvidence = new LinkedHashMap<String, Object>();
            networkEvidence.put(
                    "mode", config.getNetworkEvidenceConfig().getMode().name());
            networkEvidence.put(
                    "maxTraceRecords", config.getNetworkEvidenceConfig().getMaxTraceRecords());
            values.put("networkEvidence", networkEvidence);
        }
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
        values.put("sourceEndpointBandwidthMbPerSecond", model.getSourceEndpointBandwidthMbPerSecond());
        values.put("contentionSemantics", contentionSemantics(model));
        values.put("transferStartSemantics", transferStartSemantics(model));
        values.put("bandwidthUnit", "DECIMAL_MB_PER_SECOND");
        return values;
    }

    static String contentionSemantics(DataMovementModel model) {
        switch (model.getKind()) {
            case LEGACY_WORKFLOWSIM_V1:
                return "LEGACY_IMPLEMENTATION_DEFINED_NO_EXPLICIT_CONTENTION_MODEL";
            case FIXED_ENDPOINT_NO_CONTENTION_V1:
                return "SERIAL_PER_JOB_NO_SHARED_LINK_CONTENTION";
            case PRE_EXECUTION_TRANSFER_DELAY_V1:
                return "PARALLEL_PARENT_GROUPS_NO_SHARED_LINK_CONTENTION";
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1:
                return "FLUID_MAX_MIN_PROGRESSIVE_FILLING_VM_ENDPOINTS_V2";
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1:
                return "FLUID_MAX_MIN_PROGRESSIVE_FILLING_VM_ENDPOINTS_AND_FAT_TREE_LINKS_V2";
            case COHERENT_FILE_DATAFLOW_V2:
                return "COHERENT_FILES_CHECKED_SHARED_MAX_MIN_V2";
            case COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2:
                return "COHERENT_FILES_ISOLATED_PATH_BOTTLENECK_V2";
            case COHERENT_STORAGE_DATAFLOW_V3:
                return "STORAGE_FILES_CHECKED_SHARED_MAX_MIN_V3";
            case COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3:
                return "STORAGE_FILES_ISOLATED_PATH_BOTTLENECK_V3";
            default:
                throw new IllegalArgumentException("Unknown data movement model " + model.getKind());
        }
    }

    static String transferStartSemantics(DataMovementModel model) {
        if (model.isCoherentStorageDataflowV3()) {
            return "CONTROL_READY_REQUEST;STORE_COMMIT_GATE_IF_SHARED;ASYNC_ALL_SUCCESSFUL_OUTPUTS_V3";
        }
        if (model.isCoherentFileDataflowV2()) {
            return "DEPENDENCY_READY_AT_OBSERVATION_V2;PER_FILE_SETTLEMENT_OBSERVATION_V2;COALESCED_FILE_DESTINATION";
        }
        if (model.isPreExecutionTransferDelayV1()) {
            return "PARENT_FINISH_BASED_ARRIVAL_ESTIMATE;EXTERNAL_AT_JOB_READY";
        }
        if (model.isPreExecutionTransferDelayWithContentionV1() || model.isFatTreeContentionV1()) {
            return "ALL_GROUPS_START_AT_JOB_READY;NO_RETROACTIVE_PARENT_PROGRESS";
        }
        return "STAGE_IN_INCLUDED_IN_JOB_EXECUTION_ENVELOPE";
    }

    private static Map<String, Object> taskCostMatrix(SimulationConfig config) {
        if (config.getTaskCostMatrix() == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("unit", "EXECUTION_SECONDS");
        values.put("runtimeConversion", "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI");
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        java.util.Map<Integer, java.util.Map<Integer, Double>> sorted =
                new java.util.TreeMap<Integer, java.util.Map<Integer, Double>>(
                        config.getTaskCostMatrix().asMap());
        for (Map.Entry<Integer, java.util.Map<Integer, Double>> task : sorted.entrySet()) {
            for (Map.Entry<Integer, Double> vm : new java.util.TreeMap<Integer, Double>(task.getValue()).entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<String, Object>();
                entry.put("taskId", task.getKey());
                entry.put("vmId", vm.getKey());
                entry.put("executionSeconds", vm.getValue());
                entries.add(entry);
            }
        }
        values.put("entries", entries);
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
        values.put(
                "method", config.getClusteringParameters().getClusteringMethod().name());
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
        values.put(
                "generatorAddressing", model.usesVmIdKeyedGeneratorRows() ? "VM_ID_KEYED_ROWS" : "DENSE_VM_ID_MATRIX");
        List<List<Map<String, Object>>> matrix = new ArrayList<>();
        for (DistributionSpec[] row : model.getGeneratorSpecs()) {
            matrix.add(failureGeneratorRow(row));
        }
        values.put("generators", matrix);
        Map<String, List<Map<String, Object>>> keyedRows = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec[]> entry :
                model.getGeneratorSpecsByVmId().entrySet()) {
            keyedRows.put(Integer.toString(entry.getKey().intValue()), failureGeneratorRow(entry.getValue()));
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
            // BAT-E4（2026-10-27）：每 VM 功率字段进入平台序列化。Python 审计器
            // （verify-study-evidence.py）以 platform.vms[].idlePowerWatts/maxPowerWatts
            // 判定 powered/unpowered 分支并复算能耗守恒律；此前 E2 导出缺这两个键，
            // 带功率运行对审计器静默呈现为无功率，能耗复算被整体跳过（红→绿锚 =
            // EnergyConservationLawTest.manifestPlatformSerializesPerVmPowerFields）。
            value.put("idlePowerWatts", vm.getIdlePowerWatts());
            value.put("maxPowerWatts", vm.getMaxPowerWatts());
            // BAT-D2 后续收口（2026-10-29，D3 前提）：每 VM 频档表仅非空时序列化（空表
            // == v1 单档平台 == 未声明，legacy manifest 的平台节字节级不变）；键序固定
            // mips/idlePowerWatts/maxPowerWatts，Python 审计器以同表复算扩展守恒律
            // E_vm = P_idle(默认档)×T_sim + Σ档[(P_max(档)−P_idle(默认档))×busy(档)]
            // （DVFS_DIMENSION_DESIGN.md §2.3/§2.4）。此前频档运行的平台节缺此键，tiered
            // 运行对审计器静默呈现为无功率平台，逐档能耗复算被整体跳过（D2 提交时标记的
            // 证据面缺口）。v1 平台功率与频档两路互斥（VmSpec 构造校验的单一真源），因此
            // tiered VM 的平面功率字段恒为 0.0，不与此节冲突。红锚上移到本批：
            // FrequencyTierAssignmentTest.manifestPlatformSerializesPerVmFrequencyTiers。
            if (vm.hasFrequencyTiers()) {
                List<Map<String, Object>> frequencyTiers = new ArrayList<Map<String, Object>>();
                for (PlatformProfile.FrequencyTier tier : vm.getFrequencyTiers()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("mips", Double.valueOf(tier.getMips()));
                    item.put("idlePowerWatts", Double.valueOf(tier.getIdlePowerWatts()));
                    item.put("maxPowerWatts", Double.valueOf(tier.getMaxPowerWatts()));
                    frequencyTiers.add(item);
                }
                value.put("frequencyTiers", frequencyTiers);
            }
            // BAT-C2（2026-10-28）：强度键仅声明时序列化（缺键与 null 均等价于"未声明"，
            // legacy manifest 的平台节字节级不变）；审计器以该键复算 grams = E/3.6e6 × I。
            // 红锚同理：若缺此键，声明强度的运行对审计器静默呈现为无碳，复算被跳过。
            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                value.put("carbonIntensityGramsPerKWh", vm.getCarbonIntensityGramsPerKWh());
            }
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
        // BAT-C2（2026-10-28）：预算模型窗口仅非空时序列化（空表==未声明）；键序固定
        // start/end/green，Python 审计器以同列表重建舰队超额积分并校验排序/不重叠。
        if (!profile.getCarbonWindows().isEmpty()) {
            List<Map<String, Object>> carbonWindows = new ArrayList<Map<String, Object>>();
            for (PlatformProfile.CarbonWindow window : profile.getCarbonWindows()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("startSeconds", Double.valueOf(window.getStartSeconds()));
                item.put("endSeconds", Double.valueOf(window.getEndSeconds()));
                item.put("greenWatts", Double.valueOf(window.getGreenWatts()));
                carbonWindows.add(item);
            }
            values.put("carbonWindows", carbonWindows);
        }
        values.put(
                "networkTopology", networkTopology(profile.getNetworkTopology(), profile.getSourceStorage() != null));
        if (profile.getSourceStorage() != null) {
            org.workflowsim.data.v2.DataflowStorageSpec spec = profile.getSourceStorage();
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("attachmentHostId", spec.getAttachmentHostId());
            source.put("readBandwidthMbPerSecond", spec.getReadBandwidthMbPerSecond());
            source.put("writeBandwidthMbPerSecond", spec.getWriteBandwidthMbPerSecond());
            source.put("networkBandwidthMbPerSecond", spec.getNetworkBandwidthMbPerSecond());
            values.put("sourceStorage", source);
        }
        return values;
    }

    private static Map<String, Object> networkTopology(
            org.workflowsim.network.NetworkTopologySpec spec, boolean boundedStore) {
        if (spec == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("kind", spec.getKind().name());
        values.put("k", spec.getK());
        values.put("linkBandwidthMbPerSecond", spec.getLinkBandwidthMbPerSecond());
        values.put("coreSwitchCount", spec.getCoreSwitchCount());
        values.put(
                "hostEdgePlacements",
                spec.getHostEdgePlacements() == null
                        ? null
                        : new java.util.TreeMap<Integer, Integer>(spec.getHostEdgePlacements()));
        values.put("defaultPlacementPolicy", "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES");
        values.put("routingPolicy", "DETERMINISTIC_AL_FARES_FAT_TREE_V1");
        values.put("linkDirectionality", "INDEPENDENT_DIRECTED_LINKS");
        values.put(
                "externalSourceRouting",
                boundedStore ? "BOUNDED_STORAGE_HOST_ATTACHMENT_V3" : "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY");
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
        values.put("jobs", jobOutcomeSerializationView(report));
        values.put("tasks", report.getTasks());
        values.put("actualVmHostAssignments", report.getActualVmHostAssignments());
        values.put("workflowOutcomes", report.getWorkflowOutcomes());
        values.put("sharedStorageDagPlanTrace", report.getSharedStorageDagPlanTrace());
        return values;
    }

    /**
     * BAT-D2（2026-10-29）：JobOutcome 的结果清单序列化视图。
     *
     * <p>v1 路径（没有任何 Job 携带频档）原样返回对象列表，manifest 字节级不变；
     * 一旦任一 Job 携带非 null 的 {@code tierMips}（D2 起平台声明了频档表），改走
     * Gson 树视图：基础字段由反射生成（字段名/序/值与 v1 路径同源），"tierMips"
     * 键<b>仅在非 null 时追加</b>——null/缺键 = v1 legacy 的合法缺省（证据合同），
     * 任务×D2 台账的档身份以 MIPS 值对平台 frequencyTiers 匹配复原。</p>
     *
     * @param report 已完成仿真的不可变报告
     * @return v1 惰性路径的 JobOutcome 列表，或逐 Job 的 JSON 树视图列表
     */
    private static Object jobOutcomeSerializationView(SimulationReport report) {
        boolean anyTierAssignment = false;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getTierMips() != null) {
                anyTierAssignment = true;
                break;
            }
        }
        if (!anyTierAssignment) {
            return report.getJobs();
        }
        // 反射默认 Gson（不序列化 null）——transient tierMips 由本视图显式追加。
        com.google.gson.Gson reflection = new com.google.gson.Gson();
        List<Object> view = new ArrayList<Object>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            com.google.gson.JsonObject item = reflection.toJsonTree(job).getAsJsonObject();
            if (job.getTierMips() != null) {
                item.addProperty("tierMips", job.getTierMips());
            }
            view.add(item);
        }
        return view;
    }

    private static Map<String, Object> eventSummary(SimulationReport report) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("schema", "workflowsim-simulation-events-v1");
        values.put("eventCount", report.getEvents().size());
        if (!report.getEvents().isEmpty()) {
            values.put("firstSequence", report.getEvents().get(0).getSequence());
            values.put(
                    "lastSequence",
                    report.getEvents().get(report.getEvents().size() - 1).getSequence());
        }
        return values;
    }
}
