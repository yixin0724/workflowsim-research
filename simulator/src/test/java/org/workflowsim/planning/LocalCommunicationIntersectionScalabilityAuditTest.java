package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Counts declaration reads, not elapsed time, in LOCAL PEFT's first lazy OCT fill.
 * File lists retain their order and multiplicity; counters never change returned values.
 * Expectations are literal two-level DAG arithmetic, not production rank/OCT helpers.
 * A fresh OCT fill may reread each edge after prepare: declarations remain mutable.
 */
class LocalCommunicationIntersectionScalabilityAuditTest {
    @Test
    void positiveEdgeFilesAreScannedOncePerOctFillRatherThanOncePerVmPair() {
        assertAll("positive payload, independently varied VM and declaration counts",
                () -> assertPairScanBound(4, 8, true),
                () -> assertPairScanBound(4, 32, true),
                () -> assertPairScanBound(16, 8, true),
                () -> assertPairScanBound(16, 32, true));
    }

    @Test
    void nonemptyZeroIntersectionIsAlsoRememberedWithinTheOctFill() {
        assertAll("positive declarations with no matching parent OUTPUT / child INPUT name",
                () -> assertPairScanBound(4, 8, false),
                () -> assertPairScanBound(4, 32, false),
                () -> assertPairScanBound(16, 8, false),
                () -> assertPairScanBound(16, 32, false));
    }

    @Test
    void branchingOctRemembersEachEdgeRatherThanOnlyTheLastVisitedChild() {
        Fixture f = fixture(16, 16, true);
        Task sibling = new Task(113, 5L);
        sibling.setFileList(new CountingFileList(f.access, Collections.<FileItem>emptyList()));
        f.parent.addChild(sibling);
        sibling.addParent(f.parent);
        for (int index = 0; index < 16; index++) {
            String name = "second-edge-" + index;
            f.parent.addFile(f.file(name, 125_000.0, FileType.OUTPUT));
            sibling.addFile(f.file(name, 125_000.0, FileType.INPUT));
        }
        f.planner.setTaskList(Arrays.asList(sibling, f.child, f.parent));
        f.planner.prepare();
        // Both edges carry 2 MB: upward = 2 + max(2 + 3, 2 + 5).
        assertExact(9.0, f.planner.upwardRankOf(f.parent), "branching upward rank");
        f.access.reset();

        assertExact(5.0, f.planner.priorityOf(f.parent), "max of successor minima, not their sum");
        for (CondorVM vm : f.machines) {
            assertExact(5.0, f.planner.optimisticCostOf(f.parent, vm), "branching OCT");
        }
        // Two edges, each scanning the 32-file parent and one 16-file child.
        // The old VM-pair loop visits 240 * (48 + 48) = 23,040 elements.
        assertScanBudget(f.access, 96L, 32L, 4L, "16 VMs, two positive edges");
    }

    @Test
    void sameVmIdentitySkipsFileReadsEvenForDistinctVmObjectsWithTheSameId() {
        Fixture f = fixture(2, 32, true);
        f.planner.prepare();
        CondorVM first = f.machines.get(0);
        CondorVM sameId = vm(first.getId(), 1, 1L);
        f.access.reset();

        assertExact(0.0, f.planner.communicationSeconds(f.parent, f.child, first, first), "same VM");
        assertExact(0.0, f.planner.communicationSeconds(f.parent, f.child, first, sameId), "same VM ID");
        assertNoFileReads(f.access, "same-ID communication must retain its early return");
    }

    @Test
    void noFeasibleCrossVmPairDoesNotAddAnOctDeclarationScan() {
        Fixture f = new Fixture(Arrays.asList(vm(3, 1, 1L), vm(17, 2, 2L), vm(41, 4, 4L)));
        f.parent.setNumberOfPes(4);
        f.child.setNumberOfPes(4);
        f.addEdgeFile("edge", 1_000_000.0);
        f.planner.prepare();
        assertExact(5.0, f.planner.upwardRankOf(f.parent), "only VM41 is compatible with either task");
        f.access.reset();

        assertExact(3.0, f.planner.priorityOf(f.parent), "single compatible OCT entry");
        assertExact(Double.POSITIVE_INFINITY,
                f.planner.optimisticCostOf(f.parent, f.machines.get(0)), "incompatible parent entry");
        assertExact(Double.POSITIVE_INFINITY,
                f.planner.optimisticCostOf(f.child, f.machines.get(1)), "incompatible child entry");
        assertExact(3.0, f.planner.optimisticCostOf(f.parent, f.machines.get(2)), "same compatible VM");
        assertNoFileReads(f.access, "no compatible off-diagonal OCT candidate needs edge bytes");
    }

