package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** NF002B3 strict wire/immutable reconstruction tests, before any artifact export wiring. */
class NetworkLedgerCodecTest {
    private static final DataMovementModel.Kind KIND=DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;
    private static final List<String> PATH=Arrays.asList("VM:7","VM:42");

    @Test void completeEvidenceAndExactMetricsRoundTripWithoutPathsOrWallClocks() {
        NetworkRunEvidence original=sample(100);
        String json=NetworkLedgerCodec.encode(original);NetworkLedgerCodec.Decoded decoded=NetworkLedgerCodec.decode(json);
        assertEquals(json,NetworkLedgerCodec.encode(decoded.getEvidence()));
        assertEquals(NetworkLedgerCodec.SCHEMA,JsonParser.parseString(json).getAsJsonObject().get("schema").getAsString());
        assertTrue(decoded.getMetrics().getTransferMetrics().isAvailable());
        assertEquals(0,new BigDecimal("1500").compareTo(decoded.getMetrics().getTransferMetrics().getServicedBalanceDeltaBytes()));
        assertEquals(12.5,decoded.getMetrics().getTransferMetrics().getMeanEffectiveFctSeconds(),0);
        assertFalse(json.contains("workingDirectory"));assertFalse(json.contains("wallClock"));
    }

    @Test void allRealTruncatedPrefixesRoundTripAndKeepUnavailableTotals() {
        int full=sample(100).getTraceSnapshot().getEvents().size();
        for(int budget=1;budget<full;budget++){
            NetworkRunEvidence original=sample(budget);NetworkLedgerCodec.Decoded decoded=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(original));
            assertEquals(TransferTraceSnapshot.Status.TRUNCATED,decoded.getEvidence().getTraceSnapshot().getStatus());
            assertFalse(decoded.getMetrics().getTransferMetrics().isAvailable());
            assertNull(decoded.getMetrics().getTransferMetrics().getAdmittedPayloadBytes());
            assertEquals(NetworkRunMetrics.LocalityStatus.TRUNCATED_TRACE,decoded.getMetrics().getLocalityStatus());
            assertEquals(NetworkLedgerCodec.encode(original),NetworkLedgerCodec.encode(decoded.getEvidence()));
        }
    }

    @Test void noEngineZeroAndMissingInputCountersRemainDistinct() {
        NetworkEvidenceConfig cfg=NetworkEvidenceConfig.fluidGroupLedger(10);
        NetworkRunEvidence empty=NetworkRunEvidence.empty(cfg,KIND);
        NetworkLedgerCodec.Decoded decoded=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(empty));
        assertFalse(decoded.getEvidence().isEngineCreated());assertTrue(decoded.getMetrics().getTransferMetrics().isAvailable());
        assertEquals(Long.valueOf(0),decoded.getMetrics().getInputReferenceCount());
        NetworkRunEvidence noCounters=NetworkRunEvidence.capture(cfg,KIND,new TransferContentionEngine(10).getTraceSnapshot(),Collections.<NetworkFlowBinding>emptyList());
        decoded=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(noCounters));
        assertEquals(NetworkRunMetrics.LocalityStatus.INPUT_COUNTERS_UNAVAILABLE,decoded.getMetrics().getLocalityStatus());
    }

    @Test void largeSignedIdsAndCopiedCollectionsRemainExactAndImmutable() {
        NetworkLedgerCodec.Decoded d=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(sample(100)));
        assertEquals(Long.MAX_VALUE,d.getEvidence().getBindings().get(0).getExternalTransferId());
        assertEquals(Long.MIN_VALUE,d.getEvidence().getBindings().get(1).getExternalTransferId());
        assertThrows(UnsupportedOperationException.class,()->d.getEvidence().getBindings().clear());
        assertThrows(UnsupportedOperationException.class,()->d.getEvidence().getBindings().get(0).getTaskIds().add(9));
        assertThrows(UnsupportedOperationException.class,()->d.getEvidence().getTraceSnapshot().getEvents().clear());
        assertThrows(UnsupportedOperationException.class,()->d.getEvidence().getTraceSnapshot().getEvents().get(1).getStart().getOccupiedResources().clear());
    }

    @Test void schemaUnitsScopeAndNumericProfilesAreRequiredExactDeclarations() {
        JsonObject source=document();
        for(String key:Arrays.asList("schema","flowUnit","numericProfile","accountingVersion")){
            JsonObject wrong=source.deepCopy();wrong.addProperty(key,"unknown");bad(wrong);
            wrong=source.deepCopy();wrong.remove(key);bad(wrong);
        }
        for(String key:Arrays.asList("time","bytes","rate")){
            JsonObject wrong=source.deepCopy();wrong.getAsJsonObject("units").addProperty(key,"wrong");bad(wrong);
        }
    }

    @Test void configRequiresKnownOnModeAndPositiveIntegralBudget() {
        for(JsonElement bad:Arrays.asList(JsonNull.INSTANCE,new JsonPrimitive("100"),new JsonPrimitive(-1),
                new JsonPrimitive(0),new JsonPrimitive(.5),new JsonPrimitive(new BigDecimal("2147483648")))){
            JsonObject wrong=document();config(wrong).add("maxTraceRecords",bad);bad(wrong);
        }
        JsonObject wrong=document();config(wrong).addProperty("mode","OFF");bad(wrong);
        wrong=document();config(wrong).remove("mode");bad(wrong);
        wrong=document();evidence(wrong).add("config",JsonNull.INSTANCE);bad(wrong);
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decodeConfig(JsonNull.INSTANCE));
    }

    @Test void missingZeroFieldsCannotBeSuppliedByGsonConstructorDefaults() {
        JsonObject wrong=document();event(wrong,1).remove("effectiveTime");bad(wrong);
        wrong=document();event(wrong,0).getAsJsonObject("capacity").remove("capacityBytesPerSecond");bad(wrong);
        wrong=document();trace(wrong).remove("droppedCount");bad(wrong);
        wrong=document();evidence(wrong).remove("engineCreated");bad(wrong);
    }

    @Test void nullShapesAndWrongPayloadTypesAreNotSilentlyRepaired() {
        JsonObject wrong=document();event(wrong,1).add("start",JsonNull.INSTANCE);bad(wrong);
        wrong=document();event(wrong,1).add("complete",event(wrong,9).get("complete").deepCopy());bad(wrong);
        wrong=document();event(wrong,1).addProperty("observedTime",1.0);bad(wrong);
        wrong=document();event(wrong,4).addProperty("effectiveTime",9.0);bad(wrong);
    }

    @Test void integerAndBooleanFieldsRejectCoercionOrOverflow() {
        JsonObject wrong=document();event(wrong,1).addProperty("transferId",new BigDecimal("9223372036854775808"));bad(wrong);
        wrong=document();event(wrong,1).addProperty("admissionOrdinal",1.25);bad(wrong);
        wrong=document();evidence(wrong).addProperty("engineCreated","true");bad(wrong);
        wrong=document();bindings(wrong).get(0).getAsJsonObject().addProperty("jobId",new BigDecimal("2147483648"));bad(wrong);
        wrong=document();bindings(wrong).get(0).getAsJsonObject().getAsJsonArray("taskIds").set(0,new JsonPrimitive("5"));bad(wrong);
    }

    @Test void countersAndTheirDerivedPartitionMustAgree() {
        JsonObject wrong=document();evidence(wrong).getAsJsonObject("inputDemand").addProperty("localReferenceCount",3);bad(wrong);
        wrong=document();evidence(wrong).getAsJsonObject("inputDemand").addProperty("transferableReferenceBytes",1499);bad(wrong);
        wrong=document();evidence(wrong).getAsJsonObject("inputDemand").addProperty("scope","UNIQUE_FILES");bad(wrong);
    }

    @Test void bindingIdentitiesResourcesAndSourceScopeAreChecked() {
        JsonObject wrong=document();bindings(wrong).get(0).getAsJsonObject().addProperty("externalTransferId",7);bad(wrong);
        wrong=document();bindings(wrong).get(0).getAsJsonObject().getAsJsonArray("occupiedResources").set(0,new JsonPrimitive("VM:99"));bad(wrong);
        wrong=document();bindings(wrong).get(0).getAsJsonObject().addProperty("sourceScope","ACTUAL_FILE_REPLICA");bad(wrong);
        wrong=document();bindings(wrong).get(0).getAsJsonObject().add("parentJobId",JsonNull.INSTANCE);bad(wrong);
    }

    @Test void metricFieldsAreRecomputedRatherThanTrusted() {
        JsonObject wrong=document();transferMetrics(wrong).addProperty("admittedPayloadBytes",1501);bad(wrong);
        wrong=document();transferMetrics(wrong).addProperty("meanEffectiveFctSeconds",20);bad(wrong);
        wrong=document();transferMetrics(wrong).getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("capacityAreaBytes",3000);bad(wrong);
        wrong=document();wrong.getAsJsonObject("metrics").addProperty("localByteFraction",.5);bad(wrong);
    }

    @Test void falseCompletenessAndDropCountsAreRejected() {
        JsonObject wrong=document();trace(wrong).addProperty("droppedCount",1);bad(wrong);
        wrong=JsonParser.parseString(NetworkLedgerCodec.encode(sample(5))).getAsJsonObject();
        trace(wrong).addProperty("status","COMPLETE");trace(wrong).addProperty("droppedCount",0);bad(wrong);
        wrong=document();evidence(wrong).addProperty("engineCreated",false);bad(wrong);
    }

    @Test void removedServiceCannotBeHiddenByRenumberingTheLedger() {
        JsonObject wrong=document();JsonArray events=trace(wrong).getAsJsonArray("events");events.remove(5);
        for(int i=0;i<events.size();i++)events.get(i).getAsJsonObject().addProperty("sequence",i+1);
        bad(wrong);
    }

    @Test void duplicateKeysTrailingInputAndLenientJsonFormsAreRejected() {
        String valid=NetworkLedgerCodec.encode(sample(100));
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decode(valid.replaceFirst("\\\"schema\\\":", "\"schema\":\"duplicate\",\"schema\":")));
        for(String text:Arrays.asList(valid+"{}",valid+" //comment","{schema:'x'}",valid.substring(0,valid.lastIndexOf('}'))+",}"))
            assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decode(text));
    }

    @Test void pathologicalNumericExponentAndNestingAreBounded() {
        String valid=NetworkLedgerCodec.encode(sample(100));
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decode(valid.replace("1500","1e99999999")));
        StringBuilder deep=new StringBuilder();for(int i=0;i<100;i++)deep.append('[');deep.append('0');for(int i=0;i<100;i++)deep.append(']');
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decode(deep.toString()));
        JsonObject wrong=document();event(wrong,1).addProperty("effectiveTime",new BigDecimal("1e-4000"));bad(wrong);
    }

    @Test void binary64EquivalentSubnormalSpellingsCanBeReadAcrossRuntimeFormatters() {
        TransferContentionEngine engine=new TransferContentionEngine(10);engine.addTransfer(1,Double.MIN_VALUE,
                Collections.singletonList("VM:42"),1,0);engine.advance(Double.MIN_VALUE);
        NetworkFlowBinding b=NetworkFlowBinding.of(1,1,91,Collections.singletonList(5),null,
                NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source","VM:42",Collections.singletonList("VM:42"));
        NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(10),KIND,engine.getTraceSnapshot(),Collections.singletonList(b));
        String json=NetworkLedgerCodec.encode(e).replace("4.9E-324","5e-324");
        NetworkLedgerCodec.Decoded d=NetworkLedgerCodec.decode(json);
        assertEquals(Double.MIN_VALUE,d.getEvidence().getTraceSnapshot().getEngineTime(),0);
    }

    @Test void exactDecimalAggregatesMayExceedBinary64Range() {
        TransferContentionEngine engine=new TransferContentionEngine(20);List<NetworkFlowBinding> rows=new ArrayList<>();
        for(int i=1;i<=2;i++){
            engine.addTransfer(i,Double.MAX_VALUE,Collections.singletonList("VM:42"),1,0);
            rows.add(NetworkFlowBinding.of(i,i,90+i,Collections.singletonList(i),null,
                    NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source","VM:42",Collections.singletonList("VM:42")));
        }
        engine.advance(Double.MAX_VALUE);NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(20),KIND,engine.getTraceSnapshot(),rows);
        NetworkLedgerCodec.Decoded d=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(e));
        assertEquals(0,new BigDecimal(Double.MAX_VALUE).multiply(BigDecimal.valueOf(2))
                .compareTo(d.getMetrics().getTransferMetrics().getAdmittedPayloadBytes()));
    }

    @Test void unknownFieldsAndUnretainedBindingsAreRejectedRatherThanDropped() {
        JsonObject wrong=document();event(wrong,1).getAsJsonObject("start").addProperty("fake",1);bad(wrong);
        wrong=document();wrong.addProperty("ignored",true);bad(wrong);
        wrong=JsonParser.parseString(NetworkLedgerCodec.encode(sample(1))).getAsJsonObject();
        bindings(wrong).add(bindings(document()).get(0).deepCopy());bad(wrong);
    }

    @Test void encoderCannotPublishNumbersItsStrictReaderWouldReject() {
        NetworkEvidenceConfig cfg=NetworkEvidenceConfig.fluidGroupLedger(10);
        NetworkInputDemandSnapshot extreme=NetworkInputDemandSnapshot.of(1,0,new BigDecimal("1e-5000"),BigDecimal.ZERO);
        NetworkRunEvidence e=NetworkRunEvidence.capture(cfg,KIND,new TransferContentionEngine(10).getTraceSnapshot(),
                Collections.<NetworkFlowBinding>emptyList(),extreme);
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.encode(e));
    }

    @Test void hostileAndUnicodeResourceLabelsRemainLiteralData() {
        String key="<img src=x onerror=alert(1)>/节点\\\"";
        TransferContentionEngine engine=new TransferContentionEngine(10);
        engine.addTransfer(1,1,Collections.singletonList(key),1,0);engine.advance(1);
        NetworkFlowBinding binding=NetworkFlowBinding.of(1,1,91,Collections.singletonList(5),null,
                NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source",key,Collections.singletonList(key));
        NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(10),KIND,engine.getTraceSnapshot(),Collections.singletonList(binding));
        NetworkLedgerCodec.Decoded decoded=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(e));
        assertEquals(key,decoded.getEvidence().getBindings().get(0).getDestinationEndpoint());
        assertEquals(key,decoded.getEvidence().getTraceSnapshot().getEvents().get(0).getStart().getOccupiedResources().get(0));
    }

    @Test void encodeRejectsMissingOrSemanticallyInvalidCapture() {
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.encode(null));
        TransferTraceSnapshot invalid=new TransferTraceSnapshot(TransferTraceSnapshot.Status.COMPLETE,
                Collections.singletonList(TransferTraceEvent.start(1,0,1,1,100,PATH,100,50)),0,0);
        NetworkFlowBinding b=NetworkFlowBinding.of(1,1,91,Collections.singletonList(5),88,
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,"VM:7","VM:42",PATH);
        NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(1),KIND,invalid,Collections.singletonList(b));
        assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.encode(e));
    }

    private static NetworkRunEvidence sample(int budget){
        TransferContentionEngine engine=new TransferContentionEngine(budget);engine.setEndpointCapacity("VM:7",100);
        engine.addTransfer(Long.MAX_VALUE,500,PATH,100,0);engine.addTransfer(Long.MIN_VALUE,1000,PATH,100,0);engine.advance(20);
        List<NetworkFlowBinding> retained=new ArrayList<>();
        for(TransferTraceEvent event:engine.getTraceSnapshot().getEvents())if(event.getType()==TransferTraceEvent.Type.START)
            retained.add(NetworkFlowBinding.of(event.getTransferId(),event.getAdmissionOrdinal(),91,Collections.singletonList(5),88,
                    NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,"VM:7","VM:42",PATH));
        return NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(budget),KIND,engine.getTraceSnapshot(),retained,
                NetworkInputDemandSnapshot.of(2,0,new BigDecimal("1500"),BigDecimal.ZERO));
    }
    private static JsonObject document(){return JsonParser.parseString(NetworkLedgerCodec.encode(sample(100))).getAsJsonObject();}
    private static JsonObject evidence(JsonObject o){return o.getAsJsonObject("evidence");}
    private static JsonObject config(JsonObject o){return evidence(o).getAsJsonObject("config");}
    private static JsonObject trace(JsonObject o){return evidence(o).getAsJsonObject("traceSnapshot");}
    private static JsonObject event(JsonObject o,int index){return trace(o).getAsJsonArray("events").get(index).getAsJsonObject();}
    private static JsonArray bindings(JsonObject o){return evidence(o).getAsJsonArray("bindings");}
    private static JsonObject transferMetrics(JsonObject o){return o.getAsJsonObject("metrics").getAsJsonObject("transferMetrics");}
    private static void bad(JsonObject value){assertThrows(IllegalArgumentException.class,()->NetworkLedgerCodec.decode(value.toString()));}
}
