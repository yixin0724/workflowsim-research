package org.workflowsim.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.examples.failure.clustering.ParameterSweep;

/**
 * 历史教学 CLI 的进程级 smoke 测试。
 *
 * <p>每个 catalog 描述符分别在独立 JVM 中运行。测试只证明默认教学场景可启动、完成边界
 * 可被机器识别，且无效输入会以非零状态失败；它不证明算法论文等价、统计显著性或模型已被
 * 真实平台校准。</p>
 */
class ExamplesCliSmokeIntegrationTest {

    private static final String MISSING_DAX_PATH =
            "datasets/dax/__cli-smoke-missing__/missing-workflow.dax";

    @TempDir
    Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> catalogDefaultsCompleteInIsolatedJvm() {
        return ExampleCatalog.legacyExamples().stream().map(descriptor -> DynamicTest.dynamicTest(
                "default " + descriptor.getId(), () -> {
                    CliProcessTestSupport.ProcessResult result = run(descriptor.getMainClass());
                    assertFalse(result.isTimedOut(), result.diagnostic());
                    assertEquals(0, result.getExitCode(), result.diagnostic());
                    assertTrue(result.getOutput().contains(ExampleCliSupport.COMPLETION_PREFIX
                                    + " id=" + descriptor.getId() + " "),
                            result.diagnostic());
                }));
    }

    @TestFactory
    Stream<DynamicTest> catalogInvalidInputsFailWithNonzeroExitStatus() {
        return ExampleCatalog.legacyExamples().stream().map(descriptor -> DynamicTest.dynamicTest(
                "invalid input " + descriptor.getId(), () -> {
                    CliProcessTestSupport.ProcessResult result = run(descriptor.getMainClass(),
                            invalidArguments(descriptor));
                    assertFalse(result.isTimedOut(), result.diagnostic());
                    assertNotEquals(0, result.getExitCode(), result.diagnostic());
                    assertFalse(result.getOutput().contains(ExampleCliSupport.COMPLETION_PREFIX
                                    + " id=" + descriptor.getId() + " "),
                            result.diagnostic());
                    assertTrue(!result.getOutput().trim().isEmpty(), result.diagnostic());
                }));
    }

    @Test
    void boundedParameterSweepSmokeIsExplicitAndNoArgInvocationFails() throws Exception {
        CliProcessTestSupport.ProcessResult success = run(ParameterSweep.class.getName(), "--smoke");
        assertFalse(success.isTimedOut(), success.diagnostic());
        assertEquals(0, success.getExitCode(), success.diagnostic());
        assertTrue(success.getOutput().contains("PARAMETER_SWEEP_SMOKE_COMPLETED replications=1"),
                success.diagnostic());

        CliProcessTestSupport.ProcessResult missingMode = run(ParameterSweep.class.getName());
        assertFalse(missingMode.isTimedOut(), missingMode.diagnostic());
        assertNotEquals(0, missingMode.getExitCode(), missingMode.diagnostic());
        assertFalse(missingMode.getOutput().contains("PARAMETER_SWEEP_SMOKE_COMPLETED"),
                missingMode.diagnostic());
    }

    @Test
    void suiteListExposesEveryCatalogDescriptorWithoutExecutingThem() throws Exception {
        CliProcessTestSupport.ProcessResult result = run(WorkflowSimAllExamplesTester.class.getName(),
                "--list");
        assertFalse(result.isTimedOut(), result.diagnostic());
        assertEquals(0, result.getExitCode(), result.diagnostic());
        for (ExampleCatalog.Descriptor descriptor : ExampleCatalog.legacyExamples()) {
            assertTrue(result.getOutput().contains(descriptor.getId() + " "
                            + descriptor.getMainClass()),
                    result.diagnostic());
        }
        assertFalse(result.getOutput().contains("EXAMPLE_SUITE_COMPLETED"), result.diagnostic());
    }

    private CliProcessTestSupport.ProcessResult run(String mainClass, String... arguments)
            throws Exception {
        return CliProcessTestSupport.runMain(workspaceRoot(), temporaryDirectory, mainClass, arguments);
    }

    private static String[] invalidArguments(ExampleCatalog.Descriptor descriptor) {
        String id = descriptor.getId();
        if ("multiple-workflows".equals(id)) {
            // 多工作流入口要求 0 或恰好 3 条路径；单条路径应在创建模拟实体前失败。
            return new String[] {MISSING_DAX_PATH};
        }
        if ("balanced-clustering".equals(id)
                || "fault-clustering-dynamic".equals(id)
                || "fault-clustering-options".equals(id)
                || "fault-clustering-periodic".equals(id)) {
            return new String[] {"-d", MISSING_DAX_PATH};
        }
        return new String[] {MISSING_DAX_PATH};
    }

    private static Path workspaceRoot() {
        String configuredDatasetRoot = System.getProperty("workflowsim.datasetRoot");
        if (configuredDatasetRoot == null || configuredDatasetRoot.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        Path datasetRoot = Paths.get(configuredDatasetRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(datasetRoot)) {
            throw new IllegalStateException("Invalid dataset root: " + datasetRoot);
        }
        Path workspaceRoot = datasetRoot.getParent();
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("Dataset root must have an existing project parent: "
                    + datasetRoot);
        }
        return workspaceRoot;
    }
}
