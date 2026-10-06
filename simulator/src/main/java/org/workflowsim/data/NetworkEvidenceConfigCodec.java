package org.workflowsim.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;

/** Recording-mode dispatch shared by configuration consumers; the V1 ledger decoder stays V1-only. */
public final class NetworkEvidenceConfigCodec {
    private NetworkEvidenceConfigCodec(){ }
    /**
     * Decode the enabled-only option. Absence means OFF at the caller; null/false/explicit OFF are invalid.
     * @param value required ON object
     * @return exact immutable versioned recording policy
     */
    public static NetworkEvidenceConfig decodeConfig(JsonElement value){
        if(value==null||!value.isJsonObject())throw new IllegalArgumentException("networkEvidence must be an enabled recording object");JsonObject object=value.getAsJsonObject();
        if(!object.keySet().equals(new HashSet<>(Arrays.asList("mode","maxTraceRecords")))||!object.get("mode").isJsonPrimitive()||!object.getAsJsonPrimitive("mode").isString())throw new IllegalArgumentException("Invalid networkEvidence fields or mode type");
        String mode=object.get("mode").getAsString();
        if(NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1.name().equals(mode))return NetworkLedgerCodec.decodeConfig(value);
        if(!NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2.name().equals(mode))throw new IllegalArgumentException("Unsupported enabled networkEvidence mode: "+mode);
        JsonElement budget=object.get("maxTraceRecords");if(!budget.isJsonPrimitive()||!budget.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("maxTraceRecords must be an exact integer number");
        try{return NetworkEvidenceConfig.fileLifecycleV2(budget.getAsBigDecimal().intValueExact());}catch(ArithmeticException|NumberFormatException invalid){throw new IllegalArgumentException("maxTraceRecords must be a positive int32 number",invalid);}
    }
    /**
     * @param model physical movement model
     * @param recording optional observation policy
     * @throws IllegalArgumentException when the version/mode pair is not supported
     */
    public static void requireCompatible(DataMovementModel model,NetworkEvidenceConfig recording){
        if(model==null||recording==null)throw new IllegalArgumentException("Data movement and recording policy are required");
        switch(recording.getMode()){
            case OFF:return;
            case FLUID_GROUP_LEDGER_V1:
                if(model.isPreExecutionTransferDelayWithContentionV1()||model.isFatTreeContentionV1())return;
                throw new IllegalArgumentException("Enabled network evidence requires preExecutionTransferDelayWithContentionV1() or fatTreeContentionV1(); unsupported data movement model "+model.getKind());
            case FILE_LIFECYCLE_V2:
                if(model.isCoherentFileDataflowV2())return;
                throw new IllegalArgumentException("FILE_LIFECYCLE_V2 requires a coherent file dataflow V2 model");
            default:throw new IllegalArgumentException("Unsupported recording policy");
        }
    }
    /** @param recording immutable option @return required sidecar role, or null for OFF */
    public static String artifactRole(NetworkEvidenceConfig recording){
        switch(recording.getMode()){
            case OFF:return null;
            case FLUID_GROUP_LEDGER_V1:return NetworkLedgerCodec.ARTIFACT_ROLE;
            case FILE_LIFECYCLE_V2:return "file-lifecycle";
            default:throw new IllegalArgumentException("Unsupported recording policy");
        }
    }
}
