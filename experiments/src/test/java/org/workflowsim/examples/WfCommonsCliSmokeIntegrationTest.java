package org.workflowsim.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample;
import org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1;

/**
 * WfCommons JSON 命令行入口的进程级 smoke 测试。
 *
 * <p>覆盖一个 WfGen 合成 WfFormat 输入和一个 P7 冻结的 WfInstances 真实执行派生输入。
 * 后者仅经当前抽象 DAG 转换和标准 WorkflowSim 流程验证，不构成 trace replay 或观测
 * makespan 的复现声明。</p>
 */
class WfCommonsCliSmokeIntegrationTest {

    private static final InputCase WFFORMAT_INPUT = new InputCase("wfformat-montage-100",
            "wfformat/montage/n100/montage-100-000.json");
    private static final InputCase WFINSTANCES_INPUT = new InputCase("wfinstances-makeflow-blast-small",
            "wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json");
    private static final List<InputCase> INPUTS = Arrays.asList(WFFORMAT_INPUT, WFINSTANCES_INPUT);

    @TempDir
    Path temporaryDirectory;

    @TestFactory
    Stream<DynamicTest> simulationCliRunsBothSupportedJsonProvenances() {
        return INPUTS.stream().map(input -> DynamicTest.dynamicTest("simulate " + input.id,
                () -> {
                    Path json = input.resolve(datasetRoot());
                    CliProcessTestSupport.ProcessResult result = run(
                            WfCommonsSimulationExample1.class.getName(), json.toString());
                    assertFalse(result.isTimedOut(), result.diagnostic());
                    assertEquals(0, result.getExitCode(), result.diagnostic());
                    assertTrue(result.getOutput().contains("WFJSON_SUMMARY input="),
                            result.diagnostic());
                    assertTrue(result.getOutput().contains("failed=0"), result.diagnostic());
                }));
    }

    @TestFactory
    Stream<DynamicTest> parserValidationCliAcceptsEachJsonFileDirectly() {
        return INPUTS.stream().map(input -> DynamicTest.dynamicTest("validate " + input.id,
                () -> {
                    Path json = input.resolve(datasetRoot());
                    CliProcessTestSupport.ProcessResult result = run(
                            WfCommonsJsonParserValidationExample.class.getName(), json.toString());
                    assertFalse(result.isTimedOut(), result.diagnostic());
                    assertEquals(0, result.getExitCode(), result.diagnostic());
                    assertTrue(result.getOutput().contains("WFJSON_VALIDATION PASSED files=1"),
                            result.diagnostic());
                }));
    }

    @Test
    void jsonEntrypointsRejectAmbiguousMultipleInputArguments() throws Exception {
        Path first = WFFORMAT_INPUT.resolve(datasetRoot());
        Path second = WFINSTANCES_INPUT.resolve(datasetRoot());

        CliProcessTestSupport.ProcessResult simulation = run(
                WfCommonsSimulationExample1.class.getName(), first.toString(), second.toString());
        assertFalse(simulation.isTimedOut(), simulation.diagnostic());
        assertNotEquals(0, simulation.getExitCode(), simulation.diagnostic());
        assertFalse(simulation.getOutput().contains("WFJSON_SUMMARY"), simulation.diagnostic());

        CliProcessTestSupport.ProcessResult validation = run(
                WfCommonsJsonParserValidationExample.class.getName(), first.toString(), second.toString());
        assertFalse(validation.isTimedOut(), validation.diagnostic());
        assertNotEquals(0, validation.getExitCode(), validation.diagnostic());
        assertFalse(validation.getOutput().contains("WFJSON_VALIDATION PASSED"),
                validation.diagnostic());
    }

    private CliProcessTestSupport.ProcessResult run(String mainClass, String... arguments)
            throws Exception {
        return CliProcessTestSupport.runMain(workspaceRoot(), temporaryDirectory, mainClass, arguments);
    }

    private static Path datasetRoot() {
        String configured = System.getProperty("workflowsim.datasetRoot");
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        Path root = Paths.get(configured).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Invalid dataset root: " + root);
        }
        return root;
    }

    private static Path workspaceRoot() {
        Path workspaceRoot = datasetRoot().getParent();
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalStateException("Dataset root must have an existing project parent");
        }
        return workspaceRoot;
    }

    /** 一个需要保持来源边界的固定 JSON 冒烟输入。 */
    private static final class InputCase {
        private final String id;
        private final String relativePath;

        private InputCase(String id, String relativePath) {
            this.id = id;
            this.relativePath = relativePath;
        }

        private Path resolve(Path root) {
            Path result = root.resolve(relativePath).normalize();
            if (!result.startsWith(root)) {
                throw new IllegalStateException("Invalid JSON smoke input path: " + result);
            }
            // wfformat/wfinstances 语料体积较大且未纳入版本库（见 .gitignore）；缺失时跳过，
            // 保证无大型语料的环境（如 CI 检出）仍可执行完整门禁。
            Assumptions.assumeTrue(Files.isRegularFile(result),
                    "Skipping: optional JSON corpus not present at " + result);
            return result;
        }
    }
}
