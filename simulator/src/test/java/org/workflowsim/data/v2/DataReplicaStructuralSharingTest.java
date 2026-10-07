package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.v2.DataReplicaState.Acquisition;
import org.workflowsim.data.v2.DataReplicaState.CopyTicket;
import org.workflowsim.data.v2.DataReplicaState.Publication;
import org.workflowsim.data.v2.DataReplicaState.Replica;
import org.workflowsim.data.v2.DataReplicaState.Snapshot;
import org.workflowsim.data.v2.DataflowFilePlan.FileDefinition;
import org.workflowsim.data.v2.DataflowFilePlan.FileId;
import org.workflowsim.utils.Parameters.FileType;

/** Semantic oracles for retained immutable roots, without timing or reflective state access. */
class DataReplicaStructuralSharingTest {
    private static final DataLocation SOURCE = DataLocation.source("input-store");
    private static final DataLocation SECOND = DataLocation.source("second-store");
    private static final DataLocation VM7 = DataLocation.vm(7);
    private static final DataLocation VM42 = DataLocation.vm(42);
    private static final DataLocation VM99 = DataLocation.vm(99);
    private static final FileId EXTERNAL = id("external");
    private static final FileId ZERO_INPUT = id("zero-input");
    private static final FileId ZERO_OUTPUT = id("zero-output");
    private static final FileId P = id("p");
    private static final FileId Q = id("q");
    private static final List<DataLocation> ORDERED_LOCATIONS = Arrays.asList(VM7, VM42, VM99, SOURCE, SECOND);

    @Test void retainedSnapshotKeepsOrderedImmutableRowsTicketsAndLocationsAfterParentMutations() {
        DataflowFilePlan plan = plan();
        List<DataLocation> supplied = new ArrayList<>(Arrays.asList(SECOND, VM99, SOURCE, VM42, VM7));
        DataReplicaState parent = new DataReplicaState(plan, supplied);
        supplied.clear();
        Replica source = parent.seedExternal(EXTERNAL, SOURCE, 0).getReplica();
        Replica second = parent.seedExternal(EXTERNAL, SECOND, 0).getReplica();
        Replica zero = parent.seedExternal(ZERO_INPUT, SOURCE, 0).getReplica();
        parent.recordTaskCompletion(1, 10, VM42, true, 0);
        Replica output = parent.getReplica(P, VM42);
        Replica zeroOutput = parent.getReplica(ZERO_OUTPUT, VM42);
        CopyTicket inputCopy = parent.admitCopy(EXTERNAL, SECOND, VM42, 1);
        CopyTicket outputCopy = parent.admitCopy(P, VM42, VM7, 1);
        Snapshot retained = parent.snapshot();
        List<Replica> retainedRow = retained.getReplicas(EXTERNAL);

        parent.settleCopy(inputCopy, 2);
        parent.settleCopy(outputCopy, 3);
        parent.recordTaskCompletion(1, 11, VM99, true, 4);
        parent.recordTaskCompletion(2, 12, VM7, true, 4);
        parent.seedExternal(ZERO_INPUT, SECOND, 4);
        parent.resolveZeroReference(ZERO_INPUT, SOURCE, VM7, 4);
        parent.commitZeroOutput(ZERO_OUTPUT, VM42, SOURCE, 4);

        assertEquals(ORDERED_LOCATIONS, retained.getLocations());
        assertEquals(1, retained.getObservedThrough(), 0);
        assertSameElements(Arrays.asList(source, second), retainedRow);
        assertRows(retained, EXTERNAL, source, second);
        // These rows were not read before mutation: a snapshot must not lazily read its owner.
        assertRows(retained, P, output);
        assertRows(retained, ZERO_OUTPUT, zeroOutput);
        assertRows(retained, ZERO_INPUT, zero);
        assertRows(retained, Q);
        assertSameElements(Arrays.asList(inputCopy, outputCopy), retained.getActiveCopies());
        assertNull(retained.getReplica(EXTERNAL, VM42));
        assertNull(retained.getReplica(P, VM7));
        assertNull(retained.getReplica(ZERO_OUTPUT, SOURCE));
        assertSame(plan.getFile(P), retained.getFile(P));
        assertEquals(4, retained.getFile(P).getBytes(), 0);
        assertEquals(Arrays.asList(3), retained.getFile(P).getConsumerTaskIds());
        assertThrows(UnsupportedOperationException.class, () -> retained.getLocations().clear());
        assertThrows(UnsupportedOperationException.class, () -> retainedRow.set(0, second));
        assertThrows(UnsupportedOperationException.class, () -> retained.getReplicas(P).clear());
        assertThrows(UnsupportedOperationException.class, () -> retained.getReplicas(Q).add(output));
        assertThrows(UnsupportedOperationException.class, () -> retained.getActiveCopies().clear());
        assertThrows(UnsupportedOperationException.class, () -> retained.getFile(P).getConsumerTaskIds().add(99));
        assertThrows(IllegalArgumentException.class, () -> retained.getFile(id("unknown")));
        assertThrows(IllegalArgumentException.class, () -> retained.getReplicas(id("unknown")));
        assertThrows(IllegalArgumentException.class, () -> retained.getReplica(P, DataLocation.vm(404)));
        assertThrows(IllegalArgumentException.class, () -> retained.getReplica(P, null));
        assertCounts(parent, 4, 2, 3);
        assertLocations(parent.snapshot(), EXTERNAL, VM42, SOURCE, SECOND);
        assertLocations(parent.snapshot(), P, VM7, VM42, VM99);
        assertLocations(parent.snapshot(), Q, VM7);
    }

