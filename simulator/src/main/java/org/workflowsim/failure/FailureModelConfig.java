package org.workflowsim.failure;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

/**
 * 单次模拟会话使用的不可变失效模型描述。
 *
 * <p>随机分布生成器仅在打开会话时创建。这样采样发生在运行种子已安装之后，
 * 并避免生成器状态泄漏到下一次运行。</p>
 */
public final class FailureModelConfig {

    private static final FailureModelConfig DISABLED = new FailureModelConfig(
            FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
            FailureParameters.FTCMonitor.MONITOR_NONE,
            FailureParameters.FTCFailure.FAILURE_NONE,
            DistributionFamily.WEIBULL, new DistributionSpec[0][0],
            Collections.<Integer, DistributionSpec[]>emptyMap(), 0);

    private final FailureParameters.FTCluteringAlgorithm clusteringAlgorithm;
    private final FailureParameters.FTCMonitor monitorMode;
    private final FailureParameters.FTCFailure generatorMode;
    private final DistributionFamily distributionFamily;
    private final DistributionSpec[][] generatorSpecs;
    /** 现代 VM 级模型按真实 VM ID 键控的生成器行；与稠密矩阵二选一。 */
    private final Map<Integer, DistributionSpec[]> generatorSpecsByVmId;
    private final int maxTotalRetryJobs;

    private FailureModelConfig(FailureParameters.FTCluteringAlgorithm clusteringAlgorithm,
            FailureParameters.FTCMonitor monitorMode,
            FailureParameters.FTCFailure generatorMode,
            DistributionFamily distributionFamily, DistributionSpec[][] generatorSpecs,
            Map<Integer, DistributionSpec[]> generatorSpecsByVmId,
            int maxTotalRetryJobs) {
        this.clusteringAlgorithm = clusteringAlgorithm;
        this.monitorMode = monitorMode;
        this.generatorMode = generatorMode;
        this.distributionFamily = distributionFamily;
        this.generatorSpecs = copySpecs(generatorSpecs);
        this.generatorSpecsByVmId = copySpecsByVmId(generatorSpecsByVmId);
        this.maxTotalRetryJobs = maxTotalRetryJobs;
    }

    /** 返回普通实验显式采用的“无失效”默认模型。 */
    public static FailureModelConfig disabled() {
        return DISABLED;
    }

    public static Builder builder() {
        return new Builder();
    }

    public FailureParameters.FTCluteringAlgorithm getClusteringAlgorithm() {
        return clusteringAlgorithm;
    }

    public FailureParameters.FTCMonitor getMonitorMode() {
        return monitorMode;
    }

    public FailureParameters.FTCFailure getGeneratorMode() {
        return generatorMode;
    }

    public DistributionFamily getDistributionFamily() {
        return distributionFamily;
    }

    /**
     * 返回旧式稠密规格矩阵的副本，绝不暴露内部矩阵。
     *
     * <p>采用 {@link #getGeneratorSpecsByVmId()} 的现代 VM ID 键控配置时，返回空矩阵。</p>
     */
    public DistributionSpec[][] getGeneratorSpecs() {
        return copySpecs(generatorSpecs);
    }

    /**
     * 返回按真实 VM ID 键控的生成器行副本。
     *
     * <p>该形式仅适用于 {@code FAILURE_VM} 和 {@code FAILURE_VM_JOB}，用于避免非连续
     * VM 标识被错误地解释为二维数组下标。旧式稠密矩阵配置时返回空映射。</p>
     */
    public Map<Integer, DistributionSpec[]> getGeneratorSpecsByVmId() {
        return copySpecsByVmId(generatorSpecsByVmId);
    }

    /** @return 当前模型是否以真实 VM ID 键控生成器行 */
    public boolean usesVmIdKeyedGeneratorRows() {
        return !generatorSpecsByVmId.isEmpty();
    }

    /** @return 本次运行允许创建的 retry Job 总数；禁用故障模型时为 0 */
    public int getMaxTotalRetryJobs() {
        return maxTotalRetryJobs;
    }

    /** 当会话必须分配带状态的随机生成器时返回 {@code true}。 */
    public boolean isEnabled() {
        return generatorMode != FailureParameters.FTCFailure.FAILURE_NONE;
    }

    /** 将由当前运行种子作用域限定的新生成器矩阵和停止预算安装到旧版门面。 */
    public void applyToFailureParameters(long rootSeed) {
        if (!isEnabled()) {
            FailureParameters.init(clusteringAlgorithm, monitorMode, generatorMode,
                    (DistributionGenerator[][]) null, distributionFamily, maxTotalRetryJobs, rootSeed);
        } else if (usesVmIdKeyedGeneratorRows()) {
            FailureParameters.init(clusteringAlgorithm, monitorMode, generatorMode,
                    createGeneratorsByVmId(), distributionFamily, maxTotalRetryJobs, rootSeed);
        } else {
            FailureParameters.init(clusteringAlgorithm, monitorMode, generatorMode,
                    createGenerators(), distributionFamily, maxTotalRetryJobs, rootSeed);
        }
    }

