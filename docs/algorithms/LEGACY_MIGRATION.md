# 遗留算法迁移指南

## 概述

WorkflowSim 1.0 包含一些历史遗留算法实现，它们的行为与学术文献中的经典定义不符，或与当前执行模型不对齐。为了保持科研模拟器的准确性和可追溯性，**SimulationRunner 标准入口拒绝这些算法标签**。

本文档说明哪些算法已被弃用、为什么被弃用，以及如何迁移到受维护的替代方案。

> **R9 注记（2026-09）**：§2 的遗留规划枚举 `PlanningAlgorithm.HEFT` / `PlanningAlgorithm.DHEFT`
> 已在 R9 清理轮从代码中移除——引用它们的旧代码在当前平台不再编译，迁移目标仍是
> `SHARED_STORAGE_*` 系列。§1 的遗留在线调度器标签（`MINMIN`/`MAXMIN`/`MCT`/`ROUNDROBIN`）
> 仍保留在枚举中且继续被 `SimulationRunner` 标准入口拒绝，迁移指引依旧有效。

---

## 1. 遗留在线调度器（Online Schedulers）

### 1.1 `MINMIN` → `READY_BATCH_MINMIN`

**问题：**
- 原实现是 **SPT-Fastest-Idle**（Shortest Processing Time + 最快空闲 VM）
- 与经典 Min-Min（Braun et al. 2001）定义不符：
  - 经典 Min-Min：计算每个任务在所有机器上的 ECT，选全局最小 ECT 的任务-机器对
  - 本实现：每批次选最短任务，分配给最快空闲 VM，不考虑全局 ECT 矩阵

**迁移方案：**
```java
// 旧代码
SimulationConfig config = SimulationConfig.builder("workflow.dax", 4)
    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
    // ...
    .build();

// 新代码
SimulationConfig config = SimulationConfig.builder("workflow.dax", 4)
    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
    // ...
    .build();
```

**语义说明：**
- `READY_BATCH_MINMIN` 对每个 ready Job 求当前空闲兼容 VM 上的最小 `length/MIPS`，再选全局最小者；每次分配后将 VM 置忙并重算。
- 每轮每台 VM 最多接一个 Job，不维护离线规划器的未来可用时间。
- 在全部任务拥有同一兼容候选集合时，这个可分离成本模型可能与短任务优先给出相同结果；不能仅凭新旧标签断言输出必然不同。
- 适用于在线调度场景（任务动态到达）
- 如需经典 Min-Min 行为，应使用静态独立任务规划器：
  ```java
  .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
  .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MINMIN)
  ```

---

### 1.2 `MAXMIN` → `READY_BATCH_MAXMIN`

**问题：**
- 原实现是 **LJF-Fastest-Idle**（Longest Job First + 最快空闲 VM）
- 与经典 Max-Min 定义不符（同理 Min-Min）

**迁移方案：**
```java
// 旧代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.MAXMIN)

// 新代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN)
```

**静态 Max-Min 替代：**
```java
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MAXMIN)
```

---

### 1.3 `MCT` → `READY_BATCH_MCT`

**问题：**
- 原实现是**贪心最快 VM**（Greedy-Fastest-VM）
- 它不是维护未来 VM 可用时间的离线 MCT：历史实现偏向最快空闲 VM。
- `READY_BATCH_MCT` 按 ready 到达序，选空闲兼容 VM 中 `length/MIPS` 最小者；本轮选中后置忙，不建立未来排队时间。
- 对固定正任务长度、相同空闲兼容候选集合，最小 `length/MIPS` 与最大 MIPS 数学上等价，即使 MIPS 异构也如此。迁移的价值是明确决策层和维护契约，不是保证该输入上的映射改变。

**迁移方案：**
```java
// 旧代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.MCT)

// 新代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MCT)
```

**静态 MCT 替代：**
```java
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MCT)
```

---

### 1.4 `ROUNDROBIN` → `READY_BATCH_ROUNDROBIN`

**问题：**
- 原实现包含**死代码**（vmIndex 从未使用）
- 实际行为是 **First-Fit-Idle**（按 VM ID 顺序查找空闲）
- 不是真正的轮询（Round Robin）

**迁移方案：**
```java
// 旧代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.ROUNDROBIN)

// 新代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN)
```

---

## 2. 遗留离线规划器（Offline Planners）

> **R9 注记**：本节的 `HEFT` / `DHEFT` 枚举值已在 R9 移除，下列"旧代码"示例仅用于
> 识别历史实验脚本，在当前平台无法编译；迁移目标不变。

### 2.1 `HEFT` → `SHARED_STORAGE_HEFT`

**问题：**
- 原实现的**父子传输时间估计**与当前"共享存储 + stage-in Job"执行模型不对齐
- 规划侧假设点对点传输，执行侧使用共享存储，导致 makespan 预测与实际不符
- 未经受控回归测试验证

**迁移方案：**
```java
// 旧代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.planningAlgorithm(Parameters.PlanningAlgorithm.HEFT)

// 新代码
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
```

