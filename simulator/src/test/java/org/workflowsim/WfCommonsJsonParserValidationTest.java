package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

class WfCommonsJsonParserValidationTest {

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
    void rejectsMissingWorkflowStructureBeforeCreatingTasks() throws Exception {
        assertThrows(IOException.class, () -> parse("{\"schemaVersion\":\"1.5\"}"));
        assertThrows(IOException.class, () -> parse("{\"schemaVersion\":\"1.5\",\"workflow\":{"
                + "\"specification\":{\"tasks\":[],\"files\":[]},\"execution\":{\"tasks\":[]}}}"));
    }

    @Test
    void rejectsDuplicateTaskIdsAndMissingExecutionRuntimes() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(document(
                "[{\"id\":\"a\"},{\"id\":\"a\"}]", "[]", "[]")));
        assertThrows(IllegalArgumentException.class, () -> parse(document(
                "[{\"id\":\"a\"}]", "[]", "[]")));
    }

    @Test
    void rejectsInvalidRuntimeAndFileSizeValues() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse(document(
                "[{\"id\":\"a\"}]", "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":-1}]")));
        assertThrows(WorkflowValidationException.class, () -> parse(document(
                "[{\"id\":\"a\"}]", "[{\"id\":\"f\",\"sizeInBytes\":-1}]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsUndeclaredFilesAndUnknownDependencyEndpoints() throws Exception {
        assertThrows(WorkflowValidationException.class, () -> parse(document(
                "[{\"id\":\"a\",\"inputFiles\":[\"missing\"]}]", "[]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
        assertThrows(IllegalArgumentException.class, () -> parse(document(
                "[{\"id\":\"a\",\"parents\":[\"missing\"]}]", "[]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsInconsistentRawParentAndChildDeclarations() throws Exception {
        assertThrows(WorkflowValidationException.class, () -> parse(document(
                "[{\"id\":\"a\",\"children\":[\"b\"]},{\"id\":\"b\"}]", "[]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
        assertThrows(WorkflowValidationException.class, () -> parse(document(
                "[{\"id\":\"a\"},{\"id\":\"b\",\"parents\":[\"a\"]}]", "[]",
                "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
    }

    @Test
    void rejectsCyclesInTheDeclaredTaskGraph() throws Exception {
        assertThrows(WorkflowValidationException.class, () -> parse(document(
                "[{\"id\":\"a\",\"parents\":[\"b\"],\"children\":[\"b\"]},"
                + "{\"id\":\"b\",\"parents\":[\"a\"],\"children\":[\"a\"]}]",
                "[]", "[{\"id\":\"a\",\"runtimeInSeconds\":1},{\"id\":\"b\",\"runtimeInSeconds\":1}]")));
    }

    private void parse(String contents) throws Exception {
        Path input = temporaryDirectory.resolve("workflow.json");
        Files.write(input, contents.getBytes(StandardCharsets.UTF_8));
        new WfCommonsJsonParser().parse(input.toString(), 0, 0);
    }

    private static String document(String tasks, String files, String executionTasks) {
        return "{\"schemaVersion\":\"1.5\",\"workflow\":{\"specification\":{\"tasks\":"
                + tasks + ",\"files\":" + files + "},\"execution\":{\"tasks\":"
                + executionTasks + "}}}";
    }
}
