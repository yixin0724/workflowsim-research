package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.workflowsim.network.FatTreeTopology;

/**
 * Pure immutable resource/placement view for V2 input selection. Capacities are modeled BYTES/s.
 * SOURCE access is explicitly unbounded and off-fabric in NF003; missing VM/link capacities
 * never mean unbounded. The optional FatTreeTopology must already be safely constructed by
 * its caller. This adapter does not allocate a topology from an untrusted size parameter.
 */
public final class DataTransferFabric {
    /** Immutable selected path, including the exact standalone bottleneck used for admission. */
    public static final class Route {
        private final DataLocation source,destination;
        private final List<String> resources;
        private final double rate;
        private final boolean sourceBypass;
        private Route(DataLocation source,DataLocation destination,List<String> path,Map<String,Double> capacities){this(source,destination,path,capacities,source.getKind()==DataLocation.Kind.SOURCE);}
        private Route(DataLocation source,DataLocation destination,List<String> path,Map<String,Double> capacities,boolean bypass){
            this.source=source;this.destination=destination;sourceBypass=bypass;
            resources=Collections.unmodifiableList(new ArrayList<>(path));Map<String,Integer> multiplicities=new LinkedHashMap<>();
            for(String key:resources){if(!capacities.containsKey(key))throw bad("Unknown physical resource on selected route: "+key);Integer old=multiplicities.get(key);multiplicities.put(key,old==null?1:Math.addExact(old,1));}
            if(resources.isEmpty())throw bad("A positive remote input route must constrain its destination");
            double bottleneck=Double.MAX_VALUE;for(Map.Entry<String,Integer> entry:multiplicities.entrySet())bottleneck=Math.min(bottleneck,capacities.get(entry.getKey())/entry.getValue());
            requireCapacity(bottleneck);rate=bottleneck;
        }
        /** @return actual replica source */ public DataLocation getSource(){return source;}
        /** @return fixed destination */ public DataLocation getDestination(){return destination;}
        /** @return immutable ordered resources, with multiplicity preserved */ public List<String> getResources(){return resources;}
        /** @return minimum resource capacity divided by its path multiplicity */ public double getStandaloneRateBytesPerSecond(){return rate;}
        /** @return explicit NF003 unbounded off-fabric SOURCE input abstraction */ public boolean isUnboundedOffFabricSource(){return sourceBypass;}
    }

