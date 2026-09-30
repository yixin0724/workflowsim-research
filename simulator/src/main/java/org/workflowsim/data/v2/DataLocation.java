package org.workflowsim.data.v2;

/** Typed logical data location. This identity does not imply a route, capacity or storage service. */
public final class DataLocation implements Comparable<DataLocation> {
    /** Location category; arbitrary SOURCE labels cannot masquerade as VM identities. */
    public enum Kind { VM, SOURCE }
    private final Kind kind;
    private final int vmId;
    private final String sourceId;
    private DataLocation(Kind kind,int vmId,String sourceId){this.kind=kind;this.vmId=vmId;this.sourceId=sourceId;}
    /**
     * @param vmId nonnegative actual VM identifier
     * @return immutable VM location
     */
    public static DataLocation vm(int vmId){if(vmId<0)throw new IllegalArgumentException("VM location ID must be nonnegative");return new DataLocation(Kind.VM,vmId,null);}
    /**
     * @param sourceId exact nonempty modeled storage/source identifier
     * @return immutable source location
     */
    public static DataLocation source(String sourceId){if(sourceId==null||sourceId.isEmpty())throw new IllegalArgumentException("SOURCE location needs a nonempty ID");return new DataLocation(Kind.SOURCE,-1,sourceId);}
    /** @return typed category */ public Kind getKind(){return kind;}
    /** @return VM ID; rejects a SOURCE location */ public int getVmId(){if(kind!=Kind.VM)throw new IllegalStateException("Not a VM location");return vmId;}
    /** @return exact SOURCE identifier; rejects a VM location */ public String getSourceId(){if(kind!=Kind.SOURCE)throw new IllegalStateException("Not a SOURCE location");return sourceId;}
    @Override public int compareTo(DataLocation other){
        if(kind!=other.kind)return kind==Kind.VM?-1:1;
        return kind==Kind.VM?Integer.compare(vmId,other.vmId):sourceId.compareTo(other.sourceId);
    }
    @Override public boolean equals(Object other){
        if(this==other)return true;if(!(other instanceof DataLocation))return false;DataLocation location=(DataLocation)other;
        return kind==location.kind&&(kind==Kind.VM?vmId==location.vmId:sourceId.equals(location.sourceId));
    }
    @Override public int hashCode(){return kind==Kind.VM?31+vmId:62+sourceId.hashCode();}
    @Override public String toString(){return kind==Kind.VM?"VM:"+vmId:"SOURCE:"+sourceId;}
}
