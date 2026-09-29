# 网络账本V1编解码契约

## 当前状态

`NetworkLedgerCodec`已提供严格JSON编解码与不可变重建。**本编解码子功能不等于普通run已能导出sidecar**；正式工件写入与跨文件校验仍需下一步接线，ON临时写入保护在此前保留。

```java
String json = NetworkLedgerCodec.encode(report.getNetworkEvidence());
NetworkLedgerCodec.Decoded checked = NetworkLedgerCodec.decode(json);
NetworkRunEvidence frozen = checked.getEvidence();
NetworkRunMetrics recomputed = checked.getMetrics();
```

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
- 纯codec不持有manifest中的Job结果、VM放置或主事件，因此尚不能独立认证绑定是否属于某个具体run；正式工件校验必须另外核对配置、来源/路径、Job/Task身份、时间和组计数。
- 内部一致性不等于密码学真实性，也不证明未知截断后缀存在。

22项codec测试覆盖正常/所有保留前缀往返、巨大十进制聚合、长ID、标签作为纯数据、非法结构和派生摘要篡改。既有真实端点/Fat-tree/局部性/故障重试捕获也执行严格往返测试。非法绑定异常类型和写读数值边界先被测试发现，再实施修正。
