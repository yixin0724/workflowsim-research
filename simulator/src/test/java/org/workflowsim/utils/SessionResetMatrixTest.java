package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * C01+C02：会话状态矩阵与休眠全局围栏（审查工程 Phase 1 机械可靠性）。
 *
 * <p>契约（守恒律）：同一 JVM 串行执行两次标准 {@link SimulationRunner} 全流程后，
 * 全部可变静态字段构成的状态必须与<b>第一循环结束时刻完全一致</b>——任何跨 run
 * 累积/残留都是 campaign 研究执行器的静默污染源（H-1/H-2 类陷阱的机械检测器）。</p>
 *
 * <p>围栏（F-002/F-003）：标准 runner 声明的模型范围不触碰
 * {@code CloudSim.NetworkTopology}（Fat-tree 为自研独立实现）与
 * {@code Consts.MILLION}、{@code Parameters.SOURCE}——这些字段在两次运行前后
 * 必须保持<b>初始值</b>。</p>
 *
 * <p>审计类清单与字段全集来自 review-workspace/STATIC-FIELDS.md（机械扫描产出）；
 * 快照值用确定性深度表示（Map 排序、identity 兜底），仅用于相等性判定。</p>
 */
class SessionResetMatrixTest {

    /** 审计范围内全部含可变静态字段的类。 */
    private static final List<String> AUDIT_CLASSES = Arrays.asList(
            "org.workflowsim.utils.Parameters",
            "org.cloudbus.cloudsim.core.CloudSim",
            "org.workflowsim.failure.FailureParameters",
            "org.workflowsim.failure.FailureMonitor",
            "org.cloudbus.cloudsim.NetworkTopology",
            "org.workflowsim.utils.ReplicaCatalog",
            "org.cloudbus.cloudsim.Log",
            "org.workflowsim.rl.RlPolicyRegistry",
            "org.workflowsim.utils.SimulationRandom",
            "org.workflowsim.utils.DistributionGenerator",
            "org.workflowsim.utils.SimulationSession",
            "org.cloudbus.cloudsim.Consts");

    /** 必须保持初始值（永不触碰）的类.字段。 */
    private static final List<String> VIRGIN_FENCE = Arrays.asList(
            "org.cloudbus.cloudsim.NetworkTopology.*",
            "org.cloudbus.cloudsim.Consts.MILLION",
            "org.workflowsim.utils.Parameters.SOURCE");

    @TempDir
    Path tempDir;

    @Test
    void consecutiveRunsLeaveStableStateAndDormantGlobalsVirgin() throws Exception {
        boolean logWasDisabled = Log.isDisabled();
        Log.disable();
        try {
            Path workflow = minimalChainDax();
            Map<String, String> virgin = snapshot();
            SimulationReport first = runOnce(workflow, SchedulingAlgorithm.FCFS);
            Map<String, String> afterFirst = snapshot();
            SimulationReport second = runOnce(workflow, SchedulingAlgorithm.READY_BATCH_MCT);
            Map<String, String> afterSecond = snapshot();

            assertTrue(first.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                    "第一轮（FCFS）仿真必须完整成功，状态审计才有意义");
            assertTrue(second.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                    "第二轮（MCT）仿真必须完整成功，状态审计才有意义");

            // 泄漏检测：两个完整生命周期后，状态与第一循环结束时刻逐字段一致。
            List<String> unstable = diff("afterFirst", afterFirst, "afterSecond", afterSecond);
            assertTrue(unstable.isEmpty(),
                    "连续两次标准运行后可变静态状态发生漂移（跨 run 泄漏）：\n"
                            + String.join("\n", unstable));

            // 围栏检测：休眠字段必须从未被触碰（与最初基线一致）。
            List<String> fenceBroken = diffViolations(virgin, afterSecond);
            assertTrue(fenceBroken.isEmpty(),
                    "标准 runner 触碰了声明范围外的休眠全局（F-002/F-003 类陷阱）：\n"
                            + String.join("\n", fenceBroken));
        } finally {
            Log.setDisabled(logWasDisabled);
        }
    }

