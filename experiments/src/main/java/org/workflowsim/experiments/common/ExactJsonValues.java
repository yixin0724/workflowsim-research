package org.workflowsim.experiments.common;

import java.math.BigDecimal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** JSON value comparison without Gson's lossy conversion of parsed integers to double. */
public final class ExactJsonValues {
    private ExactJsonValues() {}

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
        if (first == null || second == null) {
            return first == second;
        }
        if (first.isJsonObject() && second.isJsonObject()) {
            return equalObjectMembers(first, second);
        }
        if (first.isJsonArray() && second.isJsonArray()) {
            return equalArrayElements(first, second);
        }
        if (first.isJsonPrimitive()
                && second.isJsonPrimitive()
                && first.getAsJsonPrimitive().isNumber()
                && second.getAsJsonPrimitive().isNumber()) {
            try {
                return new BigDecimal(first.getAsString()).compareTo(new BigDecimal(second.getAsString())) == 0;
            } catch (NumberFormatException invalidNumber) {
                return false;
            }
        }
        return first.equals(second);
    }

    /**
     * 2026-10-26 complexity extraction from equal; statements verbatim; order unchanged.
     * 作用域：仅比对两个 JSON 对象（成员集合一致且逐成员递归精确相等）。
     */
    private static boolean equalObjectMembers(JsonElement first, JsonElement second) {
        JsonObject a = first.getAsJsonObject();
        JsonObject b = second.getAsJsonObject();
        if (!a.keySet().equals(b.keySet())) {
            return false;
        }
        for (String key : a.keySet()) {
            if (!equal(a.get(key), b.get(key))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 2026-10-26 complexity extraction from equal; statements verbatim; order unchanged.
     * 作用域：仅比对两个 JSON 数组（长度一致且按下标递归精确相等）。
     */
    private static boolean equalArrayElements(JsonElement first, JsonElement second) {
        JsonArray a = first.getAsJsonArray();
        JsonArray b = second.getAsJsonArray();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!equal(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }
}
