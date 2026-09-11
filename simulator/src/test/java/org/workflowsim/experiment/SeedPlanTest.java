package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SeedPlanTest {

    @Test
    void derivedSeedsAreStableAndDistinct() {
        SeedPlan first = SeedPlan.derived(RandomizationDesign.INDEPENDENT_REPLICATIONS, 42L, 3);
        SeedPlan second = SeedPlan.derived(RandomizationDesign.INDEPENDENT_REPLICATIONS, 42L, 3);

        assertEquals(first.getSeeds(), second.getSeeds());
        assertEquals(3, first.getReplicationCount());
        assertNotEquals(first.getSeeds().get(0), first.getSeeds().get(1));
        assertEquals(Long.valueOf(42L), first.getDerivationRootSeed());
    }

    @Test
    void deterministicAndExplicitPlansRejectInvalidReplicationContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> SeedPlan.derived(RandomizationDesign.DETERMINISTIC, 1L, 2));
        assertThrows(IllegalArgumentException.class,
                () -> SeedPlan.explicit(RandomizationDesign.INDEPENDENT_REPLICATIONS,
                        Arrays.asList(Long.valueOf(1L), Long.valueOf(1L))));
    }
}
