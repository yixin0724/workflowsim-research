package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.data.TransferTraceEvent.Type.*;
import static org.workflowsim.data.TransferTraceSnapshot.Status.DISABLED;
import static org.workflowsim.data.TransferTraceSnapshot.Status.TRUNCATED;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** NF001 contracts: independent fluid arithmetic, numeric boundaries, and opt-in isolation. */
class TransferContentionTraceTest {
    private static final TransferTraceSnapshot.Status CAPTURE_COMPLETE = TransferTraceSnapshot.Status.COMPLETE;

    @Test
    void constructorsMakeCaptureExplicitAndRejectNegativeBudgets() {
        for (TransferContentionEngine engine : Arrays.asList(new TransferContentionEngine(),
                new TransferContentionEngine(0))) {
            assertSnapshot(engine.getTraceSnapshot(), DISABLED, 0, 0, 0);
        }
        TransferContentionEngine enabled = new TransferContentionEngine(16);
        assertSnapshot(enabled.getTraceSnapshot(), CAPTURE_COMPLETE, 0, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> new TransferContentionEngine(-1));
        assertThrows(IllegalArgumentException.class, () -> new TransferContentionEngine(Integer.MIN_VALUE));
        add(enabled, 1, 100, 10, 0);
        // COMPLETE describes capture, not whether active flows have finished.
        assertEquals(1, enabled.activeTransferCount());
        assertSnapshot(enabled.getTraceSnapshot(), CAPTURE_COMPLETE, 1, 0, 0);
    }

