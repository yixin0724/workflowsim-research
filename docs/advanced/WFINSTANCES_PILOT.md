# WfInstances 1.5 输入转换试点

## 主张边界

本文档认证一小组冻结的 WfInstances 1.5 输入可被抽象 WorkflowSim 调度模型严格转换。WfInstances 包含真实执行派生的工作流实例，但当前集成只转换受支持的工作流图、文件大小和任务 runtime 字段；它不重放来源执行环境，也不预测其记录的 makespan。

目录位于实验模块中的 `org.workflowsim.experiments.reference.p7.P7WfInstancesPilotMatrix`。其测试为每个选中输入运行四 VM 的同质抽象平台：`SHARED` 存储、无聚类、无故障、无建模开销、无规划算法和 FCFS。测试目的是输入契约与端到端转换认证，而不是算法比较。

P7/reference 测试通过 Maven 注入的 `workflowsim.datasetRoot` 获得一个绝对数据集根目录。逻辑输入路径均相对此根目录；本项目默认根是 `<项目根>/datasets`，不是项目根本身。

## 冻结试点输入

| 来源系统 | 工作流家族 | 相对数据集根目录的路径 | Tasks | SHA-256 |
| --- | --- | --- | ---: | --- |
| Makeflow | BLAST | `wfinstances/v1.5/makeflow/blast/blast-chameleon-small-001.json` | 43 | `5e132ac7f63096dec62173da1c7512554f9ddb08dc420f2005af277a04b8e845` |
| Nextflow | Bacass | `wfinstances/v1.5/nextflow/bacass-dirt02-001.json` | 11 | `4cbab2c46e2d7c6094701d5a9d03bc4675d59bbb96effc2ae3c255c5a4fb2999` |
| Pegasus | SRaSearch | `wfinstances/v1.5/pegasus/srasearch/srasearch-chameleon-10a-001.json` | 22 | `c2b5d73841750b5af68e5ff65f5f2d04b51a4b6b18086e7a5b1dfb72317fd4b6` |
| Pegasus | Montage | `wfinstances/v1.5/pegasus/montage/montage-chameleon-2mass-005d-001.json` | 58 | `5795e0ab9e13bb7d50d046796bcbc8ec0a884eba0512a95222bbd557bc6d0b65` |

`P7WfInstancesPilotMatrixTest` 检查文件存在性和 SHA-256，要求解析器报告 `WFCOMMONS_JSON` 和声明版本 `1.5`，检查精确的 Task 数，运行标准管线，并要求所有返回 Job 成功且结构化事件流非空。执行完整 P7/reference 测试：

```bash
mvn verify
```

## 字段契约

解析器消费以下 WfFormat 1.5 字段：

| WfFormat 位置 | WorkflowSim 表示 | 规则 |
| --- | --- | --- |
| `schemaVersion` | `WorkflowInputReport.declaredVersion` | 记录，不作为平台设置。 |
| `workflow.specification.tasks[].id/name` | Task identity/type | ID 映射为内部整数 Task ID；name 映射为 Task type。 |
| `parents`、`children` | DAG edge | 两端声明必须描述相同边；端点缺失、单侧依赖或环会被拒绝。 |
| `inputFiles`、`outputFiles` | `FileItem` dependency | 被引用文件必须已声明。 |
| `workflow.specification.files[].id/sizeInBytes` | 文件名/字节大小 | 必须是非负有限值。 |
| `workflow.execution.tasks[].id/runtimeInSeconds` | Task cloudlet length | 每个 specification Task 都必须有 runtime。 |

runtime 转换采用显式模型假设：

```text
max(100, floor(runtimeInSeconds * runtimeReferenceMips * runtimeScale)) MI
```

冻结试点固定 `runtimeReferenceMips=1000.0`、`runtimeScale=1.0`。因此输入 runtime 不是原始、硬件无关的 CPU 工作量；必要时最小长度归一化会记录在输入报告中。

当前解析器刻意不从下列执行字段重放或校准：工作流 metadata/repository/run name，执行时间戳和 `makespanInSeconds`，Task command/priority/core count/memory/read-write 计数，机器分配或机器 inventory。Gson 忽略未知 JSON 字段，不会把它们静默转换为资源设置。平台始终来自实验显式提供的 `PlatformProfile`。

## 全量语料结构校验

本地全量语料含 180 个 JSON 输入。它的解析/DAG 校验会处理 118,637 个任务和 244,573 条边，因此是显式的非测试命令，而不是常规 Maven 测试：

```bash
mvn -pl :workflowsim-experiments -am -DskipTests \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsJsonParserValidationExample \
  -Dexec.args="datasets/wfinstances/v1.5" \
  compile exec:java
```

已记录的当前语料结果是 `WFJSON_VALIDATION PASSED files=180 tasks=118637 edges=244573`。这证明当前捆绑文件的严格结构转换覆盖，不证明 trace replay、平台校准或算法优越性。命令会产生两个模块的 `target/` 中间输出；检查后运行 `mvn clean`。

## 在后续 P7 实验中的使用

只有冻结输入的 SHA-256 检查通过后，试点单元才可使用共同 P7 抽象平台和 evidence bundle。DAX 与 WfInstances 是不同的 workload population，必须按输入格式和家族分别报告，不能合并为某一调度器普遍更好的证据。任何与 WfInstances 记录 makespan、机器或多核分配的比较，都需要独立的校准与验证协议。
