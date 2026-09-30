package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

/** Hand-declared V2 logical-file oracles; these do not enable a new runtime model. */
class DataflowFilePlanTest {
    @Test void resolvesUniqueProducerAndPreservesReferenceMultiplicityWithoutDuplicateDemands(){
        Task a=task(1,out("x",8)),b=task(2,out("y",2)),c=task(3,in("x",8),in("x",8),in("y",2),out("final",16));
        edge(a,c);edge(b,c);DataflowFilePlan plan=capture(a,b,c);
        assertEquals("SCOPED_RESOLVED_WRITE_ONCE_FILES_V2",plan.getContractVersion());
        assertEquals(3,plan.getFiles().size());assertEquals(Arrays.asList(1,2,3),plan.getTaskIds());
        DataflowFilePlan.FileId x=id(0,"x"),y=id(0,"y");
        assertEquals(Integer.valueOf(1),plan.getFile(x).getProducerTaskId());assertEquals(Collections.singletonList(3),plan.getFile(x).getConsumerTaskIds());
        assertFalse(plan.getFile(x).isExternal());assertFalse(plan.getFile(x).isWorkflowOutput());assertTrue(plan.getFile(id(0,"final")).isWorkflowOutput());
        assertEquals(Long.valueOf(2),plan.getInputReferences(3).get(x));
        List<DataflowFilePlan.InputDemand> demands=plan.demandsFor(Arrays.asList(3));assertEquals(2,demands.size());
        assertEquals(x,demands.get(0).getFile().getId());assertEquals(2,demands.get(0).getReferenceCount());
        assertEquals(new BigDecimal("16"),demands.get(0).getReferenceBytes());assertEquals(y,demands.get(1).getFile().getId());
        assertEquals(Collections.singletonList(y),ids(plan.demandsFor(Arrays.asList(1,3))),"same-set producer is internal, not a stage-in demand");
    }

    @Test void sameDestinationTaskSetSharesLogicalDemandButCountsEveryInputReference(){
        Task producer=task(1,out("shared",.5));Task a=task(2,in("shared",.5),in("shared",.5)),b=task(3,in("shared",.5));edge(producer,a);edge(producer,b);
        DataflowFilePlan plan=capture(producer,a,b);List<DataflowFilePlan.InputDemand> demands=plan.demandsFor(Arrays.asList(3,2));
        assertEquals(1,demands.size());assertEquals(3,demands.get(0).getReferenceCount());assertEquals(Arrays.asList(2,3),demands.get(0).getConsumerTaskIds());
        assertEquals(new BigDecimal("1.5"),demands.get(0).getReferenceBytes());assertEquals(.5,demands.get(0).getFile().getBytes(),0);
        // A later Job attempt asks from its logical Task subset again, without historical parent Jobs.
        assertEquals(2,plan.demandsFor(Collections.singletonList(2)).get(0).getReferenceCount());
        assertEquals(1,plan.demandsFor(Collections.singletonList(3)).get(0).getReferenceCount());
    }

    @Test void scopesIdentitiesByInputIndexWithoutParsingOrRenamingFileKeys(){
        Task a=task(10,in("same",1),in("workflow-17/a/b",0)),b=task(20,in("same",2));Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(10,0);scopes.put(20,1);
        DataflowFilePlan plan=DataflowFilePlan.capture(Arrays.asList(b,a),scopes);
        assertEquals(3,plan.getFiles().size());assertNotEquals(id(0,"same"),id(1,"same"));
        assertEquals(1,plan.getFile(id(0,"same")).getBytes(),0);assertEquals(2,plan.getFile(id(1,"same")).getBytes(),0);
        assertEquals("workflow-17/a/b",plan.getFile(id(0,"workflow-17/a/b")).getId().getName());
        assertEquals(3,plan.demandsFor(Arrays.asList(20,10)).size());assertTrue(plan.getFile(id(0,"same")).isExternal());
    }

    @Test void snapshotIsDeeplyImmutableAndDoesNotMutateInputGraphOrDescriptors(){
        FileItem output=out("immutable",4),input=in("immutable",4);Task a=task(1,output),b=task(2,input);edge(a,b);a.setDepth(77);b.setDepth(88);
        List<Task> tasks=new ArrayList<>(Arrays.asList(a,b));Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(1,0);scopes.put(2,0);
        DataflowFilePlan plan=DataflowFilePlan.capture(tasks,scopes);assertEquals(77,a.getDepth());assertEquals(88,b.getDepth());assertEquals("immutable",output.getName());
        output.setName("changed");output.setSize(100);output.setType(FileType.INPUT);input.setSize(200);tasks.clear();scopes.clear();a.getChildList().clear();b.getParentList().clear();b.getFileList().clear();
        assertEquals(4,plan.getFile(id(0,"immutable")).getBytes(),0);assertEquals(Integer.valueOf(1),plan.getFile(id(0,"immutable")).getProducerTaskId());
        assertEquals(1,plan.demandsFor(Collections.singletonList(2)).size());
        assertThrows(UnsupportedOperationException.class,()->plan.getFiles().clear());assertThrows(UnsupportedOperationException.class,()->plan.getTaskIds().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.getTaskWorkflowIndices().clear());assertThrows(UnsupportedOperationException.class,()->plan.getInputReferences(2).clear());
        assertThrows(UnsupportedOperationException.class,()->plan.getOutputFiles(1).clear());assertThrows(UnsupportedOperationException.class,()->plan.getFile(id(0,"immutable")).getConsumerTaskIds().clear());
        assertThrows(UnsupportedOperationException.class,()->plan.demandsFor(Arrays.asList(2)).clear());
        assertThrows(UnsupportedOperationException.class,()->plan.demandsFor(Arrays.asList(2)).get(0).getConsumerTaskIds().clear());
    }

