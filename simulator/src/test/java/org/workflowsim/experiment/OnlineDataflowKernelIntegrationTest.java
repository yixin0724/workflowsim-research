package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowAssignmentConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class OnlineDataflowKernelIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void unplannedTasksBindBeforeInputTransferInEveryCoherentPhysicalFamily()throws Exception{
        Path input=input("flow","<job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>");
        for(boolean storage:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){
            SimulationReport report=new SimulationRunner().run(config(input,storage,shared,2000),platform(storage,fat,1000,2000));assertTrue(report.isWorkflowCompletedSuccessfully());assertEquals(Parameters.PlanningAlgorithm.INVALID,report.getConfig().getPlanningAlgorithm());List<SimulationEvent> decisions=assigned(report);assertEquals(2,decisions.size());assertEquals(Integer.valueOf(42),decisions.get(0).getVmId());
            for(SimulationEvent decision:decisions){SimulationEvent ready=report.getEvents().stream().filter(e->e.getType()==SimulationEventType.JOB_READY&&e.getJobId().equals(decision.getJobId())).findFirst().get();assertTrue(decision.getSequence()<ready.getSequence());assertEquals(decision.getSimulationTime(),ready.getSimulationTime(),0);assertEquals(DataflowAssignmentConfig.POLICY,decision.getAttributes().get("assignmentPolicy"));}
            if(storage)assertDoesNotThrow(()->StorageLifecycleCodec.encode(report.getStorageLifecycleEvidence()));else assertDoesNotThrow(()->FileLifecycleCodec.encode(report.getFileLifecycleEvidence()));
        }
    }
    @Test void laterWorkflowSeesCurrentReservationsRatherThanTimeZeroPreplanning()throws Exception{
        Path a=input("long","<job id=\"a\" runtime=\"10\"><uses file=\"a\" link=\"input\" size=\"2000000\"/></job>"),b=input("late","<job id=\"b\" runtime=\"1\"/>");SimulationConfig config=config(Arrays.asList(a.toString(),b.toString()),false,true,2000).toBuilder().workflowArrivalSeconds(Arrays.asList(0.0,.5)).build();SimulationReport report=new SimulationRunner().run(config,platform(false,false,1000,1000));List<SimulationEvent> assignments=assigned(report);assertEquals(Integer.valueOf(7),assignments.get(0).getVmId());assertEquals(Integer.valueOf(42),assignments.get(1).getVmId());assertTrue(assignments.get(1).getSimulationTime()>=.5);
    }
    @Test void busyDestinationRemainsEligibleAndItsInputTransferOverlapsCpu()throws Exception{
        Path a=input("busy","<job id=\"a\" runtime=\"10\"/>"),b=input("queued","<job id=\"b\" runtime=\"5\"><uses file=\"b\" link=\"input\" size=\"1000000\"/></job>");SimulationConfig config=config(Arrays.asList(a.toString(),b.toString()),false,true,2000).toBuilder().workflowArrivalSeconds(Arrays.asList(0.0,1.0)).build();SimulationReport report=new SimulationRunner().run(config,platform(false,false,1000,100));List<SimulationEvent> assignments=assigned(report);assertEquals(Integer.valueOf(7),assignments.get(1).getVmId());double firstFinish=report.getTasks().stream().filter(t->t.getTaskId()==1).findFirst().get().getFinishTime(),secondStart=report.getTasks().stream().filter(t->t.getTaskId()==2).findFirst().get().getStartTime();int job=assignments.get(1).getJobId();double dataReady=report.getFileLifecycleEvidence().getEvents().stream().filter(e->e.getType()==FileLifecycleEvent.Type.JOB_DATA_READY&&e.getPayload().get("jobId").getAsInt()==job).findFirst().get().getObservedTime();assertTrue(dataReady<firstFinish);assertTrue(secondStart>=firstFinish);assertTrue(assignments.get(1).getSimulationTime()<dataReady);
    }
    @Test void sharedStorageBindingDoesNotTreatUncommittedLocalOutputAsFreeInput()throws Exception{
        Path input=input("store","<job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"1000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>");SimulationReport report=new SimulationRunner().run(config(input,true,true,2000),platform(true,false,1000,1000));List<SimulationEvent> assignments=assigned(report);assertEquals(Integer.valueOf(7),assignments.get(1).getVmId());assertTrue(report.getStorageLifecycleEvidence().getCapture().getEvents().stream().anyMatch(e->e.getType()==FileLifecycleEvent.Type.INPUT_WAITING_FOR_STORE));double commit=report.getStorageLifecycleEvidence().getCapture().getEvents().stream().filter(e->e.getType()==FileLifecycleEvent.Type.COPY_SETTLED).findFirst().get().getObservedTime();assertTrue(report.getTasks().stream().filter(t->t.getTaskId()==2).allMatch(t->t.getStartTime()>=commit));
    }
    @Test void noopRetriesReserveWorkButReuseTheOriginalLogicalBinding()throws Exception{
        Path input=input("retry","<job id=\"p\" runtime=\"1\"><uses file=\"z\" link=\"output\" size=\"0\"/></job>");FailureModelConfig failure=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();long retries=0;
        for(long seed=1;seed<=32;seed++){SimulationReport report=new SimulationRunner().run(config(input,true,true,2000).toBuilder().failureModel(failure).randomSeed(seed).build(),platform(true,false,1000,1000));retries+=report.getMetrics().getRetryJobCreatedCount();List<SimulationEvent> assigned=assigned(report);assertEquals(1+report.getMetrics().getRetryJobCreatedCount(),assigned.size());for(int i=0;i<assigned.size();i++){assertEquals(assigned.get(0).getVmId(),assigned.get(i).getVmId());assertEquals(i==0?"INITIAL":"RETRY_REUSE",assigned.get(i).getAttributes().get("binding"));}if(retries>0)break;}assertTrue(retries>0);
    }
    @Test void observationAndActionsAreIndependentOfCaptureBudget()throws Exception{
        Path input=input("capture","<job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/></job>");Gson gson=new GsonBuilder().serializeNulls().create();SimulationReport reference=null;for(int budget:new int[]{0,1,2000}){SimulationReport report=new SimulationRunner().run(config(input,true,true,budget),platform(true,false,1000,2000));if(reference==null)reference=report;else{assertEquals(reference.getMakespan(),report.getMakespan(),0);assertEquals(gson.toJsonTree(reference.getJobs()),gson.toJsonTree(report.getJobs()));assertEquals(gson.toJsonTree(reference.getTasks()),gson.toJsonTree(report.getTasks()));assertEquals(gson.toJsonTree(assigned(reference)),gson.toJsonTree(assigned(report)));}}
    }
    @Test void optInIsRequiredAndCannotRelabelLegacyOrRandomPlanning()throws Exception{
        Path input=input("guard","<job id=\"x\" runtime=\"1\"/>");SimulationConfig online=config(input,false,true,0);assertTrue(online.toBuilder().build().getDataflowAssignmentConfig().isEnabled());assertThrows(IllegalArgumentException.class,()->online.toBuilder().dataflowAssignment(DataflowAssignmentConfig.off()).build());assertThrows(IllegalArgumentException.class,()->online.toBuilder().planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).build());assertThrows(IllegalArgumentException.class,()->online.toBuilder().dataMovementModel(DataMovementModel.legacyWorkflowsimV1()).build());
        SimulationReport report=new SimulationRunner().run(online,platform(false,false,1000,1000));Path out=directory.resolve("closed-action-export");assertThrows(UnsupportedOperationException.class,()->ExperimentArtifactWriter.write(report,out,"result"));assertFalse(Files.exists(out));assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,out.resolve("result.manifest.json")));assertFalse(Files.exists(out));
    }
    private Path input(String name,String body)throws Exception{Path path=directory.resolve(name+".dax");Files.write(path,("<adag>"+body+"</adag>").getBytes(StandardCharsets.UTF_8));return path;}
    private static SimulationConfig config(Path input,boolean storage,boolean shared,int budget){return config(Collections.singletonList(input.toString()),storage,shared,budget);}
    private static SimulationConfig config(List<String> paths,boolean storage,boolean shared,int budget){DataMovementModel model=storage?(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()):(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2());return SimulationConfig.builder(paths,2).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(storage?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).dataMovementModel(model).networkEvidence(budget==0?NetworkEvidenceConfig.off():storage?NetworkEvidenceConfig.storageLifecycleV3(budget):NetworkEvidenceConfig.fileLifecycleV2(budget)).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).build();}
    private static PlatformProfile platform(boolean storage,boolean fat,double firstMips,double secondMips){PlatformProfile.Builder p=PlatformProfile.builder("online-binding");int[] ids={7,42};double[] mips={firstMips,secondMips};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,4000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],mips[i],1,512,1,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,4000,4096,10000,1000000));if(storage)p.sourceStorage(DataflowStorageSpec.of(30,1,.5,2));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));return p.build();}
    private static List<SimulationEvent> assigned(SimulationReport report){List<SimulationEvent> events=new ArrayList<>();for(SimulationEvent e:report.getEvents())if(e.getType()==SimulationEventType.DATAFLOW_VM_ASSIGNED)events.add(e);return events;}
}
