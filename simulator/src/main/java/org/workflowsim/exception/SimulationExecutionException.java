package org.workflowsim.exception;

/**
 * 当 CloudSim 仿真执行过程中发生无法恢复的错误时抛出的异常。
 *
 * <p>此异常表示仿真执行层的失败，包括但不限于：</p>
 * <ul>
 *   <li>CloudSim 实体创建失败</li>
 *   <li>事件处理流程异常</li>
 *   <li>VM 创建失败或 VM-to-Host 映射不一致</li>
 *   <li>调度或规划算法执行时的内部错误</li>
 *   <li>数据移动模型计算异常</li>
 * </ul>
 *
 * <p>此异常与配置错误（{@link SimulationConfigurationException}）的区别在于：
 * 配置错误是启动前可检测的静态约束违反，而执行异常是运行时动态发生的失败。</p>
 *
 * <p>科研人员遇到此异常时应检查：</p>
 * <ol>
 *   <li>模拟器日志中的详细堆栈信息</li>
 *   <li>配置与输入的组合是否触发了未预期的边界情况</li>
 *   <li>是否需要向模拟器维护者报告潜在的实现缺陷</li>
 * </ol>
 */
public final class SimulationExecutionException extends WorkflowSimException {

    /**
     * 使用执行错误描述创建异常。
     *
     * @param message 执行失败的具体说明
     */
    public SimulationExecutionException(String message) {
        super(message);
    }

    /**
     * 使用执行错误描述和根因创建异常。
     *
     * @param message 执行失败的具体说明
     * @param cause 导致执行失败的底层异常
     */
    public SimulationExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用根因创建异常。
     *
     * @param cause 导致执行失败的底层异常
     */
    public SimulationExecutionException(Throwable cause) {
        super(cause);
    }
}
