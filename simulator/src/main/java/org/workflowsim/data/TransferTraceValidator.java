package org.workflowsim.data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Independent validation of the published NF001 observation grammar.
 * It checks scalar integration and max-min bottleneck certificates, never invoking
 * the production allocator. It establishes internal consistency, not authenticity
 * of a wholly rewritten history or reconstruction of deliberately dropped records.
 * A truncated tail receives only observed-grammar and necessary-bound checks; it
 * is not a certificate that an arbitrary unseen continuation exists. Only stable
 * boundaries through certifiedThroughSequence have full allocation certificates.
 */
public final class TransferTraceValidator {
    /** Exact scalar binary64 operations; allocation sums use the documented bounded ULP profile. */
    public static final String NUMERIC_PROFILE = "BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1";
    private TransferTraceValidator() { }

    /** Immutable coverage/grammar result, not a network performance summary. */
    public static final class Result {
        private final TransferTraceSnapshot.Status captureStatus;
        private final int recordCount;
        private final long admissionCount;
        private final long completionCount;
        private final int openFlowCount;
        private final long certifiedThroughSequence;
        private final String tailPhase;
        private final long minimumMissingRecords;
        private Result(Parser p) {
            captureStatus=p.snapshot.getStatus(); recordCount=p.events.size();
            admissionCount=p.nextOrdinal-1; completionCount=p.completed;
            openFlowCount=p.active.size(); certifiedThroughSequence=p.certified;
            tailPhase=p.tail; minimumMissingRecords=p.minimumMissing;
        }
        /** @return input capture status, not a claim that all admitted flows finished */
        public TransferTraceSnapshot.Status getCaptureStatus(){return captureStatus;}
        /** @return true only when no records were dropped and all mandatory phases were validated */
        public boolean isCompleteCapture(){return captureStatus==TransferTraceSnapshot.Status.COMPLETE;}
        /** @return retained record count */ public int getRecordCount(){return recordCount;}
        /** @return validated START count in the retained prefix */ public long getAdmissionCount(){return admissionCount;}
        /** @return validated COMPLETE count in the retained prefix */ public long getCompletionCount(){return completionCount;}
        /** @return retained-prefix open admission count, not a reconstructed truncated-run total */ public int getOpenFlowCount(){return openFlowCount;}
        /** @return last sequence ending a certified stable boundary */ public long getCertifiedThroughSequence(){return certifiedThroughSequence;}
        /** @return grammar phase at the retained tail */ public String getTailPhase(){return tailPhase;}
        /** @return known lower bound on records missing from an unfinished retained phase */ public long getMinimumMissingRecords(){return minimumMissingRecords;}
        /** @return versioned numerical comparison contract */ public String getNumericProfile(){return NUMERIC_PROFILE;}
    }

    /**
     * Validate capture shape, phase ordering, scalar service/settlement, and final allocations.
     * Truncated prefixes can end inside a batch; no complete-run metrics are implied.
     *
     * <p>Scalar formulas are checked as exact numeric binary64 values (signed zero is
     * equivalent). Resource loads are accumulated with exact BigDecimal representations
     * of the recorded doubles. Their bound is 8*(occupancyTerms+1)*ulp(capacity).
     * Bottleneck-rate ties use 8*(activeFlows+1)*ulp(max(compared rates)). Both
     * budgets are capped at 1e-12 times their scale using exact decimal arithmetic,
     * so a multi-ULP budget cannot hide a large fraction of a subnormal rate/capacity.
     * Coarsely represented overcommit is rejected, not certified as rounding noise.
     * This allocation profile is not the byte-completion tolerance.</p>
     *
     * @param snapshot immutable engine trace or untrusted reconstructed equivalent
     * @param maxTraceRecords declared capture budget; zero only for DISABLED
     * @return immutable coverage and grammar result
     * @throws IllegalArgumentException for malformed or inconsistent evidence
     */
    public static Result validate(TransferTraceSnapshot snapshot,int maxTraceRecords) {
        Parser parser=new Parser(snapshot,maxTraceRecords);
        parser.run(); return new Result(parser);
    }

