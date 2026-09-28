# 第二轮：配置组合、DAG性质与参考指标审计

**状态：本轮约定范围的检查、修复、语义保持优化和验收完成。** 本轮按独立根因确认 **5个原有问题（CP-001至CP-005）**，不是把15个失败用例算作15个Bug；另外单列两项复核/修复期间的边界闭环，不混入原始问题数量。

## 1. 合并后处理与基线

- 用户确认PR已合并；Git历史核对PR #16合并提交为 `1b6baa84ffe97944421e6ea4e9e91f22e20c2854`。
- 本地main已快进到远程main；旧本地修复分支在确认祖先关系后正常删除，未强制删除或改写历史，远程旧分支保留。
- 在本轮任何项目修改前创建 `fix/configuration-property-audit`。本轮使用英文Conventional Commits，不自动推送或合并main。
- 基线文件树为上一轮已验收的 `8e1c5365e94c23516883fdacd633ba34e9f7e201`。本机重新执行 `mvn -o -B -Pjavadoc clean verify`：723项，0失败/错误/跳过，Javadoc和原覆盖率门槛通过。
- 已核对[合并后实际GitHub Actions](https://github.com/yixin0724/workflowsim-research/actions/runs/36391719476)：head为上述合并提交，Ubuntu的Maven/Javadoc、Python自测、Chromium、报告生成和浏览器步骤均success。这是已合并基线的远端验证，不冒称本轮尚未推送分支已在远端跑过。

## 2. 最终验证结果

| 验证 | 规模与结果 | 口径 |
|---|---|---|
| 本轮原始反例 | 171项，15失败、0 errors | 对应5个独立根因；正常控制保留 |
| 修复后首批定向测试 | 171项全部通过 | 包括合法组合、深链和独立小图参考 |
| 完整本机Maven/Javadoc/覆盖率 | **901项，0失败/错误/跳过** | 核心497单元＋198集成，实验131单元＋75集成 |
| 独立干净检出 | **901总数＝899通过＋2项明确可选skip** | 仅两个历史工件兼容测试缺本机保留目录；当前模型必跑路径不跳过 |
| Python独立工具自测 | 11项通过 | 原门禁不降级 |
| 浏览器 | 7类报告通过，0浏览器错误/HTTP请求 | 本机与独立检出均通过，含晚期HTTP负例自测 |
| 合法配置缩放性质 | 117个配置格，234次运行 | 不从“能跑的配置”反推合法矩阵 |
| 随机小DAG规划参考 | 8策略×24固定种子×原始/置换＝384次规划 | 独立数组DP、插槽与关键路径枚举，不调用生产排名helper造expected |
| 深DAG | 固定4096节点，7项控制 | 六条rank/OCT递归目标和迭代validator正控制 |
| 故障/开销/到达/矩阵交互 | 4基础格、12次运行 | 逆序重放及deadline纯观察；基线重试次数A/B/C/D为0/1/1/1 |
| 最终代码规范研究重放 | 504＋126＋546＝1176次成功 | 正常条件回归，不是新增独立DAG样本 |
| 独立工件复算 | 1176份，25,772,522项检查，0问题 | 采用既有明确检查范围 |
| 基线与最终重放统计对照 | 6份索引，188,516项检查，0问题 | 所有对应条件的五个主指标数值一致 |
| 轨迹级精确对照 | 413,952 Job、412,776 Task、2,802,221条选定生命周期事件 | 1176个运行全部逐解析值一致；排除已声明的决策墙钟计时，不是原始文件字节相同 |
| 历史证据保护 | **7091个文件全部哈希/大小一致，保护目录无新增文件** | 2,723,619,169字节，旧三研究、上轮新三研究及归档/独立报告均只读 |

本轮单元指令/分支覆盖率为核心 **64.97%/56.56%**、实验 **54.91%/56.40%**。门槛仍是核心0.54/0.48、实验0.39/0.42；没有把UT与IT覆盖率简单相加。

## 3. 五个原有问题与修复

| ID | 独立根因 | 实际反例 | 修复与验证 |
|---|---|---|---|
| CP-001 | 非零到达时，在必需模型校验前解引用null聚类参数 | 18项配置检查中，该格抛NPE而非声明的IAE | 先检查必需模型，再判断跨模型约束；[配置回归](../../simulator/src/test/java/org/workflowsim/utils/SimulationConfigValidationOrderTest.java)通过 |
| CP-002 | 合法SHARED映射＋matrix参考仍按原始MI计算 | 实际1秒，却按100秒源工作量发布100.21秒下界；三个矩阵反例失败 | 按每个PE兼容候选的有效整数MI求参考，不用实际选中VM的单个effective值替代候选表；[独立参考](../../simulator/src/test/java/org/workflowsim/experiment/ControlledSharedStorageIndependentReferenceTest.java)通过 |
| CP-003 | 共享参考把preExecution的并行父输入套用串行服务和 | 合法实际结束13.21秒，却宣称下界22.21秒 | preExecution不发布此参考，**不禁止合法run**；legacy/fixed仍保留已证明的乐观参考 |
| CP-004 | long deadline混入double运算丢失低位 | 4个大数边界失败，包括`T=2^63,D=Long.MAX_VALUE`应晚1秒却误判 | 用精确long与double二进制值求差/比较后才转换观察量，保留未请求与未完成优先级；[deadline独立测试](../../simulator/src/test/java/org/workflowsim/experiment/DeadlineIndependentObservationTest.java)通过 |
| CP-005 | 有效深DAG的rank/OCT依赖调用栈 | validator通过4096链，六条up/down/OCT路径StackOverflow | 使用已校验的拓扑顺序迭代求值；保留原child/VM遍历、算式括号、PE域、ID平局和派发顺序；[深图](../../simulator/src/test/java/org/workflowsim/planning/PlanningDagDepthAuditTest.java)与[小图独立参考](../../simulator/src/test/java/org/workflowsim/planning/PlanningDagReferencePropertyAuditTest.java)通过 |

深图优化的结论是消除了所测rank/OCT路径的递归栈依赖，不是宣称所有大图都更快。没有混入未经测量的宽图缓存或调度策略变更。

## 4. 配置组合与性质设计

### 117格字节/带宽同比缩放

| 策略组 | 受控范围 | 格数 |
|---|---|---:|
| 六种内置在线调度 | legacy/fixed×SHARED/LOCAL；DATA仅LOCAL | 22 |
| 七种独立映射＋RANDOM/PSO | 四个非Fat-tree模型×两存储，加LOCAL Fat-tree | 81 |
| LOCAL HEFT/CPOP/PEFT | LOCAL×三种preExecution | 9 |
| 五种受控共享存储DAG规划 | SHARED＋legacy | 5 |

[缩放性质测试](../../simulator/src/test/java/org/workflowsim/experiment/ConfigurationMetamorphicIntegrationTest.java)使用非连续VM ID、异构MIPS、正小数字节、非根外部输入与分叉汇合。独立任务策略使用无依赖输入，不混入被禁止组合。

每格保持计算量、延迟和MIPS不变，将文件字节与Host/VM/存储/模型链路速率同时乘2：映射、时序和CPU成本应不变，需求和声明文件带宽成本应乘2。另逐Task检查原始/有效MI、CPU下界、Job包络和父先于子。这是具体变形性质，不是“增加VM/带宽必然改善makespan”的错误全局断言。

### 故障、开销、到达和矩阵同时启用

[交互测试](../../simulator/src/test/java/org/workflowsim/experiment/StochasticArrivalInteractionIntegrationTest.java)固定RANDOM＋STATIC、SHARED legacy、NONE聚类、SPACE_SHARED，故障和WED/queue/post三个开销同时开启：

| 格 | 两个工作流到达 | 矩阵 | seed | 观察deadline |
|---|---|---|---:|---:|
| A | 0.5／0.75 | 无 | 5 | 1 |
| B | 0.5／0.75 | 有 | 22 | 30 |
| C | 2／4 | 无 | 22 | 1 |
| D | 2／4 | 有 | 5 | 30 |

先A/B/C/D基线，再逆序重放，再仅改变deadline。按源Task归属检查到达门控、静态重试VM/矩阵与失败父谱系、queue/post因果时间、逻辑完成集合及终端tail；完整轨迹只剔除两个已声明wall-clock字段。各格不硬编码重试次数，但整体要求真实重试被触发；本次基线共3次。WED无单独采样账本，因此没有伪造WED随机抽样的独立重放声明。

## 5. 复核与修复期间的边界闭环（不混入原有问题数）

### R-01：准备代次与PEFT诊断缓存

只读复核指出，正常`run()`原本会清缓存，但新扩展的`prepare()`后诊断在重复准备时可能读取旧OCT。改变后继工作量后，参考优先级仍为1而不是2。现在基类的final准备流程调用内部缓存失效钩子，PEFT按每次准备代次清OCT；验证了工作量变化及同ID新VM对象。

[生命周期测试](../../simulator/src/test/java/org/workflowsim/planning/PlanningDiagnosticLifecycleTest.java)也明确：`priorityOf`负责首次填表，`optimisticCostOf`只读取已有表。首次测试误省略该预热造成的错误已纠正，不把夹具调用前提错误计为生产缺陷。

### R-02：可选参考不能改变实际执行结果

CP-002修复曾使参考扫描未选中的不可表示矩阵坐标，导致原混合报告从7成功/9失败变成全失败；Java测试通过后，浏览器生成门禁拦住了它。

最终处理为：候选计算量、传输乘积/转换、完整MI信封或累计参考时间不可表示时，参考明确返回 `UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST`。不跳过坏候选后发布缺乏依据的下界，不回退原始MI，也不改变原执行/规划校验策略。**实际选中坏坐标仍须失败**，已完成的有效所选坐标不再被可选观察连带改成FAILED。原browser夹具和种子未改，已恢复7成功/9失败并通过全部浏览器门禁。

## 6. 语义保持与研究影响

最终Java实现为 `1a15d91`（前置配置修复`31438ef`、指标修复`e557b4e`）。正常规范矩阵在该冻结代码上重放，全部与合并前记录一致：

- 五个主指标逐值相等；Job与Task outcome完整投影一致。
- 选定事件为stage-in创建、ready、scheduling cycle/decision、returned、retry创建、数据模型与Task执行；仅排除`decisionElapsedNanos`。
- 没有修改输入、带宽、随机种子、dispatcher、旧黄金值或原研究。

这不表示本轮所有数字都不改变：受影响的SHARED＋matrix参考、大long deadline及不适用的preExecution参考必须按修复后的定义变化。原规范研究使用LOCAL且deadline未请求，故这些修复不改变其对应数值。执行模型标识仍为 `WORK_CONSERVING_TASK_EXECUTION_V2`，源代码身份记录本轮实现；不是另造物理模型或调参保排名。

## 7. 复验入口与保留证据

```bash
mvn -Pjavadoc clean verify
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

浏览器命令见[构建指南](../getting-started/BUILD.md)，独立数学审计见[工具说明](../../scripts/STUDY_AUDIT.md)。本轮本机证据保留于 `output/workbench-config-audit-1b6baa8/`，不是标准检出依赖：

- [最终本机门禁](../../output/workbench-config-audit-1b6baa8/reactor-clean-verify-final-v2.log)、[独立检出门禁](../../output/workbench-config-audit-1b6baa8/isolated-final-java.log)、[覆盖率](../../output/workbench-config-audit-1b6baa8/coverage-final.json)。
- [最终独立工件复算](../../output/workbench-config-audit-1b6baa8/final-replay-evidence-audit.json)、[基线/最终统计](../../output/workbench-config-audit-1b6baa8/final-baseline-statistics-audit.json)、[轨迹精确对照](../../output/workbench-config-audit-1b6baa8/final-baseline-trajectories.json)。
- [原始反例](../../output/workbench-config-audit-1b6baa8/first-independent-regressions-before.log)、[修复后定向验证](../../output/workbench-config-audit-1b6baa8/first-independent-regressions-after.log)、[额外边界验证](../../output/workbench-config-audit-1b6baa8/closure-boundaries-after-v2.log)。
- [浏览器检查](../../output/workbench-config-audit-1b6baa8/browser-verification-v2.log)、[独立检出浏览器](../../output/workbench-config-audit-1b6baa8/isolated-final-browser.log)、[保护文件最终核对](../../output/workbench-config-audit-1b6baa8/protected-evidence-final.json)。

## 8. 仍然保留的边界

- 117格和4个随机交互格是明确设计的覆盖，不是所有参数笛卡尔积；RL_POLICY、遗留聚类/TIME_SHARED等不在这组矩阵内。
- 4096深链不等于任意规模保证。高扇入/出邻接表扫描、DLS/ETF宽图重复插槽搜索和LOCAL文件交集重复计算仅是未测量性能候选，本轮未顺手修改。
- 逐流网络账本、真实硬件/价格/故障校准没有因此建成；不声称独立重放所有网络内部过程。
- 新分支未自动推送；已核对的是合并基线Ubuntu CI。本轮独立检出在macOS/JDK17/Chrome验证，不扩大为Windows、所有JDK或所有浏览器已认证。
- 非可执行候选影响的是参考可用性；本轮没有悄悄改成“所有矩阵坐标预检”新政策，也没有把无效所选坐标包装成成功。
- 测试与检查计数不等于独立DAG样本，不构成算法普遍最优、总体因果或从此不存在未知错误的证明。
