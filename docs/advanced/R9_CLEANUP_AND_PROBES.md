# R9 清理与运行时探测轮（2026-09-16）

**性质**：R8 全面审计后残余风险区的整改执行轮（经用户授权："按照你的推荐执行，
哪些死代码该删除的可以去除掉，不需要留"）。
**分支**：`feature/r9-deadcode-and-runtime-probes`（自 main `6331d25`）。
**门禁**：JDK17 `mvn -o clean verify` 全绿——**452 测试（294 单元 + 78 集成 +
26 实验单元 + 54 实验集成），0 失败，0 跳过**；两模块 `All coverage checks have
been met`。

---

## 1. 死代码清理（含一处重要更正）

### 1.1 更正：聚类族不是死代码，保留

R8 后的初步判断曾把聚类族（HorizontalClustering / VerticalClustering /
BalancedClustering / BlockClustering / DistanceVariance 等，jacoco 0%）列为
"不可达死代码"。**本轮可达性核查证明该判断错误**：

- `ClusteringEngine` 被 Job / Task / WorkflowPlanner / AbstractLocalCommPlanningAlgorithm /
  SimulationConstants 活引用；
- `ExampleCatalog.legacyExamples()` 注册 10 个聚类示例（horizontal-clustering-size/count/
  overhead、vertical-clustering、balanced-clustering、fault-clustering-dc/sr/dr/vm/dynamic 等）；
- `ExamplesCliSmokeIntegrationTest`（@TestFactory DynamicTest）对**每个**目录示例在
  独立 JVM 中实跑并要求 exit 0 + 完成标记——本轮实测 40/40 绿。

jacoco 0% 是**测量假象**：独立 JVM 的示例运行对测试 JVM 的 jacoco agent 不可见。
聚类族全部保留，未删一行。

### 1.2 确认删除（7 文件 + 入口枚举/分发/契约全链）

**遗留报告写器族**（无主流程引用；唯一消费方是一个未注册示例与一个从不执行的手动测试）：
- `simulator/.../experiment/ExperimentCsvWriter.java`
- `simulator/.../experiment/ExperimentConsoleSummary.java`
- `simulator/.../experiment/ExperimentHtmlReportWriter.java`
- `experiments/.../examples/MyConfigurableExperiment.java`（仅引用上述写器，不在 ExampleCatalog）
- `experiments/.../examples/NewFeaturesManualTest.java`（@EnabledIfSystemProperty
  `workflowsim.manual=true` 门禁，标准构建从不执行）

**Legacy HEFT/DHEFT 规划器族**（@Deprecated、SimulationRunner 入口拒绝、
与共享存储 stage-in 执行模型不对齐的过渡兼容层）：
- `simulator/.../planning/HEFTPlanningAlgorithm.java`（1560 指令 0%）
- `simulator/.../planning/DHEFTPlanningAlgorithm.java`（718 指令 0%）
- `Parameters.PlanningAlgorithm` 枚举 `HEFT`/`DHEFT` 条目及 javadoc
- `WorkflowPlanner.getPlanningAlgorithm` 的 `case HEFT:`/`case DHEFT:` 分发分支与 import
- `AlgorithmCatalog.isSupportedBySimulationRunner` 与契约 switch 的对应 case、
  `provisionalDagPlanner` 私有 helper（随 case 删除后无调用者）
- 测试同步：`SimulationRunnerAlgorithmContractTest` 删除
  `standardRunnerRejectsLegacyDagPlannersWithUnalignedExecutionModels` 及其 helper；
  `AlgorithmCatalogTest` 删除 `manifestContractPreservesExistingHeftQualificationBoundary`；
  `AlgorithmSupportMatrixTest` 遗留分区改为空集（分区断言继续守"每个标签显式分类"）；
  `SimulationSessionTest` 两处 HEFT 标签改用 RANDOM（校验语义与标签无关：
  任何非 INVALID 规划器都要求 STATIC 调度）。

**受影响示例改接**：`DynamicWorkloadExample1` 是唯一使用 HEFT 标签的示例
（老 API：Parameters.init + WorkflowPlanner）。其教学点是
`CloudletSchedulerDynamicWorkload`（动态 MIPS VM），规划器只是配角；改接无前置
约束的 RANDOM（SHARED_STORAGE_* 要求 SHARED 存储 + SPACE_SHARED VM，与该示例的
LOCAL 文件系统、动态 MIPS 装置冲突）。改接后示例目录冒烟 IT 实测 40/40 绿。

### 1.3 文档同步

- `docs/algorithms/CATALOG.md`："Provisional Legacy DAG Planners" 节改写为
  "Legacy DAG Planners (Removed in R9)"；
- `docs/getting-started/BUILD.md`：模板运行片段改指 HeftPaperReproductionExperiment；
  门禁实录更新为 R9 数值；
- `docs/getting-started/CODE_CONFIG_EXPERIMENTS.md`：整体重写为维护 API
  （SimulationConfig/SimulationRunner/SimulationReport/ExperimentArtifactWriter）版本，
  内嵌模板经真实 classpath javac 编译验证；
- 历史文档加注不改史：`PLATFORM_AUDIT_REPORT.md`、`QUALITY_AUDIT.md`、
  `IMPLEMENTATION_SUMMARY_WAITING_TIME_FIX.md` 顶部各加 R9 注记。