    private static final class Flow {
        final long externalId,ordinal;
        final double bytes,nominal;
        final List<String> resources;
        double remaining,rate;
        Flow(TransferTraceEvent event){
            externalId=event.getTransferId();ordinal=event.getAdmissionOrdinal();
            bytes=event.getStart().getBytes();remaining=bytes;
            nominal=event.getStart().getNominalRateBytesPerSecond();rate=event.getStart().getInitialRateBytesPerSecond();
            resources=event.getStart().getOccupiedResources();
        }
    }
    private static final class Resource {
        final double capacity;
        BigDecimal load=BigDecimal.ZERO;
        long terms;
        double maximumRate;
        Resource(double capacity){this.capacity=capacity;}
    }

    private static final class Parser {
        final TransferTraceSnapshot snapshot;
        final List<TransferTraceEvent> events;
        final boolean truncated;
        final Map<String,Double> capacities=new LinkedHashMap<>();
        final LinkedHashMap<Long,Flow> active=new LinkedHashMap<>();
        final Map<Long,Long> externalIds=new HashMap<>();
        int index;
        long nextOrdinal=1,completed,certified,minimumMissing;
        double cursor;
        Double horizon;
        String tail="READY";
        boolean partial;

        Parser(TransferTraceSnapshot snapshot,int budget){
            require(snapshot!=null,"Trace snapshot is required");
            require(budget>=0,"Trace record budget cannot be negative");
            require(snapshot.getStatus()!=null&&snapshot.getEvents()!=null,"Trace status/events are required");
            nonNegative(snapshot.getEngineTime(),"engineTime");
            require(snapshot.getDroppedCount()>=0,"Negative dropped record count");
            this.snapshot=snapshot;events=snapshot.getEvents();truncated=snapshot.getStatus()==TransferTraceSnapshot.Status.TRUNCATED;
            if(snapshot.getStatus()==TransferTraceSnapshot.Status.DISABLED){
                require(budget==0&&events.isEmpty()&&snapshot.getDroppedCount()==0,"DISABLED capture must be empty with zero budget/drops");
                tail="DISABLED";return;
            }
            require(budget>0&&events.size()<=budget,"Enabled capture exceeds its positive record budget");
            if(truncated) require(events.size()==budget&&snapshot.getDroppedCount()>0,"TRUNCATED requires a full prefix and positive dropped count");
            else require(snapshot.getDroppedCount()==0,"COMPLETE capture cannot have dropped records");
            double lastEffective=0,lastObserved=0;
            for(int i=0;i<events.size();i++){
                TransferTraceEvent e=events.get(i);
                require(e!=null&&e.getType()!=null,"Null event/type at record "+(i+1));
                require(e.getSequence()==i+1L,"Non-consecutive event sequence at record "+(i+1));
                nonNegative(e.getEffectiveTime(),"effectiveTime");nonNegative(e.getObservedTime(),"observedTime");
                require(e.getEffectiveTime()<=e.getObservedTime()&&e.getObservedTime()<=snapshot.getEngineTime(),"Event time exceeds observation/watermark");
                require(e.getEffectiveTime()>=lastEffective&&e.getObservedTime()>=lastObserved,"Event times moved backwards");
                lastEffective=e.getEffectiveTime();lastObserved=e.getObservedTime();
                shape(e);
            }
        }

        void run(){
            if(snapshot.getStatus()==TransferTraceSnapshot.Status.DISABLED)return;
            while(index<events.size()&&!partial){
                if(horizon!=null){
                    if(!active.isEmpty()&&cursor<horizon){if(!step())break;continue;}
                    cursor=horizon;horizon=null;
                }
                TransferTraceEvent e=events.get(index);
                switch(e.getType()){
                    case CAPACITY:
                        require(active.isEmpty(),at("Capacity changed while flows active"));
                        require(e.getEffectiveTime()>=cursor,at("Capacity precedes engine cursor"));
                        cursor=e.getEffectiveTime();capacities.put(e.getCapacity().getResourceKey(),e.getCapacity().getCapacityBytesPerSecond());
                        index++;certified=index;break;
                    case START:
                        if(active.isEmpty()) {require(e.getEffectiveTime()>=cursor,at("Admission precedes cursor"));cursor=e.getEffectiveTime();}
                        else same(cursor,e.getEffectiveTime(),at("Unexplained active time gap before admission"));
                        require(e.getAdmissionOrdinal()==nextOrdinal,at("Admission ordinal gap or reuse"));
                        require(!externalIds.containsKey(e.getTransferId()),at("External ID reused while active"));
                        Flow admitted=new Flow(e);active.put(admitted.ordinal,admitted);externalIds.put(admitted.externalId,admitted.ordinal);
                        nextOrdinal++;index++;
                        if(!rates(admitted.ordinal,e.getObservedTime(),"ADMISSION_RATE_CHANGES"))break;
                        break;
                    case SERVICE_SEGMENT:
                    case COMPLETE:
                        require(!active.isEmpty(),at("Service/settlement without active flows"));
                        require(e.getObservedTime()>cursor,at("Advance must observe a later time"));
                        horizon=e.getObservedTime();if(!step())break;break;
                    default: throw bad(at("Rate change outside allocation transition"));
                }
            }
            if(partial)return;
            if(horizon!=null){
                if(!active.isEmpty()&&cursor<horizon){step();if(partial)return;}
                cursor=horizon;horizon=null;
            }
            if(!truncated&&!active.isEmpty())same(cursor,snapshot.getEngineTime(),"Complete capture omits active service before its watermark");
            if(truncated&&!active.isEmpty()&&cursor<snapshot.getEngineTime()){
                // Do not replay unknown arrivals. They cost START plus a record for their own
                // later progress; every already-active flow still requires a service/removal.
                cut("UNOBSERVED_TIME_PROGRESS",progressMinimum(snapshot.getEngineTime(),false));return;
            }
            require(snapshot.getEngineTime()>=cursor,"Snapshot watermark precedes validated cursor");
            tail=truncated?"PREFIX_AT_BOUNDARY":"READY";
        }

