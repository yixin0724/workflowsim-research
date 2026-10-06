package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

class StorageDataflowRuntimeTest {
    private static final DataLocation STORE=CoherentDataflowRuntime.SOURCE;
    @Test void boundedInputAndUnusedOutputDrainHaveSeparateCpuAndStorageEnds(){
        Task a=task(1,in("input",100),out("sink",300));CoherentDataflowRuntime r=runtime(true,2000,100,10,20,100,a);
        r.requestJob(0,ids(1),0,0);r.advance(10);assertEquals(ids(0),r.drainReadyJobIds());r.cpuStarted(0,ids(1),0,10);r.jobFinished(0,ids(1),0,Arrays.asList(true),11);
        assertFalse(r.isQuiescent());assertEquals(26,r.getNextCompletionTime(),0);assertNull(r.getReplicaSnapshot().getReplica(fid("sink"),STORE));r.advance(26);assertTrue(r.isQuiescent());assertEquals(26,r.getReplicaSnapshot().getReplica(fid("sink"),STORE).getVisibleAt(),0);assertEquals(2,r.getAdmittedCopyCount());
    }
    @Test void sharedStoreCommitGatesEvenSameVmLocalOutputs(){
        Task p=task(1,out("x",100)),c=task(2,in("x",100));edge(p,c);CoherentDataflowRuntime r=runtime(true,2000,100,20,10,100,p,c);produce(r,0,1,0,0,1,true);
        r.requestJob(1,ids(2),0,1);assertTrue(r.drainReadyJobIds().isEmpty());assertThrows(IllegalStateException.class,()->r.cpuStarted(1,ids(2),0,2));assertThrows(IllegalStateException.class,()->r.getPreparation(1));
        r.advance(11);assertEquals(ids(1),r.drainReadyJobIds());assertEquals(0,r.getPreparation(1).getIsolatedSeconds(),0);assertEquals(10,r.getObservedInputPreparationSeconds(1),0);r.cpuStarted(1,ids(2),0,11);r.jobFinished(1,ids(2),0,Arrays.asList(true),12);assertTrue(r.isQuiescent());
        assertEquals(1,count(r,FileLifecycleEvent.Type.INPUT_WAITING_FOR_STORE));assertEquals(1,r.getAdmittedCopyCount());
    }
    @Test void sourceCommitStartsAReadRatherThanDirectlyReleasingCpu(){
        Task p=task(1,out("x",100)),c=task(2,in("x",100));edge(p,c);CoherentDataflowRuntime r=runtime(true,2000,100,20,10,100,p,c);produce(r,0,1,0,0,1,true);r.requestJob(1,ids(2),1,1);
        r.advance(11);assertTrue(r.drainReadyJobIds().isEmpty());assertEquals(16,r.getNextCompletionTime(),0);assertThrows(IllegalStateException.class,()->r.cpuStarted(1,ids(2),1,11));r.advance(16);assertEquals(ids(1),r.drainReadyJobIds());assertEquals(5,r.getPreparation(1).getIsolatedSeconds(),0);assertEquals(15,r.getObservedInputPreparationSeconds(1),0);
        List<FileLifecycleEvent> events=r.captureStorageEvidence().getCapture().getEvents();JsonObject read=events.stream().filter(e->e.getType()==FileLifecycleEvent.Type.COPY_ADMITTED&&e.getPayload().get("purpose").getAsString().equals("INPUT")).findFirst().get().getPayload();assertEquals("SOURCE",read.getAsJsonObject("sourceReplica").getAsJsonObject("location").get("kind").getAsString());assertEquals(1,read.getAsJsonObject("destination").get("vmId").getAsInt());
    }
    @Test void localPolicyCanReadPeersWhileMandatoryWritebackContinues(){
        Task p=task(1,out("x",100)),c=task(2,in("x",100));edge(p,c);CoherentDataflowRuntime r=runtime(false,2000,100,100,1,100,p,c);produce(r,0,1,0,0,1,true);r.requestJob(1,ids(2),1,1);r.advance(3);
        assertEquals(ids(1),r.drainReadyJobIds());assertNull(r.getReplicaSnapshot().getReplica(fid("x"),STORE));r.cpuStarted(1,ids(2),1,3);r.jobFinished(1,ids(2),1,Arrays.asList(true),4);assertFalse(r.isQuiescent());r.advance(102);assertTrue(r.isQuiescent());
    }
    @Test void zeroOutputCommitsMetadataWithoutPositiveService(){
        Task p=task(1,out("zero",0));CoherentDataflowRuntime r=runtime(true,2000,100,10,10,100,p);produce(r,0,1,0,0,1,true);
        assertEquals(0,r.getAdmittedCopyCount());assertTrue(r.isQuiescent());assertEquals(DataReplicaState.Acquisition.ZERO_BYTE_OUTPUT,r.getReplicaSnapshot().getReplica(fid("zero"),STORE).getAcquisition());assertEquals(1,count(r,FileLifecycleEvent.Type.OUTPUT_RESOLVED));
    }
    @Test void failedAttemptsPublishNothingAndRetryKeepsItsRealOrigin(){
        Task p=task(1,out("x",100));CoherentDataflowRuntime r=runtime(true,2000,100,10,10,100,p);produce(r,0,1,0,0,1,false);assertEquals(0,r.getAdmittedCopyCount());assertNull(r.getReplicaSnapshot().getReplica(fid("x"),STORE));
        produce(r,1,1,1,2,3,true);r.advance(13);assertEquals(Long.valueOf(1),r.getReplicaSnapshot().getReplica(fid("x"),STORE).getOrigin().getJobAttemptId());assertEquals(DataLocation.vm(1),r.getReplicaSnapshot().getReplica(fid("x"),STORE).getOrigin().getLocation());
    }
    @Test void deferredSameDestinationInputsJoinOneReadAfterStoreCommit(){
        Task p=task(1,out("x",100)),a=task(2,in("x",100)),b=task(3,in("x",100));edge(p,a);edge(p,b);CoherentDataflowRuntime r=runtime(true,2000,100,20,10,100,p,a,b);produce(r,0,1,0,0,1,true);r.requestJob(1,ids(2),1,1);r.requestJob(2,ids(3),1,1);r.advance(11);assertEquals(2,r.getAdmittedCopyCount());r.advance(16);assertEquals(Arrays.asList(1,2),r.drainReadyJobIds());assertEquals(1,r.getPreparation(1).getNewCopies());assertEquals(1,r.getPreparation(2).getJoinedCopies());
    }
    @Test void impossibleOutputDurationRollsBackCpuFinishPublicationAndEvidence(){
        Task p=task(1,out("huge",1e200));CoherentDataflowRuntime r=runtime(true,2000,1e200,1,1e-200,1e200,p);r.requestJob(0,ids(1),0,0);r.drainReadyJobIds();r.cpuStarted(0,ids(1),0,0);int records=r.captureStorageEvidence().getCapture().getEvents().size();
        assertThrows(IllegalArgumentException.class,()->r.jobFinished(0,ids(1),0,Arrays.asList(true),1));assertEquals(0,r.getCurrentTime(),0);assertNull(r.getReplicaSnapshot().getReplica(fid("huge"),DataLocation.vm(0)));assertEquals(records,r.captureStorageEvidence().getCapture().getEvents().size());assertEquals(0,r.getAdmittedCopyCount());
    }
    @Test void failedDeferredReadAdmissionRollsBackTheStoreSettlementToo(){
        Task p=task(1,out("huge",1e200)),c=task(2,in("huge",1e200));edge(p,c);CoherentDataflowRuntime r=runtime(true,2000,1e200,1e-200,1e200,1e200,p,c);produce(r,0,1,0,0,1,true);r.requestJob(1,ids(2),1,1);int records=r.captureStorageEvidence().getCapture().getEvents().size();double next=r.getNextCompletionTime();
        assertThrows(IllegalArgumentException.class,()->r.advance(next));assertEquals(1,r.getCurrentTime(),0);assertNull(r.getReplicaSnapshot().getReplica(fid("huge"),STORE));assertEquals(1,r.getActiveCopyCount());assertEquals(records,r.captureStorageEvidence().getCapture().getEvents().size());assertTrue(r.drainReadyJobIds().isEmpty());
    }
    @Test void captureModesDoNotChangeStoragePhysicsAndV2CannotEncodeIt(){
        for(int budget:new int[]{0,1,2000}){Task p=task(1,out("x",100));CoherentDataflowRuntime r=runtime(true,budget,100,10,10,100,p);produce(r,0,1,0,0,1,true);r.advance(11);assertTrue(r.isQuiescent());assertEquals(11,r.getReplicaSnapshot().getReplica(fid("x"),STORE).getVisibleAt(),0);assertNull(r.captureEvidence());if(budget==0)assertNull(r.captureStorageEvidence());else assertThrows(IllegalArgumentException.class,()->FileLifecycleCodec.document(r.captureStorageEvidence().getCapture()));}
    }
    private static int count(CoherentDataflowRuntime r,FileLifecycleEvent.Type type){return (int)r.captureStorageEvidence().getCapture().getEvents().stream().filter(e->e.getType()==type).count();}
    private static void produce(CoherentDataflowRuntime r,int job,int task,int vm,double start,double finish,boolean success){r.requestJob(job,ids(task),vm,start);r.drainReadyJobIds();r.cpuStarted(job,ids(task),vm,start);r.jobFinished(job,ids(task),vm,Arrays.asList(success),finish);}
    private static CoherentDataflowRuntime runtime(boolean storeInputs,int budget,double vmRate,double read,double write,double nic,Task...tasks){Map<Integer,Integer> scopes=new LinkedHashMap<>(),hosts=new LinkedHashMap<>();Map<Integer,Double> caps=new LinkedHashMap<>();for(Task t:tasks)scopes.put(t.getCloudletId(),0);for(int i=0;i<2;i++){hosts.put(i,i);caps.put(i,vmRate);}DataTransferFabric f=DataTransferFabric.withStorage(caps,hosts,null,DataflowStorageSpec.of(0,read/1e6,write/1e6,nic/1e6));CoherentDataflowRuntime r=CoherentDataflowRuntime.withStorage(true,budget,storeInputs);r.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks),scopes),f.getLocations());r.bindFabric(f);return r;}
    private static List<Integer> ids(int id){return Collections.singletonList(id);}
    private static DataflowFilePlan.FileId fid(String name){return DataflowFilePlan.FileId.of(0,name);}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem f:files)t.addFile(f);return t;}
    private static FileItem in(String name,double bytes){return file(name,bytes,Parameters.FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,Parameters.FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}
    private static void edge(Task a,Task b){a.addChild(b);b.addParent(a);}
}
