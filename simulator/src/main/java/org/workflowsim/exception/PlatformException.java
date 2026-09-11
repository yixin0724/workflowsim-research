package org.workflowsim.exception;

/**
 * 当平台资源配置或容量约束无法满足时抛出的异常。
 *
 * <p>此异常专门处理与 {@link org.workflowsim.platform.PlatformProfile}、
 * Host、VM、存储资源相关的错误，包括：</p>
 * <ul>
 *   <li>VM-to-Host 放置不可行（PE 数量或容量不足）</li>
 *   <li>Host 配置无效（如负数 MIPS 或 RAM）</li>
 *   <li>VM 配置与 Host 兼容性检查失败</li>
 *   <li>存储带宽或成本定价信息缺失</li>
 *   <li>CloudSim 数据中心或存储实体创建失败</li>
 * </ul>
 *
 * <p>调用方应在构建平台配置时预检资源约束，使用
 * {@link org.workflowsim.platform.PlatformProfile} 的容量验证功能。</p>
 *
 * <p><strong>注：</strong>此异常继承自 {@link RuntimeException}，因为平台配置错误
 * 属于编程错误或配置不当，应该快速失败而不是要求调用方捕获。</p>
 */
public final class PlatformException extends RuntimeException {

    /**
     * 使用平台错误描述创建异常。
     *
     * @param message 平台错误的具体说明
     */
    public PlatformException(String message) {
        super(message);
    }

    /**
     * 使用平台错误描述和根因创建异常。
     *
     * @param message 平台错误的具体说明
     * @param cause 导致平台错误的底层异常
     */
    public PlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
