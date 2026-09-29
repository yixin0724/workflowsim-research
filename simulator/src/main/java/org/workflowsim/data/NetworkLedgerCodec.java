package org.workflowsim.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Strict, versioned network sidecar codec. Decoding rebuilds immutable objects, never Gson-populates final DTOs. */
public final class NetworkLedgerCodec {
    /** Network sidecar schema; the manifest/old metrics/main events keep their existing versions. */
    public static final String SCHEMA="workflowsim-network-ledger-v1";
    /** Role used by the enclosing experiment artifact manifest. */
    public static final String ARTIFACT_ROLE="network-ledger";
    private static final Gson JSON=new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final int MAX_JSON_DEPTH=64,MAX_NUMBER_DIGITS=4096;
    private NetworkLedgerCodec(){ }

    /** Immutable reconstructed evidence and independently recomputed metrics. */
    public static final class Decoded {
        private final NetworkRunEvidence evidence;
        private final NetworkRunMetrics metrics;
        private Decoded(NetworkRunEvidence evidence,NetworkRunMetrics metrics){this.evidence=evidence;this.metrics=metrics;}
        /** @return immutable reconstructed run capture */ public NetworkRunEvidence getEvidence(){return evidence;}
        /** @return immutable validated/recomputed metrics, never trusted supplied summaries */ public NetworkRunMetrics getMetrics(){return metrics;}
    }

    /**
     * Produce a fresh transport tree after semantic validation and metric calculation.
     * @param evidence explicitly enabled run capture
     * @return new mutable transport tree; changing it cannot change the capture
     */
    public static JsonObject document(NetworkRunEvidence evidence){
        if(evidence==null)throw bad("Enabled network evidence is required");
        NetworkRunMetrics metrics=NetworkRunMetrics.calculate(evidence);
        JsonObject root=new JsonObject();root.addProperty("schema",SCHEMA);
        root.addProperty("flowUnit","V1_PARENT_OR_EXTERNAL_GROUP");
        root.addProperty("numericProfile",TransferTraceValidator.NUMERIC_PROFILE);
        root.addProperty("accountingVersion",NetworkTraceMetrics.ACCOUNTING_VERSION);
        JsonObject units=new JsonObject();units.addProperty("time","SIMULATION_SECONDS");
        units.addProperty("bytes","MODELED_BYTES");units.addProperty("rate","BYTES_PER_SECOND");root.add("units",units);
        root.add("evidence",JSON.toJsonTree(evidence));root.add("metrics",JSON.toJsonTree(metrics));
        wireNumbers(root,0);return root;
    }
    /** @param evidence enabled capture @return formatted JSON with explicit nullable members */
    public static String encode(NetworkRunEvidence evidence){return JSON.toJson(document(evidence));}

    /**
     * Decode required fields/types and reject duplicated keys, unknown fields, malformed
     * evidence or forged summaries. Binary64 fields compare as binary64 values; exact
     * decimal quantities/IDs are never compared by lossy double conversion.
     * @param json complete JSON document
     * @return immutable validated evidence and recomputed summaries
     * @throws IllegalArgumentException for invalid content
     */
    public static Decoded decode(String json){
        JsonObject root=parseDocument(json);
        keys(root,"schema","flowUnit","numericProfile","accountingVersion","units","evidence","metrics");
        equal(root,"schema",SCHEMA);equal(root,"flowUnit","V1_PARENT_OR_EXTERNAL_GROUP");
        equal(root,"numericProfile",TransferTraceValidator.NUMERIC_PROFILE);equal(root,"accountingVersion",NetworkTraceMetrics.ACCOUNTING_VERSION);
        JsonObject units=object(root.get("units"),"units");keys(units,"time","bytes","rate");
        equal(units,"time","SIMULATION_SECONDS");equal(units,"bytes","MODELED_BYTES");equal(units,"rate","BYTES_PER_SECOND");
        JsonObject raw=object(root.get("evidence"),"evidence");
        keys(raw,"config","modelKind","traceSnapshot","bindings","engineCreated","inputDemand");
        NetworkEvidenceConfig config=decodeConfig(raw.get("config"));
        DataMovementModel.Kind model=enumValue(DataMovementModel.Kind.class,text(raw.get("modelKind"),"modelKind"),"modelKind");
        TransferTraceSnapshot trace=trace(object(raw.get("traceSnapshot"),"traceSnapshot"),config.getMaxTraceRecords());
        JsonArray rawBindings=array(raw.get("bindings"),"bindings");
        if(rawBindings.size()>config.getMaxTraceRecords())throw bad("Binding count exceeds trace budget");
        List<NetworkFlowBinding> bindings=new ArrayList<NetworkFlowBinding>();
        for(JsonElement value:rawBindings)bindings.add(binding(object(value,"binding")));
        NetworkInputDemandSnapshot input=raw.get("inputDemand").isJsonNull()?null:input(object(raw.get("inputDemand"),"inputDemand"));
        boolean created=bool(raw.get("engineCreated"),"engineCreated");
        NetworkRunEvidence evidence;
        try {
            evidence=created?NetworkRunEvidence.capture(config,model,trace,bindings,input):NetworkRunEvidence.empty(config,model);
        } catch (IllegalStateException invalidBindings) {
            throw new IllegalArgumentException("Invalid network capture binding consistency",invalidBindings);
        }
        // Also rejects surplus truncated bindings or nonempty fields laundered through empty().
        same(JSON.toJsonTree(evidence),raw,"evidence");
        NetworkRunMetrics metrics=NetworkRunMetrics.calculate(evidence);
        same(JSON.toJsonTree(metrics),root.get("metrics"),"metrics");
        return new Decoded(evidence,metrics);
    }

