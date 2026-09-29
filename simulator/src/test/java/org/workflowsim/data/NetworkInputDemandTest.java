package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** Locality uses observed skip decisions, never demand-minus-serviced-byte inference. */
class NetworkInputDemandTest {
    @Test void emptySnapshotIsACompleteZeroCounterWithoutInventingFileHits() {
        NetworkInputDemandSnapshot s=new NetworkInputDemandTracker().snapshot();
        assertEquals(0,s.getReferenceCount());assertEquals(0,s.getLocalReferenceCount());
        dec("0",s.getRequiredReferenceBytes());dec("0",s.getLocalReferenceBytes());dec("0",s.getTransferableReferenceBytes());
    }

    @Test void localFlagsPartitionExactReferenceBytesIncludingZeroSizeReferences() {
        NetworkInputDemandTracker t=new NetworkInputDemandTracker();t.record(1.5,true);t.record(.5,false);t.record(0,true);
        NetworkInputDemandSnapshot s=t.snapshot();assertEquals(3,s.getReferenceCount());assertEquals(2,s.getLocalReferenceCount());
        dec("2",s.getRequiredReferenceBytes());dec("1.5",s.getLocalReferenceBytes());dec(".5",s.getTransferableReferenceBytes());
        String before=new Gson().toJson(s);t.record(100,false);assertEquals(before,new Gson().toJson(s));
    }

    @Test void referenceCountersDoNotLoseSmallValuesDuringGroupDoubleSummation() {
        NetworkInputDemandTracker t=new NetworkInputDemandTracker();t.record(1e16,false);t.record(1,false);t.record(.5,true);
        NetworkInputDemandSnapshot s=t.snapshot();dec("10000000000000001.5",s.getRequiredReferenceBytes());
        dec("10000000000000001",s.getTransferableReferenceBytes());dec(".5",s.getLocalReferenceBytes());
    }

    @Test void invalidDemandDoesNotPartiallyMutateCounters() {
        NetworkInputDemandTracker t=new NetworkInputDemandTracker();t.record(1,true);String before=new Gson().toJson(t.snapshot());
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->t.record(bad,false));assertEquals(before,new Gson().toJson(t.snapshot()));
        }
    }

    @Test void snapshotFactoryRejectsInconsistentCountsAndBytePartitions() {
        assertAll(
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(-1,0,BigDecimal.ZERO,BigDecimal.ZERO)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(1,2,BigDecimal.ONE,BigDecimal.ONE)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(1,0,null,BigDecimal.ZERO)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(1,0,BigDecimal.ONE,BigDecimal.ONE)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(0,0,BigDecimal.ONE,BigDecimal.ZERO)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(1,1,BigDecimal.ONE,BigDecimal.ZERO)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(2,1,BigDecimal.ONE,BigDecimal.TEN)),
                ()->assertThrows(IllegalArgumentException.class,()->NetworkInputDemandSnapshot.of(2,1,BigDecimal.ONE,BigDecimal.valueOf(-1))));
        NetworkInputDemandSnapshot.of(2,1,BigDecimal.ZERO,BigDecimal.ZERO); // A reference may have zero bytes.
    }

    @Test void missingCountersDoNotBecomeZeroLocalityAndDisabledRemainsDistinct() {
        NetworkRunMetrics off=NetworkRunMetrics.calculate(null);
        assertEquals(NetworkRunMetrics.LocalityStatus.DISABLED,off.getLocalityStatus());assertNull(off.getInputReferenceCount());
        NetworkEvidenceConfig cfg=NetworkEvidenceConfig.fluidGroupLedger(10);
        NetworkRunEvidence bare=NetworkRunEvidence.capture(cfg,DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,
                new TransferContentionEngine(10).getTraceSnapshot(),Collections.<NetworkFlowBinding>emptyList());
        NetworkRunMetrics m=NetworkRunMetrics.calculate(bare);
        assertTrue(m.getTransferMetrics().isAvailable());assertEquals(NetworkRunMetrics.LocalityStatus.INPUT_COUNTERS_UNAVAILABLE,m.getLocalityStatus());
        assertNull(m.getLocalInputReferenceBytes());assertNull(m.getLocalByteFraction());
    }

    @Test void repeatedReferencesRemainModelReferencesNotDeduplicatedFileCounts() {
        NetworkInputDemandTracker t=new NetworkInputDemandTracker();for(int i=0;i<10000;i++)t.record(.5,i%2==0);
        NetworkInputDemandSnapshot s=t.snapshot();assertEquals(10000,s.getReferenceCount());assertEquals(5000,s.getLocalReferenceCount());
        dec("5000",s.getRequiredReferenceBytes());dec("2500",s.getLocalReferenceBytes());
    }

    private static void dec(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
}
