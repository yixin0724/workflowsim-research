package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Finite delays must remain finite and positive after addition to the current double clock. */
class SimulationTimeValidationTest {
    private SimulationSession session;

    @BeforeEach
    void initializeKernel() {
        session = SimulationSession.open(SimulationConfig.builder("not-parsed.dax", 1).build());
        session.initializeCloudSim(1, Calendar.getInstance(), false);
        Log.disable();
    }

    @AfterEach
    void closeSession() {
        if (session != null) { session.close(); }
        Log.enable();
    }

    @Test
    void completionTimeCannotOverflowOrRoundBackToTheCurrentClock() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationTiming.earliestCloudletCompletionTime(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> SimulationTiming.earliestCloudletCompletionTime(1e20, 1e20, 0.1));
    }

    @Test
    void nonFiniteDelaysAreRejectedBeforeEnqueueingEvents() {
        int entity = CloudSim.getCloudInfoServiceEntityId();
        for (double delay : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CloudSim.send(entity, entity, delay, 123, null));
            assertThrows(IllegalArgumentException.class, () -> CloudSim.sendFirst(entity, entity, delay, 123, null));
            assertThrows(IllegalArgumentException.class, () -> CloudSim.pause(entity, delay));
        }
    }

    @Test
    void positiveDelayCannotSilentlyBecomeImmediateAtALargeClock() {
        ClockProbe probe = new ClockProbe(1e20, 0.1, false);
        assertThrows(IllegalArgumentException.class, CloudSim::startSimulation);
        assertTrue(probe.reachedLargeClock, "Exercise the addition boundary, not an initialization error");
    }

    @Test
    void priorityEventTimeCannotOverflowAtALargeClock() {
        ClockProbe probe = new ClockProbe(1e308, 1e308, true);
        assertThrows(IllegalArgumentException.class, CloudSim::startSimulation);
        assertTrue(probe.reachedLargeClock);
    }

    @Test
    void ordinaryPositiveAndImmediateEventsRemainSupported() {
        ClockProbe probe = new ClockProbe(0.0, 1.0, false);
        assertEquals(1.0, CloudSim.startSimulation(), 0.0);
        assertTrue(probe.reachedLargeClock);
        assertEquals(0.11, SimulationTiming.earliestCloudletCompletionTime(0.0, 0.01, 0.1), 1e-12);
    }

    /** Two events only: the old implementation terminates instead of hanging this regression. */
    private static final class ClockProbe extends SimEntity {
        private final double firstDelay;
        private final double nextDelay;
        private final boolean priority;
        private boolean reachedLargeClock;

        ClockProbe(double firstDelay, double nextDelay, boolean priority) {
            super("clock-precision-probe");
            this.firstDelay = firstDelay;
            this.nextDelay = nextDelay;
            this.priority = priority;
        }

        @Override public void startEntity() { schedule(getId(), firstDelay, 701); }
        @Override public void shutdownEntity() { }
        @Override public void processEvent(SimEvent event) {
            if (event.getTag() == 701) {
                reachedLargeClock = true;
                if (priority) { CloudSim.sendFirst(getId(), getId(), nextDelay, 702, null); }
                else { CloudSim.send(getId(), getId(), nextDelay, 702, null); }
            } else {
                CloudSim.stopSimulation();
            }
        }
    }
}
