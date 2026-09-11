package org.workflowsim.exception;

/**
 * 当工作流输入文件无法解析或转换为合法 DAG 模型时抛出的异常。
 *
 * <p>此异常涵盖以下错误场景：</p>
 * <ul>
 *   <li>输入文件格式错误（XML/JSON 结构不合法）</li>
 *   <li>必需字段缺失或类型不匹配</li>
 *   <li>任务图包含环路，无法表示为 DAG</li>
 *   <li>文件依赖引用不存在的任务或文件</li>
 *   <li>WfCommons JSON 的 runtime 字段无法转换为 MI</li>
 * </ul>
 *
 * <p>调用方应将此异常视为输入数据问题，而不是模拟器内部错误。科研人员需要检查
 * 输入文件的完整性和格式正确性。</p>
 *
 * @see org.workflowsim.WorkflowParser
 * @see org.workflowsim.WfCommonsJsonParser
 * @see org.workflowsim.WorkflowValidationException
 */
public final class WorkflowParsingException extends WorkflowSimException {

    private final String inputPath;

    /**
     * 使用解析错误描述创建异常。
     *
     * @param message 解析失败的具体说明
     */
    public WorkflowParsingException(String message) {
        super(message);
        this.inputPath = null;
    }

    /**
     * 使用解析错误描述和输入路径创建异常。
     *
     * @param message 解析失败的具体说明
     * @param inputPath 导致解析失败的输入文件路径
     */
    public WorkflowParsingException(String message, String inputPath) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""));
        this.inputPath = inputPath;
    }

    /**
     * 使用解析错误描述、输入路径和根因创建异常。
     *
     * @param message 解析失败的具体说明
     * @param inputPath 导致解析失败的输入文件路径
     * @param cause 导致解析失败的底层异常
     */
    public WorkflowParsingException(String message, String inputPath, Throwable cause) {
        super(message + (inputPath != null ? " (input: " + inputPath + ")" : ""), cause);
        this.inputPath = inputPath;
    }

    /**
     * 返回导致解析失败的输入文件路径。
     *
     * @return 输入文件路径，若未指定则为 {@code null}
     */
    public String getInputPath() {
        return inputPath;
    }
}
