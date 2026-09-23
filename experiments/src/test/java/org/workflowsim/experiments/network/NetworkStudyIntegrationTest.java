package org.workflowsim.experiments.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NetworkStudyIntegrationTest {
    @Test void smallStudyRunsEvidenceValidationAndIndependentSummaryChecks(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("study");
        Path index = NetworkStudyExecutor.execute("smoke", NetworkStudyTest.datasets(), output);
        assertEquals(36, NetworkStudyValidator.validate(index));
        assertTrue(Files.size(output.resolve("results.md")) > 1000);
        assertThrows(java.io.IOException.class, () -> NetworkStudyExecutor.execute("smoke", NetworkStudyTest.datasets(), output));
        String original = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        JsonObject changed = JsonParser.parseString(original).getAsJsonObject();
        changed.getAsJsonObject("summary").addProperty("runCount", 35);
        Files.write(index, changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        changed = JsonParser.parseString(original).getAsJsonObject();
        changed.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("makespanSeconds", 0);
        Files.write(index, changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        changed = JsonParser.parseString(original).getAsJsonObject();
        changed.getAsJsonArray("runs").remove(0);
        Files.write(index, changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        Files.write(index, original.getBytes(StandardCharsets.UTF_8));
        JsonObject wrongPopulation = JsonParser.parseString(original).getAsJsonObject();
        wrongPopulation.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("population", "SYNTHETIC");
        Files.write(index, wrongPopulation.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        Files.write(index, original.getBytes(StandardCharsets.UTF_8));
        JsonObject document = JsonParser.parseString(original).getAsJsonObject();
        Path manifest = output.resolve(document.getAsJsonArray("runs").get(0).getAsJsonObject().get("manifest").getAsString());
        String originalManifest = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        JsonObject forged = JsonParser.parseString(originalManifest).getAsJsonObject();
        forged.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("sha256", "0000000000000000000000000000000000000000000000000000000000000000");
        Files.write(manifest, forged.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        forged = JsonParser.parseString(originalManifest).getAsJsonObject();
        forged.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject().addProperty("mips", 999.0);
        Files.write(manifest, forged.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        Files.write(manifest, originalManifest.getBytes(StandardCharsets.UTF_8));
        assertEquals(36, NetworkStudyValidator.validate(index));
    }
}
