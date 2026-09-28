package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.WorkflowValidationException;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.RetryLimitExceededException;
import org.workflowsim.platform.PlatformProfiles;

/** Abort cleanup must be observable before a later init can conceal stale kernel state. */
class SimulationSessionAbortTest {
    private static final long SEED = 20260902L;
    private static final String[] REFERENCE_FIELDS = {
        "entities", "entitiesByName", "future", "deferred", "waitPredicates", "cis", "calendar"
    };
    private static final String[] CONTROL_FIELDS = {
        "clock", "running", "paused", "pauseAt", "abruptTerminate", "terminateAt",
        "minTimeBetweenEvents", "cisId", "shutdownId", "traceFlag"
    };
    @TempDir Path directory;
    private boolean loggingWasDisabled;

    @BeforeEach
    void quietLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreLegacyGlobalsEvenWhenTheOldImplementationFailsAssertions() throws Exception {
        // Reflection writes are confined to teardown: known-red pause/terminate controls
        // must not poison or hang the next test, and cleanup must not run entity callbacks.
        try {
            SimulationSession active = (SimulationSession) staticField(SimulationSession.class, "activeSession");
            if (active != null) active.close();
        } finally {
            setStatic(SimulationSession.class, "activeSession", null);
            for (String name : REFERENCE_FIELDS) setStatic(CloudSim.class, name, null);
            setStatic(CloudSim.class, "clock", 0.0);
            setStatic(CloudSim.class, "running", false);
            setStatic(CloudSim.class, "paused", false);
            setStatic(CloudSim.class, "pauseAt", -1L);
            setStatic(CloudSim.class, "abruptTerminate", false);
            setStatic(CloudSim.class, "terminateAt", -1.0);
            setStatic(CloudSim.class, "minTimeBetweenEvents", 0.1);
            setStatic(CloudSim.class, "cisId", -1);
            setStatic(CloudSim.class, "shutdownId", -1);
            setStatic(CloudSim.class, "traceFlag", false);
            FailureGenerator.reset();
            FailureMonitor.reset();
            FailureParameters.reset();
            ReplicaCatalog.reset();
            Parameters.reset();
            Log.setDisabled(loggingWasDisabled);
        }
    }

