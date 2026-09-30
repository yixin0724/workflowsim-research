package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

/** Authoritative visibility is driven by explicit observations, never an optional fluid trace. */
class DataReplicaStateTest {
    private static final DataLocation SOURCE=DataLocation.source("input-store"),SECOND=DataLocation.source("second-store");
    private static final DataLocation VM7=DataLocation.vm(7),VM42=DataLocation.vm(42),VM99=DataLocation.vm(99);

    @Test void locationsAreTypedAndStableWithoutParsingTheirLabels(){
        assertEquals(VM7,DataLocation.vm(7));assertEquals(VM7.hashCode(),DataLocation.vm(7).hashCode());
        assertNotEquals(VM7,DataLocation.source("VM:7"));assertNotEquals(VM7,"VM:7");assertNotEquals(VM7,null);
        assertEquals(DataLocation.Kind.VM,VM7.getKind());assertEquals(DataLocation.Kind.SOURCE,SOURCE.getKind());
        assertEquals(7,VM7.getVmId());assertEquals("input-store",SOURCE.getSourceId());assertTrue(VM7.compareTo(VM42)<0);assertTrue(VM42.compareTo(SOURCE)<0);
        assertTrue(DataLocation.source("a").compareTo(DataLocation.source("z"))<0);assertTrue(SOURCE.toString().contains("input-store"));
        assertThrows(IllegalStateException.class,SOURCE::getVmId);assertThrows(IllegalStateException.class,VM7::getSourceId);
        assertThrows(IllegalArgumentException.class,()->DataLocation.vm(-1));assertThrows(IllegalArgumentException.class,()->DataLocation.source(""));assertThrows(IllegalArgumentException.class,()->DataLocation.source(null));
    }

    @Test void onlyExternalFilesMayBeSeededAtKnownSources(){
        DataReplicaState state=state();DataReplicaState.Publication first=state.seedExternal(id("external"),SOURCE,0);
        assertTrue(first.isNewReplica());assertEquals(DataReplicaState.Acquisition.EXTERNAL_SEED,first.getReplica().getAcquisition());
        assertTrue(first.getReplica().getOrigin().isExternal());assertNull(first.getReplica().getOrigin().getJobAttemptId());
        assertEquals(SOURCE,first.getReplica().getOrigin().getLocation());assertEquals(0,first.getReplica().getVisibleAt(),0);
        assertFalse(state.seedExternal(id("external"),SOURCE,1).isNewReplica());assertEquals(0,state.getReplica(id("external"),SOURCE).getVisibleAt(),0);
        assertTrue(state.seedExternal(id("external"),SECOND,1).isNewReplica());
        assertThrows(IllegalArgumentException.class,()->state.seedExternal(id("p"),SOURCE,2));
        assertThrows(IllegalArgumentException.class,()->state.seedExternal(id("external"),VM7,2));
        assertEquals(1,state.getObservedThrough(),0);
    }

    @Test void failedAttemptPublishesNothingAndSuccessfulRetryUsesActualPlacement(){
        DataReplicaState state=state();assertTrue(state.recordTaskCompletion(1,10,VM7,false,1).isEmpty());
        assertNull(state.getReplica(id("p"),VM7));assertEquals(1,state.getCompletedTaskAttemptCount());
        List<DataReplicaState.Publication> published=state.recordTaskCompletion(1,11,VM42,true,2);assertEquals(2,published.size());
        DataReplicaState.Replica replica=state.getReplica(id("p"),VM42);assertEquals(DataReplicaState.Acquisition.TASK_OUTPUT,replica.getAcquisition());
        assertEquals(Integer.valueOf(1),replica.getOrigin().getProducerTaskId());assertEquals(Long.valueOf(11),replica.getOrigin().getJobAttemptId());assertEquals(VM42,replica.getOrigin().getLocation());
        assertEquals(2,replica.getVisibleAt(),0);assertNull(state.getReplica(id("p"),VM7));
        assertThrows(IllegalStateException.class,()->state.recordTaskCompletion(1,10,VM7,true,3));assertEquals(2,state.getObservedThrough(),0);
    }

