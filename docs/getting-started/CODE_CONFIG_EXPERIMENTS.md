# 代码配置式实验指南

**推荐方式**：所有参数在代码中配置，IDEA 中直接右键运行，无需命令行参数。

> **R9 变更注记（2026-09-16）**：旧教程模板 `MyConfigurableExperiment` 及其
> CSV/HTML/分组控制台报告写器（`ExperimentCsvWriter` / `ExperimentHtmlReportWriter` /
> `ExperimentConsoleSummary`）已在 R9 清理中删除——它们无主流程引用、不在示例目录，
> 唯一引用测试为从不执行的手动门禁，属于死代码。本教程已改写为基于平台维护中的
> `SimulationConfig` / `SimulationRunner` / `SimulationReport` API；实验证据统一由
> `ExperimentArtifactWriter` 输出 **manifest / metrics / events 三件套**（JSON/JSONL），
> 不再有 CSV 与 HTML 报告。旧文档中 `SAVE_CSV` / `SAVE_HTML` / `VERBOSE_CONSOLE` /
> `jobs.csv` / `result.html` 等表述全部失效。

---

## 🚀 快速开始（3 步）

### 1. 新建实验类

在你自己的包下新建一个自包含的实验类，例如：

```
experiments/src/main/java/org/workflowsim/mystudy/Experiment01.java
```

内容如下（可直接复制，全部 API 与当前源码一致）：

```java
package org.workflowsim.mystudy;

import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

public final class Experiment01 {

    // ====================================================================
    // 📝 实验配置区（修改这里的参数）
    // ====================================================================

    /** 实验名称（同时作为证据工件的 runId 与输出子目录名） */
    private static final String EXPERIMENT_NAME = "experiment-01";

    /** 工作流文件路径（相对于项目根目录；dax 或 WfFormat JSON 均可） */
    private static final String WORKFLOW_PATH = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";

    /** 调度算法（取值与前置约束见下文"配置参数详解"） */
    private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.READY_BATCH_MINMIN;

    /** VM 数量 */
    private static final int VM_COUNT = 4;

    /** 随机种子（可复现性来源，务必显式固定） */
    private static final long RANDOM_SEED = 42L;

    /** deadline（秒；0 表示无 deadline。仅作为观测口径，不是仿真终止条件） */
    private static final long DEADLINE = 0L;

    /** 是否保存证据工件（manifest/metrics/events 三件套） */
    private static final boolean SAVE_ARTIFACTS = true;

    /** 输出根目录（相对于项目根目录） */
    private static final String OUTPUT_DIR = "output/my-experiments";

    private Experiment01() {
    }

    public static void main(String[] args) throws Exception {
        // 1) 组装不可变配置（所有校验在 build() 时 fail-fast）。
        SimulationConfig.Builder builder = SimulationConfig.builder(WORKFLOW_PATH, VM_COUNT)
                .schedulingAlgorithm(ALGORITHM)
                .randomSeed(RANDOM_SEED);
        if (DEADLINE > 0L) {
            builder.deadline(DEADLINE);
        }
        SimulationConfig config = builder.build();

        // 2) 运行仿真（同质本地平台；run 抛出检查型 SimulationExecutionException）。
        Log.disable(); // 屏蔽 CloudSim 逐事件日志，需要时删除本行
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal(EXPERIMENT_NAME, VM_COUNT));

        // 3) 读取报告对象（指标全部在内存中，可直接断言/打印）。
        System.out.println("makespan（含引导偏移） = " + report.getMakespan() + " 秒");
        System.out.println("作业总数 = " + report.getTotalJobs()
                + "，成功 = " + report.getSuccessfulJobs()
                + "，失败 = " + report.getFailedJobs());
        System.out.println("平均真实减速比 = "
                + report.getMetrics().getMeanComputeTrueSlowdown());

        // 4) 可选：把证据工件写入磁盘（原子替换同名文件）。
        if (SAVE_ARTIFACTS) {
            ExperimentArtifactWriter.write(report,
                    Paths.get(OUTPUT_DIR, EXPERIMENT_NAME), EXPERIMENT_NAME);
            System.out.println("证据工件已写入 " + OUTPUT_DIR + "/" + EXPERIMENT_NAME + "/");
        }
    }
}
```

