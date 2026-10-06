package org.workflowsim.data.v2;

import com.google.gson.JsonObject;

/** Immutable observed lifecycle record. Payload semantics are validated by the versioned codec. */
public final class FileLifecycleEvent {
    /** Actual successful runtime operations, ordered even when observations share a timestamp. */
    public enum Type { EXTERNAL_SEEDED, TASK_FINISHED, JOB_INPUT_REQUESTED, INPUT_RESOLVED, COPY_ADMITTED, COPY_SETTLED, JOB_DATA_READY, JOB_CPU_STARTED }
    private final long sequence;
    private final double observedTime;
    private final Type type;
    private final JsonObject payload;
    private FileLifecycleEvent(long sequence,double observedTime,Type type,JsonObject payload){this.sequence=sequence;this.observedTime=observedTime;this.type=type;this.payload=payload.deepCopy();}
    /**
     * @param sequence positive retained sequence
     * @param observedTime finite nonnegative outer observation
     * @param type event kind
     * @param payload detached payload (copied)
     * @return immutable record; this factory is not a semantic certificate
     */
    public static FileLifecycleEvent of(long sequence,double observedTime,Type type,JsonObject payload){
        if(sequence<1||!Double.isFinite(observedTime)||observedTime<0||type==null||payload==null)throw new IllegalArgumentException("Invalid file lifecycle event fields");
        return new FileLifecycleEvent(sequence,observedTime==0?0:observedTime,type,payload);
    }
    /** @return retained ordinal */ public long getSequence(){return sequence;}
    /** @return observed operation time */ public double getObservedTime(){return observedTime;}
    /** @return event type */ public Type getType(){return type;}
    /** @return defensive payload copy */ public JsonObject getPayload(){return payload.deepCopy();}
}
