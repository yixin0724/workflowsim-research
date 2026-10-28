package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * BAT-H1/H2（2026-10-28 测试工程师第三轮）：预算族的规模化平价（32-64 VM 上的
 * 独立解析重演 + 守恒锁定），与三次失败重试窗跨界的并集碳核算。
 */
class CarbonScaleAndRetryTest {

    static final Path PROBE_DIR = Paths.get("target", "carbon-scale-probe");

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
    void budgetAt32VmsMatchesIndependentIntegration() throws Exception {
        // 32-VM 均匀舰队（ΔP 从首 VM 取 150——v1 均一假设在夹具里写死：250/400 每台）。
        SimulationReport report = run(32, null, new double[][] {{0.0, 400.0, 3000.0}, {400.0, 800.0, 1000.0}});
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertNotNull(carbon, "budget must account at scale");
        double expected = integrate(report.getJobs(), report.getMakespan(), 32 * 250.0, 150.0, new double[][] {
            {0.0, 400.0, 3000.0}, {400.0, 800.0, 1000.0}
        });
        assertEquals(
                expected, carbon.getTotalCarbonExcessWattSeconds(), 1e-6, "32-VM independent integration must match");
        // 全部克拉伯窗口和恒等
        double sum = 0.0;
        for (double v : carbon.getWindowExcessWattSeconds().values()) {
            sum += v;
        }
        assertEquals(sum, carbon.getTotalCarbonExcessWattSeconds(), 1e-9, "window sums == total at scale");
    }

    // ---- helpers ----

    /** 独立重演：与 CarbonAccounting 同公式但从 Job 列表二次推导（round-2 方法规模化）。 */
    private static double integrate(
            java.util.List<SimulationReport.JobOutcome> jobs,
            double makespan,
            double idleSum,
            double dyn,
            double[][] windows) {
        java.util.TreeMap<Double, Integer> ends = new java.util.TreeMap<Double, Integer>();
        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getClassType() != 2 || job.getFinishTime() <= job.getStartTime()) {
                continue;
            }
            bump(ends, job.getStartTime(), 1);
            bump(ends, job.getFinishTime(), -1);
        }
        java.util.List<double[]> segments = new java.util.ArrayList<double[]>();
        double prev = 0.0;
        double conc = 0.0;
        for (java.util.Map.Entry<Double, Integer> e : ends.entrySet()) {
            double t = e.getKey();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + conc * dyn});
            }
            prev = t;
            conc += e.getValue();
        }
        if (prev < makespan) {
            segments.add(new double[] {prev, makespan, idleSum + conc * dyn});
        }
        double cc = 0.0;
        for (double[] w : windows) {
            double we = Math.min(w[1], makespan); // 视界钳制（round-2 定性）
            for (double[] seg : segments) {
                double lo = Math.max(seg[0], w[0]);
                double hi = Math.min(seg[1], we);
                if (hi > lo) {
                    cc += Math.max(0.0, seg[2] - w[2]) * (hi - lo);
                }
            }
        }
        return cc;
    }

    private SimulationReport run(int vms, Double intensity, double[][] windows) throws Exception {
        Path input = tempDir.resolve("scale.dax");
        Files.write(
                input,
                ("<adag version=\"3.3\" count=\"6\">"
                                + "<job id=\"a\" runtime=\"20\"/><job id=\"b\" runtime=\"30\"/>"
                                + "<job id=\"c\" runtime=\"40\"/><job id=\"d\" runtime=\"15\"/>"
                                + "<job id=\"e\" runtime=\"25\"/><job id=\"f\" runtime=\"10\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(
                        input.toAbsolutePath().toString(), vms)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261028L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("scale-" + vms);
        for (int i = 0; i < vms; i++) {
            builder.addHost(new PlatformProfile.HostSpec(900 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            i,
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            250.0,
                            400.0,
                            intensity))
                    .pinVmToHost(i, 900 + i);
        }
        if (windows != null) {
            for (double[] w : windows) {
                builder.carbonWindow(w[0], w[1], w[2]);
            }
        }
        return new SimulationRunner().run(config, builder.build());
    }

    private static void bump(java.util.TreeMap<Double, Integer> map, double k, int d) {
        Double key = Double.valueOf(k);
        Integer cur = map.get(key);
        map.put(key, Integer.valueOf((cur == null ? 0 : cur.intValue()) + d));
    }
}
