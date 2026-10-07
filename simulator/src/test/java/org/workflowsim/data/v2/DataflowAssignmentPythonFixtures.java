package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/** Fresh actual Kernel bundles for the independent online-action Python audit. */
public final class DataflowAssignmentPythonFixtures {
    private static final int BUDGET=20000;
    private DataflowAssignmentPythonFixtures(){ }
    /** @param args fresh output root */
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: DataflowAssignmentPythonFixtures <fresh-root>");Path root=Paths.get(args[0]).toAbsolutePath().normalize();if(Files.exists(root))throw new IllegalArgumentException("Fixture root exists: "+root);Files.createDirectories(root);Log.disable();JsonArray manifests=new JsonArray();Path flow=input(root,"flow","<job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"unused\" link=\"output\" size=\"500000\"/><uses file=\"z\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"z\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>");
        for(boolean storage:new boolean[]{false,true})for(boolean gate:new boolean[]{false,true}){if(!storage&&gate)continue;for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true})save(root,(storage?"v3-":"v2-")+(gate?"store-":"local-")+(shared?"shared-":"isolated-")+(fat?"fat":"endpoint"),config(flow,storage,gate,shared),platform(storage,fat,1000,2000,2),manifests);}
        Path zero=input(root,"zero","<job id=\"z\" runtime=\"1\"><uses file=\"z\" link=\"output\" size=\"0\"/></job>"),empty=input(root,"empty","<job id=\"e\" runtime=\"1\"/>");save(root,"zero-only",config(zero,true,true,true),platform(true,false,1000,2000,1),manifests);save(root,"fileless",config(empty,false,false,true),platform(false,false,1000,2000,1),manifests);
        TaskCostMatrix matrix=TaskCostMatrix.builder().put(1,7,.00149).put(1,42,.0014).put(99,999,3.5).build();save(root,"matrix-rounded-extra",config(empty,false,false,true).toBuilder().taskCostMatrix(matrix).build(),platform(false,false,1000,2000,1),manifests);
        Path busy=input(root,"busy","<job id=\"a\" runtime=\"10\"/>"),late=input(root,"late","<job id=\"b\" runtime=\"5\"><uses file=\"b\" link=\"input\" size=\"1000000\"/></job>");SimulationConfig overlap=SimulationConfig.builder(Arrays.asList(busy.toString(),late.toString()),2).workflowArrivalSeconds(Arrays.asList(0.0,1.0)).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(DataMovementModel.coherentFileDataflowV2()).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(BUDGET)).randomSeed(1).build();save(root,"busy-vm-overlap",overlap,platform(false,false,1000,100,1),manifests);
        String join="<job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"10000000\"/></job><job id=\"q\" runtime=\"0.2\"/><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"10000000\"/></job><child ref=\"c\"><parent ref=\"q\"/></child>";Path joined=input(root,"join",join);for(boolean storage:new boolean[]{false,true})save(root,"mid-copy-join-"+storage,config(joined,storage,storage,true),platform(storage,false,1000,2000,2),manifests);
        Path laterFlow=input(root,"later-flow",new String(Files.readAllBytes(flow),StandardCharsets.UTF_8).replace("<adag>","").replace("</adag>",""));SimulationConfig scopes=SimulationConfig.builder(Arrays.asList(flow.toString(),laterFlow.toString()),2).workflowArrivalSeconds(Arrays.asList(0.0,20.0)).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.SHARED).dataMovementModel(DataMovementModel.coherentStorageDataflowV3()).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(BUDGET)).randomSeed(1).build();save(root,"staggered-file-scopes",scopes,platform(true,true,1000,2000,2),manifests);
        FailureModelConfig failure=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();for(boolean storage:new boolean[]{false,true}){boolean found=false;for(long seed=1;seed<=32;seed++){SimulationReport report=new SimulationRunner().run(config(zero,storage,storage,true).toBuilder().randomSeed(seed).failureModel(failure).build(),platform(storage,false,1000,1000,1));if(report.getMetrics().getRetryJobCreatedCount()>0){saveReport(root,"retry-"+storage+"-s"+seed,report,manifests);found=true;break;}}if(!found)throw new AssertionError("Fixed seed domain did not cover online retries");}
        JsonObject index=new JsonObject();index.addProperty("schema","workflowsim-online-dataflow-fixtures-v1");index.add("standalone",new JsonArray());index.add("manifests",manifests);ExperimentArtifactWriter.writeJson(root.resolve("fixtures.json"),index);System.out.println("ONLINE_DATAFLOW_FIXTURES manifests="+manifests.size()+" root="+root);
    }
    private static void save(Path root,String name,SimulationConfig config,PlatformProfile platform,JsonArray manifests)throws Exception{saveReport(root,name,new SimulationRunner().run(config,platform),manifests);}
    private static void saveReport(Path root,String name,SimulationReport report,JsonArray manifests)throws Exception{if(!report.isWorkflowCompletedSuccessfully())throw new IllegalStateException("Incomplete fixture "+name);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,root.resolve(name),"result");if(ExperimentArtifactValidator.validate(out.getManifest()).getDataflowAssignment()==null)throw new AssertionError("Missing checked online action context");manifests.add(root.relativize(out.getManifest()).toString());}
    private static Path input(Path root,String name,String body)throws Exception{Path path=root.resolve("inputs").resolve(name+".dax");Files.createDirectories(path.getParent());Files.write(path,("<adag>"+body+"</adag>").getBytes(StandardCharsets.UTF_8));return path;}
    private static SimulationConfig config(Path input,boolean storage,boolean gate,boolean shared){DataMovementModel model=storage?(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()):(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2());return SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(gate?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).networkEvidence(storage?NetworkEvidenceConfig.storageLifecycleV3(BUDGET):NetworkEvidenceConfig.fileLifecycleV2(BUDGET)).randomSeed(1).build();}
    private static PlatformProfile platform(boolean storage,boolean fat,double first,double second,int bandwidth){PlatformProfile.Builder p=PlatformProfile.builder("online-action-corpus");int[] ids={7,42};double[] mips={first,second};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,4000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],mips[i],1,512,bandwidth,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,4000,4096,10000,1000000));if(storage)p.sourceStorage(DataflowStorageSpec.of(30,1,.5,1));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));return p.build();}
}
