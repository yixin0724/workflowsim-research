package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/** Observable invariants for active/terminal splitting; deliberately no timing thresholds. */
class RuntimeHistoryIsolationTest {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final DataLocation A=DataLocation.vm(7),B=DataLocation.vm(42),SOURCE=CoherentDataflowRuntime.SOURCE;
    @Test void terminalPreparationIdentityAndLatencySurviveLongerHistories(){
        List<Task> tasks=new ArrayList<>();for(int i=1;i<=128;i++){Task task=task(i);if(i>1)edge(tasks.get(i-2),task);tasks.add(task);}CoherentDataflowRuntime r=runtime(false,0,100,100,100,100,tasks.toArray(new Task[0]));List<CoherentDataflowRuntime.Preparation> retained=new ArrayList<>();
        for(int i=0;i<tasks.size();i++){double now=2.0*i;CoherentDataflowRuntime.Preparation p=r.requestJob(i,one(i+1),7,now);retained.add(p);r.drainReadyJobIds();r.cpuStarted(i,one(i+1),7,now);r.jobFinished(i,one(i+1),7,success(true),now+1);assertTrue(r.isQuiescent());assertTrue(r.observeFiles(Collections.<DataflowFilePlan.FileId>emptyList()).getActiveJobs().isEmpty());}
        for(int i=0;i<retained.size();i++){assertSame(retained.get(i),r.getPreparation(i));assertEquals(0,r.getObservedInputPreparationSeconds(i),0);}
        String before=state(r);assertThrows(IllegalStateException.class,()->r.requestJob(0,one(1),7,300));assertThrows(IllegalStateException.class,()->r.cpuStarted(0,one(1),7,300));assertThrows(IllegalStateException.class,()->r.jobFinished(0,one(1),7,success(true),300));assertEquals(before,state(r));assertThrows(IllegalStateException.class,()->r.getPreparation(999));
    }
    @Test void terminalFailureDoesNotSatisfyControlButRetrySuccessDoes(){
        Task p=task(1,out("x",0)),c=task(2,in("x",0));edge(p,c);CoherentDataflowRuntime r=runtime(true,2000,100,100,100,100,p,c);prepareCpu(r,10,1,7,0);CoherentDataflowRuntime.Preparation failed=r.getPreparation(10);r.jobFinished(10,one(1),7,success(false),1);String before=state(r);assertThrows(IllegalStateException.class,()->r.requestJob(20,one(2),42,1));assertEquals(before,state(r));assertTrue(r.observe().getFile(id("x")).getVisibleReplicas().isEmpty());prepareCpu(r,11,1,7,1);r.jobFinished(11,one(1),7,success(true),2);prepareCpu(r,20,2,42,2);r.jobFinished(20,one(2),42,success(true),3);assertSame(failed,r.getPreparation(10));assertEquals(0,r.getObservedInputPreparationSeconds(11),0);assertNotNull(r.observe().getFile(id("x")).getReplica(SOURCE));assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }
    @Test void outputFailureAfterEarlierPublicationLeavesNoTerminalOrReplicaHistory(){
        for(int budget:new int[]{0,1,2000}){Task p=task(1,out("a",1e100),out("z",Double.MIN_VALUE));CoherentDataflowRuntime r=runtime(true,budget,1e100,1e100,1e100,1e100,p);prepareCpu(r,10,1,7,0);String before=state(r);CoherentDataflowRuntime.Preparation preparation=r.getPreparation(10);assertThrows(IllegalArgumentException.class,()->r.jobFinished(10,one(1),7,success(true),1));assertEquals(before,state(r));assertEquals(DataflowObservation.JobPhase.RUNNING,r.observe().getActiveJobs().get(10).getPhase());assertTrue(r.observe().getFile(id("a")).getVisibleReplicas().isEmpty());assertTrue(r.observe().getFile(id("z")).getVisibleReplicas().isEmpty());assertEquals(0,r.getAdmittedCopyCount());r.jobFinished(10,one(1),7,success(false),1);assertSame(preparation,r.getPreparation(10));assertTrue(r.isQuiescent());}
    }
    @Test void failedDeferredReadDoesNotPublishStoreOrChangeTerminalParent(){
        for(int budget:new int[]{0,1,2000}){Task p=task(1,out("x",1)),c=task(2,in("x",1));edge(p,c);CoherentDataflowRuntime r=runtime(true,budget,1e16,1e16,1,1e16,p,c);prepareCpu(r,10,1,7,0);CoherentDataflowRuntime.Preparation parent=r.getPreparation(10);r.jobFinished(10,one(1),7,success(true),1);r.requestJob(20,one(2),42,1);String before=state(r);assertThrows(IllegalArgumentException.class,()->r.advance(2));assertEquals(before,state(r));assertSame(parent,r.getPreparation(10));assertEquals(1,r.getActiveCopyCount());assertNull(r.observe().getFile(id("x")).getReplica(SOURCE));assertEquals(DataflowObservation.JobPhase.WAITING_FOR_STORE,r.observe().getActiveJobs().get(20).getPhase());}
    }
    @Test void archivedProducerDoesNotMakePendingOutputQuiescent(){
        Task p=task(1,out("x",100));CoherentDataflowRuntime r=runtime(true,2000,100,20,10,100,p);prepareCpu(r,10,1,7,0);r.jobFinished(10,one(1),7,success(true),1);DataflowObservation observed=r.observe();assertTrue(observed.getActiveJobs().isEmpty());assertFalse(r.isQuiescent());assertEquals(1,observed.getPendingOutputFiles().size());assertEquals(1,observed.getActiveCopies().size());r.advance(11);assertTrue(r.isQuiescent());assertTrue(r.observe().getPendingOutputFiles().isEmpty());assertEquals(1,observed.getPendingOutputFiles().size());assertEquals(0,r.getPreparation(10).getReferenceCount());
    }
    @Test void terminalHistoryDoesNotChangeSameObservationWaiterOrder(){
        Task p=task(1,out("x",100)),a=task(2,in("x",100)),b=task(3,in("x",100));edge(p,a);edge(p,b);CoherentDataflowRuntime r=runtime(true,2000,100,20,10,100,p,a,b);prepareCpu(r,10,1,7,0);r.jobFinished(10,one(1),7,success(true),1);r.requestJob(99,one(2),42,1);r.requestJob(1,one(3),42,1);r.advance(11);assertEquals(2,r.getAdmittedCopyCount());r.advance(16);assertEquals(Arrays.asList(99,1),r.drainReadyJobIds());CoherentDataflowRuntime.Preparation joined=r.getPreparation(1);assertEquals(1,joined.getJoinedCopies());r.cpuStarted(99,one(2),42,16);r.jobFinished(99,one(2),42,success(true),17);r.cpuStarted(1,one(3),42,17);r.jobFinished(1,one(3),42,success(true),18);assertSame(joined,r.getPreparation(1));assertEquals(15,r.getObservedInputPreparationSeconds(1),0);assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }
    @Test void activeObservationSnapshotDoesNotFollowLaterTermination(){
        Task p=task(1);CoherentDataflowRuntime r=runtime(false,2000,100,100,100,100,p);prepareCpu(r,10,1,7,0);DataflowObservation before=r.observe();r.jobFinished(10,one(1),7,success(true),1);assertEquals(DataflowObservation.JobPhase.RUNNING,before.getActiveJobs().get(10).getPhase());assertTrue(r.observe().getActiveJobs().isEmpty());assertTrue(r.isQuiescent());assertEquals(0,r.getObservedInputPreparationSeconds(10),0);
    }
    private static String state(CoherentDataflowRuntime r){return JSON.toJson(r.observe())+JSON.toJson(r.isStorageVersion()?r.captureStorageEvidence():r.captureEvidence());}
    private static void prepareCpu(CoherentDataflowRuntime r,int job,int task,int vm,double now){r.requestJob(job,one(task),vm,now);assertTrue(r.drainReadyJobIds().contains(job));r.cpuStarted(job,one(task),vm,now);}
    private static CoherentDataflowRuntime runtime(boolean storage,int budget,double vm,double read,double write,double nic,Task...tasks){Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,vm);caps.put(42,vm);DataTransferFabric f;if(storage){Map<Integer,Integer> hosts=new LinkedHashMap<>();hosts.put(7,10);hosts.put(42,20);f=DataTransferFabric.withStorage(caps,hosts,null,DataflowStorageSpec.of(30,read/1e6,write/1e6,nic/1e6));}else f=DataTransferFabric.endpoints(caps,Collections.singletonList("source"));Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task t:tasks)scopes.put(t.getCloudletId(),0);CoherentDataflowRuntime r=storage?CoherentDataflowRuntime.withStorage(true,budget,true):new CoherentDataflowRuntime(true,budget);r.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks),scopes),f.getLocations());r.bindFabric(f);return r;}
    private static List<Integer> one(int id){return Collections.singletonList(id);}private static List<Boolean> success(boolean value){return Collections.singletonList(value);}private static DataflowFilePlan.FileId id(String name){return DataflowFilePlan.FileId.of(0,name);}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem f:files)t.addFile(f);return t;}private static FileItem in(String name,double bytes){return file(name,bytes,Parameters.FileType.INPUT);}private static FileItem out(String name,double bytes){return file(name,bytes,Parameters.FileType.OUTPUT);}private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}private static void edge(Task a,Task b){a.addChild(b);b.addParent(a);}
}
