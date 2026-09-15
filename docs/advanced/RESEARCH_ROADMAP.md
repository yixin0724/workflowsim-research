# 科研工作流模拟器演进路线图（Research Roadmap）

> 状态：**R1-R6 全部完成**（R1-R5 于 2026-09-14，R6 Fat-tree 于 2026-09-15）；后续方向见「长期候选项」（本文档随轮次推进持续更新）
> 创建日期：2026-09-11
> 维护约定：每完成一个轮次，将对应条目从「计划」移入「已完成」并记录实测结论与证据位置。

## 一、定位与诚实边界

本平台是**受控、确定性、无争用的抽象环境**下的工作流调度模拟器。这一边界适合做算法
语义对比与论文复现（见 `docs/algorithms/CATALOG.md`），但以下问题域当前**无法**回答：

- 分组级网络细节（丢包/排队/ECN/自适应路由）——R2 提供端点流体公平共享近似，
  R6 提供 Fat-tree 拓扑感知链路争用（确定性路由 + 链路级公平共享），两者都
  不是包级仿真；
- 真正的多租户平台语义（R5 已支持多工作流错峰共享同一 VM 池并报告每工作流
  流时，但无租户级配额/计费/抢占隔离；同一 JVM 仍是单会话串行）；
- 超售（overcommitment）下的 Host 层 CPU 干扰；
- 到达前未知的在线工作流流（R5 的错峰提交时刻仍是 t=0 已知配置；到达时才发现
  的 DAG 仍不支持）；
- 能耗与绿色计算方向；
- Spot 实例、抢占与弹性伸缩。

下列各轮次的目标就是在不破坏现有语义承诺的前提下，逐块补齐这些能力。

## 二、轮次规划（按推荐顺序）

### R1：多 seed 统计框架 + 显著性检验 —— 已完成（2026-09-14）

**动机**：现有黄金值均为单 seed 锁定。单点数值不构成统计结论；发表级算法对比需要
n≥30 seeds 的分布描述 + 假设检验。

**已交付内容**（调研发现 campaign 基础设施已具备多 seed 执行、分布汇总与 t 置信
区间，真实缺口是配对假设检验）：

- `PairedWilcoxonSignificance`（simulator，`org.workflowsim.experiment`）：
  配对 Wilcoxon 符号秩检验，精确分布（有效样本 ≤25）与正态近似两条路径；
  全零差值/单非零差值/样本不足均如实返回不可用状态而非伪造 p 值；
- `ComparisonSummary.getPairedSignificance()`：campaign 比对层自动对配对
  makespan 差值序列执行检验；推断边界（非事件键控 CRN）在状态字符串中如实声明；
- 测试：`PairedWilcoxonSignificanceTest`（9 个语义测试，含与 commons-math3
  直接调用的逐位一致性校验）+ `MultiSeedSignificanceIntegrationTest`（端到端验收）；
- 文档：`docs/experiments/CAMPAIGNS.md` 新增推断口径表与检验状态表。

**验收实测**：异构 16 VM（MIPS 800–2300）+ Montage_1000 + Weibull 故障模型，
FCFS 基线 vs READY_BATCH_MCT 候选，10 个共享根种子：
`AVAILABLE_WILCOXON_SIGNED_RANK_EXACT`，p=0.001953125（10 对差值全部为负），
中位差值 −950.2 秒——MCT 显著优于 FCFS，带 p 值的对比报告能力落地。

**过程中的实测边界（已写入测试 Javadoc）**：小规模 fixture 上多数在线调度器收敛
到相同调度；同构平台上所有在线调度器完成时间全等，退化为同一调度（差值恒零，
检验正确返回不可用状态）。配对显著性验收 fixture 必须异构平台 + 千任务规模。

### R2：链路争用带宽模型 —— 已完成（2026-09-14）

**动机**：此前带宽模型无争用、无拓扑（任意 VM 对 `min(bw)`），并发传输互不影响，
无法研究数据感知调度的核心问题。

**已交付内容**：

- `TransferContentionEngine`（simulator，`org.workflowsim.data`）：端点公平共享的
  流体争用模型——每个 VM 端点容量 `vm.getBw()`，活动传输按端点负载公平分配
  （`capacity/n`），传输有效速率 = min(名义速率, 源/目标端点份额)；字节余量随事件
  时间线性积分，速率变化历史被逐段吸收，完成时刻精确到浮点误差；SOURCE 端点不设上限；