    @Test
    void retryBudgetAbortReleasesKernelBeforeReinitializationAndHealthyReplay() throws Exception {
        SimulationReport before = healthyRun();
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {{DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 0.1, 1.0)}})
                .maxTotalRetryJobs(1).build();
        SimulationConfig config = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(SEED).failureModel(failure).build();
        RetryLimitExceededException aborted = assertThrows(RetryLimitExceededException.class,
                () -> new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("abort-budget", 1)));
        // Same deterministic failure fixture already used by the stochastic-model regression.
        assertEquals(1, aborted.getCreatedRetryJobs());
        assertEquals(1, aborted.getRequestedRetryJobs());
        assertEquals(1, aborted.getMaxTotalRetryJobs());
        assertEquals(SEED, aborted.getRootSeed());
        assertReleasedKernel(); // Must run before healthyRun(), which itself initializes CloudSim.
        assertHealthyEquivalent(before, healthyRun());
    }

    @Test
    void parserAbortReleasesKernelBeforeReinitializationAndHealthyReplay() throws Exception {
        SimulationReport before = healthyRun();
        Path invalid = directory.resolve("invalid-runtime.dax");
        Files.write(invalid, ("<adag version=\"3.3\"><job id=\"bad\" runtime=\"not-a-number\"/></adag>")
                .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(invalid.toString(), 1).build();
        WorkflowValidationException aborted = assertThrows(WorkflowValidationException.class,
                () -> new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("abort-parser", 1)));
        assertTrue(aborted.getMessage().contains("runtime"), aborted.getMessage());
        assertReleasedKernel();
        assertHealthyEquivalent(before, healthyRun());
    }

    @Test
    void plannerAbortReleasesKernelBeforeReinitializationAndHealthyReplay() throws Exception {
        SimulationReport before = healthyRun();
        SimulationConfig config = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MINMIN).build();
        IllegalStateException aborted = assertThrows(IllegalStateException.class,
                () -> new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("abort-planner", 1)));
        assertTrue(aborted.getMessage().contains("STATIC_MINMIN"), aborted.getMessage());
        assertNotNull(aborted.getCause());
        assertTrue(aborted.getCause().getMessage().contains("independent tasks only"));
        assertReleasedKernel();
        assertHealthyEquivalent(before, healthyRun());
    }

    @Test
    void abortDiscardsControlsAndPendingEventsWithoutUserCallbacks() throws Exception {
        assertCallbackAbort(false);
    }

    @Test
    void abruptAbortAlsoReleasesControlsWithoutUserCallbacks() throws Exception {
        assertCallbackAbort(true);
    }

    private static void assertCallbackAbort(boolean markAbrupt) throws Exception {
        SimulationReport before = healthyRun();
        AbortEntity aborter;
        CallbackEntity pending;
        try (SimulationSession session = SimulationSession.open(
                SimulationConfig.builder("not-parsed.dax", 1).build())) {
            session.initializeCloudSim(1, Calendar.getInstance(), false);
            aborter = new AbortEntity(markAbrupt);
            pending = new CallbackEntity();
            assertThrows(DeliberateAbort.class, CloudSim::startSimulation);
            assertEquals(1, aborter.processed);
            assertEquals(0, pending.processed);
        }
        assertAll("closing must not re-enter entity.run or shutdownEntity",
                () -> assertEquals(1, aborter.processed),
                () -> assertEquals(0, aborter.shutdowns),
                () -> assertEquals(0, pending.processed),
                () -> assertEquals(0, pending.shutdowns));
        assertReleasedKernel(); // Old paused kernels fail here; never enter their paused loop again.
        assertHealthyEquivalent(before, healthyRun());
    }

    @Test
    void defaultInitClearsLegacyControlFlagsAndRestoresDefaultCadenceWithoutStarting() throws Exception {
        CloudSim.init(1, Calendar.getInstance(), false, 0.25);
        CloudSim.terminateSimulation(100.0);
        CloudSim.pauseSimulation(50L);
        CloudSim.pauseSimulation();
        CloudSim.abruptallyTerminate();
        CloudSim.init(1, Calendar.getInstance(), false);
        assertCleanControlFields(); // No startSimulation here: old paused state cannot hang the test.
        assertTrue(CloudSim.getCloudInfoServiceEntityId() >= 0);
        assertTrue(CloudSim.getNumEntities() > 0);
    }

    @Test
    void closingAnInitializedButNeverStartedSessionReleasesItsKernel() throws Exception {
        CallbackEntity entity;
        try (SimulationSession session = SimulationSession.open(SimulationConfig.builder("not-parsed.dax", 1)
                .cloudSimMinEventIntervalSeconds(0.25).build())) {
            session.initializeCloudSim(1, Calendar.getInstance(), false);
            entity = new CallbackEntity();
            assertFalse(CloudSim.running());
            assertTrue(CloudSim.getCloudInfoServiceEntityId() >= 0);
        }
        assertEquals(0, entity.processed);
        assertEquals(0, entity.shutdowns);
        assertReleasedKernel();
    }

    @Test
    void sessionWithoutInitializationDoesNotDestroyAnUnownedLegacyKernel() throws Exception {
        seedLegacyKernel();
        Map<String, Object> before = captureKernel();
        try (SimulationSession ignored = SimulationSession.open(SimulationConfig.builder("not-parsed.dax", 1).build())) {
            // No initializeCloudSim call: this session does not own the legacy kernel.
        }
        assertKernelUnchanged(before);
        assertNull(staticField(SimulationSession.class, "activeSession"));
    }

    @Test
    void rejectedInitializeArgumentsDoNotClaimOrClearAnUnownedLegacyKernel() throws Exception {
        seedLegacyKernel();
        Map<String, Object> before = captureKernel();
        try (SimulationSession session = SimulationSession.open(SimulationConfig.builder("not-parsed.dax", 1).build())) {
            assertThrows(IllegalArgumentException.class,
                    () -> session.initializeCloudSim(-1, Calendar.getInstance(), false));
        }
        assertKernelUnchanged(before);
    }

    private static void seedLegacyKernel() {
        CloudSim.init(1, Calendar.getInstance(), false, 0.25);
        CloudSim.terminateSimulation(100.0);
        int cis = CloudSim.getCloudInfoServiceEntityId();
        CloudSim.send(cis, cis, 2.0, 99991, null);
    }

    private static void assertReleasedKernel() throws Exception {
        assertAll("kernel roots must be released before any later init",
                SimulationSessionAbortTest::assertCleanControlFields,
                () -> assertEquals(-1, CloudSim.getCloudInfoServiceEntityId()),
                () -> assertEquals(-1, ((Number) staticField(CloudSim.class, "shutdownId")).intValue()),
                () -> assertNull(staticField(CloudSim.class, "cis")),
                () -> assertNull(staticField(CloudSim.class, "calendar")),
                () -> assertTrue(released(staticField(CloudSim.class, "entities"))),
                () -> assertTrue(released(staticField(CloudSim.class, "entitiesByName"))),
                () -> assertTrue(released(staticField(CloudSim.class, "future"))),
                () -> assertTrue(released(staticField(CloudSim.class, "deferred"))),
                () -> assertTrue(released(staticField(CloudSim.class, "waitPredicates"))),
                () -> assertNull(staticField(SimulationSession.class, "activeSession")));
    }

    private static void assertCleanControlFields() {
        assertAll("kernel control state",
                () -> assertFalse(CloudSim.running()),
                () -> assertEquals(0.0, CloudSim.clock(), 0.0),
                () -> assertFalse(CloudSim.isPaused()),
                () -> assertEquals(-1L, ((Number) staticField(CloudSim.class, "pauseAt")).longValue()),
                () -> assertEquals(false, staticField(CloudSim.class, "abruptTerminate")),
                () -> assertEquals(-1.0, ((Number) staticField(CloudSim.class, "terminateAt")).doubleValue(), 0.0),
                () -> assertEquals(0.1, CloudSim.getMinTimeBetweenEvents(), 0.0));
    }

    private static boolean released(Object value) {
        if (value == null) return true;
        if (value instanceof Map) return ((Map<?, ?>) value).isEmpty();
        if (value instanceof Iterable) return !((Iterable<?>) value).iterator().hasNext();
        return false;
    }

    private static Map<String, Object> captureKernel() throws Exception {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (String name : REFERENCE_FIELDS) result.put(name, staticField(CloudSim.class, name));
        for (String name : CONTROL_FIELDS) result.put(name, staticField(CloudSim.class, name));
        return result;
    }

    private static void assertKernelUnchanged(Map<String, Object> expected) throws Exception {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object actual = staticField(CloudSim.class, entry.getKey());
            Object value = entry.getValue();
            if (value == null || value instanceof Number || value instanceof Boolean) {
                assertEquals(value, actual, entry.getKey());
            } else {
                assertSame(value, actual, entry.getKey());
            }
        }
        assertTrue(CloudSim.getNumEntities() > 0);
        assertFalse(released(staticField(CloudSim.class, "future")), "unowned future event must survive");
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStatic(Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static SimulationReport healthyRun() throws Exception {
        SimulationConfig config = SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS).randomSeed(SEED).build();
        return new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("healthy-after-abort", 1));
    }

    private static String workflow() throws Exception {
        URL resource = SimulationSessionAbortTest.class.getResource("/dax/reproducibility-workflow.dax");
        if (resource == null) throw new IllegalStateException("Missing abort/replay workflow fixture");
        return Paths.get(resource.toURI()).toString();
    }

    private static void assertHealthyEquivalent(SimulationReport expected, SimulationReport actual) {
        assertEquals(expected.getMakespan(), actual.getMakespan(), 0.0);
        assertEquals(expected.getTotalJobs(), actual.getTotalJobs());
        assertEquals(expected.getSuccessfulJobs(), actual.getSuccessfulJobs());
        Map<Integer, SimulationReport.TaskOutcome> byTask = new LinkedHashMap<Integer, SimulationReport.TaskOutcome>();
        for (SimulationReport.TaskOutcome task : actual.getTasks()) byTask.put(task.getTaskId(), task);
        assertEquals(expected.getTasks().size(), byTask.size());
        for (SimulationReport.TaskOutcome first : expected.getTasks()) {
            SimulationReport.TaskOutcome second = byTask.get(first.getTaskId());
            assertNotNull(second);
            assertEquals(first.getJobId(), second.getJobId());
            assertEquals(first.getVmId(), second.getVmId());
            assertEquals(first.getTaskStatus(), second.getTaskStatus());
            assertEquals(first.getEffectiveExecutionLengthMi(), second.getEffectiveExecutionLengthMi());
            assertEquals(first.getStartTime(), second.getStartTime(), 0.0);
            assertEquals(first.getFinishTime(), second.getFinishTime(), 0.0);
        }
    }

    private static final class DeliberateAbort extends IllegalStateException {
        DeliberateAbort() { super("intentional callback abort before finishSimulation"); }
    }

    private static final class AbortEntity extends SimEntity {
        private int processed;
        private int shutdowns;
        private final boolean markAbrupt;
        AbortEntity(boolean markAbrupt) {
            super("abort-before-cleanup-callbacks");
            this.markAbrupt = markAbrupt;
        }
        @Override public void startEntity() {
            schedule(getId(), 1.0, 8801);
            schedule(getId(), 2.0, 8802);
        }
        @Override public void processEvent(SimEvent event) {
            processed++;
            CloudSim.terminateSimulation(100.0);
            CloudSim.pauseSimulation(50L);
            CloudSim.pauseSimulation();
            if (markAbrupt) CloudSim.abruptallyTerminate();
            throw new DeliberateAbort();
        }
        @Override public void shutdownEntity() { shutdowns++; }
    }

    private static final class CallbackEntity extends SimEntity {
        private int processed;
        private int shutdowns;
        CallbackEntity() { super("pending-callback-sentinel"); }
        @Override public void startEntity() { schedule(getId(), 1.0, 8803); }
        @Override public void processEvent(SimEvent event) { processed++; }
        @Override public void shutdownEntity() { shutdowns++; }
    }
}
