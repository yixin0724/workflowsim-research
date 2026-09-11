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
package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;

/**
 * 受随机种子控制的 Task-to-compatible-VM 随机映射基线。
 *
 * <p>它仅产生映射：DAG 依赖仍由 {@code WorkflowEngine} 强制执行，且不会生成离线执行顺序。
 * VM 候选先按 ID 稳定排序，再从 PE 兼容候选中按 {@code SimulationRandom} 选择，因此同一
 * 种子和相同输入可复现。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public class RandomPlanningAlgorithm extends BasePlanningAlgorithm {

    /** 在每个 Task 的兼容 VM 集合上执行受种子控制的随机映射。 */
    @Override
    public void run() {
        Random random = SimulationRandom.newJavaRandom("planning.random");
        List<CondorVM> vms = sortedVms();
        for (Task task : getTaskList()) {
            List<CondorVM> compatible = new ArrayList<CondorVM>();
            for (CondorVM vm : vms) {
                if (task.getNumberOfPes() <= vm.getNumberOfPes()) {
                    compatible.add(vm);
                }
            }
            if (compatible.isEmpty()) {
                throw new IllegalArgumentException("RANDOM cannot map task " + task.getCloudletId()
                        + "; no VM has sufficient processing elements");
            }
            task.setVmId(compatible.get(random.nextInt(compatible.size())).getId());
        }
    }

    private List<CondorVM> sortedVms() {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalStateException("RANDOM requires at least one VM");
        }
        List<CondorVM> result = new ArrayList<CondorVM>();
        for (Object item : getVmList()) {
            if (!(item instanceof CondorVM)) {
                throw new IllegalArgumentException("RANDOM requires CondorVM instances");
            }
            result.add((CondorVM) item);
        }
        Collections.sort(result, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return result;
    }

}
