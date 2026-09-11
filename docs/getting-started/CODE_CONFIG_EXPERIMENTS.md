# 代码配置式实验指南

**推荐方式**：所有参数在代码中配置，IDEA 中直接右键运行，无需命令行参数。

---

## 🚀 快速开始（3 步）

### 1. 复制模板

复制 `experiments/src/main/java/org/workflowsim/examples/MyConfigurableExperiment.java` 到你的包下，例如：

```
experiments/src/main/java/org/workflowsim/mystudy/Experiment01.java
```

### 2. 修改配置

打开文件，修改顶部的配置区：

```java
// ========================================================================
// 📝 实验配置区（修改这里的参数）
// ========================================================================

/** 实验名称（用于输出目录） */
private static final String EXPERIMENT_NAME = "experiment-01";

/** 工作流文件路径（相对于项目根目录） */
private static final String WORKFLOW_PATH = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";

/** 调度算法 */
private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.READY_BATCH_MINMIN;

/** VM 数量 */
private static final int VM_COUNT = 4;

/** 随机种子（用于可复现） */
private static final long RANDOM_SEED = 42L;

/** deadline（秒，设为 0 表示无 deadline） */
private static final long DEADLINE = 0L;

/** 是否保存实验证据（manifest/metrics/events JSON） */
private static final boolean SAVE_ARTIFACTS = true;

/** 是否生成 CSV 表格（jobs/tasks/vms） */
private static final boolean SAVE_CSV = true;

/** 是否生成 HTML 可视化报告 */
private static final boolean SAVE_HTML = true;

/** 是否显示详细控制台输出（分组指标 + VM 明细表） */
private static final boolean VERBOSE_CONSOLE = true;

/** 输出目录（相对于项目根目录） */
private static final String OUTPUT_DIR = "output/my-experiments";

/** 是否使用时间戳子目录（避免覆盖，推荐 true） */
private static final boolean USE_TIMESTAMP_DIR = true;
```

**输出目录说明**：
- `USE_TIMESTAMP_DIR = false`：输出到 `output/my-experiments/my-experiment.*`（每次覆盖）
- `USE_TIMESTAMP_DIR = true`：输出到 `output/my-experiments/run-<timestamp>/result.*`（独立目录，推荐）

时间戳格式：`yyyyMMdd-HHmmss`，例如 `run-20260904-150830/`

### 3. 右键运行

在 IDEA 中：
1. 右键 `main` 方法
2. 点击 **Run 'Experiment01.main()'**
3. 查看控制台输出

完成！不需要任何命令行参数配置。

---

## 📖 配置参数详解

### 调度算法选择

```java
// === 在线调度器（运行时决策）===
SchedulingAlgorithm.FCFS                        // 先来先服务
SchedulingAlgorithm.READY_BATCH_MINMIN          // 小任务优先
SchedulingAlgorithm.READY_BATCH_MAXMIN          // 大任务优先
SchedulingAlgorithm.READY_BATCH_MCT             // 最小完成时间
SchedulingAlgorithm.READY_BATCH_ROUNDROBIN      // 轮转
SchedulingAlgorithm.DATA                        // 数据就近
```

对于静态规划算法，需要额外配置：

```java
// 在配置区添加静态规划算法（例如 HEFT）
private static final PlanningAlgorithm PLANNING_ALGO = PlanningAlgorithm.SHARED_STORAGE_HEFT;
private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.STATIC;
private static final ReplicaCatalog.FileSystem FILE_SYSTEM = ReplicaCatalog.FileSystem.SHARED;

// 在 main 方法中配置（找到 SimulationConfig.builder 部分）
SimulationConfig.Builder configBuilder = SimulationConfig.builder(workflowPath.toString(), VM_COUNT)
    .planningAlgorithm(PLANNING_ALGO)        // 添加这行
    .schedulingAlgorithm(ALGORITHM)
    .fileSystem(FILE_SYSTEM)
    .randomSeed(RANDOM_SEED);
```

### 工作流路径

