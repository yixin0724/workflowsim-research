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

    /** R8 审计（F8）：两个争用工厂此前无任何直接单测，锁定 kind 判别互斥性与共享实例。 */
    @Test
    void contentionFactoriesExposeConsistentKindPredicates() {
        DataMovementModel endpoint = DataMovementModel.preExecutionTransferDelayWithContentionV1();
        assertEquals(DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1,
                endpoint.getKind());
        assertTrue(endpoint.isPreExecutionTransferDelayWithContentionV1());
        assertTrue(!endpoint.isFatTreeContentionV1());
        assertTrue(!endpoint.isLegacyWorkflowsimV1());
        assertTrue(endpoint == DataMovementModel.preExecutionTransferDelayWithContentionV1());

        DataMovementModel fatTree = DataMovementModel.fatTreeContentionV1();
        assertEquals(DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1,
                fatTree.getKind());
        assertTrue(fatTree.isFatTreeContentionV1());
        assertTrue(!fatTree.isPreExecutionTransferDelayWithContentionV1());
        assertTrue(!fatTree.isPreExecutionTransferDelayV1());
        assertTrue(fatTree == DataMovementModel.fatTreeContentionV1());
    }
}
