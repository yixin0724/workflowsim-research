package org.workflowsim.experiments.rerun;

/**
 * rerun 的穷举结论集合（见 {@code RERUN_DIFF_CONTRACT.md} 的报告格式一节）。
 *
 * <p>{@code IDENTICAL_CORE} 为唯一的成功结论，退出码 0；其余每个结论对应一个
 * 独立非零退出码，使 rerun 可直接作为 CI/门禁步骤使用。新增结论必须同步修订
 * 契约文档，不允许实现时绕过枚举。</p>
 */
public enum RerunVerdict {

    /** 全部核心量逐位一致。 */
    IDENTICAL_CORE(0),

    /** 至少一个核心量不一致，分歧清单非空。 */
    DIVERGED(1),

    /** 输入无法定位（候选路径全部落空）。 */
    INPUT_UNRESOLVED(2),

    /** 输入定位成功但 sha256 或大小与 manifest 记录不符。 */
    INPUT_HASH_MISMATCH(3),

    /** 配置重建被当前代码的校验拒绝（历史证据与当前语义不兼容的合法暴露）。 */
    RECONSTRUCTION_REJECTED(4),

    /** 原三件套自身未通过结构校验，或不是 manifest v4。 */
    EVIDENCE_INVALID(5);

    private final int exitCode;

    RerunVerdict(int exitCode) {
        this.exitCode = exitCode;
    }

    /** 作为进程退出码返回：成功为 0，其余结论各不相同。 */
    public int getExitCode() {
        return exitCode;
    }
}
