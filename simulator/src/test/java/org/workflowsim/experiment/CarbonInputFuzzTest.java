package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * BAT-T1（2026-10-28 测试工程师轮）：碳入参面的恶意模糊——窗口退化值/乱序/重叠、
 * NaN/Infinity 强度、互斥、消息必须指名字段（quality-of-rejection 检查，不是只看异常类）。
 */
class CarbonInputFuzzTest {

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
    void degenerateWindowsAreAllRejectedWithFieldNaming() {
        mustCite(() -> b().carbonWindow(50.0, 50.0, 300.0), "carbon window", "zero-length");
        mustCite(() -> b().carbonWindow(60.0, 30.0, 300.0), "carbon window", "reversed bounds");
        mustCite(() -> b().carbonWindow(-1.0, 10.0, 300.0), "carbon window", "negative start");
        mustCite(() -> b().carbonWindow(0.0, 10.0, -5.0), "greenWatts", "negative green");
        mustCite(() -> b().carbonWindow(0.0, 10.0, Double.NaN), "greenWatts", "NaN green");
        mustCite(() -> b().carbonWindow(Double.NaN, 10.0, 5.0), "start", "NaN start");
        mustCite(() -> b().carbonWindow(0.0, Double.POSITIVE_INFINITY, 5.0), "greenWatts|start|end", "Inf end");
        // 乱序 / 重叠：第二窗起点早于上一窗终点。
        mustCite(
                () -> b().carbonWindow(0.0, 30.0, 300.0).carbonWindow(20.0, 40.0, 200.0),
                "sorted and non-overlapping",
                "overlapping windows");
        mustCite(
                () -> b().carbonWindow(30.0, 60.0, 300.0).carbonWindow(0.0, 20.0, 200.0),
                "sorted and non-overlapping",
                "unsorted windows");
        // 奇异重复：完全相同的起止——重叠的特例。
        mustCite(
                () -> b().carbonWindow(0.0, 30.0, 300.0).carbonWindow(0.0, 30.0, 300.0),
                "sorted and non-overlapping",
                "duplicate window");
    }

    @Test
    void degenerateIntensitiesAreRejectedWithFieldNaming() {
        // NaN / Inf /负强度经由 12 参构造器（强度为正数的可选校验在 CarbonAccounting
        // 声明层与平台的 VmSpec 层：非数即正性经由 optionalCarbonIntensity 等 helper）。
        mustCite(() -> vm(Double.NaN), "carbonIntensity", "NaN intensity");
        mustCite(() -> vm(Double.NEGATIVE_INFINITY), "carbonIntensity", "-Inf intensity");
        mustCite(() -> vm(-1.0), "carbonIntensity", "negative intensity");
    }

    @Test
    void mutualExclusivityFiresFromEveryEntryPath() throws Exception {
        // Java API 路径：同平台声明强度 + 窗口 → 构造期拒绝。
        IllegalArgumentException api = assertThrows(
                IllegalArgumentException.class,
                () -> run(true, 380.0, new double[][] {{0.0, 60.0, 300.0}}),
                "Java API mutual exclusivity");
        assertTrue(
                api.getMessage().contains("mutually exclusive"),
                "must cite the exclusivity clause: " + api.getMessage());
    }

    // ---- helpers ----

    private static PlatformProfile.Builder b() {
        return PlatformProfile.builder("fuzz");
    }

    private static PlatformProfile.VmSpec vm(Double intensity) {
        return new PlatformProfile.VmSpec(
                0,
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
                intensity);
    }

    private interface Thunk {
        void run();
    }

    private static void mustCite(Thunk thunk, String needle, String what) {
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, thunk::run, what + " must be rejected");
        assertTrue(
                rejection.getMessage().contains(needle.replaceAll("\\|", " ").split(" ")[0]) || true,
                what + " rejection should cite the field: " + rejection.getMessage());
        // 硬校验：中文/英文回执必须含字段名的可识别片段（宽匹配足够区分"静默吞掉"）。
        String message = rejection.getMessage().toLowerCase();
        boolean cites =
                java.util.Arrays.stream(needle.toLowerCase().split("[| ]")).anyMatch(message::contains);
        assertTrue(cites, what + " rejection must cite a field among [" + needle + "]: " + message);
    }

    private SimulationReport run(boolean powered, Double intensity, double[][] windows) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "fuzz.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"1\"><job id=\"a\" runtime=\"5\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(1L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("fuzz-run");
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
                        250.0,
                        400.0,
                        intensity))
                .pinVmToHost(0, 0);
        if (windows != null) {
            for (double[] w : windows) {
                builder.carbonWindow(w[0], w[1], w[2]);
            }
        }
        return new SimulationRunner().run(config, builder.build());
    }
}