        boolean rates(Long excluded,double observed,String phase){
            long previousOrdinal=0;
            while(index<events.size()&&events.get(index).getType()==TransferTraceEvent.Type.RATE_CHANGE){
                TransferTraceEvent e=events.get(index);Flow flow=flow(e);
                require(excluded==null||flow.ordinal!=excluded,at("New admission cannot also receive RATE_CHANGE"));
                require(flow.ordinal>previousOrdinal,at("Duplicate or reordered rate change"));previousOrdinal=flow.ordinal;
                same(cursor,e.getEffectiveTime(),at("Rate-change effective time mismatch"));same(observed,e.getObservedTime(),at("Rate-change observation mismatch"));
                same(flow.rate,e.getRateChange().getPreviousRateBytesPerSecond(),at("Rate history discontinuity"));
                require(e.getRateChange().getRateBytesPerSecond()!=flow.rate,at("Unchanged rate event"));
                require(e.getRateChange().getRateBytesPerSecond()<=flow.nominal,at("Rate exceeds nominal cap"));
                flow.rate=e.getRateChange().getRateBytesPerSecond();index++;
            }
            if(index==events.size()&&truncated){
                Set<Long> variable=new HashSet<Long>();
                for(Flow flow:active.values())if(flow.ordinal>previousOrdinal&&(excluded==null||flow.ordinal!=excluded))variable.add(flow.ordinal);
                if(!variable.isEmpty()){
                    fixedAllocationBounds(variable);
                    boolean alreadyStable=true;
                    try { allocation(); } catch (IllegalArgumentException notYetStable) { alreadyStable=false; }
                    double laterObservation=horizon==null?snapshot.getEngineTime():horizon;
                    long progress=!active.isEmpty()&&cursor<laterObservation
                            ? (alreadyStable?progressMinimum(laterObservation,true):active.size()):0L;
                    long missing=(alreadyStable?0L:1L)+progress;
                    cut(phase,missing);return false;
                }
                // No legal future rate event can repair this allocation: it is already fixed.
            }
            allocation();certified=index;return true;
        }

        /** Conservative event lower bound when unobserved admissions/rate changes may precede time progress. */
        long progressMinimum(double observed,boolean unseenRatesMayPrecede){
            if(active.isEmpty()||!(cursor<observed))return 0L;
            double duration=Double.POSITIVE_INFINITY;Flow earliest=null;
            for(Flow flow:active.values()){
                double candidate=flow.remaining/flow.rate;
                if(candidate<duration){duration=candidate;earliest=flow;}
            }
            double elapsed=Math.min(observed-cursor,duration);
            double end=Math.min(observed,cursor+elapsed);long settlements=0;
            for(Flow flow:active.values()){
                double after=Math.max(0.0,flow.remaining-flow.rate*elapsed);
                if(after<=tolerance(flow.bytes)||(flow==earliest&&elapsed>=duration))settlements++;
            }
            long direct=(elapsed>0.0?active.size():0L)+settlements+(end<observed?active.size()-settlements:0L);
            // A new admission costs START plus its own progress/removal. An unknown RATE
            // costs one event. Neither can remove the obligation of each already-active flow.
            long altered=active.size()+(unseenRatesMayPrecede?1L:2L);
            return Math.max(active.size(),Math.min(direct,altered));
        }