    private DistributionGenerator[][] createGenerators() {
        DistributionGenerator[][] generators = new DistributionGenerator[generatorSpecs.length][];
        for (int row = 0; row < generatorSpecs.length; row++) {
            generators[row] = new DistributionGenerator[generatorSpecs[row].length];
            for (int column = 0; column < generatorSpecs[row].length; column++) {
                generators[row][column] = generatorSpecs[row][column].createGenerator(
                        "failure.row" + row + ".column" + column);
            }
        }
        return generators;
    }

    private Map<Integer, DistributionGenerator[]> createGeneratorsByVmId() {
        Map<Integer, DistributionGenerator[]> generators = new LinkedHashMap<Integer, DistributionGenerator[]>();
        for (Map.Entry<Integer, DistributionSpec[]> entry : generatorSpecsByVmId.entrySet()) {
            int vmId = entry.getKey().intValue();
            DistributionSpec[] specs = entry.getValue();
            DistributionGenerator[] row = new DistributionGenerator[specs.length];
            for (int depth = 0; depth < specs.length; depth++) {
                row[depth] = specs[depth].createGenerator(
                        "failure.vm" + vmId + ".depth" + depth);
            }
            generators.put(Integer.valueOf(vmId), row);
        }
        return generators;
    }

    private static DistributionSpec[][] copySpecs(DistributionSpec[][] source) {
        DistributionSpec[][] result = new DistributionSpec[source.length][];
        for (int row = 0; row < source.length; row++) {
            result[row] = Arrays.copyOf(source[row], source[row].length);
        }
        return result;
    }

