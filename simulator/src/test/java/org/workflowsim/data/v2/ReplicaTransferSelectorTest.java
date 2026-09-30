package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.data.TransferTraceValidator;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.utils.Parameters.FileType;

/** Same concrete source/route plan feeds both isolated estimation and actual fluid admission. */
class ReplicaTransferSelectorTest {
    private static final DataLocation SOURCE=DataLocation.source("input-store"),VM7=DataLocation.vm(7),VM42=DataLocation.vm(42),VM99=DataLocation.vm(99);
    private static final DataflowFilePlan.FileId FILE=DataflowFilePlan.FileId.of(0,"file");

    @Test void fasterVisibleReplicaIsTheChargedSourceNotTheLogicalProducer(){
        Map<Integer,Double> capacities=capacities(1e6,10e6,10e6);DataTransferFabric fabric=DataTransferFabric.endpoints(capacities,Arrays.asList("input-store"));
        DataReplicaState state=withFastReplica(5e6);state.advanceTo(10);
        ReplicaTransferSelector.Decision decision=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        assertEquals(ReplicaTransferSelector.Outcome.NETWORK_COPY,decision.getOutcome());assertEquals(VM42,decision.getSourceReplica().getLocation());
        assertEquals(Integer.valueOf(1),decision.getSourceReplica().getOrigin().getProducerTaskId());assertEquals(VM7,decision.getSourceReplica().getOrigin().getLocation());
        assertEquals(Arrays.asList("VM:42","VM:99"),decision.getRoute().getResources());assertEquals(10e6,decision.getRoute().getStandaloneRateBytesPerSecond(),0);
        assertEquals(.5,decision.getIsolatedSeconds(),0);assertEquals(10.5,decision.getIsolatedCompletionTime(),0);
        DataReplicaState.CopyTicket ticket=state.admitCopy(FILE,decision.getSourceReplica().getLocation(),decision.getDestination(),decision.getSelectedAt());
        TransferContentionEngine engine=engine(fabric);admit(engine,decision,ticket.getOrdinal());engine.advance(10.5);
        TransferTraceEvent start=null;for(TransferTraceEvent e:engine.getTraceSnapshot().getEvents())if(e.getType()==TransferTraceEvent.Type.START)start=e;
        assertNotNull(start);assertEquals(decision.getRoute().getResources(),start.getStart().getOccupiedResources());assertFalse(start.getStart().getOccupiedResources().contains("VM:7"));
        assertEquals(10e6,start.getStart().getInitialRateBytesPerSecond(),0);assertTrue(TransferTraceValidator.validate(engine.getTraceSnapshot(),10000).isCompleteCapture());
    }

