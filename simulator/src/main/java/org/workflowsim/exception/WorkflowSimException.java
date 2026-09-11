package org.workflowsim.exception;

/**
 * WorkflowSim 模拟器异常的根基类。
 *
 * <p>所有 WorkflowSim 特定异常都应继承此类或其子类，以便调用方能够统一捕获和处理
 * 模拟器相关错误。此异常是 checked exception，要求调用方显式处理或声明抛出。</p>
 *
 * <p>异常层次结构：</p>
 * <ul>
 *   <li>{@code WorkflowSimException} - 根基类</li>
 *   <li>{@code SimulationConfigurationException} - 配置错误</li>
 *   <li>{@code WorkflowParsingException} - 输入解析错误</li>
 *   <li>{@code SimulationExecutionException} - 仿真执行错误</li>
 *   <li>{@code PlatformException} - 平台/资源错误</li>
 * </ul>
 *
 * @see SimulationConfigurationException
 * @see WorkflowParsingException
 * @see SimulationExecutionException
 */
public class WorkflowSimException extends Exception {

    /**
     * 使用错误消息创建异常。
     *
     * @param message 描述错误原因的消息
     */
    public WorkflowSimException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和根因创建异常。
     *
     * @param message 描述错误原因的消息
     * @param cause 导致此异常的底层异常
     */
    public WorkflowSimException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用根因创建异常。
     *
     * @param cause 导致此异常的底层异常
     */
    public WorkflowSimException(Throwable cause) {
        super(cause);
    }
}
