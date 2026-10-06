package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** NF005: passive checked-service observations, with independent exact arithmetic oracles. */
class TransferServiceSnapshotTest {
    @Test
    void uncheckedConstructorsRejectSnapshotsWithoutChangingTheirContracts() {
        for (TransferContentionEngine engine : Arrays.asList(new TransferContentionEngine(),
                new TransferContentionEngine(0), new TransferContentionEngine(64))) {
            IllegalStateException rejection = assertThrows(IllegalStateException.class, engine::snapshotState);
            assertTrue(rejection.getMessage().contains("coherentV2"));
            engine.setEndpointCapacity("R", 10);
            engine.addTransfer(7L, 100, Arrays.asList("R"), 10, 0);
            // Even a fully declared legacy path does not certify the unchecked engine.
            assertThrows(IllegalStateException.class, engine::snapshotState);
            engine.addTransfer(Long.MAX_VALUE, 100, Arrays.asList("unbounded"), 10, 0);
            TransferTraceSnapshot trace = engine.getTraceSnapshot();
            for (int i = 0; i < 8; i++) {
                assertThrows(IllegalStateException.class, engine::snapshotState);
            }
            assertThrows(IllegalStateException.class, () -> engine.fork().snapshotState());
            assertTraceEquals(trace, engine.getTraceSnapshot());
            assertBits(0, engine.getCurrentTime());
            assertEquals(2, engine.activeTransferCount());
            assertBits(10, engine.currentRateBytesPerSecond(7L));
            assertBits(10, engine.currentRateBytesPerSecond(Long.MAX_VALUE));
            if (trace.getStatus() != TransferTraceSnapshot.Status.DISABLED) {
                assertEquals(1L, engine.getActiveTransferAdmissionOrdinal(7L));
                assertEquals(2L, engine.getActiveTransferAdmissionOrdinal(Long.MAX_VALUE));
                engine.addTransfer(8L, 100, Arrays.asList("another-unbounded"), 10, 0);
                assertEquals(3L, engine.getActiveTransferAdmissionOrdinal(8L));
            }
        }
    }

    @Test
    void initialAndIdleSnapshotsIncludeDeclaredResourcesAndTheCommittedClock() {
        for (boolean sharing : new boolean[] {true, false}) {
            TransferContentionEngine engine = TransferContentionEngine.coherentV2(sharing);
            TransferServiceSnapshot initial = engine.snapshotState();
            assertBits(0, initial.getObservedThrough());
            assertEquals(sharing, initial.isShared());
            assertTrue(initial.getResources().isEmpty());
            assertTrue(initial.getTransfers().isEmpty());

            engine.setEndpointCapacity("z-link", 20);
            engine.setEndpointCapacity("a-link", 10);
            TransferServiceSnapshot bound = engine.snapshotState();
            assertEquals(Arrays.asList("a-link", "z-link"), new ArrayList<String>(bound.getResources().keySet()));
            assertResource(bound, "a-link", 10, BigDecimal.ZERO, 0, 0);
            assertResource(bound, "z-link", 20, BigDecimal.ZERO, 0, 0);
            engine.setEndpointCapacity("z-link", 25);
            engine.advance(7.5);
            TransferServiceSnapshot idle = engine.snapshotState();
            assertBits(7.5, idle.getObservedThrough());
            assertBits(engine.getCurrentTime(), idle.getObservedThrough());
            assertEquals(sharing, idle.isShared());
            assertTrue(idle.getTransfers().isEmpty());
            assertResource(idle, "a-link", 10, BigDecimal.ZERO, 0, 0);
            assertResource(idle, "z-link", 25, BigDecimal.ZERO, 0, 0);
            assertBits(0, bound.getObservedThrough());
            assertResource(bound, "z-link", 20, BigDecimal.ZERO, 0, 0);
            assertTrue(initial.getResources().isEmpty());
        }
    }