- `DataMovementModel.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1`：链路争用版
  执行前传输模型（传输组在数据就绪时刻统一开始，完成时刻不早于无争用模型）；
- 运行时集成：`WorkflowEngine` 争用路径（传输组登记 + `TRANSFER_CONTENTION_CHECK`
  推进检查事件 + 完成结算释放）；`WorkflowDatacenter` 端点容量注册与提交路径协同；
- 配置边界：争用模型要求静态 VM 映射（INVALID 规划层被拒绝）；LOCAL_HEFT/CPOP
  允许争用模型（规划侧仍按无争用 AST 估计，并发负载下规划/执行偏差由文档声明）；
- 测试：`TransferContentionEngineTest`（8 个流体语义测试：公平共享、竞争者完成后
  提速、迟到传输减速、双向端点占用、手算完成时刻）+ `LinkContentionSemanticsTest`
  （HEFT 论文例端到端：争用 makespan 284.1 vs 无争用 190.1，黄金值锁定、确定性复跑、
  证据链、配置边界）。

**验收实测**：HEFT 论文例（3 VM × 1 MB/s），t2（18 MB）与 t4（9 MB）传输在 t1 完成
时刻共享源端点 vm2 各得 0.5 MB/s——完成时刻从 137.1/128.1 推迟到 146.1/137.1，
makespan 从 190.1 增至 **284.1**（+49.4%），并发传输互相减速的信号真实可测。

**诚实边界**：流体模型——无分组/丢包/排队时延/链路拓扑/协议行为；端点容量是对 VM
网卡的公平共享近似。回答"并发传输减速多少"的调度权衡问题，不回答真实网络细节。

### R3：故障模型统计检验 + 覆盖率门禁 —— 已完成（2026-09-14）

**动机**：补齐测试审计 P1 遗留项——故障到达过程是否真的服从配置声称的分布，
以及覆盖率报告与阈值门禁。

**已交付内容**：

- `DistributionGeneratorKsTest`（8 个检验）：LOGNORMAL/GAMMA/WEIBULL/NORMAL 四族
  各 1500 个确定性样本通过对其理论分布的单样本 KS 检验（渐近 p 值，阈值 10⁻⁴
  保守接受）；样本均值与族理论矩一致（Weibull βΓ(1+1/α)、Gamma αβ、
  LogNormal e^{μ+σ²/2}、Normal μ，5% 容差）；`DistributionSpec` 配置接线契约；
  经 `FailureGenerator` 全区间扫描的故障到达过程检验（到达时刻 = 累计样本前缀、
  到达间隔通过 Weibull KS、消费边界与扫描地平线一致）；
  **检验效力对照**：把 Weibull 样本拿去拟合均值相同的 Gamma 必须被显著拒绝
  （p < 10⁻⁶），证明 KS 判定不是恒真；
- KS 渐近 p 值为自实现（`KolmogorovSmirnovTest` 需要 commons-math3 3.3+，仓库
  锁定 3.2；升级依赖会改变 RNG 采样行为威胁既有黄金值，故不动）；
- **JaCoCo 阈值门禁**：父 pom 新增 `check-unit-coverage` 执行（BUNDLE 级
  instruction/branch 下限），各模块棘轮阈值——simulator 0.48/0.43、experiments
  0.23/0.25（实测 2026-09-14：48.49%/43.23%、24.03%/26.81%，略留裕量），
  只升不降；覆盖率跌破阈值时 `mvn verify` 直接失败。

**验收**：8 个 KS 检验全绿；clean verify 全门禁绿且覆盖率检查通过。

### R4：DRL 调度器轨道 —— 已完成（2026-09-14）

**动机**：深度强化学习调度是当前工作流调度发文量最大的方向。平台的确定性事件循环
天然适合做 RL 环境封装（状态 = 就绪队列 + VM 负载，动作 = Job→VM，奖励 = 负
makespan）。

**已交付内容**（`org.workflowsim.rl` 包 + `RL_POLICY` 在线调度算法）：

- **环境契约**：`RlObservation`（就绪 Job 队列特征 + VM 忙/闲/MIPS/PE 视图，
  Job 保持到达序、VM 按 ID 升序，不可变）、`RlPolicy`（每个就绪 Job 选一个 VM
  下标；越界/长度错误中止仿真，忙/不兼容 VM 的动作跳过该 Job 待下次决策）、
  `RlEpisodeResult`（makespan、**reward = −makespan**、策略查询步数、决策轨迹）；
