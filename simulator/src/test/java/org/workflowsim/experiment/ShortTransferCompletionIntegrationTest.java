package org.workflowsim.experiment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import static org.junit.jupiter.api.Assertions.*;

/** Short flows finish between CloudSim checks; every completion must still release its Job. */
class ShortTransferCompletionIntegrationTest {
    @AfterEach void restoreLog() { Log.enable(); }

    @Test void staggeredShortFlowsFinishUnderBothContentionModels(@TempDir Path directory) throws Exception {
        Log.disable();
        Path dax = directory.resolve("short-flows.dax");
        String xml = "<adag version=\"2.1\">"
                + "<job id=\"a\" runtime=\"0.10\"><uses file=\"f\" link=\"output\" size=\"100\"/></job>"
                + "<job id=\"b\" runtime=\"0.15\"><uses file=\"g\" link=\"output\" size=\"150\"/></job>"
                + "<job id=\"c\" runtime=\"0.10\"><uses file=\"f\" link=\"input\" size=\"100\"/>"
                + "<uses file=\"h\" link=\"output\" size=\"200\"/></job>"
                + "<job id=\"d\" runtime=\"0.15\"><uses file=\"g\" link=\"input\" size=\"150\"/>"
                + "<uses file=\"i\" link=\"output\" size=\"100\"/></job>"
                + "<job id=\"e\" runtime=\"0.10\"><uses file=\"h\" link=\"input\" size=\"200\"/>"
                + "<uses file=\"i\" link=\"input\" size=\"100\"/></job>"
                + "<child ref=\"c\"><parent ref=\"a\"/></child>"
                + "<child ref=\"d\"><parent ref=\"b\"/></child>"
                + "<child ref=\"e\"><parent ref=\"c\"/><parent ref=\"d\"/></child></adag>";
        Files.write(dax, xml.getBytes(StandardCharsets.UTF_8));
        int transfers = 0;
        for (boolean fatTree : new boolean[] {false, true}) {
            PlatformProfile.Builder platform = PlatformProfile.builder("short-flow-checks");
            for (int id = 0; id < 4; id++) {
                platform.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000, 1000000));
                platform.addVm(new PlatformProfile.VmSpec(id, 1000, 1, 512, 1, 10000, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            }
            if (fatTree) { platform.networkTopology(NetworkTopologySpec.fatTree(4, 0.125)); }
            PlatformProfile profile = platform.build();
            for (long seed = 0; seed < 20; seed++) {
                SimulationConfig config = SimulationConfig.builder(dax.toString(), 4)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(seed)
                        .dataMovementModel(fatTree ? DataMovementModel.fatTreeContentionV1()
                                : DataMovementModel.preExecutionTransferDelayWithContentionV1()).build();
                SimulationReport report = new SimulationRunner().run(config, profile);
                assertTrue(report.isWorkflowCompletedSuccessfully());
                assertEquals(6, report.getSuccessfulJobs());
                assertEquals(5, report.getTasks().size());
                assertEquals(report.getMakespan(), new SimulationRunner().run(config, profile).getMakespan(), 0.0);
                for (SimulationEvent event : report.getEvents()) {
                    if (event.getAttributes().containsKey("contentionTransferGroupCount")) {
                        transfers += ((Number) event.getAttributes().get("contentionTransferGroupCount")).intValue();
                    }
                }
            }
        }
        assertTrue(transfers > 50, "The regression must actually exercise nonlocal short transfers");
    }
}
