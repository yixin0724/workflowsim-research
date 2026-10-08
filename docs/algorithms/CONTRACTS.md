# 算法、执行与指标语义契约

本文规定算法结果如何对应到模拟执行和证据。可选标签与组合集中在[算法目录](<CATALOG.md>)，不在这里重复完整清单。所谓复现，是在明确的 WorkflowSim 模型下实现并验证核心决策语义，不是重建论文目标环境的全部硬件、网络或软件行为。

测试应以独立算术、外部表格或独立小模型给出预期，而不是调用被检验的生产决策函数生成答案。下文的测试链接说明被覆盖的具体性质，不表示任意合法配置都已逐一测试，更不构成真实平台校准。

## 标准执行与工作量

[SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)和[SimulationRunner](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java>)共同校验：

- 标准运行使用 NONE 聚类、SPACE_SHARED VM；Task 的 PE 需求不得超过所选 VM 的 PE 数，每台 VM 同时最多派发一个 Job。
- 非 INVALID 规划器必须配 STATIC。没有规划器的 STATIC 仅在显式 coherent 在线绑定配置下合法，此时先由绑定策略确定目标 VM，STATIC 不重新选择目标。
- 平台预检资源和确定性 VM→Host 放置；计划在 VM 时间线上安排工作，不把 Host 共置解释为已校准的跨 VM 干扰或迁移模型。
- 输入与到达表在启动前声明，运行期按时刻释放。同一 JVM 的运行是串行的；静态计划的时间估计不能未经验证当作任意错峰到达的事件回放。

[TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>)区分原始单 PE 长度与当前尝试的有效计算量：

```text
L_eff(t,v) = L_raw(t)                         没有任务成本矩阵
L_eff(t,v) = Math.round(matrixSeconds(t,v) * mips(v))  有矩阵
computeSeconds(t,v) = L_eff(t,v) / mips(v)
```

矩阵一旦存在就是权威成本输入：缺少坐标不得回退到原始长度，舍入结果必须为正，MI×PE×1,000,000 必须可用 signed long 表示。多个 PE 并行执行单 PE 工作量，不能把总长度再除以单 PE MIPS 当作墙钟计算时长。原始 `lengthMi` 不被覆盖；`effectiveExecutionLengthMi` 描述当前尝试的实际计算工作。

时间感知独立规划、PSO、LOCAL 规划和对应执行使用这一转换；SHARED_STORAGE 规划器拒绝矩阵，采用下文的共享存储时长。`executionSemantics` 的当前标识为 `WORK_CONSERVING_TASK_EXECUTION_V2`，不能通过改写旧 manifest 将不同执行口径变成相同证据。

[矩阵回归](<../../simulator/src/test/java/org/workflowsim/planning/PlanningCostMatrixRegressionTest.java>)覆盖与原始 MI 排名相反的成本、舍入、缺坐标和成本无关对照；[PE 域测试](<../../simulator/src/test/java/org/workflowsim/planning/PlanningPeCompatibilityTest.java>)覆盖更快但不兼容的 VM、空可行域以及 CPOP 全路径兼容性。

## 静态顺序、插入与平局

完整静态 DAG 规划输出映射和计划开始时间；[StaticSchedulePlan](<../../simulator/src/main/java/org/workflowsim/scheduling/StaticSchedulePlan.java>)按计划开始时间构造每 VM 的 Job 序列，相同开始按 Job ID 排序。[StaticSchedulingAlgorithm](<../../simulator/src/main/java/org/workflowsim/scheduling/StaticSchedulingAlgorithm.java>)只放行该 VM 下一项已经 ready 的 Job，**不强制绝对计划开始时刻**。

后选择的 Task 可以插入先前预留区间之间的合法空档，因此算法选择顺序不等于最终执行顺序。未 ready 的队首可能阻塞其后的 Job；不能为改善某个算法的结果而临时取消顺序。RANDOM、PSO 和独立任务映射器没有完整计划，只保留映射，由引擎处理依赖释放。

模型生成的 stage-in Job 不是源逻辑 Task；静态派发使用最低 VM ID 的后备位置处理这类 Job。其 110 MI 工作量及根任务释放间隔会影响绝对时刻；偏移取决于 VM MIPS 和内核节拍，不是所有实验共用一个固定秒数。计算完成事件还受最小事件间隔和安全余量影响，计划并不完整重放内核事件队列。

