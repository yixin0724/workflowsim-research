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
 * C5-lite demonstrator (2026-10-28): the platform's first <b>carbon-aware</b> registered
 * scheduling label, exercised through the standard registry track.
 *
 * <p><b>This is a demonstrator, NOT an algorithm contribution.</b> It exists to prove the
 * registry acceptance surface and the evidence chain for carbon-aware declarations: the label
 * validates fail-fast against a powered platform ({@code powerModelRequired} — both v1 carbon
 * accountings ride the conserved energy chain, docs/advanced/CARBON_DIMENSION_DESIGN.md §2.2),
 * runs end-to-end through the standard runner, and writes an evidence bundle carrying the
 * conditional {@code carbonMetrics} section. The real CWM-style deferral engine — the
 * per-interval processor-subset knapsack (DP) that shifts non-critical-tail work into cheap
 * green windows of the budget model — is explicitly follow-up work per the design doc's
 * honest-scoping table (§3, "CWM-class DP mapping algorithm" omitted row).</p>
 *
 * <p>What the v1 heuristic honestly does: it keeps the LPT ordering discipline of
 * {@link EnergyLptFirstSchedulerProvider} for ready jobs (longest first, deterministic id
 * tie-break — the makespan-honest backbone) but, in the temporal-shift spirit of the budget
 * model, places each job on the <b>idle compatible VM with the lowest id</b> — a deliberately
 * deterministic placement, not an optimizer: no window lookahead, no carbon numerics in the
 * decision. The declaration ({@code powerModelRequired} + {@code carbonObjectiveWeight 1.0},
 * energyAware implied by both) states the objective face and platform gate; the algorithmic
 * substance is deferred to the follow-up work above.</p>
 */
public final class CarbonDeferFirstSchedulerProvider implements SchedulingAlgorithmProvider {

    static final String LABEL = "CARBON_DEFER_FIRST";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new CarbonDeferFirstSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .powerModelRequired(true)
                .carbonObjectiveWeight(1.0)
                .description("C5-lite demonstrator: budget-window-spirit placement proving the"
                        + " carbon-aware registry acceptance surface and evidence chain; requires a"
                        + " powered profile; a real CWM-style deferral engine is follow-up work")
                .build();
    }

    /**
     * The scheduling body: the same LPT job order as the E5 demonstrator
     * ({@link EnergyLptFirstSchedulerProvider}), with the placement rule swapped to the
     * lowest-id idle compatible VM — the C5-lite demonstrator discipline documented in the
     * class javadoc.
     */
    static final class CarbonDeferFirstSchedulingAlgorithm extends BaseSchedulingAlgorithm {

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
                // v1 demonstrator placement: the lowest-id IDLE compatible VM (deterministic,
                // documented as NOT a deferral engine; see the class javadoc honesty note).
                CondorVM best = null;
                for (CondorVM vm : vms) {
                    if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE && isCompatible(cloudlet, vm)) {
                        best = vm;
                        break;
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