    /**
     * Read the ON-only configuration extension; absence is handled as OFF by the caller.
     * @param value required object, not null/false/OFF
     * @return immutable enabled recording option
     */
    public static NetworkEvidenceConfig decodeConfig(JsonElement value){
        JsonObject config=object(value,"networkEvidence");keys(config,"mode","maxTraceRecords");
        equal(config,"mode",NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1.name());
        return NetworkEvidenceConfig.fluidGroupLedger(integer(config.get("maxTraceRecords"),"maxTraceRecords"));
    }

    private static TransferTraceSnapshot trace(JsonObject raw,int budget){
        keys(raw,"status","events","droppedCount","engineTime");
        TransferTraceSnapshot.Status status=enumValue(TransferTraceSnapshot.Status.class,text(raw.get("status"),"status"),"status");
        JsonArray rows=array(raw.get("events"),"trace events");if(rows.size()>budget)throw bad("Trace exceeds record budget");
        List<TransferTraceEvent> events=new ArrayList<TransferTraceEvent>();
        for(JsonElement row:rows)events.add(event(object(row,"trace event")));
        return new TransferTraceSnapshot(status,events,whole(raw.get("droppedCount"),"droppedCount"),floating(raw.get("engineTime"),"engineTime"));
    }
    private static TransferTraceEvent event(JsonObject e){
        keys(e,"sequence","type","effectiveTime","observedTime","transferId","admissionOrdinal",
                "capacity","start","rateChange","serviceSegment","complete");
        long sequence=whole(e.get("sequence"),"sequence");
        TransferTraceEvent.Type type=enumValue(TransferTraceEvent.Type.class,text(e.get("type"),"event type"),"event type");
        double effective=floating(e.get("effectiveTime"),"effectiveTime"),observed=floating(e.get("observedTime"),"observedTime");
        String selected;
        switch(type){case CAPACITY:selected="capacity";break;case START:selected="start";break;
            case RATE_CHANGE:selected="rateChange";break;case SERVICE_SEGMENT:selected="serviceSegment";break;
            default:selected="complete";break;}
        for(String payload:Arrays.asList("capacity","start","rateChange","serviceSegment","complete"))
            if(!payload.equals(selected)&&!e.get(payload).isJsonNull())throw bad("Unexpected event payload: "+payload);
        JsonObject p=object(e.get(selected),selected);
        if(type==TransferTraceEvent.Type.CAPACITY){
            if(!e.get("transferId").isJsonNull()||!e.get("admissionOrdinal").isJsonNull())throw bad("CAPACITY identity must be null");
            equalDouble(effective,observed,"CAPACITY clocks");keys(p,"resourceKey","capacityBytesPerSecond");
            return TransferTraceEvent.capacity(sequence,effective,text(p.get("resourceKey"),"resourceKey"),floating(p.get("capacityBytesPerSecond"),"capacity"));
        }
        long id=whole(e.get("transferId"),"transferId"),ordinal=whole(e.get("admissionOrdinal"),"admissionOrdinal");
        switch(type){
            case START:
                keys(p,"bytes","occupiedResources","nominalRateBytesPerSecond","initialRateBytesPerSecond");equalDouble(effective,observed,"START clocks");
                return TransferTraceEvent.start(sequence,effective,id,ordinal,floating(p.get("bytes"),"bytes"),strings(p.get("occupiedResources"),"resources"),
                        floating(p.get("nominalRateBytesPerSecond"),"nominal rate"),floating(p.get("initialRateBytesPerSecond"),"initial rate"));
            case RATE_CHANGE:
                keys(p,"previousRateBytesPerSecond","rateBytesPerSecond");
                return TransferTraceEvent.rateChange(sequence,effective,observed,id,ordinal,
                        floating(p.get("previousRateBytesPerSecond"),"previous rate"),floating(p.get("rateBytesPerSecond"),"rate"));
            case SERVICE_SEGMENT:
                keys(p,"intervalStart","intervalEnd","elapsed","rateBytesPerSecond","remainingBefore","remainingAfter");
                double end=floating(p.get("intervalEnd"),"intervalEnd");equalDouble(effective,end,"SERVICE effective/end");
                return TransferTraceEvent.serviceSegment(sequence,floating(p.get("intervalStart"),"intervalStart"),end,observed,id,ordinal,
                        floating(p.get("elapsed"),"elapsed"),floating(p.get("rateBytesPerSecond"),"rate"),
                        floating(p.get("remainingBefore"),"remainingBefore"),floating(p.get("remainingAfter"),"remainingAfter"));
            case COMPLETE:
                keys(p,"remainingAfterService");return TransferTraceEvent.complete(sequence,effective,observed,id,ordinal,floating(p.get("remainingAfterService"),"residual"));
            default:throw bad("Unexpected flow event");
        }
    }
    private static NetworkFlowBinding binding(JsonObject b){
        keys(b,"externalTransferId","admissionOrdinal","jobId","taskIds","parentJobId","groupKind","sourceScope","sourceEndpoint","destinationEndpoint","occupiedResources");
        equal(b,"sourceScope",NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE.name());
        List<Integer> taskIds=new ArrayList<Integer>();for(JsonElement task:array(b.get("taskIds"),"taskIds"))taskIds.add(integer(task,"taskId"));
        return NetworkFlowBinding.of(whole(b.get("externalTransferId"),"externalTransferId"),whole(b.get("admissionOrdinal"),"binding ordinal"),
                integer(b.get("jobId"),"jobId"),taskIds,b.get("parentJobId").isJsonNull()?null:integer(b.get("parentJobId"),"parentJobId"),
                enumValue(NetworkFlowBinding.GroupKind.class,text(b.get("groupKind"),"groupKind"),"groupKind"),
                text(b.get("sourceEndpoint"),"sourceEndpoint"),text(b.get("destinationEndpoint"),"destinationEndpoint"),strings(b.get("occupiedResources"),"binding resources"));
    }
    private static NetworkInputDemandSnapshot input(JsonObject i){
        keys(i,"scope","referenceCount","localReferenceCount","requiredReferenceBytes","localReferenceBytes","transferableReferenceBytes");
        equal(i,"scope","V1_GROUP_INPUT_REFERENCES");
        NetworkInputDemandSnapshot input=NetworkInputDemandSnapshot.of(whole(i.get("referenceCount"),"referenceCount"),
                whole(i.get("localReferenceCount"),"localReferenceCount"),decimal(i.get("requiredReferenceBytes"),"requiredReferenceBytes"),
                decimal(i.get("localReferenceBytes"),"localReferenceBytes"));
        if(input.getTransferableReferenceBytes().compareTo(decimal(i.get("transferableReferenceBytes"),"transferableReferenceBytes"))!=0)
            throw bad("Input reference byte partition differs");return input;
    }

