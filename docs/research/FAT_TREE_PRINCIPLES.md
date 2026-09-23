# Fat-tree 网络拓扑原理研读笔记（Phase 0）

> **目的**：在实现 Fat-tree 拓扑感知链路争用模型之前，把原理、既有实现先例和
> 与平台的映射关系研读清楚。本笔记是实现的前置条件，任何实现决策与本笔记冲突
> 时，先修本笔记。
>
> **来源验证状态声明**：Al-Fares 原文 PDF 在本环境无法直接下载（SIGCOMM/大学
> 镜像均被网络策略拦截），其元数据经 OpenAlex（被引 1676）与 Semantic Scholar
> （被引 3897）双重验证；文中 Al-Fares 相关结构性结论为教科书级共识知识，
> 全部公式经独立推导并互相交叉验证。SimGrid `FatTreeZone` 与仓库内 CloudSim
> 3.0.3 `network.datacenter` 源码为**全文精读**的一手材料。

---

## 0. 结论摘要

1. Fat-tree 的科学价值在两点：**无过订的满二分带宽结构**（可手算验证的性质）
   与**确定性路由下共享链路的精确定位**（争用计算的输入）。
2. 调度研究领域的主流做法是**流级（flow-level）流体模型**（SimGrid 路线），
   不是包级（packet-level，NS-3 路线）——流级模型可精确积分、可锁黄金值，
   对调度研究没有信息损失。
3. 我们平台 R2 的 `TransferContentionEngine`（VM 端点公平共享）正是流级模型的
   一个退化情形（争用域 = 端点）；Fat-tree 模型是把争用域推广为**路径上的每条
   共享链路**，R2 的事件时间线性积分、速率变化逐段吸收机制可直接复用。
4. 仓库内 CloudSim 3.0.3 自带的 `network.datacenter` 包**不可复用**：包级、
   固定三层、每 edge 只连一台 aggregate（是树不是胖树）、依赖静态全局状态
   `NetworkConstants`，违反平台配置契约哲学。详见 §3.3。
5. 第一版实现建议采用 **Al-Fares 严格 k-Pod 结构 + 确定性路由**（比 SimGrid 的
   PGFT/D-Mod-K 通用化更简单、更经典、更易手算验证）；PGFT 通用化留作扩展。

---

## 1. Al-Fares 2008：k-ary Fat-tree 结构原理

