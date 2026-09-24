/**
 * R11 rerun 与核心量差异比对（D2）：从历史 v4 证据重建运行并与当前代码结果比对。
 *
 * <p>本包只消费证据，不修改模拟器语义。流程契约见
 * {@code docs/experiments/RERUN_DIFF_CONTRACT.md}：读取三件套 → 输入定位（sha256
 * 核对）→ 配置重建 → 经标准 {@code SimulationRunner} 重跑 → 核心量精确比对 →
 * 生成 verdict 报告。核心量要求序列化值逐位一致，浮点不设容差；provenance、
 * runtime 与本机 wall-clock 字段属显式枚举的易变量白名单，不参与判定。</p>
 *
 * <p>v1 范围：单个 run 目录、manifest v4。历史 v2/v3 证据可读但不可 rerun，
 * 会被明确拒绝；结果复用缓存与 study 级批量不在本包范围内。</p>
 */
package org.workflowsim.experiments.rerun;
