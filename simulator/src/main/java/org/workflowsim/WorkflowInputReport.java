package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 记录一次工作流输入转换为 WorkflowSim 任务时的来源信息和显式归一化。
 *
 * <p>该报告只描述输入解释过程，不代表仿真输出或现实执行观测；实验清单可使用它追溯
 * 输入格式、声明的架构版本及为满足模型约束而做的转换。</p>
 */
public final class WorkflowInputReport {

    /** 已支持的工作流输入格式。 */
    public enum Format {
        DAX_XML,
        WFCOMMONS_JSON
    }

    /** 当前输入转换中可能记录的归一化类型。 */
    public enum NormalizationKind {
        TASK_RUNTIME_FLOORED_TO_MINIMUM
    }

    private final String sourcePath;
    private final Format format;
    private final String declaredVersion;
    private final List<Normalization> normalizations = new ArrayList<>();
    private int taskCount;

    /**
     * 创建输入转换报告。
     *
     * @param sourcePath 已解析输入文件的路径
     * @param format 输入格式
     * @param declaredVersion 输入中声明的格式或架构版本；未知时可为 {@code null}
     */
    public WorkflowInputReport(String sourcePath, Format format, String declaredVersion) {
        if (sourcePath == null || sourcePath.trim().isEmpty() || format == null) {
            throw new IllegalArgumentException("Workflow input report requires a path and format");
        }
        this.sourcePath = sourcePath;
        this.format = format;
        this.declaredVersion = declaredVersion;
    }

    /** @return 已解析输入文件的路径 */
    public String getSourcePath() {
        return sourcePath;
    }

    /** @return 输入格式 */
    public Format getFormat() {
        return format;
    }

    /** @return 输入中声明的格式或架构版本；未声明时为 {@code null} */
    public String getDeclaredVersion() {
        return declaredVersion;
    }

    /** @return 成功转换得到的任务数 */
    public int getTaskCount() {
        return taskCount;
    }

    /**
     * 设置成功转换得到的任务数。
     *
     * @param taskCount 非负任务数
     */
    public void setTaskCount(int taskCount) {
        if (taskCount < 0) {
            throw new IllegalArgumentException("Task count cannot be negative");
        }
        this.taskCount = taskCount;
    }

    /**
     * 记录一项有意执行的输入值转换。
     *
     * @param kind 转换类型
     * @param subject 被转换的输入对象标识
     * @param originalValue 原始数值
     * @param normalizedValue 进入模型的数值
     */
    public void recordNormalization(NormalizationKind kind, String subject,
            double originalValue, double normalizedValue) {
        normalizations.add(new Normalization(kind, subject, originalValue, normalizedValue));
    }

    /** @return 不可修改的归一化记录列表 */
    public List<Normalization> getNormalizations() {
        return Collections.unmodifiableList(normalizations);
    }

    /** 一项有意执行的输入值转换记录。 */
    public static final class Normalization {

        private final NormalizationKind kind;
        private final String subject;
        private final double originalValue;
        private final double normalizedValue;

        private Normalization(NormalizationKind kind, String subject,
                double originalValue, double normalizedValue) {
            this.kind = kind;
            this.subject = subject;
            this.originalValue = originalValue;
            this.normalizedValue = normalizedValue;
        }

        /** @return 转换类型 */
        public NormalizationKind getKind() {
            return kind;
        }

        /** @return 被转换的输入对象标识 */
        public String getSubject() {
            return subject;
        }

        /** @return 原始数值 */
        public double getOriginalValue() {
            return originalValue;
        }

        /** @return 归一化后的模型数值 */
        public double getNormalizedValue() {
            return normalizedValue;
        }
    }
}
