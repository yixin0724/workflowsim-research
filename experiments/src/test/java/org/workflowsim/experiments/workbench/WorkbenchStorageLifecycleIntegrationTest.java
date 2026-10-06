package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;

class WorkbenchStorageLifecycleIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void publicRunAndStandaloneUseOneValidatedStorageProjection()throws Exception{
        for(boolean inputs:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){
            Path experiment=run(configuration(inputs,shared,fat,2000)),manifest=manifest(experiment),standalone=directory.resolve("standalone-"+(serial++)+".html");Workbench.main(new String[]{"report",manifest.toString(),standalone.toString()});JsonObject row=displayed(experiment.resolve("report.html")),other=displayed(standalone),view=row.getAsJsonObject("storageLifecycle");
            assertNotNull(view);assertEquals(view,other.get("storageLifecycle"));assertEquals("workflowsim-storage-lifecycle-display-v3",view.get("schema").getAsString());assertTrue(view.get("contextValidated").getAsBoolean());assertTrue(view.get("quiescent").getAsBoolean());assertEquals("0",view.get("pendingOutputFileCount").getAsString());assertEquals("0",view.get("waitingStoreInputCount").getAsString());
            assertFalse(row.has("fileLifecycle"));assertFalse(row.has("networkEvidence"));assertEquals(object(manifest),row.get("manifest"));assertEquals("1000000",view.getAsJsonObject("totals").get("admittedOutputPayloadBytes").getAsString());assertFalse(object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject().has("storageLifecycle"));assertFalse(text(standalone).contains("fetch("));
        }
    }
    @Test void offHasDeclaredStorageButNoInventedLifecycleCapture()throws Exception{
        Path experiment=run(configuration(true,true,false,0));JsonObject row=displayed(experiment.resolve("report.html"));assertFalse(row.has("storageLifecycle"));assertTrue(row.getAsJsonObject("manifest").getAsJsonObject("platform").has("sourceStorage"));assertTrue(row.getAsJsonObject("manifest").has("dataflowPlan"));
    }
    @Test void rejectedCaptureIsFailedNotAnApparentZeroCopySuccess()throws Exception{
        JsonObject config=configuration(true,true,false,1);Path experiment=run(config);JsonObject row=displayed(experiment.resolve("report.html"));assertEquals("FAILED",row.get("status").getAsString());assertFalse(row.has("manifest"));assertFalse(row.has("storageLifecycle"));
    }
    @Test void storageDeclarationIsStrictAndCannotBeIgnoredByAnotherModel()throws Exception{
        JsonObject base=configuration(true,true,false,2000);for(String mode:new String[]{"missing","null","zero","wrongHost","wrongType","extra","oldModel","oldEvidence"}){
            JsonObject c=base.deepCopy(),p=c.getAsJsonObject("platform"),s=p.getAsJsonObject("sourceStorage");switch(mode){case "missing":p.remove("sourceStorage");break;case "null":p.add("sourceStorage",com.google.gson.JsonNull.INSTANCE);break;case "zero":s.addProperty("writeBandwidthMbPerSecond",0);break;case "wrongHost":s.addProperty("attachmentHostId",3);break;case "wrongType":s.addProperty("readBandwidthMbPerSecond","1");break;case "extra":s.addProperty("guess",1);break;case "oldModel":c.getAsJsonObject("simulation").addProperty("dataMovementModel","COHERENT_FILE_DATAFLOW_V2");break;default:c.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("mode","FILE_LIFECYCLE_V2");}
            Path path=json(directory.resolve(mode+".json"),c);assertThrows(IllegalArgumentException.class,()->WorkbenchConfig.read(path));
        }
    }
    @Test void unsupportedPlannersDoNotSilentlyBecomeStorageEstimators()throws Exception{
        JsonObject config=configuration(false,true,false,2000);algorithms(config,algorithm("heft",null,"LOCAL_HEFT"));Path path=json(directory.resolve("planner.json"),config);assertThrows(IllegalArgumentException.class,()->WorkbenchConfig.read(path));
    }
    @Test void validatedStorageViewDoesNotRereadChangedManifestOrSidecar()throws Exception{
        Path manifest=manifest(run(configuration(true,true,false,2000)));ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(manifest);JsonObject expected=checked.getManifestSnapshot();Files.write(manifest,"{}".getBytes(StandardCharsets.UTF_8));Files.write(checked.getStorageLifecycle(),"changed".getBytes(StandardCharsets.UTF_8));Map<String,Object> row=new LinkedHashMap<>();HtmlReports.validatedReport(checked).attachTo(row);assertEquals(expected,row.get("manifest"));assertNotNull(row.get("storageLifecycle"));assertThrows(IOException.class,()->HtmlReports.validatedReport(manifest));
    }
    @Test void loaderClearsStorageWhenSwitchingToV2AndOff()throws Exception{
        Path storage=manifest(run(configuration(true,true,false,2000)));JsonObject v2=configuration(false,true,false,2000);v2.getAsJsonObject("platform").remove("sourceStorage");v2.getAsJsonObject("simulation").addProperty("dataMovementModel","COHERENT_FILE_DATAFLOW_V2");v2.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("mode","FILE_LIFECYCLE_V2");Path file=manifest(run(v2));Path off=manifest(run(configuration(true,true,false,0)));Map<String,Object> row=new LinkedHashMap<>();HtmlReports.validatedReport(storage).attachTo(row);assertTrue(row.containsKey("storageLifecycle"));HtmlReports.validatedReport(file).attachTo(row);assertFalse(row.containsKey("storageLifecycle"));assertTrue(row.containsKey("fileLifecycle"));HtmlReports.validatedReport(off).attachTo(row);assertFalse(row.containsKey("storageLifecycle"));assertFalse(row.containsKey("fileLifecycle"));
    }
    @Test void rehashedInvalidStorageCannotGenerateStandaloneOutput()throws Exception{
        Path manifest=manifest(run(configuration(true,true,false,2000))),ledger=manifest.getParent().resolve("result.storage-lifecycle.json");JsonObject doc=object(ledger);for(JsonElement row:doc.getAsJsonArray("events"))if(row.getAsJsonObject().get("type").getAsString().equals("COPY_ADMITTED")){row.getAsJsonObject().getAsJsonObject("payload").addProperty("ownerJobId",999);break;}ExperimentArtifactWriter.writeJson(ledger,doc);
        JsonObject root=object(manifest);byte[] bytes=Files.readAllBytes(ledger),digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder sha=new StringBuilder();for(byte b:digest)sha.append(String.format("%02x",b&255));for(JsonElement item:root.getAsJsonArray("artifacts"))if(item.getAsJsonObject().get("role").getAsString().equals("storage-lifecycle")){item.getAsJsonObject().addProperty("sha256",sha.toString());item.getAsJsonObject().addProperty("sizeBytes",bytes.length);}ExperimentArtifactWriter.writeJson(manifest,root);Path output=directory.resolve("blocked/report.html");assertThrows(IOException.class,()->Workbench.main(new String[]{"report",manifest.toString(),output.toString()}));assertFalse(Files.exists(output.getParent()));
    }
    private JsonObject configuration(boolean inputs,boolean shared,boolean fat,int budget)throws Exception{
        Path input=text(directory.resolve("data-"+(serial++)+".dax"),"<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>");JsonObject c=localConfiguration(input.toString(),2,fat);algorithms(c,algorithm("random",null,"RANDOM"));JsonArray seeds=new JsonArray();seeds.add(1);c.add("seeds",seeds);JsonObject sim=c.getAsJsonObject("simulation");sim.addProperty("fileSystem",inputs?"SHARED":"LOCAL");sim.addProperty("dataMovementModel",shared?"COHERENT_STORAGE_DATAFLOW_V3":"COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3");if(budget>0){JsonObject option=new JsonObject();option.addProperty("mode","FILE_STORAGE_LIFECYCLE_V3");option.addProperty("maxTraceRecords",budget);sim.add("networkEvidence",option);}JsonObject source=new JsonObject();source.addProperty("attachmentHostId",0);source.addProperty("readBandwidthMbPerSecond",1);source.addProperty("writeBandwidthMbPerSecond",.5);source.addProperty("networkBandwidthMbPerSecond",2);c.getAsJsonObject("platform").add("sourceStorage",source);return c;
    }
    private Path run(JsonObject config)throws Exception{return Workbench.run(json(directory.resolve("config-"+(serial++)+".json"),config),directory.resolve("runs-"+(serial++)));}
    private static Path manifest(Path experiment){return experiment.resolve("runs/random-s1/result.manifest.json");}
    private static JsonObject displayed(Path path)throws Exception{return payload(path).getAsJsonArray("runs").get(0).getAsJsonObject();}
}
