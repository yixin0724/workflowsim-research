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

/** Public Workbench run and standalone report must use the separate validated V2 display schema. */
class WorkbenchFileLifecycleReportIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void bothEntryPointsEmbedTheSameV2ViewWithoutV1Fields()throws Exception{
        for(boolean shared:new boolean[]{true,false})for(boolean fat:new boolean[]{false,true}){
            Path experiment=run(shared,fat,2000,true,false),manifest=manifest(experiment);JsonObject normal=displayed(experiment.resolve("report.html"));Path standalone=standalone(manifest);JsonObject other=displayed(standalone);
            JsonObject view=normal.getAsJsonObject("fileLifecycle");assertNotNull(view);assertEquals(view,other.get("fileLifecycle"));assertEquals("workflowsim-file-lifecycle-display-v2",view.get("schema").getAsString());assertTrue(view.get("contextValidated").getAsBoolean());assertTrue(view.get("quiescent").getAsBoolean());
            assertFalse(normal.has("networkEvidence"));assertFalse(other.has("networkEvidence"));assertEquals(object(manifest),normal.get("manifest"));assertEquals(object(manifest),other.get("manifest"));
            assertEquals("1000000.5",view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());assertFalse(object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject().has("fileLifecycle"));
            assertFalse(text(standalone).contains("fetch("));assertTrue(text(standalone).contains("file-lifecycle-copy-table"));
        }
    }
    @Test void zeroPositiveCopiesAndOffHaveDifferentValidatedPayloads()throws Exception{
        for(int budget:new int[]{0,2000}){
            Path experiment=run(true,false,budget,false,false),manifest=manifest(experiment);JsonObject normal=displayed(experiment.resolve("report.html")),single=displayed(standalone(manifest));
            if(budget==0){assertFalse(normal.has("fileLifecycle"));assertFalse(single.has("fileLifecycle"));assertTrue(normal.getAsJsonObject("manifest").has("dataflowPlan"));}
            else{JsonObject view=normal.getAsJsonObject("fileLifecycle");assertEquals(view,single.get("fileLifecycle"));assertEquals("0",view.get("copyCount").getAsString());assertEquals("0",view.getAsJsonObject("copies").get("total").getAsString());}
        }
    }
    @Test void insufficientCaptureCannotProduceAnApparentlyCompleteV2Report()throws Exception{
        Path experiment=run(true,false,1,true,false);JsonObject row=displayed(experiment.resolve("report.html"));assertEquals("FAILED",row.get("status").getAsString());assertFalse(row.has("fileLifecycle"));assertFalse(row.has("manifest"));
    }
    @Test void cachedV2ProjectionAndManifestRemainPairedAfterFilesChange()throws Exception{
        Path manifest=manifest(run(true,false,2000,true,false));ExperimentArtifactValidator.ValidationResult validated=ExperimentArtifactValidator.validate(manifest);JsonObject original=validated.getManifestSnapshot();
        JsonObject replacement=original.deepCopy();replacement.remove("metrics");ExperimentArtifactWriter.writeJson(manifest,replacement);Files.write(validated.getFileLifecycle(),"invalid replacement".getBytes(StandardCharsets.UTF_8));
        Map<String,Object> row=new LinkedHashMap<>();HtmlReports.validatedReport(validated).attachTo(row);assertEquals(original,row.get("manifest"));assertNotNull(row.get("fileLifecycle"));assertFalse(row.containsKey("networkEvidence"));assertThrows(IOException.class,()->HtmlReports.validatedReport(manifest));
    }
    @Test void sharedLoaderClearsStaleDataWhenSwitchingV2V1AndOff()throws Exception{
        Path v2=manifest(run(true,false,2000,true,false)),v1=manifest(run(true,false,2000,true,true)),off=manifest(run(true,false,0,true,false));Map<String,Object> row=new LinkedHashMap<>();
        HtmlReports.validatedReport(v2).attachTo(row);assertTrue(row.containsKey("fileLifecycle"));assertFalse(row.containsKey("networkEvidence"));
        HtmlReports.validatedReport(v1).attachTo(row);assertFalse(row.containsKey("fileLifecycle"));assertTrue(row.containsKey("networkEvidence"));
        HtmlReports.validatedReport(off).attachTo(row);assertFalse(row.containsKey("fileLifecycle"));assertFalse(row.containsKey("networkEvidence"));
    }
    @Test void coherentlyRehashedInvalidFileEvidenceFailsBeforeStandaloneOutput()throws Exception{
        Path experiment=run(true,false,2000,true,false),manifest=manifest(experiment),ledger=manifest.getParent().resolve("result.file-lifecycle.json");JsonObject value=object(ledger);
        for(JsonElement e:value.getAsJsonArray("events"))if(e.getAsJsonObject().get("type").getAsString().equals("COPY_ADMITTED")){e.getAsJsonObject().getAsJsonObject("payload").addProperty("destinationVmId",999);break;}
        ExperimentArtifactWriter.writeJson(ledger,value);JsonObject root=object(manifest);byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ledger));StringBuilder sha=new StringBuilder();for(byte b:digest)sha.append(String.format("%02x",b&255));
        for(JsonElement item:root.getAsJsonArray("artifacts"))if(item.getAsJsonObject().get("role").getAsString().equals("file-lifecycle")){item.getAsJsonObject().addProperty("sha256",sha.toString());item.getAsJsonObject().addProperty("sizeBytes",Files.size(ledger));}ExperimentArtifactWriter.writeJson(manifest,root);
        Path target=directory.resolve("not-created/report.html");assertThrows(IOException.class,()->Workbench.main(new String[]{"report",manifest.toString(),target.toString()}));assertFalse(Files.exists(target.getParent()));
    }
    private Path run(boolean shared,boolean fat,int budget,boolean input,boolean legacy)throws Exception{
        String name="workflow-"+(serial++)+".dax";String body=input?"<job id=\"a\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"data\" link=\"output\" size=\"1000000\"/></job><job id=\"b\" runtime=\"1\"><uses file=\"data\" link=\"input\" size=\"1000000\"/></job><child ref=\"b\"><parent ref=\"a\"/></child>":"<job id=\"a\" runtime=\"1\"/>";
        text(directory.resolve(name),"<adag>"+body+"</adag>");JsonObject config=localConfiguration(name,2,fat);algorithms(config,algorithm("random",null,"RANDOM"));JsonArray seeds=new JsonArray();seeds.add(1);config.add("seeds",seeds);
        config.getAsJsonObject("simulation").addProperty("dataMovementModel",legacy?"PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1":shared?"COHERENT_FILE_DATAFLOW_V2":"COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2");
        if(budget>0){JsonObject option=new JsonObject();option.addProperty("mode",legacy?"FLUID_GROUP_LEDGER_V1":"FILE_LIFECYCLE_V2");option.addProperty("maxTraceRecords",budget);config.getAsJsonObject("simulation").add("networkEvidence",option);}
        return Workbench.run(json(directory.resolve("config-"+(serial++)+".json"),config),directory.resolve("output-"+(serial++)));
    }
    private static Path manifest(Path experiment){return experiment.resolve("runs/random-s1/result.manifest.json");}
    private Path standalone(Path manifest)throws Exception{Path target=directory.resolve("standalone-"+(serial++)+".html");Workbench.main(new String[]{"report",manifest.toString(),target.toString()});return target;}
    private static JsonObject displayed(Path path)throws Exception{return payload(path).getAsJsonArray("runs").get(0).getAsJsonObject();}
}
