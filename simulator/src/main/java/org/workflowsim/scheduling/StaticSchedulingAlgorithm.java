/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim.scheduling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.Parameters.ClassType;

/**
 * 静态映射结果的运行时分派器。
 *
 * <p>它不重新选择 Task-to-VM 映射，而是依赖规划器预先设置 VM ID，并检查计算 Job 是否
 * 已映射。只有当 {@link StaticSchedulePlan} 非空时才按规划器产生的每 VM 顺序放行计算 Job；
 * 仅映射规划器和历史规划器保留空计划，仍按引擎的普通 ready/idle 生命周期分派。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public class StaticSchedulingAlgorithm extends BaseSchedulingAlgorithm {

    private StaticSchedulePlan staticSchedulePlan = StaticSchedulePlan.empty();

    public StaticSchedulingAlgorithm() {
        super();
    }

    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        this.staticSchedulePlan = value;
    }

    @Override
    public void run() throws Exception {

        Map<Integer, CondorVM> mId2Vm = new HashMap<>();

        for (int i = 0; i < getVmList().size(); i++) {
            CondorVM vm = (CondorVM) getVmList().get(i);
            if (vm != null) {
                mId2Vm.put(vm.getId(), vm);
            }
        }

        List<Integer> vmIds = new ArrayList<Integer>(mId2Vm.keySet());
        Collections.sort(vmIds);
        if (vmIds.isEmpty()) {
            throw new IllegalStateException("Static scheduling requires at least one created VM");
        }
        int fallbackVmId = vmIds.get(0);

        // PLAT-15：记录被 fallback 改写 VM 映射的 Job。当这类 Job 后续在强制顺序计划
        // 校验中失败时，错误信息能指出真实根因（缺少规划器映射），而不是只报表象。
        List<Integer> fallbackRewrittenJobIds = new ArrayList<Integer>();
        for (Object item : getCloudletList()) {
            Cloudlet cloudlet = (Cloudlet) item;
            if (cloudlet.getVmId() < 0 || !mId2Vm.containsKey(cloudlet.getVmId())) {
                Log.printLine("Cloudlet " + cloudlet.getCloudletId() + " is not matched."
                        + " It is possible a model-generated stage-in job.");
                cloudlet.setVmId(fallbackVmId);
                fallbackRewrittenJobIds.add(cloudlet.getCloudletId());
            }
        }

        if (!staticSchedulePlan.isEnforcingOrder()) {
            for (Object item : getCloudletList()) {
                scheduleIfIdle((Cloudlet) item, mId2Vm);
            }
            return;
        }

        // 模型生成的非计算 Job（当前为 stage-in）不属于 Task 规划器的计划，必须先行分派。
        for (Object item : getCloudletList()) {
            Job job = (Job) item;
            if (job.getClassType() != ClassType.COMPUTE.value) {
                scheduleIfIdle(job, mId2Vm);
            } else if (!staticSchedulePlan.isPlanned(job)) {
                // PLAT-15：区分两种根因——规划器遗漏映射（Job 先被 fallback 改写）
                // 与计划本身缺条目，避免误导性错误信息。
                String rootCauseHint = fallbackRewrittenJobIds.contains(job.getCloudletId())
                        ? " Root cause: the job arrived without a planner VM mapping and was"
                            + " rewritten to fallback VM " + fallbackVmId + "; the planner must"
                            + " map every compute task (or the failure/retry path must preserve"
                            + " the original mapping)."
                        : "";
                throw new IllegalStateException("Static plan has no entry for compute job "
                        + job.getCloudletId() + "." + rootCauseHint);
            }
        }

        for (Integer vmId : vmIds) {
            CondorVM vm = mId2Vm.get(vmId);
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE) {
                continue;
            }
            Integer expectedJobId = staticSchedulePlan.nextExpectedJobId(vmId);
            if (expectedJobId == null) {
                continue;
            }
            Job expected = findReadyJob(expectedJobId.intValue());
            if (expected != null) {
                scheduleIfIdle(expected, mId2Vm);
                staticSchedulePlan.markDispatched(expected);
            }
        }
    }

    private Job findReadyJob(int jobId) {
        for (Object item : getCloudletList()) {
            Job job = (Job) item;
            if (job.getCloudletId() == jobId) {
                return job;
            }
        }
        return null;
    }

    private void scheduleIfIdle(Cloudlet cloudlet, Map<Integer, CondorVM> vms) {
        CondorVM vm = vms.get(cloudlet.getVmId());
        if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {
            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            getScheduledList().add(cloudlet);
            Log.printLine("Schedules " + cloudlet.getCloudletId() + " with "
                    + cloudlet.getCloudletLength() + " to VM " + cloudlet.getVmId());
        }
    }
}
