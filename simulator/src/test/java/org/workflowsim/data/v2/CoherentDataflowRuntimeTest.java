package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

/** Coordinator checks precede the real Kernel end-to-end test; no optional trace drives state. */
class CoherentDataflowRuntimeTest {
    @Test void fastFanInFileIsVisibleAndReusableBeforeSlowSiblingCompletes(){
        CoherentDataflowRuntime r=runtime(true,1000,fanin());produce(r,0,1,7,0,1,true);
        r.requestJob(1,Arrays.asList(2),99,1);r.requestJob(3,Arrays.asList(4),99,1.5);
        assertEquals(2,r.getActiveCopyCount());assertTrue(r.drainReadyJobIds().isEmpty());r.advance(3);
        assertNotNull(r.getReplicaSnapshot().getReplica(id("fast"),DataLocation.vm(99)));assertNull(r.getReplicaSnapshot().getReplica(id("slow"),DataLocation.vm(99)));
        assertEquals(Arrays.asList(3),r.drainReadyJobIds());
        r.requestJob(2,Arrays.asList(3),42,3);assertEquals(3,r.getAdmittedCopyCount());
        FileLifecycleEvent admission=last(r,FileLifecycleEvent.Type.COPY_ADMITTED);
        assertEquals(99,admission.getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());
        assertEquals(7,admission.getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("origin").getAsJsonObject("location").get("vmId").getAsInt());
        r.advance(4);assertEquals(Arrays.asList(2),r.drainReadyJobIds());r.advance(12);assertEquals(Arrays.asList(1),r.drainReadyJobIds());
    }
    @Test void failedOriginCannotPublishAndRetryDoesNotDuplicateFileDemand(){
        CoherentDataflowRuntime r=runtime(true,1000,fanin());produce(r,0,1,7,0,1,false);
        assertThrows(IllegalStateException.class,()->r.requestJob(1,Arrays.asList(2),99,1));assertEquals(0,r.getActiveCopyCount());
        produce(r,5,1,42,1,2,true);r.requestJob(1,Arrays.asList(2),99,2);
        assertEquals(2,r.getAdmittedCopyCount());assertEquals(42,last(r,FileLifecycleEvent.Type.COPY_ADMITTED).getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());
    }
    @Test void joiningAFileAtOneDestinationDoesNotCreateAnotherPositiveCopy(){
        CoherentDataflowRuntime r=runtime(true,1000,fanin());produce(r,0,1,7,0,1,true);
        r.requestJob(1,Arrays.asList(2),99,1);CoherentDataflowRuntime.Preparation joined=r.requestJob(3,Arrays.asList(4),99,1);
        assertEquals(0,joined.getNewCopies());assertEquals(1,joined.getJoinedCopies());assertEquals(2,r.getAdmittedCopyCount());
        assertEquals("JOIN_EXISTING",last(r,FileLifecycleEvent.Type.INPUT_RESOLVED).getPayload().get("resolution").getAsString());
    }
    @Test void zeroAndLocalReferencesRemainDistinctWithoutPositiveService(){
        CoherentDataflowRuntime r=runtime(true,1000,plan(task(1,in("zero",0)),task(2,in("zero",0))));
        CoherentDataflowRuntime.Preparation first=r.requestJob(0,Arrays.asList(1),7,0);assertEquals(1,first.getReferenceCount());assertEquals(0,first.getRequiredBytes(),0);
        assertEquals("ZERO",last(r,FileLifecycleEvent.Type.INPUT_RESOLVED).getPayload().get("resolution").getAsString());
        r.requestJob(1,Arrays.asList(2),7,0);assertEquals("LOCAL",last(r,FileLifecycleEvent.Type.INPUT_RESOLVED).getPayload().get("resolution").getAsString());
        assertEquals(Arrays.asList(0,1),r.drainReadyJobIds());assertEquals(0,r.getAdmittedCopyCount());
    }
    @Test void cpuCannotMakeItsOwnUnobservedInputBecomeReady(){
        CoherentDataflowRuntime r=runtime(true,1000,plan(task(1,in("external",.5))));r.requestJob(0,Arrays.asList(1),42,0);
        assertThrows(IllegalStateException.class,()->r.cpuStarted(0,Arrays.asList(1),42,.1));assertEquals(0,r.getCurrentTime(),0);assertNull(r.getReplicaSnapshot().getReplica(id("external"),DataLocation.vm(42)));
        r.advance(.1);assertEquals(Arrays.asList(0),r.drainReadyJobIds());r.cpuStarted(0,Arrays.asList(1),42,.1);
        FileLifecycleEvent settled=last(r,FileLifecycleEvent.Type.COPY_SETTLED);assertEquals(.005,settled.getPayload().get("effectiveTime").getAsDouble(),0);assertEquals(.1,settled.getObservedTime(),0);
    }
    @Test void failedMultiFileRequestIsAtomicAcrossCatalogServiceAndEvidence(){
        CoherentDataflowRuntime r=runtime(true,1000,plan(task(1,in("a",Double.MAX_VALUE),in("b",Double.MAX_VALUE))));int before=r.captureEvidence().getEvents().size();
        assertThrows(IllegalArgumentException.class,()->r.requestJob(0,Arrays.asList(1),7,1));
        assertEquals(0,r.getCurrentTime(),0);assertEquals(0,r.getActiveCopyCount());assertEquals(0,r.getAdmittedCopyCount());assertEquals(before,r.captureEvidence().getEvents().size());assertTrue(r.drainReadyJobIds().isEmpty());
    }
    @Test void staticDestinationAndOneTimeCpuSubmissionAreEnforced(){
        CoherentDataflowRuntime r=runtime(true,1000,plan(task(1)));r.requestJob(0,Arrays.asList(1),7,0);r.drainReadyJobIds();
        assertThrows(IllegalStateException.class,()->r.cpuStarted(0,Arrays.asList(1),42,1));assertEquals(0,r.getCurrentTime(),0);
        r.cpuStarted(0,Arrays.asList(1),7,1);assertThrows(IllegalStateException.class,()->r.cpuStarted(0,Arrays.asList(1),7,2));
        assertThrows(IllegalStateException.class,()->r.requestJob(0,Arrays.asList(1),7,2));assertEquals(1,r.getCurrentTime(),0);
    }
    @Test void captureLossDoesNotChangePhysicsOrAllowAFakeCompleteCertificate(){
        Double finish=null;for(int budget:new int[]{0,1,1000}){
            CoherentDataflowRuntime r=runtime(true,budget,plan(task(1,in("external",100))));r.requestJob(0,Arrays.asList(1),7,0);
            if(finish==null)finish=r.getNextCompletionTime();else assertEquals(finish,r.getNextCompletionTime());r.advance(10);assertEquals(Arrays.asList(0),r.drainReadyJobIds());
            if(budget==0)assertNull(r.captureEvidence());else assertEquals(budget==1?FileLifecycleEvidence.Status.TRUNCATED:FileLifecycleEvidence.Status.COMPLETE,r.captureEvidence().getStatus());
        }
    }
    @Test void sharedAndIsolatedFollowTheSameLifecycleAndKeepSingleFlowCaps(){
        for(boolean shared:new boolean[]{true,false}){
            CoherentDataflowRuntime r=runtime(shared,1000,plan(task(1,in("a",100),in("b",100))));r.requestJob(0,Arrays.asList(1),7,0);
            assertEquals(shared?20:10,r.getNextCompletionTime(),0);r.advance(shared?20:10);assertEquals(Arrays.asList(0),r.drainReadyJobIds());assertEquals(2,r.getAdmittedCopyCount());
        }
    }
    private static void produce(CoherentDataflowRuntime r,int job,int task,int vm,double start,double finish,boolean success){r.requestJob(job,Arrays.asList(task),vm,start);assertTrue(r.drainReadyJobIds().contains(job));r.cpuStarted(job,Arrays.asList(task),vm,start);r.jobFinished(job,Arrays.asList(task),vm,Arrays.asList(success),finish);}
    private static FileLifecycleEvent last(CoherentDataflowRuntime r,FileLifecycleEvent.Type type){List<FileLifecycleEvent> events=r.captureEvidence().getEvents();for(int i=events.size()-1;i>=0;i--)if(events.get(i).getType()==type)return events.get(i);throw new AssertionError("Missing "+type);}
    private static CoherentDataflowRuntime runtime(boolean shared,int budget,DataflowFilePlan plan){CoherentDataflowRuntime r=new CoherentDataflowRuntime(shared,budget);Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,10.0);caps.put(42,100.0);caps.put(99,100.0);DataTransferFabric fabric=DataTransferFabric.endpoints(caps,Arrays.asList("source"));r.initializePlan(plan,fabric.getLocations());r.bindFabric(fabric);return r;}
    private static DataflowFilePlan fanin(){Task p=task(1,out("fast",10),out("slow",100)),both=task(2,in("fast",10),in("slow",100)),other=task(3,in("fast",10)),join=task(4,in("fast",10));for(Task c:Arrays.asList(both,other,join)){p.addChild(c);c.addParent(p);}return plan(p,both,other,join);}
    private static DataflowFilePlan plan(Task...tasks){Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task task:tasks)scopes.put(task.getCloudletId(),0);return DataflowFilePlan.capture(Arrays.asList(tasks),scopes);}
    private static Task task(int id,FileItem...files){Task task=new Task(id,1000);for(FileItem file:files)task.addFile(file);return task;}
    private static FileItem in(String name,double bytes){return file(name,bytes,FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,FileType type){FileItem file=new FileItem(name,bytes);file.setType(type);return file;}
    private static DataflowFilePlan.FileId id(String name){return DataflowFilePlan.FileId.of(0,name);}
}
