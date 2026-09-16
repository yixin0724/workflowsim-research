# Fat-tree 拓扑感知链路争用模型：设计文档（Phase 1，已交付版）

> 前置：[`docs/research/FAT_TREE_PRINCIPLES.md`](FAT_TREE_PRINCIPLES.md)（Phase 0 原理研读）。
> 本文描述已实现并锁定黄金值的设计；实现偏离本文时先修本文。

---

## 1. 范围与交付物

新增**并列**数据移动模型
`DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`
（工厂 `DataMovementModel.fatTreeContentionV1()`），传输窗口语义与既有争用模型
相同（数据就绪统一开始、可与 VM 忙碌期重叠），争用语义从"VM 端点公平共享"
推广为"**Fat-tree 路径上每条共享链路 + 端点的 max-min 公平共享**"
（传输有效速率 = min(名义速率, 全部占用资源份额)）。

交付物：
1. `org.workflowsim.network.NetworkTopologySpec`：平台拓扑不可变声明（v1 仅
   `fatTree` 族工厂）；
2. `org.workflowsim.network.FatTreeTopology`：k-Pod 结构构造 + 确定性路由 +
   链路容量注册；
3. `TransferContentionEngine` 资源集推广（双端点重载委托给资源集重载，行为
   逐位不变）；
4. `PlatformProfile.Builder.networkTopology(...)` 平台侧声明；
5. `SimulationConfig` 配置契约 + `SimulationRunner` 双向契约（模型⇄拓扑声明）；
6. 测试：拓扑结构/路由单测 9、引擎资源集单测 4、e2e 集成 7（含黄金值锁定）。

## 2. 拓扑对象模型（`FatTreeTopology`）

```
FatTreeTopology.fromSpec(NetworkTopologySpec spec, List<Integer> hostIds)
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond)                     // 满配 + 默认放置
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond, coreSwitchCount)    // 可超收敛
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond, coreSwitchCount,
                            Map<Integer,Integer> hostEdgePlacements)          // 显式放置
```

- **结构计数**：k 个 Pod，每 Pod edge/aggregate 各 k/2 台；core m 台
  （满配 k²/4，1 ≤ m ≤ k²/4）。交换机逻辑名：`EDGE:p:e`、`AGG:p:a`、`CORE:c`
  （c = a·(k/2)+j 对应 (a,j) 坐标）。
- **链路资源键**（双工分方向，独立容量）：
  `LINK:ACC:h->EDGE:p:e` / `LINK:EDGE:p:e->ACC:h`（主机接入）、
  `LINK:EDGE:p:e->AGG:p:a` / `LINK:AGG:p:a->EDGE:p:e`（Pod 内）、
  `LINK:AGG:p:a->CORE:c` / `LINK:CORE:c->AGG:p:a`（core 层，仅 c < m 存在）。
  全部容量 = 统一链路带宽（MB/s → 字节/秒）。
- **放置**：`hostEdgePlacements` 给 hostId → edge 交换机全局索引
  （idx = pod·(k/2)+e ∈ [0, k²/2)），必须覆盖全部主机、每台 edge ≤ k/2 台；
  缺省时按 hostId 升序轮转（i → idx i mod k²/2，确定性）。主机数上限 k³/4。
- **确定性路由** `route(srcHostId, dstHostId) → List<String>`（v1 规则，写入
  javadoc，可审计）：
  - 同主机：空列表（VM 间本地交换不占拓扑链路）；
  - 同 edge：两条接入链路；
  - 同 Pod 不同 edge / 跨 Pod：上行 aggregate **a = srcEdge mod availA**
    （availA = ceil(2m/k)，拥有 core 上行的 aggregate 数）；跨 Pod 时在
    aggregate a 的可用 core {c = a·(k/2)+j : c < m} 中选
    **j = (srcEdge + dstEdge + srcPod + dstPod) mod jCount**；结构性质决定
    目的 Pod 侧 aggregate 必为同一编号 a，下行链路唯一；
  - 全部确定性、无哈希；重复构造逐位相同（单测锁定）。
- **查询 API**：`getSwitchCount()`、`getLinkResourceCount()`、
  `getPlacedHostCount()`、`getHostCapacity()`（k³/4）、
  `getOversubscriptionRatio()`（(k²/4)/m）、`getHostPlacements()`。
- **容量注册**：`registerCapacities(TransferContentionEngine)`——引擎对资源键
  语义中立（`setEndpointCapacity` 即通用容量注册）。

## 3. 争用引擎资源集推广（`TransferContentionEngine`）

- 新增重载：
  `addTransfer(long id, long bytes, List<String> occupiedResources, double nominalRate, double now)`。
  资源键重复时按出现次数重复计数（fat-tree 路径无重复键）。
- 双端点重载委托为 `[source, destination]`，**行为逐位不变**（R2 既有全部
  测试保持绿）。
- `recomputeRates` 推广：`rate = min(nominal, min_r(capacity_r / load_r))`；
  未注册容量的资源键视为无限（SOURCE 语义不变）。
- 其余机制（线性积分、逐段吸收、确定性迭代、AdvanceResult）零改动。

