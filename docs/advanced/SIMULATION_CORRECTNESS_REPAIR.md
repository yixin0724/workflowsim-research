# 仿真正确性整改与验收记录

**本轮实现、回归、独立复算与研究影响复验已完成。** 这表示下述明确范围内的检查通过，不表示穷尽所有输入、所有遗留API或真实平台条件。

起点为 `97c66a03101d5bd6e75b67127c5a894b6e656342`（R13）。任何项目修改之前，先从干净的 main 创建 `fix/simulation-correctness-audit`；所有改动保留在该修复分支，不自动合并或推送 main。新研究使用冻结的 Java 修复提交 `8681f9c`，后续提交补充质量门禁、独立审计工具和文档。

## 1. 验收结果

环境：Homebrew OpenJDK 17.0.19、Maven 3.6.3、macOS aarch64；浏览器检查使用本机 Chrome，Node 工具依赖由锁文件固定。

| 检查 | 实际结果 | 范围说明 |
|---|---|---|
| 原基线 `mvn -o -B clean verify` | 576 项，0失败/错误/跳过 | 旧测试通过仍未发现本轮反例 |
| 原基线独立 Javadoc 门禁 | 4处错误 | Fat-tree小于号、PSO标题、两个指标段落标签；均已修正 |
| 修复后 `mvn -o -B -Pjavadoc clean verify` | **723项，0失败/错误/跳过** | 核心438单元/语义＋79集成；实验131单元＋75集成 |
| 独立干净检出，同样联合命令 | **723总数＝721通过＋2个明确可选skip** | 两个skip仅为缺少本地保留工件的历史兼容测试；当前模型复跑必跑，不向祖先目录借证据 |
| 单元覆盖率（指令/分支） | 核心64.55%/55.87%；实验54.91%/56.40% | 没有降低0.54/0.48、0.39/0.42门槛；不是把UT与IT相加的覆盖率 |
| 新协议完整矩阵 | **504＋126＋546＝1176次实际新仿真成功** | 新目录、新协议身份；非仅读取旧结果 |
| 独立证据数学审计 | **1176份bundle，25,772,522项检查，0问题** | 原始DAX/JSON、工作量、Job/Task/事件、依赖、VM区间及明确列出的指标 |
| 独立矩阵/统计审计 | **3项研究，89,848项检查，0问题** | 独立规范矩阵、DAG内种子汇总、精确符号检验/Holm、逐DAG效果 |
| 同模型共享条件 | 84／84／126项运行的核心数值一致 | 分别为三项研究两两交集；不把它们当新的独立样本 |
| 独立审计器自测 | 11项通过 | 包含大时钟、缺工作、缺指标、伪造、裁剪矩阵、拓扑语义和输出保护 |
| 真实浏览器 | 7类报告通过，0错误/HTTP请求 | 常规、网络、单报告、精确长整数种子、全失败、混合、千任务大图；桌面及390px；独立检出也验证 |
| 浏览器门禁负例 | 正确拒绝最后视口变化触发的HTTP请求 | 请求在检查器中被拦截，负例只作用于临时副本；永久自测已进入CI |
| 历史保护 | 旧1176份bundle历史完整性通过；9项协议/索引/压缩包哈希保持不变 | 不把旧物理或旧非标准PEFT结果重新认证为当前算法 |

大量规则检查不等于大量独立实验样本。经典DAG仍为5个、合成DAG为2个，不能用检查数量放大统计证据。

## 2. 已闭环的主要问题