推荐使用的工作流（已验证，20/20 全部可用）：

```java
// === Epigenomics（生物信息学）===
"datasets/dax/epigenomics/n24/Epigenomics_24.dax"
"datasets/dax/epigenomics/n46/Epigenomics_46.dax"
"datasets/dax/epigenomics/n100/Epigenomics_100.dax"
"datasets/dax/epigenomics/n997/Epigenomics_997.dax"

// === Montage（天文学图像拼接）===
"datasets/dax/montage/n25/Montage_25.dax"
"datasets/dax/montage/n50/Montage_50.dax"
"datasets/dax/montage/n100/Montage_100.dax"
"datasets/dax/montage/n1000/Montage_1000.dax"

// === CyberShake（地震危险性分析）===
"datasets/dax/cybershake/n30/CyberShake_30.dax"
"datasets/dax/cybershake/n50/CyberShake_50.dax"
"datasets/dax/cybershake/n100/CyberShake_100.dax"
"datasets/dax/cybershake/n1000/CyberShake_1000.dax"

// === Sipht（RNA 序列分析）===
"datasets/dax/sipht/n30/Sipht_30.dax"
"datasets/dax/sipht/n60/Sipht_60.dax"
"datasets/dax/sipht/n100/Sipht_100.dax"
"datasets/dax/sipht/n1000/Sipht_1000.dax"

// === Inspiral（引力波数据分析）===
"datasets/dax/inspiral/n30/Inspiral_30.dax"
"datasets/dax/inspiral/n50/Inspiral_50.dax"
"datasets/dax/inspiral/n100/Inspiral_100.dax"
"datasets/dax/inspiral/n1000/Inspiral_1000.dax"
```

### deadline 设置

```java
// 无 deadline
private static final long DEADLINE = 0L;

// 有 deadline（300 秒）
private static final long DEADLINE = 300L;
```

如果设置了 deadline，运行结果会显示是否满足：

```
Deadline 满足:  是
Deadline Slack: 15.23 秒
```

### 输出配置

```java
// 保存实验证据到 output/my-experiments/
private static final boolean SAVE_ARTIFACTS = true;
private static final String OUTPUT_DIR = "output/my-experiments";

// 不保存证据
private static final boolean SAVE_ARTIFACTS = false;
```

保存的文件：
- `<experiment-name>.manifest.json` - 实验元数据和配置
- `<experiment-name>.metrics.json` - 性能指标（makespan、利用率等）
- `<experiment-name>.events.jsonl` - 详细事件日志

---

## 💡 完整示例

### 示例 1：对比不同算法

创建 3 个文件，每个文件只改算法：

```java
// Experiment01_FCFS.java
private static final String EXPERIMENT_NAME = "exp01-fcfs";
private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.FCFS;

// Experiment02_MINMIN.java
private static final String EXPERIMENT_NAME = "exp01-minmin";
private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.READY_BATCH_MINMIN;

// Experiment03_MAXMIN.java
private static final String EXPERIMENT_NAME = "exp01-maxmin";
private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.READY_BATCH_MAXMIN;
```

依次右键运行，对比 makespan 和 VM 利用率。

### 示例 2：不同 VM 数量对比

```java
// Experiment04_VM2.java
private static final String EXPERIMENT_NAME = "exp02-vm2";
private static final int VM_COUNT = 2;

// Experiment05_VM4.java
private static final String EXPERIMENT_NAME = "exp02-vm4";
private static final int VM_COUNT = 4;

// Experiment06_VM8.java
private static final String EXPERIMENT_NAME = "exp02-vm8";
private static final int VM_COUNT = 8;
```

### 示例 3：deadline 压力测试

```java
// Experiment07_NoDeadline.java
private static final String EXPERIMENT_NAME = "exp03-no-deadline";
private static final long DEADLINE = 0L;

// Experiment08_Tight.java
private static final String EXPERIMENT_NAME = "exp03-tight-deadline";
private static final long DEADLINE = 100000L;  // 紧张的 deadline

// Experiment09_Loose.java
private static final String EXPERIMENT_NAME = "exp03-loose-deadline";
private static final long DEADLINE = 200000L;  // 宽松的 deadline
```

