/*
 * Copyright 2013-2014 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.workflowsim.examples;

import com.google.gson.Gson;
import java.io.File;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.cloudbus.cloudsim.core.CloudSim;
import org.jdom2.Document;
import org.workflowsim.WorkflowEngine;

/**
 * 逐进程运行 {@link ExampleCatalog} 中历史教学 CLI 的聚合入口。
 *
 * <p>每个示例都在独立 JVM 中启动，避免 CloudSim、Parameters、ReplicaCatalog 和故障模型
 * 的静态状态跨示例泄漏。该工具只验证默认教学场景是否能完成，不能证明算法论文等价性、
 * 统计显著性或真实平台模型有效性。</p>
 */
public final class WorkflowSimAllExamplesTester {

    private static final long EXAMPLE_TIMEOUT_SECONDS = 90L;

    private WorkflowSimAllExamplesTester() {
    }

    /**
     * 运行全部 catalog 示例，或使用 {@code --list} 列出稳定标识和主类。
     *
     * @param args 空数组，或唯一参数 {@code --list}
     * @throws Exception 当任一子进程无法启动、超时或以非零状态退出时
     */
    public static void main(String[] args) throws Exception {
        if (args != null && args.length == 1 && "--list".equals(args[0])) {
            listExamples();
            return;
        }
        if (args != null && args.length != 0) {
            throw new IllegalArgumentException("WorkflowSimAllExamplesTester accepts only --list");
        }

        String classpath = runtimeClasspath();
        int completed = 0;
        for (ExampleCatalog.Descriptor descriptor : ExampleCatalog.legacyExamples()) {
            runExample(classpath, descriptor);
            completed++;
        }
        System.out.println("EXAMPLE_SUITE_COMPLETED count=" + completed);
    }

    private static void listExamples() {
        for (ExampleCatalog.Descriptor descriptor : ExampleCatalog.legacyExamples()) {
            System.out.println(descriptor.getId() + " " + descriptor.getMainClass());
        }
    }

    private static void runExample(String classpath, ExampleCatalog.Descriptor descriptor)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp", classpath,
                descriptor.getMainClass());
        builder.directory(new File(System.getProperty("user.dir")));
        builder.inheritIO();
        Process process = builder.start();
        if (!process.waitFor(EXAMPLE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Historical example timed out after "
                    + EXAMPLE_TIMEOUT_SECONDS + " seconds: " + descriptor.getId());
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Historical example failed with exit code "
                    + process.exitValue() + ": " + descriptor.getId());
        }
    }

    /**
     * 从运行中已加载的模块与依赖组装子 JVM 类路径。
     *
     * <p>不能直接依赖 {@code java.class.path}，因为 Maven exec 插件可能把项目依赖放在
     * 独立类加载器中。锚定实际已加载类可同时支持 Maven、IDE 和打包后的运行路径。</p>
     */
    // 同包的进程级 CLI 测试复用这条与 Maven exec 兼容的类路径组装逻辑。
    static String runtimeClasspath() {
        Set<String> entries = new LinkedHashSet<String>();
        addCodeSource(entries, WorkflowSimAllExamplesTester.class);
        addCodeSource(entries, WorkflowEngine.class);
        addCodeSource(entries, CloudSim.class);
        addCodeSource(entries, Document.class);
        addCodeSource(entries, Gson.class);
        addCodeSource(entries, WeibullDistribution.class);
        if (entries.isEmpty()) {
            throw new IllegalStateException("Unable to build an isolated example runtime classpath");
        }
        StringBuilder value = new StringBuilder();
        for (String entry : entries) {
            if (value.length() > 0) {
                value.append(File.pathSeparatorChar);
            }
            value.append(entry);
        }
        return value.toString();
    }

    private static void addCodeSource(Set<String> entries, Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new IllegalStateException("No code source is available for " + type.getName());
        }
        try {
            entries.add(new File(source.getLocation().toURI()).getPath());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid code source for " + type.getName(), exception);
        }
    }

    private static String javaExecutable() {
        return new File(System.getProperty("java.home"), "bin/java").getPath();
    }
}