    @Test
    void heterogeneousPeDomainsBandwidthsAndLowerVmIdTiesKeepTheirLiteralOracle() {
        Fixture f = new Fixture(Arrays.asList(
                vm(3, 1, 1L), vm(17, 2, 2L), vm(41, 4, 4L), vm(89, 4, 8L)));
        f.parent.setNumberOfPes(2);
        f.child.setNumberOfPes(4);
        f.addEdgeFile("edge", 12_000_000.0);
        f.planner.prepare();
        // Feasible cross pairs: (17,41),(17,89),(41,89),(89,41), costs 6,6,3,3.
        assertExact(4.5, f.planner.meanCommunicationSeconds(f.parent, f.child), "compatible pair mean");
        assertExact(9.5, f.planner.upwardRankOf(f.parent), "2 + 4.5 + 3");
        assertExact(5.0, f.planner.priorityOf(f.parent), "(9 + 3 + 3) / 3");
        double[] expectedParent = {Double.POSITIVE_INFINITY, 9.0, 3.0, 3.0};
        double[] expectedChild = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 0.0};
        for (int index = 0; index < f.machines.size(); index++) {
            assertExact(expectedParent[index], f.planner.optimisticCostOf(f.parent, f.machines.get(index)),
                    "parent OCT column " + index);
            assertExact(expectedChild[index], f.planner.optimisticCostOf(f.child, f.machines.get(index)),
                    "child OCT column " + index);
        }
        assertExact(0.0, f.planner.priorityOf(f.child), "exit rank excludes incompatible infinities");

