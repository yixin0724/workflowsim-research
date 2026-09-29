package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.NetworkRunMetrics;
import org.workflowsim.data.NetworkTraceMetrics;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.experiment.ExperimentArtifactValidator;

/** Explicit browser inputs: actual public run/report paths plus clearly scoped typed-projection stress cases. */
final class NetworkBrowserFixtures {
    private static final int BUDGET=10000;
    private NetworkBrowserFixtures(){ }

    static void append(Path output,List<Map<String,Object>> reports,Path offManifest)throws Exception{
        // Existing fixture configurations are recording-OFF. Require the new panel's state there too.
        for(Map<String,Object> report:reports){
            List<Map<String,Object>> expected=new ArrayList<Map<String,Object>>();
            for(Object state:(List<?>)report.get("statuses"))expected.add(state("FAILED".equals(state)?"NO_RESULT":"OFF"));
            report.put("network",expected);
        }
        Path data=WorkbenchTestSupport.text(output.resolve("network-display.dax"),"<adag><job id=\"a\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"0.5\"/>"
                +"<uses file=\"edge\" link=\"output\" size=\"1000000\"/></job><job id=\"b\" runtime=\"0.5\"><uses file=\"edge\" link=\"input\" size=\"1000000\"/></job><child ref=\"b\"><parent ref=\"a\"/></child></adag>");
        Path zero=WorkbenchTestSupport.text(output.resolve("network-zero.dax"),"<adag><job id=\"z\" runtime=\"1\"/></adag>");
        Map<String,Path> manifests=new LinkedHashMap<String,Path>();
        for(boolean fat:new boolean[]{false,true})for(String kind:Arrays.asList("full","prefix","zero")){
            String name="ledger-"+(fat?"fat-":"endpoint-")+kind;int budget="prefix".equals(kind)?1:2000;
            JsonObject config=WorkbenchTestSupport.localConfiguration(("zero".equals(kind)?zero:data).toString(),2,fat);
            WorkbenchTestSupport.algorithms(config,WorkbenchTestSupport.algorithm("random",null,"RANDOM"));JsonArray seeds=new JsonArray();seeds.add(1);config.add("seeds",seeds);
            if(!fat)config.getAsJsonObject("simulation").addProperty("dataMovementModel","PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1");
            JsonObject option=new JsonObject();option.addProperty("mode","FLUID_GROUP_LEDGER_V1");option.addProperty("maxTraceRecords",budget);config.getAsJsonObject("simulation").add("networkEvidence",option);
            Path experiment=Workbench.run(WorkbenchTestSupport.json(output.resolve(name+".json"),config),output.resolve(name));
            Path manifest=experiment.resolve("runs/random-s1/result.manifest.json");manifests.put(name,manifest);
            Map<String,Object> row=row(manifest,"random");Map<String,Object> expected=expect(manifest);
            reports.add(spec(name,experiment.resolve("report.html"),Collections.singletonList(row),Collections.singletonList(expected)));
            Path standalone=output.resolve(name+"-standalone.html");Workbench.main(new String[]{"report",manifest.toString(),standalone.toString()});
            Map<String,Object> standaloneRow=new LinkedHashMap<String,Object>(row);standaloneRow.put("candidate","RANDOM");
            reports.add(spec(name+"-standalone",standalone,Collections.singletonList(standaloneRow),Collections.singletonList(expected)));
        }
        List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>(),expected=new ArrayList<Map<String,Object>>();
        Path full=manifests.get("ledger-endpoint-full"),prefix=manifests.get("ledger-endpoint-prefix"),none=manifests.get("ledger-endpoint-zero");
        Map<String,Object> first=row(full,"full");rows.add(first);expected.add(expect(full));
        Map<String,Object> failed=new LinkedHashMap<String,Object>();failed.put("candidate","failed-after-full");failed.put("seed",1L);failed.put("status","FAILED");failed.put("error","Deliberate no-validated-result browser control");
        failed.put("networkEvidence",first.get("networkEvidence")); // stale data must still be ignored without a manifest
        rows.add(failed);expected.add(state("NO_RESULT"));rows.add(row(offManifest,"off"));expected.add(state("OFF"));
        rows.add(row(prefix,"prefix"));expected.add(expect(prefix));rows.add(row(none,"zero"));expected.add(expect(none));
        rows.add(row(full,"full-again"));expected.add(expect(full));
        Path switching=output.resolve("network-state-switch.html");HtmlReports.experiment("Network state switching",rows,switching);reports.add(spec("network-state-switch",switching,rows,expected));
        typedStress(output,reports,full);
    }

