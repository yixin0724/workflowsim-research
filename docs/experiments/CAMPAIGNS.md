# 批量实验与研究矩阵

批量运行之前，先明确比较单位、基线、输入和模型。不同入口承担不同职责：

| 入口 | 适用场景 |
| --- | --- |
| [Workbench](<../getting-started/WORKBENCH.md>) | 用 JSON 比较同一决策层的算法，查看离线报告 |
| 核心 `ExperimentPlan` / `ExperimentCampaignExecutor` | 在 Java 中声明比较组、基线和种子设计，生成通用 campaign 证据 |
| `NetworkStudyExecutor` | 执行已注册的网络研究矩阵，不是任意配置的通用 CLI |

通用 API 位于核心模块，研究专用驱动位于实验模块。新研究的协议和保留要求见[研究目录约定](<../../experiments/studies/README.md>)；算法与物理模型的组合以[算法目录](<../algorithms/CATALOG.md>)为准。

## 通用 campaign API

[ExperimentPlan](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentPlan.java>)的每个 `Cell` 声明：cell ID、comparison group、candidate ID、是否基线、`SimulationConfig`、`PlatformProfile`、`SeedPlan` 和可选字符串标签。

以下是放入自建 Java 实验类的 API 片段，不是独立程序。`config`、`platform` 可沿用[Java API 指南](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>)的普通在线示例；`outputDirectory` 是新建或为空的 `Path`，并需导入所用的 `org.workflowsim.experiment` 类型。

```java
ExperimentPlan plan = ExperimentPlan.builder("scheduler-comparison")
        .addCell(new ExperimentPlan.Cell("fcfs", "same-input", "FCFS", true,
                config.toBuilder().schedulingAlgorithm(SchedulingAlgorithm.FCFS).build(),
                platform, SeedPlan.deterministic(42L), null))
        .addCell(new ExperimentPlan.Cell("mct", "same-input", "READY_BATCH_MCT", false,
                config.toBuilder().schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MCT).build(),
                platform, SeedPlan.deterministic(42L), null))
        .build();
ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);
ExperimentCampaignArtifactWriter.CampaignArtifacts artifacts =
        ExperimentCampaignArtifactWriter.write(result, outputDirectory);
System.out.println(artifacts.getIndex());
```

构造阶段检查 cell ID 唯一、组内 candidate ID 唯一、每组恰有一个基线、组内随机化设计一致，以及配置/平台 VM 数一致。[SeedPlan](<../../simulator/src/main/java/org/workflowsim/experiment/SeedPlan.java>)保存实际种子列表；可显式给定，也可从记录的根经 SplitMix64 派生。确定性计划只能包含一个种子。

这些结构检查**不自动证明实验可比**：研究者仍须固定输入、平台、文件系统、成本与数据移动模型、到达表、故障/开销和指标样本范围。不要因为两个 cell 能放进一组，就混合普通在线、静态独立任务、静态 DAG 和在线数据流绑定。

[执行器](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignExecutor.java>)按 cell 和种子声明顺序串行调用 `SimulationRunner`；不支持同 JVM 并发。解析、配置、执行或重试预算异常会中止，不能被悄悄转为一个普通失败工作流样本。需要并行研究时使用独立进程和独立输出，并另外声明合并方式。

## 种子设计与统计解释

[RandomizationDesign](<../../simulator/src/main/java/org/workflowsim/experiment/RandomizationDesign.java>)是统计解释声明，不改变模拟器物理或随机模型。

| 设计 | 可用解释 |
| --- | --- |
| `DETERMINISTIC` | 单次确定性模型结果和描述性差值，不给抽样置信区间 |
| `INDEPENDENT_REPLICATIONS` | 每 cell 的均值、样本标准差和范围；至少两个样本时有双侧 95% Student-t 均值区间，合格工作流运行成功率有 cell 内 Wilson 区间 |
| `COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN` | 按相同根种子匹配的描述性差值及可用的配对 Wilcoxon 检验；不是事件键控 CRN，也没有 CRN 处理效应区间 |