- **闭环驱动**：`RlEnvironment.runEpisode` 注册策略 → 经标准 `SimulationRunner`
  运行（`RlPolicySchedulingAlgorithm` 在每次 ready-batch 更新请求动作）→ 收集
  决策轨迹 → 释放注册表；episode 外直接运行 RL_POLICY 显式失败并指向正确入口；
- **基线策略**：`EarliestFinishGreedyPolicy`（每 Job 选最早完成时间的空闲兼容
  VM，一次更新内每 VM 至多一 Job，等 ECT 选较小 VM ID）；
- **轨迹恒等**：决策轨迹与报告最终 VM 分配逐一致、每 Job 恰好分派一次——
  训练信号（观测→动作→终局奖励）与证据报告完全对齐；
- 测试：`RlPolicyContractTest`（6 个单元契约：观测结构、贪心行为、注册表生命周期）
  + `RlEnvironmentEpisodeIntegrationTest`（5 个端到端验收）。

**验收实测**：HEFT 论文例 fixture 在线轨道 episode——贪心基线黄金 makespan
**5116.1**（确定性，两次 episode 逐位一致；该值与静态 HEFT 复现的 190.1 不可比：
在线轨道用 INVALID 规划 + 遗留数据移动模型 + DAX 运行时直接折算 MI，锁定的是
环境闭环行为而非论文成绩）；reward = −makespan 精确成立；错误路径全部显式失败
（缺策略指向 RlEnvironment、动作越界/长度错误中止、永不分配由平台看门狗拦截）。

**诚实边界**：环境提供状态/动作/奖励契约与确定性闭环，**不提供学习算法本身**；
接入 PyTorch/JAX 训练器需要进程外桥接（平台是单进程 Java 事件循环），属后续
扩展。状态视图刻意最小（空间共享下 VM 负载 = 忙/闲 + MIPS + PE，无推测量），
奖励为终局 makespan、无逐步塑形。

### R5：多工作流并发 / 动态到达 —— 已完成（2026-09-14）

**动机**：解锁多租户与在线到达场景研究。

**已交付内容**：

- **配置**：`SimulationConfig.Builder.workflowArrivalSeconds(List<Double>)`——每个
  工作流输入的提交时刻（模拟秒），与路径一一对应；默认全零（历史单时刻提交行为
  逐位不变）；校验数量匹配、有限、非负，且非零到达与非 NONE 聚类显式互斥
  （聚类生成的 Job ID 不再携带原始任务编号，门控无法归属——拒绝而非静默失效）；
- **到达门控**：`WorkflowEngine` 按任务编号区间把 Job 归属到输入（解析期登记），
  就绪扫描中提交时刻未到的 Job 留在就绪列表，`WORKFLOW_ARRIVAL_SCAN` 事件在最早
  未到达时刻触发幂等重扫；非根 Job 天然晚于其根到达，只需门控根；
- **证据**：多输入或含非零到达时记录 `WORKFLOW_ARRIVED` 事件（每工作流一次，
  含下标与配置提交时刻）；`SimulationReport.getWorkflowOutcomes()` 按输入下标报告
  到达时刻、任务编号区间、任务数、最大成功完成时刻与**流时**（完成 − 提交）；
- 测试：`MultiWorkflowArrivalConfigTest`（6 个单元契约）+
  `MultiWorkflowArrivalIntegrationTest`（4 个端到端验收）。

**验收实测**：两份 HEFT 论文例（各 10 任务）并发共享 3 VM（FCFS 在线轨道）——
同时提交黄金 makespan **8118.1**；错峰提交 [0, 50] 黄金 makespan **8168.0**、
工作流 1 黄金流时 **8118.0**（= 最大完成 8168.0 − 提交 50）；错峰 makespan ≥
同时提交（晚到不提前）；工作流 1 任何任务不在 50 前开始；两次运行逐位一致；
单输入全零到达不产生任何新事件（历史轨迹逐位一致）。

**诚实边界**：提交时刻是 t=0 已知配置，不是到达前未知的在线流；多工作流共享
同一 VM 池与调度器，无租户级配额/计费/抢占隔离；到达门控与聚类互斥（见上）；
静态规划算法在 t=0 预映射 VM，与错峰到达可组合但"规划时刻 ≠ 执行时刻"的语义
需研究者自行声明。

### R6：Fat-tree 拓扑感知链路争用模型 —— 已完成（2026-09-15）

