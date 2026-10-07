package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowAssignmentConfig;
import org.workflowsim.data.v2.DataflowAssignmentValidator;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class OnlineDataflowArtifactIntegrationTest {
    @TempDir Path directory;private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void allPhysicalAccessSharingAndTopologyVariantsExportVerifiedActions()throws Exception{
        for(boolean storage:new boolean[]{false,true})for(boolean storeInputs:new boolean[]{false,true}){if(!storage&&storeInputs)continue;for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){SimulationReport report=run(storage,storeInputs,shared,fat,2000);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("bundle-"+storage+storeInputs+shared+fat),"result");ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());assertNotNull(checked.getDataflowAssignment());assertEquals(2,checked.getDataflowAssignment().getAssignmentCount());assertEquals(2,checked.getDataflowAssignment().getInitialBindingCount());assertEquals(0,checked.getDataflowAssignment().getRetryReuseCount());assertEquals(DataflowAssignmentValidator.SCOPE,checked.getDataflowAssignment().getScope());assertEquals(2,checked.getManifestSnapshot().getAsJsonArray("dataflowComputeRequests").size());assertEquals("INVALID",checked.getManifestSnapshot().getAsJsonObject("configuration").get("planningAlgorithm").getAsString());}}
    }
    @Test void offAndTruncatedCapturesCannotExportOnlineCertification()throws Exception{
        for(int budget:new int[]{0,1}){SimulationReport report=run(true,true,true,false,budget);Path output=directory.resolve("rejected-"+budget);if(budget==0)assertThrows(UnsupportedOperationException.class,()->ExperimentArtifactWriter.write(report,output,"result"));else assertThrows(java.io.IOException.class,()->ExperimentArtifactWriter.write(report,output,"result"));assertFalse(Files.exists(output));}
    }
    @Test void newPolicyAndComputeRequestMetadataCannotBeIgnored()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle();JsonObject original=read(out.getManifest()),root=original.deepCopy();root.getAsJsonObject("configuration").getAsJsonObject("dataflowAssignment").addProperty("policy","UNKNOWN");save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));root=original.deepCopy();JsonObject request=root.getAsJsonArray("dataflowComputeRequests").get(0).getAsJsonObject();request.addProperty("lengthMi",request.get("lengthMi").getAsLong()+1);save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));root=original.deepCopy();root.getAsJsonObject("configuration").remove("dataflowAssignment");save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void repairedMainHashCannotHideMissingActionCoverage()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle();List<JsonObject> events=events(out);assignment(events,0).addProperty("type","WORKFLOW_PARSED");saveEvents(out,events);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void repairedMainHashCannotTurnPendingStoreIntoZeroInputCost()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle();List<JsonObject> events=events(out);JsonObject action=assignment(events,1),selected=selected(action);assertTrue(selected.get("conditionalStoreWait").getAsBoolean());assertTrue(selected.get("inputSeconds").getAsDouble()>0);selected.addProperty("inputSeconds",0);selected.addProperty("scoreFinishSeconds",Math.max(action.get("simulationTime").getAsDouble(),selected.get("cpuAvailableAt").getAsDouble())+selected.get("computeSeconds").getAsDouble());saveEvents(out,events);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void withinBoundProgressDriftRemainsExplicitlyOutsideExactProgressProof()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle();List<JsonObject> events=events(out);JsonObject action=assignment(events,1),selected=selected(action);assertTrue(selected.get("conditionalStoreWait").getAsBoolean());double old=selected.get("inputSeconds").getAsDouble();assertTrue(old>0);double changed=old*.75;selected.addProperty("inputSeconds",changed);selected.addProperty("scoreFinishSeconds",Math.max(action.get("simulationTime").getAsDouble()+changed,selected.get("cpuAvailableAt").getAsDouble())+selected.get("computeSeconds").getAsDouble());saveEvents(out,events);assertEquals(DataflowAssignmentValidator.SCOPE,ExperimentArtifactValidator.validate(out.getManifest()).getDataflowAssignment().getScope());
    }
    @Test void actionScopeCandidateInventoryAndObservationCountsAreChecked()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle();List<JsonObject> original=events(out),changed=cloneEvents(original);assignment(changed,0).getAsJsonObject("attributes").addProperty("auditScope","FULL_FLUID_PROOF");saveEvents(out,changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));changed=cloneEvents(original);assignment(changed,0).getAsJsonObject("attributes").getAsJsonObject("observation").addProperty("activeCopyCount",999);saveEvents(out,changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));changed=cloneEvents(original);assignment(changed,0).getAsJsonObject("attributes").getAsJsonArray("candidates").remove(0);saveEvents(out,changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void executionMiAndScopeCannotContradictTheCertifiedActionAfterRehash()throws Exception{
        List<org.junit.jupiter.api.function.Executable> checks=new ArrayList<>();for(boolean storage:new boolean[]{false,true})for(String field:new String[]{"taskLengthMi","effectiveExecutionLengthMi","taskTimingScope"})checks.add(()->{ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(run(storage,storage,true,false,2000),directory.resolve("execution-"+storage+"-"+field),"result");ExperimentArtifactValidator.validate(out.getManifest());List<JsonObject> all=events(out);boolean found=false;for(JsonObject event:all)if(event.get("type").getAsString().equals("TASK_EXECUTION_MODELED")){JsonObject attrs=event.getAsJsonObject("attributes");if(field.equals("taskTimingScope"))attrs.addProperty(field,"MEASURED_CPU_WINDOW");else attrs.addProperty(field,attrs.get(field).getAsLong()+1);found=true;break;}assertTrue(found);saveEvents(out,all);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()),"Contradicted "+field+" for storage="+storage);});assertAll(checks);
    }
    @Test void frozenComputeAndValidatedActionViewsCannotBeMutated()throws Exception{
        SimulationReport report=run(true,true,true,false,2000);assertThrows(UnsupportedOperationException.class,()->report.getDataflowComputeRequests().clear());ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("snapshots"),"result");ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());JsonArray first=checked.getDataflowAssignment().getAssignments();first.get(0).getAsJsonObject().getAsJsonObject("attributes").addProperty("auditScope","changed");assertEquals(DataflowAssignmentValidator.SCOPE,checked.getDataflowAssignment().getAssignments().get(0).getAsJsonObject().getAsJsonObject("attributes").get("auditScope").getAsString());
    }
    private ExperimentArtifactWriter.ExperimentArtifacts bundle()throws Exception{return ExperimentArtifactWriter.write(run(true,true,true,false,2000),directory.resolve("bundle"),"result");}
    private SimulationReport run(boolean storage,boolean storeInputs,boolean shared,boolean fat,int budget)throws Exception{
        Path input=directory.resolve("workflow.dax");if(!Files.exists(input))Files.write(input,("<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"unused\" link=\"output\" size=\"500000\"/><uses file=\"z\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"z\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>").getBytes(StandardCharsets.UTF_8));
        DataMovementModel model=storage?(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()):(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2());SimulationConfig config=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(storeInputs?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).networkEvidence(budget==0?NetworkEvidenceConfig.off():storage?NetworkEvidenceConfig.storageLifecycleV3(budget):NetworkEvidenceConfig.fileLifecycleV2(budget)).randomSeed(1).build();PlatformProfile.Builder p=PlatformProfile.builder("online-action-bundle");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+10*i;p.addHost(new PlatformProfile.HostSpec(host,2,4000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],i==0?1000:2000,1,512,2,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,4000,4096,10000,1000000));if(storage)p.sourceStorage(DataflowStorageSpec.of(30,1,.5,1));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));return new SimulationRunner().run(config,p.build());
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void save(Path path,JsonObject value)throws Exception{Files.write(path,value.toString().getBytes(StandardCharsets.UTF_8));}
    private static List<JsonObject> events(ExperimentArtifactWriter.ExperimentArtifacts out)throws Exception{List<JsonObject> events=new ArrayList<>();for(String line:Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8))events.add(JsonParser.parseString(line).getAsJsonObject());return events;}
    private static List<JsonObject> cloneEvents(List<JsonObject> original){List<JsonObject> copy=new ArrayList<>();for(JsonObject row:original)copy.add(row.deepCopy());return copy;}
    private static JsonObject assignment(List<JsonObject> events,int index){for(JsonObject event:events)if(event.get("type").getAsString().equals("DATAFLOW_VM_ASSIGNED")){if(index--==0)return event;}throw new AssertionError("Missing assignment");}
    private static JsonObject selected(JsonObject action){int chosen=action.getAsJsonObject("attributes").get("selectedVmId").getAsInt();for(JsonElement item:action.getAsJsonObject("attributes").getAsJsonArray("candidates")){JsonObject row=item.getAsJsonObject();if(row.get("vmId").getAsInt()==chosen)return row;}throw new AssertionError("Missing selected candidate");}
    private static void saveEvents(ExperimentArtifactWriter.ExperimentArtifacts out,List<JsonObject> events)throws Exception{List<String> lines=new ArrayList<>();for(JsonObject event:events)lines.add(event.toString());Files.write(out.getEvents(),lines,StandardCharsets.UTF_8);JsonObject root=read(out.getManifest());byte[] bytes=Files.readAllBytes(out.getEvents());StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));for(JsonElement item:root.getAsJsonArray("artifacts")){JsonObject row=item.getAsJsonObject();if(row.get("role").getAsString().equals("events")){row.addProperty("sha256",hash.toString());row.addProperty("sizeBytes",bytes.length);}}save(out.getManifest(),root);}
}
