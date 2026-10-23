# Fat-tree Topology, Routing, and Sharing Model

**English** · [简体中文](<FAT_TREE_DESIGN.zh.md>)

## Structure and Scope

[NetworkTopologySpec](<../../simulator/src/main/java/org/workflowsim/network/NetworkTopologySpec.java>) describes the platform topology, and [FatTreeTopology](<../../simulator/src/main/java/org/workflowsim/network/FatTreeTopology.java>) implements the structure, host placement, fixed routing, and directed-link capacities. The transfer service is computed by [TransferContentionEngine](<../../simulator/src/main/java/org/workflowsim/data/TransferContentionEngine.java>), not implemented in the routing layer.

The structure follows the k-Pod Fat-tree idea of Al-Fares et al., with [A Scalable, Commodity Data Center Network Architecture](<https://doi.org/10.1145/1402958.1402967>) as the background literature. What is implemented here is a declarative flow-level model, not a complete reconstruction of the paper's switch protocols or of an actual data center.

| Quantity | Full configuration |
|---|---|
| Pod | k, an even number of at least 2 |
| Edge / aggregate switches per pod | k/2 each |
| Core switches | k²/4; may be explicitly reduced to 1≤m≤k²/4 |
| Host capacity | k³/4, with at most k/2 hosts per edge switch |
| Total switches | 5k²/4 in the full configuration |
| Undirected edge–aggregate and aggregate–core links | k³/4 per level in the full configuration |

Within a pod, the edge and aggregate switches are fully bipartitely connected; core `(a,j)` connects to the a-th aggregate of every pod. The full configuration provides the corresponding aggregated path capacity, but **it is not guaranteed that this implementation's fixed routing is non-blocking for arbitrary communication permutations**. The structural ratio `(k²/4)/m` of a reduced core count is not a proportional prediction of throughput for arbitrary workloads.

## Declaration and Host Placement

```java
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond);
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond, coreSwitchCount);
NetworkTopologySpec.fatTree(k, linkBandwidthMbPerSecond,
        coreSwitchCount, hostEdgePlacements);
```

- `hostEdgePlacements` maps Host IDs to global edge indices, the index being `pod*(k/2)+edge` in the range `[0,k²/2)`. An explicit map must cover every Host and must not exceed the per-edge capacity.
- By default, Hosts are placed round-robin over the edge switches in ascending Host-ID order, rather than packing adjacent Hosts onto the same edge switch.
- VM paths are based on the actual runtime VM→Host placement; the Host is not guessed from the VM number.
- Links are declared in decimal MB/s and served in B/s; the conversion factor is 1,000,000, with no additional division by 8.
- Workbench restricts k=2..16, even; the coherent V2/V3 runners additionally restrict the Java entry points to `k=2..32` and to converted B/s values that are positive, finite, and normal. The low-level factories are not a promise that arbitrarily huge topologies can be constructed safely.

## Routing and Directed Resources

`route(srcHostId,dstHostId)` returns a deterministic, ordered list of link resources:

1. The same Host returns an empty topological path; different VMs are still handled under their own endpoint constraints, which is not zero-VM communication.
2. Within the same edge switch, the two Host access links are traversed.
3. Within the same pod but a different edge switch, the source/destination access links and the edge–aggregate paths are traversed.
4. Across pods, the aggregate–core path and the destination pod's downlink path are additionally traversed.

The aggregate is chosen as `a=srcEdge mod availA` with `availA=ceil(2m/k)`; across pods, one path is selected from that aggregate's available cores by `(srcEdge+dstEdge+srcPod+dstPod) mod jCount`. The downlink is fixed by the chosen core and the destination position. There is no ECMP hashing, dynamic load sensing, or adaptive re-routing. Reducing the core count also changes which aggregates are available and what the fixed routes are; the performance change of an entire DAG must not be deduced from one layer's structure alone.

The two directions of a duplex link are independent resources, for example:

```text
LINK:ACC:h->EDGE:p:e       LINK:EDGE:p:e->ACC:h
LINK:EDGE:p:e->AGG:p:a     LINK:AGG:p:a->EDGE:p:e
LINK:AGG:p:a->CORE:c       LINK:CORE:c->AGG:p:a
```

A flow occupies its endpoint and path resources simultaneously. Repeated occurrences of a resource key are charged by the number of occupations and must not be deduplicated at will in validation or display.

## Shared Service

The allocator applies max-min progressive filling, jointly satisfying each flow's nominal cap and the weighted-capacity constraints of all occupied resources. When a flow is limited by another bottleneck or by its nominal cap, its unused share can be returned to the other flows; only in the symmetric case without additional bottlenecks does this simplify to `capacity / flow count`, and the approximation must not be applied in the general case.

Segment-wise integration and reallocation happen at internal boundaries such as admission and completion. An internally effective completion and an externally observed one can differ; physical visibility follows the corresponding model contract and is not replaced by the display refresh time. Adding constraints may change share reclamation, cache visibility, or subsequent source selection; monotonicity of whole-DAG completion time across models is not guaranteed.

## Composition with the Dataflow Models

| Branch | Topology declaration | SOURCE and execution boundary |
|---|---|---|
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1` | Required | LOCAL, the pre-placed static mapping, and its configuration boundary; parent-group/external group-level input preparation, with external SOURCE groups bypassing the topology |
| File dataflow V2 shared/independent controls | Optional | LOCAL; actual per-file VM paths; the external SOURCE still aggregates explicitly without limit and bypasses the fabric |
| Storage dataflow V3 shared/independent controls | Optional | LOCAL, or SHARED reads after the commit; SOURCE has an actual access Host and read/write/NIC bounds, with inputs and required outputs using the corresponding paths |
| Models that do not use the topology | No surplus declaration allowed | Avoids the platform declaration being silently ignored |

V2/V3 may use the default RANDOM/STATIC, or explicit control-ready online target assignment + STATIC. They are not the old CPU online schedulers wired directly onto a contention model. See the [V2 contract](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>), the [V3 contract](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>), and [online assignment](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) for details.

## Capabilities Not Included

There is no packet-level TCP/retransmission, packet loss, ECN, switch-queue detail, link propagation delay, intra-switch forwarding capacity, link failure, or adaptive routing. The flow-level model suits scheduling research under the declared abstraction, but it does not claim to be lossless in information and cannot replace real-network calibration.

The topology, source selection, capacity service, and evidence are tested separately. For the validation scope see the [V1 ledger validation](<../advanced/NETWORK_LEDGER_VALIDATION.md>) and the [per-file lifecycle format](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>); for run commands see the [build guide](<../getting-started/BUILD.md>). Historical campaign figures are interpreted only in their [data-source description](<../../experiments/studies/fattree-scheduling-campaign/RETENTION.md>) and are not universal golden values of the current implementation.