**差异说明：**
- `SHARED_STORAGE_HEFT` 刻意对齐当前 SimulationRunner 的执行模型：
  - 无聚类（ClusteringMethod.NONE）
  - 共享存储（不建模点对点传输）
  - SPACE_SHARED VM（无任务并发）
- 输出VM映射及每VM顺序，不重放绝对计划时间；事件间隔、量化和运行定序仍可能造成预测偏差，不应无条件声称makespan精确。

---

### 2.2 `DHEFT` → `SHARED_STORAGE_*` 系列

**问题：**
- 原分布式 HEFT 实现同样存在执行模型不对齐问题
- 未经受控回归验证
- SimulationRunner 拒绝该标签

**迁移方案：**
```java
// 旧代码
.planningAlgorithm(Parameters.PlanningAlgorithm.DHEFT)

// 新代码（根据需求选择）
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)   // HEFT
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)   // CPOP
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)    // DLS
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)    // ETF
.planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)   // PEFT
```

---

## 3. 受维护算法列表

### 3.1 在线调度器（Online Schedulers）

| 算法 | 说明 | 适用场景 |
|------|------|----------|
| `FCFS` | First-Come-First-Served | 基准对比 |
| `READY_BATCH_MINMIN` | 逐轮选择最小的Job最小ECT | ready批内重算空闲兼容候选 |
| `READY_BATCH_MAXMIN` | 逐轮选择最大的Job最小ECT | ready批内重算空闲兼容候选 |
| `READY_BATCH_MCT` | 按到达序最小化length/MIPS | 不维护未来availability |
| `READY_BATCH_ROUNDROBIN` | 跨批次持久VM游标 | 真正轮转，跳过忙/不兼容VM |
| `DATA` | 最少非本地真实输入字节 | 仅LOCAL；不是传输时间优化 |
| `RL_POLICY` | ready Job→VM列表下标动作 | RlEnvironment episode，无内建学习器 |

### 3.2 静态独立任务规划器（Static Independent-Task Planners）

| 算法 | 说明 | 适用场景 |
|------|------|----------|
| `STATIC_OLB` | Opportunistic Load Balancing | 负载均衡基准 |
| `STATIC_MET` | Minimum Execution Time | 有效计算时间最小，不一定是最高MIPS |
| `STATIC_MCT` | Minimum Completion Time | availability＋有效计算时间最小 |
| `STATIC_MINMIN` | Min-Min | 全局选择最小的任务最小完成估计 |
| `STATIC_MAXMIN` | Max-Min | 全局选择最大的任务最小完成估计 |
| `STATIC_SUFFERAGE` | Sufferage | 最大次优损失；仅一台兼容VM时损失为0 |
| `STATIC_ROUND_ROBIN` | Deterministic Round Robin | 按Task/VM ID轮转兼容映射，不看成本 |

**注意：** 这些算法**拒绝包含依赖边的 DAG 工作流**，仅适用于独立任务集合。

### 3.3 共享存储静态 DAG 规划器

| 算法 | 说明 | 适用场景 |
|------|------|----------|
| `SHARED_STORAGE_HEFT` | HEFT（共享存储模型） | 经典 DAG 规划基准 |
| `SHARED_STORAGE_CPOP` | CPOP（关键路径优化） | 关键路径优先 |
| `SHARED_STORAGE_DLS` | Dynamic Level Scheduling | 动态优先级 |
| `SHARED_STORAGE_ETF` | Earliest Time First | 最早开始时间 |
| `SHARED_STORAGE_PEFT` | PEFT（乐观成本表） | 后继路径优化 |

**注意：** 上表SHARED系列还要求SHARED文件系统、无开销/故障、legacy数据模型，
并拒绝任务成本矩阵；不是任意STATIC配置都受支持。配置片段：
```java
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.fileSystem(ReplicaCatalog.FileSystem.SHARED)
.clusteringParameters(new ClusteringParameters(0, 0, ClusteringMethod.NONE, null))
.dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
// 使用无开销/故障配置；平台VM必须为SPACE_SHARED
```

---

### 3.4 LOCAL静态DAG规划与仅映射基线

**LOCAL_PEFT公式修订**：一级来源已核定原算法为
`OCT(t,p)=max_child min_p'[OCT(child,p')+w(child,p')+c]`、`OCT(exit,p)=0`。
旧“自身w＋后继OCT、出口均值”不是原PEFT，新契约标识为
`PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`。历史R12/R13旧变体结果应明确标为非标准算法，
不能只用“一般模型版本差异”淡化或重新贴成原文复现；冻结数值不在此处覆写。
真正论文图1对应PEFT122/HEFT133，旧HEFT-origin的76/80并非该论文结果。
参见[一级来源与新夹具](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>)。

- `LOCAL_HEFT`、`LOCAL_CPOP`、`LOCAL_PEFT`：STATIC＋LOCAL＋NONE聚类＋无开销/故障＋
  SPACE_SHARED，数据模型选preExecution家族。规划仍是无争用估计；Fat-tree运行模型
  另须匹配拓扑声明。候选按PE兼容过滤，CPOP关键处理器要支持整条路径，PEFT使用
  dependency-ready列表处理父子rank反转。