相同根种子不保证两种算法消耗相同逻辑随机事件。命名随机流隔离了组件，但调度、批次或重试变化仍可改变组件内消费顺序。必须查看实际种子与 `matchedRootSeedCount`，不能只凭设计标签认定所有运行均已配对。

[Campaign 汇总](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignSummary.java>)先计算每次运行的指标，再在 cell 内汇总，不把全部 Job 合并成一个样本。主要边界：

- 有至少一个逻辑 Task 的运行才进入 workflow-run 完成率；只有全部逻辑 Task 成功才记为成功。
- `successfulWorkflowLogicalCompletionSeconds` 只汇总成功工作流。无成功运行时不可用，不能用失败运行的模拟结束时间代替，也不能脱离完成率单独比较。
- 无真实减速比观察的运行不进入该指标的 campaign 样本；其他指标保留各自 API 的零值/退化约定，需结合单运行观察数解释。
- 连续指标区间与 Wilson 区间都是 cell 内量；没有自动的算法差值置信区间、成功率差检验、风险比或普遍优越性证明。
- 比率是 candidate mean / baseline mean；描述性 speedup 是 baseline mean / candidate mean；改善百分比为 `100 * (baseline-candidate) / baseline`。分母为零时相应量不可用，不是串行加速比或并行效率。

[PairedWilcoxonSignificance](<../../simulator/src/main/java/org/workflowsim/experiment/PairedWilcoxonSignificance.java>)比较 candidate−baseline 的匹配 makespan 差值。零差值不进入秩统计，但配对总数、有效样本数和中位差分别保留；不足两个配对、全零、仅一个非零差值或独立重复设计会返回相应 `UNAVAILABLE_*` 状态。有效非零数不超过 25 时走精确分布，更多时走库的正态近似；并列秩与连续性校正的实现限制见源码，不能把“非参数”理解为无需统计假设。

执行前冻结主要指标、实际意义阈值、重复数或预先定义的精度/预算停止规则、多重比较方法。当前执行器没有自适应停止实现，不应因中途结果有利而停止。

## 通用 campaign 证据

[Campaign 写器](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentCampaignArtifactWriter.java>)只接受新建或为空的目录，为每个 cell/replication 写出证据，再写索引并校验：

```text
campaign-output/
  experiment-campaign-index.json
  <cell-id>/
    replication-0000.manifest.json
    replication-0000.metrics.json
    replication-0000.events.jsonl
    ...匹配记录模式的可选网络或生命周期侧车
```

索引保存计划种子、比较身份、相对路径、运行摘要和逻辑工作流 profile。`ExperimentCampaignValidator` 核对引用、逐运行证据、种子和主要摘要，但不独立复算所有 campaign 聚合字段；它不证明选样代表性或算法组合的研究意义。命令集中在[构建与验证](<../getting-started/BUILD.md>)，指标定义见[语义契约](<../algorithms/CONTRACTS.md>)。

通用运行可使用当前合法的九种物理模型及显式在线绑定，但须满足各自配置/捕获前提；支持某模型不表示每个 planner 都可用。外部 RL 策略通过专用 Java episode 接口接入，不由 campaign 自动训练或注册。

## 执行注册网络研究

[NetworkStudyExecutor](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyExecutor.java>)接受 `smoke|full`、数据集根、必须尚不存在的输出目录，以及可选变体 `r10`、`peft-comparison`、`sensitivity-r13`。省略变体等于 `r10`。