        boolean step(){
            double observed=horizon;
            require(observed>cursor&&!active.isEmpty(),at("Invalid advance state"));
            List<Flow> before=new ArrayList<>(active.values());
            double duration=Double.POSITIVE_INFINITY;Flow earliest=null;
            for(Flow f:before){double d=f.remaining/f.rate;if(d<duration){duration=d;earliest=f;}}
            double elapsed=Math.min(observed-cursor,duration);
            double end=Math.min(observed,cursor+elapsed);
            Map<Long,Double> after=new LinkedHashMap<>();List<Flow> settled=new ArrayList<>();
            for(Flow f:before){
                double remainder=Math.max(0.0,f.remaining-f.rate*elapsed);after.put(f.ordinal,remainder);
                if(remainder<=tolerance(f.bytes)||(f==earliest&&elapsed>=duration))settled.add(f);
            }
            long continuation=end<observed?before.size()-settled.size():0L;
            if(elapsed>0){
                for(int i=0;i<before.size();i++){
                    if(index==events.size()){cut("SERVICE_BATCH",before.size()-i+settled.size()+continuation);return false;}
                    TransferTraceEvent e=events.get(index);Flow f=before.get(i);
                    require(e.getType()==TransferTraceEvent.Type.SERVICE_SEGMENT,at("Missing SERVICE in active-flow batch"));
                    identity(e,f);TransferTraceEvent.ServiceSegment s=e.getServiceSegment();
                    same(cursor,s.getIntervalStart(),at("Service start mismatch"));same(end,s.getIntervalEnd(),at("Service end mismatch"));
                    same(end,e.getEffectiveTime(),at("Service effective-time mismatch"));same(observed,e.getObservedTime(),at("Service observation horizon mismatch"));
                    same(elapsed,s.getElapsed(),at("Service elapsed mismatch"));same(f.rate,s.getRateBytesPerSecond(),at("Service rate mismatch"));
                    same(f.remaining,s.getRemainingBefore(),at("Service balance discontinuity"));same(after.get(f.ordinal),s.getRemainingAfter(),at("Service subtraction/clamp mismatch"));
                    index++;
                }
            }else require(elapsed==0.0&&duration==0.0&&earliest!=null,at("Unjustified zero-elapsed settlement"));
            for(Flow f:before)f.remaining=after.get(f.ordinal);
            cursor=end;
            require(elapsed!=0.0||!settled.isEmpty(),at("Zero step made no progress"));
            for(int i=0;i<settled.size();i++){
                if(index==events.size()){cut("COMPLETION_COHORT",settled.size()-i+continuation);return false;}
                TransferTraceEvent e=events.get(index);Flow f=settled.get(i);
                require(e.getType()==TransferTraceEvent.Type.COMPLETE,at("Missing expected COMPLETE"));identity(e,f);
                same(cursor,e.getEffectiveTime(),at("Completion effective time mismatch"));same(observed,e.getObservedTime(),at("Completion observation mismatch"));
                same(f.remaining,e.getComplete().getRemainingAfterService(),at("Numerical settlement residual mismatch"));
                active.remove(f.ordinal);externalIds.remove(f.externalId);completed++;index++;
            }
            if(!settled.isEmpty())return rates(null,observed,"COMPLETION_RATE_CHANGES");
            certified=index;return true;
        }

