package org.workflowsim.data;

import java.math.BigDecimal;

/** Immutable aggregate of actual V1 grouped-input reference/local-skip observations, not unique files. */
public final class NetworkInputDemandSnapshot {
    private static final NetworkInputDemandSnapshot EMPTY=new NetworkInputDemandSnapshot(0,0,BigDecimal.ZERO,BigDecimal.ZERO);
    private final String scope="V1_GROUP_INPUT_REFERENCES";
    private final long referenceCount,localReferenceCount;
    private final BigDecimal requiredReferenceBytes,localReferenceBytes,transferableReferenceBytes;
    private NetworkInputDemandSnapshot(long references,long localReferences,BigDecimal required,BigDecimal local){
        referenceCount=references;localReferenceCount=localReferences;requiredReferenceBytes=required;
        localReferenceBytes=local;transferableReferenceBytes=required.subtract(local);
    }
    /** @return immutable zero observation */ public static NetworkInputDemandSnapshot empty(){return EMPTY;}
    /**
     * Rebuild validated aggregate observations. No missing counters are inferred as zero.
     * @param references total input references across the modeled groups
     * @param localReferences references skipped because the target already had the input
     * @param required exact sum of the observed double file sizes
     * @param local exact size sum of references actually skipped
     * @return immutable validated snapshot
     * @throws IllegalArgumentException for inconsistent counts or byte partitions
     */
    public static NetworkInputDemandSnapshot of(long references,long localReferences,BigDecimal required,BigDecimal local){
        if(references<0||localReferences<0||localReferences>references||required==null||local==null
                ||required.signum()<0||local.signum()<0||local.compareTo(required)>0
                ||(references==0&&required.signum()!=0)||(localReferences==0&&local.signum()!=0)
                ||(references==localReferences&&required.compareTo(local)!=0))
            throw new IllegalArgumentException("Inconsistent grouped input-demand counters");
        return new NetworkInputDemandSnapshot(references,localReferences,required,local);
    }
    /** @return explicit reference-counting scope */ public String getScope(){return scope;}
    /** @return observed group input references, including repeat/zero-byte references */ public long getReferenceCount(){return referenceCount;}
    /** @return references actually skipped as local */ public long getLocalReferenceCount(){return localReferenceCount;}
    /** @return exact observed reference-size total */ public BigDecimal getRequiredReferenceBytes(){return requiredReferenceBytes;}
    /** @return exact local-reference size total */ public BigDecimal getLocalReferenceBytes(){return localReferenceBytes;}
    /** @return exact nonlocal-reference size total before legacy double group summation */ public BigDecimal getTransferableReferenceBytes(){return transferableReferenceBytes;}
}
