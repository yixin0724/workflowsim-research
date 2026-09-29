package org.workflowsim.data;

import java.math.BigDecimal;
import java.math.MathContext;

/** Transfer metrics plus separately observed V1 grouped-input locality; not an inference from service shortfalls. */
public final class NetworkRunMetrics {
    /** Availability of complete-capture locality quantities. */
    public enum LocalityStatus {
        /** Recording was OFF. */ DISABLED,
        /** Flow evidence was truncated; do not publish complete-capture locality totals. */ TRUNCATED_TRACE,
        /** A manually supplied trace has no actual input-reference observations. */ INPUT_COUNTERS_UNAVAILABLE,
        /** Both complete trace and actual aggregate observations are present. */ AVAILABLE
    }
    private final String localityScope="V1_GROUP_INPUT_REFERENCES";
    private final NetworkTraceMetrics transferMetrics;
    private final LocalityStatus localityStatus;
    private final Long inputReferenceCount,localInputReferenceCount;
    private final BigDecimal requiredInputReferenceBytes,localInputReferenceBytes,transferableInputReferenceBytes;
    private final BigDecimal admittedMinusTransferableReferenceBytes;
    private final Double localByteFraction,localReferenceFraction;
    private NetworkRunMetrics(NetworkRunEvidence evidence){
        transferMetrics=evidence==null?null:NetworkTraceMetrics.calculate(evidence.getTraceSnapshot(),evidence.getConfig().getMaxTraceRecords());
        NetworkInputDemandSnapshot input=evidence==null?null:evidence.getInputDemand();
        localityStatus=evidence==null?LocalityStatus.DISABLED:!transferMetrics.isAvailable()?LocalityStatus.TRUNCATED_TRACE
                :input==null?LocalityStatus.INPUT_COUNTERS_UNAVAILABLE:LocalityStatus.AVAILABLE;
        if(localityStatus!=LocalityStatus.AVAILABLE){
            inputReferenceCount=null;localInputReferenceCount=null;requiredInputReferenceBytes=null;localInputReferenceBytes=null;
            transferableInputReferenceBytes=null;admittedMinusTransferableReferenceBytes=null;localByteFraction=null;localReferenceFraction=null;
        }else{
            inputReferenceCount=input.getReferenceCount();localInputReferenceCount=input.getLocalReferenceCount();
            requiredInputReferenceBytes=input.getRequiredReferenceBytes();localInputReferenceBytes=input.getLocalReferenceBytes();
            transferableInputReferenceBytes=input.getTransferableReferenceBytes();
            admittedMinusTransferableReferenceBytes=transferMetrics.getAdmittedPayloadBytes().subtract(transferableInputReferenceBytes);
            localByteFraction=requiredInputReferenceBytes.signum()==0?null:
                    localInputReferenceBytes.divide(requiredInputReferenceBytes,MathContext.DECIMAL128).doubleValue();
            localReferenceFraction=inputReferenceCount==0?null:BigDecimal.valueOf(localInputReferenceCount)
                    .divide(BigDecimal.valueOf(inputReferenceCount),MathContext.DECIMAL128).doubleValue();
        }
    }
    /**
     * Validate and summarize a frozen run capture. Null means OFF, not complete zero traffic.
     * @param evidence captured run evidence, null when OFF
     * @return immutable transfer and locality summary
     */
    public static NetworkRunMetrics calculate(NetworkRunEvidence evidence){return new NetworkRunMetrics(evidence);}
    /** @return grouped-reference scope, not unique-file/cache-capacity semantics */ public String getLocalityScope(){return localityScope;}
    /** @return validated flow metrics, or null when OFF */ public NetworkTraceMetrics getTransferMetrics(){return transferMetrics;}
    /** @return explicit locality availability */ public LocalityStatus getLocalityStatus(){return localityStatus;}
    /** @return reference count, or null when unavailable */ public Long getInputReferenceCount(){return inputReferenceCount;}
    /** @return actual local skip count, or null */ public Long getLocalInputReferenceCount(){return localInputReferenceCount;}
    /** @return exact input reference bytes, or null */ public BigDecimal getRequiredInputReferenceBytes(){return requiredInputReferenceBytes;}
    /** @return bytes actually skipped as local, not numerical settlement */ public BigDecimal getLocalInputReferenceBytes(){return localInputReferenceBytes;}
    /** @return exact nonlocal-reference bytes before group-sum rounding */ public BigDecimal getTransferableInputReferenceBytes(){return transferableInputReferenceBytes;}
    /** @return signed legacy group-admission minus exact transferable-reference byte gap */ public BigDecimal getAdmittedMinusTransferableReferenceBytes(){return admittedMinusTransferableReferenceBytes;}
    /** @return local-reference bytes/required-reference bytes, null without a denominator/coverage */ public Double getLocalByteFraction(){return localByteFraction;}
    /** @return actual local reference count/total reference count, null without a denominator/coverage */ public Double getLocalReferenceFraction(){return localReferenceFraction;}
}
