package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证不依赖 JVM 工作目录的 Maven 模块源码定位规则。 */
class ExperimentProvenanceTest {

    @Test
    void resolvesModuleRootForClassesAndReactorJar(@TempDir Path temporaryDirectory) throws Exception {
        Path module = Files.createDirectory(temporaryDirectory.resolve("simulator"));
        Files.write(module.resolve("pom.xml"), "<project/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path target = Files.createDirectory(module.resolve("target"));
        Path classes = Files.createDirectory(target.resolve("classes"));
        Path jar = Files.createFile(target.resolve("workflowsim-1.0.jar"));

        assertEquals(module.toAbsolutePath().normalize(),
                ExperimentProvenance.moduleRootForCodeSource(classes));
        assertEquals(module.toAbsolutePath().normalize(),
                ExperimentProvenance.moduleRootForCodeSource(jar));
    }

    @Test
    void doesNotInferAProjectRootForNonMavenCodeSources(@TempDir Path temporaryDirectory) throws Exception {
        Path standaloneJar = Files.createFile(temporaryDirectory.resolve("workflowsim-1.0.jar"));
        Path otherDirectory = Files.createDirectory(temporaryDirectory.resolve("classes"));

        assertNull(ExperimentProvenance.moduleRootForCodeSource(standaloneJar));
        assertNull(ExperimentProvenance.moduleRootForCodeSource(otherDirectory));
    }
}
