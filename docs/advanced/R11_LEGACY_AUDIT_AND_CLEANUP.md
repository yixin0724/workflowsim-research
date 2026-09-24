# R11 全仓遗留审计与清理验收记录

> **状态**：已完成（2026-09-24）。本轮包含两个已合并的清理任务：
> 任务 A `chore/cleanup-stale-artifacts`（`cd9c23b` → merge `bafdfac`）、
> 任务 B `chore/cleanup-vendored-deadcode`（`f27ba8a` → merge `e022373`）。
> 两次合并前均通过完整门禁 `mvn clean verify`（JDK 17，覆盖率达标）。
> 同轮次的 D2 复跑与差异比对能力见 [`RERUN_DIFF_CONTRACT.md`](../experiments/RERUN_DIFF_CONTRACT.md)
> 与 [`PLATFORM_UPGRADE_R10.md`](PLATFORM_UPGRADE_R10.md)。

---

## 1. 审计范围与方法

- **代码**：全仓 466 个 Java 文件逐一扫描引用关系。结论：`org.workflowsim`
  命名空间**零孤儿类**；死代码全部集中在 vendored CloudSim 子树（见 §4）。
- **文档**：59 个 Markdown 分诊为 CURRENT 42 / HISTORICAL 12（均有保留横幅，
  全部保留）/ STALE 3 / UNCERTAIN 2。
- **产物**：`output/`、根目录、`scripts/` 的陈旧文件与目录逐项取证。
- **复核纪律**：审计由并行子代理完成初筛，但**每一个删除候选都在删除前由
  主控独立复核**（用户明确要求："删除文件是高危险操作，务必再次确认"）。
  复核推翻或修正了 4 项初筛结论（见 §6 诚实记录）。

## 2. 任务 A：陈旧产物清理与文档修缮

### 2.1 删除项（每项附复核证据）

| 删除项 | 证据 |
| --- | --- |
| `VISUALIZATION_FEATURES.md`（根目录，tracked） | 全仓文档零引用其文件名；其描述的 4 个类（ExperimentConsoleSummary/CsvWriter/HtmlReportWriter/MyConfigurableExperiment）已于 R9 删除，其余提及均为描述该删除的历史文档 |
| `IMPLEMENTATION_SUMMARY_WAITING_TIME_METRICS.md`（根目录，tracked） | 全仓零引用；`USE_TIMESTAMP_DIR` 在 Java 源码零命中；与 `docs/IMPLEMENTATION_SUMMARY_WAITING_TIME_FIX.md`（带 R9 横幅）内容重复 |
| `scripts/build.sh`（tracked） | 全仓零引用；与 Makefile 目标完全重复；两者均引用**不存在的** `-Pexperiments` profile（R10 起 experiments 为默认 reactor 模块）与不存在的 `docs/BUILD_COMMANDS.md` |
| `p7-output/`（空目录，untracked） | `P7BaselineExecutor` L72 默认输出目录，运行时自动重建 |
| `.DS_Store` ×2（根目录、output/） | untracked、gitignored 的 macOS 产物 |
| `output/test-new-features/`（36K，gitignored） | 已删除的 R9 可视化写入口的历史输出，零引用 |
| `output/network-study-r10-qualified/`（**491M**，gitignored） | 见 §3 专项取证 |

### 2.2 修复项

- `Makefile`：4 处 `-Pexperiments`（不存在的 profile）移除；尾部文档指向改为
  `docs/getting-started/BUILD.md`（原指向不存在的 `docs/BUILD_COMMANDS.md`）。
- `.gitignore`：移除已失效的 `output/test-new-features/` 行。
- `docs/getting-started/DATASETS.md`：2 处失效链接 `../datasets/README.md` →
  `../../datasets/README.md`。
- `docs/README.md`：目录树补 `WORKBENCH.md`、`R9_CLEANUP_AND_PROBES.md`、
  `PLATFORM_UPGRADE_R10.md`。
- `experiments/README.md`：内容分类表补 network-limited-r10 研究、workbench/
  与 rerun/ 驱动包。
- `docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md`：补 R10 历史协议横幅
  （与 RESULTS 页对齐；此前只有 RESULTS 有横幅）。
- `docs/algorithms/LEGACY_MIGRATION.md`：补 R9 注记（`HEFT`/`DHEFT` 规划枚举
  已删除，§2 旧代码示例仅用于识别历史脚本）、§5 Q1 例外说明、更新日志条目。
- `simulator/.../planning/package-info.java`：更正"HEFT/DHEFT 保留用于兼容性
  探索"的过时注释（R9 已删除）。

## 3. `network-study-r10-qualified/` 删除取证（专项）

该目录是 491M、504 个 run 的完整研究输出，删除前做了逐 run 的机械比对：

1. 504/504 run 目录名与 `network-study-r10-final/` 完全一致；
2. `results.md` 与 `-final` **字节级相同**；
3. 504/504 个 per-run `protocol.json` 与 `-final` 相同；
4. 504 个 `result.metrics.json` 全部存在字节差异，但对全部叶子字段做深度
   比对（脚本化，排除已知波动字段）后：**非波动字段零差异**——差异仅存在于
   `totalSchedulingDecisionWallClockNanos` / `totalPlanningDecisionWallClockNanos`
   两个墙钟纳秒计时字段，核心量（makespan、成本、利用率等）完全一致。

