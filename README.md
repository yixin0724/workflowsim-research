# WorkflowSim

WorkflowSim 是面向科学工作流调度研究的离散事件模拟器。本项目基于 WorkflowSim/CloudSim 代码基础，提供严格输入校验、显式实验配置、可审计结果工件，以及经过语义测试的常用调度/映射算法轨道。

> **模型边界：** WorkflowSim 模拟的是一个已声明的抽象工作流执行环境。一次仿真结果不能单独主张重放 WfInstances 的原始执行、预测生产云平台或网络性能，或估算云服务商账单。

## 5 分钟快速上手

**第一次使用？** 阅读 [`docs/getting-started/QUICK_START.md`](docs/getting-started/QUICK_START.md) 在 5 分钟内运行你的第一个仿真，包含完整代码示例和常见任务速查。

**要跑自己的实验？** 阅读 [`docs/getting-started/CODE_CONFIG_EXPERIMENTS.md`](docs/getting-started/CODE_CONFIG_EXPERIMENTS.md) 学习代码配置式实验（所有参数在代码中配置，IDEA 直接右键运行，无需命令行）。⭐⭐⭐

**想理解算法？** 阅读 [`docs/getting-started/ALGORITHMS.md`](docs/getting-started/ALGORITHMS.md) 用直观语言理解三类算法的本质区别和底层原理（在线调度 vs 静态独立任务 vs 静态 DAG）。

**基础验证：**

```bash
mvn verify      # 核心测试，约 7 秒，应显示 Tests run: 243, Failures: 0
```

## 可用于什么研究

在固定工作流输入、平台规格、资源数、存储假设、随机性和指标口径后，可使用 WorkflowSim 比较同一决策层内的工作流调度算法。当前已建模的抽象语义和不能越界的解释如下：

| 已建模的抽象语义 | 使用时的边界 |
| --- | --- |
| DAG 依赖释放、任务运行长度、输入/输出文件依赖 | WfCommons JSON runtime 到 MI 的换算依赖显式参考 MIPS/缩放系数，是模型假设而非原始硬件事实。 |
| VM、Host、存储和确定性 VM-to-Host 放置 | 不建模 Host CPU 争用、VM 迁移或真实基础设施利用率。 |
| 在线 ready-Job 调度和受控静态 Task-to-VM 映射 | 不同决策层的算法不能混入同一个基线比较。 |
| 可选开销、故障重试、deadline 观察和成本模型 | 分布参数、云价格和失败行为未由现实数据校准时，只能解释为情景假设。 |
| 兼容数据移动模型与可选固定端点无争用模型 | 不包含网络拓扑、路由、共享链路/存储/网卡争用、排队或带宽竞争。 |

标准研究入口 `SimulationRunner` 目前只接受无任务聚类的 `SPACE_SHARED` VM 执行模型。历史示例和低层 API 仍可用于兼容性探索，但其聚类、`TIME_SHARED` VM 或遗留算法标签不应被当作已认证研究证据入口。

## 工程结构

项目采用一个 Maven Reactor 父工程和两个物理模块：

```text
WorkflowSim-1.0/
├── README.md                       # 项目入口和范围说明
├── pom.xml                         # Reactor 父工程；默认只构建 simulator/
├── simulator/                      # 可复用模拟器核心模块
│   └── src/
│       ├── main/java/              # WorkflowSim、通用研究 API、CloudSim 源码
│       └── test/                   # 单元、语义与端到端集成测试
├── experiments/                    # 显式 profile 才加入的实验模块
│   ├── src/                        # 历史示例、教程、参考基线、研究代码和测试
│   ├── reference/p7/               # 冻结参考基线（P7）的运行说明
│   └── studies/                    # 新研究的协议、矩阵和保留决策
├── datasets/                       # DAX、WfGen WfFormat、WfInstances 数据集
└── docs/                           # 构建、复现性、算法与研究协议
```

| 模块 | 坐标 | 内容 | 默认构建 |
| --- | --- | --- | --- |
| `simulator/` | `org.workflowsim:workflowsim:1.0` | 稳定模拟器实现、通用 `SimulationRunner`/工件 API、核心测试 | 是 |
| `experiments/` | `org.workflowsim:workflowsim-experiments:1.0` | 历史示例、教程、P7/reference 和未来研究实验 | 是 |

`experiments/` 依赖 `simulator/`，核心模块不会反向依赖实验模块。历史 Java 示例 FQCN 保持 `org.workflowsim.examples.*`，但其物理源码位于 `experiments/src/main/java/`。实验代码、参考基线和研究协议的分层约定见 [`experiments/README.md`](experiments/README.md)。