从项目根运行一个当前 smoke 研究；替换绝对路径占位符：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  '-Dexec.args=smoke "/absolute/datasets" "/absolute/new-network-study" r10'

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  '-Dexec.args="/absolute/new-network-study/network-study.json"'
```

执行器将 [NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>)的实际声明冻结到输出的 `protocol.json`，不是读取旧 Markdown 来决定实验。输出还包含生成输入、逐运行证据、`network-study.json` 和自动结果表。当前与只读兼容身份见[协议身份说明](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>)；不能修改条件后继续使用同一注册 ID。

### 输入与资格

- full 从六个经典 DAX 候选统一排除同名文件尺寸冲突的 Inspiral1000，最终使用 Epigenomics100/997、CyberShake100/1000、Inspiral100，以及确定性生成的 layered-32/128。
- smoke 使用 HEFT 来源的十任务输入与 layered-16。它不是 PEFT 一级来源算法证明，也不是任意裁剪的 full。
- 排除发生在观察算法效果之前，并对所有规划器一致；输入顺序、哈希与排除原因必须匹配注册声明。资格失败或输入变化不能靠换文件、漏跑或改名绕过。

### 固定条件

这些注册研究使用 **LOCAL/STATIC、NONE 聚类、SPACE_SHARED、无故障/开销、无任务成本矩阵、端点 1 MB/s**，每台 VM 固定一个 Host。网络是 V1 端点争用和 Fat-tree 争用，均从 Job 就绪时开始传输；它们不是 coherent V2/V3 的覆盖矩阵。

| 变体 | full 条件 | 规划器与实际种子 |
| --- | --- | --- |
| `r10` | VM 4/16 × endpoint、fat-tree-constrained、fat-tree-wide | `LOCAL_HEFT`、`LOCAL_CPOP` 各 seed 11 一次；`RANDOM`、`PSO` 各 11/29/47/71/101 |
| `peft-comparison` | 与上一行相同 | `LOCAL_HEFT`、`LOCAL_CPOP`、`LOCAL_PEFT`，各 seed 11 一次 |
| `sensitivity-r13` | 同构主块 VM 4/8/16/32 × 五个网络档；另在 VM 8/16、constrained 下加三种异构模式 | `LOCAL_HEFT`、`LOCAL_CPOP`、`LOCAL_PEFT`，各 seed 11 一次 |

constrained/mid/wide/fast 的 Fat-tree 链路为 0.125/0.5/1.25/5 MB/s；前两个变体只用 constrained/wide。默认同构 VM 为 1000 MIPS；异构循环模式为 `[1000,500]`、`[2000,1000,500]`、`[2000,500]`。VM 不超过 16 时 k=4，VM32 时 k=8。改变 VM 数同时可能改变拓扑，异构度也改变总算力，不能把所有效果归因于单一因素。

前两个 smoke 变体只用 VM4；随机规划器种子为 11/29。敏感性 smoke 使用 VM4 的 endpoint/constrained 同构条件，以及 constrained 下三种异构模式。具体计划单元数以注册声明为准，不表示这些运行已经执行。

### 网络研究统计与失败处理

[NetworkStudySummary](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudySummary.java>)先在每个 DAG 内平均种子，再按来源、VM、网络和适用的异构度分层，与 LOCAL_HEFT 配对。它使用**双侧符号检验**，不是通用 campaign 的 Wilcoxon；Holm 在同一分层的候选族内调整。胜平负阈值是注册的 `1e-9 * max(1, baseline, candidate)`，不是 rerun 的数值容差。

经典与合成总体分开，不把种子当作额外 DAG。经典 full 只有五个 DAG 对，即使全部同向，双侧符号检验最小原始 p 也为 0.0625；不显著不能证明等价。应报告效果幅度、逐 DAG 分布和失败状态，不把固定语料的探索结果外推到真实云。

研究执行器会保留单次失败并继续其他单元，但失败研究不输出有效推断，最终完整性校验不通过。中断索引标记 `RUNNING_OR_INTERRUPTED`；已有聚合不能冒充完整矩阵。只读验证会核对注册矩阵、协议原字节哈希、完整运行配置/平台、输入身份、完成状态、索引指标及重新计算的汇总；独立统计与物理检查见[审计工具](<../../scripts/STUDY_AUDIT.md>)。

保留协议原字节、索引、全部引用证据和生成输入，不只保留结果表。来源和历史材料通过[研究目录](<../../experiments/studies/README.md>)追溯；当前代码实际复跑是否一致由[复跑差异契约](<RERUN_DIFF_CONTRACT.md>)另行判断。
