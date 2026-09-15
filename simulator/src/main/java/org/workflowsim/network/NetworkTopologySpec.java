package org.workflowsim.network;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 平台网络拓扑的不可变声明。
 *
 * <p>v1 只支持 Al-Fares k-Pod Fat-tree（见 {@link FatTreeTopology} 与
 * {@code docs/research/FAT_TREE_PRINCIPLES.md}）。拓扑参数属于平台描述
 * （{@code PlatformProfile}），数据移动模型只声明"使用拓扑争用"这一语义。</p>
 *
 * <p>诚实边界：流级流体模型的结构声明——不包含丢包、排队时延细节、ECN、
 * 自适应路由或链路故障语义。</p>
 */
public final class NetworkTopologySpec {

    /** 拓扑族。 */
    public enum Kind {
        /** Al-Fares SIGCOMM 2008 k-Pod Fat-tree。 */
        FAT_TREE
    }

    private final Kind kind;
    private final int k;
    private final double linkBandwidthMbPerSecond;
    private final Integer coreSwitchCount;
    private final Map<Integer, Integer> hostEdgePlacements;

    private NetworkTopologySpec(Kind kind, int k, double linkBandwidthMbPerSecond,
            Integer coreSwitchCount, Map<Integer, Integer> hostEdgePlacements) {
        this.kind = kind;
        this.k = k;
        this.linkBandwidthMbPerSecond = linkBandwidthMbPerSecond;
        this.coreSwitchCount = coreSwitchCount;
        this.hostEdgePlacements = hostEdgePlacements;
    }

    /**
     * 声明满配（无过订）k-Pod Fat-tree，主机按 hostId 升序轮转铺设到全部
     * edge 交换机（确定性默认放置）。
     *
     * @param k k-Pod 参数，必须为 ≥ 2 的偶数
     * @param linkBandwidthMbPerSecond 统一链路带宽，MB/s，必须为正有限值
     * @return 不可变拓扑声明
     */
    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond) {
        return fatTree(k, linkBandwidthMbPerSecond, null, null);
    }

    /**
     * 声明可超收敛的 k-Pod Fat-tree。
     *
     * @param k k-Pod 参数，必须为 ≥ 2 的偶数
     * @param linkBandwidthMbPerSecond 统一链路带宽，MB/s，必须为正有限值
     * @param coreSwitchCount core 交换机数量，1 ≤ m ≤ k²/4；m &lt; k²/4 即超收敛
     * @return 不可变拓扑声明
     */
    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond,
            int coreSwitchCount) {
        return fatTree(k, linkBandwidthMbPerSecond, Integer.valueOf(coreSwitchCount), null);
    }

    /**
     * 声明带显式主机放置的 k-Pod Fat-tree。
     *
     * @param k k-Pod 参数，必须为 ≥ 2 的偶数
     * @param linkBandwidthMbPerSecond 统一链路带宽，MB/s，必须为正有限值
     * @param coreSwitchCount core 交换机数量，null 表示满配 k²/4
     * @param hostEdgePlacements hostId → edge 交换机全局索引
     *        （idx = pod·(k/2) + edge ∈ [0, k²/2)）；null 表示默认轮转放置；
     *        显式提供时必须覆盖全部主机且每台 edge 至多 k/2 台主机
     * @return 不可变拓扑声明
     */
    public static NetworkTopologySpec fatTree(int k, double linkBandwidthMbPerSecond,
            Integer coreSwitchCount, Map<Integer, Integer> hostEdgePlacements) {
        if (k < 2 || k % 2 != 0) {
            throw new IllegalArgumentException("Fat-tree k must be an even integer >= 2: " + k);
        }
        if (!(linkBandwidthMbPerSecond > 0.0) || Double.isInfinite(linkBandwidthMbPerSecond)
                || Double.isNaN(linkBandwidthMbPerSecond)) {
            throw new IllegalArgumentException("Fat-tree link bandwidth must be positive and finite: "
                    + linkBandwidthMbPerSecond);
        }
        int maxCore = k * k / 4;
        if (coreSwitchCount != null) {
            if (coreSwitchCount.intValue() < 1 || coreSwitchCount.intValue() > maxCore) {
                throw new IllegalArgumentException("Fat-tree core switch count must be in [1, k*k/4="
                        + maxCore + "]: " + coreSwitchCount);
            }
        }
        Map<Integer, Integer> placements = null;
        if (hostEdgePlacements != null) {
            int edgeSwitchCount = k * k / 2;
            placements = new LinkedHashMap<Integer, Integer>();
            for (Map.Entry<Integer, Integer> entry : hostEdgePlacements.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IllegalArgumentException("Fat-tree host placement entries cannot contain null");
                }
                if (entry.getValue().intValue() < 0 || entry.getValue().intValue() >= edgeSwitchCount) {
                    throw new IllegalArgumentException("Fat-tree edge switch index must be in [0, k*k/2="
                            + edgeSwitchCount + "): " + entry.getValue());
                }
                placements.put(entry.getKey(), entry.getValue());
            }
            placements = Collections.unmodifiableMap(placements);
        }
        return new NetworkTopologySpec(Kind.FAT_TREE, k, linkBandwidthMbPerSecond,
                coreSwitchCount, placements);
    }

    public Kind getKind() {
        return kind;
    }

    /** k-Pod 参数。 */
    public int getK() {
        return k;
    }

    /** 统一链路带宽，MB/s。 */
    public double getLinkBandwidthMbPerSecond() {
        return linkBandwidthMbPerSecond;
    }

    /** core 交换机数量；null 表示满配 k²/4。 */
    public Integer getCoreSwitchCount() {
        return coreSwitchCount;
    }

    /** hostId → edge 交换机全局索引；null 表示默认轮转放置。不可变。 */
    public Map<Integer, Integer> getHostEdgePlacements() {
        return hostEdgePlacements;
    }
}
