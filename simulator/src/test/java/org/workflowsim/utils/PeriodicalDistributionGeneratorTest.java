package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 验证周期性信号和分布生成器在缓存扩展后仍保持同一模型语义。 */
class PeriodicalDistributionGeneratorTest {

    @BeforeEach
    void resetRandomStreams() {
        SimulationRandom.reset(20260903L);
    }

    @AfterEach
    void clearRandomStreams() {
        SimulationRandom.reset(0L);
    }

    @Test
    void signalRejectsInvalidDomainValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicalSignal(0.0, 1.0, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicalSignal(1.0, Double.NaN, 1.0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicalSignal(1.0, 1.0, -1.0, 0.5));
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicalSignal(1.0, 1.0, 1.0, -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new PeriodicalSignal(1.0, 1.0, 1.0, 1.1));
    }

    @Test
    void signalDirectionMakesTheHighFailureIntervalStartFirst() {
        PeriodicalSignal signal = new PeriodicalSignal(10.0, 120.0, 20.0, 0.2, false);

        assertEquals(20.0, signal.getCurrentSignal(0.0));
        assertEquals(20.0, signal.getCurrentSignal(2.0));
        assertEquals(120.0, signal.getCurrentSignal(2.1));
    }

    @Test
    void cacheExtensionKeepsUsingThePeriodicalDistribution() {
        PeriodicalSignal signal = new PeriodicalSignal(10.0, 1.0, 100.0, 0.5, true);
        PeriodicalDistributionGenerator generator = new PeriodicalDistributionGenerator(
                DistributionGenerator.DistributionFamily.NORMAL, 7.0, 1.0e-9,
                signal);

        for (int index = 0; index < 1500; index++) {
            generator.getNextSample();
        }
        double firstExtendedSample = generator.getNextSample();

        assertEquals(3000, generator.getSamples().length);
        assertTrue(firstExtendedSample > 50.0,
                "Extended samples must follow the periodic lower-state mean, not base scale 7");
    }
}
