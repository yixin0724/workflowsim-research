package org.workflowsim.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.workflowsim.data.TransferContentionEngine;

/**
 * Al-Fares SIGCOMM 2008 k-Pod Fat-tree 拓扑：结构构造 + 确定性路由。
 *
 * <p>结构（k 为偶数）：k 个 Pod，每 Pod 含 k/2 台 edge 与 k/2 台 aggregate
 * 交换机（Pod 内全二部连接）；core 层满配 k²/4 台，core (i,j)（c = i·(k/2)+j）
 * 连接每个 Pod 的第 i 台 aggregate。core 减量 m &lt; k²/4 即超收敛，收敛比
 * (k²/4)/m。逐层带宽守恒（edge→agg、agg→core、core 下行、主机接入的链路总数
 * 均为 k³/4）给出满二分带宽；详见 {@code docs/research/FAT_TREE_PRINCIPLES.md}。</p>
 *
 * <p><b>确定性路由（v1 规则，全部写入证据可审计）</b>：</p>
 * <ul>
 *   <li>同主机：空路径（VM 间本地交换，不占用拓扑链路）；</li>
 *   <li>上行 aggregate 选择：a = srcEdge mod availA（availA = 拥有 core 上行
 *       的 aggregate 数 = ceil(2m/k)；满配时 availA = k/2）；</li>
 *   <li>跨 Pod core 选择：在 aggregate a 的可用 core {(a,j) : a·(k/2)+j < m}
 *       中取 j = (srcEdge + dstEdge + srcPod + dstPod) mod jCount；</li>
 *   <li>结构性质决定目的 Pod 侧 aggregate 必为同一编号 a（core (a,j) 连接所有
 *       Pod 的 aggregate a）；下行链路唯一。</li>
 * </ul>
 *
 * <p>链路为双工建模：每个方向是独立容量资源（键形如
 * {@code LINK:EDGE:0:1->AGG:0:0}），反向流量不共享容量。主机接入链路同样分
 * 方向（{@code LINK:ACC:h->EDGE:p:e} / {@code LINK:EDGE:p:e->ACC:h}）。</p>
 *
 * <p>诚实边界：交换机内部转发不设容量约束（只约束链路与端点）；均匀链路带宽；
 * 链路延迟不建模；无自适应路由/ECMP 哈希/链路故障。原理与取舍见
 * {@code docs/research/FAT_TREE_PRINCIPLES.md} §5。</p>
 */
public final class FatTreeTopology {

    private final int k;
    private final int halfK;
    private final double linkBandwidthBytesPerSecond;
    private final int coreSwitchCount;
    private final int availableAggregateCount;
    private final Map<Integer, int[]> hostToPodEdge;
    private final Map<String, Double> linkCapacities;

    private FatTreeTopology(int k, double linkBandwidthBytesPerSecond, int coreSwitchCount,
            Map<Integer, int[]> hostToPodEdge, Map<String, Double> linkCapacities) {
        this.k = k;
        this.halfK = k / 2;
        this.linkBandwidthBytesPerSecond = linkBandwidthBytesPerSecond;
        this.coreSwitchCount = coreSwitchCount;
        this.availableAggregateCount = (coreSwitchCount + halfK - 1) / halfK;
        this.hostToPodEdge = hostToPodEdge;
        this.linkCapacities = linkCapacities;
    }

