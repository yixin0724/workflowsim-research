package org.workflowsim;

/**
 * 当工作流输入无法表示为合法任务有向无环图（DAG）时抛出的异常。
 *
 * <p>调用方应将此异常视为输入或输入转换结果不满足模型约束，而不是一次可重试的
 * 仿真运行失败。</p>
 */
public final class WorkflowValidationException extends IllegalArgumentException {

    /**
     * 使用验证失败原因创建异常。
     *
     * @param message 面向调用方的验证失败说明
     */
    public WorkflowValidationException(String message) {
        super(message);
    }

    /**
     * 使用验证失败原因和根因创建异常。
     *
     * @param message 面向调用方的验证失败说明
     * @param cause 导致验证失败的底层异常
     */
    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
