package org.workflowsim.experiments.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.experiment.registry.AlgorithmDeclaration;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;

/**
 * E5 demonstrator (2026-10-27): the platform's first <b>energy-aware</b> registered
 * scheduling algorithm, exercised through the standard registry track.
 *
 * <p>Heuristic (energy-motivated, literature-anchored LPT family): among the ready jobs,
 * dispatch the <b>longest first</b>; among idle compatible VMs, the <b>fastest</b> (ties by
 * smallest VM id). Rationale: with the linear power model and uniform VMs, total busy work
 * is fixed, so energy follows makespan — E = Σ P_idle×T + (P_max−P_idle)×busy is strictly
 * monotone in T. Longest-Processing-Time-first placement is the classical makespan
 * reduction on bag workload (Graham's 4/3−ε bound vs arbitrary order), so the EDP
 * (energy-delay product) cannot increase against FCFS-style order on a pure bag.</p>
 *
 * <p>The declaration honestly declares the energy contract: energyAware=true,
 * powerModelRequired=true — the registry rejects any unpowered platform for this label,
 * because without power fields the entire point of the heuristic is unverifiable.</p>
 */
public final class EnergyLptFirstSchedulerProvider implements SchedulingAlgorithmProvider {

    static final String LABEL = "ENERGY_LPT_FIRST";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new EnergyLptFirstSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .powerModelRequired(true)
                .energyObjectiveWeight(1.0)
                .description("E5 demonstrator: longest-job-first placement minimizing makespan"
                        + " (hence EDP, by power-model monotonicity); requires a powered profile")
                .build();
    }

    /** The scheduling body; mirrors the grad-student exercise structure (BAT-3 pattern). */
    static final class EnergyLptFirstSchedulingAlgorithm extends BaseSchedulingAlgorithm {

        @Override
        public void run() {
            validateCloudletCompatibility();
            List<CondorVM> vms = sortedVmsById();
            List<Cloudlet> ready = new ArrayList<Cloudlet>();
            for (java.util.Iterator it = getCloudletList().iterator(); it.hasNext(); ) {
                ready.add((Cloudlet) it.next());
            }
            // LPT: longest ready job first (deterministic tie-break: smaller cloudlet id).
            Collections.sort(ready, new Comparator<Cloudlet>() {
                @Override
                public int compare(Cloudlet a, Cloudlet b) {
                    long delta = b.getCloudletLength() - a.getCloudletLength();
                    if (delta != 0) {
                        return delta > 0 ? 1 : -1;
                    }
                    return Integer.compare(a.getCloudletId(), b.getCloudletId());
                }
            });
            for (Cloudlet cloudlet : ready) {
                CondorVM best = null;
                for (CondorVM vm : vms) {
                    if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                        if (best == null || vm.getMips() > best.getMips()) {
                            best = vm;
                        }
                    }
                }
                if (best != null) {
                    best.setState(WorkflowSimTags.VM_STATUS_BUSY);
                    cloudlet.setVmId(best.getId());
                    getScheduledList().add(cloudlet);
                }
            }
        }
    }
}
