package org.workflowsim.experiments.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

class ExactJsonValuesTest {
    @Test
    void objectOrderIsIrrelevantButArrayOrderAndJsonTypesAreNot() {
        assertTrue(ExactJsonValues.equal(JsonParser.parseString("{\"a\":1,\"b\":[2,null]}"),
                JsonParser.parseString("{\"b\":[2.0,null],\"a\":1e0}")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("[1,2]"), JsonParser.parseString("[2,1]")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("[1]"), JsonParser.parseString("[1,2]")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("{}"), JsonParser.parseString("{\"a\":null}")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("1"), JsonParser.parseString("\"1\"")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("true"), JsonParser.parseString("\"true\"")));
        assertFalse(ExactJsonValues.equal(null, JsonNull.INSTANCE));
        assertTrue(ExactJsonValues.equal(null, null));
    }

    @Test
    void parsedLongsAndFiniteFloatingValuesAreComparedWithoutRoundingOrTolerance() {
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("9007199254740992"),
                JsonParser.parseString("9007199254740993")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("9223372036854775806"),
                JsonParser.parseString("9223372036854775807")));
        assertFalse(ExactJsonValues.equal(JsonParser.parseString("1.0"),
                JsonParser.parseString("1.0000000000000002")));
        assertTrue(ExactJsonValues.equal(JsonParser.parseString("1000.0"), JsonParser.parseString("1e3")));
        assertFalse(ExactJsonValues.equal(new JsonPrimitive(Double.NaN), new JsonPrimitive(Double.NaN)));
    }
}
