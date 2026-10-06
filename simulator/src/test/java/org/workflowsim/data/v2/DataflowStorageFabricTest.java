package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;

class DataflowStorageFabricTest {
    private static final DataLocation SOURCE=DataLocation.source("source"),A=DataLocation.vm(7),B=DataLocation.vm(42);
    @Test void boundedReadsAndWritesRetainDistinctDiskAndSharedNicResources(){
        DataTransferFabric fabric=storage(null,DataflowStorageSpec.of(30,3,.5,5));
        DataTransferFabric.Route read=fabric.route(SOURCE,A),write=fabric.route(A,SOURCE);
        assertEquals(Arrays.asList("STORE:source:READ","STORE:source:NIC","VM:7"),read.getResources());assertEquals(3e6,read.getStandaloneRateBytesPerSecond(),0);assertFalse(read.isUnboundedOffFabricSource());
        assertEquals(Arrays.asList("VM:7","STORE:source:NIC","STORE:source:WRITE"),write.getResources());assertEquals(.5e6,write.getStandaloneRateBytesPerSecond(),0);
    }
    @Test void actualStoreAttachmentDeterminesDirectedFatTreeRoutes(){
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,2),Arrays.asList(10,20,30));DataTransferFabric fabric=storage(topology,DataflowStorageSpec.of(30,3,.5,5));
        assertEquals(Arrays.asList("STORE:source:READ","STORE:source:NIC","LINK:ACC:30->EDGE:1:0","LINK:EDGE:1:0->AGG:1:0","LINK:AGG:1:0->CORE:1","LINK:CORE:1->AGG:0:0","LINK:AGG:0:0->EDGE:0:0","LINK:EDGE:0:0->ACC:10","VM:7"),fabric.route(SOURCE,A).getResources());
        assertEquals(Arrays.asList("VM:7","LINK:ACC:10->EDGE:0:0","LINK:EDGE:0:0->AGG:0:0","LINK:AGG:0:0->CORE:1","LINK:CORE:1->AGG:1:0","LINK:AGG:1:0->EDGE:1:0","LINK:EDGE:1:0->ACC:30","STORE:source:NIC","STORE:source:WRITE"),fabric.route(A,SOURCE).getResources());
        assertEquals(2e6,fabric.route(SOURCE,A).getStandaloneRateBytesPerSecond(),0);assertEquals(.5e6,fabric.route(A,SOURCE).getStandaloneRateBytesPerSecond(),0);
        for(String key:fabric.route(SOURCE,A).getResources())assertNotNull(fabric.getResourceCapacities().get(key));
    }
    @Test void sameHostStillConsumesStoreAndVmResources(){
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,2),Arrays.asList(10,20,30));DataTransferFabric fabric=storage(topology,DataflowStorageSpec.of(10,3,.5,5));
        assertEquals(Arrays.asList("STORE:source:READ","STORE:source:NIC","VM:7"),fabric.route(SOURCE,A).getResources());assertEquals(Arrays.asList("VM:7","STORE:source:NIC","STORE:source:WRITE"),fabric.route(A,SOURCE).getResources());
    }
    @Test void sourceReadCapacityIsSharedAcrossDifferentVmDestinations(){
        DataTransferFabric fabric=storage(null,DataflowStorageSpec.of(30,.00001,.0001,.0001));TransferContentionEngine engine=engine(fabric,true);
        admit(engine,1,fabric.route(SOURCE,A));admit(engine,2,fabric.route(SOURCE,B));assertEquals(5,engine.currentRateBytesPerSecond(1),0);assertEquals(Arrays.asList(1L,2L),engine.advance(20).getCompletedTransferIds());
        TransferContentionEngine isolated=engine(fabric,false);admit(isolated,1,fabric.route(SOURCE,A));admit(isolated,2,fabric.route(SOURCE,B));assertEquals(Arrays.asList(1L,2L),isolated.advance(10).getCompletedTransferIds());
    }
    @Test void storeNetworkCapacityCouplesConcurrentReadAndWrite(){
        DataTransferFabric fabric=storage(null,DataflowStorageSpec.of(30,.0001,.0001,.00001));TransferContentionEngine engine=engine(fabric,true);admit(engine,1,fabric.route(SOURCE,A));admit(engine,2,fabric.route(B,SOURCE));assertEquals(5,engine.currentRateBytesPerSecond(1),0);assertEquals(5,engine.currentRateBytesPerSecond(2),0);
    }
    @Test void originalV2SourceAbstractionIsUnchanged(){
        DataTransferFabric old=DataTransferFabric.endpoints(capacities(),Arrays.asList("source"));assertTrue(old.route(SOURCE,A).isUnboundedOffFabricSource());assertEquals(Arrays.asList("VM:7"),old.route(SOURCE,A).getResources());assertNull(old.getSourceStorage());assertThrows(IllegalArgumentException.class,()->old.route(A,SOURCE));
    }
    @Test void rejectsUnknownAttachmentAndIncompleteActualVmMapping(){
        FatTreeTopology t=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,2),Arrays.asList(10,20));assertThrows(IllegalArgumentException.class,()->storage(t,DataflowStorageSpec.of(30,1,1,1)));
        Map<Integer,Integer> hosts=hosts();hosts.remove(42);assertThrows(IllegalArgumentException.class,()->DataTransferFabric.withStorage(capacities(),hosts,null,DataflowStorageSpec.of(10,1,1,1)));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.withStorage(capacities(),hosts(),null,null));assertThrows(IllegalArgumentException.class,()->storage(null,DataflowStorageSpec.of(10,1,1,1)).route(SOURCE,SOURCE));
    }
    @Test void platformRejectsUnplacedStoreWithoutChangingOldDefaults(){
        PlatformProfile.Builder builder=PlatformProfile.builder("storage").addHost(new PlatformProfile.HostSpec(10,1,1000,2048,10000,100000)).addVm(new PlatformProfile.VmSpec(7,1000,1,512,1,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        assertNull(builder.build().getSourceStorage());assertThrows(IllegalArgumentException.class,()->builder.sourceStorage(DataflowStorageSpec.of(30,1,1,1)).build());assertEquals(10,builder.sourceStorage(DataflowStorageSpec.of(10,1,2,3)).build().getSourceStorage().getAttachmentHostId());
    }
    @Test void mapsAndStorageParametersAreFrozen(){
        Map<Integer,Double> caps=capacities();Map<Integer,Integer> hosts=hosts();DataflowStorageSpec spec=DataflowStorageSpec.of(30,1,2,3);DataTransferFabric f=DataTransferFabric.withStorage(caps,hosts,null,spec);caps.clear();hosts.clear();assertSame(spec,f.getSourceStorage());assertEquals(Integer.valueOf(10),f.getActualVmHosts().get(7));assertThrows(UnsupportedOperationException.class,()->f.getResourceCapacities().clear());
    }
    private static DataTransferFabric storage(FatTreeTopology topology,DataflowStorageSpec spec){return DataTransferFabric.withStorage(capacities(),hosts(),topology,spec);}
    private static Map<Integer,Double> capacities(){Map<Integer,Double> m=new LinkedHashMap<>();m.put(7,100e6);m.put(42,100e6);return m;}
    private static Map<Integer,Integer> hosts(){Map<Integer,Integer> m=new LinkedHashMap<>();m.put(7,10);m.put(42,20);return m;}
    private static TransferContentionEngine engine(DataTransferFabric f,boolean shared){TransferContentionEngine e=TransferContentionEngine.coherentV2(shared);f.getResourceCapacities().forEach(e::setEndpointCapacity);return e;}
    private static void admit(TransferContentionEngine e,long id,DataTransferFabric.Route route){e.addTransfer(id,100,route.getResources(),route.getStandaloneRateBytesPerSecond(),0);}
}