## 2. 开销/故障/成本运行时路径探测

- **`OverheadParametersProbeTest`**（6 用例，单元）：锁定 OverheadParameters 四个
  延迟通道（WED/queue/post/clustering）的全部采样分支——深度命中、深度 0 默认、
  无条目→0、空映射→0、空作业/空批次→0、accessor 透传。
  **OverheadParameters 指令覆盖 24.4% → 100.0%**（240/240，本轮 jacoco 实测）。
- **`RuntimeModelCombinationProbeIntegrationTest`**（3 用例，集成）：
  ① 后处理延迟端到端：JOB_RETURNED 事件 `postDelaySeconds` 全为正、makespan 严格
  增大、固定种子逐位重放；
  ② 故障 × 队列开销组合：种子 20260904（经 51 种子扫描实测选定——队列开销平移
  执行窗口后，原随机模型测试种子 20260902 不再触发失效）触发 3 失败/3 重试，
  黄金 makespan **5.600810343783252**（±1e-9），重放逐位一致；
  ③ VM 成本 × 后处理开销：计费速率保持 5.0（±1e-12）不受开销扰动，makespan
  严格增大。
- 成本模型单通道此前已有 `SimulationCostModelIntegrationTest`（DATACENTER/VM
  计费区分 + 未定价平台 fail-fast + manifest 成本口径），本轮不重复。

## 3. 常驻语料守门（corpus 测试 0 跳过）

3 个测试此前以 `assumeTrue` 在大型语料缺失时自动跳过（CI 全新检出必跳）。
门禁实际引用的语料文件仅 5 个、合计约 432 KB，全部入库：

| 文件 | 大小 | 消费测试 |
|------|------|----------|
| `datasets/wfformat/montage/n100/montage-100-000.json` | 145,730 B | ParserCrossValidationTest、WfCommonsCliSmokeIntegrationTest |
| `datasets/wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json` | 103,750 B | P7WfInstancesPilotMatrixTest、WfCommonsCliSmokeIntegrationTest |
| `datasets/wfinstances/v1.5/nextflow/bacass-dirt02-001.json` | 33,954 B | P7WfInstancesPilotMatrixTest |
| `datasets/wfinstances/v1.5/pegasus/srasearch/srasearch-chameleon-10a-001.json` | 37,935 B | P7WfInstancesPilotMatrixTest |
| `datasets/wfinstances/v1.5/pegasus/montage/montage-chameleon-2mass-005d-001.json` | 111,414 B | P7WfInstancesPilotMatrixTest |

- `.gitignore` 由目录级 ignore 改为 `/*` + `!` 逐级反选：这 5 个文件入库，
  其余大型语料（wfinstances 324 MB / wfformat 37 MB）仍不入库（实测
  `git check-ignore` 双向验证）。技术细节：`datasets/wfinstances/v1.5` 原为
  内嵌 git 仓库（语料下载自带 11 MB `.git`），外层 git 无法入库其内部文件；
  已将该元数据非破坏性移出为 `datasets/wfinstances/.v1.5-git-metadata/`
  （保留溯源、加入 ignore，改回 `.git` 即可恢复 `git pull` 更新语料）；
- 3 处 `assumeTrue` 改为 fail-fast（assertTrue / IllegalStateException）：语料
  缺失从"静默跳过"变为"门禁失败"；
- 实测：ParserCrossValidationTest 5/5、P7WfInstancesPilotMatrixTest 1/1（内含
  4 场景冻结哈希校验）、WfCommonsCliSmokeIntegrationTest 5/5，全部执行、0 跳过；
  P7 冻结哈希与任务数断言对入库文件原值通过（文件本体未动）。

## 4. 覆盖率棘轮上调（只升不降）

| 模块 | 指标 | R8 棘轮 | R9 实测 | R9 棘轮 |
|------|------|---------|---------|---------|
| simulator | instruction | 0.49 | **52.38%** | **0.52** |
| simulator | branch | 0.44 | **46.32%** | **0.46** |
| experiments | instruction | 0.23 | **33.78%** | **0.33** |
| experiments | branch | 0.25 | **35.58%** | **0.35** |

simulator 提升主因：删除约 1.1 万指令 0% 死代码（分母收窄）+ OverheadParameters
探测（分子提升）；experiments 提升主因：常驻语料守门后 corpus 测试实跑。

## 5. 边界与未做事项

- 聚类族、ClusteringEngine、全部聚类示例、`ExamplesCliSmokeIntegrationTest`
  原样保留（见 §1.1）；
- `SimulationRunner` 对不支持标签的拒绝机制保留（枚举删除后 `default: return
  false` 继续覆盖 INVALID 等）；历史审计文档只加注不改史；
- FailureGenerator（52.4%）与 ReclusteringEngine（42.3%）未在本轮拉起：后者仅
  经老 API 故障聚类示例在独立 JVM 执行（jacoco 不可见的测量假象，与聚类族同理），
  前者的分布族 switch 已有 KS 检验测试族覆盖其统计性质；两者留待后续轮按需处理；
- vendored CloudSim（15.2%）维持"不修改、不专门覆盖"的既定边界。
