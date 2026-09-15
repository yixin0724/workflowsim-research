package org.workflowsim.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.TransferContentionEngine;

/**
 * Al-Fares k-Pod Fat-tree 拓扑的结构、放置与确定性路由单测。
 *
 * <p>黄金值全部来自 {@code docs/research/FAT_TREE_PRINCIPLES.md} §1 的手工推导：
 * k=2 最小拓扑（2 主机、5 交换机、1 core）与 k=4 拓扑（16 主机容量、20 交换机、
 * 4 core）。路由键序列按 v1 确定性规则手算：上行 aggregate = srcEdge mod availA；
 * 跨 Pod core 选择 j = (srcEdge + dstEdge + srcPod + dstPod) mod 该 aggregate 的
 * 可用 core 数。</p>
 */
public class FatTreeTopologyTest {

    private static List<Integer> hosts(int count) {
        List<Integer> ids = new ArrayList<Integer>();
        for (int id = 0; id < count; id++) {
            ids.add(Integer.valueOf(id));
        }
        return ids;
    }

    /** k=2 最小结构：2 主机、5 交换机（1 core + 2 Pod × 2 台）、12 个链路资源键。 */
    @Test
    public void minimalK2StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(2, 100.0), hosts(2));
        assertEquals(2, topology.getK());
        assertEquals(1, topology.getCoreSwitchCount());
        assertEquals(5, topology.getSwitchCount());
        assertEquals(2, topology.getHostCapacity());
        assertEquals(2, topology.getPlacedHostCount());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);
        // 接入 2×2 + Pod 内 edge↔agg 2 Pod×1×1×2 + agg↔core 2 Pod×1×2 = 12。
        assertEquals(12, topology.getLinkResourceCount());
        // 8 Mb/s... 100 MB/s = 100×10⁶/8 字节/秒。
        assertEquals(100.0 * 1_000_000.0 / 8.0,
                topology.getLinkBandwidthBytesPerSecond(), 1.0e-6);
        // 默认轮转放置：host0 → Pod0，host1 → Pod1（各 1 台 edge）。
        Map<Integer, int[]> placements = topology.getHostPlacements();
        assertEquals(0, placements.get(Integer.valueOf(0))[0]);
        assertEquals(1, placements.get(Integer.valueOf(1))[0]);
    }

    /** k=2 跨 Pod 路由黄金值：6 个资源键，经唯一 core。 */
    @Test
    public void crossPodRouteOnMinimalTopology() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(2, 100.0), hosts(2));
        assertEquals(Arrays.asList(
                "LINK:ACC:0->EDGE:0:0",
                "LINK:EDGE:0:0->AGG:0:0",
                "LINK:AGG:0:0->CORE:0",
                "LINK:CORE:0->AGG:1:0",
                "LINK:AGG:1:0->EDGE:1:0",
                "LINK:EDGE:1:0->ACC:1"), topology.route(0, 1));
        // 同主机：本地交换，不占拓扑链路。
        assertEquals(Collections.emptyList(), topology.route(0, 0));
    }

    /** k=4 满配结构：20 交换机、16 主机容量、80 链路资源键（8 主机放置）。 */
    @Test
    public void fullK4StructureMatchesHandComputedCounts() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        assertEquals(4, topology.getCoreSwitchCount());
        assertEquals(20, topology.getSwitchCount());
        assertEquals(16, topology.getHostCapacity());
        assertEquals(1.0, topology.getOversubscriptionRatio(), 0.0);
        // 接入 8×2 + Pod 内 4×2×2×2 + agg↔core 4 Pod×2 agg×2 core×2 方向 = 80。
        assertEquals(80, topology.getLinkResourceCount());
        // 默认轮转：host i → edgeSwitchIndex i mod 8 → pod=i/2, edge=i%2。
        Map<Integer, int[]> placements = topology.getHostPlacements();
        for (int i = 0; i < 8; i++) {
            assertEquals(i / 2, placements.get(Integer.valueOf(i))[0], "host " + i + " pod");
            assertEquals(i % 2, placements.get(Integer.valueOf(i))[1], "host " + i + " edge");
        }
    }

    /** k=4 同 edge / 同 Pod 跨 edge / 跨 Pod 三类路由黄金值。 */
    @Test
    public void routeClassesOnK4() {
        // 9 台主机 → host0 与 host8 轮转到同一 edge (0,0)。
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        // 同 edge（2 键）。
        assertEquals(Arrays.asList(
                "LINK:ACC:0->EDGE:0:0",
                "LINK:EDGE:0:0->ACC:8"), topology.route(0, 8));
        // 同 Pod 跨 edge：host0 (0,0) → host1 (0,1)，agg = 0%2 = 0（4 键）。
        assertEquals(Arrays.asList(
                "LINK:ACC:0->EDGE:0:0",
                "LINK:EDGE:0:0->AGG:0:0",
                "LINK:AGG:0:0->EDGE:0:1",
                "LINK:EDGE:0:1->ACC:1"), topology.route(0, 1));
        // 跨 Pod：host0 (0,0) → host2 (1,0)。agg = 0；uplinks = min(2, 4−0) = 2；
        // j = (0+0+0+1) mod 2 = 1 → core 1（6 键）。
        assertEquals(Arrays.asList(
                "LINK:ACC:0->EDGE:0:0",
                "LINK:EDGE:0:0->AGG:0:0",
                "LINK:AGG:0:0->CORE:1",
                "LINK:CORE:1->AGG:1:0",
                "LINK:AGG:1:0->EDGE:1:0",
                "LINK:EDGE:1:0->ACC:2"), topology.route(0, 2));
    }

    /** 路由确定性：同参数两次构造给出逐位相同的路径。 */
    @Test
    public void routingIsDeterministicAcrossConstructions() {
        FatTreeTopology first = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        FatTreeTopology second = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(9));
        for (int src = 0; src < 9; src++) {
            for (int dst = 0; dst < 9; dst++) {
                assertEquals(first.route(src, dst), second.route(src, dst));
                // 路由键全部为已注册容量的物理链路（registerCapacities 覆盖性）。
                TransferContentionEngine engine = new TransferContentionEngine();
                first.registerCapacities(engine);
                engine.addTransfer(1L, 1000L, first.route(src, dst), 1.0e12, 0.0);
                double expected = src == dst ? 1.0e12
                        : Math.min(1.0e12, 100.0 * 1_000_000.0 / 8.0);
                assertEquals(expected, engine.currentRateBytesPerSecond(1L), 1.0e-6,
                        "route " + src + "->" + dst + " 的单流速率应为链路带宽（无共享）");
            }
        }
    }

    /** 超收敛：core 减量收敛比正确、路由仍确定性、无 core 上行的 aggregate 不被使用。 */
    @Test
    public void oversubscribedCoreReduction() {
        // k=4, m=3：availA = ceil(2×3/4) = 2；agg0 有 core {0,1}，agg1 有 core {2}。
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0, 3), hosts(8));
        assertEquals(4.0 / 3.0, topology.getOversubscriptionRatio(), 1.0e-12);
        // 交换机 = core 3 + k Pod × k 台 = 19。
        assertEquals(19, topology.getSwitchCount());
        // agg↔core 链路资源数：4 Pod × (agg0 2 core + agg1 1 core) × 2 方向 = 24；
        // 接入 16 + Pod 内 32 + 24 = 72。
        assertEquals(72, topology.getLinkResourceCount());
        // host1 (0,1) → host3 (1,1)：agg = 1%2 = 1；uplinks(1) = min(2, 3−2) = 1；
        // j = (1+1+0+1) mod 1 = 0 → core 2。
        assertEquals(Arrays.asList(
                "LINK:ACC:1->EDGE:0:1",
                "LINK:EDGE:0:1->AGG:0:1",
                "LINK:AGG:0:1->CORE:2",
                "LINK:CORE:2->AGG:1:1",
                "LINK:AGG:1:1->EDGE:1:1",
                "LINK:EDGE:1:1->ACC:3"), topology.route(1, 3));
        // k=4, m=2：availA = ceil(4/4) = 1 → 全部流量走 agg0；收敛比 2.0。
        FatTreeTopology heavy = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0, 2), hosts(8));
        assertEquals(2.0, heavy.getOversubscriptionRatio(), 0.0);
        assertEquals("LINK:EDGE:0:1->AGG:0:0", heavy.route(1, 3).get(1));
    }

    /** 显式放置：覆盖、容量与未知主机校验。 */
    @Test
    public void explicitPlacementValidation() {
        // 显式放置：host0/host1 同放 edge0（k=4 每 edge 容量 2，合法）。
        Map<Integer, Integer> placement = new LinkedHashMap<Integer, Integer>();
        placement.put(Integer.valueOf(0), Integer.valueOf(0));
        placement.put(Integer.valueOf(1), Integer.valueOf(0));
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0, null, placement), hosts(2));
        assertEquals(0, topology.getHostPlacements().get(Integer.valueOf(1))[1]);
        // 同 edge 路由（2 键）。
        assertEquals(2, topology.route(0, 1).size());
        // 缺失主机覆盖 → 拒绝。
        Map<Integer, Integer> partial = new LinkedHashMap<Integer, Integer>();
        partial.put(Integer.valueOf(0), Integer.valueOf(0));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(
                        NetworkTopologySpec.fatTree(4, 100.0, null, partial), hosts(2)));
        assertTrue(missing.getMessage().contains("must cover host 1"), missing.getMessage());
        // 未知主机 → 拒绝。
        Map<Integer, Integer> unknown = new LinkedHashMap<Integer, Integer>(placement);
        unknown.put(Integer.valueOf(99), Integer.valueOf(0));
        IllegalArgumentException unknownEx = assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(
                        NetworkTopologySpec.fatTree(4, 100.0, null, unknown), hosts(2)));
        assertTrue(unknownEx.getMessage().contains("unknown host 99"), unknownEx.getMessage());
        // k=2 每 edge 容量 1：两台主机同 edge → 拒绝。
        Map<Integer, Integer> overCapacity = new LinkedHashMap<Integer, Integer>();
        overCapacity.put(Integer.valueOf(0), Integer.valueOf(0));
        overCapacity.put(Integer.valueOf(1), Integer.valueOf(0));
        IllegalArgumentException over = assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(
                        NetworkTopologySpec.fatTree(2, 100.0, null, overCapacity), hosts(2)));
        assertTrue(over.getMessage().contains("at most k/2"), over.getMessage());
    }

    /** 引擎级组合：重叠路径公平共享半速，不相交路径零干扰（无阻塞性质）。 */
    @Test
    public void sharedAndDisjointRoutesFairSharing() {
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(8));
        TransferContentionEngine engine = new TransferContentionEngine();
        topology.registerCapacities(engine);
        double linkBw = 100.0 * 1_000_000.0 / 8.0;
        // 流 1（host0→host2，跨 Pod）与流 2（host5→host7，跨 Pod）路由不相交：
        // 各得全额链路速率。
        engine.addTransfer(1L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        engine.addTransfer(2L, 1000L, topology.route(5, 7), 1.0e12, 0.0);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);
        // 流 3 与流 1 完全同路：共享链路上两流各得半速；流 2 不受影响。
        engine.addTransfer(3L, 1000L, topology.route(0, 2), 1.0e12, 0.0);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(1L), 1.0e-6);
        assertEquals(linkBw / 2.0, engine.currentRateBytesPerSecond(3L), 1.0e-6);
        assertEquals(linkBw, engine.currentRateBytesPerSecond(2L), 1.0e-6);
    }

    /** 构造与声明校验：k 奇偶、带宽、core 范围、主机数上限、重复/空主机、未放置路由。 */
    @Test
    public void specAndConstructionValidation() {
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(3, 100.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(0, 100.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, 0.0));
        assertThrows(IllegalArgumentException.class, () -> NetworkTopologySpec.fatTree(4, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkTopologySpec.fatTree(4, 100.0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkTopologySpec.fatTree(4, 100.0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkTopologySpec.fatTree(2, 100.0, null,
                        Collections.singletonMap(Integer.valueOf(0), Integer.valueOf(2))));
        // 主机数超过 k³/4 → 拒绝。
        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(2, 100.0), hosts(3)));
        assertTrue(tooMany.getMessage().contains("k^3/4"), tooMany.getMessage());
        // 重复主机 ID → 拒绝；空主机列表 → 拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0),
                        Arrays.asList(0, 0, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, 100.0),
                        Collections.<Integer>emptyList()));
        // 未放置主机的路由 → 拒绝。
        FatTreeTopology topology = FatTreeTopology.fromSpec(
                NetworkTopologySpec.fatTree(4, 100.0), hosts(4));
        IllegalArgumentException unplaced = assertThrows(IllegalArgumentException.class,
                () -> topology.route(0, 77));
        assertTrue(unplaced.getMessage().contains("not placed"), unplaced.getMessage());
        // 非 FAT_TREE 声明 / null → 拒绝。
        assertThrows(IllegalArgumentException.class,
                () -> FatTreeTopology.fromSpec(null, hosts(2)));
    }
}
