package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.*;
import com.google.gson.Gson;
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
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;

/** NF002D2: both real public report paths embed validated network display data, never fetch sidecars in a browser. */
class WorkbenchNetworkReportIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logDisabled;
    @BeforeEach void quiet(){logDisabled=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logDisabled);}

    @Test void normalAndStandaloneReportsEmbedTheSameValidatedFullNetworkView()throws Exception{
        for(boolean fat:new boolean[]{false,true}){
            Path experiment=run(fat,2000,true),manifest=manifest(experiment);
            JsonObject normal=displayed(experiment.resolve("report.html"));Path standalone=standalone(manifest);
            JsonObject single=displayed(standalone),network=normal.getAsJsonObject("networkEvidence");
            assertNotNull(network);assertEquals(network,single.getAsJsonObject("networkEvidence"));
            assertEquals("COMPLETE",network.get("captureStatus").getAsString());assertTrue(network.get("contextValidated").getAsBoolean());
            assertTrue(network.get("metricsAvailable").getAsBoolean());assertEquals(object(manifest),normal.getAsJsonObject("manifest"));
            assertEquals(object(manifest),single.getAsJsonObject("manifest"));
            NetworkLedgerCodec.Decoded checked=ExperimentArtifactValidator.validate(manifest).getDecodedNetworkLedger();
            assertEquals(checked.getMetrics().getTransferMetrics().getAdmittedPayloadBytes().toString(),network.getAsJsonObject("totals").get("admittedPayloadBytes").getAsString());
            assertEquals(Long.toString(checked.getMetrics().getInputReferenceCount()),network.getAsJsonObject("locality").get("referenceCount").getAsString());
            assertTrue(network.getAsJsonObject("flows").getAsJsonArray("rows").size()>0);
            assertFalse(object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject().has("networkEvidence"),"display data is not retained scientific run metadata");
            assertFalse(text(standalone).contains("fetch("));assertTrue(text(standalone).contains("network-flow-table"));
        }
    }

    @Test void zeroFlowOnAndAbsentOffHaveDistinctPayloadsInBothPaths()throws Exception{
        for(int budget:new int[]{0,2000}){
            Path experiment=run(false,budget,false),manifest=manifest(experiment);JsonObject normal=displayed(experiment.resolve("report.html")),single=displayed(standalone(manifest));
            if(budget==0){assertFalse(normal.has("networkEvidence"));assertFalse(single.has("networkEvidence"));}
            else{
                JsonObject network=normal.getAsJsonObject("networkEvidence");assertEquals(network,single.get("networkEvidence"));
                assertEquals("COMPLETE",network.get("captureStatus").getAsString());assertEquals("0",network.get("recordedAdmissionCount").getAsString());
                assertEquals("0",network.getAsJsonObject("totals").get("servicedBalanceDeltaBytes").getAsString());
                assertEquals("0",network.getAsJsonObject("flows").get("total").getAsString());assertTrue(network.getAsJsonObject("fct").get("meanEffectiveSeconds").isJsonNull());
            }
        }
    }

    @Test void truncatedReportsEmbedOnlyPrefixEventsAndKeepTotalsUnavailable()throws Exception{
        for(boolean fat:new boolean[]{false,true}){
            Path experiment=run(fat,1,true);JsonObject network=displayed(experiment.resolve("report.html")).getAsJsonObject("networkEvidence");
            assertEquals(network,displayed(standalone(manifest(experiment))).get("networkEvidence"));
            assertEquals("TRUNCATED",network.get("captureStatus").getAsString());assertFalse(network.get("metricsAvailable").getAsBoolean());
            assertTrue(network.getAsJsonObject("totals").get("admittedPayloadBytes").isJsonNull());
            assertTrue(network.getAsJsonObject("flows").get("total").isJsonNull());assertEquals(0,network.getAsJsonObject("flows").getAsJsonArray("rows").size());
            assertEquals("1",network.getAsJsonObject("events").get("total").getAsString());assertTrue(Long.parseLong(network.get("droppedRecords").getAsString())>0);
        }
    }

    @Test void cachedDecodedResultIsReusableAndAbsentForOff()throws Exception{
        for(int budget:new int[]{0,2000}){
            ExperimentArtifactValidator.ValidationResult result=ExperimentArtifactValidator.validate(manifest(run(false,budget,true)));
            if(budget==0)assertNull(result.getDecodedNetworkLedger());
            else{
                assertNotNull(result.getDecodedNetworkLedger());assertSame(result.getDecodedNetworkLedger(),result.getDecodedNetworkLedger());
                assertEquals(result.getNetworkCaptureStatus(),result.getDecodedNetworkLedger().getEvidence().getTraceSnapshot().getStatus());
            }
        }
    }

    @Test void corruptAndCoherentlyRehashedInvalidLedgersFailBeforeStandaloneOutput()throws Exception{
        Path experiment=run(false,2000,true),sourceManifest=manifest(experiment);
        byte[] originalReport=Files.readAllBytes(experiment.resolve("report.html")),originalHistory=Files.readAllBytes(experiment.getParent().resolve("index.html"));
        for(String attack:new String[]{"hash","metrics","source"}){
            Path copy=directory.resolve("copy-"+attack);Files.createDirectories(copy);
            try(java.util.stream.Stream<Path> stream=Files.list(sourceManifest.getParent())){
                for(Path p:(Iterable<Path>)stream.filter(Files::isRegularFile)::iterator)Files.copy(p,copy.resolve(p.getFileName()));
            }
            Path ledger=copy.resolve("result.network-ledger.json");JsonObject document=object(ledger);
            if("source".equals(attack)){
                document.getAsJsonObject("evidence").getAsJsonArray("bindings").get(0).getAsJsonObject().addProperty("sourceEndpoint","VM:999");
                document=NetworkLedgerCodec.document(new Gson().fromJson(document.get("evidence"),NetworkRunEvidence.class));
                NetworkLedgerCodec.decode(document.toString());
            }else document.getAsJsonObject("metrics").getAsJsonObject("transferMetrics").addProperty("admittedPayloadBytes",123);
            ExperimentArtifactWriter.writeJson(ledger,document);
            if(!"hash".equals(attack))rehash(copy.resolve("result.manifest.json"),ledger);
            Path target=directory.resolve("must-not-create-"+attack).resolve("report.html");
            assertThrows(IOException.class,()->Workbench.main(new String[]{"report",copy.resolve("result.manifest.json").toString(),target.toString()}));
            assertFalse(Files.exists(target.getParent()));
        }
        assertArrayEquals(originalReport,Files.readAllBytes(experiment.resolve("report.html")));assertArrayEquals(originalHistory,Files.readAllBytes(experiment.getParent().resolve("index.html")));
    }

    @Test void unreferencedStaleSidecarCannotEnableNetworkDisplayForOff()throws Exception{
        Path experiment=run(false,0,true),manifest=manifest(experiment);
        Files.write(manifest.getParent().resolve("result.network-ledger.json"),"not a ledger".getBytes(StandardCharsets.UTF_8));
        assertFalse(displayed(standalone(manifest)).has("networkEvidence"));
    }

    @Test void validatedReportUsesTheSameManifestSnapshotAsItsCachedNetworkContext()throws Exception{
        Path manifest=manifest(run(false,2000,true));JsonObject original=object(manifest);
        ExperimentArtifactValidator.ValidationResult validated=ExperimentArtifactValidator.validate(manifest);
        JsonObject returned=validated.getManifestSnapshot();returned.remove("metrics");returned.addProperty("tamperedCopy",true);
        assertEquals(original,validated.getManifestSnapshot(),"returned snapshot must be a defensive copy");
        JsonObject replacement=original.deepCopy();replacement.remove("metrics");replacement.addProperty("unvalidatedReplacement",true);
        ExperimentArtifactWriter.writeJson(manifest,replacement);
        Map<String,Object> row=new LinkedHashMap<String,Object>();HtmlReports.validatedReport(validated).attachTo(row);
        assertEquals(original,row.get("manifest"),"cached network A must not be paired with a later unvalidated manifest B");
        assertNotNull(row.get("networkEvidence"));
        assertThrows(IOException.class,()->HtmlReports.validatedReport(manifest),"a new read must reject the replaced invalid file");
    }

    @Test void sharedValidatedLoaderPreservesManifestAndClearsStaleOptionalDisplay()throws Exception{
        Path on=manifest(run(false,2000,true)),off=manifest(run(false,0,true));Map<String,Object> row=new LinkedHashMap<String,Object>();
        HtmlReports.validatedReport(on).attachTo(row);assertNotNull(row.get("networkEvidence"));assertEquals(object(on),row.get("manifest"));
        HtmlReports.validatedReport(off).attachTo(row);assertFalse(row.containsKey("networkEvidence"));assertEquals(object(off),row.get("manifest"));
    }

    private Path run(boolean fat,int budget,boolean input)throws Exception{
        String name="workflow-"+(serial++)+".dax";String data=input?"<job id=\"a\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"0.5\"/><uses file=\"edge\" link=\"output\" size=\"1000000\"/></job>"
                +"<job id=\"b\" runtime=\"0.5\"><uses file=\"edge\" link=\"input\" size=\"1000000\"/></job><child ref=\"b\"><parent ref=\"a\"/></child>":"<job id=\"a\" runtime=\"1\"/>";
        text(directory.resolve(name),"<adag>"+data+"</adag>");JsonObject config=localConfiguration(name,2,fat);
        algorithms(config,algorithm("random",null,"RANDOM"));JsonArray seeds=new JsonArray();seeds.add(1);config.add("seeds",seeds);
        if(!fat)config.getAsJsonObject("simulation").addProperty("dataMovementModel","PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1");
        if(budget>0){JsonObject option=new JsonObject();option.addProperty("mode","FLUID_GROUP_LEDGER_V1");option.addProperty("maxTraceRecords",budget);config.getAsJsonObject("simulation").add("networkEvidence",option);}
        Path experiment=Workbench.run(json(directory.resolve("config-"+(serial++)+".json"),config),directory.resolve("output-"+(serial++)));
        assertEquals("COMPLETED_SUCCESSFULLY",object(experiment.resolve("experiment.json")).get("status").getAsString());return experiment;
    }
    private static Path manifest(Path experiment){return experiment.resolve("runs/random-s1/result.manifest.json");}
    private Path standalone(Path manifest)throws Exception{Path path=directory.resolve("standalone-"+(serial++)+".html");Workbench.main(new String[]{"report",manifest.toString(),path.toString()});return path;}
    private static JsonObject displayed(Path report)throws Exception{return payload(report).getAsJsonArray("runs").get(0).getAsJsonObject();}
    private static void rehash(Path path,Path ledger)throws Exception{
        JsonObject manifest=object(path);byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(ledger));StringBuilder sha=new StringBuilder();
        for(byte value:digest)sha.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));
        for(JsonElement entry:manifest.getAsJsonArray("artifacts"))if("network-ledger".equals(entry.getAsJsonObject().get("role").getAsString())){
            entry.getAsJsonObject().addProperty("sha256",sha.toString());entry.getAsJsonObject().addProperty("sizeBytes",Files.size(ledger));}
        ExperimentArtifactWriter.writeJson(path,manifest);
    }
}
