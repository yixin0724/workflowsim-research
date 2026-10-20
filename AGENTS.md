# AGENTS.md — WorkflowSim-yixin 编码代理守则

> 本仓库是**研究工作流调度问题的仿真实验平台**（单工作流 DAG 调度 + 多工作流错峰竞争）。
> 科学性标准：忠实实现文献算法；实验结论**可复现、可追溯、证据链完整**（内部效度；明确不追求对真实集群的保真校准）。
> 本文件是所有代理动手前的必读件。修改它需人工评审——它是新代理的"入职第一课"。

## 项目事实（代理最容易搞错的五件事）

1. **Maven 多模块 reactor**：`simulator`（核心，无上游依赖）→ `experiments`（Workbench + 研究驱动器）→ `grouped-workflows`（第二篇论文模块，风格另见其目录内 AGENTS.md）。核心模块不得反向依赖实验模块。
2. **CloudSim 3 全局状态内核**：同一 JVM 严禁并发/并行仿真。一切运行必须经 `SimulationSession`（try-with-resources）建立会话边界。
3. 需要 JDK 17+、Maven 3.6.3+；编译目标 Java 8 字节码（`maven.compiler.release=8`）。
4. 工作流输入三源（Pegasus DAX / WfGen 合成 / WfInstances 真实派生）证据边界不同，**禁止混为同一 workload population**（见 `datasets/README.md`）。
5. Git 主分支工作流；`review-workspace/` 是审查工程临时目录（git-ignored），不代表产品状态。

## 常用命令（动手前先跑通读）

```bash
mvn -q compile                                  # 编译全部模块
mvn -pl :workflowsim test                       # 模拟器单元测试
mvn -Pjavadoc clean verify                      # CI 同款全门禁（测试+覆盖率+Javadoc）
python3 -B scripts/check-docs.py                # 文档链接/锚点校验
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py   # Python 审计器自测

# Workbench 冒烟（标准入口）
mvn -q -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"
```

**陷阱**：单独跑 `experiments` 模块测试必须带 `-am`——否则从 `~/.m2` 取旧 simulator jar，provenance 源码哈希域不同，rerun 测试会假红（这不是 bug，别去"修"）。

## 领域地图（30 秒版）

- 事件链：`WorkflowPlanner → ClusteringEngine → WorkflowEngine → WorkflowScheduler → WorkflowDatacenter`（`org.workflowsim` 根包）。
- 决策分三层且**不可跨层比较**：规划（planning/，离线 Task→VM+顺序）、调度（scheduling/，在线 ready Job→VM）、coherent 在线绑定（data/v2 的 DataflowVmAssigner）。
- 数据/网络物理模型八种（`DataMovementModel`）；证据侧车三种（网络账本 / 文件生命周期 V2 / 存储生命周期 V3），格式契约在 `docs/advanced/`。
- **契约文档体系在 `docs/`**：改行为必同步改对应契约文档，反之亦然。进入代码深区前先读 `docs/README.md` 找到对应契约。

## 不可违反（Do-Not 清单——违反即科学性损坏）

1. **失败必须显式**：禁止任何静默降级、兜底回退、吞异常后继续。宁可 fail-fast 中止，不产出误导性结果。
2. **测试期望值只有三个合法来源**：①一级文献手推（论文/教科书）②项目契约文档 ③守恒律/不变量。**严禁把实现输出抄作期望值**；唯一例外是显式标注的 golden-regression（职责是防漂移，不是证正确）。
3. `EvidenceCoreDiffer` 白名单**不可随手扩**：新增豁免=证据契约修订，必须同步 `docs/experiments/RERUN_DIFF_CONTRACT.md` 写明理由。
4. **events v1 事件 schema 不得擅改**：新增事件类型或关键字段需用户裁决（F-006/F-007 提案在案即此例）。
5. 聚类遗留区（`clustering/balancing` 家族、`ReclusteringEngine` 的 DC/DR/BLOCK/VERTICAL）是**历史兼容代码**：可修真实缺陷（先写红测试）；不得改语义、不得扩用。标准 `SimulationRunner` 只接受聚类 NONE——这是设计。
6. 已弃用调度标签（`MINMIN`/`MAXMIN`/`MCT`/`ROUNDROBIN` 等）被标准运行器**拒绝是设计行为**，不是待修 bug。
7. 一切可变全局静态（`Parameters`/`FailureMonitor`/`ReplicaCatalog`/`NetworkTopology` 等 67 个字段，清单见审查工程）必须在 `SimulationSession` 生命周期内访问；想新增全局静态，先自问为何不用会话注入。
8. 随机性必须走 `SimulationRandom` 命名流；禁止裸 `new Random()` 或种子字面量。
9. **浮点决定性纪律**：证据路径上的集合遍历与求和保持确定顺序（`LinkedHashMap`/`TreeMap`/ID 升序 tie-break）；改动前想清楚是否引入迭代序漂移。
10. Java 与 Python 审计器**语义必须对齐**：改 Java 指标/事件计算，必须同步核对 `scripts/` 对应复算口径。

