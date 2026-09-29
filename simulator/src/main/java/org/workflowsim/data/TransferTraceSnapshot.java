package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deeply immutable view of one engine's optional fluid-transfer trace.
 *
 * <p>The budget bounds the number of retained event records, not bytes of memory:
 * a START record can contain an arbitrarily long resource list. Snapshots copy
 * the retained list and can outlive the engine; retaining many snapshots can
 * therefore use additional memory. Event objects and their typed payloads are
 * immutable and can safely be shared between snapshots.</p>
 */
public final class TransferTraceSnapshot {
    /** Capture completeness, independent of whether the engine still has active transfers. */
    public enum Status {
        /** Capture was not enabled; there is no event history or dropped-event count. */
        DISABLED,
        /** No true event has been dropped, even if the record budget is exactly full. */
        COMPLETE,
        /** At least one true event exceeded the budget; only a deterministic prefix remains. */
        TRUNCATED
    }

    private final Status status;
    private final List<TransferTraceEvent> events;
    private final long droppedCount;
    private final double engineTime;

    TransferTraceSnapshot(Status status, List<TransferTraceEvent> events, long droppedCount, double engineTime) {
        this.status = status;
        this.events = events.isEmpty() ? Collections.<TransferTraceEvent>emptyList()
                : Collections.unmodifiableList(new ArrayList<TransferTraceEvent>(events));
        this.droppedCount = droppedCount;
        this.engineTime = engineTime;
    }

    /** @return capture status; COMPLETE does not mean that every transfer has finished */
    public Status getStatus() { return status; }

    /** @return immutable event prefix in sequence order, with deeply immutable elements */
    public List<TransferTraceEvent> getEvents() { return events; }

    /**
     * @return number of true events not retained after the budget filled; zero for
     *         disabled capture. Unchanged-rate calculations and same-time advances
     *         do not increment this count.
     */
    public long getDroppedCount() { return droppedCount; }

    /**
     * The engine clock at snapshot creation, not an external or wall-clock time.
     * It can be later than the final event when an advance includes idle time.
     *
     * @return the engine's last advance time, also available when capture is disabled
     */
    public double getEngineTime() { return engineTime; }
}