    /**
     * 由拓扑声明与平台主机 ID 列表构造 Fat-tree。
     *
     * @param spec 拓扑声明（{@link NetworkTopologySpec#fatTree}）
     * @param hostIds 平台全部主机 ID（顺序无关，内部按升序规范化）
     * @return 不可变拓扑实例
     * @throws IllegalArgumentException 主机数超过 k³/4、放置越界/重复/超容量、
     *         超收敛过度导致某 aggregate 无 core 上行
     */
    public static FatTreeTopology fromSpec(NetworkTopologySpec spec, List<Integer> hostIds) {
        if (spec == null || spec.getKind() != NetworkTopologySpec.Kind.FAT_TREE) {
            throw new IllegalArgumentException("FatTreeTopology requires a FAT_TREE topology spec");
        }
        if (hostIds == null || hostIds.isEmpty()) {
            throw new IllegalArgumentException("FatTreeTopology requires at least one host");
        }
        int k = spec.getK();
        int halfK = k / 2;
        List<Integer> sorted = new ArrayList<Integer>(new LinkedHashSet<Integer>(hostIds));
        if (sorted.size() != hostIds.size()) {
            throw new IllegalArgumentException("Duplicate host ids in FatTreeTopology placement: " + hostIds);
        }
        Collections.sort(sorted);
        int hostCapacity = k * k * k / 4;
        if (sorted.size() > hostCapacity) {
            throw new IllegalArgumentException("A k=" + k + " fat-tree hosts at most k^3/4="
                    + hostCapacity + " hosts, received " + sorted.size());
        }
        int coreSwitchCount = spec.getCoreSwitchCount() != null
                ? spec.getCoreSwitchCount().intValue() : k * k / 4;
        // 超收敛校验：每台 aggregate（编号 a < availA 才会被路由使用）必须至少
        // 有一条 core 上行，即 a*halfK < coreSwitchCount 对 a = availA-1 成立。
        int availableAggregateCount = (coreSwitchCount + halfK - 1) / halfK;
        if (availableAggregateCount < 1) {
            throw new IllegalArgumentException("Fat-tree core switch count too small for k=" + k
                    + ": at least 1 core switch must be reachable");
        }
        // 放置解析：显式 map 或默认轮转（hostId 升序 → edge 交换机索引轮转）。
        int edgeSwitchCount = k * halfK;
        Map<Integer, int[]> hostToPodEdge = new LinkedHashMap<Integer, int[]>();
        Map<Integer, Integer> placements = spec.getHostEdgePlacements();
        if (placements == null) {
            for (int i = 0; i < sorted.size(); i++) {
                int edgeSwitchIndex = i % edgeSwitchCount;
                hostToPodEdge.put(sorted.get(i),
                        new int[]{edgeSwitchIndex / halfK, edgeSwitchIndex % halfK});
            }
        } else {
            Map<Integer, Integer> perEdgeCount = new LinkedHashMap<Integer, Integer>();
            for (Integer hostId : sorted) {
                Integer edgeSwitchIndex = placements.get(hostId);
                if (edgeSwitchIndex == null) {
                    throw new IllegalArgumentException("Explicit fat-tree host placement must cover host "
                            + hostId);
                }
                int idx = edgeSwitchIndex.intValue();
                Integer count = perEdgeCount.get(Integer.valueOf(idx));
                int next = (count == null ? 0 : count.intValue()) + 1;
                if (next > halfK) {
                    throw new IllegalArgumentException("Fat-tree edge switch " + idx + " hosts at most k/2="
                            + halfK + " hosts");
                }
                perEdgeCount.put(Integer.valueOf(idx), Integer.valueOf(next));
                hostToPodEdge.put(hostId, new int[]{idx / halfK, idx % halfK});
            }
            for (Integer placedHost : placements.keySet()) {
                if (!hostToPodEdge.containsKey(placedHost)) {
                    throw new IllegalArgumentException("Explicit fat-tree host placement references unknown host "
                            + placedHost);
                }
            }
        }
        Map<String, Double> linkCapacities = buildLinkCapacities(k, halfK, coreSwitchCount,
                hostToPodEdge, spec.getLinkBandwidthMbPerSecond() * 1_000_000.0 / 8.0);
        return new FatTreeTopology(k, spec.getLinkBandwidthMbPerSecond() * 1_000_000.0 / 8.0,
                coreSwitchCount, Collections.unmodifiableMap(hostToPodEdge),
                Collections.unmodifiableMap(linkCapacities));
    }

    /** 构造全部物理链路容量表（双工分方向；仅已放置主机的接入链路）。 */
    private static Map<String, Double> buildLinkCapacities(int k, int halfK, int coreSwitchCount,
            Map<Integer, int[]> hostToPodEdge, double bandwidthBytesPerSecond) {
        Map<String, Double> capacities = new LinkedHashMap<String, Double>();
        for (Map.Entry<Integer, int[]> entry : hostToPodEdge.entrySet()) {
            int hostId = entry.getKey().intValue();
            String edge = "EDGE:" + entry.getValue()[0] + ":" + entry.getValue()[1];
            capacities.put("LINK:ACC:" + hostId + "->" + edge, Double.valueOf(bandwidthBytesPerSecond));
            capacities.put("LINK:" + edge + "->ACC:" + hostId, Double.valueOf(bandwidthBytesPerSecond));
        }
        for (int pod = 0; pod < k; pod++) {
            for (int edge = 0; edge < halfK; edge++) {
                for (int agg = 0; agg < halfK; agg++) {
                    capacities.put("LINK:EDGE:" + pod + ":" + edge + "->AGG:" + pod + ":" + agg,
                            Double.valueOf(bandwidthBytesPerSecond));
                    capacities.put("LINK:AGG:" + pod + ":" + agg + "->EDGE:" + pod + ":" + edge,
                            Double.valueOf(bandwidthBytesPerSecond));
                }
            }
            // aggregate 的 core 上行：core c = agg*halfK + j，仅 c < coreSwitchCount 存在。
            for (int agg = 0; agg < halfK; agg++) {
                for (int j = 0; j < halfK && agg * halfK + j < coreSwitchCount; j++) {
                    int core = agg * halfK + j;
                    capacities.put("LINK:AGG:" + pod + ":" + agg + "->CORE:" + core,
                            Double.valueOf(bandwidthBytesPerSecond));
                    capacities.put("LINK:CORE:" + core + "->AGG:" + pod + ":" + agg,
                            Double.valueOf(bandwidthBytesPerSecond));
                }
            }
        }
        return capacities;
    }