    @Test void taskOutcomesWithinOneJobAreSeparateAndDuplicatesCannotRewriteProvenance(){
        DataReplicaState state=state();state.recordTaskCompletion(1,99,VM7,true,2);state.recordTaskCompletion(2,99,VM7,false,2);
        assertNotNull(state.getReplica(id("p"),VM7));assertNull(state.getReplica(id("q"),VM7));assertEquals(2,state.getCompletedTaskAttemptCount());
        assertTrue(state.recordTaskCompletion(1,99,VM7,true,2).isEmpty());assertEquals(2,state.getCompletedTaskAttemptCount());
        assertThrows(IllegalStateException.class,()->state.recordTaskCompletion(1,99,VM42,true,2));
        assertThrows(IllegalStateException.class,()->state.recordTaskCompletion(1,99,VM7,true,3));
        List<DataReplicaState.Publication> repeated=state.recordTaskCompletion(1,100,VM7,true,3);
        assertEquals(2,repeated.size());assertFalse(repeated.get(0).isNewReplica());assertEquals(2,state.getReplica(id("p"),VM7).getVisibleAt(),0);
        assertEquals(Long.valueOf(99),state.getReplica(id("p"),VM7).getOrigin().getJobAttemptId());
    }

    @Test void constituentTasksOfOneJobCannotClaimDifferentPlacementsOrCompletionObservations(){
        DataReplicaState state=state();state.recordTaskCompletion(1,99,VM7,true,2);
        assertThrows(IllegalStateException.class,()->state.recordTaskCompletion(2,99,VM42,true,2));
        assertNull(state.getReplica(id("q"),VM42));assertEquals(1,state.getCompletedTaskAttemptCount());assertEquals(2,state.getObservedThrough(),0);
        assertThrows(IllegalStateException.class,()->state.recordTaskCompletion(2,99,VM7,false,3));
        assertEquals(1,state.getCompletedTaskAttemptCount());assertEquals(2,state.getObservedThrough(),0);
        assertTrue(state.recordTaskCompletion(2,99,VM7,false,2).isEmpty());assertEquals(2,state.getCompletedTaskAttemptCount());
    }

    @Test void pendingCopiesAreNotVisibleAndSettleOnlyAtTheObservedCompletion(){
        assertEquals("PER_FILE_SETTLEMENT_OBSERVATION_V2",DataReplicaState.VISIBILITY_POLICY);
        DataReplicaState state=state();state.seedExternal(id("external"),SOURCE,0);
        DataReplicaState.CopyTicket ticket=state.admitCopy(id("external"),SOURCE,VM7,1);DataReplicaState.Snapshot pending=state.snapshot();
        assertEquals(1,ticket.getOrdinal());assertEquals(1,ticket.getReleaseTime(),0);assertEquals(.5,ticket.getFile().getBytes(),0);
        assertSame(ticket,state.getInFlight(id("external"),VM7));assertNull(state.getReplica(id("external"),VM7));assertEquals(1,state.getActiveCopyCount());
        assertThrows(IllegalStateException.class,()->state.admitCopy(id("external"),VM7,VM42,2));assertEquals(1,state.getObservedThrough(),0);
        state.advanceTo(5);assertNull(state.getReplica(id("external"),VM7));
        DataReplicaState.Publication completed=state.settleCopy(ticket,10);assertTrue(completed.isNewReplica());assertEquals(10,completed.getReplica().getVisibleAt(),0);
        assertEquals(DataReplicaState.Acquisition.COPY_SETTLEMENT,completed.getReplica().getAcquisition());assertEquals(Long.valueOf(1),completed.getReplica().getCopyOrdinal());
        assertEquals(SOURCE,completed.getReplica().getCopiedFrom());assertEquals(0,state.getActiveCopyCount());assertNull(state.getInFlight(id("external"),VM7));
        assertEquals(1,pending.getActiveCopies().size());assertNull(pending.getReplica(id("external"),VM7),"old snapshot cannot gain a future replica");
    }

