# 论文指标口径卡（Metrics Writing Card）

> 供撰写论文/报告时正确引用本平台指标的一页式参考。每行三件事：我方口径（公式/样本域）→ 与文献最相近口径的差异一句话 → 写作建议。
> 权威定义以[算法、执行与指标契约](<CONTRACTS.zh.md>)为准；本文只集中"引用差异"，不新增语义。
> 审查工程来源：科学效度审计链 A（2026-10-20）。

[English](<METRICS_CARD.md>) · **简体中文**

## 时间族

| 指标 | 我方口径 | 与文献最相近口径的差异 | 写作建议 |
|---|---|---|---|
| `makespanSeconds` / `simulationEndSeconds` | 模拟终止时钟 | 文献 makespan 常指工作流完成跨度；我们的正值终态尾（post-delay、V3 写回）并入该值 | 注明"终态时钟"口径；比较执行器开销时同时给 `terminalLifecycleTailSeconds` |
| `logicalTaskCompletionSeconds` | 全部逻辑 Task 成功时，首次成功信封完成时刻最大值 | 与上一口径只差尾部；本指标更贴近文献 makespan | 两口径都报告并给出差值，勿混称 |
| `terminalLifecycleTailSeconds` | 终态 − 逻辑完成 | 文献无直接对应 | 引用为"写回/收尾尾巴"，非 CPU |
| `computeJobOutcomeThroughputPerSecond` | 计算尝试数 / 终态时钟 | 分母含尾部 | 口径注明即可 |

## 等待与减速比族

| 指标 | 我方口径 | 与文献最相近口径的差异 | 写作建议 |
|---|---|---|---|
| `wait_j`（等待均值的前体） | `JOB_READY → start`（依赖就绪后等待） | 文献常见 submit→start 纯排队 | 论文写"就绪后等待"；VM 排队口径用 `meanJobVmQueueWaitingTimeSeconds` |
| `meanComputeTrueSlowdown`（含中位/分位/最大/成功样本变体） | `max((wait+exec)/max(exec,10),1)`，就绪基线样本 | 文献"bounded slowdown"主族同为分母地板（有 `1+Q/max(R,10)` 先例，地板同取 10）；跨论文差异主要在地板取值（1s/0.1s 变体）与分子窗口（文献多为 submit 基线） | 引用时给出公式+地板+窗口；字段名的"True"勿解读为 unbounded stretch；跨论文只做同口径比较 |
| p95 / 中位数（各变体） | 升序样本最近秩 `ceil(qn)` | 插值型分位与最近秩有系统差（小样本 p95 退化为最大） | 固定声明口径，不跨论文合并分位值 |

## 计数、利用率与公平族

| 指标 | 我方口径 | 与文献最相近口径的差异 | 写作建议 |
|---|---|---|---|
| `logicalTaskAttemptCount` | 含 retry 尝试 | 文献或按任务计数 | 引用时给出定义 |
| `retryAmplificationRatio` | 尝试数 / 逻辑 Task 数 | 不是失败率 | 报告附公式 |
| 成功率族 | 计算尝试基率与逻辑任务率并存 | 分母选择易混淆 | 注明分母 |
| `vmMetrics` | 每 VM 已完成 Job 区间并集 / 终态时钟 | 文献常见 busy/end 一致 | 声明含 stage-in/失败/retry 的区间组成 |
| `vmUtilizationJainFairnessIndex` | `(Σu)²/(nΣu²)`；空/全零→1.0 | 文献 Jain 公式一致 | 退化语义声明后引用 |

## 数据传输族

| 指标 | 我方口径 | 与文献最相近口径的差异 | 写作建议 |
|---|---|---|---|
| DATA_STAGE_IN_MODELED 族 | 名义估计（无争用隔离秒数、引用字节、真实外输入） | 文献常笼统称"流量" | 区分名义估计与实际争用墙钟；注明是引用字节而非传输字节 |
| 争用 FCT/利用率 | 侧车账本精确会计 | 详见 NETWORK_LEDGER_VALIDATION 契约 | 引用该契约口径 |

## 引用纪律（性能评估小节模板句）

1. 每个报告指标同时给出：口径公式（或引用本卡）+ 样本域（retry/失败/stage-in 计入与否）。
2. 涉及减速比/等待：声明窗口基线（就绪 vs 提交）与分母地板（10 秒）。
3. 跨论文比较仅限同口径同分母；"bounded slowdown" 引用注明地板值。
4. 威胁效度段固定声明：所有指标是声明模拟模型的产物，不是生产系统测量。