---

## 🎯 最佳实践

### 1. 实验命名规范

```java
// 推荐：描述性名称
private static final String EXPERIMENT_NAME = "heft-vs-minmin-epi100-vm4";

// 避免：无意义名称
private static final String EXPERIMENT_NAME = "test1";
```

### 2. 随机种子管理

```java
// 可复现实验：使用固定种子
private static final long RANDOM_SEED = 42L;

// 多次独立运行：改变种子
private static final long RANDOM_SEED = 43L;  // Run 2
private static final long RANDOM_SEED = 44L;  // Run 3
```

### 3. 输出目录组织

```java
// 按研究课题组织
private static final String OUTPUT_DIR = "output/scheduling-comparison";
private static final String OUTPUT_DIR = "output/deadline-study";
private static final String OUTPUT_DIR = "output/scalability-test";
```

### 4. 代码注释

```java
/** 
 * 实验目标：对比 HEFT 和 MINMIN 在 Epigenomics 100 工作流上的性能
 * 
 * 预期结果：HEFT 应该有更短的 makespan
 * 
 * 运行时间：约 1-2 秒
 */
public class Experiment_HEFT_vs_MINMIN {
    // ...
}
```

---

## ⚠️ 平台建模限制与指标口径（必读）

理解以下建模限制，才能正确解读实验指标、避免误读结论。

### 1. "1 VM = 1 并发槽位"建模限制

WorkflowSim 的所有内置调度算法**只向空闲 VM 派发作业**（派发后立即将 VM 标记为 BUSY，
作业完成后才恢复 IDLE），不存在 per-VM 作业队列。这是刻意的"就绪批次在线派发"设计，
带来两个直接后果：

| 现象 | 含义 |
|------|------|
| `meanJobVmQueueWaitingTimeSeconds` 恒为 0 | 作业从不进入 VM 队列，等待全部发生在 broker 级就绪列表 |
| 多 PE VM 只使用 1 个 PE | 作业固定 `pesNumber=1`，4PE VM 实际只用 1 PE 执行，但成本模型按全部 PE 计费 |

**推论**：`meanJobVmLevelSlowdown` 恒 ≈ 1.0，**不能**用于调度算法比较；
请改用 `meanComputeTrueSlowdown`（真实减速比，基于 JOB_READY 就绪时刻）。

### 2. 等待时间/减速比指标的统计口径

- `meanComputeTotalWaitingTimeSeconds` 与 `meanComputeTrueSlowdown` 的样本
  **包含失败与 retry 尝试**（每个 JobOutcome 一行）。跨不同失败率方案比较均值时注意此偏差；
  如需无偏口径，请改用 `successOnlyMeanComputeTotalWaitingTimeSeconds` /
  `successOnlyMeanComputeTrueSlowdown`（仅成功作业样本，观测数见
  `successOnlyWaitingObservationCount`）。
- 真实减速比采用 Feitelson 有界减速比：`max((等待+执行) / max(执行, 10s), 1.0)`，
  短作业的分母被钳制到 10 秒，因此短作业信号被压低——这是抑制噪声的既定设计。
- `meanJobResponseTimeSeconds` 的起点是 **VM 到达时刻**（CloudSim `submissionTime`），
  **不含**调度器排队等待；"就绪→完成"全程口径请用 `总等待 + 执行` 组合。
- 观测数：`totalWaitingTimeObservationCount` 与 `trueSlowdownObservationCount` 同集合
  （就绪事件齐备且时间戳有效的计算作业）；jobs.csv 中无观测的行 readyTime/
  totalWaitingTime 列留空（不以 0 冒充测量值）。
