package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Optional retained-history compatibility, separate from the mandatory fresh-bundle rerun tests. */
class HistoricalEvidenceCompatibilityIntegrationTest {
    @Test
    void retainedClassicEvidenceRemainsReadableWithoutRetaggingItsModel() throws Exception {
        assertHistoricalBundle(false);
    }

    @Test
    void retainedSyntheticEvidenceRemainsReadableWithoutRetaggingItsModel() throws Exception {
        assertHistoricalBundle(true);
    }

    private static void assertHistoricalBundle(boolean synthetic) throws Exception {
        Path run = historicalRun(synthetic);
        // Keep the assumption inside each test so an ordinary checkout reports explicit skips.
        assumeTrue(run != null, "Optional retained R10 evidence is not present in this checkout");
        RerunEvidence evidence = RerunEvidenceReader.read(run);
        assertEquals("workflowsim-experiment-manifest-v4", evidence.getManifest().get("schema").getAsString());
        assertFalse(evidence.getManifest().getAsJsonObject("configuration").has("executionSemantics"),
                "retained historical evidence must not be silently relabelled as the corrected model");
        assertTrue(evidence.getEventCount() > 0);
    }

    private static Path historicalRun(boolean synthetic) throws Exception {
        String datasetRoot = System.getProperty("workflowsim.datasetRoot");
        if (datasetRoot == null) { return null; }
        Path checkout = Paths.get(datasetRoot).toAbsolutePath().normalize().getParent();
        if (checkout == null) { return null; }
        Path runs = checkout.resolve("output/network-study-r10-final/runs");
        if (!Files.isDirectory(runs)) { return null; }
        // Do not search ancestors: a clean-checkout copy must not borrow its parent's evidence.
        List<Path> directories;
        try (Stream<Path> stream = Files.list(runs)) {
            directories = stream.filter(Files::isDirectory).sorted().collect(Collectors.toList());
        }
        for (Path directory : directories) {
            if (directory.getFileName().toString().startsWith("layered-") == synthetic) { return directory; }
        }
        return null;
    }
}
