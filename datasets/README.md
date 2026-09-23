# WorkflowSim 工作流数据集

本目录组织三个来源不同的数据集合：经典 Pegasus DAX、WfGen 合成 WfFormat JSON，以及
WfInstances 真实执行派生 WfFormat JSON。后两者使用相同 JSON 格式，但来源与证据边界不同。

P7/reference 驱动接收本目录的**绝对路径**，再解析 `dax/...`、`wfformat/...`、
`wfinstances/...` 逻辑路径。历史示例以项目根为工作目录，使用 `datasets/...` 相对路径。

## 标准检出与完整语料

经典 DAX 与质量门禁所需的小型 JSON 输入随仓库提供。完整 WfFormat/WfInstances 语料不全部
纳入版本库；不能把某台机器上的完整本地快照规模当成每次克隆都会得到的文件数量。

[忽略规则](../.gitignore) 明确保留以下五个 JSON 输入：

```text
wfformat/montage/n100/montage-100-000.json
wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json
wfinstances/v1.5/nextflow/bacass-dirt02-001.json
wfinstances/v1.5/pegasus/srasearch/srasearch-chameleon-10a-001.json
wfinstances/v1.5/pegasus/montage/montage-chameleon-2mass-005d-001.json
```

解析器交叉校验、WfInstances 试点矩阵和 CLI 冒烟测试依赖这些输入。缺失必需文件时测试失败，
不再通过自动跳过掩盖缺失。默认 `mvn verify` 构建并验证核心与实验两个模块。

完整本地语料按以下布局组织；其中 JSON 索引与生成器 manifest 仅在相应完整语料准备后存在：

```text
datasets/
├── dax/
│   ├── INDEX.json
│   ├── <family>/n<num_tasks>/<Family>_<n>.dax
│   └── heft/heft-paper-example.dax
├── wfformat/
│   ├── INDEX.json
│   └── <recipe>/n<num_tasks>/<recipe>-<n>-<idx>.json
│                                  # 完整生成语料可附同名 .manifest.json
└── wfinstances/
    └── v1.5/<runtime-system>/<application>/<instance>.json
```

WfInstances 保留来源执行系统与应用目录，不使用本项目的规模分档 catalog。

## 使用仓库自带输入

以下命令从项目根运行，不需要额外 Maven profile：

```bash
# WfGen 合成输入
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json" \
  compile exec:java

# WfInstances 真实执行派生输入
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json" \
  compile exec:java
```

## DAX：经典 Pegasus 输入

[dax/INDEX.json](dax/INDEX.json) 列出经典五大工作流的 20 个输入，路径相对于 `datasets/dax/`。
另有 [HEFT/CPOP 论文算例](dax/heft/heft-paper-example.dax)，不应把它混入经典语料数量。

| family | 应用 | 规模 |
| --- | --- | --- |
| `cybershake` | CyberShake | 30 / 50 / 100 / 1000 |
| `epigenomics` | Epigenomics | 24 / 46 / 100 / 997 |
| `inspiral` | LIGO Inspiral | 30 / 50 / 100 / 1000 |
| `montage` | Montage | 25 / 50 / 100 / 1000 |
| `sipht` | SIPHT | 30 / 60 / 100 / 1000 |

经典输入来自 Pegasus 工作流表征，原 `config/dax/` 已迁入本树。经典集合主要使用 DAX 2.1，
论文算例声明 DAX 3.3；输入版本以文件自身声明为准。

## WfGen：合成 WfFormat JSON

完整本地集合的历史解析验证覆盖 42 个合成实例，来源为 `wfforge.data.wfgen_dataset`
调用 WfCommons WfGen 生成。结构、runtime 和文件大小来自学习到的分布，不是一次生产执行
的原始观测。标准检出只保证前述 Montage 小型输入。

完整语料曾覆盖 Montage、Epigenomics、BLAST、1000Genome 的 100/400/1000 规模，以及
Seismology 的 400/1000 规模。扩展实验应核对本地实际文件、索引、生成参数与输入哈希，
不能假定所有 recipe 都具备相同规模。

生成器附带的同名 manifest 描述 recipe、请求规模、种子与生成参数；它不是
`ExperimentArtifactWriter` 产生的仿真实验 manifest，二者的 schema 不可混用。

## WfInstances 1.5：真实执行派生 JSON

完整本地快照的历史结构验证记录为 180 个 JSON 输入、118637 个任务、244573 条边：
Makeflow 30 个、Nextflow 15 个、Pegasus 135 个。该记录说明当时完整快照可完成解析/DAG
转换，不代表标准检出包含全部输入，也不代表所有输入都通过了标准仿真管线认证。

四个固定输入的端到端试点、哈希及解释边界见
[WfInstances 试点说明](../docs/advanced/WFINSTANCES_PILOT.md)。解析器使用任务图、文件大小
和任务 runtime；来源机器、核数、内存、命令、时间戳和 observed makespan 不会被自动重放。

需要核对本地已准备的完整目录时运行：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
  -Dexec.args="datasets/wfinstances/v1.5" \
  compile exec:java
```

该命令只遍历传入目录实际存在的文件；报告数量小于历史快照规模时，应核对语料完整性。
验证后可用 `mvn clean` 清理两个模块的构建输出，保留所需的验证记录。

## 输入转换与证据

输入 runtime 的单位为秒；标准运行以显式模型参数转换为计算长度：

```text
max(100, floor(runtimeSeconds * runtimeReferenceMips * runtimeScale))
```

默认 `runtimeReferenceMips=1000.0`、`runtimeScale=1.0`。MI 是模拟的计算工作量，不能解释为
来源硬件无关的实际 CPU 用量。显式 `TaskCostMatrix` 则按 Task/VM 执行秒数覆盖成本估计，
运行时按目标 VM MIPS 舍入为正整数 MI。

JSON 文件大小以字节表示，父子关系必须一致；缺失端点、单侧依赖或环会被拒绝。
实验 manifest v4 记录输入哈希、到达时刻、任务成本矩阵与平台拓扑，provenance 保持 v3；
历史 manifest v2/v3 继续可读，但不能视作带有 v4 新增的完整配置。

## 已知数据修复

[Epigenomics_997.dax](dax/epigenomics/n997/Epigenomics_997.dax) 的上游文件含 209 个负文件
size 和 57 个负 job runtime。当前版本仅去除错误负号，原始未修复版本保留为
[Epigenomics_997.dax.orig](dax/epigenomics/n997/Epigenomics_997.dax.orig)，供来源审计；
修复未改变 DAG 结构和数值绝对值。

## 外部 Python 加载器

`wfforge.data.load_workflow` 属于另一个 Python 工程，不是本 Java Maven 工程的运行依赖。
本项目直接通过 Java 解析器读取工作流；不要据外部加载器的能力推断本项目具备 trace replay。
