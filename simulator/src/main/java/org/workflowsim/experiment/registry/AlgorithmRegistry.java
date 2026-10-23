package org.workflowsim.experiment.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Stateless lookup and combination gate for {@link SchedulingAlgorithmProvider} entries
 * discovered through the standard Java {@link ServiceLoader} SPI (2026-10-23, BAT-3).
 *
 * <p>The registry holds no mutable global state: every lookup scans the current
 * classpath's providers deterministically (ServiceLoader order), which keeps it safe
 * under the single-JVM serialization discipline without a new session-managed static.
 * Duplicate labels are rejected loudly rather than shadowed.</p>
 *
 * <p>{@link #validate(String, ValidationInput)} is the fail-fast consumption point: the
 * same combination matrix the standard runner applies to built-in labels — file system,
 * data-movement kind, task-cost matrix, failure model, and the planning-layer pairing —
 * is checked against the provider's self-declared {@link AlgorithmDeclaration}.</p>
 */
public final class AlgorithmRegistry {

    private AlgorithmRegistry() {}

    /** The configuration facts a registered label is validated against. */
    public static final class ValidationInput {
        private final ReplicaCatalog.FileSystem fileSystem;
        private final DataMovementModel dataMovementModel;
        private final boolean taskCostMatrixDeclared;
        private final FailureModelConfig failureModel;
        private final Parameters.PlanningAlgorithm planningAlgorithm;

        /** Assembles the validation input from the standard run configuration. */
        public ValidationInput(SimulationConfig config) {
            this(
                    config.getFileSystem(),
                    config.getDataMovementModel(),
                    config.getTaskCostMatrix() != null
                            && !config.getTaskCostMatrix().asMap().isEmpty(),
                    config.getFailureModel(),
                    config.getPlanningAlgorithm());
        }

        /** Constructs the validation input explicitly (fixed-field, immutable). */
        public ValidationInput(
                ReplicaCatalog.FileSystem fileSystem,
                DataMovementModel dataMovementModel,
                boolean taskCostMatrixDeclared,
                FailureModelConfig failureModel,
                Parameters.PlanningAlgorithm planningAlgorithm) {
            this.fileSystem = fileSystem;
            this.dataMovementModel = dataMovementModel;
            this.taskCostMatrixDeclared = taskCostMatrixDeclared;
            this.failureModel = failureModel;
            this.planningAlgorithm = planningAlgorithm;
        }

        /** @return the configured file system */
        public ReplicaCatalog.FileSystem getFileSystem() {
            return fileSystem;
        }

        /** @return the configured data movement model */
        public DataMovementModel getDataMovementModel() {
            return dataMovementModel;
        }

        /** @return whether the configuration carries a non-empty task-cost matrix */
        public boolean isTaskCostMatrixDeclared() {
            return taskCostMatrixDeclared;
        }

        /** @return the configured failure model (never null; NONE default) */
        public FailureModelConfig getFailureModel() {
            return failureModel;
        }

        /** @return the configured planning algorithm (INVALID for pure online tracks) */
        public Parameters.PlanningAlgorithm getPlanningAlgorithm() {
            return planningAlgorithm;
        }
    }

    /**
     * Returns all providers visible on the classpath, keyed by label.
     *
     * @throws IllegalStateException on duplicate labels or labels colliding with built-in
     *         enum names — assignment ambiguity must be explicit
     */
    public static Map<String, SchedulingAlgorithmProvider> providers() {
        Map<String, SchedulingAlgorithmProvider> byLabel = new LinkedHashMap<String, SchedulingAlgorithmProvider>();
        ServiceLoader<SchedulingAlgorithmProvider> loader = ServiceLoader.load(SchedulingAlgorithmProvider.class);
        for (SchedulingAlgorithmProvider provider : loader) {
            String label = normalize(provider.label());
            if (label == null) {
                throw new IllegalStateException("Registered scheduling provider has an empty label: "
                        + provider.getClass().getName());
            }
            for (Parameters.SchedulingAlgorithm builtin : Parameters.SchedulingAlgorithm.values()) {
                if (label.equals(builtin.name())) {
                    throw new IllegalStateException(
                            "Registered label '" + label + "' collides with the built-in scheduling enum");
                }
            }
            for (Parameters.PlanningAlgorithm builtin : Parameters.PlanningAlgorithm.values()) {
                if (label.equals(builtin.name())) {
                    throw new IllegalStateException(
                            "Registered label '" + label + "' collides with the built-in planning enum");
                }
            }
            if (byLabel.put(label, provider) != null) {
                throw new IllegalStateException("Duplicate registered scheduling label: '" + label + "'");
            }
        }
        return byLabel;
    }

    /** @return true when a provider with this exact label is registered */
    public static boolean isRegistered(String label) {
        return label != null && providers().containsKey(normalize(label));
    }

    /**
     * Returns the provider for a label.
     *
     * @throws IllegalArgumentException when the label is unregistered
     */
    public static SchedulingAlgorithmProvider find(String label) {
        Map<String, SchedulingAlgorithmProvider> providers = providers();
        SchedulingAlgorithmProvider provider = providers.get(normalize(label));
        if (provider == null) {
            List<String> known = new ArrayList<String>(providers.keySet());
            throw new IllegalArgumentException("No registered scheduling algorithm labeled '" + label
                    + "' on the classpath" + (known.isEmpty() ? " (no providers registered)" : "; known: " + known));
        }
        return provider;
    }

    /**
     * Fail-fast combination gate for a registered label: validates the provider's
     * self-declared metadata against the run configuration.
     *
     * @throws IllegalArgumentException with the precise violated contract clause
     */
    public static void validate(String label, ValidationInput input) {
        SchedulingAlgorithmProvider provider = find(label);
        AlgorithmDeclaration declaration = provider.declaration();
        if (declaration == null) {
            throw new IllegalArgumentException(
                    "Registered scheduling algorithm '" + label + "' declares no contract metadata");
        }
        if (input.getPlanningAlgorithm() != Parameters.PlanningAlgorithm.INVALID) {
            throw new IllegalArgumentException("Registered scheduling label '" + label
                    + "' belongs to the plain online scheduling track and requires PlanningAlgorithm.INVALID, got "
                    + input.getPlanningAlgorithm() + " — a registered label cannot be paired with a planner");
        }
        if (!declaration.coversFileSystem(input.getFileSystem())) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label + "' declares "
                    + declaration.getFileSystemScope() + " and does not cover file system " + input.getFileSystem());
        }
        String kind = input.getDataMovementModel() == null
                ? null
                : input.getDataMovementModel().getKind().name();
        if (kind != null
                && !declaration.getAllowedDataMovementKinds().isEmpty()
                && !declaration.getAllowedDataMovementKinds().contains(kind)) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares data-movement kinds " + declaration.getAllowedDataMovementKinds()
                    + ", which do not cover the configured " + kind);
        }
        if (input.isTaskCostMatrixDeclared() && !declaration.acceptsTaskCostMatrix()) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares that it does not accept a task-cost matrix");
        }
        if (input.getFailureModel() != null
                && input.getFailureModel().isEnabled()
                && !declaration.allowsFailureModel()) {
            throw new IllegalArgumentException("Registered scheduling algorithm '" + label
                    + "' declares that it does not accept an enabled failure model");
        }
    }

    private static String normalize(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }
}