| 范围 | 发现与修复 | 代表性回归 |
|---|---|---|
| CPU工作量 | 提交前节流使新Job获得到达前CPU时间；改为在执行集合改变前结清旧工作 | [执行语义回归](../../simulator/src/test/java/org/workflowsim/experiment/ExecutionModelRegressionTest.java) |
| 成本矩阵 | 规划、Job、Task窗口、流时与故障观察未一致消费矩阵；共用整数MI换算，保留原始与有效量 | [矩阵规划回归](../../simulator/src/test/java/org/workflowsim/planning/PlanningCostMatrixRegressionTest.java)、[工作量换算](../../simulator/src/test/java/org/workflowsim/utils/TaskExecutionModelTest.java) |
| 重试 | 复制被stage-in加长的Job、丢失映射/矩阵；重试从纯计算量重建并保留模型元数据 | [重试回归](../../simulator/src/test/java/org/workflowsim/reclustering/ReclusteringEngineTest.java) |
| LOCAL数据可达 | 未来副本提前可见，根/非根外部输入起点不符；副本记录可用时刻，修正输入就绪估计 | [数据可用时刻](../../simulator/src/test/java/org/workflowsim/planning/LocalDataAvailabilityPlanningTest.java) |
| PE兼容域 | LOCAL/PSO可选择不兼容VM；过滤可行域，PSO位置保持兼容，rank不平均不可行无穷值 | [PE约束](../../simulator/src/test/java/org/workflowsim/planning/PlanningPeCompatibilityTest.java) |
| PEFT原文 | 旧实现错误使用自身计算成本与出口均值；修正为原文后继成本、出口零 | [真正论文回归](../../simulator/src/test/java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java)、[独立手算](../../simulator/src/test/java/org/workflowsim/planning/LocalPeftSuccessorCostContractTest.java) |
| 大整数工作量 | Job创建int截断、MI×PE×百万指令溢出、完成进度绕回；checked long及饱和进度 | [内核工作量边界](../../simulator/src/test/java/org/workflowsim/model/ResCloudletWorkAccountingTest.java) |
| 小数字节 | 多处long累加使0.5B变0且跳过传输；端到端double、有限总量/速率、小流相对容差 | [输入/事件/等待](../../simulator/src/test/java/org/workflowsim/experiment/FractionalTransferEvidenceTest.java)、[微小流](../../simulator/src/test/java/org/workflowsim/data/FractionalTransferContentionEngineTest.java) |
| WED开销 | 小批次/零间隔丢掉已配置延迟，零数据切换模型改变抽样；统一分批释放规则 | [执行语义回归](../../simulator/src/test/java/org/workflowsim/experiment/ExecutionModelRegressionTest.java) |
| 时间与清理 | NaN/Inf或时钟舍入造成无进度事件；异常close只日志停止、遗留队列与控制状态 | [时间可表示性](../../simulator/src/test/java/org/workflowsim/utils/SimulationTimeValidationTest.java)、[中止清理](../../simulator/src/test/java/org/workflowsim/utils/SimulationSessionAbortTest.java) |
| XML输入 | DTD可在主输入哈希不变时改变runtime；禁止DOCTYPE/外部实体，使用已加固解析器 | [输入严格验证](../../simulator/src/test/java/org/workflowsim/WorkflowParserStrictValidationTest.java) |
| 研究认证 | 自述空/裁剪矩阵、协议身份和固定条件矛盾、假成功及协调缺指标可能通过 | [研究验证负例](../../experiments/src/test/java/org/workflowsim/experiments/network/NetworkStudyValidatorTest.java) |
| 复跑与报告 | 迁址误报、大整数合并、JSON Pointer白名单碰撞、在线标签INVALID、种子显示舍入 | [精确差异](../../experiments/src/test/java/org/workflowsim/experiments/rerun/EvidenceCoreDifferTest.java)、[Workbench链路](../../experiments/src/test/java/org/workflowsim/experiments/workbench/WorkbenchIntegrationTest.java) |

新测试首先在旧实现上观察到对应失败，再实施修复；夹具编译错误与校验器自身误报没有冒充生产缺陷。最后所有上述Java回归均纳入723项联合门禁。

## 3. 当前关键契约