    @Test void selectedReplicaHostAndEveryLinkCapacityDetermineTheSameRouteAndRate(){
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,2),Arrays.asList(10,20,30));
        Map<Integer,Integer> hosts=hosts(10,20,30);DataTransferFabric fabric=DataTransferFabric.fatTree(capacities(1e6,10e6,10e6),hosts,topology,Arrays.asList("input-store"));
        DataReplicaState state=withFastReplica(5e6);state.advanceTo(10);ReplicaTransferSelector.Decision d=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        assertEquals(VM42,d.getSourceReplica().getLocation());assertEquals(Arrays.asList("VM:42","LINK:ACC:20->EDGE:0:1","LINK:EDGE:0:1->AGG:0:1",
                "LINK:AGG:0:1->CORE:2","LINK:CORE:2->AGG:1:1","LINK:AGG:1:1->EDGE:1:0","LINK:EDGE:1:0->ACC:30","VM:99"),d.getRoute().getResources());
        assertEquals(2e6,d.getRoute().getStandaloneRateBytesPerSecond(),0);assertEquals(2.5,d.getIsolatedSeconds(),0);assertEquals(12.5,d.getIsolatedCompletionTime(),0);
        for(String key:d.getRoute().getResources())assertTrue(fabric.getResourceCapacities().containsKey(key));
    }

    @Test void topologyCanReverseEndpointOnlyRankingAndSameHostDoesNotMeanLocalData(){
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,.5),Arrays.asList(10,20));
        DataTransferFabric fabric=DataTransferFabric.fatTree(capacities(1e6,10e6,10e6),hosts(10,20,10),topology,Arrays.asList("input-store"));
        DataReplicaState state=withFastReplica(5e6);state.advanceTo(10);ReplicaTransferSelector.Decision d=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        assertEquals(VM7,d.getSourceReplica().getLocation());assertEquals(ReplicaTransferSelector.Outcome.NETWORK_COPY,d.getOutcome());
        assertEquals(Arrays.asList("VM:7","VM:99"),d.getRoute().getResources());assertEquals(5,d.getIsolatedSeconds(),0);
    }

    @Test void futureCopiesCannotWinAndFrozenDecisionsDoNotReselect(){
        DataReplicaState state=state(5e6);state.recordTaskCompletion(1,1,VM7,true,0);DataReplicaState.CopyTicket pending=state.admitCopy(FILE,VM7,VM42,0);state.advanceTo(4);
        DataReplicaState.Snapshot before=state.snapshot();DataTransferFabric fabric=DataTransferFabric.endpoints(capacities(1e6,10e6,10e6),Arrays.asList("input-store"));
        ReplicaTransferSelector.Decision old=ReplicaTransferSelector.select(before,fabric,FILE,VM99);assertEquals(VM7,old.getSourceReplica().getLocation());
        state.settleCopy(pending,5);ReplicaTransferSelector.Decision current=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        assertEquals(VM42,current.getSourceReplica().getLocation());assertEquals(VM7,old.getSourceReplica().getLocation());
        assertEquals(VM7,ReplicaTransferSelector.select(before,fabric,FILE,VM99).getSourceReplica().getLocation());assertEquals(5,old.getIsolatedSeconds(),0);
    }

    @Test void equalRatesUseStableTypedLocationOrderIndependentOfCallerOrder(){
        DataReplicaState state=withFastReplica(100);state.advanceTo(10);Map<Integer,Double> reverse=new LinkedHashMap<>();reverse.put(99,10.0);reverse.put(42,10.0);reverse.put(7,10.0);
        ReplicaTransferSelector.Decision d=ReplicaTransferSelector.select(state.snapshot(),DataTransferFabric.endpoints(reverse,Arrays.asList("input-store")),FILE,VM99);
        assertEquals(VM7,d.getSourceReplica().getLocation());assertEquals("VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2",d.getPolicy());
    }

    @Test void localZeroAndMissingSourceAreDistinctAndDoNotInventPositiveService(){
        DataReplicaState state=state(1);DataTransferFabric fabric=DataTransferFabric.endpoints(capacities(100,100,100),Arrays.asList("input-store"));
        ReplicaTransferSelector.Decision missing=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        assertEquals(ReplicaTransferSelector.Outcome.WAITING_FOR_SOURCE,missing.getOutcome());assertNull(missing.getSourceReplica());assertNull(missing.getRoute());assertNull(missing.getIsolatedSeconds());
        state.recordTaskCompletion(1,1,VM7,true,0);ReplicaTransferSelector.Decision local=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM7);
        assertEquals(ReplicaTransferSelector.Outcome.LOCAL_PRESENT,local.getOutcome());assertNull(local.getRoute());assertEquals(0,local.getIsolatedSeconds(),0);
        DataflowFilePlan.FileId zero=DataflowFilePlan.FileId.of(0,"zero");state.seedExternal(zero,SOURCE,0);
        ReplicaTransferSelector.Decision empty=ReplicaTransferSelector.select(state.snapshot(),fabric,zero,VM99);
        assertEquals(ReplicaTransferSelector.Outcome.ZERO_BYTE_REFERENCE,empty.getOutcome());assertEquals(SOURCE,empty.getSourceReplica().getLocation());assertNull(empty.getRoute());assertEquals(0,empty.getIsolatedSeconds(),0);
        state.resolveZeroReference(zero,SOURCE,VM99,0);assertEquals(ReplicaTransferSelector.Outcome.LOCAL_PRESENT,ReplicaTransferSelector.select(state.snapshot(),fabric,zero,VM99).getOutcome());
        assertEquals(0,state.getAdmittedCopyCount());
    }

    @Test void unboundedOffFabricSourceIsAnExplicitScopeNotAMissingResourceFallback(){
        DataReplicaState state=state(1);DataflowFilePlan.FileId external=DataflowFilePlan.FileId.of(0,"external");state.seedExternal(external,SOURCE,0);
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,.000001),Arrays.asList(10,20,30));
        DataTransferFabric fabric=DataTransferFabric.fatTree(capacities(100,100,100),hosts(10,20,30),topology,Arrays.asList("input-store"));
        ReplicaTransferSelector.Decision d=ReplicaTransferSelector.select(state.snapshot(),fabric,external,VM99);
        assertTrue(d.getRoute().isUnboundedOffFabricSource());assertEquals(Arrays.asList("VM:99"),d.getRoute().getResources());assertEquals(.005,d.getIsolatedSeconds(),0);
        assertThrows(IllegalArgumentException.class,()->fabric.route(DataLocation.source("unregistered"),VM99));
        assertThrows(IllegalArgumentException.class,()->fabric.route(VM7,SOURCE),"storage output routes are NF004, not silently bypassed here");
    }

    @Test void mapsPlacementsAndSelectedRoutesAreImmutable(){
        Map<Integer,Double> caps=capacities(1e6,10e6,10e6);Map<Integer,Integer> hosts=hosts(10,20,30);List<String> sources=new ArrayList<>(Arrays.asList("input-store"));
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,2),Arrays.asList(10,20,30));DataTransferFabric fabric=DataTransferFabric.fatTree(caps,hosts,topology,sources);
        DataReplicaState state=withFastReplica(5e6);state.advanceTo(10);ReplicaTransferSelector.Decision d=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);List<String> original=new ArrayList<>(d.getRoute().getResources());
        caps.clear();hosts.clear();sources.clear();topology.getHostPlacements().get(20)[0]=99;
        assertEquals(original,d.getRoute().getResources());assertEquals(original,fabric.route(VM42,VM99).getResources());assertEquals(2.5,d.getIsolatedSeconds(),0);
        assertThrows(UnsupportedOperationException.class,()->d.getRoute().getResources().clear());assertThrows(UnsupportedOperationException.class,()->fabric.getResourceCapacities().clear());assertThrows(UnsupportedOperationException.class,()->fabric.getLocations().clear());
    }

    @Test void finiteNumericSupportRejectsBadCapacitiesAndUnrepresentableEstimates(){
        for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY,Double.MIN_VALUE})assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(capacities(bad,100,100),Arrays.asList("input-store")));
        DataReplicaState tiny=state(Double.MIN_VALUE);tiny.recordTaskCompletion(1,1,VM7,true,0);
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(tiny.snapshot(),DataTransferFabric.endpoints(capacities(1e6,1e6,1e6),Arrays.asList("input-store")),FILE,VM99));
        DataReplicaState huge=state(Double.MAX_VALUE);huge.recordTaskCompletion(1,1,VM7,true,0);
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(huge.snapshot(),DataTransferFabric.endpoints(capacities(Double.MIN_NORMAL,Double.MIN_NORMAL,Double.MIN_NORMAL),Arrays.asList("input-store")),FILE,VM99));
        DataReplicaState clock=state(1);clock.recordTaskCompletion(1,1,VM7,true,0);clock.advanceTo(0x1.0p54);
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(clock.snapshot(),DataTransferFabric.endpoints(capacities(49,49,49),Arrays.asList("input-store")),FILE,VM99));
        assertEquals(0,clock.getAdmittedCopyCount());assertEquals(0x1.0p54,clock.getObservedThrough(),0);
        DataReplicaState overflow=state(1e308);overflow.recordTaskCompletion(1,1,VM7,true,0);overflow.advanceTo(1e308);
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(overflow.snapshot(),DataTransferFabric.endpoints(capacities(1,1,1),Arrays.asList("input-store")),FILE,VM99));
        assertEquals(0,overflow.getAdmittedCopyCount());
    }

    @Test void rejectsMissingPlacementUnknownEndpointsAndConvertedTopologyOverflow(){
        FatTreeTopology topology=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,1),Arrays.asList(10,20,30));
        Map<Integer,Integer> missing=hosts(10,20,30);missing.remove(42);assertThrows(IllegalArgumentException.class,()->DataTransferFabric.fatTree(capacities(1,1,1),missing,topology,Arrays.asList("input-store")));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.fatTree(capacities(1,1,1),hosts(10,20,404),topology,Arrays.asList("input-store")));
        FatTreeTopology overflow=FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2,Double.MAX_VALUE),Arrays.asList(10,20));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.fatTree(capacities(1,1,1),hosts(10,20,10),overflow,Arrays.asList("input-store")));
        DataTransferFabric fabric=DataTransferFabric.endpoints(capacities(1,1,1),Arrays.asList("input-store"));
        assertThrows(IllegalArgumentException.class,()->fabric.route(DataLocation.vm(404),VM99));assertThrows(IllegalArgumentException.class,()->fabric.route(VM7,VM7));
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(state(1).snapshot(),fabric,DataflowFilePlan.FileId.of(0,"unknown"),VM99));
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(state(1).snapshot(),fabric,FILE,DataLocation.vm(404)));
    }

    @Test void invalidFabricOrSelectorInputsFailClosedWithoutMutatingReplicaState(){
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(null,Arrays.asList("input-store")));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(capacities(1,1,1),null));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(capacities(1,1,1),Arrays.asList("s","s")));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(capacities(1,1,1),Arrays.asList((String)null)));
        Map<Integer,Double> missing=new LinkedHashMap<>();missing.put(null,1.0);assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(missing,Collections.<String>emptyList()));
        missing.clear();missing.put(1,null);assertThrows(IllegalArgumentException.class,()->DataTransferFabric.endpoints(missing,Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class,()->DataTransferFabric.fatTree(capacities(1,1,1),hosts(10,20,30),null,Arrays.asList("input-store")));
        DataReplicaState state=state(1);DataTransferFabric fabric=DataTransferFabric.endpoints(capacities(1,1,1),Arrays.asList("input-store"));
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(null,fabric,FILE,VM99));
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(state.snapshot(),null,FILE,VM99));
        assertThrows(IllegalArgumentException.class,()->ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,SOURCE));
        assertEquals(0,state.getAdmittedCopyCount());assertEquals(0,state.getObservedThrough(),0);
    }

    @Test void noSharingControlKeepsTheSameFrozenPathAndSingleFlowBottleneck(){
        Task producer=task(1,out("file",100),out("other",100));
        DataReplicaState state=new DataReplicaState(DataflowFilePlan.capture(Arrays.asList(producer),Collections.singletonMap(1,0)),Arrays.asList(SOURCE,VM7,VM42,VM99));state.recordTaskCompletion(1,1,VM7,true,0);
        DataTransferFabric fabric=DataTransferFabric.fatTree(capacities(100,100,100),hosts(10,20,30),FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4,.00001),Arrays.asList(10,20,30)),Arrays.asList("input-store"));
        ReplicaTransferSelector.Decision p=ReplicaTransferSelector.select(state.snapshot(),fabric,FILE,VM99);
        ReplicaTransferSelector.Decision q=ReplicaTransferSelector.select(state.snapshot(),fabric,DataflowFilePlan.FileId.of(0,"other"),VM99);assertEquals(10,p.getIsolatedSeconds(),0);
        TransferContentionEngine shared=engine(fabric);admit(shared,p,1);admit(shared,q,2);assertTrue(shared.advance(10).getCompletedTransferIds().isEmpty());assertEquals(2,shared.advance(20).getCompletedTransferIds().size());
        // Distinct logical files avoid the V2 same-file/destination coalescing case.
        // Independent single-flow engines cancel sharing only: physical resources and nominal caps are identical.
        int id=1;for(ReplicaTransferSelector.Decision decision:Arrays.asList(p,q)){TransferContentionEngine isolated=engine(fabric);admit(isolated,decision,id);assertEquals(Collections.singletonList((long)id),isolated.advance(10).getCompletedTransferIds());id++;}
    }

    @Test void singleAndDisjointFrozenFlowsHaveTheSameIsolatedAndSharedTimes(){
        Map<Integer,Double> caps=new LinkedHashMap<>();for(int id=1;id<=4;id++)caps.put(id,10.0);
        DataTransferFabric fabric=DataTransferFabric.endpoints(caps,Collections.<String>emptyList());
        Task a=task(1,out("a",100)),b=task(2,out("b",100));Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(1,0);scopes.put(2,0);
        DataReplicaState state=new DataReplicaState(DataflowFilePlan.capture(Arrays.asList(a,b),scopes),fabric.getLocations());state.recordTaskCompletion(1,11,DataLocation.vm(1),true,0);state.recordTaskCompletion(2,12,DataLocation.vm(3),true,0);
        ReplicaTransferSelector.Decision p=ReplicaTransferSelector.select(state.snapshot(),fabric,DataflowFilePlan.FileId.of(0,"a"),DataLocation.vm(2));
        ReplicaTransferSelector.Decision q=ReplicaTransferSelector.select(state.snapshot(),fabric,DataflowFilePlan.FileId.of(0,"b"),DataLocation.vm(4));
        TransferContentionEngine shared=engine(fabric);admit(shared,p,1);admit(shared,q,2);assertEquals(Arrays.asList(1L,2L),shared.advance(10).getCompletedTransferIds());assertEquals(10,p.getIsolatedSeconds(),0);assertEquals(10,q.getIsolatedSeconds(),0);
    }

    private static DataReplicaState withFastReplica(double bytes){DataReplicaState state=state(bytes);state.recordTaskCompletion(1,1,VM7,true,0);state.settleCopy(state.admitCopy(FILE,VM7,VM42,0),5);return state;}
    private static DataReplicaState state(double bytes){Task p=task(1,out("file",bytes)),c=task(2,in("file",bytes),in("zero",0),in("external",.5));p.addChild(c);c.addParent(p);Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(1,0);scopes.put(2,0);
        return new DataReplicaState(DataflowFilePlan.capture(Arrays.asList(p,c),scopes),Arrays.asList(SOURCE,VM7,VM42,VM99));}
    private static Task task(int id,FileItem...files){Task task=new Task(id,1000);for(FileItem file:files)task.addFile(file);return task;}
    private static FileItem in(String name,double bytes){return file(name,bytes,FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,FileType type){FileItem file=new FileItem(name,bytes);file.setType(type);return file;}
    private static Map<Integer,Double> capacities(double a,double b,double c){Map<Integer,Double> result=new LinkedHashMap<>();result.put(7,a);result.put(42,b);result.put(99,c);return result;}
    private static Map<Integer,Integer> hosts(int a,int b,int c){Map<Integer,Integer> result=new LinkedHashMap<>();result.put(7,a);result.put(42,b);result.put(99,c);return result;}
    private static TransferContentionEngine engine(DataTransferFabric fabric){TransferContentionEngine engine=new TransferContentionEngine(10000);for(Map.Entry<String,Double> capacity:fabric.getResourceCapacities().entrySet())engine.setEndpointCapacity(capacity.getKey(),capacity.getValue());return engine;}
    private static void admit(TransferContentionEngine engine,ReplicaTransferSelector.Decision decision,long id){engine.addTransfer(id,decision.getFile().getBytes(),decision.getRoute().getResources(),decision.getRoute().getStandaloneRateBytesPerSecond(),decision.getSelectedAt());}
}
