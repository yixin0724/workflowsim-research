# 算法原理导读

本文面向初次使用者，解释算法决定什么、使用哪些信息，以及结果能说明什么。
严格边界见[算法目录](<../algorithms/CATALOG.md>)与[语义契约](<../algorithms/CONTRACTS.md>)。

## 一、先区分决策范围

当前支持7种在线策略、17种非空规划器；`STATIC` 是执行规划结果的分派器，不是另一个
优化算法。它们回答的不是完全相同的问题，不能把不同决策层、文件系统和成本模型的结果
无说明地混为同一基线。

| 决策范围 | 何时决定 | 可用信息 | 输出 |
| --- | --- | --- | --- |
| 在线 ready-batch | 运行中、Job 依赖满足后 | 当前 ready Job、VM 状态；DATA 另读副本位置 | 本次分派 |
| 静态独立任务映射 | 执行前 | 无依赖任务集合、兼容 VM、计算成本 | Task→VM，无完整执行顺序 |
| DAG 仅映射 | 执行前 | 全部 Task、VM；PSO 的目标仍忽略 DAG 边 | Task→VM，无完整执行顺序 |
| 受控静态 DAG 规划 | 执行前 | DAG、文件、兼容 VM、声明的成本模型 | Task→VM＋每 VM 的 Job 顺序 |

**共同限制**：标准 `SimulationRunner` 对所有算法均要求 `NONE` 聚类、`SPACE_SHARED`
VM。任何非空规划器都必须搭配 `STATIC`；`STATIC` 也必须有规划器。任务需要的 PE 数
不能超过所选 VM 的 PE 数。调度层仍然每台 VM 同时最多派一个 Job，不因 VM 多 PE 就并发
派多个 Job。

**成本口径**：独立任务的时间感知策略、PSO 和 LOCAL 规划器通过 `TaskExecutionModel`
与运行时共用计算转换：

```text
无矩阵：effectiveSeconds(t,p) = rawPerPeLengthMi(t) / mips(p)
有矩阵：effectiveSeconds(t,p) = round(matrixSeconds(t,p) * mips(p)) / mips(p)
```

矩阵存在时不能缺坐标后偷偷回退到原始长度。非正舍入结果、非有限数值及无法用 signed-long
指令计数表示的工作量会显式失败。原始 Task 长度不被改写；有效执行 MI 是另一个概念。
这些公式描述计算工作，不包含整个 Job 的排队、输入传输或完成事件尾部。

## 二、在线 ready-batch 调度器（7种）

引擎先满足依赖，再交给调度器 ready Job。每轮每台空闲兼容 VM 最多接一个 Job，
分配后置忙；排不下的 Job 等后续事件。在线策略不能搭配要求预先知道目标 VM 的
preExecution 数据移动模型，也不接受离线任务成本矩阵。

| 算法 | 决策 |
| --- | --- |
| `FCFS` | 按 ready 到达序，给第一个空闲兼容 VM（VM ID升序） |
| `READY_BATCH_ROUNDROBIN` | 保留跨批次 VM 游标，跳过忙或不兼容 VM，成功分配后推进 |
| `READY_BATCH_MCT` | 按 ready 到达序，逐个选择 `length/MIPS` 最小的空闲兼容 VM |
| `READY_BATCH_MINMIN` | 每轮求各未分配 Job 的最小 ECT，再选全局最小者；分配后重算 |
| `READY_BATCH_MAXMIN` | 每轮求各未分配 Job 的最小 ECT，再选其中最大者；分配后重算 |
| `DATA` | 仅用于 LOCAL：选择非本地真实输入字节数最少的空闲兼容 VM |
| `RL_POLICY` | 在同一 ready 域调用注册策略，由 `RlEnvironment` 驱动 episode |

这里 ECT 的比较量是 `length/MIPS`，不预订未来机器可用时间，也不包含传输时长。
固定正 length、相同候选集合下，最小 ECT 与最大 MIPS 等价，即使 VM 异构也是如此。
Min/Max-Min 在共同兼容域、可分离成本下也可能与短/长任务优先重合；不能用标签差异
代替行为证据。不同 PE 需求、平局和批次变化应单独检查。