- **分解指标可加性前提**：`meanComputeTotalWaitingTimeSeconds` ≈
  `meanComputeReadyToDecisionDelaySeconds` + `meanComputeDecisionToStartDelaySeconds`
  仅在三个指标的观察数（`totalWaitingTimeObservationCount` /
  `readyToDecisionDelayObservationCount` / `decisionToStartDelayObservationCount`）
  相等、样本集合重合时严格成立；观察数不等时不可加。正常运行下每作业两事件
  各恰一次（fail-fast 保护），三者恒相等。
- **从 jobs.csv 重算分布统计须过滤**：CSV 的 readyTime/totalWaitingTime 覆盖
  **全部**有 JOB_READY 观测的作业（stage-in 作业通常也有 JOB_READY，会被填充），
  而 metrics.json 的等待/减速比分布只统计**计算作业**；重算前必须先按
  `classType == 2`（COMPUTE）过滤，否则 n 与均值都和 metrics.json 不一致。

### 3. metrics.json schema 版本

证据包 metrics.json 当前为 `workflowsim-simulation-metrics-v2`。
v1 → v2 为破坏性变更（字段重命名 + 新增），解析旧字段名的下游代码需同步更新；
validator 会拒绝 v1 schema 与缺失关键字段的文件。

### 4. 分布统计与公平性指标（新增）

均值会掩盖长尾，比较调度算法时建议同时看分布：

- **分布统计**（最近秩法）：`medianComputeTotalWaitingTimeSeconds` /
  `p95ComputeTotalWaitingTimeSeconds` / `maxComputeTotalWaitingTimeSeconds`，
  减速比对应 `medianComputeTrueSlowdown` / `p95ComputeTrueSlowdown` /
  `maxComputeTrueSlowdown`。无观测时全部为 0.0（HTML 报告显示 "—"）。
  **小样本提示**：最近秩法在 n≤20 时 P95 基本退化为 max；n=2 的中位数取两中值
  的较小者（不做平均）。小规模样本的 P95 请勿过度解读。
- **VM 利用率 Jain 公平性指数** `vmUtilizationJainFairnessIndex` ∈ (0,1]：
  1.0 = 所有 VM 利用率相同。与变异系数（CV）互补——CV 无法区分"两台各 50%"
  与"一台 100% 一台 0%"这类形态差异。
- **重试放大率** `retryAmplificationRatio` = 逻辑任务尝试总数 / 逻辑任务数：
  无失败时恒为 1.0；无逻辑任务时为 0.0（该分支无实际含义，结合
  `logicalTaskCount` 判断）；衡量失败恢复带来的总执行开销放大。
- campaign 层（多 seed 汇总）已聚合等待/减速比/利用率/Jain 四个维度
  （`summary.cells[].meanCompute*`，含均值与 95% 置信区间）。

### 5. 证据事件词表要点

- `SCHEDULING_DECISION`（算法决策点，指标消费，每作业恰一次——重复即事件流破坏）
  与 `JOB_DISPATCHED`（派发审计点，证据链用途）在同一时刻成对出现，
  职责不同，有意保留两个事件类型。
- retry 作业的 `JOB_READY` 事件携带 `retryOfFailedJobId` 属性，可追溯重试谱系。

---

## 🔧 故障排查

### Q1: 不同数据集的特点？

**已全部验证可用（20/20）**，按工作流类型：

- **Epigenomics**（生物信息学）：流水线型，数据依赖简单
- **Montage**（天文图像拼接）：扇入扇出结构，数据密集
- **CyberShake**（地震分析）：参数扫描型，计算密集
- **Sipht**（RNA 序列）：多分支流水线，混合型
- **Inspiral**（引力波）：分支合并结构，计算密集

选择不同的工作流可以测试算法在不同 DAG 结构下的表现。

### Q2: 运行时看到 WARNING 日志？

**正常现象**。部分数据集（如 Montage、CyberShake）的 DAX 文件中，同一文件在不同任务中声明的 size 可能略有不同（这是真实科学工作流的特点）。

WorkflowSim 会记录警告并使用第一次声明的 size，不影响仿真执行。

