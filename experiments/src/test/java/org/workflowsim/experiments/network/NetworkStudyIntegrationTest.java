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

    @Test void sensitivitySmokeStudyRunsAcrossHeterogeneityAxis(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("r13-study");
        Path index = NetworkStudyExecutor.execute("smoke", NetworkStudyPlan.StudyVariant.SENSITIVITY_R13,
                NetworkStudyTest.datasets(), output);
        assertEquals(30, NetworkStudyValidator.validate(index));
        String document = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        assertTrue(document.contains("sensitivity-response-r13-v1"));
        assertTrue(document.contains("-HET_STRONG-"));
        assertTrue(document.contains("-HET_MILD-"));
        assertTrue(document.contains("-HET_EXTREME-"));
        JsonObject plan = JsonParser.parseString(document).getAsJsonObject().getAsJsonObject("plan");
        assertEquals(5, plan.getAsJsonArray("conditions").size());
        assertEquals(30, plan.get("runCount").getAsInt());
        assertEquals(30, JsonParser.parseString(document).getAsJsonObject().getAsJsonArray("runs").size());
        String markdown = new String(Files.readAllBytes(output.resolve("results.md")), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("敏感性响应面研究"));
        assertTrue(markdown.contains("异构度"));
        // 篡改异构等级会被验证器捕获（条件与运行必须一致）
        JsonObject changed = JsonParser.parseString(document).getAsJsonObject();
        changed.getAsJsonArray("runs").get(0).getAsJsonObject().addProperty("heterogeneity", "HET_EXTREME");
        Files.write(index, changed.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        // 篡改异构平台的VM MIPS会被验证器捕获
        Files.write(index, document.getBytes(StandardCharsets.UTF_8));
        JsonObject original = JsonParser.parseString(document).getAsJsonObject();
        int hetRun = -1;
        for (int i = 0; i < original.getAsJsonArray("runs").size(); i++) {
            if (original.getAsJsonArray("runs").get(i).getAsJsonObject().get("runId").getAsString().contains("-HET_STRONG-")) {
                hetRun = i; break;
            }
        }
        assertTrue(hetRun >= 0);
        Path manifest = output.resolve(original.getAsJsonArray("runs").get(hetRun).getAsJsonObject()
                .get("manifest").getAsString());
        String originalManifest = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        JsonObject forged = JsonParser.parseString(originalManifest).getAsJsonObject();
        forged.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject().addProperty("mips", 1000.0);
        Files.write(manifest, forged.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> NetworkStudyValidator.validate(index));
        Files.write(manifest, originalManifest.getBytes(StandardCharsets.UTF_8));
        assertEquals(30, NetworkStudyValidator.validate(index));
    }

    @Test void peftComparisonSmokeStudyRunsOnFrozenR10Matrix(@TempDir Path temp) throws Exception {
        Path output = temp.resolve("peft-study");
        Path index = NetworkStudyExecutor.execute("smoke", true, NetworkStudyTest.datasets(), output);
        assertEquals(18, NetworkStudyValidator.validate(index));
        String document = new String(Files.readAllBytes(index), StandardCharsets.UTF_8);
        assertTrue(document.contains("peft-comparison-r12-v1"));
        assertTrue(document.contains("LOCAL_PEFT"));
        assertFalse(document.contains("\"RANDOM\""));
        assertFalse(document.contains("\"PSO\""));
        String markdown = new String(Files.readAllBytes(output.resolve("results.md")), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("PEFT 对比研究"));
        assertTrue(markdown.contains("VM同构"));
        JsonObject plan = JsonParser.parseString(document).getAsJsonObject().getAsJsonObject("plan");
        assertEquals(3, plan.getAsJsonArray("planners").size());
        assertEquals(18, plan.get("runCount").getAsInt());
    }
}