    @Test
    void twoSharedHundredByteFlowsHaveEightyFiveBytesLeftAtThreeSeconds() {
        TransferContentionEngine engine = service(true, 10);
        engine.addTransfer(9L, 100, Arrays.asList("R"), 10, 0);
        engine.addTransfer(3L, 100, Arrays.asList("R"), 10, 0);
        engine.advance(3);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        assertBits(3, snapshot.getObservedThrough());
        assertTrue(snapshot.isShared());
        assertEquals(Arrays.asList(9L, 3L), new ArrayList<Long>(snapshot.getTransfers().keySet()));
        assertFlow(snapshot, 9L, 100, 85, 5, 10, 0, "R");
        assertFlow(snapshot, 3L, 100, 85, 5, 10, 0, "R");
        assertResource(snapshot, "R", 10, new BigDecimal(10), 2, 2);
    }

    @Test
    void lateAdmissionReportsCommittedReallocationAndEachActualAdmissionTime() {
        TransferContentionEngine engine = service(true, 10);
        engine.addTransfer(1L, 100, Arrays.asList("R"), 10, 2);
        engine.addTransfer(2L, 100, Arrays.asList("R"), 10, 5);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        assertBits(5, snapshot.getObservedThrough());
        assertFlow(snapshot, 1L, 100, 70, 5, 10, 2, "R");
        assertFlow(snapshot, 2L, 100, 100, 5, 10, 5, "R");
        assertResource(snapshot, "R", 10, new BigDecimal(10), 2, 2);
    }

    @Test
    void duplicatePathKeysConsumeCapacityPerOccurrenceInBothModes() {
        for (boolean sharing : new boolean[] {true, false}) {
            TransferContentionEngine engine = service(sharing, 12);
            engine.setEndpointCapacity("S", 100);
            engine.setEndpointCapacity("IDLE", 30);
            List<String> path = new ArrayList<String>(Arrays.asList("R", "S", "R"));
            engine.addTransfer(1L, 120, path, 12, 0);
            path.clear();
            engine.addTransfer(2L, 120, Arrays.asList("R"), 12, 0);
            TransferServiceSnapshot snapshot = engine.snapshotState();
            assertFlow(snapshot, 1L, 120, 120, sharing ? 4 : 6, 12, 0, "R", "S", "R");
            assertFlow(snapshot, 2L, 120, 120, sharing ? 4 : 12, 12, 0, "R");
            assertResource(snapshot, "R", 12, new BigDecimal(sharing ? 12 : 24), 2, 3);
            assertResource(snapshot, "S", 100, new BigDecimal(sharing ? 4 : 6), 1, 1);
            assertResource(snapshot, "IDLE", 30, BigDecimal.ZERO, 0, 0);
        }
    }

    @Test
    void resourceLoadsSumExactBinaryRatesRatherThanRoundedDecimalOrDoubleSums() {
        TransferContentionEngine engine = service(true, 1);
        engine.setEndpointCapacity("S", 1);
        engine.addTransfer(1L, 10, Arrays.asList("R", "S", "R"), 0.1, 0);
        engine.addTransfer(2L, 10, Arrays.asList("R", "S"), 0.2, 0);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        BigDecimal tenth = new BigDecimal(0.1);
        BigDecimal fifth = new BigDecimal(0.2);
        assertFlow(snapshot, 1L, 10, 10, 0.1, 0.1, 0, "R", "S", "R");
        assertFlow(snapshot, 2L, 10, 10, 0.2, 0.2, 0, "R", "S");
        assertResource(snapshot, "R", 1, tenth.multiply(BigDecimal.valueOf(2)).add(fifth), 2, 3);
        assertResource(snapshot, "S", 1, tenth.add(fifth), 2, 2);
        assertTrue(snapshot.getResources().get("R").getAssignedRateBytesPerSecond()
                .compareTo(new BigDecimal("0.4")) > 0);
        assertNotEquals(0, snapshot.getResources().get("S").getAssignedRateBytesPerSecond()
                .compareTo(new BigDecimal(0.1 + 0.2)));
    }

    @Test
    void isolatedLoadCanExceedPhysicalCapacityWithoutClamping() {
        TransferContentionEngine engine = service(false, 10);
        engine.addTransfer(1L, 100, Arrays.asList("R"), 100, 0);
        engine.addTransfer(2L, 100, Arrays.asList("R"), 100, 0);
        engine.advance(3);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        assertFalse(snapshot.isShared());
        assertBits(3, snapshot.getObservedThrough());
        assertFlow(snapshot, 1L, 100, 70, 10, 100, 0, "R");
        assertFlow(snapshot, 2L, 100, 70, 10, 100, 0, "R");
        assertResource(snapshot, "R", 10, new BigDecimal(20), 2, 2);
    }

