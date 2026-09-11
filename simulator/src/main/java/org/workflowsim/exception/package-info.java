/**
 * WorkflowSim 模拟器的异常类型体系。
 *
 * <p>此包提供精确的异常分类，帮助科研人员快速定位问题根源：</p>
 *
 * <ul>
 *   <li>{@link org.workflowsim.exception.WorkflowSimException} -
 *       所有 WorkflowSim 异常的根基类</li>
 *   <li>{@link org.workflowsim.exception.SimulationConfigurationException} -
 *       配置参数、算法组合、模型约束验证失败</li>
 *   <li>{@link org.workflowsim.exception.WorkflowParsingException} -
 *       输入文件解析、DAG 转换、字段映射错误</li>
 *   <li>{@link org.workflowsim.exception.SimulationExecutionException} -
 *       CloudSim 执行、事件处理、调度算法运行时错误</li>
 *   <li>{@link org.workflowsim.exception.PlatformException} -
 *       平台资源配置、容量约束、VM-to-Host 放置错误</li>
 * </ul>
 *
 * <h2>使用指南</h2>
 *
 * <p><strong>科研人员视角</strong>：当实验失败时，异常类型直接指示需要检查的部分：</p>
 * <ul>
 *   <li>{@code SimulationConfigurationException} → 检查 {@code SimulationConfig} 和算法组合</li>
 *   <li>{@code WorkflowParsingException} → 检查输入文件格式和 DAG 完整性</li>
 *   <li>{@code PlatformException} → 检查 Host/VM 资源配置的可行性</li>
 *   <li>{@code SimulationExecutionException} → 查看日志，可能需要报告给维护者</li>
 * </ul>
 *
 * <p><strong>开发者视角</strong>：选择最精确的异常类型抛出：</p>
 * <pre>{@code
 * // 配置验证
 * if (config.getVmCount() != platform.getVms().size()) {
 *     throw new SimulationConfigurationException(
 *         "VM count mismatch: config=" + config.getVmCount() +
 *         ", platform=" + platform.getVms().size());
 * }
 *
 * // 输入解析
 * if (!dagValidator.isAcyclic(tasks)) {
 *     throw new WorkflowParsingException(
 *         "Workflow contains cycles", inputPath);
 * }
 *
 * // 平台资源
 * if (totalRequiredPes > host.getNumberOfPes()) {
 *     throw new PlatformException(
 *         "Host PE capacity insufficient: required=" + totalRequiredPes +
 *         ", available=" + host.getNumberOfPes());
 * }
 * }</pre>
 *
 * <h2>与遗留异常的关系</h2>
 *
 * <ul>
 *   <li>{@link org.workflowsim.WorkflowValidationException} 继续用于 DAG 结构验证，
 *       现在应视为 {@code WorkflowParsingException} 的特定场景</li>
 *   <li>{@link org.workflowsim.failure.RetryLimitExceededException} 是受保护预算的
 *       运行时终止条件，保持其 {@code IllegalStateException} 继承关系</li>
 * </ul>
 *
 * @since WorkflowSim 1.0
 */
package org.workflowsim.exception;
