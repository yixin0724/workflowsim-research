package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.BOOTSTRAP;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.bits;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.chain;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.independentTasks;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.publicPlanner;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.rawSchedule;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.rawSlot;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.runPublic;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.snapshot;
import static org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.vm;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.planning.SharedStorageDagPlanner.Strategy;
import org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.CountingReservations;
import org.workflowsim.planning.SharedStorageDlsEtfSlotAuditSupport.Kernel;

/**
 * Bounded operation counts, never wall-clock thresholds. These are real prepared scheduling-kernel
 * counts; they do not claim to measure public run() preparation, rank work, or reservation sorting.
 */
class SharedStorageDlsEtfSlotVisitAuditTest {
    @Test
    void dlsEqualWideRootsScanAtMostOncePerOccupancyGeneration() {
        assertAll("DLS bounded widths",
                () -> checkWide(Strategy.DLS, 64),
                () -> checkWide(Strategy.DLS, 128),
                () -> checkWide(Strategy.DLS, 256));
    }

    @Test
    void etfEqualWideRootsScanAtMostOncePerOccupancyGeneration() {
        assertAll("ETF bounded widths",
                () -> checkWide(Strategy.ETF, 64),
                () -> checkWide(Strategy.ETF, 128),
                () -> checkWide(Strategy.ETF, 256));
    }

    private static void checkWide(Strategy strategy, int count) throws Exception {
        List<Task> tasks = independentTasks(count, false);
        CondorVM machine = vm(7, 1000.0, 1);
        Kernel kernel = new Kernel(strategy, tasks, Collections.singletonList(machine));
        SharedStorageDagPlanTrace trace = kernel.schedule();
        assertEquals(count, trace.getTaskPlans().size());
        double next = BOOTSTRAP;
        for (int index = 0; index < count; index++) {
            Task task = tasks.get(index);
            SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(task.getCloudletId());
            assertEquals(7, plan.getVmId());
            assertEquals(bits(1.0), bits(plan.getUpwardRankSeconds()));
            assertEquals(bits(next), bits(plan.getPlannedStartSeconds()));
            assertEquals(bits(next), bits(task.getStaticScheduleStartTime()));
            assertEquals(bits(next + 1.0), bits(plan.getPlannedFinishSeconds()));
            assertEquals(bits(0.0), bits(plan.getPlannedDataStageInSeconds()));
            assertSelection(strategy, plan, index + 1, 1.0, next);
            next += 1.0;
        }

        long originalVisits = count * (count - 1L) * (count + 4L) / 6L;
        long originalScans = count * (count + 3L) / 2L;
        long reusedVisitBudget = count * (count - 1L) / 2L;
        String evidence = strategy + " N=" + count + " actual prepared-kernel visits=" + kernel.visits()
                + ", actual scan passes=" + kernel.scans() + "; uncached source formulas="
                + originalVisits + " visits/" + originalScans + " passes; reuse budget="
                + reusedVisitBudget + " visits/" + count + " passes";
        System.out.println("SLOT_VISIT_AUDIT " + evidence);
        assertAll(evidence,
                () -> assertTrue(kernel.visits() <= reusedVisitBudget, evidence),
                () -> assertTrue(kernel.scans() <= count, evidence));
    }

    @Test
    void rawHelperCounterObservesShortCircuitingRatherThanScheduleSize() throws Exception {
        CountingReservations empty = rawSchedule(new double[0][2]);
        assertEquals(bits(3.0), bits(rawSlot(empty, 3.0, 1.0)));
        assertEquals(1L, empty.scans);
        assertEquals(0L, empty.visits);

        double[][] intervals = new double[64][2];
        for (int index = 0; index < intervals.length; index++) {
            intervals[index][0] = 2.0 + index;
            intervals[index][1] = 3.0 + index;
        }
        CountingReservations leading = rawSchedule(intervals);
        assertEquals(bits(0.0), bits(rawSlot(leading, 0.0, 1.0)));
        assertEquals(1L, leading.scans);
        assertEquals(1L, leading.visits, "A leading gap returns after the first real entry visit");
        leading.sort((first, second) -> 0);
        assertEquals(1L, leading.sorts);
        assertEquals(1L, leading.scans, "ArrayList sorting is not a slot scan");
        assertEquals(1L, leading.visits, "Sorting must not contaminate slot-visit accounting");

        CountingReservations tail = rawSchedule(intervals);
        assertEquals(bits(100.0), bits(rawSlot(tail, 100.0, 1.0)));
        assertEquals(1L, tail.scans);
        assertEquals(64L, tail.visits, "The unchanged helper visits even intervals before readiness");
    }

