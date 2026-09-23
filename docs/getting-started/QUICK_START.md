# 快速上手指南

## 环境准备

要求 **JDK 17+、Maven 3.6.3+**，生成的 Java 产物兼容 Java 8 API。
从仓库根目录运行：

```bash
java -version
mvn -v
mvn verify
```

默认验证 `simulator/` 与 `experiments/` 两个模块的单元、集成和覆盖率门禁。
以 `BUILD SUCCESS` 和本次测试报告为准，不依赖固定的历史测试数量。
只检查核心时使用 `mvn -pl :workflowsim verify`；没有需要启用的 `experiments` profile。

## 先运行仓库中的示例

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.WorkflowSimBasicExample1 \
  compile exec:java
```

该入口使用仓库中的经典 DAX 输入。所有示例命令都以项目根为工作目录。
IDEA 运行时选择 `workflowsim-experiments` 模块 classpath，设置工作目录为 `$PROJECT_DIR$`，
详见[IDEA 配置](IDEA_SETUP.md)。

## 创建自己的仿真

在 `experiments/src/main/java/org/workflowsim/mystudy/` 下新建 `MyFirstSimulation.java`。
下面是完整源代码；这个类由你创建，不是仓库预置入口。

```java
package org.workflowsim.mystudy;

import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class MyFirstSimulation {
    public static void main(String[] args) throws Exception {
        Log.disable();

        SimulationConfig config = SimulationConfig.builder(
                "datasets/dax/montage/n25/Montage_25.dax", 3)
                .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(42L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("my-platform", 3);
        SimulationReport report = new SimulationRunner().run(config, platform);

        System.out.println("Makespan: " + report.getMakespan() + " 秒");
        System.out.println("成功 Job 数: " + report.getSuccessfulJobs());
        System.out.println("失败 Job 数: " + report.getFailedJobs());
        System.out.printf("平均 VM 利用率: %.2f%%%n",
                report.getMetrics().getMeanVmModeledIntervalUtilization() * 100.0);

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, Paths.get("output/my-first"), "run-seed42");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        System.out.println("已验证工件: " + artifacts.getManifest());
    }
}
```

运行新建的类：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.mystudy.MyFirstSimulation \
  compile exec:java
```

输出数值取决于输入、平台、算法和模型。Job 总数包含模拟器生成的 stage-in Job，不能直接
当成工作流逻辑任务数。

## 五个关键概念

### 工作流输入

`SimulationConfig.builder(workflowPath, vmCount)` 声明输入与 VM 数量。以下输入在标准检出中可用：

```java
// Pegasus DAX XML
"datasets/dax/montage/n25/Montage_25.dax"
"datasets/dax/epigenomics/n24/Epigenomics_24.dax"

// WfGen 合成 WfFormat JSON
"datasets/wfformat/montage/n100/montage-100-000.json"

// WfInstances 的真实执行派生 JSON
"datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json"
```

输入格式是 DAX XML 或 WfFormat JSON；WfGen 和 WfInstances 是不同来源的 JSON 集合。
完整语料的获取范围与解释边界见[数据集说明](../../datasets/README.md)。

### 调度算法

六个普通在线调度器通过 `SimulationRunner` 运行：`FCFS`、`READY_BATCH_MINMIN`、
`READY_BATCH_MAXMIN`、`READY_BATCH_MCT`、`READY_BATCH_ROUNDROBIN`、`DATA`。
`DATA` 必须配合 `ReplicaCatalog.FileSystem.LOCAL`。

`RL_POLICY` 使用单独的 `RlEnvironment.runEpisode(...)` 策略入口；它提供环境与奖励契约，
不包含训练算法。完整算法选择见[算法导读](ALGORITHMS.md)。

### 随机种子

显式设置 `.randomSeed(42L)`。在相同代码版本、输入、平台与模型配置下，根种子用于重建
仿真随机流。墙钟耗时等性能观测不应要求逐位一致。更多约束见
[可复现性说明](../experiments/REPRODUCIBILITY.md)。

### 平台定义

