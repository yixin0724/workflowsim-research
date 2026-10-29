package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * Test-scope registered scheduling provider for the BAT-D4 DVFS tests
 * (2026-10-29). Registered in the simulator test classpath's META-INF/services
 * so AlgorithmRegistry.validate() can exercise the frequency-tiers-required
 * clause without a heavyweight integration driver.
 *
 * <p>Declaration: frequencyTiersRequired=true (implies dvfsAware=true).</p>
 */
public final class DvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    @Override
    public String label() {
        return "dvfs-test-required";
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        throw new IllegalStateException("The DVFS test provider never schedules");
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .frequencyTiersRequired(true)
                .description("dvfs frequency-tiers-required test provider")
                .build();
    }
}
