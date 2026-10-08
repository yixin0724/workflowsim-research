# 算法目录与选型

本文列出标准运行器实际支持的标签及组合。支持集合以 [AlgorithmCatalog](<../../simulator/src/main/java/org/workflowsim/experiment/AlgorithmCatalog.java>)的 `isSupportedBySimulationRunner(...)` 为准；[SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)和[运行器](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java>)继续检查模型、输入与平台前提。

“有实现”“标签可选”和“任意组合合法”是不同概念。算法名称不构成真实平台校准、论文全部环境假设重现或普遍优越性的证据。公式、执行时序和指标口径见[语义契约](<CONTRACTS.md>)；运行方式见 [Workbench](<../getting-started/WORKBENCH.md>)和[Java API 指南](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>)。

## 先选择决策层

| 要回答的问题 | 决策层 | 输出与边界 |
| --- | --- | --- |
| 当前 ready Job 派给哪台空闲 VM | 普通在线调度 | 本次派发，不预订未来机器可用时间 |
| 外部策略如何控制同一个 ready 域 | RL 环境 | Job→VM 列表下标动作；平台不训练策略 |
| 无依赖任务如何分配计算负载 | 静态独立任务映射 | Task→VM；拒绝任何依赖边 |
| DAG 只需要一个预先映射的基线 | RANDOM / PSO | Task→VM，不生成完整每 VM 顺序 |
| DAG 如何排序并放入 VM 空档 | SHARED / LOCAL 静态规划 | 映射和每 VM 顺序；计划时间是估计，不是事件重放 |
| 控制就绪后先选目标、再准备输入 | coherent 在线绑定 | 独立绑定策略 + STATIC 已绑定派发，不能与普通 CPU 在线调度混称 |

## 当前调度器标签

下表是 `Parameters.SchedulingAlgorithm` 中标准入口接受的全部标签；具体组合仍受后文限制。

| 标签 | 实际决策 |
| --- | --- |
| `FCFS` | 按 ready 到达序，选择 VM ID 最小的空闲兼容 VM |
| `READY_BATCH_MINMIN` | 各未分配 Job 先求最小 ECT，再选全局最小者；分配后重算 |
| `READY_BATCH_MAXMIN` | 各未分配 Job 先求最小 ECT，再选其中最大者；分配后重算 |
| `READY_BATCH_MCT` | 按 ready 到达序，选择当前空闲兼容 VM 中 `length/MIPS` 最小者 |
| `READY_BATCH_ROUNDROBIN` | 按 VM ID 排列的持久游标轮转，跳过忙或不兼容 VM |
| `DATA` | 选择非本地真实输入字节数最少的空闲兼容 VM；必须使用 LOCAL |
| `RL_POLICY` | 由 `RlEnvironment.runEpisode(...)` 注册的策略选择 VM 列表下标 |
| `STATIC` | 执行已有映射/绑定；有完整静态计划时另强制每 VM 顺序，不是独立优化算法 |

ready-batch 的 ECT 比较量为 `length/MIPS`，不包括未来 availability、输入传输或争用。对固定正长度和相同候选集合，最小 ECT 等价于最大 MIPS；不能仅凭不同标签断言映射一定不同。`DATA` 比较字节而非传输秒数。

普通在线策略和 RL 每轮每台 VM 最多派一个 Job，剩余 Job 等后续更新。`RL_POLICY` 不能作为普通 Workbench JSON 候选，必须走 [RlEnvironment](<../../simulator/src/main/java/org/workflowsim/rl/RlEnvironment.java>)；它提供环境、轨迹和奖励，不包含 DQN/PPO 等学习器。

## 当前规划器标签

下表列出 `Parameters.PlanningAlgorithm` 的全部受支持值。除 `INVALID` 外，均要求 `SchedulingAlgorithm.STATIC`。