**动机**：R2 的争用域只有 VM 端点——并发传输是否互相减速与它们在网络中的
位置无关。数据中心网络研究的标准拓扑是 Al-Fares SIGCOMM 2008 的 k-Pod
Fat-tree（满二分带宽 + 确定性路由），争用发生在路径上的共享链路。先研读原理
（Phase 0：`docs/research/FAT_TREE_PRINCIPLES.md`，含 SimGrid FatTreeZone 与
仓库内 CloudSim network.datacenter 源码精读——后者是退化的树非胖树，不可复用），
再设计（Phase 1：`docs/research/FAT_TREE_DESIGN.md`），后实现。

**已交付内容**：

- `org.workflowsim.network.NetworkTopologySpec`：平台拓扑不可变声明
  （`fatTree(k, 链路带宽MB/s[, core数量][, 显式放置])`）；
- `org.workflowsim.network.FatTreeTopology`：k-Pod 结构构造（core 可减量
  超收敛，收敛比 (k²/4)/m）+ **确定性路由**（上行 a = srcEdge mod availA；
  跨 Pod core j = (srcEdge+dstEdge+srcPod+dstPod) mod jCount；下行唯一）
  返回双工分方向的链路资源键序列；
- `TransferContentionEngine` 资源集泛化：传输占用"端点 + 路径链路"资源集，
  速率 = min(名义, 各占用资源份额)；双端点重载委托资源集重载，R2 行为逐位
  不变（既有 12 个引擎测试全绿）；
- `DataMovementModel.fatTreeContentionV1()`（kind
  `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`）：窗口语义与
  R2 相同，争用域推广到路径链路；
- 配置契约：LOCAL 文件系统 + 静态映射 + NONE 聚类 + 无故障/无开销；
  `SimulationRunner` 双向契约——模型缺拓扑声明拒绝、拓扑声明无模型拒绝；
- 证据：`DATA_STAGE_IN_MODELED` 追加 `fatTreePathLinkCount`；
- 测试：`FatTreeTopologyTest`（9：结构计数、三类路由黄金值、超收敛确定性、
  重叠路径半速/不相交零干扰、全量参数校验）+ 引擎资源集单测（+4）+
  `FatTreeContentionIntegrationTest`（7：端到端黄金值 **1032.1** 锁定、
  ≥ R2 黄金值 284.1 的约束超集性质、确定性、健康不变量、契约拒绝组）。

**验收实测**：HEFT 论文例 × LOCAL_HEFT × 3 主机（默认轮转放置到 pod0/edge0、
pod0/edge1、pod1/edge0）× k=4 满配、链路 1 MB/s——makespan 从无争用 190.1 →
R2 端点争用 284.1 → Fat-tree 链路争用 **1032.1**（共享链路进一步减速），
三级模型族构成可对照的研究维度。

**诚实边界**：流级流体模型（无丢包/排队细节/ECN）；确定性最短路径（无自适应
路由/ECMP 哈希）；交换机内部转发不设容量约束；均匀链路带宽；链路延迟不建模；
外部 SOURCE 流量不经过拓扑；无链路/交换机故障。

## 三、长期候选项（未排期）

| 方向 | 说明 |
| --- | --- |
| 能耗模型 | 绿色计算方向，当前发文热点 |
| 通信感知 PEFT | 补齐 Topcuoglu 家族 + PEFT 的完整对比（现在 c=0 下退化） |
| Deadline 感知调度 | deadline 从"观察不干预"升级为 WED 驱动准入/优先级 |
| 工作流分区调度 | 子图调度 / DAG 分区映射 |
| WfInstances 校准报告 | 用真实 makespan 分布对照模拟分布，量化模型偏差界 |
| 敏感性分析工具 | VM 数/带宽/任务规模对结论的影响曲线（审稿人常问） |
| 多算法对比矩阵生成器 | 同轨道内标准对比矩阵一键生成 |

## 四、执行纪律（所有轮次共同遵守）

1. **每轮单独分支**，PR 合并后清理；
2. **做完必须测试**——新增能力必须附带测试，且完整门禁（`mvn verify`，JDK 17）
   全绿后才算完成；无测试的交付不算完成；
3. **CI 强制验证**：每个 PR 在 GitHub Actions 上全绿才可合并；
4. **不改变既有语义承诺**：黄金值、manifest 契约、已声明的平台边界只能扩展，
   不能静默变更；确需变更时在文档与 PR 中显式记录；
5. **诚实记录边界**：每轮新增能力的适用范围与已知限制写入对应文档
   （CATALOG / 本路线图 / 模块 Javadoc）。
