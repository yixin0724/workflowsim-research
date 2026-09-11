package org.workflowsim.experiment;

import java.util.Locale;

/**
 * 将一次仿真结果格式化为面向科研人员的分组控制台摘要。
 *
 * <p>从 {@link SimulationMetrics} 的约 90 个指标中精选约 25 个关键指标，按
 * 「总体 / 性能 / 资源 / 数据传输 / 成本 / 容错 / Deadline」分组展示；
 * 容错和 Deadline 分组仅在相关数据存在时输出，保持摘要简洁。
 *
 * <p>完整指标始终保存在 {@code metrics.json} 中，本类只影响控制台展示。
 */
public final class ExperimentConsoleSummary {

    private ExperimentConsoleSummary() {
    }

    /**
     * 生成分组指标摘要文本。
     *
     * @param report 已完成仿真的不可变报告
     * @return 可直接打印到控制台的多行摘要
     * @throws IllegalArgumentException 当报告为空时抛出
     */
    public static String format(SimulationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Report is required");
        }
        SimulationMetrics m = report.getMetrics();
        StringBuilder sb = new StringBuilder();

        line(sb, "═══════════════════════════════════════════════════════");
        line(sb, "  仿真结果摘要");
        line(sb, "═══════════════════════════════════════════════════════");

        // ── 总体 ──
        line(sb, "【总体】");
        row(sb, "Makespan", seconds(m.getMakespanSeconds()));
        row(sb, "工作流完成", m.isWorkflowCompletedSuccessfully() ? "✅ 成功" : "❌ 未完成");
        row(sb, "逻辑任务", m.getSuccessfullyCompletedLogicalTaskCount() + "/" + m.getLogicalTaskCount()
                + rate(m.getSuccessfulLogicalTaskCompletionRate()));
        row(sb, "作业 (成功/失败)", m.getSuccessfulJobOutcomeCount() + "/" + m.getFailedJobOutcomeCount());

        // ── 性能 ──
        line(sb, "");
        line(sb, "【性能】");
        row(sb, "吞吐量", String.format(Locale.US, "%.4f 作业/秒", m.getComputeJobOutcomeThroughputPerSecond()));
        row(sb, "平均作业运行时间", seconds(m.getMeanComputeJobRunTimeSeconds()));
        row(sb, "平均总等待时间", seconds(m.getMeanComputeTotalWaitingTimeSeconds()));
        row(sb, "  └ 调度器等待", seconds(m.getMeanComputeReadyToDecisionDelaySeconds()));
        row(sb, "  └ 派发延迟", seconds(m.getMeanComputeDecisionToStartDelaySeconds()));
        row(sb, "  └ VM 队列等待", seconds(m.getMeanJobVmQueueWaitingTimeSeconds()) + " (通常 ≈0)");
        if (m.getTotalWaitingTimeObservationCount() > 0) {
            row(sb, "总等待时间 中位数/P95/最大", seconds(m.getMedianComputeTotalWaitingTimeSeconds())
                    + " / " + seconds(m.getP95ComputeTotalWaitingTimeSeconds())
                    + " / " + seconds(m.getMaxComputeTotalWaitingTimeSeconds())
                    + " (n=" + m.getTotalWaitingTimeObservationCount() + ")");
        }
        row(sb, "平均响应时间", seconds(m.getMeanJobResponseTimeSeconds()) + " (VM到达→完成，不含调度排队)");
        if (m.getMeanComputeTrueSlowdown() > 0) {
            row(sb, "平均真实减速比", String.format(Locale.US, "%.4f (n=%d)",
                    m.getMeanComputeTrueSlowdown(), m.getTrueSlowdownObservationCount()));
            row(sb, "真实减速比 中位数/P95/最大", String.format(Locale.US, "%.4f / %.4f / %.4f",
                    m.getMedianComputeTrueSlowdown(), m.getP95ComputeTrueSlowdown(),
                    m.getMaxComputeTrueSlowdown()));
            if (m.getSuccessOnlyWaitingObservationCount() > 0
                    && m.getSuccessOnlyWaitingObservationCount()
                        != m.getTotalWaitingTimeObservationCount()) {
                // 仅当存在失败尝试导致口径差异时展示 success-only 变体。
                row(sb, "成功作业均值 等待/减速比", seconds(m.getSuccessOnlyMeanComputeTotalWaitingTimeSeconds())
                        + " / " + String.format(Locale.US, "%.4f",
                                m.getSuccessOnlyMeanComputeTrueSlowdown())
                        + " (n=" + m.getSuccessOnlyWaitingObservationCount() + ")");
            }
        }
        row(sb, "调度周期数", String.valueOf(m.getSchedulingCycleCount()));
        if (m.isControlledSharedStorageCriticalPathReferenceAvailable()) {
            row(sb, "关键路径下界", seconds(m.getControlledSharedStorageCriticalPathLowerBoundSeconds()));
            row(sb, "调度长度比 (SLR)", String.format(Locale.US, "%.4f",
                    m.getControlledSharedStorageScheduleLengthRatio()));
        }

