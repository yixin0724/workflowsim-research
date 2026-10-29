package org.workflowsim.experiment.registry;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.workflowsim.utils.ReplicaCatalog;

/**
 * Self-declared combination contract of a registered scheduling algorithm (2026-10-23,
 * BAT-3). The standard runner consumes exactly these fields to validate a configuration
 * the same way it validates its built-in matrix rows: file-system scope, accepted
 * data-movement model kinds, task-cost-matrix acceptance, and failure-model acceptance.
 *
 * <p>The declaration is the algorithm author's contract statement. The platform enforces
 * it fail-fast; it does not verify that the declared class actually implements a
 * literature-grade heuristic — that claim remains the author's responsibility.</p>
 *
 * <p>BAT-E3（2026-10-27）能耗维度声明字段：{@code energyAware} 声明算法的评分/选择逻辑
 * 使用了能耗成本；{@code powerModelRequired} 要求平台剖面提供每 VM 功率字段
 * （否则注册即拒绝该配置）；{@code energyObjectiveWeight} 是作者声明的能耗权衡权重
 * （仅正、有限；0 表示未参与目标函数）。三者默认关闭，全部经由
 * {@link AlgorithmRegistry} 在 validate() 中与平台功率字段联动执行。</p>
 *
 * <p>BAT-C5（2026-10-28）碳维度声明字段：{@code carbonAware} 与
 * {@code carbonObjectiveWeight} 按同一蕴含格镜像能耗三元组——正权重蕴含 carbonAware，
 * 且因 v1 两种碳核算（强度/预算）都骑在已审计的能量链上（设计文档 §2.2），同时蕴含
 * energyAware；预算窗口场景另由碳预算模型（平台 carbonWindows 声明）承载，不新增
 * 独立的碳平台门槛字段。默认全关（0/未声明的碳权重），既有注册零影响。</p>
 *
 * <p>BAT-D4（2026-10-29）DVFS 维度声明字段（docs/advanced/DVFS_DIMENSION_DESIGN.md
 * §2.6）：{@code dvfsAware} 声明算法通过频档 API 做逐 Job 变频决策；搭配
 * {@code powerModelRequired} 时注入因子提醒：频档表<b>替代</b>平面功率字段（BAT-D1
 * 单一真源），要求频档表的算法不要再声明平面功率门槛——{@code frequencyTiersRequired}
 * 要求平台剖面对每台（或参与计算的）VM 声明频档表（BAT-D4 蕴含格：
 * required ⇒ aware）。默认全关，既有注册零影响。</p>
 */
public final class AlgorithmDeclaration {

    /** File-system scope the declared algorithm is valid for. */
    public enum FileSystemScope {
        /** Usable with either supported catalog file system. */
        ANY,
        /** Requires LOCAL replica placement (e.g. locality-aware decisions). */
        LOCAL_ONLY,
        /** Requires SHARED datacenter-level storage semantics. */
        SHARED_ONLY
    }

    private final FileSystemScope fileSystemScope;
    private final Set<String> allowedDataMovementKinds;
    private final boolean acceptsTaskCostMatrix;
    private final boolean allowsFailureModel;
    private final String description;
    private final boolean energyAware;
    private final boolean powerModelRequired;
    private final double energyObjectiveWeight;
    private final boolean carbonAware;
    private final double carbonObjectiveWeight;
    private final boolean dvfsAware;
    private final boolean frequencyTiersRequired;

    private AlgorithmDeclaration(
            FileSystemScope fileSystemScope,
            Set<String> allowedDataMovementKinds,
            boolean acceptsTaskCostMatrix,
            boolean allowsFailureModel,
            String description,
            boolean energyAware,
            boolean powerModelRequired,
            double energyObjectiveWeight,
            boolean carbonAware,
            double carbonObjectiveWeight,
            boolean dvfsAware,
            boolean frequencyTiersRequired) {
        this.fileSystemScope = fileSystemScope;
        this.allowedDataMovementKinds = Collections.unmodifiableSet(new LinkedHashSet<>(allowedDataMovementKinds));
        this.acceptsTaskCostMatrix = acceptsTaskCostMatrix;
        this.allowsFailureModel = allowsFailureModel;
        this.description = description;
        this.energyAware = energyAware;
        this.powerModelRequired = powerModelRequired;
        this.energyObjectiveWeight = energyObjectiveWeight;
        this.carbonAware = carbonAware;
        this.carbonObjectiveWeight = carbonObjectiveWeight;
        this.dvfsAware = dvfsAware;
        this.frequencyTiersRequired = frequencyTiersRequired;
    }

