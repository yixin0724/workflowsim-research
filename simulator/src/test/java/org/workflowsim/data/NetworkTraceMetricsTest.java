package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** NF002B2 analytic byte/time oracles; no metric expectations obtained from the allocator. */
class NetworkTraceMetricsTest {
    private static final int BUDGET=10000;

    @Test void disabledIsUnavailableWhereasCompleteZeroFlowHasRealZeroTotals() {
        NetworkTraceMetrics off=NetworkTraceMetrics.calculate(new TransferContentionEngine().getTraceSnapshot(),0);
        assertFalse(off.isAvailable()); assertNull(off.getAdmittedPayloadBytes()); assertNull(off.getCompletedFctSampleCount());
        NetworkTraceMetrics empty=metrics(engine());assertTrue(empty.isAvailable());
        dec("0",empty.getAdmittedPayloadBytes());dec("0",empty.getServicedBalanceDeltaBytes());
        dec("0",empty.getModeledRateAreaBytes());dec("0",empty.getCompletionResidualBytes());
        assertEquals(Integer.valueOf(0),empty.getCompletedFctSampleCount());assertNull(empty.getMeanEffectiveFctSeconds());
        assertNull(empty.getP95EffectiveFctSeconds());assertEquals(0,empty.getIntegrationEpochCount());
        assertTrue(empty.getFlows().isEmpty());assertTrue(empty.getResources().isEmpty());
    }

    @Test void lateAdvanceUsesEffectiveFctAndSeparatesNotificationLag() {
        TransferContentionEngine e=late();NetworkTraceMetrics m=metrics(e);
        dec("1500",m.getAdmittedPayloadBytes());dec("1500",m.getCompletedDemandBytes());
        dec("1500",m.getServicedBalanceDeltaBytes());dec("1500",m.getModeledRateAreaBytes());
        dec("0",m.getCompletionResidualBytes());dec("0",m.getRemainingLedgerBytes());
        assertEquals(Integer.valueOf(2),m.getCompletedFctSampleCount());
        assertEquals(12.5,m.getMeanEffectiveFctSeconds(),0);assertEquals(15,m.getP95EffectiveFctSeconds(),0);
        assertEquals(15,m.getMaxEffectiveFctSeconds(),0);assertEquals(7.5,m.getMeanNotificationLagSeconds(),0);
        assertEquals(10,m.getFlows().get(0).getEffectiveFctSeconds(),0);
        assertEquals(20,m.getFlows().get(0).getObservedFctSeconds(),0);
        assertEquals(5,m.getFlows().get(1).getNotificationLagSeconds(),0);
        assertEquals(2,m.getIntegrationEpochCount());
        NetworkTraceMetrics.ResourceUsage r=resource(m,"A");
        dec("1500",r.getRateAreaBytes());dec("1500",r.getCapacityAreaBytes());
        assertEquals(1,r.getIntegrationEpochUtilization(),0);balance(m);
    }