### 2. 修改配置

打开文件，修改顶部配置区的常量：算法、工作流路径、VM 数、种子、deadline。
每个开关的取值范围与前置约束见下文"配置参数详解"。

### 3. 右键运行

在 IDEA 中：
1. 右键 `main` 方法
2. 点击 **Run 'Experiment01.main()'**
3. 查看控制台输出与 `output/my-experiments/experiment-01/` 下的证据工件

完成！不需要任何命令行参数配置。

**输出目录说明**：`ExperimentArtifactWriter` 对同名工件做**原子替换**（先写同目录
临时文件，完整写入成功后才替换）。想保留多次运行，请把变化量编进 runId，例如
`EXPERIMENT_NAME + "-seed" + RANDOM_SEED` 或追加时间戳；否则同一 runId 的后一次
运行会覆盖前一次的三件套。

---

## 📖 配置参数详解

### 调度算法选择（`Parameters.SchedulingAlgorithm`）

`SimulationRunner` 只接受下表"可用"列中的标签；其余标签在运行入口即抛
`SimulationConfigurationException`（fail-fast，判定逻辑见
`AlgorithmCatalog.isSupportedBySimulationRunner`）：

| 枚举值 | SimulationRunner | 说明 |
|---|---|---|
| `FCFS` | ✅ | 先到先服务 |
| `READY_BATCH_MINMIN` | ✅ | 就绪批次 Min-Min（推荐基线） |
| `READY_BATCH_MAXMIN` | ✅ | 就绪批次 Max-Max |
| `READY_BATCH_MCT` | ✅ | 就绪批次最小完成时间 |
| `READY_BATCH_ROUNDROBIN` | ✅ | 就绪批次轮转 |
| `DATA` | ✅ | 数据感知调度（要求 LOCAL 文件系统，DATA+SHARED 组合被校验拒绝） |
| `STATIC` | ✅ | 静态派发层：执行规划器算好的 Task→VM 映射（必须搭配非 INVALID 规划器） |
| `RL_POLICY` | ✅* | R4 RL 轨道：须经 `RlEnvironment.runEpisode` 运行并注册策略；直接用 `SimulationRunner` 会因缺少注册策略显式失败 |
| `MINMIN` / `MAXMIN` / `MCT` / `ROUNDROBIN` | ❌ | @Deprecated 兼容标签，实际行为与名称不符（见枚举 Javadoc），一律拒绝 |
| `INVALID` | ❌ | 占位标签，拒绝 |

### 规划算法选择（`Parameters.PlanningAlgorithm`）

规划器做**离线 Task→VM 映射**，运行期必须搭配 `SchedulingAlgorithm.STATIC`
派发（`SimulationConfig.build()` 强制校验；反之 STATIC 也必须搭配非 INVALID
规划器）。R9 已移除与执行模型不对齐的历史 `HEFT` / `DHEFT` 标签。

| 家族 | 枚举值 | 前置约束 |
|---|---|---|
| 无规划（默认） | `INVALID` | 搭配在线调度算法（FCFS / READY_BATCH_* / DATA） |
| 随机映射 | `RANDOM` | 无 |
| 独立任务基线 | `STATIC_OLB` / `STATIC_MET` / `STATIC_MCT` / `STATIC_MINMIN` / `STATIC_MAXMIN` / `STATIC_SUFFERAGE` / `STATIC_ROUND_ROBIN` | 拒绝含依赖边的 DAG（仅独立任务集） |
| 共享存储 DAG | `SHARED_STORAGE_HEFT` / `SHARED_STORAGE_CPOP` / `SHARED_STORAGE_DLS` / `SHARED_STORAGE_ETF` / `SHARED_STORAGE_PEFT` | SHARED 文件系统 + NONE 聚类 + 关闭故障/开销 + SPACE_SHARED VM |
| 元启发式 | `PSO` | 论文复现（Pandey et al., AINA 2010），顺序负载模型 |
| 通信感知 DAG | `LOCAL_HEFT` / `LOCAL_CPOP` | LOCAL 文件系统 + NONE 聚类 + 无故障/开销 + `preExecutionTransferDelayV1()` 数据移动模型 |

