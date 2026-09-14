package org.workflowsim.experiments.tutorials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/** DAX/WfCommons JSON 解析路径的教程性对照测试。 */
class ParserCrossValidationTest {

    private static final String WF_JSON_RELATIVE_PATH =
            "wfformat/montage/n100/montage-100-000.json";
    private static Path workflowPath;

    /** 初始化解析器所需静态状态，并验证 Maven 显式注入的测试数据集。 */
    @BeforeAll
    static void setUp() {
        Parameters.init(20, (String) null, null, null,
                null, null,
                Parameters.SchedulingAlgorithm.INVALID,
                Parameters.PlanningAlgorithm.INVALID,
                null, 0);
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);

        String configuredRoot = System.getProperty("workflowsim.datasetRoot");
        if (configuredRoot == null || configuredRoot.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        Path datasetRoot = Paths.get(configuredRoot);
        if (!datasetRoot.isAbsolute() || !Files.isDirectory(datasetRoot)) {
            throw new IllegalStateException("Invalid absolute dataset root: " + configuredRoot);
        }
        workflowPath = datasetRoot.resolve(WF_JSON_RELATIVE_PATH).normalize();
        if (!workflowPath.startsWith(datasetRoot.normalize())) {
            throw new IllegalStateException("Invalid tutorial input path: " + workflowPath);
        }
        // wfformat 语料体积较大且未纳入版本库（见 .gitignore）；缺失时跳过本教程对照测试，
        // 保证无大型语料的环境（如 CI 检出）仍可执行完整门禁。
        Assumptions.assumeTrue(Files.isRegularFile(workflowPath),
                "Skipping: optional WfCommons corpus not present at " + workflowPath);
    }

    @Test
    void wfCommonsParserProducesValidTaskList() throws IOException {
        WfCommonsJsonParser.ParseResult result = parse();

        assertNotNull(result);
        List<Task> tasks = result.getTasks();
        assertFalse(tasks.isEmpty(), "Montage-100 JSON 应解析出任务");
    }

    @Test
    void wfCommonsTasksHaveConsistentDependencies() throws IOException {
        List<Task> tasks = parse().getTasks();

        for (Task task : tasks) {
            for (Task child : task.getChildList()) {
                assertTrue(child.getParentList().contains(task),
                        String.format("Task %d 是 Task %d 的 child,但 Task %d 的 parentList 不含 %d",
                                child.getCloudletId(), task.getCloudletId(),
                                child.getCloudletId(), task.getCloudletId()));
            }
        }
    }

    @Test
    void wfCommonsTasksHavePositiveDepth() throws IOException {
        for (Task task : parse().getTasks()) {
            assertTrue(task.getDepth() >= 1,
                    "Task " + task.getCloudletId() + " depth 应 >= 1,实际 " + task.getDepth());
        }
    }

    @Test
    void wfCommonsTasksHavePositiveCloudletLength() throws IOException {
        for (Task task : parse().getTasks()) {
            assertTrue(task.getCloudletLength() > 0,
                    "Task " + task.getCloudletId() + " length 应 > 0");
        }
    }

    @Test
    void wfCommonsNextTaskIdIsConsistent() throws IOException {
        WfCommonsJsonParser.ParseResult result = parse();
        assertEquals(result.getTasks().size(), result.getNextTaskId(),
                "nextTaskId 应等于任务总数(从 firstTaskId=0 开始)");
    }

    private static WfCommonsJsonParser.ParseResult parse() throws IOException {
        return new WfCommonsJsonParser().parse(workflowPath.toString(), 0, 0);
    }
}