## 风格与语言

- 维护轨代码的 javadoc/注释/文档一律**中文**，保持既有"根因注释"风格（F-xxx / PLAT-x / R 轮次标记 + 修复日期）。
- `grouped-workflows/` 例外：紧凑英文，规则见该目录内 AGENTS.md。
- 数据集中英混合命名沿用现状；新文件名用 ASCII。

## 仓库结构约定（2026-10-20 审查工程新增）

| 目录/文件 | 定位 | 新增项规则 |
|---|---|---|
| `simulator/` | 核心引擎（无上游依赖） | 算法/模型/证据类；不依赖 experiments |
| `experiments/` | 实验入口+示例+协议+复现 | 待后续拆分为 workbench/rerun/studies/legacy |
| `grouped-workflows/` | 执行包研究（第二篇论文） | 独立生命周期；不反向依赖 |
| `datasets/` | 工作流输入（三类含 .gitignore 反选） | 不新增全量语料；先查 `datasets/INDEX.json` |
| `docs/` | 契约文档体系 | 每新文档须入 `docs/README.md` 索引；`check-docs.py` 0 issues |
| `scripts/` | Python 审计器（纯 stdlib） | 不引入 Node 依赖；自测成对 |
| `output/` | 运行产物（gitignored） | 只存 tar 正本 + 当前活跃实验；旧堆须清理 |
| `review-workspace/` | 临时工作区（gitignored） | 工程收尾后整体清除 |
| `AGENTS.md` | 编码代理守则 | 修改需人工评审 |
| `Makefile` | 便捷命令 | 与 pom.xml 目标同步维护 |

**新增顶层目录/文件的纪律**：先问"为什么不放进已有 raise 的目录"；确定必须新增时同步更新本表 + `docs/README.md`（如涉及文档） + `check-docs.py` 结果。

## 完成定义（Done）

- 新逻辑：**先写失败测试**（期望值三源合法）→ 修复 → 全绿；回归测试与根因 javadoc 成对出现。
- 行为变更：对应契约文档同步更新且 `check-docs.py` 0 issues。
- 收尾：`mvn -Pjavadoc clean verify` 全绿；涉及 Python 审计域时其自测全绿。
- 未获用户裁决的事项（事件 schema、清理执行、契约方向变更）：**停在提案态**，不先斩后奏。

## 遇到"反直觉"的实现时

先假定它是**刻意的**：查该处 javadoc 的根因注释（F-xxx 标记）与对应契约文档；仍无法确认 → 询问用户或提 issue。**不要顺手"修复"**——本仓库大量代码的"怪"正是科学性设计本身（例如：白名单正则从常量派生、比较器用 `Long.compare`、解析器两阶段提交）。

## 深挖路线（按需加载，勿一次全读）

- 契约与边界：`docs/`（入口 `docs/README.md`）
- 算法语义/组合矩阵：`docs/algorithms/CATALOG.md` + `CONTRACTS.md`
- 数据集规则：`datasets/README.md`
- 审查工程沉淀（缺陷账/方法论，临时）：`review-workspace/`
