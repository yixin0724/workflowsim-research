package org.workflowsim.data;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Explicit producer for independent Python integration checks; never calls the Python checker. */
public final class NetworkLedgerPythonFixtures {
    private static final int BUDGET=10000;
    private NetworkLedgerPythonFixtures(){ }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: NetworkLedgerPythonFixtures <new-output-directory>");
        Path root=Paths.get(args[0]).toAbsolutePath().normalize();
        if(Files.exists(root))throw new IllegalArgumentException("Refusing existing fixture root: "+root);
        Files.createDirectories(root);List<String> inputs=new ArrayList<String>();
        Path data=root.resolve("data.dax"),zero=root.resolve("zero.dax");
        Files.write(data,("<adag><job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"0.5\"/>"
                +"<uses file=\"a\" link=\"output\" size=\"5000000\"/></job><job id=\"c\" runtime=\"2\">"
                +"<uses file=\"a\" link=\"input\" size=\"5000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(zero,"<adag><job id=\"z\" runtime=\"1\"/></adag>".getBytes(StandardCharsets.UTF_8));
        for(boolean fat:new boolean[]{false,true}){
            runtime(root,inputs,data,fat,10000,"full");runtime(root,inputs,data,fat,1,"prefix");
            runtime(root,inputs,zero,fat,10000,"zero");
        }
        Map<Integer,Integer> crossing=new LinkedHashMap<Integer,Integer>();crossing.put(10,1);crossing.put(20,2);
        Map<Integer,Integer> sameEdge=new LinkedHashMap<Integer,Integer>();sameEdge.put(10,0);sameEdge.put(20,0);
        runtime(root,inputs,data,true,10000,"cross-core1",NetworkTopologySpec.fatTree(4,5,1,crossing));
        runtime(root,inputs,data,true,10000,"cross-core3",NetworkTopologySpec.fatTree(4,5,3,crossing));
        runtime(root,inputs,data,true,10000,"same-edge",NetworkTopologySpec.fatTree(4,5,1,sameEdge));
        runtime(root,inputs,data,true,10000,"k2",NetworkTopologySpec.fatTree(2,5));
        TransferContentionEngine late=engine();late.setEndpointCapacity("A",100);
        add(late,1,500,100,0,"A");add(late,2,1000,100,0,"A");late.advance(20);save(root,inputs,"late",late);
        TransferContentionEngine weighted=engine();weighted.setEndpointCapacity("A",100);
        add(weighted,1,100,10,0,"A","A");add(weighted,2,800,100,0,"A");weighted.advance(10);save(root,inputs,"weighted",weighted);
        TransferContentionEngine subnormal=engine();double q=Double.MIN_VALUE;
        add(subnormal,11,192*q,128,0);add(subnormal,22,64*q,64,0);subnormal.advance(2*q);save(root,inputs,"zero-step",subnormal);
        TransferContentionEngine clock=engine();double now=0x1.0p54;clock.advance(now);add(clock,Long.MAX_VALUE,1,49,now);
        clock.advance(Math.nextUp(now));save(root,inputs,"large-clock",clock);
        TransferContentionEngine huge=engine();add(huge,1,Double.MAX_VALUE,1,0);add(huge,2,Double.MAX_VALUE,1,0);
        huge.advance(Double.MAX_VALUE);save(root,inputs,"huge-aggregate",huge);
        TransferContentionEngine slow=engine();slow.setEndpointCapacity("tiny",Double.MIN_VALUE);add(slow,1,1,1,0,"tiny");
        slow.advance(Double.MIN_VALUE);save(root,inputs,"below-binary64-area",slow);
        slow.advance(1);save(root,inputs,"infinite-derived-duration",slow);
        TransferContentionEngine after=engine();add(after,1,100,100,0,"A");after.advance(1);after.setEndpointCapacity("A",100);
        add(after,2,100,100,1,"A");after.advance(2);save(root,inputs,"late-capacity",after);
        Map<String,Object> index=new LinkedHashMap<String,Object>();index.put("schema","workflowsim-network-check-fixtures-v1");index.put("inputs",inputs);
        ExperimentArtifactWriter.writeJson(root.resolve("fixtures.json"),index);
        System.out.println("NETWORK_PYTHON_FIXTURES inputs="+inputs.size()+" root="+root);
    }
    private static TransferContentionEngine engine(){return new TransferContentionEngine(BUDGET);}
    private static void add(TransferContentionEngine e,long id,double bytes,double rate,double now,String...resources){e.addTransfer(id,bytes,Arrays.asList(resources),rate,now);}
    private static void save(Path root,List<String> inputs,String name,TransferContentionEngine engine)throws Exception{
        List<NetworkFlowBinding> bindings=new ArrayList<NetworkFlowBinding>();
        for(TransferTraceEvent e:engine.getTraceSnapshot().getEvents())if(e.getType()==TransferTraceEvent.Type.START)
            bindings.add(NetworkFlowBinding.of(e.getTransferId(),e.getAdmissionOrdinal(),1,Collections.singletonList(1),null,
                    NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source","VM:fixture",e.getStart().getOccupiedResources()));
        NetworkRunEvidence evidence=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),
                DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,engine.getTraceSnapshot(),bindings);
        String encoded=NetworkLedgerCodec.encode(evidence);NetworkLedgerCodec.decode(encoded);
        Path path=root.resolve(name+".network-ledger.json");Files.write(path,encoded.getBytes(StandardCharsets.UTF_8));
        inputs.add(root.relativize(path).toString().replace('\\','/'));
    }
    private static void runtime(Path root,List<String> inputs,Path dax,boolean fat,int budget,String tag)throws Exception{
        runtime(root,inputs,dax,fat,budget,tag,fat?NetworkTopologySpec.fatTree(4,5):null);
    }
    private static void runtime(Path root,List<String> inputs,Path dax,boolean fat,int budget,String tag,NetworkTopologySpec topology)throws Exception{
        PlatformProfile.Builder platform=PlatformProfile.builder("independent-network-fixture");int[] ids={7,42};
        for(int i=0;i<2;i++)platform.addHost(new PlatformProfile.HostSpec(10+10*i,2,2000,4096,100000,1000000))
                .addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,5,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],10+10*i);
        if(fat)platform.networkTopology(topology);
        SimulationConfig config=SimulationConfig.builder(dax.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1)
                .dataMovementModel(fat?DataMovementModel.fatTreeContentionV1():DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(budget)).build();
        SimulationReport report=new SimulationRunner().run(config,platform.build());
        ExperimentArtifactWriter.ExperimentArtifacts files=ExperimentArtifactWriter.write(report,root.resolve((fat?"fat-":"endpoint-")+tag),"result");
        ExperimentArtifactValidator.validate(files.getManifest());
        inputs.add(root.relativize(files.getManifest()).toString().replace('\\','/'));
    }
}
