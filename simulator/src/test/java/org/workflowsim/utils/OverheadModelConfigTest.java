package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import org.junit.jupiter.api.Test;

class OverheadModelConfigTest {

    @Test
    void rejectsUnboundedNormalDistributionForSimulatedDelays() {
        DistributionSpec normal = DistributionSpec.of(
                DistributionGenerator.DistributionFamily.NORMAL, 1.0, 0.1);

        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(0, normal))
                .build());
    }
}