        /** Necessary bounds on final rates already fixed by a truncated transition. */
        void fixedAllocationBounds(Set<Long> variable){
            Map<String,Resource> fixed=new LinkedHashMap<String,Resource>();
            for(Flow flow:active.values())for(String key:flow.resources){
                Double capacity=capacities.get(key);if(capacity==null)continue;
                Resource r=fixed.get(key);if(r==null){r=new Resource(capacity);fixed.put(key,r);}
                r.terms++; // Keep the profile's ALL-active occupancy scale, not just fixed users.
                if(!variable.contains(flow.ordinal)){
                    r.load=r.load.add(decimal(flow.rate));r.maximumRate=Math.max(r.maximumRate,flow.rate);
                }
            }
            for(Resource r:fixed.values())require(r.load.subtract(decimal(r.capacity)).compareTo(ulpBudget(r.capacity,r.terms))<=0,
                    at("Already-fixed prefix allocation exceeds resource capacity"));
            Map<Long,BigDecimal> variableUpper=new HashMap<Long,BigDecimal>();
            for(Flow flow:active.values())if(variable.contains(flow.ordinal)){
                BigDecimal upper=decimal(flow.nominal);
                Map<String,Long> multiplicity=new HashMap<String,Long>();
                for(String key:flow.resources)multiplicity.put(key,multiplicity.containsKey(key)?multiplicity.get(key)+1L:1L);
                for(Map.Entry<String,Long> entry:multiplicity.entrySet()){
                    Resource r=fixed.get(entry.getKey());if(r==null)continue;
                    BigDecimal available=decimal(r.capacity).add(ulpBudget(r.capacity,r.terms)).subtract(r.load);
                    require(available.signum()>0,at("No positive capacity remains for a variable prefix flow"));
                    upper=upper.min(available.divide(BigDecimal.valueOf(entry.getValue()),new MathContext(40,RoundingMode.CEILING)));
                }
                variableUpper.put(flow.ordinal,upper);
            }
            for(Flow flow:active.values()){
                if(variable.contains(flow.ordinal)||near(flow.rate,flow.nominal,active.size()))continue;
                // Any final user no greater than this rate within the capped relative profile
                // must be <= rate/(1-1e-12). Ceiling arithmetic is a conservative upper bound.
                BigDecimal tieUpper=decimal(flow.rate).divide(BigDecimal.ONE.subtract(new BigDecimal("1e-12")),
                        new MathContext(40,RoundingMode.CEILING));
                boolean possible=false;
                for(String key:flow.resources){
                    Resource r=fixed.get(key);if(r==null)continue;
                    if(decimal(r.maximumRate).subtract(decimal(flow.rate)).compareTo(
                            ulpBudget(Math.max(r.maximumRate,flow.rate),active.size()))>0)continue;
                    BigDecimal upper=r.load;
                    for(Flow other:active.values())if(variable.contains(other.ordinal)){
                        BigDecimal candidate=variableUpper.get(other.ordinal).min(tieUpper);
                        for(String occupied:other.resources)if(key.equals(occupied))upper=upper.add(candidate);
                    }
                    if(decimal(r.capacity).subtract(upper).compareTo(ulpBudget(r.capacity,r.terms))<=0){possible=true;break;}
                }
                require(possible,at("Fixed prefix rate cannot acquire any max-min bottleneck certificate"));
            }
        }

        void allocation(){
            Map<String,Resource> resources=new LinkedHashMap<>();
            for(Flow f:active.values()){
                positive(f.rate,"allocated rate");require(f.rate<=f.nominal,at("Rate exceeds nominal cap"));
                for(String key:f.resources){
                    Double cap=capacities.get(key);if(cap==null)continue;
                    Resource r=resources.get(key);if(r==null){r=new Resource(cap);resources.put(key,r);}
                    r.load=r.load.add(decimal(f.rate));r.terms++;
                    r.maximumRate=Math.max(r.maximumRate,f.rate);
                }
            }
            for(Resource r:resources.values())require(r.load.subtract(decimal(r.capacity)).compareTo(ulpBudget(r.capacity,r.terms))<=0,at("Resource capacity exceeded"));
            for(Flow f:active.values()){
                if(near(f.rate,f.nominal,active.size()))continue;
                boolean bottleneck=false;
                for(String key:f.resources){
                    Resource r=resources.get(key);if(r==null)continue;
                    boolean saturated=r.load.subtract(decimal(r.capacity)).abs().compareTo(ulpBudget(r.capacity,r.terms))<=0;
                    boolean noHigher=decimal(r.maximumRate).subtract(decimal(f.rate)).compareTo(ulpBudget(Math.max(r.maximumRate,f.rate),active.size()))<=0;
                    if(saturated&&noHigher){bottleneck=true;break;}
                }
                require(bottleneck,at("Allocation lacks a max-min bottleneck certificate"));
            }
        }

        Flow flow(TransferTraceEvent e){
            Flow f=active.get(e.getAdmissionOrdinal());require(f!=null,at("Unknown or completed admission"));identity(e,f);return f;
        }
        void identity(TransferTraceEvent e,Flow f){require(e.getAdmissionOrdinal()==f.ordinal&&e.getTransferId()==f.externalId,at("Wrong admission identity or order"));}
        void cut(String phase,long missing){
            require(truncated,at("Complete capture ends inside "+phase));
            require(snapshot.getDroppedCount()>=missing,at("Dropped count cannot cover known missing records"));
            partial=true;tail=phase;minimumMissing=missing;
        }
        String at(String message){return "Network trace at record "+(index+1)+": "+message;}
    }

