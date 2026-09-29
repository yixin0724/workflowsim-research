# 流体账本独立校验与精确指标（NF-002B1/B2）

## 当前能力与边界

本阶段提供Java API的独立语义校验，不调用生产端的progressive-filling求解器来计算“期望速率”。
已有运行级捕获、绑定、独立校验、精确字节/FCT/资源指标和实际局部性观测；**正式sidecar导出、rerun网络比较和Workbench显示仍属后续功能**。在导出接线完成前，ON工件入口继续明确拒绝写入，OFF历史工件格式不变。

```java
NetworkRunEvidence evidence = report.getNetworkEvidence(); // OFF为null
if (evidence != null) {
    NetworkRunMetrics runMetrics = NetworkRunMetrics.calculate(evidence);
    NetworkTraceMetrics metrics = runMetrics.getTransferMetrics();
    TransferTraceValidator.Result checked = metrics.getValidation(); // 已先校验
    if (metrics.isAvailable()) { // 仅COMPLETE capture发布完整捕获总量
        BigDecimal service = metrics.getServicedBalanceDeltaBytes();
    }
}
```

- `COMPLETE`表示所有记录均保留，不表示所有流已完成。活动流可以处于完整的中途快照中。
- `TRUNCATED`表示保留确定性前缀；不得用其产生完整运行的流量、平均FCT或利用率认证。
- `certifiedThroughSequence`仅标记已获得完整分配证书的稳定边界。尾部可能处于入流改率、服务批次、完成队列或待推进窗口中。
- 对截断尾部只检查已观测语法和必要的上下界；**不证明任意未知后缀存在，也不重建被丢弃的历史**。
- 这是内部一致性校验，不是对整份被一致重写证据的密码学认证。配置、Job绑定、主事件与provenance仍需在工件层关联校验。
- 当前运行捕获中的流是`PARENT_GROUP_V1`或`EXTERNAL_GROUP_V1`；不是逐文件流。来源标签为`MODELED_CONSTRAINED_SOURCE`，不等于名义估算器实际选择的逐文件副本。

## 精确派生指标（NF-002B2）

`NetworkTraceMetrics.calculate`先调用独立校验器，再读取冻结快照，不访问活引擎、不推进时间。会计版本为`EXACT_BINARY64_INPUT_DECIMAL_V1`：把记录中的double精确表示为BigDecimal后执行不舍入的乘积和累加。

| 量 | 定义与限制 |
| --- | --- |
| admittedPayloadBytes | 所有START的需求字节B |
| servicedBalanceDeltaBytes | 精确累加before-after，记作D |
| modeledRateAreaBytes | 精确累加rate×原始elapsed，记作A；不替换为时钟差 |
| completionResidualBytes | 完成时的数值移除R，不是网络服务 |
| remainingLedgerBytes | 尚未完成的账本余额Q |
| rateAreaMinusBalanceDeltaBytes | 有符号A-D，可正可负，不取绝对值或归零 |
| completedDemandBytes | 已完成流的原始需求，不等于已传输服务量 |

每条流都检查精确的`B=D+R+Q`，不错误断言`A=D`。大余额减去0.5后可能仍是同一个double：此时D=0、A=0.5，差异必须保留。BigDecimal也避免超过Double.MAX_VALUE的跨流总量或容量积分被输出为Infinity。

- 每个入流序号独立保存FCT，即使外部ID被重用。活动流的完成时间/FCT为null，属于删失样本。
- effective FCT与observed FCT、通知滞后分别保留。零effective FCT可以伴随正服务，不输出虚构或无限吞吐率。
- 均值先以DECIMAL128求均值再转换为double；p95使用整数计算的nearest-rank，不插值。
- OFF/TRUNCATED保留校验诊断，但字节总量、FCT样本总量和利用率统计不可用；不是以零替代。COMPLETE零流则有真实零字节总量，但没有FCT或利用率样本。
- 资源积分按原路径重复权重收费，不能把所有资源的积分之和当作唯一交付字节。
- 利用率是`boundedRateArea/capacityArea`，**每个正elapsed服务批次只计一次容量面积**，排除全局空闲间隔；当时已登记但未占用的资源计零负载。
- 从未受限的SOURCE有流量面积、无利用率分母。资源后来才登记容量时，先前无上限阶段的流量不进入受限时期的利用率分子。