`PlatformProfiles.homogeneousLocal("my-platform", 3)` 创建三台同构 VM：每台 1000 MIPS、
1 PE、512 MB、`SPACE_SHARED`，一台 VM 对应一个 Host。这个工厂方法的名称不设置文件系统；
文件系统由 `SimulationConfig.fileSystem(...)` 决定，默认是 SHARED。

### 结果报告

| 方法 | 含义 |
| --- | --- |
| `getMakespan()` | 仿真结束时刻（秒） |
| `getLogicalTaskCompletionSeconds()` | 逻辑任务完成时刻 |
| `getSuccessfulJobs()` / `getFailedJobs()` | 成功/失败 Job 数 |
| `getMetrics().getMeanVmModeledIntervalUtilization()` | 平均 VM 利用率 |
| `getMetrics().getComputeJobOutcomeThroughputPerSecond()` | 计算 Job 吞吐率 |
| `getMetrics().getTotalModeledProcessingCost()` | 抽象处理成本 |

完整定义见 [SimulationMetrics.java](../../simulator/src/main/java/org/workflowsim/experiment/SimulationMetrics.java)。

## 静态 DAG 规划

以下片段替换前面示例的配置，并增加对应 import：

```java
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

SimulationConfig config = SimulationConfig.builder(
        "datasets/dax/montage/n25/Montage_25.dax", 3)
        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
        .randomSeed(42L)
        .build();
```

共享存储家族包含 `SHARED_STORAGE_HEFT/CPOP/DLS/ETF/PEFT`，要求无聚类、无故障/开销，
并使用 `SPACE_SHARED` VM。通信感知的 `LOCAL_HEFT/LOCAL_CPOP` 另要求 LOCAL 文件系统和
pre-execution 家族数据移动模型，见[算法导读](ALGORITHMS.md)。

## 保存与验证证据

上面的 `ExperimentArtifactWriter.write(...)` 一次写出：

```text
output/my-first/
├── run-seed42.manifest.json
├── run-seed42.metrics.json
└── run-seed42.events.jsonl
```

当前 manifest schema 为 **v4**，显式记录工作流到达时刻、成本矩阵、网络拓扑和真实数据移动
语义；provenance schema 保持 **v3**，metrics 为 v2，事件为 v1。`ExperimentArtifactValidator`
继续兼容历史 manifest v2/v3，但不会把历史缺失字段补成可信的完整配置。

同一输出目录与 runId 会替换同名工件。要保留不同配置/种子，应使用不同 runId。
仅调用 `ExperimentManifestWriter.writeJson(...)` 会写独立 manifest，不生成完整可验证证据包。

## 常用配置

```java
// VM 数量必须与平台 VM 数量一致
SimulationConfig.builder("datasets/dax/montage/n25/Montage_25.dax", 10);
PlatformProfiles.homogeneousLocal("platform", 10);

// 截止时间用于事后观测，不改变调度或终止仿真
// 在配置 builder 上调用 .deadline(100L)
System.out.println(report.getMetrics().isDeadlineMet());
System.out.println(report.getMetrics().getDeadlineSlackSeconds());
```

多工作流到达时刻按输入顺序声明，单位为从仿真零时刻起算的秒：

```java
import java.util.Arrays;

SimulationConfig arrivalConfig = SimulationConfig.builder(Arrays.asList(
        "datasets/dax/montage/n25/Montage_25.dax",
        "datasets/dax/epigenomics/n24/Epigenomics_24.dax"), 3)
        .workflowArrivalSeconds(Arrays.asList(0.0, 2000.0))
        .randomSeed(42L)
        .build();
```

工作流及其到达时刻在启动前声明，运行期按时刻释放；非零到达要求无聚类。

## 下一步与排查

- 构建、定向测试与工件校验命令：[构建指南](BUILD.md)。
- 自定义平台、故障、成本与证据字段：[代码配置指南](CODE_CONFIG_EXPERIMENTS.md)。
- IDEA 找不到实验类：重新加载根 Maven 工程，确认两个模块均已导入，见[IDEA 配置](IDEA_SETUP.md)。
- `SHARED_STORAGE_HEFT requires shared storage`：显式选择 SHARED 文件系统。
- 多场景、多种子比较：[实验设计](../experiments/CAMPAIGNS.md)。
