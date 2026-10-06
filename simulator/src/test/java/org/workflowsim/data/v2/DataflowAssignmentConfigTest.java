package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/** NF005 opt-in values and strict JSON decoding, without running a simulator. */
class DataflowAssignmentConfigTest {
    private static final String MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1";
    private static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";

    @Test
    void defaultOffIsASingletonWithNoPolicy() {
        DataflowAssignmentConfig off = DataflowAssignmentConfig.off();

        assertSame(off, DataflowAssignmentConfig.off());
        assertEquals(DataflowAssignmentConfig.Mode.OFF, off.getMode());
        assertFalse(off.isEnabled());
        assertNull(off.getPolicy());
    }

    @Test
    void explicitOnlineNominalIsASingletonWithExactlyTheSupportedConstants() {
        DataflowAssignmentConfig online = DataflowAssignmentConfig.onlineNominal();

        assertSame(online, DataflowAssignmentConfig.onlineNominal());
        assertTrue(online.isEnabled());
        assertEquals(DataflowAssignmentConfig.Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1,
                online.getMode());
        assertEquals(MODE, online.getMode().name());
        assertEquals(POLICY, DataflowAssignmentConfig.POLICY);
        assertEquals(POLICY, online.getPolicy());
        assertArrayEquals(new DataflowAssignmentConfig.Mode[] {
                DataflowAssignmentConfig.Mode.OFF,
                DataflowAssignmentConfig.Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1
        }, DataflowAssignmentConfig.Mode.values());
        assertFalse(DataflowAssignmentConfig.off().isEnabled());
    }

    @Test
    void exactValuesHaveConsistentEqualityAndHashCodes() {
        DataflowAssignmentConfig off = DataflowAssignmentConfig.off();
        DataflowAssignmentConfig online = DataflowAssignmentConfig.onlineNominal();
        DataflowAssignmentConfig decoded = DataflowAssignmentConfig.decodeConfig(enabledJson());
        DataflowAssignmentConfig decodedAgain = DataflowAssignmentConfig.decodeConfig(enabledJson());

        assertEquals(off, off);
        assertEquals(off, DataflowAssignmentConfig.off());
        assertEquals(off.hashCode(), DataflowAssignmentConfig.off().hashCode());
        assertEquals(online, online);
        assertEquals(online, decoded);
        assertEquals(decoded, online);
        assertEquals(decoded, decodedAgain);
        assertEquals(online, decodedAgain);
        assertEquals(online.hashCode(), decoded.hashCode());
        assertEquals(decoded.hashCode(), decodedAgain.hashCode());
        assertNotEquals(off, online);
        assertNotEquals(online, off);
        for (DataflowAssignmentConfig value : new DataflowAssignmentConfig[] {off, online}) {
            assertFalse(value.equals(null));
            assertFalse(value.equals(value.getMode()));
            assertFalse(value.equals(MODE));
        }
    }