**文献**：Al-Fares, Loukissas, Vahdat. *A Scalable, Commodity Data Center
Network Architecture*. ACM SIGCOMM 2008.
DOI: [10.1145/1402958.1402967](https://doi.org/10.1145/1402958.1402967)

**动机**：传统多层树形数据中心网络带宽逐层向根收敛（core 过订），而高端交换
机昂贵。Fat-tree 只用廉价的 k 端口商用交换机，构造出任意二分切面带宽都不缩减
的网络（"胖树"：越往上链路越多）。

### 1.1 k-Pod 结构（k 为偶数，全部交换机同为 k 端口）

三层结构，自底向上：

```
                 ┌──────────────────────────────┐
        core     │  k²/4 台，每台 k 口（每口连一个 Pod）│
                 └──────────────────────────────┘
                       ▲    ▲    ▲        ▲
        pods      k 个 Pod（下文展开一个 Pod）
                 ┌─────────────────────┐
                 │ aggregate：k/2 台    │  每台：k/2 口↓连全部 edge，k/2 口↑连 core
                 │ edge     ：k/2 台    │  每台：k/2 口↓连主机，k/2 口↑连全部 aggregate
                 └─────────────────────┘
                       │ │ │ │
        hosts    每台 edge 下挂 k/2 台主机
```

**连接规则（精确）**：
- Pod 内全二部连接：每台 edge 交换机的 k/2 个上行口分别连到本 Pod 的 k/2 台
  aggregate（每台一根）；反向同理。
- Core 层编号 (i, j)，i, j ∈ [0, k/2)。core (i, j) 的 k 个端口各连一个 Pod，
  在 Pod p 中连的是该 Pod 的第 i 台 aggregate 交换机。
- 因此每台 aggregate 交换机 i（i ∈ [0, k/2)）的 k/2 条上行链路连向 core
  {(i, 0), (i, 1), …, (i, k/2−1)}；每台 core 有 k/2 组下行端口组。

### 1.2 计数公式（已独立推导验证）

| 量 | 公式 | k=4 | k=48 |
| --- | --- | --- | --- |
| 主机数 | k³/4 | 16 | 27,648 |
| core 交换机 | k²/4 | 4 | 576 |
| Pod 内交换机（每 Pod k 台 × k 个 Pod） | k² | 16 | 2,304 |
| 交换机总数 | 5k²/4 | 20 | 2,880 |
| 链路总数（不含主机接入） | k³/2 | 32 | 55,296 |

**逐层带宽守恒（"胖"的定量含义）**：
- 主机上行链路总数 = k³/4；
- Pod 内 edge→aggregate 上行链路 = 每 Pod (k/2)×(k/2)=k²/4 条 × k 个 Pod = k³/4；
- aggregate→core 上行链路 = 同 = k³/4；
- core 下行端口 = (k²/4)×k = k³/4。
- **每一层链路的聚合带宽都等于主机侧聚合接入带宽 k³/4·bw** → 满二分带宽
  （full bisection bandwidth），网络在任何一对一通信模式下都不成为瓶颈。
  这是实现的正确性判据之一：不相交路径上的并发传输必须互不减速（§4 黄金值
  场景 3）。

### 1.3 寻址与路由

- **结构化寻址**：主机地址 `10.pod.switch.port`（k ≤ 255 时可编码进 IPv4），
  三段下标使任何交换机仅凭目的地址的第三段即可判断上行方向。
- **确定性最短路径路由（SSPF 语义）**：同 Pod 流量不出 Pod（edge→aggregate→
  edge）；跨 Pod 流量上行到 core 层再下行。上行每层有 k/2 条等价链路，原文用
  确定性规则（源流五元组哈希的稳定选择）保证同一传输的所有"分组"走同一条
  路径以避免乱序——在流级模型中等价于**为每条流确定性地选定唯一路径**。
- **下行唯一**：从选定 core 回到目的主机，每一层的下行链路都是唯一确定的。

### 1.4 过量收敛比（oversubscription）

core 交换机数量 m 可从 k²/4 减到更小，得到收敛比 r = (k²/4)/m > 1 的超收敛
网络（真实数据中心普遍如此）。此时**跨 Pod 流量会挤在更少的 core 端口上**，
争用集中出现在 core 层——这正是我们做对照实验的旋钮（§4 黄金值场景 4）。
Pod 内结构不受影响（Pod 内永远 1:1）。

---

## 2. PGFT 与 D-Mod-K 路由（Zahavi 2010，SimGrid 的实现基础）

**文献**（经 SimGrid 源码注释与示例 XML 引用；OpenAlex/Crossref 未收录该会议
论文，Technion PDF 链接 https://ece.technion.ac.il/wp-content/uploads/2021/01/
publication_776.pdf 在本环境不可达）：Zahavi, *D-Mod-K Routing Providing
Non-Blocking Traffic for Shift Permutations on Real Life Fat Trees*, Hot
Interconnects, 2010.

要点（与我们的实现相关性在于理解 SimGrid 为什么那样写）：

- **PGFT 参数化**：`h ; m_1,…,m_h ; w_1,…,w_h ; p_1,…,p_h`——h 层交换机；
  第 i 层节点挂 m_i 个下层节点；第 i−1 层节点有 w_i 个上层邻居；层间 p_i 条
  并行链路。主机数必须恰为 ∏m_i。Al-Fares k-Pod 是该参数化的一个特例。
- **D-Mod-K 路由**：上行链路的选择 = 对目的主机编号逐层整除后取模
  （"destination mod k"），使 shift-permutation 类流量模式（HPC MPI 集合通信
  的典型模式）获得无阻塞性质。
- **与 Al-Fares 的差别**：Al-Fares 的上行选择只要求确定性+负载均衡；D-Mod-K
  对特定流量模式给出更强的无阻塞保证。我们第一版不需要这个强度。

---

## 3. 既有仿真实现调研

### 3.1 SimGrid `FatTreeZone`（全文精读，498 行 .cpp + 156 行 .hpp）

源码：simgrid/simgrid `src/kernel/routing/FatTreeZone.{hpp,cpp}`（GitHub master，
2025 年仍在维护）。机制拆解：

- **构造**：`set_topology(h, m[], w[], p[])` → `generate_switches()` 按逐层计数
  公式创建交换机 → `generate_labels()` 用混合进制计数给每个节点分配 h 维 label
  → `connect_node_to_parents()` 按 label 前缀/后缀匹配（`are_related`）建立
  层间连接，支持 p_i 条并行链路。
- **路由** `get_local_route(src, dst)`：先用 `is_in_sub_tree` 判断当前节点是否
  已是目的的祖先；不是则按 D-Mod-K 公式选一条上行链路（`parents[d]`）并爬升；
  到达公共祖先后下行，下行从 p_i 条并行缆中按 `source->position % p` 确定性
  选缆。**返回的是一条链路列表**——争用不发生在路由层。
- **争用**：由 SimGrid 的 network model 对"链路上的并发流"做 max-min 公平
  共享（流级流体模型）；`limiter_link_` 表示节点自身带宽上限（对应我们的
  VM 端点 bw）；`loopback_` 表示同节点内通信旁路。
- **链路方向性**：SPLITDUPLEX 策略下每条物理链路由 up_link/down_link 两条独立
  容量组成（双工），否则单条共享。
- **测试**（85 行）：仅参数校验（层数/向量长度/零值/带宽/延迟非法），路由
  正确性由 s4u 层测试覆盖。

**可借鉴**：构造与路由分离、路由只返回链路序列、争用全部下沉到流体引擎、
limiter = 端点容量。**不可照搬**：PGFT 通用化对我们第一版是过度设计。

### 3.2 NS-3 生态

NS-3 核心库**没有**官方 Fat-tree 模块（GitHub code search 无命中）；社区有
包级第三方示例（如 `AmitBhalerao/DCN-Fat-Tree-Topology-using-NS3`、
`HenryHelstad/fattreeDCNsimulation`，均经 GitHub API 确认存在）。包级模型服务
于网络协议研究；调度研究主流用流级。我们不采包级路线。

### 3.3 仓库内 CloudSim 3.0.3 `network.datacenter`（全文精读关键类）

`org.cloudbus.cloudsim.network.datacenter`：`Switch`（SimEntity，包级转发：
`processPacket` → 上行/下行队列 → 按带宽调度延迟事件）、`EdgeSwitch` /
`AggregateSwitch` / `RootSwitch`、`NetworkDatacenter`、`NetworkHost`、
`HostPacket`、`NetworkConstants`（全局静态常量：带宽、层级编号）。

**不可复用的原因（四条，均违反平台契约）**：
1. **不是胖树**：`Switch.java` 源码注释 "ASSUMPTION EACH EDGE is Connected to
   one aggregate level switch"——每 edge 单上行，是退化的树，没有多路径与满
   二分带宽性质，与 Fat-tree 的科学目标不符。
2. **包级 SimEntity 事件链**：与我们的数据移动模型（传输组登记 + 流体积分）
   架构不兼容，复用等于重写。
3. **`NetworkConstants` 静态全局状态**：违反平台"不可变配置 + 显式契约"哲学
   （这是我们 R0-P0 加固明确消除的反模式）。
4. 历史上从未被 WorkflowSim 轨道使用，无测试覆盖。

**结论**：全新实现，但保留"每交换机有 uplinkbandwidth/downlinkbandwidth、
uplinkswitches/downlinkswitches 列表"这一朴素对象建模的合理内核。

---

## 4. 争用语义与平台映射

### 4.1 模型语义（流级流体 + 链路级 max-min 公平共享）

- 一次数据传输 = 一条**流**（有字节量）；路由为它确定一条**链路序列**
  （主机上行口 → edge → aggregate → core → … → 目的主机下行口）。
- R10使用max-min progressive filling，同时满足每条流名义速率和全部资源容量约束。
  当某条流受别处瓶颈限制时，剩余容量回分给其他流；只有对称无额外瓶颈时才简化为capacity/n。
- 字节余量在每个传输开始/内部完成时点分段积分并重分配；即使CloudSim检查较晚，也不延迟带宽回收。
- VM端点与有向链路共同约束。加入约束可能通过回收份额加速其他流，整个DAG完成时间跨模型不保证单调。

### 4.2 可手算黄金值场景（验收设计，Phase 2 实现）

| # | 场景 | 预期 | 验证的性质 |
| --- | --- | --- | --- |
| 1 | 两条跨 Pod 流共享一条 core 下行链路 | 各得该链路半速 | 链路公平共享 |
| 2 | 两条流汇向同一目的主机（不同源 Pod） | 受目的主机下行口限制 | 端点∩链路取最小 |
| 3 | 两条不相交路径的流并发 | 互不减速 | 无阻塞性质 |
| 4 | core 减半（2:1 超收敛）下的跨 Pod 流 vs Pod 内流 | 前者瓶颈减半、后者不受影响 | 超收敛语义 |
| 5 | 单条流独占路径 | 速率 = min(链路 bw, 端点 bw)，完成时刻 = 字节/速率 | 基线退化 |

### 4.3 与现有模型的关系

新增并列数据移动模型（如 `fatTreeContentionV1`），**不替代** R2 端点模型与
legacy 模型：拓扑无关 vs 拓扑感知是两个合法研究维度，三者构成可对照的模型族。
合法组合约束（配置层强制，沿用 SimulationConfig 既有模式）：要求静态 VM 映射
（路径在规划期即确定，与 preExecution 家族同一前提）、NONE 聚类、无故障等，
细节在 Phase 1 设计中定稿。

---

## 5. 设计决策（沿用已向用户确认的推荐）

| 决策点 | 选择 | 理由 |
| --- | --- | --- |
| 仿真粒度 | 流级流体模型 | 调度研究主流（SimGrid）；可锁黄金值 |
| 拓扑形态 | Al-Fares 严格 k-Pod（第一版） | 经典、可手算；PGFT 留作扩展 |
| 路由 | 确定性最短路径（同 Pod 不出 Pod；跨 Pod 上行确定性选 core、下行唯一） | 可审计、可复现 |
| VM→拓扑放置 | PlatformProfile 显式声明（host → pod/edge） | 平台"确定性配置契约"哲学 |
| 与 R2 关系 | 新增并列模型 | 拓扑无关/拓扑感知双研究维度 |
| 超收敛 | core 数量可配置参数（默认满配 = 无过订） | 免费实验旋钮 |
| 诚实边界 | 流级、无丢包/排队细节/ECN、无自适应路由、无链路故障、均匀链路带宽、链路延迟 v1 忽略或统一参数 | 写入文档声明 |

---

## 6. 来源清单（验证状态）

| 来源 | 验证状态 |
| --- | --- |
| Al-Fares et al., SIGCOMM 2008, DOI 10.1145/1402958.1402967 | 元数据经 OpenAlex（1676 引）/Semantic Scholar（3897 引）验证；PDF 本环境不可得，结构结论为教科书级共识，公式独立推导 |
| Zahavi, Hot Interconnects 2010（D-Mod-K） | 经 SimGrid 源码注释与示例 XML 引用验证；OpenAlex/Crossref 未收录 |
| SimGrid `FatTreeZone`（simgrid/simgrid GitHub master） | **全文精读**（.cpp 498 行 + .hpp 156 行 + 测试 85 行 + 示例 XML） |
| CloudSim 3.0.3 `org.cloudbus.cloudsim.network.datacenter` | 仓库内源码，关键类**精读**（Switch/NetworkConstants 等 18 个类） |
| NS-3 Fat-tree 生态 | GitHub API 搜索验证（核心库无官方模块；第三方包级示例存在） |
