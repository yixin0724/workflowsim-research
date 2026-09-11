# WorkflowSim 工作流数据集

所有可由本项目使用的工作流输入集中于本目录。当前有 **三个数据集合**，采用两种
文件格式：Pegasus DAX XML，以及 WfCommons WfFormat JSON。集合的来源、目录布局和
证据范围并不相同，实验报告不得将它们视为一个可直接混合的 workload population。

本目录也是 P7/reference 与未来研究驱动所需的“数据集根目录”。这类驱动必须接收此目录
的**绝对路径**，再在其下解析 `dax/...`、`wfformat/...`、`wfinstances/...` 逻辑路径；
不要传项目根目录。历史示例保留以项目根为当前工作目录的 `datasets/...` 相对路径契约，
两者不可混用。

```text
datasets/
├── README.md
├── dax/                         # 经典 Pegasus DAX 2.1 XML，20 个输入
│   ├── INDEX.json               # 本项目维护的相对路径 catalog
│   └── <family>/n<num_tasks>/<Family>_<n>.dax
├── wfformat/                    # WfGen 生成的合成 WfFormat JSON，42 个输入
│   ├── INDEX.json               # 本项目维护的相对路径 catalog
│   └── <recipe>/n<num_tasks>/<recipe>-<n>-<idx>.json
│                                  # 每个输入附有同名 .manifest.json
└── wfinstances/
    └── v1.5/                    # WfInstances 上游语料快照，180 个 WfFormat JSON
        └── <runtime-system>/<application>/<instance>.json
```

`dax/` 和 `wfformat/` 使用本项目维护的 `INDEX.json`，并按 family/recipe 与任务规模
分档。`wfinstances/v1.5/` 是按来源执行系统和应用组织的版本化上游语料快照，不存在
本项目 `INDEX.json`，也不应被描述为与前两者对称的规模分档集合。

## 各集合的模拟器支持现状

| 集合与格式 | WorkflowSim 当前状态 | 已验证范围 |
|---|---|---|
| DAX 2.1 XML (`dax/`) | `WorkflowParser` 原生解析，经典示例默认输入。 | 目录 catalog 中的 20 个 DAX 输入。 |
| WfGen WfFormat JSON (`wfformat/`) | 所有 `.json` 由 `WorkflowParser` 分发给 `WfCommonsJsonParser`，严格转换任务、文件与 DAG。 | 42 个合成实例完成解析验证与独立交叉对账。 |
| WfInstances 1.5 WfFormat JSON (`wfinstances/v1.5/`) | 通过同一个 JSON 解析器进行严格结构转换，作为抽象工作流输入。 | 180 个文件已完成解析/DAG 结构验证；只有 4 个 SHA-256 固定试点输入完成标准管线端到端认证。 |

JSON 输入可通过实验模块中的历史/教程示例运行。例如：

```bash
# WfGen 合成 WfFormat 输入
mvn -Pexperiments -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json" \
  compile exec:java

# WfInstances 真实执行派生输入的抽象转换示例
mvn -Pexperiments -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json" \
  compile exec:java
```

WfInstances 文件包含来源执行的机器、核数、内存、命令、时间戳和 observed makespan 等
字段；当前 WorkflowSim 只转换任务图、文件大小和任务 runtime，不会重放来源平台或预测
其观测 makespan。完整字段契约、四个固定试点及其输入哈希见
[`docs/advanced/WFINSTANCES_PILOT.md`](../docs/advanced/WFINSTANCES_PILOT.md)。

## dax/：经典 Pegasus DAX XML

这些输入来自 Pegasus 2008 年表征（Bharathi et al.）的经典五大工作流，可作为与既有
WorkflowSim 和相关调度研究进行受控比较的输入来源。原始 WorkflowSim 发行包的
`config/dax/` 已迁入本树并统一按 family/规模组织。

| family | 应用 | 当前规模 |
|---|---|---|
| `cybershake` | CyberShake | 30 / 50 / 100 / 1000 |
| `epigenomics` | Epigenomics | 24 / 46 / 100 / 997 |
| `inspiral` | LIGO Inspiral | 30 / 50 / 100 / 1000 |
| `montage` | Montage | 25 / 50 / 100 / 1000 |
| `sipht` | SIPHT | 30 / 60 / 100 / 1000 |

[`dax/INDEX.json`](dax/INDEX.json) 中的路径均相对于 `datasets/dax/`，并只列出当前实际
保留的 20 个 DAX 文件。

## wfformat/：WfGen 合成 WfFormat JSON

这些实例由 `wfforge.data.wfgen_dataset` 基于 WfCommons WfGen 生成。它们是**合成
profile**：结构、runtime 和文件大小来自被学习的分布，不是某一次生产执行的原始测量。
每个实例的同名 `.manifest.json` 记录 recipe、请求规模、随机种子、缩放因子和输出路径。

