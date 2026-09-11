package org.workflowsim.examples;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.Job;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 历史教学命令行示例共享的输入、随机性和结果边界约定。
 *
 * <p>该类只服务于 {@code experiments} 中保留的历史 API 示例。它不改变
 * {@code SimulationRunner} 的算法契约，也不将这些示例提升为可复现实验 campaign。</p>
 */
public final class ExampleCliSupport {

    /** 已通过严格 DAX 输入契约并适合历史单工作流教学示例的默认输入。 */
    public static final String DEFAULT_DAX_PATH =
            "datasets/dax/epigenomics/n100/Epigenomics_100.dax";

    /** 多工作流历史示例使用的三个严格输入候选。 */
    public static final List<String> DEFAULT_MULTIPLE_DAX_PATHS = Collections.unmodifiableList(Arrays.asList(
            "datasets/dax/epigenomics/n24/Epigenomics_24.dax",
            "datasets/dax/epigenomics/n46/Epigenomics_46.dax",
            DEFAULT_DAX_PATH));

    /** 历史教学情景的固定根种子；正式研究实验仍必须显式记录自己的种子计划。 */
    public static final long LEGACY_DEMO_SEED = 20260901L;

    /**
     * Epigenomics n100 历史容错教学情景使用的 Weibull 到达间隔尺度。
     *
     * <p>该值仅用于让旧示例在有限时间内展示低频故障和重试，不是由真实平台故障记录
     * 校准得到的参数。</p>
     */
    public static final double LEGACY_DEMO_FAILURE_INTERVAL_SCALE = 7500.0;

    /**
     * 按 DAG 层级生成失效流的遗留重聚类示例使用的更稀疏教学尺度。
     *
     * <p>各层持有独立随机流，不能直接复用单流示例的尺度；该值仅保证默认教学情景有界，
     * 不表示真实失效过程。</p>
     */
    public static final double LEGACY_CLUSTERING_DEMO_FAILURE_INTERVAL_SCALE = 50000.0;

    /** 统一机器可读的成功边界前缀。 */
    public static final String COMPLETION_PREFIX = "EXAMPLE_COMPLETED";

    private static final ThreadLocal<String> activeExampleId = new ThreadLocal<String>();

    private ExampleCliSupport() {
    }

    /**
     * 开始一个历史教学场景，清理上一场景可能遗留的 WorkflowSim 静态状态并安装固定种子。
     *
     * <p>命令行 smoke 仍会在独立 JVM 中运行每个示例；这里的清理仅为同 JVM 的人工调用
     * 提供明确边界，不能替代进程隔离。</p>
     *
     * @param exampleId catalog 中稳定且非空的示例标识
     */
    public static void begin(String exampleId) {
        begin(exampleId, LEGACY_DEMO_SEED);
    }

    /**
     * 开始一个历史教学场景，并安装调用方显式指定的根种子。
     *
     * @param exampleId catalog 中稳定且非空的示例标识
     * @param rootSeed 本次场景要记录和重放的根种子
     */
    public static void begin(String exampleId, long rootSeed) {
        if (exampleId == null || exampleId.trim().isEmpty()) {
            throw new IllegalArgumentException("Example id cannot be empty");
        }
        resetLegacyState();
        Parameters.setRandomSeed(rootSeed);
        activeExampleId.set(exampleId);
    }

    /**
     * 解析单工作流示例的零或一个 DAX 参数，并在创建 CloudSim 实体前验证输入存在。
     *
     * @param args 命令行参数
     * @param defaultPath 未传参数时使用的受控默认输入
     * @return 可读的 DAX 文件路径，保留调用者提供的相对路径语义
     */
    public static String singleDax(String[] args, String defaultPath) {
        int count = args == null ? 0 : args.length;
        if (count > 1) {
            throw new IllegalArgumentException("Expected zero or one DAX input path");
        }
        String path = count == 0 ? defaultPath : args[0];
        return requireDax(path);
    }

