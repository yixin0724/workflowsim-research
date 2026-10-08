# WorkflowSim

WorkflowSim 是用于科学工作流调度研究的离散事件模拟器。它在统一的声明模型中处理任务依赖、计算资源、存储、数据传输与调度决策，并输出可校验、可重放的实验结果。

模拟结果用于同条件研究比较，不等同于真实集群监控、生产网络预测或云服务商账单。项目提供Java仿真环境和策略接口，不包含深度学习训练器。

## 快速开始

需要 **JDK 17+、Maven 3.6.3+**。在项目根目录执行：

```bash
# 检查输入、参数和算法组合
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行实验并生成离线交互报告
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

打开输出根目录的 `index.html` 查看实验历史，进入实验目录的 `report.html` 查看方案比较、任务时间线、等待和资源利用率。每次运行使用独立输出目录，失败原因与已完成结果均会保留。

- 使用配置文件运行、查看报告：[Workbench指南](<docs/getting-started/WORKBENCH.md>)
- 使用Java API构建实验：[代码配置指南](<docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>)
- 构建、测试与工具命令：[构建指南](<docs/getting-started/BUILD.md>)
- 按主题查找：[文档中心](<docs/README.md>)

## 选择示例

| 目标 | 配置 |
|---|---|
| 比较在线CPU调度方案 | [在线调度比较](<experiments/configs/online-comparison.json>) |
| 使用网络争用模型 | [网络比较](<experiments/configs/network-comparison.json>) |
| 查看逐文件来源、传输和CPU输入门控 | [一致文件数据流V2](<experiments/configs/coherent-file-dataflow-v2.json>) |
| 模拟有限SOURCE和必要输出写回 | [受限存储数据流V3](<experiments/configs/storage-dataflow-v3.json>) |
| 在控制就绪时选择VM，再传输输入 | [在线目标绑定与受限存储](<experiments/configs/online-storage-dataflow-v1.json>) |

示例使用不同支持矩阵，不能通过任意混合算法、文件系统和网络选项来构造实验。完整范围见[数据流能力矩阵](<docs/advanced/DATAFLOW_CAPABILITY_MATRIX.md>)及[算法目录](<docs/algorithms/CATALOG.md>)。

## 主要能力

| 方面 | 支持范围 |
|---|---|
| 工作流输入 | Pegasus DAX XML、WfCommons WfFormat/WfInstances JSON；读取依赖、运行量及文件信息，不重放来源机器的时间线 |
| 平台 | 显式Host/VM、确定性VM放置、容量预检和声明式成本；不模拟CPU超卖、VM迁移或实际硬件监控 |
| 调度与规划 | 在线CPU调度、独立任务规划、受控共享存储/LOCAL DAG规划，以及明确标注的随机与搜索基线 |
| 一致数据流 | V2逐文件来源、路径、合并与可见性；可选端点或实际Fat-tree路径 |
| 受限存储 | V3有限读/写/NIC、SOURCE接入Host、成功输出异步落库及终止排空 |
| 在线目标绑定 | 根据已提交网络观测和预计CPU预留绑定实际兼容VM；STATIC随后派发，NOOP重试复用绑定 |
| 实验控制 | 多工作流错峰到达、受支持组合下的失败/重试、deadline事后观察、矩阵计算成本 |
| 策略接入 | `RlEnvironment` / `RlPolicy`状态、动作和奖励接口；外部策略需遵守对应模型约束 |

标准 `SimulationRunner` 使用无任务聚类的SPACE_SHARED VM。同一JVM内串行运行；批量并行应使用独立进程。在线目标绑定与在线CPU调度是不同决策阶段，不能混为同一种算法。

## 运行链与项目结构

```text
工作流 + SimulationConfig + PlatformProfile
    → 输入与组合校验 → 解析依赖 → 可选静态规划
    → 依赖/到达门控 → 固定映射或显式在线目标绑定
    → 输入准备 → CPU派发与事件循环 → 必要输出排空
    → SimulationReport → 证据校验 → 离线报告 / 精确重放
```

- `simulator/`：仿真核心、平台、算法、网络、策略接口及测试。
- `experiments/`：统一入口、示例、实验执行器和报告；默认参与Maven reactor。
- `datasets/`：工作流输入及[数据说明](<datasets/README.md>)。
- `scripts/`：独立证据校验、浏览器验收和性能比较工具。
- `docs/`：当前使用指南、模型契约和维护说明。

核心模块不依赖实验模块。

## 实验结果与研究边界

标准工件包含manifest v4、metrics v2和events v1；网络侧车按明确的记录模式选择。哈希、大小、事件顺序和声明上下文共同校验，重放按JSON精确值比较，不用容差掩盖科学量变化。

- 生命周期证据证明其声明范围内的来源、路径、输入/输出及CPU因果，不自动构成逐区间流体服务会计。
- 在线动作证据验证绑定、计算声明、名义输入边界和已记录分数，不证明精确实时余额、未来争用或全局最优性。
- 文件引用量不等于网络流量，抽象成本不等于账单，运行器墙钟耗时不等于模拟时间。具体统计口径见[可复现性契约](<docs/experiments/REPRODUCIBILITY.md>)与[重放契约](<docs/experiments/RERUN_DIFF_CONTRACT.md>)。
- 论文夹具和兼容性数据只验证对应版本/条件，不能直接替代当前代码的实验结论。

## 开发与验证

```bash
mvn -Pjavadoc clean verify
```

联合门禁包含单元、语义、集成、覆盖率与核心API文档；独立Python及离线浏览器检查见[构建指南](<docs/getting-started/BUILD.md>)。性能比较先检查状态、捕获和动作指纹一致，再报告耗时及分配量，方法见[状态复制性能说明](<docs/advanced/DATAFLOW_STATE_COPY_PERFORMANCE.md>)。
