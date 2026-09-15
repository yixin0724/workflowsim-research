# WorkflowSim 构建与运行指南

## 快速导航

| 任务 | 命令 | 用时估计 |
|------|------|----------|
| [编译](#编译) | `mvn compile` | ~5秒 |
| [运行核心测试](#测试) | `mvn test` | ~7秒 |
| [完整验证](#完整验证) | `mvn verify` | ~15秒 |
| [完整验证（含实验）](#完整验证) | `mvn verify` | ~2分钟 |
| [运行历史示例](#运行历史示例) | `mvn -pl :workflowsim-experiments -am -Dexec.mainClass=... compile exec:java` | ~10秒 |
| [执行 P7 基线](#p7-冻结参考基线) | `mvn ... P7BaselineExecutor` | ~分钟级 |
| [清理](#清理) | `mvn clean` | ~2秒 |
| [生成 API 文档](#api-文档) | `mvn -Pjavadoc verify` | ~20秒 |

---

## 工程结构

WorkflowSim 使用一个 Maven Reactor 父工程和两个物理模块：

| 模块 | Maven 坐标 | 职责 | 默认是否构建 |
| --- | --- | --- | --- |
| `simulator/` | `org.workflowsim:workflowsim:1.0` | 可复用模拟器核心、维护的 API、通用研究运行/工件 API 与核心测试 | ✅ 是 |
| `experiments/` | `org.workflowsim:workflowsim-experiments:1.0` | 历史示例、教学、冻结参考基线（P7）、未来研究实验及其测试 | ❌ 否，默认构建 |

**设计原则**：下游使用者可以只依赖稳定的 `simulator/` 坐标，而研究代码、教程和参考结果仍能在同一个 Reactor 中被完整验证。实验模块依赖核心模块，核心模块绝不反向依赖实验模块。

**目录布局**：

```text
WorkflowSim-1.0/
├── pom.xml                         # Reactor 父工程：默认只包含 simulator
├── simulator/
│   └── src/
│       ├── main/java/              # 核心 WorkflowSim、研究 API、CloudSim 源码
│       └── test/                   # 单元、语义与集成测试
├── experiments/
│   ├── pom.xml                     # 实验模块（默认构建）
│   ├── src/                        # 示例、教程、P7 基线、研究代码及测试
│   ├── reference/p7/               # P7 参考实验说明
│   └── studies/                    # 新研究协议/矩阵目录
├── datasets/                       # DAX、WfFormat、WfInstances 数据集
└── docs/                           # 构建、算法、实验协议文档
```

---

## 环境要求

- **JDK 17+**（构建在现代 JDK 上运行，但产物兼容 Java 8 API）
- **Maven 3.6.3+**

验证环境：

```bash
java -version   # 应显示 17 或更高
mvn -v          # 应显示 3.6.3 或更高
```

**所有命令均从仓库根目录 `WorkflowSim-1.0/` 执行**，不要直接用 `-f experiments/pom.xml` 构建实验模块。

---

## 编译

```bash
# 仅编译核心模块
mvn compile

# 编译核心 + 实验模块
mvn compile
```

**输出位置**：
- `simulator/target/classes/` - 核心 class 文件
- `experiments/target/classes/` - 实验代码 class 文件

---

## 测试

### 快速测试

```bash
# 运行核心单元/语义测试（~7秒，243个测试）
mvn test

# 两个模块的快速测试
mvn test

# 运行指定测试类
mvn test -Dtest=SimulationRunnerIntegrationTest

# 运行指定测试方法
mvn test -Dtest=SimulationRunnerIntegrationTest#supportedAlgorithmCompletesAndProducesVerifiableEvidence
```

`mvn test` 只运行 Surefire 阶段的单元/语义测试，**不包括** `*IntegrationTest.java`（它们在 `verify` 阶段由 Failsafe 运行）。

### 完整验证

```bash
# 核心模块完整验证（单元 + 集成，~15秒）
mvn verify

# 完整工程质量门禁（核心 + 实验，~2分钟，358个测试 + 覆盖率阈值检查）
mvn verify
```

**测试统计**：
- 核心单元测试：243 个（Surefire，含千任务级规模回归、配对显著性检验、链路争用流体模型、故障分布 KS 检验与 RL 策略契约）
- 核心集成测试：52 个（Failsafe，`*IntegrationTest.java`，含多 seed 显著性验收与 RL episode 端到端验收）
- 实验模块测试：63 个（17 单元 + 46 集成）
- **总计：358 个测试**

**覆盖率门禁（R3）**：JaCoCo `check-unit-coverage` 在 verify 阶段对单元测试覆盖率
（`target/jacoco.exec`）强制 BUNDLE 级下限——simulator instruction ≥ 0.48 /
branch ≥ 0.43，experiments ≥ 0.23 / ≥ 0.25（棘轮值，只升不降；实测 2026-09-14：
48.51%/43.25%、24.03%/26.81%）。报告输出在 `target/site/jacoco/`（HTML+XML）与
`target/site/jacoco-it/`（集成覆盖率）。

CI：`.github/workflows/ci.yml` 在每次 push/PR 到 `main` 时以 JDK 17 执行完整门禁；
`wfformat`/`wfinstances` 大型语料未纳入版本库，相关测试在语料缺失时自动跳过（本地
有语料时完整执行）。

**研究结论前的完整本地门禁应使用 `verify`**，不是只用 `test`。任何依赖示例、P7 参考矩阵或教程语料的门禁都必须使用 `mvn verify`。

### 测试覆盖率

```bash
# 生成 JaCoCo 覆盖率报告
mvn verify jacoco:report

# 查看报告
open simulator/target/site/jacoco/index.html
```

**输出位置**：
- `simulator/target/site/jacoco/` - 核心模块覆盖率
- `experiments/target/site/jacoco/` - 实验模块覆盖率

---

## 清理

```bash
# 清理核心模块（默认）
mvn clean

# 清理所有模块（默认行为）
mvn clean

# 清理后重新构建
mvn clean verify
```

**清理范围**：`target/`、JaCoCo 报告、临时 manifest、事件日志和未明确保留的实验输出都是中间文件，验证后必须清理。

---

## API 文档

```bash
# 生成并检查核心 API 文档
mvn -Pjavadoc verify

# 查看文档
open simulator/target/reports/apidocs/index.html
```

该 profile 检查 `simulator/` 的维护源码（中文 Javadoc）并生成 API 文档。它不生成或检查上游 CloudSim API 注释。

---

## 运行历史示例

示例已移动到 `experiments/` 模块，但 FQCN 保持不变（`org.workflowsim.examples.*`）。它们有历史相对数据路径契约，因此从根目录启动：

```bash
# 默认 DAX 示例（WorkflowSimBasicExample1，Epigenomics_100）
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.WorkflowSimBasicExample1 \
  compile exec:java


# 代码配置式实验模板（推荐）
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.MyConfigurableExperiment \
  compile exec:java

# WfCommons WfFormat JSON 示例
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/blast/n100/blast-100-000.json" \
  compile exec:java

# WfInstances 1.5 结构/DAG 交叉校验（较慢，不是常规测试）
mvn -pl :workflowsim-experiments -am -DskipTests \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
  -Dexec.args="datasets/wfinstances/v1.5" \
  compile exec:java
```

**注意**：历史示例适合教学、兼容性探索和输入验证，不会自动获得研究证据资格。研究结论应使用 `SimulationConfig`、`PlatformProfile`、`SimulationRunner` 和明确的工件写入/验证流程。

---

## P7 冻结参考基线

P7 是实验模块中的冻结参考实验。唯一的批量入口为 `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`，接收两个参数：

1. **绝对数据集根目录**：包含 `dax/`、`wfformat/`、`wfinstances/` 的目录（通常是 `/absolute/path/to/WorkflowSim-1.0/datasets`）
2. **绝对且为空的输出目录**（执行器拒绝覆盖非空目录）

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/p7-output" \
  compile exec:java
```

P7 的内部逻辑输入名相对于第一个参数，例如 `dax/epigenomics/n100/Epigenomics_100.dax`。完整矩阵、输入哈希、证据 schema 和统计边界见 [`../experiments/reference-baselines/P7_PROTOCOL.md`](../experiments/reference-baselines/P7_PROTOCOL.md)。

---

## 只读验证器

以下命令只读取目标工件，不生成或改写文件：

```bash
# 验证单次运行的 manifest/metrics/events 工件
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentArtifactValidator \
  -Dexec.args="/absolute/output/scenario/manifest.json" \
  compile exec:java

# 验证 P7 冻结基线 index 及其引用的全部 evidence bundle
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/output/p7-baseline-index.json" \
  compile exec:java

# 验证 ExperimentCampaignArtifactWriter 产生的 campaign index
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  -Dexec.args="/absolute/output/experiment-campaign-index.json" \
  compile exec:java
```

**验证边界**：验证成功只证明工件的结构、路径、哈希、metrics/manifest 一致性、事件序列和当前协议交叉约束符合契约；它不等价于现实平台校准、追踪重放或算法优越性证明。

---

## 依赖说明

| 依赖 | 坐标 | 用途 |
| --- | --- | --- |
| JDOM2 | `org.jdom:jdom2:2.0.6` | DAX XML 解析 |
| Commons Math | `org.apache.commons:commons-math3:3.2` | 故障分布抽样 |
| Gson | `com.google.code.gson:gson:2.10.1` | WfCommons JSON 解析 |
| JUnit Jupiter | `org.junit.jupiter:junit-jupiter:5.10.2` | 测试框架 |

工程依赖完全由 POM 管理，构建不依赖早期发行包中的 `lib/`、`bin/` 或 `release_notes.txt`。

---

## 数据集路径

项目内 `datasets/` 是数据集的物理位置。历史示例使用从项目根开始的相对路径 `datasets/...`；P7/reference 代码则只接受显式的绝对数据集根目录，并在其下解析 `dax/...`、`wfformat/...`、`wfinstances/...`。这两个契约不可混用。

原 `config/dax/` 的历史文件已迁入 `datasets/dax/<family>/n<规模>/`：

| 已废弃路径 | 当前路径 |
| --- | --- |
| `config/dax/Montage_100.xml` | `datasets/dax/montage/n100/Montage_100.dax` |
| `config/dax/Sipht_1000.xml` | `datasets/dax/sipht/n1000/Sipht_1000.dax` |
| `config/dax/Epigenomics_24.xml` | `datasets/dax/epigenomics/n24/Epigenomics_24.dax` |

数据集的详细范围、WfInstances 解释边界和已知瑕疵见 [`DATASETS.md`](DATASETS.md)。

---

## 常见问题

### 为什么 `mvn test` 没有运行集成测试？

`*IntegrationTest` 由 Maven Failsafe 在 `integration-test`/`verify` 阶段运行。使用 `mvn verify` 运行完整测试。

### 为什么示例找不到类？

示例在 `experiments/` 模块，现已默认构建。若找不到类，先执行 `mvn compile`。

### 如何只清理核心模块？

`mvn clean` 默认清理所有模块。

### 构建速度慢？

- 单独 `mvn test`（7秒）适合快速验证
- 单独 `mvn verify`（15秒）适合核心完整验证
- `mvn verify`（2分钟）仅在提交前/发布前执行

---

## 下一步

- **快速上手**：[`QUICK_START.md`](../getting-started/QUICK_START.md) - 5分钟运行第一个仿真
- **数据集选择**：[`DATASETS.md`](DATASETS.md) - 如何选择合适的工作流输入
- **算法理解**：[`ALGORITHMS.md`](ALGORITHMS.md) - 三类算法的底层原理