    /**
     * 解析多工作流示例的输入：不传参数时使用受控默认集合；显式提供时必须恰好提供
     * {@code expectedCount} 条 DAX 路径。
     *
     * @param args 命令行参数
     * @param defaults 未传参数时使用的输入集合
     * @param expectedCount 显式传入时要求的路径数
     * @return 已逐项验证的不可修改路径列表
     */
    public static List<String> multipleDax(String[] args, List<String> defaults, int expectedCount) {
        if (defaults == null || defaults.size() != expectedCount || expectedCount <= 0) {
            throw new IllegalArgumentException("Invalid default multi-workflow DAX contract");
        }
        int count = args == null ? 0 : args.length;
        if (count != 0 && count != expectedCount) {
            throw new IllegalArgumentException("Expected zero or exactly " + expectedCount
                    + " DAX input paths");
        }
        List<String> selected = count == 0 ? defaults : Arrays.asList(args);
        java.util.ArrayList<String> validated = new java.util.ArrayList<String>(selected.size());
        for (String path : selected) {
            validated.add(requireDax(path));
        }
        return Collections.unmodifiableList(validated);
    }

    /** 验证一个 DAX 输入文件存在且为普通文件。 */
    public static String requireDax(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("DAX input path cannot be empty");
        }
        File dax = new File(path.trim());
        if (!dax.isFile()) {
            throw new IllegalArgumentException("DAX input does not exist: " + dax.getAbsolutePath());
        }
        return path.trim();
    }

    /**
     * 严格解析仅由“单字符 flag + 一个值”组成的遗留命令行。
     *
     * <p>重复 flag、未知 flag、空 flag、缺失值和空值都会立即失败。数值和领域约束由
     * 调用方使用本类的解析辅助方法继续检查。</p>
     *
     * @param args 命令行参数
     * @param allowedOptions 支持的 flag，例如 {@code -d}
     * @return 按出现顺序保存的不可修改 option map
     */
    public static Map<String, String> parseValueOptions(String[] args, String... allowedOptions) {
        Set<String> allowed = new LinkedHashSet<String>(Arrays.asList(allowedOptions));
        Map<String, String> values = new LinkedHashMap<String, String>();
        int count = args == null ? 0 : args.length;
        for (int index = 0; index < count; index += 2) {
            String option = args[index];
            if (option == null || option.length() != 2 || option.charAt(0) != '-') {
                throw new IllegalArgumentException("Expected a single-character option at argument " + index);
            }
            if (!allowed.contains(option)) {
                throw new IllegalArgumentException("Unsupported option: " + option);
            }
            if (index + 1 >= count) {
                throw new IllegalArgumentException("Missing value for option " + option);
            }
            String value = args[index + 1];
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Option " + option + " cannot have an empty value");
            }
            if (values.put(option, value.trim()) != null) {
                throw new IllegalArgumentException("Duplicate option: " + option);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    /** 获取可选参数值；缺失时返回调用方声明的默认值。 */
    public static String optionOrDefault(Map<String, String> options, String option, String defaultValue) {
        if (options == null) {
            throw new IllegalArgumentException("Options cannot be null");
        }
        String value = options.get(option);
        return value == null ? defaultValue : value;
    }

    /** 解析严格大于零的有限浮点参数。 */
    public static double positiveFinite(String option, String value) {
        double result = finite(option, value);
        if (result <= 0.0) {
            throw new IllegalArgumentException(option + " must be finite and greater than zero");
        }
        return result;
    }

    /** 解析大于等于零的有限浮点参数。 */
    public static double nonNegativeFinite(String option, String value) {
        double result = finite(option, value);
        if (result < 0.0) {
            throw new IllegalArgumentException(option + " must be finite and non-negative");
        }
        return result;
    }

    /** 解析大于等于零的整数参数。 */
    public static int nonNegativeInt(String option, String value) {
        try {
            int result = Integer.parseInt(value);
            if (result < 0) {
                throw new IllegalArgumentException(option + " must be non-negative");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be an integer", exception);
        }
    }

    /** 解析 64 位有符号整数，例如显式实验根种子。 */
    public static long longValue(String option, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a 64-bit integer", exception);
        }
    }

    /**
     * 计算一组严格正有限因子的乘积，并在溢出时失败。
     *
     * @param expression 人类可读的参数表达式
     * @param factors 已分别完成领域校验的乘数
     * @return 有限正乘积
     */
    public static double positiveFiniteProduct(String expression, double... factors) {
        if (factors == null || factors.length == 0) {
            throw new IllegalArgumentException("Product factors cannot be empty");
        }
        double product = 1.0;
        for (double factor : factors) {
            if (Double.isNaN(factor) || Double.isInfinite(factor) || factor <= 0.0) {
                throw new IllegalArgumentException(expression + " requires finite positive factors");
            }
            product *= factor;
            if (Double.isInfinite(product) || Double.isNaN(product)) {
                throw new IllegalArgumentException(expression + " is not finite");
            }
        }
        return product;
    }

    /** 验证 option 值只由允许的单字符代码组成且至少包含一个字符。 */
    public static String allowedCharacters(String option, String value, String allowedCharacters) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " cannot be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            if (allowedCharacters.indexOf(value.charAt(index)) < 0) {
                throw new IllegalArgumentException(option + " contains unsupported code: " + value.charAt(index));
            }
        }
        return value;
    }

    /**
     * 验证 option 值恰好属于给定的有限集合。
     *
     * @param option 命令行 option 名称
     * @param value 待验证值
     * @param allowedValues 可接受的精确值
     * @return 原值，便于直接赋给调用方变量
     */
    public static String allowedValue(String option, String value, String... allowedValues) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(option + " cannot be empty");
        }
        for (String allowedValue : allowedValues) {
            if (value.equals(allowedValue)) {
                return value;
            }
        }
        throw new IllegalArgumentException(option + " has unsupported value: " + value);
    }

    /** 解析闭区间 {@code [0, 1]} 内的有限浮点参数。 */
    public static double unitInterval(String option, String value) {
        double result = nonNegativeFinite(option, value);
        if (result > 1.0) {
            throw new IllegalArgumentException(option + " must be between zero and one");
        }
        return result;
    }

    /** 解析开区间 {@code (0, 1)} 内的有限浮点参数。 */
    public static double openUnitInterval(String option, String value) {
        double result = unitInterval(option, value);
        if (result == 0.0 || result == 1.0) {
            throw new IllegalArgumentException(option + " must be strictly between zero and one");
        }
        return result;
    }

    /**
     * 输出稳定的完成边界。失败 Job 在容错教学情景中可能代表可恢复的中间尝试，因而如实计数。
     *
     * @param jobs 本次 CloudSim 事件循环返回的 Job 列表
     */
    public static void reportCompletion(List<Job> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            throw new IllegalStateException("Example produced no jobs");
        }
        int successful = 0;
        int failed = 0;
        for (Job job : jobs) {
            if (job.getCloudletStatus() == Cloudlet.SUCCESS) {
                successful++;
            } else if (job.getCloudletStatus() == Cloudlet.FAILED) {
                failed++;
            }
        }
        String id = activeExampleId.get();
        if (id == null) {
            id = "unregistered";
        }
        Log.printLine(COMPLETION_PREFIX + " id=" + id
                + " totalJobs=" + jobs.size()
                + " successfulJobs=" + successful
                + " failedJobs=" + failed);
    }

    /** 将历史示例的错误转换为可让 CLI 和 Maven 正确失败的未检查异常。 */
    public static IllegalStateException failure(String exampleId, Exception cause) {
        String message = cause == null ? "unknown error" : cause.getMessage();
        Log.printLine("The simulation has been terminated due to an unexpected error: " + message);
        return new IllegalStateException("Historical example failed: " + exampleId, cause);
    }

    /** 清理本线程已登记的示例标识和 WorkflowSim 历史静态状态。 */
    public static void end() {
        resetLegacyState();
        activeExampleId.remove();
    }

    private static void resetLegacyState() {
        if (CloudSim.running()) {
            CloudSim.stopSimulation();
        }
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
        ReplicaCatalog.reset();
        Parameters.reset();
    }

    private static double finite(String option, String value) {
        try {
            double result = Double.parseDouble(value);
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                throw new IllegalArgumentException(option + " must be finite");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(option + " must be a number", exception);
        }
    }
}
