package org.workflowsim.experiments.common;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;

/** JSON value comparison without Gson's lossy conversion of parsed integers to double. */
public final class ExactJsonValues {
    private ExactJsonValues() { }

    /**
     * Compare exact numeric values, unordered object members, and ordered array elements.
     * Equivalent decimal notation (1000, 1000.0, 1e3) is equal; no numeric epsilon is used.
     * Strings, booleans, nulls, and absent members remain distinct JSON values.
     *
     * @param first first JSON value, or null for an absent member
     * @param second second JSON value, or null for an absent member
     * @return whether both values are exactly equivalent under this contract
     */
    public static boolean equal(JsonElement first, JsonElement second) {
        if (first == null || second == null) { return first == second; }
        if (first.isJsonObject() && second.isJsonObject()) {
            JsonObject a = first.getAsJsonObject();
            JsonObject b = second.getAsJsonObject();
            if (!a.keySet().equals(b.keySet())) { return false; }
            for (String key : a.keySet()) {
                if (!equal(a.get(key), b.get(key))) { return false; }
            }
            return true;
        }
        if (first.isJsonArray() && second.isJsonArray()) {
            JsonArray a = first.getAsJsonArray();
            JsonArray b = second.getAsJsonArray();
            if (a.size() != b.size()) { return false; }
            for (int i = 0; i < a.size(); i++) {
                if (!equal(a.get(i), b.get(i))) { return false; }
            }
            return true;
        }
        if (first.isJsonPrimitive() && second.isJsonPrimitive()
                && first.getAsJsonPrimitive().isNumber() && second.getAsJsonPrimitive().isNumber()) {
            try {
                return new BigDecimal(first.getAsString()).compareTo(new BigDecimal(second.getAsString())) == 0;
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
        }
        return first.equals(second);
    }
}
