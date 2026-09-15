package org.workflowsim.rl;

/**
 * 一次成功分派的记录：决策时刻、Job 与目标 VM。跳过的 Job 不产生决策记录。
 */
public final class RlDecision {

    private final double time;
    private final int jobId;
    private final int vmId;

    RlDecision(double time, int jobId, int vmId) {
        this.time = time;
        this.jobId = jobId;
        this.vmId = vmId;
    }

    /** @return 分派时刻的仿真时钟（秒） */
    public double getTime() {
        return time;
    }

    /** @return 被分派的 Job ID */
    public int getJobId() {
        return jobId;
    }

    /** @return 分派目标 VM ID */
    public int getVmId() {
        return vmId;
    }
}
