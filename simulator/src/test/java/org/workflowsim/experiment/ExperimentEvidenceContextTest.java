package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ExperimentEvidenceContext} 研究身份契约的全分支测试：字段必填与
 * 去空白、数据集根的绝对性/存在性校验、协议文件可用性对 sha256 指纹的
 * 影响、asMap 输出形状。
 */
class ExperimentEvidenceContextTest {

    @TempDir
    Path datasetRoot;

    private ExperimentEvidenceContext.Builder fullBuilder() {
        return ExperimentEvidenceContext.builder("study-x")
                .artifact("org.workflowsim", "driver", "1.0")
                .driver(ExperimentEvidenceContextTest.class, "src/test/java")
                .protocol("protocol-1", null)
                .datasetRoot(datasetRoot);
    }

    @Test
    void builderRejectsBlankOrMissingIdentityFields() {
        assertThrows(IllegalArgumentException.class,
                () -> ExperimentEvidenceContext.builder(null));
        assertThrows(IllegalArgumentException.class,
                () -> ExperimentEvidenceContext.builder("   "));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().artifact(null, "a", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().artifact("g", "", "1.0"));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().artifact("g", "a", " "));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().driver(null, "src"));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().driver(String.class, ""));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().protocol(null, null));
    }

    @Test
    void buildRejectsPartiallyConfiguredContext() {
        // 缺 artifact。
        IllegalStateException noArtifact = assertThrows(IllegalStateException.class,
                () -> ExperimentEvidenceContext.builder("study-x")
                        .driver(ExperimentEvidenceContextTest.class, "src")
                        .protocol("p", null)
                        .datasetRoot(datasetRoot)
                        .build());
        assertTrue(noArtifact.getMessage().contains("required"), noArtifact.getMessage());
        // 缺 datasetRoot。
        assertThrows(IllegalStateException.class,
                () -> ExperimentEvidenceContext.builder("study-x")
                        .artifact("g", "a", "1.0")
                        .driver(ExperimentEvidenceContextTest.class, "src")
                        .protocol("p", null)
                        .build());
    }

    @Test
    void datasetRootMustBeAbsoluteAndExist() {
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().datasetRoot(null));
        assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().datasetRoot(java.nio.file.Paths.get("relative", "root")));
        Path missing = datasetRoot.resolve("not-here");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fullBuilder().datasetRoot(missing));
        assertTrue(ex.getMessage().contains("not a directory"), ex.getMessage());
    }

    @Test
    void identityFieldsAreTrimmedAndNormalized() {
        ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("  study-x  ")
                .artifact(" g ", " a ", " 1.0 ")
                .driver(ExperimentEvidenceContextTest.class, " src ")
                .protocol(" proto ", null)
                .datasetRoot(datasetRoot)
                .build();
        Map<String, Object> values = context.asMap();
        assertEquals("study-x", values.get("id"));
        assertNotNull(values.get("component"));
    }

    @Test
    void protocolFingerprintReflectsFileAvailability() throws IOException {
        // 协议文件缺失 → available=false、sha256=null。
        Map<String, Object> missing = fullBuilder().build().asMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> missingProtocol = (Map<String, Object>) missing.get("protocol");
        assertEquals("protocol-1", missingProtocol.get("logicalId"));
        assertEquals(Boolean.FALSE, missingProtocol.get("available"));
        assertNull(missingProtocol.get("sha256"));

        // 目录路径不是常规文件 → 同样视为不可用。
        Map<String, Object> directory = fullBuilder()
                .protocol("protocol-1", datasetRoot)
                .build().asMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> directoryProtocol = (Map<String, Object>) directory.get("protocol");
        assertEquals(Boolean.FALSE, directoryProtocol.get("available"));

        // 真实协议文件 → available=true、sha256 为 64 位十六进制。
        Path protocolFile = datasetRoot.resolve("PROTOCOL.md");
        Files.write(protocolFile, "# protocol".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> present = fullBuilder()
                .protocol("protocol-1", protocolFile)
                .build().asMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> presentProtocol = (Map<String, Object>) present.get("protocol");
        assertEquals(Boolean.TRUE, presentProtocol.get("available"));
        String sha256 = (String) presentProtocol.get("sha256");
        assertNotNull(sha256);
        assertTrue(sha256.matches("[0-9a-f]{64}"), sha256);
    }

    @Test
    void datasetSectionDeclaresExplicitAbsoluteResolutionPolicy() {
        Map<String, Object> values = fullBuilder().build().asMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> dataset = (Map<String, Object>) values.get("dataset");
        assertEquals(datasetRoot.toAbsolutePath().normalize().toString(), dataset.get("root"));
        assertEquals("EXPLICIT_ABSOLUTE_DATASET_ROOT", dataset.get("resolutionPolicy"));
    }
}
