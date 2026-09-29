# 独立Python网络账本检查

## 用法

检查器只依赖Python标准库（Python 3.9+，CI使用3.12），运行时不调用JVM、Maven、生产分配器或网络服务，不改写输入文件。

```bash
python3 scripts/verify-network-ledger.py path/to/result.network-ledger.json --json
python3 scripts/verify-network-ledger.py path/to/result.manifest.json --json
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-network-ledger.py
```

入口为[verify-network-ledger.py](<../../scripts/verify-network-ledger.py>)，独立实现为[_network_audit.py](<../../scripts/_network_audit.py>)，自包含验收为[test-network-ledger.py](<../../scripts/test-network-ledger.py>)。

- `VALID_COMPLETE`：完整捕获的流体语法、容量/公平性、精确会计和派生指标通过。
- `VALID_PREFIX`：截断前缀的已知约束通过，`completeCaptureCertified=false`；不发布完整流量/FCT总量，不证明任意未知后缀存在。
- `DISABLED`：v4 manifest未请求网络证据；不是“认证零流量”。
- `EVIDENCE_INVALID`：格式、哈希、数值或语义不一致，进程退出码为1。

单独账本输入检查内部网络一致性；manifest输入还检查角色/路径/哈希/大小和网络运行上下文，并设置`contextualRunChecked=true`。只认manifest登记角色，忽略目录中未引用的旧账本。

## 如何保持独立

Java生产端用progressive filling执行，Java校验与指标使用BigDecimal。Python检查器采用：

1. 原始时间、速率、余额的binary64标量公式和`math.ulp`；
2. `Fraction.from_float`得到精确有理数，独立累计字节、速率面积和容量负载；不使用默认28位Decimal累加来近似结果；
3. max-min瓶颈证书验证稳定分配，不重新调用生产求解器；
4. 均值/利用率按契约用DECIMAL128舍入后转换为binary64，p95采用整数nearest-rank；
5. 严格区分byte balance delta、rate-area、settlement residual、remaining和路径重复占用；
6. 输入引用和局部性来自其显式观察计数，不从服务短缺反推。

比较时，int/long及精确十进制聚合不经过有损double转换。binary64字段允许不同运行时等价的最小值拼写（如`4.9e-324`和`5e-324`）。即使速率面积小于最小double，精确分数仍保留该正值。

JSON重复键、非标准常量、错误类型、缺字段、超深结构和超范围数值均拒绝。原始数字token长度在Decimal规范化前检查，避免超长零指数绕过限制。工件引用拒绝目录逃逸和NUL文件名。

## 检查范围

完整捕获检查入流/改率块、固定服务批次数、数值结算队列、zero-elapsed完成、observed horizon和最终水位。相邻同时间COMPLETE不自动视为同一批。截断尾部使用固定流容量下界、其他资源对可变流的上界、可能瓶颈及最低缺失记录义务；不执行未知后缀搜索。

manifest模式核对网络配置、Job/Task/VM、实际主机放置、模型来源与确定性Fat-tree路径、初始容量、Job-ready/输入完成/Job开始、每Job传输组数、引用计数和允许的旧double归组差。正传输组数不能超过非本地引用数；每个Job尝试至多有一个V1外部输入组，这两项也约束截断的已知上下文。拓扑校验只用算术，不为不可信的大k创建整张链路图。

**这不是一般CPU/任务统计研究的完整审计，也不是密码学真实性证明。** 原有[证据检查器](<../../scripts/verify-study-evidence.py>)和[统计检查器](<../../scripts/verify-study-statistics.py>)仍负责其各自领域。V1网络流仍是父组/外部组，不是逐文件FCT、TCP或有限缓存一致性模型。

## 验收与CI

30项自包含Python测试使用手算夹具，不需要JDK或历史研究数据。覆盖迟到advance、容量可行但不公平的75/25、缺失服务/回收、重复资源权重、固定错误前缀、残差、zero-elapsed相邻完成、大时钟零FCT、超过double范围的累计量、小于最小double的面积、身份重用、哈希修复后的上下文篡改及只读行为。

[NetworkLedgerPythonFixtures](<../../simulator/src/test/java/org/workflowsim/data/NetworkLedgerPythonFixtures.java>)是**单独的生产端样例生成器**，拒绝覆盖已有目录；它不调用Python检查器。CI先生成18个输入，再由Python读取检查：十个真实端点/Fat-tree完整、截断、零流及显式拓扑bundle，以及八个数值/资源边界账本。生成样例需要JDK，不代表检查器本身依赖或调用Java。

```bash
mvn -Pcore-exec -pl :workflowsim -am test-compile exec:java \
  -Dexec.mainClass=org.workflowsim.data.NetworkLedgerPythonFixtures \
  -Dexec.classpathScope=test \
  -Dexec.args='"output/network-check-fresh"'
python3 scripts/verify-network-ledger.py output/network-check-fresh/endpoint-full/result.manifest.json --json
```

当前本地验收：30项网络自测、原11项独立研究检查器测试、18个新生成输入全部通过；此前六个ON bundle也以manifest和standalone两种入口共12次通过。两项上下文漏检已在Python和Java两侧先用一致重哈希的反例复现再修复，完整Java/Javadoc回归1126项通过，原覆盖率门槛未改变。远程CI结果以实际推送后的运行结果为准。
