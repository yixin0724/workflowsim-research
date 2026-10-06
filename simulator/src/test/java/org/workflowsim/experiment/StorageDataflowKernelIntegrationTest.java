package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Storage V3 is executed by the real Kernel; export stays explicitly closed until its codec is wired. */
class StorageDataflowKernelIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void unusedOutputExtendsSimulationButDoesNotDelayCpuReturn()throws Exception{
        Path input=input("sink","<job id=\"p\" runtime=\"1\"><uses file=\"out\" link=\"output\" size=\"1000000\"/></job>");SimulationReport report=run(input,true,1,false,1000);assertTrue(report.isWorkflowCompletedSuccessfully());
        FileLifecycleEvent finish=events(report,FileLifecycleEvent.Type.TASK_FINISHED).get(0),settled=events(report,FileLifecycleEvent.Type.COPY_SETTLED).get(0),admitted=events(report,FileLifecycleEvent.Type.COPY_ADMITTED).get(0);
        assertEquals("OUTPUT",admitted.getPayload().get("purpose").getAsString());assertEquals(finish.getObservedTime(),admitted.getObservedTime(),0);assertTrue(settled.getObservedTime()>finish.getObservedTime()+1.9);assertTrue(report.getMakespan()>=settled.getObservedTime());
        assertTrue(report.getJobs().stream().filter(j->j.getTaskIds().contains(1)).allMatch(j->j.getFinishTime()<=finish.getObservedTime()));assertNull(report.getFileLifecycleEvidence());assertNull(report.getNetworkEvidence());
    }
    @Test void differentVmReadsShareTheSourceWhileNominalMetricMeaningStaysIntact()throws Exception{
        Path input=input("reads","<job id=\"a\" runtime=\"1\"><uses file=\"in\" link=\"input\" size=\"1000000\"/></job><job id=\"b\" runtime=\"1\"><uses file=\"in\" link=\"input\" size=\"1000000\"/></job>");SimulationReport shared=run(input,true,2,false,1000),isolated=run(input,false,2,false,1000);assertTrue(shared.getMakespan()>isolated.getMakespan());
        assertEquals(2,events(shared,FileLifecycleEvent.Type.COPY_ADMITTED).size());int summaries=0;for(SimulationEvent event:shared.getEvents())if(event.getType()==SimulationEventType.DATA_STAGE_IN_MODELED&&"LOGICAL_FILE_STORAGE_V3".equals(event.getAttributes().get("transferUnit"))){assertEquals(1.0,((Number)event.getAttributes().get("modeledTransferSeconds")).doubleValue(),0);assertTrue(((Number)event.getAttributes().get("observedInputPreparationSeconds")).doubleValue()>=2);summaries++;}assertEquals(2,summaries);
    }
    @Test void storeWaitCannotBeBypassedLocallyAndCpuCanOverlapWriteback()throws Exception{
        Path input=input("overlap","<job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"1000000\"/></job><job id=\"free\" runtime=\"0.5\"/><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>");SimulationReport report=run(input,true,1,false,1000);assertEquals(1,events(report,FileLifecycleEvent.Type.INPUT_WAITING_FOR_STORE).size());double storage=events(report,FileLifecycleEvent.Type.COPY_SETTLED).get(0).getObservedTime();
        assertTrue(report.getTasks().stream().filter(t->t.getTaskId()==2).allMatch(t->t.getStartTime()<storage));assertTrue(report.getTasks().stream().filter(t->t.getTaskId()==3).allMatch(t->t.getStartTime()>=storage));
        assertEquals("LOCAL",events(report,FileLifecycleEvent.Type.INPUT_RESOLVED).get(0).getPayload().get("resolution").getAsString());
    }
    @Test void storageReadAndWriteUseActualFatTreeAttachment()throws Exception{
        Path input=input("fat","<job id=\"p\" runtime=\"1\"><uses file=\"in\" link=\"input\" size=\"1000000\"/><uses file=\"out\" link=\"output\" size=\"1000000\"/></job>");SimulationReport report=run(input,true,1,true,1000);List<FileLifecycleEvent> copies=events(report,FileLifecycleEvent.Type.COPY_ADMITTED);assertEquals(2,copies.size());
        JsonObject read=copies.get(0).getPayload(),write=copies.get(1).getPayload();assertEquals("STORE:source:READ",read.getAsJsonArray("resources").get(0).getAsString());assertEquals("STORE:source:NIC",read.getAsJsonArray("resources").get(1).getAsString());assertTrue(read.getAsJsonArray("resources").get(2).getAsString().startsWith("LINK:ACC:30->"));assertEquals("SOURCE",write.getAsJsonObject("destination").get("kind").getAsString());int n=write.getAsJsonArray("resources").size();assertEquals("STORE:source:NIC",write.getAsJsonArray("resources").get(n-2).getAsString());assertEquals("STORE:source:WRITE",write.getAsJsonArray("resources").get(n-1).getAsString());
    }
    @Test void offAndTruncatedCaptureDoNotChangeCpuOrStorageTrajectory()throws Exception{
        Path input=input("parity","<job id=\"p\" runtime=\"1\"><uses file=\"in\" link=\"input\" size=\"1000000\"/><uses file=\"out\" link=\"output\" size=\"1000000\"/></job>");SimulationReport off=run(input,true,1,false,0),on=run(input,true,1,false,1000),prefix=run(input,true,1,false,1);Gson json=new Gson();assertNull(off.getStorageLifecycleEvidence());
        for(SimulationReport report:Arrays.asList(on,prefix)){assertEquals(off.getMakespan(),report.getMakespan(),0);assertEquals(json.toJsonTree(off.getJobs()),json.toJsonTree(report.getJobs()));assertEquals(json.toJsonTree(off.getTasks()),json.toJsonTree(report.getTasks()));}
    }
    @Test void modelModeAndStoragePresenceAreExplicitAndExportDoesNotMislabelIt()throws Exception{
        Path input=input("guard","<job id=\"p\" runtime=\"1\"/>");SimulationReport report=run(input,true,1,false,1000);Path target=directory.resolve("must-not-exist");assertThrows(UnsupportedOperationException.class,()->ExperimentArtifactWriter.write(report,target,"run"));assertFalse(Files.exists(target));assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,target.resolve("run.manifest.json")));assertFalse(Files.exists(target));
        assertThrows(IllegalArgumentException.class,()->configuration(input,true,1,1000).toBuilder().networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(1000)).build());
        assertThrows(SimulationConfigurationException.class,()->new SimulationRunner().run(configuration(input,true,1,0),platform(1,false,false)));
        assertThrows(SimulationConfigurationException.class,()->new SimulationRunner().run(configuration(input,true,1,0).toBuilder().dataMovementModel(DataMovementModel.legacyWorkflowsimV1()).build(),platform(1,false,true)));
        assertFalse(DataMovementModel.coherentStorageDataflowV3().isCoherentFileDataflowV2());assertFalse(DataMovementModel.coherentStorageDataflowV3().isCoherentShared());
    }
    @Test void realNoopRetriesOnlyUploadSuccessfulOutputs()throws Exception{
        Path input=input("retry","<job id=\"p\" runtime=\"1\"><uses file=\"in\" link=\"input\" size=\"1000000\"/><uses file=\"out\" link=\"output\" size=\"1000000\"/></job>");org.workflowsim.failure.FailureModelConfig failure=org.workflowsim.failure.FailureModelConfig.builder().clusteringAlgorithm(org.workflowsim.failure.FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(org.workflowsim.failure.FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new org.workflowsim.utils.DistributionSpec[][]{{org.workflowsim.utils.DistributionSpec.of(org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();long retries=0;
        for(long seed:new long[]{1,5,17,22}){SimulationReport report=new SimulationRunner().run(configuration(input,true,1,2000).toBuilder().randomSeed(seed).failureModel(failure).build(),platform(1,false,true));retries+=report.getMetrics().getRetryJobCreatedCount();int outputs=0;for(FileLifecycleEvent e:events(report,FileLifecycleEvent.Type.COPY_ADMITTED))if(e.getPayload().get("purpose").getAsString().equals("OUTPUT")){int owner=e.getPayload().get("ownerJobId").getAsInt();assertTrue(report.getJobs().stream().anyMatch(j->j.getJobId()==owner&&j.getStatus()==Cloudlet.SUCCESS));outputs++;}assertEquals(1,outputs);}assertTrue(retries>0);
    }
    private Path input(String name,String body)throws Exception{Path path=directory.resolve(name+".dax");Files.write(path,("<adag>"+body+"</adag>").getBytes(StandardCharsets.UTF_8));return path;}
    private SimulationReport run(Path input,boolean shared,int vms,boolean fat,int budget)throws Exception{return new SimulationRunner().run(configuration(input,shared,vms,budget),platform(vms,fat,true));}
    private static SimulationConfig configuration(Path input,boolean shared,int vms,int budget){return SimulationConfig.builder(input.toString(),vms).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.SHARED).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).dataMovementModel(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.storageLifecycleV3(budget)).build();}
    private static PlatformProfile platform(int count,boolean fat,boolean store){PlatformProfile.Builder p=PlatformProfile.builder("storage-kernel");int[] ids={7,42};for(int i=0;i<count;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,10,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,2000,4096,10000,1000000));if(store)p.sourceStorage(DataflowStorageSpec.of(30,1,.5,10));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,10));return p.build();}
    private static List<FileLifecycleEvent> events(SimulationReport report,FileLifecycleEvent.Type type){List<FileLifecycleEvent> result=new ArrayList<>();for(FileLifecycleEvent e:report.getStorageLifecycleEvidence().getCapture().getEvents())if(e.getType()==type)result.add(e);return result;}
}
