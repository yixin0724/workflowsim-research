package org.workflowsim.failure;

/**
 * 表示一次受配置保护的仿真超过了允许创建的重试 Job 总数。
 *
 * <p>这不是一个已完成的实验结果。调用方必须记录异常和配置，而不能把部分执行结果当作
 * 故障恢复指标。遗留静态 API 未设置此预算时保持历史的无上限行为。</p>
 */
public final class RetryLimitExceededException extends IllegalStateException {

    private final int failedJobId;
    private final int createdRetryJobs;
    private final int requestedRetryJobs;
    private final int maxTotalRetryJobs;
    private final long rootSeed;

    public RetryLimitExceededException(int failedJobId, int createdRetryJobs,
            int requestedRetryJobs, int maxTotalRetryJobs, long rootSeed) {
        super("Retry budget exceeded for failed Job " + failedJobId
                + ": created=" + createdRetryJobs
                + ", requested=" + requestedRetryJobs
                + ", maxTotalRetryJobs=" + maxTotalRetryJobs
                + ", rootSeed=" + rootSeed);
        this.failedJobId = failedJobId;
        this.createdRetryJobs = createdRetryJobs;
        this.requestedRetryJobs = requestedRetryJobs;
        this.maxTotalRetryJobs = maxTotalRetryJobs;
        this.rootSeed = rootSeed;
    }

    public int getFailedJobId() { return failedJobId; }
    public int getCreatedRetryJobs() { return createdRetryJobs; }
    public int getRequestedRetryJobs() { return requestedRetryJobs; }
    public int getMaxTotalRetryJobs() { return maxTotalRetryJobs; }
    public long getRootSeed() { return rootSeed; }
}
