package org.workflowsim.experiments.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.NetworkRunMetrics;
import org.workflowsim.data.NetworkTraceMetrics;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.data.TransferTraceValidator;

/** Bounded display projection of already-validated immutable network data, never another evidence schema. */
final class NetworkReportView {
    static final int FLOW_LIMIT=64, RESOURCE_LIMIT=64, EVENT_LIMIT=128, LIST_LIMIT=12, LABEL_LIMIT=256;
    private long labelsClipped, nestedItemsOmitted;
    private NetworkReportView(){ }

    /** Null means OFF. The caller sets contextValidated only after complete artifact/context validation. */
    static Map<String,Object> fromDecoded(NetworkLedgerCodec.Decoded decoded,boolean contextValidated){
        return decoded==null?null:new NetworkReportView().project(decoded,contextValidated);
    }

    private Map<String,Object> project(NetworkLedgerCodec.Decoded decoded,boolean contextValidated){
        NetworkRunEvidence evidence=decoded.getEvidence();NetworkRunMetrics run=decoded.getMetrics();
        NetworkTraceMetrics metrics=run.getTransferMetrics();TransferTraceValidator.Result validation=metrics.getValidation();
        Map<String,Object> root=map();root.put("schema","workflowsim-network-display-v1");
        root.put("captureStatus",validation.getCaptureStatus().name());root.put("metricsAvailable",metrics.isAvailable());
        root.put("contextValidated",contextValidated);root.put("engineCreated",evidence.isEngineCreated());
        root.put("flowUnit","V1_PARENT_OR_EXTERNAL_GROUP");root.put("modelKind",evidence.getModelKind().name());
        root.put("numericProfile",validation.getNumericProfile());root.put("accountingVersion",metrics.getAccountingVersion());
        root.put("traceBudget",number(evidence.getConfig().getMaxTraceRecords()));root.put("engineTime",number(metrics.getEngineTime()));
        root.put("retainedRecords",number(validation.getRecordCount()));root.put("droppedRecords",number(metrics.getDroppedRecordCount()));
        root.put("recordedAdmissionCount",number(validation.getAdmissionCount()));root.put("recordedCompletionCount",number(validation.getCompletionCount()));
        root.put("recordedOpenFlowCount",number(validation.getOpenFlowCount()));root.put("certifiedThroughSequence",number(validation.getCertifiedThroughSequence()));
        root.put("tailPhase",validation.getTailPhase());root.put("minimumMissingRecords",number(validation.getMinimumMissingRecords()));
        root.put("integrationEpochCount",number(metrics.getIntegrationEpochCount()));
        Map<String,Object> totals=map();
        totals.put("admittedPayloadBytes",number(metrics.getAdmittedPayloadBytes()));totals.put("completedDemandBytes",number(metrics.getCompletedDemandBytes()));
        totals.put("servicedBalanceDeltaBytes",number(metrics.getServicedBalanceDeltaBytes()));totals.put("modeledRateAreaBytes",number(metrics.getModeledRateAreaBytes()));
        totals.put("completionResidualBytes",number(metrics.getCompletionResidualBytes()));totals.put("remainingLedgerBytes",number(metrics.getRemainingLedgerBytes()));
        totals.put("rateAreaMinusBalanceDeltaBytes",number(metrics.getRateAreaMinusBalanceDeltaBytes()));root.put("totals",freeze(totals));
        Map<String,Object> fct=map();fct.put("completedSamples",number(metrics.getCompletedFctSampleCount()));
        fct.put("meanEffectiveSeconds",number(metrics.getMeanEffectiveFctSeconds()));fct.put("p95EffectiveSeconds",number(metrics.getP95EffectiveFctSeconds()));
        fct.put("maxEffectiveSeconds",number(metrics.getMaxEffectiveFctSeconds()));fct.put("meanNotificationLagSeconds",number(metrics.getMeanNotificationLagSeconds()));root.put("fct",freeze(fct));
        Map<String,Object> locality=map();locality.put("status",run.getLocalityStatus().name());locality.put("scope",run.getLocalityScope());
        locality.put("referenceCount",number(run.getInputReferenceCount()));locality.put("localReferenceCount",number(run.getLocalInputReferenceCount()));
        locality.put("requiredReferenceBytes",number(run.getRequiredInputReferenceBytes()));locality.put("localReferenceBytes",number(run.getLocalInputReferenceBytes()));
        locality.put("transferableReferenceBytes",number(run.getTransferableInputReferenceBytes()));
        locality.put("admittedMinusTransferableReferenceBytes",number(run.getAdmittedMinusTransferableReferenceBytes()));
        locality.put("localByteFraction",number(run.getLocalByteFraction()));locality.put("localReferenceFraction",number(run.getLocalReferenceFraction()));root.put("locality",freeze(locality));
        Map<Long,NetworkFlowBinding> bindings=new HashMap<Long,NetworkFlowBinding>();
        for(NetworkFlowBinding b:evidence.getBindings())if(bindings.put(b.getAdmissionOrdinal(),b)!=null)throw new IllegalArgumentException("Duplicate display admission binding");
        List<Map<String,Object>> flows=new ArrayList<Map<String,Object>>();
        for(int index=0;index<Math.min(FLOW_LIMIT,metrics.getFlows().size());index++){
            NetworkTraceMetrics.FlowSummary f=metrics.getFlows().get(index);NetworkFlowBinding b=bindings.get(f.getAdmissionOrdinal());
            if(b==null)throw new IllegalArgumentException("Display flow has no validated admission binding");
            Map<String,Object> row=map();row.put("externalTransferId",number(f.getExternalTransferId()));row.put("admissionOrdinal",number(f.getAdmissionOrdinal()));
            row.put("jobId",number(b.getJobId()));row.put("parentJobId",number(b.getParentJobId()));row.put("groupKind",b.getGroupKind().name());row.put("sourceScope",b.getSourceScope().name());
            row.put("source",label(b.getSourceEndpoint()));row.put("destination",label(b.getDestinationEndpoint()));
            row.put("taskIds",ids(b.getTaskIds()));row.put("taskIdCount",number(b.getTaskIds().size()));row.put("taskIdsOmitted",number(Math.max(0,b.getTaskIds().size()-LIST_LIMIT)));
            row.put("resources",labels(b.getOccupiedResources()));row.put("resourceCount",number(b.getOccupiedResources().size()));row.put("resourcesOmitted",number(Math.max(0,b.getOccupiedResources().size()-LIST_LIMIT)));
            row.put("complete",f.isComplete());row.put("admissionTime",number(f.getAdmissionTime()));row.put("completionEffectiveTime",number(f.getCompletionEffectiveTime()));
            row.put("completionObservedTime",number(f.getCompletionObservedTime()));row.put("effectiveFctSeconds",number(f.getEffectiveFctSeconds()));
            row.put("observedFctSeconds",number(f.getObservedFctSeconds()));row.put("notificationLagSeconds",number(f.getNotificationLagSeconds()));
            row.put("demandBytes",number(f.getDemandBytes()));row.put("servicedBalanceDeltaBytes",number(f.getServicedBalanceDeltaBytes()));
            row.put("modeledRateAreaBytes",number(f.getModeledRateAreaBytes()));row.put("completionResidualBytes",number(f.getCompletionResidualBytes()));row.put("remainingBytes",number(f.getRemainingBytes()));
            flows.add(freeze(row));
        }
        root.put("flows",preview(metrics.isAvailable()?metrics.getFlows().size():null,FLOW_LIMIT,flows));
        List<Map<String,Object>> resources=new ArrayList<Map<String,Object>>();
        for(int index=0;index<Math.min(RESOURCE_LIMIT,metrics.getResources().size());index++){
            NetworkTraceMetrics.ResourceUsage r=metrics.getResources().get(index);Map<String,Object> row=map();
            row.put("resourceKey",label(r.getResourceKey()));row.put("capacityBytesPerSecond",number(r.getLastDeclaredCapacityBytesPerSecond()));
            row.put("rateAreaBytes",number(r.getRateAreaBytes()));row.put("boundedRateAreaBytes",number(r.getBoundedRateAreaBytes()));
            row.put("capacityAreaBytes",number(r.getCapacityAreaBytes()));row.put("integrationEpochUtilization",number(r.getIntegrationEpochUtilization()));resources.add(freeze(row));
        }
        root.put("resources",preview(metrics.isAvailable()?metrics.getResources().size():null,RESOURCE_LIMIT,resources));
        List<TransferTraceEvent> raw=evidence.getTraceSnapshot().getEvents();List<Map<String,Object>> events=new ArrayList<Map<String,Object>>();
        for(int index=0;index<Math.min(EVENT_LIMIT,raw.size());index++){
            TransferTraceEvent e=raw.get(index);Map<String,Object> row=map();row.put("sequence",number(e.getSequence()));row.put("type",e.getType().name());
            row.put("effectiveTime",number(e.getEffectiveTime()));row.put("observedTime",number(e.getObservedTime()));
            row.put("externalTransferId",number(e.getTransferId()));row.put("admissionOrdinal",number(e.getAdmissionOrdinal()));
            NetworkFlowBinding binding=e.getAdmissionOrdinal()==null?null:bindings.get(e.getAdmissionOrdinal());row.put("jobId",binding==null?null:number(binding.getJobId()));
            row.put("detail",detail(e));events.add(freeze(row));
        }
        root.put("events",preview(raw.size(),EVENT_LIMIT,events));root.put("labelsClipped",number(labelsClipped));root.put("nestedItemsOmitted",number(nestedItemsOmitted));
        root.put("labelLimit",number(LABEL_LIMIT));root.put("nestedListLimit",number(LIST_LIMIT));return freeze(root);
    }

