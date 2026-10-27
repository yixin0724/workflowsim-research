package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * Test-scope registered scheduling provider for the BAT-E3 energy tests
 * (2026-10-27). Registered in the simulator test classpath's META-INF/services
 * so AlgorithmRegistry.validate() can exercise the power-required clause
 * without a heavyweight integration driver.
 *
 * <p>Declaration: powerModelRequired=true (implies energyAware=true).</p>
 */
public final class EnergyTestSchedulerProvider implements SchedulingAlgorithmProvider {

    @Override
    public String label() {
        return "energy-test-required";
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        throw new IllegalStateException("The energy test provider never schedules");
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .powerModelRequired(true)
                .description("energy power-required test provider")
                .build();
    }
}
