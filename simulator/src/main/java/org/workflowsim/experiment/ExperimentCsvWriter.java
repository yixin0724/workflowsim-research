package org.workflowsim.experiment;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 将仿真结果导出为人类可读的 CSV 表格文件。
 *
 * <p>生成三个 CSV 文件供 Excel / Python pandas / R 分析：
 * <ul>
 * <li>{@code <runId>.jobs.csv} - 每个作业的执行明细</li>
 * <li>{@code <runId>.tasks.csv} - 每个任务的执行明细</li>
 * <li>{@code <runId>.vms.csv} - 每个 VM 的统计摘要</li>
 * </ul>
 *
 * <p>CSV 格式：UTF-8 编码，逗号分隔，带表头行。
 */
public final class ExperimentCsvWriter {

    private ExperimentCsvWriter() {
    }

    /**
     * 写入完整 CSV 工件包（jobs/tasks/vms）。
     *
     * @param report 已完成仿真的不可变报告
     * @param outputDirectory 存放 CSV 文件的输出目录
     * @param runId 文件名安全的运行标识
     * @return 三个已写入 CSV 文件的绝对路径
     * @throws IOException 当目录或文件无法写入时抛出
     * @throws IllegalArgumentException 当报告、输出目录或运行标识不合法时抛出
     */
    public static CsvArtifacts write(SimulationReport report, Path outputDirectory, String runId)
            throws IOException {
        if (report == null || outputDirectory == null || runId == null || runId.isEmpty()) {
            throw new IllegalArgumentException("Report, output directory and runId are required");
        }

        Path directory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(directory);

        Path jobsCsv = directory.resolve(runId + ".jobs.csv");
        Path tasksCsv = directory.resolve(runId + ".tasks.csv");
        Path vmsCsv = directory.resolve(runId + ".vms.csv");

        writeJobsCsv(report, jobsCsv);
        writeTasksCsv(report, tasksCsv);
        writeVmsCsv(report, vmsCsv);

        return new CsvArtifacts(jobsCsv, tasksCsv, vmsCsv);
    }

    private static void writeJobsCsv(SimulationReport report, Path target) throws IOException {
        // L1：从 JOB_READY 事件恢复每个作业的 readyTime，使 jobs.csv 能直接支撑
        // 总等待时间（startTime - readyTime）的逐作业分析，无需回到 events.jsonl。
        java.util.Map<Integer, Double> readyTimes = new java.util.LinkedHashMap<Integer, Double>();
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.JOB_READY && event.getJobId() != null) {
                readyTimes.put(event.getJobId(), Double.valueOf(event.getSimulationTime()));
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            // 表头
            writer.write("jobId,vmId,status,statusName,classType,");
            writer.write("submissionTime,readyTime,startTime,finishTime,totalWaitingTime,");
            writer.write("vmQueueWaitingTime,executionTime,responseTime,");
            writer.write("cpuTime,taskCount,modeledCpuCost,modeledBandwidthCost,modeledTotalCost,modeledFileBytes");
            writer.newLine();

            // 数据行
            for (SimulationReport.JobOutcome job : report.getJobs()) {
                Double readyTime = readyTimes.get(Integer.valueOf(job.getJobId()));
                String readyTimeText;
                String totalWaitingText;
                if (readyTime != null && job.getStartTime() >= readyTime.doubleValue()) {
                    readyTimeText = String.format(java.util.Locale.US, "%.6f", readyTime);
                    totalWaitingText = String.format(java.util.Locale.US, "%.6f",
                            job.getStartTime() - readyTime.doubleValue());
                } else {
                    // 无 JOB_READY 观测或时钟异常（start<ready）时留空，避免用 0 冒充
                    // 测量值。注意：本列覆盖全部作业（stage-in 作业通常也有 JOB_READY，
                    // 会被填充），而 metrics.json 的总等待/减速比分布只统计计算作业——
                    // 从 CSV 重算均值时需先按 classType=2（compute）过滤。
                    readyTimeText = "";
                    totalWaitingText = "";
                }
                writer.write(String.format(java.util.Locale.US,
                        "%d,%d,%d,%s,%d,%.6f,%s,%.6f,%.6f,%s,%.6f,%.6f,%.6f,%.6f,%d,%.6f,%.6f,%.6f,%.6f",
                        job.getJobId(),
                        job.getVmId(),
                        job.getStatus(),
                        ExperimentHtmlReportWriter.statusName(job.getStatus()),
                        job.getClassType(),
                        job.getSubmissionTime(),
                        readyTimeText,
                        job.getStartTime(),
                        job.getFinishTime(),
                        totalWaitingText,
                        job.getWaitingTime(),
                        job.getExecutionTime(),
                        job.getResponseTime(),
                        job.getCpuTime(),
                        job.getTaskCount(),
                        job.getModeledCpuEnvelopeCost(),
                        job.getModeledDeclaredFileBandwidthCost(),
                        job.getModeledProcessingCost(),
                        job.getModeledDeclaredFileBytes()));
                writer.newLine();
            }
        }
    }

    private static void writeTasksCsv(SimulationReport report, Path target) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            // 表头
            writer.write("taskId,jobId,vmId,jobStatus,jobStatusName,taskStatus,taskStatusName,");
            writer.write("depth,lengthMi,startTime,finishTime,duration,exactJobTiming");
            writer.newLine();

            // 数据行
            for (SimulationReport.TaskOutcome task : report.getTasks()) {
                double duration = task.getFinishTime() - task.getStartTime();
                writer.write(String.format(java.util.Locale.US,
                        "%d,%d,%d,%d,%s,%d,%s,%d,%d,%.6f,%.6f,%.6f,%s",
                        task.getTaskId(),
                        task.getJobId(),
                        task.getVmId(),
                        task.getJobStatus(),
                        ExperimentHtmlReportWriter.statusName(task.getJobStatus()),
                        task.getTaskStatus(),
                        ExperimentHtmlReportWriter.statusName(task.getTaskStatus()),
                        task.getDepth(),
                        task.getLengthMi(),
                        task.getStartTime(),
                        task.getFinishTime(),
                        duration,
                        task.hasExactJobTiming()));
                writer.newLine();
            }
        }
    }

    private static void writeVmsCsv(SimulationReport report, Path target) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            // 表头
            writer.write("vmId,jobCount,reportedCpuTimeSeconds,modeledBusyIntervalSeconds,");
            writer.write("modeledUtilization,reportedCpuTimeOverMakespan");
            writer.newLine();

            // 数据行
            SimulationMetrics metrics = report.getMetrics();
            for (SimulationMetrics.VmMetrics vm : metrics.getVmMetrics().values()) {
                writer.write(String.format(java.util.Locale.US, "%d,%d,%.6f,%.6f,%.6f,%.6f",
                        vm.getVmId(),
                        vm.getJobOutcomeCount(),
                        vm.getReportedCpuTimeSeconds(),
                        vm.getModeledBusyIntervalSeconds(),
                        vm.getModeledIntervalUtilization(),
                        vm.getReportedCpuTimeOverMakespan()));
                writer.newLine();
            }
        }
    }

    /** 三个已写入 CSV 文件的绝对路径容器。 */
    public static final class CsvArtifacts {
        private final Path jobs;
        private final Path tasks;
        private final Path vms;

        CsvArtifacts(Path jobs, Path tasks, Path vms) {
            this.jobs = jobs;
            this.tasks = tasks;
            this.vms = vms;
        }

        public Path getJobs() { return jobs; }
        public Path getTasks() { return tasks; }
        public Path getVms() { return vms; }
    }
}