    /** @return the file-system scope this algorithm declares support for */
    public FileSystemScope getFileSystemScope() {
        return fileSystemScope;
    }

    /** @return the data-movement {@link org.workflowsim.data.DataMovementModel.Kind} names
     *         the declaration covers; empty means every kind the runner itself supports */
    public Set<String> getAllowedDataMovementKinds() {
        return allowedDataMovementKinds;
    }

    /** @return whether configurations declaring a task-cost matrix may use this algorithm */
    public boolean acceptsTaskCostMatrix() {
        return acceptsTaskCostMatrix;
    }

    /** @return whether configs with an enabled failure model may use this algorithm */
    public boolean allowsFailureModel() {
        return allowsFailureModel;
    }

    /** @return a one-line human description surfaced in validation errors and docs */
    public String getDescription() {
        return description;
    }

    /** BAT-E3：@return 算法是否在其决策逻辑中声明使用能耗成本 */
    public boolean isEnergyAware() {
        return energyAware;
    }

    /** BAT-E3：@return 算法是否要求平台剖面声明每 VM 功率字段 */
    public boolean isPowerModelRequired() {
        return powerModelRequired;
    }

    /** BAT-E3：@return 声明的能耗目标权重（能量-时距乘积的权衡系数；0=不参与） */
    public double getEnergyObjectiveWeight() {
        return energyObjectiveWeight;
    }

    /** BAT-C5：@return 算法是否在其决策逻辑中声明使用碳成本（强度或预算模型） */
    public boolean isCarbonAware() {
        return carbonAware;
    }

    /** BAT-C5：@return 声明的碳目标权重（碳-时延权衡系数；0=不参与） */
    public double getCarbonObjectiveWeight() {
        return carbonObjectiveWeight;
    }

    /** BAT-D4：@return 算法是否声明通过频档 API 做逐 Job 变频决策 */
    public boolean isDvfsAware() {
        return dvfsAware;
    }

    /** BAT-D4：@return 算法是否要求平台剖面对 VM 声明频档表（蕴含 dvfsAware） */
    public boolean isFrequencyTiersRequired() {
        return frequencyTiersRequired;
    }

    /** Returns whether the declared file-system scope covers the given configured system. */
    public boolean coversFileSystem(ReplicaCatalog.FileSystem fileSystem) {
        if (fileSystem == null) {
            return false;
        }
        switch (fileSystemScope) {
            case ANY:
                return true;
            case LOCAL_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.LOCAL;
            case SHARED_ONLY:
                return fileSystem == ReplicaCatalog.FileSystem.SHARED;
            default:
                return false;
        }
    }

    /** Builder with the same validation discipline as the rest of the configuration API. */
    public static final class Builder {
        private FileSystemScope fileSystemScope = FileSystemScope.ANY;
        private final Set<String> allowedDataMovementKinds = new LinkedHashSet<>();
        private boolean acceptsTaskCostMatrix = false;
        private boolean allowsFailureModel = false;
        private String description = "";
        private boolean energyAware = false;
        private boolean powerModelRequired = false;
        private double energyObjectiveWeight = 0.0;
        private boolean carbonAware = false;
        private double carbonObjectiveWeight = 0.0;
        private boolean dvfsAware = false;
        private boolean frequencyTiersRequired = false;

        /** Declares the file-system scope this algorithm is valid for (default ANY). */
        public Builder fileSystemScope(FileSystemScope scope) {
            if (scope == null) {
                throw new IllegalArgumentException("FileSystemScope is required");
            }
            this.fileSystemScope = scope;
            return this;
        }