22项指标测试覆盖上述算式、相邻零时长完成、0/截断、正负舍入差、巨大累计量、nearest-rank和不可变性；实际端点、Fat-tree、局部命中、分数文件、截断和故障重试捕获也参与集成核对。

## 实际局部性观察

局部性不使用“需求字节减服务字节”推断。原有输入循环只调用一次本地副本判断，将**同一个布尔结果**同时用于原有跳过分支和可选观察计数器；不重复查目录、不改入流/释放/副本策略。

`NetworkInputDemandTracker`仅保留固定数量的long/BigDecimal聚合项，不保存每文件或每Job历史。其冻结快照的范围为`V1_GROUP_INPUT_REFERENCES`：包含父组中的重复引用、不同Job尝试的再次引用以及零字节引用，不能解释为唯一文件数或有限缓存模型。

- requiredReferenceBytes按所观察到的double文件大小精确累计；localReferenceBytes只累计实际被跳过的引用；transferableReferenceBytes为其余引用。
- 计数和字节满足各自的精确分区关系。零引用没有命中率分母；零字节引用仍会增加引用数，但没有字节命中率分母。
- `NetworkRunMetrics`分别给出引用数命中率、字节命中率，以及`admittedMinusTransferableReferenceBytes`。
- 最后一项是原V1 double归组入流量与精确非本地引用量的**有符号差异**，不是局部性收益。例如同组`1e16+1`或`1e16+3`会产生-1或+1的入流舍入差；实际local计数仍为0。
- 原始计数器可在trace预算耗尽后继续O(1)聚合，但TRUNCATED不发布完整捕获的局部性指标。
- 旧的低层`NetworkRunEvidence.capture`重载若未提供观察，局部性为`INPUT_COUNTERS_UNAVAILABLE`，不会捏造零命中；OFF也与零需求完整捕获不同。

11项新增计数/运行测试覆盖不可变性、非法输入不改状态、精确小值、本地/非本地实际分支、零引用/零字节、截断、正负归组误差；原故障重试及ON/OFF隔离对照也检查每次真实尝试的引用数。当前观察是聚合证据，不是逐文件完成时间线；工件层仍需将其与主事件、绑定和来源身份一起验证。

## 完整捕获的校验语法

1. 记录sequence为连续的1..N，START的admissionOrdinal为连续入流序号。外部ID可在旧入流完成后重用，但不能同时重用。
2. 每条记录恰有一种匹配type的payload；数值、身份、资源列表和时间域必须合法。
3. CAPACITY仅能在无活动流时登记或覆盖。
4. START携带新流的最终初始速率，随后是按既有入流顺序排列的旧流RATE_CHANGE。不能用后续改率修改该START已经声明的新流初始速率。
5. 正elapsed积分步必须给每个步前活动流恰好一条SERVICE，按入流顺序排列。用活动流数量划分批次，不能按相同时间或连续类型粗略分组。
6. 使用原始binary64标量公式独立核对elapsed、before/after和内部边界；保留记录中的elapsed，不能替换成`end-start`。
7. 完成队列由相对/ULP字节容差及最早流强制归零规则决定。残差来自强制归零前的remaining，不属于网络服务流量。
8. 完成队列之后才处理幸存流改率；在完整改率块结束后验证容量和max-min证书，不能把序列化过程的瞬态过载误判为最终过载。
9. observed horizon内已知还有活动流时必须继续积分，不能静默跳过窗口。完整EOF必须结束所有已知必需阶段，活动状态须与engineTime一致。

