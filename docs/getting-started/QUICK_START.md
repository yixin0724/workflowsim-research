# 快速上手指南

5 分钟内运行你的第一个 WorkflowSim 仿真。

## 环境准备

要求：JDK 17+ 和 Maven 3.6.3+

```bash
java -version   # 应显示 17 或更高
mvn -v          # 应显示 3.6.3 或更高
```

克隆项目后，先验证基础环境：

```bash
cd WorkflowSim-1.0
mvn verify      # 核心测试，约 7 秒
```

看到 `BUILD SUCCESS` 和 `Tests run: 243, Failures: 0` 即通过。

## 第一个仿真：单文件示例

创建 `MyFirstSimulation.java`：

```java
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.*;
import org.workflowsim.platform.*;
import org.workflowsim.utils.Parameters.*;

public class MyFirstSimulation {
    public static void main(String[] args) throws Exception {
        // 1. 禁用 CloudSim 日志（可选，但推荐）
        Log.disable();
        
        // 2. 配置仿真：工作流 + 算法 + 随机种子
        SimulationConfig config = SimulationConfig.builder(
                "datasets/dax/Montage_25.dax",  // Pegasus DAX 输入
                3                                // VM 数量
            )
            .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
            .randomSeed(42L)                     // 保证可复现
            .build();
        
        // 3. 定义平台：3 台同构 VM，每台 1000 MIPS
        PlatformProfile platform = PlatformProfiles.homogeneousLocal(
            "my-platform", 
            3  // 与上面 VM 数量一致
        );
        
        // 4. 运行仿真
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        
        // 5. 查看结果
        System.out.println("仿真完成！");
        System.out.println("Makespan: " + report.getMakespan() + " 秒");
        System.out.println("成功 Job 数: " + report.getSuccessfulJobs());
        System.out.println("失败 Job 数: " + report.getFailedJobs());
        System.out.println("平均 VM 利用率: " + 
            String.format("%.2f%%", report.getMetrics().getMeanVmModeledIntervalUtilization() * 100));
    }
}
```

放在 `simulator/src/test/java/` 或 `experiments/src/main/java/` 下，运行：

```bash
# 如果放在 experiments/
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=MyFirstSimulation compile exec:java
```

预期输出类似：

```
仿真完成！
Makespan: 65.42 秒
成功 Job 数: 25
失败 Job 数: 0
平均 VM 利用率: 87.34%
```

## 理解这 5 行核心代码

### 1. 工作流输入

```java
SimulationConfig.builder("datasets/dax/Montage_25.dax", 3)
```

支持三种输入格式：
- **Pegasus DAX XML**：`datasets/dax/*.dax`（经典格式）
- **WfCommons WfFormat JSON**：`datasets/wfformat/montage/*.json`（合成工作流）
- **WfInstances JSON**：`datasets/wfinstances/v1.5/pegasus/montage/*.json`（真实执行轨迹抽象）

路径从**项目根目录**开始算。

### 2. 调度算法

```java
.schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
```

6 种在线调度器（运行时决策）：
- `FCFS` - 先来先服务
- `READY_BATCH_MINMIN` - 小任务优先
- `READY_BATCH_MAXMIN` - 大任务优先
- `READY_BATCH_MCT` - 最小完成时间
- `READY_BATCH_ROUNDROBIN` - 轮转
- `DATA` - 数据就近

想用静态规划算法？见下一节。

### 3. 随机种子

```java
.randomSeed(42L)
```

保证同一配置下的可复现。相同种子 → 相同结果（makespan 到小数点后 12 位）。

### 4. 平台定义

```java
PlatformProfiles.homogeneousLocal("my-platform", 3)
```

同构平台：所有 VM 相同配置（1000 MIPS, 1 核, 512 MB, LOCAL 存储）。

需要异构？见高级用法。

### 5. 结果报告

```java
SimulationReport report = runner.run(config, platform);
```

`SimulationReport` 包含 67 个指标：

| 指标方法 | 含义 |
| --- | --- |
| `getMakespan()` | 仿真结束时间（秒） |
| `getLogicalTaskCompletionSeconds()` | 逻辑任务完成时间（不含引擎尾部） |
| `getSuccessfulJobs()` / `getFailedJobs()` | 成功/失败 Job 数 |
| `getMetrics().getMeanVmModeledIntervalUtilization()` | 平均 VM 利用率（0.0 ~ 1.0） |
| `getMetrics().getComputeJobOutcomeThroughputPerSecond()` | 吞吐率（Job/秒） |
| `getMetrics().getTotalModeledProcessingCost()` | 抽象处理成本 |

完整指标列表见 `SimulationMetrics` 的 Javadoc。

## 进阶：静态 DAG 规划

对比 HEFT、CPOP、PEFT 等经典 DAG 算法：

```java
SimulationConfig config = SimulationConfig.builder(
        "datasets/dax/Montage_25.dax", 3
    )
    .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)  // 静态规划算法
    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)           // 必须搭配 STATIC 分派
    .fileSystem(ReplicaCatalog.FileSystem.SHARED)              // 必须 SHARED 存储
    .randomSeed(42L)
    .build();
```