各算法的决策语义、验证状态与适用边界见 [`../algorithms/CATALOG.md`](../algorithms/CATALOG.md)。

### 工作流路径

两种输入格式自动识别（按扩展名与内容严格校验）：

- **DAX**（Pegasus XML）：`datasets/dax/<app>/n<规模>/<App>_<n>.dax`，
  例如 `datasets/dax/epigenomics/n100/Epigenomics_100.dax`
- **WfCommons WfFormat JSON**：`datasets/wfformat/<app>/n<规模>/<app>-<n>-000.json`，
  例如 `datasets/wfformat/blast/n100/blast-100-000.json`

路径相对于**项目根目录**（IDEA 运行的工作目录）。多工作流输入用
`SimulationConfig.builder(List<String>, int)`（dax 与 JSON 可混合），到达时刻用
`workflowArrivalSeconds(List<Double>)` 声明。

### deadline 设置

```java
builder.deadline(3600L); // 秒
```

deadline 是**观测口径**而非终止条件：仿真总是跑到工作流自然结束，报告与
manifest 记录 deadline 值供事后判定是否违约（看门狗只校验引擎无滞留作业，
不会把 deadline 当作终止条件）。负值在 `build()` 时抛 `IllegalArgumentException`。

### 高级模型开关（Builder 方法速查）

以下方法都在 `SimulationConfig.Builder` 上，默认值即"关闭"：

| 方法 | 类型所在包 | 用途 |
|---|---|---|
| `overheadModel(OverheadModelConfig)` | `org.workflowsim.utils` | 队列/后处理/聚类/引擎延迟（按深度键控的分布采样，`DistributionSpec.of(family, scale, shape)`；深度 0 为默认项） |
| `failureModel(FailureModelConfig)` | `org.workflowsim.failure` | 故障到达流 + 重试预算（`generatorSpecs` 为按 VM/深度分组的分布矩阵；`maxTotalRetryJobs` 超限 fail-fast） |
| `costModel(Parameters.CostModel)` | `org.workflowsim.utils` | `DATACENTER`（数据中心级计费）或 `VM`（要求平台为每台 VM 显式定价，否则拒绝） |
| `dataMovementModel(DataMovementModel)` | `org.workflowsim.data` | `legacyWorkflowsimV1()` / `preExecutionTransferDelayV1()` / `preExecutionTransferDelayWithContentionV1()` |
| `taskCostMatrix(TaskCostMatrix)` | `org.workflowsim.utils` | 显式 Task×VM 计算成本矩阵（论文复现用） |
| `runtimeScale(double)` / `runtimeReferenceMips(double)` | — | 真实运行时间标定 |
| `randomSeed(long)` | — | 全局根种子：开销/故障采样、RANDOM 规划、就绪批次平局全部由此派生 |

⚠️ `overheadParameters(OverheadParameters)` 是历史可变对象入口，新代码一律用
不可变的 `overheadModel(...)`（会话按根种子自动生成运行期参数）。

---

## 💡 完整示例

以下片段省略 import（与模板一致，另按需引入
`org.workflowsim.utils.Parameters`、`org.workflowsim.platform.PlatformProfile`）。

### 示例 1：对比不同调度算法

```java
for (SchedulingAlgorithm algorithm : new SchedulingAlgorithm[]{
        SchedulingAlgorithm.FCFS,
        SchedulingAlgorithm.READY_BATCH_MINMIN,
        SchedulingAlgorithm.READY_BATCH_MCT}) {
    SimulationConfig config = SimulationConfig.builder(WORKFLOW_PATH, VM_COUNT)
            .schedulingAlgorithm(algorithm)
            .randomSeed(RANDOM_SEED)
            .build();
    SimulationReport report = new SimulationRunner().run(config,
            PlatformProfiles.homogeneousLocal(algorithm.name(), VM_COUNT));
    System.out.printf("%-22s makespan=%10.1f  trueSlowdown=%.4f%n",
            algorithm.name(), report.getMakespan(),
            report.getMetrics().getMeanComputeTrueSlowdown());
}
```

同一 seed、同一平台、只变算法——这是受控对比的最小形态。跨算法比较请锁定
其余全部开关（文件系统、开销、故障、数据移动模型）。

### 示例 2：不同 VM 数量对比