    @Test
    void isolatedExactLoadCanExceedDoubleMaximumBeforeAnyAdvance() {
        TransferContentionEngine engine = service(false, Double.MAX_VALUE);
        engine.addTransfer(1L, Double.MAX_VALUE, Arrays.asList("R"), Double.MAX_VALUE, 0);
        engine.addTransfer(Long.MAX_VALUE, Double.MAX_VALUE, Arrays.asList("R"), Double.MAX_VALUE, 0);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        BigDecimal maximum = new BigDecimal(Double.MAX_VALUE);
        assertResource(snapshot, "R", Double.MAX_VALUE, maximum.multiply(BigDecimal.valueOf(2)), 2, 2);
        assertTrue(snapshot.getResources().get("R").getAssignedRateBytesPerSecond().compareTo(maximum) > 0);
        assertFlow(snapshot, 1L, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, 0, "R");
        assertFlow(snapshot, Long.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                Double.MAX_VALUE, Double.MAX_VALUE, 0, "R");
        assertBits(0, snapshot.getObservedThrough());
        assertBits(0, engine.getCurrentTime());
    }

    @Test
    void mapsEntriesViewsAndOrderedPathsAreDeeplyImmutable() {
        TransferContentionEngine engine = service(true, 10);
        engine.setEndpointCapacity("S", 20);
        engine.addTransfer(1L, 100, Arrays.asList("R", "S", "R"), 4, 0);
        TransferServiceSnapshot snapshot = engine.snapshotState();
        TransferServiceSnapshot.Resource resource = snapshot.getResources().get("R");
        TransferServiceSnapshot.Flow flow = snapshot.getTransfers().get(1L);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getResources().put("new", resource));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getResources().remove("R"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getResources().keySet().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getResources().entrySet().iterator().next().setValue(resource));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getTransfers().put(2L, flow));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getTransfers().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getTransfers().values().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getTransfers().entrySet().iterator().next().setValue(flow));
        assertThrows(UnsupportedOperationException.class, () -> flow.getResources().add("S"));
        assertThrows(UnsupportedOperationException.class, () -> flow.getResources().set(0, "S"));
        assertThrows(UnsupportedOperationException.class, () -> flow.getResources().clear());
        resource.getAssignedRateBytesPerSecond().add(BigDecimal.ONE);
        assertResource(snapshot, "R", 10, new BigDecimal(8), 1, 2);
        assertFlow(snapshot, 1L, 100, 100, 4, 4, 0, "R", "S", "R");
        assertSnapshotEquals(snapshot, engine.snapshotState());
    }

    @Test
    void repeatReadsLeaveTimeRatesTraceAndFutureServiceUnchangedWithCaptureOff() {
        for (boolean sharing : new boolean[] {true, false}) {
            TransferContentionEngine engine = service(sharing, 10);
            TransferContentionEngine unreadControl = service(sharing, 10);
            for (TransferContentionEngine target : Arrays.asList(engine, unreadControl)) {
                target.addTransfer(1L, 100, Arrays.asList("R"), 10, 0);
                target.addTransfer(2L, 200, Arrays.asList("R"), 10, 0);
                target.advance(3);
            }
            TransferServiceSnapshot before = engine.snapshotState();
            TransferTraceSnapshot trace = engine.getTraceSnapshot();
            assertEquals(TransferTraceSnapshot.Status.DISABLED, trace.getStatus());
            assertTrue(trace.getEvents().isEmpty());
            assertEquals(0L, trace.getDroppedCount());
            for (int i = 0; i < 32; i++) {
                assertSnapshotEquals(before, engine.snapshotState());
                assertBits(3, engine.getCurrentTime());
                assertBits(sharing ? 5 : 10, engine.currentRateBytesPerSecond(1L));
                assertBits(sharing ? 5 : 10, engine.currentRateBytesPerSecond(2L));
                assertEquals(2, engine.activeTransferCount());
                assertTraceEquals(trace, engine.getTraceSnapshot());
            }
            assertThrows(IllegalStateException.class, () -> engine.getActiveTransferAdmissionOrdinal(1L));
            TransferContentionEngine.AdvanceResult result = engine.advance(40);
            TransferContentionEngine.AdvanceResult control = unreadControl.advance(40);
            assertEquals(Arrays.asList(1L, 2L), result.getCompletedTransferIds());
            assertEquals(control.getCompletedTransferIds(), result.getCompletedTransferIds());
            assertBits(sharing ? 20 : 10, result.getCompletionObservations().get(0).getEffectiveTime());
            assertBits(sharing ? 30 : 20, result.getCompletionObservations().get(1).getEffectiveTime());
            for (TransferContentionEngine.CompletionObservation completion : result.getCompletionObservations()) {
                assertBits(40, completion.getObservedTime());
                assertBits(0, completion.getRemainingAfterService());
            }
            assertSnapshotEquals(unreadControl.snapshotState(), engine.snapshotState());
            engine.addTransfer(Long.MAX_VALUE, 10, Arrays.asList("R"), 10, 40);
            assertFlow(engine.snapshotState(), Long.MAX_VALUE, 10, 10, 10, 10, 40, "R");
            assertTrue(engine.getTraceSnapshot().getEvents().isEmpty());
            assertEquals(TransferTraceSnapshot.Status.DISABLED, engine.getTraceSnapshot().getStatus());
        }
    }

    @Test
    void retainedSnapshotsSurviveProgressCompletionCapacityChangesAndNewAdmission() {
        TransferContentionEngine engine = service(true, 10);
        engine.setEndpointCapacity("UNUSED", 30);
        engine.addTransfer(1L, 100, Arrays.asList("R"), 10, 0);
        TransferServiceSnapshot admitted = engine.snapshotState();
        engine.advance(3);
        TransferServiceSnapshot progressed = engine.snapshotState();
        TransferContentionEngine.AdvanceResult completion = engine.advance(12);
        assertBits(10, completion.getCompletionObservations().get(0).getEffectiveTime());
        TransferServiceSnapshot idle = engine.snapshotState();
        assertBits(12, idle.getObservedThrough()); // The observed clock, not the effective completion.
        assertTrue(idle.getTransfers().isEmpty());
        engine.setEndpointCapacity("R", 20);
        engine.setEndpointCapacity("NEW", 5);
        engine.addTransfer(2L, 60, Arrays.asList("R"), 20, 14);
        TransferServiceSnapshot latest = engine.snapshotState();
        assertBits(14, latest.getObservedThrough());
        assertEquals(Arrays.asList(2L), new ArrayList<Long>(latest.getTransfers().keySet()));
        assertFlow(latest, 2L, 60, 60, 20, 20, 14, "R");
        assertResource(latest, "R", 20, new BigDecimal(20), 1, 1);
        assertResource(latest, "NEW", 5, BigDecimal.ZERO, 0, 0);
        assertBits(0, admitted.getObservedThrough());
        assertFlow(admitted, 1L, 100, 100, 10, 10, 0, "R");
        assertResource(admitted, "R", 10, new BigDecimal(10), 1, 1);
        assertBits(3, progressed.getObservedThrough());
        assertFlow(progressed, 1L, 100, 70, 10, 10, 0, "R");
        assertResource(idle, "R", 10, BigDecimal.ZERO, 0, 0);
        assertResource(idle, "UNUSED", 30, BigDecimal.ZERO, 0, 0);
        assertFalse(idle.getResources().containsKey("NEW"));
        assertTrue(idle.getTransfers().isEmpty());
    }

    @Test
    void failedCheckedAdmissionAllocationLeavesTheObservationUnchanged() {
        TransferContentionEngine engine = service(true, 1);
        engine.addTransfer(1L, Double.MAX_VALUE, Arrays.asList("R"), 1, 0);
        TransferServiceSnapshot before = engine.snapshotState();
        TransferTraceSnapshot trace = engine.getTraceSnapshot();
        // Sharing would halve the rate and overflow the remaining service duration.
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(2L, Double.MAX_VALUE, Arrays.asList("R"), 1, 1));
        assertSnapshotEquals(before, engine.snapshotState());
        assertTraceEquals(trace, engine.getTraceSnapshot());
        assertBits(0, engine.getCurrentTime());
        assertFlow(before, 1L, Double.MAX_VALUE, Double.MAX_VALUE, 1, 1, 0, "R");
        assertResource(before, "R", 1, BigDecimal.ONE, 1, 1);
    }

    @Test
    void failedCheckedAdvanceReallocationRollsBackTheEntireObservation() {
        TransferContentionEngine engine = service(true, 2);
        double start = 0x1.0p53;
        engine.advance(start);
        engine.addTransfer(1L, 4, Arrays.asList("R"), 2, start);
        engine.addTransfer(2L, 5, Arrays.asList("R"), 2, start);
        TransferServiceSnapshot before = engine.snapshotState();
        TransferTraceSnapshot trace = engine.getTraceSnapshot();
        // After settling flow 1, flow 2's half-second finish cannot advance this clock.
        assertThrows(IllegalArgumentException.class, () -> engine.advance(start + 4));
        assertSnapshotEquals(before, engine.snapshotState());
        assertTraceEquals(trace, engine.getTraceSnapshot());
        assertBits(start, engine.getCurrentTime());
        assertFlow(before, 1L, 4, 4, 1, 2, start, "R");
        assertFlow(before, 2L, 5, 5, 1, 2, start, "R");
        assertResource(before, "R", 2, new BigDecimal(2), 2, 2);
    }

    @Test
    void forkSnapshotsRemainIndependentThroughServiceAndAdmissionsInBothModes() {
        for (boolean sharing : new boolean[] {true, false}) {
            TransferContentionEngine original = service(sharing, 10);
            original.addTransfer(11L, 100, Arrays.asList("R"), 10, 0);
            original.advance(3);
            TransferServiceSnapshot originalBefore = original.snapshotState();
            TransferContentionEngine fork = original.fork();
            assertSnapshotEquals(originalBefore, fork.snapshotState());
            fork.advance(5);
            fork.addTransfer(22L, 100, Arrays.asList("R"), 10, 5);
            TransferServiceSnapshot forkBefore = fork.snapshotState();
            assertBits(5, forkBefore.getObservedThrough());
            assertEquals(sharing, forkBefore.isShared());
            assertFlow(forkBefore, 11L, 100, 50, sharing ? 5 : 10, 10, 0, "R");
            assertFlow(forkBefore, 22L, 100, 100, sharing ? 5 : 10, 10, 5, "R");
            assertSnapshotEquals(originalBefore, original.snapshotState());
            original.advance(12);
            assertTrue(original.snapshotState().getTransfers().isEmpty());
            assertSnapshotEquals(forkBefore, fork.snapshotState());
            assertEquals(Arrays.asList(11L, 22L), fork.advance(25).getCompletedTransferIds());
            assertBits(3, originalBefore.getObservedThrough());
            assertFlow(originalBefore, 11L, 100, 70, 10, 10, 0, "R");
            assertBits(5, forkBefore.getObservedThrough());
            assertFlow(forkBefore, 22L, 100, 100, sharing ? 5 : 10, 10, 5, "R");
        }
    }

    @Test
    void callerIdentitiesIncludingLongMaximumKeepAdmissionOrderAcrossTransitions() {
        TransferContentionEngine engine = service(true, 50);
        List<Long> ids = Arrays.asList(Long.MAX_VALUE, 7L, Long.MIN_VALUE, 0L, Long.MAX_VALUE - 1);
        for (Long id : ids) {
            engine.addTransfer(id, id == Long.MAX_VALUE ? 10 : 100, Arrays.asList("R"), 10, 0);
        }
        TransferServiceSnapshot before = engine.snapshotState();
        assertEquals(ids, new ArrayList<Long>(before.getTransfers().keySet()));
        for (Long id : ids) {
            assertEquals(id.longValue(), before.getTransfers().get(id).getTransferId());
        }
        assertResource(before, "R", 50, new BigDecimal(50), 5, 5);
        assertEquals(Arrays.asList(Long.MAX_VALUE), engine.advance(1).getCompletedTransferIds());
        engine.addTransfer(900L, 20, Arrays.asList("R"), 10, 1);
        TransferServiceSnapshot after = engine.snapshotState();
        assertEquals(Arrays.asList(7L, Long.MIN_VALUE, 0L, Long.MAX_VALUE - 1, 900L),
                new ArrayList<Long>(after.getTransfers().keySet()));
        assertFlow(after, Long.MAX_VALUE - 1, 100, 90, 10, 10, 0, "R");
        assertFlow(after, 900L, 20, 20, 10, 10, 1, "R");
        assertEquals(ids, new ArrayList<Long>(before.getTransfers().keySet()));
        assertFlow(before, Long.MAX_VALUE, 10, 10, 10, 10, 0, "R");
    }

    private static TransferContentionEngine service(boolean sharing, double capacity) {
        TransferContentionEngine engine = TransferContentionEngine.coherentV2(sharing);
        engine.setEndpointCapacity("R", capacity);
        return engine;
    }

    private static void assertFlow(TransferServiceSnapshot snapshot, long id, double bytes,
            double remaining, double rate, double nominalRate, double admittedAt, String... path) {
        TransferServiceSnapshot.Flow flow = snapshot.getTransfers().get(id);
        assertNotNull(flow);
        assertEquals(id, flow.getTransferId());
        assertBits(bytes, flow.getBytes());
        assertBits(remaining, flow.getRemainingBytes());
        assertBits(rate, flow.getRateBytesPerSecond());
        assertBits(nominalRate, flow.getNominalRateBytesPerSecond());
        assertBits(admittedAt, flow.getAdmittedAt());
        assertEquals(Arrays.asList(path), flow.getResources());
    }

    private static void assertResource(TransferServiceSnapshot snapshot, String key, double capacity,
            BigDecimal assignedRate, int activeFlows, long pathMultiplicity) {
        TransferServiceSnapshot.Resource resource = snapshot.getResources().get(key);
        assertNotNull(resource);
        assertEquals(key, resource.getKey());
        assertBits(capacity, resource.getCapacityBytesPerSecond());
        assertEquals(0, assignedRate.compareTo(resource.getAssignedRateBytesPerSecond()));
        assertEquals(activeFlows, resource.getActiveFlowCount());
        assertEquals(pathMultiplicity, resource.getPathMultiplicityCount());
    }

    private static void assertSnapshotEquals(TransferServiceSnapshot expected, TransferServiceSnapshot actual) {
        assertBits(expected.getObservedThrough(), actual.getObservedThrough());
        assertEquals(expected.isShared(), actual.isShared());
        assertEquals(new ArrayList<String>(expected.getResources().keySet()),
                new ArrayList<String>(actual.getResources().keySet()));
        assertEquals(new ArrayList<Long>(expected.getTransfers().keySet()),
                new ArrayList<Long>(actual.getTransfers().keySet()));
        for (TransferServiceSnapshot.Resource resource : expected.getResources().values()) {
            assertResource(actual, resource.getKey(), resource.getCapacityBytesPerSecond(),
                    resource.getAssignedRateBytesPerSecond(), resource.getActiveFlowCount(),
                    resource.getPathMultiplicityCount());
        }
        for (Map.Entry<Long, TransferServiceSnapshot.Flow> entry : expected.getTransfers().entrySet()) {
            TransferServiceSnapshot.Flow flow = entry.getValue();
            assertFlow(actual, entry.getKey(), flow.getBytes(), flow.getRemainingBytes(),
                    flow.getRateBytesPerSecond(), flow.getNominalRateBytesPerSecond(), flow.getAdmittedAt(),
                    flow.getResources().toArray(new String[0]));
        }
    }

    private static void assertTraceEquals(TransferTraceSnapshot expected, TransferTraceSnapshot actual) {
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getEvents(), actual.getEvents());
        assertEquals(expected.getDroppedCount(), actual.getDroppedCount());
        assertBits(expected.getEngineTime(), actual.getEngineTime());
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }
}
