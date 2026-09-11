package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/**
 * 不可变且可执行的研究实验 campaign 声明。
 *
 * <p>每个比较组必须且只能声明一个基线。组内所有候选方案必须采用相同的随机化解释，
 * 但每个 cell 仍显式保存各自的种子序列，以便实验协议能够独立审计。</p>
 */
public final class ExperimentPlan {

    public static final String SCHEMA = "workflowsim-experiment-plan-v1";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    private final String id;
    private final List<Cell> cells;

    private ExperimentPlan(Builder builder) {
        this.id = builder.id;
        this.cells = Collections.unmodifiableList(new ArrayList<Cell>(builder.cells));
    }

    /**
     * 创建实验计划构建器。
     *
     * @param id 计划的稳定标识符，必须符合本类定义的标识符格式
     * @return 尚未包含任何 cell 的构建器
     * @throws IllegalArgumentException 当标识符无效时
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String getId() { return id; }
    public List<Cell> getCells() { return cells; }

    /**
     * 返回计划中所有 cell 声明的重复运行总数。
     *
     * @return 每个 cell 的复制次数之和
     */
    public int getRunCount() {
        int result = 0;
        for (Cell cell : cells) {
            result += cell.getSeedPlan().getReplicationCount();
        }
        return result;
    }

    public static final class Builder {
        private final String id;
        private final List<Cell> cells = new ArrayList<Cell>();

        private Builder(String id) {
            this.id = requireIdentifier(id, "Experiment plan ID");
        }

        /**
         * 向计划追加一个候选方案、平台和工作流组合。
         *
         * <p>跨 cell 的标识符、基线和随机化设计约束在 {@link #build()} 时统一校验。</p>
         *
         * @param value 要声明的非空实验 cell
         * @return 当前构建器，以支持链式调用
         * @throws IllegalArgumentException 当 cell 为 {@code null} 时
         */
        public Builder addCell(Cell value) {
            cells.add(requireNonNull(value, "Experiment cell"));
            return this;
        }

        /**
         * 校验并冻结当前声明。
         *
         * @return 满足比较组、基线、候选标识和随机化设计约束的不可变计划
         * @throws IllegalArgumentException 当未声明 cell，或任一跨 cell 约束不成立时
         */
        public ExperimentPlan build() {
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("Experiment plan requires at least one cell");
            }
            Set<String> cellIds = new HashSet<String>();
            Set<String> candidates = new HashSet<String>();
            Map<String, Cell> baselineByGroup = new HashMap<String, Cell>();
            Map<String, RandomizationDesign> designByGroup =
                    new HashMap<String, RandomizationDesign>();
            for (Cell cell : cells) {
                if (!cellIds.add(cell.getId())) {
                    throw new IllegalArgumentException("Experiment plan has duplicate cell ID " + cell.getId());
                }
                if (!candidates.add(cell.getComparisonGroup() + "\u0000" + cell.getCandidateId())) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has duplicate candidate ID " + cell.getCandidateId());
                }
                RandomizationDesign existing = designByGroup.put(cell.getComparisonGroup(),
                        cell.getSeedPlan().getRandomizationDesign());
                if (existing != null && existing != cell.getSeedPlan().getRandomizationDesign()) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has inconsistent randomization designs");
                }
                if (cell.isBaseline() && baselineByGroup.put(cell.getComparisonGroup(), cell) != null) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has more than one baseline");
                }
            }
            for (Cell cell : cells) {
                if (!baselineByGroup.containsKey(cell.getComparisonGroup())) {
                    throw new IllegalArgumentException("Comparison group " + cell.getComparisonGroup()
                            + " has no baseline cell");
                }
            }
            return new ExperimentPlan(this);
        }
    }

    /**
     * 一个候选算法、平台、工作流配置及其显式种子序列构成的不可变实验 cell。
     */
    public static final class Cell {
        private final String id;
        private final String comparisonGroup;
        private final String candidateId;
        private final boolean baseline;
        private final SimulationConfig config;
        private final PlatformProfile platform;
        private final SeedPlan seedPlan;
        private final Map<String, String> tags;

        /**
         * 创建一个实验 cell。
         *
         * @param id cell 的稳定标识符
         * @param comparisonGroup 该 cell 所属的比较组标识符
         * @param candidateId 比较组内候选方案的标识符
         * @param baseline 此 cell 是否为比较组基线
         * @param config 本次仿真的显式配置
         * @param platform 与配置 VM 数量一致的平台描述
         * @param seedPlan 复制次数和随机化设计均已声明的种子计划
         * @param tags 可选的不可变实验标签；传入 {@code null} 等价于空标签
         * @throws IllegalArgumentException 当标识符、必要对象、VM 数量或标签不满足约束时
         */
        public Cell(String id, String comparisonGroup, String candidateId, boolean baseline,
                SimulationConfig config, PlatformProfile platform, SeedPlan seedPlan,
                Map<String, String> tags) {
            this.id = requireIdentifier(id, "Experiment cell ID");
            this.comparisonGroup = requireIdentifier(comparisonGroup, "Comparison group ID");
            this.candidateId = requireIdentifier(candidateId, "Candidate ID");
            this.baseline = baseline;
            this.config = requireNonNull(config, "Simulation configuration");
            this.platform = requireNonNull(platform, "Platform profile");
            if (config.getVmCount() != platform.getVms().size()) {
                throw new IllegalArgumentException("Experiment cell configuration VM count "
                        + config.getVmCount() + " does not match platform VM count "
                        + platform.getVms().size());
            }
            this.seedPlan = requireNonNull(seedPlan, "Seed plan");
            Map<String, String> values = tags == null ? Collections.<String, String>emptyMap() : tags;
            this.tags = Collections.unmodifiableMap(new LinkedHashMap<String, String>(values));
            for (Map.Entry<String, String> entry : this.tags.entrySet()) {
                requireIdentifier(entry.getKey(), "Experiment tag key");
                if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                    throw new IllegalArgumentException("Experiment tag values cannot be empty");
                }
            }
        }

        public String getId() { return id; }
        public String getComparisonGroup() { return comparisonGroup; }
        public String getCandidateId() { return candidateId; }
        public boolean isBaseline() { return baseline; }
        public SimulationConfig getConfig() { return config; }
        public PlatformProfile getPlatform() { return platform; }
        public SeedPlan getSeedPlan() { return seedPlan; }
        public Map<String, String> getTags() { return tags; }
    }

    private static String requireIdentifier(String value, String subject) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(subject + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }

    private static <T> T requireNonNull(T value, String subject) {
        if (value == null) {
            throw new IllegalArgumentException(subject + " is required");
        }
        return value;
    }
}