示例警告：
```
WARNING: DAX file 'p2mass-atlas.fits' has inconsistent input size declarations: 
first=4171851, current=4185623 (using first declaration)
```

如果不想看到这些警告，可以在 main 方法开头添加：
```java
Log.disable();
```

### Q3: 找不到工作流文件

**原因**：路径相对于项目根目录。

**解决**：确保路径正确：
```bash
# 在项目根目录运行
ls datasets/dax/epigenomics/n100/Epigenomics_100.dax
```

### Q4: IDEA 无法运行（橙色咖啡杯图标）

**原因**：experiments 模块未加载。

**解决**：参考 [`IDEA_SETUP.md`](IDEA_SETUP.md)，重新加载 Maven 项目。

### Q5: 输出目录权限错误

**原因**：输出目录不可写。

**解决**：使用项目内的目录：
```java
private static final String OUTPUT_DIR = "output/my-experiments";
```

---

## 📚 下一步

- **理解算法原理**：[`ALGORITHMS.md`](ALGORITHMS.md)
- **选择合适数据集**：[`DATASETS.md`](DATASETS.md)
- **高级配置选项**：[`RUN_EXPERIMENTS.md`](RUN_EXPERIMENTS.md)
- **实验可复现性**：[`../experiments/REPRODUCIBILITY.md`](../experiments/REPRODUCIBILITY.md)

---

## 🆚 对比：代码配置 vs 命令行参数

| 特性 | 代码配置（推荐） | 命令行参数 |
| --- | --- | --- |
| IDEA 运行 | ✅ 直接右键 | ❌ 需要配置 Run Configuration |
| 参数可见性 | ✅ 一目了然 | ❌ 需要查看启动命令 |
| 版本控制 | ✅ Git 跟踪配置变化 | ❌ 配置在 IDE 中，不入库 |
| 批量实验 | ✅ 复制文件即可 | ❌ 需要写脚本 |
| 适合场景 | ✅ 研究、教学、快速迭代 | ✅ 生产环境、批处理 |

**结论**：对于研究和教学场景，代码配置式更直观、更容易管理。

---

## 📊 输出结果说明

### 控制台输出

#### 简洁模式（`VERBOSE_CONSOLE = false`）
```
═══════════════════════════════════════════════════════
  仿真完成
═══════════════════════════════════════════════════════
Makespan:       94.09 秒
成功 Job 数:    26
失败 Job 数:    0
平均 VM 利用率: 66.64%
总 CPU 成本:    806.76
═══════════════════════════════════════════════════════
```

#### 详细模式（`VERBOSE_CONSOLE = true`，推荐）
```
═══════════════════════════════════════════════════════
  仿真结果摘要
═══════════════════════════════════════════════════════
【总体】
  Makespan:          94.09 秒
  工作流完成:             ✅ 成功
  逻辑任务:              25/25 (100.0%)
  作业 (成功/失败):        26/0

【性能】
  吞吐量:               0.2657 作业/秒
  平均作业运行时间:          10.03 秒
  平均总等待时间:           1.93 秒
    └ 调度器等待:         1.93 秒
    └ 派发延迟:          0.00 秒
    └ VM 队列等待:       0.00 秒 (通常 ≈0)
  总等待时间 中位数/P95/最大:  0.00 秒 / 11.70 秒 / 13.64 秒 (n=25)
  平均响应时间:            10.03 秒 (VM到达→完成，不含调度排队)
  平均真实减速比:           1.1562 (n=25)
  真实减速比 中位数/P95/最大:  1.0000 / 1.9757 / 1.9889
  调度周期数:             21
  关键路径下界:            57.80 秒
  调度长度比 (SLR):       1.6278

【资源利用】
  平均 VM 利用率:         66.64%
  利用率变异系数:           0.2935
  利用率 Jain 公平性指数:    0.9207
  VM 总繁忙时间:          250.81 秒

【数据传输】
  建模传输文件数:           89
  总建模传输时间:           22.76 秒
  总所需输入:             325.58 MB

【成本（模型抽象值）】
  总处理成本:             806.76
  CPU 包络成本:          752.42
  文件带宽成本:            54.34

【VM 明细】
  VM     作业数      CPU时间(秒)       繁忙时间(秒)        利用率
  0      13       93.62          93.62          99.51%
  1      5        59.70          59.70          63.46%
  2      4        48.81          48.81          51.88%
  3      4        48.66          48.66          51.72%
```
（示例为 Montage_25、4 VM、FCFS 的真实输出；无失败/重试时【容错】组不显示，
未配置 deadline 时【Deadline】组不显示；有失败时还会显示 success-only 行。）

