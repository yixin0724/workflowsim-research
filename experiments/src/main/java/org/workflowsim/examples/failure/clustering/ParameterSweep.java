/*
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package org.workflowsim.examples.failure.clustering;

import org.workflowsim.examples.ExampleCliSupport;

/**
 * 历史容错聚类参数扫描辅助工具。
 *
 * <p>本类不再在无参数时隐式启动 125,000 次模拟。真正的研究参数扫描必须作为
 * {@code experiments/studies/<study-id>} 下具有冻结配置、种子计划、比较组、停止条件和
 * 结果工件的 campaign 实现；这里仅保留有界 smoke 入口及供旧调用方复用的单次运行方法。</p>
 *
 * @author chenweiwei
 */
public final class ParameterSweep {

    private static final String DEFAULT_DAX = ExampleCliSupport.DEFAULT_DAX_PATH;
    private static final String DEFAULT_CLUSTERING = "DR";
    private static final String SMOKE_COMPLETION_PREFIX = "PARAMETER_SWEEP_SMOKE_COMPLETED";

    private ParameterSweep() {
    }

    /**
     * 仅运行显式请求的一格、一重复 smoke。
     *
     * @param args 必须是唯一参数 {@code --smoke}
     */
    public static void main(String[] args) {
        if (args == null || args.length != 1 || !"--smoke".equals(args[0])) {
            throw new IllegalArgumentException("ParameterSweep is not a default experiment runner. "
                    + "Use --smoke for one bounded compatibility run; implement research scans "
                    + "as a protocol-driven study campaign.");
        }
        double makespan = execute("150000", 50.0, 10.0, 2.0, 30.0,
                DEFAULT_CLUSTERING, ExampleCliSupport.LEGACY_DEMO_SEED);
        if (!Double.isFinite(makespan) || makespan <= 0.0) {
            throw new IllegalStateException("Parameter sweep smoke produced an invalid makespan");
        }
        System.out.println(SMOKE_COMPLETION_PREFIX + " replications=1 makespan=" + makespan);
    }

    /**
     * 执行一个历史参数组合，使用固定默认根种子。
     *
     * <p>保留该签名是为了兼容现有教学调用。需要重复运行时应使用带根种子的重载，确保
     * 每个重复有可记录且可重放的随机条件。</p>
     */
    public static double execute(String failureScale, double queueScale, double queuePriorWeight,
            double queueShape, double failurePriorWeight, String clustering) {
        return execute(failureScale, queueScale, queuePriorWeight, queueShape,
                failurePriorWeight, clustering, ExampleCliSupport.LEGACY_DEMO_SEED);
    }

    /**
     * 执行一个历史参数组合并显式指定根种子。
     *
     * @param failureScale Weibull 故障尺度
     * @param queueScale Gamma 队列尺度
     * @param queuePriorWeight 队列旧估计器先验强度
     * @param queueShape Gamma 队列形状
     * @param failurePriorWeight 故障旧估计器先验强度
     * @param clustering SR、DR、NOOP 或 DC
     * @param rootSeed 本次运行的可重放根种子
     * @return 模型 makespan；失败时抛异常，不使用 sentinel
     */
    public static double execute(String failureScale, double queueScale, double queuePriorWeight,
            double queueShape, double failurePriorWeight, String clustering, long rootSeed) {
        String[] args = {"-d", DEFAULT_DAX,
            "-q", Double.toString(queueScale),
            "-w", Double.toString(queuePriorWeight),
            "-s", Double.toString(queueShape),
            "-p", failureScale,
            "-t", Double.toString(failurePriorWeight),
            "-c", clustering,
            "-r", Long.toString(rootSeed)};
        return FaultTolerantClusteringExample5.main2(args);
    }

    /**
     * 兼容历史“100 次平均”名称，但改为 100 个确定性且不同的根种子。
     *
     * <p>同一参数格中的种子序列是固定的；不同参数格只要传入同一 {@code rootSeed} 就能
     * 使用 common random numbers。该方法不输出研究证据，也不包含置信区间或停止规则。</p>
     */
    public static double execute100(String failureScale, double queueScale, double queuePriorWeight,
            double queueShape, double failurePriorWeight, String clustering) {
        return executeReplications(failureScale, queueScale, queuePriorWeight, queueShape,
                failurePriorWeight, clustering, 100, ExampleCliSupport.LEGACY_DEMO_SEED);
    }

    /** 执行给定数量的确定性重复，并返回算术平均 makespan。 */
    public static double executeReplications(String failureScale, double queueScale,
            double queuePriorWeight, double queueShape, double failurePriorWeight,
            String clustering, int replications, long rootSeed) {
        if (replications <= 0) {
            throw new IllegalArgumentException("replications must be greater than zero");
        }
        double sum = 0.0;
        for (int replication = 0; replication < replications; replication++) {
            double makespan = execute(failureScale, queueScale, queuePriorWeight, queueShape,
                    failurePriorWeight, clustering, rootSeed + replication);
            if (!Double.isFinite(makespan) || makespan <= 0.0) {
                throw new IllegalStateException("Replication " + replication
                        + " produced an invalid makespan");
            }
            sum += makespan;
            if (!Double.isFinite(sum)) {
                throw new IllegalStateException("Mean makespan accumulation overflowed");
            }
        }
        return sum / replications;
    }
}
