package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A deeply immutable observation of the existing fluid integrator, not packet traffic.
 *
 * <p>Exactly one typed payload is present, as selected by {@link #getType()}.
 * {@code observedTime} is the outer advance/addTransfer caller's time;
 * {@code effectiveTime} is the actual internal boundary represented by the
 * engine's double clock. Capacity setters have no time argument, so both times
 * are the engine's last known advance time. No external clock is consulted.</p>
 *
 * <p>For a SERVICE_SEGMENT the effective time is its interval end. Preserve its
 * original elapsed value for rate-area accounting: rounded clock subtraction
 * need not recover it, and a positive elapsed can have identical start/end
 * clocks. Completion residuals are numerical settlement, not extra service.</p>
 */
public final class TransferTraceEvent {
    /** The single payload carried by an event. */
    public enum Type {
        /** A successful capacity registration or overwrite. */
        CAPACITY,
        /** An admitted flow and its initial validated final allocation. */
        START,
        /** A previously active flow's validated final rate changed. */
        RATE_CHANGE,
        /** One positive-elapsed integration segment for a flow. */
        SERVICE_SEGMENT,
        /** A flow was removed by the existing completion rules. */
        COMPLETE
    }

    /** Immutable resource-capacity registration. */
    public static final class Capacity {
        private final String resourceKey;
        private final double capacityBytesPerSecond;

        private Capacity(String resourceKey, double capacityBytesPerSecond) {
            this.resourceKey = resourceKey;
            this.capacityBytesPerSecond = capacityBytesPerSecond;
        }

        /** @return registered resource key */
        public String getResourceKey() { return resourceKey; }
        /** @return capacity installed by this successful put, in bytes/second */
        public double getCapacityBytesPerSecond() { return capacityBytesPerSecond; }
    }

    /** Immutable admission description; resource order and duplicate weights are preserved. */
    public static final class Start {
        private final double bytes;
        private final List<String> occupiedResources;
        private final double nominalRateBytesPerSecond;
        private final double initialRateBytesPerSecond;

        private Start(double bytes, List<String> occupiedResources, double nominalRateBytesPerSecond,
                double initialRateBytesPerSecond) {
            this.bytes = bytes;
            this.occupiedResources = Collections.unmodifiableList(new ArrayList<String>(occupiedResources));
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.initialRateBytesPerSecond = initialRateBytesPerSecond;
        }

        /** @return original modeled byte demand, including any fractional bytes */
        public double getBytes() { return bytes; }
        /** @return immutable defensive copy in original order, including duplicate keys */
        public List<String> getOccupiedResources() { return occupiedResources; }
        /** @return the admitted flow's nominal rate cap in bytes/second */
        public double getNominalRateBytesPerSecond() { return nominalRateBytesPerSecond; }
        /** @return validated final allocation after admission, not a solver intermediate */
        public double getInitialRateBytesPerSecond() { return initialRateBytesPerSecond; }
    }

    /** Immutable change between two validated final allocations. */
    public static final class RateChange {
        private final double previousRateBytesPerSecond;
        private final double rateBytesPerSecond;

        private RateChange(double previousRateBytesPerSecond, double rateBytesPerSecond) {
            this.previousRateBytesPerSecond = previousRateBytesPerSecond;
            this.rateBytesPerSecond = rateBytesPerSecond;
        }

        /** @return previous effective rate in bytes/second */
        public double getPreviousRateBytesPerSecond() { return previousRateBytesPerSecond; }
        /** @return new validated final effective rate in bytes/second */
        public double getRateBytesPerSecond() { return rateBytesPerSecond; }
    }

    /**
     * Immutable values around the original subtract/clamp operation.
     * The recorded remaining-after value precedes forced earliest-flow zeroing
     * and tolerance-based removal. Rate times elapsed is modeled service;
     * remaining-before minus remaining-after may differ by floating-point rounding.
     */
    public static final class ServiceSegment {
        private final double intervalStart;
        private final double intervalEnd;
        private final double elapsed;
        private final double rateBytesPerSecond;
        private final double remainingBefore;
        private final double remainingAfter;

        private ServiceSegment(double intervalStart, double intervalEnd, double elapsed,
                double rateBytesPerSecond, double remainingBefore, double remainingAfter) {
            this.intervalStart = intervalStart;
            this.intervalEnd = intervalEnd;
            this.elapsed = elapsed;
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.remainingBefore = remainingBefore;
            this.remainingAfter = remainingAfter;
        }

        /** @return engine clock at the start of this integration step */
        public double getIntervalStart() { return intervalStart; }
        /** @return internal boundary clock, possibly equal to start despite positive elapsed */
        public double getIntervalEnd() { return intervalEnd; }
        /** @return original integration elapsed, never recomputed from the two clocks */
        public double getElapsed() { return elapsed; }
        /** @return effective rate used by this step, in bytes/second */
        public double getRateBytesPerSecond() { return rateBytesPerSecond; }
        /** @return remaining modeled bytes before the original subtract/clamp */
        public double getRemainingBefore() { return remainingBefore; }
        /** @return remaining modeled bytes after subtract/clamp but before numerical settlement */
        public double getRemainingAfter() { return remainingAfter; }
    }

    /** Numerical removal of a flow, separate from any recorded fluid service. */
    public static final class Complete {
        private final double remainingAfterService;

        private Complete(double remainingAfterService) {
            this.remainingAfterService = remainingAfterService;
        }

        /**
         * Remaining bytes after the last original subtract/clamp, before forced
         * earliest-flow zeroing or tolerance removal. These bytes were discarded
         * numerically, not served as traffic; completion is not retimed to an
         * ideal bytes/rate finish.
         *
         * @return nonnegative numerical settlement residual in modeled bytes
         */
        public double getRemainingAfterService() { return remainingAfterService; }
    }

    private final long sequence;
    private final Type type;
    private final double effectiveTime;
    private final double observedTime;
    private final Long transferId;
    private final Long admissionOrdinal;
    private final Capacity capacity;
    private final Start start;
    private final RateChange rateChange;
    private final ServiceSegment serviceSegment;
    private final Complete complete;

    private TransferTraceEvent(long sequence, Type type, double effectiveTime, double observedTime,
            Long transferId, Long admissionOrdinal, Capacity capacity, Start start, RateChange rateChange,
            ServiceSegment serviceSegment, Complete complete) {
        this.sequence = sequence;
        this.type = type;
        this.effectiveTime = effectiveTime;
        this.observedTime = observedTime;
        this.transferId = transferId;
        this.admissionOrdinal = admissionOrdinal;
        this.capacity = capacity;
        this.start = start;
        this.rateChange = rateChange;
        this.serviceSegment = serviceSegment;
        this.complete = complete;
    }

    static TransferTraceEvent capacity(long sequence, double engineTime, String resourceKey, double capacity) {
        return new TransferTraceEvent(sequence, Type.CAPACITY, engineTime, engineTime, null, null,
                new Capacity(resourceKey, capacity), null, null, null, null);
    }

    static TransferTraceEvent start(long sequence, double now, long transferId, long admissionOrdinal,
            double bytes, List<String> resources, double nominalRate, double initialRate) {
        return new TransferTraceEvent(sequence, Type.START, now, now, transferId, admissionOrdinal,
                null, new Start(bytes, resources, nominalRate, initialRate), null, null, null);
    }

    static TransferTraceEvent rateChange(long sequence, double effectiveTime, double observedTime,
            long transferId, long admissionOrdinal, double previousRate, double rate) {
        return new TransferTraceEvent(sequence, Type.RATE_CHANGE, effectiveTime, observedTime,
                transferId, admissionOrdinal, null, null, new RateChange(previousRate, rate), null, null);
    }

    static TransferTraceEvent serviceSegment(long sequence, double intervalStart, double intervalEnd,
            double observedTime, long transferId, long admissionOrdinal, double elapsed, double rate,
            double remainingBefore, double remainingAfter) {
        return new TransferTraceEvent(sequence, Type.SERVICE_SEGMENT, intervalEnd, observedTime,
                transferId, admissionOrdinal, null, null, null,
                new ServiceSegment(intervalStart, intervalEnd, elapsed, rate, remainingBefore, remainingAfter), null);
    }

    static TransferTraceEvent complete(long sequence, double effectiveTime, double observedTime,
            long transferId, long admissionOrdinal, double remainingAfterService) {
        return new TransferTraceEvent(sequence, Type.COMPLETE, effectiveTime, observedTime,
                transferId, admissionOrdinal, null, null, null, null, new Complete(remainingAfterService));
    }

    /** @return one-based sequence within this engine's retained deterministic prefix */
    public long getSequence() { return sequence; }
    /** @return kind identifying the single non-null typed payload */
    public Type getType() { return type; }
    /** @return actual internal double-clock boundary, not an idealized completion time */
    public double getEffectiveTime() { return effectiveTime; }
    /** @return outer caller time, or the known engine clock for a capacity setter */
    public double getObservedTime() { return observedTime; }
    /** @return caller's flow ID, or null for CAPACITY; completed IDs may be reused */
    public Long getTransferId() { return transferId; }
    /**
     * @return one-based successful-insertion ordinal distinguishing reused flow IDs
     *         within this engine, or null for CAPACITY
     */
    public Long getAdmissionOrdinal() { return admissionOrdinal; }
    /** @return capacity payload only for CAPACITY, otherwise null */
    public Capacity getCapacity() { return capacity; }
    /** @return admission payload only for START, otherwise null */
    public Start getStart() { return start; }
    /** @return final-allocation change only for RATE_CHANGE, otherwise null */
    public RateChange getRateChange() { return rateChange; }
    /** @return original integration values only for SERVICE_SEGMENT, otherwise null */
    public ServiceSegment getServiceSegment() { return serviceSegment; }
    /** @return numerical settlement only for COMPLETE, otherwise null */
    public Complete getComplete() { return complete; }
}
