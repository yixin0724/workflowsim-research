package org.workflowsim.experiment.registry;

import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * Extension surface for third-party online scheduling algorithms (2026-10-23, BAT-3).
 *
 * <p>A provider is discovered through the standard Java {@link java.util.ServiceLoader} SPI,
 * so an external algorithm exists as a new class plus a service-registration file on the
 * classpath — <b>zero modifications to the repository's core tree</b>. The provider's
 * {@link #declaration()} is the self-declared contract metadata consumed by the standard
 * runner's fail-fast combination matrix; the platform validates the run configuration
 * against that declaration and refuses incompatible combinations instead of silently
 * running them.</p>
 *
 * <p>Scope: the online CPU scheduling decision layer (the FCFS / ready-batch family).
 * The dispatch is per ready Job at an idle compatible VM; no future machine-availability
 * reservation is provided. Planning-layer and coherent-assignment extension surfaces are
 * deliberately separate contracts.</p>
 *
 * <p>Contract obligations of an implementation:</p>
 * <ul>
 * <li>{@link #label()} must be a stable, non-empty, ASCII identifier that is not the name
 * of any built-in {@code Parameters.SchedulingAlgorithm} or {@code PlanningAlgorithm}
 * enum constant (collision is rejected at lookup, not silently shadowed);</li>
 * <li>{@link #create()} returns a fresh instance of the scheduling policy per invocation —
 * stateful policies rely on the scheduler's per-run policy lifecycle, and instances must
 * not be shared across runs;</li>
 * <li>{@link #declaration()} returns the same immutable declaration on every call.</li>
 * </ul>
 *
 * <p>These providers express model requirements; they do not acquire evidence contracts,
 * cluster/code provenance, or literature anchoring. A registered label in an experiment
 * configuration is exactly as auditable as the declared class behind it and no more.</p>
 */
public interface SchedulingAlgorithmProvider {

    /** @return the stable configuration label used in Workbench JSON and event metadata */
    String label();

    /** @return a fresh scheduling policy instance for one run */
    BaseSchedulingAlgorithm create();

    /** @return the immutable, self-declared contract metadata for the fail-fast matrix */
    AlgorithmDeclaration declaration();
}