| 标签 | 家族 | 决策概要 |
| --- | --- | --- |
| `INVALID` | 无规划 | 普通在线调度或显式 coherent 在线绑定，不是优化算法 |
| `RANDOM` | DAG 仅映射 | 在兼容 VM 中按命名随机流抽样；不优化成本或生成每 VM 顺序 |
| `PSO` | DAG 仅映射 | 以抽象成本和 VM 顺序计算负载为目标，忽略 DAG 边及网络时序 |
| `STATIC_OLB` | 独立任务 | 最小暂定 availability，用有效计算时长更新负载 |
| `STATIC_MET` | 独立任务 | 最小有效计算时长，不看 availability |
| `STATIC_MCT` | 独立任务 | 最小 `availability + effectiveSeconds` |
| `STATIC_MINMIN` | 独立任务 | 逐轮选各任务最小暂定完成时间中的最小者 |
| `STATIC_MAXMIN` | 独立任务 | 逐轮选各任务最小暂定完成时间中的最大者 |
| `STATIC_SUFFERAGE` | 独立任务 | 最大“次优完成时间−最优完成时间”损失；单一候选时损失为 0 |
| `STATIC_ROUND_ROBIN` | 独立任务 | 按 Task ID 轮转兼容 VM，选择规则不看成本 |
| `SHARED_STORAGE_HEFT` | SHARED DAG | upward rank 优先，插入式最早完成分配 |
| `SHARED_STORAGE_CPOP` | SHARED DAG | `r_u+r_d` 优先，一条关键路径固定到全路径兼容的最优 VM |
| `SHARED_STORAGE_DLS` | SHARED DAG | 选择依赖就绪 Task-VM 对中 `b-level−最早插入开始时间` 最大者 |
| `SHARED_STORAGE_ETF` | SHARED DAG | 最早插入开始时间优先，相同开始用 b-level 等规则破平局 |
| `SHARED_STORAGE_PEFT` | SHARED DAG | 最大平均 OCT 的 ready Task，最小 `EFT+OCT` 的 VM；通信项为 0 |
| `LOCAL_HEFT` | LOCAL DAG | 含平均通信的 upward rank，插入式 EFT |
| `LOCAL_CPOP` | LOCAL DAG | 前驱方向 downward rank 与 upward rank；关键路径固定，其他任务插入 |
| `LOCAL_PEFT` | LOCAL DAG | 后继成本 OCT、出口 0、ready-list 选择和插入式 `EFT+OCT` |

## 组合规则

标准运行器统一要求 **NONE 聚类、SPACE_SHARED VM、PE 兼容放置**。每台 VM 同时最多派发一个 Job，多 PE 不表示可同时派发多个 Job。

| 轨道 | planner / scheduler | 文件系统与数据移动 | 任务成本矩阵 |
| --- | --- | --- | --- |
| 普通在线与 RL | INVALID / 对应在线标签 | legacy 或固定端点无争用；DATA 仅 LOCAL；RL 还需环境注册策略 | 不接受 |
| 独立任务映射 | STATIC 独立任务规划器 / STATIC | 输入必须无边；选满足自身前提的 V1 模型 | 可用 |
| DAG 仅映射 | RANDOM 或 PSO / STATIC | 可选满足前提的 V1 模型；coherent 组合见对应行 | 可用，但 RANDOM 不据此优化 |
| SHARED DAG | SHARED_STORAGE 家族 / STATIC | SHARED + `legacyWorkflowsimV1()`；无故障、无开销 | 不接受 |
| LOCAL DAG | LOCAL 家族 / STATIC | LOCAL + V1 pre-execution 家族；无故障、无开销 | 可用 |
| coherent 预先映射 | RANDOM / STATIC | V2 为 LOCAL；V3 为 LOCAL/SHARED；无开销，可选 Fat-tree，V3 必须声明 SOURCE 存储 | 可用 |
| coherent 在线绑定 | INVALID / STATIC，显式启用绑定 | 同上一行的物理前提；控制就绪后绑定，再准备输入 | 可用 |

V1 pre-execution 家族是 `preExecutionTransferDelayV1()`、`preExecutionTransferDelayWithContentionV1()`、`fatTreeContentionV1()`。后者必须 LOCAL、无故障/开销并声明平台拓扑；LOCAL 规划器在这些运行模型下仍使用无争用估计。拓扑与路由见 [Fat-tree 模型](<../research/FAT_TREE_DESIGN.md>)。

