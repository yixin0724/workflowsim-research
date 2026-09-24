package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiments.rerun.ManifestConfigRebuilder.RebuiltConfiguration;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 阶段 3 验收：manifest → {@link SimulationConfig} + {@link PlatformProfile} 的
 * 逆向重建逐字段忠实（经真实仿真产出的 v4 证据做 round-trip），任何校验失败
 * 都映射为 {@code RECONSTRUCTION_REJECTED}；workflowPaths 一律替换为阶段 2 已
 * 核对哈希的定位路径。
 */
class ManifestConfigRebuilderTest {

    private static Path fixtureRun;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateFixture() throws Exception {
        fixtureRun = Files.createTempDirectory("rerun-rebuilder-fixture");
        RerunTestSupport.generateEvidence(fixtureRun);
    }

    // ---- round-trip 忠实性 ----

    @Test
    void rebuildsSimpleFixtureFaithfully() throws Exception {
        RerunEvidence evidence = RerunEvidenceReader.read(fixtureRun);
        RerunInputs inputs = RerunInputResolver.resolve(evidence);
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence, inputs);

        SimulationConfig expected = SimulationConfig
                .builder(RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax"), 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(91L)
                .build();
        assertConfigEquals(expected, rebuilt.getConfig());
        assertPlatformEquals(PlatformProfiles.homogeneousLocal("rerun-fixture", 3),
                rebuilt.getPlatform());
    }

