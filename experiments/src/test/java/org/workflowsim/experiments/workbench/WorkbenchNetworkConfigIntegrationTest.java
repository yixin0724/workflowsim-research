package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.TransferTraceSnapshot;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentManifestWriter;
import org.workflowsim.experiments.rerun.EvidenceCoreDiffer;
import org.workflowsim.experiments.rerun.RerunEvidenceReader;

/** NF002D1: opt-in configuration reaches each real candidate/seed without changing legacy OFF. */
class WorkbenchNetworkConfigIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logDisabled;
    @BeforeEach void prepare()throws Exception{
        logDisabled=Log.isDisabled();Log.disable();
        text(directory.resolve("flow.dax"),"<adag><job id=\"a\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/>"
                +"<uses file=\"edge\" link=\"output\" size=\"1000000\"/></job><job id=\"b\" runtime=\"0.5\">"
                +"<uses file=\"edge\" link=\"input\" size=\"1000000\"/></job><child ref=\"b\"><parent ref=\"a\"/></child></adag>");
    }
    @AfterEach void restore(){Log.setDisabled(logDisabled);}

    @Test void absentOptionStaysOffWithoutMutatingUserConfigurationOrManifestShape()throws Exception{
        JsonObject missingSimulation=configuration("flow.dax",2);missingSimulation.remove("simulation");
        for(JsonObject source:Arrays.asList(missingSimulation,configuration("flow.dax",2),network(false,0),network(true,0))){
            WorkbenchConfig config=read(source);
            assertEquals(source,config.getSource());
            for(WorkbenchConfig.Candidate candidate:config.getCandidates()){
                assertFalse(candidate.config.getNetworkEvidenceConfig().isEnabled());
                assertFalse(candidate.config.withRandomSeed(Long.MAX_VALUE).getNetworkEvidenceConfig().isEnabled());
                assertFalse(ExperimentManifestWriter.configurationSnapshot(candidate.config).containsKey("networkEvidence"));
            }
        }
    }

    @Test void onModeAndBudgetReachEveryCandidateAndSurviveSeedCopies()throws Exception{
        for(boolean fat:new boolean[]{false,true})for(int budget:new int[]{1,2000,Integer.MAX_VALUE}){
            JsonObject source=network(fat,budget);
            algorithms(source,algorithm("random",null,"RANDOM"),algorithm("local-heft",null,"LOCAL_HEFT"));
            WorkbenchConfig config=read(source);config.validateInputs();
            assertEquals(4,config.getRunCount());assertEquals(source,config.getSource());
            config.getSource().getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("maxTraceRecords",7);
            assertEquals(source,config.getSource(),"returned JSON cannot mutate the stored option");
            for(WorkbenchConfig.Candidate candidate:config.getCandidates())for(long seed:new long[]{1,17,Long.MIN_VALUE,Long.MAX_VALUE}){
                NetworkEvidenceConfig option=candidate.config.withRandomSeed(seed).getNetworkEvidenceConfig();
                assertEquals(NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,option.getMode());
                assertEquals(budget,option.getMaxTraceRecords());
            }
        }
    }

    @Test void presentInvalidObjectsNeverSilentlyBecomeOff()throws Exception{
        for(JsonElement invalid:Arrays.asList(JsonNull.INSTANCE,new JsonPrimitive(false),new JsonPrimitive("ON"),new JsonArray(),
                JsonParser.parseString("{\"mode\":\"OFF\",\"maxTraceRecords\":0}"),
                JsonParser.parseString("{\"mode\":\"unknown\",\"maxTraceRecords\":10}"),
                JsonParser.parseString("{\"mode\":\"FLUID_GROUP_LEDGER_V1\"}"),
                JsonParser.parseString("{\"maxTraceRecords\":10}"),
                JsonParser.parseString("{\"mode\":\"FLUID_GROUP_LEDGER_V1\",\"maxTraceRecords\":10,\"extra\":true}"))){
            JsonObject source=network(false,2000);source.getAsJsonObject("simulation").add("networkEvidence",invalid);
            assertThrows(IllegalArgumentException.class,()->read(source));
        }
    }

    @Test void budgetMustBePositiveExactIntAndDuplicateKeysStillReject()throws Exception{
        for(JsonElement invalid:Arrays.asList(JsonNull.INSTANCE,new JsonPrimitive(true),new JsonPrimitive("10"),new JsonPrimitive(0),
                new JsonPrimitive(-1),new JsonPrimitive(.5),new JsonPrimitive(new BigDecimal("1.0000000000000001")),new JsonPrimitive(new BigDecimal("2147483648")),new JsonPrimitive(new BigDecimal("1e9999")))){
            JsonObject source=network(false,2000);source.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").add("maxTraceRecords",invalid);
            assertThrows(IllegalArgumentException.class,()->read(source));
        }
        for(BigDecimal integral:Arrays.asList(new BigDecimal("1.0"),new BigDecimal("1e3"))){
            JsonObject source=network(false,2000);source.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("maxTraceRecords",integral);
            assertEquals(integral.intValueExact(),read(source).getCandidates().get(0).config.getNetworkEvidenceConfig().getMaxTraceRecords());
        }
        Path config=json(directory.resolve("duplicate.json"),network(false,2000));String raw=text(config);
        assertTrue(raw.contains("\"maxTraceRecords\": 2000"));
        text(config,raw.replace("\"maxTraceRecords\": 2000","\"maxTraceRecords\": 1, \"maxTraceRecords\": 2000"));
        assertThrows(IllegalArgumentException.class,()->WorkbenchConfig.read(config));
    }

    @Test void incompatiblePhysicsAndOnlineDispatchAreNotEnabledByTheRecordingOption()throws Exception{
        for(String model:Arrays.asList("LEGACY_WORKFLOWSIM_V1","PRE_EXECUTION_TRANSFER_DELAY_V1","FIXED_ENDPOINT_NO_CONTENTION_V1")){
            JsonObject source=network(false,2000);JsonObject sim=source.getAsJsonObject("simulation");sim.addProperty("dataMovementModel",model);
            if(model.startsWith("FIXED")){
                JsonObject fixed=new JsonObject();fixed.addProperty("accessBandwidth",1000);fixed.addProperty("latencySeconds",0);fixed.addProperty("sourceBandwidth",1000);sim.add("fixedEndpoint",fixed);
            }
            assertThrows(IllegalArgumentException.class,()->read(source));
        }
        JsonObject shared=network(false,2000);shared.getAsJsonObject("simulation").addProperty("fileSystem","SHARED");
        assertDoesNotThrow(()->read(shared).validateInputs(),"recording does not impose a new LOCAL-only policy on endpoint fluid models");
        JsonObject online=network(false,2000);algorithms(online,algorithm("fcfs","FCFS",null));
        assertThrows(IllegalArgumentException.class,()->read(online));
        JsonObject typo=network(false,2000);typo.getAsJsonObject("simulation").addProperty("networkEvidenceBudget",2000);
        assertThrows(IllegalArgumentException.class,()->read(typo));
    }

    @Test void invalidOptionFailsBeforeOutputDirectoryCreationOrSentinelReplacement()throws Exception{
        JsonObject source=network(false,2000);source.getAsJsonObject("simulation").getAsJsonObject("networkEvidence").addProperty("maxTraceRecords",.5);
        Path config=json(directory.resolve("invalid.json"),source),absent=directory.resolve("must-stay-absent");
        assertThrows(IllegalArgumentException.class,()->Workbench.run(config,absent));assertFalse(Files.exists(absent));
        Path existing=Files.createDirectory(directory.resolve("existing")),sentinel=existing.resolve("keep.txt");
        byte[] bytes="preserve existing output".getBytes(StandardCharsets.UTF_8);Files.write(sentinel,bytes);
        assertThrows(IllegalArgumentException.class,()->Workbench.run(config,existing));assertArrayEquals(bytes,Files.readAllBytes(sentinel));
        try(java.util.stream.Stream<Path> files=Files.list(existing)){assertEquals(1,files.count());}
    }

    @Test void realEndpointAndFatTreeRunsPublishValidatedFullAndTruncatedLedgersForEverySeed()throws Exception{
        for(boolean fat:new boolean[]{false,true})for(int budget:new int[]{1,2000}){
            JsonObject source=network(fat,budget);Path experiment=run(source);
            assertEquals(source,object(experiment.resolve("configuration.json")));
            JsonObject index=object(experiment.resolve("experiment.json"));assertEquals("COMPLETED_SUCCESSFULLY",index.get("status").getAsString());
            assertEquals(2,index.get("successfulRuns").getAsInt());
            JsonArray displayed=payload(experiment.resolve("report.html")).getAsJsonArray("runs");int position=0;
            for(JsonElement element:index.getAsJsonArray("runs")){
                JsonObject row=element.getAsJsonObject();Path manifestPath=experiment.resolve(row.get("manifest").getAsString());
                ExperimentArtifactValidator.ValidationResult validated=ExperimentArtifactValidator.validate(manifestPath);
                assertNotNull(validated.getNetworkLedger());
                assertEquals(budget==1?TransferTraceSnapshot.Status.TRUNCATED:TransferTraceSnapshot.Status.COMPLETE,validated.getNetworkCaptureStatus());
                JsonObject manifest=object(manifestPath);assertEquals(3,manifest.getAsJsonArray("artifacts").size());
                assertEquals(budget,manifest.getAsJsonObject("configuration").getAsJsonObject("networkEvidence").get("maxTraceRecords").getAsInt());
                assertEquals(manifest,displayed.get(position++).getAsJsonObject().getAsJsonObject("manifest"),"display never mutates scientific evidence");
                assertFalse(object(validated.getMetrics()).has("networkEvidence"));
                assertEquals(budget!=1,NetworkLedgerCodec.decode(text(validated.getNetworkLedger())).getMetrics().getTransferMetrics().isAvailable());
            }
        }
    }

    @Test void onOffOnWorkbenchRunsAreIsolatedAndOffKeepsItsOldArtifacts()throws Exception{
        JsonObject source=network(false,2000);Path first=run(source);
        JsonObject off=source.deepCopy();off.getAsJsonObject("simulation").remove("networkEvidence");Path middle=run(off),last=run(source);
        for(long seed:new long[]{1,17}){
            Path offManifest=middle.resolve("runs/random-s"+seed+"/result.manifest.json");
            assertNull(ExperimentArtifactValidator.validate(offManifest).getNetworkLedger());
            assertFalse(Files.exists(offManifest.getParent().resolve("result.network-ledger.json")));
            assertFalse(object(offManifest).getAsJsonObject("configuration").has("networkEvidence"));
            assertEquals(2,object(offManifest).getAsJsonArray("artifacts").size());
            Path a=first.resolve("runs/random-s"+seed),b=last.resolve("runs/random-s"+seed);
            assertTrue(EvidenceCoreDiffer.compare(RerunEvidenceReader.read(a),RerunEvidenceReader.read(b)).isIdenticalCore());
        }
    }

    private JsonObject network(boolean fat,int budget){
        JsonObject source=localConfiguration("flow.dax",2,fat);
        algorithms(source,algorithm("random",null,"RANDOM"));
        if(!fat)source.getAsJsonObject("simulation").addProperty("dataMovementModel","PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1");
        JsonArray seeds=new JsonArray();seeds.add(1);seeds.add(17);source.add("seeds",seeds);
        if(budget>0){JsonObject option=new JsonObject();option.addProperty("mode","FLUID_GROUP_LEDGER_V1");option.addProperty("maxTraceRecords",budget);source.getAsJsonObject("simulation").add("networkEvidence",option);}
        return source;
    }
    private WorkbenchConfig read(JsonObject source)throws Exception{return WorkbenchConfig.read(json(directory.resolve("config-"+(serial++)+".json"),source));}
    private Path run(JsonObject source)throws Exception{return Workbench.run(json(directory.resolve("config-"+(serial++)+".json"),source),directory.resolve("output-"+(serial++)));}
}
