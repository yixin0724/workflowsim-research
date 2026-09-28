package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Fractional flow contracts are checked against hand-calculated bytes/rate durations. */
class FractionalTransferContentionEngineTest {
    @Test
    void independentTinyFlowsKeepTheirOwnCompletionTimes() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.addTransfer(1L, 5.0e-10, Collections.<String>emptyList(), 1.0e-9, 0.0);
        engine.addTransfer(2L, 1.0e-12, Collections.<String>emptyList(), 1.0e-9, 0.0);
        assertEquals(2, engine.activeTransferCount());
        assertEquals(1.0e-9, engine.currentRateBytesPerSecond(1L), 1.0e-24);
        assertEquals(1.0e-9, engine.currentRateBytesPerSecond(2L), 1.0e-24);

        TransferContentionEngine.AdvanceResult small = engine.advance(0.001);
        assertEquals(Collections.singletonList(2L), small.getCompletedTransferIds());
        assertEquals(1, engine.activeTransferCount());
        assertEquals(0.5, small.getNextCompletionTime(), 1.0e-15);
        assertTrue(engine.advance(0.499).getCompletedTransferIds().isEmpty());
        TransferContentionEngine.AdvanceResult large = engine.advance(0.5);
        assertEquals(Collections.singletonList(1L), large.getCompletedTransferIds());
        assertNull(large.getNextCompletionTime());
        assertEquals(0, engine.activeTransferCount());
    }

    @Test
    void doubleEndpointOverloadPreservesHalfAByte() {
        TransferContentionEngine engine = new TransferContentionEngine();
        TransferContentionEngine.AdvanceResult start =
                engine.addTransfer(1L, 0.5, "SOURCE", "VM:0", 1_000_000.0, 0.0);
        assertEquals(0.0000005, start.getNextCompletionTime(), 1.0e-20);
        assertEquals(Collections.singletonList(1L), engine.advance(0.0000005).getCompletedTransferIds());
    }

    @Test
    void invalidOrUnrepresentablePositiveDemandsAreRejectedBeforeRegistration() {
        TransferContentionEngine engine = new TransferContentionEngine();
        for (double bytes : new double[] {0.0, -0.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> engine.addTransfer(1L, bytes, Collections.<String>emptyList(), 1.0, 0.0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(1L, Double.MIN_VALUE, Collections.<String>emptyList(), 1_000_000.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> engine.addTransfer(1L, Double.MAX_VALUE, Collections.<String>emptyList(), Double.MIN_VALUE, 0.0));
        assertEquals(0, engine.activeTransferCount());
    }

    @Test
    void positiveNonlocalBytesCannotSilentlyBecomeZeroEstimatedSeconds(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("underflow.dax");
        Files.write(input, ("<adag version=\"2.1\"><job id=\"root\" runtime=\"1\">"
                + "<uses file=\"tiny\" link=\"input\" size=\"" + Double.toString(Double.MIN_VALUE)
                + "\"/></job></adag>").getBytes(StandardCharsets.UTF_8));
        boolean disabled = Log.isDisabled();
        Log.disable();
        try {
            for (DataMovementModel model : new DataMovementModel[] {
                    DataMovementModel.preExecutionTransferDelayV1(),
                    DataMovementModel.preExecutionTransferDelayWithContentionV1(),
                    DataMovementModel.fatTreeContentionV1()}) {
                PlatformProfile.Builder platform = PlatformProfile.builder("unrepresentable-transfer")
                        .addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000, 1000000))
                        .addVm(new PlatformProfile.VmSpec(0, 1000, 1, 512,
                                1, 10000, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                        .pinVmToHost(0, 0);
                if (model.isFatTreeContentionV1()) { platform.networkTopology(NetworkTopologySpec.fatTree(2, 1)); }
                SimulationConfig config = SimulationConfig.builder(input.toString(), 1)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MET)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model).build();
                PlatformProfile profile = platform.build();
                Exception failure = assertThrows(Exception.class, () -> new SimulationRunner().run(config, profile));
                Throwable root = failure;
                while (root.getCause() != null && root.getCause() != root) { root = root.getCause(); }
                assertTrue(root.getMessage().contains("Positive nonlocal bytes"), root.toString());
            }
        } finally { Log.setDisabled(disabled); }
    }
}
