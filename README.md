# WorkflowSim

WorkflowSim 是科学工作流调度研究的离散事件模拟器。它把任务依赖、计算资源、存储、数据传输与调度方案放到同一声明模型里运行，并输出可校验的配置、指标和事件证据。

模型结果用于同条件研究比较，不能直接声称重放真实工作流执行、预测生产网络或估算云服务商账单。Java 侧负责仿真环境、策略适配与证据，不建设深度学习训练器或模型训练流程。

**正确性与可扩展性**：当前执行语义为 `WORK_CONSERVING_TASK_EXECUTION_V2`。[首轮整改](docs/advanced/SIMULATION_CORRECTNESS_REPAIR.md)修复执行与证据问题，[第二轮审计](docs/advanced/CONFIGURATION_PROPERTY_AUDIT.md)检查配置组合、深DAG和参考/deadline，[第三轮审计](docs/advanced/GRAPH_PLANNING_SCALABILITY_AUDIT.md)以有界计数优化高扇入/出校验、宽图槽位重复查询和OCT文件扫描。旧研究不覆盖；优化通过独立参考与同条件实际轨迹对照，而非为保持排名调参数。

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

# 联合质量门禁：两个模块、单元/语义/集成/覆盖率与核心 API 文档
mvn -Pjavadoc clean verify
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
| LOCAL通信规划 | LOCAL_HEFT/CPOP/PEFT；STATIC派发，LOCAL存储，preExecution家族模型，无故障/开销；带副本可用时刻的无争用规划估计，不是完整事件重放 |
| 随机与搜索基线 | RANDOM、PSO。PSO采用顺序负载目标，不含DAG/网络优化；无矩阵的raw MI模型下其简化成本项与映射无关，显式矩阵下按有效执行秒数计价 |
| 网络 | 保留原五类模型；新增逐文件`COHERENT_FILE_DATAFLOW_V2`及同生命周期无共享对照。来源/实际路径/副本可见/CPU门控统一；端点与可选Fat-tree，流级模型仍不含包/丢包/ECN/自适应路由 |
| 存储数据流 | `COHERENT_STORAGE_DATAFLOW_V3`及独立瓶颈对照；显式SOURCE接入Host、有限读/写/共享NIC、成功输出异步落库和结束排空；SHARED提交后读穿透缓存与LOCAL来源策略分开 |
| 在线目标绑定 | 显式`CONTROL_READY_ONLINE_ASSIGNMENT_V1`；控制就绪时依据已提交网络观测/预计CPU预留选VM，再传输输入；STATIC只派发固定目标，NOOP重试不换VM；不是全局最优或完整争用预测 |
| 多工作流/异常 | 可预先声明错峰到达；支持受控开销、失败尝试和重试预算；deadline仅事后观察，功能不能任意交叉组合 |
| RL策略适配 | RlEnvironment + RlPolicy状态/动作/终局奖励契约，外部策略接入；没有训练器、神经网络或模型权重 |

原preExecution/争用模型要求预先静态映射，因此普通在线CPU调度/RL_POLICY仍不能直接与Fat-tree争用组合。新目标绑定是仅对一致V2/V3开放的独立决策阶段，CPU仍用STATIC派发。统一入口拒绝在线、独立任务和DAG轨道混排。DAG轨道内部还需区分执行纪律：LOCAL规划器给出每VM顺序，RANDOM/PSO仅给映射、由运行时选择已就绪作业；R10是完整策略流水线比较，不能把差异仅归因于映射优化，也不能忽略固定顺序带来的队头等待。

V2默认分支支持RANDOM映射、STATIC派发、LOCAL、NONE聚类（每Job一个Task）、无开销和受控NOOP重试；显式在线目标绑定是另行声明的可选分支。V2的SOURCE输入仍采用显式无限汇聚/绕过fabric抽象，不因新存储版本而改义；不能直接改用原CPU在线/RL调度器，或把LOCAL规划器旧估计当成V2估计。可运行[共享示例](<experiments/configs/coherent-file-dataflow-v2.json>)与[独立路径瓶颈对照](<experiments/configs/coherent-file-dataflow-isolated-v2.json>)；完整支持矩阵见[V2契约](<docs/advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>)。

V3存储模型继续使用RANDOM/STATIC/NONE/无开销，额外支持LOCAL或SHARED提交后缓存读取；所有成功输出（包括unused/零字节）必须到SOURCE，CPU返回可早于输出写回结束。已接通Java/Python独立证据、rerun和专用报告，见[存储V3契约](<docs/advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)、[共享存储示例](<experiments/configs/storage-dataflow-v3.json>)与[独立瓶颈对照](<experiments/configs/storage-dataflow-isolated-v3.json>)。不把现有规划器自动视为V3估计器。显式在线绑定已接通Java/Python动作校验、完整重放和独立报告面板；见[在线绑定契约](<docs/advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)、[在线V2示例](<experiments/configs/online-file-dataflow-v1.json>)与[在线V3示例](<experiments/configs/online-storage-dataflow-v1.json>)。其动作认证要求完整生命周期捕获，证明绑定/名义边界/已记录分数，不认证精确实时余额或未来争用过程。

