package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.data.TransferTraceSnapshot;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** NF002A grouped capture is observational and Java-API-only until export wiring. */
class NetworkEvidenceCaptureIntegrationTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final int FULL_BUDGET = 2000;
    private static final String GROUPED_DAX = "<adag version=\"2.1\">"
            + "<job id=\"p\" name=\"producer\" runtime=\"1.0\">"
            + "<uses file=\"external-a\" link=\"input\" size=\"0.125\"/>"
            + "<uses file=\"external-b\" link=\"input\" size=\"0.375\"/>"
            + "<uses file=\"a\" link=\"output\" size=\"2000000\"/>"
            + "<uses file=\"b\" link=\"output\" size=\"3000000\"/></job>"
            + "<job id=\"c\" name=\"consumer\" runtime=\"2.0\">"
            + "<uses file=\"a\" link=\"input\" size=\"2000000\"/>"
            + "<uses file=\"b\" link=\"input\" size=\"3000000\"/></job>"
            + "<child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    @TempDir Path temporary;
    private boolean loggingWasDisabled;
    @BeforeEach void silenceLogging() { loggingWasDisabled = Log.isDisabled(); Log.disable(); }
    @AfterEach void restoreLogging() { Log.setDisabled(loggingWasDisabled); }

    @Test
    void endpointRandomStaticCaptureUsesActualJobTaskIdsAndOneBindingPerFileGroup() throws Exception {
        SimulationReport report = run(input("grouped.dax", GROUPED_DAX), false, 2, FULL_BUDGET);
        assertComplete(report);
        SimulationReport.JobOutcome producer = computeJob(report, 1), consumer = computeJob(report, 2);
        assertEquals(7, producer.getVmId()); assertEquals(42, consumer.getVmId());
        assertEquals(2, report.getNetworkEvidence().getBindings().size());
        NetworkFlowBinding external = bindingForJob(report, producer.getJobId());
        NetworkFlowBinding parent = bindingForJob(report, consumer.getJobId());
        assertEquals(NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, external.getGroupKind());
        assertNull(external.getParentJobId());
        assertEquals(Parameters.SOURCE, external.getSourceEndpoint());
        assertEquals(Arrays.asList(Parameters.SOURCE, "VM:7"), external.getOccupiedResources());
        assertEquals(0.5, startForBinding(report, external).getStart().getBytes(), 0.0,
                "Two external files form one modeled group, not two file flows");
        assertEquals(NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, parent.getGroupKind());
        assertEquals(Integer.valueOf(producer.getJobId()), parent.getParentJobId());
        assertEquals(consumer.getTaskIds(), parent.getTaskIds());
        assertFalse(parent.getTaskIds().contains(parent.getJobId()), "Fixture distinguishes Job IDs from Task IDs");
        assertEquals("VM:7", parent.getSourceEndpoint()); assertEquals("VM:42", parent.getDestinationEndpoint());
        assertEquals(Arrays.asList("VM:7", "VM:42"), parent.getOccupiedResources());
        assertEquals(5_000_000.0, startForBinding(report, parent).getStart().getBytes(), 0.0,
                "The producer's two files form one admitted parent group");
    }

    @Test
    void fatTreeCaptureRecordsChargedPathButExternalTrafficUsesOnlyDestinationResources() throws Exception {
        SimulationReport report = run(input("fat-grouped.dax", GROUPED_DAX), true, 2, FULL_BUDGET);
        assertComplete(report);
        NetworkFlowBinding external = bindingForJob(report, computeJob(report, 1).getJobId());
        NetworkFlowBinding parent = bindingForJob(report, computeJob(report, 2).getJobId());
        assertEquals(Parameters.SOURCE, external.getSourceEndpoint()); assertNull(external.getParentJobId());
        assertEquals(Collections.singletonList("VM:7"), external.getOccupiedResources());
        assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, external.getSourceScope());
        assertEquals(Arrays.asList("VM:7", "VM:42", "LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->AGG:0:0",
                "LINK:AGG:0:0->EDGE:0:1", "LINK:EDGE:0:1->ACC:20"), parent.getOccupiedResources());
        assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, parent.getSourceScope());
        assertEquals(parent.getOccupiedResources(), startForBinding(report, parent).getStart().getOccupiedResources());
        assertEquals(external.getOccupiedResources(), startForBinding(report, external).getStart().getOccupiedResources());
    }

    @Test
    void endpointOnAndOffHaveIdenticalOldJobsTasksMetricsAndMainEvents() throws Exception {
        Path workflow = input("endpoint-parity.dax", GROUPED_DAX);
        SimulationReport off = run(workflow, false, 2, 0), on = run(workflow, false, 2, FULL_BUDGET);
        assertNull(off.getNetworkEvidence()); assertComplete(on); assertOldEvidenceEquals(off, on);
    }

    @Test
    void fatTreeOnAndOffHaveIdenticalOldJobsTasksMetricsAndMainEvents() throws Exception {
        Path workflow = input("fat-parity.dax", GROUPED_DAX);
        SimulationReport off = run(workflow, true, 2, 0), on = run(workflow, true, 2, FULL_BUDGET);
        assertNull(off.getNetworkEvidence()); assertComplete(on); assertOldEvidenceEquals(off, on);
    }

    @Test
    void onOffOnRunsAreIsolatedAndPriorSnapshotsOutliveSessionCleanup() throws Exception {
        Path workflow = input("isolation.dax", GROUPED_DAX);
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport first = run(workflow, fatTree, 2, FULL_BUDGET);
            NetworkRunEvidence frozen = first.getNetworkEvidence(); String before = JSON.toJson(frozen);
            SimulationReport off = run(workflow, fatTree, 2, 0), again = run(workflow, fatTree, 2, FULL_BUDGET);
            assertNull(off.getNetworkEvidence()); assertOldEvidenceEquals(first, off); assertOldEvidenceEquals(first, again);
            assertEquals(before, JSON.toJson(frozen)); assertEquals(before, JSON.toJson(again.getNetworkEvidence()));
            assertSame(frozen, first.getNetworkEvidence());
            assertThrows(UnsupportedOperationException.class, () -> frozen.getBindings().clear());
            assertThrows(UnsupportedOperationException.class, () -> frozen.getBindings().get(0).getTaskIds().add(999));
            assertThrows(UnsupportedOperationException.class, () -> frozen.getBindings().get(0).getOccupiedResources().add("invented"));
            assertThrows(UnsupportedOperationException.class, () -> frozen.getTraceSnapshot().getEvents().clear());
        }
    }

    @Test
    void rootWithoutInputsProducesCompleteZeroFlowEvidenceWithoutChangingCompletion() throws Exception {
        Path workflow = input("no-inputs.dax", rootDax(null));
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport off = run(workflow, fatTree, 1, 0), on = run(workflow, fatTree, 1, FULL_BUDGET);
            assertOldEvidenceEquals(off, on); assertComplete(on);
            assertTrue(on.getNetworkEvidence().isEngineCreated(), "Existing root path creates engine even without inputs");
            assertTrue(on.getNetworkEvidence().getBindings().isEmpty());
            assertTrue(traceEvents(on, TransferTraceEvent.Type.START).isEmpty());
            assertEquals(0.0, number(onlyEvent(on, SimulationEventType.DATA_STAGE_IN_MODELED,
                    computeJob(on, 1).getJobId()), "contentionTransferGroupCount"), 0.0);
        }
    }

    @Test
    void localParentHitCreatesNoAdditionalFlowBindingOrStageInHold() throws Exception {
        Path workflow = input("local-hit.dax", GROUPED_DAX);
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport off = run(workflow, fatTree, 1, 0), on = run(workflow, fatTree, 1, FULL_BUDGET);
            assertOldEvidenceEquals(off, on); assertComplete(on);
            assertEquals(1, on.getNetworkEvidence().getBindings().size());
            assertEquals(NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, on.getNetworkEvidence().getBindings().get(0).getGroupKind());
            int childId = computeJob(on, 2).getJobId();
            assertEquals(0.0, number(onlyEvent(on, SimulationEventType.DATA_STAGE_IN_MODELED, childId), "contentionTransferGroupCount"), 0.0);
            assertEquals(onlyEvent(on, SimulationEventType.JOB_READY, childId).getSimulationTime(),
                    onlyEvent(on, SimulationEventType.SCHEDULING_DECISION, childId).getSimulationTime(), 0.0);
        }
    }

    @Test
    void fractionalTransferPreservesEffectiveAndLateObservedClocksWithoutReportTimeAdvance() throws Exception {
        Path workflow = input("fractional.dax", rootDax(0.5));
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport off = run(workflow, fatTree, 1, 0), on = run(workflow, fatTree, 1, FULL_BUDGET);
            assertOldEvidenceEquals(off, on); assertComplete(on);
            assertEquals(1, traceEvents(on, TransferTraceEvent.Type.START).size());
            assertEquals(1, traceEvents(on, TransferTraceEvent.Type.COMPLETE).size());
            TransferTraceEvent start = traceEvents(on, TransferTraceEvent.Type.START).get(0);
            TransferTraceEvent complete = traceEvents(on, TransferTraceEvent.Type.COMPLETE).get(0);
            assertEquals(0.5, start.getStart().getBytes(), 0.0);
            assertEquals(start.getEffectiveTime(), start.getObservedTime(), 0.0);
            assertEquals(start.getEffectiveTime() + 0.5 / 1_000_000.0, complete.getEffectiveTime(), 1.0e-12);
            assertEquals(start.getObservedTime() + 0.1, complete.getObservedTime(), 1.0e-12);
            assertTrue(complete.getObservedTime() > complete.getEffectiveTime());
            assertEquals(complete.getObservedTime(), on.getNetworkEvidence().getTraceSnapshot().getEngineTime(), 0.0,
                    "Freeze must not advance to the later simulation end");
            assertTrue(on.getMakespan() > on.getNetworkEvidence().getTraceSnapshot().getEngineTime());
            assertEquals(complete.getObservedTime(), computeJob(on, 1).getStartTime(), 0.0,
                    "Runtime keeps observed CloudSim release time");
        }
    }

    @Test
    void boundedRunsPreserveTheFullRunPrefixAndEveryRetainedStartBinding() throws Exception {
        Path workflow = input("truncated.dax", GROUPED_DAX);
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport full = run(workflow, fatTree, 2, FULL_BUDGET); assertComplete(full);
            List<TransferTraceEvent> starts = traceEvents(full, TransferTraceEvent.Type.START); assertEquals(2, starts.size());
            for (int budget : Arrays.asList(1, (int) starts.get(0).getSequence(), (int) starts.get(1).getSequence())) {
                SimulationReport small = run(workflow, fatTree, 2, budget);
                TransferTraceSnapshot trace = small.getNetworkEvidence().getTraceSnapshot();
                List<TransferTraceEvent> fullEvents = full.getNetworkEvidence().getTraceSnapshot().getEvents();
                assertTrue(budget < fullEvents.size()); assertEquals(TransferTraceSnapshot.Status.TRUNCATED, trace.getStatus());
                assertEquals(budget, trace.getEvents().size()); assertEquals((long) fullEvents.size() - budget, trace.getDroppedCount());
                assertEquals(JSON.toJson(fullEvents.subList(0, budget)), JSON.toJson(trace.getEvents()));
                assertTrue(small.getNetworkEvidence().getBindings().size() <= budget);
                assertAllRetainedStartsBound(small); assertOldEvidenceEquals(full, small);
            }
        }
    }

    @Test
    void endpointCapturePreservesRealFailedAttemptsAndRetryGroupBindings() throws Exception {
        Path workflow = input("retry-observation.dax", rootDax(20_000_000.0));
        org.workflowsim.failure.FailureModelConfig failures = org.workflowsim.failure.FailureModelConfig.builder()
                .clusteringAlgorithm(org.workflowsim.failure.FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(org.workflowsim.failure.FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new org.workflowsim.utils.DistributionSpec[][] {{
                        org.workflowsim.utils.DistributionSpec.of(
                                org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL, 2.0, 1.0)}})
                .maxTotalRetryJobs(64).build();
        PlatformProfile platform = PlatformProfile.builder("network-retry-observation")
                .addHost(new PlatformProfile.HostSpec(10, 2, 1000.0, 4096, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(7, 1000.0, 1, 512, 20L, 10000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(7, 10)
                .storage(new PlatformProfile.StorageSpec(1000000L, 20)).build();
        int observedRetries = 0;
        for (long seed : new long[] {1, 5, 17, 22}) {
            SimulationConfig offConfig = SimulationConfig.builder(workflow.toString(), 1)
                    .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.SHARED).failureModel(failures).randomSeed(seed)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1()).build();
            SimulationReport off = new SimulationRunner().run(offConfig, platform);
            SimulationReport on = new SimulationRunner().run(offConfig.toBuilder()
                    .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(FULL_BUDGET)).build(), platform);
            assertOldEvidenceEquals(off, on); assertComplete(on);
            observedRetries += on.getMetrics().getRetryJobCreatedCount();
            int computeAttempts = 0;
            for (SimulationReport.JobOutcome job : on.getJobs()) {
                if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                    computeAttempts++;
                    NetworkFlowBinding binding = bindingForJob(on, job.getJobId());
                    assertEquals(Collections.singletonList(1), binding.getTaskIds());
                    assertEquals(NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, binding.getGroupKind());
                    assertNull(binding.getParentJobId());
                }
            }
            assertEquals(computeAttempts, on.getNetworkEvidence().getBindings().size(),
                    "Shared inputs are fetched for every actual attempt, including failed/retry attempts");
        }
        assertTrue(observedRetries > 0, "The bounded seed set must exercise real retry capture");
        System.out.println("NETWORK_EVIDENCE_RETRY_CONTROL retries=" + observedRetries);
    }

    @Test
    void temporaryArtifactGuardRejectsOnBeforeCreatingOrOverwritingAnyFile() throws Exception {
        SimulationReport report = run(input("guard-bundle.dax", rootDax(0.5)), false, 1, FULL_BUDGET);
        Path absent = temporary.resolve("new-bundle");
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentArtifactWriter.write(report, absent, "on")));
        assertFalse(Files.exists(absent));
        Path existing = Files.createDirectory(temporary.resolve("existing-bundle"));
        byte[] sentinel = "unchanged existing evidence\n".getBytes(StandardCharsets.UTF_8);
        for (String suffix : Arrays.asList("manifest.json", "metrics.json", "events.jsonl")) Files.write(existing.resolve("on." + suffix), sentinel);
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentArtifactWriter.write(report, existing, "on", null)));
        for (String suffix : Arrays.asList("manifest.json", "metrics.json", "events.jsonl")) assertArrayEquals(sentinel, Files.readAllBytes(existing.resolve("on." + suffix)));
        assertEquals(3, fileNames(existing).size(), "Reject before temporary or sidecar files are created");
    }

    @Test
    void temporaryManifestGuardCoversEveryOverloadBeforeWritingFiles() throws Exception {
        SimulationReport report = run(input("guard-manifest.dax", rootDax(0.5)), true, 1, FULL_BUDGET);
        Path missingDirectory = temporary.resolve("new-manifest-directory");
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentManifestWriter.writeJson(report, missingDirectory.resolve("on.json"))));
        assertFalse(Files.exists(missingDirectory));
        Path target = temporary.resolve("existing-manifest.json");
        byte[] sentinel = "do not replace\n".getBytes(StandardCharsets.UTF_8); Files.write(target, sentinel);
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentManifestWriter.writeJson(report, target, (ExperimentEvidenceContext) null)));
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentManifestWriter.writeJson(report, target, Collections.<Map<String, Object>>emptyList())));
        assertGuard(assertThrows(UnsupportedOperationException.class, () -> ExperimentManifestWriter.writeJson(report, target, Collections.<Map<String, Object>>emptyList(), null)));
        assertArrayEquals(sentinel, Files.readAllBytes(target));
    }

    @Test
    void offStillWritesTheSameThreeFileBundleAndOldMetricsDocumentShape() throws Exception {
        Path workflow = input("off-export.dax", GROUPED_DAX);
        for (boolean fatTree : Arrays.asList(false, true)) {
            SimulationReport off = run(workflow, fatTree, 2, 0); assertNull(off.getNetworkEvidence());
            Path directory = temporary.resolve("off-bundle-" + fatTree);
            ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(off, directory, "off");
            assertEquals(Arrays.asList("off.events.jsonl", "off.manifest.json", "off.metrics.json"), fileNames(directory));
            JsonObject manifest = jsonFile(artifacts.getManifest());
            assertEquals(new HashSet<String>(Arrays.asList("schema", "configuration", "platform", "inputs", "workflowProfile",
                    "workflowGraph", "result", "metrics", "events", "artifacts", "provenance", "runtime")), manifest.keySet());
            assertEquals("workflowsim-experiment-manifest-v4", manifest.get("schema").getAsString());
            assertFalse(manifest.getAsJsonObject("configuration").has("networkEvidence"));
            JsonObject metrics = jsonFile(artifacts.getMetrics());
            assertEquals(new HashSet<String>(Arrays.asList("schema", "metrics")), metrics.keySet());
            assertEquals("workflowsim-simulation-metrics-v2", metrics.get("schema").getAsString());
            assertEquals(JSON.toJsonTree(off.getMetrics()), metrics.get("metrics"));
            JsonArray entries = manifest.getAsJsonArray("artifacts"); assertEquals(2, entries.size());
            assertEquals("metrics", entries.get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("events", entries.get(1).getAsJsonObject().get("role").getAsString());
            List<String> eventLines = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
            assertEquals(off.getEvents().size(), eventLines.size()); Gson eventsJson = new Gson();
            for (int i = 0; i < eventLines.size(); i++) assertEquals(eventsJson.toJsonTree(off.getEvents().get(i)), JsonParser.parseString(eventLines.get(i)));
            assertEquals(off.getEvents().size(), ExperimentArtifactValidator.validate(artifacts.getManifest()).getEventCount());
            Path standalone = temporary.resolve("off-standalone-" + fatTree + ".json"); ExperimentManifestWriter.writeJson(off, standalone);
            assertTrue(Files.isRegularFile(standalone)); assertFalse(jsonFile(standalone).getAsJsonObject("configuration").has("networkEvidence"));
        }
    }

    private Path input(String name, String content) throws Exception {
        Path path = temporary.resolve(name); Files.write(path, content.getBytes(StandardCharsets.UTF_8)); return path;
    }
    private static String rootDax(Double bytes) {
        return "<adag version=\"2.1\"><job id=\"root\" name=\"root\" runtime=\"1.0\">"
                + (bytes == null ? "" : "<uses file=\"tiny-input\" link=\"input\" size=\"" + bytes + "\"/>") + "</job></adag>";
    }
    private static SimulationReport run(Path workflow, boolean fatTree, int vmCount, int budget) throws Exception {
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), vmCount)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1L).cloudSimMinEventIntervalSeconds(0.1)
                .dataMovementModel(fatTree ? DataMovementModel.fatTreeContentionV1() : DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(budget == 0 ? NetworkEvidenceConfig.off() : NetworkEvidenceConfig.fluidGroupLedger(budget)).build();
        return new SimulationRunner().run(config, platform(fatTree, vmCount));
    }
    private static PlatformProfile platform(boolean fatTree, int vmCount) {
        PlatformProfile.Builder builder = PlatformProfile.builder("network-evidence-integration-" + fatTree);
        int[] ids = {7, 42};
        for (int i = 0; i < vmCount; i++) {
            int hostId = 10 + i * 10;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(ids[i], 1000.0, 1, 512, 1L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(ids[i], hostId);
        }
        if (fatTree) builder.networkTopology(NetworkTopologySpec.fatTree(4, 1.0));
        return builder.build();
    }
    private static void assertComplete(SimulationReport report) {
        assertTrue(report.isWorkflowCompletedSuccessfully()); NetworkRunEvidence evidence = report.getNetworkEvidence();
        assertNotNull(evidence); assertSame(report.getConfig().getNetworkEvidenceConfig(), evidence.getConfig());
        assertEquals(report.getConfig().getDataMovementModel().getKind(), evidence.getModelKind());
        assertEquals(TransferTraceSnapshot.Status.COMPLETE, evidence.getTraceSnapshot().getStatus());
        assertEquals(0L, evidence.getTraceSnapshot().getDroppedCount()); assertTrue(evidence.isEngineCreated());
        assertAllRetainedStartsBound(report);
        assertEquals(traceEvents(report, TransferTraceEvent.Type.START).size(), traceEvents(report, TransferTraceEvent.Type.COMPLETE).size());
    }
    private static void assertAllRetainedStartsBound(SimulationReport report) {
        org.workflowsim.data.TransferTraceValidator.validate(report.getNetworkEvidence().getTraceSnapshot(),
                report.getConfig().getNetworkEvidenceConfig().getMaxTraceRecords());
        org.workflowsim.data.NetworkTraceMetrics networkMetrics=org.workflowsim.data.NetworkTraceMetrics.calculate(
                report.getNetworkEvidence().getTraceSnapshot(),report.getConfig().getNetworkEvidenceConfig().getMaxTraceRecords());
        assertEquals(report.getNetworkEvidence().getTraceSnapshot().getStatus()==TransferTraceSnapshot.Status.COMPLETE,
                networkMetrics.isAvailable());
        if(networkMetrics.isAvailable()){
            assertEquals(report.getNetworkEvidence().getBindings().size(),networkMetrics.getFlows().size());
            assertEquals(0,networkMetrics.getAdmittedPayloadBytes().compareTo(networkMetrics.getServicedBalanceDeltaBytes()
                    .add(networkMetrics.getCompletionResidualBytes()).add(networkMetrics.getRemainingLedgerBytes())));
        }else{
            assertNull(networkMetrics.getAdmittedPayloadBytes());assertNull(networkMetrics.getCompletedFctSampleCount());
        }
        Map<Long, NetworkFlowBinding> bindings = new LinkedHashMap<Long, NetworkFlowBinding>();
        for (NetworkFlowBinding binding : report.getNetworkEvidence().getBindings()) {
            assertNull(bindings.put(binding.getAdmissionOrdinal(), binding));
            SimulationReport.JobOutcome job = jobById(report, binding.getJobId());
            assertEquals(job.getTaskIds(), binding.getTaskIds()); assertEquals("VM:" + job.getVmId(), binding.getDestinationEndpoint());
            assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, binding.getSourceScope());
        }
        List<TransferTraceEvent> starts = traceEvents(report, TransferTraceEvent.Type.START); assertEquals(starts.size(), bindings.size());
        for (TransferTraceEvent start : starts) {
            NetworkFlowBinding binding = bindings.get(start.getAdmissionOrdinal());
            assertNotNull(binding, "Every retained START requires a binding, including a truncated prefix");
            assertEquals(start.getTransferId().longValue(), binding.getExternalTransferId());
            assertEquals(start.getStart().getOccupiedResources(), binding.getOccupiedResources());
        }
    }
    private static List<TransferTraceEvent> traceEvents(SimulationReport report, TransferTraceEvent.Type type) {
        List<TransferTraceEvent> result = new ArrayList<TransferTraceEvent>();
        for (TransferTraceEvent event : report.getNetworkEvidence().getTraceSnapshot().getEvents()) if (event.getType() == type) result.add(event);
        return result;
    }
    private static NetworkFlowBinding bindingForJob(SimulationReport report, int jobId) {
        List<NetworkFlowBinding> matches = new ArrayList<NetworkFlowBinding>();
        for (NetworkFlowBinding binding : report.getNetworkEvidence().getBindings()) if (binding.getJobId() == jobId) matches.add(binding);
        assertEquals(1, matches.size()); return matches.get(0);
    }
    private static TransferTraceEvent startForBinding(SimulationReport report, NetworkFlowBinding binding) {
        for (TransferTraceEvent event : traceEvents(report, TransferTraceEvent.Type.START)) if (event.getAdmissionOrdinal().longValue() == binding.getAdmissionOrdinal()) return event;
        throw new AssertionError("Missing START for admission " + binding.getAdmissionOrdinal());
    }
    private static SimulationReport.JobOutcome computeJob(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) if (job.getClassType() == Parameters.ClassType.COMPUTE.value && job.getTaskIds().contains(taskId)) return job;
        throw new AssertionError("Missing compute Job for Task " + taskId);
    }
    private static SimulationReport.JobOutcome jobById(SimulationReport report, int jobId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) if (job.getJobId() == jobId) return job;
        throw new AssertionError("Missing Job " + jobId);
    }
    private static SimulationEvent onlyEvent(SimulationReport report, SimulationEventType type, int jobId) {
        List<SimulationEvent> matches = new ArrayList<SimulationEvent>();
        for (SimulationEvent event : report.getEvents()) if (event.getType() == type && Integer.valueOf(jobId).equals(event.getJobId())) matches.add(event);
        assertEquals(1, matches.size(), "Expected one " + type + " for Job " + jobId); return matches.get(0);
    }
    private static double number(SimulationEvent event, String field) {
        Object value = event.getAttributes().get(field); assertTrue(value instanceof Number); return ((Number) value).doubleValue();
    }
    private static void assertOldEvidenceEquals(SimulationReport expected, SimulationReport actual) {
        assertEquals(Double.doubleToRawLongBits(expected.getMakespan()), Double.doubleToRawLongBits(actual.getMakespan()));
        assertEquals(expected.getSuccessfulJobs(), actual.getSuccessfulJobs()); assertEquals(expected.getFailedJobs(), actual.getFailedJobs());
        assertEquals(JSON.toJson(expected.getJobs()), JSON.toJson(actual.getJobs()));
        assertEquals(JSON.toJson(expected.getTasks()), JSON.toJson(actual.getTasks()));
        assertEquals(JSON.toJson(expected.getInputs()), JSON.toJson(actual.getInputs()));
        assertEquals(JSON.toJson(expected.getInputReports()), JSON.toJson(actual.getInputReports()));
        assertEquals(JSON.toJson(expected.getVmSummaries()), JSON.toJson(actual.getVmSummaries()));
        assertEquals(expected.getActualVmHostAssignments(), actual.getActualVmHostAssignments());
        assertEquals(JSON.toJson(expected.getWorkflowProfile()), JSON.toJson(actual.getWorkflowProfile()));
        assertEquals(JSON.toJson(expected.getWorkflowGraph()), JSON.toJson(actual.getWorkflowGraph()));
        assertEquals(JSON.toJson(expected.getWorkflowOutcomes()), JSON.toJson(actual.getWorkflowOutcomes()));
        assertEquals(JSON.toJson(expected.getSharedStorageDagPlanTrace()), JSON.toJson(actual.getSharedStorageDagPlanTrace()));
        assertEquals(metricsCore(expected), metricsCore(actual)); assertEquals(eventsCore(expected), eventsCore(actual));
    }
    private static String metricsCore(SimulationReport report) {
        JsonObject metrics = JSON.toJsonTree(report.getMetrics()).getAsJsonObject();
        assertNotNull(metrics.remove("totalSchedulingDecisionWallClockNanos"));
        assertNotNull(metrics.remove("totalPlanningDecisionWallClockNanos")); return JSON.toJson(metrics);
    }
    private static String eventsCore(SimulationReport report) {
        JsonArray events = JSON.toJsonTree(report.getEvents()).getAsJsonArray();
        for (JsonElement element : events) {
            JsonObject attributes = element.getAsJsonObject().getAsJsonObject("attributes");
            attributes.remove("decisionElapsedNanos"); attributes.remove("planningDecisionElapsedNanos");
        }
        return JSON.toJson(events);
    }
    private static void assertGuard(UnsupportedOperationException failure) {
        String message = failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("network") && message.contains("export") && message.contains("not wired"), message);
    }
    private static List<String> fileNames(Path directory) throws Exception {
        try (Stream<Path> paths = Files.list(directory)) { return paths.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList()); }
    }
    private static JsonObject jsonFile(Path path) throws Exception {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
