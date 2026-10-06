package org.workflowsim.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deeply immutable current-state observation of checked coherent V2 transfer service.
 *
 * <p>This contains committed active-flow balances and allocations, not a trace,
 * completion record, or prediction. Capture is not required. The engine retains
 * no snapshot history, and a caller-retained snapshot is unaffected by later
 * service, admissions, or capacity changes.</p>
 */
public final class TransferServiceSnapshot {
    /** One declared physical resource, including resources with no active users. */
    public static final class Resource {
        private final String key;
        private final double capacityBytesPerSecond;
        private final BigDecimal assignedRateBytesPerSecond;
        private final int activeFlowCount;
        private final long pathMultiplicityCount;

        private Resource(String key, double capacityBytesPerSecond,
                BigDecimal assignedRateBytesPerSecond, int activeFlowCount,
                long pathMultiplicityCount) {
            this.key = key;
            this.capacityBytesPerSecond = capacityBytesPerSecond;
            this.assignedRateBytesPerSecond = assignedRateBytesPerSecond;
            this.activeFlowCount = activeFlowCount;
            this.pathMultiplicityCount = pathMultiplicityCount;
        }

        /** @return declared physical resource key */
        public String getKey() { return key; }

        /** @return declared physical capacity in bytes per second */
        public double getCapacityBytesPerSecond() { return capacityBytesPerSecond; }

        /**
         * Exact sum of each assigned binary64 rate multiplied by its path multiplicity.
         * No decimal rounding, double summation, or capacity clamp is applied. In
         * isolated mode this can exceed both physical capacity and Double.MAX_VALUE.
         *
         * @return exact assigned resource load, not completed traffic or utilization
         */
        public BigDecimal getAssignedRateBytesPerSecond() { return assignedRateBytesPerSecond; }

        /** @return number of distinct active flows using this resource */
        public int getActiveFlowCount() { return activeFlowCount; }

        /** @return sum of this resource's occurrence counts across all active paths */
        public long getPathMultiplicityCount() { return pathMultiplicityCount; }
    }

    /** Immutable copied balance and current allocation of one active admission. */
    public static final class Flow {
        private final long transferId;
        private final double bytes;
        private final double remainingBytes;
        private final double rateBytesPerSecond;
        private final double nominalRateBytesPerSecond;
        private final double admittedAt;
        private final List<String> resources;

        // Trusted package construction from the checked engine's committed active state.
        Flow(long transferId, double bytes, double remainingBytes, double rateBytesPerSecond,
                double nominalRateBytesPerSecond, double admittedAt, List<String> resources) {
            this.transferId = transferId;
            this.bytes = bytes;
            this.remainingBytes = remainingBytes;
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.admittedAt = admittedAt;
            this.resources = Collections.unmodifiableList(new ArrayList<String>(resources));
        }

        /** @return caller identity, without conversion to a trace ordinal or floating point */
        public long getTransferId() { return transferId; }

        /** @return original modeled byte demand */
        public double getBytes() { return bytes; }

        /** @return committed remaining modeled bytes at the snapshot's observed-through time */
        public double getRemainingBytes() { return remainingBytes; }

        /** @return current committed service allocation in bytes per second */
        public double getRateBytesPerSecond() { return rateBytesPerSecond; }

        /** @return admission's nominal rate limit in bytes per second */
        public double getNominalRateBytesPerSecond() { return nominalRateBytesPerSecond; }

        /** @return actual coherent-service admission time, independent of trace capture */
        public double getAdmittedAt() { return admittedAt; }

        /** @return immutable ordered path, preserving every repeated resource key */
        public List<String> getResources() { return resources; }
    }

    /** Construction-only accumulation; neither the snapshot nor the engine retains it. */
    private static final class ResourceTotals {
        private final double capacity;
        private BigDecimal assignedRate = BigDecimal.ZERO;
        private int activeFlows;
        private long pathMultiplicity;

        private ResourceTotals(double capacity) { this.capacity = capacity; }
    }

    private final double observedThrough;
    private final boolean shared;
    private final Map<String, Resource> resources;
    private final Map<Long, Flow> transfers;

    // Only the checked engine supplies these already-committed values; no state is revalidated or advanced.
    TransferServiceSnapshot(double observedThrough, boolean shared, Map<String, Double> capacities,
            Map<Long, Flow> transfers) {
        this.observedThrough = observedThrough;
        this.shared = shared;
        this.transfers = Collections.unmodifiableMap(new LinkedHashMap<Long, Flow>(transfers));

        Map<String, ResourceTotals> totals = new TreeMap<String, ResourceTotals>();
        for (Map.Entry<String, Double> entry : capacities.entrySet()) {
            totals.put(entry.getKey(), new ResourceTotals(entry.getValue()));
        }
        for (Flow flow : this.transfers.values()) {
            Map<String, Integer> multiplicities = new LinkedHashMap<String, Integer>();
            for (String key : flow.resources) {
                Integer previous = multiplicities.get(key);
                multiplicities.put(key, previous == null ? 1 : previous + 1);
            }
            BigDecimal exactRate = new BigDecimal(flow.rateBytesPerSecond);
            for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                ResourceTotals total = totals.get(entry.getKey());
                int multiplicity = entry.getValue();
                total.assignedRate = total.assignedRate.add(exactRate.multiply(BigDecimal.valueOf(multiplicity)));
                total.activeFlows++;
                total.pathMultiplicity += multiplicity;
            }
        }
        Map<String, Resource> copiedResources = new LinkedHashMap<String, Resource>();
        for (Map.Entry<String, ResourceTotals> entry : totals.entrySet()) {
            ResourceTotals total = entry.getValue();
            copiedResources.put(entry.getKey(), new Resource(entry.getKey(), total.capacity,
                    total.assignedRate, total.activeFlows, total.pathMultiplicity));
        }
        this.resources = Collections.unmodifiableMap(copiedResources);
    }

    /**
     * @return engine's committed last advance time, not CloudSim time, wall time,
     *         or the effective completion time of a previously settled flow
     */
    public double getObservedThrough() { return observedThrough; }

    /** @return whether physical resources are shared between active flows */
    public boolean isShared() { return shared; }

    /** @return immutable declared-resource map in natural String key order, including idle resources */
    public Map<String, Resource> getResources() { return resources; }

    /** @return immutable current active-flow map in admission order, not an admission history */
    public Map<Long, Flow> getTransfers() { return transfers; }
}
