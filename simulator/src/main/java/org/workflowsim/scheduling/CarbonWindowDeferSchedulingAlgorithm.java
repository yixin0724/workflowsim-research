package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

/**
 * C1（2026-11-02）：绿色预算窗口内的碳感知延后调度（CWM 时间平移的在线版本）。
 *
 * <p>这不是 C5-lite 那个"LPT + 最小 VM id"的演示器，而是真正会把作业<b>压后到更
 * 干净的时间窗</b>再执行的引擎。规则只有一条，刻意保持可审计：</p>
 *
 * <ol>
 *   <li>取当前时刻所在的窗口 w（{@code carbonWindows} 覆盖 [0,T_sim)，声明期已校验
 *       排序与不重叠），记其绿色功率 g_w。</li>
 *   <li>向前找<b>第一个绿色功率严格更高</b>的窗口 w'；若无，按普通在线策略派发。</li>
 *   <li>若就绪集合非空且其中每个作业都<b>尚未</b>被压后过，则本轮一个都不派发，
 *       把唤醒时刻设为 w' 的起点——清账窗口开始后统一派发。</li>
 *   <li>每个作业最多被压后一次：这保证不会无休止推迟（饥饿上界），也让"压后收益"
 *       可以逐作业归因。</li>
 * </ol>
 *
 * <p><b>诚实边界</b>：本引擎只看平台声明的绿色功率，不看实时舰队负载（那属于研究
 * 协议的窗口声明域）；它牺牲成批完成时间换更低超额，是否值得由实验判据决定，引擎
 * 自身不宣称更优。窗口与派发规则都来自运行配置，因此同一输入逐位可复现。</p>
 *
 * <p>契约：延后的作业仍留在调度器就绪队列里，由
 * {@link DeferralAwareSchedulingAlgorithm#getNextWakeSeconds()} 给出的唤醒事件重新
 * 触发调度周期；{@link org.workflowsim.WorkflowScheduler} 负责排该事件，并对
 * "既压后又没有唤醒时刻"的死锁形态显式失败。</p>
 */
public final class CarbonWindowDeferSchedulingAlgorithm extends BaseSchedulingAlgorithm
        implements DeferralAwareSchedulingAlgorithm {

    /** 已被压后过的作业 id：每个作业至多压后一次（饥饿上界）。 */
    private final Set<Integer> deferredOnce = new HashSet<Integer>();

    private double nextWakeSeconds = Double.POSITIVE_INFINITY;
    private int deferredJobCount;

    @Override
    public void run() {
        validateCloudletCompatibility();
        nextWakeSeconds = Double.POSITIVE_INFINITY;
        deferredJobCount = 0;

        List<Cloudlet> ready = new ArrayList<Cloudlet>();
        for (Object item : getCloudletList()) {
            ready.add((Cloudlet) item);
        }
        Collections.sort(ready, new Comparator<Cloudlet>() {
            @Override
            public int compare(Cloudlet left, Cloudlet right) {
                return Integer.compare(left.getCloudletId(), right.getCloudletId());
            }
        });

        double now = CloudSim.clock();
        List<PlatformProfile.CarbonWindow> windows = Parameters.getCarbonWindows();
        if (!ready.isEmpty() && windows != null && !windows.isEmpty()) {
            PlatformProfile.CarbonWindow clean = nextCleanerWindow(windows, now);
            if (clean != null && canDefer(ready)) {
                nextWakeSeconds = clean.getStartSeconds();
                deferredJobCount = ready.size();
                for (Cloudlet cloudlet : ready) {
                    deferredOnce.add(Integer.valueOf(cloudlet.getCloudletId()));
                }
                return;
            }
        }
        dispatch(ready);
    }

    /** 普通在线派发：FCFS 到最小的空闲且兼容 VM（与 FCFS 同序，便于对照）。 */
    private void dispatch(List<Cloudlet> ready) {
        List<CondorVM> vms = sortedVmsById();
        for (Cloudlet cloudlet : ready) {
            CondorVM target = null;
            for (CondorVM vm : vms) {
                if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                    target = vm;
                    break;
                }
            }
            if (target == null) {
                continue;
            }
            target.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(target.getId());
            getScheduledList().add(cloudlet);
        }
    }

    /** 只有"全部就绪作业都还没压后过"时才整体压后——避免部分压后造成的乱序与饥饿。 */
    private boolean canDefer(List<Cloudlet> ready) {
        for (Cloudlet cloudlet : ready) {
            if (deferredOnce.contains(Integer.valueOf(cloudlet.getCloudletId()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 找当前时刻之后第一个绿色功率更高的窗口。
     *
     * @return 该窗口；当前已在高点、或其后没有更干净的窗口、或候选窗口起点不晚于
     *     当前时刻（无法推进时钟）时返回 {@code null}
     */
    private static PlatformProfile.CarbonWindow nextCleanerWindow(
            List<PlatformProfile.CarbonWindow> windows, double now) {
        double currentGreen = greenWattsAt(windows, now);
        PlatformProfile.CarbonWindow best = null;
        for (PlatformProfile.CarbonWindow window : windows) {
            if (window.getStartSeconds() <= now) {
                continue;
            }
            if (window.getGreenWatts() > currentGreen) {
                if (best == null || window.getStartSeconds() < best.getStartSeconds()) {
                    best = window;
                }
            }
        }
        return best;
    }

    /** 覆盖 {@code now} 的窗口绿色功率；窗口未覆盖（历史/边界形态）时视为无穷大（不触发压后）。 */
    private static double greenWattsAt(List<PlatformProfile.CarbonWindow> windows, double now) {
        for (PlatformProfile.CarbonWindow window : windows) {
            if (now >= window.getStartSeconds() && now < window.getEndSeconds()) {
                return window.getGreenWatts();
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public double getNextWakeSeconds() {
        return nextWakeSeconds;
    }

    @Override
    public int getDeferredJobCount() {
        return deferredJobCount;
    }

    /** 供证据侧读取的"压后过的作业数"（跨周期累计，只用于观察）。 */
    public int getTotalDeferredJobs() {
        return deferredOnce.size();
    }
}
