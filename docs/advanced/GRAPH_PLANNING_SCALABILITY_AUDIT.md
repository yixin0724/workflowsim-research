# 第三轮：图与规划可扩展性审计

**本轮约定范围的检查、优化和验证已完成。** 确认并处理了 **4类重复工作热点**，另修复 **1项畸形邻接表的异常契约问题**。不把12个失败测试算成12个独立Bug，也不把操作计数减少冒称整个平台同比加速。

## 1. 基线、分支与保护

- 基于第二轮已完成但尚未合并的 `c62c6b8f3dcecb18ab3ce375972cb56a46a06688`，在任何项目修改前创建叠加分支 `perf/graph-planning-scalability-audit`。
- 原 `fix/configuration-property-audit` 分支保留；本轮不自动推送、合并main或删除未合并工作。后续拆PR应明确第二轮依赖。
- 基线文件树 `e7c43a7ae0807cdb91678849eaaf9586dd198981` 重新执行 `mvn -o -B -Pjavadoc clean verify`：901项，0失败/错误/跳过。
- 对旧三研究、前两轮完整本地审计目录及三个原归档建立SHA-256快照：**14962个文件、5,560,940,297字节**。最终检查内容、大小与目录清单均未改变。
- 所有新日志、探针和重放只进入 `output/workbench-scalability-audit-c62c6b8/`。

## 2. 验收结果

| 检查 | 实际结果 | 说明 |
|---|---|---|
| 首次有界计数/语义测试 | 41项，12失败、0 errors | 11个计数预算失败＋1个null邻接异常类型失败；29个语义控制通过 |
| 优化后定向测试 | 41项全部通过 | 原expected与浮点/生命周期边界未放宽 |
| 完整Maven/Javadoc/覆盖率 | **942项，0失败/错误/跳过** | 核心538单元＋198集成，实验131单元＋75集成 |
| 独立干净检出 | **942总数＝940通过＋2项明确可选skip** | 仅缺少本地历史工件的兼容测试；不向祖先目录借输出 |
| Python独立校验器自测 | 11项通过 | 本机与独立检出均执行 |
| 浏览器 | 7类报告及离线负例通过 | 本机/独立检出，桌面与390px；0浏览器错误/HTTP请求 |
| Shared DLS/ETF真实运行对照 | **16个条件，0差异** | 两种图、DAX/JSON、两算法、1/4VM；Job/Task、全部指标/事件只排除声明墙钟字段 |
| 规范研究重放 | **504＋126＋546＝1176次成功** | 冻结优化代码，参数、输入与模型不改 |
| 独立工件复算 | 1176份、25,772,522项检查、0问题 | 明确范围内输入、工作量、时序和指标 |
| 基线/重放统计 | 6份索引、188,516项检查、0问题 | 所有对应条件主指标一致 |
| 规范轨迹精确对照 | **413952 Job、412776 Task、2802221条选定事件，0差异** | 1176运行逐解析值一致，不是provenance文件字节相同 |
| 保护文件 | 14962份全部一致，无新增文件 | 前两轮证据和原研究未覆盖 |

单元指令/分支覆盖率：核心 **65.28%/56.84%**、实验 **54.91%/56.40%**。门槛仍为核心0.54/0.48、实验0.39/0.42，不把UT与IT相加。

## 3. 实测热点与收益

| 编号 | 对象与固定条件 | 原实现实测 | 优化后实测 | 计数边界 |
|---|---|---:|---:|---|
| GS-001 | DAG星型，257节点/256边 | 33152次源列表比较 | 0次重复线性membership比较 | 替换为本次调用的集合索引；仍有索引构建、图遍历与排序 |
| GS-001 | 两层稠密图，128节点/4096边 | 266240次源列表比较 | 0次重复线性membership比较 | 不表示验证不再做工作 |
| GS-002 | DAX依赖内核，width512，含重复声明 | 262656次列表比较 | 0次 | 反射调用真实连边内核，不是XML解码计数 |
| GS-002 | JSON声明对称检查，width512 | 131840次列表比较 | 0次 | 只计声明membership的线性比较 |
| GS-002 | JSON连接内核，width512 | 262656次列表比较 | 0次 | 保留两方向声明去重与首次连接顺序 |
| GS-003 | DLS/ETF，256等长独立任务、1VM | 2828800次占用区间访问／33152次扫描 | **32640次／256次** | 真实准备后调度内核，排除准备、rank和排序 |
| GS-004 | LOCAL PEFT，16VM、每端32文件、1条边 | 15360次文件元素读取 | **64次** | 一次首次lazy OCT填表，准备期另计 |
| GS-004 | LOCAL PEFT，16VM、2条边 | 23040次文件元素读取 | **96次** | 每条边一遍原输出/输入扫描 |

DLS/ETF的等长重复查询案例符合：原访问数 `N(N−1)(N+4)/6`，优化后 `N(N−1)/2`。N=64/128/256的实测分别由45696/357632/2828800变为2016/8128/32640。256案例访问数减少约98.85%；不是声称端到端速度提高86.67倍。

LOCAL单边案例元素读取减少99.58%，即同类读取从240遍降为1遍。零交集仍缓存已读出的零值，避免再次扫描；同VM或无可行跨VM对不新增文件读取。

## 4. 实现与必须保留的行为

### 图校验与输入连接

[图验证器](../../simulator/src/main/java/org/workflowsim/WorkflowDagValidator.java)使用本次调用的惰性反向membership索引，原始邻接列表及其顺序仍是权威输入，duplicate/self/outside/asymmetric/cycle检查和Kahn深度遍历保留。GS-005异常契约修复：null父/子列表明确抛`WorkflowValidationException`，不再泄漏内部NPE。