    @Test void eachFileBecomesVisibleBeforeOtherFanInCopiesFinish(){
        DataReplicaState state=state();state.recordTaskCompletion(1,1,VM7,true,0);state.recordTaskCompletion(2,2,VM42,true,0);
        DataReplicaState.CopyTicket fast=state.admitCopy(id("p"),VM7,VM99,1),slow=state.admitCopy(id("q"),VM42,VM99,1);
        state.settleCopy(fast,2);assertNotNull(state.getReplica(id("p"),VM99));assertNull(state.getReplica(id("q"),VM99));assertSame(slow,state.getInFlight(id("q"),VM99));
        // Another consumer may use the arrived p replica while q remains pending.
        DataReplicaState.CopyTicket next=state.admitCopy(id("p"),VM99,VM42,2);assertEquals(VM99,next.getSourceReplica().getLocation());
        state.settleCopy(slow,5);assertEquals(5,state.getReplica(id("q"),VM99).getVisibleAt(),0);
    }

    @Test void oneActiveCopyPerFileAndDestinationCanBeJoinedWithoutDuplicateAdmission(){
        DataReplicaState state=state();state.seedExternal(id("external"),SOURCE,0);
        DataReplicaState.CopyTicket a=state.admitCopy(id("external"),SOURCE,VM7,1);
        assertSame(a,state.getInFlight(id("external"),VM7));
        assertThrows(IllegalStateException.class,()->state.admitCopy(id("external"),SOURCE,VM7,2));assertEquals(1,state.getAdmittedCopyCount());assertEquals(1,state.getObservedThrough(),0);
        DataReplicaState.CopyTicket b=state.admitCopy(id("external"),SOURCE,VM42,2);assertNotSame(a,b);assertEquals(2,state.getAdmittedCopyCount());assertEquals(2,state.getActiveCopyCount());
    }

    @Test void zeroByteReferenceStillNeedsAVisibleSourceButNeverAllocatesAPositiveCopy(){
        DataReplicaState state=state();assertThrows(IllegalStateException.class,()->state.resolveZeroReference(id("zero"),SOURCE,VM7,1));assertEquals(0,state.getObservedThrough(),0);
        state.seedExternal(id("zero"),SOURCE,0);assertThrows(IllegalArgumentException.class,()->state.admitCopy(id("zero"),SOURCE,VM7,0));
        DataReplicaState.Publication zero=state.resolveZeroReference(id("zero"),SOURCE,VM7,0);
        assertTrue(zero.isNewReplica());assertEquals(DataReplicaState.Acquisition.ZERO_BYTE_REFERENCE,zero.getReplica().getAcquisition());assertNull(zero.getReplica().getCopyOrdinal());
        assertFalse(state.resolveZeroReference(id("zero"),VM7,VM7,0).isNewReplica());assertEquals(0,state.getAdmittedCopyCount());assertEquals(0,state.getActiveCopyCount());
        state.seedExternal(id("external"),SOURCE,0);assertThrows(IllegalArgumentException.class,()->state.resolveZeroReference(id("external"),SOURCE,VM7,1));
        assertEquals(0,state.getObservedThrough(),0);
    }

    @Test void positiveLocalInputsAreNotAdmittedAgainAndInputsSurviveComputeFailure(){
        DataReplicaState state=state();state.seedExternal(id("external"),SOURCE,0);state.settleCopy(state.admitCopy(id("external"),SOURCE,VM7,0),1);
        assertThrows(IllegalStateException.class,()->state.admitCopy(id("external"),VM7,VM7,1));
        assertThrows(IllegalStateException.class,()->state.admitCopy(id("external"),SOURCE,VM7,1));
        state.recordTaskCompletion(3,50,VM7,false,2);assertNotNull(state.getReplica(id("external"),VM7));assertEquals(1,state.getAdmittedCopyCount());
    }

    @Test void copyChainKeepsRootProducerAttemptAndImmediateAcquisitionSeparate(){
        DataReplicaState state=state();state.recordTaskCompletion(1,88,VM7,true,0);
        state.settleCopy(state.admitCopy(id("p"),VM7,VM42,1),2);DataReplicaState.CopyTicket next=state.admitCopy(id("p"),VM42,VM99,2);
        state.settleCopy(next,3);DataReplicaState.Replica replica=state.getReplica(id("p"),VM99);
        assertEquals(Integer.valueOf(1),replica.getOrigin().getProducerTaskId());assertEquals(Long.valueOf(88),replica.getOrigin().getJobAttemptId());assertEquals(VM7,replica.getOrigin().getLocation());
        assertEquals(0,replica.getOrigin().getObservedAt(),0);assertEquals(VM42,replica.getCopiedFrom());assertEquals(Long.valueOf(2),replica.getCopyOrdinal());assertEquals(3,replica.getVisibleAt(),0);
    }