    private static void typedStress(Path output,List<Map<String,Object>> reports,Path cpuManifest)throws Exception{
        List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>(),expected=new ArrayList<Map<String,Object>>();
        TransferContentionEngine huge=new TransferContentionEngine(BUDGET);huge.addTransfer(Long.MAX_VALUE,Double.MAX_VALUE,Collections.<String>emptyList(),1,0);
        huge.addTransfer(Long.MIN_VALUE,Double.MAX_VALUE,Collections.<String>emptyList(),1,0);huge.advance(Double.MAX_VALUE);
        typed(rows,expected,cpuManifest,"typed-huge",decode(huge));
        TransferContentionEngine tiny=new TransferContentionEngine(BUDGET);tiny.setEndpointCapacity("tiny",Double.MIN_VALUE);tiny.addTransfer(1,1,Collections.singletonList("tiny"),1,0);tiny.advance(Double.MIN_VALUE);
        typed(rows,expected,cpuManifest,"typed-subnormal",decode(tiny));
        String hostile="</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029";
        TransferContentionEngine many=new TransferContentionEngine(BUDGET);List<String> path=Collections.nCopies(NetworkReportView.LIST_LIMIT+1,hostile);
        for(int i=0;i<=NetworkReportView.FLOW_LIMIT;i++)many.addTransfer(i==0?Long.MAX_VALUE:i==1?Long.MIN_VALUE:i,1,path,1,0);many.advance(1);
        typed(rows,expected,cpuManifest,"typed-hostile-capped",decode(many));
        TransferContentionEngine resources=new TransferContentionEngine(BUDGET);for(int i=0;i<=NetworkReportView.RESOURCE_LIMIT;i++)resources.setEndpointCapacity(i==0?hostile:"R:"+i,1);
        typed(rows,expected,cpuManifest,"typed-resource-cap",decode(resources));
        TransferContentionEngine reuse=new TransferContentionEngine(BUDGET);reuse.addTransfer(Long.MAX_VALUE,1,Collections.<String>emptyList(),1,0);reuse.advance(1);
        reuse.addTransfer(Long.MAX_VALUE,1,Collections.<String>emptyList(),1,1);reuse.advance(2);reuse.addTransfer(Long.MIN_VALUE,1,Collections.<String>emptyList(),1,2);reuse.advance(3);
        typed(rows,expected,cpuManifest,"typed-reused-id",decode(reuse));
        Path target=output.resolve("network-typed-stress.html");HtmlReports.experiment("独立流体投影显示夹具 · 非运行网络上下文",rows,target);
        reports.add(spec("network-typed-stress",target,rows,expected));
    }
    private static void typed(List<Map<String,Object>> rows,List<Map<String,Object>> expected,Path manifest,String name,NetworkLedgerCodec.Decoded decoded)throws Exception{
        Map<String,Object> row=row(manifest,name);row.put("networkEvidence",NetworkReportView.fromDecoded(decoded,false));rows.add(row);expected.add(expect(decoded,false));
    }
    private static NetworkLedgerCodec.Decoded decode(TransferContentionEngine engine){
        List<NetworkFlowBinding> bindings=new ArrayList<NetworkFlowBinding>();List<Integer> tasks=new ArrayList<Integer>();for(int i=1;i<=NetworkReportView.LIST_LIMIT+1;i++)tasks.add(i);
        for(TransferTraceEvent e:engine.getTraceSnapshot().getEvents())if(e.getType()==TransferTraceEvent.Type.START)
            bindings.add(NetworkFlowBinding.of(e.getTransferId(),e.getAdmissionOrdinal(),100+e.getAdmissionOrdinal().intValue(),tasks,null,
                    NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,"source",e.getStart().getOccupiedResources().isEmpty()?"destination":e.getStart().getOccupiedResources().get(0),e.getStart().getOccupiedResources()));
        NetworkRunEvidence evidence=NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(BUDGET),DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,engine.getTraceSnapshot(),bindings);
        return NetworkLedgerCodec.decode(NetworkLedgerCodec.encode(evidence));
    }
    private static Map<String,Object> row(Path manifest,String candidate)throws Exception{
        HtmlReports.ValidatedReport evidence=HtmlReports.validatedReport(manifest);JsonObject root=evidence.getManifest();Map<String,Object> row=new LinkedHashMap<String,Object>();
        row.put("candidate",candidate);row.put("seed",root.getAsJsonObject("configuration").get("rootSeed").getAsBigDecimal().longValueExact());
        row.put("status",root.getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString());evidence.attachTo(row);return row;
    }
    private static Map<String,Object> expect(Path manifest)throws Exception{return expect(ExperimentArtifactValidator.validate(manifest).getDecodedNetworkLedger(),true);}
    private static Map<String,Object> expect(NetworkLedgerCodec.Decoded decoded,boolean context){
        if(decoded==null)return state("OFF");NetworkRunMetrics run=decoded.getMetrics();NetworkTraceMetrics m=run.getTransferMetrics();
        String status=!m.isAvailable()?"TRUNCATED":m.getValidation().getAdmissionCount()==0?"ZERO":"COMPLETE";Map<String,Object> expected=state(status);
        expected.put("contextValidated",context);expected.put("flowTotal",m.isAvailable()?Integer.toString(m.getFlows().size()):null);
        expected.put("flowShown",Integer.toString(Math.min(NetworkReportView.FLOW_LIMIT,m.getFlows().size())));
        expected.put("resourceTotal",m.isAvailable()?Integer.toString(m.getResources().size()):null);
        expected.put("resourceShown",Integer.toString(Math.min(NetworkReportView.RESOURCE_LIMIT,m.getResources().size())));
        expected.put("eventTotal",Integer.toString(m.getValidation().getRecordCount()));expected.put("eventShown",Integer.toString(Math.min(NetworkReportView.EVENT_LIMIT,m.getValidation().getRecordCount())));
        List<String> ids=new ArrayList<String>(),ordinals=new ArrayList<String>(),jobs=new ArrayList<String>();Map<Long,NetworkFlowBinding> bindings=new LinkedHashMap<Long,NetworkFlowBinding>();
        for(NetworkFlowBinding b:decoded.getEvidence().getBindings())bindings.put(b.getAdmissionOrdinal(),b);
        for(int i=0;i<Math.min(NetworkReportView.FLOW_LIMIT,m.getFlows().size());i++){
            NetworkTraceMetrics.FlowSummary f=m.getFlows().get(i);ids.add(Long.toString(f.getExternalTransferId()));ordinals.add(Long.toString(f.getAdmissionOrdinal()));jobs.add(Integer.toString(bindings.get(f.getAdmissionOrdinal()).getJobId()));
        }
        expected.put("flowIds",ids);expected.put("flowOrdinals",ordinals);expected.put("flowJobs",jobs);
        Map<String,Object> values=new LinkedHashMap<String,Object>();values.put("network-admitted",text(m.getAdmittedPayloadBytes()));values.put("network-serviced",text(m.getServicedBalanceDeltaBytes()));
        values.put("network-area",text(m.getModeledRateAreaBytes()));values.put("network-residual",text(m.getCompletionResidualBytes()));values.put("network-remaining",text(m.getRemainingLedgerBytes()));
        values.put("network-adjustment",text(m.getRateAreaMinusBalanceDeltaBytes()));values.put("network-fct-count",text(m.getCompletedFctSampleCount()));
        values.put("network-fct-mean",text(m.getMeanEffectiveFctSeconds()));values.put("network-fct-p95",text(m.getP95EffectiveFctSeconds()));values.put("network-notification-lag",text(m.getMeanNotificationLagSeconds()));
        values.put("network-local-count",run.getInputReferenceCount()==null?null:text(run.getInputReferenceCount())+" / "+text(run.getLocalInputReferenceCount()));
        values.put("network-local-bytes",text(run.getLocalInputReferenceBytes()));values.put("network-local-fraction",text(run.getLocalByteFraction()));values.put("network-grouping-gap",text(run.getAdmittedMinusTransferableReferenceBytes()));
        expected.put("values",values);return expected;
    }
    private static String text(Object value){return value==null?null:value.toString();}
    private static Map<String,Object> state(String name){Map<String,Object> value=new LinkedHashMap<String,Object>();value.put("state",name);return value;}
    private static Map<String,Object> spec(String name,Path path,List<Map<String,Object>> rows,List<Map<String,Object>> network){
        List<String> candidates=new ArrayList<String>(),seeds=new ArrayList<String>(),statuses=new ArrayList<String>();
        for(Map<String,Object> row:rows){candidates.add(row.get("candidate").toString());seeds.add(row.get("seed").toString());statuses.add(row.get("status").toString());}
        Map<String,Object> spec=new LinkedHashMap<String,Object>();spec.put("name",name);spec.put("path",path.toString());spec.put("candidates",candidates);spec.put("seeds",seeds);spec.put("statuses",statuses);spec.put("network",network);return spec;
    }
}