```java
for (int vmCount : new int[]{2, 4, 8}) {
    SimulationConfig config = SimulationConfig.builder(WORKFLOW_PATH, vmCount)
            .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
            .randomSeed(RANDOM_SEED)
            .build();
    SimulationReport report = new SimulationRunner().run(config,
            PlatformProfiles.homogeneousLocal("scale-" + vmCount, vmCount));
    System.out.printf("VM=%d makespan=%.1f 利用率Jain=%.4f%n", vmCount,
            report.getMakespan(),
            report.getMetrics().getVmUtilizationJainFairnessIndex());
}
```

### 示例 3：细粒度平台 + VM 级成本

`PlatformProfiles.homogeneousLocal` 之外，可用 `PlatformProfile.builder` 逐台
声明主机/VM/定价（构造参数以源码为准：
`HostSpec(int id, int pes, double mipsPerPe, int ramMb, long bandwidth, long storageMb)`、
`VmSpec(int id, double mips, int pes, int ramMb, long bandwidth, long imageSizeMb, String vmm, CloudletSchedulerMode schedulerMode[, CostSpec costs])`、
`CostSpec(double cpuPerSecond, double memory, double storage, double bandwidth)`）：

```java
PlatformProfile platform = PlatformProfile.builder("priced-platform")
        .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 10_000L, 1_000_000L))
        .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                new PlatformProfile.CostSpec(5.0, 0.0, 0.0, 0.0)))
        .costs(new PlatformProfile.CostSpec(2.0, 0.0, 0.0, 0.0))
        .build();

SimulationConfig config = SimulationConfig.builder(WORKFLOW_PATH, 1)
        .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
        .costModel(Parameters.CostModel.VM)   // 要求每台 VM 都有定价，否则 fail-fast
        .randomSeed(RANDOM_SEED)
        .build();
SimulationReport report = new SimulationRunner().run(config, platform);
for (SimulationReport.JobOutcome job : report.getJobs()) {
    System.out.printf("job=%d vm=%d cpuTime=%.3f cost=%.3f%n", job.getJobId(),
            job.getVmId(), job.getCpuTime(), job.getModeledProcessingCost());
}
```

注意：成本单位是**抽象成本单位**（manifest 中 `modeledProcessingCostScope`
声明为 `ABSTRACT_COST_UNITS`），不是货币；memory/storage 定价已声明但不计费。

---

## 🎯 最佳实践

### 1. 实验命名规范

- 一个研究问题一个实验类；类名表达变量（`MinminVsMctOnEpigenomics100`）。
- runId 用文件名安全字符，并把关键变量编进去：
  `epigenomics-n100-minmin-seed42`。

### 2. 随机种子管理

- **永远显式设置 `randomSeed`**：可复现性声明依赖固定根种子。
- 多种子实验用连续种子（42/43/44），并在结果中报告种子集合。
- 同一 config 对象重复 `run` 是**逐位可重放**的（会话按根种子重建全部随机流），
  可用来做重放断言（平台自身的集成测试正是这样锁定行为的）。

### 3. 输出目录组织

```
output/my-experiments/
├── experiment-01/
│   ├── experiment-01.manifest.json    （运行身份 + 配置 + 工件清单）
│   ├── experiment-01.metrics.json     （指标快照，schema v2）
│   └── experiment-01.events.jsonl     （全事件流，每行一个 JSON）
└── minmin-vs-mct/
    └── ...
```

`output/` 默认不入库；需要长期归档的证据工件复制到
`experiments/studies/<study>/` 并配 RETENTION.md（参考
`experiments/studies/fattree-scheduling-campaign/`）。

### 4. 代码注释

- 配置区每个常量写 Javadoc（模板已示范）。
- 非常规开关（为什么用这个种子/这个矩阵）在代码内注明依据（论文/审计报告章节）。

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
  （就绪事件齐备且时间戳有效的计算作业）。**无观测的样本不以 0 冒充测量值**：
  metrics.json 的等待/减速比统计只计入有观测的计算作业；从 events.jsonl 自行重算时，
  缺少 JOB_READY 观测的作业必须剔除而不是按 0 计。
