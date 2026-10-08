# Java API 实验指南

只需修改输入、资源和内置算法时，优先使用 [Workbench](<WORKBENCH.md>)。本指南用于自定义 Java 实验、故障模型或外部策略；算法选择统一见[算法目录](<../algorithms/CATALOG.md>)，输入选择见[数据集说明](<../../datasets/README.md>)。

## 一个完整示例

在实验模块中新建以下文件。这是你创建的入口，不是仓库预置类：

```text
experiments/src/main/java/org/workflowsim/mystudy/MyExperiment.java
```

```java
package org.workflowsim.mystudy;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class MyExperiment {
    private MyExperiment() {
    }

    public static void main(String[] args) throws Exception {
        String workflow = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";
        int vmCount = 4;
        long seed = 42L;

        SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(seed)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("example", vmCount);

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);
        System.out.println("Simulation end (s): " + report.getMakespan());
        System.out.println("Logical completion (s): " + report.getLogicalTaskCompletionSeconds());
        System.out.println("Successful/failed Job attempts: "
                + report.getSuccessfulJobs() + "/" + report.getFailedJobs());
        System.out.println("Mean VM utilization: "
                + report.getMetrics().getMeanVmModeledIntervalUtilization());

        Path output = Paths.get("output/my-study");
        String runId = "fcfs-seed" + seed;
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, runId);
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        System.out.println("Validated manifest: " + artifacts.getManifest());
    }
}
```

从仓库根目录运行：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.mystudy.MyExperiment
```

IDEA 运行时选择实验模块 classpath，工作目录为 `$PROJECT_DIR$`，见[IDEA 配置](<IDEA_SETUP.md>)。Java API 的相对输入/输出路径以进程工作目录为基准，不采用 Workbench 的配置文件相对路径规则。

## 配置与模型边界

- [SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)是不可变配置；`build()` 校验组合，运行器继续校验输入、平台、成本覆盖和执行前提。默认使用 FCFS、无规划、SHARED 和 legacy 数据移动，故障与开销关闭；种子应显式声明。
- 标准运行器要求 NONE 聚类、SPACE_SHARED VM、PE 兼容放置；每台 VM 同时最多派发一个 Job，不等于所有任务永远只使用一个 PE。
- `PlatformProfiles.homogeneousLocal` 创建一台 VM 对应一台 Host、每 VM 1000 MIPS/1 PE/512 MB 的平台；名称中的 Local 不设置文件系统。配置中的 VM 数必须与平台一致。
- 离线规划必须配合 STATIC 派发。coherent V2/V3 的显式在线绑定是独立决策层，可使用 INVALID/STATIC，不能把它与普通 CPU 在线调度混为一谈。
- V1、coherent V2 和受限存储 V3 的数据就绪、输出提交和记录语义不同。不要只替换一个枚举而忽略文件系统、拓扑、SOURCE 存储或证据模式要求，见[能力与组合说明](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>)。

## 常用配方

以下配方独立应用于基础示例，不能无条件叠加。片段不是独立程序；所示 import 放在文件头，其余代码放在 `main` 中、调用运行器之前。

### 共享存储静态规划

增加 import，并替换基础示例的 `config` 定义：

```java
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
        .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
        .randomSeed(seed)
        .build();
```

共享存储规划器要求无故障/开销、NONE 聚类和 SPACE_SHARED，且不接受任务成本矩阵。LOCAL 规划器使用不同的 V1 pre-execution 模型，不能作为 coherent V2/V3 估计器。完整组合和论文适配范围见[算法契约](<../algorithms/CONTRACTS.md>)。

### 异构资源、到达与截止时间

替换平台定义，可声明四台计算能力不同的异构 VM：

```java
PlatformProfile platform = PlatformProfiles.heterogeneousLocal("heterogeneous",
        java.util.Arrays.asList(500.0, 1000.0, 2000.0, 1000.0));