## 环境与快速开始

需要 JDK 17+ 与 Maven 3.6.3+。从项目根目录执行：

```bash
# 默认质量门禁：仅核心模拟器。
mvn verify

# 完整质量门禁：核心 + 示例/教程/P7/reference 测试。
mvn verify

# 可选的核心 API 文档检查。
mvn -Pjavadoc verify

# 历史 DAX 示例。示例已在 experiments 模块，但 FQCN 保持不变。
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.WorkflowSimBasicExample1 \
  compile exec:java

# WfInstances 1.5 JSON 的抽象转换示例（不是来源平台重放）。
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json" \
  compile exec:java

# 检查完成后清理两个模块的构建中间输出。
mvn clean
```

`mvn test` 只运行快速单元/语义测试；`mvn verify` 还会运行命名为 `*IntegrationTest` 的端到端测试。默认 `mvn verify` 覆盖所有模块。`target/`、JaCoCo 报告、临时 manifest、日志和未明确保留的实验输出都必须在验证后清理。

更完整的 Maven 命令、模块目标、P7 执行器与只读验证器见 [`docs/getting-started/BUILD.md`](docs/getting-started/BUILD.md)。

## 工作流输入与数据集

所有项目内工作流数据位于 [`datasets/`](datasets/)。

| 输入格式 | 位置 | 当前支持范围 |
| --- | --- | --- |
| Pegasus DAX 2.1 XML | `datasets/dax/` | `WorkflowParser` 原生解析；经典示例的默认输入格式。 |
| WfCommons WfFormat JSON（WfGen） | `datasets/wfformat/` | 严格解析工作流图、文件大小和任务 runtime；WfGen 是合成 profile，不是原始执行测量。 |
| WfInstances 1.5 JSON | `datasets/wfinstances/v1.5/` | 严格结构转换和端到端输入认证；当前不读取机器、内存、核数、命令、时间戳或观测 makespan 来重放来源平台。 |

WfCommons JSON 的 `runtimeInSeconds` 按下面的显式模型假设换算为 CloudSim MI：

```text
max(100, floor(runtimeInSeconds * runtimeReferenceMips * runtimeScale))
```

默认 `runtimeReferenceMips` 为 `1000.0`，`runtimeScale` 为 `1.0`。两者必须与实验结果一同记录，不能把换算后的 MI 当作硬件无关的真实 CPU 工作量。WfInstances 字段映射、认证输入与不可主张范围见 [`docs/advanced/WFINSTANCES_PILOT.md`](docs/advanced/WFINSTANCES_PILOT.md)；数据集布局和来源说明见 [`datasets/README.md`](datasets/README.md)。

历史示例使用以项目根为基准的 `datasets/...` 相对路径。P7/reference 与未来研究驱动应传入**绝对数据集根目录**，即这个 `datasets/` 目录本身，并在其下使用 `dax/...`、`wfformat/...` 或 `wfinstances/...` 逻辑路径；不要把项目根目录传为数据根。

## 推荐研究入口

研究代码应使用明确的运行 API，而不是直接串接遗留静态 `Parameters`、`ReplicaCatalog` 和 CloudSim 初始化：

```text
工作流输入 + SimulationConfig + PlatformProfile
                    |
                    v
             SimulationRunner.run(...)
                    |
                    v
    SimulationReport + 可选 evidence artifact bundle
```

- `SimulationConfig`：单次运行的不可变配置，含输入、算法、存储、随机种子、故障、开销、deadline、成本和数据移动模型。
- `PlatformProfile`：不可变的抽象 Host/VM/存储/成本描述，并预检 VM-to-Host 放置可行性。
- `SimulationRunner`：同一 JVM 内串行执行一次解析、规划、调度和 CloudSim 仿真，返回不可变 `SimulationReport`。
- `ExperimentManifestWriter` / `ExperimentArtifactWriter`：写出可验证的 `manifest.json`、`metrics.json` 与 `events.jsonl` 工件。
- `ExperimentPlan` / campaign 工具：声明和执行多场景、多重复实验；它们不把相同 root seed 误标记为 event-keyed CRN。仅显式独立重复的 cell 才会给模型运行完成率提供 Wilson 区间，且不自动产生算法差异推断。

