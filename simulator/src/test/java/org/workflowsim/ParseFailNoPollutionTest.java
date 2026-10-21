package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * C12：解析器拒绝不得留下半提交状态（审查工程 Phase 3 污染探针）。
 *
 * <p>军规来源（事务性守恒律）：一次失败的解析是<b>未发生</b>的输入——它对
 * {@link ReplicaCatalog} 的任何写入都属于污染：同 JVM 内重试解析另一个工作流时，
 * "first-declared size"规则会用被污染条目的旧尺寸<b>静默替换</b>新声明的尺寸，
 * 造成传输字节模型偏差且无任何报警。</p>
 *
 * <p>现有的 DOCTYPE/XXE/结构拒绝行为已由 WorkflowParserStrictValidationTest 覆盖；
 * 本类专攻<b>失败时点的全局状态洁净度</b>：每个非法样本被拒绝之后，目录必须
 * 不含任何由该次解析登记的文件。</p>
 */
class ParseFailNoPollutionTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void cleanSlate() {
        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        Parameters.reset();
    }

    /** 与既有严格校验测试同一驱动配方：写入文件 → Parameters.init → parse()。 */
    private void parseFile(Path input) throws Exception {
        Parameters.init(
                2,
                input.toString(),
                null,
                null,
                null,
                null,
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.PlanningAlgorithm.INVALID,
                null,
                0L);
        new WorkflowParser(0).parse();
    }

    private Path write(String body) throws Exception {
        Path path = tempDir.resolve("probe-" + System.nanoTime() + ".dax");
        Files.write(path, body.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** C12-1：第二个 job 无效（负 runtime）时，第一个 job 已解析的输入不得残留目录。 */
    @Test
    void invalidSecondJobLeavesNoHalfCommittedInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"good-first\" name=\"a\" runtime=\"100\">\n"
                + "    <uses name=\"early-input.bin\" link=\"input\" size=\"300000\"/>\n"
                + "    <uses name=\"early-output.bin\" link=\"output\" size=\"100\"/>\n"
                + "  </job>\n"
                + "  <job id=\"bad-second\" name=\"b\" runtime=\"-5\"/>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("early-input.bin"), "拒绝后的目录不得残留首个 job 已登记的输入（半提交）");
        assertTrue(!ReplicaCatalog.containsFile("early-output.bin"), "输出文件名不得被登记（输出走条款注册而非文件登记）");
    }

    /** C12-2：依赖缺失（child 引用不存在父）在任何 job 注册之后才失败。 */
    @Test
    void missingDependencyReferenceLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"j1\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"f1.bin\" link=\"input\" size=\"50\"/>\n"
                + "  </job>\n"
                + "  <job id=\"j2\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"j2\"><parent ref=\"nonexistent\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("f1.bin"), "依赖校验失败后目录必须保持洁净");
    }

    /**
     * C12-3：污染的可观测危害链——失败解析登记的旧尺寸必须不影响后续成功解析。
     * （成功样本声明的同名文件尺寸与失败样本不同；成功解析后登记的必须是新尺寸。）
     */
    @Test
    void failedParseMustNotSwapSizesForSubsequentSuccessfulParse() throws Exception {
        Path bad = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"same-name.bin\" link=\"input\" size=\"300000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"-1\"/>\n"
                + "</adag>\n");
        Path good = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"1\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"same-name.bin\" link=\"input\" size=\"700000\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(bad));
        parseFile(good);
        org.workflowsim.FileItem item = ReplicaCatalog.getFile("same-name.bin");
        assertTrue(item != null, "成功重试解析后文件应存在");
        double wrongSize = Double.parseDouble("300000");
        double expectedSize = Double.parseDouble("700000");
        assertTrue(
                item.getSize() == expectedSize && item.getSize() != wrongSize,
                "污染危害链：失败解析的旧尺寸不得替换成功解析的新声明（当前=" + item.getSize() + "）");
    }

    /** C12-4：重复 job id 在第二个重复项失败——首个 job 的输入同样不得残留。 */
    @Test
    void duplicateJobIdLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"dup\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"dup-input.bin\" link=\"input\" size=\"10\"/>\n"
                + "  </job>\n"
                + "  <job id=\"dup\" name=\"b\" runtime=\"10\"/>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("dup-input.bin"), "重复 ID 拒绝后目录必须洁净");
    }

    /** C12-5：DAG 存在环（非法图）时拒绝后目录洁净。 */
    @Test
    void cyclicDagRejectionLeavesNoInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"x\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"cycle-input.bin\" link=\"input\" size=\"10\"/>\n"
                + "  </job>\n"
                + "  <job id=\"y\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"x\"><parent ref=\"y\"/></child>\n"
                + "  <child ref=\"y\"><parent ref=\"x\"/></child>\n"
                + "</adag>\n");
        assertThrows(WorkflowValidationException.class, () -> parseFile(dax));
        assertTrue(!ReplicaCatalog.containsFile("cycle-input.bin"), "环拒绝后目录必须洁净");
    }

    /** WfCommons JSON 路径同口径：非法实例拒绝后目录洁净。 */
    @Test
    void invalidJsonInstanceLeavesNoInputs() throws Exception {
        Path json = tempDir.resolve("bad-instance.json");
        Files.write(
                json,
                ("{\n"
                                + "  \"schemaVersion\": \"1.5\",\n"
                                + "  \"workflow\": { \"specification\": { \"tasks\": [\n"
                                + "      { \"id\": \"t1\", \"name\": \"n\", \"type\": \"compute\",\n"
                                + "        \"inputFiles\": [\"f.json\"], \"outputFiles\": [] },\n"
                                + "      { \"id\": \"t2\", \"name\": \"n\", \"type\": \"compute\",\n"
                                + "        \"inputFiles\": [], \"outputFiles\": [] }\n"
                                + "    ],\n"
                                + "    \"files\": [ { \"id\": \"f.json\", \"sizeInBytes\": 100 } ],\n"
                                + "    \"machine\": {} },\n"
                                + "  \"execution\": { \"tasks\": [ { \"id\": \"t1\", \"runtimeInSeconds\": 1 } ] }\n"
                                + "}\n")
                        .getBytes(StandardCharsets.UTF_8));
        // t2 无 runtime → 拒绝。
        assertThrows(Exception.class, () -> new WfCommonsJsonParser().parse(json.toString(), 0, 1));
        assertTrue(!ReplicaCatalog.containsFile("f.json"), "JSON 拒绝后目录必须洁净");
    }

    /** 成功路径稳健性基线：合法解析仍然登记（防止修复做成"从不注册"）。 */
    @Test
    void successfulParseStillRegistersInputs() throws Exception {
        Path dax = write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"10\">\n"
                + "    <uses name=\"ok-input.bin\" link=\"input\" size=\"123\"/>\n"
                + "  </job>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"10\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n");
        parseFile(dax);
        org.workflowsim.FileItem item = ReplicaCatalog.getFile("ok-input.bin");
        assertTrue(item != null, "成功解析必须登记输入文件（基线：修复不得矫枉过正）");
    }
}