[DAX解析器](../../simulator/src/main/java/org/workflowsim/WorkflowParser.java)以完整32位父/子Task ID编码有向边，首次出现才追加双向列表，索引在每次parse与每个DAX输入边界清空。[JSON解析器](../../simulator/src/main/java/org/workflowsim/WfCommonsJsonParser.java)先用局部声明membership核对对称性，再在一次连接过程中共享边集合；没有通过取父子声明并集“修复”不一致输入。

这些索引用额外O(E)局部空间换取重复扫描减少，不能解读为零内存成本或整个解析器O(1)。

### Shared DLS/ETF最近精确查询复用

[共享规划器](../../simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java)每个VM身份只保留一条最近的`ready/duration`原始double位键及结果；任何reserve使对应VM记录失效，每个public plan创建新规划器。候选与最终allocate共用该路径，其他算法仍走原路径。原始slot循环完全保留，总缓存空间O(V)，不同查询覆盖旧记录。

没有合并接触区间，也不丢弃零长度区间。例如`[1,2],[2,3]`、ready=2、duration=`2^-53`，原double运算可返回2，合并区间却会返回3；`[5,5]`、ready4、duration2原本返回5。这些边界均用原循环oracle和原始double位比较守护。

### LOCAL只在一次OCT计算内复用边字节

[LOCAL基类](../../simulator/src/main/java/org/workflowsim/planning/AbstractLocalCommPlanningAlgorithm.java)与[PEFT](../../simulator/src/main/java/org/workflowsim/planning/LocalPeftPlanningAlgorithm.java)仅在同一父任务的一次OCT填表内惰性复用边交集。缓存不跨prepare与首次lazy OCT阶段保留；不缓存动态副本或局部性选择。

同VM/PE短路保持，原父OUTPUT集合、子INPUT遍历及重复输入累加顺序保持，带宽式仍为 `bytes / 1e6 / min(bw_from,bw_to)`。可变文件的size/name/type/list在prepare后、首次OCT前发生变化时，仍按既有行为读取；已填OCT则保持原有代次生命周期。

## 5. 独立控制与研究等价性

新增测试包含：高扇入/出及32个固定种子DAG深度oracle、原邻接错误类型、DAX/JSON重复声明与解析器复用、真实准备后DLS/ETF计数、48次独立小DAG计划、gap/zero/sub-ULP、VM/插入失效、重复run/新对象，以及LOCAL文件读取和阶段变更的15项计数/语义测试。没有用优化后的生产函数生成新的expected。

另执行两种工作流（128个宽独立任务、80个层次任务）×DAX/JSON×DLS/ETF×1/4VM共16个真实条件。分别加载保存的c62基线JAR和优化classes，记录实际加载位置；Job/Task、所有指标及事件只排除明确的wall-clock字段后完全一致。这不是墙钟基准。

最终优化Java实现为 `177e18a`（前置图优化`6585b98`、槽位复用`11eac2a`）。在该冻结代码上重放1176个规范条件，独立统计与Job/Task/生命周期投影均与第二轮保存记录一致；输入、参数、seed、模型和dispatcher均未调整。执行模型仍为 `WORK_CONSERVING_TASK_EXECUTION_V2`，不因内部性能优化另造物理版本。

## 6. 复验与保留入口

```bash
mvn -Pjavadoc clean verify
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

本轮输出为有意保留的本地生成物，不是标准检出依赖：

- [原计数反例](../../output/workbench-scalability-audit-c62c6b8/first-counting-regressions-before.log)、[首次green](../../output/workbench-scalability-audit-c62c6b8/first-counting-regressions-after.log)、[完整门禁和实际优化后计数](../../output/workbench-scalability-audit-c62c6b8/reactor-clean-verify-candidate.log)。
- [独立检出Java](../../output/workbench-scalability-audit-c62c6b8/isolated-java.log)、[独立检出浏览器](../../output/workbench-scalability-audit-c62c6b8/isolated-browser.log)、[浏览器负例](../../output/workbench-scalability-audit-c62c6b8/isolated-browser-negative.log)、[覆盖率](../../output/workbench-scalability-audit-c62c6b8/coverage-final.json)。
- [Shared真实运行对照](../../output/workbench-scalability-audit-c62c6b8/shared-runtime-equivalence.json)、同目录基线/新实现输出、输入和探针。
- [规范工件独立复算](../../output/workbench-scalability-audit-c62c6b8/replay-evidence-audit.json)、[规范统计/基线比较](../../output/workbench-scalability-audit-c62c6b8/baseline-replay-statistics.json)、[轨迹精确对照](../../output/workbench-scalability-audit-c62c6b8/baseline-replay-trajectories.json)。
- [保护文件复核](../../output/workbench-scalability-audit-c62c6b8/protected-after.json)。

## 7. 仍然保留的边界

- 操作计数的作用域均明确；没有用单次机器时间证明总体加速，也没有紧墙钟CI阈值。所有源列表比较为零不等于哈希索引、排序、JSON/XML解码免费。
- 单条/VM缓存只复用连续相同查询；全不同查询仍可能保持原三次扫描阶数，不承诺所有宽图都降阶。
- 深度、宽度和数据集均有限；没有穷尽所有异步变更、带副作用的自定义Task/VM子类、遗留聚类/TIME_SHARED或跨平台组合。
- 本轮仍按既有同步模型与Task/VM身份语义验证；不引入线程安全或并发取消承诺。
- 逐流网络账本与真实硬件/故障/价格校准没有因本轮优化而建成，所有研究边界仍保留。
- 当前第三轮分支叠加在未合并的第二轮分支上；未自动推送，也不声称当前第三轮代码已在远端CI执行。