    @Test void earlierOutputAtDestinationIsNotOverwrittenByAStillCompletingCopy(){
        DataReplicaState state=state();state.recordTaskCompletion(1,10,VM7,true,1);DataReplicaState.CopyTicket copy=state.admitCopy(id("p"),VM7,VM42,2);
        state.recordTaskCompletion(1,11,VM42,true,3);DataReplicaState.Publication result=state.settleCopy(copy,4);
        assertFalse(result.isNewReplica());assertEquals(3,result.getReplica().getVisibleAt(),0);assertEquals(Long.valueOf(11),result.getReplica().getOrigin().getJobAttemptId());
        assertEquals(0,state.getActiveCopyCount());assertEquals(4,state.getObservedThrough(),0);
    }

    @Test void rejectsForeignRepeatedAndNonAdvancingSettlementsAtomically(){
        DataReplicaState a=state(),b=state();a.seedExternal(id("external"),SOURCE,0);b.seedExternal(id("external"),SOURCE,0);
        DataReplicaState.CopyTicket ticket=a.admitCopy(id("external"),SOURCE,VM7,1);
        assertThrows(IllegalArgumentException.class,()->b.settleCopy(ticket,2));assertEquals(0,b.getObservedThrough(),0);
        assertThrows(IllegalArgumentException.class,()->a.settleCopy(ticket,1));assertEquals(1,a.getActiveCopyCount());assertEquals(1,a.getObservedThrough(),0);
        a.settleCopy(ticket,2);assertThrows(IllegalStateException.class,()->a.settleCopy(ticket,3));assertEquals(2,a.getObservedThrough(),0);
        assertThrows(IllegalArgumentException.class,()->a.settleCopy(null,3));
    }

    @Test void positiveAdmissionCannotCreateATicketWithNoFiniteFutureObservation(){
        DataReplicaState state=state();state.seedExternal(id("external"),SOURCE,0);
        assertThrows(IllegalArgumentException.class,()->state.admitCopy(id("external"),SOURCE,VM7,Double.MAX_VALUE));
        assertEquals(0,state.getAdmittedCopyCount());assertEquals(0,state.getActiveCopyCount());assertEquals(0,state.getObservedThrough(),0);
        state.seedExternal(id("zero"),SOURCE,Double.MAX_VALUE);
        assertTrue(state.resolveZeroReference(id("zero"),SOURCE,VM7,Double.MAX_VALUE).isNewReplica(),"zero references need no later positive-service event");
    }

    @Test void snapshotIsImmutableAndLocationInputCollectionsAreCopied(){
        List<DataLocation> locations=new ArrayList<>(Arrays.asList(SOURCE,VM42,VM7));DataReplicaState state=new DataReplicaState(plan(),locations);locations.clear();
        state.seedExternal(id("external"),SOURCE,0);DataReplicaState.Snapshot snapshot=state.snapshot();
        assertEquals(Arrays.asList(VM7,VM42,SOURCE),snapshot.getLocations());assertEquals(1,snapshot.getReplicas(id("external")).size());
        state.admitCopy(id("external"),SOURCE,VM7,1);assertTrue(snapshot.getActiveCopies().isEmpty());assertEquals(0,snapshot.getObservedThrough(),0);
        assertThrows(UnsupportedOperationException.class,()->snapshot.getLocations().clear());assertThrows(UnsupportedOperationException.class,()->snapshot.getReplicas(id("external")).clear());
        assertThrows(UnsupportedOperationException.class,()->snapshot.getActiveCopies().clear());assertThrows(IllegalArgumentException.class,()->snapshot.getReplicas(id("unknown")));
        assertThrows(IllegalArgumentException.class,()->snapshot.getReplica(id("external"),VM99));
    }

