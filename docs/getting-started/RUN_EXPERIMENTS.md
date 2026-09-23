# 实验运行完整指南

本指南教你如何配置参数、选择算法、运行实验并保存结果。

## 方式一：在代码中运行（推荐 ⭐）

### 1. 创建你的实验类

在 `experiments/src/main/java/org/workflowsim/mystudy/` 下新建 `MyFirstExperiment.java`，
再使用下面的完整模板。若编写 JUnit 测试，应放到相应模块的 `src/test/java/` 并用测试入口运行。

模板通过 `ExperimentArtifactWriter.write(...)` 输出完整证据包：manifest v4、metrics v2 与
事件流 v1，manifest 内 provenance 仍为 v3。校验器继续兼容历史 manifest v2/v3。
到达时刻、任务成本矩阵、平台拓扑与数据移动语义会写入新 manifest。

```java
package org.workflowsim.mystudy;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.*;
import org.workflowsim.platform.*;
import org.workflowsim.utils.Parameters.*;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MyFirstExperiment {
    
    public static void main(String[] args) throws Exception {
        // 1. 禁用 CloudSim 冗长日志（推荐）
        Log.disable();
        
        // 2. 配置仿真参数
        SimulationConfig config = SimulationConfig.builder(
                "datasets/dax/montage/n100/Montage_100.dax",  // 工作流路径
                4                                               // VM 数量
            )
            // === 核心参数 ===
            .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)  // 调度算法
            .randomSeed(42L)                                               // 随机种子（保证可复现）
            
            // === 可选参数 ===
            .deadline(500L)                                     // deadline（秒），不设置则无 deadline
            .fileSystem(ReplicaCatalog.FileSystem.SHARED)       // 文件系统：SHARED（共享） 或 LOCAL（本地）
            .runtimeScale(1.0)                                  // 运行时缩放系数（加速/减速仿真）
            
            // === 高级参数（通常使用默认值）===
            // .overheadModel(...)          // 开销模型
            // .dataMovementModel(...)       // 数据移动模型
            // .failureModel(...)            // 故障模型
            
            .build();
        
        // 3. 定义平台（VM 配置）
        // 选项 A：同构平台（所有 VM 相同）
        PlatformProfile platform = PlatformProfiles.homogeneousLocal(
            "my-platform",    // 平台名称
            4                 // VM 数量（必须与 config 中的数量一致）
        );
        
        // 选项 B：异构平台（自定义每台 VM）
        // PlatformProfile platform = PlatformProfile.builder("my-heterogeneous-platform")
        //     .addHost(new PlatformProfile.HostSpec(0, 8, 4000.0, 4096, 10_000L, 1_000_000L))
        //     .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
        //         PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))    // VM0: 1000 MIPS, 1核, 512MB
        //     .addVm(new PlatformProfile.VmSpec(1, 2000.0, 2, 1024, 1000L, 10_000L, "Xen",
        //         PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))    // VM1: 2000 MIPS, 2核, 1GB
        //     .addVm(new PlatformProfile.VmSpec(2, 1500.0, 1, 512, 1000L, 10_000L, "Xen",
        //         PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))    // VM2: 1500 MIPS, 1核, 512MB
        //     .addVm(new PlatformProfile.VmSpec(3, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
        //         PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))    // VM3: 1000 MIPS, 1核, 512MB
        //     .build();
        
        // 4. 运行仿真
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        
        // 5. 查看结果
        System.out.println("=== 仿真完成 ===");
        System.out.println("Makespan: " + report.getMakespan() + " 秒");
        System.out.println("成功 Job 数: " + report.getSuccessfulJobs());
        System.out.println("失败 Job 数: " + report.getFailedJobs());
        System.out.println("平均 VM 利用率: " + 
            String.format("%.2f%%", report.getMetrics().getMeanVmModeledIntervalUtilization() * 100));
        System.out.println("总 CPU 成本: " + report.getMetrics().getTotalModeledProcessingCost());
        
        // 6. 可选：保存实验证据（manifest + metrics + events）
        Path outputDir = Paths.get("output/my-experiment");
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, outputDir, "run-seed42");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        System.out.println("\n已验证实验证据: " + artifacts.getManifest());
    }
}
```

### 2. 在 IDEA 中运行

#### 方式 2A：右键运行（推荐）

1. 确保 **Maven 已同步**（IDEA 右侧 Maven 工具窗口点击刷新）
2. 在 `MyFirstExperiment.java` 中右键 → **Run 'MyFirstExperiment.main()'**
3. 如果提示找不到类，需要先编译：**Maven 窗口 → WorkflowSim Parent → Lifecycle → compile**

#### 方式 2B：创建 Run Configuration