5 种静态 DAG 规划器：
- `SHARED_STORAGE_HEFT` - 最经典基线
- `SHARED_STORAGE_CPOP` - 关键路径优化
- `SHARED_STORAGE_DLS` - 动态层调度
- `SHARED_STORAGE_ETF` - 最早开始时间优先
- `SHARED_STORAGE_PEFT` - 乐观代价表

**注意**：这些算法有严格前提条件（共享存储、无故障、`SPACE_SHARED` VM 等），
详见算法文档。

## 进阶：保存实验证据

生成可审计的工件（manifest + metrics + events）：

```java
import org.workflowsim.experiment.artifact.*;
import java.nio.file.*;

// ... 运行仿真得到 report ...

Path outputDir = Paths.get("output");
Files.createDirectories(outputDir);

// 写入 manifest.json、metrics.json、events.jsonl
ExperimentManifestWriter.write(
    report,
    outputDir.resolve("manifest.json")
);

ExperimentArtifactWriter.writeMetrics(
    report,
    outputDir.resolve("metrics.json")
);

ExperimentArtifactWriter.writeEvents(
    report.getEvents(),
    outputDir.resolve("events.jsonl")
);

System.out.println("实验证据已保存到 output/");
```

工件可用 `ExperimentManifestValidator` 只读验证其完整性。

## 常见任务速查

### 切换工作流

```java
// DAX
"datasets/dax/Montage_25.dax"
"datasets/dax/Epigenomics_24.dax"

// WfFormat
"datasets/wfformat/montage/montage-2.5Mb-003.json"
"datasets/wfformat/blast/blast-chameleon-small-003.json"

// WfInstances
"datasets/wfinstances/v1.5/pegasus/montage/montage-2mass-2mass-***.json"
```

### 切换算法

```java
// 在线调度
.schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MAXMIN)

// 静态 DAG
.planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_CPOP)
.schedulingAlgorithm(SchedulingAlgorithm.STATIC)
.fileSystem(ReplicaCatalog.FileSystem.SHARED)

// 静态独立任务
.planningAlgorithm(PlanningAlgorithm.STATIC_MINMIN)
.schedulingAlgorithm(SchedulingAlgorithm.STATIC)
```

### 增加 VM 数量

```java
SimulationConfig.builder("workflow.dax", 10)  // 10 台 VM
// ...
PlatformProfiles.homogeneousLocal("platform", 10)
```

### 设置 deadline

```java
.deadline(100L)  // 100 秒
```

查看 deadline 观测结果：

```java
System.out.println("Deadline met: " + report.getMetrics().isDeadlineMet());
System.out.println("Slack: " + report.getMetrics().getDeadlineSlackSeconds());
```

### 启用故障模型

```java
import org.workflowsim.failure.*;
import org.workflowsim.utils.DistributionGenerator.*;
import org.workflowsim.utils.DistributionSpec;

FailureModelConfig failure = FailureModelConfig.builder()
    .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
    .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
    .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
    .generatorSpecs(new DistributionSpec[][]{{
        DistributionSpec.of(DistributionFamily.WEIBULL, 0.1, 1.0)
    }})
    .maxTotalRetryJobs(50)
    .build();

SimulationConfig config = SimulationConfig.builder("workflow.dax", 3)
    .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
    .failureModel(failure)
    .randomSeed(42L)
    .build();
```

查看重试统计：

```java
System.out.println("Retries created: " + report.getMetrics().getRetryJobCreatedCount());
System.out.println("Failed jobs: " + report.getMetrics().getFailedComputeJobOutcomeCount());
```

## 下一步

- **理解算法原理**：[`ALGORITHMS.md`](../getting-started/ALGORITHMS.md) - 三类算法的本质区别和每个算法的底层原理
- **构建命令速查**：[`BUILD.md`](../getting-started/BUILD.md) - 如何运行测试、构建文档、执行 P7 基线
- **算法决策边界**：[`CATALOG.md`](../algorithms/CATALOG.md) - 每个算法的严格定义和主张范围
- **可复现性保证**：[`REPRODUCIBILITY.md`](../experiments/REPRODUCIBILITY.md) - 随机性、种子和确定性 tie-breaking
- **实验设计规范**：[`CAMPAIGNS.md`](../experiments/CAMPAIGNS.md) - 多场景、多重复实验设计

## 故障排查

### `ClassNotFoundException: org.workflowsim.examples.*`

示例代码在 `experiments/` 模块，现已默认构建。

### `Input must have exactly N VMs, but received M`

配置中的 VM 数量必须与平台定义一致：

```java
// ✅ 正确
.builder("workflow.dax", 3)
PlatformProfiles.homogeneousLocal("platform", 3)

// ❌ 错误
.builder("workflow.dax", 3)
PlatformProfiles.homogeneousLocal("platform", 5)
```

### `SHARED_STORAGE_HEFT requires shared storage`

静态 DAG 算法必须配置 `SHARED` 存储：

```java
.fileSystem(ReplicaCatalog.FileSystem.SHARED)
```

### Makespan 每次运行都不一样

忘记设置随机种子：

```java
.randomSeed(42L)  // 任意固定值即可
```