    @Test
    void capacityEventsUseTheKnownEngineClockAndOnlySuccessfulPuts() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.advance(7.5);
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 0, 0, 7.5);
        assertThrows(IllegalArgumentException.class, () -> engine.setEndpointCapacity(null, 100));
        assertThrows(IllegalArgumentException.class, () -> engine.setEndpointCapacity("", 100));
        for (double bad : new double[] {0, -1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> engine.setEndpointCapacity("A", bad));
        }
        assertEquals(0, engine.getTraceSnapshot().getEvents().size());
        engine.setEndpointCapacity("A", 100);
        engine.setEndpointCapacity("A", 60);
        engine.setEndpointCapacity("A", 60); // Successful same-value puts also count.
        List<TransferTraceEvent> capacities = engine.getTraceSnapshot().getEvents();
        assertTypes(capacities, CAPACITY, CAPACITY, CAPACITY);
        for (int i = 0; i < capacities.size(); i++) {
            TransferTraceEvent event = capacities.get(i);
            assertCommon(event, i + 1, CAPACITY, null, null, 7.5, 7.5);
            assertEquals("A", event.getCapacity().getResourceKey());
            assertBits(i == 0 ? 100.0 : 60.0, event.getCapacity().getCapacityBytesPerSecond());
        }
        add(engine, 1, 60, 100, 7.5, "A");
        assertBits(60, engine.currentRateBytesPerSecond(1));
        assertThrows(IllegalStateException.class, () -> engine.setEndpointCapacity("A", 80));
        assertThrows(IllegalArgumentException.class,
                () -> engine.setEndpointCapacity("A", Double.NaN));
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 4, 0, 7.5);
        engine.advance(9.5);
        engine.setEndpointCapacity("A", 80);
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertCommon(events.get(6), 7, CAPACITY, null, null, 9.5, 9.5);
        assertBits(80, events.get(6).getCapacity().getCapacityBytesPerSecond());
    }

    @Test
    void nominalCapReturnsUnusedCapacityAndStartContainsTheFinalRate() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 100, 10, 0, "A");
        TransferContentionEngine.AdvanceResult second = add(engine, 2, 900, 100, 0, "A");
        assertBits(10, engine.currentRateBytesPerSecond(1));
        assertBits(90, engine.currentRateBytesPerSecond(2));
        assertBits(10, second.getNextCompletionTime());
        List<TransferTraceEvent> admission = engine.getTraceSnapshot().getEvents();
        assertTypes(admission, CAPACITY, START, START);
        assertStart(admission.get(1), 1, 1, 100, 10, 10, Arrays.asList("A"));
        assertStart(admission.get(2), 2, 2, 900, 100, 90, Arrays.asList("A"));
        assertEquals(Arrays.asList(1L, 2L), engine.advance(10).getCompletedTransferIds());
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, CAPACITY, START, START, SERVICE_SEGMENT, SERVICE_SEGMENT, COMPLETE, COMPLETE);
        assertService(events.get(3), 1, 1, 0, 10, 10, 10, 100, 0, 10);
        assertService(events.get(4), 2, 2, 0, 10, 10, 90, 900, 0, 10);
        assertCompletion(events.get(5), 1, 1, 10, 10, 0);
        assertCompletion(events.get(6), 2, 2, 10, 10, 0);
        // 10*10 + 90*10 = 100*10: independent flow and capacity balances.
        assertBits(100, serviceArea(events, 1, 1));
        assertBits(900, serviceArea(events, 2, 2));
        assertBits(1000, serviceArea(events, 1, 1) + serviceArea(events, 2, 2));
    }

    @Test
    void cascadingBottlenecksEmitOnlyValidatedFinalAllocationChanges() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 100);
        engine.setEndpointCapacity("B", 40);
        add(engine, 1, 1000, 100, 0, "A");
        add(engine, 2, 1000, 100, 0, "A", "B");
        add(engine, 3, 1000, 10, 0, "B");
        assertBits(70, engine.currentRateBytesPerSecond(1));
        assertBits(30, engine.currentRateBytesPerSecond(2));
        assertBits(10, engine.currentRateBytesPerSecond(3));
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, CAPACITY, CAPACITY, START, START, RATE_CHANGE, START, RATE_CHANGE, RATE_CHANGE);
        assertStart(events.get(2), 1, 1, 1000, 100, 100, Arrays.asList("A"));
        assertStart(events.get(3), 2, 2, 1000, 100, 40, Arrays.asList("A", "B"));
        assertRateChange(events.get(4), 1, 1, 100, 60, 0, 0);
        assertStart(events.get(5), 3, 3, 1000, 10, 10, Arrays.asList("B"));
        assertRateChange(events.get(6), 1, 1, 60, 70, 0, 0);
        assertRateChange(events.get(7), 2, 2, 40, 30, 0, 0);
        engine.advance(1);
        List<TransferTraceEvent> services = engine.getTraceSnapshot().getEvents().subList(8, 11);
        assertService(services.get(0), 1, 1, 0, 1, 1, 70, 1000, 930, 1);
        assertService(services.get(1), 2, 2, 0, 1, 1, 30, 1000, 970, 1);
        assertService(services.get(2), 3, 3, 0, 1, 1, 10, 1000, 990, 1);
        assertBits(100, serviceArea(services, 1, 1) + serviceArea(services, 2, 2));
        assertBits(40, serviceArea(services, 2, 2) + serviceArea(services, 3, 3));
    }

    @Test
    void duplicateResourceWeightsAndInputOrderSurviveCallerMutation() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 100);
        List<String> resources = new ArrayList<String>(Arrays.asList("A", "UNREGISTERED", "A"));
        engine.addTransfer(1, 1000.0, resources, 10, 0);
        TransferTraceSnapshot before = engine.getTraceSnapshot();
        resources.clear();
        resources.add("B");
        add(engine, 2, 1000, 100, 0, "A");
        assertBits(10, engine.currentRateBytesPerSecond(1));
        assertBits(80, engine.currentRateBytesPerSecond(2));
        TransferTraceEvent start = before.getEvents().get(1);
        assertStart(start, 1, 1, 1000, 10, 10, Arrays.asList("A", "UNREGISTERED", "A"));
        assertThrows(UnsupportedOperationException.class,
                () -> start.getStart().getOccupiedResources().set(0, "B"));
        engine.advance(1);
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertBits(10, serviceArea(events, 1, 1));
        assertBits(80, serviceArea(events, 2, 2));
        // Duplicate A consumes capacity twice: 2*10 + 80 = 100.
        assertBits(100, 2 * serviceArea(events, 1, 1) + serviceArea(events, 2, 2));
        assertEquals(Arrays.asList("A", "UNREGISTERED", "A"), start.getStart().getOccupiedResources());
    }

    @Test
    void lateAdvanceKeepsInternalBoundariesAndDoesNotInventIdleTailService() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 500, 100, 0, "A");
        add(engine, 2, 1000, 100, 0, "A");
        TransferContentionEngine.AdvanceResult result = engine.advance(20);
        assertEquals(Arrays.asList(1L, 2L), result.getCompletedTransferIds());
        assertNull(result.getNextCompletionTime());
        assertEquals(0, engine.activeTransferCount());
        TransferTraceSnapshot snapshot = engine.getTraceSnapshot();
        assertSnapshot(snapshot, CAPTURE_COMPLETE, 10, 0, 20);
        List<TransferTraceEvent> events = snapshot.getEvents();
        assertTypes(events, CAPACITY, START, START, RATE_CHANGE,
                SERVICE_SEGMENT, SERVICE_SEGMENT, COMPLETE, RATE_CHANGE, SERVICE_SEGMENT, COMPLETE);
        assertStart(events.get(2), 2, 2, 1000, 100, 50, Arrays.asList("A"));
        assertRateChange(events.get(3), 1, 1, 100, 50, 0, 0);
        assertService(events.get(4), 1, 1, 0, 10, 10, 50, 500, 0, 20);
        assertService(events.get(5), 2, 2, 0, 10, 10, 50, 1000, 500, 20);
        assertCompletion(events.get(6), 1, 1, 10, 20, 0);
        assertRateChange(events.get(7), 2, 2, 50, 100, 10, 20);
        assertService(events.get(8), 2, 2, 10, 15, 5, 100, 500, 0, 20);
        assertCompletion(events.get(9), 2, 2, 15, 20, 0);
        assertBits(500, serviceArea(events, 1, 1));
        assertBits(1000, serviceArea(events, 2, 2));
        engine.advance(20);
        engine.advance(25);
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 10, 0, 25);
        assertSnapshot(snapshot, CAPTURE_COMPLETE, 10, 0, 20);
    }

    @Test
    void lateAdmissionObservesEarlierCompletionBeforeItsNewStart() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 100);
        add(engine, 1, 10, 10, 0, "A");
        add(engine, 2, 180, 100, 0, "A");
        TransferContentionEngine.AdvanceResult result = add(engine, 3, 100, 100, 1.5, "A");
        assertEquals(Collections.singletonList(1L), result.getCompletedTransferIds());
        // At 1.5 the older flow has 180 - 90*1 - 100*0.5 = 40 bytes left.
        assertBits(2.3, result.getNextCompletionTime());
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, CAPACITY, START, START, SERVICE_SEGMENT, SERVICE_SEGMENT,
                COMPLETE, RATE_CHANGE, SERVICE_SEGMENT, START, RATE_CHANGE);
        assertService(events.get(3), 1, 1, 0, 1, 1, 10, 10, 0, 1.5);
        assertService(events.get(4), 2, 2, 0, 1, 1, 90, 180, 90, 1.5);
        assertCompletion(events.get(5), 1, 1, 1, 1.5, 0);
        assertRateChange(events.get(6), 2, 2, 90, 100, 1, 1.5);
        assertService(events.get(7), 2, 2, 1, 1.5, 0.5, 100, 90, 40, 1.5);
        assertCommon(events.get(8), 9, START, 3L, 3L, 1.5, 1.5);
        assertStart(events.get(8), 3, 3, 100, 100, 50, Arrays.asList("A"));
        assertRateChange(events.get(9), 2, 2, 100, 50, 1.5, 1.5);
        assertEquals(Arrays.asList(2L, 3L), engine.advance(3).getCompletedTransferIds());
    }

    @Test
    void simultaneousReverseIdsUseInsertionOrderWithinAllBoundaryPhases() {
        TransferContentionEngine engine = new TransferContentionEngine(64);
        engine.setEndpointCapacity("A", 90);
        add(engine, 9, 30, 90, 0, "A");
        add(engine, 3, 30, 90, 0, "A");
        add(engine, -4, 90, 90, 0, "A");
        int before = engine.getTraceSnapshot().getEvents().size();
        TransferContentionEngine.AdvanceResult result = engine.advance(1);
        assertEquals(Arrays.asList(9L, 3L), result.getCompletedTransferIds());
        assertBits(1.0 + 60.0 / 90.0, result.getNextCompletionTime());
        List<TransferTraceEvent> all = engine.getTraceSnapshot().getEvents();
        assertSequences(all);
        List<TransferTraceEvent> boundary = all.subList(before, all.size());
        assertTypesWithoutSequence(boundary, SERVICE_SEGMENT, SERVICE_SEGMENT, SERVICE_SEGMENT,
                COMPLETE, COMPLETE, RATE_CHANGE);
        assertService(boundary.get(0), 9, 1, 0, 1, 1, 30, 30, 0, 1);
        assertService(boundary.get(1), 3, 2, 0, 1, 1, 30, 30, 0, 1);
        assertService(boundary.get(2), -4, 3, 0, 1, 1, 30, 90, 60, 1);
        assertCompletion(boundary.get(3), 9, 1, 1, 1, 0);
        assertCompletion(boundary.get(4), 3, 2, 1, 1, 0);
        assertRateChange(boundary.get(5), -4, 3, 30, 90, 1, 1);
    }

    @Test
    void reversedPathsShareResourcesWhileDisjointPathsKeepNominalRate() {
        TransferContentionEngine engine = new TransferContentionEngine(64);
        engine.setEndpointCapacity("A", 100);
        engine.setEndpointCapacity("B", 100);
        add(engine, 9, 100, 100, 0, "A", "B");
        add(engine, 1, 100, 100, 0, "B", "A");
        add(engine, 3, 100, 100, 0, "UNREGISTERED");
        assertBits(50, engine.currentRateBytesPerSecond(9));
        assertBits(50, engine.currentRateBytesPerSecond(1));
        assertBits(100, engine.currentRateBytesPerSecond(3));
        List<TransferTraceEvent> starts = eventsOfType(engine.getTraceSnapshot(), START);
        assertEquals(Arrays.asList("A", "B"), starts.get(0).getStart().getOccupiedResources());
        assertEquals(Arrays.asList("B", "A"), starts.get(1).getStart().getOccupiedResources());
        assertEquals(Collections.singletonList(3L), engine.advance(1).getCompletedTransferIds());
        assertEquals(1, eventsOfType(engine.getTraceSnapshot(), RATE_CHANGE).size());
        assertEquals(Arrays.asList(9L, 1L), engine.advance(2).getCompletedTransferIds());
        assertBits(100, serviceArea(engine.getTraceSnapshot().getEvents(), 3, 3));
    }

    @Test
    void sameTimeAdvancesAndUnchangedFinalRatesEmitNothing() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.setEndpointCapacity("A", 1000);
        add(engine, 1, 100, 10, 0, "A");
        add(engine, 2, 100, 10, 0, "A");
        assertTypes(engine.getTraceSnapshot().getEvents(), CAPACITY, START, START);
        for (int i = 0; i < 3; i++) {
            assertBits(10, engine.advance(0).getNextCompletionTime());
            assertBits(10, engine.currentRateBytesPerSecond(1));
        }
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 3, 0, 0);
        engine.advance(1);
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 5, 0, 1);
        engine.advance(1);
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 5, 0, 1);
        assertTrue(eventsOfType(engine.getTraceSnapshot(), RATE_CHANGE).isEmpty());
    }

    @Test
    void halfByteEndpointDemandIsNotRoundedInStartOrService() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        TransferContentionEngine.AdvanceResult start =
                engine.addTransfer(1, 0.5, "SOURCE", "VM:0", 1_000_000.0, 0.0);
        double duration = 0.5 / 1_000_000.0;
        assertBits(duration, start.getNextCompletionTime());
        assertEquals(Collections.singletonList(1L), engine.advance(0.000002).getCompletedTransferIds());
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, START, SERVICE_SEGMENT, COMPLETE);
        assertStart(events.get(0), 1, 1, 0.5, 1_000_000, 1_000_000, Arrays.asList("SOURCE", "VM:0"));
        assertService(events.get(1), 1, 1, 0, duration, duration, 1_000_000, 0.5, 0, 0.000002);
        assertCompletion(events.get(2), 1, 1, duration, 0.000002, 0);
        assertBits(0.5, serviceArea(events, 1, 1));
    }

    @Test
    void independentTinyFlowsKeepFractionalServiceAndTheirOwnCompletionTimes() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        add(engine, 1, 5.0e-10, 1.0e-9, 0);
        add(engine, 2, 1.0e-12, 1.0e-9, 0);
        assertEquals(2, engine.activeTransferCount());
        assertTrue(engine.advance(0).getCompletedTransferIds().isEmpty());
        double smallDuration = 1.0e-12 / 1.0e-9;
        assertEquals(Collections.singletonList(2L), engine.advance(smallDuration).getCompletedTransferIds());
        List<TransferTraceEvent> small = engine.getTraceSnapshot().getEvents();
        assertTypes(small, START, START, SERVICE_SEGMENT, SERVICE_SEGMENT, COMPLETE);
        assertService(small.get(2), 1, 1, 0, smallDuration, smallDuration,
                1.0e-9, 5.0e-10, 5.0e-10 - 1.0e-9 * smallDuration, smallDuration);
        assertService(small.get(3), 2, 2, 0, smallDuration, smallDuration,
                1.0e-9, 1.0e-12, Math.max(0.0, 1.0e-12 - 1.0e-9 * smallDuration), smallDuration);
        assertTrue(engine.advance(0.499).getCompletedTransferIds().isEmpty());
        assertEquals(Collections.singletonList(1L), engine.advance(0.5).getCompletedTransferIds());
        List<TransferTraceEvent> completions = eventsOfType(engine.getTraceSnapshot(), COMPLETE);
        assertEquals(Long.valueOf(2), completions.get(0).getTransferId());
        assertEquals(0.001, completions.get(0).getEffectiveTime(), 1.0e-18);
        assertEquals(Long.valueOf(1), completions.get(1).getTransferId());
        assertEquals(0.5, completions.get(1).getEffectiveTime(), 1.0e-15);
        assertEquals(1.0e-12, serviceArea(engine.getTraceSnapshot().getEvents(), 2, 2), 1.0e-27);
        assertEquals(5.0e-10, serviceArea(engine.getTraceSnapshot().getEvents(), 1, 1), 1.0e-24);
        assertTrue(eventsOfType(engine.getTraceSnapshot(), RATE_CHANGE).isEmpty());
    }

    @Test
    void toleranceCompletionKeepsResidualSeparateAndDoesNotRetimeToIdealFinish() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        add(engine, 1, 1, 1, 0);
        double observed = 1.0 - 5.0e-10;
        double residual = 1.0 - observed;
        assertTrue(residual > 0);
        assertEquals(Collections.singletonList(1L), engine.advance(observed).getCompletedTransferIds());
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, START, SERVICE_SEGMENT, COMPLETE);
        assertService(events.get(1), 1, 1, 0, observed, observed, 1, 1, residual, observed);
        assertCompletion(events.get(2), 1, 1, observed, observed, residual);
        assertTrue(serviceArea(events, 1, 1) < 1.0);
        assertBits(1, serviceArea(events, 1, 1) + residual);
        assertTrue(events.get(2).getEffectiveTime() < 1.0);
        engine.advance(1);
        assertEquals(3, engine.getTraceSnapshot().getEvents().size());
    }

    @Test
    void forcedEarliestZeroDoesNotEraseTheOriginalSubtractResidual() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        add(engine, 1, 1, 49, 0);
        // The rounded reciprocal makes 49*(1/49) one representable value below 1.
        double duration = 1.0 / 49.0;
        double residual = 1.0 - 49.0 * duration;
        assertTrue(residual > 0.0);
        assertEquals(Collections.singletonList(1L), engine.advance(1).getCompletedTransferIds());
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, START, SERVICE_SEGMENT, COMPLETE);
        assertService(events.get(1), 1, 1, 0, duration, duration, 49, 1, residual, 1);
        assertCompletion(events.get(2), 1, 1, duration, 1, residual);
        assertEquals(0, engine.activeTransferCount());
        assertBits(1, serviceArea(events, 1, 1) + residual);
    }

    @Test
    void largeClockPreservesPositiveElapsedWhenStartAndEndRoundToTheSameTime() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        double start = 0x1.0p54;
        double observed = Math.nextUp(start);
        engine.advance(start);
        TransferContentionEngine.AdvanceResult admitted = add(engine, 1, 1, 49, start);
        assertBits(start, admitted.getNextCompletionTime());
        assertEquals(Collections.singletonList(1L), engine.advance(observed).getCompletedTransferIds());
        double elapsed = 1.0 / 49.0;
        double residual = 1.0 - 49.0 * elapsed;
        List<TransferTraceEvent> events = engine.getTraceSnapshot().getEvents();
        assertTypes(events, START, SERVICE_SEGMENT, COMPLETE);
        assertService(events.get(1), 1, 1, start, start, elapsed, 49, 1, residual, observed);
        assertTrue(events.get(1).getServiceSegment().getElapsed() > 0);
        assertBits(0, events.get(1).getServiceSegment().getIntervalEnd()
                - events.get(1).getServiceSegment().getIntervalStart());
        assertCompletion(events.get(2), 1, 1, start, observed, residual);
        assertBits(1, serviceArea(events, 1, 1) + residual);
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 3, 0, observed);
    }

    @Test
    void completedExternalIdsCanBeReusedWithDistinctAdmissionOrdinals() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        assertThrows(IllegalArgumentException.class, () -> add(engine, 5, 0, 1, 0));
        add(engine, 5, 0.5, 1, 0);
        assertEquals(Collections.singletonList(5L), engine.advance(0.5).getCompletedTransferIds());
        assertThrows(IllegalArgumentException.class, () -> add(engine, 5, 0, 1, 0.5));
        add(engine, 5, 1, 1, 0.5);
        assertEquals(Collections.singletonList(5L), engine.advance(1.5).getCompletedTransferIds());
        add(engine, -1, 1, 1, 1.5);
        List<TransferTraceEvent> starts = eventsOfType(engine.getTraceSnapshot(), START);
        assertEquals(3, starts.size());
        assertStart(starts.get(0), 5, 1, 0.5, 1, 1, Collections.<String>emptyList());
        assertStart(starts.get(1), 5, 2, 1, 1, 1, Collections.<String>emptyList());
        assertStart(starts.get(2), -1, 3, 1, 1, 1, Collections.<String>emptyList());
        List<TransferTraceEvent> completions = eventsOfType(engine.getTraceSnapshot(), COMPLETE);
        assertCompletion(completions.get(0), 5, 1, 0.5, 0.5, 0);
        assertCompletion(completions.get(1), 5, 2, 1.5, 1.5, 0);
    }

    @Test
    void duplicateActiveIdAtFutureTimeStillFailsBeforeAdvance() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        add(engine, 7, 1, 1, 0);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> add(engine, 7, 2, 1, 100));
        assertEquals("Duplicate transfer id: 7", failure.getMessage());
        IllegalArgumentException beforeOtherValidation = assertThrows(IllegalArgumentException.class,
                () -> add(engine, 7, Double.NaN, 1, 100));
        assertEquals(failure.getMessage(), beforeOtherValidation.getMessage());
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 1, 0, 0);
        assertEquals(1, engine.activeTransferCount());
        assertBits(1, engine.advance(0).getNextCompletionTime());
        assertEquals(Collections.singletonList(7L), engine.advance(1).getCompletedTransferIds());
        add(engine, 7, 1, 1, 1);
        assertStart(engine.getTraceSnapshot().getEvents().get(3), 7, 2, 1, 1, 1,
                Collections.<String>emptyList());
    }

    @Test
    void rejectedAdmissionsAndInvalidTimesEmitNothingAndConsumeNoOrdinal() {
        TransferContentionEngine engine = new TransferContentionEngine(32);
        engine.advance(2);
        for (double bytes : new double[] {0, -0.5, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> add(engine, 1, bytes, 1, 3));
        }
        for (double rate : new double[] {0, -1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> add(engine, 1, 1, rate, 3));
        }
        assertThrows(IllegalArgumentException.class, () -> add(engine, 1, Double.MIN_VALUE, 1_000_000, 3));
        assertThrows(IllegalArgumentException.class, () -> add(engine, 1, Double.MAX_VALUE, Double.MIN_VALUE, 3));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(1, 1.0, (List<String>) null, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> add(engine, 1, 1, 1, 3, "A", null));
        assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(1, 1.0, null, "A", 1, 3));
        assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(1, 1L, "A", null, 1, 3));
        for (double time : new double[] {1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> engine.advance(time));
            assertThrows(IllegalArgumentException.class, () -> add(engine, 1, 1, 1, time));
        }
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 0, 0, 2);
        assertEquals(0, engine.activeTransferCount());
        add(engine, 1, 1, 1, 2);
        assertCommon(engine.getTraceSnapshot().getEvents().get(0), 1, START, 1L, 1L, 2, 2);
    }

    @Test
    void oldAdvanceResultListMutabilityIsPreservedAndCannotMutateTheTrace() {
        TransferContentionEngine engine = new TransferContentionEngine(16);
        add(engine, 1, 1, 1, 0);
        TransferContentionEngine.AdvanceResult result = engine.advance(1);
        TransferTraceSnapshot snapshot = engine.getTraceSnapshot();
        result.getCompletedTransferIds().clear();
        result.getCompletedTransferIds().add(999L);
        assertEquals(Collections.singletonList(999L), result.getCompletedTransferIds());
        assertCompletion(snapshot.getEvents().get(2), 1, 1, 1, 1, 0);
        assertCompletion(engine.getTraceSnapshot().getEvents().get(2), 1, 1, 1, 1, 0);
    }

    @Test
    void pastSnapshotsAndTheirResourceListsStayDeeplyImmutable() {
        TransferListInput input = new TransferListInput("A", "B", "A");
        TransferContentionEngine engine = new TransferContentionEngine(4);
        engine.addTransfer(1, 1.0, input, 1, 0);
        TransferTraceSnapshot past = engine.getTraceSnapshot();
        TransferTraceEvent start = past.getEvents().get(0);
        List<String> resources = start.getStart().getOccupiedResources();
        assertThrows(UnsupportedOperationException.class, () -> past.getEvents().add(start));
        assertThrows(UnsupportedOperationException.class, () -> past.getEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> past.getEvents().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> resources.add("C"));
        assertThrows(UnsupportedOperationException.class, () -> resources.clear());
        assertThrows(UnsupportedOperationException.class, () -> resources.set(0, "C"));
        input.values.clear();
        engine.advance(0.5);
        engine.advance(1);
        add(engine, 2, 1, 1, 2); // The next event exceeds the four-event prefix.
        assertSnapshot(engine.getTraceSnapshot(), TRUNCATED, 4, 1, 2);
        assertSnapshot(past, CAPTURE_COMPLETE, 1, 0, 0);
        assertEquals(Arrays.asList("A", "B", "A"), resources);
        assertStart(start, 1, 1, 1, 1, 1, Arrays.asList("A", "B", "A"));
    }

    @Test
    void newPublicDtosAndEveryTypedPayloadHaveOnlyPrivateFinalInstanceFields() {
        for (Class<?> type : Arrays.<Class<?>>asList(TransferTraceSnapshot.class, TransferTraceEvent.class,
                TransferTraceEvent.Capacity.class, TransferTraceEvent.Start.class,
                TransferTraceEvent.RateChange.class, TransferTraceEvent.ServiceSegment.class,
                TransferTraceEvent.Complete.class)) {
            assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) { continue; }
                assertTrue(Modifier.isPrivate(field.getModifiers()), field.toString());
                assertTrue(Modifier.isFinal(field.getModifiers()), field.toString());
                assertFalse(Map.class.isAssignableFrom(field.getType()), field.toString());
                assertFalse(field.getType().isArray(), field.toString());
            }
        }
    }

    @Test
    void separateEnginesDoNotShareSequencesAdmissionOrdinalsOrBudgets() {
        TransferContentionEngine first = new TransferContentionEngine(3);
        TransferContentionEngine second = new TransferContentionEngine(16);
        add(first, 9, 1, 1, 0);
        first.advance(1);
        TransferTraceSnapshot completedFirst = first.getTraceSnapshot();
        add(second, 9, 2, 1, 0);
        assertCommon(second.getTraceSnapshot().getEvents().get(0), 1, START, 9L, 1L, 0, 0);
        add(first, 9, 1, 1, 2);
        assertSnapshot(first.getTraceSnapshot(), TRUNCATED, 3, 1, 2);
        assertSnapshot(second.getTraceSnapshot(), CAPTURE_COMPLETE, 1, 0, 0);
        assertSnapshot(completedFirst, CAPTURE_COMPLETE, 3, 0, 1);
        assertBits(2, second.advance(0).getNextCompletionTime());
        assertBits(3, first.advance(2).getNextCompletionTime());
    }

    @Test
    void exactlyFullBudgetIsCompleteUntilTheFirstTrueDroppedEvent() {
        TransferContentionEngine engine = new TransferContentionEngine(1);
        add(engine, 1, 1, 1, 0);
        TransferTraceSnapshot exactlyFull = engine.getTraceSnapshot();
        assertSnapshot(exactlyFull, CAPTURE_COMPLETE, 1, 0, 0);
        engine.advance(0);
        engine.currentRateBytesPerSecond(1);
        assertThrows(IllegalArgumentException.class, () -> add(engine, 1, 1, 1, 2));
        assertSnapshot(engine.getTraceSnapshot(), CAPTURE_COMPLETE, 1, 0, 0);
        engine.advance(0.25);
        assertSnapshot(engine.getTraceSnapshot(), TRUNCATED, 1, 1, 0.25);
        engine.advance(0.5);
        assertSnapshot(engine.getTraceSnapshot(), TRUNCATED, 1, 2, 0.5);
        engine.advance(1);
        assertSnapshot(engine.getTraceSnapshot(), TRUNCATED, 1, 4, 1);
        engine.advance(2);
        assertSnapshot(engine.getTraceSnapshot(), TRUNCATED, 1, 4, 2);
        assertSnapshot(exactlyFull, CAPTURE_COMPLETE, 1, 0, 0);
    }

    @Test
    void zeroAndDefaultBudgetsStayDisabledAcrossAWholeHistory() {
        for (TransferContentionEngine engine : Arrays.asList(new TransferContentionEngine(),
                new TransferContentionEngine(0))) {
            runBudgetScenario(engine);
            assertSnapshot(engine.getTraceSnapshot(), DISABLED, 0, 0, 24);
            assertThrows(UnsupportedOperationException.class,
                    () -> engine.getTraceSnapshot().getEvents().add(null));
        }
    }

    @Test
    void everyBoundedBudgetKeepsTheExactPrefixAndCountsOnlyTrueEvents() {
        TransferContentionEngine large = new TransferContentionEngine(128);
        runBudgetScenario(large);
        TransferTraceSnapshot full = large.getTraceSnapshot();
        assertSnapshot(full, CAPTURE_COMPLETE, 16, 0, 24);
        for (int budget = 0; budget <= 18; budget++) {
            TransferContentionEngine bounded = new TransferContentionEngine(budget);
            runBudgetScenario(bounded);
            TransferTraceSnapshot actual = bounded.getTraceSnapshot();
            if (budget == 0) {
                assertSnapshot(actual, DISABLED, 0, 0, 24);
            } else {
                int retained = Math.min(budget, 16);
                assertSnapshot(actual, budget < 16 ? TRUNCATED : CAPTURE_COMPLETE, retained, 16 - retained, 24);
                for (int i = 0; i < retained; i++) {
                    assertSameEvent(full.getEvents().get(i), actual.getEvents().get(i));
                }
            }
            assertEquals(0, bounded.activeTransferCount());
            assertTrue(bounded.advance(24).getCompletedTransferIds().isEmpty());
            assertNull(bounded.advance(24).getNextCompletionTime());
            assertEquals(actual.getDroppedCount(), bounded.getTraceSnapshot().getDroppedCount());
        }
    }

    @Test
    void offEnginesHaveNoTraceStateEvenAfterOperationsAndSnapshotRequests() throws Exception {
        Field state = TransferContentionEngine.class.getDeclaredField("traceState");
        state.setAccessible(true);
        for (TransferContentionEngine engine : Arrays.asList(new TransferContentionEngine(),
                new TransferContentionEngine(0))) {
            assertNull(state.get(engine));
            runBudgetScenario(engine);
            engine.getTraceSnapshot();
            assertNull(state.get(engine));
        }
        assertNotNull(state.get(new TransferContentionEngine(1)));
    }

    @Test
    void enabledAndFullCaptureDoNotAddReadsOrCopiesOfTheCallerResourceList() {
        for (int budget : new int[] {0, 1, 32}) {
            TransferContentionEngine engine = new TransferContentionEngine(budget);
            engine.setEndpointCapacity("A", 100); // The one-event budget is already full.
            TransferListInput resources = new TransferListInput("A", "UNREGISTERED", "A");
            engine.addTransfer(1, 1000.0, resources, 10, 0);
            // Existing behavior validates each key and makes one ArrayList input copy.
            assertEquals(3, resources.getCalls);
            assertEquals(1, resources.toArrayCalls);
            resources.values.clear();
            add(engine, 2, 1000, 100, 0, "A");
            assertBits(10, engine.currentRateBytesPerSecond(1));
            assertBits(80, engine.currentRateBytesPerSecond(2));
            engine.advance(1);
            assertEquals(3, resources.getCalls);
            assertEquals(1, resources.toArrayCalls);
        }
    }

    @Test
    void everyOperationHasRawBitParityForDefaultZeroEnabledAndTruncatedModes() {
        Parity parity = new Parity();
        parity.step(0, e -> { e.setEndpointCapacity("A", 100); return null; });
        parity.step(0, e -> { e.setEndpointCapacity("B", 40); return null; });
        parity.step(0, e -> { e.setEndpointCapacity("A", 100); return null; });
        parity.step(0, e -> add(e, 9, 1000, 100, 0, "A"));
        parity.step(0, e -> add(e, 3, 1000, 100, 0, "A", "B"));
        parity.step(0, e -> add(e, 2, 1000, 10, 0, "B"));
        parity.step(0, e -> add(e, 9, 1, 1, 100, "A"));
        parity.step(0, e -> { e.setEndpointCapacity("C", 100); return null; });
        parity.step(0, e -> { e.setEndpointCapacity(null, 100); return null; });
        parity.step(0, e -> { e.setEndpointCapacity("", 100); return null; });
        for (double bad : new double[] {0, -1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            parity.step(0, e -> { e.setEndpointCapacity("A", bad); return null; });
            parity.step(0, e -> add(e, 4, bad, 1, 100));
            parity.step(0, e -> add(e, 4, 1, bad, 100));
        }
        parity.step(0, e -> add(e, 4, Double.MIN_VALUE, 1_000_000, 100));
        parity.step(0, e -> add(e, 4, Double.MAX_VALUE, Double.MIN_VALUE, 100));
        parity.step(0, e -> e.addTransfer(4, 1.0, (List<String>) null, 1, 100));
        parity.step(0, e -> add(e, 4, 1, 1, 100, "A", null));
        parity.step(0, e -> e.addTransfer(4, 1.0, null, "A", 1, 100));
        parity.step(0, e -> e.addTransfer(4, 1L, "A", null, 1, 100));
        for (double time : new double[] {-1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            parity.step(0, e -> e.advance(time));
            parity.step(0, e -> add(e, 4, 1, 1, time));
        }
        parity.step(0.25, e -> e.advance(0.25));
        parity.step(0.25, e -> add(e, -5, 0.5, 1, 0.25));
        parity.step(0.75, e -> e.advance(0.75));
        parity.step(100, e -> e.advance(100));
        parity.step(100, e -> e.addTransfer(9, 1L, "A", "B", 10, 100));
        parity.step(100, e -> e.addTransfer(9, 1L, "A", "B", 10, 1000));
        parity.step(100.1, e -> e.advance(100.1));
        parity.step(100.1, e -> e.addTransfer(3, 0.5, "SOURCE", "OTHER", 1_000_000, 100.1));
        parity.step(101, e -> e.advance(101));
        parity.step(101, e -> e.addTransfer(2, 1L, Collections.<String>emptyList(), 10, 101));
        parity.step(102, e -> e.advance(102));
        parity.step(102, e -> add(e, 2, 1.0e-12, 1.0e-9, 102));
        parity.step(102, e -> add(e, -5, 5.0e-10, 1.0e-9, 102));
        parity.step(102.001, e -> e.advance(102.001));
        parity.step(103, e -> e.advance(103));
        double large = 0x1.0p54;
        parity.step(large, e -> e.advance(large));
        parity.step(large, e -> add(e, 4, 1, 49, large));
        parity.step(Math.nextUp(large), e -> e.advance(Math.nextUp(large)));
        parity.step(Math.nextUp(large), e -> { e.setEndpointCapacity("B", 200); return null; });
        assertEquals(DISABLED, parity.engines[0].getTraceSnapshot().getStatus());
        assertEquals(DISABLED, parity.engines[1].getTraceSnapshot().getStatus());
        assertEquals(CAPTURE_COMPLETE, parity.engines[2].getTraceSnapshot().getStatus());
        assertEquals(TRUNCATED, parity.engines[3].getTraceSnapshot().getStatus());
    }

    private static TransferContentionEngine.AdvanceResult add(TransferContentionEngine engine,
            long id, double bytes, double nominal, double now, String... resources) {
        return engine.addTransfer(id, bytes, Arrays.asList(resources), nominal, now);
    }

    private static void assertBits(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual),
                "raw double bits for expected " + expected + " and actual " + actual);
    }

    private static void assertSnapshot(TransferTraceSnapshot snapshot, TransferTraceSnapshot.Status status,
            int count, long dropped, double engineTime) {
        assertEquals(status, snapshot.getStatus());
        assertEquals(count, snapshot.getEvents().size());
        assertEquals(dropped, snapshot.getDroppedCount());
        assertBits(engineTime, snapshot.getEngineTime());
        assertSequences(snapshot.getEvents());
    }

    private static void assertSequences(List<TransferTraceEvent> events) {
        for (int i = 0; i < events.size(); i++) { assertEquals(i + 1L, events.get(i).getSequence()); }
    }

    private static void assertTypes(List<TransferTraceEvent> events, TransferTraceEvent.Type... types) {
        assertSequences(events);
        assertTypesWithoutSequence(events, types);
    }

    private static void assertTypesWithoutSequence(List<TransferTraceEvent> events, TransferTraceEvent.Type... types) {
        assertEquals(types.length, events.size());
        for (int i = 0; i < types.length; i++) {
            TransferTraceEvent event = events.get(i);
            assertEquals(types[i], event.getType(), "event " + i);
            assertEquals(types[i] == CAPACITY, event.getCapacity() != null);
            assertEquals(types[i] == START, event.getStart() != null);
            assertEquals(types[i] == RATE_CHANGE, event.getRateChange() != null);
            assertEquals(types[i] == SERVICE_SEGMENT, event.getServiceSegment() != null);
            assertEquals(types[i] == COMPLETE, event.getComplete() != null);
        }
    }

    private static void assertCommon(TransferTraceEvent event, long sequence, TransferTraceEvent.Type type,
            Long id, Long ordinal, double effective, double observed) {
        assertEquals(sequence, event.getSequence());
        assertEquals(type, event.getType());
        assertEquals(id, event.getTransferId());
        assertEquals(ordinal, event.getAdmissionOrdinal());
        assertBits(effective, event.getEffectiveTime());
        assertBits(observed, event.getObservedTime());
    }

    private static void assertFlow(TransferTraceEvent event, TransferTraceEvent.Type type, long id, long ordinal) {
        assertEquals(type, event.getType());
        assertEquals(Long.valueOf(id), event.getTransferId());
        assertEquals(Long.valueOf(ordinal), event.getAdmissionOrdinal());
    }

    private static void assertStart(TransferTraceEvent event, long id, long ordinal, double bytes,
            double nominal, double initial, List<String> resources) {
        assertFlow(event, START, id, ordinal);
        assertBits(bytes, event.getStart().getBytes());
        assertBits(nominal, event.getStart().getNominalRateBytesPerSecond());
        assertBits(initial, event.getStart().getInitialRateBytesPerSecond());
        assertEquals(resources, event.getStart().getOccupiedResources());
    }

    private static void assertRateChange(TransferTraceEvent event, long id, long ordinal,
            double previous, double rate, double effective, double observed) {
        assertFlow(event, RATE_CHANGE, id, ordinal);
        assertBits(previous, event.getRateChange().getPreviousRateBytesPerSecond());
        assertBits(rate, event.getRateChange().getRateBytesPerSecond());
        assertBits(effective, event.getEffectiveTime());
        assertBits(observed, event.getObservedTime());
    }

    private static void assertService(TransferTraceEvent event, long id, long ordinal,
            double start, double end, double elapsed, double rate, double before, double after, double observed) {
        assertFlow(event, SERVICE_SEGMENT, id, ordinal);
        TransferTraceEvent.ServiceSegment service = event.getServiceSegment();
        assertBits(start, service.getIntervalStart());
        assertBits(end, service.getIntervalEnd());
        assertBits(elapsed, service.getElapsed());
        assertBits(rate, service.getRateBytesPerSecond());
        assertBits(before, service.getRemainingBefore());
        assertBits(after, service.getRemainingAfter());
        assertBits(end, event.getEffectiveTime());
        assertBits(observed, event.getObservedTime());
    }

    private static void assertCompletion(TransferTraceEvent event, long id, long ordinal,
            double effective, double observed, double residual) {
        assertFlow(event, COMPLETE, id, ordinal);
        assertBits(effective, event.getEffectiveTime());
        assertBits(observed, event.getObservedTime());
        assertBits(residual, event.getComplete().getRemainingAfterService());
    }

    private static double serviceArea(List<TransferTraceEvent> events, long id, long ordinal) {
        double area = 0;
        for (TransferTraceEvent event : events) {
            if (event.getType() == SERVICE_SEGMENT && event.getTransferId().longValue() == id
                    && event.getAdmissionOrdinal().longValue() == ordinal) {
                area += event.getServiceSegment().getRateBytesPerSecond() * event.getServiceSegment().getElapsed();
            }
        }
        return area;
    }

    private static List<TransferTraceEvent> eventsOfType(TransferTraceSnapshot snapshot, TransferTraceEvent.Type type) {
        List<TransferTraceEvent> selected = new ArrayList<TransferTraceEvent>();
        for (TransferTraceEvent event : snapshot.getEvents()) {
            if (event.getType() == type) { selected.add(event); }
        }
        return selected;
    }

    private static void assertSameEvent(TransferTraceEvent expected, TransferTraceEvent actual) {
        assertCommon(actual, expected.getSequence(), expected.getType(), expected.getTransferId(),
                expected.getAdmissionOrdinal(), expected.getEffectiveTime(), expected.getObservedTime());
        switch (expected.getType()) {
            case CAPACITY:
                assertEquals(expected.getCapacity().getResourceKey(), actual.getCapacity().getResourceKey());
                assertBits(expected.getCapacity().getCapacityBytesPerSecond(), actual.getCapacity().getCapacityBytesPerSecond());
                break;
            case START:
                TransferTraceEvent.Start start = expected.getStart();
                assertStart(actual, expected.getTransferId(), expected.getAdmissionOrdinal(), start.getBytes(),
                        start.getNominalRateBytesPerSecond(), start.getInitialRateBytesPerSecond(), start.getOccupiedResources());
                break;
            case RATE_CHANGE:
                assertRateChange(actual, expected.getTransferId(), expected.getAdmissionOrdinal(),
                        expected.getRateChange().getPreviousRateBytesPerSecond(), expected.getRateChange().getRateBytesPerSecond(),
                        expected.getEffectiveTime(), expected.getObservedTime());
                break;
            case SERVICE_SEGMENT:
                TransferTraceEvent.ServiceSegment segment = expected.getServiceSegment();
                assertService(actual, expected.getTransferId(), expected.getAdmissionOrdinal(),
                        segment.getIntervalStart(), segment.getIntervalEnd(), segment.getElapsed(), segment.getRateBytesPerSecond(),
                        segment.getRemainingBefore(), segment.getRemainingAfter(), expected.getObservedTime());
                break;
            case COMPLETE:
                assertCompletion(actual, expected.getTransferId(), expected.getAdmissionOrdinal(),
                        expected.getEffectiveTime(), expected.getObservedTime(), expected.getComplete().getRemainingAfterService());
                break;
            default:
                fail("Unhandled event type " + expected.getType());
        }
    }

    private static void runBudgetScenario(TransferContentionEngine engine) {
        engine.advance(2);
        engine.setEndpointCapacity("A", 90);
        engine.setEndpointCapacity("A", 100);
        add(engine, 9, 500, 100, 2, "A");
        add(engine, 3, 1000, 100, 2, "A");
        assertThrows(IllegalArgumentException.class, () -> add(engine, 9, 1, 1, 22));
        engine.advance(2);
        assertEquals(Arrays.asList(9L, 3L), engine.advance(22).getCompletedTransferIds());
        assertThrows(IllegalArgumentException.class, () -> add(engine, 4, 0, 1, 23));
        engine.setEndpointCapacity("A", 100);
        add(engine, 9, 0.5, 1, 22);
        engine.advance(22.25);
        assertEquals(Collections.singletonList(9L), engine.advance(23).getCompletedTransferIds());
        engine.advance(24);
    }

    /** Detect extra access to the caller's list, not engine-owned defensive copies. */
    private static final class TransferListInput extends AbstractList<String> {
        private final List<String> values;
        private int getCalls;
        private int toArrayCalls;

        private TransferListInput(String... values) {
            this.values = new ArrayList<String>(Arrays.asList(values));
        }

        @Override public String get(int index) { getCalls++; return values.get(index); }
        @Override public int size() { return values.size(); }
        @Override public Object[] toArray() { toArrayCalls++; return values.toArray(); }
    }

    private interface Operation {
        TransferContentionEngine.AdvanceResult apply(TransferContentionEngine engine);
    }

    private static final class Outcome {
        private final TransferContentionEngine.AdvanceResult result;
        private final RuntimeException failure;

        private Outcome(TransferContentionEngine.AdvanceResult result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        private static Outcome capture(TransferContentionEngine engine, Operation operation) {
            try { return new Outcome(operation.apply(engine), null); }
            catch (RuntimeException failure) { return new Outcome(null, failure); }
        }
    }

    private static void assertSameResult(TransferContentionEngine.AdvanceResult expected,
            TransferContentionEngine.AdvanceResult actual) {
        if (expected == null) { assertNull(actual); return; }
        assertNotNull(actual);
        assertEquals(expected.getCompletedTransferIds(), actual.getCompletedTransferIds());
        if (expected.getNextCompletionTime() == null) { assertNull(actual.getNextCompletionTime()); }
        else {
            assertNotNull(actual.getNextCompletionTime());
            assertBits(expected.getNextCompletionTime(), actual.getNextCompletionTime());
        }
    }

    /** Parity supplements the independent, bounded physical expectations above. */
    private static final class Parity {
        private final TransferContentionEngine[] engines = {new TransferContentionEngine(),
                new TransferContentionEngine(0), new TransferContentionEngine(1024), new TransferContentionEngine(1)};
        private final long[] ids = {9, 3, 2, -5, 4, 999};

        private void step(double expectedClock, Operation operation) {
            Outcome baseline = Outcome.capture(engines[0], operation);
            for (int mode = 1; mode < engines.length; mode++) {
                Outcome actual = Outcome.capture(engines[mode], operation);
                if (baseline.failure == null) {
                    assertNull(actual.failure);
                    assertSameResult(baseline.result, actual.result);
                } else {
                    assertNotNull(actual.failure);
                    assertEquals(baseline.failure.getClass(), actual.failure.getClass());
                    assertEquals(baseline.failure.getMessage(), actual.failure.getMessage());
                }
                assertEquals(engines[0].activeTransferCount(), engines[mode].activeTransferCount());
            }
            for (long id : ids) {
                Double baselineRate;
                try { baselineRate = engines[0].currentRateBytesPerSecond(id); }
                catch (IllegalArgumentException unknown) { baselineRate = null; }
                for (int mode = 1; mode < engines.length; mode++) {
                    final TransferContentionEngine engine = engines[mode];
                    if (baselineRate == null) {
                        assertThrows(IllegalArgumentException.class, () -> engine.currentRateBytesPerSecond(id));
                    } else {
                        assertBits(baselineRate, engine.currentRateBytesPerSecond(id));
                    }
                }
            }
            TransferContentionEngine.AdvanceResult prediction = engines[0].advance(expectedClock);
            assertTrue(prediction.getCompletedTransferIds().isEmpty());
            for (TransferContentionEngine engine : engines) {
                TransferTraceSnapshot before = engine.getTraceSnapshot();
                assertBits(expectedClock, before.getEngineTime());
                assertSameResult(prediction, engine.advance(expectedClock));
                TransferTraceSnapshot after = engine.getTraceSnapshot();
                assertEquals(before.getEvents().size(), after.getEvents().size());
                assertEquals(before.getDroppedCount(), after.getDroppedCount());
                assertEquals(before.getStatus(), after.getStatus());
            }
        }
    }
}