### 文件输出

当启用相应开关时，自动生成以下文件：

#### 1. JSON 证据包（`SAVE_ARTIFACTS = true`）

- **`<experiment-name>.manifest.json`** - 主文件
  - 实验配置（算法、平台、参数）
  - 输入工作流元数据
  - 基本结果（makespan、jobs、tasks）
  - Provenance（研究身份、协议、数据集）
  - 关联其他工件的引用

- **`<experiment-name>.metrics.json`** - 性能指标
  - 88 个完整指标（含真实等待时间/响应时间/真实减速比/分布统计/Jain 公平性指数）
  - VM 统计
  - 工作流特征

- **`<experiment-name>.events.jsonl`** - 执行轨迹
  - JSON Lines 格式（每行一个事件）
  - 时间戳、类型、实体ID、详细信息
  - 完整的仿真事件序列

#### 2. CSV 详细表格（`SAVE_CSV = true`）⭐ **新增**

- **`<experiment-name>.jobs.csv`** - 作业执行明细
  - 每行一个作业
  - 字段：jobId, vmId, status, statusName, classType, submissionTime, readyTime, startTime, finishTime, **totalWaitingTime, vmQueueWaitingTime, executionTime, responseTime**, cpuTime, taskCount, costs...
  （readyTime/totalWaitingTime 覆盖全部有 JOB_READY 观测的作业——重算 compute 口径
  的分布统计前需先按 classType=2 过滤）
  - 可直接导入 **Excel / Python pandas / R**

- **`<experiment-name>.tasks.csv`** - 任务执行明细
  - 每行一个任务
  - 字段：taskId, jobId, vmId, depth, startTime, finishTime, duration, status...
  - 用于细粒度性能分析

- **`<experiment-name>.vms.csv`** - VM 统计摘要
  - 每行一个 VM
  - 字段：vmId, jobCount, cpuTime, busyTime, utilization...
  - 用于资源利用率分析

#### 3. HTML 可视化报告（`SAVE_HTML = true`）⭐ **新增**

- **`<experiment-name>.html`** - 自包含交互式报告
  - ✅ 无外部依赖，可离线在浏览器中打开
  - ✅ 关键指标卡片
  - ✅ 作业执行甘特图（按 VM 分组，可悬停查看详情）
  - ✅ VM 利用率统计表
  - ✅ 作业/任务详细数据表（可点击列头排序）
  - ✅ 任务表默认折叠（点击展开）

**示例**（`USE_TIMESTAMP_DIR = true`，推荐）：
```
output/my-experiments/
├── run-20260904-150830/            (第一次运行)
│   ├── result.manifest.json        (实验配置和基本结果)
│   ├── result.metrics.json         (88 个性能指标)
│   ├── result.events.jsonl         (完整事件序列)
│   ├── result.jobs.csv             (作业明细表 - Excel/Python 分析)
│   ├── result.tasks.csv            (任务明细表)
│   ├── result.vms.csv              (VM 统计表)
│   └── result.html                 (交互式可视化报告 - 浏览器打开)
└── run-20260904-151045/            (第二次运行，独立目录不覆盖)
    └── ...
```

**作业时间指标说明**（jobs.csv 中的核心列）：

```
submissionTime          startTime                  finishTime
     |                      |                          |
     |<- vmQueueWaiting -->|<--- executionTime ------>|
     |<-------------- responseTime ------------------->|
```