## 4. 配置契约

`SimulationConfig.build()`（IAE，沿用既有消息风格）：
1. fatTree 模型要求 LOCAL 文件系统（VM→VM 通信仅 LOCAL 下被建模）；
2. fatTree 模型要求 NONE 聚类、故障禁用、`OverheadModelConfig.none()`；
3. 静态映射前提并入既有检查（preExecution 家族 + INVALID 规划拒绝）；
4. LOCAL_HEFT/LOCAL_CPOP 接受三种 preExecution 家族模型（含 fatTree）；
5. `PlanningContext.validateLocalStaticDag()` 同步接受 fatTree。

`SimulationRunner.prepareFatTreeTopology()` **双向契约**（
`SimulationConfigurationException`，会话外直接抛出、不被执行期包装）：
- 使用 fatTree 模型而平台未声明拓扑 → 拒绝；
- 平台声明拓扑而模型不使用 → 拒绝（避免声明被静默忽略）；
- 拓扑装配为纯配置计算（不触碰 CloudSim 状态），装配成功后注入
  `WorkflowDatacenter.setFatTreeTopology(...)`。

## 5. 运行时集成点

- `WorkflowDatacenter.getTransferContentionEngine()`：fatTree 模型下注册 VM
  端点容量后追加 `topology.registerCapacities(engine)`；
- `WorkflowDatacenter.fatTreePathResources(srcVm, dstVm, userId)`：经分配策略
  解析 VM→Host 后调用 `topology.route(...)`；
- `WorkflowDatacenter.processCloudletSubmit`：fatTree 与 preExecution 家族同列
  走"传输不进执行信封"路径；
- `WorkflowEngine.startContentionStageIn`：fatTree 分支下传输组资源集 =
  `[源 VM 端点（非 SOURCE 时）, 目标 VM 端点] + route(...)`；SOURCE 外部输入
  组仅目标端点（**诚实边界 v1：外部流量不经过拓扑**）；名义速率沿用
  `estimateTransferSecondsForFiles` 聚合规则；
- 证据：`DATA_STAGE_IN_MODELED` 在 fatTree 下追加 `fatTreePathLinkCount`
  （VM→VM 组路径链路总数）。

## 6. 验收（已实现并绿）

| 测试 | 内容 |
| --- | --- |
| `FatTreeTopologyTest`（9） | k=2/k=4 结构计数、放置校验（缺失/未知/超容量）、三类路由键序列黄金值、超收敛（m=3/m=2）确定性路由、双构造确定性 + 单流满速、重叠路径半速/不相交零干扰、spec 全量参数校验 |
| `TransferContentionEngineTest`（+4） | 资源集共享链路半速、端点∩链路取最小、不相交资源集零干扰、空/未注册资源名义速率 + null 校验 |
| `FatTreeContentionIntegrationTest`（8） | **R8 审计重录**：对称供给（链路 1.0 MB/s == VM 端点）黄金值 **284.1**，与 R2 端点黄金逐位相等——该等式即链路容量单位契约的端到端锁（F1 ÷8 bug 修复前同场景为 1032.1）；慢链路探针（0.25 MB/s < 端点）黄金值 **588.1** > 284.1，锁定链路束缚时模型正确生效；确定性复跑逐位一致；证据含新 kind 与 fatTreePathLinkCount > 0；健康不变量；配置契约拒绝组 + LOCAL_HEFT 允许；运行器双向契约两组拒绝 |

e2e fixture：heft-paper-example.dax × LOCAL_HEFT × 论文成本矩阵 × 3 主机
（默认轮转放置到 pod0/edge0、pod0/edge1、pod1/edge0）× k=4 满配 Fat-tree、
链路带宽 1 MB/s（与 VM 端点带宽相等——R8 审计后该对称供给下链路层不束缚单流，R6 ≡ R2；慢链路探针另行声明 0.25 MB/s 验证模型有效性）。

**campaign 装置分离（R8 再标定，2026-09-16）**：`FatTreeSchedulingCampaignExecutor`
的联合实验装置与上述 simulator fixture 相互独立——campaign 链路基线带宽经
用户授权再标定为 **0.125 MB/s**（= VM 端点 1 MB/s 的 1/8，8:1 接入超收敛，
恰恢复 F1 单位修复前声明 1.0 时的实际物理区间），A4 带宽比轴 **1.25 MB/s**
（基线真 10×，> 端点 ⇒ 非束缚 ⇒ 精确收敛回 R2）。再标定重跑实测 R6 > R2
（论文例 HEFT 5195.1 → 5738.1）、R6 列与修复前黄金值逐位相等（交叉验证
单位修复语义）。装置参数、依据与实测见
`docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md` §2.3/§2.4 与
`FATTREE_SCHEDULING_RESULTS.md`。

## 7. 诚实边界（已写入代码 javadoc 与 README）

流级流体模型；无丢包/排队细节/ECN；确定性最短路径（无自适应路由、无 ECMP
哈希）；交换机内部转发无容量约束（只约束链路与端点）；均匀链路带宽；链路
延迟不建模；外部 SOURCE 流量不经拓扑；链路/交换机故障不建模。