    @Test
    void rebuildsRichStaticPlannerRunFaithfully() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("rich-static"));
        String dax = RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax");
        // fat-tree 竞争 DMM 与 overhead 模型互斥（isNoOverhead 校验），overhead
        // 的 round-trip 由 rebuildsOverheadModelRunFaithfully 单独覆盖。
        SimulationConfig expected = SimulationConfig.builder(dax, 4)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .planningAlgorithm(PlanningAlgorithm.PSO)
                .randomSeed(77L)
                .runtimeScale(2.0)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build();
        PlatformProfile expectedPlatform = fatTreePlatform("rich-static");
        RerunTestSupport.generateEvidence(run, expected, expectedPlatform);

        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence,
                RerunInputResolver.resolve(evidence));
        assertConfigEquals(expected, rebuilt.getConfig());
        assertPlatformEquals(expectedPlatform, rebuilt.getPlatform());
        assertEquals(DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1,
                rebuilt.getConfig().getDataMovementModel().getKind());
    }

    @Test
    void rebuildsOverheadModelRunFaithfully() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("overhead-replay"));
        String dax = RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax");
        Map<Integer, DistributionSpec> queueDelays =
                new LinkedHashMap<Integer, DistributionSpec>();
        queueDelays.put(Integer.valueOf(0), DistributionSpec.withPriors(
                DistributionFamily.WEIBULL, 10.0, 1.5, 2.0, 3.0, 0.5));
        SimulationConfig expected = SimulationConfig.builder(dax, 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(55L)
                .overheadModel(OverheadModelConfig.builder()
                        .workflowEngineDelayInterval(1)
                        .bandwidth(100.0)
                        .queueDelays(queueDelays)
                        .build())
                .build();
        PlatformProfile expectedPlatform =
                PlatformProfiles.homogeneousLocal("overhead-replay", 3);
        RerunTestSupport.generateEvidence(run, expected, expectedPlatform);

        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence,
                RerunInputResolver.resolve(evidence));
        assertConfigEquals(expected, rebuilt.getConfig());
        assertPlatformEquals(expectedPlatform, rebuilt.getPlatform());
    }

    @Test
    void rebuildsFailureModelRunFaithfully() throws Exception {
        Path run = Files.createDirectories(tempDir.resolve("failure-replay"));
        String dax = RerunTestSupport.resourcePath("/dax/reproducibility-workflow.dax");
        // 温和故障率（WEIBULL(5000,1.0)）：保证 dax 全部任务在预算内完成，
        // 本用例只验证故障模型配置的 round-trip 忠实性，不验证重试动力学。
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                        DistributionFamily.WEIBULL, 5000.0, 1.0)}})
                .maxTotalRetryJobs(16)
                .build();
        SimulationConfig expected = SimulationConfig.builder(dax, 1)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(20260902L)
                .failureModel(failure)
                .build();
        PlatformProfile expectedPlatform =
                PlatformProfiles.homogeneousLocal("failure-replay", 1);
        RerunTestSupport.generateEvidence(run, expected, expectedPlatform);

        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence,
                RerunInputResolver.resolve(evidence));
        assertConfigEquals(expected, rebuilt.getConfig());
        assertPlatformEquals(expectedPlatform, rebuilt.getPlatform());
        assertTrue(rebuilt.getConfig().getFailureModel().isEnabled());
        assertEquals(16, rebuilt.getConfig().getFailureModel().getMaxTotalRetryJobs());
    }

    // ---- RECONSTRUCTION_REJECTED ----

    @Test
    void rejectsIncompatibleSchedulerPlannerCombination() throws Exception {
        Path run = copyFixtureTo("bad-combo");
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("configuration")
                .addProperty("planningAlgorithm", "PSO");
        writeManifest(run, manifest);

        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> rebuildFrom(run));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, failure.getVerdict());
        assertTrue(failure.getMessage().contains("Manifest cannot be rebuilt"),
                failure.getMessage());
    }

    @Test
    void rejectsUnknownEnumValue() throws Exception {
        Path run = copyFixtureTo("bad-enum");
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("configuration")
                .addProperty("schedulingAlgorithm", "NOT_AN_ALGORITHM");
        writeManifest(run, manifest);

        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> rebuildFrom(run));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, failure.getVerdict());
        assertTrue(failure.getMessage().contains("unknown value: NOT_AN_ALGORITHM"),
                failure.getMessage());
    }

    @Test
    void rejectsPreflightPlacementMismatch() throws Exception {
        Path run = copyFixtureTo("bad-preflight");
        JsonObject manifest = readManifest(run);
        JsonArray vms = manifest.getAsJsonObject("platform").getAsJsonArray("vms");
        int recorded = vms.get(0).getAsJsonObject().get("preflightHostId").getAsInt();
        vms.get(0).getAsJsonObject().addProperty("preflightHostId",
                recorded == 2 ? 1 : 2);
        writeManifest(run, manifest);

        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> rebuildFrom(run));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, failure.getVerdict());
        assertTrue(failure.getMessage().contains("preflight host assignment differs"),
                failure.getMessage());
    }

    @Test
    void rejectsDataMovementParameterMismatch() throws Exception {
        Path run = copyFixtureTo("bad-dmm");
        JsonObject manifest = readManifest(run);
        manifest.getAsJsonObject("configuration").getAsJsonObject("dataMovementModel")
                .addProperty("accessLinkBandwidthMbPerSecond", 987654.321);
        writeManifest(run, manifest);

        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> rebuildFrom(run));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, failure.getVerdict());
        assertTrue(failure.getMessage().contains("do not match the rebuilt"),
                failure.getMessage());
    }

    @Test
    void rejectsTaskCostMatrixWithNonStaticScheduling() throws Exception {
        // ManifestV4Validator 先于 rebuilder 拦截该组合（EVIDENCE_INVALID）；此处
        // 用内存 manifest 直接驱动 rebuilder，验证其自身对不合法组合的防御。
        Path run = copyFixtureTo("bad-matrix");
        JsonObject manifest = readManifest(run);
        JsonObject configuration = manifest.getAsJsonObject("configuration");
        configuration.add("taskCostMatrix", completeMatrix());
        RerunEvidence evidence = new RerunEvidence(run,
                run.resolve("result.manifest.json"), run.resolve("result.metrics.json"),
                run.resolve("result.events.jsonl"), 0, manifest);
        RerunInputs inputs = RerunInputResolver.resolve(evidence);

        RerunFailureException failure = assertThrows(RerunFailureException.class,
                () -> ManifestConfigRebuilder.rebuild(evidence, inputs));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, failure.getVerdict());
    }

    @Test
    void acceptsTaskCostMatrixWithStaticScheduling() throws Exception {
        Path run = copyFixtureTo("good-matrix");
        JsonObject manifest = readManifest(run);
        JsonObject configuration = manifest.getAsJsonObject("configuration");
        configuration.addProperty("schedulingAlgorithm", "STATIC");
        configuration.addProperty("planningAlgorithm", "STATIC_ROUND_ROBIN");
        configuration.add("taskCostMatrix", completeMatrix());
        writeManifest(run, manifest);

        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence,
                RerunInputResolver.resolve(evidence));
        SimulationConfig config = rebuilt.getConfig();
        assertEquals(SchedulingAlgorithm.STATIC, config.getSchedulingAlgorithm());
        assertEquals(PlanningAlgorithm.STATIC_ROUND_ROBIN, config.getPlanningAlgorithm());
        Map<Integer, Map<Integer, Double>> costs = config.getTaskCostMatrix().asMap();
        assertEquals(6, costs.size());
        assertEquals(Double.valueOf(12.0),
                costs.get(Integer.valueOf(0)).get(Integer.valueOf(2)));
        assertEquals(Double.valueOf(14.0),
                costs.get(Integer.valueOf(4)).get(Integer.valueOf(0)));
    }

    // ---- 定位路径替换 ----

    @Test
    void usesResolvedInputPathsInsteadOfRecordedOnes() throws Exception {
        Path run = studyLayoutRun();
        // 记录路径指向不存在的原机器位置；同名文件在 <study>/inputs/ 下（tier 1）。
        Path studyInput = run.getParent().getParent().resolve("inputs")
                .resolve("reproducibility-workflow.dax");
        Files.createDirectories(studyInput.getParent());
        JsonObject manifest = readManifest(run);
        Files.copy(Paths.get(inputPath(manifest)), studyInput);
        rewriteInputPathIn(manifest, "/nonexistent/original-machine/reproducibility-workflow.dax");
        writeManifest(run, manifest);

        RerunEvidence evidence = RerunEvidenceReader.read(run);
        RerunInputs inputs = RerunInputResolver.resolve(evidence.getManifest(), run, tempDir);
        assertEquals(1, inputs.get(0).getTier());
        RebuiltConfiguration rebuilt = ManifestConfigRebuilder.rebuild(evidence, inputs);
        assertEquals(Collections.singletonList(studyInput.toString()),
                rebuilt.getConfig().getWorkflowPaths());
    }

    // ---- helpers ----

    private static PlatformProfile fatTreePlatform(String name) {
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int i = 0; i < 4; i++) {
            builder.addHost(new PlatformProfile.HostSpec(i, 2, 1000.0, 8192, 1000000L, 1000000L));
        }
        for (int i = 0; i < 4; i++) {
            builder.addVm(new PlatformProfile.VmSpec(i, 1000.0, 2, 4096, 1000000L, 1000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        builder.storage(new PlatformProfile.StorageSpec(1000000L, 100000));
        builder.costs(new PlatformProfile.CostSpec(0.12, 0.05, 0.001, 0.0002));
        builder.networkTopology(NetworkTopologySpec.fatTree(4, 100.0));
        return builder.build();
    }

    /** fixture 的 6 个任务 × 3 台 VM 的完整矩阵；cost = 10 + taskId + vmId。 */
    private static JsonObject completeMatrix() {
        JsonObject matrix = new JsonObject();
        matrix.addProperty("unit", "EXECUTION_SECONDS");
        matrix.addProperty("runtimeConversion",
                "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI");
        JsonArray entries = new JsonArray();
        for (int taskId = 0; taskId < 6; taskId++) {
            for (int vmId = 0; vmId < 3; vmId++) {
                JsonObject entry = new JsonObject();
                entry.addProperty("taskId", taskId);
                entry.addProperty("vmId", vmId);
                entry.addProperty("executionSeconds", 10.0 + taskId + vmId);
                entries.add(entry);
            }
        }
        matrix.add("entries", entries);
        return matrix;
    }

    private static RebuiltConfiguration rebuildFrom(Path run) throws Exception {
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        return ManifestConfigRebuilder.rebuild(evidence, RerunInputResolver.resolve(evidence));
    }

    private Path copyFixtureTo(String name) throws Exception {
        Path run = Files.createDirectories(tempDir.resolve(name));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    /** 构造 {@code <study>/runs/<run>} 布局并复制证据。 */
    private Path studyLayoutRun() throws Exception {
        Path study = Files.createDirectories(tempDir.resolve("study-" + System.nanoTime()));
        Path run = Files.createDirectories(study.resolve("runs/fixture-run"));
        RerunTestSupport.copyEvidenceFiles(fixtureRun, run);
        return run;
    }

    private static String inputPath(JsonObject manifest) {
        return manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .get("path").getAsString();
    }

    private static JsonObject readManifest(Path run) throws Exception {
        return JsonParser.parseString(new String(
                Files.readAllBytes(run.resolve("result.manifest.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void writeManifest(Path run, JsonObject manifest) throws Exception {
        Gson gson = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
        Files.write(run.resolve("result.manifest.json"),
                gson.toJson(manifest).getBytes(StandardCharsets.UTF_8));
    }

    /** 同步改写 inputs/workflowPaths/workflowOutcomes 三处路径（v4 交叉校验要求）。 */
    private static void rewriteInputPathIn(JsonObject manifest, String newPath) {
        manifest.getAsJsonArray("inputs").get(0).getAsJsonObject()
                .addProperty("path", newPath);
        JsonArray workflowPaths = manifest.getAsJsonObject("configuration")
                .getAsJsonArray("workflowPaths");
        workflowPaths.set(0, new JsonPrimitive(newPath));
        manifest.getAsJsonObject("result").getAsJsonArray("workflowOutcomes")
                .get(0).getAsJsonObject().addProperty("path", newPath);
    }

    // ---- 字段级比较 ----

    private static void assertConfigEquals(SimulationConfig expected, SimulationConfig actual) {
        assertEquals(expected.getWorkflowPaths(), actual.getWorkflowPaths());
        assertEquals(expected.getVmCount(), actual.getVmCount());
        assertEquals(expected.getWorkflowArrivalSeconds(), actual.getWorkflowArrivalSeconds());
        assertEquals(expected.getSchedulingAlgorithm(), actual.getSchedulingAlgorithm());
        assertEquals(expected.getPlanningAlgorithm(), actual.getPlanningAlgorithm());
        assertEquals(expected.getReduceMethod(), actual.getReduceMethod());
        assertEquals(expected.getDeadline(), actual.getDeadline());
        assertEquals(expected.getFileSystem(), actual.getFileSystem());
        assertEquals(expected.getRandomSeed(), actual.getRandomSeed());
        assertEquals(expected.getRuntimeScale(), actual.getRuntimeScale());
        assertEquals(expected.getRuntimeReferenceMips(), actual.getRuntimeReferenceMips());
        assertEquals(expected.getCloudSimMinEventIntervalSeconds(),
                actual.getCloudSimMinEventIntervalSeconds());
        assertEquals(expected.getCostModel(), actual.getCostModel());
        assertDataMovementEquals(expected.getDataMovementModel(), actual.getDataMovementModel());
        assertOverheadEquals(expected.getOverheadModel(), actual.getOverheadModel());
        assertClusteringEquals(expected.getClusteringParameters(),
                actual.getClusteringParameters());
        assertFailureEquals(expected.getFailureModel(), actual.getFailureModel());
        assertEquals(expected.getTaskCostMatrix() == null, actual.getTaskCostMatrix() == null);
        if (expected.getTaskCostMatrix() != null) {
            assertEquals(expected.getTaskCostMatrix().asMap(), actual.getTaskCostMatrix().asMap());
        }
    }

    private static void assertDataMovementEquals(DataMovementModel expected,
            DataMovementModel actual) {
        assertEquals(expected.getKind(), actual.getKind());
        assertEquals(expected.getAccessLinkBandwidthMbPerSecond(),
                actual.getAccessLinkBandwidthMbPerSecond());
        assertEquals(expected.getAccessLinkLatencySeconds(), actual.getAccessLinkLatencySeconds());
        assertEquals(expected.getSourceEndpointBandwidthMbPerSecond(),
                actual.getSourceEndpointBandwidthMbPerSecond());
    }

    private static void assertOverheadEquals(OverheadModelConfig expected,
            OverheadModelConfig actual) {
        assertEquals(expected.getWorkflowEngineDelayInterval(),
                actual.getWorkflowEngineDelayInterval());
        assertEquals(expected.getBandwidth(), actual.getBandwidth());
        assertSpecMapEquals(expected.getWorkflowEngineDelays(), actual.getWorkflowEngineDelays());
        assertSpecMapEquals(expected.getQueueDelays(), actual.getQueueDelays());
        assertSpecMapEquals(expected.getPostDelays(), actual.getPostDelays());
        assertSpecMapEquals(expected.getClusteringDelays(), actual.getClusteringDelays());
    }

    private static void assertSpecMapEquals(Map<Integer, DistributionSpec> expected,
            Map<Integer, DistributionSpec> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (Map.Entry<Integer, DistributionSpec> entry : expected.entrySet()) {
            assertSpecEquals(entry.getValue(), actual.get(entry.getKey()));
        }
    }

    private static void assertSpecEquals(DistributionSpec expected, DistributionSpec actual) {
        assertEquals(expected.getFamily(), actual.getFamily());
        assertEquals(Double.doubleToLongBits(expected.getScale()),
                Double.doubleToLongBits(actual.getScale()));
        assertEquals(Double.doubleToLongBits(expected.getShape()),
                Double.doubleToLongBits(actual.getShape()));
        assertEquals(expected.getPriorShape(), actual.getPriorShape());
        assertEquals(expected.getPriorScale(), actual.getPriorScale());
        assertEquals(expected.getLikelihoodPrior(), actual.getLikelihoodPrior());
    }

    private static void assertClusteringEquals(ClusteringParameters expected,
            ClusteringParameters actual) {
        assertEquals(expected.getClusteringMethod(), actual.getClusteringMethod());
        assertEquals(expected.getClustersNum(), actual.getClustersNum());
        assertEquals(expected.getClustersSize(), actual.getClustersSize());
        assertEquals(expected.getCode(), actual.getCode());
    }

    private static void assertFailureEquals(FailureModelConfig expected,
            FailureModelConfig actual) {
        assertEquals(expected.isEnabled(), actual.isEnabled());
        assertEquals(expected.getClusteringAlgorithm(), actual.getClusteringAlgorithm());
        assertEquals(expected.getMonitorMode(), actual.getMonitorMode());
        assertEquals(expected.getGeneratorMode(), actual.getGeneratorMode());
        assertEquals(expected.getDistributionFamily(), actual.getDistributionFamily());
        assertEquals(expected.getMaxTotalRetryJobs(), actual.getMaxTotalRetryJobs());
        DistributionSpec[][] expectedSpecs = expected.getGeneratorSpecs();
        DistributionSpec[][] actualSpecs = actual.getGeneratorSpecs();
        assertEquals(expectedSpecs.length, actualSpecs.length);
        for (int row = 0; row < expectedSpecs.length; row++) {
            assertEquals(expectedSpecs[row].length, actualSpecs[row].length);
            for (int column = 0; column < expectedSpecs[row].length; column++) {
                assertSpecEquals(expectedSpecs[row][column], actualSpecs[row][column]);
            }
        }
        assertEquals(expected.getGeneratorSpecsByVmId().keySet(),
                actual.getGeneratorSpecsByVmId().keySet());
        for (Map.Entry<Integer, DistributionSpec[]> entry
                : expected.getGeneratorSpecsByVmId().entrySet()) {
            DistributionSpec[] actualRow = actual.getGeneratorSpecsByVmId().get(entry.getKey());
            assertEquals(entry.getValue().length, actualRow.length);
            for (int i = 0; i < actualRow.length; i++) {
                assertSpecEquals(entry.getValue()[i], actualRow[i]);
            }
        }
    }

    private static void assertPlatformEquals(PlatformProfile expected, PlatformProfile actual) {
        assertEquals(expected.getName(), actual.getName());
        assertEquals(expected.getHosts().size(), actual.getHosts().size());
        for (int i = 0; i < expected.getHosts().size(); i++) {
            PlatformProfile.HostSpec e = expected.getHosts().get(i);
            PlatformProfile.HostSpec a = actual.getHosts().get(i);
            assertEquals(e.getId(), a.getId());
            assertEquals(e.getPes(), a.getPes());
            assertEquals(e.getMipsPerPe(), a.getMipsPerPe());
            assertEquals(e.getRamMb(), a.getRamMb());
            assertEquals(e.getBandwidth(), a.getBandwidth());
            assertEquals(e.getStorageMb(), a.getStorageMb());
        }
        assertEquals(expected.getVms().size(), actual.getVms().size());
        for (int i = 0; i < expected.getVms().size(); i++) {
            PlatformProfile.VmSpec e = expected.getVms().get(i);
            PlatformProfile.VmSpec a = actual.getVms().get(i);
            assertEquals(e.getId(), a.getId());
            assertEquals(e.getMips(), a.getMips());
            assertEquals(e.getPes(), a.getPes());
            assertEquals(e.getRamMb(), a.getRamMb());
            assertEquals(e.getBandwidth(), a.getBandwidth());
            assertEquals(e.getImageSizeMb(), a.getImageSizeMb());
            assertEquals(e.getVmm(), a.getVmm());
            assertEquals(e.getSchedulerMode(), a.getSchedulerMode());
            assertEquals(e.hasCosts(), a.hasCosts());
            if (e.hasCosts()) {
                assertCostEquals(e.getCosts(), a.getCosts());
            }
        }
        assertEquals(expected.getStorage().getCapacityMb(), actual.getStorage().getCapacityMb());
        assertEquals(expected.getStorage().getMaxTransferRateMbPerSecond(),
                actual.getStorage().getMaxTransferRateMbPerSecond());
        assertCostEquals(expected.getCosts(), actual.getCosts());
        assertEquals(expected.getPinnedVmHostIds(), actual.getPinnedVmHostIds());
        assertEquals(expected.getVmHostAssignments(), actual.getVmHostAssignments());
        NetworkTopologySpec e = expected.getNetworkTopology();
        NetworkTopologySpec a = actual.getNetworkTopology();
        assertEquals(e == null, a == null);
        if (e != null) {
            assertEquals(e.getKind(), a.getKind());
            assertEquals(e.getK(), a.getK());
            assertEquals(e.getLinkBandwidthMbPerSecond(), a.getLinkBandwidthMbPerSecond());
            assertEquals(e.getCoreSwitchCount(), a.getCoreSwitchCount());
            assertEquals(e.getHostEdgePlacements(), a.getHostEdgePlacements());
        }
    }

    private static void assertCostEquals(PlatformProfile.CostSpec expected,
            PlatformProfile.CostSpec actual) {
        assertEquals(expected.getCpuPerSecond(), actual.getCpuPerSecond());
        assertEquals(expected.getMemory(), actual.getMemory());
        assertEquals(expected.getStorage(), actual.getStorage());
        assertEquals(expected.getBandwidth(), actual.getBandwidth());
    }
}
