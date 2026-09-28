package org.workflowsim.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Calendar;
import org.cloudbus.cloudsim.ResCloudlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Direct kernel boundaries, with a real resource record and independently derived limits. */
class ResCloudletWorkAccountingTest {
    private SimulationSession session;
    private static final long LIMIT_MI = Long.MAX_VALUE / 1_000_000L;

    @BeforeEach
    void initializeKernel() {
        session = SimulationSession.open(SimulationConfig.builder("not-parsed.dax", 1).build());
        session.initializeCloudSim(1, Calendar.getInstance(), false);
    }

    @AfterEach
    void closeSession() {
        session.close();
    }

    @Test
    void safeInstructionBoundaryRetainsAllWork() {
        ResCloudlet task = new ResCloudlet(task(LIMIT_MI, 1));
        assertEquals(LIMIT_MI, task.getRemainingCloudletLength());
        ResCloudlet twoPes = new ResCloudlet(task(LIMIT_MI / 2, 2));
        assertEquals((LIMIT_MI / 2) * 2, twoPes.getRemainingCloudletLength());
    }

    @Test
    void overflowingMiToInstructionsIsRejectedBeforeAccounting() {
        assertThrows(IllegalArgumentException.class, () -> new ResCloudlet(task(LIMIT_MI + 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ResCloudlet(task(LIMIT_MI / 2 + 1, 2)));
    }

    @Test
    void changedEnvelopeCannotOverflowRemainingWork() {
        Task source = task(100L, 1);
        ResCloudlet task = new ResCloudlet(source);
        source.setCloudletLength(LIMIT_MI + 1);
        assertThrows(IllegalArgumentException.class, task::getRemainingCloudletLength);
    }

    @Test
    void overCompletionCannotWrapAndResurrectFinishedWork() {
        ResCloudlet task = new ResCloudlet(task(100L, 1));
        task.updateCloudletFinishedSoFar(Long.MAX_VALUE);
        assertEquals(0, task.getRemainingCloudletLength());
        task.updateCloudletFinishedSoFar(Long.MAX_VALUE);
        assertEquals(0, task.getRemainingCloudletLength());
    }

    @Test
    void negativeProgressIsRejected() {
        ResCloudlet task = new ResCloudlet(task(100L, 1));
        assertThrows(IllegalArgumentException.class, () -> task.updateCloudletFinishedSoFar(-1));
        assertEquals(100L, task.getRemainingCloudletLength());
    }

    @Test
    void legalStageInGrowthStillAddsWorkAfterResourceWrapping() {
        Task source = task(100L, 1);
        ResCloudlet task = new ResCloudlet(source);
        source.setCloudletLength(150L);
        task.updateCloudletFinishedSoFar(100L * 1_000_000L);
        assertEquals(50L, task.getRemainingCloudletLength());
    }

    private static Task task(long mi, int pes) {
        Task task = new Task(1, mi);
        task.setNumberOfPes(pes);
        task.setResourceParameter(0, 1.0, 0.0);
        assertEquals(0L, task.getCloudletFinishedSoFar(), "Do not use an unregistered fixture");
        return task;
    }
}