    @Test void rejectsAmbiguousWritersAndInPlaceMutationEvenWhenSizesAgree(){
        Task a=task(1,out("x",1)),b=task(2,out("x",1));assertThrows(IllegalArgumentException.class,()->capture(a,b));
        Task inPlace=task(1,in("x",1),out("x",1));assertThrows(IllegalArgumentException.class,()->capture(inPlace));
        Task duplicates=task(1,out("x",1),out("x",1));assertEquals(Collections.singletonList(id(0,"x")),capture(duplicates).getOutputFiles(1));
    }

    @Test void requiresSurvivingResolvedSizesToAgreeWithoutEpsilonOrDoubleSummation(){
        Task producer=task(1,out("x",1)),consumer=task(2,in("x",Math.nextUp(1.0)));edge(producer,consumer);
        assertThrows(IllegalArgumentException.class,()->capture(producer,consumer));
        assertThrows(IllegalArgumentException.class,()->capture(task(1,in("external",1)),task(2,in("external",2))));
        DataflowFilePlan huge=capture(task(1,in("huge",Double.MAX_VALUE),in("huge",Double.MAX_VALUE)));
        assertEquals(0,new BigDecimal(Double.MAX_VALUE).multiply(BigDecimal.valueOf(2)).compareTo(huge.demandsFor(Arrays.asList(1)).get(0).getReferenceBytes()));
    }

    @Test void acceptsAncestorProducerButRejectsMissingOrDownstreamControlCausality(){
        Task a=task(1,out("x",1)),middle=task(2),last=task(3,in("x",1));edge(a,middle);edge(middle,last);
        assertEquals(Integer.valueOf(1),capture(a,middle,last).getFile(id(0,"x")).getProducerTaskId());
        Task p=task(1,out("x",1)),c=task(2,in("x",1));assertThrows(IllegalArgumentException.class,()->capture(p,c));
        edge(c,p);assertThrows(IllegalArgumentException.class,()->capture(p,c),"a downstream output is not a SOURCE input");
    }