    /**
     * 确定性路由：返回源主机到目的主机传输占用的链路资源键序列（上行在前、
     * 下行在后，含两端接入链路）。
     *
     * @param srcHostId 源主机 ID
     * @param dstHostId 目的主机 ID
     * @return 资源键列表；同主机返回空列表
     * @throws IllegalArgumentException 主机未放置在拓扑中
     */
    public List<String> route(int srcHostId, int dstHostId) {
        int[] src = requirePlaced(srcHostId);
        int[] dst = requirePlaced(dstHostId);
        List<String> path = new ArrayList<String>();
        if (srcHostId == dstHostId) {
            return path;
        }
        String srcEdge = "EDGE:" + src[0] + ":" + src[1];
        String dstEdge = "EDGE:" + dst[0] + ":" + dst[1];
        path.add("LINK:ACC:" + srcHostId + "->" + srcEdge);
        if (srcEdge.equals(dstEdge)) {
            path.add("LINK:" + dstEdge + "->ACC:" + dstHostId);
            return path;
        }
        int agg = src[1] % availableAggregateCount;
        path.add("LINK:" + srcEdge + "->AGG:" + src[0] + ":" + agg);
        if (src[0] != dst[0]) {
            int uplinks = coreUplinkCount(agg);
            int j = (src[1] + dst[1] + src[0] + dst[0]) % uplinks;
            int core = agg * halfK + j;
            path.add("LINK:AGG:" + src[0] + ":" + agg + "->CORE:" + core);
            path.add("LINK:CORE:" + core + "->AGG:" + dst[0] + ":" + agg);
        }
        path.add("LINK:AGG:" + dst[0] + ":" + agg + "->" + dstEdge);
        path.add("LINK:" + dstEdge + "->ACC:" + dstHostId);
        return path;
    }

    /**
     * 把全部链路容量注册进争用引擎（作为通用资源键；引擎对资源键语义中立）。
     *
     * @param engine 流体争用引擎
     */
    public void registerCapacities(TransferContentionEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("Contention engine cannot be null");
        }
        for (Map.Entry<String, Double> entry : linkCapacities.entrySet()) {
            engine.setEndpointCapacity(entry.getKey(), entry.getValue().doubleValue());
        }
    }

    private int[] requirePlaced(int hostId) {
        int[] podEdge = hostToPodEdge.get(Integer.valueOf(hostId));
        if (podEdge == null) {
            throw new IllegalArgumentException("Host " + hostId + " is not placed in the fat-tree topology");
        }
        return podEdge;
    }

    /** aggregate a 的可用 core 上行数量（≥ 1 由构造校验保证）。 */
    private int coreUplinkCount(int agg) {
        return Math.min(halfK, coreSwitchCount - agg * halfK);
    }

    /** k-Pod 参数。 */
    public int getK() {
        return k;
    }

    /** core 交换机数量（满配 = k²/4）。 */
    public int getCoreSwitchCount() {
        return coreSwitchCount;
    }

    /** 交换机总数 = core + k 个 Pod × k 台。 */
    public int getSwitchCount() {
        return coreSwitchCount + k * k;
    }

    /** 物理链路资源键总数（双工分方向计数）。 */
    public int getLinkResourceCount() {
        return linkCapacities.size();
    }

    /** 已放置主机数。 */
    public int getPlacedHostCount() {
        return hostToPodEdge.size();
    }

    /** 主机容量上限 k³/4。 */
    public int getHostCapacity() {
        return k * k * k / 4;
    }

    /** 超收敛比 (k²/4)/m；满配为 1.0。 */
    public double getOversubscriptionRatio() {
        return (k * k / 4.0) / coreSwitchCount;
    }

    /** 统一链路带宽，字节/秒。 */
    public double getLinkBandwidthBytesPerSecond() {
        return linkBandwidthBytesPerSecond;
    }

    /** 主机 → {pod, edgeIndex} 的不可变放置视图（测试与证据用）。 */
    public Map<Integer, int[]> getHostPlacements() {
        Map<Integer, int[]> copy = new LinkedHashMap<Integer, int[]>();
        for (Map.Entry<Integer, int[]> entry : hostToPodEdge.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }
}