    private static Map<Integer, DistributionSpec[]> copySpecsByVmId(
            Map<Integer, DistributionSpec[]> source) {
        Map<Integer, DistributionSpec[]> sorted = new TreeMap<Integer, DistributionSpec[]>();
        for (Map.Entry<Integer, DistributionSpec[]> entry : source.entrySet()) {
            sorted.put(entry.getKey(), Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<Integer, DistributionSpec[]>(sorted));
    }

    /** 构建器会在创建任何模拟实体前验证配置结构。 */
    public static final class Builder {

        private FailureParameters.FTCluteringAlgorithm clusteringAlgorithm
                = FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP;
        private FailureParameters.FTCMonitor monitorMode = FailureParameters.FTCMonitor.MONITOR_NONE;
        private FailureParameters.FTCFailure generatorMode = FailureParameters.FTCFailure.FAILURE_NONE;
        private DistributionFamily distributionFamily = DistributionFamily.WEIBULL;
        private DistributionSpec[][] generatorSpecs = new DistributionSpec[0][0];
        private Map<Integer, DistributionSpec[]> generatorSpecsByVmId
                = Collections.<Integer, DistributionSpec[]>emptyMap();
        private int maxTotalRetryJobs = -1;

        public Builder clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm value) {
            clusteringAlgorithm = value;
            return this;
        }

        public Builder monitorMode(FailureParameters.FTCMonitor value) {
            monitorMode = value;
            return this;
        }

        public Builder generatorMode(FailureParameters.FTCFailure value) {
            generatorMode = value;
            return this;
        }

        public Builder distributionFamily(DistributionFamily value) {
            distributionFamily = value;
            return this;
        }

        public Builder generatorSpecs(DistributionSpec[][] value) {
            generatorSpecs = value;
            return this;
        }

        /**
         * 按真实 VM ID 指定生成器行。
         *
         * <p>此形式仅用于 VM 维度参与选择的模型，允许平台使用非连续 VM ID，例如
         * {@code 10} 和 {@code 20}，而无需构造无意义的第 0 至第 19 行。</p>
         *
         * @param value VM ID 到各任务深度规格行的映射
         * @return 当前构建器
         */
        public Builder generatorSpecsByVmId(Map<Integer, DistributionSpec[]> value) {
            generatorSpecsByVmId = value;
            return this;
        }

        /**
         * 设置本次故障模型最多可创建的 retry Job 数。
         *
         * <p>启用故障模型时必须显式设置正值，使高故障率实验拥有可记录的停止条件。</p>
         *
         * @param value 正的 retry Job 总数上限
         * @return 当前构建器
         */
        public Builder maxTotalRetryJobs(int value) {
            maxTotalRetryJobs = value;
            return this;
        }

        public FailureModelConfig build() {
            if (clusteringAlgorithm == null || monitorMode == null || generatorMode == null
                    || distributionFamily == null || generatorSpecs == null
                    || generatorSpecsByVmId == null) {
                throw new IllegalArgumentException("Failure configuration contains a required null value");
            }
            boolean hasDenseMatrix = generatorSpecs.length != 0;
            boolean hasVmIdKeyedRows = !generatorSpecsByVmId.isEmpty();
            if (generatorMode == FailureParameters.FTCFailure.FAILURE_NONE) {
                if (hasDenseMatrix || hasVmIdKeyedRows) {
                    throw new IllegalArgumentException("A disabled failure model cannot define generators");
                }
                if (maxTotalRetryJobs != -1 && maxTotalRetryJobs != 0) {
                    throw new IllegalArgumentException("A disabled failure model must use retry budget 0");
                }
            } else {
                if (hasDenseMatrix == hasVmIdKeyedRows) {
                    throw new IllegalArgumentException("An enabled failure model requires exactly one generator "
                            + "layout: a dense matrix or VM-ID-keyed rows");
                }
                if (hasVmIdKeyedRows) {
                    if (generatorMode != FailureParameters.FTCFailure.FAILURE_VM
                            && generatorMode != FailureParameters.FTCFailure.FAILURE_VM_JOB) {
                        throw new IllegalArgumentException("VM-ID-keyed generator rows require FAILURE_VM or "
                                + "FAILURE_VM_JOB mode");
                    }
                    validateVmIdKeyedGeneratorRows(generatorSpecsByVmId, distributionFamily);
                } else {
                    validateGeneratorMatrix(generatorSpecs, distributionFamily);
                }
                if (maxTotalRetryJobs <= 0) {
                    throw new IllegalArgumentException("An enabled failure model requires an explicit positive "
                            + "maxTotalRetryJobs safety budget");
                }
            }
            return new FailureModelConfig(clusteringAlgorithm, monitorMode, generatorMode,
                    distributionFamily, generatorSpecs, generatorSpecsByVmId,
                    generatorMode == FailureParameters.FTCFailure.FAILURE_NONE
                            ? 0 : maxTotalRetryJobs);
        }

        private static void validateGeneratorMatrix(DistributionSpec[][] matrix,
                DistributionFamily expectedFamily) {
            if (matrix.length == 0) {
                throw new IllegalArgumentException("An enabled failure model requires a generator matrix");
            }
            if (expectedFamily == DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Failure inter-arrival distributions cannot use NORMAL "
                        + "because it can generate negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
            for (int row = 0; row < matrix.length; row++) {
                if (matrix[row] == null || matrix[row].length == 0) {
                    throw new IllegalArgumentException("Failure generator row " + row + " is empty");
                }
                for (int column = 0; column < matrix[row].length; column++) {
                    if (matrix[row][column] == null) {
                        throw new IllegalArgumentException("Failure generator [" + row + "][" + column + "] is null");
                    }
                    if (matrix[row][column].getFamily() != expectedFamily) {
                        throw new IllegalArgumentException("Failure generator [" + row + "][" + column
                                + "] has family " + matrix[row][column].getFamily()
                                + " but the failure model declares " + expectedFamily);
                    }
                }
            }
        }

        private static void validateVmIdKeyedGeneratorRows(Map<Integer, DistributionSpec[]> rows,
                DistributionFamily expectedFamily) {
            if (expectedFamily == DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Failure inter-arrival distributions cannot use NORMAL "
                        + "because it can generate negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
            for (Map.Entry<Integer, DistributionSpec[]> entry : rows.entrySet()) {
                Integer vmId = entry.getKey();
                DistributionSpec[] specs = entry.getValue();
                if (vmId == null || vmId.intValue() < 0) {
                    throw new IllegalArgumentException("VM-ID-keyed failure generator rows require non-negative VM IDs");
                }
                if (specs == null || specs.length == 0) {
                    throw new IllegalArgumentException("Failure generator row for VM " + vmId + " is empty");
                }
                for (int depth = 0; depth < specs.length; depth++) {
                    DistributionSpec spec = specs[depth];
                    if (spec == null) {
                        throw new IllegalArgumentException("Failure generator for VM " + vmId
                                + " at depth " + depth + " is null");
                    }
                    if (spec.getFamily() != expectedFamily) {
                        throw new IllegalArgumentException("Failure generator for VM " + vmId
                                + " at depth " + depth + " has family " + spec.getFamily()
                                + " but the failure model declares " + expectedFamily);
                    }
                }
            }
        }
    }
}