coherent 在线绑定使用 `CONTROL_READY_ONLINE_ASSIGNMENT_V1` 与策略 `NOMINAL_INPUT_EARLIEST_RESERVATION_V1`。它是 **STATIC 通常要求非 INVALID 规划器的显式例外**，不是给任意 STATIC 配置的豁免。不能与 RANDOM 预规划并用；NOOP 重试复用逻辑绑定。Workbench 要求匹配的完整生命周期捕获，Java OFF 运行不提供在线动作导出认证。详见[在线绑定契约](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)和[模型能力表](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>)。

矩阵可用不表示每个规划器都会优化矩阵：时间感知独立规划器、PSO、LOCAL 规划及执行共用有效整数 MI；RANDOM 和 STATIC_ROUND_ROBIN 保持各自的成本无关选择。故障只在允许的组合中使用标准 NOOP 重试契约；Workbench 不开放故障或额外开销配置。

## 兼容标签迁移

| 旧标签 | 当前状态与保留行为 | 维护入口 |
| --- | --- | --- |
| `MINMIN` | 枚举保留但标准运行器拒绝；[SPT-fastest-idle 实现](<../../simulator/src/main/java/org/workflowsim/scheduling/SptFastestIdleSchedulingAlgorithm.java>)先按原始长度选短 Job | 在线用 `READY_BATCH_MINMIN`；独立任务全局映射用 `STATIC_MINMIN` |
| `MAXMIN` | 枚举保留但标准运行器拒绝；[LJF-fastest-idle 实现](<../../simulator/src/main/java/org/workflowsim/scheduling/LjfFastestIdleSchedulingAlgorithm.java>)先选长 Job | 在线用 `READY_BATCH_MAXMIN`；独立任务映射用 `STATIC_MAXMIN` |
| `MCT` | 枚举保留但标准运行器拒绝；[最快空闲 VM 实现](<../../simulator/src/main/java/org/workflowsim/scheduling/FastestVmSchedulingAlgorithm.java>)不维护离线 availability | 在线用 `READY_BATCH_MCT`；独立任务映射用 `STATIC_MCT` |
| `ROUNDROBIN` | 枚举保留但标准运行器拒绝；[First-Fit-Idle 实现](<../../simulator/src/main/java/org/workflowsim/scheduling/FirstFitIdleSchedulingAlgorithm.java>)不是持久游标轮转 | 在线用 `READY_BATCH_ROUNDROBIN`；独立任务映射用 `STATIC_ROUND_ROBIN` |
| `HEFT` / `DHEFT` | 不在当前规划枚举中，旧代码不能直接编译 | 按模型选择 SHARED_STORAGE 或 LOCAL 家族，不是无条件同义替换 |
| `SchedulingAlgorithm.INVALID` | 不可运行 | 选择显式在线标签或合法的 STATIC 组合 |

`SPT`、`LJF`、`FASTEST_VM`、`FIRST_FIT_IDLE` 不是当前调度枚举名；保留的实现类不能据此当作新的可运行标签。兼容标签拒绝行为由[标准入口测试](<../../simulator/src/test/java/org/workflowsim/experiment/SimulationRunnerAlgorithmContractTest.java>)覆盖。迁移要声明决策层与模型，不把旧结果重命名为另一算法的证据。

## 比较结果时固定什么

固定输入及哈希、平台与 VM→Host 放置、文件系统、数据移动、成本矩阵、开销/故障、到达表、种子设计、执行语义和指标样本范围。完整静态 DAG 规划强制每 VM 顺序，而 RANDOM/PSO 仅映射后由 STATIC 派发 ready Job；二者比较的是完整策略流程，不只是 VM 映射质量。

传输争用可能使有序计划出现队首等待，即使 VM 空闲；增加链路约束也不保证任意 DAG 的总完成时间单调变化。确定性算法的多种子重复不是独立工作流样本。来源不同的数据集合不应直接合并成同一总体，见[数据集说明](<../../datasets/README.md>)。