    @Test void parentChildSiblingAndNestedCompletionHistoriesAreIndependentInBothDirections() {
        DataflowFilePlan plan = plan();
        DataReplicaState parent = state(plan);
        parent.recordTaskCompletion(1, 10, VM7, true, 1);
        Replica original = parent.getReplica(P, VM7);
        Snapshot common = parent.snapshot();
        DataReplicaState child = parent.fork();
        DataReplicaState sibling = parent.fork();
        for (DataReplicaState branch : Arrays.asList(parent, child, sibling)) {
            assertTrue(branch.recordTaskCompletion(1, 10, VM7, true, 1).isEmpty());
            rejectsWithoutChange(IllegalStateException.class, branch, plan,
                    () -> branch.recordTaskCompletion(1, 10, VM7, false, 1));
        }

        child.recordTaskCompletion(2, 20, VM42, true, 2);
        Replica childQ = child.getReplica(Q, VM42);
        DataReplicaState nested = child.fork();
        Snapshot childBeforeLaterWrites = child.snapshot();
        // The same Task/Job key may have different outcomes in independently forked histories.
        assertTrue(parent.recordTaskCompletion(2, 20, VM7, false, 2).isEmpty());
        sibling.recordTaskCompletion(2, 20, VM99, true, 3);
        Replica siblingQ = sibling.getReplica(Q, VM99);
        assertTrue(child.recordTaskCompletion(4, 20, VM42, false, 2).isEmpty());
        assertTrue(nested.recordTaskCompletion(4, 20, VM42, true, 2).isEmpty());
        rejectsWithoutChange(IllegalStateException.class, nested, plan,
                () -> nested.recordTaskCompletion(4, 20, VM42, false, 2));
        assertTrue(child.recordTaskCompletion(4, 20, VM42, false, 2).isEmpty());

        child.recordTaskCompletion(1, 30, VM42, true, 4);
        nested.recordTaskCompletion(1, 30, VM99, true, 5);
        assertTrue(parent.recordTaskCompletion(1, 30, VM7, false, 6).isEmpty());
        List<Publication> repeated = sibling.recordTaskCompletion(1, 30, VM7, true, 7);
        assertExisting(repeated.get(0), original);

        assertRows(common, P, original);
        assertRows(common, Q);
        assertRows(childBeforeLaterWrites, P, original);
        assertRows(childBeforeLaterWrites, Q, childQ);
        assertRows(parent.snapshot(), P, original);
        assertRows(parent.snapshot(), Q);
        assertRows(sibling.snapshot(), P, original);
        assertRows(sibling.snapshot(), Q, siblingQ);
        assertRows(child.snapshot(), Q, childQ);
        assertRows(nested.snapshot(), Q, childQ);
        assertLocations(child.snapshot(), P, VM7, VM42);
        assertLocations(nested.snapshot(), P, VM7, VM99);
        assertNull(child.getReplica(P, VM99));
        assertNull(nested.getReplica(P, VM42));
        assertCounts(parent, 6, 0, 3);
        assertCounts(child, 4, 0, 4);
        assertCounts(sibling, 7, 0, 3);
        assertCounts(nested, 5, 0, 4);
    }

