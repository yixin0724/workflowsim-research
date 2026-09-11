package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SimulationRandomTest {

    @AfterEach
    void restoreDefaultSeed() {
        SimulationRandom.reset(0L);
    }

    @Test
    void resetReplaysNamedJavaStream() {
        SimulationRandom.reset(20260901L);
        Random first = SimulationRandom.newJavaRandom("planning.random");
        long[] firstValues = {first.nextLong(), first.nextLong(), first.nextLong()};

        SimulationRandom.reset(20260901L);
        Random second = SimulationRandom.newJavaRandom("planning.random");
        long[] secondValues = {second.nextLong(), second.nextLong(), second.nextLong()};

        assertArrayEquals(firstValues, secondValues);
    }

    @Test
    void componentNamesProduceIndependentStreams() {
        SimulationRandom.reset(20260901L);
        long planningValue = SimulationRandom.newJavaRandom("planning.random").nextLong();

        SimulationRandom.reset(20260901L);
        long clusteringValue = SimulationRandom.newJavaRandom("clustering.horizontal.bundle").nextLong();

        assertNotEquals(planningValue, clusteringValue);
    }

    @Test
    void distributionSamplesReplayAfterReset() {
        SimulationRandom.reset(99L);
        DistributionGenerator first = new DistributionGenerator(
                DistributionGenerator.DistributionFamily.WEIBULL, 2.0, 3.0);
        double firstSample = first.getNextSample();

        SimulationRandom.reset(99L);
        DistributionGenerator second = new DistributionGenerator(
                DistributionGenerator.DistributionFamily.WEIBULL, 2.0, 3.0);

        assertEquals(firstSample, second.getNextSample(), 0.0);
    }
}
