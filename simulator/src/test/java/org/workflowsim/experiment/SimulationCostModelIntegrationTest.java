package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationCostModelIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void datacenterAndExplicitVmPricingProduceDistinctAuditableCosts() throws Exception {
        PlatformProfile platform = pricedPlatform();
        SimulationConfig datacenter = config(Parameters.CostModel.DATACENTER);
        SimulationConfig vm = config(Parameters.CostModel.VM);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        SimulationReport datacenterReport = runner.run(datacenter, platform);
        SimulationReport vmReport = runner.run(vm, platform);

        assertEquals(datacenterReport.getJobs().size(), vmReport.getJobs().size());
        assertEquals(2, datacenterReport.getJobs().size(),
                "The fixture includes one model-generated stage-in Job and one compute Job");
        assertCostRate(datacenterReport, 2.0);
        assertCostRate(vmReport, 5.0);
    }

    @Test
    void vmCostModelRejectsAnUnpricedPlatformInsteadOfReportingZeroCost() throws Exception {
        SimulationConfig config = config(Parameters.CostModel.VM);

        assertThrows(SimulationConfigurationException.class, () -> new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("unpriced", 1)));
    }

    @Test
    void manifestRecordsVmPricesAndTheExplicitModeledCostScope(@org.junit.jupiter.api.io.TempDir Path output)
            throws Exception {
        Log.disable();
        SimulationReport report = new SimulationRunner().run(config(Parameters.CostModel.VM),
                pricedPlatform());
        Path manifest = output.resolve("priced.manifest.json");
        ExperimentManifestWriter.writeJson(report, manifest);

        String contents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(contents.contains("modeledProcessingCostScope"));
        assertTrue(contents.contains("ABSTRACT_COST_UNITS"));
        assertTrue(contents.contains("DECLARED_FILE_BYTES_ALL_JOB_FILE_ITEMS"));
        assertTrue(contents.contains("DECIMAL_MB_1_000_000_BYTES"));
        assertTrue(contents.contains("NO_INTERNAL_BILLING_ROUNDING"));
        assertTrue(contents.contains("MEMORY_AND_STORAGE_PRICES_ARE_DECLARED_BUT_NOT_CHARGED"));
        assertTrue(contents.contains("\"cpuPerSecond\": 5.0"));
        assertTrue(contents.contains("\"bandwidth\": 0.0"));
    }

    private static SimulationConfig config(Parameters.CostModel costModel) throws Exception {
        return SimulationConfig.builder(workflow(), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .costModel(costModel)
                .build();
    }

    private static PlatformProfile pricedPlatform() {
        PlatformProfile.CostSpec datacenterCosts = new PlatformProfile.CostSpec(2.0, 0.0, 0.0, 0.0);
        PlatformProfile.CostSpec vmCosts = new PlatformProfile.CostSpec(5.0, 0.0, 0.0, 0.0);
        return PlatformProfile.builder("priced-platform")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED, vmCosts))
                .costs(datacenterCosts)
                .build();
    }

    private static void assertCostRate(SimulationReport report, double cpuCostPerSecond) {
        double sum = 0.0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            assertEquals(job.getCpuTime() * cpuCostPerSecond,
                    job.getModeledProcessingCost(), 1.0e-12);
            sum += job.getModeledProcessingCost();
        }
        assertEquals(sum, report.getMetrics().getTotalModeledProcessingCost(), 1.0e-12);
    }

    private static String workflow() throws Exception {
        URL url = SimulationCostModelIntegrationTest.class.getResource(
                "/wfcommons/minimum-runtime.json");
        if (url == null) {
            throw new IllegalStateException("Missing WfCommons test fixture");
        }
        return Paths.get(url.toURI()).toString();
    }
}
