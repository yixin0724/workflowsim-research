# 工作流输入与数据集

本目录包含经典 Pegasus DAX、WfGen 合成 WfFormat JSON，以及 WfInstances 真实执行派生 WfFormat JSON。后两者使用相同 JSON 格式，但来源与证据边界不同，不应混为同一个 workload population。

## 选择输入

| 需要 | 选择 | 使用边界 |
| --- | --- | --- |
| 从随仓库提供的科学工作流开始 | `dax/` 经典输入 | 按所选模型检查 DAG、文件命名和大小约束；存在文件不代表所有算法均可使用 |
| 使用合成工作流或扩展规模扫描 | `wfformat/` WfGen 输入 | 标准检出只保证一个 Montage 输入；其他 recipe/规模需单独准备 |
| 研究真实执行派生的图结构 | `wfinstances/v1.5/` | 标准检出有四个小型输入；不是来源平台 trace replay |

运行入口见[快速开始](<../docs/getting-started/QUICK_START.md>)。Workbench 配置的 `workflowPaths` 相对于配置文件所在目录；Java 示例从项目根运行时使用 `datasets/...` 路径。接受数据集根参数的研究驱动应使用其明确规定的路径契约，不猜测项目位置。

## 标准检出范围

经典五大工作流的 **20 个 DAX 输入**随仓库提供，另有独立的 [HEFT/CPOP 论文算例](<dax/heft/heft-paper-example.dax>)，不计入这 20 个输入。

[忽略规则](<../.gitignore>)明确保留以下 **1 个 WfFormat、4 个 WfInstances** 输入：

```text
wfformat/montage/n100/montage-100-000.json
wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json
wfinstances/v1.5/nextflow/bacass-dirt02-001.json
wfinstances/v1.5/pegasus/srasearch/srasearch-chameleon-10a-001.json
wfinstances/v1.5/pegasus/montage/montage-chameleon-2mass-005d-001.json
```

解析器交叉校验、小型端到端矩阵和 CLI 冒烟测试依赖这些 JSON 输入；缺失必需文件时测试失败。默认 `mvn verify` 验证核心与实验两个模块，但不能把小型输入门禁推广为整个上游语料已认证。

## DAX：经典 Pegasus 工作流

[经典输入索引](<dax/INDEX.json>)中的路径相对于 `datasets/dax/`。

| family | 应用 | 规模 |
| --- | --- | --- |
| `cybershake` | CyberShake 地震危险性 | 30 / 50 / 100 / 1000 |
| `epigenomics` | Epigenomics 生物信息 | 24 / 46 / 100 / 997 |
| `inspiral` | LIGO Inspiral 引力波 | 30 / 50 / 100 / 1000 |
| `montage` | Montage 天文图像拼接 | 25 / 50 / 100 / 1000 |
| `sipht` | SIPHT sRNA 预测 | 30 / 60 / 100 / 1000 |

```text
路径模式：datasets/dax/<family>/n<tasks>/<Family>_<tasks>.dax
示例：    datasets/dax/epigenomics/n100/Epigenomics_100.dax
```

经典输入来自 Pegasus 工作流表征，主要使用 DAX 2.1；论文算例声明 DAX 3.3，以输入自身声明为准。

### 已知数据修复

[Epigenomics_997.dax](<dax/epigenomics/n997/Epigenomics_997.dax>) 的上游文件含 209 个负文件 size 和 57 个负 job runtime。当前版本仅去除错误负号，原始未修复版本保留为 [Epigenomics_997.dax.orig](<dax/epigenomics/n997/Epigenomics_997.dax.orig>)，供来源审计；修复未改变 DAG 结构和数值绝对值。

## 可选完整 JSON 语料

完整 WfFormat/WfInstances 语料不全部纳入版本库。下列数量描述已有本地快照的来源范围，不保证每次克隆都会获得，也不是当前全量仿真认证。

### WfGen 合成输入

已有完整集合的解析记录覆盖 42 个合成实例，来源为外部 Python 工程的 `wfforge.data.wfgen_dataset` 调用 WfCommons WfGen 生成。结构、runtime 和文件大小来自学习到的分布，不是一次生产执行的原始观测。

该集合覆盖 Montage、Epigenomics、BLAST、1000Genome 的 100/400/1000 规模，以及 Seismology 的 400/1000 规模。扩展前核对本地文件、索引、生成参数和哈希，不假定所有 recipe 具备相同规模。

