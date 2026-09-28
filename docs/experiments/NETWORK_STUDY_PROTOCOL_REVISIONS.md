# 网络研究协议修订与只读认证

## 参数矩阵相同，不等于执行模型相同

正确性整改后的新运行使用 `configuration.executionSemantics=WORK_CONSERVING_TASK_EXECUTION_V2`，
取自核心 `TaskExecutionModel.EXECUTION_SEMANTICS`。工作量与执行时钟修正可能改变科学结果；
不能把新运行冒认为旧冻结实验，也不能为维持旧数值而降低验证要求。

| 参数矩阵 / CLI 变体 | 冻结历史协议（只读兼容） | 新执行协议 | full / smoke 次数 |
|---|---|---|---:|
| R10 / `r10`（默认） | `network-limited-r10-v2` | `network-limited-r10-v3` | 504 / 36 |
| R12 / `peft-comparison` | `peft-comparison-r12-v1` | `peft-comparison-r12-v2` | 126 / 18 |
| R13 / `sensitivity-r13` | `sensitivity-response-r13-v1` | `sensitivity-response-r13-v2` | 546 / 30 |

`protocol ID + mode` 共同构成注册身份。smoke 是有自己输入、种子和条件声明的微型矩阵，
不是任意裁剪的 full，也不能只把 mode 改成 full 就取得完整研究认证。
执行器只生成表中的新协议；没有“用当前代码输出旧模型身份”的开关。

历史协议与结果保留于 [R10](<../../experiments/studies/network-limited-r10/PROTOCOL.md>)、
[R12](<../../experiments/studies/peft-comparison-r12/PROTOCOL.md>)、
[R13](<../../experiments/studies/sensitivity-r13/PROTOCOL.md>)。
它们记录当时的模型和观测，不能作为修正后模型的黄金值、性能结论或本次验证结果。
原预注册叙述作为历史记录保留，新自动报告不继承其机制判定。

## R12/R13：旧 LOCAL_PEFT 是非标准实现

