package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * F-006 / F-007 events v1 contract tests (adjudication approved 2026-10-23).
 *
 * <p>Contract claims under test:</p>
 * <ol>
 *   <li>The event stream of a naturally completed run terminates with exactly one
 *       {@code SIMULATION_TERMINATED} event whose time equals the run's makespan and whose
 *       {@code simulationEndSeconds} attribute equals {@code SimulationMetrics#getSimulationEndSeconds()},
 *       even when a positive post-delay tail follows the last {@code JOB_RETURNED}.</li>
 *   <li>Every Job execution instance records exactly one {@code JOB_EXECUTION_STARTED} event,
 *       ordered between {@code JOB_DISPATCHED} and the instance's first
 *       {@code TASK_EXECUTION_MODELED}, carrying the envelope start, the effective/requested
 *       data-stage seconds, and the modeled post-stage start (envelope + effective).</li>
 * </ol>
 *
 * <p>These assertions are the Java half of the closed recompute boundary: the makespan family
 * and the decision-to-start family become derivable from the event stream alone.</p>
 */
class EventTerminalAndJobStartContractTest {

    private boolean logWasDisabled;

    @TempDir
    Path tempDir;

    @BeforeEach
    void disableSimulationLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLog() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void terminalEventClosesTheMakespanRecomputeBoundaryUnderPositivePostDelays() throws Exception {
        SimulationReport report = runScenario();
        List<SimulationEvent> events = report.getEvents();

        long terminalCount = countByType(events, SimulationEventType.SIMULATION_TERMINATED);
        assertEquals(1, terminalCount, "exactly one terminal event per completed run");

        SimulationEvent terminal = events.get(events.size() - 1);
        assertEquals(SimulationEventType.SIMULATION_TERMINATED, terminal.getType(), "terminal event is last");
        assertEquals(
                0,
                Double.compare(report.getMakespan(), terminal.getSimulationTime()),
                "terminal event time must equal the run makespan bit-exactly");
        Object declaredEnd = terminal.getAttributes().get("simulationEndSeconds");
        assertNotNull(declaredEnd, "terminal event must carry simulationEndSeconds");
        assertEquals(
                0,
                Double.compare(report.getMetrics().getSimulationEndSeconds(), ((Number) declaredEnd).doubleValue()),
                "terminal event's simulationEndSeconds must mirror the metrics termination clock");
    }

    @Test
    void jobExecutionStartEventsCoverEveryJobInstanceWithEnvelopeAndStage() throws Exception {
        SimulationReport report = runScenario();
        List<SimulationEvent> events = report.getEvents();

        long startCount = countByType(events, SimulationEventType.JOB_EXECUTION_STARTED);
        assertEquals(
                report.getJobs().size(),
                startCount,
                "one JOB_EXECUTION_STARTED event per completed Job execution instance");

        Map<Integer, Long> dispatchSequence = new HashMap<Integer, Long>();
        Map<Integer, Long> startSequence = new HashMap<Integer, Long>();
        Map<Integer, Long> firstTaskSequence = new HashMap<Integer, Long>();
        Map<Integer, Double> envelopeStart = new HashMap<Integer, Double>();
        Map<Integer, Double> effectiveStage = new HashMap<Integer, Double>();
        for (SimulationEvent event : events) {
            Integer jobId = event.getJobId();
            if (jobId == null) {
                continue;
            }
            if (event.getType() == SimulationEventType.JOB_DISPATCHED) {
                dispatchSequence.put(jobId, Long.valueOf(event.getSequence()));
            } else if (event.getType() == SimulationEventType.JOB_EXECUTION_STARTED) {
                if (startSequence.put(jobId, Long.valueOf(event.getSequence())) != null) {
                    throw new AssertionError("duplicate JOB_EXECUTION_STARTED for job " + jobId);
                }
                envelopeStart.put(jobId, doubleAttribute(event, "jobEnvelopeStartSeconds"));
                effectiveStage.put(jobId, doubleAttribute(event, "effectiveDataStageSeconds"));
                assertNotNull(
                        event.getAttributes().get("requestedDataStageSeconds"),
                        "requestedDataStageSeconds must be present for job " + jobId);
                double modeled = doubleAttribute(event, "modeledJobStartSeconds");
                assertEquals(
                        0,
                        Double.compare(
                                envelopeStart.get(jobId).doubleValue()
                                        + effectiveStage.get(jobId).doubleValue(),
                                modeled),
                        "modeledJobStartSeconds = envelope + effective stage (job " + jobId + ")");
            } else if (event.getType() == SimulationEventType.TASK_EXECUTION_MODELED
                    && !firstTaskSequence.containsKey(jobId)) {
                firstTaskSequence.put(jobId, Long.valueOf(event.getSequence()));
            }
        }

        Set<Integer> seenJobs = new HashSet<Integer>();
        for (SimulationReport.JobOutcome outcome : report.getJobs()) {
            seenJobs.add(Integer.valueOf(outcome.getJobId()));
        }
        for (Integer jobId : seenJobs) {
            Long dispatched = dispatchSequence.get(jobId);
            Long started = startSequence.get(jobId);
            Long firstTask = firstTaskSequence.get(jobId);
            assertNotNull(started, "job " + jobId + " lacks JOB_EXECUTION_STARTED");
            assertTrue(
                    dispatched == null || dispatched.longValue() < started.longValue(),
                    "JOB_EXECUTION_STARTED (job " + jobId + ") must follow JOB_DISPATCHED");
            assertTrue(
                    firstTask == null || started.longValue() <= firstTask.longValue(),
                    "JOB_EXECUTION_STARTED (job " + jobId + ") must precede its first TASK_EXECUTION_MODELED");
        }
    }

    private static double doubleAttribute(SimulationEvent event, String name) {
        Object value = event.getAttributes().get(name);
        assertTrue(value instanceof Number, name + " must be a numeric attribute");
        double number = ((Number) value).doubleValue();
        assertTrue(Double.isFinite(number) && number >= 0.0, name + " must be finite and non-negative, was " + number);
        return number;
    }

    private static long countByType(List<SimulationEvent> events, SimulationEventType type) {
        long count = 0;
        for (SimulationEvent event : events) {
            if (event.getType() == type) {
                count++;
            }
        }
        return count;
    }

    private SimulationReport runScenario() throws Exception {
        Path workflow = scenarioDax();
        PlatformProfile.Builder builder = PlatformProfile.builder("event-contract-platform");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));

        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .overheadModel(OverheadModelConfig.builder()
                        .postDelays(java.util.Collections.singletonMap(
                                Integer.valueOf(0), DistributionSpec.of(DistributionFamily.LOGNORMAL, 4.0, 0.3)))
                        .build())
                .randomSeed(20261023L)
                .build();
        return new SimulationRunner().run(config, builder.build());
    }

    /** Two-layer four-task DAG with inter-layer files and a positive post-delay tail. */
    private Path scenarioDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        dax.append("  <job id=\"t1\" name=\"a\" runtime=\"60\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"output\" size=\"7000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t2\" name=\"b\" runtime=\"50\">\n");
        dax.append("    <uses name=\"out-2.bin\" link=\"output\" size=\"7000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t3\" name=\"c\" runtime=\"40\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"input\" size=\"7000000\"/>\n");
        dax.append("    <uses name=\"out-3.bin\" link=\"output\" size=\"7000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <job id=\"t4\" name=\"d\" runtime=\"30\">\n");
        dax.append("    <uses name=\"out-1.bin\" link=\"input\" size=\"7000000\"/>\n");
        dax.append("  </job>\n");
        dax.append("  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n");
        dax.append("</adag>\n");
        Path path = Paths.get(tempDir.toFile().getAbsolutePath(), "event-contract.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