    @Test
    void oneReadyChainHasNoWideCandidateMultiplier() throws Exception {
        final int count = 64;
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            List<Task> tasks = chain(count);
            Kernel kernel = new Kernel(strategy, tasks, Collections.singletonList(vm(7, 1000.0, 1)));
            SharedStorageDagPlanTrace trace = kernel.schedule();
            double next = BOOTSTRAP;
            for (int index = 0; index < count; index++) {
                SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(tasks.get(index).getCloudletId());
                assertEquals(bits(next), bits(plan.getPlannedStartSeconds()));
                assertEquals(bits(next + 1.0), bits(plan.getPlannedFinishSeconds()));
                assertEquals(bits(count - index), bits(plan.getUpwardRankSeconds()));
                assertSelection(strategy, plan, index + 1, count - index, next);
                next += 1.0;
            }
            assertTrue(kernel.visits() > 0L, "Control must actually visit occupied reservations");
            assertTrue(kernel.visits() <= count * (count - 1L), "At most two scans per single ready task");
            assertTrue(kernel.scans() <= 2L * count);
        }
    }

    @Test
    void allDistinctDurationsAreNotMistakenForIdenticalQueryKeys() throws Exception {
        final int count = 16;
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            List<Task> tasks = independentTasks(count, true);
            Kernel kernel = new Kernel(strategy, tasks, Collections.singletonList(vm(7, 1000.0, 1)));
            SharedStorageDagPlanTrace trace = kernel.schedule();
            double next = BOOTSTRAP;
            for (int step = 0; step < count; step++) {
                int index = count - step - 1;
                double duration = index + 1.0;
                SharedStorageDagPlanTrace.TaskPlan plan = trace.getTaskPlan(tasks.get(index).getCloudletId());
                assertEquals(bits(next), bits(plan.getPlannedStartSeconds()));
                assertEquals(bits(next + duration), bits(plan.getPlannedFinishSeconds()));
                assertSelection(strategy, plan, step + 1, duration, next);
                next += duration;
            }
            // Exact query reuse cannot merge distinct duration keys. This deliberately claims no
            // quadratic bound for all-distinct queries; only the duplicate allocation may hit.
            long candidateScans = count * (count + 1L) / 2L;
            long candidateVisits = count * (count - 1L) * (count + 1L) / 6L;
            assertTrue(kernel.scans() >= candidateScans);
            assertTrue(kernel.scans() <= candidateScans + count);
            assertTrue(kernel.visits() >= candidateVisits);
            assertTrue(kernel.visits() <= count * (count - 1L) * (count + 4L) / 6L);
        }
    }

    @Test
    void preparedKernelAndPublicEntryPointHaveIdenticalSmallControlTraces() throws Exception {
        for (Strategy strategy : new Strategy[] {Strategy.DLS, Strategy.ETF}) {
            for (boolean connected : new boolean[] {false, true}) {
                List<Task> kernelTasks = connected ? chain(8) : independentTasks(8, false);
                Kernel kernel = new Kernel(strategy, kernelTasks, Collections.singletonList(vm(7, 1000.0, 1)));
                SharedStorageDagPlanTrace counted = kernel.schedule();
                List<Task> publicTasks = connected ? chain(8) : independentTasks(8, false);
                SharedStorageDagPlanTrace normal = runPublic(publicPlanner(strategy, publicTasks,
                        Collections.singletonList(vm(7, 1000.0, 1))));
                assertEquals(snapshot(normal), snapshot(counted), strategy + " connected=" + connected);
            }
        }
    }

    private static void assertSelection(Strategy strategy, SharedStorageDagPlanTrace.TaskPlan plan,
            int order, double rank, double start) {
        if (strategy == Strategy.DLS) {
            assertEquals(Integer.valueOf(order), plan.getDlsSelectionOrder());
            assertEquals(bits(rank - start), bits(plan.getDlsDynamicLevelAtSelection().doubleValue()));
        } else {
            assertEquals(Integer.valueOf(order), plan.getEtfSelectionOrder());
            assertEquals(bits(start), bits(plan.getEtfEarliestStartAtSelection().doubleValue()));
        }
    }
}
