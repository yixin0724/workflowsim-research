package org.workflowsim.data.v2;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Immutable, explicitly opt-in configuration for NF005 online destination binding.
 * Assignment occurs when a task becomes control-ready, not by remapping it at CPU
 * dispatch. The assignment mode and policy are separate from network evidence
 * capture and the physical file/storage lifecycle; this value contains neither
 * observer state nor runtime VM decisions.
 *
 * <p>The default is {@link #off()}. Callers omit the ordinary
 * {@code configuration.dataflowAssignment} member entirely when OFF, rather than
 * encoding null or an OFF object. An enabled member contains only {@code mode}
 * and {@code policy}; it belongs outside the algorithm contract.
 */
public final class DataflowAssignmentConfig {
    /** Versioned destination-binding mode, not a capture or physical lifecycle mode. */
    public enum Mode {
        /** Preserve the default behavior without online destination assignment. */
        OFF,
        /** Bind destinations online when tasks become control-ready. */
        CONTROL_READY_ONLINE_ASSIGNMENT_V1
    }

    /** Fixed identifier for the nominal-input earliest-reservation assignment policy. */
    public static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";

    private static final DataflowAssignmentConfig OFF = new DataflowAssignmentConfig(Mode.OFF);
    private static final DataflowAssignmentConfig ONLINE_NOMINAL =
            new DataflowAssignmentConfig(Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1);

    private final Mode mode;

    private DataflowAssignmentConfig(Mode mode) {
        this.mode = mode;
    }

    /** @return the shared default-OFF configuration, represented by omission in JSON */
    public static DataflowAssignmentConfig off() {
        return OFF;
    }

    /** @return the shared explicit control-ready configuration with the fixed nominal policy */
    public static DataflowAssignmentConfig onlineNominal() {
        return ONLINE_NOMINAL;
    }

    /** @return the destination-binding mode */
    public Mode getMode() {
        return mode;
    }

    /** @return {@link #POLICY} when enabled, or null when OFF */
    public String getPolicy() {
        return isEnabled() ? POLICY : null;
    }

    /** @return whether control-ready online destination assignment is explicitly enabled */
    public boolean isEnabled() {
        return mode == Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1;
    }

    /**
     * Decodes only an explicitly enabled ordinary configuration member.
     *
     * <p>The object must have exactly the case-sensitive keys {@code mode} and
     * {@code policy}, both strict JSON strings. Their values must be exactly
     * {@code CONTROL_READY_ONLINE_ASSIGNMENT_V1} and
     * {@code NOMINAL_INPUT_EARLIEST_RESERVATION_V1}, respectively. No coercion,
     * trimming, default inference or fallback is performed. A caller handling an
     * absent member must use {@link #off()} instead of passing null here.
     * The supplied JSON is neither modified nor retained.
     *
     * @param element the enabled {@code configuration.dataflowAssignment} member
     * @return the shared {@link #onlineNominal()} configuration
     * @throws IllegalArgumentException if the element is null, is not an object,
     *         has missing or extra keys, has non-string fields, or contains any
     *         unsupported mode or policy (including OFF)
     */
    public static DataflowAssignmentConfig decodeConfig(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(
                    "dataflowAssignment must be an object containing exactly mode and policy");
        }
        JsonObject object = element.getAsJsonObject();
        if (object.size() != 2 || !object.has("mode") || !object.has("policy")) {
            throw new IllegalArgumentException(
                    "dataflowAssignment must contain exactly mode and policy");
        }
        String mode = requireString(object, "mode");
        String policy = requireString(object, "policy");
        if (!Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1.name().equals(mode)) {
            throw new IllegalArgumentException("Unsupported dataflowAssignment mode: " + mode);
        }
        if (!POLICY.equals(policy)) {
            throw new IllegalArgumentException("Unsupported dataflowAssignment policy: " + policy);
        }
        return onlineNominal();
    }

    private static String requireString(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("dataflowAssignment " + key + " must be a JSON string");
        }
        return value.getAsString();
    }

    /**
     * Compares exact modes; each mode has a single fixed policy.
     *
     * @param other object to compare with this configuration
     * @return whether both configurations have the same mode and policy
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof DataflowAssignmentConfig
                && mode == ((DataflowAssignmentConfig) other).mode;
    }

    /** @return a hash consistent with the exact mode and its fixed policy */
    @Override
    public int hashCode() {
        return mode.hashCode();
    }
}