| recipe | 应用 | 领域 | 当前规模 |
|---|---|---|---|
| `montage` | Montage | 天文 | 100 / 400 / 1000 |
| `epigenomics` | Epigenomics | 生物信息 | 100 / 400 / 1000 |
| `blast` | BLAST | 生物信息 | 100 / 400 / 1000 |
| `1000genome` | 1000Genome | 生物信息 | 100 / 400 / 1000 |
| `seismology` | Seismic Cross Correlation | 地震学 | 400 / 1000 |

当前规模分档为小型 `100`、中型 `400`、大型 `1000`；个别 recipe 的实际可用规模以
[`wfformat/INDEX.json`](wfformat/INDEX.json) 为准。新增或再生成 WfGen 输入时，应同时
更新该索引和对应 manifest，而不是假设所有 recipe 都有相同规模。

## wfinstances/v1.5/：真实执行派生 WfFormat JSON

WfInstances 是开放的生产工作流执行实例集合。本地 `v1.5/` 是保留上游目录和说明的
版本化快照，当前包含 180 个 JSON 输入：

| 来源执行系统 | 当前输入数 | 工作流应用 |
|---|---:|---|
| Makeflow | 30 | BLAST、BWA |
| Nextflow | 15 | nf-core pipelines |
| Pegasus | 135 | 1000Genome、Cycles、Epigenomics、Montage、Seismology、SoyKB、SRaSearch |

全量解析/DAG 验证的已记录结果为 `files=180`、`tasks=118637`、`edges=244573`。该检查只
证明本项目可严格转换当前语料的受支持字段；它不证明 trace replay、平台校准、网络预测或
算法优越性。四个冻结端到端输入的独立认证和不可主张范围见
[`docs/advanced/WFINSTANCES_PILOT.md`](../docs/advanced/WFINSTANCES_PILOT.md)。

此目录可能保留上游 README、应用说明、版本控制元数据或 CI 配置，以支持来源审计；它们
不参与本项目 Maven 构建或 Java 解析，也不是 WorkflowSim 的运行依赖。

## 输入转换与使用注意

- DAX 的 `runtime` 与 WfFormat 的 `workflow.execution.tasks[].runtimeInSeconds` 均以秒为
  输入单位。标准运行通过以下显式模型假设转换为 CloudSim MI：

  ```text
  max(100, floor(runtimeSeconds * runtimeReferenceMips * runtimeScale))
  ```

  默认 `runtimeReferenceMips=1000.0`、`runtimeScale=1.0`；这两个值可由
  `SimulationConfig` 改变，必须随研究结果记录。MI 不是毫秒，也不是硬件无关的真实 CPU
  工作量。
- WfFormat 文件大小使用字节；任务依赖来自 `parents` 与 `children`。本项目要求两侧声明
  描述相同边集合，缺失端点、单侧依赖或环都会 fail fast。
- `montage`、`epigenomics` 同时存在于 DAX 与 WfGen 两个集合，可用于受控的数据源对照；
  这种对照不应延伸为 WfInstances trace replay 或跨来源工作负载的无条件合并。
- 需要校验整个 WfInstances 语料时，运行：

  ```bash
  mvn -Pexperiments -pl :workflowsim-experiments -am -DskipTests \
    -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
    -Dexec.args="datasets/wfinstances/v1.5" \
    compile exec:java
  ```

  该命令会生成 Maven `target/` 构建输出，验证后应使用 `mvn -Pexperiments clean` 清理。

## 已知数据瑕疵与修复

`dax/epigenomics/n997/Epigenomics_997.dax` 的上游原始文件含 209 个负文件 size 和 57 个
负 job runtime。当前 `.dax` 是仅去除错误负号的修复版；原始未修复版本保留为同目录
`Epigenomics_997.dax.orig`，供来源与修复审计。修复版的 DAG 结构和数值绝对值未改变。

## 上游 wfforge 统一加载器

`wfforge.data.load_workflow` 是另一个 Python 工程的加载入口，不属于本 Java Maven 工程。
下例只说明它对 DAX 与本项目 WfGen `wfformat/` 输入的使用方式；不要据此推断当前
WorkflowSim 会用它处理 WfInstances：

```python
from wfforge.data import load_workflow

wf = load_workflow("datasets/dax/montage/n100/Montage_100.dax")
wf = load_workflow("datasets/wfformat/montage/n100/montage-100-000.json")
wf.validate()
```

其实现位于 wfforge 工程的 `src/wfforge/data/`，与本项目 Java 解析器相互独立。