- **分解指标可加性前提**：`meanComputeTotalWaitingTimeSeconds` ≈
  `meanComputeReadyToDecisionDelaySeconds` + `meanComputeDecisionToStartDelaySeconds`
  仅在三个指标的观察数（`totalWaitingTimeObservationCount` /
  `readyToDecisionDelayObservationCount` / `decisionToStartDelayObservationCount`）
  相等、样本集合重合时严格成立；观察数不等时不可加。正常运行下每作业两事件
  各恰一次（fail-fast 保护），三者恒相等。
- **从 events.jsonl 重算分布统计须过滤计算作业**：`JOB_READY` 事件对 stage-in
  作业同样会出现，而 metrics.json 的等待/减速比分布只统计**计算作业**；重算前必须
  按事件 `classType == 2`（COMPUTE）过滤，否则 n 与均值都和 metrics.json 不一致。

### 3. metrics.json schema 版本

证据包 metrics.json 当前为 `workflowsim-simulation-metrics-v2`。
v1 → v2 为破坏性变更（字段重命名 + 新增），解析旧字段名的下游代码需同步更新；
validator 会拒绝 v1 schema 与缺失关键字段的文件。

### 4. 分布统计与公平性指标

均值会掩盖长尾，比较调度算法时建议同时看分布：

- **分布统计**（最近秩法）：`medianComputeTotalWaitingTimeSeconds` /
  `p95ComputeTotalWaitingTimeSeconds` / `maxComputeTotalWaitingTimeSeconds`，
  减速比对应 `medianComputeTrueSlowdown` / `p95ComputeTrueSlowdown` /
  `maxComputeTrueSlowdown`。无观测时全部为 0.0，结合同文件的观测数字段判读。
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
- `SCHEDULING_DECISION` / `JOB_DISPATCHED` 携带 `queueDelaySeconds`，
  `JOB_RETURNED` 携带 `postDelaySeconds`——开销模型的每次采样都留有事件级证据。

---

## 📦 证据工件（manifest / metrics / events）

`ExperimentArtifactWriter.write(report, outputDirectory, runId)` 一次写出三件套
（另有四参重载可附加 `ExperimentEvidenceContext` 研究身份）：

| 文件 | 内容 |
|---|---|
| `<runId>.manifest.json` | 运行身份：config 全量、平台、输入工件 sha256、算法契约（`AlgorithmCatalog`）、工件清单 |
| `<runId>.metrics.json` | `{"schema": "workflowsim-simulation-metrics-v2", "metrics": {...}}`，`metrics` 为 `SimulationMetrics` 的完整 Gson 序列化 |
| `<runId>.events.jsonl` | 全事件流，每行一个 `SimulationEvent` JSON |

写入语义：先写同目录临时文件，**完整成功后原子替换**同名旧文件；中途失败不会
留下半成品。metrics 与 events 同时登记进 manifest 的工件清单。

### events.jsonl 每行结构（`SimulationEvent`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `sequence` | long | 全局事件序号 |
| `simulationTime` | double | 仿真时刻（秒） |
| `type` | string | 事件类型（`WORKFLOW_PARSED` / `WORKFLOW_ARRIVED` / `PLANNING_COMPLETED` / `JOBS_CLUSTERED` / `STAGE_IN_JOB_CREATED` / `JOB_READY` / `SCHEDULING_CYCLE` / `SCHEDULING_DECISION` / `JOB_DISPATCHED` / `DATA_STAGE_IN_MODELED` / `TASK_EXECUTION_MODELED` / `JOB_RETURNED` / `JOB_FAILED` 等） |
| `jobId` / `vmId` / `classType` | int（可空） | 关联作业/VM/作业类别（2=COMPUTE） |
| `taskIds` | int[] | 关联逻辑任务 |
| `attributes` | object | 事件属性（如 `queueDelaySeconds`、`postDelaySeconds`、`retryOfFailedJobId`、`modeledTransferSeconds`） |

### metrics.json 常用字段（`SimulationMetrics`，节选）