    private String detail(TransferTraceEvent event){
        switch(event.getType()){
            case CAPACITY:return "resource="+label(event.getCapacity().getResourceKey())+"; capacity(B/s)="+number(event.getCapacity().getCapacityBytesPerSecond());
            case START:
                TransferTraceEvent.Start s=event.getStart();List<String> path=labels(s.getOccupiedResources());
                return "bytes="+number(s.getBytes())+"; nominal(B/s)="+number(s.getNominalRateBytesPerSecond())+"; initial(B/s)="+number(s.getInitialRateBytesPerSecond())
                        +"; resources="+path+"; pathItemsOmitted="+Math.max(0,s.getOccupiedResources().size()-LIST_LIMIT);
            case RATE_CHANGE:return "previous(B/s)="+number(event.getRateChange().getPreviousRateBytesPerSecond())+"; rate(B/s)="+number(event.getRateChange().getRateBytesPerSecond());
            case SERVICE_SEGMENT:
                TransferTraceEvent.ServiceSegment s2=event.getServiceSegment();
                return "start="+number(s2.getIntervalStart())+"; end="+number(s2.getIntervalEnd())+"; elapsed="+number(s2.getElapsed())+"; rate(B/s)="+number(s2.getRateBytesPerSecond())
                        +"; before="+number(s2.getRemainingBefore())+"; after="+number(s2.getRemainingAfter());
            case COMPLETE:return "numericalResidual(not traffic)="+number(event.getComplete().getRemainingAfterService());
            default:throw new IllegalArgumentException("Unknown network event type");
        }
    }
    private String label(String value){
        if(value.length()<=LABEL_LIMIT)return value;labelsClipped++;int end=LABEL_LIMIT;
        if(Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end)+"…";
    }
    private List<String> labels(List<String> values){
        List<String> shown=new ArrayList<String>();for(int i=0;i<Math.min(LIST_LIMIT,values.size());i++)shown.add(label(values.get(i)));
        nestedItemsOmitted+=Math.max(0,values.size()-LIST_LIMIT);return Collections.unmodifiableList(shown);
    }
    private List<String> ids(List<Integer> values){
        List<String> shown=new ArrayList<String>();for(int i=0;i<Math.min(LIST_LIMIT,values.size());i++)shown.add(number(values.get(i)));
        nestedItemsOmitted+=Math.max(0,values.size()-LIST_LIMIT);return Collections.unmodifiableList(shown);
    }
    private static Map<String,Object> preview(Integer total,int limit,List<Map<String,Object>> rows){
        Map<String,Object> value=map();value.put("total",number(total));value.put("shown",number(rows.size()));
        value.put("omitted",total==null?null:number(total-rows.size()));value.put("limit",number(limit));
        value.put("rows",Collections.unmodifiableList(rows));return freeze(value);
    }
    private static String number(Object value){return value==null?null:value.toString();}
    private static Map<String,Object> map(){return new LinkedHashMap<String,Object>();}
    private static Map<String,Object> freeze(Map<String,Object> value){return Collections.unmodifiableMap(value);}
}