P7冻结主矩阵只包含前五种；DATA、RL不是那个矩阵中的额外单元。DATA比较的是**字节**，
不是端点带宽、延迟或争用时间。旧 `MINMIN/MAXMIN/MCT/ROUNDROBIN` 标签只用于兼容，
标准入口拒绝它们；迁移不意味着每份输入的映射必然改变。

RL动作按 ready到达序对应每个Job，取值是按VM ID排序后的**列表下标**，不是VM ID。
`-1`表示本轮跳过；null、数组长度错误或越界值中止；忙、不兼容或本轮已占用VM的动作被
跳过而不自动重选。轨迹只记录成功分派，奖励是 `−makespan`。环境不含学习器，持续不分配
会被停滞检查拦截；外部训练器需要桥接。

## 三、静态独立任务映射器（7种）

任意父边或子边都会被拒绝。任务按ID排序；等价候选按Task/VM ID确定性破平局。
`availability` 是规划时暂定的累计计算负载，不是运行期队列回放。

| 算法 | 选择规则 |
| --- | --- |
| `STATIC_OLB` | 选availability最小的兼容VM，再用该任务的有效计算时长更新availability |
| `STATIC_MET` | 选有效计算时长最小的兼容VM，忽略availability |
| `STATIC_MCT` | 选 `availability + effectiveSeconds` 最小的兼容VM |
| `STATIC_MINMIN` | 逐轮选各任务最小暂定完成时间中的最小者 |
| `STATIC_MAXMIN` | 逐轮选各任务最小暂定完成时间中的最大者 |
| `STATIC_SUFFERAGE` | 选“次优完成时间−最优完成时间”损失最大的任务；仅一个兼容VM时损失为0 |
| `STATIC_ROUND_ROBIN` | 按Task ID轮转选择兼容VM，故意不看成本与负载 |

时间感知策略真正消费矩阵。OLB虽不比较当前任务在不同VM上的执行快慢，也必须用有效
时长更新负载。矩阵下“MET选择最快机器”指**执行这个任务最快**，不等于选择MIPS最大者。
这些算法只给映射，不给网络计划或完整每VM执行顺序。

## 四、受控共享存储 DAG 规划器（5种）

要求 `STATIC`、`SHARED`、NONE聚类、无开销/故障、SPACE_SHARED，且必须使用
`legacyWorkflowsimV1()`。这一轨道**拒绝成本矩阵**，不能把别的规划器支持矩阵推广到它。

计算用原始单PE长度；先把全部真实输入的 `bytes/1e6/storageRate` 相加，再把总秒数折算
为整数MI。候选模型时长为 `(rawPerPeLengthMi + floor(mips * transferSeconds)) / mips`。
不是逐文件floor，也不是把Task长度乘PE数当墙钟时长。这里没有点对点通信项，但共享存储
输入延迟并非免费。

| 算法 | 优先级与分配 |
| --- | --- |
| `SHARED_STORAGE_HEFT` | 兼容VM平均时长构成upward rank，降序选择后做EFT空档插入 |
| `SHARED_STORAGE_CPOP` | ready任务中选最大 `r_u+r_d`；一条确定关键路径固定在全路径兼容且总时长最小的VM |
| `SHARED_STORAGE_DLS` | 每步选依赖就绪Task-VM对中 `b-level − 最早插入开始时间` 最大者 |
| `SHARED_STORAGE_ETF` | 每步选依赖就绪Task-VM对中最早插入开始时间最小者；同开始先比较b-level |
| `SHARED_STORAGE_PEFT` | ready任务中选最大平均OCT，再选 `EFT+OCT` 最小的兼容VM |

这一轨道的OCT是**后继成本**：

```text
OCT(exit,p) = 0
OCT(t,p) = max_child min_compatible_p' { duration(child,p') + OCT(child,p') }
```

由于通信项为0，OCT与当前候选VM无关，处理器选择退化为纯EFT；与HEFT的差异仍可能来自
任务优先级。不能把这称为网络感知PEFT。