`makespanSeconds`、`jobOutcomeCount`、`successfulJobOutcomeCount`、
`failedComputeJobOutcomeCount`、`retryJobCreatedCount`、`logicalTaskCount`、
`allLogicalTasksCompletedSuccessfully`、`meanComputeJobRunTimeSeconds`、
`meanJobVmQueueWaitingTimeSeconds`、`meanJobResponseTimeSeconds`、
`meanComputeTotalWaitingTimeSeconds`、`meanComputeTrueSlowdown`、
`medianComputeTrueSlowdown`、`p95ComputeTrueSlowdown`、`maxComputeTrueSlowdown`、
`totalWaitingTimeObservationCount`、`trueSlowdownObservationCount`、
`successOnlyMeanComputeTrueSlowdown`、`vmUtilizationJainFairnessIndex`、
`retryAmplificationRatio`、`totalModeledProcessingCost`。

完整字段以 `SimulationMetrics.java` 源码为准（Gson 按字段名序列化）。

### Python 分析示例（字段名与写出一致）

```python
import json

base = 'output/my-experiments/experiment-01/experiment-01'

# 1) 读 metrics.json
with open(base + '.metrics.json') as f:
    doc = json.load(f)
assert doc['schema'] == 'workflowsim-simulation-metrics-v2'
m = doc['metrics']
print('makespan =', m['makespanSeconds'])
print('真实减速比 =', m['meanComputeTrueSlowdown'],
      '(n =', m['trueSlowdownObservationCount'], ')')

# 2) 读 events.jsonl：计算作业的就绪→派发延迟（classType==2 过滤口径见上文）
events = [json.loads(l) for l in open(base + '.events.jsonl')]
ready = {e['jobId']: e['simulationTime']
         for e in events if e['type'] == 'JOB_READY' and e.get('classType') == 2}
dispatched = {e['jobId']: e['simulationTime']
              for e in events if e['type'] == 'JOB_DISPATCHED' and e.get('classType') == 2}
waits = [dispatched[j] - ready[j] for j in dispatched if j in ready]
print('就绪→派发等待: n =', len(waits), ' mean =', sum(waits) / len(waits))

# 3) 开销证据：每次派发采样到的队列延迟
queue = [e['attributes'].get('queueDelaySeconds', 0.0)
         for e in events if e['type'] == 'JOB_DISPATCHED']
print('queueDelaySeconds 序列 =', queue)
```

---

## 🔧 故障排查

### Q1: 不同数据集的特点？

按工作流类型（`datasets/dax/` 与 `datasets/wfformat/` 两套语料）：

- **Epigenomics**（生物信息学）：流水线型，数据依赖简单
- **Montage**（天文图像拼接）：扇入扇出结构，数据密集
- **CyberShake**（地震分析）：参数扫描型，计算密集
- **Sipht**（RNA 序列）：多分支流水线，混合型
- **Inspiral**（引力波）：分支合并结构，计算密集

选择不同的工作流可以测试算法在不同 DAG 结构下的表现。

### Q2: 运行时看到 WARNING 日志？

**正常现象**。部分数据集（如 Montage、CyberShake）的 DAX 文件中，同一文件在不同任务中
声明的 size 可能略有不同（这是真实科学工作流的特点）。WorkflowSim 会记录警告并使用
第一次声明的 size，不影响仿真执行。如果不想看到逐事件日志，在运行前调用
`Log.disable()`（模板已包含）。

### Q3: 找不到工作流文件

**原因**：路径相对于项目根目录。

**解决**：确保从项目根目录运行，并核对路径：
```bash
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

### Q6: 构建/运行时报 `SimulationConfigurationException`？

配置组合被入口校验拒绝（fail-fast）。常见原因：规划器未搭配 `STATIC` 调度、
`STATIC` 未搭配规划器、`SHARED_STORAGE_*` 未用 SHARED 文件系统、`DATA` 用了
SHARED 文件系统、`CostModel.VM` 平台缺 VM 定价、使用了 @Deprecated 兼容标签。
异常消息会指明具体约束。

---

## 📚 下一步

- **理解算法原理**：[`ALGORITHMS.md`](ALGORITHMS.md)
- **算法契约与验证状态**：[`../algorithms/CATALOG.md`](../algorithms/CATALOG.md)
- **选择合适数据集**：[`DATASETS.md`](DATASETS.md)
- **命令行/批量运行**：[`RUN_EXPERIMENTS.md`](RUN_EXPERIMENTS.md)、[`BUILD.md`](BUILD.md)
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
