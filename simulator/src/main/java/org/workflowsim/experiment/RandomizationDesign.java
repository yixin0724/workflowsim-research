package org.workflowsim.experiment;

/**
 * 声明一个实验 campaign 的种子序列允许采用的统计解释。
 *
 * <p>该声明会随结果记录，但不会改变模拟器的随机模型。</p>
 */
public enum RandomizationDesign {

    /** 单次确定性运行，因此不适用抽样推断。 */
    DETERMINISTIC,

    /** 独立重复，允许计算每个 cell 的 Student-t 均值区间。 */
    INDEPENDENT_REPLICATIONS,

    /**
     * 候选运行复用根种子，但当前模型不是事件键控模型，因此不能主张
     * common-random-numbers 配对推断。
     */
    COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN
}