```text
datasets/wfformat/<recipe>/n<tasks>/<recipe>-<tasks>-<idx>.json
```

完整生成语料可附索引和同名 `.manifest.json`，其中记录 recipe、请求规模、种子与生成参数；它们不是 `ExperimentArtifactWriter` 写出的仿真实验 manifest，schema 不能混用，也不保证随标准检出提供。

### WfInstances 1.5

已有完整快照的结构验证记录为 180 个 JSON 输入、118637 个任务、244573 条边：Makeflow 30 个、Nextflow 15 个、Pegasus 135 个。它只说明对应快照可完成解析/DAG 转换，不表示所有输入都通过标准仿真管线。

```text
datasets/wfinstances/v1.5/<runtime-system>/<application>/<instance>.json
```

解析器使用任务图、文件大小和 runtime；来源机器、核数、内存、命令、时间戳和 observed makespan 不会被自动重放。报告应明确所用输入哈希和模拟参数，不能仅凭来源真实就声称在真实生产平台验证了算法性能。

## 使用与检查命令

从项目根目录运行仓库自带输入：

```bash
# WfGen 合成输入
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json"

# WfInstances 真实执行派生输入
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json"

# 只检查传入目录实际存在的 JSON：解析/DAG 结构，不运行仿真
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
  -Dexec.args="datasets/wfinstances/v1.5"
```

最后一条命令不会获取缺失语料；输出数量应与实际准备的文件清单核对，而不是默认期待完整快照数量。

## 转换、兼容性与证据

WfFormat 1.5嵌套结构中的主要字段如下；解析器保留声明版本，不把执行记录自动转换为资源设置：

| 字段 | 转换与检查 |
|---|---|
| `schemaVersion` | 输入报告中的声明版本 |
| `workflow.specification.tasks[].id/name` | 内部Task身份和类型 |
| `parents` / `children` | 两侧依赖必须一致，拒绝缺失端点、单侧关系和环 |
| `inputFiles` / `outputFiles` | 必须引用已声明文件 |
| `workflow.specification.files[].id/sizeInBytes` | 文件键及有限非负字节大小 |
| `workflow.execution.tasks[].id/runtimeInSeconds` | 每个specification Task都必须有runtime |

随仓库提供的四个WfInstances输入具有固定身份与哈希，小型端到端校验由[输入矩阵定义](<../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7WfInstancesPilotMatrix.java>)和[矩阵测试](<../experiments/src/test/java/org/workflowsim/experiments/reference/p7/P7WfInstancesPilotMatrixTest.java>)维护；它不是对来源平台时间线的重放。

输入 runtime 以秒表示，按下式转换为模型计算长度：

```text
max(100, floor(runtimeSeconds * runtimeReferenceMips * runtimeScale))
```

默认 `runtimeReferenceMips=1000.0`、`runtimeScale=1.0`。MI 是模拟计算量，不是硬件无关的实际 CPU 用量。显式任务成本矩阵按 Task/VM 秒数和目标 MIPS 舍入为正整数 MI；原始归一化长度保留为 `lengthMi`，当前尝试记录 `effectiveExecutionLengthMi`。计算 MI×PE×1,000,000 必须可用 signed long 表示，非正舍入和溢出显式失败。

文件大小是有限非负的字节当量，流体模型保留可表示的小数字节。DAX 禁止 DOCTYPE/外部实体；JSON 父子关系必须一致，缺失端点、单侧依赖或环会被拒绝。

DAX 的重复 INPUT 尺寸沿用首个登记值并记录警告；这不是任意模型都接受同名冲突的保证。LOCAL 规划器要求一致的文件大小；coherent 文件计划还要求可判定的生产者与控制因果。具有重复输出名的输入可能适用于普通在线调度，却不满足这些更严格模型，见[算法目录](<../docs/algorithms/CATALOG.md>)与[模型能力说明](<../docs/advanced/DATAFLOW_CAPABILITY_MATRIX.md>)。

实验 manifest v4 记录输入哈希、到达时刻、任务成本矩阵和平台声明，provenance 为 v3。保留原始输入与配置，不能把解析成功、旧证据可读或字段校验通过解释为来源平台校准。

`wfforge.data.load_workflow` 属于外部 Python 工程，不是本 Maven 工程的运行依赖；本项目直接通过 Java 解析器读取工作流。