        // ── 资源利用 ──
        line(sb, "");
        line(sb, "【资源利用】");
        row(sb, "平均 VM 利用率", percent(m.getMeanVmModeledIntervalUtilization()));
        row(sb, "利用率变异系数", String.format(Locale.US, "%.4f",
                m.getVmModeledBusyTimeCoefficientOfVariation()));
        row(sb, "利用率 Jain 公平性指数", String.format(Locale.US, "%.4f",
                m.getVmUtilizationJainFairnessIndex()));
        row(sb, "VM 总繁忙时间", seconds(m.getTotalVmModeledBusyIntervalSeconds()));

        // ── 数据传输 ──
        line(sb, "");
        line(sb, "【数据传输】");
        row(sb, "建模传输文件数", String.valueOf(m.getModeledDataTransferFileCount()));
        row(sb, "总建模传输时间", seconds(m.getTotalModeledDataTransferSeconds()));
        row(sb, "总所需输入", bytes(m.getTotalModeledRequiredInputBytes()));

        // ── 成本 ──
        line(sb, "");
        line(sb, "【成本（模型抽象值）】");
        row(sb, "总处理成本", String.format(Locale.US, "%.2f", m.getTotalModeledProcessingCost()));
        row(sb, "CPU 包络成本", String.format(Locale.US, "%.2f", m.getTotalModeledCpuEnvelopeCost()));
        row(sb, "文件带宽成本", String.format(Locale.US, "%.2f",
                m.getTotalModeledDeclaredFileBandwidthCost()));

        // ── 容错（仅在发生重试时展示）──
        if (m.getRetryJobCreatedCount() > 0 || m.getFailedComputeJobOutcomeCount() > 0) {
            line(sb, "");
            line(sb, "【容错/重试】");
            row(sb, "重试作业创建数", String.valueOf(m.getRetryJobCreatedCount()));
            row(sb, "重试的逻辑任务数", String.valueOf(m.getRetriedLogicalTaskCount()));
            row(sb, "重试放大率", String.format(Locale.US, "%.4f (尝试/任务)",
                    m.getRetryAmplificationRatio()));
            row(sb, "失败尝试包络时间", seconds(m.getFailedComputeAttemptEnvelopeSeconds()));
            row(sb, "失败尝试成本", String.format(Locale.US, "%.2f",
                    m.getFailedComputeAttemptModeledProcessingCost()));
        }

        // ── Deadline（仅在启用时展示）──
        if (m.isDeadlineObservationEnabled()) {
            line(sb, "");
            line(sb, "【Deadline】");
            row(sb, "Deadline 满足", m.isDeadlineMet() ? "✅ 是" : "❌ 否");
            // 按 outcome 三分支展示：工作流未完成时 Slack/Tardiness 只是仿真时间轴
            // 差值，没有 SLA 含义——直接显示"超时 0.00 秒"会误导。
            String outcome = m.getDeadlineObservationOutcome();
            if (m.isDeadlineMet()) {
                row(sb, "剩余 Slack", seconds(m.getDeadlineSlackSeconds()));
            } else if ("MISSED_INCOMPLETE_WORKFLOW".equals(outcome)) {
                row(sb, "判定依据", "工作流未完成（Slack/Tardiness 不适用，仅时间轴差值）");
            } else {
                row(sb, "超时 Tardiness", seconds(m.getDeadlineTardinessSeconds()));
            }
        }

        line(sb, "═══════════════════════════════════════════════════════");
        return sb.toString();
    }

    /**
     * 生成每个 VM 的统计明细表格文本。
     *
     * @param report 已完成仿真的不可变报告
     * @return 可直接打印到控制台的 VM 明细表
     */
    public static String formatVmTable(SimulationReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Report is required");
        }
        StringBuilder sb = new StringBuilder();
        line(sb, "【VM 明细】");
        line(sb, String.format(Locale.US, "  %-6s %-8s %-14s %-14s %-10s",
                "VM", "作业数", "CPU时间(秒)", "繁忙时间(秒)", "利用率"));
        for (SimulationMetrics.VmMetrics vm : report.getMetrics().getVmMetrics().values()) {
            line(sb, String.format(Locale.US, "  %-6d %-8d %-14.2f %-14.2f %-10s",
                    vm.getVmId(),
                    vm.getJobOutcomeCount(),
                    vm.getReportedCpuTimeSeconds(),
                    vm.getModeledBusyIntervalSeconds(),
                    percent(vm.getModeledIntervalUtilization())));
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text).append(System.lineSeparator());
    }

    private static void row(StringBuilder sb, String label, String value) {
        sb.append(String.format(Locale.US, "  %-18s %s", label + ":", value))
                .append(System.lineSeparator());
    }

    private static String seconds(double value) {
        return String.format(Locale.US, "%.2f 秒", value);
    }

    private static String percent(double ratio) {
        return String.format(Locale.US, "%.2f%%", ratio * 100.0);
    }

    private static String rate(double ratio) {
        return String.format(Locale.US, " (%.1f%%)", ratio * 100.0);
    }

    private static String bytes(double value) {
        if (value >= 1024.0 * 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.2f GB", value / (1024.0 * 1024.0 * 1024.0));
        }
        if (value >= 1024.0 * 1024.0) {
            return String.format(Locale.US, "%.2f MB", value / (1024.0 * 1024.0));
        }
        if (value >= 1024.0) {
            return String.format(Locale.US, "%.2f KB", value / 1024.0);
        }
        return String.format(Locale.US, "%.0f B", value);
    }
}
