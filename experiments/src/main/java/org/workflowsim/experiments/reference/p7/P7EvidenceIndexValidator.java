package org.workflowsim.experiments.reference.p7;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.platform.PlatformProfile;

/**
 * 对 P7 索引及其所有引用证据包进行只读交叉校验。
 *
 * <p>v2 是迁移前的完整基线格式；v3 额外将索引中的 reference 身份与每份 manifest 的
 * study provenance 做逐字段结构比对，防止将不同 P7 driver、协议或数据集根混入同一索引。
 * 开发性子集使用独立 schema，绝不会被验证为完整冻结基线。</p>
 */
public final class P7EvidenceIndexValidator {

    private static final String SCHEMA_V2 = "workflowsim-p7-baseline-index-v2";
    private static final String SCHEMA_V3 = P7BaselineExecutor.BASELINE_INDEX_SCHEMA;
    private static final String SELECTION_SCHEMA = P7BaselineExecutor.SELECTION_INDEX_SCHEMA;
    // 历史 v2/v3 工件写入过的协议路径必须继续可读，不能因文档改名而失效。
    private static final String HISTORICAL_PROTOCOL_REFERENCE = "P7_EXPERIMENT_PROTOCOL.md";
    private static final String HISTORICAL_PROTOCOL_ID = "docs/experiments/reference-baselines/" + HISTORICAL_PROTOCOL_REFERENCE;
    private static final String P7_STUDY_ID = "p7-baseline";
    private static final String P7_DRIVER_CLASS
            = "org.workflowsim.experiments.reference.p7.P7BaselineExecutor";

    private P7EvidenceIndexValidator() {
    }

    /**
     * 校验 P7 索引、每个相对证据路径以及索引和 manifest 的关键配置/结果一致性。
     *
     * @param indexPath 待校验的 P7 冻结基线或开发性选择索引文件
     * @return 包含索引位置和运行数量的校验结果
     * @throws IOException 当索引、引用工件或其内容不一致时抛出
     * @throws IllegalArgumentException 当 {@code indexPath} 为空时抛出
     */
    public static ValidationResult validate(Path indexPath) throws IOException {
        if (indexPath == null) {
            throw new IllegalArgumentException("P7 index path is required");
        }
        Path index = indexPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(index)) {
            throw new IOException("P7 index does not exist: " + index);
        }
        Path rootDirectory = index.getParent();
        JsonObject document = object(index, "P7 index");
        String schema = requireString(document, "schema", "P7 index");
        IndexKind indexKind = determineIndexKind(document, schema);
        boolean requiresStudyIdentity = !SCHEMA_V2.equals(schema);
        if (!isAcceptedProtocolReference(requireString(document, "protocol", "P7 index"))) {
            throw new IOException("P7 index has an unexpected protocol reference");
        }
        JsonObject referenceIdentity = requiresStudyIdentity
                ? validateV3ReferenceIdentity(requireObject(document, "referenceIdentity", "P7 index"))
                : null;
        long rootSeed = requireLong(document, "rootSeed", "P7 index");
        double runtimeReferenceMips = requireFiniteNumber(document, "runtimeReferenceMips", "P7 index");
        double runtimeScale = requireFiniteNumber(document, "runtimeScale", "P7 index");
        double cloudSimMinEventIntervalSeconds = requireFiniteNumber(document,
                "cloudSimMinEventIntervalSeconds", "P7 index");
        validateFrozenSettings(rootSeed, runtimeReferenceMips, runtimeScale,
                cloudSimMinEventIntervalSeconds, "P7 index");
        int expectedRunCount = requireInt(document, "runCount", "P7 index");
        JsonArray runs = requireArray(document, "runs", "P7 index");
        if (expectedRunCount <= 0) {
            throw new IOException("P7 index must contain at least one run");
        }
        if (runs.size() != expectedRunCount) {
            throw new IOException("P7 index runCount mismatch: declares " + expectedRunCount
                    + " but contains " + runs.size());
        }

