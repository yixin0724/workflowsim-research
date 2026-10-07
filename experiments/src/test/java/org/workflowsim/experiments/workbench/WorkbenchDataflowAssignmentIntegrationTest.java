package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.utils.Parameters;

class WorkbenchDataflowAssignmentIntegrationTest {
    @TempDir Path directory;private int serial;private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void normalRunAndStandaloneShareValidatedActionAndPhysicalViews()throws Exception{
        for(boolean storage:new boolean[]{false,true})for(boolean gate:new boolean[]{false,true}){if(!storage&&gate)continue;for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){Path experiment=run(configuration(storage,gate,shared,fat,2000)),manifest=manifest(experiment),standalone=directory.resolve("standalone-"+(serial++)+".html");Workbench.main(new String[]{"report",manifest.toString(),standalone.toString()});JsonObject row=displayed(experiment.resolve("report.html")),other=displayed(standalone),view=row.getAsJsonObject("dataflowAssignment");assertNotNull(view);assertEquals("DATAFLOW_BINDING_V1",other.get("candidate").getAsString());assertEquals(view,other.get("dataflowAssignment"));assertEquals("workflowsim-dataflow-assignment-display-v1",view.get("schema").getAsString());assertTrue(view.get("contextValidated").getAsBoolean());assertFalse(view.get("liveProgressReplayed").getAsBoolean());assertEquals("2",view.getAsJsonObject("summary").get("assignmentCount").getAsString());assertTrue(row.has(storage?"storageLifecycle":"fileLifecycle"));assertEquals(object(manifest),row.get("manifest"));assertFalse(object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject().has("dataflowAssignment"));assertTrue(text(standalone).contains("dataflow-assignment-section"));assertFalse(text(standalone).contains("fetch("));}}
    }
    @Test void explicitBindingDefaultsToInvalidPreplanningAndStaticDispatch()throws Exception{
        JsonObject c=configuration(false,false,true,false,2000);JsonObject a=c.getAsJsonArray("algorithms").get(0).getAsJsonObject();a.remove("scheduler");a.remove("planner");WorkbenchConfig parsed=WorkbenchConfig.read(json(directory.resolve("defaults.json"),c));assertTrue(parsed.getCandidates().get(0).config.getDataflowAssignmentConfig().isEnabled());assertEquals(Parameters.PlanningAlgorithm.INVALID,parsed.getCandidates().get(0).config.getPlanningAlgorithm());assertEquals(Parameters.SchedulingAlgorithm.STATIC,parsed.getCandidates().get(0).config.getSchedulingAlgorithm());
    }
    @Test void policySchemaAndCaptureRequirementsAreStrict()throws Exception{
        JsonObject base=configuration(true,true,true,false,2000);for(String mode:new String[]{"null","extra","policy","mode","off-capture","old-ledger","random"}){JsonObject c=base.deepCopy(),a=c.getAsJsonArray("algorithms").get(0).getAsJsonObject(),option=a.getAsJsonObject("dataflowAssignment");switch(mode){case "null":a.add("dataflowAssignment",com.google.gson.JsonNull.INSTANCE);break;case "extra":option.addProperty("fallback",true);break;case "policy":option.addProperty("policy","FCFS");break;case "mode":option.addProperty("mode","OFF");break;case "off-capture":c.getAsJsonObject("simulation").remove("networkEvidence");break;case "old-ledger":c.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("mode","FLUID_GROUP_LEDGER_V1");break;default:a.addProperty("planner","RANDOM");}Path path=json(directory.resolve(mode+".json"),c);assertThrows(IllegalArgumentException.class,()->WorkbenchConfig.read(path));}
    }
    @Test void bindingCannotBeMislabeledAsExistingCpuOnlineOrOfflineMappingTrack()throws Exception{
        JsonObject c=configuration(false,false,true,false,2000);c.getAsJsonArray("algorithms").add(algorithm("random","STATIC","RANDOM"));Path path=json(directory.resolve("mixed.json"),c);IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->WorkbenchConfig.read(path));assertTrue(error.getMessage().contains("decision layers"));
    }
    @Test void truncatedOnlineCaptureIsFailedWithoutAClaimedActionView()throws Exception{
        Path experiment=run(configuration(true,true,true,false,1));JsonObject row=displayed(experiment.resolve("report.html"));assertEquals("FAILED",row.get("status").getAsString());assertFalse(row.has("manifest"));assertFalse(row.has("dataflowAssignment"));
    }
    @Test void cachedValidatedSnapshotDoesNotReopenChangedActionFiles()throws Exception{
        Path path=manifest(run(configuration(true,true,true,false,2000)));ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(path);JsonObject expected=checked.getManifestSnapshot();Files.write(path,"{}".getBytes(StandardCharsets.UTF_8));Files.write(checked.getEvents(),"invalid".getBytes(StandardCharsets.UTF_8));Map<String,Object> row=new LinkedHashMap<>();HtmlReports.validatedReport(checked).attachTo(row);assertEquals(expected,row.get("manifest"));assertNotNull(row.get("dataflowAssignment"));assertThrows(IOException.class,()->HtmlReports.validatedReport(path));
    }
    @Test void loaderClearsActionViewWhenSwitchingToPreplannedPhysicalModel()throws Exception{
        Path online=manifest(run(configuration(true,true,true,false,2000)));JsonObject preplanned=configuration(true,true,true,false,2000);algorithms(preplanned,algorithm("online","STATIC","RANDOM"));Path fixed=manifest(run(preplanned));Map<String,Object> row=new LinkedHashMap<>();HtmlReports.validatedReport(online).attachTo(row);assertTrue(row.containsKey("dataflowAssignment"));HtmlReports.validatedReport(fixed).attachTo(row);assertFalse(row.containsKey("dataflowAssignment"));assertTrue(row.containsKey("storageLifecycle"));
    }
    @Test void rehashedInvalidActionCannotCreateStandaloneReport()throws Exception{
        Path path=manifest(run(configuration(true,true,true,false,2000))),events=path.getParent().resolve("result.events.jsonl");List<String> changed=new ArrayList<>();boolean done=false;for(String line:Files.readAllLines(events,StandardCharsets.UTF_8)){JsonObject event=JsonParser.parseString(line).getAsJsonObject();if(!done&&event.get("type").getAsString().equals("DATAFLOW_VM_ASSIGNED")){event.getAsJsonObject("attributes").getAsJsonObject("observation").addProperty("scopedFileCount",999);done=true;}changed.add(event.toString());}assertTrue(done);Files.write(events,changed,StandardCharsets.UTF_8);byte[] bytes=Files.readAllBytes(events);StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));JsonObject root=object(path);for(JsonElement item:root.getAsJsonArray("artifacts")){JsonObject entry=item.getAsJsonObject();if(entry.get("role").getAsString().equals("events")){entry.addProperty("sha256",hash.toString());entry.addProperty("sizeBytes",bytes.length);}}ExperimentArtifactWriter.writeJson(path,root);Path output=directory.resolve("rejected/report.html");assertThrows(IOException.class,()->Workbench.main(new String[]{"report",path.toString(),output.toString()}));assertFalse(Files.exists(output.getParent()));
    }
    private JsonObject configuration(boolean storage,boolean gate,boolean shared,boolean fat,int budget)throws Exception{
        Path input=text(directory.resolve("input-"+(serial++)+".dax"),"<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>");JsonObject c=localConfiguration(input.toString(),2,fat),a=algorithm("online","STATIC","INVALID"),assignment=new JsonObject();assignment.addProperty("mode","CONTROL_READY_ONLINE_ASSIGNMENT_V1");assignment.addProperty("policy","NOMINAL_INPUT_EARLIEST_RESERVATION_V1");a.add("dataflowAssignment",assignment);algorithms(c,a);JsonArray seeds=new JsonArray();seeds.add(1);c.add("seeds",seeds);JsonObject sim=c.getAsJsonObject("simulation");sim.addProperty("fileSystem",gate?"SHARED":"LOCAL");sim.addProperty("dataMovementModel",storage?(shared?"COHERENT_STORAGE_DATAFLOW_V3":"COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3"):(shared?"COHERENT_FILE_DATAFLOW_V2":"COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2"));JsonObject recording=new JsonObject();recording.addProperty("mode",storage?"FILE_STORAGE_LIFECYCLE_V3":"FILE_LIFECYCLE_V2");recording.addProperty("maxTraceRecords",budget);sim.add("networkEvidence",recording);if(storage){JsonObject store=new JsonObject();store.addProperty("attachmentHostId",0);store.addProperty("readBandwidthMbPerSecond",1);store.addProperty("writeBandwidthMbPerSecond",.5);store.addProperty("networkBandwidthMbPerSecond",2);c.getAsJsonObject("platform").add("sourceStorage",store);}return c;
    }
    private Path run(JsonObject config)throws Exception{return Workbench.run(json(directory.resolve("config-"+(serial++)+".json"),config),directory.resolve("runs-"+(serial++)));}
    private static Path manifest(Path experiment){return experiment.resolve("runs/online-s1/result.manifest.json");}
    private static JsonObject displayed(Path path)throws Exception{return payload(path).getAsJsonArray("runs").get(0).getAsJsonObject();}
}
