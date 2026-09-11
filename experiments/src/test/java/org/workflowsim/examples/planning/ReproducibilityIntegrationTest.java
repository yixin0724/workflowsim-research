package org.workflowsim.examples.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.examples.WorkflowSimBasicExample1;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 用同一配置完整运行三次解析、事件循环和结果收集链路，验证运行快照完全一致。
 */
class ReproducibilityIntegrationTest extends WorkflowSimBasicExample1 {

    @Test
    void identicalConfigurationProducesIdenticalFullRunSnapshots() throws Exception {
        RunSnapshot first = runScenario();
        assertEquals(first, runScenario());
        assertEquals(first, runScenario());
    }

    private RunSnapshot runScenario() throws Exception {
        URL daxResource = getClass().getResource("/dax/reproducibility-workflow.dax");
        if (daxResource == null) {
            throw new IllegalStateException("Missing reproducibility DAX fixture");
        }

        String daxPath = Paths.get(daxResource.toURI()).toString();
        Parameters.setRandomSeed(20260901L);
        Parameters.init(3, daxPath, null, null,
                new OverheadParameters(0, null, null, null, null, 0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                Parameters.PlanningAlgorithm.INVALID, null, 0);
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.SHARED);

        Log.disable();
        try {
            CloudSim.init(1, Calendar.getInstance(), false);
            WorkflowDatacenter datacenter = createDatacenter("reproducibility-datacenter");
            WorkflowPlanner planner = new WorkflowPlanner("reproducibility-planner", 1);
            WorkflowEngine engine = planner.getWorkflowEngine();
            List<CondorVM> vms = createVM(engine.getSchedulerId(0), Parameters.getVmNum());
            engine.submitVmList(vms, 0);
            engine.bindSchedulerDatacenter(datacenter.getId(), 0);

            double makespan = CloudSim.startSimulation();
            return RunSnapshot.capture(makespan, vms, engine.getJobsReceivedList());
        } finally {
            CloudSim.stopSimulation();
            Log.enable();
        }
    }

    private static final class RunSnapshot {

        private final String makespan;
        private final List<String> resources;
        private final List<String> completionOrder;

        private RunSnapshot(String makespan, List<String> resources, List<String> completionOrder) {
            this.makespan = makespan;
            this.resources = resources;
            this.completionOrder = completionOrder;
        }

        private static RunSnapshot capture(double makespan, List<CondorVM> vms, List<Job> jobs) {
            List<String> resources = new ArrayList<>();
            for (CondorVM vm : vms) {
                resources.add(vm.getId() + "|" + Double.toString(vm.getMips()) + "|" + vm.getBw());
            }

            List<String> jobsInCompletionOrder = new ArrayList<>();
            for (Job job : jobs) {
                StringBuilder taskIds = new StringBuilder();
                for (Task task : job.getTaskList()) {
                    if (taskIds.length() > 0) {
                        taskIds.append(',');
                    }
                    taskIds.append(task.getCloudletId());
                }
                jobsInCompletionOrder.add(job.getCloudletId() + "|" + taskIds + "|"
                        + job.getVmId() + "|" + job.getCloudletStatus() + "|"
                        + Double.toString(job.getExecStartTime()) + "|"
                        + Double.toString(job.getFinishTime()));
            }
            return new RunSnapshot(Double.toString(makespan), resources, jobsInCompletionOrder);
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof RunSnapshot)) {
                return false;
            }
            RunSnapshot that = (RunSnapshot) object;
            return Objects.equals(makespan, that.makespan)
                    && Objects.equals(resources, that.resources)
                    && Objects.equals(completionOrder, that.completionOrder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(makespan, resources, completionOrder);
        }

        @Override
        public String toString() {
            return "RunSnapshot{" + "makespan=" + makespan
                    + ", resources=" + resources
                    + ", completionOrder=" + completionOrder + '}';
        }
    }
}
