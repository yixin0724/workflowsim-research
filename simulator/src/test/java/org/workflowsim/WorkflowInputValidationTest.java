package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URL;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

class WorkflowInputValidationTest {

    @BeforeEach
    void setUp() {
        Parameters.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);
    }

    @AfterEach
    void tearDown() {
        Parameters.reset();
        ReplicaCatalog.reset();
    }

    @Test
    void daxParserResolvesDependenciesRegardlessOfXmlElementOrder() throws Exception {
        WorkflowParser parser = parserFor("/dax/dependency-before-jobs.dax");
        parser.parse();

        List<Task> tasks = parser.getTaskList();
        assertEquals(2, tasks.size());
        assertEquals(1, tasks.get(0).getDepth());
        assertEquals(2, tasks.get(1).getDepth());
        assertEquals(tasks.get(0), tasks.get(1).getParentList().get(0));
        assertEquals(0.0, tasks.get(0).getFileList().get(0).getSize());
        assertEquals(1, parser.getInputReports().size());
        assertEquals(WorkflowInputReport.Format.DAX_XML,
                parser.getInputReports().get(0).getFormat());

        parser.parse();
        assertEquals(2, parser.getTaskList().size(), "Repeated parsing must not append stale tasks");
    }

    @Test
    void runtimeReferenceMipsControlsDaxSecondToMiConversion() throws Exception {
        Parameters.setRuntimeReferenceMips(2000.0);
        WorkflowParser parser = parserFor("/dax/dependency-before-jobs.dax");
        parser.parse();

        assertEquals(400L, parser.getTaskList().get(0).getCloudletLength());
    }

    @Test
    void parserRejectsMissingDependenciesAndCycles() throws Exception {
        assertThrows(WorkflowValidationException.class, () -> {
            WorkflowParser parser = parserFor("/dax/missing-parent.dax");
            parser.parse();
        });
        assertThrows(WorkflowValidationException.class, () -> {
            WorkflowParser parser = parserFor("/dax/cycle.dax");
            parser.parse();
        });
    }

    @Test
    void wfCommonsReportsMinimumRuntimeNormalization() throws Exception {
        String path = resourcePath("/wfcommons/minimum-runtime.json");
        WfCommonsJsonParser.ParseResult result = new WfCommonsJsonParser().parse(path, 0, 0);

        assertEquals(100L, result.getTasks().get(0).getCloudletLength());
        WorkflowInputReport report = result.getInputReport();
        assertEquals("1.5", report.getDeclaredVersion());
        assertEquals(1, report.getNormalizations().size());
        assertEquals(WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                report.getNormalizations().get(0).getKind());
        assertFalse(result.getTasks().get(0).getFileList().isEmpty());
        assertEquals(0.0, result.getTasks().get(0).getFileList().get(0).getSize());
    }

    private WorkflowParser parserFor(String resource) throws Exception {
        String path = resourcePath(resource);
        Parameters.init(2, path, null, null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID, null, 0L);
        return new WorkflowParser(0);
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
