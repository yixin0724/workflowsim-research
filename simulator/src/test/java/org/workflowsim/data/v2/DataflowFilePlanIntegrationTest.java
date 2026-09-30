package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.reclustering.ReclusteringEngine;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** Parser/clustering/retry boundary controls only; NF003 runtime wiring is deliberately not enabled. */
class DataflowFilePlanIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}

    @Test void multipleParsedWorkflowsKeepTheirExistingScopedKeysAndSurviveSessionCleanup()throws Exception{
        Path first=dax("first.dax",workflow(8,false)),second=dax("second.dax",workflow(16,false));DataflowFilePlan plan;
        SimulationConfig config=SimulationConfig.builder(Arrays.asList(first.toString(),second.toString()),1).workflowArrivalSeconds(Arrays.asList(0.0,5.0)).build();
        try(SimulationSession session=SimulationSession.open(config)){
            WorkflowParser parser=new WorkflowParser(0);parser.parse();plan=DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices());
            assertEquals(4,plan.getTaskIds().size());assertEquals(Integer.valueOf(0),plan.getTaskWorkflowIndices().get(1));assertEquals(Integer.valueOf(1),plan.getTaskWorkflowIndices().get(3));
            assertEquals(8,plan.getFile(id(0,"workflow-0/edge")).getBytes(),0);assertEquals(16,plan.getFile(id(1,"workflow-1/edge")).getBytes(),0);
        }
        assertEquals(Integer.valueOf(1),plan.getFile(id(0,"workflow-0/edge")).getProducerTaskId());
        assertEquals(Integer.valueOf(3),plan.getFile(id(1,"workflow-1/edge")).getProducerTaskId());
        assertEquals(2,plan.demandsFor(Arrays.asList(2,4)).size());
    }

    @Test void preClusteringSnapshotPreservesReferencesAndRetryUsesLogicalTaskMembership()throws Exception{
        Path input=dax("retry.dax",workflow(8,true));
        try(SimulationSession session=SimulationSession.open(SimulationConfig.builder(input.toString(),1).build())){
            WorkflowParser parser=new WorkflowParser(0);parser.parse();DataflowFilePlan plan=DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices());
            BasicClustering clustering=new BasicClustering();clustering.setTaskList(parser.getTaskList());clustering.run();
            assertTrue(parser.getTaskList().isEmpty(),"legacy clustering clears its input task list");assertEquals(2,clustering.getJobList().size());
            Job producer=clustering.getJobList().get(0),consumer=clustering.getJobList().get(1);
            assertEquals(0,producer.getCloudletId());assertEquals(1,producer.getTaskList().get(0).getCloudletId());
            assertEquals(2,plan.demandsFor(taskIds(consumer)).get(0).getReferenceCount(),"logical references survive legacy Job file-object union");
            Job retry=ReclusteringEngine.process(producer,99).get(0);
            assertEquals(99,retry.getCloudletId());assertEquals(Arrays.asList(1),taskIds(retry));assertNotSame(producer.getTaskList().get(0),retry.getTaskList().get(0));
            assertEquals(2,consumer.getParentList().size(),"old retry rewiring retains original and retry parent Jobs");
            List<DataflowFilePlan.InputDemand> demands=plan.demandsFor(taskIds(consumer));assertEquals(1,demands.size());assertEquals(2,demands.get(0).getReferenceCount());
            assertEquals(Integer.valueOf(1),demands.get(0).getFile().getProducerTaskId(),"file origin is not either parent Job attempt ID");
            retry.getTaskList().get(0).getFileList().get(0).setName("mutated-shared-legacy-item");
            assertEquals("edge",demands.get(0).getFile().getId().getName());
        }
    }

    @Test void resolvedInputSizeContractDoesNotClaimToRecoverDiscardedDaxDeclarations()throws Exception{
        Path input=dax("first-wins.dax","<job id=\"a\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1\"/></job>"
                +"<job id=\"b\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"2\"/></job>");
        try(SimulationSession session=SimulationSession.open(SimulationConfig.builder(input.toString(),1).build())){
            WorkflowParser parser=new WorkflowParser(0);parser.parse();assertSame(parser.getTaskList().get(0).getFileList().get(0),parser.getTaskList().get(1).getFileList().get(0));
            DataflowFilePlan plan=DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices());
            assertEquals(1,plan.getFile(id(0,"x")).getBytes(),0);assertEquals(2,plan.demandsFor(Arrays.asList(1,2)).get(0).getReferenceCount());
        }
    }

    @Test void v2OnlyIndexRejectsSurvivingSizeConflictWithoutChangingLegacyParserAcceptance()throws Exception{
        Path input=dax("size-conflict.dax","<job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"8\"/></job>"
                +"<job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"16\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>");
        try(SimulationSession session=SimulationSession.open(SimulationConfig.builder(input.toString(),1).build())){
            WorkflowParser parser=new WorkflowParser(0);assertDoesNotThrow(parser::parse);
            assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices()));
            assertEquals(8,parser.getTaskList().get(0).getFileList().get(0).getSize(),0);assertEquals(16,parser.getTaskList().get(1).getFileList().get(0).getSize(),0);
        }
    }

    @Test void rejectsAmbiguousLogicalOutputsAfterOtherwiseValidDagParsing()throws Exception{
        Path input=dax("multi-writer.dax","<job id=\"a\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"1\"/></job>"
                +"<job id=\"b\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"1\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1\"/></job>"
                +"<child ref=\"c\"><parent ref=\"a\"/><parent ref=\"b\"/></child>");
        try(SimulationSession session=SimulationSession.open(SimulationConfig.builder(input.toString(),1).build())){
            WorkflowParser parser=new WorkflowParser(0);assertDoesNotThrow(parser::parse);
            assertThrows(IllegalArgumentException.class,()->DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices()));
        }
    }

    @Test void wfcommonsZeroByteOutputsRemainProducedLogicalObjects()throws Exception{
        Path input=Paths.get(getClass().getResource("/wfcommons/retry-dependency.json").toURI());
        try(SimulationSession session=SimulationSession.open(SimulationConfig.builder(input.toString(),1).build())){
            WorkflowParser parser=new WorkflowParser(0);parser.parse();DataflowFilePlan plan=DataflowFilePlan.capture(parser.getTaskList(),parser.getTaskWorkflowIndices());
            DataflowFilePlan.FileDefinition file=plan.getFile(id(0,"root-output"));assertFalse(file.isExternal());assertEquals(0,file.getBytes(),0);
            assertEquals(Integer.valueOf(1),file.getProducerTaskId());assertEquals(Arrays.asList(2),file.getConsumerTaskIds());
            assertEquals(1,plan.demandsFor(Arrays.asList(2)).size());assertTrue(plan.getFile(id(0,"child-output")).isWorkflowOutput());
        }
    }

    private Path dax(String name,String body)throws Exception{Path path=directory.resolve(name);Files.write(path,("<adag version=\"3.3\">"+body+"</adag>").getBytes(StandardCharsets.UTF_8));return path;}
    private static String workflow(int bytes,boolean duplicate){return "<job id=\"p\" runtime=\"1\"><uses file=\"edge\" link=\"output\" size=\""+bytes+"\"/></job>"
            +"<job id=\"c\" runtime=\"1\"><uses file=\"edge\" link=\"input\" size=\""+bytes+"\"/>"+(duplicate?"<uses file=\"edge\" link=\"input\" size=\""+bytes+"\"/>":"")+"</job><child ref=\"c\"><parent ref=\"p\"/></child>";}
    private static List<Integer> taskIds(Job job){List<Integer> ids=new ArrayList<>();for(Task task:job.getTaskList())ids.add(task.getCloudletId());return ids;}
    private static DataflowFilePlan.FileId id(int workflow,String name){return DataflowFilePlan.FileId.of(workflow,name);}
}
