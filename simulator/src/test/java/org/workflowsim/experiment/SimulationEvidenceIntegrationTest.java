package org.workflowsim.experiment;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationEvidenceIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void reportExposesTaskEventsAndPreciselyNamedMetrics() throws Exception {
        Log.disable();
        SimulationReport report = runFixture();

        assertEquals(6, report.getJobs().size());
        assertEquals(5, report.getTasks().size());
        assertEquals(5, report.getWorkflowGraph().size());
        int graphEdges = 0;
        for (SimulationReport.TaskNode node : report.getWorkflowGraph()) {
            graphEdges += node.getChildIds().size();
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> node.getParentIds().add(999));
        }
        assertEquals(report.getWorkflowProfile().getEdgeCount(), graphEdges);
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> report.getWorkflowGraph().clear());
        assertFalse(report.getEvents().isEmpty());
        assertEquals(SimulationEventType.WORKFLOW_PARSED, report.getEvents().get(0).getType());
        assertTrue(hasType(report.getEvents(), SimulationEventType.JOBS_CLUSTERED));
        assertTrue(hasType(report.getEvents(), SimulationEventType.JOB_READY));
        assertTrue(hasType(report.getEvents(), SimulationEventType.SCHEDULING_CYCLE));
        assertTrue(hasType(report.getEvents(), SimulationEventType.SCHEDULING_DECISION));
        assertTrue(hasType(report.getEvents(), SimulationEventType.DATA_STAGE_IN_MODELED));
        assertTrue(hasType(report.getEvents(), SimulationEventType.TASK_EXECUTION_MODELED));
        assertTrue(hasType(report.getEvents(), SimulationEventType.JOB_RETURNED));

        long previous = -1L;
        for (SimulationEvent event : report.getEvents()) {
            assertEquals(previous + 1L, event.getSequence());
            previous = event.getSequence();
        }

        SimulationMetrics metrics = report.getMetrics();
        assertEquals(report.getMakespan(), metrics.getMakespanSeconds(), 0.0);
        assertEquals(6, metrics.getJobOutcomeCount());
        assertEquals(5, metrics.getComputeJobOutcomeCount());
        assertEquals(1, metrics.getStageInJobOutcomeCount());
        assertEquals(5, metrics.getSuccessfulComputeJobOutcomeCount());
        assertEquals(0, metrics.getFailedComputeJobOutcomeCount());
        assertEquals(1.0, metrics.getSuccessfulComputeJobOutcomeRate(), 0.0);
        assertTrue(metrics.getSchedulingCycleCount() >= 1);
        assertTrue(metrics.getTotalSchedulingDecisionWallClockNanos() >= 0L);
        assertEquals(3, metrics.getVmMetrics().size());
        for (SimulationMetrics.VmMetrics vm : metrics.getVmMetrics().values()) {
            assertTrue(vm.getModeledIntervalUtilization() >= 0.0);
            assertTrue(vm.getModeledIntervalUtilization() <= 1.0);
        }
    }

    @Test
    void evidenceBundleCrossReferencesMetricsAndEventStream(@TempDir Path output) throws Exception {
        Log.disable();
        SimulationReport report = runFixture();
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                report, output, "fixture-fcfs");

        assertTrue(Files.isRegularFile(artifacts.getManifest()));
        assertTrue(Files.isRegularFile(artifacts.getMetrics()));
        assertTrue(Files.isRegularFile(artifacts.getEvents()));
        String manifest = new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8);
        String metrics = new String(Files.readAllBytes(artifacts.getMetrics()), StandardCharsets.UTF_8);
        List<String> events = Files.readAllLines(artifacts.getEvents(), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("workflowsim-experiment-manifest-v4"));
        assertTrue(manifest.contains("fixture-fcfs.metrics.json"));
        assertTrue(manifest.contains("fixture-fcfs.events.jsonl"));
        assertTrue(manifest.contains("sourceTreeSha256"));
        assertTrue(manifest.contains("\"study\": null"));
        assertTrue(metrics.contains("workflowsim-simulation-metrics-v2"));
        JsonObject core = JsonParser.parseString(manifest).getAsJsonObject()
                .getAsJsonObject("provenance").getAsJsonObject("core");
        assertTrue(core.get("sourceTreeSha256").getAsString().matches("[0-9a-f]{64}"));
        assertTrue(core.get("sourceFileCount").getAsInt() > 0);
        assertEquals("src/main/java/**/*.java", core.get("sourceTreeScope").getAsString());
        assertEquals(report.getEvents().size(), ExperimentArtifactValidator.validate(
                artifacts.getManifest()).getEventCount());
        assertEquals(report.getEvents().size(), events.size());
        assertTrue(events.get(0).contains("WORKFLOW_PARSED"));
        try (java.util.stream.Stream<Path> stream = Files.list(output)) {
            assertEquals(3L, stream.count());
        }
    }

    private SimulationReport runFixture() throws Exception {
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/reproducibility-workflow.dax"), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(20260902L)
                .build();
        return new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("evidence-fixture", 3));
    }

    private static boolean hasType(List<SimulationEvent> events, SimulationEventType type) {
        for (SimulationEvent event : events) {
            if (event.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
