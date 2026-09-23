# WorkflowSim 构建与运行指南

## 环境与模块

要求 **JDK 17+、Maven 3.6.3+**。构建使用现代 JDK，Java 产物通过 `--release 8`
保持 Java 8 API 兼容。以下命令均从仓库根目录执行：

```bash
java -version
mvn -v
```

[根 POM](../../pom.xml) 默认声明两个模块，**不需要 `experiments` profile**：

| 模块 | Maven 坐标 | 职责 | 默认构建 |
| --- | --- | --- | --- |
| `simulator/` | `org.workflowsim:workflowsim:1.0` | 核心模拟器、通用运行/工件 API、核心测试 | 是 |
| `experiments/` | `org.workflowsim:workflowsim-experiments:1.0` | 示例、教程、P7 参考实验、研究驱动及测试 | 是 |

实验模块依赖核心模块。需要只检查核心时，显式使用 `-pl :workflowsim`；运行实验入口时
使用 `-pl :workflowsim-experiments -am`，让 Reactor 同时构建其核心依赖。

## 命令速查

| 任务 | 命令 |
| --- | --- |
| 编译两个模块 | `mvn compile` |
| 仅编译核心 | `mvn -pl :workflowsim compile` |
| 两个模块的单元/语义测试 | `mvn test` |
| 仅核心单元/语义测试 | `mvn -pl :workflowsim test` |
| 两个模块完整验证 | `mvn verify` |
| 仅核心完整验证 | `mvn -pl :workflowsim verify` |
| 两个模块清理后完整验证 | `mvn clean verify` |
| 清理两个模块的构建输出 | `mvn clean` |
| 仅清理核心构建输出 | `mvn -pl :workflowsim clean` |
| 核心 API 文档及验证 | `mvn -pl :workflowsim -Pjavadoc verify` |

编译输出分别在 `simulator/target/classes/` 和 `experiments/target/classes/`。
`mvn clean` 清理所选模块的构建目录，不会清理任意自定义实验输出目录或已归档证据。

## 测试与质量门禁

Surefire 在 `test` 阶段运行单元/语义测试，排除 `*IntegrationTest.java`；Failsafe
在 `integration-test`/`verify` 阶段运行这些集成测试。完整研究验证使用 `mvn verify`。

```bash
# 一个核心单元测试类
mvn -pl :workflowsim -Dtest=TaskCostMatrixTest test

# 一个核心单元测试方法
mvn -pl :workflowsim \
  -Dtest=TaskCostMatrixTest#getCostSecondsReturnsRegisteredEntries test

# 核心单元测试 + 指定集成测试，并完成 verify 门禁
mvn -pl :workflowsim -Dit.test=SimulationEvidenceIntegrationTest verify
```

测试数量随代码变化，以本次构建的 Surefire/Failsafe 报告为准。报告位于各模块的
`target/surefire-reports/` 与 `target/failsafe-reports/`，不要用历史测试总数判断当前构建
是否完整。

JaCoCo 在 `verify` 阶段检查单元测试覆盖率。阈值由
[核心 POM](../../simulator/pom.xml) 与[实验 POM](../../experiments/pom.xml) 声明；
当前 instruction/branch 下限分别为核心 `0.54/0.48`、实验 `0.39/0.42`（R10 随实测上调）。
报告输出在各模块 `target/site/jacoco/`（单元）及 `target/site/jacoco-it/`（集成）。

```bash
open simulator/target/site/jacoco/index.html
```

[CI 配置](../../.github/workflows/ci.yml) 对推送/PR 到 `main` 使用 JDK 17 执行
`mvn -B verify`，默认覆盖两个模块。测试引用的五个 WfFormat/WfInstances 小型输入已通过
[忽略规则](../../.gitignore) 的白名单纳入版本库；缺失必需输入会失败，相关测试不再因
语料缺失跳过。完整的大型语料仍需另行准备，见[数据集说明](../../datasets/README.md)。

## API 文档

```bash
mvn -pl :workflowsim -Pjavadoc verify
open simulator/target/reports/apidocs/index.html
```

`javadoc` profile 生成并检查维护中的 Java API 注释；上游 CloudSim 源码注释不在本项目
维护的 Javadoc 检查范围内。

## 运行示例与代码配置实验

历史示例保留 `org.workflowsim.examples.*` 类名和从项目根解析 `datasets/...` 的路径约定。

```bash
# 默认 DAX 示例
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.WorkflowSimBasicExample1 \
  compile exec:java

# 使用 SimulationConfig 的 HEFT/CPOP 论文算例
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.HeftPaperReproductionExperiment \
  compile exec:java

# 使用仓库自带的 WfFormat 输入
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json" \
  compile exec:java
```

自建实验类的方法见[快速上手](QUICK_START.md)和[代码配置指南](CODE_CONFIG_EXPERIMENTS.md)。
旧 `MyConfigurableExperiment` 及其 CSV/HTML 写器已删除，不是可运行入口。

完整 WfInstances 语料已准备到本地时，可以额外运行解析/DAG 校验：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
  -Dexec.args="datasets/wfinstances/v1.5" \
  compile exec:java
```

该命令验证的是传入目录实际存在的文件；标准检出中的小型输入集合不能代表完整语料。

## P7 冻结参考基线

批量入口为 `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`。为明确运行位置，
传入两个参数：绝对数据集根目录，以及绝对且为空的输出目录。

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/p7-output" \
  compile exec:java
```

逻辑输入名如 `dax/epigenomics/n100/Epigenomics_100.dax` 相对于数据集根目录。完整矩阵、
输入哈希与证据约束见[P7 协议](../experiments/reference-baselines/P7_PROTOCOL.md)。

## 工件与只读验证器

`ExperimentArtifactWriter.write(report, outputDirectory, runId)` 写出 manifest、metrics 和
事件流三个相互引用的工件。当前 manifest 为 `workflowsim-experiment-manifest-v4`，
包含工作流到达时刻、任务成本矩阵、网络拓扑和数据移动语义；其 provenance 仍为
`workflowsim-provenance-v3`。校验器兼容历史 manifest v2/v3，但历史格式不能据此视为包含
v4 新增的完整配置。metrics schema 仍为 v2，事件 schema 仍为 v1。

```bash
# 验证单次运行的完整工件包
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentArtifactValidator \
  -Dexec.args="/absolute/output/run.manifest.json" \
  compile exec:java

# 验证 P7 index 及其引用的工件包
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/output/p7-baseline-index.json" \
  compile exec:java

# 验证通用 campaign index
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  -Dexec.args="/absolute/output/experiment-campaign-index.json" \
  compile exec:java
```

验证器只读目标证据，命令中的 Maven 编译仍会生成构建输出。验证成功说明工件满足对应版本
的结构、引用、哈希与交叉约束，不代表来源平台校准或算法优越性。

## 常见问题

- **示例找不到类**：从根 POM 导入两个模块并重新加载 Maven；运行实验类时选择实验模块
  classpath。无需启用额外的 `experiments` profile，详见[IDEA 配置](IDEA_SETUP.md)。
- **工作流文件不存在**：核对当前目录和[数据集路径](DATASETS.md)。经典 DAX 位于
  `datasets/dax/<family>/n<规模>/`，旧 `config/dax/` 路径已停用。
- **只想快速检查核心**：使用 `mvn -pl :workflowsim test`；需要核心集成与覆盖率门禁时
  使用 `mvn -pl :workflowsim verify`；提交前运行默认双模块的 `mvn verify`。

工程依赖由 POM 管理，不依赖早期发行包的 `lib/`、`bin/` 或发布说明文件。
