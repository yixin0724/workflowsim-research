# P7 兼容参考协议

> **用途：冻结参考与历史兼容，不是当前入门指南。** P7 保留特定输入、配置和在线调度器的比较矩阵；它不概括平台当前能力，也不保证新代码重现旧实现的数值。运行方式及保留的历史数值见[P7 本地说明](<../../../experiments/reference/p7/README.md>)。

## 冻结矩阵

[矩阵实现](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineMatrix.java>)规定 **2 个输入 × 2 个平台变体 × 5 个调度器 = 20 个运行单元**，不是 20 个输入。逻辑路径相对于传入的绝对数据集根目录。

| 输入 | 逻辑路径 | Task 数 | VM 数 | SHA-256 |
|---|---|---:|---:|---|
| Epigenomics n100 | `dax/epigenomics/n100/Epigenomics_100.dax` | 100 | 10 | `374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36` |
| Epigenomics n997 | `dax/epigenomics/n997/Epigenomics_997.dax` | 997 | 50 | `2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628` |

调度器集合仅为 `FCFS`、`READY_BATCH_ROUNDROBIN`、`READY_BATCH_MCT`、`READY_BATCH_MINMIN`、`READY_BATCH_MAXMIN`。规划层为 `INVALID`；其他算法、工作流家族及 WfCommons/WfInstances 输入不自动加入本矩阵。

| 平台变体 | VM MIPS | 放置 |
|---|---|---|
| H0 / `H0_HOMOGENEOUS` | 全部 1000 | 一 Host 一 VM |
| H1 / `H1_HETEROGENEOUS` | 偶数 VM ID 为 500，奇数为 1500；数量相等、均值 1000 | 一 Host 一 VM |

Host/VM ID 均从 0 连续编号。每 Host 为 2 PE、每 PE 2000 MIPS、2048 MB RAM、带宽参数 10000、存储 1000000 MB；每 VM 为 1 PE、512 MB RAM、带宽参数 1000、镜像 10000 MB、Xen、`SPACE_SHARED`。未设置显式 pin 或 VM 单独定价；预检与实际 VM–Host 分配均进入证据并被核对。共享存储采用平台默认容量 1000000000000 MB、传输率 15 MB/s；数据中心成本参数依次为 CPU 秒 3.0、内存 0.05、存储 0.1、带宽 0.1。这些是抽象配置，不是硬件或云账单校准。

## 固定配置与量的含义

| 配置 | 固定值 |
|---|---|
| 文件系统 / 数据移动 | `SHARED` / `LEGACY_WORKFLOWSIM_V1` |
| 聚类 / 开销 / 故障 | `NONE` / `OverheadModelConfig.none()` / disabled |
| 根种子 | `20260901` |
| runtime 参考 MIPS / scale | `1000.0` / `1.0` |
| CloudSim 最小事件间隔 | `0.1` 模拟秒 |
| 到达 / deadline / 成本矩阵 / 拓扑 | 单输入零到达 / 未请求（0）/ 无 / 无 |
| 成本模型 | `DATACENTER`；记录但不作为 P7 比较指标 |

DAX 的非负 runtime 转换为 `max(100, floor(runtimeSeconds * referenceMips * runtimeScale))` MI，并接受当前内核的可表示性校验。事件间隔是内核节拍，不是物理链路延迟。

主指标 `makespan` 保留**仿真结束时刻**含义，包括该模型的 stage-in；不能换成逻辑任务完成时间后仍称同一指标。辅助证据包括 Job 成败、起止/CPU 时间、类别、Task outcome 及每 VM 的作业汇总。Job 执行信封可能含整数 MI 表示的 stage-in，Task 窗口是模型观察；VM 汇总不是 Host 监测。成本、利用率、能耗和本机决策墙钟耗时均不是 P7 主/次要比较指标。

本矩阵仅支持单一家族、固定模型下的描述性比较。确定性重复用于复现核查，不是独立随机样本，不能据此做显著性检验。相同根种子不等于事件键控共同随机数。不得据此主张跨家族优势、真实 trace 重放、网络/存储争用收益、故障鲁棒性或生产平台性能。

## 执行、索引与校验

P7 驱动属于默认 reactor 构建的实验模块，核心模块不依赖它。推荐通过[执行器](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineExecutor.java>)显式提供绝对数据集根及绝对空输出目录；数据根是包含 `dax/` 的目录，不是其上层项目根。非空输出目录会被拒绝。CLI 也支持零参数模式，从 `user.dir` 推导数据集和带时间戳的输出目录；该便利行为不能替代研究中明确记录输入/输出位置。

| 入口 | 索引 / 身份 |
|---|---|
| `executeBaseline(...)` 或 CLI | `p7-baseline-index.json`；schema `workflowsim-p7-baseline-index-v3`；kind `FROZEN_FULL_BASELINE` |
| `executeSelection(...)` | `p7-reference-selection-index.json`；schema `workflowsim-p7-reference-selection-index-v1`；kind `PARTIAL_SELECTION_NOT_P7_BASELINE` |

即使选择运行碰巧覆盖全部单元，选择索引也不是正式基线。执行器逐运行核对输入 hash、Task 数与成功完成，写出并校验 evidence bundle；所有运行成功后才写索引并立即校验。

新工件使用 manifest v4、metrics v2、events v1、provenance v3，并记录当前 `WORK_CONSERVING_TASK_EXECUTION_V2` 执行语义。新增字段或同一参数矩阵不使旧结果自动成为当前模型结果。[索引验证器](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7EvidenceIndexValidator.java>)核对完整 4×5 矩阵、输入指纹、固定配置、平台与实际放置、结果摘要、逐运行工件及研究身份；历史 v2/v3 身份只读兼容，不补造缺失字段。

生成可比较结果前应通过包含集成测试的完整门禁。矩阵/执行器测试、核心语义测试和工件校验共同约束该参考轨道；仅验证索引结构不证明真实平台准确性或历史数值在当前代码下不变。保留结果时交接完整索引与所有引用工件，不能只交汇总表。

## 协议身份与文档指纹

[P7ReferenceIdentity](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7ReferenceIdentity.java>)使用 study ID `p7-baseline`、组件 `org.workflowsim:workflowsim-experiments:1.0`、驱动 `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`，以及固定逻辑 ID `docs/experiments/reference-baselines/P7_PROTOCOL.md`。

该逻辑 ID 对应的**本文件实际字节**会被计算 SHA-256 并写入新运行的 provenance；通过驱动 code source 定位，不依赖 CLI 当前目录。独立 JAR 缺少并置协议时记录 `available=false` 和空 hash，不伪造指纹。旧 `P7_EXPERIMENT_PROTOCOL.md` 的历史逻辑身份仍被验证器接受。

本次精简保留原路径和协议身份，但会改变未来新运行记录的文档指纹；**没有修改历史 bundle、索引或其原有 hash**。协议 hash 是一致性与来源记录，不是数字签名、发行认证或运行结果的黄金值。

算法的一般说明见[算法目录](<../../algorithms/CATALOG.md>)，新研究的协议管理见[研究目录](<../../../experiments/studies/README.md>)。