    private static void shape(TransferTraceEvent e){
        int payloads=(e.getCapacity()!=null?1:0)+(e.getStart()!=null?1:0)+(e.getRateChange()!=null?1:0)
                +(e.getServiceSegment()!=null?1:0)+(e.getComplete()!=null?1:0);
        require(payloads==1,"An event must contain exactly one typed payload");
        if(e.getType()==TransferTraceEvent.Type.CAPACITY){
            require(e.getCapacity()!=null&&e.getTransferId()==null&&e.getAdmissionOrdinal()==null,"Invalid CAPACITY shape");
            require(e.getCapacity().getResourceKey()!=null&&!e.getCapacity().getResourceKey().isEmpty(),"Invalid capacity resource key");
            positive(e.getCapacity().getCapacityBytesPerSecond(),"capacity");same(e.getEffectiveTime(),e.getObservedTime(),"Capacity clocks differ");return;
        }
        require(e.getTransferId()!=null&&e.getAdmissionOrdinal()!=null&&e.getAdmissionOrdinal()>0,"Flow identity is required");
        switch(e.getType()){
            case START:
                require(e.getStart()!=null,"Invalid START payload");
                positive(e.getStart().getBytes(),"demand");positive(e.getStart().getNominalRateBytesPerSecond(),"nominal rate");
                positive(e.getStart().getInitialRateBytesPerSecond(),"initial rate");
                positive(e.getStart().getBytes()/e.getStart().getNominalRateBytesPerSecond(),"nominal duration");
                require(e.getStart().getInitialRateBytesPerSecond()<=e.getStart().getNominalRateBytesPerSecond(),"Initial rate exceeds nominal");
                require(e.getStart().getOccupiedResources()!=null,"Resources are required");
                for(String key:e.getStart().getOccupiedResources())require(key!=null,"Null occupied resource");
                same(e.getEffectiveTime(),e.getObservedTime(),"Admission clocks differ");break;
            case RATE_CHANGE:
                require(e.getRateChange()!=null,"Invalid RATE_CHANGE payload");
                positive(e.getRateChange().getPreviousRateBytesPerSecond(),"previous rate");positive(e.getRateChange().getRateBytesPerSecond(),"new rate");break;
            case SERVICE_SEGMENT:
                require(e.getServiceSegment()!=null,"Invalid SERVICE payload");
                TransferTraceEvent.ServiceSegment s=e.getServiceSegment();
                nonNegative(s.getIntervalStart(),"interval start");nonNegative(s.getIntervalEnd(),"interval end");
                positive(s.getElapsed(),"service elapsed");positive(s.getRateBytesPerSecond(),"service rate");
                nonNegative(s.getRemainingBefore(),"remaining before");nonNegative(s.getRemainingAfter(),"remaining after");break;
            case COMPLETE:
                require(e.getComplete()!=null,"Invalid COMPLETE payload");nonNegative(e.getComplete().getRemainingAfterService(),"settlement residual");break;
            default: throw bad("Unsupported flow event");
        }
    }
    private static double tolerance(double bytes){return Math.min(bytes*.5,Math.max(1e-9*bytes,4.0*Math.ulp(bytes)));}
    private static BigDecimal decimal(double v){return new BigDecimal(v);}
    private static BigDecimal ulpBudget(double scale,long terms){
        BigDecimal ulps=decimal(Math.ulp(scale)).multiply(BigDecimal.valueOf(8L)).multiply(BigDecimal.valueOf(terms+1L));
        return ulps.min(decimal(scale).scaleByPowerOfTen(-12));
    }
    private static boolean near(double a,double b,long terms){return decimal(a).subtract(decimal(b)).abs().compareTo(ulpBudget(Math.max(a,b),terms))<=0;}
    private static void positive(double value,String field){require(Double.isFinite(value)&&value>0,"Expected positive finite "+field);}
    private static void nonNegative(double value,String field){require(Double.isFinite(value)&&value>=0,"Expected nonnegative finite "+field);}
    private static void same(double expected,double actual,String message){require(expected==actual,message+" (expected "+expected+", got "+actual+")");}
    private static void require(boolean condition,String message){if(!condition)throw bad(message);}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
