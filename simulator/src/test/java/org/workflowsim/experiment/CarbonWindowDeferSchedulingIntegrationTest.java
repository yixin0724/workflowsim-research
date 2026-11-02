package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * C1（2026-11-02）：碳感知延后引擎的行为契约。
 *
 * <p>引擎把就绪批次压后到"绿色功率严格更高"的下一个窗口再执行（每个作业至多一次）。
 * 本测试用<b>手推场景</b>锚定三件事：压后真的发生（作业起点落在干净窗口内）、
 * 预算碳超额真的下降（对照碳无关的 FCFS）、无窗口声明时行为不漂移。</p>
 *
 * <p>期望值来源：{@code CARBON_DIMENSION_DESIGN.md} §2.3 的预算模型定义
 * （excess = ∫max(0, P_fleet(t) − green(t))dt）+ 本测试显式声明的窗口与功率。</p>
 */
class CarbonWindowDeferSchedulingIntegrationTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void deferralMovesEveryJobStartIntoTheCleanerWindow() throws Exception {
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);

        assertEquals(
                2,
                deferred.getMetrics().getSuccessfulComputeJobOutcomeCount(),
                "both tasks must still complete (deferral must not starve the workflow)");
        for (SimulationReport.JobOutcome job : deferred.getJobs()) {
            if (job.getClassType() != 2) {
                continue;
            }
            assertTrue(
                    job.getStartTime() >= 100.0,
                    "the dirty window [0,100) must be skipped: job " + job.getJobId() + " started at "
                            + job.getStartTime());
        }
        assertTrue(deferred.getMakespan() >= 100.0, "the makespan must span the skipped dirty window");
    }

    @Test
    void deferralLowersCarbonExcessAgainstTheCarbonAgnosticBaseline() throws Exception {
        SimulationReport baseline = run(SchedulingAlgorithm.FCFS, true);
        SimulationReport deferred = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, true);

        double baselineExcess = baseline.getCarbonAccounting().getTotalCarbonExcessWattSeconds();
        double deferredExcess = deferred.getCarbonAccounting().getTotalCarbonExcessWattSeconds();
        assertTrue(baselineExcess > 0.0, "the carbon-agnostic run must incur excess in the dirty window");
        assertEquals(
                0.0,
                deferredExcess,
                "running inside the 400 W green window at 300 W active power must leave zero excess" + " (baseline was "
                        + baselineExcess + ")");
    }

    @Test
    void withoutDeclaredWindowsTheEngineBehavesLikePlainFcfs() throws Exception {
        SimulationReport plain = run(SchedulingAlgorithm.FCFS, false);
        SimulationReport engine = run(SchedulingAlgorithm.CARBON_WINDOW_DEFER, false);

        assertEquals(
                0,
                Double.compare(plain.getMakespan(), engine.getMakespan()),
                "with no window table the engine must not defer: makespan " + plain.getMakespan() + " vs "
                        + engine.getMakespan());
        assertNull(
                engine.getCarbonAccounting(),
                "with no window table there is no budget accounting (absence is legal, not zero excess)");
    }

    /** 运行夹具：单 VM（idle 100 / max 300），窗口 [0,100)=120W 脏、[100,400)=400W 干净。 */
    private SimulationReport run(SchedulingAlgorithm algorithm, boolean declareWindows) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "defer-" + algorithm + ".dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261102L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-defer-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        1000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        100.0,
                        300.0,
                        null))
                .pinVmToHost(0, 0);
        if (declareWindows) {
            builder.carbonWindow(0.0, 100.0, 120.0);
            builder.carbonWindow(100.0, 400.0, 400.0);
        }
        return new SimulationRunner().run(config, builder.build());
    }
}
