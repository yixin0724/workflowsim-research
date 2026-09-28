package org.workflowsim.planning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/** Fixture construction only: expected decisions and times live in the tests, not here. */
final class PlanningRegressionFixtures {

    static final int[] VM_IDS = {3, 8};
    static final double EPSILON = 1.0e-9;

    private PlanningRegressionFixtures() {
    }

    static List<CondorVM> vms(double firstMips, int firstPes, double secondMips, int secondPes) {
        return Arrays.asList(vm(3, firstMips, firstPes), vm(8, secondMips, secondPes));
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1L, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    static PlatformProfile platform(List<CondorVM> vms) {
        PlatformProfile.Builder builder = PlatformProfile.builder("planning-semantics-regression");
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            builder.addHost(new PlatformProfile.HostSpec(index, vm.getNumberOfPes(), vm.getMips(),
                    2048, 10000L, 1000000L));
            builder.addVm(new PlatformProfile.VmSpec(vm.getId(), vm.getMips(), vm.getNumberOfPes(),
                    512, vm.getBw(), 10000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(vm.getId(), index);
        }
        return builder.build();
    }

    static Task projectedTask(int id, long rawLength, double firstSeconds, double secondSeconds) {
        Map<Integer, Double> costs = new LinkedHashMap<Integer, Double>();
        costs.put(Integer.valueOf(3), Double.valueOf(firstSeconds));
        costs.put(Integer.valueOf(8), Double.valueOf(secondSeconds));
        Task task = new Task(id, rawLength);
        task.setVmExecutionCostSeconds(costs);
        return task;
    }

    static List<Task> projectedTasks(double[][] costs) {
        List<Task> tasks = new ArrayList<Task>();
        for (int index = 0; index < costs.length; index++) {
            tasks.add(projectedTask(index + 1, 100L, costs[index][0], costs[index][1]));
        }
        return tasks;
    }

    static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    static FileItem file(String name, double bytes, Parameters.FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }

    static SimulationConfig config(String workflow, Parameters.PlanningAlgorithm algorithm,
            double[][] costs, boolean local) {
        SimulationConfig.Builder builder = SimulationConfig.builder(workflow, VM_IDS.length)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .runtimeReferenceMips(1.0)
                .randomSeed(7L);
        if (costs != null) {
            TaskCostMatrix.Builder matrix = TaskCostMatrix.builder();
            for (int task = 0; task < costs.length; task++) {
                for (int vm = 0; vm < VM_IDS.length; vm++) {
                    matrix.put(task + 1, VM_IDS[vm], costs[task][vm]);
                }
            }
            builder.taskCostMatrix(matrix.build());
        }
        if (local) {
            builder.fileSystem(org.workflowsim.utils.ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
        }
        return builder.build();
    }

    static AbstractLocalCommPlanningAlgorithm localPlanner(Parameters.PlanningAlgorithm algorithm,
            List<Task> tasks, List<CondorVM> vms) {
        PlanningContext context = new PlanningContext(
                config("planning-regression.dax", algorithm, null, true), platform(vms));
        AbstractLocalCommPlanningAlgorithm planner;
        switch (algorithm) {
            case LOCAL_HEFT:
                planner = new LocalHeftPlanningAlgorithm(context);
                break;
            case LOCAL_CPOP:
                planner = new LocalCpopPlanningAlgorithm(context);
                break;
            case LOCAL_PEFT:
                planner = new LocalPeftPlanningAlgorithm(context);
                break;
            default:
                throw new IllegalArgumentException("Not a LOCAL planner: " + algorithm);
        }
        planner.setTaskList(tasks);
        planner.setVmList(vms);
        return planner;
    }

    static Path workflow(Path directory, String name, String jobsAndEdges) throws IOException {
        String dax = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag xmlns=\"http://pegasus.isi.edu/schema/DAX\" version=\"3.3\">\n"
                + jobsAndEdges + "\n</adag>\n";
        Path path = directory.resolve(name);
        Files.write(path, dax.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
        return path;
    }

    static SimulationReport.JobOutcome computeJob(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job;
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }
}