        f.planner.run();
        assertEquals(41, f.parent.getVmId(), "VM41 and VM89 tie in OEFT; choose lower compatible ID");
        assertEquals(41, f.child.getVmId(), "same-VM successor avoids the positive edge transfer");
        assertExact(110.1, f.parent.getStaticScheduleStartTime(), "bootstrap");
        assertExact(112.1, f.child.getStaticScheduleStartTime(), "parent finish");
        assertExact(115.1, f.planner.plannedFinishOf(f.child).doubleValue(), "child finish");
    }

    @Test
    void duplicateOutputsAreMembershipButEveryChildInputStillContributes() {
        Fixture f = new Fixture(Arrays.asList(vm(3, 1, 2L), vm(17, 1, 4L)));
        setCosts(f.parent, f.machines, 1.0, 1.0);
        setCosts(f.child, f.machines, 1.0, 100.0);
        FileItem output = f.file("f", 1.25, FileType.OUTPUT);
        f.parent.addFile(output);
        f.parent.addFile(output);
        f.parent.addFile(f.file("g", 0.5, FileType.OUTPUT));
        FileItem input = f.file("f", 1.25, FileType.INPUT);
        f.child.addFile(input);
        f.child.addFile(input);
        f.child.addFile(f.file("f", 1.25, FileType.OUTPUT));
        f.child.addFile(f.file("g", 0.5, FileType.INPUT));
        f.child.addFile(f.file("external", 7.0, FileType.INPUT));
        f.planner.prepare();

        // Byte intersection is 1.25 + 1.25 + 0.5. Do not deduplicate child inputs,
        // multiply by output occurrences, or import isRealInputFile's stage-in exclusion.
        double expected = 3.0 / 1.0e6 / 2.0;
        assertExact(expected, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "positive fractional duplicate payload");
        assertExact(expected, f.planner.meanCommunicationSeconds(f.parent, f.child), "duplicate pair mean");
        assertExact(0.0, f.planner.communicationSeconds(f.child, f.parent,
                f.machines.get(0), f.machines.get(1)), "task pairs are directed");
        assertExact(expected, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(1), f.machines.get(0)), "VM direction does not change endpoint minimum");
        assertExact((1.0 + (1.0 + expected)) / 2.0, f.planner.priorityOf(f.parent), "duplicate-aware OCT rank");
        assertExact(1.0 + expected, f.planner.optimisticCostOf(f.parent, f.machines.get(1)),
                "OCT uses every child INPUT occurrence too");
    }

    @Test
    void positiveInputAdditionOrderRemainsBitExactAndDirectQueriesStillReadCurrentLists() {
        Fixture f = new Fixture(Arrays.asList(vm(3, 1, 2L), vm(17, 1, 4L)));
        setCosts(f.parent, f.machines, 1.0, 1.0);
        setCosts(f.child, f.machines, 1.0, 10_000_000_000.0);
        f.parent.addFile(f.file("large", 0x1.0p53, FileType.OUTPUT));
        f.parent.addFile(f.file("unit", 1.0, FileType.OUTPUT));
        FileItem largeInput = f.file("large", 0x1.0p53, FileType.INPUT);
        FileItem unitInput = f.file("unit", 1.0, FileType.INPUT);
        f.child.addFile(largeInput);
        f.child.addFile(unitInput);
        f.child.addFile(unitInput);
        f.planner.prepare();

        // ((2^53 + 1) + 1) rounds to 2^53; ((1 + 1) + 2^53) is 2^53 + 2.
        double largeFirst = 0x1.0p53 / 1.0e6 / 2.0;
        double smallFirst = (0x1.0p53 + 2.0) / 1.0e6 / 2.0;
        assertNotEquals(Double.doubleToLongBits(largeFirst), Double.doubleToLongBits(smallFirst));
        assertExact(largeFirst, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "original child iteration order");
        f.planner.priorityOf(f.parent);
        assertExact(1.0 + largeFirst, f.planner.optimisticCostOf(f.parent, f.machines.get(1)), "original OCT sum");
        f.child.setFileList(new CountingFileList(f.access, Arrays.asList(unitInput, unitInput, largeInput)));
        assertExact(smallFirst, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "live direct query after list replacement");
        assertExact(1.0 + largeFirst, f.planner.optimisticCostOf(f.parent, f.machines.get(1)), "filled OCT is unchanged");
        f.planner.prepare();
        assertExact(smallFirst, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "reprepared child iteration order");
        f.planner.priorityOf(f.parent);
        assertExact(1.0 + smallFirst, f.planner.optimisticCostOf(f.parent, f.machines.get(1)), "reprepared OCT sum");
    }

    @Test
    void zeroSizedMatchingDeclarationsRemainAnAcceptedZeroPayloadControl() {
        Fixture f = fixture(2, 1, true);
        f.parent.getFileList().get(0).setSize(0.0);
        f.child.getFileList().get(0).setSize(0.0);
        f.planner.prepare();

        assertExact(0.0, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "zero payload");
        assertExact(5.0, f.planner.upwardRankOf(f.parent), "2 + 0 + 3");
        assertExact(3.0, f.planner.priorityOf(f.parent), "zero communication successor cost");
    }

    @Test
    void firstLazyOctReadsChangedSizesButAlreadyFilledOctKeepsItsExistingLifetime() {
        Fixture f = asymmetricFixture();
        FileItem output = f.parent.getFileList().get(0);
        FileItem input = f.child.getFileList().get(0);
        f.planner.prepare();
        assertExact(52.5, f.planner.upwardRankOf(f.parent), "prepared with one MB");
        assertThrows(IllegalStateException.class,
                () -> f.planner.optimisticCostOf(f.parent, f.machines.get(0)), "OCT is still lazy");

        output.setSize(9_000_000.0);
        input.setSize(9_000_000.0);
        assertExact(9.0, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "live byte intersection");
        assertExact(9.0, f.planner.meanCommunicationSeconds(f.parent, f.child), "live mean communication");
        assertExact(52.5, f.planner.upwardRankOf(f.parent), "upward rank remains prepared");
        assertExact(5.5, f.planner.priorityOf(f.parent), "first OCT uses [1, 10], not [1, 2]");
        assertExact(1.0, f.planner.optimisticCostOf(f.parent, f.machines.get(0)), "first OCT column");
        assertExact(10.0, f.planner.optimisticCostOf(f.parent, f.machines.get(1)), "second OCT column");

        output.setSize(17_000_000.0);
        input.setSize(17_000_000.0);
        assertExact(17.0, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "direct query remains live after OCT fill");
        assertExact(5.5, f.planner.priorityOf(f.parent), "do not silently change the existing OCT lifetime");
        assertExact(10.0, f.planner.optimisticCostOf(f.parent, f.machines.get(1)), "previously filled OCT");
        f.planner.prepare();
        assertExact(68.5, f.planner.upwardRankOf(f.parent), "reprepare uses seventeen MB");
        assertExact(9.5, f.planner.priorityOf(f.parent), "reprepared OCT is [1, 18]");
    }

    @Test
    void replacingBothFileListsBeforeFirstOctRefreshesMembershipWithoutRepreparingRanks() {
        Fixture f = asymmetricFixture();
        f.planner.prepare();
        f.parent.setFileList(new CountingFileList(f.access,
                Collections.singletonList(f.file("replacement", 9_000_000.0, FileType.OUTPUT))));
        f.child.setFileList(new CountingFileList(f.access,
                Collections.singletonList(f.file("replacement", 9_000_000.0, FileType.INPUT))));

        assertExact(52.5, f.planner.upwardRankOf(f.parent), "unchanged prepared upward rank");
        assertExact(5.5, f.planner.priorityOf(f.parent), "first OCT sees replacement declarations");
        f.planner.prepare();
        assertExact(60.5, f.planner.upwardRankOf(f.parent), "new prepared upward rank");
        assertExact(5.5, f.planner.priorityOf(f.parent), "new prepared OCT");
    }

    @Test
    void nameAndTypeChangesBeforeFirstOctAreNotHiddenByPreparedIntersectionValues() {
        Fixture renamed = asymmetricFixture();
        renamed.planner.prepare();
        renamed.child.getFileList().get(0).setName("no-longer-matching");
        assertExact(1.0, renamed.planner.priorityOf(renamed.parent), "renamed input removes communication");
        assertExact(52.5, renamed.planner.upwardRankOf(renamed.parent), "prepared rank is not recomputed");

        Fixture retyped = asymmetricFixture();
        retyped.planner.prepare();
        retyped.parent.getFileList().get(0).setType(FileType.INPUT);
        assertExact(1.0, retyped.planner.priorityOf(retyped.parent), "only OUTPUT names contribute");
        retyped.planner.prepare();
        assertExact(51.5, retyped.planner.upwardRankOf(retyped.parent), "reprepared zero intersection");
        assertExact(1.0, retyped.planner.priorityOf(retyped.parent), "reprepared zero-communication OCT");
    }

    @Test
    void reprepareRefreshesChangedFilesAndUsesEquivalentNewVmObjectsAsOctKeys() {
        Fixture f = asymmetricFixture();
        f.planner.prepare();
        assertExact(1.5, f.planner.priorityOf(f.parent), "initial OCT [1, 2]");
        f.parent.getFileList().get(0).setSize(9_000_000.0);
        f.child.getFileList().get(0).setSize(9_000_000.0);
        CondorVM replacementFirst = vm(f.machines.get(0).getId(), 1, 1L);
        CondorVM replacementSecond = vm(f.machines.get(1).getId(), 1, 1L);
        f.planner.setVmList(Arrays.asList(replacementSecond, replacementFirst));
        f.planner.prepare();

        assertThrows(IllegalStateException.class,
                () -> f.planner.optimisticCostOf(f.parent, replacementSecond), "reprepare clears the OCT table");
        assertExact(60.5, f.planner.upwardRankOf(f.parent), "new file generation");
        assertExact(5.5, f.planner.priorityOf(f.parent), "new OCT generation");
        assertExact(1.0, f.planner.optimisticCostOf(f.parent, replacementFirst), "replacement first key");
        assertExact(10.0, f.planner.optimisticCostOf(f.parent, replacementSecond), "replacement second key");
    }

    @Test
    void failedPreparationDoesNotPoisonTheNextSuccessfulFileAndOctGeneration() {
        Fixture f = asymmetricFixture();
        f.planner.prepare();
        assertExact(1.5, f.planner.priorityOf(f.parent), "initial OCT");
        f.child.getFileList().get(0).setSize(2_000_000.0);
        assertThrows(IllegalStateException.class, f.planner::prepare, "inconsistent sizes still fail");
        // Do not invent a diagnostic contract for the failed intermediate preparation.
        f.parent.getFileList().get(0).setSize(9_000_000.0);
        f.child.getFileList().get(0).setSize(9_000_000.0);
        f.planner.prepare();
        assertExact(60.5, f.planner.upwardRankOf(f.parent), "recovered prepared rank");
        assertExact(5.5, f.planner.priorityOf(f.parent), "recovered OCT");
    }

    @Test
    void staticPairBytesDoNotBecomeLocalWhenAllocationRegistersAReplica() {
        Fixture f = fixture(2, 1, true);
        f.parent.getFileList().get(0).setSize(4_000_000.0);
        f.child.getFileList().get(0).setSize(4_000_000.0);
        f.planner.prepare();
        assertExact(3.0, f.planner.priorityOf(f.parent), "static OCT before allocation");
        assertExact(4.0, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "static pair communication before allocation");

        f.planner.allocate(f.parent, f.machines.get(0), 110.1);
        f.planner.allocate(f.child, f.machines.get(1), 110.1);
        assertExact(116.1, f.child.getStaticScheduleStartTime(), "112.1 parent finish plus four-second transfer");
        assertExact(119.1, f.planner.plannedFinishOf(f.child).doubleValue(), "child computation after transfer");
        assertExact(4.0, f.planner.communicationSeconds(f.parent, f.child,
                f.machines.get(0), f.machines.get(1)), "static estimate must not cache dynamic locality");
        assertExact(3.0, f.planner.priorityOf(f.parent), "allocation does not alter the filled OCT");
    }

    private static void assertPairScanBound(int vmCount, int fileCount, boolean matches) {
        Fixture f = fixture(vmCount, fileCount, matches);
        f.planner.prepare();
        double communication = matches ? (fileCount * 125_000.0) / 1.0e6 / 1.0 : 0.0;
        assertExact(2.0 + (communication + 3.0), f.planner.upwardRankOf(f.parent), "prepared edge rank");
        f.access.reset(); // Exclude the separate preparation/replica-assembly scans.

        assertExact(3.0, f.planner.priorityOf(f.parent), "same-VM successor is the exact minimum");
        for (CondorVM machine : f.machines) {
            assertExact(3.0, f.planner.optimisticCostOf(f.parent, machine), "parent OCT");
            assertExact(0.0, f.planner.optimisticCostOf(f.child, machine), "exit OCT");
        }
        // Before optimization: V*(V-1)*2F element/type/name reads. For V=16,F=32
        // this is 15,360 element reads, versus a bound of 64 for one fresh edge read.
        assertScanBudget(f.access, 2L * fileCount, matches ? fileCount : 0L, 2L,
                "VMs=" + vmCount + ", files per endpoint=" + fileCount + ", matching=" + matches);
    }

    private static void assertScanBudget(AccessCounter access, long elementBudget,
            long sizeBudget, long iteratorBudget, String subject) {
        final String observed = subject + "; observed " + access;
        System.out.println("LOCAL_INTERSECTION_AUDIT " + observed);
        assertAll(observed,
                () -> assertTrue(access.elements <= elementBudget, "list elements <= " + elementBudget + "; " + observed),
                () -> assertTrue(access.types <= elementBudget, "file type reads <= " + elementBudget + "; " + observed),
                () -> assertTrue(access.names <= elementBudget, "file name reads <= " + elementBudget + "; " + observed),
                () -> assertTrue(access.sizes <= sizeBudget, "file size reads <= " + sizeBudget + "; " + observed),
                () -> assertTrue(access.iterators <= iteratorBudget, "file iterators <= " + iteratorBudget + "; " + observed));
    }

    private static void assertNoFileReads(AccessCounter access, String subject) {
        assertEquals(0L, access.elements + access.types + access.names + access.sizes + access.iterators,
                subject + "; observed " + access);
    }

    private static void assertExact(double expected, double actual, String subject) {
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual),
                subject + ": expected " + expected + ", actual " + actual);
    }

    private static Fixture fixture(int vmCount, int fileCount, boolean matches) {
        List<CondorVM> machines = new ArrayList<CondorVM>();
        for (int index = 0; index < vmCount; index++) {
            machines.add(vm(7 + 13 * index, 1, 1L));
        }
        Fixture f = new Fixture(machines);
        for (int index = 0; index < fileCount; index++) {
            f.parent.addFile(f.file("edge-" + index, 125_000.0, FileType.OUTPUT));
            f.child.addFile(f.file((matches ? "edge-" : "external-") + index, 125_000.0, FileType.INPUT));
        }
        return f;
    }

    private static Fixture asymmetricFixture() {
        Fixture f = fixture(2, 1, true);
        f.parent.getFileList().get(0).setSize(1_000_000.0);
        f.child.getFileList().get(0).setSize(1_000_000.0);
        setCosts(f.parent, f.machines, 1.0, 1.0);
        setCosts(f.child, f.machines, 1.0, 100.0);
        return f;
    }

    private static void setCosts(Task task, List<CondorVM> machines, double... seconds) {
        assertEquals(machines.size(), seconds.length);
        Map<Integer, Double> values = new LinkedHashMap<Integer, Double>();
        for (int index = 0; index < machines.size(); index++) {
            values.put(Integer.valueOf(machines.get(index).getId()), Double.valueOf(seconds[index]));
        }
        task.setVmExecutionCostSeconds(values);
    }

    private static CondorVM vm(int id, int pes, long bandwidth) {
        return new CondorVM(id, 0, 1.0, pes, 512, bandwidth, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }

    private static final class Fixture {
        final AccessCounter access = new AccessCounter();
        final Task parent = new Task(71, 2L);
        final Task child = new Task(19, 3L);
        final List<CondorVM> machines;
        final LocalPeftPlanningAlgorithm planner;

        Fixture(List<CondorVM> machines) {
            this.machines = new ArrayList<CondorVM>(machines);
            parent.addChild(child);
            child.addParent(parent);
            parent.setFileList(new CountingFileList(access, Collections.<FileItem>emptyList()));
            child.setFileList(new CountingFileList(access, Collections.<FileItem>emptyList()));
            PlatformProfile.Builder platform = PlatformProfile.builder("local-intersection-counting-audit");
            for (int index = 0; index < machines.size(); index++) {
                CondorVM vm = machines.get(index);
                platform.addHost(new PlatformProfile.HostSpec(index, vm.getNumberOfPes(), 1.0,
                        2048, 10_000L, 1_000_000L));
                platform.addVm(new PlatformProfile.VmSpec(vm.getId(), 1.0, vm.getNumberOfPes(),
                        512, vm.getBw(), 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
                platform.pinVmToHost(vm.getId(), index);
            }
            SimulationConfig config = SimulationConfig.builder("not-parsed-local-intersection.dax", machines.size())
                    .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                    .cloudSimMinEventIntervalSeconds(0.1).build();
            planner = new LocalPeftPlanningAlgorithm(new PlanningContext(config, platform.build()));
            planner.setTaskList(Arrays.asList(child, parent)); // Deliberately not topological.
            List<CondorVM> reversed = new ArrayList<CondorVM>(machines);
            Collections.reverse(reversed);
            planner.setVmList(reversed); // IDs, not caller list order, govern ties.
        }

        FileItem file(String name, double bytes, FileType type) {
            return new CountingFileItem(access, name, bytes, type);
        }

        void addEdgeFile(String name, double bytes) {
            parent.addFile(file(name, bytes, FileType.OUTPUT));
            child.addFile(file(name, bytes, FileType.INPUT));
        }
    }

    /** Mutable list adapter: observe the same element visits without replacing file semantics. */
    private static final class CountingFileList extends AbstractList<FileItem> {
        private final AccessCounter access;
        private final List<FileItem> values;

        CountingFileList(AccessCounter access, List<FileItem> values) {
            this.access = access;
            this.values = new ArrayList<FileItem>(values);
        }

        @Override
        public FileItem get(int index) {
            FileItem value = values.get(index);
            access.elements++;
            return value;
        }

        @Override
        public int size() {
            return values.size();
        }

        @Override
        public Iterator<FileItem> iterator() {
            access.iterators++;
            return super.iterator();
        }

        @Override
        public void add(int index, FileItem value) {
            values.add(index, value);
            modCount++;
        }

        @Override
        public FileItem set(int index, FileItem value) {
            return values.set(index, value);
        }

        @Override
        public FileItem remove(int index) {
            FileItem removed = values.remove(index);
            modCount++;
            return removed;
        }
    }

    private static final class CountingFileItem extends FileItem {
        private final AccessCounter access;

        CountingFileItem(AccessCounter access, String name, double bytes, FileType type) {
            super(name, bytes);
            this.access = access;
            setType(type);
        }

        @Override
        public String getName() {
            access.names++;
            return super.getName();
        }

        @Override
        public double getSize() {
            access.sizes++;
            return super.getSize();
        }

        @Override
        public FileType getType() {
            access.types++;
            return super.getType();
        }
    }

    private static final class AccessCounter {
        long elements;
        long types;
        long names;
        long sizes;
        long iterators;

        void reset() {
            elements = 0L;
            types = 0L;
            names = 0L;
            sizes = 0L;
            iterators = 0L;
        }

        @Override
        public String toString() {
            return "elements=" + elements + ", types=" + types + ", names=" + names
                    + ", sizes=" + sizes + ", iterators=" + iterators;
        }
    }
}