    @Test void validationErrorsDoNotAdvanceClockOrConsumeAdmissions(){
        DataReplicaState state=state();state.seedExternal(id("external"),SOURCE,1);
        for(double bad:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,.5}){
            assertThrows(IllegalArgumentException.class,()->state.advanceTo(bad));assertThrows(IllegalArgumentException.class,()->state.admitCopy(id("external"),SOURCE,VM7,bad));
        }
        assertThrows(IllegalArgumentException.class,()->state.recordTaskCompletion(404,10,VM7,true,3));
        assertThrows(IllegalArgumentException.class,()->state.recordTaskCompletion(1,-1,VM7,true,3));assertThrows(IllegalArgumentException.class,()->state.recordTaskCompletion(1,10,SOURCE,true,3));
        assertThrows(IllegalArgumentException.class,()->state.admitCopy(id("external"),DataLocation.vm(404),VM7,3));
        assertThrows(IllegalArgumentException.class,()->state.getReplica(id("missing"),SOURCE));assertThrows(IllegalArgumentException.class,()->state.getInFlight(id("external"),DataLocation.vm(404)));
        assertEquals(1,state.getObservedThrough(),0);assertEquals(0,state.getAdmittedCopyCount());assertEquals(0,state.getCompletedTaskAttemptCount());
    }

    @Test void invalidCatalogConstructionAndUnknownIdentityAreNotSilentlyAccepted(){
        assertThrows(IllegalArgumentException.class,()->new DataReplicaState(null,Arrays.asList(SOURCE)));
        assertThrows(IllegalArgumentException.class,()->new DataReplicaState(plan(),null));assertThrows(IllegalArgumentException.class,()->new DataReplicaState(plan(),Arrays.asList(SOURCE,SOURCE)));
        assertThrows(IllegalArgumentException.class,()->new DataReplicaState(plan(),Arrays.asList(SOURCE,null)));
        DataReplicaState state=state();assertThrows(IllegalArgumentException.class,()->state.seedExternal(id("external"),DataLocation.source("unknown"),1));
        assertThrows(IllegalArgumentException.class,()->state.seedExternal(id("unknown"),SOURCE,1));assertThrows(IllegalArgumentException.class,()->state.getReplica(null,SOURCE));
        assertEquals(0,state.getObservedThrough(),0);
    }

    @Test void equallyNamedFilesFromTwoInputsDoNotBorrowEachOthersReplicas(){
        Task a=task(1,in("same",1)),b=task(2,in("same",2));Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(1,0);scopes.put(2,1);
        DataReplicaState state=new DataReplicaState(DataflowFilePlan.capture(Arrays.asList(a,b),scopes),Arrays.asList(SOURCE,VM7));
        state.seedExternal(DataflowFilePlan.FileId.of(0,"same"),SOURCE,0);
        assertThrows(IllegalStateException.class,()->state.admitCopy(DataflowFilePlan.FileId.of(1,"same"),SOURCE,VM7,1));
        assertNull(state.getReplica(DataflowFilePlan.FileId.of(1,"same"),SOURCE));
        assertEquals(1,state.admitCopy(DataflowFilePlan.FileId.of(0,"same"),SOURCE,VM7,1).getFile().getBytes(),0);
    }

    private static DataReplicaState state(){return new DataReplicaState(plan(),Arrays.asList(SOURCE,SECOND,VM7,VM42,VM99));}
    private static DataflowFilePlan plan(){Task p=task(1,out("p",4),out("p2",1)),q=task(2,out("q",2)),c=task(3,in("p",4),in("q",2),in("external",.5),in("zero",0));
        p.addChild(c);c.addParent(p);q.addChild(c);c.addParent(q);Map<Integer,Integer> scopes=new LinkedHashMap<>();for(int id=1;id<=3;id++)scopes.put(id,0);return DataflowFilePlan.capture(Arrays.asList(p,q,c),scopes);}
    private static Task task(int id,FileItem...files){Task task=new Task(id,1000);for(FileItem file:files)task.addFile(file);return task;}
    private static FileItem in(String name,double bytes){return file(name,bytes,FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,FileType type){FileItem item=new FileItem(name,bytes);item.setType(type);return item;}
    private static DataflowFilePlan.FileId id(String name){return DataflowFilePlan.FileId.of(0,name);}
}