    @Test void validatesFrozenDagAndWorkflowPartitionWithoutAssigningDepths(){
        Task a=task(1),b=task(2);a.addChild(b);assertThrows(IllegalArgumentException.class,()->capture(a,b));
        b.addParent(a);edge(b,a);assertThrows(IllegalArgumentException.class,()->capture(a,b));
        Task d=task(4);d.addParent(d);d.addChild(d);assertThrows(IllegalArgumentException.class,()->capture(d));
        Task x=task(5),y=task(6);edge(x,y);edge(x,y);assertThrows(IllegalArgumentException.class,()->capture(x,y));
        Task p=task(10),c=task(20);edge(p,c);Map<Integer,Integer> scopes=new LinkedHashMap<>();scopes.put(10,0);scopes.put(20,1);
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(Arrays.asList(p,c),scopes));
        Task missing=task(90);p.getParentList().add(missing);assertThrows(IllegalArgumentException.class,()->capture(p,c));
    }

    @Test void rejectsInvalidIdentityDeclarationsAndTaskScopeMappings(){
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.FileId.of(-1,"x"));assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.FileId.of(0,""));
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.FileId.of(0,null));assertThrows(IllegalArgumentException.class,()->capture(task(-1)));
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(null,Collections.<Integer,Integer>emptyMap()));
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(Arrays.asList(task(1)),null));
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(Arrays.asList(task(1)),Collections.<Integer,Integer>emptyMap()));
        assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(Arrays.asList(task(1)),Collections.singletonMap(1,-1)));
        Map<Integer,Integer> extra=new LinkedHashMap<>();extra.put(1,0);extra.put(2,0);assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(Arrays.asList(task(1)),extra));
        assertThrows(IllegalArgumentException.class,()->capture(task(1),task(1)));assertThrows(IllegalArgumentException.class,()->capture((Task)null));
    }

    @Test void rejectsInvalidFileMetadataAndDistinguishesZeroFromNoInput(){
        for(double bytes:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->capture(task(1,in("x",bytes))));
        for(FileItem file:new FileItem[]{in(null,1),in("",1),new FileItem("unset-type",1),typed("none",1,FileType.NONE),null})assertThrows(IllegalArgumentException.class,()->capture(task(1,file)));
        Task nullFiles=task(1);nullFiles.setFileList(null);assertThrows(IllegalArgumentException.class,()->capture(nullFiles));
        DataflowFilePlan noInput=capture(task(1)),zero=capture(task(1,in("zero",-0.0),in("zero",0.0)));
        assertTrue(noInput.demandsFor(Arrays.asList(1)).isEmpty());assertEquals(1,zero.demandsFor(Arrays.asList(1)).size());
        assertEquals(2,zero.demandsFor(Arrays.asList(1)).get(0).getReferenceCount());assertEquals(BigDecimal.ZERO,zero.demandsFor(Arrays.asList(1)).get(0).getReferenceBytes());
        assertEquals(Double.doubleToLongBits(0.0),Double.doubleToLongBits(zero.getFile(id(0,"zero")).getBytes()));
    }

    @Test void taskSubsetValidationIsPureAndDeterministicAcrossCallerOrder(){
        DataflowFilePlan plan=capture(task(9,in("z",1)),task(2,in("a",.5)));
        assertEquals(Arrays.asList(id(0,"a"),id(0,"z")),ids(plan.demandsFor(Arrays.asList(9,2))));
        assertEquals(ids(plan.demandsFor(Arrays.asList(9,2))),ids(plan.demandsFor(Arrays.asList(2,9))));
        assertTrue(plan.demandsFor(Collections.<Integer>emptyList()).isEmpty());assertThrows(IllegalArgumentException.class,()->plan.demandsFor(null));
        assertThrows(IllegalArgumentException.class,()->plan.demandsFor(Arrays.asList(2,2)));assertThrows(IllegalArgumentException.class,()->plan.demandsFor(Arrays.asList(2,null)));
        assertThrows(IllegalArgumentException.class,()->plan.demandsFor(Arrays.asList(100)));assertThrows(IllegalArgumentException.class,()->plan.getInputReferences(100));
        assertThrows(IllegalArgumentException.class,()->plan.getOutputFiles(100));assertThrows(IllegalArgumentException.class,()->plan.getFile(id(7,"missing")));
        assertTrue(DataflowFilePlan.capture(Collections.<Task>emptyList(),Collections.<Integer,Integer>emptyMap()).getFiles().isEmpty());
    }

    @Test void deepDagValidationIsIterativeAndDoesNotRetainAnAllPairsClosure(){
        int count=12000;List<Task> tasks=new ArrayList<>();Map<Integer,Integer> scopes=new LinkedHashMap<>();
        for(int i=0;i<count;i++){Task t=task(i);tasks.add(t);scopes.put(i,0);if(i>0)edge(tasks.get(i-1),t);}
        tasks.get(0).addFile(out("far-ancestor",1));tasks.get(count-1).addFile(in("far-ancestor",1));
        DataflowFilePlan plan=DataflowFilePlan.capture(tasks,scopes);
        assertEquals(count,plan.getTaskIds().size());assertEquals(Integer.valueOf(0),plan.getFile(id(0,"far-ancestor")).getProducerTaskId());
        assertEquals(1,plan.demandsFor(Arrays.asList(count-1)).size());
    }

    @Test void fileIdentityOrderingAndEqualityUseExactScopeAndName(){
        DataflowFilePlan.FileId a=id(0,"节点/a"),copy=id(0,"节点/a"),other=id(1,"节点/a");
        assertEquals(a,copy);assertEquals(a.hashCode(),copy.hashCode());assertNotEquals(a,other);assertNotEquals(a,"节点/a");assertNotEquals(a,null);
        assertTrue(a.compareTo(other)<0);assertTrue(id(0,"a").compareTo(id(0,"z"))<0);assertEquals(0,a.getWorkflowInputIndex());
        assertEquals("节点/a",a.getName());assertTrue(a.toString().contains("节点/a"));
    }

    private static DataflowFilePlan capture(Task...tasks){Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task task:tasks)if(task!=null)scopes.put(task.getCloudletId(),0);return DataflowFilePlan.capture(Arrays.asList(tasks),scopes);}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem file:files)t.addFile(file);return t;}
    private static void edge(Task from,Task to){from.addChild(to);to.addParent(from);}
    private static FileItem in(String name,double bytes){return typed(name,bytes,FileType.INPUT);}
    private static FileItem out(String name,double bytes){return typed(name,bytes,FileType.OUTPUT);}
    private static FileItem typed(String name,double bytes,FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}
    private static DataflowFilePlan.FileId id(int workflow,String name){return DataflowFilePlan.FileId.of(workflow,name);}
    private static List<DataflowFilePlan.FileId> ids(List<DataflowFilePlan.InputDemand> demands){List<DataflowFilePlan.FileId> ids=new ArrayList<>();for(DataflowFilePlan.InputDemand d:demands)ids.add(d.getFile().getId());return ids;}
}