依据 Hamid Arabnejad 的博士论文 *QoS based workflow scheduling on heterogeneous resources*（2016），
见作者[公开收录页](<https://repositorio-aberto.up.pt/handle/10216/92290>)和[公开全文](<https://repositorio-aberto.up.pt/bitstream/10216/92290/2/129782.pdf>)
第3章**印刷 p.71（Eq.7 及出口条件）、p.73（Algorithm 1）**；这里指印刷页码，不是 PDF 阅读器的页序号。

- `peft-comparison-r12-v1` 与 `sensitivity-response-r13-v1` 使用的旧 `LOCAL_PEFT`，在 OCT 递推中错误计入**当前任务自身**的 `w(t,p)`，并把出口 OCT 设为出口任务平均计算成本。这与原文定义不符，属于非标准实现，不是已被论文验证的 PEFT。
- 标准 Eq.7 计入的是**后继任务**在候选后继处理器上的 `w(child,p')`，出口 `OCT=0`。R13 的 ready-list 修复只调整就绪任务分配纪律，没有纠正上述递推或出口条件；运行成功、旧黄金吻合及证据完整性通过不能替代算法正确性证明。
- 因此旧-v1的数值只能记录当时非标准实现的行为，不能用于解释标准 PEFT 的优劣、“同构退化/异构激活”或其他 OCT 机制。历史正文中的论文复现声明、预注册判定和机制解释均须在这一纠错背景下阅读，不能直接继承为标准算法结论。

新 `peft-comparison-r12-v2` 与 `sensitivity-response-r13-v2` 研究将以符合原文 Eq.7 的后继计算成本和出口零值为前提，
结合修正后的执行模型，并通过真正的论文 fixture 校核算法实现。标准 PEFT 的表现与机制仍需新研究评估；
本说明不声明新研究结果已经生成。旧协议、原预注册、历史数值表和冻结工件均不改写，也不重标为标准 PEFT 证据。

## 注册声明与输入身份

[NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>)
同时提供新执行计划和无文件 I/O 的注册声明：后者不解析原机器输入、不生成合成文件，
不从待验证索引反向推定“应该有哪些实验”。两者复用同一条件、算法、种子和计数构造。

注册声明固定：

- 输入 ID、顺序、家族、经典/合成来源及 SHA-256；经典输入指纹与冻结语料对应，合成输入由保留的确定性生成器固定。
- full 的统一资格排除项，以及各 mode 的完整 VM/网络/异构度、候选规划器和种子集合。
- 端点/链路参数、传输起点、统计口径与新执行模型标识。

输入和排除项中的原机器绝对路径作为保留元数据处理，不要求它们在验证机器上存在，
但必须对应注册逻辑输入，并与逐运行输入、显式数据集根一致。
修改工作流内容、种子、候选集或条件需要另外声明研究协议，不能继续使用同一注册 ID。

## 验证器究竟证明什么

[NetworkStudyValidator](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyValidator.java>)
执行以下只读检查：

1. 根索引与保留协议 JSON 的值一致，且匹配注册的完整矩阵与固定参数；拒绝空矩阵、协调裁剪、重复/遗漏/未声明单元。
2. 对协议文件的**原始字节**计算 SHA-256，绑定每个 run 的 study ID、protocol logical ID、available 标志、协议哈希和研究驱动构件身份。
3. 逐个验证 manifest/metrics/events，再按执行器的标准配置和平台构造核对完整快照：包括 overhead、failure、clustering、costModel、Host/VM/存储/价格/拓扑及 pin/preflight/actual 放置。
4. 核对算法标签、输入身份、索引指标与实际完成性；索引的 SUCCESS 不能覆盖证据中的 INCOMPLETE。发布的五个指标必须存在、为 JSON 数值、可表示为有限 double 且精确值非负；该规范研究的平均建模区间利用率还须在 `[0,1]`。符号和上界按精确十进制判断，不允许将微小负值或略大于1的值经 double 舍入后放行；此限制不扩展到其他通用指标。
5. 从运行记录重算汇总，使用精确 JSON 数值比较；不能借助 double 舍入隐藏声明差异。

算法契约说明文本和历史 source/binary hashes 不要求与当前源码相同；算法标签仍必须匹配。
旧协议原始 v4 缺少 executionSemantics 的形式继续可读，但不能塞入当前模型标识再冒认旧协议；
新协议则必须同时在协议和运行配置中声明当前执行模型。

CLI 保留 `NETWORK_STUDY_VALIDATION PASSED runs=...` 前缀，并附加协议、mode 和验证范围：
`HISTORICAL_PROTOCOL_INTEGRITY` 或 `DECLARED_PROTOCOL_INTEGRITY`，明确 `NOT_A_CURRENT_CODE_RERUN`。

**这证明注册声明及其保留证据满足交叉约束，不证明作者身份、真实云校准、算法优越性，
也不证明历史科学量在当前代码下重跑不变。** 协议 SHA 是一致性绑定，不是数字签名。
需要实际复跑和差异判定时使用 [D2 契约](<RERUN_DIFF_CONTRACT.md>)，保留 `DIVERGED` 等合法结果，
不要改写冻结工件来制造 `IDENTICAL_CORE`。

## 当前运行示例

```bash
# 新目录，微型 PEFT 对比；当前生成 peft-comparison-r12-v2
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="smoke /absolute/datasets /absolute/new-study-output peft-comparison"

# 同一验证入口可读取已知的新协议和历史协议
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/study-output/network-study.json"
```

验证器不修改目标证据；上例中的 Maven 编译仍会生成构建输出。
full 研究需要显式安排预算和全新输出目录，不属于默认 smoke 门禁。

## 统计与保留边界

本修订不改变先按 DAG 汇总种子、再按来源/VM/网络/异构度配对的公式，也不增加独立 DAG 数。
经典组仅 5 对，双侧符号检验最小原始 p 为 0.0625；不得把未达 0.05 当作算法等价证明。
同构/异构条件的总算力、VM32 的拓扑变化，以及逐 DAG 的效果分布仍需随结论呈现。

交接时保留完整协议原字节、索引、逐运行三件套和生成输入；不要只交汇总表，也不要把历史
结果的成功记录当作本次执行证据。原目录、失败试点、压缩包和新修订结果应分别登记保留用途。
新执行与修复测试一律使用新目录或测试临时目录，不覆盖原冻结输出。