    private final Set<DataLocation> locations;
    private final Map<String,Double> capacities;
    private final Map<Integer,Integer> hosts;
    private final FatTreeTopology topology;
    private final DataflowStorageSpec sourceStorage;
    public static final String STORE_READ="STORE:source:READ",STORE_WRITE="STORE:source:WRITE",STORE_NIC="STORE:source:NIC";
    private DataTransferFabric(Map<Integer,Double> endpointBytes,Map<Integer,Integer> actualHosts,FatTreeTopology topology,Collection<String> sources){
        if(endpointBytes==null||sources==null)throw bad("VM capacities and explicit SOURCE locations are required");
        Set<DataLocation> known=new TreeSet<>();Map<String,Double> values=new TreeMap<>();
        for(Map.Entry<Integer,Double> entry:endpointBytes.entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)throw bad("VM endpoint capacity entries cannot be null");
            DataLocation vm=DataLocation.vm(entry.getKey());requireCapacity(entry.getValue());known.add(vm);values.put(vm.toString(),entry.getValue());
        }
        for(String id:sources)if(!known.add(DataLocation.source(id)))throw bad("Duplicate SOURCE location");
        Map<Integer,Integer> placement=new TreeMap<>();
        if(topology!=null){
            if(actualHosts==null||!actualHosts.keySet().equals(endpointBytes.keySet()))throw bad("Actual host mapping must cover exactly every VM endpoint");
            Set<Integer> placed=topology.getHostPlacements().keySet();requireCapacity(topology.getLinkBandwidthBytesPerSecond());
            for(Map.Entry<Integer,Integer> entry:actualHosts.entrySet()){
                Integer host=entry.getValue();if(host==null||host<0||!placed.contains(host))throw bad("VM is not assigned to a known physical host");placement.put(entry.getKey(),host);
            }
            for(String link:topology.getLinkIds())if(values.put(link,topology.getLinkBandwidthBytesPerSecond())!=null)throw bad("Physical resource keys collide");
        }else if(actualHosts!=null)throw bad("Host mapping without a topology is unsupported");
        locations=Collections.unmodifiableSet(known);capacities=Collections.unmodifiableMap(values);hosts=Collections.unmodifiableMap(placement);this.topology=topology;sourceStorage=null;
    }

    /**
     * @param vmCapacityBytesPerSecond positive normal finite binary64 VM capacities
     * @param unboundedSources explicit distinct SOURCE identifiers
     * @return endpoint-only immutable fabric
     */
    public static DataTransferFabric endpoints(Map<Integer,Double> vmCapacityBytesPerSecond,Collection<String> unboundedSources){return new DataTransferFabric(vmCapacityBytesPerSecond,null,null,unboundedSources);}
    /**
     * @param vmCapacityBytesPerSecond positive normal finite binary64 VM capacities
     * @param actualVmHosts complete actual VM-to-host map, not logical producer placements
     * @param topology already constructed immutable Fat-tree routing/physical-link view
     * @param unboundedSources explicit distinct off-fabric SOURCE identifiers
     * @return immutable endpoint plus deterministic directed-link fabric
     */
    public static DataTransferFabric fatTree(Map<Integer,Double> vmCapacityBytesPerSecond,Map<Integer,Integer> actualVmHosts,FatTreeTopology topology,Collection<String> unboundedSources){
        if(topology==null)throw bad("Fat-tree view is required");return new DataTransferFabric(vmCapacityBytesPerSecond,actualVmHosts,topology,unboundedSources);
    }
    private DataTransferFabric(DataTransferFabric base,Map<Integer,Integer> actualHosts,DataflowStorageSpec storage){
        if(storage==null||actualHosts==null)throw bad("Bounded store and actual VM hosts are required");
        Set<Integer> vmIds=new TreeSet<>();for(DataLocation location:base.locations)if(location.getKind()==DataLocation.Kind.VM)vmIds.add(location.getVmId());
        if(!vmIds.equals(actualHosts.keySet()))throw bad("Storage fabric needs every actual VM host");Map<Integer,Integer> placement=new TreeMap<>();
        for(Map.Entry<Integer,Integer> entry:actualHosts.entrySet()){if(entry.getValue()==null||entry.getValue()<0)throw bad("Invalid actual VM host");placement.put(entry.getKey(),entry.getValue());}
        if(base.topology!=null&&!base.topology.getHostPlacements().containsKey(storage.getAttachmentHostId()))throw bad("Store attachment host is not placed in topology");
        Map<String,Double> resources=new TreeMap<>(base.capacities);resources.put(STORE_READ,storage.getReadCapacityBytesPerSecond());resources.put(STORE_WRITE,storage.getWriteCapacityBytesPerSecond());resources.put(STORE_NIC,storage.getNetworkCapacityBytesPerSecond());
        locations=base.locations;topology=base.topology;hosts=Collections.unmodifiableMap(placement);capacities=Collections.unmodifiableMap(resources);sourceStorage=storage;
    }
    /**
     * @param vmCapacityBytesPerSecond normal finite VM capacities
     * @param actualVmHosts complete actual VM host map
     * @param topology optional already-validated topology
     * @param storage explicit bounded store; endpoint-only host membership is checked by PlatformProfile
     * @return bounded store read/write and shared-NIC fabric, distinct from old V2 factories
     */
    public static DataTransferFabric withStorage(Map<Integer,Double> vmCapacityBytesPerSecond,Map<Integer,Integer> actualVmHosts,FatTreeTopology topology,DataflowStorageSpec storage){
        DataTransferFabric base=new DataTransferFabric(vmCapacityBytesPerSecond,topology==null?null:actualVmHosts,topology,Collections.singletonList("source"));return new DataTransferFabric(base,actualVmHosts,storage);
    }
    /** @return explicit bounded store, or null for original V2 semantics */ public DataflowStorageSpec getSourceStorage(){return sourceStorage;}
    /** @return immutable typed known locations */ public Set<DataLocation> getLocations(){return locations;}
    /** @return immutable normal finite capacities in bytes/s; SOURCE is explicitly absent */ public Map<String,Double> getResourceCapacities(){return capacities;}
    /** @return frozen actual VM host mapping, empty in endpoint-only mode */ public Map<Integer,Integer> getActualVmHosts(){return hosts;}
    /** @return immutable physical routing view, or null for endpoint-only mode */ public FatTreeTopology getTopology(){return topology;}

    /**
     * Compute the path once from the actual selected source and actual VM placements.
     * Local references have no positive route and are resolved by the selector instead.
     * @param source known VM holder or explicitly unbounded SOURCE
     * @param destination known different VM destination
     * @return immutable path and standalone bottleneck, never an estimator-only source guess
     */
    public Route route(DataLocation source,DataLocation destination){
        requireLocation(source);requireLocation(destination);
        if(sourceStorage!=null)return storageRoute(source,destination);
        if(destination.getKind()!=DataLocation.Kind.VM||source.equals(destination))throw bad("NF003 input routes require a remote VM destination; local/output paths are separate cases");
        List<String> resources=new ArrayList<>();
        if(source.getKind()==DataLocation.Kind.VM){
            resources.add(source.toString());
            if(topology!=null)resources.addAll(topology.route(hosts.get(source.getVmId()),hosts.get(destination.getVmId())));
        }
        resources.add(destination.toString());return new Route(source,destination,resources,capacities);
    }
    private Route storageRoute(DataLocation source,DataLocation destination){
        if(source.equals(destination))throw bad("A local object has no positive storage route");
        boolean reading=source.getKind()==DataLocation.Kind.SOURCE,writing=destination.getKind()==DataLocation.Kind.SOURCE;
        if(reading&&writing)throw bad("Store-to-store routing is unsupported");List<String> resources=new ArrayList<>();
        if(reading){resources.add(STORE_READ);resources.add(STORE_NIC);}else resources.add(source.toString());
        int from=reading?sourceStorage.getAttachmentHostId():hosts.get(source.getVmId());int to=writing?sourceStorage.getAttachmentHostId():hosts.get(destination.getVmId());
        if(topology!=null)resources.addAll(topology.route(from,to));
        if(writing){resources.add(STORE_NIC);resources.add(STORE_WRITE);}else resources.add(destination.toString());
        return new Route(source,destination,resources,capacities,false);
    }
    void requireLocation(DataLocation location){if(location==null||!locations.contains(location))throw bad("Unknown fabric location: "+location);}
    private static void requireCapacity(double capacity){if(!Double.isFinite(capacity)||capacity<Double.MIN_NORMAL)throw bad("V2 fabric capacities and standalone rates must be positive normal finite binary64 values");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
