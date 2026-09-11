package org.workflowsim.exception;

/**
 * 当仿真配置不满足模型约束或算法前置条件时抛出的异常。
 *
 * <p>此异常表示 {@link org.workflowsim.utils.SimulationConfig}、
 * {@link org.workflowsim.platform.PlatformProfile}、
 * {@link org.workflowsim.failure.FailureModelConfig} 或其他配置对象的参数无效或不兼容。</p>
 *
 * <p>典型场景：</p>
 * <ul>
 *   <li>VM 数量与平台 VM 规格数量不匹配</li>
 *   <li>算法组合不被标准运行器支持</li>
 *   <li>共享存储 DAG 规划器要求的前置条件未满足（如开销已启用）</li>
 *   <li>成本模型要求的 VM 定价信息缺失</li>
 *   <li>随机种子或数值参数超出合法范围</li>
 * </ul>
 *
 * <p>调用方应在仿真启动前检查和修正配置，而不是将此异常视为可重试的运行时错误。</p>
 *
 * <p><strong>注：</strong>此异常继承自 {@link RuntimeException}，因为配置错误通常在启动前
 * 就应该被发现，不需要强制捕获。这符合"快速失败"（fail-fast）的设计原则。</p>
 */
public final class SimulationConfigurationException extends RuntimeException {

    /**
     * 使用配置错误描述创建异常。
     *
     * @param message 配置错误的具体说明
     */
    public SimulationConfigurationException(String message) {
        super(message);
    }

    /**
     * 使用配置错误描述和根因创建异常。
     *
     * @param message 配置错误的具体说明
     * @param cause 导致配置验证失败的底层异常
     */
    public SimulationConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