### 必须保留的数值边界

- 正elapsed的区间起止double时钟可能相同；零FCT不等于零服务。
- zero-elapsed迭代不输出SERVICE，但可以输出完成记录；强制归零残差可能大于通常完成容差。
- 两个相邻、时间完全相同的COMPLETE也可能来自不同迭代，完成顺序不一定按原始入流序号排序。
- `q=Double.MIN_VALUE`，无资源X=`192q@128`、Y=`64q@64`，在0入流并advance到`2q`，会先服务一次、完成Y，再以零elapsed完成X，残差`64q`。这是已执行的回归控制，不是应被“修复”为更多传输的记录。

## 独立max-min瓶颈证书

对每个稳定分配检查：

- 每流速率有限、正且不超过其nominal cap；
- 每个有限资源的加权负载不超过容量，重复资源键按出现次数收费；
- 对每个未被nominal cap限制的流，至少存在一个已饱和的占用资源，且该资源的每个共同用户速率都不高于该流（在明确数值profile内）。

最后一项能拒绝“容量100、两条相同需求却给75/25”之类容量可行但不公平的伪造，也能发现竞争者完成后未回收容量的历史。
未登记容量的资源不构成瓶颈；SOURCE的缺省无限预算不输出Infinity或虚构利用率。

## 数值profile

固定标识：`BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`。

- 服务和时间标量公式按binary64数值精确比较；正负零时钟按数值等价处理。
- 容量负载通过BigDecimal表示记录中double的精确值，避免求和溢出。
- 容量比较预算为`min(8*(occupancyTerms+1)*ulp(capacity), capacity*1e-12)`。
- 速率比较采用同样的相对上限；ULP项按活动流数量及所比较速率的最大值计算。
- 这不是输入字节的完成容差，也不是全局时间epsilon。

相对上限必不可少：裸多ULP预算在subnormal尺度可能大于整个容量。独立反例已确认旧引擎在容量`3q`、两个相同流时可分配`2q+2q`，形成约33%的超配。**校验器拒绝这种精度不足的物理证书，不修改旧引擎来恢复兼容性，也不把大比例误差说成舍入噪声。** 新版本物理模型仍需明确其可表示的数值域。

## 截断尾部的必要检查

- 必须恰好保留预算条数、droppedCount为正；恰好装满而没有丢记录仍是COMPLETE。
- 已固定流包括新入流、已处理改率的旧流，以及被顺序越过而已不能再改率的旧流。
- 固定子集的加权负载下界不能超容量；固定的非nominal-capped流必须仍有一个可能成立的瓶颈。
- 可变流的上界同时受nominal、比较速率、所有占用资源的剩余容量、固定占用及重复权重限制；这些是保守必要条件，不调用分配器。
- 若已无合法待处理改率对象，就必须关闭并认证该分配，不能借TRUNCATED掩盖已确定错误。
- 已确定的缺失服务批次、完成队列，以及后续观察窗口内每个幸存流的必要记录，都计入最低droppedCount义务。
- 若只有snapshot水位前进而未保留对应observed horizon，只计算保守最低义务；未知新START或改率可能改变后续批次数，不能擅自重放完整缺失历史。

## 验收方式

30项定向校验测试覆盖正常轨迹和一致重编号后的篡改反例，包括：遗漏/交换服务、缺失完成或改率、错误身份、75/25不公平、重复资源去重、残差伪造、零时长边界、截断完成伪装、少报droppedCount、跨资源不可补救分配和水位缺失义务。

正常控制还包括36个固定多资源/数量级场景（含重复资源、0和较大时钟），对每个真实保留前缀进行校验，以及已有端点、Fat-tree、零流、局部命中、分数文件和故障重试运行的实际捕获。负例先运行并观察失败，再实施修正；不通过调整旧研究参数或刷新golden来证明兼容性。
