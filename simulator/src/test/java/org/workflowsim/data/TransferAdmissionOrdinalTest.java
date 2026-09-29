package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** NF002A's constant-time diagnostic join must not infer ordinal from external ID. */
class TransferAdmissionOrdinalTest {
    @Test
    void readsActualOrdinalWithoutAdvancingEmittingRecordsOrChangingPrediction() {
        TransferContentionEngine engine = new TransferContentionEngine(100);
        engine.setEndpointCapacity("A", 100.0);
        engine.addTransfer(9001L, 1000.0, "A", "B", 100.0, 0.0);
        engine.addTransfer(-73L, 500.0, "A", "C", 100.0, 0.0);
        String before = new Gson().toJson(engine.getTraceSnapshot());
        Double prediction = engine.advance(0.0).getNextCompletionTime();
        for (int i = 0; i < 1000; i++) {
            assertEquals(1L, engine.getActiveTransferAdmissionOrdinal(9001L));
            assertEquals(2L, engine.getActiveTransferAdmissionOrdinal(-73L));
        }
        assertEquals(before, new Gson().toJson(engine.getTraceSnapshot()));
        assertEquals(prediction, engine.advance(0.0).getNextCompletionTime());
        assertEquals(Arrays.asList(-73L, 9001L), engine.advance(20.0).getCompletedTransferIds());
    }

    @Test
    void rejectsDisabledMissingAndAlreadyCompletedAdmissions() {
        TransferContentionEngine disabled = new TransferContentionEngine();
        disabled.addTransfer(4L, 1.0, "A", "B", 1.0, 0.0);
        assertThrows(IllegalStateException.class, () -> disabled.getActiveTransferAdmissionOrdinal(4L));
        TransferContentionEngine enabled = new TransferContentionEngine(10);
        assertThrows(IllegalStateException.class, () -> enabled.getActiveTransferAdmissionOrdinal(4L));
        enabled.addTransfer(4L, 1.0, "A", "B", 1.0, 0.0);
        enabled.advance(1.0);
        assertThrows(IllegalStateException.class, () -> enabled.getActiveTransferAdmissionOrdinal(4L));
    }

    @Test
    void reuseGetsANewOrdinalAndRejectedAdmissionsDoNotConsumeOne() {
        TransferContentionEngine engine = new TransferContentionEngine(100);
        engine.addTransfer(71L, 1.0, "A", "B", 1.0, 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(71L, 1.0, "A", "B", 1.0, 5.0));
        assertEquals(1L, engine.getActiveTransferAdmissionOrdinal(71L));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(80L, 0.0, "A", "B", 1.0, 0.0));
        TransferTraceSnapshot firstAdmission = engine.getTraceSnapshot();
        engine.advance(1.0);
        engine.addTransfer(71L, 1.0, "A", "B", 1.0, 1.0);
        assertEquals(2L, engine.getActiveTransferAdmissionOrdinal(71L));
        assertEquals(Long.valueOf(1L), firstAdmission.getEvents().get(0).getAdmissionOrdinal());
    }

    @Test
    void activeOrdinalRemainsAvailableAfterTheTracePrefixIsFull() {
        TransferContentionEngine engine = new TransferContentionEngine(1);
        engine.setEndpointCapacity("A", 100.0);
        for (int i = 0; i < 20; i++) {
            engine.addTransfer(10_000L + i, 1.0, "A", "B", 1.0, i);
            assertEquals(i + 1L, engine.getActiveTransferAdmissionOrdinal(10_000L + i));
            engine.advance(i + 1.0);
        }
        assertEquals(TransferTraceSnapshot.Status.TRUNCATED, engine.getTraceSnapshot().getStatus());
        assertEquals(1, engine.getTraceSnapshot().getEvents().size());
        assertTrue(engine.getTraceSnapshot().getDroppedCount() > 20L);
    }
}