完整静态计划强制的是**每VM顺序**，不是绝对计划时间。模型生成的110MI stage-in Job先在
最低ID VM运行，根Job随后经历内核释放间隔。事件定序、短任务完成规则仍可能引起预测差异。

## 五、通信感知 LOCAL DAG 规划器（3种）

LOCAL_HEFT/CPOP/PEFT与共享存储轨道使用不同输入传输模型。它们要求STATIC、LOCAL、
NONE聚类、无开销/故障、SPACE_SHARED，以及preExecution家族数据模型。计算支持成本矩阵；
候选必须PE兼容，rank均值也仅覆盖兼容候选。

| 算法 | 优先级与分配 |
| --- | --- |
| `LOCAL_HEFT` | 含平均通信成本的upward rank降序；兼容VM中最小插入EFT |
| `LOCAL_CPOP` | ready列表内最大 `r_u+r_d`；关键路径放到全路径兼容且总计算成本最小的VM |
| `LOCAL_PEFT` | ready列表内最大兼容VM平均OCT；兼容VM中最小 `EFT+OCT` |

CPOP采用前驱方向的向下rank：

```text
r_d(entry) = 0
r_d(t) = max_parent { r_d(parent) + meanCompute(parent) + meanCommunication(parent,t) }
priority(t) = r_u(t) + r_d(t)
```

关键路径沿既保持关键优先级、又满足upward-rank递推的实际边前进。多条等长路径按ID
选一条，不把跨分支捷径或所有同优先级节点都当成该路径。

### 数据何时可用

无争用估计逐文件使用 `bytes/(1e6*rate)`：SOURCE→VM受目标带宽限制，VM→VM取两端
较小带宽。在任务依赖就绪时，只使用当前部分计划中已经可用的副本；目标VM有可见副本
才免传输。

- 同父文件延迟相加，从该父的计划完成时刻估计；不同父组可并行。
- **外部输入从消费Job依赖就绪时开始，包括根任务**。stage-in登记到平台不等于文件已在
  目标VM本地，不能免掉根的外部输入传输。
- 输入就绪候选取依赖就绪、各父到达、`dependencyReady + externalTransferSeconds`的最大值。
  正hold按最小事件间隔钳制。
- 副本带availableAt：全部INPUT在**整个hold结束**时可见，OUTPUT在计算完成时可见。
  输入可早于消费任务的计算开始到达；反过来，先被规划的任务不能把未来副本提前暴露给
  后选择、却插入更早空隙的任务。
- VM只预留计算，传输可以与VM忙碌期重叠；可行的早期空档仍能被利用。

这是**部分计划估计，不是完整事件重放**。同刻事件次序、后规划任务更早产生的副本、
短计算完成以及并发争用仍可能造成计划与运行差异。固定论文夹具对齐不等于任意DAG都对齐。

### PEFT已由一级来源核定的递推

```text
OCT(exit,p) = 0
OCT(t,p) = max_child min_compatible_p' { OCT(child,p') + w(child,p') + c(t,child,p,p') }
rank_o(t) = mean_compatible_p OCT(t,p)
```

同VM的c为0；OCT从**后继任务**开始计成本，不含当前任务自身计算量。每步只在**父任务均已
分配**的任务中选最大rank_o，再选插入EFT+OCT最小的VM。原文Algorithm1就使用ready-list，
子任务rank高于父任务仍必须等父先分配。