    private SimulationReport runOnce(Path workflow, SchedulingAlgorithm algorithm)
            throws org.workflowsim.exception.SimulationExecutionException {
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(algorithm)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.legacyWorkflowsimV1())
                .costModel(Parameters.CostModel.DATACENTER)
                .randomSeed(42L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("reset-matrix", 2);
        return new SimulationRunner().run(config, platform);
    }

    /** 4 任务纯链 DAX（runtime 100 秒 ×2，无文件）——最小标准合法输入。 */
    private Path minimalChainDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        for (int id = 1; id <= 4; id++) {
            dax.append("  <job id=\"t").append(id).append("\" name=\"chain\" runtime=\"100\"/>\n");
        }
        for (int id = 2; id <= 4; id++) {
            dax.append("  <child ref=\"t").append(id).append("\">\n");
            dax.append("    <parent ref=\"t").append(id - 1).append("\"/>\n");
            dax.append("  </child>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("minimal-chain.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    // ---- 状态快照与比较 ----

    /** 类.字段 → 确定性深度表示。 */
    private static Map<String, String> snapshot() throws Exception {
        Map<String, String> state = new TreeMap<String, String>();
        for (String className : AUDIT_CLASSES) {
            Class<?> type = Class.forName(className);
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                // 排除 JaCoCo 等织入的合成字段：仪表探针不是产品的声明状态。
                if (field.isSynthetic() || field.getName().startsWith("$")) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(null);
                state.put(className + "." + field.getName(), canonical(value, 0));
            }
        }
        return state;
    }

    /** 确定性表示：null/原始值直书；集合保序；Map 键排序；对象 identity 兜底；深度封顶。 */
    private static String canonical(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (depth > 3) {
            return shortIdentity(value);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum) {
            return value.getClass().getSimpleName() + ":" + String.valueOf(value);
        }
        if (value.getClass().isArray()) {
            Object[] array = (Object[]) wrapArray(value);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(canonical(array[i], depth + 1));
            }
            return sb.append("]").toString();
        }
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder sb = new StringBuilder("{");
            for (Object key : new TreeMap<Object, Object>((Map<Object, Object>) map).keySet()) {
                sb.append(canonical(key, depth + 1)).append("=")
                        .append(canonical(map.get(key), depth + 1)).append(",");
            }
            return sb.append("}").toString();
        }
        if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            StringBuilder sb = new StringBuilder("[");
            for (Object item : collection) {
                sb.append(canonical(item, depth + 1)).append(",");
            }
            return sb.append("]").toString();
        }
        return shortIdentity(value);
    }

    private static Object wrapArray(Object array) {
        if (array instanceof Object[]) {
            return array;
        }
        int length = java.lang.reflect.Array.getLength(array);
        Object[] boxed = new Object[length];
        for (int i = 0; i < length; i++) {
            boxed[i] = java.lang.reflect.Array.get(array, i);
        }
        return boxed;
    }

    private static String shortIdentity(Object value) {
        return value.getClass().getName() + "@" + System.identityHashCode(value);
    }

    /** 抽查所有 snapshot 键的不同项。 */
    private static List<String> diff(String leftLabel, Map<String, String> left,
            String rightLabel, Map<String, String> right) {
        List<String> diffs = new ArrayList<String>();
        for (String key : new TreeMap<String, String>(right).keySet()) {
            String before = left.get(key);
            String after = right.get(key);
            if (before == null || !before.equals(after)) {
                diffs.add(String.format("  %s: [%s]=%s → [%s]=%s", key, leftLabel, before,
                        rightLabel, after));
            }
        }
        return diffs;
    }

    /** 围栏校验：指定字段必须等于 virgin 基线（规则支持 Class.* 前缀或精确字段）。 */
    private static List<String> diffViolations(Map<String, String> virgin, Map<String, String> current) {
        List<String> violations = new ArrayList<String>();
        for (String rule : VIRGIN_FENCE) {
            boolean prefixRule = rule.endsWith(".*");
            String target = prefixRule ? rule.substring(0, rule.length() - 2) : rule;
            for (String key : current.keySet()) {
                boolean matches = prefixRule ? key.startsWith(target + ".") : key.equals(target);
                if (!matches) {
                    continue;
                }
                if (!virgin.get(key).equals(current.get(key))) {
                    violations.add(String.format("  %s: %s → %s", key, virgin.get(key),
                            current.get(key)));
                }
            }
        }
        return violations;
    }
}
