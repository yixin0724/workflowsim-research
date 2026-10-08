# WorkflowSim文档中心

这里维护当前使用方法、模型契约和验证规则。首次使用按“快速开始 → Workbench”阅读；需要自定义Java实验时再看代码配置指南。开发轮次、完成清单和被替代的审计稿不作为长期入口。

## 开始使用

| 需要 | 文档 |
|---|---|
| 跑通已有配置并打开报告 | [快速开始](<getting-started/QUICK_START.md>) |
| 配置、验证、运行和重建离线报告 | [Workbench指南](<getting-started/WORKBENCH.md>) |
| 在Java中构建配置、平台及实验 | [代码配置指南](<getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Maven、Python、浏览器和Javadoc检查 | [构建与验证](<getting-started/BUILD.md>) |
| 配置IDEA模块、类路径和工作目录 | [IDEA设置](<getting-started/IDEA_SETUP.md>) |

## 选择算法、输入和模型

- [算法目录](<algorithms/CATALOG.md>)：决策层、可选标签、组合限制及旧名称迁移。
- [算法与指标契约](<algorithms/CONTRACTS.md>)：计算量、通信、顺序、原论文对照、指标口径及验证边界。
- [工作流输入与数据集](<../datasets/README.md>)：随仓库输入、可选语料、来源、转换和格式约束。
- [数据流与网络能力矩阵](<advanced/DATAFLOW_CAPABILITY_MATRIX.md>)：物理模型、存储、在线绑定及未实现能力。
- [Fat-tree拓扑与共享模型](<research/FAT_TREE_DESIGN.md>)：结构、放置、确定性路径和容量分配边界。

## 数据流与证据参考

| 主题 | 文档 |
|---|---|
| 文件身份、真实来源、副本可见性和CPU输入屏障 | [一致文件数据流V2](<advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>) |
| 有限SOURCE、提交后读取和必要输出写回 | [存储数据流V3](<advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>) |
| 控制就绪时选VM、名义分数和动作认证范围 | [在线目标绑定](<advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) |
| 逐文件侧车的精确字段与验证规则 | [文件生命周期格式](<advanced/FILE_LIFECYCLE_V2_FORMAT.md>) |
| V1组级账本的格式、角色及严格读取 | [网络账本格式](<advanced/NETWORK_LEDGER_FORMAT.md>) |
| V1稳定分配证书、精确会计和截断前缀 | [账本校验与指标](<advanced/NETWORK_LEDGER_VALIDATION.md>) |
| 不依赖Java的V1/V2/V3及在线检查入口 | [独立Python证据校验](<advanced/NETWORK_LEDGER_PYTHON.md>) |
| 同一验证快照、精确文本和有界预览 | [离线网络与动作报告](<advanced/NETWORK_REPORT_DISPLAY.md>) |

## 实验、重放与统计

- [可复现性契约](<experiments/REPRODUCIBILITY.md>)：随机性、时间/工作量、成本、参考值和deadline语义。
- [批量实验与网络研究](<experiments/CAMPAIGNS.md>)：场景矩阵、种子、参数控制、运行和统计方法。
- [证据重放与差异比较](<experiments/RERUN_DIFF_CONTRACT.md>)：配置重建、判定、精确核心量与少量易变字段。
- [协议身份与历史证据边界](<experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)：哪些旧身份仍可读取，为什么旧结果不等于当前实现的结论。
- [独立研究检查工具](<../scripts/STUDY_AUDIT.md>)：一般证据/统计校验的实际范围，与数据流专用证书区分。

## 维护

- [文档与源码注释规范](<advanced/CODE_STYLE.md>)：内容分层、链接/示例要求、来源保护和文档检查命令。
- [数据流状态与性能测量](<advanced/DATAFLOW_STATE_COPY_PERFORMANCE.md>)：不可变历史/活动工作集、测量口径及可重复比较。
- [实验模块说明](<../experiments/README.md>)：模块职责和维护入口。

## 历史资料的保留原则

历史实验的原始数值、预注册、输入来源与必要兼容协议保留在对应数据/参考目录，并明确版本与局限；它们不构成当前用户指南或新的科学结论。需要识别已保留研究时查看[研究数据说明](<../experiments/studies/README.md>)。其余开发过程可从Git历史查阅，不在当前导航中重复陈列。