平局应按各策略的明确规则处理：ready-batch Min/Max-Min 的任务平局用较小 Job ID、候选 VM 平局用较小 VM ID；FCFS/MCT 保留 ready 到达序，轮转保留跨批次游标。静态独立任务按 Task ID 建立顺序；静态 DAG 的等优先级、等候选分数通常用 Task/VM ID，DLS/ETF 另有下述优先级规则。不得用不稳定的集合遍历次序代替这些规则。

[共享存储 HEFT 测试](<../../simulator/src/test/java/org/workflowsim/planning/SharedStorageHeftPlanningAlgorithmTest.java>)检验 rank、PE 排除和空档插入；[DLS/ETF 插入边界测试](<../../simulator/src/test/java/org/workflowsim/planning/SharedStorageDlsEtfSlotSemanticsAuditTest.java>)覆盖相接区间、微小空档、浮点边界与预留变化，避免以缓存或区间合并改变调度语义。

## 通信估计与执行模型

[Topcuoglu、Hariri、Wu 的 HEFT/CPOP 论文](<https://doi.org/10.1109/71.993206>)使用任务计算成本和处理器间通信成本来估计排程。论文中的边通信假设、模拟器的文件系统、传输发生的时点和 VM 占用范围并非同一件事。不能把带通信项的纸面 HEFT 预测，直接解释为任意 SHARED 配置的执行时间。

### SHARED 的计算信封模型

SHARED_STORAGE 规划器要求 SHARED、legacy 数据移动、无故障/开销、NONE 聚类和 SPACE_SHARED。对每个 Task 先累加真实输入的存储延迟，再一次性转换为 MI：

```text
inputSeconds(t) = sum(bytes(file) / 1e6 / storageRate)
duration(t,v) = (L_raw(t) + floor(mips(v) * inputSeconds(t))) / mips(v)
```

不是逐文件分别 floor，也不是免费通信。父子依赖通过引擎释放约束，输入延迟被加入 Job 执行信封；该轨道没有点对点通信边、路径或共享链路争用。计算时长与 stage-in 估计的实现见 [SharedStorageDagPlanner](<../../simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java>)。固定端点模型也在 Job 内建模输入延迟，但附加接入参数不同，因此不能未经适配用于这些 SHARED_STORAGE 规划器。

### LOCAL 的 V1 输入可用性估计

LOCAL_HEFT/CPOP/PEFT 要求 LOCAL、STATIC、无故障/开销，以及 V1 pre-execution 家族。无争用计算使用有效 MI；文件传输使用十进制 MB/s：SOURCE→VM 受目标端点带宽约束，VM→VM 使用两端带宽的较小值。

- 在 Task 的依赖就绪时点，只读当前部分计划中已可用的副本；目标已有可见副本才免传输。
- 同一父任务的文件延迟相加，从父任务的计划完成时刻估计到达；不同父组可以重叠。
- 根与非根的外部输入均从消费 Job 依赖就绪时开始。平台级 stage-in 登记不意味着文件已在目标 VM 本地。
- 输入就绪取依赖就绪、各父组到达及外部输入到达的最大值；正 hold 受最小事件间隔约束。
- INPUT 副本在完整 hold 结束时可用，OUTPUT 在计划计算完成时可用。未来副本不得让插入更早空档的任务提前命中；输入可以早于消费者真正开始计算时到达。
- VM 只预留计算，输入准备可与 VM 忙碌区间重叠。这里是部分计划估计，不是整个运行期事件重放。

[输入可用性回归](<../../simulator/src/test/java/org/workflowsim/planning/LocalDataAvailabilityPlanningTest.java>)覆盖外部根输入、未来副本、有效空档和输入到达早于计算开始。文件名/大小冲突可能使某些现成输入不适用，不能从普通在线运行成功推导 LOCAL 规划也合法。

启用 V1 端点或 Fat-tree 争用时，**规划仍无争用**，运行期所有父组在 Job 就绪时统一启动，不追溯更早父完成以来的传输进度。求解器采用受名义上限与资源容量共同约束的 max-min progressive filling，并在内部完成点回收、重新分配容量；不是简单固定的 `capacity/n`。因此无争用 V1 与争用 V1 的差异还包含传输起点规则。Fat-tree 另加入确定性有向路径约束，外部 SOURCE 仍绕过 fabric，详见[拓扑与路由契约](<../research/FAT_TREE_DESIGN.md>)。

### coherent 文件、存储与控制平面

coherent V2/V3 是独立的逐文件生命周期，不是把 LOCAL 规划器的估计替换成精确网络重放。控制依赖就绪、目标绑定、输入可见、CPU 执行和 SOURCE 提交必须分开解释。

- **V2 文件数据流**：LOCAL；按实际可见源副本和实际路径准备输入，同文件同目标可合并复制，各文件独立观察可见；外部 SOURCE 显式无限汇聚并绕过 fabric。
- **V3 存储数据流**：必须声明受限 SOURCE 的附着 Host、读/写/NIC 容量。LOCAL 可读 VM 副本；SHARED 必须先等 SOURCE 提交，再用目标缓存或从 SOURCE 读，同 VM 缓存也不能绕过提交。
- V3 所有成功输出都有写回义务，包括未被消费的 sink 和零字节输出；CPU 返回不等待写回，但模拟结束等待义务完成。失败尝试不发布可读输出。
- 两个家族均可选 Fat-tree。无争用对照保留相同路径、生命周期和单流瓶颈，只去掉跨流共享，不把传输改成零成本。
- 未启用在线绑定时要求 RANDOM/STATIC；显式 `CONTROL_READY_ONLINE_ASSIGNMENT_V1` 使用 INVALID/STATIC，在控制就绪后根据名义输入估计和 CPU 预留选目标，然后准备输入。NOOP 重试复用原逻辑绑定。

`dataflowPlan` 记录文件身份和生产/消费关系，不是 STATIC 的每 VM 执行顺序计划。生命周期证书记录来源、路径、可见性与因果，不认证逐区间服务面积或未来拥塞；在线动作认证也不重放精确在途余额。Workbench 在线导出要求完整且匹配的生命周期捕获，Java OFF/截断运行不能冒充完整动作证书。模型未实现 TCP、丢包/ECN、包级队列或自适应路由。

具体字段与数值域见[V2 契约](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>)、[V3 契约](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)和[在线绑定契约](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。对应的[存储运行测试](<../../simulator/src/test/java/org/workflowsim/data/v2/StorageDataflowRuntimeTest.java>)与[在线证据测试](<../../simulator/src/test/java/org/workflowsim/experiment/OnlineDataflowArtifactIntegrationTest.java>)检查提交门控、输出义务、失败来源和捕获完整性。

## 优先级与成本语义

### HEFT、CPOP、DLS 与 ETF

记 `w̄(t)` 为兼容 VM 上的平均模型时长，`c̄` 为所选模型的平均通信估计；SHARED 的通信项为 0，模型时长包含其共享存储输入延迟。

```text
r_u(t) = w̄(t) + max_child(c̄(t,child) + r_u(child))
r_d(entry) = 0
r_d(t) = max_parent(r_d(parent) + w̄(parent) + c̄(parent,t))
CPOP priority(t) = r_u(t) + r_d(t)
```

出口任务的 `max_child` 项为 0。HEFT 按 upward rank 降序，再选插入式最小 EFT。CPOP 在依赖就绪集合内选择优先级最大者；关键路径沿满足 rank 递推的实际边确定，选择一条而不是所有等优先级节点，并固定到支持整条路径、总模型时长最小的 VM。

SHARED_STORAGE_DLS 在 ready Task-VM 对中最大化 `b-level − earliestInsertionStart`，等分用 Task ID、VM ID。SHARED_STORAGE_ETF 最小化插入开始时间，同开始先选更高 b-level，再用 Task/VM ID。它们保留 [DLS](<https://doi.org/10.1109/71.207593>)与 [ETF](<https://doi.org/10.1137/0218016>)的选择核心，但不因此取得原文的互连硬件、通信资源或同构处理器假设。

### PEFT 的后继成本递推

对兼容 VM，当前 PEFT 使用后继成本而非当前 Task 自身成本：

```text
OCT(exit,v) = 0
OCT(t,v) = max_child min_compatible_v' {
    OCT(child,v') + w(child,v') + c(t,child,v,v')
}
rank_o(t) = mean_compatible_v OCT(t,v)
OEFT(t,v) = insertionEFT(t,v) + OCT(t,v)
```

每步只在父任务均已分配的 ready-list 中选择最大 `rank_o` 的 Task，再选最小 OEFT 的兼容 VM；平局用较小 Task/VM ID。子任务平均 OCT 高于父任务并非错误，ready-list 保证分配顺序。

LOCAL 的不同 VM 通信按端点带宽模型计算，同 VM 为 0；OCT 是静态 look-ahead，不使用运行期副本折扣，实际插入 EFT 另按部分计划的副本可用性估计。SHARED 去掉通信项后，兼容候选的 OCT 与当前 VM 无关，处理器选择退化为 EFT，但任务优先级仍可能不同；不能把它称为网络感知 PEFT。

递推和来源页码见保留不变的 [PEFT 论文夹具来源说明](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>)。[独立递推反例](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftSuccessorCostContractTest.java>)覆盖出口、后继成本与 rank 反转；[原文夹具回归](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java>)用字面矩阵/OCT/选择顺序验证计划和实际 Task/Job 区间。当前机器契约标识为 `PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`；自身成本/出口均值的变体不能重命名为原 PEFT。

### 独立任务与仅映射目标

独立任务映射器拒绝任何父/子边；其 availability 是暂定累计计算负载，不是运行期队列回放。OLB 按 availability 选择但仍用有效计算时长更新；MET 忽略 availability；MCT、Min/Max-Min 和 Sufferage 使用完成估计。Sufferage 只有一个兼容 VM 时定义损失为 0，轮转有意不看成本。命名依据[独立任务映射分类](<https://doi.org/10.1006/jpdc.1999.1581>)，不是给在线 ready-batch 自动赋予离线语义。

RANDOM 在按 ID 排序的兼容 VM 中沿输入 Task 顺序抽样。PSO 则先按 Task/VM ID 固定坐标，并将不兼容位置投影到最近合法 VM 下标，等距取较小 ID，不额外抽样。其参考实现与常量见 [PSOPlanningAlgorithm](<../../simulator/src/main/java/org/workflowsim/planning/PSOPlanningAlgorithm.java>)。

```text
PSO fitness = 0.8 * sum(effectiveSeconds(t,assignedVm) * assignedVmMips / 1000)
            + 0.2 * max_vm(sum_assigned_tasks effectiveSeconds(t,vm))
```

该目标忽略 DAG 边和网络。只有无矩阵的原始 MI 模型中，成本项在数学上才等于 `sum(L_raw)/1000`、与映射无关；浮点舍入须单独考虑。有矩阵时成本可以随映射变化。它不是实际云计费，也不保证优于其他基线。

RL 使用另一条 ready-job 契约：动作按 ready 到达序排列，值为 VM ID 排序后的列表下标而非 VM ID，`-1` 跳过；null、长度错误或越界中止，忙/不兼容/已占用目标使该 Job 本轮跳过而不自动重选。成功派发进入轨迹，奖励为负 makespan；持续无进展会被拦截。实现见 [RL 派发器](<../../simulator/src/main/java/org/workflowsim/scheduling/RlPolicySchedulingAlgorithm.java>)，不包含学习算法。

## 指标与观察样本

指标由 [SimulationMetrics](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationMetrics.java>)计算，不是生产平台遥测。时间均为模拟秒，墙钟决策耗时另行报告。

### 等待、减速比与缺失观察

对有有效观察的计算 Job 尝试 j：

```text
wait_j = start_j - JOB_READY_j
execution_j = finish_j - start_j
slowdown_j = max((wait_j + execution_j) / max(execution_j, 10), 1)
meanComputeTrueSlowdown = arithmetic_mean(slowdown_j)
```

先对每个尝试按上述两个 `max` 计算，再求均值；不是对汇总均值做一次比值，也没有减速比上限。`execution_j` 是 Job 执行信封：legacy/固定端点输入可能包含在其中，pre-execution/coherent 输入准备则发生在开始前。`JOB_READY` 与文件数据就绪不是同一事件，等待不能一律解释为纯调度器排队。

- 只对有 `JOB_READY` 且 `start >= ready` 的计算 Job 统计总等待和 bounded slowdown；缺失或不合顺序的观察不以零值加入样本。重复关键事件会显式失败。
- 正常均值包含成功、失败与 retry 尝试；`successOnlyMeanComputeTotalWaitingTimeSeconds`、`successOnlyMeanComputeTrueSlowdown` 是单独的成功样本，不是同一统计量的改名。
- 无观察时聚合字段可能按 API 约定为 0.0，必须结合 `totalWaitingTimeObservationCount`、`trueSlowdownObservationCount` 或 `successOnlyWaitingObservationCount` 识别缺测，不能报告成实测零等待。
- ready→decision 与 decision→start 有各自的观察数；只有样本集合相同才能把两者均值相加得到总等待。
- VM queue waiting 从 VM submission 起算；response 为 submission→finish，不包含此前的 ready 等待。VM 级 slowdown 不能替代这里的 ready 基准。
- 分位数使用升序样本的最近秩 `ceil(q*n)`，偶数样本的中位数不做两中值平均；小样本 P95 可能退化为最大值。bounded slowdown 为 1 也不证明没有等待。

[等待指标测试](<../../simulator/src/test/java/org/workflowsim/experiment/JobWaitingTimeMetricsTest.java>)覆盖手算值、10 秒下界、缺失事件、成功样本和分位数。事件重算应过滤计算 Job（`classType == 2`），不能把 stage-in 的 ready 事件混入该样本。

### 其他核心指标

| 指标 | 统计口径与边界 |
| --- | --- |
| Job 计数、成功率与吞吐 | 总 Job 数可含 stage-in；计算 Job 成功率/吞吐以计算尝试为样本，失败和 retry 不等于新的逻辑 Task |
| 逻辑任务完成 | 一个源 Task 至少有一次成功计算尝试才算完成；retry 谱系须可追溯到失败父尝试 |
| `makespanSeconds` / `simulationEndSeconds` | 同一个模拟结束时钟，不是从首个计算 Job 开始计时 |
| `logicalTaskCompletionSeconds` | 全部源 Task 成功时，取各 Task 首次成功 Job 信封完成时间的最大值；无逻辑任务或未完成时不可用 |
| `terminalLifecycleTailSeconds` | 完成工作流的模拟结束与逻辑完成之差；V3 输出义务可以形成尾部，不能当 CPU 计算时间 |
| 重试与失败成本 | 每个已返回尝试分别计数；`retryAmplificationRatio` 为逻辑 Task 尝试数/逻辑 Task 数，不是失败概率 |
| VM 繁忙与利用率 | 每 VM 已完成 Job 区间的并集，包含适用的 stage-in/失败/retry，除以模拟结束时刻；不是 Host 利用率或网络占用 |
| 输入需求与名义传输秒数 | 含工作流父任务产生的 Job 外部输入，不仅是 SOURCE；名义估计、实际复制字节与争用墙钟不同，不能互相替代 |
| 抽象处理成本 | 每个已返回尝试的 CPU 信封成本加声明文件带宽成本；字节按连续十进制 MB 换算，无逐文件账单取整；memory/storage 价格声明不等于实际收费 |
| 决策开销 | 显式规划与调度周期的 `System.nanoTime` 观察，不推进模拟时钟，不要求确定性重跑逐位相同 |
| 受控关键路径/SLR 参考 | 仅适用声明的 SHARED、NONE、无故障/开销、SPACE_SHARED、legacy/固定端点范围；并行输入的 pre-execution/coherent 模型不可套用串行输入下界；不可用时查看 scope/available 而非把零当有效下界 |
| deadline SLA | 从模拟零时刻比较精确 signed-long 阈值与 binary64 结束时钟，先精确比较再计算显示余量；未请求或工作流未完成须区分，不改变派发、准入、重试或终止 |
| Task 时间 | 原始长度与有效计算 MI 分开；Task 窗口使用当前尝试工作量，精确 Task/Job 一致性还需检查时序范围与单 Task 条件 |

Jain 指数为 `(sum(u))² / (n * sum(u²))`。在同一窗口、同一 VM 样本且非零平均负载下，`Jain = 1 / (1 + CV²)`，两者不是独立的分布形态证据；空或全零利用率时返回 1.0 是约定，不表示资源被充分利用。

## 证据与主张范围

失败模型在尝试信封完成边界判定结果，仅成功输出才能成为后续可读来源；这是受控尝试/重试语义，不是中途断电、真实分布式事务或存储系统校准。

manifest 中的算法契约、执行版本和模型配置必须与实际输入、平台和证据一致。工件结构/哈希校验、生命周期因果检查、算法 oracle、统计显著性和现实校准是不同层次，不能互相替代。保持原始证据身份，不通过改名、改 golden 或混合不同决策/执行层来掩盖差异。