        /** Adds one accepted data-movement kind name (e.g. LEGACY_WORKFLOWSIM_V1). */
        public Builder allowDataMovementKind(String kind) {
            if (kind == null || kind.trim().isEmpty()) {
                throw new IllegalArgumentException("data-movement kind must be a non-empty name");
            }
            allowedDataMovementKinds.add(kind);
            return this;
        }

        /** Declares whether a declared task-cost matrix may accompany this algorithm. */
        public Builder acceptsTaskCostMatrix(boolean accepts) {
            this.acceptsTaskCostMatrix = accepts;
            return this;
        }

        /** Declares whether an enabled failure model may accompany this algorithm. */
        public Builder allowsFailureModel(boolean allows) {
            this.allowsFailureModel = allows;
            return this;
        }

        /** BAT-E3：声明算法的决策逻辑使用能耗成本（默认 false）。 */
        public Builder energyAware(boolean aware) {
            this.energyAware = aware;
            return this;
        }

        /** BAT-E3：要求配置的平台剖面提供每 VM 功率字段（默认 false）。 */
        public Builder powerModelRequired(boolean required) {
            this.powerModelRequired = required;
            if (required && !energyAware) {
                this.energyAware = true; // required implies aware
            }
            return this;
        }

        /** BAT-E3：声明能耗目标权重；必须 ≥ 0 且有限（默认 0 表示不参与）。 */
        public Builder energyObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("energyObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.energyObjectiveWeight = weight;
            if (weight > 0.0) {
                this.energyAware = true; // positive weight implies aware
            }
            return this;
        }

        /** BAT-C5：声明算法的决策逻辑使用碳成本（强度或预算模型；默认 false）。 */
        public Builder carbonAware(boolean aware) {
            this.carbonAware = aware;
            return this;
        }

        /**
         * BAT-C5：声明碳目标权重；必须 ≥ 0 且有限（默认 0 表示不参与）。正权重蕴含
         * carbonAware，且因 v1 两种碳核算都定义在已守恒锁定的能量链上
         * （docs/advanced/CARBON_DIMENSION_DESIGN.md §2.2），同时蕴含 energyAware——
         * 蕴含格与能量三元组同构，注册入口零特判。
         */
        public Builder carbonObjectiveWeight(double weight) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("carbonObjectiveWeight must be finite and >= 0: " + weight);
            }
            this.carbonObjectiveWeight = weight;
            if (weight > 0.0) {
                this.carbonAware = true; // positive weight implies aware
                this.energyAware = true; // v1 carbon rides the conserved energy chain
            }
            return this;
        }

        /** BAT-D4：声明算法通过频档 API 做逐 Job 变频决策（默认 false；描述性标志，非门槛）。 */
        public Builder dvfsAware(boolean aware) {
            this.dvfsAware = aware;
            return this;
        }

        /**
         * BAT-D4：要求配置的平台剖面对 VM 声明频档表（默认 false）。required ⇒ aware
         * 蕴含格——与 {@code powerModelRequired ⇒ energyAware} 同构：required 标志在
         * setter 处拉起 aware 标志，注册入口零特判。
         */
        public Builder frequencyTiersRequired(boolean required) {
            this.frequencyTiersRequired = required;
            if (required) {
                this.dvfsAware = true; // required implies aware
            }
            return this;
        }

        /** Sets the one-line description used in errors and documentation. */
        public Builder description(String text) {
            if (text == null) {
                throw new IllegalArgumentException("description must not be null (use empty string)");
            }
            this.description = text;
            return this;
        }

        /** Builds the immutable declaration; all validation happens here, fail-fast. */
        public AlgorithmDeclaration build() {
            return new AlgorithmDeclaration(
                    fileSystemScope,
                    allowedDataMovementKinds,
                    acceptsTaskCostMatrix,
                    allowsFailureModel,
                    description,
                    energyAware,
                    powerModelRequired,
                    energyObjectiveWeight,
                    carbonAware,
                    carbonObjectiveWeight,
                    dvfsAware,
                    frequencyTiersRequired);
        }
    }
}
