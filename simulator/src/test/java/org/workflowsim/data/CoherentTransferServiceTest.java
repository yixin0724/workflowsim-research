package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/** The V2 run driver needs transactional service and completion clocks even when recording is OFF. */
class CoherentTransferServiceTest {
    @Test void sharedAndIsolatedUseTheSameSingleFlowBottleneck(){
        for(boolean sharing:new boolean[]{true,false}){
            TransferContentionEngine e=service(sharing,10);e.addTransfer(1,100,Arrays.asList("R"),100,0);e.addTransfer(2,100,Arrays.asList("R"),100,0);
            assertEquals(sharing?5:10,e.currentRateBytesPerSecond(1),0);
            double finish=sharing?20:10;TransferContentionEngine.AdvanceResult result=e.advance(finish+7);
            assertEquals(Arrays.asList(1L,2L),result.getCompletedTransferIds());assertEquals(2,result.getCompletionObservations().size());
            assertEquals(finish,result.getCompletionObservations().get(0).getEffectiveTime(),0);assertEquals(finish+7,result.getCompletionObservations().get(0).getObservedTime(),0);
            assertEquals(TransferTraceSnapshot.Status.DISABLED,e.getTraceSnapshot().getStatus());
        }
    }
    @Test void completionObservationsDoNotDependOnOptionalTrace(){
        TransferContentionEngine e=service(true,100);e.addTransfer(41,.5,Arrays.asList("R"),100,0);
        TransferContentionEngine.AdvanceResult result=e.advance(.1);
        assertEquals(41,result.getCompletionObservations().get(0).getTransferId());assertEquals(.005,result.getCompletionObservations().get(0).getEffectiveTime(),0);
        assertEquals(.1,result.getCompletionObservations().get(0).getObservedTime(),0);assertTrue(e.getTraceSnapshot().getEvents().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->result.getCompletionObservations().clear());
    }
    @Test void failedAdmissionRollsBackExistingClockAndAllocation(){
        TransferContentionEngine e=service(true,1);e.addTransfer(1,Double.MAX_VALUE,Arrays.asList("R"),1,0);
        assertThrows(IllegalArgumentException.class,()->e.addTransfer(2,Double.MAX_VALUE,Arrays.asList("R"),1,1));
        assertEquals(0,e.getCurrentTime(),0);assertEquals(1,e.activeTransferCount());assertEquals(1,e.currentRateBytesPerSecond(1),0);
        assertEquals(Double.MAX_VALUE,e.advance(0).getNextCompletionTime(),0);
    }
    @Test void failedReallocationDuringAdvanceRollsBackSettlementsToo(){
        TransferContentionEngine e=service(true,2);double start=0x1.0p53;e.advance(start);
        e.addTransfer(1,4,Arrays.asList("R"),2,start);e.addTransfer(2,5,Arrays.asList("R"),2,start);
        assertThrows(IllegalArgumentException.class,()->e.advance(start+4));
        assertEquals(start,e.getCurrentTime(),0);assertEquals(2,e.activeTransferCount());assertEquals(1,e.currentRateBytesPerSecond(1),0);assertEquals(1,e.currentRateBytesPerSecond(2),0);
    }
    @Test void rejectsMissingResourcesAndImpossibleAbsoluteAdmissionWithoutMutation(){
        TransferContentionEngine e=service(true,10);
        assertThrows(IllegalArgumentException.class,()->e.addTransfer(1,1,Arrays.asList("missing"),10,2));
        assertThrows(IllegalArgumentException.class,()->e.addTransfer(1,1,Collections.<String>emptyList(),10,2));
        assertThrows(IllegalArgumentException.class,()->e.addTransfer(1,1,Arrays.asList("R"),10,Double.MAX_VALUE));
        assertEquals(0,e.activeTransferCount());assertEquals(0,e.getCurrentTime(),0);
    }
    @Test void normalInputDoesNotPermitSubnormalSharedAllocations(){
        TransferContentionEngine e=service(true,Double.MIN_NORMAL);e.addTransfer(1,Double.MIN_NORMAL,Arrays.asList("R"),Double.MIN_NORMAL,0);
        assertThrows(IllegalArgumentException.class,()->e.addTransfer(2,Double.MIN_NORMAL,Arrays.asList("R"),Double.MIN_NORMAL,0));
        assertEquals(1,e.activeTransferCount());assertEquals(Double.MIN_NORMAL,e.currentRateBytesPerSecond(1),0);
        assertThrows(IllegalArgumentException.class,()->TransferContentionEngine.coherentV2(true).setEndpointCapacity("tiny",Double.MIN_VALUE));
    }
    @Test void completionResidualIsAvailableWithoutCallingItTraffic(){
        TransferContentionEngine e=service(true,10);e.addTransfer(1,1,Arrays.asList("R"),10,0);e.addTransfer(2,1+1e-10,Arrays.asList("R"),10,0);
        TransferContentionEngine.AdvanceResult result=e.advance(1);assertEquals(2,result.getCompletionObservations().size());
        assertEquals(0,result.getCompletionObservations().get(0).getRemainingAfterService(),0);assertTrue(result.getCompletionObservations().get(1).getRemainingAfterService()>0);
        assertTrue(e.getTraceSnapshot().getEvents().isEmpty());
    }
    @Test void stagedForksPreserveOriginalProgressAndCompletionClocks(){
        TransferContentionEngine original=service(true,10);original.addTransfer(1,100,Arrays.asList("R"),10,0);original.advance(5);
        TransferContentionEngine staged=original.fork();assertEquals(Arrays.asList(1L),staged.advance(10).getCompletedTransferIds());
        assertEquals(1,original.activeTransferCount());assertEquals(5,original.getCurrentTime(),0);
        TransferContentionEngine.AdvanceResult result=original.advance(20);assertEquals(10,result.getCompletionObservations().get(0).getEffectiveTime(),0);assertEquals(20,result.getCompletionObservations().get(0).getObservedTime(),0);
    }
    @Test void oldConstructorsKeepTheirOriginalNumericalAndRecordingContracts(){
        TransferContentionEngine old=new TransferContentionEngine(20);old.setEndpointCapacity("R",Double.MIN_NORMAL);
        old.addTransfer(1,Double.MIN_NORMAL,Arrays.asList("R"),Double.MIN_NORMAL,0);old.addTransfer(2,Double.MIN_NORMAL,Arrays.asList("R"),Double.MIN_NORMAL,0);
        assertEquals(Double.MIN_NORMAL/2,old.currentRateBytesPerSecond(1),0);
        TransferContentionEngine copy=old.fork();assertEquals(2,copy.advance(2).getCompletedTransferIds().size());assertEquals(2,old.activeTransferCount());
        assertTrue(copy.advance(2).getCompletionObservations().isEmpty());assertEquals(TransferTraceSnapshot.Status.COMPLETE,old.getTraceSnapshot().getStatus());
    }
    @Test void finiteDurationCanStillOverflowServiceAreaAndMustRollBack(){
        java.util.Random random=new java.util.Random(1);Double rate=null;
        for(int i=0;i<100000;i++){double candidate=1+random.nextDouble(),duration=Double.MAX_VALUE/candidate;if(Double.isFinite(duration)&&!Double.isFinite(candidate*duration)){rate=candidate;break;}}
        assertNotNull(rate,"bounded binary64 counterexample search must find an overflowing rounded product");
        TransferContentionEngine engine=service(true,rate);engine.addTransfer(1,Double.MAX_VALUE,Arrays.asList("R"),rate,0);double until=Double.MAX_VALUE/rate;
        assertThrows(IllegalArgumentException.class,()->engine.advance(until));assertEquals(0,engine.getCurrentTime(),0);assertEquals(1,engine.activeTransferCount());
    }
    @Test void checkedSharingAcceptsOrdinaryThreeWayRoundingWithinDeclaredProfile(){
        TransferContentionEngine e=service(true,1_000_000);for(int i=1;i<=3;i++)e.addTransfer(i,1_000_000,Arrays.asList("R"),1_000_000,0);
        assertEquals(3,e.advance(3).getCompletedTransferIds().size());
    }
    private static TransferContentionEngine service(boolean sharing,double capacity){TransferContentionEngine engine=TransferContentionEngine.coherentV2(sharing);engine.setEndpointCapacity("R",capacity);return engine;}
}