1. **Run** → **Edit Configurations** → **+** → **Application**
2. 配置：
   - **Name**: My First Experiment
   - **Main class**: `org.workflowsim.mystudy.MyFirstExperiment`
   - **Working directory**: `$PROJECT_DIR$`（项目根目录）
   - **Use classpath of module**: `workflowsim-experiments`（本节 main 类位于实验模块源码目录）
3. **Apply** → **OK**
4. 点击绿色运行按钮

#### 常见问题：找不到 experiments 模块

根 Maven 工程默认包含 `simulator/` 与 `experiments/`，当前没有 `experiments` profile。
确认导入的是[根 POM](../../pom.xml)，取消对实验模块的 Ignored 标记，然后执行
**Reload All Maven Projects**。Maven importer/runner 使用 JDK 17+。

模块、工作目录与运行 classpath 的详细设置见 [IDEA 配置指南](IDEA_SETUP.md)。

---

## 方式二：Maven 命令行运行

```bash
# 编译项目
mvn compile

# 运行你的实验类
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.mystudy.MyFirstExperiment \
  compile exec:java
```

---

## 核心配置选项说明

### 调度算法选择

```java
// === 在线调度器（运行时决策）===
SchedulingAlgorithm.FCFS                        // 先来先服务
SchedulingAlgorithm.READY_BATCH_MINMIN          // 小任务优先
SchedulingAlgorithm.READY_BATCH_MAXMIN          // 大任务优先
SchedulingAlgorithm.READY_BATCH_MCT             // 最小完成时间
SchedulingAlgorithm.READY_BATCH_ROUNDROBIN      // 轮转
SchedulingAlgorithm.DATA                        // 数据就近

// === 静态 DAG 规划器（离线规划，需要 SHARED 存储）===
// 必须同时设置：
// .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
// .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
// .fileSystem(ReplicaCatalog.FileSystem.SHARED)

PlanningAlgorithm.SHARED_STORAGE_HEFT           // HEFT（最常用基线）
PlanningAlgorithm.SHARED_STORAGE_CPOP           // CPOP（关键路径优化）
PlanningAlgorithm.SHARED_STORAGE_DLS            // DLS（动态层调度）
PlanningAlgorithm.SHARED_STORAGE_ETF            // ETF（最早开始时间）
PlanningAlgorithm.SHARED_STORAGE_PEFT           // PEFT（乐观代价表）
PlanningAlgorithm.PSO                           // PSO（粒子群优化，论文复现）

// === 通信感知静态 DAG 规划器（离线规划，需要 LOCAL 存储，论文复现）===
// 必须同时设置：
// .planningAlgorithm(PlanningAlgorithm.LOCAL_HEFT)
// .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
// .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
// .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
// 争用研究变体（非论文复现组合）：preExecutionTransferDelayWithContentionV1()
// （R2 端点争用）或 fatTreeContentionV1()（R6 Fat-tree 链路争用，还需平台声明
// PlatformProfile.builder(...).networkTopology(NetworkTopologySpec.fatTree(...))）

PlanningAlgorithm.LOCAL_HEFT                    // HEFT（通信感知，Topcuoglu TPDS 2002 复现）
PlanningAlgorithm.LOCAL_CPOP                    // CPOP（通信感知，同上）

// === 静态独立任务映射器（无依赖任务）===
// 必须同时设置：
// .planningAlgorithm(PlanningAlgorithm.STATIC_MINMIN)
// .schedulingAlgorithm(SchedulingAlgorithm.STATIC)

PlanningAlgorithm.STATIC_MINMIN                 // Min-Min
PlanningAlgorithm.STATIC_MAXMIN                 // Max-Min
PlanningAlgorithm.STATIC_MCT                    // MCT
PlanningAlgorithm.STATIC_OLB                    // OLB（机会主义负载均衡）
PlanningAlgorithm.STATIC_MET                    // MET（最小执行时间）
PlanningAlgorithm.STATIC_SUFFERAGE              // Sufferage（吃亏值优先）
PlanningAlgorithm.STATIC_ROUND_ROBIN            // 轮转
```

### 数据移动模型与证据

R2/R6 共用 max-min progressive filling，并在流完成时分段积分、重新分配剩余容量。
R2 的资源为 VM 端点；R6 还包含确定性路由的有向链路，外部 SOURCE 输入绕过拓扑。
这两个模型在 Job 就绪时启动全部传输组，LOCAL 规划器仍按无争用 AST 估计。
模型语义保存在 manifest v4 的数据移动声明中，provenance 仍为 v3。

### 工作流输入选择