    @Test
    void configurationHasOnlyPrivateImmutableInstanceState() {
        assertTrue(Modifier.isFinal(DataflowAssignmentConfig.class.getModifiers()));
        for (Constructor<?> constructor : DataflowAssignmentConfig.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        }
        for (Field field : DataflowAssignmentConfig.class.getDeclaredFields()) {
            if (field.isSynthetic()) {
                continue;
            }
            assertTrue(Modifier.isFinal(field.getModifiers()), field.getName());
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isPrivate(field.getModifiers()), field.getName());
                assertEquals(DataflowAssignmentConfig.Mode.class, field.getType(), field.getName());
            }
        }
    }

    @Test
    void decoderAcceptsTheExactEnabledObjectInEitherKeyOrderWithoutModifyingIt() {
        JsonObject input = enabledJson();
        JsonObject before = input.deepCopy();

        assertSame(DataflowAssignmentConfig.onlineNominal(),
                DataflowAssignmentConfig.decodeConfig(input));
        assertEquals(before, input);

        JsonObject reversed = new JsonObject();
        reversed.addProperty("policy", POLICY);
        reversed.addProperty("mode", MODE);
        assertSame(DataflowAssignmentConfig.onlineNominal(),
                DataflowAssignmentConfig.decodeConfig(reversed));
    }

    @Test
    void decoderRejectsJavaNullAndEveryNonObjectJsonShape() {
        assertRejected(null);
        for (String json : new String[] {
                "null", "true", "false", "0", "-1", "1.5", "\"\"", "\"OFF\"",
                "\"" + MODE + "\"", "\"" + POLICY + "\"", "\"{}\"",
                "[]", "[\"" + MODE + "\"]", "[" + enabledJson() + "]"
        }) {
            assertRejected(JsonParser.parseString(json));
        }
    }

    @Test
    void decoderRejectsMissingAndExtraKeys() {
        assertRejected(new JsonObject());
        for (String key : new String[] {"mode", "policy"}) {
            JsonObject missing = enabledJson();
            missing.remove(key);
            assertRejected(missing);
        }
        for (String extraKey : new String[] {"extra", "enabled", "maxTraceRecords", "Mode", "Policy", ""}) {
            JsonObject extra = enabledJson();
            extra.add(extraKey, null);
            assertRejected(extra);
        }
    }

    @Test
    void decoderRejectsUnknownOrCaseChangedKeysEvenWhenThereAreExactlyTwo() {
        for (String[] replacement : new String[][] {
                {"mode", "Mode"}, {"mode", "MODE"}, {"mode", "mode "},
                {"mode", " mode"}, {"mode", "assignmentMode"}, {"mode", ""},
                {"policy", "Policy"}, {"policy", "POLICY"}, {"policy", "policy "},
                {"policy", " policy"}, {"policy", "assignmentPolicy"}, {"policy", ""}
        }) {
            JsonObject input = enabledJson();
            input.add(replacement[1], input.remove(replacement[0]));
            assertEquals(2, input.size());
            assertRejected(input);
        }
    }

    @Test
    void decoderRequiresStrictStringsForBothFieldsWithoutJsonCoercion() {
        for (String key : new String[] {"mode", "policy"}) {
            String supportedValue = enabledJson().get(key).getAsString();
            for (String json : new String[] {
                    "null", "true", "false", "0", "1", "-1", "1.5", "1e3", "[]", "{}",
                    "[\"" + supportedValue + "\"]",
                    "{\"value\":\"" + supportedValue + "\"}"
            }) {
                JsonObject input = enabledJson();
                input.add(key, JsonParser.parseString(json));
                assertRejected(input);
            }
        }
    }

    @Test
    void decoderRejectsOffObjectsInsteadOfInferringDefaultOmission() {
        JsonObject off = enabledJson();
        off.addProperty("mode", "OFF");
        assertRejected(off);
        off.add("policy", null);
        assertRejected(off);
        off.remove("policy");
        assertRejected(off);
    }

    @Test
    void decoderRejectsUnknownBlankCaseChangedOrPaddedModes() {
        for (String mode : new String[] {
                "OFF", "off", "UNKNOWN", "ONLINE", "", " ", "\t\n", "1", POLICY,
                "control_ready_online_assignment_v1", "Control_Ready_Online_Assignment_V1",
                "CONTROL_READY_ONLINE_ASSIGNMENT_V2", " " + MODE, MODE + " "
        }) {
            JsonObject input = enabledJson();
            input.addProperty("mode", mode);
            assertRejected(input);
        }
    }

    @Test
    void decoderRejectsUnknownBlankCaseChangedOrPaddedPolicies() {
        for (String policy : new String[] {
                "OFF", "UNKNOWN", "", " ", "\t\n", "1", MODE,
                "nominal_input_earliest_reservation_v1", "Nominal_Input_Earliest_Reservation_V1",
                "NOMINAL_INPUT_EARLIEST_RESERVATION_V2", " " + POLICY, POLICY + " "
        }) {
            JsonObject input = enabledJson();
            input.addProperty("policy", policy);
            assertRejected(input);
        }
    }

    @Test
    void callerJsonMutationCannotChangeTheDecodedConfiguration() {
        JsonObject input = enabledJson();
        DataflowAssignmentConfig decoded = DataflowAssignmentConfig.decodeConfig(input);
        int originalHash = decoded.hashCode();

        input.addProperty("mode", "OFF");
        input.addProperty("policy", "changed");
        input.addProperty("maxTraceRecords", 1);
        input.remove("mode");

        assertTrue(decoded.isEnabled());
        assertEquals(DataflowAssignmentConfig.Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1,
                decoded.getMode());
        assertEquals(POLICY, decoded.getPolicy());
        assertEquals(originalHash, decoded.hashCode());
        assertSame(DataflowAssignmentConfig.onlineNominal(), decoded);
        assertSame(decoded, DataflowAssignmentConfig.decodeConfig(enabledJson()));
        assertRejected(input);
        assertFalse(DataflowAssignmentConfig.off().isEnabled());
        assertNull(DataflowAssignmentConfig.off().getPolicy());
    }

    private static JsonObject enabledJson() {
        JsonObject object = new JsonObject();
        object.addProperty("mode", MODE);
        object.addProperty("policy", POLICY);
        return object;
    }

    private static void assertRejected(JsonElement element) {
        assertThrows(IllegalArgumentException.class,
                () -> DataflowAssignmentConfig.decodeConfig(element), String.valueOf(element));
    }
}