- 新运行声明 `configuration.executionSemantics=WORK_CONSERVING_TASK_EXECUTION_V2`；旧v4缺字段时可读，但不能冒认新模型。
- `TaskExecutionModel` 统一规划与运行的矩阵换算：`round(seconds × vmMips)`。有矩阵却缺坐标时明确失败。
- `TaskOutcome.lengthMi` 保留解析归一化后的源工作量；`effectiveExecutionLengthMi` 记录当前尝试计算量，不含stage-in。Task窗口及故障观察使用有效量。
- 重试不继承旧执行时间或已注入传输的信封；STATIC数据准备前已有目标VM。
- `MI × PE × 1_000_000`必须可表示；正延迟相加不能变成非有限或同刻事件。不能用时钟粒度给任务补算到达前工作。
- WED interval=0表示不拆分，正值为子批次最大作业数；非空批次抽样一次并累计应用到尾部，空批次不消耗随机流。
- 内核reset不调用用户实体run/shutdown回调；只有实际进入初始化尝试的会话才取得清理归属。它是串行生命周期管理，不是并发取消API。
- 需求字节不是链路流量；stage-in总秒数是名义估计之和，不是并行网络墙钟或实际争用持有时长。bounded slowdown=1不证明无等待。

## 4. PEFT一级来源与夹具身份