| 列名 | 含义 | 公式 |
|------|------|------|
| `submissionTime` | 作业到达 VM 的时刻 | CloudSim 原生记录 |
| `readyTime` | 计算作业就绪时刻（JOB_READY 事件） | 无观测时留空 |
| `totalWaitingTime` | **总等待时间**（就绪 → 开始执行） | startTime - readyTime |
| `vmQueueWaitingTime` | 作业在 VM 队列中的等待时长 | startTime - submissionTime |
| `executionTime` | 作业实际执行时长 | finishTime - startTime |
| `responseTime` | 用户感知的完成时长 | finishTime - submissionTime |

⚠️ **重要说明**：WorkflowSim 的所有调度算法都只向**空闲 VM** 派发作业，因此 `vmQueueWaitingTime` **恒为 0**。真实的调度等待时间（作业就绪到开始执行）在 **metrics 层面**测量：

对应的统计指标（metrics.json / 控制台【性能】分组）：
- `meanComputeTotalWaitingTimeSeconds` - **平均总等待时间**（作业就绪 → 开始执行，从 JOB_READY 事件计算）
  - 分解项：`meanComputeReadyToDecisionDelaySeconds`（调度器等待）+ `meanComputeDecisionToStartDelaySeconds`（派发延迟）
- `meanJobVmQueueWaitingTimeSeconds` - 平均 VM 队列等待（通常为 0）
- `meanJobResponseTimeSeconds` - 平均响应时间
- `meanComputeTrueSlowdown` - **平均真实减速比**（基于 bounded slowdown 定义，分母下界 10s，结果下界 1.0）
- `meanJobVmLevelSlowdown` - 平均 VM 层面减速比（responseTime / executionTime，通常恒为 1.0，仅供内部参考）

**减速比（Slowdown）说明**：

真实减速比采用调度研究的标准 bounded slowdown 定义（Feitelson et al.）：

```
真实减速比 = max( (真实等待 + 执行时间) / max(执行时间, 10s), 1.0 )
其中：真实等待 = startTime - readyTime（从 JOB_READY 事件提取）
```

- **1.0** = 理想状态（作业就绪后立即执行，无等待）
- **>1.0** = 存在调度等待，数值越大调度效率越低
- 分母下界 10 秒：防止极短作业导致数值爆炸
- 结果下界 1.0：保证减速比语义（不存在"加速"）

⚠️ 旧指标 `meanJobVmLevelSlowdown`（原名 `meanJobSlowdown`）基于 VM 到达时间计算，
由于 WorkflowSim 调度器只向空闲 VM 派发作业，该值恒为 1.0，**不适合用于算法比较**。

### 使用建议

1. **日常研究**：开启所有输出（`VERBOSE_CONSOLE + CSV + HTML`）
   - 控制台查看关键指标
   - CSV 用于深度分析（Python/R/Excel）
   - HTML 用于直观可视化

2. **批量实验**：只保存 JSON + CSV
   - 关闭 `VERBOSE_CONSOLE`（减少日志）
   - 保持 `SAVE_CSV = true`（便于后续分析）
   - 可选择性生成 HTML（仅对重点实验）

3. **快速验证**：只开启 `VERBOSE_CONSOLE`
   - 关闭所有文件输出
   - 快速迭代调试

---

## 📈 CSV 数据分析示例

### Excel 分析

1. 打开 `result.jobs.csv`（或旧版 `experiment-01.jobs.csv`）
2. 选中数据 → 插入 → 图表
3. 制作甘特图、柱状图等

### Python pandas 分析

