package org.workflowsim.data;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaxMinTransferTest {
    @Test void nominalLimitReturnsUnusedCapacityToOtherFlow() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 100, 10, "A");
        add(engine, 2, 900, 100, "A");
        assertEquals(10, engine.currentRateBytesPerSecond(1), 1e-12);
        assertEquals(90, engine.currentRateBytesPerSecond(2), 1e-12);
        assertEquals(Arrays.asList(1L, 2L), engine.advance(10).getCompletedTransferIds());
    }

    @Test void secondBottleneckReleasesSharedCapacity() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        engine.setEndpointCapacity("B", 10);
        add(engine, 1, 1000, 100, "A", "B");
        add(engine, 2, 1000, 100, "A");
        assertEquals(10, engine.currentRateBytesPerSecond(1), 1e-12);
        assertEquals(90, engine.currentRateBytesPerSecond(2), 1e-12);
    }

    @Test void cascadingBottlenecksAndDuplicateResourceMultiplicityAreRespected() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        engine.setEndpointCapacity("B", 40);
        add(engine, 1, 1000, 100, "A");
        add(engine, 2, 1000, 100, "A", "B");
        add(engine, 3, 1000, 10, "B");
        assertEquals(70, engine.currentRateBytesPerSecond(1), 1e-12);
        assertEquals(30, engine.currentRateBytesPerSecond(2), 1e-12);
        assertEquals(10, engine.currentRateBytesPerSecond(3), 1e-12);
        TransferContentionEngine duplicate = new TransferContentionEngine();
        duplicate.setEndpointCapacity("A", 100);
        add(duplicate, 1, 1000, 10, "A", "A");
        add(duplicate, 2, 1000, 100, "A");
        assertEquals(10, duplicate.currentRateBytesPerSecond(1), 1e-12);
        assertEquals(80, duplicate.currentRateBytesPerSecond(2), 1e-12);
    }

    @Test void delayedCheckIntegratesEveryIntermediateCompletion() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 500, 100, "A");
        add(engine, 2, 1000, 100, "A");
        assertEquals(Arrays.asList(1L, 2L), engine.advance(15).getCompletedTransferIds());
        assertEquals(0, engine.activeTransferCount());
        assertNull(engine.advance(20).getNextCompletionTime());
    }

    @Test void addingAfterAnUnobservedCompletionReturnsItExactlyOnce() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 10, 10, "A");
        add(engine, 2, 180, 100, "A");
        TransferContentionEngine.AdvanceResult result = engine.addTransfer(3, 100,
                Collections.singletonList("A"), 100, 1.5);
        assertEquals(Collections.singletonList(1L), result.getCompletedTransferIds());
        assertEquals(2.3, result.getNextCompletionTime(), 1e-12);
        assertEquals(Arrays.asList(2L, 3L), engine.advance(3).getCompletedTransferIds());
        assertTrue(engine.advance(3).getCompletedTransferIds().isEmpty());
    }

    @Test void allocationIsOrderIndependentAndScaleInvariant() {
        for (double scale : new double[] {1e-12, 1, 1e12}) {
            for (boolean reverse : new boolean[] {false, true}) {
                TransferContentionEngine engine = new TransferContentionEngine();
                engine.setEndpointCapacity("A", 100 * scale);
                if (reverse) {
                    add(engine, 2, 1000, 100 * scale, "A");
                    add(engine, 1, 1000, 10 * scale, "A");
                } else {
                    add(engine, 1, 1000, 10 * scale, "A");
                    add(engine, 2, 1000, 100 * scale, "A");
                }
                assertEquals(10, engine.currentRateBytesPerSecond(1) / scale, 1e-10);
                assertEquals(90, engine.currentRateBytesPerSecond(2) / scale, 1e-10);
                assertTrue(engine.currentRateBytesPerSecond(1) + engine.currentRateBytesPerSecond(2)
                        <= 100 * scale * (1 + 1e-12));
            }
        }
    }

    @Test void rejectsNonFiniteTimeAndCapacityChangesWithoutCorruptingState() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 1000, 100, "A");
        for (double time : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertThrows(IllegalArgumentException.class, () -> engine.advance(time));
        }
        assertThrows(IllegalStateException.class, () -> engine.setEndpointCapacity("A", 50));
        assertThrows(IllegalArgumentException.class, () -> engine.setEndpointCapacity("B", Double.NaN));
        assertEquals(10, engine.advance(0).getNextCompletionTime(), 1e-12);
    }

    private static void add(TransferContentionEngine engine, long id, long bytes,
            double nominal, String... resources) {
        engine.addTransfer(id, bytes, Arrays.asList(resources), nominal, 0);
    }
}
