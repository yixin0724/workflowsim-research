package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.TaskExecutionModel;

/** Literal independent oracles plus real runtime/assigner captures; no Kernel or filesystem needed. */
class DataflowAssignmentValidatorTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1";

    @Test void handmadeNoInputTieHasOneImmutableFullRecord() {
        Fixture f = literal(false); DataflowAssignmentValidator audit = f.audit();
        for (JsonElement row : f.main) audit.acceptEvent(row.getAsJsonObject());
        DataflowAssignmentValidator.Result result = audit.finish();
        assertEquals(SCOPE, result.getScope()); assertEquals(1, result.getAssignmentCount());
        assertEquals(1, result.getInitialBindingCount()); assertEquals(0, result.getRetryReuseCount());
        assertEquals(f.main.get(0), result.getAssignments().get(0)); assertEquals(8, result.getAssignments().get(0).getAsJsonObject().size());
        result.getAssignments().get(0).getAsJsonObject().getAsJsonObject("attributes").addProperty("selectedVmId", 999);
        assertEquals(7, result.getAssignments().get(0).getAsJsonObject().get("vmId").getAsInt());
        assertSame(result, audit.finish()); assertThrows(IllegalStateException.class, () -> audit.acceptEvent(f.main.get(0).getAsJsonObject()));
    }

    @Test void globalMainSequenceZeroIsNotThePositiveAssignmentSequence() {
        Fixture f=literal(false);DataflowAssignmentValidator audit=f.audit();JsonObject first=new JsonObject();first.addProperty("sequence",0);first.addProperty("simulationTime",0);first.addProperty("type","WORKFLOW_PARSED");for(String key:new String[]{"jobId","vmId","classType"})first.add(key,com.google.gson.JsonNull.INSTANCE);first.add("taskIds",new JsonArray());first.add("attributes",new JsonObject());audit.acceptEvent(first);for(JsonElement event:f.main)audit.acceptEvent(event.getAsJsonObject());assertEquals(1,audit.finish().getAssignmentCount());
    }

    @Test void handmadeNewCopyUsesFullBytesOverIndependentRate() {
        Fixture f = literal(true); assertEquals(10, candidate(f, 10, 7).get("inputSeconds").getAsDouble());
        assertEquals(1, f.validate().getAssignmentCount());
        Fixture wrong = f.copy(); setInput(wrong, 10, 7, Math.nextDown(10.0)); bad(wrong);
        wrong = f.copy(); setInput(wrong, 10, 42, Math.nextUp(10.0)); bad(wrong);
    }

    @Test void realAssignerAndRuntimeCoverAllFourKindsAndBothStoragePolicies() {
        for (boolean storage : new boolean[]{false, true}) for (boolean shared : new boolean[]{false, true})
            for (boolean gate : storage ? new boolean[]{false, true} : new boolean[]{false}) {
                Task p = task(1, 1000, in("external", 100), out("x", 100), out("zero", 0), out("unused", 50));
                Task c = task(2, 1000, in("x", 100), in("x", 100), in("zero", 0)); edge(p, c);
                Run run = new Run(storage, shared, gate, p, c);
                Job first = run.prepare(10, p, 0); run.drain(); run.completeReady(first, true);
                Job second = run.prepare(20, c, run.now()); run.drain(); run.completeReady(second, true); run.drain();
                Fixture f = run.fixture(); assertEquals(2, f.validate().getAssignmentCount());
                assertEquals(storage, observation(f, 20).get("storageVersion").getAsBoolean());
                assertEquals(gate, observation(f, 20).get("storeBackedInputs").getAsBoolean());
                assertEquals(shared, observation(f, 20).get("interFlowSharing").getAsBoolean());
                assertEquals(2, observation(f, 20).get("scopedFileCount").getAsInt(), "unique files, not three references");
                assertEquals(storage ? 2 : 0, observation(f, 20).get("pendingOutputFileCount").getAsInt());
            }
    }

    @Test void zeroAcquisitionAndSubsequentLocalHitNeedNoCopyOrPositiveDelay() {
        Task a = task(1, 1000, in("empty", 0)), b = task(2, 1000, in("empty", 0));
        Run r = new Run(false, true, false, a, b); r.completeReady(r.prepare(10, a, 0), true);
        r.completeReady(r.prepare(20, b, r.now()), true); Fixture f = r.fixture();
        assertEquals("ZERO", lifePayload(f, "INPUT_RESOLVED", 0).get("resolution").getAsString());
        assertEquals("LOCAL", lifePayload(f, "INPUT_RESOLVED", 1).get("resolution").getAsString());
        assertEquals(0, candidate(f, 20, 7).get("inputSeconds").getAsDouble()); assertEquals(2, f.validate().getAssignmentCount());
        Fixture wrong = f.copy(); setInput(wrong, 20, 7, 1); bad(wrong);
    }

    @Test void currentProducerVmCacheIsVisibleOnlyAfterSuccessfulCompletion() {
        Task p = task(1, 1000, out("x", 100)), c = task(2, 1000, in("x", 100)); edge(p, c);
        Run r = new Run(false, true, false, p, c); r.completeReady(r.prepare(10, p, 0), true);
        r.completeReady(r.prepare(20, c, r.now()), true); Fixture f = r.fixture();
        assertEquals(0, candidate(f, 20, 7).get("inputSeconds").getAsDouble());
        assertEquals(10, candidate(f, 20, 42).get("inputSeconds").getAsDouble()); assertEquals(2, f.validate().getAssignmentCount());
        Fixture wrong = f.copy(); setInput(wrong, 20, 42, 0); bad(wrong); // The final remote candidate is not a present cache.
    }

    @Test void activeInputJoinsUseFrozenNominalBoundsAndPreRequestCounts() {
        Fixture f = joined(); assertEquals(2, f.validate().getAssignmentCount());
        assertEquals(1, observation(f, 20).get("activeCopyCount").getAsInt());
        assertEquals(1, observation(f, 20).get("activeJobCount").getAsInt());
        assertEquals(1, candidate(f, 20, 7).get("joinedInputCopies").getAsInt());
        assertEquals(5, candidate(f, 20, 7).get("inputSeconds").getAsDouble());
        assertEquals("JOIN_EXISTING", lifePayload(f, "INPUT_RESOLVED", 1).get("resolution").getAsString());
    }

    @Test void withinBoundsProgressDriftIsExplicitlyPermittedByTheAuditScope() {
        Fixture f = joined(); setInput(f, 20, 7, 8); // Runtime logged 5; 8 is not a replay of its real remaining balance.
        DataflowAssignmentValidator.Result result = f.validate();
        assertEquals(SCOPE, result.getScope());
        assertEquals(8, result.getAssignments().get(1).getAsJsonObject().getAsJsonObject("attributes")
                .getAsJsonArray("candidates").get(0).getAsJsonObject().get("inputSeconds").getAsDouble());
    }

    @Test void activeInputOutsideFullFrozenBoundAndFalseZeroAreRejected() {
        for (double value : new double[]{0, Math.nextUp(10.0)}) {
            Fixture wrong = joined(); setInput(wrong, 20, 7, value); bad(wrong);
        }
        Fixture upper = joined(); setInput(upper, 20, 7, 10); assertEquals(2, upper.validate().getAssignmentCount());
    }

    @Test void sharedStoreGateIncludesProducerCacheAndPostCommitReadBounds() {
        Fixture f = conditional(); assertEquals(2, f.validate().getAssignmentCount());
        assertEquals(10, candidate(f, 20, 7).get("inputSeconds").getAsDouble());
        assertEquals(15, candidate(f, 20, 42).get("inputSeconds").getAsDouble());
        assertTrue(candidate(f, 20, 7).get("conditionalStoreWait").getAsBoolean());
        assertEquals(0, candidate(f, 20, 7).get("joinedInputCopies").getAsInt());
        assertEquals(1, observation(f, 20).get("pendingOutputFileCount").getAsInt());
        Fixture wrong = f.copy(); setInput(wrong, 20, 7, 0); bad(wrong);
        wrong = f.copy(); setInput(wrong, 20, 42, Math.nextDown(5.0)); bad(wrong);
        wrong = f.copy(); setInput(wrong, 20, 42, Math.nextUp(15.0)); bad(wrong);
    }

    @Test void positivePostReadLowerBoundIsInclusiveNotFalselyStrict() {
        Fixture f = conditional(); setInput(f, 20, 7, 1); setInput(f, 20, 42, 5);
        assertEquals(2, f.validate().getAssignmentCount(), "a positive write may be absorbed by binary64 addition");
    }

    @Test void realBinary64ConditionalWriteCanBeAbsorbedByHugePostRead() {
        Task p = task(1, 1000, out("x", 100)), c = task(2, 1000, in("x", 100)); edge(p, c);
        DataTransferFabric fabric = storageFabric(1000, 1000, 1e-14, 1000, 1000, false);
        Run r = new Run(true, false, true, fabric, 250, 250, 1, 1, p, c);
        Job producer = r.prepare(10, p, 0); r.start(producer, 0); r.finish(producer, 1, true);
        Job consumer = r.prepare(20, c, 1); r.drain(); r.completeReady(consumer, true);
        Fixture f = r.fixture(); double read = 100 / (f.life.getAsJsonObject("fabric").getAsJsonObject("sourceStorage")
                .get("readBandwidthMbPerSecond").getAsDouble() * 1_000_000.0);
        assertEquals(read, candidate(f, 20, 42).get("inputSeconds").getAsDouble());
        assertEquals(2, f.validate().getAssignmentCount());
    }

    @Test void committedSharedInputsUseSourceOnlyWhileLocalPolicyUsesFasterVisiblePeer() {
        for (boolean gate : new boolean[]{false, true}) {
            Task p = task(1, 1000, out("x", 100)), c = task(2, 1000, in("x", 100)); edge(p, c);
            Run r = new Run(true, false, gate, storageFabric(100, 100, 20, 10, 100, false), 1000, 1000, 1, 1, p, c);
            r.completeReady(r.prepare(10, p, 0), true); r.drain();
            r.completeReady(r.prepare(20, c, r.now()), true); Fixture f = r.fixture();
            assertEquals(gate ? 5 : 1, candidate(f, 20, 42).get("inputSeconds").getAsDouble());
            assertFalse(candidate(f, 20, 42).get("conditionalStoreWait").getAsBoolean());
            assertEquals(0, observation(f, 20).get("pendingOutputFileCount").getAsInt());
            assertEquals(2, f.validate().getAssignmentCount());
            setInput(f, 20, 42, gate ? 1 : 5); bad(f);
        }
    }

    @Test void storageRetryPublishesOnlyTheSuccessfulAttemptAndKeepsItsBinding() {
        for (boolean shared : new boolean[]{false, true}) for (boolean gate : new boolean[]{false, true}) {
            Task p = task(1, 1000, out("x", 100)); Run r = new Run(true, shared, gate, p);
            r.completeReady(r.prepare(10, p, 0), false);
            r.completeReady(r.retry(20, 10, task(1, 1000, out("x", 100)), r.now()), true); r.drain();
            Fixture f = r.fixture(); assertEquals(1, f.validate().getRetryReuseCount());
            assertEquals(20, lifePayload(f, "COPY_ADMITTED", 0).get("ownerJobId").getAsInt());
            assertEquals(7, action(f, 20).get("vmId").getAsInt());
        }
    }

    @Test void sourceGateAndJoinFlagsCannotBeForged() {
        Fixture f = conditional(); candidate(f, 20, 7).addProperty("conditionalStoreWait", false); bad(f);
        f = joined(); candidate(f, 20, 7).addProperty("conditionalStoreWait", true); bad(f);
        f = joined(); candidate(f, 20, 7).addProperty("joinedInputCopies", 0); bad(f);
        f = conditional(); candidate(f, 20, 7).addProperty("joinedInputCopies", 1); bad(f);
    }

    @Test void inputMaxIsNotSumOrExpandedReferenceMultiplicity() {
        Task t = task(1, 1000, in("a", 100), in("a", 100), in("b", 200));
        Run r = new Run(false, false, false, t); Job j = r.prepare(10, t, 0); r.drain(); r.completeReady(j, true);
        Fixture f = r.fixture(); assertEquals(20, candidate(f, 10, 7).get("inputSeconds").getAsDouble());
        assertEquals(2, observation(f, 10).get("scopedFileCount").getAsInt()); assertEquals(1, f.validate().getAssignmentCount());
        setInput(f, 10, 7, 40); bad(f);
    }

    @Test void aFixedFileLowerBoundDominatesAnUnknownJoinedRemainingBound() {
        Task a = task(1, 1000, in("x", 100)), b = task(2, 1000, in("x", 100), in("y", 200));
        Run r = new Run(false, false, false, a, b); Job first = r.prepare(10, a, 0); Job second = r.prepare(20, b, 5);
        r.drain(); r.completeReady(first, true); r.completeReady(second, true); Fixture f = r.fixture();
        assertEquals(20, candidate(f, 20, 7).get("inputSeconds").getAsDouble()); assertEquals(2, f.validate().getAssignmentCount());
        setInput(f, 20, 7, Math.nextDown(20.0)); bad(f);
    }

    @Test void startedBusyCpuReservationsSubtractOnlyObservedElapsedTime() {
        Task a = task(1, 10000), b = task(2, 10000), c = task(3, 1000);
        Run r = new Run(false, true, false, a, b, c); Job first = r.prepare(10, a, 0); r.start(first, 0);
        Job second = r.prepare(20, b, 0); r.start(second, 0); Job third = r.prepare(30, c, 9.5);
        r.finish(first, 10, true); r.finish(second, 10, true); r.start(third, 10); r.finish(third, 11, true);
        Fixture f = r.fixture(); assertEquals(10, candidate(f, 30, 7).get("cpuAvailableAt").getAsDouble());
        assertEquals(10, candidate(f, 30, 42).get("cpuAvailableAt").getAsDouble()); assertEquals(3, f.validate().getAssignmentCount());
        candidate(f, 30, 7).addProperty("cpuAvailableAt", 9.5); rescore(f, 30, 7); bad(f);
    }

    @Test void notStartedReservationsKeepTheirFullComputeRegardlessOfWaitingTime() {
        Task a = task(1, 1000), b = task(2, 1000); Run r = new Run(false, true, false, a, b);
        Job first = r.prepare(10, a, 0), second = r.prepare(20, b, 5);
        r.start(first, 5); r.finish(first, 6, true); r.completeReady(second, true); Fixture f = r.fixture();
        assertEquals(6, candidate(f, 20, 7).get("cpuAvailableAt").getAsDouble()); assertEquals(42, action(f, 20).get("vmId").getAsInt());
        assertEquals(2, f.validate().getAssignmentCount());
    }

    @Test void startedReservationClampsToZeroWithoutPretendingJobHasCompleted() {
        Task a = task(1, 1000), b = task(2, 1000); Run r = new Run(false, true, false, a, b);
        Job first = r.prepare(10, a, 0); r.start(first, 0); Job second = r.prepare(20, b, 5);
        r.finish(first, 6, true); r.completeReady(second, true); Fixture f = r.fixture();
        assertEquals(5, candidate(f, 20, 7).get("cpuAvailableAt").getAsDouble());
        assertEquals(1, observation(f, 20).get("activeJobCount").getAsInt()); assertEquals(2, f.validate().getAssignmentCount());
    }

    @Test void completedJobsDoNotLeaveStaleReservations() {
        Task a = task(1, 10000), b = task(2, 1000); Run r = new Run(false, true, false, a, b);
        Job first = r.prepare(10, a, 0); r.start(first, 0); r.finish(first, 1, true);
        r.completeReady(r.prepare(20, b, 1), true); Fixture f = r.fixture();
        assertEquals(0, observation(f, 20).get("activeJobCount").getAsInt());
        assertEquals(1, candidate(f, 20, 7).get("cpuAvailableAt").getAsDouble()); assertEquals(2, f.validate().getAssignmentCount());
    }

    @Test void cpuReservationSumRetainsRequestOrderNotJobIdOrMagnitudeOrder() {
        Task a = task(1, 1_000_000_000_000L), b = task(2, 1), c = task(3, 1), d = task(4, 1);
        Run r = new Run(false, false, false, endpoints(10, 10), 1000, 1e-16, 1, 1, a, b, c, d);
        Job first = r.prepare(900, a, 0), second = r.prepare(20, b, 0), third = r.prepare(30, c, 0), last = r.prepare(10, d, 0);
        r.completeReady(first, true); r.completeReady(second, true); r.completeReady(third, true); r.completeReady(last, true);
        Fixture f = r.fixture(); double ordered = (1e9 + .001) + .001, reordered = 1e9 + (.001 + .001);
        assertNotEquals(ordered, reordered); assertEquals(ordered, candidate(f, 10, 7).get("cpuAvailableAt").getAsDouble());
        assertEquals(4, f.validate().getAssignmentCount());
        candidate(f, 10, 7).addProperty("cpuAvailableAt", reordered); rescore(f, 10, 7); bad(f);
    }

    @Test void retryReusesBindingAndCandidateDomainEvenWithAnotherBusyAttempt() {
        Fixture f = retried(); DataflowAssignmentValidator.Result result = f.validate();
        assertEquals(3, result.getAssignmentCount()); assertEquals(2, result.getInitialBindingCount()); assertEquals(1, result.getRetryReuseCount());
        assertEquals("RETRY_REUSE", attrs(f, 30).get("binding").getAsString());
        assertEquals(1, attrs(f, 30).getAsJsonArray("candidates").size()); assertEquals(42, action(f, 30).get("vmId").getAsInt());
    }

    @Test void missingOrLateRetryCreationAndWrongParentAreRejected() {
        Fixture f = retried(); removeMain(f, "RETRY_JOB_CREATED", 30); bad(f);
        f = retried(); event(f.main, "RETRY_JOB_CREATED", 30).getAsJsonObject("attributes").addProperty("failedJobId", 20); bad(f);
        f = retried(); int creation = index(f.main, "RETRY_JOB_CREATED", 30), binding = index(f.main, "DATAFLOW_VM_ASSIGNED", 30);
        JsonElement swapped = f.main.get(creation); f.main.set(creation, f.main.get(binding)); f.main.set(binding, swapped); renumber(f.main); bad(f);
    }

    @Test void retryMustFollowFailedReturnAndFailureNotJustFinalStatus() {
        Fixture f = retried(); removeMain(f, "JOB_FAILED", 10); bad(f);
        f = retried(); removeMain(f, "JOB_RETURNED", 10); bad(f);
        f = retried(); event(f.main, "JOB_READY", 30).getAsJsonObject("attributes").addProperty("retryOfFailedJobId", 20); bad(f);
    }

    @Test void initialAndRetryLabelsCannotBeSwapped() {
        Fixture f = literal(false); attrs(f, 10).addProperty("binding", "RETRY_REUSE"); bad(f);
        f = retried(); attrs(f, 30).addProperty("binding", "INITIAL"); bad(f);
        f = retried(); attrs(f, 30).getAsJsonArray("candidates").add(candidate(f, 20, 7).deepCopy()); bad(f);
    }

    @Test void retryCannotChangeLogicalVmOrReuseSuccessfulParent() {
        Fixture f = retried(); action(f, 30).addProperty("vmId", 7); bad(f);
        f = retried(); jobOutcome(f, 10).addProperty("status", Cloudlet.SUCCESS);
        taskOutcome(f, 10).addProperty("jobStatus", Cloudlet.SUCCESS); taskOutcome(f, 10).addProperty("taskStatus", Cloudlet.SUCCESS); bad(f);
        f = retried(); request(f, 30).addProperty("taskId", 2); bad(f);
    }

    @Test void failurePolicyBudgetAndNoopStaticConstraintsAreNotRngProofs() {
        Fixture f = retried(); config(f).getAsJsonObject("failureModel").addProperty("maxTotalRetryJobs", 0); bad(f);
        f = retried(); config(f).getAsJsonObject("failureModel").addProperty("generatorMode", "FAILURE_NONE"); bad(f);
        f = literal(false); config(f).getAsJsonObject("failureModel").addProperty("clusteringAlgorithm", "FTCLUSTERING_SR"); bad(f);
    }

    @Test void peCompatibilityChecksBothTaskAndJobRequirementsAndNullRows() {
        for (boolean taskPes : new boolean[]{false, true}) {
            Task t = task(1, 1000); if (taskPes) t.setNumberOfPes(2);
            Run r = new Run(false, false, false, endpoints(10, 10), 1000, 1000, 1, 2, t);
            Job j = r.prepare(10, t, 0, taskPes ? 1 : 2); r.completeReady(j, true); Fixture f = r.fixture();
            assertFalse(candidate(f, 10, 7).get("compatible").getAsBoolean());
            for (String field : Arrays.asList("inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies"))
                assertTrue(candidate(f, 10, 7).get(field).isJsonNull());
            assertEquals(42, action(f, 10).get("vmId").getAsInt()); assertEquals(1, f.validate().getAssignmentCount());
            Fixture wrong = f.copy(); candidate(wrong, 10, 7).addProperty("inputSeconds", 0); bad(wrong);
            wrong = f.copy(); candidate(wrong, 10, 42).addProperty("compatible", false); bad(wrong);
        }
    }

    @Test void positivePesAndLogicalTaskLengthPesAreFrozenAcrossAttempts() {
        for (String field : Arrays.asList("taskPes", "jobPes")) {
            Fixture f = literal(false); request(f, 10).addProperty(field, 0); bad(f);
            f = retried(); request(f, 30).addProperty(field, 2); bad(f);
        }
        Fixture f = retried(); request(f, 30).addProperty("lengthMi", 999); taskOutcome(f, 30).addProperty("lengthMi", 999); bad(f);
    }

    @Test void matrixBinary64ProductRoundsToPositivePerPeMiRatherThanRawLength() {
        Task t = task(1, 5000); costs(t, .00149, .0015);
        Run r = new Run(false, true, false, t); r.completeReady(r.prepare(10, t, 0), true); Fixture f = r.fixture();
        assertEquals(.001, candidate(f, 10, 7).get("computeSeconds").getAsDouble());
        assertEquals(.002, candidate(f, 10, 42).get("computeSeconds").getAsDouble());
        assertEquals(1, taskOutcome(f, 10).get("effectiveExecutionLengthMi").getAsLong()); assertEquals(1, f.validate().getAssignmentCount());
        taskOutcome(f, 10).addProperty("effectiveExecutionLengthMi", 5000); bad(f);
    }

    @Test void exactHalfTiesRoundPositiveButNextDownHalfDoesNotRoundUp() {
        Fixture half = literal(false); setVmMips(half, 1); setMatrix(half, .5, .5);
        taskOutcome(half, 10).addProperty("effectiveExecutionLengthMi", 1); assertEquals(1, half.validate().getAssignmentCount());
        Fixture below = half.copy(); matrixEntry(below, 1, 7).addProperty("executionSeconds", Math.nextDown(.5)); bad(below);
        Fixture tiny = half.copy(); matrixEntry(tiny, 1, 7).addProperty("executionSeconds", Double.MIN_VALUE); bad(tiny);
    }

    @Test void exactMatrixRoundingAlsoRejectsLargeBinary64AndInstructionOverflow() {
        for (double seconds : new double[]{4503599627370495.5, 4503599627370496.0, Double.MAX_VALUE}) {
            Fixture f = literal(false); setVmMips(f, 1); setMatrix(f, seconds, seconds); bad(f);
        }
        Fixture f = literal(false); request(f, 10).addProperty("lengthMi", 9_223_372_036_855L);
        taskOutcome(f, 10).addProperty("lengthMi", 9_223_372_036_855L); bad(f);
        f = literal(false); request(f, 10).addProperty("lengthMi", 4_611_686_018_428L); request(f, 10).addProperty("taskPes", 2);
        taskOutcome(f, 10).addProperty("lengthMi", 4_611_686_018_428L);
        for (JsonElement vm : f.manifest.getAsJsonObject("platform").getAsJsonArray("vms")) vm.getAsJsonObject().addProperty("pes", 2);
        bad(f);
    }

    @Test void selectedEffectiveMiAndCandidateComputeAreComparedExactly() {
        Fixture f = literal(false); taskOutcome(f, 10).addProperty("effectiveExecutionLengthMi", 1001); bad(f);
        f = literal(false); candidate(f, 10, 42).addProperty("computeSeconds", Math.nextUp(1.0)); rescore(f, 10, 42); bad(f);
        f = literal(false); request(f, 10).addProperty("lengthMi", 1001); bad(f);
    }

    @Test void extraNonnegativeMatrixCoordinatesRemainValidUnchangedCoreMetadata() {
        Fixture f = literal(false); setMatrix(f, 1, 1);
        JsonObject matrix = config(f).getAsJsonObject("taskCostMatrix"); JsonArray entries = matrix.getAsJsonArray("entries");
        entries.add(row("taskId", 99, "vmId", 7, "executionSeconds", 2));
        entries.add(row("taskId", 1, "vmId", 999, "executionSeconds", 3));
        entries.add(row("taskId", 99, "vmId", 999, "executionSeconds", 4));
        JsonObject before = matrix.deepCopy();
        assertEquals(1, f.validate().getAssignmentCount());
        assertEquals(before, config(f).getAsJsonObject("taskCostMatrix"), "extra coordinates must not be removed or reinterpreted");
    }

    @Test void matrixMissingDuplicateAndUnknownKeysDoNotFallbackToDeclaredMi() {
        Fixture f = literal(false); setMatrix(f, 1, 1); config(f).getAsJsonObject("taskCostMatrix").getAsJsonArray("entries").remove(0); bad(f);
        f = literal(false); setMatrix(f, 1, 1); JsonArray rows = config(f).getAsJsonObject("taskCostMatrix").getAsJsonArray("entries"); rows.add(rows.get(0).deepCopy()); bad(f);
        f = literal(false); setMatrix(f, 1, 1); matrixEntry(f, 1, 7).addProperty("other", 0); bad(f);
    }

    @Test void scoreUsesExactBinary64AndNumericVmTieNotCandidatePosition() {
        Fixture f = literal(false); candidate(f, 10, 7).addProperty("scoreFinishSeconds", Math.nextUp(1.0)); bad(f);
        f = literal(false); JsonArray rows = attrs(f, 10).getAsJsonArray("candidates"); JsonElement a = rows.get(0); rows.set(0, rows.get(1)); rows.set(1, a); bad(f);
        f = literal(false); changeAttemptVm(f, 10, 42); bad(f); // Both scores tie; every identity now agrees on the wrong winner.
    }

    @Test void positiveAddsMustAdvanceTheBinary64ScoreClock() {
        Fixture f = literal(false); double now = 1e16;
        for (JsonElement row : f.main) row.getAsJsonObject().addProperty("simulationTime", now);
        for (JsonElement row : f.life.getAsJsonArray("events")) row.getAsJsonObject().addProperty("observedTime", now);
        f.life.getAsJsonObject("capture").addProperty("observedThrough", now); f.manifest.getAsJsonObject("result").addProperty("simulationEndSeconds", now);
        observation(f, 10).addProperty("observedThrough", now); observation(f, 10).addProperty("serviceThrough", now);
        for (int vm : new int[]{7, 42}) { candidate(f, 10, vm).addProperty("cpuAvailableAt", now); candidate(f, 10, vm).addProperty("scoreFinishSeconds", now); }
        bad(f);
    }

    @Test void finiteComputeDivisionAndFiniteScoreAreMandatory() {
        Fixture f = literal(false); setVmMips(f, Double.MIN_VALUE); bad(f);
        f = literal(false); candidate(f, 10, 7).addProperty("scoreFinishSeconds", Double.POSITIVE_INFINITY); bad(f);
        f = literal(false); candidate(f, 10, 7).addProperty("computeSeconds", Double.NaN); bad(f);
    }

    @Test void initialCandidatesMustCoverExactlyAllActualVmIds() {
        Fixture f = literal(false); attrs(f, 10).getAsJsonArray("candidates").remove(1); bad(f);
        f = literal(false); attrs(f, 10).getAsJsonArray("candidates").add(candidate(f, 10, 7).deepCopy()); bad(f);
        f = literal(false); candidate(f, 10, 42).addProperty("vmId", 1); bad(f);
        f = literal(false); f.manifest.getAsJsonObject("platform").getAsJsonArray("vms").remove(1); bad(f);
    }

    @Test void observationCountsAndFlagsMustMatchPreRequestNotPostRequestState() {
        for (String field : Arrays.asList("activeCopyCount", "activeJobCount", "pendingOutputFileCount", "scopedFileCount")) {
            Fixture f = joined(); JsonObject summary = observation(f, 20); summary.addProperty(field, summary.get(field).getAsInt() + 1); bad(f);
        }
        for (String field : Arrays.asList("storageVersion", "storeBackedInputs", "interFlowSharing")) {
            Fixture f = conditional(); JsonObject summary = observation(f, 20); summary.addProperty(field, !summary.get(field).getAsBoolean()); bad(f);
        }
        Fixture f = conditional(); observation(f, 20).addProperty("pendingOutputFileCount", 0); bad(f);
    }

    @Test void observationMustBeBoundAndExactlyAtTheActionServiceBoundary() {
        Fixture f = literal(false); observation(f, 10).addProperty("status", "PLAN_ONLY"); bad(f);
        f = joined(); observation(f, 20).addProperty("serviceThrough", 4); bad(f);
        f = joined(); observation(f, 20).addProperty("observedThrough", 4); bad(f);
        f = joined(); action(f, 20).addProperty("simulationTime", 4); event(f.main, "JOB_READY", 20).addProperty("simulationTime", 4); bad(f);
    }

    @Test void noMissingDuplicateEarlyLateOrExtraAssignmentRecords() {
        Fixture f = literal(false); removeMain(f, "DATAFLOW_VM_ASSIGNED", 10); bad(f);
        f = literal(false); f.main.add(action(f, 10).deepCopy()); renumber(f.main); bad(f);
        f = literal(false); JsonElement assignment = f.main.get(0); f.main.set(0, f.main.get(1)); f.main.set(1, assignment); renumber(f.main); bad(f);
        f = literal(false); action(f, 10).addProperty("jobId", 999); bad(f);
        f = literal(false); action(f, 10).addProperty("classType", Parameters.ClassType.STAGE_IN.value); bad(f);
    }

    @Test void assignmentSequenceAndMainOrderCannotBeInventedIndependently() {
        Fixture f = joined(); attrs(f, 20).addProperty("assignmentSequence", 1); bad(f);
        f = literal(false); attrs(f, 10).addProperty("assignmentSequence", 2); bad(f);
        f = literal(false); event(f.main, "JOB_READY", 10).addProperty("sequence", 1); bad(f);
        f = literal(false); removeMain(f, "JOB_READY", 10); bad(f);
    }

    @Test void sameClockLifecycleRequestOrderCannotBeReversedInMainAssignments() {
        Task a = task(1, 1000), b = task(2, 1000); Run r = new Run(false, false, false, a, b);
        Job first = r.prepare(10, a, 0), second = r.prepare(20, b, 0); r.completeReady(first, true); r.completeReady(second, true);
        Fixture f = r.fixture(); JsonArray reversed = array(f.main.get(2), f.main.get(3), f.main.get(0), f.main.get(1));
        for (int i = 4; i < f.main.size(); i++) reversed.add(f.main.get(i)); f.main = reversed; renumber(f.main);
        attrs(f, 20).addProperty("assignmentSequence", 1); attrs(f, 10).addProperty("assignmentSequence", 2); bad(f);
    }

    @Test void fullMainTaskJobVmIdentitiesAndComputeMetadataMustAgree() {
        Fixture f = literal(false); action(f, 10).add("taskIds", array(2)); bad(f);
        f = literal(false); taskOutcome(f, 10).addProperty("vmId", 42); bad(f);
        f = literal(false); jobOutcome(f, 10).add("taskIds", array(1, 2)); bad(f);
        f = literal(false); request(f, 10).addProperty("jobId", 11); bad(f);
        f = literal(false); f.manifest.getAsJsonArray("dataflowComputeRequests").add(request(f, 10).deepCopy()); bad(f);
        f = literal(false); f.manifest.getAsJsonArray("dataflowComputeRequests").remove(0); bad(f);
    }

    @Test void assignmentAndNestedWireShapesAreClosed() {
        Fixture f = literal(false); action(f, 10).addProperty("extra", 1); bad(f);
        f = literal(false); attrs(f, 10).remove("auditScope"); bad(f);
        f = literal(false); attrs(f, 10).addProperty("extra", 1); bad(f);
        f = literal(false); observation(f, 10).addProperty("extra", 1); bad(f);
        f = literal(false); candidate(f, 10, 7).remove("joinedInputCopies"); bad(f);
        f = literal(false); candidate(f, 10, 7).addProperty("extra", 1); bad(f);
        f = literal(false); request(f, 10).addProperty("extra", 1); bad(f);
    }

    @Test void versionsAndScopeCannotClaimStrongerOrUnknownSemantics() {
        for (String field : Arrays.asList("assignmentMode", "assignmentPolicy", "inputEstimateSemantics", "cpuReservationSemantics", "auditScope", "binding")) {
            Fixture f = literal(false); attrs(f, 10).addProperty(field, "FLUID_OR_COUNTERFACTUAL_CERTIFICATE"); bad(f);
        }
        Fixture f = literal(false); observation(f, 10).addProperty("contract", "UNKNOWN"); bad(f);
    }

    @Test void rawNumbersCannotBeStringsBooleansNullsOrRoundedIntegerClaims() {
        for (JsonElement invalid : Arrays.asList(new JsonPrimitive("1"), new JsonPrimitive(true), JsonNull.INSTANCE,
                new JsonPrimitive(new BigDecimal("1.00000000000000000001")), new JsonPrimitive(new BigDecimal("9223372036854775808")))) {
            Fixture f = literal(false); attrs(f, 10).add("assignmentSequence", invalid); bad(f);
        }
        for (String field : Arrays.asList("inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds", "joinedInputCopies")) {
            Fixture f = literal(false); JsonObject row = candidate(f, 10, 7); row.addProperty(field, row.get(field).getAsString()); bad(f);
        }
        Fixture f = literal(false); candidate(f, 10, 7).addProperty("compatible", "true"); bad(f);
        f = literal(false); candidate(f, 10, 7).addProperty("conditionalStoreWait", 0); bad(f);
        f = literal(false); request(f, 10).addProperty("taskPes", new BigDecimal("1.000000000000000001")); bad(f);
    }

    @Test void negativeUnderflowOverflowAndExcessPrecisionNumericsAreRejected() {
        for (BigDecimal invalid : Arrays.asList(new BigDecimal("-1e-400"), new BigDecimal("1e-400"), new BigDecimal("1e400"), new BigDecimal("1e-4097"))) {
            Fixture f = literal(false); candidate(f, 10, 7).addProperty("inputSeconds", invalid); bad(f);
        }
        Fixture f = literal(false); observation(f, 10).addProperty("activeJobCount", .5); bad(f);
        f = literal(false); request(f, 10).addProperty("lengthMi", "1000"); bad(f);
    }

    @Test void lexicalDuplicateKeysMustBeRejectedBeforeTreeBasedAudit() {
        String action = literal(false).main.get(0).toString();
        String duplicate = action.replace("\"assignmentSequence\":1", "\"assignmentSequence\":1,\"assignmentSequence\":1");
        assertNotEquals(action, duplicate);
        assertThrows(IllegalArgumentException.class, () -> NetworkLedgerCodec.parseDocument(duplicate));
    }

    @Test void configurationMustBeExplicitSupportedOnlineAndUnmodifiedPhysicalKind() {
        Fixture f = literal(false); config(f).remove("dataflowAssignment"); bad(f);
        f = literal(false); config(f).add("dataflowAssignment", JsonNull.INSTANCE); bad(f);
        f = literal(false); config(f).getAsJsonObject("dataflowAssignment").addProperty("extra", true); bad(f);
        f = literal(false); config(f).addProperty("planningAlgorithm", "RANDOM"); bad(f);
        f = literal(false); config(f).addProperty("schedulingAlgorithm", "MINMIN"); bad(f);
        f = literal(false); config(f).getAsJsonObject("clustering").addProperty("method", "HORIZONTAL"); bad(f);
        f = literal(false); config(f).getAsJsonObject("overheadModel").addProperty("bandwidth", 1); bad(f);
        f = literal(false); config(f).addProperty("fileSystem", "SHARED"); bad(f);
        f = literal(false); config(f).getAsJsonObject("dataMovementModel").addProperty("kind", "COHERENT_STORAGE_DATAFLOW_V3"); bad(f);
    }

    @Test void actionExportRejectsOffWrongBudgetTruncationAndNonquiescentCapture() {
        Fixture f = literal(false); config(f).remove("networkEvidence"); bad(f);
        f = literal(false); config(f).getAsJsonObject("networkEvidence").addProperty("mode", "OFF"); bad(f);
        f = literal(false); config(f).getAsJsonObject("networkEvidence").addProperty("maxTraceRecords", 999); bad(f);
        f = literal(false); f.life.getAsJsonObject("capture").addProperty("status", "TRUNCATED"); bad(f);
        f = literal(false); f.life.getAsJsonArray("events").remove(3); recount(f.life); bad(f);
        Fixture original = literal(false);
        assertThrows(IllegalArgumentException.class, () -> new DataflowAssignmentValidator(original.manifest, (FileLifecycleCodec.Decoded) null));
        assertThrows(IllegalArgumentException.class, () -> new DataflowAssignmentValidator(original.manifest, (StorageLifecycleCodec.Decoded) null));
    }

    @Test void storageCaptureWithPendingUnusedOutputIsNotACompleteActionExport() {
        Task p = task(1, 1000, out("unused", 100)); Run r = new Run(true, true, false, p);
        r.completeReady(r.prepare(10, p, 0), true); Fixture f = r.fixture();
        assertFalse(StorageLifecycleCodec.decodeDocument(f.life).isQuiescent()); bad(f);
        r.drain(); assertEquals(1, r.fixture().validate().getAssignmentCount());
    }

    @Test void frozenManifestAndAcceptedActionTreesCannotBeMutatedAfterValidationInput() {
        Fixture f = literal(false); DataflowAssignmentValidator audit = f.audit();
        f.manifest.getAsJsonArray("dataflowComputeRequests").get(0).getAsJsonObject().addProperty("lengthMi", 42);
        JsonObject first = action(f, 10); audit.acceptEvent(first); first.getAsJsonObject("attributes").addProperty("selectedVmId", 999);
        for (int i = 1; i < f.main.size(); i++) audit.acceptEvent(f.main.get(i).getAsJsonObject());
        assertEquals(7, audit.finish().getAssignments().get(0).getAsJsonObject().getAsJsonObject("attributes").get("selectedVmId").getAsInt());
    }

    @Test void independentReadFabricCoversEndpointAndBoundedDirectedTopologyPaths() {
        DataTransferFabric production = storageFabric(100, 100, 30, 10, 100, true);
        Task t = task(1, 1000, in("x", 40));
        Run r = new Run(true, true, false, production, 1000, 2000, 1, 1, t);
        Job j = r.prepare(10, t, 0); r.drain(); r.completeReady(j, true); Fixture f = r.fixture();
        FileLifecycleValidator.ReadFabric read = new FileLifecycleValidator.ReadFabric(f.life.getAsJsonObject("fabric"), true);
        assertEquals(20, read.rate(DataLocation.source("source"), DataLocation.vm(42)));
        assertEquals(10, read.rate(DataLocation.vm(42), DataLocation.source("source")));
        assertEquals(30, read.rate(DataLocation.source("source"), DataLocation.vm(7)));
        assertEquals(1, f.validate().getAssignmentCount());
        assertThrows(IllegalArgumentException.class, () -> read.rate(DataLocation.vm(7), DataLocation.vm(7)));
        assertThrows(IllegalArgumentException.class, () -> read.rate(DataLocation.source("other"), DataLocation.vm(7)));
        assertThrows(IllegalArgumentException.class, () -> read.rate(DataLocation.vm(999), DataLocation.vm(7)));
    }

    @Test void baseLifecycleStillRejectsMissingSourceCausalityBeforeActionAudit() {
        Fixture f = literal(true); f.life.getAsJsonArray("events").remove(0); recount(f.life); bad(f);
        f = literal(true); lifePayload(f, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").addProperty("visibleAt", 1); bad(f);
        f = conditional(); removeLife(f, "COPY_ADMITTED", 0); bad(f);
    }

    private static Fixture literal(boolean input) {
        JsonObject file = row("workflowInputIndex", 0, "name", "x");
        JsonObject plan = row("contractVersion", "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2", "tasks", array(row("taskId", 1,
                "workflowInputIndex", 0, "parents", array(), "inputs", input ? array(row("fileId", file, "referenceCount", 1)) : array(), "outputs", array())),
                "files", input ? array(row("fileId", file, "bytes", 100, "producerTaskId", null)) : array());
        JsonArray events = new JsonArray();
        if (input) lifeEvent(events, "EXTERNAL_SEEDED", 0, row("fileId", file, "location", source()));
        lifeEvent(events, "JOB_INPUT_REQUESTED", 0, row("jobId", 10, "taskIds", array(1), "destinationVmId", 7));
        if (input) {
            JsonObject replica = row("fileId", file, "location", source(), "visibleAt", 0, "acquisition", "EXTERNAL_SEED",
                    "origin", row("producerTaskId", null, "jobAttemptId", null, "location", source(), "observedAt", 0), "copiedFrom", null, "copyOrdinal", null);
            lifeEvent(events, "COPY_ADMITTED", 0, row("copyOrdinal", 1, "fileId", file, "bytes", 100, "sourceReplica", replica,
                    "destinationVmId", 7, "resources", array("VM:7"), "standaloneRate", 10, "isolatedSeconds", 10));
            lifeEvent(events, "INPUT_RESOLVED", 0, row("jobId", 10, "fileId", file, "referenceCount", 1, "resolution", "NEW_COPY", "copyOrdinal", 1, "source", source()));
            lifeEvent(events, "COPY_SETTLED", 10, row("copyOrdinal", 1, "effectiveTime", 10, "remainingAfterService", 0));
        }
        lifeEvent(events, "JOB_DATA_READY", input ? 10 : 0, row("jobId", 10));
        lifeEvent(events, "JOB_CPU_STARTED", input ? 10 : 0, row("jobId", 10, "vmId", 7));
        lifeEvent(events, "TASK_FINISHED", input ? 11 : 1, row("jobId", 10, "taskId", 1, "vmId", 7, "success", true));
        JsonObject life = row("schema", "workflowsim-file-lifecycle-v2", "modelKind", "COHERENT_FILE_DATAFLOW_V2",
                "recording", row("mode", "FILE_LIFECYCLE_V2", "maxTraceRecords", 4096),
                "certificateScope", "FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2", "policies", row("fileIdentity", "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2",
                        "release", "DEPENDENCY_READY_AT_OBSERVATION_V2", "visibility", "PER_FILE_SETTLEMENT_OBSERVATION_V2",
                        "selection", "VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2", "sourceAccess", "UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2", "sharing", "SHARED_MAX_MIN"),
                "capture", row("status", "COMPLETE", "observedThrough", input ? 11 : 1, "retainedRecords", events.size(), "droppedRecords", 0),
                "filePlan", plan, "fabric", row("locations", array(vm(7), vm(42), source()), "resources",
                        array(row("key", "VM:7", "capacityBytesPerSecond", 10), row("key", "VM:42", "capacityBytesPerSecond", 10)), "vmHostAssignments", array(), "topology", null), "events", events);
        JsonObject summary = row("contract", "OBSERVED_DATAFLOW_STATE_V1", "status", "BOUND", "storageVersion", false,
                "storeBackedInputs", false, "interFlowSharing", true, "observedThrough", 0, "serviceThrough", 0,
                "activeCopyCount", 0, "activeJobCount", 0, "pendingOutputFileCount", 0, "scopedFileCount", input ? 1 : 0);
        JsonObject attrs = row("assignmentMode", "CONTROL_READY_ONLINE_ASSIGNMENT_V1", "assignmentPolicy", "NOMINAL_INPUT_EARLIEST_RESERVATION_V1",
                "inputEstimateSemantics", "MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1", "cpuReservationSemantics", "ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1",
                "auditScope", SCOPE, "assignmentSequence", 1, "binding", "INITIAL", "selectedVmId", 7, "observation", summary,
                "candidates", array(score(7, input ? 10 : 0), score(42, input ? 10 : 0)));
        JsonArray main = array(mainEvent(1, "DATAFLOW_VM_ASSIGNED", 10, 1, 7, 0, attrs), mainEvent(2, "JOB_READY", 10, 1, 7, 0, row()),
                mainEvent(3, "JOB_RETURNED", 10, 1, 7, input ? 11 : 1, row("jobStatus", Cloudlet.SUCCESS)));
        JsonObject manifest = manifest(life, false, false, array(vmMeta(7, 1000, 1), vmMeta(42, 1000, 1)),
                array(computeRequest(10, 1, 1000, 1, 1)), array(jobRow(10, 1, 7, Cloudlet.SUCCESS)), array(taskRow(10, 1, 7, Cloudlet.SUCCESS, 1000, 1000)), null, false);
        return new Fixture(manifest, life, main, false);
    }

    private static Fixture joined() {
        Task a = task(1, 1000, in("x", 100)), b = task(2, 1000, in("x", 100)); Run r = new Run(false, true, false, a, b);
        Job first = r.prepare(10, a, 0), second = r.prepare(20, b, 5); r.drain(); r.completeReady(first, true); r.completeReady(second, true); return r.fixture();
    }
    private static Fixture conditional() {
        Task p = task(1, 1000, out("x", 100)), c = task(2, 1000, in("x", 100)); edge(p, c);
        Run r = new Run(true, true, true, storageFabric(100, 100, 20, 10, 100, false), 1000, 1000, 1, 1, p, c);
        r.completeReady(r.prepare(10, p, 0), true); Job consumer = r.prepare(20, c, 1); r.drain(); r.completeReady(consumer, true); return r.fixture();
    }
    private static Fixture retried() {
        Task a = task(1, 1000), b = task(2, 100000); Run r = new Run(false, true, false, endpoints(10, 10), 100, 1000, 1, 1, a, b);
        r.completeReady(r.prepare(10, a, 0), false); Job busy = r.prepare(20, b, r.now());
        Job retry = r.retry(30, 10, task(1, 1000), r.now()); r.completeReady(retry, true); r.completeReady(busy, true); return r.fixture();
    }

    private static final class Fixture {
        final JsonObject manifest, life; JsonArray main; final boolean storage;
        Fixture(JsonObject manifest, JsonObject life, JsonArray main, boolean storage) { this.manifest = manifest; this.life = life; this.main = main; this.storage = storage; }
        Fixture copy() { return new Fixture(manifest.deepCopy(), life.deepCopy(), main.deepCopy(), storage); }
        DataflowAssignmentValidator audit() { return storage ? new DataflowAssignmentValidator(manifest, StorageLifecycleCodec.decodeDocument(life)) : new DataflowAssignmentValidator(manifest, FileLifecycleCodec.decodeDocument(life)); }
        DataflowAssignmentValidator.Result validate() { DataflowAssignmentValidator audit = audit(); for (JsonElement event : main) audit.acceptEvent(event.getAsJsonObject()); return audit.finish(); }
    }

    /** Produces genuine action/lifecycle records while keeping unrelated root metadata deliberately small. */
    private static final class Run {
        final boolean storage, gate; final CoherentDataflowRuntime runtime; final DataflowVmAssigner assigner;
        final JsonArray main = new JsonArray(), vmRows; final Map<Integer, Info> jobs = new LinkedHashMap<>();
        final Map<Integer, Integer> retryParents = new LinkedHashMap<>(); final Task[] tasks; final double mips7, mips42;
        Run(boolean storage, boolean shared, boolean gate, Task... tasks) {
            this(storage, shared, gate, storage ? storageFabric(10, 10, 20, 10, 100, false) : endpoints(10, 10), 1000, 1000, 1, 1, tasks);
        }
        Run(boolean storage, boolean shared, boolean gate, DataTransferFabric fabric, double a, double b, int pa, int pb, Task... tasks) {
            this.storage = storage; this.gate = gate; this.tasks = tasks; mips7 = a; mips42 = b;
            vmRows = array(vmMeta(7, a, pa), vmMeta(42, b, pb));
            runtime = storage ? CoherentDataflowRuntime.withStorage(shared, 4096, gate) : new CoherentDataflowRuntime(shared, 4096);
            Map<Integer, Integer> scopes = new LinkedHashMap<>(); for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
            runtime.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks), scopes), fabric.getLocations()); runtime.bindFabric(fabric);
            assigner = new DataflowVmAssigner(runtime, Arrays.asList(new DataflowVmAssigner.VmOption(42, b, pb), new DataflowVmAssigner.VmOption(7, a, pa)));
        }
        Job prepare(int id, Task task, double now) { return prepare(id, task, now, 1); }
        Job prepare(int id, Task task, double now, int pes) {
            Job job = new Job(id, task.getCloudletLength()); job.setClassType(Parameters.ClassType.COMPUTE.value);
            job.setTaskList(Collections.singletonList(task)); job.setNumberOfPes(pes);
            if (retryParents.containsKey(id)) job.setVmId(task.getVmId());
            DataflowVmAssigner.Decision decision = assigner.prepare(job, now).getDecision();
            JsonObject attributes = JSON.toJsonTree(decision.getAttributes()).getAsJsonObject();
            assertEquals(SCOPE, attributes.get("auditScope").getAsString(), "production action must declare its bounded audit scope");
            jobs.put(id, new Info(job, task)); emit("DATAFLOW_VM_ASSIGNED", job, now, attributes);
            JsonObject ready = row(); if (retryParents.containsKey(id)) ready.addProperty("retryOfFailedJobId", retryParents.get(id));
            emit("JOB_READY", job, now, ready); return job;
        }
        Job retry(int id, int parentId, Task task, double now) {
            Info parent = jobs.get(parentId); task.setVmId(parent.job.getVmId()); retryParents.put(id, parentId);
            main.add(mainEvent(main.size() + 1, "RETRY_JOB_CREATED", id, task.getCloudletId(), task.getVmId(), now, row("failedJobId", parentId)));
            return prepare(id, task, now, parent.job.getNumberOfPes());
        }
        void start(Job job, double at) { runtime.drainReadyJobIds(); runtime.cpuStarted(job.getCloudletId(), one(jobs.get(job.getCloudletId()).task.getCloudletId()), job.getVmId(), at); }
        void finish(Job job, double at, boolean success) {
            Info info = jobs.get(job.getCloudletId()); runtime.jobFinished(job.getCloudletId(), one(info.task.getCloudletId()), job.getVmId(), Collections.singletonList(success), at);
            info.status = success ? Cloudlet.SUCCESS : Cloudlet.FAILED; emit("JOB_RETURNED", job, at, row("jobStatus", info.status));
            if (!success) emit("JOB_FAILED", job, at, row());
        }
        void completeReady(Job job, boolean success) { double now = now(); start(job, now); finish(job, now + 1, success); }
        void drain() { int count = 0; while (runtime.getNextCompletionTime() != null) { assertTrue(count++ < 100); runtime.advance(runtime.getNextCompletionTime()); } }
        double now() { return runtime.getCurrentTime(); }
        void emit(String type, Job job, double at, JsonObject attrs) { main.add(mainEvent(main.size() + 1, type, job.getCloudletId(), jobs.get(job.getCloudletId()).task.getCloudletId(), job.getVmId(), at, attrs)); }
        Fixture fixture() {
            JsonObject life = storage ? StorageLifecycleCodec.document(runtime.captureStorageEvidence()) : FileLifecycleCodec.document(runtime.captureEvidence());
            JsonArray requests = new JsonArray(), outcomes = new JsonArray(), taskOutcomes = new JsonArray();
            for (Info info : jobs.values()) {
                int id = info.job.getCloudletId(), task = info.task.getCloudletId(), vm = info.job.getVmId();
                requests.add(computeRequest(id, task, info.task.getCloudletLength(), info.task.getNumberOfPes(), info.job.getNumberOfPes()));
                outcomes.add(jobRow(id, task, vm, info.status));
                long effective = TaskExecutionModel.executionLengthMi(info.task, vm, vm == 7 ? mips7 : mips42);
                taskOutcomes.add(taskRow(id, task, vm, info.status, info.task.getCloudletLength(), effective));
            }
            JsonArray matrixEntries = new JsonArray();
            for (Task task : tasks) if (task.hasVmExecutionCostSeconds()) for (int vm : new int[]{7, 42})
                matrixEntries.add(row("taskId", task.getCloudletId(), "vmId", vm, "executionSeconds", task.getVmExecutionCostSeconds(vm)));
            JsonObject matrix = matrixEntries.size() == 0 ? null : matrix(matrixEntries);
            return new Fixture(manifest(life, storage, gate, vmRows, requests, outcomes, taskOutcomes, matrix, !retryParents.isEmpty()), life, main.deepCopy(), storage);
        }
    }
    private static final class Info { final Job job; final Task task; int status = Cloudlet.SUCCESS; Info(Job job, Task task) { this.job = job; this.task = task; } }

    private static JsonObject manifest(JsonObject life, boolean storage, boolean gate, JsonArray vms, JsonArray requests, JsonArray jobs, JsonArray tasks, JsonObject matrix, boolean failures) {
        JsonObject config = row("dataflowAssignment", row("mode", "CONTROL_READY_ONLINE_ASSIGNMENT_V1", "policy", "NOMINAL_INPUT_EARLIEST_RESERVATION_V1"),
                "planningAlgorithm", "INVALID", "schedulingAlgorithm", "STATIC", "clustering", row("method", "NONE"),
                "dataMovementModel", row("kind", life.get("modelKind")), "fileSystem", gate ? "SHARED" : "LOCAL", "networkEvidence", life.get("recording").deepCopy(),
                "overheadModel", row("workflowEngineDelayInterval", 0, "bandwidth", 0, "workflowEngineDelays", row(), "queueDelays", row(), "postDelays", row(), "clusteringDelays", row()),
                "failureModel", row("clusteringAlgorithm", "FTCLUSTERING_NOOP", "monitorMode", "MONITOR_NONE", "generatorMode", failures ? "FAILURE_ALL" : "FAILURE_NONE", "maxTotalRetryJobs", failures ? 10 : 0),
                "taskCostMatrix", matrix);
        return row("configuration", config, "platform", row("vms", vms), "dataflowPlan", life.get("filePlan").deepCopy(), "dataflowComputeRequests", requests,
                "result", row("simulationEndSeconds", life.getAsJsonObject("capture").get("observedThrough"), "jobs", jobs, "tasks", tasks));
    }
    private static JsonObject score(int vm, double input) { return row("vmId", vm, "compatible", true, "inputSeconds", input, "cpuAvailableAt", 0, "computeSeconds", 1, "scoreFinishSeconds", input + 1, "conditionalStoreWait", false, "joinedInputCopies", 0); }
    private static JsonObject vmMeta(int id, double mips, int pes) { return row("id", id, "mips", mips, "pes", pes, "schedulerMode", "SPACE_SHARED"); }
    private static JsonObject computeRequest(int job, int task, long length, int taskPes, int jobPes) { return row("jobId", job, "taskId", task, "taskPes", taskPes, "jobPes", jobPes, "lengthMi", length); }
    private static JsonObject jobRow(int job, int task, int vm, int status) { return row("jobId", job, "classType", Parameters.ClassType.COMPUTE.value, "taskIds", array(task), "taskCount", 1, "vmId", vm, "status", status); }
    private static JsonObject taskRow(int job, int task, int vm, int status, long length, long effective) { return row("jobId", job, "taskId", task, "vmId", vm, "jobStatus", status, "taskStatus", status, "lengthMi", length, "effectiveExecutionLengthMi", effective); }
    private static JsonObject mainEvent(long sequence, String type, int job, int task, int vm, double time, JsonObject attrs) { return row("sequence", sequence, "simulationTime", time, "type", type, "jobId", job, "vmId", vm, "classType", Parameters.ClassType.COMPUTE.value, "taskIds", array(task), "attributes", attrs); }
    private static void lifeEvent(JsonArray events, String type, double at, JsonObject payload) { events.add(row("sequence", events.size() + 1, "observedTime", at, "type", type, "payload", payload)); }
    private static JsonObject row(Object... values) { JsonObject row = new JsonObject(); for (int i = 0; i < values.length; i += 2) row.add((String) values[i], values[i + 1] instanceof JsonElement ? (JsonElement) values[i + 1] : JSON.toJsonTree(values[i + 1])); return row; }
    private static JsonArray array(Object... values) { JsonArray array = new JsonArray(); for (Object value : values) array.add(value instanceof JsonElement ? (JsonElement) value : JSON.toJsonTree(value)); return array; }
    private static JsonObject source() { return row("kind", "SOURCE", "vmId", null, "sourceId", "source"); }
    private static JsonObject vm(int id) { return row("kind", "VM", "vmId", id, "sourceId", null); }
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static Task task(int id, long length, FileItem... files) { Task task = new Task(id, length); for (FileItem file : files) task.addFile(file); return task; }
    private static FileItem in(String name, double bytes) { FileItem file = new FileItem(name, bytes); file.setType(Parameters.FileType.INPUT); return file; }
    private static FileItem out(String name, double bytes) { FileItem file = new FileItem(name, bytes); file.setType(Parameters.FileType.OUTPUT); return file; }
    private static void edge(Task a, Task b) { a.addChild(b); b.addParent(a); }
    private static void costs(Task task, double a, double b) { Map<Integer, Double> costs = new LinkedHashMap<>(); costs.put(7, a); costs.put(42, b); task.setVmExecutionCostSeconds(costs); }
    private static DataTransferFabric endpoints(double a, double b) { Map<Integer, Double> caps = new LinkedHashMap<>(); caps.put(7, a); caps.put(42, b); return DataTransferFabric.endpoints(caps, Collections.singletonList("source")); }
    private static DataTransferFabric storageFabric(double a, double b, double read, double write, double nic, boolean tree) {
        Map<Integer, Double> caps = new LinkedHashMap<>(); caps.put(7, a); caps.put(42, b);
        Map<Integer, Integer> hosts = new LinkedHashMap<>(); hosts.put(7, 10); hosts.put(42, 20);
        FatTreeTopology topology = null;
        if (tree) { Map<Integer, Integer> placements = new LinkedHashMap<>(); placements.put(10, 0); placements.put(20, 3);
            topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, .00002, null, placements), Arrays.asList(10, 20)); }
        return DataTransferFabric.withStorage(caps, hosts, topology, DataflowStorageSpec.of(10, read / 1_000_000.0, write / 1_000_000.0, nic / 1_000_000.0));
    }
    private static void bad(Fixture fixture) { assertThrows(IllegalArgumentException.class, fixture::validate); }
    private static JsonObject config(Fixture f) { return f.manifest.getAsJsonObject("configuration"); }
    private static JsonObject action(Fixture f, int job) { return event(f.main, "DATAFLOW_VM_ASSIGNED", job); }
    private static JsonObject attrs(Fixture f, int job) { return action(f, job).getAsJsonObject("attributes"); }
    private static JsonObject observation(Fixture f, int job) { return attrs(f, job).getAsJsonObject("observation"); }
    private static JsonObject candidate(Fixture f, int job, int vm) { for (JsonElement value : attrs(f, job).getAsJsonArray("candidates")) { JsonObject row = value.getAsJsonObject(); if (row.get("vmId").getAsInt() == vm) return row; } throw new AssertionError("Missing candidate " + vm); }
    private static JsonObject event(JsonArray events, String type, int job) { return events.get(index(events, type, job)).getAsJsonObject(); }
    private static int index(JsonArray events, String type, int job) { for (int i = 0; i < events.size(); i++) { JsonObject row = events.get(i).getAsJsonObject(); if (row.get("type").getAsString().equals(type) && row.get("jobId").getAsInt() == job) return i; } throw new AssertionError("Missing " + type + "/" + job); }
    private static JsonObject request(Fixture f, int job) { return byJob(f.manifest.getAsJsonArray("dataflowComputeRequests"), job); }
    private static JsonObject jobOutcome(Fixture f, int job) { return byJob(f.manifest.getAsJsonObject("result").getAsJsonArray("jobs"), job); }
    private static JsonObject taskOutcome(Fixture f, int job) { return byJob(f.manifest.getAsJsonObject("result").getAsJsonArray("tasks"), job); }
    private static JsonObject byJob(JsonArray rows, int job) { for (JsonElement value : rows) if (value.getAsJsonObject().get("jobId").getAsInt() == job) return value.getAsJsonObject(); throw new AssertionError("Missing Job " + job); }
    private static JsonObject lifePayload(Fixture f, String type, int ordinal) { for (JsonElement value : f.life.getAsJsonArray("events")) if (value.getAsJsonObject().get("type").getAsString().equals(type) && ordinal-- == 0) return value.getAsJsonObject().getAsJsonObject("payload"); throw new AssertionError("Missing lifecycle " + type); }
    private static void setInput(Fixture f, int job, int vm, double input) { candidate(f, job, vm).addProperty("inputSeconds", input); rescore(f, job, vm); }
    private static void rescore(Fixture f, int job, int vm) { JsonObject row = candidate(f, job, vm); double now = action(f, job).get("simulationTime").getAsDouble(); row.addProperty("scoreFinishSeconds", Math.max(now + row.get("inputSeconds").getAsDouble(), row.get("cpuAvailableAt").getAsDouble()) + row.get("computeSeconds").getAsDouble()); }
    private static void renumber(JsonArray events) { for (int i = 0; i < events.size(); i++) events.get(i).getAsJsonObject().addProperty("sequence", i + 1); }
    private static void removeMain(Fixture f, String type, int job) { f.main.remove(index(f.main, type, job)); renumber(f.main); }
    private static void recount(JsonObject life) { renumber(life.getAsJsonArray("events")); life.getAsJsonObject("capture").addProperty("retainedRecords", life.getAsJsonArray("events").size()); }
    private static void removeLife(Fixture f, String type, int ordinal) { JsonArray rows = f.life.getAsJsonArray("events"); for (int i = 0; i < rows.size(); i++) if (rows.get(i).getAsJsonObject().get("type").getAsString().equals(type) && ordinal-- == 0) { rows.remove(i); recount(f.life); return; } throw new AssertionError("Missing lifecycle removal"); }
    private static void setVmMips(Fixture f, double mips) { for (JsonElement row : f.manifest.getAsJsonObject("platform").getAsJsonArray("vms")) row.getAsJsonObject().addProperty("mips", mips); }
    private static JsonObject matrix(JsonArray entries) { return row("unit", "EXECUTION_SECONDS", "runtimeConversion", "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI", "entries", entries); }
    private static void setMatrix(Fixture f, double a, double b) { config(f).add("taskCostMatrix", matrix(array(row("taskId", 1, "vmId", 7, "executionSeconds", a), row("taskId", 1, "vmId", 42, "executionSeconds", b)))); }
    private static JsonObject matrixEntry(Fixture f, int task, int vm) { for (JsonElement row : config(f).getAsJsonObject("taskCostMatrix").getAsJsonArray("entries")) if (row.getAsJsonObject().get("taskId").getAsInt() == task && row.getAsJsonObject().get("vmId").getAsInt() == vm) return row.getAsJsonObject(); throw new AssertionError("Missing coordinate"); }
    private static void changeAttemptVm(Fixture f, int job, int vm) {
        jobOutcome(f, job).addProperty("vmId", vm); taskOutcome(f, job).addProperty("vmId", vm); attrs(f, job).addProperty("selectedVmId", vm);
        for (JsonElement row : f.main) if (row.getAsJsonObject().get("jobId").getAsInt() == job) row.getAsJsonObject().addProperty("vmId", vm);
        for (JsonElement row : f.life.getAsJsonArray("events")) { JsonObject p = row.getAsJsonObject().getAsJsonObject("payload"); if (p.has("jobId") && p.get("jobId").getAsInt() == job) { if (p.has("vmId")) p.addProperty("vmId", vm); if (p.has("destinationVmId")) p.addProperty("destinationVmId", vm); } }
    }
}
