package org.workflowsim.utils;

import org.cloudbus.cloudsim.Consts;
import org.workflowsim.Task;

/** Shared conversion from declared task costs to the work actually executable by CloudSim. */
public final class TaskExecutionModel {
    /** Execution contract written into new evidence; older manifests remain historical records. */
    public static final String EXECUTION_SEMANTICS = "WORK_CONSERVING_TASK_EXECUTION_V2";

    /** CloudSim stores total work in instructions in a signed long, not merely in MI. */
    public static final long MAX_TOTAL_LENGTH_MI = Long.MAX_VALUE / Consts.MILLION;

    private TaskExecutionModel() { }

    /**
     * Resolve per-PE compute MI without mutating the task's declared input length.
     * A present matrix is authoritative: missing coordinates never fall back to raw MI.
     *
     * @param task logical task, including any immutable task/VM cost projection
     * @param vmId actual candidate VM identifier
     * @param mips candidate VM's per-PE MIPS
     * @return positive, representable per-PE compute MI after matrix rounding
     */
    public static long executionLengthMi(Task task, int vmId, double mips) {
        if (task == null || !Double.isFinite(mips) || mips <= 0.0) {
            throw new IllegalArgumentException("Task and positive finite VM MIPS are required");
        }
        if (!task.hasVmExecutionCostSeconds()) {
            return requireRepresentableLength(task.getCloudletLength(), task.getNumberOfPes());
        }
        Double seconds = task.getVmExecutionCostSeconds(vmId);
        if (seconds == null) {
            throw new IllegalArgumentException("Task " + task.getCloudletId()
                    + " cost matrix has no entry for VM " + vmId);
        }
        double converted = seconds.doubleValue() * mips;
        if (!Double.isFinite(converted) || converted >= (double) Long.MAX_VALUE) {
            throw new IllegalArgumentException("Task " + task.getCloudletId()
                    + " cost matrix overflows compute MI on VM " + vmId);
        }
        return requireRepresentableLength(Math.round(converted), task.getNumberOfPes());
    }

    /**
     * Compute the execution seconds represented by the same integer MI used at runtime.
     * Candidate PE compatibility must be checked by the caller before scoring a placement.
     *
     * @param task logical task
     * @param vmId candidate VM identifier
     * @param mips per-PE MIPS
     * @return finite positive compute seconds, excluding stage-in
     */
    public static double executionSeconds(Task task, int vmId, double mips) {
        double seconds = executionLengthMi(task, vmId, mips) / mips;
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            throw new IllegalArgumentException("Task execution seconds must be finite and positive");
        }
        return seconds;
    }

    /**
     * Reject work that would overflow CloudSim's MI-to-instruction or PE conversion.
     *
     * @param perPeMi work per PE in MI
     * @param pes number of PEs required by the cloudlet
     * @return the unchanged validated per-PE MI
     */
    public static long requireRepresentableLength(long perPeMi, int pes) {
        if (perPeMi <= 0L) {
            throw new IllegalArgumentException("Task work converts to non-positive MI (" + perPeMi + ")");
        }
        if (pes <= 0 || perPeMi > MAX_TOTAL_LENGTH_MI / pes) {
            throw new IllegalArgumentException("Compute work must be positive and representable "
                    + "as signed-long instructions: " + perPeMi + " MI per PE, " + pes + " PEs");
        }
        return perPeMi;
    }
}
