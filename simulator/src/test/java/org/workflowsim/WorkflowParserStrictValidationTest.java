package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

class WorkflowParserStrictValidationTest {

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
    void rejectsDuplicateJobIdsAndNonFiniteRuntimes() throws Exception {
        assertThrows(WorkflowValidationException.class, () -> parse(dax(
                "<job id=\"a\" runtime=\"1\"/><job id=\"a\" runtime=\"1\"/>")));
        assertThrows(WorkflowValidationException.class, () -> parse(dax(
                "<job id=\"a\" runtime=\"NaN\"/>")));
    }

    @Test
    void rejectsUnsupportedFileLinksButToleratesConflictingInputSizes() throws Exception {
        // 不支持的 link 类型仍然拒绝
        assertThrows(WorkflowValidationException.class, () -> parse(dax(
                "<job id=\"a\" runtime=\"1\"><uses name=\"x\" link=\"temporary\" size=\"1\"/>"
                + "</job>")));
        
        // 不一致的 input size 现在只记录警告，不再抛异常（放宽校验以支持真实工作流）
        parse(dax(
                "<job id=\"a\" runtime=\"1\"><uses name=\"x\" link=\"input\" size=\"1\"/>"
                + "</job><job id=\"b\" runtime=\"1\"><uses name=\"x\" link=\"input\" size=\"2\"/>"
                + "</job>"));
        // 如果上面的 parse 成功返回，说明没有抛异常，符合预期
    }

    private void parse(String contents) throws Exception {
        Path input = temporaryDirectory.resolve("workflow.dax");
        Files.write(input, contents.getBytes(StandardCharsets.UTF_8));
        Parameters.init(1, input.toString(), null, null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID, null, 0L);
        new WorkflowParser(0).parse();
    }

    private static String dax(String body) {
        return "<?xml version=\"1.0\"?><adag version=\"3.3\" name=\"strict\">"
                + body + "</adag>";
    }
}