已取得作者[公开博士论文](https://repositorio-aberto.up.pt/handle/10216/92290)第3章收录的原文章：
印刷p71 Eq(7)明确后继计算成本与exit0，p73 Eq(9)/Algorithm1给出OEFT及ready-list，
图1与表5/6完整数据也已独立交叉核算。来源、作者版页码和转录说明见
[真实论文夹具说明](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>)。
原文使用平均链路成本；当前LOCAL的异构端点min(bw)和副本仍是需要声明的模型适配。

旧LOCAL_PEFT把自身w计入OCT并将出口设成均值，这**不是原PEFT**。只在同一递推下改变
单出口统一常数才会整体平移；这不能为“把后继w换成自身w”辩护。当前LOCAL和SHARED均
使用后继成本/出口0，但共享存储时长、通信和运行模型依然不同。新契约版本为
`PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`；历史R12/R13旧变体结果不能仅以一般模型版本差异
解释，更不能改名充当原PEFT复现。

现在明确区分两份10任务、3VM夹具：

- **真正PEFT文章图1**的计算矩阵首行为22/21/36，Table5首行OCT为64/68/86、出口全0；
  Table6选择顺序为`1,4,6,2,3,5,8,7,9,10`，PEFT纯makespan122，HEFT控制为133。
  在声明的110.1 bootstrap下，端到端回归预期为232.1/243.1，并核对TaskOutcome有效MI及
  计算窗口和Job一致。
- **保留HEFT-origin夹具**首行为14/16/9，不是PEFT文章图1。其HEFT/CPOP参考值仍为
  80/86（绝对190.1/196.1）；CPOP路径`{1,2,9,10}`在VM1。正确Eq7对这份旧输入的独立
  结果是PEFT85（绝对195.1），由单独HEFT-origin regression维护。旧76/186.1来自错误
  递推，不应称为论文PEFT结果。

固定夹具可分别验证行为，不能证明任一算法在所有输入上更优。

### 争用变体

LOCAL三个规划器还接受端点争用 `preExecutionTransferDelayWithContentionV1()` 与
`fatTreeContentionV1()`；后者要求平台声明Fat-tree拓扑，且声明拓扑时必须选择该模型。
规划仍无争用，运行期争用组在Job就绪时开始，不追溯早先父任务完成以来的传输。
端点/Fat-tree模型使用max-min progressive filling，按流完成时点分段积分并重分配带宽。
SOURCE流绕过Fat-tree链路，但仍受目标端点约束。不能把“增加链路约束后任意DAG的makespan
必然变大”当作数学不变量。

## 六、DAG仅映射基线（2种）

`RANDOM`在每个Task的兼容VM中按命名种子抽样；VM按ID排序，但Task抽样顺序沿输入列表。
它不优化矩阵、也不生成执行顺序；矩阵仍可决定映射后的运行时计算时长。

`PSO`使用30粒子、100轮、惯性0.7、c1=c2=1.5，目标为
`0.8 * cost + 0.2 * 最大VM顺序计算负载`，单价`MIPS/1000`。它消费有效矩阵计算时间，
但fitness仍忽略DAG依赖和网络。粒子不兼容位置投影到最近合法VM下标，等距取较小ID，
不增加随机抽样；全兼容平台保留原坐标和随机流。

**成本是否变化取决于模型**：无矩阵时，`(rawLength/MIPS)*(MIPS/1000)`约掉MIPS，成本项
数学上与映射无关；仅改变MIPS异构度不能改变这一点。有矩阵时，
`cost = sum(effectiveSeconds(task,assignedVm)*assignedVmMips/1000)`可以依映射改变。
两种情况都不是真实云价格校准，也不保证PSO在所有输入上优于随机。

## 七、如何选择和比较

- 在线派发问题：使用online轨道，明确是否包含DATA/RL，勿把它们加入冻结P7矩阵冒充原单元。
- 无依赖计算映射：使用STATIC独立任务轨道，声明raw-MI还是矩阵模型。
- DAG输入但只需映射基线：RANDOM/PSO＋STATIC；不要解读为完整离线排程。
- DAG完整规划：在SHARED与LOCAL中选一个固定模型，分别建立实验矩阵。
- 多工作流到达：在线轨道有相应arrival接口；静态计划的零时刻假设不能未经验证推广到错峰到达。

比较时固定输入、VM/Host、文件系统、数据移动、矩阵、开销/故障和指标范围，并记录执行语义
版本。新证据中的`executionSemantics`用于区分修复前后的运行口径；不要只改golden或覆写
冻结结果来掩盖模型变化。完整可用标签以`AlgorithmCatalog.isSupportedBySimulationRunner()`为准。