来源为作者 Hamid Arabnejad 的[开放博士论文机构记录](https://repositorio-aberto.up.pt/handle/10216/92290)与[公开PDF](https://repositorio-aberto.up.pt/bitstream/10216/92290/2/129782.pdf)。第3章明确收录TPDS25(3):682–694、DOI `10.1109/TPDS.2013.57` 对应文章；这是作者重排收录版，不冒称IEEE VOR页码。

印刷p71（PDF83）式(7)：

```text
OCT(t,p) = max_child min_q [OCT(child,q) + w(child,q) + c(t,child,p,q)]
OCT(exit,p) = 0
```

印刷p73式(9)为 `OEFT=EFT+OCT`，Algorithm1使用ready-list和插入EFT。图1经系统OCR、同源字形/SVG几何解码，并由独立Fraction脚本逐项复算Table5/6；没有声称人工视觉审阅。

真正论文输入的PEFT/HEFT为 **122/133**，含110.1引导后 **232.1/243.1**。旧HEFT-origin输入在正确PEFT下为 **85/195.1**，保留独立回归；旧76/186.1及出口均值表来自非标准实现。旧R12/R13结果和预注册正文未改写，只增加明确历史说明。详见[一级来源夹具说明](../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md)。

## 5. 参考值与研究影响没有被掩盖

### 独立小/大算例控制

保留原基线JAR，SHA-256：`5cdd07462567df3290d92c31052463eb4b722c19371e0e40bd38243b3b2b7f81`。

Epigenomics997、LOCAL_HEFT、16VM：旧核心253528.02533242913秒、5个超出1MI容差的CPU下界异常；新核心253528.51872582204秒、0异常；旧Datacenter＋新规划253528.49572582205秒、4异常。规模回归先断言独立工作量下界与Task/Job包络，再检查参考值，不仅仅刷新黄金数字。

对历史campaign固定36格，又做旧核心、新核心、旧Datacenter＋新规划三组控制。HEFT论文装置12格不变；Cybershake100的V1赢家由PSO变为HEFT，Cybershake50的HEFT端点结果也改变。没有调整带宽或算法来维持旧赢家，也不从该子集外推完整360次历史换冠比例。

### 三套完整新研究

新协议分别为 `network-limited-r10-v3`、`peft-comparison-r12-v2`、`sensitivity-response-r13-v2`。504/126/546次均实际执行并通过生产与独立检查；在逐条件同输入对照中，结束时间改变161/97/414次，五个主字段任一改变217/101/440次。微小逐值差异不自动有实际意义，旧非标准PEFT也不能当作同一算法对照。

最大HEFT例（Cybershake100、16VM、endpoint）从41629.930933变为283759.323274秒。隔离控制确认：换回旧规划、保留新运行时，100个Job起止与旧二进制完全一致；仅恢复根输入估计已重现该case净增量的99.743%。新计划把7个约40GB根输入集中到VM0，有限端点共享和固定每VM顺序造成严重等待。这是所声明策略组合的退化，不能以“变慢”倒推新运行时工作量出错，更不能回滚正确语义保排名。

R10比较完整pipeline：LOCAL列表算法带每VM顺序，RANDOM/PSO仅映射、可从ready集合机会派发；不能只解释为映射优化。R12/R13三个列表算法执行纪律一致。完整解读、逐条件变化及保留说明见[研究影响复验](../../experiments/studies/correctness-repair/RESULTS.md)。

## 6. 独立校验器也接受了校验

[独立工具](../../scripts/STUDY_AUDIT.md)仅用Python标准库，不导入Java生产函数。第一轮真实数据接入发现checker的拓扑expected遗漏4个声明字段，导致882个误报；补齐明确常量和负例，没有放宽成忽略未知字段。时间约束也从按绝对时钟比例放宽改为 `abs_tol + 4×ULP`，CPU仅额外容忍1/MIPS；大时钟正常/缺工作反例均验证。

最终证据检查覆盖原始输入、阶段输入需求、有效工作量、事件顺序、Job/Task窗口、依赖和VM区间，复算明确列出的计数、等待/分位数、slowdown、利用率、成本等；统计检查独立注册矩阵、种子汇总、精确符号p与Holm，并输出逐DAG效果。每个报告列明`checkedMetrics`和`outsideMetricScope`，不存在仅凭退出0隐藏检查缺口的约定。

## 7. 复现入口与证据保留

```bash
mvn -Pjavadoc clean verify
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

浏览器、独立数学审计和新研究的完整命令见[构建指南](../getting-started/BUILD.md)、[审计工具说明](../../scripts/STUDY_AUDIT.md)和[协议修订](../experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md)。所有新运行/审计输出都要求新目录或新文件。

本轮本机保留在 `output/workbench-correctness-repair-97c66a0/`，不作为标准检出依赖：

- [Java联合门禁](../../output/workbench-correctness-repair-97c66a0/reactor-clean-verify-candidate.log)、[独立检出门禁](../../output/workbench-correctness-repair-97c66a0/isolated-clean-verify.log)、[覆盖率](../../output/workbench-correctness-repair-97c66a0/coverage-final.json)。
- [独立证据审计](../../output/workbench-correctness-repair-97c66a0/independent-evidence-new-v2.json)、[独立统计审计](../../output/workbench-correctness-repair-97c66a0/independent-statistics-new-v2.json)、[新旧数值对照](../../output/workbench-correctness-repair-97c66a0/research-impact-deltas.json)。
- [隔离机制控制](../../output/workbench-correctness-repair-97c66a0/impact-analysis/REPORT.md)、[浏览器最终检查](../../output/workbench-correctness-repair-97c66a0/browser-verification-complete.log)、[浏览器负例自测](../../output/workbench-correctness-repair-97c66a0/report-checker-selftest.log)。

## 8. 不作的承诺

- 没有真实云硬件、价格、故障或网络校准；流级网络不含包、丢包、ECN或自适应路由。
- 证据没有完整逐流账本，Python不独立重放网络分配；网络核心由对应单元/语义测试与指定控制检验。deadline、受控共享存储下界和wall-clock等未列为Python复算覆盖，另有Java契约测试，不能冒称逐字段全证。
- 没有穷尽所有超深DAG、极端值、遗留聚类/TIME_SHARED API、并发取消或多进程性能场景。标准Runner仍是NONE聚类、SPACE_SHARED、同JVM串行。
- 本地验证为macOS/JDK17/Chrome；未声称实际跑过远端GitHub Actions、Linux或Windows全部组合。
- 5个经典DAG的双侧符号检验最小原始p为0.0625，不能达到0.05；2个合成DAG更无普遍推断力。不显著不等价，VM/异构/拓扑轴变化不证明单因素因果。
- 本轮修复和测试不是“从此不存在任何未发现错误”的证明；未来变更仍须维持这些门禁与独立证据纪律。