结论：`-qualified` 是 `-final` 认证协议的**重复执行**（独立跑出的第二份合格
证据，被 `-final` 取代），不是不同的研究结果。据此删除，释放 491M。

**保留**（不可删除）：`output/network-study-r10-final/`（集成测试
`RerunDiffExecutorIntegrationTest` 硬依赖）、`output/network-study-r10/`
（v1 试点，PROTOCOL §44 明确保留）。

## 4. 任务 B：vendored CloudSim 死代码删除（68 个文件）

全部位于 `simulator/src/main/java/org/cloudbus/cloudsim/` 下：

| 组 | 数量 | 内容 |
| --- | --- | --- |
| `power/` 子树 | 33 | 能耗模型（power/ 19 + power/models/ 13 + power/lists/ 1），WorkflowSim 不使用 |
| `network/datacenter/` | 17 | CloudSim 3.0.3 自带网络数据中心包；`FAT_TREE_PRINCIPLES.md` §0-4 在 R6 即判定"不可复用" |
| 顶层孤儿 | 7 | SanStorage、UtilizationModelNull、UtilizationModelPlanetLabInMemory、UtilizationModelStochastic、VmSchedulerSpaceShared、VmSchedulerTimeSharedOverSubscription、lists/ResCloudletList |
| `distributions/` | 8 | Exponential/Gamma/Lognormal/Lomax/Pareto/Uniform/Weibull/Zipf（互引闭环，无外部使用者） |
| `util/` | 3 | WorkloadModel、WorkloadFileReader、ExecutionTimeMeasurer |

**复核方法**：对全部 68 个类名，在 `simulator/src`、`experiments/src`、
`datasets` 的所有 Java/XML/properties/txt/md/json 文件中做词边界检索；除删除
集内部互引外，唯一外部命中是 `PowerDatacenter`——4 个存活文件
（`CloudSimTags`/`Datacenter`/`Cloudlet`/`DatacenterBroker`）中的 **Javadoc
注释文字**，非代码引用。全仓无 `Class.forName`/反射使用。javadoc 插件本就
排除 `**/org/cloudbus/**`，无文档构建影响。

**明确保留**：`org.cloudbus.cloudsim.network` 顶层包（`core/SimEntity.java`
L676-677 使用 `NetworkTopology`/`TopologicalGraph`）、`UtilizationModel` 接口、
`UtilizationModelFull` 及其余全部存活 CloudSim 核心类。

配套：`docs/research/FAT_TREE_PRINCIPLES.md` 顶部加清理轮注记（精读对象已
删除，历史研读记录完整保留，文中源码行号引用不再可解析）。

## 5. 门禁与验证结果

| 检查 | 任务 A 后 | 任务 B 后 |
| --- | --- | --- |
| `mvn clean verify`（全量：单测+集成+覆盖率） | ✅ BUILD SUCCESS | ✅ BUILD SUCCESS |
| `RerunDiffExecutorIntegrationTest`（D2 历史证据复跑） | ✅ 3/3 | ✅ 3/3 |
| `ExamplesCliSmokeIntegrationTest` | ✅ | ✅ 40/40 |
| `FatTreeCampaignGoldenIntegrationTest`（黄金值） | ✅ | ✅ 6/6 |
| `make help` 冒烟 | ✅ | — |

**sourceTreeSha256 影响声明**：任务 B 改变源码树，此后复跑任何历史证据时
`rerunSourceTreeSha256` 与 `originalSourceTreeSha256` **必然不同**。这是设计
内行为：`RerunDiffExecutor` 将源码树差异记录为报告 note，verdict 只由核心量
比对决定（集成测试在删除后 3/3 通过即为实证）。

## 6. 诚实记录：复核推翻/修正的初筛结论

1. **`PsoReproductionExperiment.java` 保留**（推翻子代理的删除建议）：
   `PLATFORM_AUDIT_REPORT.md` L247 明确引用其 Javadoc 作为审计发现
   REPRO-3/REPRO-4 的文档化位置，删除会使历史审计引用悬空。同目录的
   `HeftPaperReproductionExperiment` 亦保留（`BUILD.md:98` 有运行命令）。
2. **mystudy 教学文档无需修改**（初筛判为"待人工决定"）：复核发现
   `RUN_EXPERIMENTS.md` 与 `QUICK_START.md` 本来就写明"在 `mystudy/` 下
   **新建**"，`mystudy` 包不存在是教学设计而非文档错误，不做多余改动。
3. **删除文件数修正：56 → 68**：子代理初筛报"56 个 vendored 死文件"，
   逐文件枚举复核后实为 68 个（power 33 + network.datacenter 17 + 独立 18）。
4. **`-qualified` "与 -final 数值相同"的说法需精确化**：并非字节级副本，
   504 个 metrics 文件全部存在墙钟计时字段差异；核心量经深度比对确认一致
   （§3）。删除决定基于精确取证而非初筛的粗略表述。

## 7. 相关文档

- [`R9_CLEANUP_AND_PROBES.md`](R9_CLEANUP_AND_PROBES.md) — 上一轮死代码清理
- [`PLATFORM_UPGRADE_R10.md`](PLATFORM_UPGRADE_R10.md) — R10 平台升级验收
- [`../experiments/RERUN_DIFF_CONTRACT.md`](../experiments/RERUN_DIFF_CONTRACT.md) — D2 复跑与差异比对契约
- [`../algorithms/LEGACY_MIGRATION.md`](../algorithms/LEGACY_MIGRATION.md) — 遗留算法迁移指南（本轮加注记）