核心 API 源码位于 [`simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java`](simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java)。历史/教程 WfCommons 示例位于 [`experiments/src/main/java/org/workflowsim/examples/wfcommons/WfCommonsSimulationExample1.java`](experiments/src/main/java/org/workflowsim/examples/wfcommons/WfCommonsSimulationExample1.java)。

由于 WorkflowSim/CloudSim 保留历史静态状态，标准契约只支持同一 JVM 内的串行运行。需要并行实验时，应使用相互隔离的 JVM 进程，并单独记录进程隔离和工件合并过程。

## 已维护的算法轨道

算法标签描述的是当前 WorkflowSim 模型中的决策语义，而不是对原论文实现、真实网络环境或普遍优越性的宣称。每个标准运行的 manifest 会记录机器可读的算法契约。

**三类算法的本质区别**：决策时机和能看到的信息不同，**不能混在同一基线比较**。详细原理和每个算法的一句话解释见 [`docs/getting-started/ALGORITHMS.md`](docs/getting-started/ALGORITHMS.md)。

| 研究问题 | 已维护算法 | 关键限制 |
| --- | --- | --- |
| **在线 DAG ready-Job 调度** | `FCFS`、`READY_BATCH_ROUNDROBIN`、`READY_BATCH_MCT`、`READY_BATCH_MINMIN`、`READY_BATCH_MAXMIN`、`DATA` | 运行时决定的是依赖已满足后由引擎释放的 Job；不是离线 DAG 全局时序表。`DATA` 只依据非本地输入字节数，不依据端点带宽或延迟。 |
| **静态独立任务映射** | `STATIC_OLB`、`STATIC_MET`、`STATIC_MCT`、`STATIC_MINMIN`、`STATIC_MAXMIN`、`STATIC_SUFFERAGE`、`STATIC_ROUND_ROBIN` | 只接受没有父子边的任务集合；不产生网络调度或完整离线执行 trace。 |
| **受控 shared-storage 静态 DAG 映射** | `SHARED_STORAGE_HEFT`、`SHARED_STORAGE_CPOP`、`SHARED_STORAGE_DLS`、`SHARED_STORAGE_ETF`、`SHARED_STORAGE_PEFT` | 要求 `STATIC` 调度、共享存储、无聚类、无开销、禁用故障和 `SPACE_SHARED` VM；不表示网络拓扑、路由或共享链路争用。 |
| **通信感知 LOCAL 静态 DAG 映射（论文复现）** | `LOCAL_HEFT`、`LOCAL_CPOP` | 要求 `STATIC` 调度、LOCAL 文件系统、NONE 聚类、无开销、禁用故障、`preExecutionTransferDelayV1` 数据移动模型与 `SPACE_SHARED` VM；受控带宽模型（VM 对 `min(bw)`），不表示链路争用或网络拓扑。 |

**论文复现验证**（Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002 规范算例，`LOCAL_HEFT`/`LOCAL_CPOP`）：HEFT 向上 rank 与论文逐一相同，VM 映射 10/10 且每任务区间逐位等于论文区间（相对 makespan 80.1 vs 论文 80）；CPOP 复现论文关键路径 {n1, n3, n7, n10} 与关键路径处理器，相对 makespan 87.1 vs 论文 86。传输按论文 AST 语义建模为执行前网络延迟（可与 VM 忙碌期重叠，VM 只被计算占用）。复现细节与平台适配声明见 [`docs/PLATFORM_AUDIT_REPORT.md`](docs/PLATFORM_AUDIT_REPORT.md) 与 [`docs/algorithms/CATALOG.md`](docs/algorithms/CATALOG.md)。

旧 `HEFT`、`DHEFT`、`MINMIN`、`MAXMIN`、`MCT` 和 `ROUNDROBIN` 标签仍可由历史 API 或示例调用，但标准 `SimulationRunner` 会拒绝它们作为研究入口，以避免将兼容性实现误称为维护的算法复现。算法决策语义、确定性 tie-break 和测试范围见 [`docs/algorithms/CATALOG.md`](docs/algorithms/CATALOG.md) 与 [`docs/algorithms/CONTRACTS.md`](docs/algorithms/CONTRACTS.md)。

## 结果、指标与可复现性

`SimulationReport` 和实验工件可提供以下模拟器派生观察量：

