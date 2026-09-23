# WorkflowSim

WorkflowSim 是科学工作流调度研究的离散事件模拟器。它把任务依赖、计算资源、存储、数据传输与调度方案放到同一声明模型里运行，并输出可校验的配置、指标和事件证据。

模型结果用于同条件研究比较，不能直接声称重放真实工作流执行、预测生产网络或估算云服务商账单。Java 侧负责仿真环境、策略适配与证据，不建设深度学习训练器或模型训练流程。

## 统一实验入口

需要 JDK 17+、Maven 3.6.3+，从项目根目录执行：

```bash
# 先验证输入、参数和算法组合
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行同条件多方案比较，生成离线交互报告与历史
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"

# 完整质量门禁：两个模块、单元/语义/集成/覆盖率
mvn clean verify
```

打开输出根目录的 `index.html` 查看实验历史；进入每次实验的 `report.html` 查看方案比较、任务时间线、等待和 VM 利用率。每次运行创建独立目录，失败记录会保留。网络示例配置为 `experiments/configs/network-comparison.json`。

详细操作与配置：[统一入口指南](docs/getting-started/WORKBENCH.md)。需要完整模型API时使用[代码配置指南](docs/getting-started/CODE_CONFIG_EXPERIMENTS.md)。旧 `MyConfigurableExperiment` 与旧 HTML/CSV 写器已在 R9 删除；当前报告由新 Workbench 从校验证据生成。

## 结构与运行链

```text
工作流 DAX/JSON + SimulationConfig + PlatformProfile
                  ↓
输入/组合校验 → 解析依赖图 → 可选静态规划
                  ↓
WorkflowEngine 依赖/到达门控 → 数据传输 → WorkflowScheduler 派发
                  ↓
CloudSim 事件循环 → SimulationReport → v4证据 → 校验/报告
```

- `simulator/`：可复用的核心、平台资源、算法、网络、RL环境与测试。
- `experiments/`：示例、参考基线、研究执行器、统一入口与报告。默认与核心一起构建，无需额外 profile。
- `datasets/`：工作流输入。经典DAX与少量必要JSON随仓库保留，全量本地语料的范围见[数据集说明](datasets/README.md)。
- `docs/`：操作、算法契约、研究协议和版本化审计。

核心不依赖实验模块。标准 `SimulationRunner` 只支持无任务聚类的 SPACE_SHARED VM；同JVM内串行执行，批量并行需要独立进程。

## 研究能力与边界

| 能力 | 当前范围 |
|---|---|
| 输入 | Pegasus DAX XML、WfCommons WfFormat/WfInstances JSON；消费任务关系、runtime和文件大小，不重放来源机器和观测时间线 |
| 平台 | 显式Host/VM/存储/定价；确定性VM放置与容量预检；不模拟超卖CPU、VM迁移或真实Host监控 |
| 在线调度 | FCFS、READY_BATCH_ROUNDROBIN/MCT/MINMIN/MAXMIN、DATA；处理依赖已满足的Job。DATA要求LOCAL，只考虑非本地字节 |
| 独立任务规划 | STATIC_OLB/MET/MCT/MINMIN/MAXMIN/SUFFERAGE/ROUND_ROBIN；拒绝含父子边的输入 |
| 共享存储DAG规划 | SHARED_STORAGE_HEFT/CPOP/DLS/ETF/PEFT；受控SHARED、无聚类/故障/开销、legacy传输模型 |
| LOCAL通信规划 | LOCAL_HEFT/CPOP；STATIC派发，LOCAL存储，preExecution家族模型，无故障/开销；规划仍用无争用估计 |
| 随机与搜索基线 | RANDOM、PSO。PSO沿参考实现的顺序负载目标，不含DAG/网络优化；其简化成本项不随映射改变 |
| 网络 | legacy、固定端点、执行前无争用、端点争用、Fat-tree争用五类。R10争用采用max-min progressive filling和分段积分；流级模型不含包/丢包/ECN/自适应路由 |
| 多工作流/异常 | 可预先声明错峰到达；支持受控开销、失败尝试和重试预算；deadline仅事后观察，功能不能任意交叉组合 |
| RL策略适配 | RlEnvironment + RlPolicy状态/动作/终局奖励契约，外部策略接入；没有训练器、神经网络或模型权重 |

所有preExecution/争用模型要求静态映射。因此当前在线调度/RL_POLICY不能直接与Fat-tree争用组合。不同决策层不能混入同一排行榜；统一入口会拒绝这类混比。

算法细节：[算法目录](docs/algorithms/CATALOG.md)、[测试契约](docs/algorithms/CONTRACTS.md)。历史HEFT/DHEFT枚举及实现已经删除；旧MINMIN/MAXMIN/MCT/ROUNDROBIN兼容标签仍被标准运行器拒绝。

R10纠正了LOCAL_CPOP向下秩方向和关键路径选择。十任务参考算例关键路径为{1,2,9,10}；HEFT/CPOP绝对结束时间190.1/196.1，扣除110.1引导后为80/86。测试包含完整rank、逐任务区间及多个独立反例，不能仅以自有黄金值代替算法语义验证。

## 实验证据与指标

当前标准输出为 manifest v4、metrics v2、events v1；provenance仍为v3。v4完整保存输入顺序、工作流到达时刻、原始任务×VM执行秒数矩阵、拓扑声明及数据移动语义。验证器继续读取历史manifest v2/v3，但不会补造旧版遗漏的条件。

主要指标包括：仿真结束时间、全部逻辑任务完成时间、成功/失败尝试与重试、平均/中位/P95等待、VM忙碌区间利用率、吞吐、deadline观察和抽象成本。文件需求量不等于网络流量；成本不等于云账单；本机算法决策耗时不等于模拟执行时间。

证据包的哈希/大小、事件序列、指标一致性及v4新增配置字段都被校验。证据文件逐个原子替换，整个三件套不是一个文件系统事务，完整性以验证通过为准。

## 网络受限研究

新入口 `org.workflowsim.experiments.network.NetworkStudyExecutor` 提供 `smoke` 与 `full` 模式。CI运行36次小矩阵，正式研究显式执行；其参数、资格排除、种子、统计口径和逐运行v4工件一起保留。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-study-directory"

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-study-directory/network-study.json"
```

正式协议包含三个经典家族、约百/千任务规模、4/16台资源和通信密集合成负载；千任务Inspiral因同名文件尺寸冲突被一致排除，共7个合格输入、504次运行。HEFT/CPOP每条件一次，RANDOM/PSO各5个种子；先按DAG汇总种子，再分来源、资源和网络条件比较，不制造伪独立样本。精确符号检验配合Holm调整，报告效果幅度和胜平负。

旧360次Fat-tree研究保留为历史。R10按原参数复跑后其3/10换冠结论变为1/10，部分旧结论已失效；请勿把[R8历史结果](docs/experiments/FATTREE_SCHEDULING_RESULTS.md)当作当前研究证据。实施与验收见[R10记录](docs/advanced/PLATFORM_UPGRADE_R10.md)。

## 其他入口

- [快速上手](docs/getting-started/QUICK_START.md)、[构建指南](docs/getting-started/BUILD.md)
- [WfInstances边界](docs/advanced/WFINSTANCES_PILOT.md)
- [通用campaign与统计](docs/experiments/CAMPAIGNS.md)
- [P7冻结参考说明](experiments/reference/p7/README.md)

未决定保留的临时输出应清理；正式研究和交付报告按各自保留说明保存。开发门禁 `mvn test` 仅单元/语义，`mvn verify` 还包括集成；最终验收使用 `mvn clean verify`，不降低覆盖率阈值。
