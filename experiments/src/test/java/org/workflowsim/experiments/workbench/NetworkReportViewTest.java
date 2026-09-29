package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkInputDemandSnapshot;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.data.TransferTraceEvent;

/** Typed validated capture -> bounded display only. Extreme labels do not pretend to be run-context artifacts. */
class NetworkReportViewTest {
    @TempDir Path directory;
    private static final int BUDGET=10000;
    private static final DataMovementModel.Kind MODEL=DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;

    @Test void absentZeroAndTruncatedRemainDifferentStates(){
        assertNull(NetworkReportView.fromDecoded(null,false));
        NetworkLedgerCodec.Decoded empty=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(NetworkRunEvidence.empty(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),MODEL)));
        JsonObject zero=tree(empty);
        assertEquals("COMPLETE",zero.get("captureStatus").getAsString());assertTrue(zero.get("metricsAvailable").getAsBoolean());
        assertFalse(zero.get("engineCreated").getAsBoolean());assertEquals("0",zero.getAsJsonObject("totals").get("admittedPayloadBytes").getAsString());
        assertEquals("0",zero.getAsJsonObject("flows").get("total").getAsString());
        assertTrue(zero.getAsJsonObject("fct").get("meanEffectiveSeconds").isJsonNull());
        TransferContentionEngine engine=new TransferContentionEngine(1);engine.setEndpointCapacity("A",100);engine.addTransfer(1,100,Collections.singletonList("A"),100,0);engine.advance(1);
        JsonObject prefix=tree(decoded(engine,1));
        assertEquals("TRUNCATED",prefix.get("captureStatus").getAsString());assertFalse(prefix.get("metricsAvailable").getAsBoolean());
        assertTrue(prefix.getAsJsonObject("totals").get("admittedPayloadBytes").isJsonNull());
        assertTrue(prefix.getAsJsonObject("flows").get("total").isJsonNull());assertTrue(prefix.getAsJsonObject("resources").get("total").isJsonNull());
        assertEquals(0,prefix.getAsJsonObject("flows").getAsJsonArray("rows").size());
        assertEquals("1",prefix.getAsJsonObject("events").get("shown").getAsString());
    }

    @Test void allNetworkNumericValuesAreStringsAndSignedIdsRemainExact(){
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(Long.MAX_VALUE,1,Collections.<String>emptyList(),1,0);
        engine.addTransfer(Long.MIN_VALUE,1,Collections.<String>emptyList(),1,0);engine.advance(1);
        JsonObject view=tree(decoded(engine,BUDGET));assertNoJsonNumbers(view);
        JsonArray flows=view.getAsJsonObject("flows").getAsJsonArray("rows");
        assertEquals(Long.toString(Long.MAX_VALUE),flows.get(0).getAsJsonObject().get("externalTransferId").getAsString());
        assertEquals(Long.toString(Long.MIN_VALUE),flows.get(1).getAsJsonObject().get("externalTransferId").getAsString());
        assertFalse(view.get("contextValidated").getAsBoolean());
    }

    @Test void reusedExternalIdJoinsItsOwnAttemptByAdmissionOrdinal(){
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(7,1,Collections.<String>emptyList(),1,0);engine.advance(1);
        engine.addTransfer(7,2,Collections.<String>emptyList(),1,1);engine.advance(3);
        JsonArray flows=tree(decoded(engine,BUDGET)).getAsJsonObject("flows").getAsJsonArray("rows");
        assertEquals("7",flows.get(0).getAsJsonObject().get("externalTransferId").getAsString());
        assertEquals("7",flows.get(1).getAsJsonObject().get("externalTransferId").getAsString());
        assertEquals("101",flows.get(0).getAsJsonObject().get("jobId").getAsString());
        assertEquals("102",flows.get(1).getAsJsonObject().get("jobId").getAsString());
    }

    @Test void hugeAndSubBinary64AggregatesKeepExactDecimalText(){
        TransferContentionEngine huge=new TransferContentionEngine(BUDGET);
        for(int id=1;id<=2;id++)huge.addTransfer(id,Double.MAX_VALUE,Collections.<String>emptyList(),1,0);
        huge.advance(Double.MAX_VALUE);NetworkLedgerCodec.Decoded large=decoded(huge,BUDGET);
        assertEquals(large.getMetrics().getTransferMetrics().getAdmittedPayloadBytes().toString(),tree(large).getAsJsonObject("totals").get("admittedPayloadBytes").getAsString());
        TransferContentionEngine tiny=new TransferContentionEngine(BUDGET);tiny.setEndpointCapacity("A",Double.MIN_VALUE);
        tiny.addTransfer(1,1,Collections.singletonList("A"),1,0);tiny.advance(Double.MIN_VALUE);NetworkLedgerCodec.Decoded small=decoded(tiny,BUDGET);
        String amount=tree(small).getAsJsonObject("totals").get("modeledRateAreaBytes").getAsString();
        assertEquals(new BigDecimal(Double.MIN_VALUE).multiply(new BigDecimal(Double.MIN_VALUE)).toString(),amount);
        assertTrue(new BigDecimal(amount).signum()>0);
        assertFalse(tree(small).getAsJsonObject("flows").getAsJsonArray("rows").get(0).getAsJsonObject().get("complete").getAsBoolean());
    }

    @Test void originalPositiveElapsedIsNotRecomputedFromRoundedClocks(){
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);double now=0x1.0p54;engine.advance(now);
        engine.addTransfer(1,1,Collections.<String>emptyList(),49,now);engine.advance(Math.nextUp(now));
        JsonObject view=tree(decoded(engine,BUDGET));assertEquals("0.0",view.getAsJsonObject("fct").get("meanEffectiveSeconds").getAsString());
        JsonObject service=null;for(JsonElement e:view.getAsJsonObject("events").getAsJsonArray("rows"))if("SERVICE_SEGMENT".equals(e.getAsJsonObject().get("type").getAsString()))service=e.getAsJsonObject();
        assertNotNull(service);assertTrue(service.get("detail").getAsString().contains("elapsed="+Double.toString(1.0/49.0)));
        assertTrue(new BigDecimal(view.getAsJsonObject("totals").get("completionResidualBytes").getAsString()).signum()>0);
    }

    @Test void flowResourceAndEventPreviewLimitsDoNotPretendCaptureWasTruncated(){
        for(int count:new int[]{NetworkReportView.FLOW_LIMIT,NetworkReportView.FLOW_LIMIT+1}){
            TransferContentionEngine e=new TransferContentionEngine(BUDGET);for(int i=0;i<count;i++)e.addTransfer(i,1,Collections.<String>emptyList(),1,0);e.advance(1);
            checkPreview(tree(decoded(e,BUDGET)),"flows",count,NetworkReportView.FLOW_LIMIT);
        }
        for(int count:new int[]{NetworkReportView.RESOURCE_LIMIT,NetworkReportView.RESOURCE_LIMIT+1}){
            TransferContentionEngine e=new TransferContentionEngine(BUDGET);for(int i=0;i<count;i++)e.setEndpointCapacity("A"+i,1);
            checkPreview(tree(decoded(e,BUDGET)),"resources",count,NetworkReportView.RESOURCE_LIMIT);
        }
        for(int count:new int[]{NetworkReportView.EVENT_LIMIT,NetworkReportView.EVENT_LIMIT+1}){
            TransferContentionEngine e=new TransferContentionEngine(BUDGET);for(int i=0;i<count;i++)e.setEndpointCapacity("A",i+1);
            checkPreview(tree(decoded(e,BUDGET)),"events",count,NetworkReportView.EVENT_LIMIT);
        }
    }

    @Test void nestedListsAndLabelsAreBoundedWithoutBreakingSurrogatePairs(){
        StringBuilder label=new StringBuilder();for(int i=0;i<NetworkReportView.LABEL_LIMIT-1;i++)label.append('x');label.append("😀tail");
        List<String> resources=new ArrayList<String>();for(int i=0;i<NetworkReportView.LIST_LIMIT+1;i++)resources.add(label.toString());
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(1,1,resources,1,0);engine.advance(1);
        List<Integer> tasks=new ArrayList<Integer>();for(int i=0;i<NetworkReportView.LIST_LIMIT+1;i++)tasks.add(i);
        NetworkFlowBinding binding=NetworkFlowBinding.of(1,1,101,tasks,null,NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,label.toString(),label.toString(),resources);
        NetworkRunEvidence evidence=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),MODEL,engine.getTraceSnapshot(),Collections.singletonList(binding));
        JsonObject view=tree(NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(evidence))),row=view.getAsJsonObject("flows").getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(NetworkReportView.LIST_LIMIT,row.getAsJsonArray("resources").size());assertEquals("1",row.get("resourcesOmitted").getAsString());
        assertEquals(NetworkReportView.LIST_LIMIT,row.getAsJsonArray("taskIds").size());assertEquals("1",row.get("taskIdsOmitted").getAsString());
        String shown=row.get("source").getAsString();assertTrue(shown.length()<=NetworkReportView.LABEL_LIMIT+1);
        assertFalse(Character.isHighSurrogate(shown.charAt(shown.length()-2)));assertTrue(shown.endsWith("…"));
        assertTrue(Long.parseLong(view.get("labelsClipped").getAsString())>0);
    }

    @Test void hostileLabelsRemainLiteralEscapedDisplayData()throws Exception{
        String label="</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029@@DATA@@";
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(1,1,Collections.singletonList(label),1,0);engine.advance(1);
        NetworkFlowBinding binding=NetworkFlowBinding.of(1,1,101,Collections.singletonList(1),null,NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source",label,Collections.singletonList(label));
        NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),MODEL,engine.getTraceSnapshot(),Collections.singletonList(binding));
        NetworkLedgerCodec.Decoded decoded=NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(e));
        Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("seed",Long.MAX_VALUE);row.put("candidate","typed-fixture");row.put("status","FAILED");row.put("networkEvidence",NetworkReportView.fromDecoded(decoded,false));
        Path report=directory.resolve("projection.html");HtmlReports.experiment("projection fixture",Collections.singletonList(row),report);
        JsonObject display=WorkbenchTestSupport.payload(report).getAsJsonArray("runs").get(0).getAsJsonObject().getAsJsonObject("networkEvidence");
        assertEquals(label,display.getAsJsonObject("flows").getAsJsonArray("rows").get(0).getAsJsonObject().get("destination").getAsString());
        String html=WorkbenchTestSupport.text(report);assertFalse(html.contains("<img src=x"));assertTrue(html.contains("\\u2028")&&html.contains("\\u2029"));
    }

    @Test void missingCountersAndNegativeGroupingGapAreNotLocalHitSavings(){
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(1,1e16,Collections.<String>emptyList(),1,0);engine.advance(1e16);
        NetworkLedgerCodec.Decoded absent=decoded(engine,BUDGET);JsonObject missing=tree(absent);
        assertEquals("INPUT_COUNTERS_UNAVAILABLE",missing.getAsJsonObject("locality").get("status").getAsString());
        assertTrue(missing.getAsJsonObject("locality").get("referenceCount").isJsonNull());
        NetworkRunEvidence e=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),MODEL,engine.getTraceSnapshot(),bindings(engine),
                NetworkInputDemandSnapshot.of(2,0,new BigDecimal("10000000000000001"),BigDecimal.ZERO));
        JsonObject local=tree(NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(e))).getAsJsonObject("locality");
        assertEquals("-1",local.get("admittedMinusTransferableReferenceBytes").getAsString());
        assertEquals("0",local.get("localReferenceCount").getAsString());assertEquals("0.0",local.get("localByteFraction").getAsString());
    }

    @Test void hugeDropCounterRemainsExactAndNoUnboundedRawLedgerIsEmbedded(){
        TransferContentionEngine engine=new TransferContentionEngine(1);engine.setEndpointCapacity("A",1);engine.addTransfer(1,1,Collections.singletonList("A"),1,0);engine.advance(1);
        JsonObject document=JsonParser.parseString(NetworkLedgerCodec.encode(capture(engine,1))).getAsJsonObject();
        document.getAsJsonObject("evidence").getAsJsonObject("traceSnapshot").addProperty("droppedCount",Long.MAX_VALUE);
        document.getAsJsonObject("metrics").getAsJsonObject("transferMetrics").addProperty("droppedRecordCount",Long.MAX_VALUE);
        JsonObject view=tree(NetworkLedgerCodec.decode(document.toString()));assertEquals(Long.toString(Long.MAX_VALUE),view.get("droppedRecords").getAsString());
        assertFalse(view.has("traceSnapshot"));assertFalse(view.has("evidence"));assertFalse(view.has("bindings"));
    }

    @Test void projectionCannotMutateEvidenceAndItsRootIsReadOnly(){
        TransferContentionEngine engine=new TransferContentionEngine(BUDGET);engine.addTransfer(1,1,Collections.<String>emptyList(),1,0);engine.advance(1);
        NetworkLedgerCodec.Decoded decoded=decoded(engine,BUDGET);String before=NetworkLedgerCodec.encode(decoded.getEvidence());
        Map<String,Object> view=NetworkReportView.fromDecoded(decoded,true);
        assertThrows(UnsupportedOperationException.class,()->view.put("captureStatus","OFF"));
        assertEquals(before,NetworkLedgerCodec.encode(decoded.getEvidence()));assertEquals(Boolean.TRUE,view.get("contextValidated"));
    }

    private static JsonObject tree(NetworkLedgerCodec.Decoded decoded){return new com.google.gson.GsonBuilder().serializeNulls().create().toJsonTree(NetworkReportView.fromDecoded(decoded,false)).getAsJsonObject();}
    private static NetworkLedgerCodec.Decoded decoded(TransferContentionEngine e,int budget){return NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(capture(e,budget)));}
    private static NetworkRunEvidence capture(TransferContentionEngine e,int budget){return NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(budget),MODEL,e.getTraceSnapshot(),bindings(e));}
    private static List<NetworkFlowBinding> bindings(TransferContentionEngine e){List<NetworkFlowBinding> rows=new ArrayList<NetworkFlowBinding>();
        for(TransferTraceEvent event:e.getTraceSnapshot().getEvents())if(event.getType()==TransferTraceEvent.Type.START)rows.add(NetworkFlowBinding.of(event.getTransferId(),event.getAdmissionOrdinal(),100+event.getAdmissionOrdinal().intValue(),Collections.singletonList(1),null,NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source","destination",event.getStart().getOccupiedResources()));return rows;}
    private static void assertNoJsonNumbers(JsonElement value){if(value.isJsonObject())for(Map.Entry<String,JsonElement> e:value.getAsJsonObject().entrySet())assertNoJsonNumbers(e.getValue());
        else if(value.isJsonArray())for(JsonElement e:value.getAsJsonArray())assertNoJsonNumbers(e);else if(value.isJsonPrimitive())assertFalse(value.getAsJsonPrimitive().isNumber(),value.toString());}
    private static void checkPreview(JsonObject view,String key,int total,int cap){JsonObject preview=view.getAsJsonObject(key);
        assertEquals("COMPLETE",view.get("captureStatus").getAsString());assertEquals("0",view.get("droppedRecords").getAsString());
        assertEquals(Integer.toString(total),preview.get("total").getAsString());assertEquals(Integer.toString(Math.min(total,cap)),preview.get("shown").getAsString());
        assertEquals(Integer.toString(Math.max(0,total-cap)),preview.get("omitted").getAsString());assertEquals(Math.min(total,cap),preview.getAsJsonArray("rows").size());}
}