- CloudSim 仿真结束时间 `simulationEndSeconds`（兼容字段为 `makespan`）、Job 成功/失败和逻辑 Task 完成情况；全部逻辑 Task 成功时，另有 `logicalTaskCompletionSeconds` 与末端生命周期尾部，不能用失败运行的结束时间伪装为工作流完成时间；
- completed Job/compute attempt、retry 创建、被重试逻辑 Task、失败 attempt 的 Job-envelope 时间和抽象处理成本；这些是当前 fail-after-attempt 模型证据，不是现实中途宕机损失或可结算浪费；
- 吞吐、调度延迟、VM busy time 与 VM utilization；
- deadline SLA 观察。deadline 从模拟时间零点对 `simulationEndSeconds` 进行观察，不会反向改变调度、准入或重试；
- 模拟的 processing cost。它拆分为 Job CPU-envelope 与声明文件带宽抽象成本，累积所有完成 attempts；不是云服务商价格重放、实际网络账单或内置计费舍入；
- 数据 stage-in 的模型延迟及逻辑输入需求文件数/字节数；需求量不等于实际通过网络或存储系统搬运的流量；
- campaign 中的 workflow-run 完成率，以及仅在完整成功运行上汇总的逻辑完成时间；这些是模型随机运行的统计描述，不能外推为真实云可靠性、可用性或故障分布校准；
- 受控 shared-storage 静态 DAG 范围内的 SLR；
- 显式规划和调度决策的本地 JVM 墙钟开销，仅能用于同环境诊断，不能作为跨机器性能比较。

一个 evidence bundle 由 `manifest.json`、`metrics.json` 和 `events.jsonl` 构成。保留工件前应使用核心只读验证器；P7/reference 的新工件还会明确记录核心组件、参考研究组件和显式数据集根。详细命令见 [`docs/getting-started/BUILD.md`](docs/getting-started/BUILD.md)，随机性/统计边界见 [`docs/experiments/REPRODUCIBILITY.md`](docs/experiments/REPRODUCIBILITY.md) 和 [`docs/experiments/CAMPAIGNS.md`](docs/experiments/CAMPAIGNS.md)。

## 文档导航

| 想了解的问题 | 阅读位置 |
| --- | --- |
| **如何快速上手？5 分钟运行第一个仿真** | [`docs/getting-started/QUICK_START.md`](docs/getting-started/QUICK_START.md) ⭐ |
| **如何配置参数、选择算法、在 IDEA 中运行实验？** | [`docs/getting-started/RUN_EXPERIMENTS.md`](docs/getting-started/RUN_EXPERIMENTS.md) ⭐ |
| **experiments 模块在 IDEA 中显示橙色咖啡杯？** | [`docs/getting-started/IDEA_SETUP.md`](docs/getting-started/IDEA_SETUP.md) 🔧 |
| **三类算法的本质区别和底层原理是什么？** | [`docs/getting-started/ALGORITHMS.md`](docs/getting-started/ALGORITHMS.md) ⭐ |
| **质量审计：算法是否正确？指标是否准确？** | [`docs/advanced/QUALITY_AUDIT.md`](docs/advanced/QUALITY_AUDIT.md) |
| 如何构建、运行示例、执行 P7 或验证工件？ | [`docs/getting-started/BUILD.md`](docs/getting-started/BUILD.md) |
| 两模块如何划分，研究代码放在哪里？ | [`experiments/README.md`](experiments/README.md)、[`experiments/studies/README.md`](experiments/studies/README.md) |
| 模型如何保证可重放？随机性、成本和 deadline 的含义是什么？ | [`docs/experiments/REPRODUCIBILITY.md`](docs/experiments/REPRODUCIBILITY.md) |
| 算法的实际决策层、可比较范围和不可主张是什么？ | [`docs/algorithms/CATALOG.md`](docs/algorithms/CATALOG.md) |
| 算法和指标测试到底验证了什么？ | [`docs/algorithms/CONTRACTS.md`](docs/algorithms/CONTRACTS.md) |
| 冻结参考基线的固定实验矩阵和已记录结果是什么？ | [`docs/experiments/reference-baselines/P7_PROTOCOL.md`](docs/experiments/reference-baselines/P7_PROTOCOL.md)、[`docs/experiments/reference-baselines/P7_RESULTS.md`](docs/experiments/reference-baselines/P7_RESULTS.md) |
| WfInstances 当前解析了哪些字段？ | [`docs/advanced/WFINSTANCES_PILOT.md`](docs/advanced/WFINSTANCES_PILOT.md) |
| 如何设计多场景/多重复实验以及数据移动模型？ | [`docs/experiments/CAMPAIGNS.md`](docs/experiments/CAMPAIGNS.md) |
| 数据集目录、格式和已知输入修复是什么？ | [`datasets/README.md`](datasets/README.md) |
