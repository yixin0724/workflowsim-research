package org.workflowsim.utils;

/** 随附 CloudSim 事件内核要求的公共时间规则。 */
public final class SimulationTiming {

    /** WorkflowDatacenter 的历史完成事件保护量，单位为模拟秒。 */
    public static final double CLOUDLET_COMPLETION_SAFETY_SECONDS = 0.01;

    private SimulationTiming() {
    }

    /**
     * 计算 WorkflowDatacenter 可接受的最早 Cloudlet 完成时间。
     *
     * <p>该值取内核预测完成时间和“当前时间 + 最小事件间隔 + 完成保护量”中的较大者，
     * 以避免零延迟或过近事件违反 CloudSim 内核约束。</p>
     *
     * @param currentTime 当前模拟时间
     * @param predictedCompletionTime 内核预测的完成时间
     * @param minimumEventIntervalSeconds 内核允许的最小事件间隔，单位为模拟秒
     * @return 可接受的最早完成时间
     */
    public static double earliestCloudletCompletionTime(double currentTime,
            double predictedCompletionTime, double minimumEventIntervalSeconds) {
        if (!isFiniteNonNegative(currentTime) || !isFiniteNonNegative(predictedCompletionTime)
                || !isFiniteNonNegative(minimumEventIntervalSeconds)) {
            throw new IllegalArgumentException("CloudSim timing values must be finite and non-negative");
        }
        return Math.max(predictedCompletionTime, currentTime + minimumEventIntervalSeconds
                + CLOUDLET_COMPLETION_SAFETY_SECONDS);
    }

    private static boolean isFiniteNonNegative(double value) {
        return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
