package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.utils.Parameters;

class P7WfInstancesPilotMatrixTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void selectedWfInstancesInputsHaveFrozenHashesAndStrictlyRunAsAbstractWorkloads()
            throws Exception {
        Path root = datasetRoot();
        List<P7WfInstancesPilotMatrix.Scenario> scenarios = P7WfInstancesPilotMatrix.scenarios();
        assertEquals(4, scenarios.size());
        // wfinstances 语料体积较大且未纳入版本库（见 .gitignore）；缺失时跳过本 pilot 矩阵，
        // 保证无大型语料的环境（如 CI 检出）仍可执行完整门禁。注意这里必须直接拼路径：
        // scenario.resolve(root) 自带严格校验，文件缺失时会在 assumeTrue 之前抛异常。
        for (P7WfInstancesPilotMatrix.Scenario scenario : scenarios) {
            Assumptions.assumeTrue(
                    Files.isRegularFile(root.resolve(scenario.getRelativePath()).normalize()),
                    "Skipping: optional WfInstances corpus not present for "
                            + scenario.getRelativePath());
        }
        Log.disable();

        for (P7WfInstancesPilotMatrix.Scenario scenario : scenarios) {
            Path input = scenario.resolve(root);
            assertTrue(Files.isRegularFile(input), scenario.getRelativePath());
            assertEquals(scenario.getExpectedSha256(), sha256(input));

            SimulationReport report = new SimulationRunner().run(
                    P7WfInstancesPilotMatrix.baselineConfig(scenario, root,
                            Parameters.SchedulingAlgorithm.FCFS),
                    P7WfInstancesPilotMatrix.baselinePlatform());
            assertEquals(1, report.getInputReports().size());
            assertEquals(WorkflowInputReport.Format.WFCOMMONS_JSON,
                    report.getInputReports().get(0).getFormat());
            assertEquals("1.5", report.getInputReports().get(0).getDeclaredVersion());
            assertEquals(scenario.getExpectedTaskCount(),
                    report.getInputReports().get(0).getTaskCount());
            assertEquals(scenario.getExpectedTaskCount(), report.getTasks().size());
            assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
            assertEquals(0, report.getFailedJobs());
            assertFalse(report.getEvents().isEmpty());
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) {
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static Path datasetRoot() {
        String configured = System.getProperty("workflowsim.datasetRoot");
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        return ReferenceDatasetRoot.require(Paths.get(configured));
    }
}
