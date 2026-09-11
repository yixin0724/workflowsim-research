package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Job;
import org.workflowsim.utils.Parameters.ClassType;

/**
 * 一次运行私有的按 VM 分组的静态 Job 顺序。
 *
 * <p>完整静态 DAG 规划器会写入每台 VM 的 Job 序列；仅产生 Task-to-VM 映射的规划器则
 * 有意返回空计划。该对象只约束静态分派顺序，不把模型解释为现实机器队列。</p>
 */
public final class StaticSchedulePlan {

    private static final StaticSchedulePlan EMPTY = new StaticSchedulePlan(
            Collections.<Integer, List<Integer>>emptyMap());

    private final Map<Integer, List<Integer>> jobIdsByVm;
    private final Map<Integer, Integer> nextIndexByVm;

    private StaticSchedulePlan(Map<Integer, List<Integer>> source) {
        Map<Integer, List<Integer>> frozen = new LinkedHashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Integer>> entry : source.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<Integer>(entry.getValue())));
        }
        this.jobIdsByVm = Collections.unmodifiableMap(frozen);
        this.nextIndexByVm = new LinkedHashMap<Integer, Integer>();
        for (Integer vmId : frozen.keySet()) {
            this.nextIndexByVm.put(vmId, 0);
        }
    }

    /** 为仅映射或历史兼容规划器返回空计划。 */
    public static StaticSchedulePlan empty() {
        return EMPTY;
    }

    /**
     * 在无聚类将规划 Task 转换为 Job 后构造计划。
     *
     * <p>如果没有任一计算 Job 带有计划开始时间，则该运行仍只是映射，不强制 Job 顺序。
     * 同一 VM 的相同开始时间按 Job ID 升序打破平局。</p>
     */
    public static StaticSchedulePlan fromJobs(List<Job> jobs) {
        Map<Integer, List<Job>> grouped = new LinkedHashMap<Integer, List<Job>>();
        boolean anyPlanned = false;
        for (Job job : jobs) {
            if (job.getClassType() != ClassType.COMPUTE.value) {
                continue;
            }
            double plannedStart = job.getStaticScheduleStartTime();
            if (Double.isNaN(plannedStart)) {
                continue;
            }
            anyPlanned = true;
            List<Job> items = grouped.get(job.getVmId());
            if (items == null) {
                items = new ArrayList<Job>();
                grouped.put(job.getVmId(), items);
            }
            items.add(job);
        }
        if (!anyPlanned) {
            return empty();
        }
        for (Job job : jobs) {
            if (job.getClassType() == ClassType.COMPUTE.value
                    && Double.isNaN(job.getStaticScheduleStartTime())) {
                throw new IllegalStateException("Static schedule is incomplete for compute job "
                        + job.getCloudletId());
            }
        }

        Map<Integer, List<Integer>> ordered = new LinkedHashMap<Integer, List<Integer>>();
        for (Map.Entry<Integer, List<Job>> entry : grouped.entrySet()) {
            List<Job> items = entry.getValue();
            Collections.sort(items, new Comparator<Job>() {
                @Override
                public int compare(Job first, Job second) {
                    int byStart = Double.compare(first.getStaticScheduleStartTime(),
                            second.getStaticScheduleStartTime());
                    return byStart != 0 ? byStart
                            : Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
            List<Integer> ids = new ArrayList<Integer>();
            for (Job job : items) {
                ids.add(job.getCloudletId());
            }
            ordered.put(entry.getKey(), ids);
        }
        return new StaticSchedulePlan(ordered);
    }

    public boolean isEnforcingOrder() {
        return !jobIdsByVm.isEmpty();
    }

    public boolean isPlanned(Job job) {
        List<Integer> ids = jobIdsByVm.get(job.getVmId());
        return ids != null && ids.contains(job.getCloudletId());
    }

    /** 返回该静态计划下当前唯一可由指定 VM 分派的计算 Job。 */
    public Integer nextExpectedJobId(int vmId) {
        List<Integer> ids = jobIdsByVm.get(vmId);
        if (ids == null) {
            return null;
        }
        int index = nextIndexByVm.get(vmId);
        return index < ids.size() ? ids.get(index) : null;
    }

    /** 仅在预期 Job 已实际分派后推进对应 VM 的计划游标。 */
    public void markDispatched(Job job) {
        Integer expected = nextExpectedJobId(job.getVmId());
        if (expected == null || expected.intValue() != job.getCloudletId()) {
            throw new IllegalStateException("Static plan dispatch order violation for job "
                    + job.getCloudletId() + " on VM " + job.getVmId());
        }
        nextIndexByVm.put(job.getVmId(), nextIndexByVm.get(job.getVmId()) + 1);
    }
}
