# 网络账本V1编解码契约

## 当前状态

`NetworkLedgerCodec`提供严格JSON编解码与不可变重建；`ExperimentArtifactWriter`现在已能写出ON网络账本，`ExperimentArtifactValidator`会同时核对内容及运行上下文。另有不调用Java的[独立Python检查器](<NETWORK_LEDGER_PYTHON.md>)。已接入[ON重放与精确网络比较](<../experiments/RERUN_DIFF_CONTRACT.md>)；**Workbench配置/显示仍属后续接线，不把本阶段等同于完整P0/P2交付。**

```java
String json = NetworkLedgerCodec.encode(report.getNetworkEvidence());
NetworkLedgerCodec.Decoded checked = NetworkLedgerCodec.decode(json);
NetworkRunEvidence frozen = checked.getEvidence();
NetworkRunMetrics recomputed = checked.getMetrics();
```

## 正式导出和验证

```java
// config已选择受支持的流体模型、静态mapper/dispatch和对应平台。
SimulationConfig observed = config.toBuilder()
    .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(50000)).build();
SimulationReport report = new SimulationRunner().run(observed, platform);
ExperimentArtifactWriter.ExperimentArtifacts files =
    ExperimentArtifactWriter.write(report, outputDirectory, "run");
ExperimentArtifactValidator.ValidationResult checked =
    ExperimentArtifactValidator.validate(files.getManifest());
Path ledger = checked.getNetworkLedger(); // OFF为null，不凭目录中的旧文件猜测
```

ON写出原metrics/events及新增`run.network-ledger.json`，随后以manifest登记role/hash/size并最后提交。OFF仍写原三件套，不添加networkEvidence字段或网络角色。复用目录时旧的未引用账本不会被擅自删除，读取器只认manifest角色。

- 任何网络语义或上下文错误必须在创建目录/修改既有文件前失败。
- 每个文件单独原子替换，manifest最后写；这不是跨多个文件的文件系统事务。中途I/O失败会被后续哈希/内容校验发现。
- 独立`ExperimentManifestWriter.writeJson`仍不能丢弃ON账本，必须使用完整bundle导出。
- v4的networkEvidence对象必须与network-ledger角色同时存在；null、false、OFF对象或非法预算不能被当成缺省OFF。
- 工件大小与事件序号按精确long校验，不接受小数截断或越界转换。

21项工件集成测试覆盖完整/截断/零流、OFF目录复用、写前预检保护、重算哈希后的非法指标/服务/来源/路径/容量/局部性、观察时刻与Job释放、精确长度、UTF-8及搬迁。拓扑正控制包含同edge、跨Pod、显式放置和缩减core；不会为不可信的大k分配整张拓扑。

## 文档头

- schema：`workflowsim-network-ledger-v1`
- 预定工件role：`network-ledger`，文件名`<runId>.network-ledger.json`
- flowUnit：`V1_PARENT_OR_EXTERNAL_GROUP`
- units：time=`SIMULATION_SECONDS`，bytes=`MODELED_BYTES`，rate=`BYTES_PER_SECOND`
- numericProfile：`BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`
- accountingVersion：`EXACT_BINARY64_INPUT_DECIMAL_V1`
- evidence：配置、现有模型kind、原始traceSnapshot、不可变bindings、engineCreated、可选原始inputDemand聚合观察。
- metrics：从重建证据重新计算的NetworkRunMetrics，不信任文档提交的摘要。

本schema不包含工作目录、源码路径或墙钟测量。它不升级或改变旧manifest-v4、metrics-v2、events-v1格式。

## 严格读取

1. 拒绝重复JSON键、宽松JSON形式、尾随文档、缺失或未知字段、错误枚举、字符串/布尔值冒充数字。
2. 所有nullable字段必须显式存在。CAPACITY身份为空；其他事件身份必需，且恰好一种匹配type的payload非空。
3. int/long身份与预算按精确整数读取，不能先转换为double；预算必须正且模式必须为`FLUID_GROUP_LEDGER_V1`。
4. trace的binary64字段必须有限且可表示；非零值下溢到0不能被静默接受。不同运行时将同一个最小double拼写为`4.9e-324`或`5e-324`时按binary64值比较，不以某个JDK的最短字符串作为唯一格式。
5. BigDecimal聚合值按精确十进制比较，可以超过Double.MAX_VALUE；有符号会计差不能被当作非法负流量或取绝对值。
6. JSON嵌套深度上限64，数字token长度、精度和绝对scale上限4096。写出端也检查相同边界，不能产生自身无法读取的文档。这不是整个trace的字节内存上限。
7. 显式调用各不可变DTO工厂，禁止依赖Gson绕过private构造器后的默认0/null字段。
8. 运行独立trace语义/容量证书校验，精确关联保留START与bindings，核对输入计数分区，重新计算并比较所有metrics字段。
9. 截断证据中的额外未保留binding不能被读取器静默过滤。engineCreated=false不能掩盖非空记录、时间或输入观察。

对象成员顺序不影响语义；事件、路径、Task列表等数组顺序保留。binary64字段按数值比较，精确整数与十进制聚合字段不会通过有损浮点比较。

## 保证与非保证

- `Decoded`仅返回不可变证据和重新计算的不可变指标；修改源JSON树不影响它们。
- COMPLETE、TRUNCATED、无引擎零观察、缺失输入计数等状态保持不同含义。
- 纯codec不持有manifest中的Job结果、VM放置或主事件；正式工件校验另核对配置、实际VM放置、Job/Task身份、来源/确定性路径、平台容量、Job-ready/输入完成/Job开始时间和每Job组计数。输入引用数与主事件一致，正传输组数不得超过非本地引用数，每个Job尝试至多一个V1外部输入组（也约束已保留前缀）。精确引用字节与原double归组量在明确累加误差边界内核对，不能把允许的±1归组舍入差当作局部性。
- v4旧工具可能只验证额外角色的哈希而不理解这些网络语义。ON证据必须使用升级后的校验/重放链路；专用rerun已升级并逐字段比较sidecar；Workbench网络显示仍待后续完成。
- 内部一致性不等于密码学真实性，也不证明未知截断后缀存在。

22项codec测试覆盖正常/所有保留前缀往返、巨大十进制聚合、长ID、标签作为纯数据、非法结构和派生摘要篡改。既有真实端点/Fat-tree/局部性/故障重试捕获也执行严格往返测试。非法绑定异常类型和写读数值边界先被测试发现，再实施修正。
