package org.workflowsim.data.v2;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Frozen V2 file lifecycle capture; distinct from a fluid rate/service-accounting certificate. */
public final class FileLifecycleEvidence {
    /** Complete-only export requires no dropped lifecycle operations. */
    public enum Status { COMPLETE, TRUNCATED }
    private final int maxRecords;
    private final long droppedRecords;
    private final double observedThrough;
    private final boolean shared;
    private final JsonObject filePlan,fabric;
    private final List<FileLifecycleEvent> events;
    private FileLifecycleEvidence(int maxRecords,long dropped,double time,boolean shared,JsonObject plan,JsonObject fabric,List<FileLifecycleEvent> events){
        this.maxRecords=maxRecords;droppedRecords=dropped;observedThrough=time;this.shared=shared;filePlan=plan.deepCopy();this.fabric=fabric.deepCopy();this.events=Collections.unmodifiableList(new ArrayList<>(events));
    }
    /**
     * Freeze detached capture data. Full structural/lifecycle validation is performed by the codec.
     * @param budget positive record budget
     * @param dropped dropped operation count
     * @param observedThrough final runtime observation
     * @param shared inter-flow capacity sharing policy
     * @param plan resolved logical plan
     * @param fabric actual resource/placement snapshot
     * @param events retained prefix
     * @return immutable evidence
     */
    public static FileLifecycleEvidence capture(int budget,long dropped,double observedThrough,boolean shared,JsonObject plan,JsonObject fabric,List<FileLifecycleEvent> events){
        if(budget<1||dropped<0||!Double.isFinite(observedThrough)||observedThrough<0||plan==null||fabric==null||events==null||events.size()>budget||(dropped>0&&events.size()!=budget))throw new IllegalArgumentException("Invalid file lifecycle capture shape");
        long sequence=1;double previous=0;for(FileLifecycleEvent event:events){if(event==null||event.getSequence()!=sequence++||event.getObservedTime()<previous||event.getObservedTime()>observedThrough)throw new IllegalArgumentException("Invalid file lifecycle prefix order");previous=event.getObservedTime();}
        return new FileLifecycleEvidence(budget,dropped,observedThrough,shared,plan,fabric,events);
    }
    /** @return complete versus lost prefix */ public Status getStatus(){return droppedRecords==0?Status.COMPLETE:Status.TRUNCATED;}
    /** @return lifecycle record cap, not byte cap */ public int getMaxRecords(){return maxRecords;}
    /** @return dropped lifecycle operations */ public long getDroppedRecords(){return droppedRecords;}
    /** @return final observation without advancing service */ public double getObservedThrough(){return observedThrough;}
    /** @return true for shared resources, false for isolated single-flow caps */ public boolean isShared(){return shared;}
    /** @return defensive resolved-plan copy */ public JsonObject getFilePlan(){return filePlan.deepCopy();}
    /** @return defensive actual-fabric copy */ public JsonObject getFabric(){return fabric.deepCopy();}
    /** @return immutable retained events */ public List<FileLifecycleEvent> getEvents(){return events;}
}