```

更细的平台、VM→Host 绑定及抽象定价使用 [PlatformProfile](<../../simulator/src/main/java/org/workflowsim/platform/PlatformProfile.java>)。VM 级成本模式要求每台 VM 显式定价；成本不是校准后的云货币价格。

多工作流通过 `SimulationConfig.builder(List<String>, vmCount)` 声明，随后用 `workflowArrivalSeconds(List<Double>)` 指定同长度的非负秒数列表。输入及到达表启动前已知，运行期按时刻释放。

`deadline(long)` 从仿真零时刻起算，0 表示未请求；它只用于事后 SLA 观察，不改变调度、重试或仿真终止。`config.withRandomSeed(...)` 可生成仅种子变化的新配置；确定性算法的多种子重复不自动成为独立统计样本。

### 显式故障与重试预算

以下配置按基础平台的实际 VM ID 0 至 3 声明抽象故障流。使用 VM-ID 键控行时，生成器模式必须是 `FAILURE_VM` 或 `FAILURE_VM_JOB`，不能配 `FAILURE_ALL`。

```java
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

java.util.Map<Integer, DistributionSpec[]> rows =
        new java.util.LinkedHashMap<Integer, DistributionSpec[]>();
for (int vmId = 0; vmId < vmCount; vmId++) {
    rows.put(vmId, new DistributionSpec[]{
        DistributionSpec.of(DistributionFamily.WEIBULL, 1000.0, 1.0)
    });
}
FailureModelConfig failure = FailureModelConfig.builder()
        .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
        .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
        .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
        .generatorSpecsByVmId(rows)
        .maxTotalRetryJobs(50)
        .build();
config = config.toBuilder().failureModel(failure).build();
```

参数只是模型输入，不是现网故障校准；单次运行也不保证必然出现失败。标准运行器支持 NOOP 重试，预算必须为正，耗尽会显式失败。故障在尝试完成边界判定，失败输出不成为可读副本。只有允许故障的模型/算法组合才能采用此配方，约束以 [FailureModelConfig](<../../simulator/src/main/java/org/workflowsim/failure/FailureModelConfig.java>)及[算法契约](<../algorithms/CONTRACTS.md>)为准。

### 接入外部策略

`RL_POLICY` 通过 [RlEnvironment](<../../simulator/src/main/java/org/workflowsim/rl/RlEnvironment.java>)的 `runEpisode(config, platform, policy)` 注册策略并运行 episode，提供观测、动作、轨迹与奖励，不提供学习算法。coherent 模型的在线目标绑定不是这个 RL 接口，详见[在线绑定契约](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 读取与保存结果

- `getMakespan()` 是模拟结束时刻；`getLogicalTaskCompletionSeconds()` 仅在全部逻辑任务成功时可用，二者可能因输出写回等生命周期尾部而不同。
- 成功/失败 Job 数统计尝试，含模型生成的 stage-in Job；不要当作逻辑任务数。等待、减速比、成本和利用率有各自样本范围，见[指标契约](<../algorithms/CONTRACTS.md>)和 [SimulationMetrics](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationMetrics.java>)。
- `ExperimentArtifactWriter.write(...)` 写出 manifest v4、metrics v2、events v1，manifest 内 provenance 为 v3；启用记录时另有匹配的网络/生命周期侧车。仅写独立 manifest 不等于完整证据包。
- 同一目录与 runId 会替换同名工件。单文件采用临时写入后替换，支持时为原子替换；整个证据包不是跨文件事务，写出后必须校验。需要保留多次运行时使用不同 runId 或目录。
- Workbench 可从校验后的 manifest 生成自包含 HTML；Java 写器本身负责证据而不是报告界面。命令见[Workbench 指南](<WORKBENCH.md>)。

记录输入哈希、配置、平台、种子与实现版本，不把墙钟耗时当作可逐位重现的模拟时间。实验证据是否保留应显式决定；[忽略规则](<../../.gitignore>)并不忽略整个 `output/`。构建和独立验证命令集中在[构建指南](<BUILD.md>)。
