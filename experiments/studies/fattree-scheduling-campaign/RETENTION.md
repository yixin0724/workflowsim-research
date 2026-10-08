# Fat-tree 历史 campaign：来源、参数与保留

> 本目录保存 **R8 再标定运行的历史数值**，不是当前执行语义的认证或黄金值。后续 CPOP、网络分配、CPU 工作量和 LOCAL 数据可达时间修正已改变部分结果；不能为了逐位一致而覆盖历史工件或调整新模型。后续研究身份与验证边界见[协议修订](<../../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)。

## 保留工件与参考时期

- [campaign-results.json](<campaign-results.json>)：schema `workflowsim-fattree-campaign-v1`，包含逐运行摘要、弱单调诊断、排名与配对统计。
- [campaign-results.md](<campaign-results.md>)：同一历史运行的自动数值表。
- 来源为 [FatTreeSchedulingCampaignExecutor](<../../src/main/java/org/workflowsim/experiments/fattree/FatTreeSchedulingCampaignExecutor.java>) 的 R8 再标定版本；原记录日期为 2026-09-16、时间 13:42（未记录时区），JDK 17、seed 91，360 个单元均为 `COMPLETED_SUCCESSFULLY`。当前源码链接用于识别驱动，不表示当前实现与当时相同。

这些工件是当时结论的数值依据，不是完整的逐运行 manifest/metrics/events bundle。两份文件长期保留，文档整理不重生成、不改写其中的数值。

## 冻结矩阵与装置

研究问题是网络约束如何改变调度方案的相对表现。10 个 DAX 为 Epigenomics n24/n46/n100、CyberShake n30/n50/n100、Inspiral n30/n50/n100，以及 [HEFT 十任务输入](<../../../datasets/dax/heft/heft-paper-example.dax>)。Montage/Sipht 因 LOCAL 规划器检测到的同名文件尺寸冲突被排除；未纳入千任务档位。

平台一 Host 一 VM，主矩阵/第一敏感性块为 3 VM，结构块为 4 VM；全部 1 MIPS、1 MB/s VM 端点、SPACE_SHARED。使用 LOCAL/STATIC、NONE 聚类、无故障/开销，不提供 TaskCostMatrix，计算量来自 DAX runtime 归一化。该装置不是 simulator 论文复现测试的显式成本矩阵装置。

| 块 | 组合 | 运行单元 |
|---|---|---:|
| 主矩阵 | 10 DAG × LOCAL_HEFT/LOCAL_CPOP/RANDOM/PSO × V1/R2/R6 | 120 |
| 3 主机敏感性 | 10 DAG × LOCAL_HEFT/LOCAL_CPOP × 6 拓扑变体，均 R6 | 120 |
| 4 主机结构敏感性 | 10 DAG × LOCAL_HEFT/LOCAL_CPOP × 同类 6 变体，均 R6 | 120 |

V1 为 `PRE_EXECUTION_TRANSFER_DELAY_V1`，R2 为 `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1`，R6 为 `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`；名称记录当时模型分支，不表示其后来实现没有变化。

链路基线为 **0.125 MB/s**（VM 端点的 1/8），A4 为 **1.25 MB/s**（基线 10 倍）。6 个变体为：

| 变体 | 拓扑参数 |
|---|---|
| baseline | k=4，满配 core，基线链路，默认轮转放置 |
| A1 | k=4，2 个 core |
| A2 | k=8，16 个 core（满配） |
| A3 | k=8，8 个 core |
| A4 | k=4 满配，仅链路改为 1.25 MB/s |
| A5 | k=4 满配，显式同 edge 主机对；3 主机为 `{0→0,1→0,2→2}`，4 主机为 `{0→0,1→0,2→2,3→2}`，值为全局 edge 索引 |

除列出的单轴变更外参数不变。默认 3 主机依次位于 pod0/edge0、pod0/edge1、pod1/edge0；第 4 主机为 pod1/edge1。所有运行固定 seed 91；跨 DAG 配对 Wilcoxon、逐 DAG 第一名和弱单调性仅为这组装置的描述。重复确定性运行不是新增独立样本，跨模型 makespan 单调性和结构轴退化都不是普遍定理。

## 再标定的来源说明

最初实现曾把声明 1.0 MB/s 的 Fat-tree 链路错误按 0.125 MB/s 使用（多除以 8）。修正单位后，以链路/端点同为 1.0 MB/s 重跑该历史装置，出现 R6 与 R2 相等的结果。随后明确将基线声明为 0.125、A4 声明为 1.25 MB/s，形成**本目录保留版本**；这是参数变更及其来源记录，不是改变单位来隐藏物理参数。

再标定后若干旧 R6 数值恢复一致，并不意味着后续修正模型也应相同。两种主机规模中的结构轴退化及当时的排名/统计，只能按本目录的参考时期引用，不能把旧 360 单元的结论推广为当前实现结果。

## 当前代码下的差异分析

以下命令按驱动中的历史参数使用**当前实现**生成新结果，不恢复 R8 二进制。使用全新输出目录，之后比较并解释差异；不要指向本目录。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor \
  -Dexec.args="/absolute/datasets /absolute/new-fattree-comparison"
```

当前测试只约束其声明的输入和模型版本，不能要求新输出整体等于旧工件。临时比较输出与正式保留证据分开管理；本目录的两份原始数值工件不属于清理对象。