```java
// DAX 格式（Pegasus）
"datasets/dax/montage/n100/Montage_100.dax"
"datasets/dax/epigenomics/n100/Epigenomics_100.dax"
"datasets/dax/sipht/n100/Sipht_100.dax"

// WfCommons WfFormat JSON（仓库自带的合成输入）
"datasets/wfformat/montage/n100/montage-100-000.json"

// WfInstances JSON（仓库自带的真实执行派生试点）
"datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json"
```

### 常用配置模式

#### 模式 1：在线调度 + 共享存储

```java
SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
    .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
    .fileSystem(ReplicaCatalog.FileSystem.SHARED)
    .randomSeed(42L)
    .build();
```

#### 模式 2：静态 DAG 规划 + HEFT

```java
SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
    .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
    .fileSystem(ReplicaCatalog.FileSystem.SHARED)
    .randomSeed(42L)
    .build();
```

#### 模式 3：记录 deadline 观测

截止时间从仿真零时刻起算，只用于结果判定，不改变调度策略或终止仿真。

```java
SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
    .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MAXMIN)
    .deadline(300L)  // 300 秒 deadline
    .randomSeed(42L)
    .build();

// 查看 deadline 观测结果
boolean met = report.getMetrics().isDeadlineMet();
double slack = report.getMetrics().getDeadlineSlackSeconds();  // 正值=提前，负值=超时
```

#### 模式 4：故障模型（高级）

```java
import org.workflowsim.failure.*;
import org.workflowsim.utils.DistributionGenerator.*;
import org.workflowsim.utils.DistributionSpec;
import java.util.LinkedHashMap;
import java.util.Map;

// 本节使用同构工厂生成的 VM ID 0..vmCount-1。
Map<Integer, DistributionSpec[]> failureRows = new LinkedHashMap<Integer, DistributionSpec[]>();
for (int vmId = 0; vmId < vmCount; vmId++) {
    failureRows.put(vmId, new DistributionSpec[]{
        DistributionSpec.of(DistributionFamily.WEIBULL, 0.1, 1.0)
    });
}
FailureModelConfig failure = FailureModelConfig.builder()
    .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
    .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
    .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
    .generatorSpecsByVmId(failureRows)
    .maxTotalRetryJobs(50)
    .build();

SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
    .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
    .failureModel(failure)
    .randomSeed(42L)
    .build();
```

---

## 完整可用示例

### 示例 1：对比 3 种在线调度器

```java
public class CompareSchedulers {
    public static void main(String[] args) throws Exception {
        Log.disable();
        
        String workflow = "datasets/dax/montage/n100/Montage_100.dax";
        int vmCount = 4;
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("platform", vmCount);
        
        SchedulingAlgorithm[] algorithms = {
            SchedulingAlgorithm.FCFS,
            SchedulingAlgorithm.READY_BATCH_MINMIN,
            SchedulingAlgorithm.READY_BATCH_MAXMIN
        };
        
        for (SchedulingAlgorithm algo : algorithms) {
            SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
                .schedulingAlgorithm(algo)
                .randomSeed(42L)
                .build();
            
            SimulationRunner runner = new SimulationRunner();
            SimulationReport report = runner.run(config, platform);
            
            System.out.printf("%s: makespan=%.2f, 利用率=%.2f%%\n",
                algo.name(),
                report.getMakespan(),
                report.getMetrics().getMeanVmModeledIntervalUtilization() * 100
            );
        }
    }
}
```

### 示例 2：对比 HEFT vs CPOP

```java
public class CompareDAGPlanners {
    public static void main(String[] args) throws Exception {
        Log.disable();
        
        String workflow = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";
        int vmCount = 4;
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("platform", vmCount);
        
        PlanningAlgorithm[] algorithms = {
            PlanningAlgorithm.SHARED_STORAGE_HEFT,
            PlanningAlgorithm.SHARED_STORAGE_CPOP
        };
        
        for (PlanningAlgorithm algo : algorithms) {
            SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
                .planningAlgorithm(algo)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(42L)
                .build();
            
            SimulationRunner runner = new SimulationRunner();
            SimulationReport report = runner.run(config, platform);
            
            System.out.printf("%s: makespan=%.2f\n",
                algo.name().replace("SHARED_STORAGE_", ""),
                report.getMakespan()
            );
        }
    }
}
```

---

## 下一步

- **深入理解算法**：[`docs/getting-started/ALGORITHMS.md`](../getting-started/ALGORITHMS.md)
- **数据集选择**：[`docs/getting-started/DATASETS.md`](../getting-started/DATASETS.md)
- **可复现性保证**：[`docs/experiments/REPRODUCIBILITY.md`](../experiments/REPRODUCIBILITY.md)
- **多场景实验设计**：[`docs/experiments/CAMPAIGNS.md`](../experiments/CAMPAIGNS.md)