算法细节：[算法目录](docs/algorithms/CATALOG.md)、[测试契约](docs/algorithms/CONTRACTS.md)。历史HEFT/DHEFT枚举及实现已经删除；旧MINMIN/MAXMIN/MCT/ROUNDROBIN兼容标签仍被标准运行器拒绝。

R10纠正了LOCAL_CPOP向下秩方向和关键路径选择。HEFT来源的十任务算例关键路径为{1,2,9,10}；HEFT/CPOP绝对结束时间190.1/196.1，扣除110.1引导后为80/86。

本轮依据作者公开收录原文修正 LOCAL_PEFT：OCT使用**后继计算成本**，出口为零。真正PEFT论文算例的PEFT/HEFT为122/133（含引导232.1/243.1），完整OCT、选择顺序、映射和Task/Job区间均有独立来源回归，见[原文与夹具说明](simulator/src/test/resources/dax/peft-paper-example.SOURCE.md)。旧76/80不是该论文算例；旧R12/R13使用的非标准实现已标为历史，不能据其评价标准PEFT。测试通过仍不能代替模型边界说明或真实平台校准。

## 实验证据与指标

当前标准输出为 manifest v4、metrics v2、events v1；provenance仍为v3。v4完整保存输入顺序、工作流到达时刻、原始任务×VM执行秒数矩阵、拓扑声明及数据移动语义。验证器继续读取历史manifest v2/v3，但不会补造旧版遗漏的条件。

网络记录显式opt-in：V1流体模型的`FLUID_GROUP_LEDGER_V1`提供组级服务/速率指标；新模型的`FILE_LIFECYCLE_V2`提供独立逐文件来源、请求、复制、可见性和CPU因果证书，**不是逐区间流体服务会计证书**。V2首次导出只接受完整捕获，预算不足在写工件之前拒绝，运行物理不受记录开关影响。V2记录OFF仍保留核心`dataflowPlan`，旧模型OFF形状不变。存储V3另用`FILE_STORAGE_LIFECYCLE_V3`及`storage-lifecycle`角色，独立证明输出落库与输入等待/CPU的因果关系，仍不是流体服务面积证书。三类侧车分别有Java/Python校验、完整rerun比较与专用离线面板；详见[文件生命周期格式](<docs/advanced/FILE_LIFECYCLE_V2_FORMAT.md>)。

主要指标包括：仿真结束时间、全部逻辑任务完成时间、成功/失败尝试与重试、平均/中位/P95等待、VM忙碌区间利用率、吞吐、deadline观察和抽象成本。文件需求量不等于网络流量；成本不等于云账单；本机算法决策耗时不等于模拟执行时间。

证据包的哈希/大小、事件序列、指标一致性及v4新增配置字段都被校验。证据文件逐个原子替换，整个三件套不是一个文件系统事务，完整性以验证通过为准。

## 网络受限研究

`org.workflowsim.experiments.network.NetworkStudyExecutor` 提供 `smoke` 与 `full` 模式，以及默认网络矩阵、`peft-comparison`、`sensitivity-r13` 三个变体。网络集成测试分别覆盖36/18/30次小矩阵；正式研究显式执行，保留参数、资格排除、种子、统计口径和逐运行v4工件。

修正后的新执行身份分别为 `network-limited-r10-v3`、`peft-comparison-r12-v2`、`sensitivity-response-r13-v2`。旧身份只做历史协议完整性检查，不能用新模型结果替换旧证据；详见[研究协议修订](docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md)。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-study-directory"

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-study-directory/network-study.json"
```

默认网络矩阵包含三个经典家族、约百/千任务规模、4/16台资源和通信密集合成负载；千任务Inspiral因同名文件尺寸冲突一致排除，共7个合格输入、504次运行。HEFT/CPOP每条件一次，RANDOM/PSO各5个种子。PEFT比较矩阵为126次，敏感性矩阵扩展VM/带宽/异构条件为546次。

统计先按DAG汇总种子，再分来源、资源和网络条件比较；精确符号检验配合Holm调整，报告效果幅度和胜平负。经典组至多5个DAG时，双侧符号检验原始p值最低0.0625，不能达到0.05门槛；不显著不能推出等价，跨条件的总MIPS/拓扑变化也不能被解释成单因素因果效果。

旧360次Fat-tree研究保留为历史。R10当时按原参数复跑，将R8的3/10换冠结论改为1/10；这仍是本次执行语义修订前的历史记录。请勿把[R8历史结果](docs/experiments/FATTREE_SCHEDULING_RESULTS.md)或[R10验收记录](docs/advanced/PLATFORM_UPGRADE_R10.md)当作当前代码的数值认证，新的排名须按新模型重新评估。

## 其他入口

- [快速上手](docs/getting-started/QUICK_START.md)、[构建指南](docs/getting-started/BUILD.md)
- [WfInstances边界](docs/advanced/WFINSTANCES_PILOT.md)
- [通用campaign与统计](docs/experiments/CAMPAIGNS.md)
- [P7冻结参考说明](experiments/reference/p7/README.md)

未决定保留的临时输出应清理；正式研究和交付报告按各自保留说明保存。开发门禁 `mvn test` 仅单元/语义，`mvn verify` 还包括集成；最终联合验收使用 `mvn -Pjavadoc clean verify`，并执行[离线报告浏览器检查](docs/getting-started/BUILD.md#离线报告浏览器验收)，不降低覆盖率阈值。