        Set<String> uniqueCells = new HashSet<String>();
        for (JsonElement element : runs) {
            if (!element.isJsonObject()) {
                throw new IOException("P7 index run entry must be an object");
            }
            validateRun(rootDirectory, element.getAsJsonObject(), uniqueCells, rootSeed,
                    runtimeReferenceMips, runtimeScale, cloudSimMinEventIntervalSeconds,
                    requiresStudyIdentity, referenceIdentity);
        }
        if (indexKind == IndexKind.FROZEN_BASELINE
                && !P7BaselineMatrix.baselineCellKeys().equals(uniqueCells)) {
            throw new IOException("P7 baseline index does not cover the exact frozen scenario/algorithm matrix");
        }
        return new ValidationResult(index, expectedRunCount);
    }

    /** 命令行校验入口；不会写入任何文件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: P7EvidenceIndexValidator <p7-index.json>");
        }
        ValidationResult result = validate(Paths.get(args[0]));
        System.out.println("P7_INDEX_VALIDATION PASSED index=" + result.getIndex()
                + " runs=" + result.getRunCount());
    }

    private static IndexKind determineIndexKind(JsonObject document, String schema) throws IOException {
        if (SCHEMA_V2.equals(schema)) {
            return IndexKind.FROZEN_BASELINE;
        }
        if (SCHEMA_V3.equals(schema)) {
            if (!P7BaselineExecutor.BASELINE_INDEX_KIND.equals(requireString(document, "kind", "P7 index"))) {
                throw new IOException("P7 baseline index has an unexpected kind");
            }
            return IndexKind.FROZEN_BASELINE;
        }
        if (SELECTION_SCHEMA.equals(schema)) {
            if (!P7BaselineExecutor.SELECTION_INDEX_KIND.equals(requireString(document, "kind", "P7 index"))) {
                throw new IOException("P7 reference selection index has an unexpected kind");
            }
            return IndexKind.REFERENCE_SELECTION;
        }
        throw new IOException("Unsupported P7 index schema: " + schema);
    }

    private static JsonObject validateV3ReferenceIdentity(JsonObject identity) throws IOException {
        if (!P7_STUDY_ID.equals(requireString(identity, "id", "P7 reference identity"))) {
            throw new IOException("P7 reference identity has an unexpected study ID");
        }
        JsonObject component = requireObject(identity, "component", "P7 reference identity");
        requireString(component, "groupId", "P7 reference component");
        if (!"workflowsim-experiments".equals(requireString(component, "artifactId",
                "P7 reference component"))) {
            throw new IOException("P7 reference identity has an unexpected artifactId");
        }
        requireString(component, "version", "P7 reference component");
        if (!P7_DRIVER_CLASS.equals(requireString(component, "anchorClass", "P7 reference component"))) {
            throw new IOException("P7 reference identity has an unexpected driver class");
        }
        requireString(component, "sourceTreeScope", "P7 reference component");

        JsonObject protocol = requireObject(identity, "protocol", "P7 reference identity");
        if (!isAcceptedProtocolId(requireString(protocol, "logicalId", "P7 reference protocol"))) {
            throw new IOException("P7 reference identity has an unexpected protocol ID");
        }
        boolean available = requireBoolean(protocol, "available", "P7 reference protocol");
        String sha256 = requireNullableSha256(protocol, "sha256", "P7 reference protocol");
        if (available != (sha256 != null)) {
            throw new IOException("P7 reference protocol available/sha256 fields disagree");
        }

        JsonObject dataset = requireObject(identity, "dataset", "P7 reference identity");
        if (!isAbsolutePath(requireString(dataset, "root", "P7 reference dataset"))) {
            throw new IOException("P7 reference identity has a non-absolute dataset root");
        }
        if (!"EXPLICIT_ABSOLUTE_DATASET_ROOT".equals(requireString(dataset,
                "resolutionPolicy", "P7 reference dataset"))) {
            throw new IOException("P7 reference identity has an unsupported dataset resolution policy");
        }
        return identity;
    }

    private static boolean isAcceptedProtocolReference(String value) {
        return P7BaselineExecutor.PROTOCOL_REFERENCE.equals(value)
                || HISTORICAL_PROTOCOL_REFERENCE.equals(value);
    }

    private static boolean isAcceptedProtocolId(String value) {
        return P7ReferenceIdentity.PROTOCOL_ID.equals(value)
                || HISTORICAL_PROTOCOL_ID.equals(value);
    }

    private static void validateRun(Path rootDirectory, JsonObject record, Set<String> uniqueCells,
            long rootSeed, double runtimeReferenceMips, double runtimeScale,
            double cloudSimMinEventIntervalSeconds, boolean v3, JsonObject referenceIdentity)
            throws IOException {
        String scenario = requireString(record, "scenarioId", "P7 index run");
        String algorithm = requireString(record, "schedulingAlgorithm", "P7 index run");
        P7BaselineMatrix.Scenario expectedScenario = P7BaselineMatrix.scenarioById(scenario);
        if (expectedScenario == null) {
            throw new IOException("P7 index contains an unknown scenario: " + scenario);
        }
        String cell = P7BaselineMatrix.cellKey(scenario, algorithm);
        if (!P7BaselineMatrix.baselineCellKeys().contains(cell)) {
            throw new IOException("P7 index contains a cell outside the frozen matrix: "
                    + scenario + "/" + algorithm);
        }
        if (!uniqueCells.add(cell)) {
            throw new IOException("P7 index has duplicate scenario/algorithm cell: "
                    + scenario + "/" + algorithm);
        }
        validateRecordDefinition(record, expectedScenario, algorithm);
        String workflowSha256 = requireString(record, "workflowSha256", "P7 index run");
        if (!expectedScenario.getExpectedWorkflowSha256().equals(workflowSha256)) {
            throw new IOException("P7 index workflow hash differs from frozen input for " + scenario);
        }
        Path manifest = requiredRelativeFile(rootDirectory,
                requireString(record, "manifest", "P7 index run"), "manifest");
        Path metrics = requiredRelativeFile(rootDirectory,
                requireString(record, "metrics", "P7 index run"), "metrics");
        Path events = requiredRelativeFile(rootDirectory,
                requireString(record, "events", "P7 index run"), "events");
        ExperimentArtifactValidator.ValidationResult bundle = ExperimentArtifactValidator.validate(manifest);
        if (!metrics.equals(bundle.getMetrics()) || !events.equals(bundle.getEvents())) {
            throw new IOException("P7 index sidecar path does not match manifest artifacts for "
                    + scenario + "/" + algorithm);
        }

        JsonObject manifestDocument = object(manifest, "referenced experiment manifest");
        validateManifestStudyIdentity(manifestDocument, v3, referenceIdentity, scenario);
        JsonObject configuration = requireObject(manifestDocument, "configuration", "experiment manifest");
        if (!algorithm.equals(requireString(configuration, "schedulingAlgorithm", "manifest configuration"))) {
            throw new IOException("P7 index scheduler does not match manifest for " + scenario);
        }
        validateP7Configuration(configuration, expectedScenario, algorithm);
        if (rootSeed != requireLong(configuration, "rootSeed", "manifest configuration")
                || Double.compare(runtimeReferenceMips,
                        requireFiniteNumber(configuration, "runtimeReferenceMips", "manifest configuration")) != 0
                || Double.compare(runtimeScale,
                        requireFiniteNumber(configuration, "runtimeScale", "manifest configuration")) != 0
                || Double.compare(cloudSimMinEventIntervalSeconds, requireFiniteNumber(configuration,
                        "cloudSimMinEventIntervalSeconds", "manifest configuration")) != 0) {
            throw new IOException("P7 index root seed, runtime conversion, or CloudSim event cadence differs "
                    + "from manifest for " + scenario);
        }
        JsonArray inputs = requireArray(manifestDocument, "inputs", "experiment manifest");
        if (inputs.size() != 1 || !inputs.get(0).isJsonObject()) {
            throw new IOException("P7 manifest must reference exactly one workflow input for " + scenario);
        }
        validateP7Input(inputs.get(0).getAsJsonObject(), expectedScenario, workflowSha256,
                referenceIdentity, scenario);
        validateP7Platform(requireObject(manifestDocument, "platform", "experiment manifest"),
                expectedScenario, scenario);
        JsonObject result = requireObject(manifestDocument, "result", "experiment manifest");
        validateActualVmHostAssignments(result, expectedScenario, scenario);
        compareRecordNumber(record, result, "makespan", scenario);
        compareRecordInteger(record, result, "totalJobs", scenario);
        compareRecordInteger(record, result, "successfulJobs", scenario);
        compareRecordInteger(record, result, "failedJobs", scenario);
    }

    private static void validateFrozenSettings(long rootSeed, double runtimeReferenceMips,
            double runtimeScale, double cloudSimMinEventIntervalSeconds, String subject)
            throws IOException {
        if (rootSeed != P7BaselineMatrix.ROOT_SEED
                || Double.compare(runtimeReferenceMips, P7BaselineMatrix.RUNTIME_REFERENCE_MIPS) != 0
                || Double.compare(runtimeScale, P7BaselineMatrix.RUNTIME_SCALE) != 0
                || Double.compare(cloudSimMinEventIntervalSeconds,
                        P7BaselineMatrix.CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS) != 0) {
            throw new IOException(subject + " differs from the frozen P7 seed, runtime conversion, or event cadence");
        }
    }

    private static void validateRecordDefinition(JsonObject record,
            P7BaselineMatrix.Scenario expectedScenario, String algorithm) throws IOException {
        String subject = "P7 index run";
        requireExactString(record, "workflowFamily", expectedScenario.getWorkflowFamily(), subject);
        requireExactInt(record, "expectedTaskCount", expectedScenario.getExpectedTaskCount(), subject);
        requireExactString(record, "platformVariant", expectedScenario.getPlatformVariant().name(), subject);
        if (!P7BaselineMatrix.isBaselineAlgorithmName(algorithm)) {
            throw new IOException("P7 index contains an unsupported scheduling algorithm: " + algorithm);
        }
    }

    private static void validateP7Configuration(JsonObject configuration,
            P7BaselineMatrix.Scenario expectedScenario, String algorithm) throws IOException {
        String subject = "P7 manifest configuration";
        requireExactInt(configuration, "vmCount", expectedScenario.getVmCount(), subject);
        requireExactString(configuration, "schedulingAlgorithm", algorithm, subject);
        requireExactString(configuration, "planningAlgorithm", "INVALID", subject);
        requireExactLong(configuration, "deadline", 0L, subject);
        requireExactString(configuration, "fileSystem", "SHARED", subject);
        requireExactLong(configuration, "rootSeed", P7BaselineMatrix.ROOT_SEED, subject);
        requireExactNumber(configuration, "runtimeReferenceMips",
                P7BaselineMatrix.RUNTIME_REFERENCE_MIPS, subject);
        requireExactNumber(configuration, "runtimeScale", P7BaselineMatrix.RUNTIME_SCALE, subject);
        requireExactNumber(configuration, "cloudSimMinEventIntervalSeconds",
                P7BaselineMatrix.CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS, subject);
        requireExactString(configuration, "costModel", "DATACENTER", subject);

        JsonObject dataMovement = requireObject(configuration, "dataMovementModel", subject);
        requireExactString(dataMovement, "kind", "LEGACY_WORKFLOWSIM_V1", subject + " data movement");
        requireExactNumber(dataMovement, "accessLinkBandwidthMbPerSecond", 0.0,
                subject + " data movement");
        requireExactNumber(dataMovement, "accessLinkLatencySeconds", 0.0,
                subject + " data movement");
        requireExactNumber(dataMovement, "sourceEndpointBandwidthMbPerSecond", 0.0,
                subject + " data movement");
        requireExactString(dataMovement, "contentionSemantics",
                "LEGACY_IMPLEMENTATION_DEFINED_NO_EXPLICIT_CONTENTION_MODEL",
                subject + " data movement");

        JsonObject overhead = requireObject(configuration, "overheadModel", subject);
        requireExactInt(overhead, "workflowEngineDelayInterval", 0, subject + " overhead");
        requireExactNumber(overhead, "bandwidth", 0.0, subject + " overhead");
        requireEmptyObject(overhead, "workflowEngineDelays", subject + " overhead");
        requireEmptyObject(overhead, "queueDelays", subject + " overhead");
        requireEmptyObject(overhead, "postDelays", subject + " overhead");
        requireEmptyObject(overhead, "clusteringDelays", subject + " overhead");

        JsonObject clustering = requireObject(configuration, "clustering", subject);
        requireExactString(clustering, "method", "NONE", subject + " clustering");
        requireExactInt(clustering, "clustersNum", 0, subject + " clustering");
        requireExactInt(clustering, "clustersSize", 0, subject + " clustering");

        JsonObject failure = requireObject(configuration, "failureModel", subject);
        requireExactString(failure, "clusteringAlgorithm", "FTCLUSTERING_NOOP", subject + " failure model");
        requireExactString(failure, "monitorMode", "MONITOR_NONE", subject + " failure model");
        requireExactString(failure, "generatorMode", "FAILURE_NONE", subject + " failure model");
        requireExactString(failure, "distributionFamily", "WEIBULL", subject + " failure model");
        requireExactInt(failure, "maxTotalRetryJobs", 0, subject + " failure model");
        requireExactString(failure, "generatorAddressing", "DENSE_VM_ID_MATRIX",
                subject + " failure model");
        if (requireArray(failure, "generators", subject + " failure model").size() != 0) {
            throw new IOException(subject + " failure model must not declare generators");
        }
        requireEmptyObject(failure, "generatorsByVmId", subject + " failure model");
    }

    private static void validateP7Input(JsonObject input, P7BaselineMatrix.Scenario expectedScenario,
            String indexedWorkflowSha256, JsonObject referenceIdentity, String scenario) throws IOException {
        String subject = "P7 manifest input";
        requireExactString(input, "sha256", expectedScenario.getExpectedWorkflowSha256(), subject);
        if (!indexedWorkflowSha256.equals(requireString(input, "sha256", subject))) {
            throw new IOException("P7 index workflow hash does not match manifest for " + scenario);
        }
        requireExactInt(input, "taskCount", expectedScenario.getExpectedTaskCount(), subject);
        requireExactString(input, "format", "DAX_XML", subject);
        requireExactString(input, "declaredVersion", "2.1", subject);

        Path inputPath = absolutePath(requireString(input, "path", subject), subject + " path");
        Path relativeWorkflow = Paths.get(expectedScenario.getRelativeWorkflowPath());
        if (!inputPath.endsWith(relativeWorkflow)) {
            throw new IOException("P7 manifest input path does not match frozen workflow for " + scenario);
        }
        if (referenceIdentity != null) {
            JsonObject dataset = requireObject(referenceIdentity, "dataset", "P7 reference identity");
            Path datasetRoot = absolutePath(requireString(dataset, "root", "P7 reference dataset"),
                    "P7 reference dataset root");
            Path expectedPath = datasetRoot.resolve(relativeWorkflow).normalize();
            if (!expectedPath.equals(inputPath)) {
                throw new IOException("P7 manifest input path does not resolve from its recorded dataset root for "
                        + scenario);
            }
        }
    }

    private static void validateP7Platform(JsonObject actual,
            P7BaselineMatrix.Scenario expectedScenario, String scenario) throws IOException {
        PlatformProfile expected = P7BaselineMatrix.baselinePlatform(expectedScenario);
        String subject = "P7 manifest platform for " + scenario;
        requireExactString(actual, "name", expected.getName(), subject);
        requireExactInt(actual, "hostCount", expected.getHosts().size(), subject);
        requireExactInt(actual, "vmCount", expected.getVms().size(), subject);
        JsonArray actualHosts = requireArray(actual, "hosts", subject);
        JsonArray actualVms = requireArray(actual, "vms", subject);
        if (actualHosts.size() != expected.getHosts().size() || actualVms.size() != expected.getVms().size()) {
            throw new IOException(subject + " host or VM list size differs from the frozen platform");
        }
        for (int index = 0; index < expected.getHosts().size(); index++) {
            PlatformProfile.HostSpec host = expected.getHosts().get(index);
            JsonObject actualHost = objectAt(actualHosts, index, subject + " hosts");
            requireExactInt(actualHost, "id", host.getId(), subject + " host");
            requireExactInt(actualHost, "pes", host.getPes(), subject + " host");
            requireExactNumber(actualHost, "mipsPerPe", host.getMipsPerPe(), subject + " host");
            requireExactInt(actualHost, "ramMb", host.getRamMb(), subject + " host");
            requireExactLong(actualHost, "bandwidth", host.getBandwidth(), subject + " host");
            requireExactLong(actualHost, "storageMb", host.getStorageMb(), subject + " host");
        }
        for (int index = 0; index < expected.getVms().size(); index++) {
            PlatformProfile.VmSpec vm = expected.getVms().get(index);
            JsonObject actualVm = objectAt(actualVms, index, subject + " VMs");
            requireExactInt(actualVm, "id", vm.getId(), subject + " VM");
            requireExactNumber(actualVm, "mips", vm.getMips(), subject + " VM");
            requireExactInt(actualVm, "pes", vm.getPes(), subject + " VM");
            requireExactInt(actualVm, "ramMb", vm.getRamMb(), subject + " VM");
            requireExactLong(actualVm, "bandwidth", vm.getBandwidth(), subject + " VM");
            requireExactLong(actualVm, "imageSizeMb", vm.getImageSizeMb(), subject + " VM");
            requireExactString(actualVm, "vmm", vm.getVmm(), subject + " VM");
            requireExactString(actualVm, "schedulerMode", vm.getSchedulerMode().name(), subject + " VM");
            requireExactInt(actualVm, "preflightHostId",
                    expected.getVmHostAssignments().get(vm.getId()).intValue(), subject + " VM");
            requireNullOrAbsent(actualVm, "pinnedHostId", subject + " VM");
            requireNullOrAbsent(actualVm, "costs", subject + " VM");
        }
        JsonObject storage = requireObject(actual, "storage", subject);
        requireExactLong(storage, "capacityMb", expected.getStorage().getCapacityMb(), subject + " storage");
        requireExactInt(storage, "maxTransferRateMbPerSecond",
                expected.getStorage().getMaxTransferRateMbPerSecond(), subject + " storage");
        validateCostSpec(requireObject(actual, "costs", subject), expected.getCosts(), subject + " costs");
    }

    private static void validateActualVmHostAssignments(JsonObject result,
            P7BaselineMatrix.Scenario expectedScenario, String scenario) throws IOException {
        PlatformProfile expected = P7BaselineMatrix.baselinePlatform(expectedScenario);
        JsonObject actual = requireObject(result, "actualVmHostAssignments", "P7 manifest result");
        if (actual.size() != expected.getVmHostAssignments().size()) {
            throw new IOException("P7 manifest actual VM-host assignment count differs for " + scenario);
        }
        for (java.util.Map.Entry<Integer, Integer> entry : expected.getVmHostAssignments().entrySet()) {
            requireExactInt(actual, Integer.toString(entry.getKey()), entry.getValue().intValue(),
                    "P7 manifest actual VM-host assignments");
        }
    }

    private static void validateCostSpec(JsonObject actual, PlatformProfile.CostSpec expected,
            String subject) throws IOException {
        requireExactNumber(actual, "cpuPerSecond", expected.getCpuPerSecond(), subject);
        requireExactNumber(actual, "memory", expected.getMemory(), subject);
        requireExactNumber(actual, "storage", expected.getStorage(), subject);
        requireExactNumber(actual, "bandwidth", expected.getBandwidth(), subject);
    }

    private static void validateManifestStudyIdentity(JsonObject manifest, boolean v3,
            JsonObject referenceIdentity, String scenario) throws IOException {
        String expectedSchema = v3 ? ExperimentArtifactValidator.MANIFEST_SCHEMA_V3
                : ExperimentArtifactValidator.MANIFEST_SCHEMA_V2;
        if (!expectedSchema.equals(requireString(manifest, "schema", "referenced experiment manifest"))) {
            throw new IOException("P7 index and manifest schemas do not agree for " + scenario);
        }
        if (!v3) {
            return;
        }
        JsonObject provenance = requireObject(manifest, "provenance", "referenced experiment manifest");
        if (!provenance.has("study") || !provenance.get("study").isJsonObject()) {
            throw new IOException("P7 v3 manifest is missing study provenance for " + scenario);
        }
        if (!referenceIdentity.equals(provenance.getAsJsonObject("study"))) {
            throw new IOException("P7 index reference identity differs from manifest provenance for " + scenario);
        }
    }

    private static void compareRecordNumber(JsonObject record, JsonObject result, String key,
            String scenario) throws IOException {
        double indexed = requireFiniteNumber(record, key, "P7 index run");
        double manifested = requireFiniteNumber(result, key, "manifest result");
        if (Double.compare(indexed, manifested) != 0) {
            throw new IOException("P7 index " + key + " does not match manifest for " + scenario);
        }
    }

    private static void compareRecordInteger(JsonObject record, JsonObject result, String key,
            String scenario) throws IOException {
        if (requireInt(record, key, "P7 index run") != requireInt(result, key, "manifest result")) {
            throw new IOException("P7 index " + key + " does not match manifest for " + scenario);
        }
    }

    private static Path requiredRelativeFile(Path root, String path, String role) throws IOException {
        Path rawPath;
        try {
            rawPath = Paths.get(path);
        } catch (InvalidPathException exception) {
            throw new IOException("P7 index " + role + " path is invalid: " + path, exception);
        }
        if (rawPath.isAbsolute()) {
            throw new IOException("P7 index " + role + " path must be relative: " + path);
        }
        Path resolved = root.resolve(rawPath).normalize();
        if (!resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            throw new IOException("P7 index " + role + " path is invalid or missing: " + path);
        }
        return resolved;
    }

    private static JsonObject object(Path path, String subject) throws IOException {
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON " + subject + ": " + path.getFileName(), exception);
        }
    }

    private static JsonObject objectAt(JsonArray values, int index, String subject) throws IOException {
        if (index < 0 || index >= values.size() || !values.get(index).isJsonObject()) {
            throw new IOException(subject + " is missing object at index " + index);
        }
        return values.get(index).getAsJsonObject();
    }

    private static Path absolutePath(String value, String subject) throws IOException {
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute()) {
                throw new IOException(subject + " must be absolute: " + value);
            }
            return path.normalize();
        } catch (InvalidPathException exception) {
            throw new IOException(subject + " is invalid: " + value, exception);
        }
    }

    private static JsonObject requireObject(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw new IOException(subject + " is missing object " + key);
        }
        return object.get(key).getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IOException(subject + " is missing array " + key);
        }
        return object.get(key).getAsJsonArray();
    }

    private static void requireEmptyObject(JsonObject object, String key, String subject)
            throws IOException {
        if (!requireObject(object, key, subject).entrySet().isEmpty()) {
            throw new IOException(subject + " must have an empty object " + key);
        }
    }

    private static void requireNullOrAbsent(JsonObject object, String key, String subject)
            throws IOException {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            throw new IOException(subject + " must have null or absent " + key);
        }
    }

    private static void requireExactString(JsonObject object, String key, String expected,
            String subject) throws IOException {
        String actual = requireString(object, key, subject);
        if (!expected.equals(actual)) {
            throw new IOException(subject + " has unexpected " + key + ": " + actual);
        }
    }

    private static void requireExactInt(JsonObject object, String key, int expected, String subject)
            throws IOException {
        int actual = requireInt(object, key, subject);
        if (actual != expected) {
            throw new IOException(subject + " has unexpected " + key + ": " + actual);
        }
    }

    private static void requireExactLong(JsonObject object, String key, long expected, String subject)
            throws IOException {
        long actual = requireLong(object, key, subject);
        if (actual != expected) {
            throw new IOException(subject + " has unexpected " + key + ": " + actual);
        }
    }

    private static void requireExactNumber(JsonObject object, String key, double expected,
            String subject) throws IOException {
        double actual = requireFiniteNumber(object, key, subject);
        if (Double.compare(actual, expected) != 0) {
            throw new IOException(subject + " has unexpected " + key + ": " + actual);
        }
    }

    private static String requireString(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException(subject + " is missing string " + key);
        }
        JsonPrimitive primitive = object.get(key).getAsJsonPrimitive();
        if (!primitive.isString() || primitive.getAsString().trim().isEmpty()) {
            throw new IOException(subject + " has invalid string " + key);
        }
        return primitive.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IOException(subject + " has invalid boolean " + key);
        }
        return object.get(key).getAsBoolean();
    }

    private static int requireInt(JsonObject object, String key, String subject) throws IOException {
        long value = requireLong(object, key, subject);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException(subject + " has out-of-range integer " + key);
        }
        return (int) value;
    }

    private static long requireLong(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    private static double requireFiniteNumber(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            double value = object.get(key).getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IOException(subject + " has non-finite number " + key);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    private static String requireNullableSha256(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key)) {
            throw new IOException(subject + " is missing nullable SHA-256 " + key);
        }
        JsonElement value = object.get(key);
        if (value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || !value.getAsString().matches("[0-9a-f]{64}")) {
            throw new IOException(subject + " has invalid nullable SHA-256 " + key);
        }
        return value.getAsString();
    }

    private static boolean isAbsolutePath(String value) {
        try {
            return Paths.get(value).isAbsolute();
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private enum IndexKind {
        FROZEN_BASELINE,
        REFERENCE_SELECTION
    }

    /** 供程序调用方使用的只读校验结果。 */
    public static final class ValidationResult {
        private final Path index;
        private final int runCount;

        private ValidationResult(Path index, int runCount) {
            this.index = index;
            this.runCount = runCount;
        }

        public Path getIndex() { return index; }
        public int getRunCount() { return runCount; }
    }
}