    @Test void capacityEpochDenominatorIsCountedOnceNotOncePerServiceRow() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);
        add(e,1,100,10,0,"A");add(e,2,900,100,0,"A");e.advance(10);
        NetworkTraceMetrics m=metrics(e);assertEquals(1,m.getIntegrationEpochCount());
        dec("1000",resource(m,"A").getRateAreaBytes());dec("1000",resource(m,"A").getCapacityAreaBytes());
        assertEquals(1,resource(m,"A").getIntegrationEpochUtilization(),0);
    }

    @Test void nominalCappedFlowLeavesMeasuredCapacityUnused() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);add(e,1,100,10,0,"A");e.advance(10);
        NetworkTraceMetrics m=metrics(e);dec("100",m.getServicedBalanceDeltaBytes());
        dec("1000",resource(m,"A").getCapacityAreaBytes());assertEquals(.1,resource(m,"A").getIntegrationEpochUtilization(),0);
    }

    @Test void duplicatedResourcesAreOccupancyWeightsNotDeliveredPayload() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);
        add(e,1,100,10,0,"A","A");add(e,2,800,100,0,"A");e.advance(10);
        NetworkTraceMetrics m=metrics(e);dec("900",m.getAdmittedPayloadBytes());dec("900",m.getModeledRateAreaBytes());
        dec("1000",resource(m,"A").getRateAreaBytes());assertEquals(1,resource(m,"A").getIntegrationEpochUtilization(),0);
    }

    @Test void multiHopResourceAreaCannotBeSummedAsUniqueTransferredBytes() {
        TransferContentionEngine e=engine();for(String key:Arrays.asList("A","B","C"))e.setEndpointCapacity(key,100);
        add(e,1,100,100,0,"A","B","C");e.advance(1);NetworkTraceMetrics m=metrics(e);
        dec("100",m.getModeledRateAreaBytes());BigDecimal occupancy=BigDecimal.ZERO;
        for(NetworkTraceMetrics.ResourceUsage r:m.getResources())occupancy=occupancy.add(r.getRateAreaBytes());
        dec("300",occupancy);
    }

    @Test void residualsRemainSeparateFromExactRateAreaAndBalanceDebit() {
        TransferContentionEngine e=engine();add(e,1,1,49,0);e.advance(1);NetworkTraceMetrics m=metrics(e);
        assertTrue(m.getCompletionResidualBytes().signum()>0);dec("1",m.getCompletedDemandBytes());
        assertEquals(0,m.getServicedBalanceDeltaBytes().add(m.getCompletionResidualBytes()).compareTo(BigDecimal.ONE));
        BigDecimal exactArea=new BigDecimal(49.0).multiply(new BigDecimal(1.0/49.0));
        assertEquals(0,exactArea.compareTo(m.getModeledRateAreaBytes()));
        assertEquals(0,exactArea.subtract(m.getServicedBalanceDeltaBytes()).compareTo(m.getRateAreaMinusBalanceDeltaBytes()));balance(m);
    }

    @Test void largeBalanceCancellationDoesNotEraseModeledRateArea() {
        TransferContentionEngine e=engine();add(e,1,1e16,1,0);e.advance(.5);NetworkTraceMetrics m=metrics(e);
        dec("0",m.getServicedBalanceDeltaBytes());dec("0.5",m.getModeledRateAreaBytes());
        dec("0.5",m.getRateAreaMinusBalanceDeltaBytes());dec("10000000000000000",m.getRemainingLedgerBytes());
        assertFalse(m.getFlows().get(0).isComplete());assertNull(m.getFlows().get(0).getEffectiveFctSeconds());balance(m);
    }

    @Test void arithmeticAdjustmentRetainsNegativeAsWellAsPositiveRoundingDifferences() {
        TransferContentionEngine e=engine();add(e,1,1,1,0);e.advance(.3);NetworkTraceMetrics m=metrics(e);
        assertTrue(m.getRateAreaMinusBalanceDeltaBytes().signum()<0);
        assertEquals(0,new BigDecimal(.3).subtract(BigDecimal.ONE.subtract(new BigDecimal(.7)))
                .compareTo(m.getRateAreaMinusBalanceDeltaBytes()));balance(m);
    }

    @Test void registeredCapacityWithoutAnyServiceHasNoUtilizationSample() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);e.advance(10);
        NetworkTraceMetrics m=metrics(e);assertTrue(m.isAvailable());assertEquals(0,m.getIntegrationEpochCount());
        dec("0",resource(m,"A").getCapacityAreaBytes());assertNull(resource(m,"A").getIntegrationEpochUtilization());
        assertNull(m.getMeanEffectiveFctSeconds());
    }

    @Test void zeroClockFctDoesNotErasePositiveServiceOrCreateThroughputInfinity() {
        double time=0x1.0p54;TransferContentionEngine e=engine();e.advance(time);add(e,1,1,49,time);e.advance(Math.nextUp(time));
        NetworkTraceMetrics m=metrics(e);assertTrue(m.getModeledRateAreaBytes().signum()>0);
        assertEquals(0,m.getMeanEffectiveFctSeconds(),0);assertEquals(0,m.getP95EffectiveFctSeconds(),0);
        assertEquals(4,m.getMeanNotificationLagSeconds(),0);
        String json=new GsonBuilder().serializeNulls().create().toJson(m);assertFalse(json.contains("Infinity"));assertFalse(json.contains("NaN"));balance(m);
    }

    @Test void zeroElapsedSettlementAccountsForLargeSubnormalResidualWithoutTraffic() {
        double q=Double.MIN_VALUE;TransferContentionEngine e=engine();add(e,11,192*q,128,0);add(e,22,64*q,64,0);e.advance(2*q);
        NetworkTraceMetrics m=metrics(e);BigDecimal unit=new BigDecimal(q);
        assertEquals(0,unit.multiply(BigDecimal.valueOf(256)).compareTo(m.getAdmittedPayloadBytes()));
        assertEquals(0,unit.multiply(BigDecimal.valueOf(192)).compareTo(m.getServicedBalanceDeltaBytes()));
        assertEquals(0,unit.multiply(BigDecimal.valueOf(64)).compareTo(m.getCompletionResidualBytes()));
        assertEquals(1,m.getIntegrationEpochCount());assertEquals(2,m.getFlows().size());balance(m);
    }

    @Test void unboundedSourceHasAreaButNoUtilizationDenominator() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("VM",100);add(e,1,100,100,0,"source","VM");e.advance(1);
        NetworkTraceMetrics.ResourceUsage source=resource(metrics(e),"source");
        assertNull(source.getLastDeclaredCapacityBytesPerSecond());assertNull(source.getIntegrationEpochUtilization());
        dec("100",source.getRateAreaBytes());dec("0",source.getCapacityAreaBytes());dec("0",source.getBoundedRateAreaBytes());
    }

    @Test void resourceBecomingBoundedDoesNotChargeUnboundedHistoryToItsDenominator() {
        TransferContentionEngine e=engine();add(e,1,100,100,0,"S");e.advance(1);e.setEndpointCapacity("S",100);
        add(e,2,100,100,1,"S");e.advance(2);NetworkTraceMetrics.ResourceUsage r=resource(metrics(e),"S");
        dec("200",r.getRateAreaBytes());dec("100",r.getBoundedRateAreaBytes());dec("100",r.getCapacityAreaBytes());
        assertEquals(1,r.getIntegrationEpochUtilization(),0);
    }

    @Test void epochAveragesExcludeIdleTimeButIncludeUnusedRegisteredResources() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);e.setEndpointCapacity("unused",100);
        e.advance(10);add(e,1,100,100,10,"A");e.advance(11);e.advance(100);NetworkTraceMetrics m=metrics(e);
        dec("100",resource(m,"A").getCapacityAreaBytes());assertEquals(1,resource(m,"A").getIntegrationEpochUtilization(),0);
        dec("100",resource(m,"unused").getCapacityAreaBytes());dec("0",resource(m,"unused").getRateAreaBytes());
        assertEquals(0,resource(m,"unused").getIntegrationEpochUtilization(),0);
    }

    @Test void activeFlowsAreCensoredAndCompleteCaptureDoesNotMeanCompletedWork() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);add(e,1,100,10,0,"A");e.advance(2);
        NetworkTraceMetrics m=metrics(e);assertTrue(m.isAvailable());assertEquals(Integer.valueOf(0),m.getCompletedFctSampleCount());
        assertNull(m.getMeanEffectiveFctSeconds());dec("0",m.getCompletedDemandBytes());
        dec("20",m.getServicedBalanceDeltaBytes());dec("80",m.getRemainingLedgerBytes());
        assertNull(m.getFlows().get(0).getCompletionEffectiveTime());assertNull(m.getFlows().get(0).getNotificationLagSeconds());balance(m);
    }

    @Test void everyTruncatedCutSuppressesWholeCaptureTotalsAndTimingSummaries() {
        TransferTraceSnapshot full=late().getTraceSnapshot();
        for(int count=1;count<full.getEvents().size();count++) {
            NetworkTraceMetrics m=NetworkTraceMetrics.calculate(new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,
                    full.getEvents().subList(0,count),full.getEvents().size()-count,full.getEngineTime()),count);
            assertFalse(m.isAvailable());assertNull(m.getAdmittedPayloadBytes());assertNull(m.getServicedBalanceDeltaBytes());
            assertNull(m.getModeledRateAreaBytes());assertNull(m.getCompletionResidualBytes());assertNull(m.getRemainingLedgerBytes());
            assertNull(m.getCompletedFctSampleCount());assertNull(m.getMeanEffectiveFctSeconds());assertNull(m.getP95EffectiveFctSeconds());
            assertTrue(m.getFlows().isEmpty());assertTrue(m.getResources().isEmpty());
            assertEquals(count,m.getValidation().getRecordCount());
        }
    }

    @Test void exactByteAggregatesDoNotOverflowBinary64OrLoseMeanTime() {
        TransferContentionEngine e=engine();for(int id=1;id<=3;id++)add(e,id,Double.MAX_VALUE,1,0);e.advance(Double.MAX_VALUE);
        NetworkTraceMetrics m=metrics(e);BigDecimal total=new BigDecimal(Double.MAX_VALUE).multiply(BigDecimal.valueOf(3));
        assertEquals(0,total.compareTo(m.getAdmittedPayloadBytes()));assertEquals(0,total.compareTo(m.getModeledRateAreaBytes()));
        assertEquals(Double.MAX_VALUE,m.getMeanEffectiveFctSeconds(),0);balance(m);
        assertFalse(new GsonBuilder().serializeNulls().create().toJson(m).contains("Infinity"));
    }

    @Test void unusedCapacityAreaCanExceedDoubleRangeWithoutInfinity() {
        TransferContentionEngine e=engine();e.setEndpointCapacity("unused",Double.MAX_VALUE);
        add(e,1,Double.MAX_VALUE,1,0);e.advance(Double.MAX_VALUE);NetworkTraceMetrics m=metrics(e);
        BigDecimal area=new BigDecimal(Double.MAX_VALUE).multiply(new BigDecimal(Double.MAX_VALUE));
        assertEquals(0,area.compareTo(resource(m,"unused").getCapacityAreaBytes()));
        assertEquals(0,resource(m,"unused").getIntegrationEpochUtilization(),0);
    }

    @Test void p95UsesExactNearestRankRatherThanInterpolationOrObservedClock() {
        TransferContentionEngine e=engine();for(int id=1;id<=20;id++)add(e,id,id,1,0);e.advance(30);
        NetworkTraceMetrics m=metrics(e);assertEquals(Integer.valueOf(20),m.getCompletedFctSampleCount());
        assertEquals(10.5,m.getMeanEffectiveFctSeconds(),0);assertEquals(19,m.getP95EffectiveFctSeconds(),0);
        assertEquals(20,m.getMaxEffectiveFctSeconds(),0);assertEquals(19.5,m.getMeanNotificationLagSeconds(),0);
    }

    @Test void reusedIdsRemainDistinctAndFrozenSummariesOutliveTheEngine() {
        TransferContentionEngine e=engine();add(e,77,1,1,0);e.advance(1);add(e,77,2,1,1);e.advance(3);
        NetworkTraceMetrics m=metrics(e);assertEquals(2,m.getFlows().size());
        assertEquals(1,m.getFlows().get(0).getAdmissionOrdinal());assertEquals(2,m.getFlows().get(1).getAdmissionOrdinal());
        assertEquals(1.5,m.getMeanEffectiveFctSeconds(),0);
        String before=new GsonBuilder().serializeNulls().create().toJson(m);add(e,99,10,1,3);e.advance(20);
        assertEquals(before,new GsonBuilder().serializeNulls().create().toJson(m));
        assertThrows(UnsupportedOperationException.class,()->m.getFlows().clear());
        assertThrows(UnsupportedOperationException.class,()->m.getResources().add(null));
    }

    @Test void invalidEvidenceCannotProducePlausibleMetrics() {
        TransferTraceSnapshot invalid=new TransferTraceSnapshot(TransferTraceSnapshot.Status.COMPLETE,
                Collections.singletonList(TransferTraceEvent.start(1,0,1,1,100,Collections.<String>emptyList(),100,50)),0,0);
        assertThrows(IllegalArgumentException.class,()->NetworkTraceMetrics.calculate(invalid,1));
        assertThrows(IllegalArgumentException.class,()->NetworkTraceMetrics.calculate(null,1));
    }

    private static TransferContentionEngine engine(){return new TransferContentionEngine(BUDGET);}
    private static void add(TransferContentionEngine e,long id,double bytes,double rate,double now,String...resources){e.addTransfer(id,bytes,Arrays.asList(resources),rate,now);}
    private static TransferContentionEngine late(){TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);add(e,1,500,100,0,"A");add(e,2,1000,100,0,"A");e.advance(20);return e;}
    private static NetworkTraceMetrics metrics(TransferContentionEngine e){return NetworkTraceMetrics.calculate(e.getTraceSnapshot(),BUDGET);}
    private static NetworkTraceMetrics.ResourceUsage resource(NetworkTraceMetrics m,String key){
        for(NetworkTraceMetrics.ResourceUsage r:m.getResources())if(key.equals(r.getResourceKey()))return r;
        throw new AssertionError("Missing resource "+key);
    }
    private static void dec(String expected,BigDecimal actual){assertNotNull(actual);assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private static void balance(NetworkTraceMetrics m){assertEquals(0,m.getAdmittedPayloadBytes().compareTo(
            m.getServicedBalanceDeltaBytes().add(m.getCompletionResidualBytes()).add(m.getRemainingLedgerBytes())));}
}