- LOCAL副本带可用时刻：输入在完整传输hold结束可用，输出在计算完成可用。根与非根
  外部输入均从消费Job依赖就绪时开始，不能把统一stage-in当成目标VM已缓存。
  这不是完整事件重放，不能把固定论文夹具的时序推广到任意共享输入DAG。
- `RANDOM`、`PSO`：只产生Task→VM映射，不产生完整每VM顺序。RANDOM不优化计算成本；
  PSO消费有效成本但fitness忽略DAG边和网络，且粒子位置受PE兼容域约束。

独立任务的时间感知策略、PSO和LOCAL规划统一使用
`TaskExecutionModel`：有矩阵时为 `round(matrixSeconds*MIPS)/MIPS`，无矩阵时为
原始单PE长度/MIPS。缺矩阵坐标与不可表示的工作量显式失败。PSO的MIPS/1000单价使成本
**仅在无矩阵raw-MI模型下**与映射无关；矩阵下成本可随映射变化，但仍非真实云定价。
STATIC_ROUND_ROBIN和RANDOM保持成本无关的选择规则。

所有标准Runner运行统一NONE聚类、SPACE_SHARED；非空规划器只能配STATIC。
更多决策层和模型限制见[算法目录](<CATALOG.md>)与[契约](<CONTRACTS.md>)。

## 4. 如何检测遗留算法使用

### 4.1 编译时警告

使用 `@Deprecated` 标注的枚举值会触发编译器警告：
```
warning: [deprecation] MINMIN in SchedulingAlgorithm has been deprecated
```

### 4.2 运行时错误

`SimulationRunner.run()` 会在启动前验证算法，遗留标签会抛出 `SimulationConfigurationException`：
```
SimulationConfigurationException: Scheduling algorithm MINMIN is a legacy 
compatibility label and is not supported by SimulationRunner; use its 
READY_BATCH_* replacement
```

### 4.3 测试覆盖

`SimulationRunnerAlgorithmContractTest` 验证所有遗留标签都被拒绝：
```java
@Test
void configurationRejectsLegacyMinMinSchedulerInsteadOfMapping() {
    assertThrows(SimulationConfigurationException.class, () -> ...);
}
```

---

## 5. 常见问题

### Q1: 为什么不直接移除遗留枚举值？

**A:** 保留是为了：
1. **二进制兼容性** - 旧配置文件和序列化数据仍能加载
2. **清晰的错误提示** - 运行时抛出明确的迁移指引，而不是神秘的枚举解析失败
3. **渐进式迁移** - 给用户时间更新实验脚本

> **R9 注记**：以上理由仅适用于在线调度器标签。规划侧 `HEFT`/`DHEFT` 枚举已在 R9
> 移除——`SHARED_STORAGE_*` 完全取代后已无任何仓内引用，保留价值消失。

### Q2: 旧的静态 API 还能用吗？

**A:** 可以，但不推荐。旧静态API（如 `Parameters.init(...)`）仍可接入遗留标签，但：
- 无法生成标准 SimulationRunner 证据（manifest.json + metrics.json）
- 状态泄漏风险高（单 JVM 多次运行）
- 即使有兼容性单测，也不代表满足标准Runner的研究证据与模型组合契约

### Q3: 我的论文引用了 MINMIN，现在怎么办？

**A:** 根据复现需求选择：
1. **如需复现旧实验** - 使用旧版 WorkflowSim（< 1.0）或旧静态 API
2. **如需新实验** - 在论文中说明使用 `READY_BATCH_MINMIN`，并引用本迁移指南解释差异
3. **如需经典 Min-Min** - 使用 `STATIC_MINMIN`，并在论文中说明这是离线全局规划，而非在线调度

---

## 6. 参考文献

- **Braun et al. (2001)**: *A Comparison of Eleven Static Heuristics for Mapping a Class of Independent Tasks onto Heterogeneous Distributed Computing Systems*
- **Topcuoglu et al. (2002)**: *Performance-effective and low-complexity task scheduling for heterogeneous computing* (HEFT)
- **WorkflowSim 原论文**: Chen & Deelman (2012), *WorkflowSim: A toolkit for simulating scientific workflows in distributed environments*

---

## 7. 更新日志

- **2024-01**: 首次发布迁移指南
- **2024-01**: 为 `MINMIN`/`MAXMIN`/`MCT`/`ROUNDROBIN` 添加 `@Deprecated` 注解
- **2024-01**: 为 `HEFT`/`DHEFT` 添加 `@Deprecated` 注解
- **2024-01**: SimulationRunner 添加算法验证逻辑
- **2026-09（R9）**: 移除 `HEFT`/`DHEFT` 规划枚举；本文 §2 与 §5 Q1 加注记
- **正确性修复同步**: 更新ready-batch实际语义、矩阵成本、PE可行域、LOCAL时间戳副本与PEFT ready-list边界；历史标签与冻结证据不重写

---

**联系方式：** 如有疑问，请提交 GitHub Issue 或联系维护团队。
