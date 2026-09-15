package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;

/**
 * R4 RL 轨道的决策时刻状态快照（observation）。
 *
 * <p>状态 = 当前仿真时钟下的就绪 Job 队列特征 + VM 负载视图，与路线图 R4 的
 * 环境契约一致。快照在调度决策时刻从运行时状态一次性抽取，构造后不可变；
 * ready Job 保持调度器队列的到达序，VM 按 ID 升序——顺序本身是确定性的，
 * 策略输出与状态输入之间的索引对应关系因此逐位可复现。</p>
 *
 * <p>VM 负载视图刻意保持最小：空间共享模型下每台 VM 同一时刻至多执行一个
 * Job，因此"负载"由忙/闲状态 + MIPS + PE 数完整刻画；不引入未经回归验证的
 * 推测量。Job 视图提供聚合工作长度（与在线调度器的 ECT 语义相同：
 * {@code cloudletLength / mips}）与 PE 需求，供策略估计完成时刻。</p>
 */
public final class RlObservation {

    /** 就绪 Job 的状态视图。 */
    public static final class JobView {
        private final int jobId;
        private final long totalLength;
        private final int pes;

        JobView(int jobId, long totalLength, int pes) {
            this.jobId = jobId;
            this.totalLength = totalLength;
            this.pes = pes;
        }

        /** @return Job ID（CloudSim cloudlet ID） */
        public int getJobId() {
            return jobId;
        }

        /** @return Job 聚合工作长度（MI），ECT 估计使用 {@code totalLength / vmMips} */
        public long getTotalLength() {
            return totalLength;
        }

        /** @return Job 需要的 PE 数 */
        public int getPes() {
            return pes;
        }
    }

    /** VM 的状态视图。 */
    public static final class VmView {
        private final int vmId;
        private final double mips;
        private final int pes;
        private final boolean idle;

        VmView(int vmId, double mips, int pes, boolean idle) {
            this.vmId = vmId;
            this.mips = mips;
            this.pes = pes;
            this.idle = idle;
        }

        /** @return VM ID */
        public int getVmId() {
            return vmId;
        }

        /** @return VM 单 PE 性能（MIPS） */
        public double getMips() {
            return mips;
        }

        /** @return VM 的 PE 数 */
        public int getPes() {
            return pes;
        }

        /** @return VM 当前是否空闲（空间共享模型下忙 VM 不接受新 Job） */
        public boolean isIdle() {
            return idle;
        }
    }

    private final double simulationTime;
    private final List<JobView> readyJobs;
    private final List<VmView> vms;

    private RlObservation(double simulationTime, List<JobView> readyJobs, List<VmView> vms) {
        this.simulationTime = simulationTime;
        this.readyJobs = Collections.unmodifiableList(new ArrayList<JobView>(readyJobs));
        this.vms = Collections.unmodifiableList(new ArrayList<VmView>(vms));
    }

    /**
     * 在决策时刻从运行时状态抽取不可变快照。
     *
     * @param readyJobs 当前就绪 Job 队列（保持到达序）
     * @param vms 当前全部 VM（内部按 ID 升序排列）
     * @return 不可变观测快照
     */
    public static RlObservation of(List<? extends Cloudlet> readyJobs, List<CondorVM> vms) {
        List<JobView> jobViews = new ArrayList<JobView>();
        for (Cloudlet cloudlet : readyJobs) {
            jobViews.add(new JobView(cloudlet.getCloudletId(), cloudlet.getCloudletLength(),
                    cloudlet.getNumberOfPes()));
        }
        List<CondorVM> sorted = new ArrayList<CondorVM>(vms);
        Collections.sort(sorted, (first, second) -> Integer.compare(first.getId(), second.getId()));
        List<VmView> vmViews = new ArrayList<VmView>();
        for (CondorVM vm : sorted) {
            vmViews.add(new VmView(vm.getId(), vm.getMips(), vm.getNumberOfPes(),
                    vm.getState() == WorkflowSimTags.VM_STATUS_IDLE));
        }
        return new RlObservation(CloudSim.clock(), jobViews, vmViews);
    }

    /** @return 决策时刻的仿真时钟（秒） */
    public double getSimulationTime() {
        return simulationTime;
    }

    /** @return 就绪 Job 视图列表（调度器到达序，索引即策略动作下标） */
    public List<JobView> getReadyJobs() {
        return readyJobs;
    }

    /** @return VM 视图列表（VM ID 升序，索引即策略动作取值域） */
    public List<VmView> getVms() {
        return vms;
    }
}