    @Test void cachePublicationsAreIndependentAcrossParentChildSiblingAndNestedBranches() {
        DataReplicaState parent = state(plan());
        Replica zero = parent.seedExternal(ZERO_INPUT, SOURCE, 0).getReplica();
        Replica external = parent.seedExternal(EXTERNAL, SOURCE, 0).getReplica();
        parent.recordTaskCompletion(1, 1, VM7, true, 0);
        Replica produced = parent.getReplica(ZERO_OUTPUT, VM7);
        Snapshot common = parent.snapshot();
        DataReplicaState child = parent.fork();
        DataReplicaState sibling = parent.fork();
        Replica child7 = child.resolveZeroReference(ZERO_INPUT, SOURCE, VM7, 1).getReplica();
        Replica childSeed = child.seedExternal(EXTERNAL, SECOND, 1).getReplica();
        DataReplicaState nested = child.fork();
        Snapshot nestedBeforeWrites = nested.snapshot();

        Replica parent42 = parent.resolveZeroReference(ZERO_INPUT, SOURCE, VM42, 2).getReplica();
        Replica parentStore = parent.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 2).getReplica();
        Replica child99 = child.resolveZeroReference(ZERO_INPUT, VM7, VM99, 3).getReplica();
        Replica childStore = child.commitZeroOutput(ZERO_OUTPUT, VM7, SECOND, 3).getReplica();
        Replica nested42 = nested.resolveZeroReference(ZERO_INPUT, VM7, VM42, 4).getReplica();
        Replica nestedStore = nested.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 4).getReplica();
        Replica sibling99 = sibling.resolveZeroReference(ZERO_INPUT, SOURCE, VM99, 5).getReplica();
        Replica siblingSeed = sibling.seedExternal(EXTERNAL, SECOND, 5).getReplica();
        Replica siblingStore = sibling.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 5).getReplica();

        assertRows(common, ZERO_INPUT, zero);
        assertRows(common, EXTERNAL, external);
        assertRows(common, ZERO_OUTPUT, produced);
        assertRows(nestedBeforeWrites, ZERO_INPUT, child7, zero);
        assertRows(nestedBeforeWrites, ZERO_OUTPUT, produced);
        assertRows(parent.snapshot(), ZERO_INPUT, parent42, zero);
        assertRows(child.snapshot(), ZERO_INPUT, child7, child99, zero);
        assertRows(nested.snapshot(), ZERO_INPUT, child7, nested42, zero);
        assertRows(sibling.snapshot(), ZERO_INPUT, sibling99, zero);
        assertRows(parent.snapshot(), EXTERNAL, external);
        assertRows(child.snapshot(), EXTERNAL, external, childSeed);
        assertRows(nested.snapshot(), EXTERNAL, external, childSeed);
        assertRows(sibling.snapshot(), EXTERNAL, external, siblingSeed);
        assertRows(parent.snapshot(), ZERO_OUTPUT, produced, parentStore);
        assertRows(child.snapshot(), ZERO_OUTPUT, produced, childStore);
        assertRows(nested.snapshot(), ZERO_OUTPUT, produced, nestedStore);
        assertRows(sibling.snapshot(), ZERO_OUTPUT, produced, siblingStore);
        assertNotSame(childSeed, siblingSeed);
        assertNotSame(parent42, nested42);
        assertNotSame(parentStore, nestedStore);
        assertEquals(2, parentStore.getVisibleAt(), 0);
        assertEquals(4, nestedStore.getVisibleAt(), 0);
        assertEquals(5, siblingStore.getVisibleAt(), 0);
        assertSame(produced.getOrigin(), parentStore.getOrigin());
        assertSame(produced.getOrigin(), childStore.getOrigin());
        assertSame(produced.getOrigin(), nestedStore.getOrigin());
        assertSame(produced.getOrigin(), siblingStore.getOrigin());
        assertCounts(parent, 2, 0, 1);
        assertCounts(child, 3, 0, 1);
        assertCounts(nested, 4, 0, 1);
        assertCounts(sibling, 5, 0, 1);
    }

    @Test void inheritedTicketsCanSettleIndependentlyInEveryBranchAndKeepTheirOriginalIdentity() {
        DataReplicaState parent = state(plan());
        Replica source = parent.seedExternal(EXTERNAL, SOURCE, 0).getReplica();
        parent.recordTaskCompletion(1, 1, VM7, true, 0);
        Replica output = parent.getReplica(P, VM7);
        CopyTicket inputCopy = parent.admitCopy(EXTERNAL, SOURCE, VM42, 1);
        CopyTicket outputCopy = parent.admitCopy(P, VM7, VM99, 1);
        Snapshot retained = parent.snapshot();
        DataReplicaState child = parent.fork();
        DataReplicaState sibling = parent.fork();
        DataReplicaState nested = child.fork();
        for (DataReplicaState branch : Arrays.asList(parent, child, sibling, nested)) {
            assertCounts(branch, 1, 2, 1, inputCopy, outputCopy);
            assertSame(inputCopy, branch.getInFlight(EXTERNAL, VM42));
            assertSame(outputCopy, branch.getInFlight(P, VM99));
            assertSame(source, inputCopy.getSourceReplica());
            assertSame(output, outputCopy.getSourceReplica());
        }

        Replica childInput = child.settleCopy(inputCopy, 2).getReplica();
        assertActive(child, outputCopy);
        assertActive(parent, inputCopy, outputCopy);
        assertActive(nested, inputCopy, outputCopy);
        assertNull(child.getInFlight(EXTERNAL, VM42));
        assertNull(parent.getReplica(EXTERNAL, VM42));
        Replica parentOutput = parent.settleCopy(outputCopy, 3).getReplica();
        assertActive(parent, inputCopy);
        assertActive(sibling, inputCopy, outputCopy);
        assertNull(parent.getInFlight(P, VM99));
        assertNull(child.getReplica(P, VM99));
        Replica nestedInput = nested.settleCopy(inputCopy, 4).getReplica();
        Replica nestedOutput = nested.settleCopy(outputCopy, 4).getReplica();
        Replica siblingOutput = sibling.settleCopy(outputCopy, 5).getReplica();
        Replica siblingInput = sibling.settleCopy(inputCopy, 5).getReplica();
        Replica parentInput = parent.settleCopy(inputCopy, 6).getReplica();
        Replica childOutput = child.settleCopy(outputCopy, 7).getReplica();

        assertCopy(parentInput, source, VM42, 6, 1);
        assertCopy(childInput, source, VM42, 2, 1);
        assertCopy(nestedInput, source, VM42, 4, 1);
        assertCopy(siblingInput, source, VM42, 5, 1);
        assertCopy(parentOutput, output, VM99, 3, 2);
        assertCopy(childOutput, output, VM99, 7, 2);
        assertCopy(nestedOutput, output, VM99, 4, 2);
        assertCopy(siblingOutput, output, VM99, 5, 2);
        assertNotSame(parentInput, childInput);
        assertNotSame(nestedOutput, siblingOutput);
        assertSameElements(Arrays.asList(inputCopy, outputCopy), retained.getActiveCopies());
        assertRows(retained, EXTERNAL, source);
        assertRows(retained, P, output);
        assertEquals(1, retained.getObservedThrough(), 0);
        assertCounts(parent, 6, 2, 1);
        assertCounts(child, 7, 2, 1);
        assertCounts(nested, 4, 2, 1);
        assertCounts(sibling, 5, 2, 1);
        for (DataReplicaState branch : Arrays.asList(parent, child, sibling, nested)) {
            assertNull(branch.getInFlight(EXTERNAL, VM42));
            assertNull(branch.getInFlight(P, VM99));
        }
    }

    @Test void newlyAdmittedSiblingTicketsWithTheSameOrdinalCannotBeInterchanged() {
        DataflowFilePlan plan = plan();
        DataReplicaState parent = state(plan);
        parent.seedExternal(EXTERNAL, SOURCE, 0);
        CopyTicket inherited = parent.admitCopy(EXTERNAL, SOURCE, VM7, 1);
        DataReplicaState child = parent.fork();
        DataReplicaState sibling = parent.fork();
        CopyTicket childTicket = child.admitCopy(EXTERNAL, SOURCE, VM42, 2);
        CopyTicket siblingTicket = sibling.admitCopy(EXTERNAL, SOURCE, VM42, 2);
        CopyTicket parentTicket = parent.admitCopy(EXTERNAL, SOURCE, VM99, 2);
        DataReplicaState nested = child.fork();
        assertEquals(2, childTicket.getOrdinal());
        assertEquals(2, siblingTicket.getOrdinal());
        assertEquals(2, parentTicket.getOrdinal());
        assertNotSame(childTicket, siblingTicket);
        assertSame(childTicket.getSourceReplica(), siblingTicket.getSourceReplica());
        assertEquals(childTicket.getDestination(), siblingTicket.getDestination());
        assertEquals(childTicket.getReleaseTime(), siblingTicket.getReleaseTime(), 0);
        rejectsWithoutChange(IllegalStateException.class, child, plan, () -> child.settleCopy(siblingTicket, 3));
        rejectsWithoutChange(IllegalStateException.class, sibling, plan, () -> sibling.settleCopy(childTicket, 3));
        rejectsWithoutChange(IllegalStateException.class, parent, plan, () -> parent.settleCopy(childTicket, 3));
        rejectsWithoutChange(IllegalStateException.class, nested, plan, () -> nested.settleCopy(siblingTicket, 3));
        rejectsWithoutChange(IllegalStateException.class, child, plan, () -> child.settleCopy(parentTicket, 3));

        child.settleCopy(childTicket, 3);
        assertActive(child, inherited);
        assertActive(sibling, inherited, siblingTicket);
        assertActive(nested, inherited, childTicket);
        assertNull(sibling.getReplica(EXTERNAL, VM42));
        nested.settleCopy(childTicket, 4);
        sibling.settleCopy(siblingTicket, 5);
        parent.settleCopy(parentTicket, 3);
        CopyTicket childNext = child.admitCopy(EXTERNAL, VM42, VM99, 3);
        CopyTicket siblingNext = sibling.admitCopy(EXTERNAL, VM42, VM99, 5);
        assertEquals(3, childNext.getOrdinal());
        assertEquals(3, siblingNext.getOrdinal());
        assertNotSame(childNext, siblingNext);
        assertCounts(child, 3, 3, 0, inherited, childNext);
        assertCounts(sibling, 5, 3, 0, inherited, siblingNext);
        assertCounts(parent, 3, 2, 0, inherited);
        assertCounts(nested, 4, 2, 0, inherited);
    }

    @Test void conflictingTaskFlagsJobPlacementsAndObservationTimesAreRejectedAtomically() {
        DataflowFilePlan plan = plan();
        DataReplicaState state = state(plan);
        state.seedExternal(EXTERNAL, SOURCE, 0);
        CopyTicket active = state.admitCopy(EXTERNAL, SOURCE, VM99, 1);
        state.recordTaskCompletion(1, 20, VM7, true, 2);
        Replica output = state.getReplica(P, VM7);
        Replica zeroOutput = state.getReplica(ZERO_OUTPUT, VM7);
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(1, 20, VM7, false, 2));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(1, 20, VM42, true, 2));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(1, 20, VM7, true, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(2, 20, VM42, true, 2));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(2, 20, VM7, false, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.recordTaskCompletion(2, -1, VM7, true, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.recordTaskCompletion(404, 30, VM7, true, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.recordTaskCompletion(2, 30, SOURCE, true, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.recordTaskCompletion(2, 30, DataLocation.vm(404), true, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.recordTaskCompletion(2, 30, null, true, 3));
        assertTrue(state.recordTaskCompletion(1, 20, VM7, true, 2).isEmpty());
        assertCounts(state, 2, 1, 1, active);
        assertRows(state.snapshot(), P, output);
        assertRows(state.snapshot(), ZERO_OUTPUT, zeroOutput);
        assertRows(state.snapshot(), Q);

        // Constituent Tasks may differ in success; outputless Tasks still have write-once history.
        assertTrue(state.recordTaskCompletion(2, 20, VM7, false, 2).isEmpty());
        assertTrue(state.recordTaskCompletion(3, 20, VM7, true, 2).isEmpty());
        assertTrue(state.recordTaskCompletion(4, 20, VM7, false, 2).isEmpty());
        assertTrue(state.recordTaskCompletion(4, 20, VM7, false, 2).isEmpty());
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(2, 20, VM7, true, 2));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.recordTaskCompletion(4, 20, VM7, true, 2));
        assertCounts(state, 2, 1, 4, active);
        // Rejected calls using Job 30 must not leave behind even a Job-only observation.
        state.recordTaskCompletion(2, 30, VM42, true, 4);
        assertEquals(Long.valueOf(30), state.getReplica(Q, VM42).getOrigin().getJobAttemptId());
        assertEquals(4, state.getReplica(Q, VM42).getVisibleAt(), 0);
        assertCounts(state, 4, 1, 5, active);
    }

    @Test void invalidAdmissionsAndObservationTimesLeaveClocksCountersRowsAndActiveIdsUnchanged() {
        DataflowFilePlan plan = plan();
        DataReplicaState state = state(plan);
        state.seedExternal(EXTERNAL, SOURCE, 1);
        state.seedExternal(ZERO_INPUT, SOURCE, 1);
        state.recordTaskCompletion(1, 10, VM7, true, 1);
        CopyTicket active = state.admitCopy(EXTERNAL, SOURCE, VM42, 2);
        Snapshot retained = state.snapshot();
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, VM99, VM7, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, SOURCE, SOURCE, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, SOURCE, VM42, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.admitCopy(P, VM7, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(ZERO_INPUT, SOURCE, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(id("unknown"), SOURCE, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, DataLocation.vm(404), VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, SOURCE, DataLocation.vm(404), 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, null, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, SOURCE, null, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.admitCopy(EXTERNAL, SOURCE, VM99, Double.MAX_VALUE));
        for (double invalid : new double[] {-1, 1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.advanceTo(invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                    () -> state.admitCopy(EXTERNAL, SOURCE, VM99, invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                    () -> state.seedExternal(EXTERNAL, SECOND, invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                    () -> state.resolveZeroReference(ZERO_INPUT, SOURCE, VM7, invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                    () -> state.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                    () -> state.recordTaskCompletion(2, 30, VM7, true, invalid));
            rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(active, invalid));
        }
        assertCounts(state, 2, 1, 1, active);
        CopyTicket next = state.admitCopy(EXTERNAL, SOURCE, VM99, 3);
        assertEquals(2, next.getOrdinal());
        assertCounts(state, 3, 2, 1, active, next);
        assertSameElements(Arrays.asList(active), retained.getActiveCopies());
        assertEquals(2, retained.getObservedThrough(), 0);
        assertNull(retained.getReplica(EXTERNAL, VM99));
    }

    @Test void invalidForeignEarlyAndRepeatedSettlementsKeepTheWholeVisibleAndActiveState() {
        DataflowFilePlan plan = plan();
        DataReplicaState state = state(plan);
        Replica source = state.seedExternal(EXTERNAL, SOURCE, 1).getReplica();
        CopyTicket first = state.admitCopy(EXTERNAL, SOURCE, VM42, 2);
        CopyTicket second = state.admitCopy(EXTERNAL, SOURCE, VM99, 3);
        Snapshot retained = state.snapshot();
        DataReplicaState otherRun = state(plan);
        otherRun.seedExternal(EXTERNAL, SOURCE, 1);
        CopyTicket foreign = otherRun.admitCopy(EXTERNAL, SOURCE, VM42, 2);
        assertEquals(first.getOrdinal(), foreign.getOrdinal());
        rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(null, 4));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(foreign, 4));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(first, 2));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(second, 3));
        for (double invalid : new double[] {-1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            rejectsWithoutChange(IllegalArgumentException.class, state, plan, () -> state.settleCopy(first, invalid));
        }
        Replica firstCopy = state.settleCopy(first, 4).getReplica();
        assertCopy(firstCopy, source, VM42, 4, 1);
        assertCounts(state, 4, 2, 0, second);
        assertNull(state.getInFlight(EXTERNAL, VM42));
        rejectsWithoutChange(IllegalStateException.class, state, plan, () -> state.settleCopy(first, 5));
        Replica secondCopy = state.settleCopy(second, 5).getReplica();
        assertCopy(secondCopy, source, VM99, 5, 2);
        assertCounts(state, 5, 2, 0);
        assertRows(retained, EXTERNAL, source);
        assertSameElements(Arrays.asList(first, second), retained.getActiveCopies());
        assertEquals(3, retained.getObservedThrough(), 0);
        assertCounts(otherRun, 2, 1, 0, foreign);
    }

    @Test void sourceSeedsAndZeroReferencesPreserveTheFirstPublicationObjectAndOrigin() {
        DataReplicaState state = state(plan());
        Publication seeded = state.seedExternal(EXTERNAL, SOURCE, 0);
        Replica external = seeded.getReplica();
        Replica zero = state.seedExternal(ZERO_INPUT, SOURCE, 0).getReplica();
        Snapshot beforeReferences = state.snapshot();
        assertTrue(seeded.isNewReplica());
        assertExisting(state.seedExternal(EXTERNAL, SOURCE, 2), external);
        assertExisting(state.seedExternal(ZERO_INPUT, SOURCE, 2), zero);
        Replica reference = state.resolveZeroReference(ZERO_INPUT, SOURCE, VM42, 2).getReplica();
        List<Replica> retainedRow = state.snapshot().getReplicas(ZERO_INPUT);
        assertExisting(state.resolveZeroReference(ZERO_INPUT, VM42, VM42, 3), reference);
        assertExisting(state.resolveZeroReference(ZERO_INPUT, VM42, SOURCE, 4), zero);
        Replica secondSeed = state.seedExternal(EXTERNAL, SECOND, 4).getReplica();
        Replica secondReference = state.resolveZeroReference(ZERO_INPUT, VM42, SECOND, 5).getReplica();
        assertExisting(state.seedExternal(ZERO_INPUT, SECOND, 6), secondReference);
        assertExisting(state.seedExternal(EXTERNAL, SOURCE, 6), external);

        assertEquals(Acquisition.EXTERNAL_SEED, external.getAcquisition());
        assertEquals(0, external.getVisibleAt(), 0);
        assertEquals(SOURCE, external.getOrigin().getLocation());
        assertEquals(0, external.getOrigin().getObservedAt(), 0);
        assertTrue(external.getOrigin().isExternal());
        assertNull(external.getOrigin().getProducerTaskId());
        assertNull(external.getOrigin().getJobAttemptId());
        assertNull(external.getCopiedFrom());
        assertNull(external.getCopyOrdinal());
        assertEquals(.5, external.getFile().getBytes(), 0);
        assertEquals(SECOND, secondSeed.getOrigin().getLocation());
        assertEquals(4, secondSeed.getVisibleAt(), 0);
        assertEquals(Acquisition.ZERO_BYTE_REFERENCE, reference.getAcquisition());
        assertEquals(2, reference.getVisibleAt(), 0);
        assertEquals(SOURCE, reference.getCopiedFrom());
        assertSame(zero.getOrigin(), reference.getOrigin());
        assertNull(reference.getCopyOrdinal());
        assertEquals(Acquisition.ZERO_BYTE_REFERENCE, secondReference.getAcquisition());
        assertEquals(5, secondReference.getVisibleAt(), 0);
        assertEquals(VM42, secondReference.getCopiedFrom());
        assertSame(zero.getOrigin(), secondReference.getOrigin());
        assertRows(beforeReferences, EXTERNAL, external);
        assertRows(beforeReferences, ZERO_INPUT, zero);
        assertSameElements(Arrays.asList(reference, zero), retainedRow);
        assertRows(state.snapshot(), ZERO_INPUT, reference, zero, secondReference);
        assertRows(state.snapshot(), EXTERNAL, external, secondSeed);
        assertCounts(state, 6, 0, 0);
    }

    @Test void directOutputAndPositiveCopyRacesAlwaysReturnTheFirstVisibleReplicaObject() {
        DataReplicaState state = state(plan());
        List<Publication> first = state.recordTaskCompletion(1, 10, VM7, true, 1);
        Replica original = first.get(0).getReplica();
        Replica zeroOriginal = first.get(1).getReplica();
        assertEquals(P, original.getFile().getId());
        assertEquals(ZERO_OUTPUT, zeroOriginal.getFile().getId());
        assertTrue(first.get(0).isNewReplica());
        assertTrue(first.get(1).isNewReplica());
        assertThrows(UnsupportedOperationException.class, first::clear);
        assertTrue(state.recordTaskCompletion(1, 10, VM7, true, 1).isEmpty());
        List<Publication> retry = state.recordTaskCompletion(1, 11, VM7, true, 2);
        assertEquals(2, retry.size());
        assertExisting(retry.get(0), original);
        assertExisting(retry.get(1), zeroOriginal);
        CopyTicket beforeDirect = state.admitCopy(P, VM7, VM42, 2);
        Replica direct = state.recordTaskCompletion(1, 12, VM42, true, 3).get(0).getReplica();
        Snapshot directFirst = state.snapshot();
        assertExisting(state.settleCopy(beforeDirect, 4), direct);
        assertEquals(Acquisition.TASK_OUTPUT, direct.getAcquisition());
        assertEquals(3, direct.getVisibleAt(), 0);
        assertEquals(Long.valueOf(12), direct.getOrigin().getJobAttemptId());
        assertNull(direct.getCopiedFrom());
        assertNull(direct.getCopyOrdinal());

        CopyTicket beforeRetry = state.admitCopy(P, VM7, VM99, 4);
        Replica copied = state.settleCopy(beforeRetry, 5).getReplica();
        Snapshot copyFirst = state.snapshot();
        List<Publication> laterOutput = state.recordTaskCompletion(1, 13, VM99, true, 6);
        assertExisting(laterOutput.get(0), copied);
        assertTrue(laterOutput.get(1).isNewReplica());
        assertCopy(copied, original, VM99, 5, 2);
        assertEquals(Integer.valueOf(1), copied.getOrigin().getProducerTaskId());
        assertEquals(Long.valueOf(10), copied.getOrigin().getJobAttemptId());
        assertEquals(VM7, copied.getOrigin().getLocation());
        assertEquals(1, copied.getOrigin().getObservedAt(), 0);
        assertRows(directFirst, P, original, direct);
        assertRows(copyFirst, P, original, direct, copied);
        assertRows(state.snapshot(), P, original, direct, copied);
        assertSameElements(Arrays.asList(beforeDirect), directFirst.getActiveCopies());
        assertTrue(copyFirst.getActiveCopies().isEmpty());
        assertCounts(state, 6, 2, 4);
    }

    @Test void positiveCopiesAndExternalSeedsCannotReplaceEachOthersEarlierPublication() {
        DataReplicaState seedFirst = state(plan());
        seedFirst.seedExternal(EXTERNAL, SOURCE, 0);
        CopyTicket inFlight = seedFirst.admitCopy(EXTERNAL, SOURCE, SECOND, 1);
        Replica earlierSeed = seedFirst.seedExternal(EXTERNAL, SECOND, 2).getReplica();
        Snapshot retainedSeed = seedFirst.snapshot();
        assertExisting(seedFirst.settleCopy(inFlight, 3), earlierSeed);
        assertSame(earlierSeed, retainedSeed.getReplica(EXTERNAL, SECOND));
        assertEquals(Acquisition.EXTERNAL_SEED, earlierSeed.getAcquisition());
        assertEquals(SECOND, earlierSeed.getOrigin().getLocation());
        assertEquals(2, earlierSeed.getVisibleAt(), 0);
        assertNull(earlierSeed.getCopyOrdinal());
        assertCounts(seedFirst, 3, 1, 0);

        DataReplicaState copyFirst = state(plan());
        Replica source = copyFirst.seedExternal(EXTERNAL, SOURCE, 0).getReplica();
        CopyTicket ticket = copyFirst.admitCopy(EXTERNAL, SOURCE, SECOND, 1);
        Replica earlierCopy = copyFirst.settleCopy(ticket, 2).getReplica();
        Snapshot retainedCopy = copyFirst.snapshot();
        assertExisting(copyFirst.seedExternal(EXTERNAL, SECOND, 3), earlierCopy);
        assertSame(earlierCopy, retainedCopy.getReplica(EXTERNAL, SECOND));
        assertCopy(earlierCopy, source, SECOND, 2, 1);
        assertCounts(copyFirst, 3, 1, 0);
    }

    @Test void v2ZeroReferencesAndV3ZeroOutputCommitsPreserveFirstPublicationWithoutCopyIds() {
        DataReplicaState state = state(plan());
        state.recordTaskCompletion(1, 10, VM7, true, 1);
        Replica original = state.getReplica(ZERO_OUTPUT, VM7);
        // V3 zero-output metadata may commit at the producing completion observation itself.
        Replica committed = state.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 1).getReplica();
        Snapshot beforeReference = state.snapshot();
        assertExisting(state.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 3), committed);
        Replica reference = state.resolveZeroReference(ZERO_OUTPUT, SOURCE, VM42, 3).getReplica();
        List<Publication> retry = state.recordTaskCompletion(1, 11, VM42, true, 4);
        assertExisting(retry.get(1), reference);
        assertExisting(state.commitZeroOutput(ZERO_OUTPUT, VM42, SOURCE, 5), committed);
        Replica referenceFirst = state.resolveZeroReference(ZERO_OUTPUT, VM42, SECOND, 5).getReplica();
        assertExisting(state.commitZeroOutput(ZERO_OUTPUT, VM42, SECOND, 6), referenceFirst);
        assertExisting(state.resolveZeroReference(ZERO_OUTPUT, SOURCE, VM7, 6), original);
        assertExisting(state.recordTaskCompletion(1, 12, VM7, true, 7).get(1), original);

        assertEquals(Acquisition.TASK_OUTPUT, original.getAcquisition());
        assertEquals(1, original.getVisibleAt(), 0);
        assertEquals(0, original.getFile().getBytes(), 0);
        assertEquals(Acquisition.ZERO_BYTE_OUTPUT, committed.getAcquisition());
        assertEquals(1, committed.getVisibleAt(), 0);
        assertEquals(VM7, committed.getCopiedFrom());
        assertEquals(Acquisition.ZERO_BYTE_REFERENCE, reference.getAcquisition());
        assertEquals(3, reference.getVisibleAt(), 0);
        assertEquals(SOURCE, reference.getCopiedFrom());
        assertEquals(Acquisition.ZERO_BYTE_REFERENCE, referenceFirst.getAcquisition());
        assertEquals(5, referenceFirst.getVisibleAt(), 0);
        assertEquals(VM42, referenceFirst.getCopiedFrom());
        for (Replica replica : Arrays.asList(original, committed, reference, referenceFirst)) {
            assertSame(original.getOrigin(), replica.getOrigin());
            assertEquals(Long.valueOf(10), replica.getOrigin().getJobAttemptId());
            assertEquals(Integer.valueOf(1), replica.getOrigin().getProducerTaskId());
            assertEquals(VM7, replica.getOrigin().getLocation());
            assertEquals(1, replica.getOrigin().getObservedAt(), 0);
            assertNull(replica.getCopyOrdinal());
        }
        assertRows(beforeReference, ZERO_OUTPUT, original, committed);
        assertRows(state.snapshot(), ZERO_OUTPUT, original, reference, committed, referenceFirst);
        assertCounts(state, 7, 0, 3);
    }

    @Test void invalidSeedsZeroReferencesAndZeroOutputCommitsAreAtomicWithLiveCopies() {
        DataflowFilePlan plan = plan();
        DataReplicaState state = state(plan);
        state.seedExternal(EXTERNAL, SOURCE, 1);
        state.seedExternal(ZERO_INPUT, SOURCE, 1);
        state.recordTaskCompletion(1, 10, VM7, true, 1);
        CopyTicket active = state.admitCopy(EXTERNAL, SOURCE, VM99, 2);
        Snapshot retained = state.snapshot();
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_INPUT, VM7, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(P, VM7, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_OUTPUT, SOURCE, SECOND, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_OUTPUT, VM7, VM42, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_OUTPUT, VM42, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(id("unknown"), VM7, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_OUTPUT, null, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.commitZeroOutput(ZERO_OUTPUT, VM7, DataLocation.source("unknown"), 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.resolveZeroReference(EXTERNAL, SOURCE, VM7, 3));
        rejectsWithoutChange(IllegalStateException.class, state, plan,
                () -> state.resolveZeroReference(ZERO_INPUT, VM42, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.resolveZeroReference(id("unknown"), SOURCE, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.resolveZeroReference(ZERO_INPUT, SOURCE, null, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.seedExternal(P, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.seedExternal(ZERO_OUTPUT, SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.seedExternal(ZERO_INPUT, VM7, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.seedExternal(id("unknown"), SOURCE, 3));
        rejectsWithoutChange(IllegalArgumentException.class, state, plan,
                () -> state.seedExternal(ZERO_INPUT, DataLocation.source("unknown"), 3));
        assertCounts(state, 2, 1, 1, active);
        assertTrue(state.commitZeroOutput(ZERO_OUTPUT, VM7, SOURCE, 3).isNewReplica());
        assertTrue(state.resolveZeroReference(ZERO_INPUT, SOURCE, VM42, 3).isNewReplica());
        assertCounts(state, 3, 1, 1, active);
        assertNull(retained.getReplica(ZERO_OUTPUT, SOURCE));
        assertNull(retained.getReplica(ZERO_INPUT, VM42));
        assertSameElements(Arrays.asList(active), retained.getActiveCopies());
    }

    @Test void manyFilesAndCompletedAttemptsStayStableThroughForksSnapshotsAndLaterRowUpdates() {
        final int fileCount = 256;
        final int extraHistory = 2048;
        DataflowFilePlan plan = manyFilePlan(fileCount);
        DataReplicaState parent = state(plan);
        List<Replica> originals = new ArrayList<>();
        List<Replica> seeds = new ArrayList<>();
        List<Replica> zeroReferences = new ArrayList<>();
        List<CopyTicket> tickets = new ArrayList<>();
        List<Replica> copies = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            seeds.add(parent.seedExternal(id("seed-" + i), SOURCE, 0).getReplica());
            zeroReferences.add(parent.resolveZeroReference(id("seed-" + i), SOURCE, VM7, 0).getReplica());
            originals.add(parent.recordTaskCompletion(i + 1, i + 1, VM7, true, 0).get(0).getReplica());
        }
        for (int i = 0; i < fileCount; i++) {
            tickets.add(parent.admitCopy(id("file-" + i), VM7, VM42, 0));
        }
        for (CopyTicket ticket : tickets) copies.add(parent.settleCopy(ticket, 1).getReplica());
        for (int i = 0; i < extraHistory; i++) {
            parent.recordTaskCompletion(i % fileCount + 1, 10000 + i, VM7, (i & 1) == 0, 1);
        }
        final long commonCount = fileCount + extraHistory;
        assertCounts(parent, 1, fileCount, commonCount);
        Snapshot retained = parent.snapshot();
        DataReplicaState child = parent.fork();
        DataReplicaState sibling = parent.fork();
        for (DataReplicaState branch : Arrays.asList(parent, child, sibling)) {
            assertTrue(branch.recordTaskCompletion(1, 10000, VM7, true, 1).isEmpty());
            assertEquals(commonCount, branch.getCompletedTaskAttemptCount());
        }
        for (int i = 0; i < fileCount; i++) {
            parent.seedExternal(id("seed-" + i), SECOND, 2);
            parent.recordTaskCompletion(i + 1, 20000 + i, VM99, true, 2);
        }
        for (int i = 0; i < fileCount; i++) {
            child.resolveZeroReference(id("seed-" + i), VM7, VM42, 3);
            child.recordTaskCompletion(i + 1, 20000 + i, VM99, false, 3);
        }
        DataReplicaState nested = child.fork();
        Snapshot childBeforeLaterWrites = child.snapshot();
        for (int i = 0; i < fileCount; i++) child.seedExternal(id("seed-" + i), SECOND, 4);
        for (int i = 0; i < fileCount; i++) nested.recordTaskCompletion(i + 1, 30000 + i, VM99, true, 5);
        for (int i = 0; i < fileCount; i++) sibling.recordTaskCompletion(i + 1, 20000 + i, VM99, true, 6);

        Snapshot parentNow = parent.snapshot();
        Snapshot childNow = child.snapshot();
        Snapshot siblingNow = sibling.snapshot();
        Snapshot nestedNow = nested.snapshot();
        assertEquals(fileCount * 2, plan.getFiles().size());
        for (int i = 0; i < fileCount; i++) {
            FileId output = id("file-" + i);
            FileId seed = id("seed-" + i);
            assertRows(retained, output, originals.get(i), copies.get(i));
            assertRows(retained, seed, zeroReferences.get(i), seeds.get(i));
            assertCopy(copies.get(i), originals.get(i), VM42, 1, i + 1);
            assertEquals(i + .25, retained.getFile(output).getBytes(), 0);
            assertEquals(0, originals.get(i).getVisibleAt(), 0);
            assertEquals(Long.valueOf(i + 1), originals.get(i).getOrigin().getJobAttemptId());
            assertRows(childNow, output, originals.get(i), copies.get(i));
            assertRows(childBeforeLaterWrites, output, originals.get(i), copies.get(i));
            assertRows(siblingNow, seed, zeroReferences.get(i), seeds.get(i));
            assertLocations(parentNow, output, VM7, VM42, VM99);
            assertLocations(siblingNow, output, VM7, VM42, VM99);
            assertLocations(nestedNow, output, VM7, VM42, VM99);
            assertLocations(parentNow, seed, VM7, SOURCE, SECOND);
            assertLocations(childNow, seed, VM7, VM42, SOURCE, SECOND);
            assertLocations(nestedNow, seed, VM7, VM42, SOURCE);
            assertLocations(childBeforeLaterWrites, seed, VM7, VM42, SOURCE);
            assertSame(childNow.getReplica(seed, VM42), nestedNow.getReplica(seed, VM42));
            assertEquals(3, nestedNow.getReplica(seed, VM42).getVisibleAt(), 0);
            assertEquals(2, parentNow.getReplica(output, VM99).getVisibleAt(), 0);
            assertEquals(5, nestedNow.getReplica(output, VM99).getVisibleAt(), 0);
            assertEquals(6, siblingNow.getReplica(output, VM99).getVisibleAt(), 0);
            assertEquals(2, parentNow.getReplica(seed, SECOND).getVisibleAt(), 0);
            assertEquals(4, childNow.getReplica(seed, SECOND).getVisibleAt(), 0);
            assertNotSame(parentNow.getReplica(output, VM99), siblingNow.getReplica(output, VM99));
            assertNull(retained.getReplica(output, VM99));
            assertNull(retained.getReplica(seed, SECOND));
        }
        assertEquals(ORDERED_LOCATIONS, retained.getLocations());
        assertEquals(1, retained.getObservedThrough(), 0);
        assertTrue(retained.getActiveCopies().isEmpty());
        assertEquals(3, childBeforeLaterWrites.getObservedThrough(), 0);
        assertCounts(parent, 2, fileCount, commonCount + fileCount);
        assertCounts(child, 4, fileCount, commonCount + fileCount);
        assertCounts(sibling, 6, fileCount, commonCount + fileCount);
        assertCounts(nested, 5, fileCount, commonCount + fileCount * 2);
    }

    private static void assertExisting(Publication publication, Replica first) {
        assertFalse(publication.isNewReplica());
        assertSame(first, publication.getReplica());
    }

    private static void assertCopy(Replica replica, Replica source, DataLocation destination, double at, long ordinal) {
        assertSame(source.getFile(), replica.getFile());
        assertEquals(destination, replica.getLocation());
        assertEquals(at, replica.getVisibleAt(), 0);
        assertEquals(Acquisition.COPY_SETTLEMENT, replica.getAcquisition());
        assertSame(source.getOrigin(), replica.getOrigin());
        assertEquals(source.getLocation(), replica.getCopiedFrom());
        assertEquals(Long.valueOf(ordinal), replica.getCopyOrdinal());
    }

    private static void assertRows(Snapshot snapshot, FileId file, Replica... expected) {
        assertSameElements(Arrays.asList(expected), snapshot.getReplicas(file));
        for (Replica replica : expected) assertSame(replica, snapshot.getReplica(file, replica.getLocation()));
    }

    private static void assertLocations(Snapshot snapshot, FileId file, DataLocation... expected) {
        List<DataLocation> actual = new ArrayList<>();
        for (Replica replica : snapshot.getReplicas(file)) actual.add(replica.getLocation());
        assertEquals(Arrays.asList(expected), actual);
    }

    private static <T> void assertSameElements(List<T> expected, List<T> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
    }

    private static void assertActive(DataReplicaState state, CopyTicket... tickets) {
        assertEquals(tickets.length, state.getActiveCopyCount());
        List<CopyTicket> actual = state.snapshot().getActiveCopies();
        assertSameElements(Arrays.asList(tickets), actual);
        long previous = 0;
        for (CopyTicket ticket : actual) {
            assertTrue(ticket.getOrdinal() > previous);
            previous = ticket.getOrdinal();
            assertSame(ticket, state.getInFlight(ticket.getFile().getId(), ticket.getDestination()));
        }
    }

    private static void assertCounts(DataReplicaState state, double clock, long admitted, long completed, CopyTicket... active) {
        assertEquals(clock, state.getObservedThrough(), 0);
        assertEquals(clock, state.snapshot().getObservedThrough(), 0);
        assertEquals(admitted, state.getAdmittedCopyCount());
        assertEquals(completed, state.getCompletedTaskAttemptCount());
        assertActive(state, active);
    }

    private static <T extends Throwable> void rejectsWithoutChange(Class<T> type, DataReplicaState state,
                                                                  DataflowFilePlan plan, Executable invalid) {
        Checkpoint before = new Checkpoint(state, plan);
        assertThrows(type, invalid);
        before.assertUnchanged(state);
    }

    /** Eager public-value copies prevent even an accidentally mutable Snapshot from hiding a change. */
    private static final class Checkpoint {
        private final Snapshot snapshot;
        private final double clock;
        private final long admitted;
        private final long completed;
        private final List<DataLocation> locations;
        private final List<CopyTicket> active;
        private final Map<FileId, List<Replica>> rows = new LinkedHashMap<>();
        private final Map<FileId, List<CopyTicket>> inFlight = new LinkedHashMap<>();

        private Checkpoint(DataReplicaState state, DataflowFilePlan plan) {
            snapshot = state.snapshot();
            clock = state.getObservedThrough();
            admitted = state.getAdmittedCopyCount();
            completed = state.getCompletedTaskAttemptCount();
            locations = new ArrayList<>(snapshot.getLocations());
            active = new ArrayList<>(snapshot.getActiveCopies());
            for (FileDefinition file : plan.getFiles()) {
                rows.put(file.getId(), new ArrayList<>(snapshot.getReplicas(file.getId())));
                List<CopyTicket> targets = new ArrayList<>();
                for (DataLocation location : locations) targets.add(state.getInFlight(file.getId(), location));
                inFlight.put(file.getId(), targets);
            }
        }

        private void assertUnchanged(DataReplicaState state) {
            Snapshot after = state.snapshot();
            assertEquals(clock, state.getObservedThrough(), 0);
            assertEquals(clock, snapshot.getObservedThrough(), 0);
            assertEquals(clock, after.getObservedThrough(), 0);
            assertEquals(admitted, state.getAdmittedCopyCount());
            assertEquals(completed, state.getCompletedTaskAttemptCount());
            assertEquals(active.size(), state.getActiveCopyCount());
            assertEquals(locations, snapshot.getLocations());
            assertEquals(locations, after.getLocations());
            assertSameElements(active, snapshot.getActiveCopies());
            assertSameElements(active, after.getActiveCopies());
            for (Map.Entry<FileId, List<Replica>> row : rows.entrySet()) {
                FileId file = row.getKey();
                assertSameElements(row.getValue(), snapshot.getReplicas(file));
                assertSameElements(row.getValue(), after.getReplicas(file));
                for (int i = 0; i < locations.size(); i++) {
                    DataLocation location = locations.get(i);
                    Replica expected = null;
                    for (Replica replica : row.getValue()) {
                        if (location.equals(replica.getLocation())) expected = replica;
                    }
                    assertSame(expected, state.getReplica(file, location));
                    assertSame(expected, snapshot.getReplica(file, location));
                    assertSame(expected, after.getReplica(file, location));
                    assertSame(inFlight.get(file).get(i), state.getInFlight(file, location));
                }
            }
        }
    }

    private static DataReplicaState state(DataflowFilePlan plan) {
        return new DataReplicaState(plan, Arrays.asList(SECOND, VM99, SOURCE, VM42, VM7));
    }

    private static DataflowFilePlan plan() {
        Task p = task(1, out("p", 4), out("zero-output", 0));
        Task q = task(2, out("q", 2));
        Task consumer = task(3, in("p", 4), in("q", 2), in("zero-output", 0), in("external", .5), in("zero-input", 0));
        p.addChild(consumer);
        consumer.addParent(p);
        q.addChild(consumer);
        consumer.addParent(q);
        return capture(Arrays.asList(p, q, consumer, task(4)));
    }

    private static DataflowFilePlan manyFilePlan(int count) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) tasks.add(task(i + 1, in("seed-" + i, 0), out("file-" + i, i + .25)));
        return capture(tasks);
    }

    private static DataflowFilePlan capture(List<Task> tasks) {
        Map<Integer, Integer> scopes = new LinkedHashMap<>();
        for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
        return DataflowFilePlan.capture(tasks, scopes);
    }

    private static Task task(int id, FileItem... files) {
        Task task = new Task(id, 1000);
        for (FileItem file : files) task.addFile(file);
        return task;
    }

    private static FileItem in(String name, double bytes) { return file(name, bytes, FileType.INPUT); }
    private static FileItem out(String name, double bytes) { return file(name, bytes, FileType.OUTPUT); }
    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }
    private static FileId id(String name) { return FileId.of(0, name); }
}
