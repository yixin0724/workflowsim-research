# Fat-tree拓扑、路由与共享模型

## 结构与适用范围

[NetworkTopologySpec](<../../simulator/src/main/java/org/workflowsim/network/NetworkTopologySpec.java>)描述平台拓扑，[FatTreeTopology](<../../simulator/src/main/java/org/workflowsim/network/FatTreeTopology.java>)负责结构、主机放置、固定路由和有向链路容量。传输服务由[TransferContentionEngine](<../../simulator/src/main/java/org/workflowsim/data/TransferContentionEngine.java>)计算，不在路由层实现。

结构采用Al-Fares等人的k-Pod Fat-tree思想，背景文献为[A Scalable, Commodity Data Center Network Architecture](<https://doi.org/10.1145/1402958.1402967>)。这里实现的是声明式流级模型，不是该论文交换机协议或实际数据中心的完整重建。

| 数量 | 满配结构 |
|---|---|
| Pod | k，k为不小于2的偶数 |
| 每Pod的edge / aggregate交换机 | 各k/2 |
| core交换机 | k²/4；可显式减为1≤m≤k²/4 |
| 主机容量 | k³/4，每edge最多k/2个主机 |
| 交换机总数 | 满配时5k²/4 |
| edge–aggregate、aggregate–core无向链路 | 满配时每层各k³/4 |

同Pod的edge与aggregate全二部连接；core `(a,j)`连接所有Pod的第a个aggregate。满配结构提供相应的聚合路径容量，但**不保证本实现的固定选路对任意通信排列都无阻塞**。减少core的结构比率`(k²/4)/m`不是任意工作负载吞吐的等比例预测。

## 声明与主机放置

```java
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond);
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond, coreSwitchCount);
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond,
        coreSwitchCount, hostEdgePlacements);
```

- `hostEdgePlacements`为Host ID到全局edge索引的映射，索引为`pod*(k/2)+edge`，范围`[0,k²/2)`。显式映射须覆盖全部Host且不能超出每edge容量。
- 默认把Host ID升序排列后轮转放置到edge，而不是把相邻Host都填入同一edge。
- VM路径基于运行时实际VM→Host放置，不用VM编号猜测Host。
- 链路声明单位为十进制MB/s，服务单位为B/s，换算因子为1,000,000，不额外除以8。
- Workbench限定偶数`k=2..16`；coherent V2/V3运行器还对Java入口限定`k=2..32`及正normal有限的换算后B/s。低层工厂不是任意巨大拓扑都可安全构造的承诺。

## 路由与有向资源

`route(srcHostId,dstHostId)`返回确定性的有序链路资源列表：

1. 同Host返回空拓扑路径；不同VM仍按各自端点约束处理，不等于零VM通信。
2. 同edge经过两条Host接入链路。
3. 同Pod不同edge经过源/目的接入与edge–aggregate路径。
4. 跨Pod再经过aggregate–core及目的Pod下行路径。

aggregate选择为`a=srcEdge mod availA`，其中`availA=ceil(2m/k)`；跨Pod从该aggregate的可用core中按`(srcEdge+dstEdge+srcPod+dstPod) mod jCount`选定一条路径。下行由选定core和目的位置确定。该规则没有ECMP哈希、动态负载感知或自适应改路。减少core还会改变可选aggregate及固定路由，不能只根据一层结构推导整个DAG的性能变化。

双工链路的两个方向是独立资源，例如：

```text
LINK:ACC:h->EDGE:p:e       LINK:EDGE:p:e->ACC:h
LINK:EDGE:p:e->AGG:p:a     LINK:AGG:p:a->EDGE:p:e
LINK:AGG:p:a->CORE:c       LINK:CORE:c->AGG:p:a
```

流同时占用其端点与路径资源。资源键的重复出现按占用次数计费，不能在验证或显示时随意去重。

## 共享服务

分配器采用max-min progressive filling，同时满足每流名义上限和全部占用资源的加权容量约束。某流被其他瓶颈或名义上限限制时，其未使用份额可回分给其他流；只有对称、没有额外瓶颈的情况才简化为`capacity / 流数`，一般情况不能套用该近似。

在准入、完成等内部边界分段积分与重分配。内部有效完成和外部观察可以不同；物理可见性遵守对应模型契约，不由显示刷新时间替代。添加约束可能改变份额回收、缓存可见性或后续来源选择，不能保证整个DAG完成时间跨模型单调。

## 与数据流模型的组合

| 分支 | 拓扑声明 | SOURCE与执行边界 |
|---|---|---|
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1` | 必需 | LOCAL、预先静态映射及其配置边界；父组/外部组级输入准备，外部SOURCE组不经过拓扑 |
| 文件数据流V2共享/独立对照 | 可选 | LOCAL；实际逐文件VM路径；外部SOURCE仍明确无限/绕过fabric |
| 存储数据流V3共享/独立对照 | 可选 | LOCAL或SHARED提交后读取；SOURCE有实际接入Host和读/写/NIC约束，输入和必要输出使用相应路径 |
| 不使用拓扑的模型 | 不允许多余声明 | 避免平台声明被静默忽略 |

V2/V3可使用默认RANDOM/STATIC，或显式控制就绪在线目标绑定＋STATIC。它们不是把旧CPU在线调度器直接接到争用模型上。详见[V2契约](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>)、[V3契约](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)和[在线绑定](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 不包含的能力

没有包级TCP/重传、丢包、ECN、交换机队列细节、链路传播时延、交换机内部转发容量、链路故障或自适应路由。流级模型适合所声明抽象下的调度研究，但不声称没有信息损失，也不能替代真实网络校准。

拓扑、源选择、容量服务和证据分别测试。校验范围见[V1账本校验](<../advanced/NETWORK_LEDGER_VALIDATION.md>)、[逐文件生命周期格式](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>)；运行命令见[构建指南](<../getting-started/BUILD.md>)。历史campaign数值只在其[数据来源说明](<../../experiments/studies/fattree-scheduling-campaign/RETENTION.md>)中解释，不作为当前实现的普遍黄金值。
