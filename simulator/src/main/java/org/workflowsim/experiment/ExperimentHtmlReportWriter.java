package org.workflowsim.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;

/**
 * 生成自包含的交互式 HTML 可视化报告。
 *
 * <p>包含：
 * <ul>
 * <li>关键指标摘要卡片</li>
 * <li>作业执行甘特图（按 VM 分组）</li>
 * <li>VM 利用率时间轴</li>
 * <li>作业/任务/VM 详细数据表格（可排序）</li>
 * </ul>
 *
 * <p>输出为单个自包含 HTML 文件，无外部依赖，可在浏览器中直接打开。
 */
public final class ExperimentHtmlReportWriter {

    private ExperimentHtmlReportWriter() {
    }

    /**
     * 生成自包含的 HTML 可视化报告。
     *
     * @param report 已完成仿真的不可变报告
     * @param outputFile HTML 输出文件
     * @param runId 运行标识（用于报告标题）
     * @throws IOException 当文件无法写入时抛出
     * @throws IllegalArgumentException 当报告或输出路径为空时抛出
     */
    public static void write(SimulationReport report, Path outputFile, String runId)
            throws IOException {
        if (report == null || outputFile == null) {
            throw new IllegalArgumentException("Report and output file are required");
        }

        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            writeHtmlDocument(writer, report, runId == null ? "Simulation Report" : runId);
        }
    }

    private static void writeHtmlDocument(BufferedWriter w, SimulationReport report, String title)
            throws IOException {
        SimulationMetrics m = report.getMetrics();

        w.write("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        w.write("<meta charset=\"UTF-8\">\n");
        w.write("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        w.write("<title>" + escapeHtml(title) + " - WorkflowSim</title>\n");
        writeStyles(w);
        w.write("</head>\n<body>\n");

        // 标题
        w.write("<div class=\"header\"><h1>" + escapeHtml(title) + "</h1>");
        w.write("<p class=\"subtitle\">WorkflowSim 实验可视化报告</p></div>\n");

        // 关键指标卡片
        writeMetricsCards(w, m);

        // 甘特图
        writeGanttChart(w, report);

        // VM 利用率表格
        writeVmTable(w, report);

        // 作业详细表格
        writeJobsTable(w, report);

        // 任务详细表格（可折叠，默认折叠）
        writeTasksTable(w, report);

        writeScripts(w);
        w.write("</body>\n</html>");
    }

    private static void writeStyles(BufferedWriter w) throws IOException {
        w.write("<style>\n");
        w.write("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        w.write("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; ");
        w.write("background: #f5f7fa; color: #333; line-height: 1.6; }\n");
        w.write(".header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); ");
        w.write("color: white; padding: 2rem; text-align: center; box-shadow: 0 4px 6px rgba(0,0,0,0.1); }\n");
        w.write(".header h1 { font-size: 2rem; margin-bottom: 0.5rem; }\n");
        w.write(".subtitle { opacity: 0.9; font-size: 1rem; }\n");
        w.write(".container { max-width: 1400px; margin: 2rem auto; padding: 0 1rem; }\n");
        w.write(".cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); ");
        w.write("gap: 1rem; margin-bottom: 2rem; }\n");
        w.write(".card { background: white; padding: 1.5rem; border-radius: 8px; ");
        w.write("box-shadow: 0 2px 8px rgba(0,0,0,0.08); }\n");
        w.write(".card-title { font-size: 0.875rem; color: #666; margin-bottom: 0.5rem; ");
        w.write("text-transform: uppercase; letter-spacing: 0.5px; }\n");
        w.write(".card-value { font-size: 1.75rem; font-weight: 700; color: #667eea; }\n");
        w.write(".card-unit { font-size: 1rem; color: #999; margin-left: 0.25rem; }\n");
        w.write(".section { background: white; padding: 2rem; border-radius: 8px; margin-bottom: 2rem; ");
        w.write("box-shadow: 0 2px 8px rgba(0,0,0,0.08); }\n");
        w.write(".section-title { font-size: 1.5rem; margin-bottom: 1.5rem; color: #2c3e50; ");
        w.write("border-bottom: 2px solid #667eea; padding-bottom: 0.5rem; }\n");
        w.write(".gantt-container { overflow-x: auto; }\n");
        w.write(".gantt-chart { min-width: 800px; position: relative; }\n");
        w.write(".gantt-row { display: flex; align-items: center; height: 40px; ");
        w.write("border-bottom: 1px solid #e0e0e0; }\n");
        w.write(".gantt-label { width: 80px; font-weight: 600; font-size: 0.875rem; ");
        w.write("padding-right: 1rem; text-align: right; color: #555; }\n");
        w.write(".gantt-timeline { flex: 1; position: relative; height: 100%; }\n");
        w.write(".gantt-bar { position: absolute; height: 24px; top: 8px; border-radius: 4px; ");
        w.write("font-size: 0.75rem; color: white; display: flex; align-items: center; ");
        w.write("padding: 0 8px; overflow: hidden; transition: all 0.2s; cursor: pointer; }\n");
        w.write(".gantt-bar:hover { transform: translateY(-2px); box-shadow: 0 4px 8px rgba(0,0,0,0.2); ");
        w.write("z-index: 10; }\n");
        w.write(".status-SUCCESS { background: #10b981; }\n");
        w.write(".status-FAILED { background: #ef4444; }\n");
        w.write(".status-CANCELED { background: #6b7280; }\n");
        w.write("table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }\n");
        w.write("thead { background: #f8fafc; }\n");
        w.write("th { padding: 0.75rem; text-align: left; font-weight: 600; color: #475569; ");
        w.write("border-bottom: 2px solid #e2e8f0; cursor: pointer; user-select: none; }\n");
        w.write("th:hover { background: #e2e8f0; }\n");
        w.write("td { padding: 0.75rem; border-bottom: 1px solid #e2e8f0; }\n");
        w.write("tr:hover { background: #f8fafc; }\n");
        w.write(".badge { display: inline-block; padding: 0.25rem 0.75rem; border-radius: 12px; ");
        w.write("font-size: 0.75rem; font-weight: 600; }\n");
        w.write(".badge-success { background: #d1fae5; color: #065f46; }\n");
        w.write(".badge-error { background: #fee2e2; color: #991b1b; }\n");
        w.write(".collapsible-header { cursor: pointer; padding: 1rem; background: #f8fafc; ");
        w.write("border-radius: 8px; margin-bottom: 1rem; display: flex; justify-content: space-between; ");
        w.write("align-items: center; }\n");
        w.write(".collapsible-content { display: none; }\n");
        w.write(".collapsible-content.open { display: block; }\n");
        w.write(".toggle-icon { transition: transform 0.3s; }\n");
        w.write(".toggle-icon.open { transform: rotate(180deg); }\n");
        w.write("</style>\n");
    }

    private static void writeMetricsCards(BufferedWriter w, SimulationMetrics m) throws IOException {
        w.write("<div class=\"container\"><div class=\"cards\">\n");
        card(w, "Makespan", String.format(Locale.US, "%.2f", m.getMakespanSeconds()), "秒");
        card(w, "吞吐量", String.format(Locale.US, "%.4f", m.getComputeJobOutcomeThroughputPerSecond()),
                "作业/秒");
        card(w, "平均总等待时间", String.format(Locale.US, "%.2f", m.getMeanComputeTotalWaitingTimeSeconds()), "秒");
        // L1：无观测时显示 "—" 而非 0.0000，避免误读为真实测量值。
        if (m.getTotalWaitingTimeObservationCount() > 0) {
            card(w, "等待时间 P95", String.format(Locale.US, "%.2f",
                    m.getP95ComputeTotalWaitingTimeSeconds()), "秒");
            card(w, "等待时间 中位数/最大", String.format(Locale.US, "%.2f / %.2f",
                    m.getMedianComputeTotalWaitingTimeSeconds(),
                    m.getMaxComputeTotalWaitingTimeSeconds()), "秒");
        }
        if (m.getTrueSlowdownObservationCount() > 0) {
            card(w, "平均真实减速比", String.format(Locale.US, "%.4f", m.getMeanComputeTrueSlowdown()), "");
            card(w, "减速比 P95/最大", String.format(Locale.US, "%.4f / %.4f",
                    m.getP95ComputeTrueSlowdown(), m.getMaxComputeTrueSlowdown()), "");
        } else {
            card(w, "平均真实减速比", "—", "无观测");
        }
        card(w, "平均响应时间", String.format(Locale.US, "%.2f", m.getMeanJobResponseTimeSeconds()), "秒 (VM到达→完成)");
        card(w, "成功作业", String.valueOf(m.getSuccessfulJobOutcomeCount()), "个");
        card(w, "失败作业", String.valueOf(m.getFailedJobOutcomeCount()), "个");
        card(w, "平均 VM 利用率", String.format(Locale.US, "%.1f", 
                m.getMeanVmModeledIntervalUtilization() * 100), "%");
        card(w, "利用率 Jain 公平性指数", String.format(Locale.US, "%.4f",
                m.getVmUtilizationJainFairnessIndex()), "");
        if (m.getRetryAmplificationRatio() > 1.0) {
            card(w, "重试放大率", String.format(Locale.US, "%.4f", m.getRetryAmplificationRatio()),
                    "尝试/任务");
        }
        card(w, "总处理成本", String.format(Locale.US, "%.2f", m.getTotalModeledProcessingCost()), "");
        w.write("</div></div>\n");
    }

    private static void card(BufferedWriter w, String title, String value, String unit)
            throws IOException {
        w.write("<div class=\"card\"><div class=\"card-title\">" + title + "</div>");
        w.write("<div class=\"card-value\">" + value);
        if (!unit.isEmpty()) {
            w.write("<span class=\"card-unit\">" + unit + "</span>");
        }
        w.write("</div></div>\n");
    }

    private static void writeGanttChart(BufferedWriter w, SimulationReport report) throws IOException {
        w.write("<div class=\"container\"><div class=\"section\">");
        w.write("<h2 class=\"section-title\">作业执行甘特图</h2>\n");
        w.write("<div class=\"gantt-container\"><div class=\"gantt-chart\">\n");

        // 按 VM 分组作业
        Map<Integer, List<SimulationReport.JobOutcome>> vmJobs = new HashMap<>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            vmJobs.computeIfAbsent(job.getVmId(), k -> new ArrayList<>()).add(job);
        }

        double makespan = report.getMakespan();
        List<Integer> vmIds = new ArrayList<>(vmJobs.keySet());
        Collections.sort(vmIds);

        for (int vmId : vmIds) {
            w.write("<div class=\"gantt-row\">");
            w.write("<div class=\"gantt-label\">VM " + vmId + "</div>");
            w.write("<div class=\"gantt-timeline\">");

            for (SimulationReport.JobOutcome job : vmJobs.get(vmId)) {
                // 防护：makespan<=0 时百分比置 0（避免 NaN%）；时钟异常（finish<start）
                // 时宽度钳到 0，不渲染负宽度条。
                double leftPercent = makespan > 0 ? (job.getStartTime() / makespan) * 100 : 0.0;
                double widthPercent = makespan > 0
                        ? Math.max(0.0, (job.getFinishTime() - job.getStartTime()) / makespan) * 100
                        : 0.0;
                String statusClass = statusName(job.getStatus());
                w.write(String.format(Locale.US,
                        "<div class=\"gantt-bar status-%s\" style=\"left:%.2f%%;width:%.2f%%\" "
                        + "title=\"Job %d | %.2f - %.2f 秒 | 状态: %s\">J%d</div>",
                        statusClass, leftPercent, widthPercent, job.getJobId(),
                        job.getStartTime(), job.getFinishTime(), statusClass, job.getJobId()));
            }

            w.write("</div></div>\n");
        }

        w.write("</div></div></div></div>\n");
    }

    private static void writeVmTable(BufferedWriter w, SimulationReport report) throws IOException {
        w.write("<div class=\"container\"><div class=\"section\">");
        w.write("<h2 class=\"section-title\">VM 资源利用统计</h2>\n");
        w.write("<table><thead><tr>");
        w.write("<th>VM ID</th><th>作业数</th><th>CPU 时间 (秒)</th>");
        w.write("<th>繁忙时间 (秒)</th><th>利用率</th></tr></thead><tbody>\n");

        List<SimulationMetrics.VmMetrics> vms = new ArrayList<>(
                report.getMetrics().getVmMetrics().values());
        vms.sort(Comparator.comparingInt(SimulationMetrics.VmMetrics::getVmId));

        for (SimulationMetrics.VmMetrics vm : vms) {
            w.write(String.format(Locale.US,
                    "<tr><td>%d</td><td>%d</td><td>%.2f</td><td>%.2f</td><td>%.1f%%</td></tr>\n",
                    vm.getVmId(), vm.getJobOutcomeCount(), vm.getReportedCpuTimeSeconds(),
                    vm.getModeledBusyIntervalSeconds(), vm.getModeledIntervalUtilization() * 100));
        }

        w.write("</tbody></table></div></div>\n");
    }

    private static void writeJobsTable(BufferedWriter w, SimulationReport report) throws IOException {
        w.write("<div class=\"container\"><div class=\"section\">");
        w.write("<h2 class=\"section-title\">作业详细数据</h2>\n");
        w.write("<table id=\"jobsTable\"><thead><tr>");
        w.write("<th onclick=\"sortTable('jobsTable', 0)\">Job ID</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 1)\">VM ID</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 2)\">状态</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 3)\">到达时间(VM)</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 4)\">开始时间</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 5)\">结束时间</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 6)\">VM 队列等待</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 7)\">执行时间</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 8)\">响应时间</th>");
        w.write("<th onclick=\"sortTable('jobsTable', 9)\">任务数</th>");
        w.write("</tr></thead><tbody>\n");

        for (SimulationReport.JobOutcome job : report.getJobs()) {
            String statusName = statusName(job.getStatus());
            String badgeClass = job.getStatus() == Cloudlet.SUCCESS ? "badge-success" : "badge-error";

            w.write(String.format(Locale.US,
                    "<tr><td>%d</td><td>%d</td><td><span class=\"badge %s\">%s</span></td>"
                    + "<td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%.2f</td><td>%d</td></tr>\n",
                    job.getJobId(), job.getVmId(), badgeClass, statusName,
                    job.getSubmissionTime(), job.getStartTime(), job.getFinishTime(),
                    job.getWaitingTime(), job.getExecutionTime(), job.getResponseTime(),
                    job.getTaskCount()));
        }

        w.write("</tbody></table></div></div>\n");
    }

    private static void writeTasksTable(BufferedWriter w, SimulationReport report) throws IOException {
        w.write("<div class=\"container\"><div class=\"section\">");
        w.write("<div class=\"collapsible-header\" onclick=\"toggleCollapsible('tasksTable')\">");
        w.write("<h2 class=\"section-title\" style=\"margin:0\">任务详细数据 (" + report.getTasks().size()
                + " 个)</h2>");
        w.write("<span class=\"toggle-icon\" id=\"tasksTableIcon\">▼</span></div>\n");
        w.write("<div class=\"collapsible-content\" id=\"tasksTableContent\">");
        w.write("<table id=\"tasksTable\"><thead><tr>");
        w.write("<th onclick=\"sortTable('tasksTable', 0)\">Task ID</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 1)\">Job ID</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 2)\">VM ID</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 3)\">深度</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 4)\">开始时间</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 5)\">结束时间</th>");
        w.write("<th onclick=\"sortTable('tasksTable', 6)\">持续时间</th>");
        w.write("</tr></thead><tbody>\n");

        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            double duration = task.getFinishTime() - task.getStartTime();
            w.write(String.format(Locale.US,
                    "<tr><td>%d</td><td>%d</td><td>%d</td><td>%d</td>"
                    + "<td>%.2f</td><td>%.2f</td><td>%.2f</td></tr>\n",
                    task.getTaskId(), task.getJobId(), task.getVmId(), task.getDepth(),
                    task.getStartTime(), task.getFinishTime(), duration));
        }

        w.write("</tbody></table></div></div></div>\n");
    }

    private static void writeScripts(BufferedWriter w) throws IOException {
        w.write("<script>\n");
        w.write("function sortTable(tableId, column) {\n");
        w.write("  const table = document.getElementById(tableId);\n");
        w.write("  const tbody = table.querySelector('tbody');\n");
        w.write("  const rows = Array.from(tbody.querySelectorAll('tr'));\n");
        w.write("  const isNumeric = !isNaN(parseFloat(rows[0].children[column].textContent));\n");
        w.write("  rows.sort((a, b) => {\n");
        w.write("    const aVal = a.children[column].textContent.trim();\n");
        w.write("    const bVal = b.children[column].textContent.trim();\n");
        w.write("    return isNumeric ? parseFloat(aVal) - parseFloat(bVal) : aVal.localeCompare(bVal);\n");
        w.write("  });\n");
        w.write("  rows.forEach(row => tbody.appendChild(row));\n");
        w.write("}\n");
        w.write("function toggleCollapsible(id) {\n");
        w.write("  const content = document.getElementById(id + 'Content');\n");
        w.write("  const icon = document.getElementById(id + 'Icon');\n");
        w.write("  content.classList.toggle('open');\n");
        w.write("  icon.classList.toggle('open');\n");
        w.write("}\n");
        w.write("</script>\n");
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 将 CloudSim 状态码转为大写安全名称（未知状态返回 UNKNOWN）。 */
    static String statusName(int status) {
        String name = Cloudlet.getStatusString(status);
        return name == null ? "UNKNOWN" : name.toUpperCase(Locale.US);
    }
}
