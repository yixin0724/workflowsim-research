package org.workflowsim.data.v2;

/**
 * Immutable storage-version capture carrier. It is deliberately not a V2 certificate:
 * bounded store resources, deferred reads and output obligations need their own codec/context.
 */
public final class StorageLifecycleEvidence {
    private final FileLifecycleEvidence capture;
    private final boolean storeBackedInputs;
    private StorageLifecycleEvidence(FileLifecycleEvidence capture,boolean storeBackedInputs){this.capture=capture;this.storeBackedInputs=storeBackedInputs;}
    /**
     * @param capture immutable common observed-event carrier, not independently certified here
     * @param storeBackedInputs require SOURCE commitment before VM read-through/cache access
     * @return detached storage-version capture
     */
    public static StorageLifecycleEvidence capture(FileLifecycleEvidence capture,boolean storeBackedInputs){
        if(capture==null||!capture.getFabric().has("sourceStorage"))throw new IllegalArgumentException("Storage lifecycle requires an explicit bounded fabric");return new StorageLifecycleEvidence(capture,storeBackedInputs);
    }
    /** @return common immutable capture; never pass it through the V2-only codec */
    public FileLifecycleEvidence getCapture(){return capture;}
    /** @return independent input-access policy, not the inter-flow sharing switch */
    public boolean isStoreBackedInputs(){return storeBackedInputs;}
}
