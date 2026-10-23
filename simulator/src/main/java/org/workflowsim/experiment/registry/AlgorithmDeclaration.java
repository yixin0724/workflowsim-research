package org.workflowsim.experiment.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.workflowsim.utils.ReplicaCatalog;

/**
 * Self-declared combination contract of a registered scheduling algorithm (2026-10-23,
 * BAT-3). The standard runner consumes exactly these fields to validate a configuration
 * the same way it validates its built-in matrix rows: file-system scope, accepted
 * data-movement model kinds, task-cost-matrix acceptance, and failure-model acceptance.
 *
 * <p>The declaration is the algorithm author's contract statement. The platform enforces
 * it fail-fast; it does not verify that the declared class actually implements a
 * literature-grade heuristic — that claim remains the author's responsibility.</p>
 */
public final class AlgorithmDeclaration {

    /** File-system scope the declared algorithm is valid for. */
    public enum FileSystemScope {
        /** Usable with either supported catalog file system. */
        ANY,
        /** Requires LOCAL replica placement (e.g. locality-aware decisions). */
        LOCAL_ONLY,
        /** Requires SHARED datacenter-level storage semantics. */
        SHARED_ONLY
    }

    private final FileSystemScope fileSystemScope;
    private final Set<String> allowedDataMovementKinds;
    private final boolean acceptsTaskCostMatrix;
    private final boolean allowsFailureModel;
    private final String description;

    private AlgorithmDeclaration(
            FileSystemScope fileSystemScope,
            Set<String> allowedDataMovementKinds,
            boolean acceptsTaskCostMatrix,
            boolean allowsFailureModel,
            String description) {
        this.fileSystemScope = fileSystemScope;
        this.allowedDataMovementKinds = Collections.unmodifiableSet(new LinkedHashSet<>(allowedDataMovementKinds));
        this.acceptsTaskCostMatrix = acceptsTaskCostMatrix;
        this.allowsFailureModel = allowsFailureModel;
        this.description = description;
    }

    /** @return the file-system scope this algorithm declares support for */
    public FileSystemScope getFileSystemScope() {
        return fileSystemScope;
    }

    /** @return the data-movement {@link org.workflowsim.data.DataMovementModel.Kind} names
     *         the declaration covers; empty means every kind the runner itself supports */
    public Set<String> getAllowedDataMovementKinds() {
        return allowedDataMovementKinds;
    }

    /** @return whether configurations declaring a task-cost matrix may use this algorithm */
    public boolean acceptsTaskCostMatrix() {
        return acceptsTaskCostMatrix;
    }

    /** @return whether configs with an enabled failure model may use this algorithm */
    public boolean allowsFailureModel() {
        return allowsFailureModel;
    }

    /** @return a one-line human description surfaced in validation errors and docs */
    public String getDescription() {
        return description;
    }

    /** Returns whether the declared file-system scope covers the given configured system. */
    public boolean coversFileSystem(ReplicaCatalog.FileSystem fileSystem) {
        if (fileSystem == null) {
            return false;
        }
        switch (fileSystemScope) {
            case ANY:
                return true;
            case LOCAL_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.LOCAL;
            case SHARED_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.SHARED;
            default:
                return false;
        }
    }

    /** Builder with the same validation discipline as the rest of the configuration API. */
    public static final class Builder {
        private FileSystemScope fileSystemScope = FileSystemScope.ANY;
        private final Set<String> allowedDataMovementKinds = new LinkedHashSet<>();
        private boolean acceptsTaskCostMatrix = false;
        private boolean allowsFailureModel = false;
        private String description = "";

        /** Declares the file-system scope this algorithm is valid for (default ANY). */
        public Builder fileSystemScope(FileSystemScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("FileSystemScope is required");
            }
            this.fileSystemScope = scope;
            return this;
        }

        /** Adds one accepted data-movement kind name (e.g. LEGACY_WORKFLOWSIM_V1). */
        public Builder allowDataMovementKind(String kind) {
            if (kind == null || kind.trim().isEmpty()) {
                throw new IllegalArgumentException("data-movement kind must be a non-empty name");
            }
            allowedDataMovementKinds.add(kind);
            return this;
        }

        /** Declares whether a declared task-cost matrix may accompany this algorithm. */
        public Builder acceptsTaskCostMatrix(boolean accepts) {
            this.acceptsTaskCostMatrix = accepts;
            return this;
        }

        /** Declares whether an enabled failure model may accompany this algorithm. */
        public Builder allowsFailureModel(boolean allows) {
            this.allowsFailureModel = allows;
            return this;
        }

        /** Sets the one-line description used in errors and documentation. */
        public Builder description(String text) {
            if (text == null) {
                throw new IllegalArgumentException("description must not be null (use empty string)");
            }
            this.description = text;
            return this;
        }

        /** Builds the immutable declaration; all validation happens here, fail-fast. */
        public AlgorithmDeclaration build() {
            return new AlgorithmDeclaration(
                    fileSystemScope, allowedDataMovementKinds, acceptsTaskCostMatrix, allowsFailureModel, description);
        }
    }
}