```python
import pandas as pd

# 读取 CSV（时间戳目录）
jobs = pd.read_csv('output/my-experiments/run-20260904-150830/result.jobs.csv')
tasks = pd.read_csv('output/my-experiments/run-20260904-150830/result.tasks.csv')
vms = pd.read_csv('output/my-experiments/run-20260904-150830/result.vms.csv')

# 分析 VM 队列等待时间（通常为 0）
print("VM 队列等待时间统计：")
print(jobs['vmQueueWaitingTime'].describe())

# 分析响应时间分布
print("\n响应时间统计：")
print(jobs['responseTime'].describe())

# 计算 VM 层面减速比（通常 ≈1.0，不推荐用于算法评估）
jobs['vmLevelSlowdown'] = jobs['responseTime'] / jobs['executionTime']
print("\nVM 层面减速比统计（参考）：")
print(jobs['vmLevelSlowdown'].describe())

# 计算真实减速比（推荐用于调度算法比较）
# 需要从事件日志提取 readyTime
import json
ready_times = {}
with open('output/my-experiments/run-20260904-150830/result.events.jsonl') as f:
    for line in f:
        e = json.loads(line)
        if e.get('type') == 'JOB_READY' and e.get('jobId') is not None:
            ready_times[e['jobId']] = e['simulationTime']

jobs['readyTime'] = jobs['jobId'].map(ready_times)
jobs['totalWaitingTime'] = jobs['startTime'] - jobs['readyTime']
jobs['trueSlowdown'] = ((jobs['totalWaitingTime'] + jobs['executionTime']) / 
                        jobs['executionTime'].clip(lower=10.0)).clip(lower=1.0)
print("\n真实等待时间（就绪 → 开始）统计：")
print(jobs['totalWaitingTime'].describe())
print("\n真实减速比统计（调度效率指标）：")
print(jobs['trueSlowdown'].describe())

# 对比不同 VM 的响应时间
print("\n各 VM 平均响应时间：")
print(jobs.groupby('vmId')['responseTime'].mean())

# VM 负载均衡分析
print("\nVM 利用率：")
print(vms[['vmId', 'jobCount', 'modeledUtilization']])

# 绘制真实减速比分布
import matplotlib.pyplot as plt
jobs['trueSlowdown'].hist(bins=20)
plt.xlabel('真实减速比')
plt.ylabel('作业数量')
plt.title('作业真实减速比分布')
plt.axvline(x=1.0, color='red', linestyle='--', label='理想值 (无等待)')
plt.legend()
plt.show()
```

### R 分析

```r
# 读取 CSV（时间戳目录）
jobs <- read.csv('output/my-experiments/run-20260904-150830/result.jobs.csv')
tasks <- read.csv('output/my-experiments/run-20260904-150830/result.tasks.csv')

# 统计摘要
summary(jobs$vmQueueWaitingTime)  # 通常为 0
summary(jobs$responseTime)

# 计算真实减速比（推荐用于算法比较）
# 需要先从事件日志提取 readyTime
library(jsonlite)
events <- stream_in(file('output/my-experiments/run-20260904-150830/result.events.jsonl'))
ready_events <- events[events$type == 'JOB_READY' & !is.na(events$jobId), ]
ready_times <- setNames(ready_events$simulationTime, ready_events$jobId)

jobs$readyTime <- ready_times[as.character(jobs$jobId)]
jobs$totalWaitingTime <- jobs$startTime - jobs$readyTime
jobs$trueSlowdown <- pmax((jobs$totalWaitingTime + jobs$executionTime) / 
                          pmax(jobs$executionTime, 10), 1.0)

summary(jobs$totalWaitingTime)
summary(jobs$trueSlowdown)

# 可视化真实减速比
library(ggplot2)
ggplot(jobs, aes(x=trueSlowdown)) +
  geom_histogram(bins=20, fill="steelblue", alpha=0.7) +
  geom_vline(xintercept=1.0, color="red", linetype="dashed", size=1) +
  labs(title="作业真实减速比分布", 
       x="真实减速比", y="作业数量",
       caption="红线：理想值 (无等待)")

# 真实等待时间 vs 执行时间散点图
ggplot(jobs, aes(x=executionTime, y=totalWaitingTime)) +
  geom_point(alpha=0.5) +
  labs(title="真实等待时间 vs 执行时间", 
       x="执行时间 (秒)", y="真实等待时间 (秒)")
```

---

