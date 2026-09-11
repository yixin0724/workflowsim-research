package org.workflowsim.experiments.reference.p7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.workflowsim.WorkflowParser;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

class P7BaselineMatrixTest {

    @Test
    void frozenMatrixUsesOnlyAuditedInputsAndApprovedModelSettings() throws Exception {
        Path datasetRoot = datasetRoot();
        List<P7BaselineMatrix.Scenario> scenarios = P7BaselineMatrix.scenarios(datasetRoot);

        assertEquals(4, scenarios.size());
        assertEquals(5, P7BaselineMatrix.schedulingAlgorithms().size());
        Set<String> scenarioIds = new HashSet<String>();
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            assertTrue(scenarioIds.add(scenario.getId()));
            Path workflow = scenario.resolveWorkflow(datasetRoot);
            assertTrue(Files.isRegularFile(workflow), scenario.getRelativeWorkflowPath());
            assertEquals(scenario.getExpectedWorkflowSha256(), sha256(workflow));

            SimulationConfig parserConfig = P7BaselineMatrix.baselineConfig(scenario,
                    Parameters.SchedulingAlgorithm.FCFS, datasetRoot);
            try (SimulationSession session = SimulationSession.open(parserConfig)) {
                WorkflowParser parser = new WorkflowParser(0);
                parser.parse();
                assertEquals(scenario.getExpectedTaskCount(), parser.getTaskList().size());
                assertEquals(scenario.getExpectedTaskCount(),
                        parser.getInputReports().get(0).getTaskCount());
            }

            for (Parameters.SchedulingAlgorithm algorithm
                    : P7BaselineMatrix.schedulingAlgorithms()) {
                SimulationConfig config = P7BaselineMatrix.baselineConfig(
                        scenario, algorithm, datasetRoot);
                assertEquals(algorithm, config.getSchedulingAlgorithm());
                assertEquals(Parameters.PlanningAlgorithm.INVALID, config.getPlanningAlgorithm());
                assertEquals(org.workflowsim.utils.ReplicaCatalog.FileSystem.SHARED,
                        config.getFileSystem());
                assertEquals(P7BaselineMatrix.ROOT_SEED, config.getRandomSeed());
                assertEquals(P7BaselineMatrix.RUNTIME_REFERENCE_MIPS,
                        config.getRuntimeReferenceMips(), 0.0);
                assertEquals(P7BaselineMatrix.RUNTIME_SCALE, config.getRuntimeScale(), 0.0);
                assertEquals(P7BaselineMatrix.CLOUDSIM_MIN_EVENT_INTERVAL_SECONDS,
                        config.getCloudSimMinEventIntervalSeconds(), 0.0);
                assertFalse(config.getFailureModel().isEnabled());
                assertEquals(0, config.getOverheadModel().getWorkflowEngineDelayInterval());
                assertTrue(config.getOverheadModel().getWorkflowEngineDelays().isEmpty());
                assertTrue(config.getOverheadModel().getQueueDelays().isEmpty());
                assertTrue(config.getOverheadModel().getPostDelays().isEmpty());
                assertTrue(config.getOverheadModel().getClusteringDelays().isEmpty());
                assertEquals(org.workflowsim.utils.ClusteringParameters.ClusteringMethod.NONE,
                        config.getClusteringParameters().getClusteringMethod());
            }
        }
    }

    @Test
    void platformsRemainOneVmPerHostAndHeterogeneousMeanCapacityIsControlled() {
        List<P7BaselineMatrix.Scenario> scenarios = P7BaselineMatrix.scenarios(datasetRoot());
        for (P7BaselineMatrix.Scenario scenario : scenarios) {
            PlatformProfile platform = P7BaselineMatrix.baselinePlatform(scenario);
            assertEquals(scenario.getVmCount(), platform.getHosts().size());
            assertEquals(scenario.getVmCount(), platform.getVms().size());
            assertFalse(platform.getVms().isEmpty());
            for (int index = 0; index < platform.getVms().size(); index++) {
                assertEquals(index, platform.getHosts().get(index).getId());
                assertEquals(index, platform.getVms().get(index).getId());
                assertEquals(PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        platform.getVms().get(index).getSchedulerMode());
            }
            double totalMips = 0.0;
            for (PlatformProfile.VmSpec vm : platform.getVms()) {
                totalMips += vm.getMips();
            }
            assertEquals(1000.0, totalMips / platform.getVms().size(), 0.0);
        }
    }

    @Test
    void referenceDatasetContractRejectsRelativeRootsAndEscapingPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> P7BaselineMatrix.scenarios(Paths.get("datasets")));
        assertThrows(IllegalArgumentException.class,
                () -> ReferenceDatasetRoot.resolveFile(datasetRoot(), "../pom.xml"));
    }

    private static Path datasetRoot() {
        String configured = System.getProperty("workflowsim.datasetRoot");
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        return ReferenceDatasetRoot.require(Paths.get(configured));
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
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
