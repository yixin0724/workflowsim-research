# 遗留算法迁移指南

## 概述

WorkflowSim 1.0 包含一些历史遗留算法实现，它们的行为与学术文献中的经典定义不符，或与当前执行模型不对齐。为了保持科研模拟器的准确性和可追溯性，**SimulationRunner 标准入口拒绝这些算法标签**。

本文档说明哪些算法已被弃用、为什么被弃用，以及如何迁移到受维护的替代方案。

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
SimulationConfig config = SimulationConfig.builder()
    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
    // ...
    .build();

// 新代码
SimulationConfig config = SimulationConfig.builder()
    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
    // ...
    .build();
```

**语义说明：**
- `READY_BATCH_MINMIN` 明确说明是"就绪批次内的 SPT + 最快空闲"变体
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
- 与经典 MCT（Minimum Completion Time）定义不符：
  - 经典 MCT：为每个任务选择使其完成时间最小的机器
  - 本实现：总是选最快 MIPS 的 VM，不考虑负载均衡

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
- 规划侧与执行侧语义一致，makespan 预测准确

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
| `READY_BATCH_MINMIN` | SPT + 最快空闲 VM | 就绪批次短任务优先 |
| `READY_BATCH_MAXMIN` | LJF + 最快空闲 VM | 就绪批次长任务优先 |
| `READY_BATCH_MCT` | 贪心最快 VM | 简单负载分配 |
| `READY_BATCH_ROUNDROBIN` | First-Fit-Idle | 轮流分配 VM |
| `DATA` | 数据感知调度 | 输入文件亲和性优化 |

### 3.2 静态独立任务规划器（Static Independent-Task Planners）

| 算法 | 说明 | 适用场景 |
|------|------|----------|
| `STATIC_OLB` | Opportunistic Load Balancing | 负载均衡基准 |
| `STATIC_MET` | Minimum Execution Time | 最快机器优先 |
| `STATIC_MCT` | Minimum Completion Time | 最早完成时间 |
| `STATIC_MINMIN` | 经典 Min-Min | 短任务优先全局优化 |
| `STATIC_MAXMIN` | 经典 Max-Min | 长任务优先全局优化 |
| `STATIC_SUFFERAGE` | Sufferage | 考虑备选机器代价 |

**注意：** 这些算法**拒绝包含依赖边的 DAG 工作流**，仅适用于独立任务集合。

### 3.3 静态 DAG 规划器（Static DAG Planners）

| 算法 | 说明 | 适用场景 |
|------|------|----------|
| `SHARED_STORAGE_HEFT` | HEFT（共享存储模型） | 经典 DAG 规划基准 |
| `SHARED_STORAGE_CPOP` | CPOP（关键路径优化） | 关键路径优先 |
| `SHARED_STORAGE_DLS` | Dynamic Level Scheduling | 动态优先级 |
| `SHARED_STORAGE_ETF` | Earliest Time First | 最早开始时间 |
| `SHARED_STORAGE_PEFT` | PEFT（乐观成本表） | 后继路径优化 |

**注意：** 这些算法要求配合以下约束：
```java
.schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
.clusteringMethod(ClusteringMethod.NONE)  // 禁用任务聚类
// 平台 VM 必须配置为 SPACE_SHARED 模式
```

---

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

### Q2: 旧的静态 API 还能用吗？

**A:** 可以，但不推荐。旧 API（如 `Parameters.setSchedulingAlgorithm()`）仍接受遗留标签，但：
- 无法生成标准 SimulationRunner 证据（manifest.json + metrics.json）
- 状态泄漏风险高（单 JVM 多次运行）
- 不受回归测试保护

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

---

**联系方式：** 如有疑问，请提交 GitHub Issue 或联系维护团队。
