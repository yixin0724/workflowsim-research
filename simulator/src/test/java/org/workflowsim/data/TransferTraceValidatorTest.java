package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.data.TransferTraceEvent.Type.*;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** NF002B1: independent grammar/certificate validation, not a call to the production allocator. */
class TransferTraceValidatorTest {
    private static final Gson JSON = new Gson();
    private static final int BUDGET = 10000;

    @Test void distinguishesDisabledCompleteAndTruncatedCaptures() {
        TransferTraceValidator.Result off = TransferTraceValidator.validate(new TransferContentionEngine().getTraceSnapshot(), 0);
        assertEquals(TransferTraceSnapshot.Status.DISABLED, off.getCaptureStatus());
        assertFalse(off.isCompleteCapture());
        TransferTraceValidator.Result empty = valid(new TransferContentionEngine(BUDGET).getTraceSnapshot());
        assertTrue(empty.isCompleteCapture()); assertEquals(0, empty.getRecordCount());
        assertEquals(0, empty.getOpenFlowCount());
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(null, 1));
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(new TransferContentionEngine().getTraceSnapshot(), -1));
    }

    @Test void validatesAnalyticLateAdvanceAndItsEveryRetainedPrefix() {
        TransferTraceSnapshot trace = late();
        TransferTraceValidator.Result result = valid(trace);
        assertEquals(2, result.getAdmissionCount()); assertEquals(2, result.getCompletionCount());
        assertEquals(0, result.getOpenFlowCount()); assertEquals(10, result.getCertifiedThroughSequence());
        assertAllPrefixes(trace);
    }

    @Test void acceptsAtomicCascadingRateChangesBeforeCheckingCapacity() {
        TransferContentionEngine e = engine(); e.setEndpointCapacity("A",100); e.setEndpointCapacity("B",40);
        add(e,11,1000,100,0,"A"); add(e,22,1000,100,0,"A","B"); add(e,33,1000,10,0,"B");
        assertEquals(70,e.currentRateBytesPerSecond(11),0); assertEquals(30,e.currentRateBytesPerSecond(22),0);
        assertEquals(10,e.currentRateBytesPerSecond(33),0); valid(e.getTraceSnapshot());
        e.advance(1); valid(e.getTraceSnapshot()); assertAllPrefixes(e.getTraceSnapshot());
    }

    @Test void countsResourceMultiplicityAndAllowsUnregisteredAndEmptyKeys() {
        TransferContentionEngine e=engine(); e.setEndpointCapacity("A",100);
        add(e,1,1000,10,0,"A","","A"); add(e,2,1000,100,0,"A");
        assertEquals(80,e.currentRateBytesPerSecond(2),0); e.advance(2); valid(e.getTraceSnapshot());
        TransferContentionEngine unlimited=engine(); add(unlimited,1,.5,1,0); unlimited.advance(1);
        valid(unlimited.getTraceSnapshot());
    }

    @Test void rejectsCapacityFeasibleButUnfairAllocations() {
        List<TransferTraceEvent> rows=Arrays.asList(
                TransferTraceEvent.capacity(1,0,"A",100),
                TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),100,100),
                TransferTraceEvent.start(3,0,2,2,100,Arrays.asList("A"),100,25),
                TransferTraceEvent.rateChange(4,0,0,1,1,100,75));
        invalid(complete(rows,0));
    }

    @Test void rejectsUnusedCapacityAndNominalCapViolations() {
        invalid(complete(Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),100,50)),0));
        invalid(complete(Collections.singletonList(TransferTraceEvent.start(1,0,1,1,100,
                Collections.<String>emptyList(),10,11)),0));
        invalid(complete(Collections.singletonList(TransferTraceEvent.start(1,0,1,1,100,
                Collections.<String>emptyList(),100,50)),0));
    }

    @Test void allocationProfileAllowsOnlyBoundedNormalUlpNoise() {
        double unit=Math.ulp(50.0);
        for(int multiplier:new int[]{1,128}) {
            double delta=multiplier*unit;
            TransferTraceSnapshot trace=complete(Arrays.asList(
                    TransferTraceEvent.capacity(1,0,"A",100),
                    TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),100,100),
                    TransferTraceEvent.start(3,0,2,2,100,Arrays.asList("A"),100,50+delta),
                    TransferTraceEvent.rateChange(4,0,0,1,1,100,50-delta)),0);
            if(multiplier==1) valid(trace); else invalid(trace);
        }
    }

    @Test void ulpBudgetCannotHideLargeFractionalUnderallocationAtSubnormalRates() {
        double q=Double.MIN_VALUE;
        invalid(complete(Arrays.asList(TransferTraceEvent.capacity(1,0,"A",10*q),
                TransferTraceEvent.start(2,0,1,1,64*q,Arrays.asList("A"),64*q,q)),0));
    }

    @Test void coarseSubnormalCapacityOvercommitIsDetectedNotCertifiedAsRoundingNoise() {
        double q=Double.MIN_VALUE;
        // These finite positive inputs expose the old allocator's coarse division resolution.
        // Observability must flag 4q of allocation against 3q capacity, not bless a 33% overload.
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",3*q);
        add(e,1,1,1,0,"A");add(e,2,1,1,0,"A");
        assertEquals(2*q,e.currentRateBytesPerSecond(1),0);assertEquals(2*q,e.currentRateBytesPerSecond(2),0);
        invalid(e.getTraceSnapshot());
    }

    @Test void rejectsTreatingRepeatedResourceAsOneShare() {
        invalid(complete(Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A","A"),10,10),
                TransferTraceEvent.start(3,0,2,2,100,Arrays.asList("A"),100,90)),0));
    }

    @Test void rejectsMissingOrReorderedServiceAndCompletionRecords() {
        TransferTraceSnapshot trace=late();
        List<TransferTraceEvent> removed=new ArrayList<>(trace.getEvents()); removed.remove(5); invalid(complete(renumber(removed),20));
        List<TransferTraceEvent> swapped=new ArrayList<>(trace.getEvents()); Collections.swap(swapped,4,5); invalid(complete(renumber(swapped),20));
        removed=new ArrayList<>(trace.getEvents()); removed.remove(6); invalid(complete(renumber(removed),20));
        List<TransferTraceEvent> extra=new ArrayList<>(trace.getEvents()); extra.add(trace.getEvents().get(9)); invalid(complete(renumber(extra),20));
    }

    @Test void rejectsMissingReallocationEvenWithCoherentlyChangedLaterService() {
        List<TransferTraceEvent> rows=new ArrayList<>(late().getEvents());
        rows.remove(7);
        rows.set(7,TransferTraceEvent.serviceSegment(8,10,20,20,2,2,10,50,500,0));
        rows.set(8,TransferTraceEvent.complete(9,20,20,2,2,0));
        invalid(complete(renumber(rows),20));
    }

    @Test void preservesToleranceResidualAndForcedEarliestResidual() {
        for(double rate:new double[]{1,49}) {
            TransferContentionEngine e=engine(); add(e,1,1,rate,0);
            e.advance(rate==1?1-5e-10:1);
            TransferTraceSnapshot trace=e.getTraceSnapshot(); valid(trace);
            assertTrue(trace.getEvents().get(2).getComplete().getRemainingAfterService()>0);
            List<TransferTraceEvent> wrong=new ArrayList<>(trace.getEvents());
            TransferTraceEvent old=wrong.get(2);
            wrong.set(2,TransferTraceEvent.complete(3,old.getEffectiveTime(),old.getObservedTime(),1,1,0));
            invalid(complete(wrong,trace.getEngineTime()));
        }
    }

    @Test void acceptsPositiveElapsedWithEqualRoundedClocksButRejectsErasingElapsed() {
        TransferContentionEngine e=engine(); double time=0x1.0p54; e.advance(time); add(e,1,1,49,time); e.advance(Math.nextUp(time));
        TransferTraceSnapshot trace=e.getTraceSnapshot(); valid(trace); assertAllPrefixes(trace);
        List<TransferTraceEvent> wrong=new ArrayList<>(trace.getEvents());
        wrong.set(1,tweak(wrong.get(1),"serviceSegment","elapsed",new JsonPrimitive(0.0)));
        invalid(complete(wrong,trace.getEngineTime()));
    }

    @Test void adjacentSameTimeCompletionsMayBelongToDifferentZeroElapsedIterations() {
        double q=Double.MIN_VALUE;
        TransferContentionEngine e=engine(); add(e,11,192*q,128,0); add(e,22,64*q,64,0);
        assertEquals(Arrays.asList(22L,11L),e.advance(2*q).getCompletedTransferIds());
        TransferTraceSnapshot trace=e.getTraceSnapshot(); assertEquals(6,trace.getEvents().size());
        assertEquals(COMPLETE,trace.getEvents().get(4).getType()); assertEquals(COMPLETE,trace.getEvents().get(5).getType());
        assertEquals(64*q,trace.getEvents().get(5).getComplete().getRemainingAfterService(),0);
        valid(trace); assertAllPrefixes(trace);
        List<TransferTraceEvent> wrong=new ArrayList<>(trace.getEvents()); Collections.swap(wrong,4,5);
        invalid(complete(renumber(wrong),2*q));
    }

    @Test void validatesFractionalIndependentFlowsAndInfiniteDerivedDuration() {
        TransferContentionEngine e=engine(); add(e,1,5e-10,1e-9,0); add(e,2,1e-12,1e-9,0);
        e.advance(.001); valid(e.getTraceSnapshot()); e.advance(.5); valid(e.getTraceSnapshot());
        TransferContentionEngine slow=engine(); slow.setEndpointCapacity("tiny",Double.MIN_VALUE);
        add(slow,1,1,1,0,"tiny"); slow.advance(1);
        assertEquals(Double.POSITIVE_INFINITY,1.0/slow.currentRateBytesPerSecond(1),0);
        assertEquals(1,valid(slow.getTraceSnapshot()).getOpenFlowCount());
    }

    @Test void acceptsReusedExternalIdsButRejectsWrongIncarnationsAndOrdinalGaps() {
        TransferContentionEngine e=engine(); add(e,-7,.5,1,0); e.advance(.5); add(e,-7,1,1,.5); e.advance(2);
        valid(e.getTraceSnapshot());
        List<TransferTraceEvent> bad=new ArrayList<>(e.getTraceSnapshot().getEvents());
        bad.set(3,tweak(bad.get(3),null,"admissionOrdinal",new JsonPrimitive(3L))); invalid(complete(bad,2));
        invalid(complete(Arrays.asList(TransferTraceEvent.start(1,0,7,1,1,Collections.<String>emptyList(),1,1),
                TransferTraceEvent.start(2,0,7,2,1,Collections.<String>emptyList(),1,1)),0));
    }

    @Test void acceptsIdleTimeAndCapacityChangesOnlyWhenEmpty() {
        TransferContentionEngine e=engine(); e.advance(7.5); e.setEndpointCapacity("A",2); e.setEndpointCapacity("A",2);
        add(e,1,1,1,7.5,"A"); e.advance(10); e.setEndpointCapacity("A",3); e.advance(20); valid(e.getTraceSnapshot());
        invalid(complete(Arrays.asList(TransferTraceEvent.start(1,0,1,1,1,Collections.<String>emptyList(),1,1),
                TransferTraceEvent.capacity(2,0,"A",100)),0));
        invalid(complete(Arrays.asList(TransferTraceEvent.start(1,0,1,1,100,Collections.<String>emptyList(),1,1),
                TransferTraceEvent.start(2,5,2,2,1,Collections.<String>emptyList(),1,1)),5));
    }

    @Test void rejectsMalformedShapeTimesSequencesAndIncompleteLaundering() {
        TransferTraceSnapshot trace=late();
        List<TransferTraceEvent> wrong=new ArrayList<>(trace.getEvents()); wrong.set(1,tweak(wrong.get(1),null,"sequence",new JsonPrimitive(9L))); invalid(complete(wrong,20));
        wrong=new ArrayList<>(trace.getEvents()); wrong.set(1,tweak(wrong.get(1),null,"start",JsonNull.INSTANCE)); invalid(complete(wrong,20));
        wrong=new ArrayList<>(trace.getEvents()); wrong.set(4,tweak(wrong.get(4),null,"observedTime",new JsonPrimitive(1.0))); invalid(complete(wrong,20));
        invalid(complete(trace.getEvents().subList(0,5),20));
        assertThrows(IllegalArgumentException.class,()->TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,trace.getEvents().subList(0,5),1,20),5));
        assertThrows(IllegalArgumentException.class,()->TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.COMPLETE,trace.getEvents(),1,20),BUDGET));
        assertThrows(IllegalArgumentException.class,()->TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,trace.getEvents(),0,20),trace.getEvents().size()));
    }

    @Test void truncatedAllocationCannotHideAlreadyFixedContradictions() {
        List<List<TransferTraceEvent>> invalidPrefixes=Arrays.asList(
                Collections.singletonList(TransferTraceEvent.start(1,0,1,1,100,Collections.<String>emptyList(),100,50)),
                Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                        TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),100,100),
                        TransferTraceEvent.start(3,0,2,2,100,Arrays.asList("A"),100,25),
                        TransferTraceEvent.rateChange(4,0,0,1,1,100,75)),
                Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                        TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),100,100),
                        TransferTraceEvent.start(3,0,2,2,100,Arrays.asList("A"),100,25)),
                Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                        TransferTraceEvent.start(2,0,1,1,100,Arrays.asList("A"),10,10),
                        TransferTraceEvent.start(3,0,2,2,100,Collections.<String>emptyList(),100,50)),
                Arrays.asList(TransferTraceEvent.capacity(1,0,"A",50),TransferTraceEvent.capacity(2,0,"B",100),
                        TransferTraceEvent.start(3,0,1,1,100,Arrays.asList("B"),100,100),
                        TransferTraceEvent.start(4,0,2,2,100,Arrays.asList("A"),100,75)),
                Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                        TransferTraceEvent.start(2,0,1,1,1000,Arrays.asList("A"),100,100),
                        TransferTraceEvent.start(3,0,2,2,1000,Arrays.asList("A"),100,50),
                        TransferTraceEvent.rateChange(4,0,0,1,1,100,50),
                        TransferTraceEvent.start(5,0,3,3,1000,Arrays.asList("A"),100,40),
                        TransferTraceEvent.rateChange(6,0,0,1,1,50,70)));
        assertAll(invalidPrefixes.stream().map(rows -> (org.junit.jupiter.api.function.Executable) () ->
                assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                        new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows,1,0),rows.size()))));
    }

    @Test void truncatedClosedAllocationMustBudgetTheKnownNextServiceAndCompletion() {
        List<TransferTraceEvent> prefix=late().getEvents().subList(0,8);
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,prefix,1,20),8));
        TransferTraceValidator.Result valid=TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,prefix,2,20),8);
        assertEquals(2,valid.getMinimumMissingRecords());
    }

    @Test void truncatedUnfinishedRateBlockNeedsCorrectionAndPendingAdvanceRecords() {
        List<TransferTraceEvent> prefix=late().getEvents().subList(0,7);
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,prefix,1,20),7));
    }

    @Test void truncatedServiceAndCompletionCutsRetainMandatoryHorizonWork() {
        TransferContentionEngine e=engine();add(e,1,1,1,0);add(e,2,10,1,0);e.advance(2);
        List<TransferTraceEvent> rows=e.getTraceSnapshot().getEvents();assertEquals(6,rows.size());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                        new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows.subList(0,4),1,2),4)),
                () -> assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                        new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows.subList(0,3),2,2),3)));
        assertAllPrefixes(e.getTraceSnapshot());
    }

    @Test void variablePrefixRatesCannotIgnoreTheirOtherResourceCaps() {
        List<TransferTraceEvent> rows=Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                TransferTraceEvent.capacity(2,0,"B",10),
                TransferTraceEvent.start(3,0,1,1,100,Arrays.asList("A","B"),100,10),
                TransferTraceEvent.start(4,0,2,2,100,Arrays.asList("A"),100,50));
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows,1,0),rows.size()));
    }

    @Test void variablePrefixCapsSubtractAlreadyFixedLoadOnOtherResources() {
        List<TransferTraceEvent> rows=Arrays.asList(TransferTraceEvent.capacity(1,0,"A",100),
                TransferTraceEvent.capacity(2,0,"B",50),
                TransferTraceEvent.start(3,0,1,1,100,Arrays.asList("B"),40,40),
                TransferTraceEvent.start(4,0,2,2,100,Arrays.asList("A","B"),100,25),
                TransferTraceEvent.rateChange(5,0,0,1,1,40,25),
                TransferTraceEvent.start(6,0,3,3,100,Arrays.asList("A"),100,60),
                TransferTraceEvent.rateChange(7,0,0,1,1,25,40));
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows,1,0),rows.size()));
    }

    @Test void stablePrefixWatermarkCannotAdvanceWithTooFewDroppedRecords() {
        List<TransferTraceEvent> ends=Collections.singletonList(TransferTraceEvent.start(1,0,1,1,1,
                Collections.<String>emptyList(),1,1));
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,ends,1,2),1));
        TransferTraceValidator.Result possible=TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,ends,2,2),1);
        assertEquals(2,possible.getMinimumMissingRecords());
        List<TransferTraceEvent> stays=Collections.singletonList(TransferTraceEvent.start(1,0,1,1,10,
                Collections.<String>emptyList(),1,1));
        TransferTraceValidator.Result oneService=TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,stays,1,2),1);
        assertEquals(1,oneService.getMinimumMissingRecords());
    }

    @Test void everyKnownSurvivorNeedsARecordBeforeTheObservedHorizonCanFinish() {
        TransferContentionEngine e=engine();add(e,1,1,1,0);add(e,2,10,1,0);add(e,3,10,1,0);e.advance(2);
        List<TransferTraceEvent> rows=e.getTraceSnapshot().getEvents();assertEquals(9,rows.size());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                        new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows.subList(0,6),2,2),6)),
                () -> assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                        new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,rows.subList(0,4),4,2),4)));
        assertAllPrefixes(e.getTraceSnapshot());
        TransferContentionEngine allRemain=engine();
        for(int id=1;id<=3;id++)add(allRemain,id,10,1,0);
        allRemain.advance(2);List<TransferTraceEvent> starts=allRemain.getTraceSnapshot().getEvents().subList(0,3);
        assertThrows(IllegalArgumentException.class, () -> TransferTraceValidator.validate(
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,starts,2,2),3));
        assertFalse(TransferTraceValidator.validate(new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,
                starts,3,2),3).isCompleteCapture());
    }

    @Test void signedZeroAndStableActiveCaptureRemainValid() {
        TransferContentionEngine e=engine(); e.advance(-0.0); add(e,1,10,2,-0.0);
        TransferTraceValidator.Result result=valid(e.getTraceSnapshot());
        assertTrue(result.isCompleteCapture()); assertEquals(1,result.getOpenFlowCount());
    }

    @Test void acceptsDeterministicEngineScriptsAndEveryPrefixWithoutUsingEngineAsMetricOracle() {
        for(int seed=0;seed<8;seed++) {
            Random random=new Random(seed); TransferContentionEngine e=engine(); e.setEndpointCapacity("A",3);e.setEndpointCapacity("B",5);
            for(int i=0;i<6;i++) {
                double now=i*.25; e.advance(now); valid(e.getTraceSnapshot());
                String[][] paths={{"A"},{"B"},{"A","B"},{}};
                add(e,100+i,.5+random.nextInt(5),1+random.nextInt(8),now,paths[random.nextInt(paths.length)]);
                valid(e.getTraceSnapshot());
            }
            e.advance(40); valid(e.getTraceSnapshot()); assertAllPrefixes(e.getTraceSnapshot());
        }
    }

    @Test void acceptsBoundedScaledMultiResourceScriptsAndAllTheirPrefixes() {
        for(int seed=0;seed<12;seed++) for(double scale:new double[]{1e-9,1.0,1e9}) {
            Random random=new Random(seed);TransferContentionEngine e=engine();
            for(int resource=0;resource<3;resource++)e.setEndpointCapacity("R"+resource,scale*(1+random.nextInt(10)));
            double now=seed%2==0?0:1e6;e.advance(now);
            for(int i=0;i<10;i++) {
                now+=i%3==0?0:.125;e.advance(now);valid(e.getTraceSnapshot());
                List<String> path=new ArrayList<String>();
                for(int resource=0;resource<3;resource++)if(random.nextBoolean())path.add("R"+resource);
                if(!path.isEmpty()&&random.nextInt(5)==0)path.add(path.get(0));
                e.addTransfer(1000+i,scale*(.5+random.nextInt(12)),path,scale*(.5+random.nextInt(12)),now);
                valid(e.getTraceSnapshot());
            }
            e.advance(now+2000);valid(e.getTraceSnapshot());assertAllPrefixes(e.getTraceSnapshot());
        }
    }

    private static TransferContentionEngine engine(){return new TransferContentionEngine(BUDGET);}
    private static void add(TransferContentionEngine e,long id,double bytes,double rate,double time,String...resources){
        e.addTransfer(id,bytes,Arrays.asList(resources),rate,time);
    }
    private static TransferTraceSnapshot late(){
        TransferContentionEngine e=engine();e.setEndpointCapacity("A",100);add(e,1,500,100,0,"A");add(e,2,1000,100,0,"A");e.advance(20);return e.getTraceSnapshot();
    }
    private static TransferTraceSnapshot complete(List<TransferTraceEvent> rows,double time){return new TransferTraceSnapshot(TransferTraceSnapshot.Status.COMPLETE,rows,0,time);}
    private static TransferTraceValidator.Result valid(TransferTraceSnapshot t){return TransferTraceValidator.validate(t,BUDGET);}
    private static void invalid(TransferTraceSnapshot t){assertThrows(IllegalArgumentException.class,()->valid(t));}
    private static void assertAllPrefixes(TransferTraceSnapshot full){
        for(int i=1;i<full.getEvents().size();i++) {
            TransferTraceSnapshot prefix=new TransferTraceSnapshot(TransferTraceSnapshot.Status.TRUNCATED,
                    full.getEvents().subList(0,i),full.getEvents().size()-i,full.getEngineTime());
            TransferTraceValidator.Result result=TransferTraceValidator.validate(prefix,i);
            assertFalse(result.isCompleteCapture());assertEquals(i,result.getRecordCount());
        }
    }
    private static List<TransferTraceEvent> renumber(List<TransferTraceEvent> rows){
        List<TransferTraceEvent> result=new ArrayList<>();for(int i=0;i<rows.size();i++)result.add(tweak(rows.get(i),null,"sequence",new JsonPrimitive(i+1)));return result;
    }
    private static TransferTraceEvent tweak(TransferTraceEvent event,String object,String field,JsonElement value){
        JsonObject copy=JSON.toJsonTree(event).getAsJsonObject();JsonObject target=object==null?copy:copy.getAsJsonObject(object);
        target.add(field,value);return JSON.fromJson(copy,TransferTraceEvent.class);
    }
}
