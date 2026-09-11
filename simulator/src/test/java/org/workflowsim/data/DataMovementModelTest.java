package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DataMovementModelTest {

    @Test
    void legacyModelIsTheExplicitCompatibilityDefault() {
        DataMovementModel model = DataMovementModel.legacyWorkflowsimV1();

        assertTrue(model.isLegacyWorkflowsimV1());
        assertEquals(DataMovementModel.Kind.LEGACY_WORKFLOWSIM_V1, model.getKind());
    }

    @Test
    void fixedEndpointModelValidatesEveryPhysicalParameter() {
        DataMovementModel model = DataMovementModel.fixedEndpointNoContention(20.0, 0.1, 10.0);
        assertEquals(DataMovementModel.Kind.FIXED_ENDPOINT_NO_CONTENTION_V1, model.getKind());
        assertEquals(20.0, model.getAccessLinkBandwidthMbPerSecond(), 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> DataMovementModel.fixedEndpointNoContention(0.0, 0.1, 10.0));
        assertThrows(IllegalArgumentException.class,
                () -> DataMovementModel.fixedEndpointNoContention(10.0, -0.1, 10.0));
    }

    @Test
    void preExecutionTransferDelayModelIsAStableSharedInstance() {
        DataMovementModel model = DataMovementModel.preExecutionTransferDelayV1();

        assertTrue(model.isPreExecutionTransferDelayV1());
        assertEquals(DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_V1, model.getKind());
        assertTrue(model == DataMovementModel.preExecutionTransferDelayV1());
        assertTrue(!model.isLegacyWorkflowsimV1());
    }
}
