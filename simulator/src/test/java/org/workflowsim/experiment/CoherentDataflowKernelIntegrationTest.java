package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.data.v2.FileLifecycleEvidence;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Real Kernel path, not just a stand-alone catalog/selector example. */
class CoherentDataflowKernelIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final String DAX="<adag><job id=\"p\" runtime=\"1\"><uses file=\"ext-a\" link=\"input\" size=\"0.125\"/><uses file=\"ext-b\" link=\"input\" size=\"0.375\"/><uses file=\"a\" link=\"output\" size=\"2000000\"/><uses file=\"b\" link=\"output\" size=\"3000000\"/></job><job id=\"c\" runtime=\"2\"><uses file=\"a\" link=\"input\" size=\"2000000\"/><uses file=\"b\" link=\"input\" size=\"3000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}

    @Test void actualKernelTransfersFilesAndGatesCpuAfterObservedVisibility()throws Exception{
        SimulationReport report=run(input("files.dax",DAX),false,true,2000);assertTrue(report.isWorkflowCompletedSuccessfully());assertNull(report.getNetworkEvidence());
        FileLifecycleEvidence evidence=report.getFileLifecycleEvidence();assertNotNull(evidence);assertEquals(FileLifecycleEvidence.Status.COMPLETE,evidence.getStatus());assertNotNull(report.getDataflowPlan());
        List<FileLifecycleEvent> starts=events(evidence,FileLifecycleEvent.Type.COPY_ADMITTED),ends=events(evidence,FileLifecycleEvent.Type.COPY_SETTLED);assertEquals(4,starts.size());assertEquals(4,ends.size());
        assertEquals(.125,starts.get(0).getPayload().get("bytes").getAsDouble(),0);assertEquals(.375,starts.get(1).getPayload().get("bytes").getAsDouble(),0);
        assertEquals(7,starts.get(2).getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());assertEquals(42,starts.get(2).getPayload().get("destinationVmId").getAsInt());
        assertTrue(ends.get(0).getPayload().get("effectiveTime").getAsDouble()<ends.get(0).getObservedTime(),"small positive flow settles at a later Kernel observation");
        assertTrue(ends.get(2).getObservedTime()<ends.get(3).getObservedTime(),"one file is independently visible before its sibling");
        List<FileLifecycleEvent> cpu=events(evidence,FileLifecycleEvent.Type.JOB_CPU_STARTED);assertEquals(2,cpu.size());assertTrue(cpu.get(1).getObservedTime()>=ends.get(3).getObservedTime());
        assertEquals(7,report.getTasks().get(0).getVmId());
    }
    @Test void recordingOffFullAndTruncatedDoNotChangeKernelPhysics()throws Exception{
        Path path=input("parity.dax",DAX);SimulationReport off=run(path,false,true,0),on=run(path,false,true,2000),prefix=run(path,false,true,1);
        assertNull(off.getFileLifecycleEvidence());assertEquals(FileLifecycleEvidence.Status.TRUNCATED,prefix.getFileLifecycleEvidence().getStatus());
        for(SimulationReport other:Arrays.asList(on,prefix)){assertEquals(JSON.toJsonTree(off.getJobs()),JSON.toJsonTree(other.getJobs()));assertEquals(JSON.toJsonTree(off.getTasks()),JSON.toJsonTree(other.getTasks()));assertEquals(metricsCore(off),metricsCore(other));assertEquals(mainEvents(off),mainEvents(other));}
    }
    @Test void actualFatTreeUsesSourceLinksThenDestinationOrder()throws Exception{
        FileLifecycleEvidence evidence=run(input("fat.dax",DAX),true,true,2000).getFileLifecycleEvidence();
        FileLifecycleEvent transfer=events(evidence,FileLifecycleEvent.Type.COPY_ADMITTED).get(2);
        assertEquals(JSON.toJsonTree(Arrays.asList("VM:7","LINK:ACC:10->EDGE:0:0","LINK:EDGE:0:0->AGG:0:0","LINK:AGG:0:0->EDGE:0:1","LINK:EDGE:0:1->ACC:20","VM:42")),transfer.getPayload().get("resources"));
        assertNotNull(evidence.getFabric().get("topology"));assertFalse(evidence.getFabric().get("topology").isJsonNull());
    }
    @Test void realSharedAndIsolatedRunsRetainSingleFlowBottlenecks()throws Exception{
        Path path=input("ablation.dax",DAX);SimulationReport shared=run(path,false,true,2000),isolated=run(path,false,false,2000);
        assertTrue(shared.isWorkflowCompletedSuccessfully());assertTrue(isolated.isWorkflowCompletedSuccessfully());
        List<FileLifecycleEvent> a=events(shared.getFileLifecycleEvidence(),FileLifecycleEvent.Type.COPY_ADMITTED),b=events(isolated.getFileLifecycleEvidence(),FileLifecycleEvent.Type.COPY_ADMITTED);assertEquals(a.size(),b.size());
        for(int i=0;i<a.size();i++){assertEquals(a.get(i).getPayload().get("resources"),b.get(i).getPayload().get("resources"));assertEquals(a.get(i).getPayload().get("standaloneRate"),b.get(i).getPayload().get("standaloneRate"));}
        assertTrue(shared.getMakespan()>isolated.getMakespan());
    }
    @Test void realFailureRetryKeepsCachedInputsAndOnlySuccessPublishesOutputs()throws Exception{
        Path path=input("retry.dax","<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"20000000\"/><uses file=\"result\" link=\"output\" size=\"5000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"result\" link=\"input\" size=\"5000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>");
        FailureModelConfig failures=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();
        PlatformProfile platform=PlatformProfile.builder("coherent-retry").addHost(new PlatformProfile.HostSpec(10,2,1000,4096,10000L,1000000L))
                .addVm(new PlatformProfile.VmSpec(7,1000,1,512,20L,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(7,10).build();
        int retries=0;for(long seed:new long[]{1,5,17,22}){
            SimulationConfig config=config(path,true,2000,1).toBuilder().failureModel(failures).randomSeed(seed).build();SimulationReport report=new SimulationRunner().run(config,platform);assertTrue(report.isWorkflowCompletedSuccessfully());retries+=report.getMetrics().getRetryJobCreatedCount();
            FileLifecycleEvidence evidence=report.getFileLifecycleEvidence();assertEquals(1,events(evidence,FileLifecycleEvent.Type.COPY_ADMITTED).size(),"LOCAL inputs are cached across attempts, output is local on this one-VM fixture");
            assertEquals(report.getTasks().size(),events(evidence,FileLifecycleEvent.Type.TASK_FINISHED).size());
        }
        assertTrue(retries>0,"bounded deterministic seed set must contain actual failed attempts");
    }
    @Test void realKernelReusesAnEarlyFileCacheWhileTheSlowSiblingIsPending()throws Exception{
        Path path=input("kernel-cache.dax","<adag><job id=\"p\" runtime=\"1\"><uses file=\"fast\" link=\"output\" size=\"2000000\"/><uses file=\"slow\" link=\"output\" size=\"8000000\"/></job><job id=\"both\" runtime=\"1\"><uses file=\"fast\" link=\"input\" size=\"2000000\"/><uses file=\"slow\" link=\"input\" size=\"8000000\"/></job><job id=\"gate\" runtime=\"6\"/><job id=\"reuse\" runtime=\"1\"><uses file=\"fast\" link=\"input\" size=\"2000000\"/></job><child ref=\"both\"><parent ref=\"p\"/></child><child ref=\"gate\"><parent ref=\"p\"/></child><child ref=\"reuse\"><parent ref=\"p\"/><parent ref=\"gate\"/></child></adag>");
        PlatformProfile.Builder p=PlatformProfile.builder("actual-cache-source");for(int vm=0;vm<4;vm++){p.addHost(new PlatformProfile.HostSpec(vm,2,2000,4096,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(vm,1000,1,512,vm==1?1L:10L,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(vm,vm);}p.networkTopology(NetworkTopologySpec.fatTree(4,10));
        SimulationReport report=new SimulationRunner().run(config(path,true,2000,4),p.build());assertTrue(report.isWorkflowCompletedSuccessfully());FileLifecycleEvent reuse=null,slow=null;
        for(FileLifecycleEvent e:events(report.getFileLifecycleEvidence(),FileLifecycleEvent.Type.COPY_ADMITTED)){JsonObject payload=e.getPayload();String name=payload.getAsJsonObject("fileId").get("name").getAsString();if(name.equals("slow"))slow=e;if(name.equals("fast")&&payload.get("destinationVmId").getAsInt()==3)reuse=e;}
        assertNotNull(reuse);assertNotNull(slow);assertEquals(2,reuse.getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());assertEquals(1,reuse.getPayload().getAsJsonObject("sourceReplica").getAsJsonObject("origin").getAsJsonObject("location").get("vmId").getAsInt());
        long slowId=slow.getPayload().get("copyOrdinal").getAsLong();double slowObserved=-1;for(FileLifecycleEvent e:events(report.getFileLifecycleEvidence(),FileLifecycleEvent.Type.COPY_SETTLED))if(e.getPayload().get("copyOrdinal").getAsLong()==slowId)slowObserved=e.getObservedTime();assertTrue(reuse.getObservedTime()<slowObserved,"cached fast file is selected before the first consumer's slow input arrives");
        assertTrue(reuse.getPayload().getAsJsonArray("resources").get(0).getAsString().equals("VM:2"));assertDoesNotThrow(()->org.workflowsim.data.v2.FileLifecycleCodec.encode(report.getFileLifecycleEvidence()));
        ExperimentArtifactWriter.ExperimentArtifacts bundle=ExperimentArtifactWriter.write(report,directory.resolve("actual-cache-bundle"),"result");assertTrue(ExperimentArtifactValidator.validate(bundle.getManifest()).getDecodedFileLifecycle().isQuiescent());
    }
    @Test void realKernelSameDestinationWaitersShareOneCopyInBothServiceModes()throws Exception{
        Path path=input("kernel-join.dax","<adag><job id=\"p\" runtime=\"1\"><uses file=\"shared\" link=\"output\" size=\"2000000\"/></job><job id=\"a\" runtime=\"1\"><uses file=\"shared\" link=\"input\" size=\"2000000\"/></job><job id=\"b\" runtime=\"1\"><uses file=\"shared\" link=\"input\" size=\"2000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"shared\" link=\"input\" size=\"2000000\"/></job><child ref=\"a\"><parent ref=\"p\"/></child><child ref=\"b\"><parent ref=\"p\"/></child><child ref=\"c\"><parent ref=\"p\"/></child></adag>");
        Double makespan=null;for(boolean shared:new boolean[]{true,false}){SimulationReport report=run(path,false,shared,2000);assertEquals(1,events(report.getFileLifecycleEvidence(),FileLifecycleEvent.Type.COPY_ADMITTED).size());assertTrue(events(report.getFileLifecycleEvidence(),FileLifecycleEvent.Type.INPUT_RESOLVED).stream().anyMatch(e->e.getPayload().get("resolution").getAsString().equals("JOIN_EXISTING")));if(makespan==null)makespan=report.getMakespan();else assertEquals(makespan,report.getMakespan(),0);}
    }
    @Test void unsupportedPolicyCombinationsRemainExplicitlyRejected()throws Exception{
        Path path=input("matrix.dax",DAX);
        assertThrows(IllegalArgumentException.class,()->SimulationConfig.builder(path.toString(),2).dataMovementModel(DataMovementModel.coherentFileDataflowV2()).build());
        assertThrows(IllegalArgumentException.class,()->config(path,true,0,2).toBuilder().fileSystem(ReplicaCatalog.FileSystem.SHARED).build());
        assertThrows(IllegalArgumentException.class,()->config(path,true,0,2).toBuilder().planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT).build());
        assertThrows(IllegalArgumentException.class,()->config(path,true,0,2).toBuilder().networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(100)).build());
        assertThrows(IllegalArgumentException.class,()->SimulationConfig.builder(path.toString(),2).networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(100)).build());
    }
    private static JsonObject metricsCore(SimulationReport report){JsonObject metrics=JSON.toJsonTree(report.getMetrics()).getAsJsonObject();assertNotNull(metrics.remove("totalSchedulingDecisionWallClockNanos"));assertNotNull(metrics.remove("totalPlanningDecisionWallClockNanos"));return metrics;}
    private static List<JsonObject> mainEvents(SimulationReport report){List<JsonObject> result=new ArrayList<>();for(SimulationEvent event:report.getEvents()){JsonObject row=JSON.toJsonTree(event).getAsJsonObject();if(row.has("attributes")){row.getAsJsonObject("attributes").remove("planningDecisionElapsedNanos");row.getAsJsonObject("attributes").remove("decisionElapsedNanos");}result.add(row);}return result;}
    private static List<FileLifecycleEvent> events(FileLifecycleEvidence evidence,FileLifecycleEvent.Type type){List<FileLifecycleEvent> result=new ArrayList<>();for(FileLifecycleEvent event:evidence.getEvents())if(event.getType()==type)result.add(event);return result;}
    private Path input(String name,String content)throws Exception{Path path=directory.resolve(name);Files.write(path,content.getBytes(StandardCharsets.UTF_8));return path;}
    private static SimulationConfig config(Path path,boolean shared,int budget,int vms){return SimulationConfig.builder(path.toString(),vms).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1)
            .dataMovementModel(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.fileLifecycleV2(budget)).build();}
    private static SimulationReport run(Path path,boolean fat,boolean shared,int budget)throws Exception{PlatformProfile.Builder p=PlatformProfile.builder("coherent-kernel");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,2048,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,1L,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,1));return new SimulationRunner().run(config(path,shared,budget,2),p.build());}
}
