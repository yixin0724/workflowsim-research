# 研究协议与保留证据

本目录保存研究特定的协议和来源说明，不是另一个 Maven 模块，也不是平台能力清单。研究驱动和测试位于实验模块的标准源码目录；通用运行操作见[Workbench](<../../docs/getting-started/WORKBENCH.md>)。

## 当前可执行协议

[协议修订与认证范围](<../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)是运行、注册矩阵和验证规则的维护入口。[NetworkStudyPlan](<../src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>)为以下三个变体注册不同身份；相同参数矩阵不代表相同执行模型。

| CLI 变体 | 当前协议 ID | full / smoke 运行单元 |
|---|---|---:|
| `r10`（默认） | `network-limited-r10-v3` | 504 / 36 |
| `peft-comparison` | `peft-comparison-r12-v2` | 126 / 18 |
| `sensitivity-r13` | `sensitivity-response-r13-v2` | 546 / 30 |

当前执行使用 `WORK_CONSERVING_TASK_EXECUTION_V2`；两个 PEFT 研究使用后继成本、出口 OCT 为零的标准递推。完整研究须显式执行到新目录，不是将 smoke 改名；旧协议身份只用于只读历史完整性检查。修正模型的保留研究及影响分析见[研究影响复验](<correctness-repair/RESULTS.md>)，不是对每次后续代码变更的重新认证。

## 历史材料与可选证据位置

以下记录描述各自当时的实现。**完整性校验通过不代表当前模型数值相同，也不是算法正确性或真实平台校准证明。** 表中的 `output/...` 均是相对于项目根的原工作区保留位置，不随普通检出提供，不保证当前机器存在；不要用新的运行覆盖它们。

| 历史记录 | 身份与范围 | 保留材料 / 可选位置 |
|---|---|---|
| Fat-tree campaign | R8 再标定版本；360 个单元，seed 91 | [来源与参数](<fattree-scheduling-campaign/RETENTION.md>)；仓库内保留 [JSON](<fattree-scheduling-campaign/campaign-results.json>) 和 [数值表](<fattree-scheduling-campaign/campaign-results.md>) |
| R10 首次试点 | 576 个单元；其中 12 个不兼容运行失败，未认证为完整比较 | `output/network-study-r10/`；[资格排除说明](<network-limited-r10/PROTOCOL.md>) |
| R10 合格矩阵 | `network-limited-r10-v2`；504 个单元 | [历史来源说明](<network-limited-r10/PROTOCOL.md>)；`output/network-study-r10-final/`；`output/network-study-r10-evidence.tar.gz` |
| R12 / S5 PEFT 比较 | `peft-comparison-r12-v1`；126 个单元；旧 PEFT 非标准 | `output/peft-comparison-r12/`；`output/peft-comparison-r12-evidence.tar.gz`；修正身份为 `peft-comparison-r12-v2` |
| R13 敏感性响应面 | `sensitivity-response-r13-v1`；546 个单元；旧 PEFT 非标准 | 原[预注册协议](<sensitivity-r13/PROTOCOL.md>)和[评估记录](<sensitivity-r13/RESULTS.md>)原地归档；`output/sensitivity-r13/`；`output/sensitivity-r13-evidence.tar.gz` |
| 正确性修复后复验 | 当前三种协议的冻结实现研究，Java 提交 `8681f9c` | [来源、数值变化与保留说明](<correctness-repair/RESULTS.md>)；`output/workbench-correctness-repair-97c66a0/studies/` |

### R12 来源与比较边界

旧 R12 使用与 R10 相同的 7 个合格输入（5 个经典 DAX、2 个合成 DAG），4/16 台同构 1000 MIPS VM，端点争用与 0.125/1.25 MB/s Fat-tree 三种网络；三个列表规划器 `LOCAL_HEFT`、`LOCAL_CPOP`、`LOCAL_PEFT` 每条件一次，seed 11：`7 × 2 × 3 × 3 = 126`。沿用对所有规划器一致的 Inspiral1000 排除规则，LOCAL/STATIC/SPACE_SHARED、无故障/开销，一 Host 一 VM；没有把 R10 的 RANDOM/PSO 重跑混入它。

旧 R12/R13 的 `LOCAL_PEFT` 错把自身计算成本计入 OCT，并将出口设为平均计算成本；ready-list 修复没有修正该公式。旧数值、“同构退化/异构激活”与论文正确性叙述只记录那个非标准实现，不能直接评价标准 PEFT。新的协议与历史记录之间的关系见[协议修订](<../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)；标准算法来源见[原文夹具说明](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>)。

三个历史网络研究的原始输出目录各自保存 `protocol.json`、`network-study.json`、自动结果表、生成输入和逐运行 manifest/metrics/events。R12 的旧结果表位于可选路径 `output/peft-comparison-r12/results.md`，不是当前数值基准。保留压缩包及原始协议字节；删除重复解释文档不应删除或重新标记这些证据。

## 新研究与交接要求

- 执行前声明研究问题、同一决策层的算法、输入哈希、平台/模型、种子设计、指标、统计单位、停止条件及保留规则；可在一份协议中说明，不要求机械拆成多份重复文档。
- 输入位置明确，输出使用新目录。保留失败与排除原因，不按算法效果事后挑选输入；共享根种子不自动构成事件键控共同随机数。
- 交接完整协议原字节、索引、全部引用 bundle 与生成输入，不能仅交汇总表。原始实验数据、归档、夹具和黄金值不属于可随文档清理删除的临时文件。
- 先在 DAG 内汇总随机种子，再按声明来源和条件比较；不把重复条件当独立样本，也不由不显著推出等价。通用统计口径见[campaign 指南](<../../docs/experiments/CAMPAIGNS.md>)；独立检查工具见[研究审计工具](<../../scripts/STUDY_AUDIT.md>)。

历史材料仅从本地研究目录追溯，不作为当前入门导航。
