package org.workflowsim.examples;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 以独立 JVM 运行教学 CLI 的测试辅助类。
 *
 * <p>WorkflowSim 的旧式入口依赖 CloudSim 和多个 WorkflowSim 静态状态。测试不能在
 * 同一 JVM 内依次调用这些 {@code main} 方法，否则一个入口的状态可能掩盖另一个入口的
 * 初始化缺陷。该辅助类复用示例聚合器的运行时类路径策略，但把每次子进程输出留在 JUnit
 * 临时目录中，便于失败时提供可诊断的尾部日志。</p>
 */
final class CliProcessTestSupport {

    static final long DEFAULT_TIMEOUT_SECONDS = 90L;
    private static final int DIAGNOSTIC_OUTPUT_LIMIT = 12_000;

    private CliProcessTestSupport() {
    }

    /**
     * 从项目根目录启动一个主类，并将 stdout/stderr 合并写入临时日志。
     *
     * @param workspaceRoot 包含 {@code datasets/} 的项目根目录
     * @param logDirectory JUnit 管理的临时日志目录
     * @param mainClass 要执行的完整主类名
     * @param arguments 传给主类的命令行参数
     * @return 子进程的退出、超时和输出信息
     * @throws IOException 当子进程或日志文件无法创建时
     */
    static ProcessResult runMain(Path workspaceRoot, Path logDirectory, String mainClass,
            String... arguments) throws IOException {
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            throw new IllegalArgumentException("Workspace root must be an existing directory: "
                    + workspaceRoot);
        }
        if (logDirectory == null) {
            throw new IllegalArgumentException("Log directory cannot be null");
        }
        if (mainClass == null || mainClass.trim().isEmpty()) {
            throw new IllegalArgumentException("Main class cannot be empty");
        }
        Files.createDirectories(logDirectory);
        Path output = Files.createTempFile(logDirectory, "workflowsim-cli-", ".log");

        List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        command.add("-cp");
        command.add(WorkflowSimAllExamplesTester.runtimeClasspath());
        command.add(mainClass);
        if (arguments != null) {
            command.addAll(Arrays.asList(arguments));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workspaceRoot.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();

        boolean finished;
        try {
            finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + mainClass, exception);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(10L, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping timed out process "
                        + mainClass, exception);
            }
        }

        String contents = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        return new ProcessResult(mainClass, output, finished,
                finished ? process.exitValue() : -1, contents);
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/java").getPath();
    }

    /** 一个已终止子进程的不可变测试结果。 */
    static final class ProcessResult {
        private final String mainClass;
        private final Path outputPath;
        private final boolean finished;
        private final int exitCode;
        private final String output;

        ProcessResult(String mainClass, Path outputPath, boolean finished, int exitCode,
                String output) {
            this.mainClass = mainClass;
            this.outputPath = outputPath;
            this.finished = finished;
            this.exitCode = exitCode;
            this.output = output;
        }

        boolean isTimedOut() {
            return !finished;
        }

        int getExitCode() {
            return exitCode;
        }

        String getOutput() {
            return output;
        }

        /** 返回包含日志路径和尾部输出的断言诊断信息。 */
        String diagnostic() {
            String tail = output;
            if (tail.length() > DIAGNOSTIC_OUTPUT_LIMIT) {
                tail = "[output truncated; final " + DIAGNOSTIC_OUTPUT_LIMIT + " characters follow]\n"
                        + tail.substring(tail.length() - DIAGNOSTIC_OUTPUT_LIMIT);
            }
            return "mainClass=" + mainClass + " exitCode=" + exitCode
                    + " timedOut=" + isTimedOut() + " log=" + outputPath + "\n" + tail;
        }
    }
}
