package org.workflowsim.data;

import java.math.BigDecimal;

/** O(1)-entry run-local observer; it keeps aggregates, never per-file or per-Job history. */
public final class NetworkInputDemandTracker {
    private long references,localReferences;
    private BigDecimal requiredBytes=BigDecimal.ZERO,localBytes=BigDecimal.ZERO;
    /**
     * Observe the same locality decision that the V1 input loop uses, without re-querying its catalog.
     * @param bytes finite nonnegative modeled file size
     * @param local whether the actual loop skipped this reference as local
     * @throws IllegalArgumentException for an invalid size, without changing counters
     */
    public void record(double bytes,boolean local){
        if(!Double.isFinite(bytes)||bytes<0)throw new IllegalArgumentException("Input reference bytes must be finite and nonnegative");
        long next=Math.addExact(references,1L),nextLocal=local?Math.addExact(localReferences,1L):localReferences;
        BigDecimal amount=new BigDecimal(bytes);
        BigDecimal nextRequired=requiredBytes.add(amount),nextLocalBytes=local?localBytes.add(amount):localBytes;
        references=next;localReferences=nextLocal;requiredBytes=nextRequired;localBytes=nextLocalBytes;
    }
    /** @return immutable exact aggregate with no reference to this tracker */
    public NetworkInputDemandSnapshot snapshot(){return NetworkInputDemandSnapshot.of(references,localReferences,requiredBytes,localBytes);}
}
