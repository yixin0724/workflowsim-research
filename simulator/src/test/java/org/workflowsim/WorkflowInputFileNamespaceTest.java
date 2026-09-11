package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/** 验证一次多工作流提交不会因裸文件名而意外共享数据。 */
class WorkflowInputFileNamespaceTest {

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void initializeParserState() {
        Parameters.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
    }

    @AfterEach
    void clearParserState() {
        Parameters.reset();
        ReplicaCatalog.reset();
    }

    @Test
    void separatelySubmittedDaxInputsKeepSameRawFileNameIndependent() throws Exception {
        Path first = write("first.dax", dax("first", 1.0));
        Path second = write("second.dax", dax("second", 2.0));

        WorkflowParser parser = parse(Arrays.asList(first, second));

        assertEquals(2, parser.getTaskList().size());
        assertIndependentFileIdentities(parser.getTaskList());
    }

    @Test
    void mixedDaxAndWfCommonsInputsKeepSameRawFileNameIndependent() throws Exception {
        Path dax = write("workflow.dax", dax("dax-task", 1.0));
        Path json = write("workflow.json", wfCommons("json-task", 2.0));

        WorkflowParser parser = parse(Arrays.asList(dax, json));

        assertEquals(2, parser.getTaskList().size());
        assertIndependentFileIdentities(parser.getTaskList());
    }

    private void assertIndependentFileIdentities(List<Task> tasks) {
        FileItem first = tasks.get(0).getFileList().get(0);
        FileItem second = tasks.get(1).getFileList().get(0);

        assertEquals(1.0, first.getSize());
        assertEquals(2.0, second.getSize());
        assertNotEquals(first.getName(), second.getName());
        assertTrue(first.getName().endsWith("/shared-input"));
        assertTrue(second.getName().endsWith("/shared-input"));
        assertTrue(ReplicaCatalog.containsFile(first.getName()));
        assertTrue(ReplicaCatalog.containsFile(second.getName()));
    }

    private WorkflowParser parse(List<Path> paths) {
        Parameters.init(2, Arrays.asList(paths.get(0).toString(), paths.get(1).toString()),
                null, null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID, null, 0L);
        WorkflowParser parser = new WorkflowParser(0);
        parser.parse();
        return parser;
    }

    private Path write(String name, String contents) throws Exception {
        Path input = temporaryDirectory.resolve(name);
        Files.write(input, contents.getBytes(StandardCharsets.UTF_8));
        return input;
    }

    private static String dax(String taskId, double size) {
        return "<?xml version=\"1.0\"?><adag version=\"3.3\" name=\"input\">"
                + "<job id=\"" + taskId + "\" runtime=\"1\">"
                + "<uses name=\"shared-input\" link=\"input\" size=\"" + size + "\"/>"
                + "</job></adag>";
    }

    private static String wfCommons(String taskId, double size) {
        return "{\"schemaVersion\":\"1.5\",\"workflow\":{\"specification\":{\"tasks\":[{"
                + "\"id\":\"" + taskId + "\",\"inputFiles\":[\"shared-input\"]}],"
                + "\"files\":[{\"id\":\"shared-input\",\"sizeInBytes\":" + size + "}]},"
                + "\"execution\":{\"tasks\":[{\"id\":\"" + taskId
                + "\",\"runtimeInSeconds\":1}]}}}";
    }
}
