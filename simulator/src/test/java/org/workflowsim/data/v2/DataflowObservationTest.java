package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

class DataflowObservationTest {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final DataLocation SOURCE=CoherentDataflowRuntime.SOURCE,A=DataLocation.vm(7),B=DataLocation.vm(42);
    @Test void uninitializedAndPlanOnlyStatesDoNotInventActualFabric(){
        CoherentDataflowRuntime r=CoherentDataflowRuntime.withStorage(true,2000,true);DataflowObservation empty=r.observe();assertEquals(DataflowObservation.Status.UNINITIALIZED,empty.getStatus());assertFalse(empty.isCompleteFileView());assertNull(empty.getFabric());assertNull(empty.getService());
        Task p=task(1,in("in",100),out("out",100));r.initializePlan(plan(p),Arrays.asList(A,B,SOURCE));DataflowObservation known=r.observe();assertEquals(DataflowObservation.Status.PLAN_ONLY,known.getStatus());assertFalse(known.isBound());assertNull(known.getFabric());assertNotNull(known.getFile(fid("in")).getReplica(SOURCE));assertTrue(known.getFile(fid("out")).getVisibleReplicas().isEmpty());assertEquals(0,known.getObservedThrough(),0);
    }
    @Test void resourceSharingAndRemainingBytesAreObservedWithoutAdvancement(){
        CoherentDataflowRuntime r=twoReaders(2000);r.advance(3);DataflowObservation view=r.observe();assertEquals(3,view.getObservedThrough(),0);assertEquals(3,view.getService().getObservedThrough(),0);assertEquals(2,view.getActiveCopies().size());
        for(DataflowObservation.CopyView c:view.getActiveCopies().values()){assertEquals(85,c.getService().getRemainingBytes(),0);assertEquals(5,c.getService().getRateBytesPerSecond(),0);assertEquals("INPUT",c.getPurpose());assertNull(view.getFile(fid("x")).getReplica(c.getDestination()));}
        assertEquals(0,new BigDecimal("10").compareTo(view.getService().getResources().get(DataTransferFabric.STORE_READ).getAssignedRateBytesPerSecond()));assertEquals(DataflowObservation.JobPhase.WAITING_FOR_INPUTS,view.getActiveJobs().get(10).getPhase());assertEquals(3,r.getCurrentTime(),0);
    }
    @Test void repeatReadsDoNotDrainReadinessOrRecordLifecycleEvents(){
        CoherentDataflowRuntime r=twoReaders(2000);r.advance(20);int before=r.captureStorageEvidence().getCapture().getEvents().size();String expected=JSON.toJson(r.observe());for(int i=0;i<10;i++)assertEquals(expected,JSON.toJson(r.observe()));assertEquals(before,r.captureStorageEvidence().getCapture().getEvents().size());assertEquals(Arrays.asList(10,20),r.drainReadyJobIds());
    }
    @Test void snapshotsAreDeeplyImmutableAndDetachedFromLaterProgress(){
        CoherentDataflowRuntime r=twoReaders(2000);r.advance(3);DataflowObservation before=r.observe();assertThrows(UnsupportedOperationException.class,()->before.getFiles().clear());assertThrows(UnsupportedOperationException.class,()->before.getActiveCopies().clear());assertThrows(UnsupportedOperationException.class,()->before.getFile(fid("x")).getVisibleReplicas().clear());assertThrows(UnsupportedOperationException.class,()->before.getActiveJobs().get(10).getPendingInputs().clear());assertThrows(UnsupportedOperationException.class,()->before.getActiveCopies().values().iterator().next().getService().getResources().clear());
        r.advance(20);assertEquals(2,before.getActiveCopies().size());assertEquals(85,before.getActiveCopies().values().iterator().next().getService().getRemainingBytes(),0);assertNull(before.getFile(fid("x")).getReplica(A));assertNotNull(r.observe().getFile(fid("x")).getReplica(A));assertTrue(r.observe().getActiveCopies().isEmpty());
    }
    @Test void sourceCommitAndTargetVisibilityHaveDifferentObservedPhases(){
        CoherentDataflowRuntime r=storeGate(false,2000);DataflowObservation waiting=r.observe();assertEquals(DataflowObservation.JobPhase.WAITING_FOR_STORE,waiting.getActiveJobs().get(20).getPhase());assertTrue(waiting.getActiveJobs().get(20).getWaitingStoreInputs().contains(fid("x")));assertTrue(waiting.getPendingOutputFiles().contains(fid("x")));assertEquals("OUTPUT",waiting.getActiveCopy(fid("x"),SOURCE).getPurpose());assertEquals(10,waiting.getActiveCopy(fid("x"),SOURCE).getOwnerJobId());assertFalse(waiting.getActiveJobs().containsKey(10));
        r.advance(11);DataflowObservation reading=r.observe();assertTrue(reading.getActiveJobs().get(20).getWaitingStoreInputs().isEmpty());assertEquals(DataflowObservation.JobPhase.WAITING_FOR_INPUTS,reading.getActiveJobs().get(20).getPhase());assertNotNull(reading.getFile(fid("x")).getReplica(SOURCE));assertNull(reading.getFile(fid("x")).getReplica(B));assertEquals("INPUT",reading.getActiveCopy(fid("x"),B).getPurpose());assertTrue(reading.getPendingOutputFiles().isEmpty());
        r.advance(21);assertEquals(DataflowObservation.JobPhase.READY_FOR_CPU,r.observe().getActiveJobs().get(20).getPhase());assertNull(waiting.getFile(fid("x")).getReplica(SOURCE));
    }
    @Test void sameVmHolderBeforeStoreCommitIsNotDataReady(){
        CoherentDataflowRuntime r=storeGate(true,2000);DataflowObservation view=r.observe();assertNotNull(view.getFile(fid("x")).getReplica(A));assertNull(view.getFile(fid("x")).getReplica(SOURCE));assertEquals(DataflowObservation.JobPhase.WAITING_FOR_STORE,view.getActiveJobs().get(20).getPhase());r.advance(11);assertEquals(DataflowObservation.JobPhase.READY_FOR_CPU,r.observe().getActiveJobs().get(20).getPhase());
    }
    @Test void effectivePredictionDoesNotPublishBeforeObservedSettlement(){
        Task t=task(1,in("tiny",.5));CoherentDataflowRuntime r=v2(2000,t);r.requestJob(10,one(1),7,0);assertEquals(.05,r.getNextCompletionTime(),0);assertNull(r.observe().getFile(fid("tiny")).getReplica(A));assertEquals(0,r.observe().getObservedThrough(),0);r.advance(.1);assertEquals(.1,r.observe().getFile(fid("tiny")).getReplica(A).getVisibleAt(),0);
    }
    @Test void scopedReadsDistinguishOutsideScopeFromKnownButUnavailable(){
        Task t=task(1,in("in",10),out("out",20));CoherentDataflowRuntime r=v2(2000,t);DataflowObservation scoped=r.observeFiles(oneFile("in"));assertFalse(scoped.isCompleteFileView());assertEquals(1,scoped.getFiles().size());assertThrows(IllegalArgumentException.class,()->scoped.getFile(fid("out")));assertTrue(r.observe().getFile(fid("out")).getVisibleReplicas().isEmpty());assertThrows(IllegalArgumentException.class,()->r.observeFiles(oneFile("unknown")));assertThrows(IllegalArgumentException.class,()->r.observeFiles(Arrays.asList((DataflowFilePlan.FileId)null)));
    }
    @Test void offTruncatedAndFullCaptureProduceIdenticalObservations(){
        String expected=null;for(int budget:new int[]{0,1,2000}){CoherentDataflowRuntime r=twoReaders(budget);r.advance(3);String actual=JSON.toJson(r.observe());if(expected==null)expected=actual;else assertEquals(expected,actual);}
    }
    @Test void failedAdvanceOrInputRequestLeavesObservationUntouched(){
        CoherentDataflowRuntime r=twoReaders(2000);r.advance(3);String before=JSON.toJson(r.observe());assertThrows(IllegalArgumentException.class,()->r.advance(Double.POSITIVE_INFINITY));assertEquals(before,JSON.toJson(r.observe()));assertThrows(IllegalStateException.class,()->r.requestJob(10,one(1),7,4));assertEquals(before,JSON.toJson(r.observe()));
    }
    @Test void originalV2CopyOwnerAndCpuPhasesAreExplicit(){
        Task t=task(1,in("x",10));CoherentDataflowRuntime r=v2(2000,t);r.requestJob(17,one(1),7,0);assertEquals(17,r.observe().getActiveCopy(fid("x"),A).getOwnerJobId());r.advance(1);r.drainReadyJobIds();r.cpuStarted(17,one(1),7,1);assertEquals(DataflowObservation.JobPhase.RUNNING,r.observe().getActiveJobs().get(17).getPhase());r.jobFinished(17,one(1),7,Collections.singletonList(true),2);assertTrue(r.observe().getActiveJobs().isEmpty());
    }
    @Test void zeroOutputIsVisibleMetadataWithoutAnActivePositiveCopy(){
        Task t=task(1,out("z",0));CoherentDataflowRuntime r=storage(true,2000,t);r.requestJob(10,one(1),7,0);r.drainReadyJobIds();r.cpuStarted(10,one(1),7,0);r.jobFinished(10,one(1),7,Collections.singletonList(true),1);DataflowObservation view=r.observe();assertTrue(view.getActiveCopies().isEmpty());assertTrue(view.getPendingOutputFiles().isEmpty());assertEquals(DataReplicaState.Acquisition.ZERO_BYTE_OUTPUT,view.getFile(fid("z")).getReplica(SOURCE).getAcquisition());
    }
    private static CoherentDataflowRuntime twoReaders(int budget){Task a=task(1,in("x",100)),b=task(2,in("x",100));CoherentDataflowRuntime r=storage(true,budget,a,b);r.requestJob(10,one(1),7,0);r.requestJob(20,one(2),42,0);return r;}
    private static CoherentDataflowRuntime storeGate(boolean local,int budget){Task p=task(1,out("x",100)),c=task(2,in("x",100));p.addChild(c);c.addParent(p);CoherentDataflowRuntime r=storage(true,budget,p,c);r.requestJob(10,one(1),7,0);r.drainReadyJobIds();r.cpuStarted(10,one(1),7,0);r.jobFinished(10,one(1),7,Collections.singletonList(true),1);r.requestJob(20,one(2),local?7:42,1);return r;}
    private static CoherentDataflowRuntime storage(boolean gate,int budget,Task...tasks){Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,100.0);caps.put(42,100.0);Map<Integer,Integer> hosts=new LinkedHashMap<>();hosts.put(7,10);hosts.put(42,20);DataTransferFabric f=DataTransferFabric.withStorage(caps,hosts,null,DataflowStorageSpec.of(30,.00001,.00001,.0001));CoherentDataflowRuntime r=CoherentDataflowRuntime.withStorage(true,budget,gate);r.initializePlan(plan(tasks),f.getLocations());r.bindFabric(f);return r;}
    private static CoherentDataflowRuntime v2(int budget,Task...tasks){DataTransferFabric f=DataTransferFabric.endpoints(Collections.singletonMap(7,10.0),Collections.singletonList("source"));CoherentDataflowRuntime r=new CoherentDataflowRuntime(true,budget);r.initializePlan(plan(tasks),f.getLocations());r.bindFabric(f);return r;}
    private static DataflowFilePlan plan(Task...tasks){Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task t:tasks)scopes.put(t.getCloudletId(),0);return DataflowFilePlan.capture(Arrays.asList(tasks),scopes);}
    private static java.util.List<Integer> one(int id){return Collections.singletonList(id);}
    private static java.util.List<DataflowFilePlan.FileId> oneFile(String name){return Collections.singletonList(fid(name));}
    private static DataflowFilePlan.FileId fid(String name){return DataflowFilePlan.FileId.of(0,name);}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem file:files)t.addFile(file);return t;}
    private static FileItem in(String name,double bytes){return file(name,bytes,Parameters.FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,Parameters.FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}
}