    /** Typed semantic equality: expected Number runtime types distinguish binary64 from exact decimal/integer fields. */
    private static void same(JsonElement expected,JsonElement actual,String path){
        if(expected==null||expected.isJsonNull()){
            if(actual==null||!actual.isJsonNull())throw bad("Expected explicit null at "+path);return;
        }
        if(actual==null||actual.isJsonNull())throw bad("Missing value at "+path);
        if(expected.isJsonObject()){
            JsonObject left=expected.getAsJsonObject(),right=object(actual,path);
            if(!left.keySet().equals(right.keySet()))throw bad("Missing/unknown fields at "+path);
            for(String key:left.keySet())same(left.get(key),right.get(key),path+"/"+key);return;
        }
        if(expected.isJsonArray()){
            JsonArray left=expected.getAsJsonArray(),right=array(actual,path);
            if(left.size()!=right.size())throw bad("Array length differs at "+path);
            for(int n=0;n<left.size();n++)same(left.get(n),right.get(n),path+"/"+n);return;
        }
        JsonPrimitive primitive=expected.getAsJsonPrimitive();
        if(primitive.isNumber()){
            Number n=primitive.getAsNumber();
            if(n instanceof Double||n instanceof Float)equalDouble(n.doubleValue(),floating(actual,path),path);
            else if(decimal(expected,path).compareTo(decimal(actual,path))!=0)throw bad("Exact numeric value differs at "+path);
        }else if(primitive.isBoolean()){
            if(primitive.getAsBoolean()!=bool(actual,path))throw bad("Boolean differs at "+path);
        }else if(!primitive.getAsString().equals(text(actual,path)))throw bad("Text differs at "+path);
    }
    private static void wireNumbers(JsonElement value,int depth){
        if(depth>MAX_JSON_DEPTH)throw bad("Network ledger JSON nesting exceeds limit");
        if(value.isJsonObject())for(JsonElement child:value.getAsJsonObject().asMap().values())wireNumbers(child,depth+1);
        else if(value.isJsonArray())for(JsonElement child:value.getAsJsonArray())wireNumbers(child,depth+1);
        else if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isNumber())decimal(value,"encoded number");
    }
    /**
     * Read strict JSON syntax into a fresh transport object while preserving numeric types.
     * This lexical helper does NOT validate network semantics or supplied metrics; use
     * {@link #decode(String)} for a certificate. It also supports exact numeric tokens
     * longer than Gson JsonReader's 1024-character buffer, within our 4096-character limit.
     * @param json complete JSON object
     * @return fresh JSON tree with exact BigDecimal numeric primitives
     * @throws IllegalArgumentException for invalid syntax or numeric/depth limits
     */
    public static JsonObject parseDocument(String json){
        if(json==null)throw bad("Network ledger JSON is required");
        JsonSyntax parser=new JsonSyntax(json);JsonElement value=parser.value(0);parser.space();
        if(parser.at!=json.length())throw parser.error("Trailing JSON content");return object(value,"network ledger JSON");
    }

    /** RFC 8259 lexical grammar only; semantic reconstruction stays in the codec above. */
    private static final class JsonSyntax {
        final String text;int at;
        JsonSyntax(String text){this.text=text;at=!text.isEmpty()&&text.charAt(0)==0xfeff?1:0;}
        JsonElement value(int depth){
            if(depth>MAX_JSON_DEPTH)throw error("JSON nesting exceeds limit");space();char c=peek();
            if(c=='{'){
                at++;JsonObject object=new JsonObject();space();if(take('}'))return object;
                while(true){
                    space();if(peek()!='"')throw error("Object keys must be quoted strings");String key=string();
                    if(object.has(key))throw error("Duplicate JSON key: "+key);space();expect(':');object.add(key,value(depth+1));space();
                    if(take('}'))return object;expect(',');
                }
            }
            if(c=='['){
                at++;JsonArray array=new JsonArray();space();if(take(']'))return array;
                while(true){array.add(value(depth+1));space();if(take(']'))return array;expect(',');}
            }
            if(c=='"')return new JsonPrimitive(string());
            if(c=='t'){literal("true");return new JsonPrimitive(true);}
            if(c=='f'){literal("false");return new JsonPrimitive(false);}
            if(c=='n'){literal("null");return JsonNull.INSTANCE;}
            if(c=='-'||digit(c))return numeric();
            throw error("Expected JSON value");
        }
        JsonPrimitive numeric(){
            int start=at;take('-');
            if(take('0')){ /* A following digit is rejected by the enclosing delimiter grammar. */ }
            else if(peek()>='1'&&peek()<='9')digits(start);
            else throw error("Invalid JSON number integer part");
            if(take('.')){int first=at;digits(start);if(at==first)throw error("Number fraction needs digits");}
            if(peek()=='e'||peek()=='E'){
                at++;if(peek()=='+'||peek()=='-')at++;int first=at;digits(start);
                if(at==first)throw error("Number exponent needs digits");
            }
            if(at-start>MAX_NUMBER_DIGITS)throw error("Numeric token exceeds limit");
            return new JsonPrimitive(number(text.substring(start,at),"JSON number"));
        }
        void digits(int start){while(digit(peek())){at++;if(at-start>MAX_NUMBER_DIGITS)throw error("Numeric token exceeds limit");}}
        String string(){
            expect('"');StringBuilder result=new StringBuilder();
            while(at<text.length()){
                char c=text.charAt(at++);if(c=='"')return result.toString();
                if(c<0x20)throw error("Unescaped string control character");
                if(c!=92){result.append(c);continue;}
                if(at>=text.length())throw error("Incomplete string escape");char escape=text.charAt(at++);
                if(escape=='"'||escape==92||escape=='/'){result.append(escape);continue;}
                switch(escape){
                    case 'b':result.append((char)8);break;
                    case 'f':result.append((char)12);break;
                    case 'n':result.append((char)10);break;
                    case 'r':result.append((char)13);break;
                    case 't':result.append((char)9);break;
                    case 'u':
                        if(text.length()-at<4)throw error("Incomplete Unicode escape");int code=0;
                        for(int i=0;i<4;i++){char h=text.charAt(at++);int hex=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;
                            if(hex<0)throw error("Invalid Unicode escape");code=code*16+hex;}
                        result.append((char)code);break;
                    default:throw error("Invalid string escape");
                }
            }
            throw error("Unterminated string");
        }
        void literal(String value){if(!text.startsWith(value,at))throw error("Invalid JSON literal");at+=value.length();}
        void space(){while(at<text.length()){char c=text.charAt(at);if(c!=' '&&c!=10&&c!=13&&c!=9)return;at++;}}
        char peek(){return at<text.length()?text.charAt(at):(char)0;}
        boolean take(char c){if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        void expect(char c){if(!take(c))throw error("Expected delimiter "+c);}
        static boolean digit(char c){return c>='0'&&c<='9';}
        IllegalArgumentException error(String message){return bad(message+" at character "+at);}
    }
    private static JsonObject object(JsonElement e,String name){if(e==null||!e.isJsonObject())throw bad("Expected object: "+name);return e.getAsJsonObject();}
    private static JsonArray array(JsonElement e,String name){if(e==null||!e.isJsonArray())throw bad("Expected array: "+name);return e.getAsJsonArray();}
    private static String text(JsonElement e,String name){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw bad("Expected string: "+name);return e.getAsString();}
    private static boolean bool(JsonElement e,String name){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw bad("Expected boolean: "+name);return e.getAsBoolean();}
    private static BigDecimal decimal(JsonElement e,String name){if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw bad("Expected number: "+name);return number(e.getAsString(),name);}
    private static BigDecimal number(String token,String name){
        if(token.length()>MAX_NUMBER_DIGITS)throw bad("Numeric token too long: "+name);
        try{BigDecimal n=new BigDecimal(token);if(n.precision()>MAX_NUMBER_DIGITS||Math.abs((long)n.scale())>MAX_NUMBER_DIGITS)throw bad("Numeric exponent/precision too large: "+name);return n;}
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid number: "+name,e);}
    }
    private static double floating(JsonElement e,String name){BigDecimal n=decimal(e,name);double d=n.doubleValue();
        if(!Double.isFinite(d)||(d==0.0&&n.signum()!=0))throw bad("Binary64 value is not representable: "+name);return d;}
    private static long whole(JsonElement e,String name){try{return decimal(e,name).longValueExact();}catch(ArithmeticException x){throw new IllegalArgumentException("Expected exact long: "+name,x);}}
    private static int integer(JsonElement e,String name){try{return decimal(e,name).intValueExact();}catch(ArithmeticException x){throw new IllegalArgumentException("Expected exact int: "+name,x);}}
    private static List<String> strings(JsonElement e,String name){List<String> out=new ArrayList<String>();for(JsonElement item:array(e,name))out.add(text(item,name));return out;}
    private static void keys(JsonObject object,String... names){if(!object.keySet().equals(new LinkedHashSet<String>(Arrays.asList(names))))throw bad("Missing or unknown fields; expected "+Arrays.toString(names));}
    private static void equal(JsonObject object,String field,String value){if(!value.equals(text(object.get(field),field)))throw bad("Unsupported "+field);}
    private static void equalDouble(double a,double b,String path){if(a!=b)throw bad("Binary64 value differs at "+path);}
    private static <E extends Enum<E>>E enumValue(Class<E> type,String value,String name){try{return Enum.valueOf(type,value);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Unsupported "+name+": "+value,e);}}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
