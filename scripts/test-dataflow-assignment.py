#!/usr/bin/env python3
"""NF005B hand-oracle action/context tests; stdlib only, no Java/subprocess.

Expected actions are literal arithmetic on hand timelines, never constructed
from checker facts. Bundle mutations repair hashes unless testing hash checks.
Run: python3 -B scripts/test-dataflow-assignment.py
"""
import contextlib
import copy
from decimal import Decimal
import importlib.util
import io
import json
import math
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from _network_audit import CheckError, decode_json, inspect_path
import _dataflow_assignment_audit as audit
import _file_lifecycle_context as v2_context
import _storage_lifecycle_context as v3_context


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v2 = load("nf005b_v2_bundles", "test-file-lifecycle-context.py")
v3 = load("nf005b_v3_bundles", "test-storage-lifecycle-context.py")
h2, h3 = v2.hand, v3.hand
cli = load("nf005b_network_cli", "verify-network-ledger.py")
MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1"
POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1"
SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1"
ACTION = "DATAFLOW_VM_ASSIGNED"


def candidate(vm, inputs, cpu, compute, score, wait=False, joined=0):
    return dict(vmId=vm, compatible=True, inputSeconds=inputs, cpuAvailableAt=cpu, computeSeconds=compute,
                scoreFinishSeconds=score, conditionalStoreWait=wait, joinedInputCopies=joined)


def incompatible(vm):
    return dict(vmId=vm, compatible=False, inputSeconds=None, cpuAvailableAt=None, computeSeconds=None,
                scoreFinishSeconds=None, conditionalStoreWait=None, joinedInputCopies=None)


def spec(rows, copies=0, jobs=0, pending=0, binding="INITIAL"):
    return dict(rows=rows, copies=copies, jobs=jobs, pending=pending, binding=binding)


def costs(rows):
    """Coordinates supplied by a hand oracle, not by any checker result."""
    return dict(unit="EXECUTION_SECONDS", runtimeConversion="ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI",
                entries=[dict(taskId=tid, vmId=vm, executionSeconds=seconds)
                         for tid, columns in rows.items() for vm, seconds in columns.items()])


def install(bundle, expected, *, lengths=None, pes=None, mips=None, request_pes=None, matrix=None, effective=None):
    root, config = bundle.manifest, bundle.manifest["configuration"]
    config.update(planningAlgorithm="INVALID", dataflowAssignment=dict(mode=MODE, policy=POLICY), taskCostMatrix=matrix)
    if "generatorMode" not in config["failureModel"]:
        failures = sum(j["status"] == 5 for j in root["result"]["jobs"])
        config["failureModel"] = v3.failure_policy("FAILURE_ALL" if failures else "FAILURE_NONE", failures)
    for vm in root["platform"]["vms"]:
        vm["mips"] = (mips or {}).get(vm["id"], 1)
        vm["pes"] = (pes or {}).get(vm["id"], 1)
        vm["schedulerMode"] = "SPACE_SHARED"
    root["dataflowComputeRequests"] = []
    execution_metadata = {}
    for row in root["result"]["tasks"]:
        tid, jid = row["taskId"], row["jobId"]
        length = (lengths or {}).get(tid, 1)
        effective_length = (effective or {}).get(jid, length)
        task_pes, job_pes = (request_pes or {}).get(tid, (1, 1))
        row.update(lengthMi=length, effectiveExecutionLengthMi=effective_length)
        root["dataflowComputeRequests"].append(dict(jobId=jid, taskId=tid, taskPes=task_pes, jobPes=job_pes, lengthMi=length))
        # These are independently supplied fixture inputs, not checker facts.
        execution_metadata[jid] = dict(taskLengthMi=length, effectiveExecutionLengthMi=effective_length,
                                       taskTimingScope="MODEL_DERIVED_COMPUTE_WINDOW")
    storage = bundle.ledger["schema"] == "workflowsim-storage-lifecycle-v3"
    contended = bundle.ledger["modelKind"] in ("COHERENT_FILE_DATAFLOW_V2", "COHERENT_STORAGE_DATAFLOW_V3")
    task_inputs = {task["taskId"]: task["inputs"] for task in root["dataflowPlan"]["tasks"]}
    events, sequence = [], 0
    for event in bundle.events:
        if event["type"] == "TASK_EXECUTION_MODELED" and event["classType"] == 2:
            event["attributes"].update(execution_metadata[event["jobId"]])
        if event["type"] == "JOB_READY" and event["classType"] == 2:
            sequence += 1
            now, jid = event["simulationTime"], event["jobId"]
            oracle = expected[jid]
            observation = dict(contract="OBSERVED_DATAFLOW_STATE_V1", status="BOUND", storageVersion=storage,
                storeBackedInputs=storage and config["fileSystem"] == "SHARED", interFlowSharing=contended,
                observedThrough=now, serviceThrough=now, activeCopyCount=oracle["copies"], activeJobCount=oracle["jobs"],
                pendingOutputFileCount=oracle["pending"], scopedFileCount=len(task_inputs[event["taskIds"][0]]))
            attrs = dict(auditScope=SCOPE, assignmentMode=MODE, assignmentPolicy=POLICY,
                inputEstimateSemantics="MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1",
                cpuReservationSemantics="ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1",
                assignmentSequence=sequence, binding=oracle["binding"], selectedVmId=event["vmId"],
                observation=observation, candidates=copy.deepcopy(oracle["rows"]))
            action = copy.deepcopy(event)
            action.update(type=ACTION, attributes=attrs)
            events.append(action)
        events.append(event)
    bundle.events = events
    bundle.resequence()
    bundle.write()
    return bundle


def one_task(vm=2, now=0, finish=1, vms=((2, 10), (10, 20), (99, 30))):
    return h2.document([h2.task(0)], [], [h2.request(10, 0, vm, now), h2.ready(10, now),
                       h2.cpu(10, vm, now), h2.finish(10, 0, vm, finish)], h2.endpoints(vms))


def reservation_timeline():
    return h2.document([h2.task(t) for t in range(4)], [], [
        h2.request(10, 0, 2, 0), h2.ready(10, 0), h2.request(11, 1, 2, 1), h2.ready(11, 1),
        h2.cpu(10, 2, 2), h2.request(12, 2, 2, 3), h2.ready(12, 3), h2.cpu(11, 2, 4),
        h2.request(13, 3, 2, 5), h2.ready(13, 5), h2.finish(10, 0, 2, 10), h2.cpu(12, 2, 10),
        h2.finish(11, 1, 2, 11), h2.cpu(13, 2, 11), h2.finish(12, 2, 2, 12), h2.finish(13, 3, 2, 13)],
        h2.endpoints(((2, 10),)))


def ordered_reservations():
    events = []
    for jid, tid in ((30, 0), (20, 1), (10, 2), (40, 3)):
        events += [h2.request(jid, tid, 2, 0), h2.ready(jid, 0)]
    for jid, tid, start, finish in ((30, 0, 1, 1.1), (20, 1, 2, 2.2), (10, 2, 3, 3.3), (40, 3, 4, 4.1)):
        events += [h2.cpu(jid, 2, start), h2.finish(jid, tid, 2, finish)]
    return h2.document([h2.task(t) for t in range(4)], [], events, h2.endpoints(((2, 10),)))


def cached_store():
    doc = h3.cached_peer()
    doc["policies"].update(inputAccess=h3.STORE, selection=h3.STORE)
    for event in doc["events"]:
        p = event["payload"]
        if event["type"] == "COPY_ADMITTED" and p["copyOrdinal"] == 2:
            p.update(sourceReplica=h3.replica("x", None, 0, "EXTERNAL_SEED"),
                     resources=[h3.READ, h3.NIC, "VM:3"], standaloneRate=10, isolatedSeconds=10)
        elif event["type"] == "INPUT_RESOLVED" and p["jobId"] == 11:
            p["source"] = h3.loc()
        elif event["type"] == "COPY_SETTLED" and p["copyOrdinal"] == 2:
            p["effectiveTime"], event["observedTime"] = 24, 25
        elif p.get("jobId") == 11 and event["type"] in ("JOB_DATA_READY", "JOB_CPU_STARTED", "TASK_FINISHED"):
            event["observedTime"] = 26 if event["type"] == "TASK_FINISHED" else 25
    return h3.seal(doc)


class AssignmentTests(unittest.TestCase):
    def bundle(self, factory=one_task, storage=False, **kwargs):
        directory = tempfile.TemporaryDirectory(prefix="nf005b-python-actions-")
        self.addCleanup(directory.cleanup)
        return (v3.Bundle if storage else v2.Bundle)(directory.name, factory, **kwargs)

    def simple(self, **kwargs):
        return install(self.bundle(**kwargs), {10: spec([candidate(2, 0, 0, 1, 1),
            candidate(10, 0, 0, 1, 1), candidate(99, 0, 0, 1, 1)])})

    def action(self, bundle, job=10):
        return bundle.main_event(ACTION, job)

    def row(self, bundle, job=10, index=0):
        return self.action(bundle, job)["attributes"]["candidates"][index]

    def good(self, bundle):
        bundle.write()
        report = inspect_path(bundle.path)
        self.assertEqual("VALID_COMPLETE", report["status"])
        self.assertTrue(report["dataflowAssignmentContextChecked"])
        self.assertEqual(SCOPE, report["dataflowAssignmentScope"])
        self.assertFalse(report["liveProgressReplayed"])
        self.assertFalse(report["fluidServiceAccountingCertified"])
        self.assertTrue(report["artifactReferencesChecked"])
        return report

    def bad(self, bundle, message=None, write=True):
        if write:
            bundle.resequence()
            bundle.write()
        manager = self.assertRaisesRegex(CheckError, message) if message else self.assertRaises(CheckError)
        with manager:
            inspect_path(bundle.path)

    def roundtrip(self, store=False, isolated=False, topology=False):
        b = self.bundle(lambda: h3.roundtrip(store, isolated, topology), storage=True)
        if store:
            rows = [candidate(1, 1, 2, 50, 53, True),
                    candidate(2, 5 if topology else 2, 2, 1, 8 if topology else 5, True),
                    candidate(3, 2, 2, 50, 54, True)]
        else:
            rows = [candidate(1, 0, 2, 50, 52), candidate(2, 4 if topology else 1, 2, 1, 7 if topology else 4),
                    candidate(3, .5, 2, 50, 52.5)]
        return install(b, {10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 50, 50), candidate(3, 0, 0, 50, 50)]),
                           11: spec(rows, copies=1, pending=1)},
                       matrix=costs({1: {1: 1, 2: 50, 3: 50}, 2: {1: 50, 2: 1, 3: 50}}))

    def fanin(self, isolated=False):
        b = self.bundle(h2.fanin, isolated=isolated)
        return install(b, {
            10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 100, 100), candidate(3, 0, 0, 100, 100)]),
            11: spec([candidate(1, 0, 1, 100, 101), candidate(2, 10, 1, 1, 12), candidate(3, 10, 1, 100, 111)]),
            12: spec([candidate(1, 0, 2, 100, 102), candidate(2, .5, 3, 1, 4, joined=1), candidate(3, 1, 2, 100, 103)], copies=2, jobs=1),
            13: spec([candidate(1, 0, 5, 100, 105), candidate(2, 0, 6, 100, 106), candidate(3, .2, 5, 1, 6.2)], copies=1, jobs=1)},
            matrix=costs({1: {1: 1, 2: 100, 3: 100}, 2: {1: 100, 2: 1, 3: 100},
                          3: {1: 100, 2: 1, 3: 100}, 4: {1: 100, 2: 100, 3: 1}}))

    def retry(self, storage=True):
        def v2_retry():
            return h2.document([h2.task(0)], [], [h2.request(10, 0, 1, 0), h2.ready(10, 0), h2.cpu(10, 1, 0),
                h2.finish(10, 0, 1, 1, False), h2.request(11, 0, 1, 2), h2.ready(11, 2), h2.cpu(11, 1, 2),
                h2.finish(11, 0, 1, 3)], h2.endpoints(((1, 40), (2, 40))))
        b = self.bundle(v3.retry_only if storage else v2_retry, storage=storage)
        return install(b, {10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 100, 100)]),
                           11: spec([candidate(1, 0, 2, 1, 3)], binding="RETRY_REUSE")},
                       matrix=costs({1: {1: 1, 2: 100}}))

    def metadata_bundle(self, storage=False):
        def storage_task():
            return h3.document([h3.task(0)], [], [h3.request(10, 0, 2, 0), h3.ready(10, 0), h3.cpu(10, 2, 0),
                               h3.finish(10, 0, 2, 1)], h3.fabric(((2, 40, 10),)))
        b = self.bundle(storage_task if storage else lambda: one_task(vms=((2, 10),)), storage=storage)
        return install(b, {10: spec([candidate(2, 0, 0, 1, 1)])}, lengths={1: 1000}, mips={2: 1000})

    def test_eight_storage_model_input_policy_topology_combinations(self):
        for isolated in (False, True):
            for store in (False, True):
                for topology in (False, True):
                    with self.subTest(isolated=isolated, store=store, topology=topology):
                        report = self.good(self.roundtrip(store, isolated, topology))
                        self.assertEqual([1, 2], [f["selectedVmId"] for f in report["assignmentFacts"]])
                        self.assertEqual(2, report["validatedAssignmentCount"])
                        self.assertEqual(store, report["assignmentFacts"][1]["candidates"][1]["conditionalStoreWait"])

    def test_v2_both_capacity_kinds_with_and_without_topology(self):
        for isolated in (False, True):
            for topology in (False, True):
                with self.subTest(isolated=isolated, topology=topology):
                    b = self.bundle(h2.fat_document if topology else h2.publication, isolated=isolated)
                    other_input = 100 / 30 if topology else 10
                    install(b, {100: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 50, 50), candidate(3, 0, 0, 50, 50)]),
                        101: spec([candidate(1, 0, 2, 50, 52), candidate(2, 10, 2, 1, 13),
                                   candidate(3, other_input, 2, 50, 55.333333333333336 if topology else 62)])},
                        matrix=costs({1: {1: 1, 2: 50, 3: 50}, 2: {1: 50, 2: 1, 3: 50}}))
                    self.assertEqual(2, self.good(b)["validatedAssignmentCount"])

    def test_old_configuration_absence_keeps_original_acceptance_and_shape(self):
        for storage, factory in ((False, h2.publication), (True, lambda: h3.roundtrip(True))):
            for enabled in (False, True):
                with self.subTest(storage=storage, enabled=enabled):
                    b = self.bundle(factory, storage=storage, enabled=enabled)
                    report = inspect_path(b.path)
                    self.assertEqual("VALID_COMPLETE" if enabled else "DISABLED", report["status"])
                    self.assertNotIn("dataflowAssignmentContextChecked", report)
                    self.assertNotIn("assignmentFacts", report)
                    public = v3_context if storage else v2_context
                    if enabled:
                        expected = public.verify_context(b.manifest, b.ledger, b.events)
                        self.assertEqual(report, dict(expected, artifactReferencesChecked=True))

    def test_public_audit_and_existing_contexts_agree_and_are_detached(self):
        for b in (self.simple(), self.roundtrip(True)):
            before = copy.deepcopy((b.manifest, b.ledger, b.events))
            public = v3_context if b.ledger["schema"].endswith("v3") else v2_context
            with mock.patch.object(public, "verify_document", wraps=public.verify_document) as base:
                result = audit.verify_context(b.manifest, b.ledger, b.events)
                self.assertEqual(1, base.call_count)
            self.assertEqual(result, public.verify_context(b.manifest, b.ledger, b.events))
            self.assertEqual(before, (b.manifest, b.ledger, b.events))
            result["assignmentFacts"][0]["candidates"][0]["vmId"] = 999
            result["assignmentFacts"][0]["observation"]["activeJobCount"] = 999
            self.assertEqual(before, (b.manifest, b.ledger, b.events))
            json.dumps(result, allow_nan=False)

    def test_global_main_sequence_zero_and_null_noncompute_preamble(self):
        for b in (self.simple(), self.roundtrip(True)):
            # Real main streams begin with a zero-based non-compute event, whose
            # identities/class are null; assignmentSequence is independently 1.
            b.events.insert(0, dict(sequence=0, simulationTime=0, type="WORKFLOW_PARSED", jobId=None,
                                    vmId=None, classType=None, taskIds=[], attributes={}))
            b.resequence()
            report = self.good(b)
            self.assertEqual(0, b.events[0]["sequence"])
            self.assertEqual(1, report["assignmentFacts"][0]["assignmentSequence"])
            self.assertEqual(1, next(e for e in b.events if e["type"] == ACTION)["sequence"])
            # Gaps in global sequencing still reject independently of actions.
            b.events[0]["sequence"] = 1
            with self.assertRaisesRegex(CheckError, "nonconsecutive main event sequence"):
                audit.verify_context(b.manifest, b.ledger, b.events)

    def test_actual_vm_id_tie_break_not_text_or_declaration_order(self):
        b = self.simple()
        b.manifest["platform"]["vms"].reverse()
        self.assertEqual(2, self.good(b)["assignmentFacts"][0]["selectedVmId"])
        self.action(b)["attributes"]["selectedVmId"] = 10
        self.bad(b, "selected VM")

    def test_nonminimal_selected_binding_rejected_even_when_anchors_match(self):
        b = self.bundle(lambda: one_task(vm=10))
        install(b, {10: spec([candidate(2, 0, 0, 1, 1), candidate(10, 0, 0, 1, 1), candidate(99, 0, 0, 1, 1)])})
        self.bad(b, "argmin")

    def test_candidate_coverage_order_duplicates_unknown_and_missing(self):
        for mutation in (lambda r: r.reverse(), lambda r: r.pop(), lambda r: r.append(copy.deepcopy(r[0])),
                         lambda r: r[0].update(vmId=1)):
            b = self.simple()
            mutation(self.action(b)["attributes"]["candidates"])
            self.bad(b, "candidate rows")

    def test_pe_compatibility_checks_task_and_job_pes_and_null_rows(self):
        b = self.bundle(lambda: one_task(vm=10, vms=((2, 10), (10, 20))))
        install(b, {10: spec([incompatible(2), candidate(10, 0, 0, 1, 1)])},
                pes={2: 1, 10: 4}, request_pes={1: (2, 3)})
        self.good(b)
        for key, value in (("compatible", True), ("computeSeconds", 0), ("conditionalStoreWait", False), ("joinedInputCopies", 0)):
            saved = copy.deepcopy(self.row(b))
            self.row(b)[key] = value
            self.bad(b)
            self.action(b)["attributes"]["candidates"][0] = saved
        b.manifest["dataflowComputeRequests"][0].update(taskPes=1, jobPes=1)
        self.bad(b, "compatibility")

    def test_task_pes_and_job_pes_independently_exclude_a_vm(self):
        for requested in ((2, 1), (1, 2)):
            b = self.bundle(lambda: one_task(vm=10, vms=((2, 10), (10, 20))))
            install(b, {10: spec([incompatible(2), candidate(10, 0, 0, 1, 1)])}, pes={10: 2}, request_pes={1: requested})
            self.good(b)
            self.row(b)["compatible"] = True
            self.bad(b, "compatibility")

    def test_raw_mi_without_matrix_and_selected_effective_mi(self):
        b = self.bundle(lambda: one_task(vms=((2, 10),), finish=2.5))
        install(b, {10: spec([candidate(2, 0, 0, 2.5, 2.5)])}, lengths={1: 5}, mips={2: 2})
        self.assertEqual(5, self.good(b)["assignmentFacts"][0]["effectiveExecutionLengthMi"])
        b.manifest["result"]["tasks"][0]["effectiveExecutionLengthMi"] = 6
        self.bad(b, "selected effective MI")

    def test_matrix_rounding_uses_binary64_product_then_positive_ties(self):
        for seconds, mips, expected_mi, compute in ((.5, 1, 1, 1), (1.5, 1, 2, 2), (2.5, 1, 3, 3),
                                                   (.145, 100, 14, .14), (.015, 100, 2, .02)):
            with self.subTest(seconds=seconds, mips=mips):
                b = self.bundle(lambda: one_task(vms=((2, 10),), finish=compute))
                install(b, {10: spec([candidate(2, 0, 0, compute, compute)])}, lengths={1: 77}, mips={2: mips},
                        matrix=costs({1: {2: seconds}}), effective={10: expected_mi})
                self.assertEqual(expected_mi, self.good(b)["assignmentFacts"][0]["effectiveExecutionLengthMi"])

    def test_rounding_oracle_avoids_naive_half_addition_at_two_to_52(self):
        for raw, expected in ((.5, 1), (2.5, 3), (float(2**52 + 1), 2**52 + 1),
                              (float(2**52 + 3), 2**52 + 3), (float(2**53), 2**53)):
            self.assertEqual(expected, audit._round_positive_binary64(raw))
        self.assertNotEqual(2**52 + 1, math.floor(float(2**52 + 1) + .5))

    def test_matrix_zero_round_overflow_and_effective_pe_cap_rejected(self):
        for seconds in (.49, 1e309, 2**52 + 1):
            b = self.bundle(lambda: one_task(vms=((2, 10),)))
            install(b, {10: spec([candidate(2, 0, 0, 1, 1)])})
            b.manifest["configuration"]["taskCostMatrix"] = costs({1: {2: seconds}})
            if math.isfinite(seconds):
                self.bad(b)
            else:
                with self.assertRaises(CheckError):
                    audit.verify_context(b.manifest, b.ledger, b.events)
        maximum = 9_223_372_036_854
        b = self.bundle(lambda: one_task(vms=((2, 10),), finish=maximum / 2))
        install(b, {10: spec([candidate(2, 0, 0, maximum / 2, maximum / 2)])}, lengths={1: maximum // 2},
                pes={2: 2}, request_pes={1: (2, 1)})
        self.good(b)
        b.manifest["dataflowComputeRequests"][0]["lengthMi"] += 1
        b.manifest["result"]["tasks"][0]["lengthMi"] += 1
        self.bad(b, "PE-scaled")

    def test_matrix_coordinates_are_complete_keyed_and_strict(self):
        for mutate in (lambda m: m["entries"].pop(), lambda m: m["entries"].append(copy.deepcopy(m["entries"][0])),
                       lambda m: m["entries"][0].update(vmId=0), lambda m: m["entries"][0].update(taskId=0),
                       lambda m: m["entries"][0].update(executionSeconds="1"), lambda m: m.update(extra=True)):
            b = self.simple()
            b.manifest["configuration"]["taskCostMatrix"] = costs({1: {2: 1, 10: 1, 99: 1}})
            mutate(b.manifest["configuration"]["taskCostMatrix"])
            self.bad(b)

    def test_matrix_modern_unit_and_conversion_envelope_is_closed(self):
        b = self.simple()
        b.manifest["configuration"]["taskCostMatrix"] = costs({1: {2: 1, 10: 1, 99: 1}})
        self.good(b)
        for key in ("unit", "runtimeConversion"):
            for value in ("OTHER", None, True):
                b = self.simple()
                matrix = costs({1: {2: 1, 10: 1, 99: 1}})
                matrix[key] = value
                b.manifest["configuration"]["taskCostMatrix"] = matrix
                self.bad(b)
            b = self.simple()
            matrix = costs({1: {2: 1, 10: 1, 99: 1}})
            matrix.pop(key)
            b.manifest["configuration"]["taskCostMatrix"] = matrix
            self.bad(b, "taskCostMatrix")
        b = self.simple()
        b.manifest["configuration"]["taskCostMatrix"] = dict(entries=costs({1: {2: 1, 10: 1, 99: 1}})["entries"])
        self.bad(b, "taskCostMatrix")

    def test_legal_extra_matrix_coordinates_preserve_core_data_and_acceptance(self):
        b = self.simple()
        matrix = costs({1: {2: 1, 10: 1, 99: 1}, 0: {0: .25}, 2147483647: {2147483647: 7}})
        b.manifest["configuration"]["taskCostMatrix"] = matrix
        before = copy.deepcopy(matrix)
        self.good(b)
        self.assertEqual(before, b.manifest["configuration"]["taskCostMatrix"])
        matrix["entries"].append(copy.deepcopy(matrix["entries"][-1]))
        self.bad(b, "duplicate task-cost")

    def test_source_bounds_max_files_not_reference_weighted_sum_and_cache_route(self):
        report = self.good(self.fanin())
        rows = report["assignmentFacts"]
        self.assertEqual(10, rows[1]["candidates"][1]["inputBounds"]["lowerSeconds"])
        self.assertEqual(.2, rows[3]["candidates"][2]["inputBounds"]["lowerSeconds"])
        self.assertEqual(1, rows[2]["candidates"][1]["joinedInputCopies"])
        self.assertEqual((1, 1), tuple(rows[3]["observation"][key] for key in ("activeCopyCount", "activeJobCount")))

    def test_within_bounds_logged_progress_drift_is_permitted_not_live_replay(self):
        for isolated in (False, True):
            for value in (.125, .75, 1):
                b = self.fanin(isolated)
                self.row(b, 12, 1)["inputSeconds"] = value  # CPU availability=3 dominates all these estimates.
                report = self.good(b)
                self.assertFalse(report["liveProgressReplayed"])
                self.assertFalse(report["assignmentFacts"][2]["candidates"][1]["inputBounds"]["knownExact"])

    def test_join_bound_false_zero_outside_upper_and_join_flag_rejected(self):
        for key, value in (("inputSeconds", 0), ("inputSeconds", 1.0001), ("joinedInputCopies", 0),
                           ("joinedInputCopies", 2), ("conditionalStoreWait", True)):
            b = self.fanin()
            self.row(b, 12, 1)[key] = value
            self.bad(b)

    def test_exact_new_copy_input_cannot_drift_with_repaired_score(self):
        b = self.fanin()
        self.row(b, 11, 1).update(inputSeconds=9, scoreFinishSeconds=11)
        self.bad(b, "outside nominal")
        b = self.fanin()
        self.row(b, 13, 2).update(inputSeconds=1, scoreFinishSeconds=7)  # Ignore faster VM2 cache.
        self.bad(b, "outside nominal")

    def test_local_vs_shared_source_policy_is_independent_of_capacity_kind(self):
        matrix = costs({1: {1: 100, 2: 1, 3: 100}, 2: {1: 100, 2: 100, 3: 1}})
        for shared in (False, True):
            b = self.bundle(cached_store if shared else h3.cached_peer, storage=True)
            rows = ([candidate(1, 10, 14, 100, 124), candidate(2, 0, 14, 100, 114), candidate(3, 10, 14, 1, 25)] if shared else
                    [candidate(1, 2.5, 14, 100, 116.5), candidate(2, 0, 14, 100, 114), candidate(3, 2, 14, 1, 17)])
            install(b, {10: spec([candidate(1, 10, 0, 100, 110), candidate(2, 10, 0, 1, 11), candidate(3, 10, 0, 100, 110)]),
                        11: spec(rows)}, matrix=matrix)
            self.good(b)
            if shared:
                self.row(b, 11, 2).update(inputSeconds=2, scoreFinishSeconds=17)
                self.bad(b, "outside nominal")

    def test_conditional_store_wait_bounds_and_positive_postread_lower_equality(self):
        for value, score in ((1, 4), (2.5, 5.5), (3, 6)):
            b = self.roundtrip(True)
            self.row(b, 11, 1).update(inputSeconds=value, scoreFinishSeconds=score)
            report = self.good(b)
            bounds = report["assignmentFacts"][1]["candidates"][1]["inputBounds"]
            self.assertEqual(dict(lowerSeconds=1, upperSeconds=3, lowerExclusive=False, knownExact=False), bounds)
        for value in (0, .999, 3.001):
            b = self.roundtrip(True)
            self.row(b, 11, 1).update(inputSeconds=value, scoreFinishSeconds=3 + value)
            self.bad(b, "outside nominal")

    def test_shared_same_vm_cache_still_waits_for_source_commit_first(self):
        b = self.bundle(h3.same_vm_gate, storage=True)
        install(b, {10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 100, 100), candidate(3, 0, 0, 100, 100)]),
                    11: spec([candidate(1, 1, 2, 1, 4, True), candidate(2, 2, 2, 100, 104, True),
                              candidate(3, 2, 2, 100, 104, True)], copies=1, pending=1)},
                matrix=costs({1: {1: 1, 2: 100, 3: 100}, 2: {1: 1, 2: 100, 3: 100}}))
        bounds = self.good(b)["assignmentFacts"][1]["candidates"][0]["inputBounds"]
        self.assertEqual(dict(lowerSeconds=0, upperSeconds=2, lowerExclusive=True, knownExact=False), bounds)
        self.row(b, 11).update(inputSeconds=0, scoreFinishSeconds=3)
        self.bad(b, "outside nominal")

    def test_false_conditional_flag_rejected_even_when_logged_input_is_in_bounds(self):
        for changed in (False, 0, "true", None):
            b = self.roundtrip(True)
            self.row(b, 11, 1)["conditionalStoreWait"] = changed
            self.bad(b)

    def test_cpu_reservations_use_observed_start_not_future_actual_finish(self):
        b = self.bundle(reservation_timeline)
        install(b, {10: spec([candidate(2, 0, 0, 5, 5)]), 11: spec([candidate(2, 0, 6, 2, 8)], jobs=1),
                    12: spec([candidate(2, 0, 9, 3, 12)], jobs=2), 13: spec([candidate(2, 0, 11, 1, 12)], jobs=3)},
                lengths={1: 5, 2: 2, 3: 3, 4: 1})
        rows = self.good(b)["assignmentFacts"]
        self.assertEqual([0, 6, 9, 11], [f["candidates"][0]["cpuAvailableAt"] for f in rows])
        self.row(b, 12).update(cpuAvailableAt=12, scoreFinishSeconds=15)
        self.bad(b, "CPU reservation")

    def test_binary64_cpu_sum_follows_request_order_not_job_id(self):
        b = self.bundle(ordered_reservations)
        install(b, {30: spec([candidate(2, 0, 0, .1, .1)]),
                    20: spec([candidate(2, 0, .1, .2, .30000000000000004)], jobs=1),
                    10: spec([candidate(2, 0, .30000000000000004, .3, .6000000000000001)], jobs=2),
                    40: spec([candidate(2, 0, .6000000000000001, .1, .7000000000000001)], jobs=3)},
                lengths={1: 1, 2: 2, 3: 3, 4: 1}, mips={2: 10})
        b.manifest["result"]["jobs"].reverse()
        b.manifest["result"]["tasks"].reverse()
        self.good(b)
        self.row(b, 40).update(cpuAvailableAt=.6, scoreFinishSeconds=.7)
        self.bad(b, "CPU reservation")

    def test_live_job_can_have_zero_remaining_reservation(self):
        def timeline():
            return h2.document([h2.task(0), h2.task(1)], [], [h2.request(10, 0, 2, 0), h2.ready(10, 0), h2.cpu(10, 2, 0),
                h2.request(11, 1, 2, 3), h2.ready(11, 3), h2.finish(10, 0, 2, 5), h2.cpu(11, 2, 5), h2.finish(11, 1, 2, 6)],
                h2.endpoints(((2, 10),)))
        b = install(self.bundle(timeline), {10: spec([candidate(2, 0, 0, 1, 1)]), 11: spec([candidate(2, 0, 3, 1, 4)], jobs=1)})
        self.good(b)
        self.action(b, 11)["attributes"]["observation"]["activeJobCount"] = 0
        self.bad(b, "activeJobCount")

    def test_static_noop_retries_reuse_binding_in_both_lifecycle_versions(self):
        for storage in (False, True):
            report = self.good(self.retry(storage))
            self.assertEqual(["INITIAL", "RETRY_REUSE"], [f["binding"] for f in report["assignmentFacts"]])
            self.assertEqual([2, 1], [len(f["candidates"]) for f in report["assignmentFacts"]])

    def test_retry_binding_candidate_set_and_predecessor_forgeries(self):
        for mutation in (lambda b: self.action(b, 11)["attributes"].update(binding="INITIAL"),
                         lambda b: self.action(b, 10)["attributes"].update(binding="RETRY_REUSE"),
                         lambda b: self.action(b, 11)["attributes"]["candidates"].append(candidate(2, 0, 2, 100, 102)),
                         lambda b: b.main_event("RETRY_JOB_CREATED", 11)["attributes"].update(failedJobId=11),
                         lambda b: b.main_event("JOB_READY", 11)["attributes"].update(retryOfFailedJobId=12)):
            b = self.retry()
            mutation(b)
            self.bad(b)

    def test_retry_cannot_remap_vm_with_repaired_attempt_anchors(self):
        b = self.retry()
        v3.remap_failed_attempt(b, job=11, vm=2)
        self.action(b, 11)["attributes"]["selectedVmId"] = 2
        self.row(b, 11).update(vmId=2, computeSeconds=100, scoreFinishSeconds=102)
        b.manifest["result"]["tasks"][1]["effectiveExecutionLengthMi"] = 100
        self.bad(b, "STATIC NOOP|binding")

    def test_retry_task_metadata_is_stable_even_when_each_result_raw_length_agrees(self):
        for key in ("lengthMi", "taskPes", "jobPes"):
            b = self.retry()
            b.manifest["dataflowComputeRequests"][1][key] = 2
            if key == "lengthMi":
                b.manifest["result"]["tasks"][1][key] = 2
            self.bad(b, "metadata changes")

    def test_retry_budget_disabled_failure_and_modern_policy_hardening(self):
        for storage in (False, True):
            for mutation in (lambda p: p.update(maxTotalRetryJobs=0), lambda p: p.update(generatorMode="FAILURE_NONE"),
                             lambda p: p.update(maxTotalRetryJobs=True), lambda p: p.update(monitorMode="MONITOR_ALL"),
                             lambda p: p.update(clusteringAlgorithm="FTCLUSTERING_DC"), lambda p: p.update(generators=[])):
                b = self.retry(storage)
                mutation(b.manifest["configuration"]["failureModel"])
                self.bad(b)

    def test_scoped_file_count_is_unique_current_inputs_not_plan_refs_or_copies(self):
        # Four declared files: external plus three outputs. The producer requests
        # one input; its child requests x twice and zero once, all local metadata.
        # Thus scoped counts are 1,2, not plan=4, child references=3 or copies=0.
        for storage in (False, True):
            h = h3 if storage else h2
            def timeline():
                tasks = [h.task(0, inputs=[("external", 1)], outputs=["unused", "x", "zero"]),
                         h.task(1, [0], [("x", 2), ("zero", 1)])]
                files = [h.file("external", 0), h.file("unused", 0, 0), h.file("x", 0, 0), h.file("zero", 0, 0)]
                events = [h.seed("external"), h.request(10, 0, 1, 0), h.resolve(10, "external", "ZERO", None, 0),
                          h.ready(10, 0), h.cpu(10, 1, 0), h.finish(10, 0, 1, 1)]
                if storage:
                    events += [h.output(10, 0, name, "ZERO", 1, 1) for name in ("unused", "x", "zero")]
                events += [h.request(11, 1, 1, 1), h.resolve(11, "x", "LOCAL", 1, 1, count=2),
                           h.resolve(11, "zero", "LOCAL", 1, 1), h.ready(11, 1), h.cpu(11, 1, 1), h.finish(11, 1, 1, 2)]
                if storage:
                    return h.document(tasks, files, events, h.fabric(((1, 40, 10),)), store_inputs=True)
                return h.document(tasks, files, events, h.endpoints(((1, 40),)))
            b = install(self.bundle(timeline, storage=storage), {
                10: spec([candidate(1, 0, 0, 1, 1)]), 11: spec([candidate(1, 0, 1, 1, 2)])})
            self.assertEqual([1, 2], [self.action(b, job)["attributes"]["observation"]["scopedFileCount"] for job in (10, 11)])
            report = self.good(b)
            self.assertEqual(4, report["fileCount"])
            self.assertEqual(0, report["admissionCount"])
            self.assertEqual(3, b.main_event("DATA_STAGE_IN_MODELED", 11)["attributes"]["modeledTransferFileCount"])
            self.assertEqual([1, 2], [fact["observation"]["scopedFileCount"] for fact in report["assignmentFacts"]])
            for false_count in (0, 1, 3, 4):
                self.action(b, 11)["attributes"]["observation"]["scopedFileCount"] = false_count
                self.bad(b, "scopedFileCount")
            self.action(b, 11)["attributes"]["observation"]["scopedFileCount"] = 2
            self.action(b, 10)["attributes"]["observation"]["scopedFileCount"] = 4
            self.bad(b, "scopedFileCount")

    def test_every_observation_field_and_global_counter_is_checked(self):
        changes = {"contract": "other", "status": "UNBOUND", "storageVersion": False, "storeBackedInputs": False,
                   "interFlowSharing": False, "observedThrough": 1, "serviceThrough": 1, "activeCopyCount": 0,
                   "activeJobCount": 1, "pendingOutputFileCount": 0, "scopedFileCount": 2}
        for key, value in changes.items():
            b = self.roundtrip(True)
            self.action(b, 11)["attributes"]["observation"][key] = value
            self.bad(b)

    def test_observation_counts_exclude_current_job_and_current_copies(self):
        b = self.fanin()
        self.action(b, 11)["attributes"]["observation"].update(activeJobCount=1, activeCopyCount=2)
        self.bad(b, "activeCopyCount|activeJobCount")

    def test_action_identity_class_and_task_membership_are_not_coercible(self):
        for key, value in (("jobId", 11), ("vmId", 10), ("taskIds", [2]), ("taskIds", [1, 1]), ("classType", 1),
                           ("classType", True), ("jobId", "10"), ("vmId", 2**31)):
            b = self.simple()
            self.action(b)[key] = value
            self.bad(b)

    def test_exactly_one_action_per_compute_attempt(self):
        b = self.simple()
        b.events.remove(self.action(b))
        self.bad(b, "one assignment")
        b = self.simple()
        b.events.insert(0, copy.deepcopy(self.action(b)))
        self.bad(b)

    def test_action_clock_ready_anchor_and_before_ready_order(self):
        b = self.simple()
        action = self.action(b)
        b.events.remove(action)
        b.events.insert(1, action)
        self.bad(b, "precede JOB_READY")
        b = self.roundtrip(True)
        self.action(b, 11)["simulationTime"] = 1.5
        self.bad(b, "request clock")
        b = self.roundtrip(True)
        b.main_event("JOB_READY", 11)["simulationTime"] = 1.5
        self.bad(b)

    def test_assignment_sequence_follows_global_main_and_lifecycle_order(self):
        for value in (0, 2, True, "1", Decimal("1.0000000000000000001"), 2**63):
            b = self.simple()
            self.action(b)["attributes"]["assignmentSequence"] = value
            if isinstance(value, Decimal):
                with self.assertRaises(CheckError):
                    audit.verify_context(b.manifest, b.ledger, b.events)
            else:
                self.bad(b)
        b = self.fanin()
        self.action(b, 12)["attributes"]["assignmentSequence"] = 2
        self.bad(b, "assignmentSequence")

    def test_schema_exactness_for_attributes_observation_candidates_and_requests(self):
        for select in (lambda b: self.action(b)["attributes"], lambda b: self.action(b)["attributes"]["observation"],
                       lambda b: self.row(b), lambda b: b.manifest["dataflowComputeRequests"][0],
                       lambda b: b.manifest["configuration"]["dataflowAssignment"]):
            for unknown in (True, False):
                b = self.simple()
                target = select(b)
                if unknown:
                    target["unknown"] = 0
                else:
                    target.pop(next(iter(target)))
                self.bad(b)

    def test_every_action_semantics_tag_is_exact(self):
        for key in ("auditScope", "assignmentMode", "assignmentPolicy", "inputEstimateSemantics", "cpuReservationSemantics"):
            b = self.simple()
            self.action(b)["attributes"][key] = "OTHER"
            self.bad(b)

    def test_strict_numeric_candidates_and_exact_java_counter_ranges(self):
        for key, value in (("inputSeconds", "0"), ("cpuAvailableAt", False), ("computeSeconds", True),
                           ("scoreFinishSeconds", None), ("joinedInputCopies", 2**31), ("joinedInputCopies", .5),
                           ("joinedInputCopies", False), ("compatible", 1), ("conditionalStoreWait", 0)):
            b = self.simple()
            self.row(b)[key] = value
            self.bad(b)
        for value in (float("nan"), float("inf"), Decimal("1e-999")):
            b = self.simple()
            self.row(b)["computeSeconds"] = value
            with self.assertRaises(CheckError):
                audit.verify_context(b.manifest, b.ledger, b.events)
        for value in (True, "0", Decimal("0.0000000000000000001"), 2**31):
            b = self.simple()
            self.action(b)["attributes"]["observation"]["activeJobCount"] = value
            with self.assertRaises(CheckError):
                audit.verify_context(b.manifest, b.ledger, b.events)

    def test_compute_request_coverage_ids_length_and_pes(self):
        for key, value in (("jobId", 999), ("taskId", 2), ("lengthMi", 2), ("taskPes", 0), ("jobPes", -1),
                           ("taskPes", 2**31), ("jobPes", True), ("lengthMi", 2**63), ("lengthMi", "1")):
            b = self.simple()
            b.manifest["dataflowComputeRequests"][0][key] = value
            self.bad(b)
        for mutation in (lambda r: r.pop(), lambda r: r.append(copy.deepcopy(r[0]))):
            b = self.simple()
            mutation(b.manifest["dataflowComputeRequests"])
            self.bad(b)
        b = self.simple()
        b.manifest["result"]["tasks"][0].pop("lengthMi")
        self.bad(b)

    def test_platform_pes_mips_and_selected_mi_strictness(self):
        for key, value in (("pes", 0), ("pes", True), ("pes", 2**31), ("mips", 0), ("mips", "1"), ("mips", False)):
            b = self.simple()
            b.manifest["platform"]["vms"][0][key] = value
            self.bad(b)
        for value in (True, "1", 0, 2**63):
            b = self.simple()
            b.manifest["result"]["tasks"][0]["effectiveExecutionLengthMi"] = value
            self.bad(b)

    def test_online_execution_raw_mi_rehashed_forgery(self):
        for storage in (False, True):
            with self.subTest(storage=storage):
                b = self.metadata_bundle(storage)
                self.good(b)
                b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"]["taskLengthMi"] = 1
                self.bad(b)

    def test_online_execution_effective_mi_rehashed_forgery(self):
        for storage in (False, True):
            with self.subTest(storage=storage):
                b = self.metadata_bundle(storage)
                self.good(b)
                b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"]["effectiveExecutionLengthMi"] = 1
                self.bad(b)

    def test_online_execution_scope_rehashed_forgeries(self):
        for storage in (False, True):
            for false_scope in ("OTHER", "model_derived_compute_window", None, True):
                with self.subTest(storage=storage, false_scope=false_scope):
                    b = self.metadata_bundle(storage)
                    b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"]["taskTimingScope"] = false_scope
                    self.bad(b)

    def test_online_execution_required_fields_and_strict_numeric_mi(self):
        for storage in (False, True):
            for key in ("taskLengthMi", "effectiveExecutionLengthMi", "taskTimingScope"):
                with self.subTest(storage=storage, missing=key):
                    b = self.metadata_bundle(storage)
                    b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"].pop(key)
                    self.bad(b)
            for key in ("taskLengthMi", "effectiveExecutionLengthMi"):
                for value in ("1000", True, None, 0, 1000.5, 2**63):
                    with self.subTest(storage=storage, key=key, value=value):
                        b = self.metadata_bundle(storage)
                        b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"][key] = value
                        self.bad(b)

    def test_online_public_audit_checks_execution_metadata_after_private_base(self):
        for storage in (False, True):
            with self.subTest(storage=storage):
                b = self.metadata_bundle(storage)
                b.main_event("TASK_EXECUTION_MODELED", 10)["attributes"]["effectiveExecutionLengthMi"] = 1
                with self.assertRaises(CheckError):
                    audit.verify_context(b.manifest, b.ledger, b.events)

    def test_online_scheduler_mode_is_required_exact_space_shared(self):
        for storage in (False, True):
            for mode in ("MISSING", None, "TIME_SHARED", "space_shared", True):
                with self.subTest(storage=storage, mode=mode):
                    b = self.metadata_bundle(storage)
                    vm = b.manifest["platform"]["vms"][0]
                    if mode == "MISSING":
                        vm.pop("schedulerMode")
                    else:
                        vm["schedulerMode"] = mode
                    self.bad(b)

    def test_online_scheduler_mode_covers_unselected_and_incompatible_vms(self):
        b = self.simple()
        b.manifest["platform"]["vms"][-1]["schedulerMode"] = "TIME_SHARED"
        with self.subTest(candidate="unselected"):
            self.bad(b)
        b = self.bundle(lambda: one_task(vm=10, vms=((2, 10), (10, 20))))
        install(b, {10: spec([incompatible(2), candidate(10, 0, 0, 1, 1)])}, pes={10: 2}, request_pes={1: (2, 1)})
        b.manifest["platform"]["vms"][0]["schedulerMode"] = "TIME_SHARED"
        with self.subTest(candidate="incompatible"):
            self.bad(b)

    def test_old_absence_preserves_scheduler_and_execution_metadata_acceptance(self):
        for storage in (False, True):
            for mode in ("MISSING", None, "TIME_SHARED"):
                with self.subTest(storage=storage, mode=mode):
                    b = self.bundle(lambda: h3.roundtrip(True), storage=True) if storage else self.bundle(h2.publication)
                    for vm in b.manifest["platform"]["vms"]:
                        if mode != "MISSING":
                            vm["schedulerMode"] = mode
                    for event in b.events:
                        if event["type"] == "TASK_EXECUTION_MODELED":
                            event["attributes"].update(taskLengthMi=1, effectiveExecutionLengthMi=1, taskTimingScope="OTHER")
                    b.write()
                    report = inspect_path(b.path)
                    self.assertEqual("VALID_COMPLETE", report["status"])
                    self.assertNotIn("dataflowAssignmentContextChecked", report)

    def test_logged_compute_cpu_and_score_are_exact_binary64(self):
        for key, value in (("computeSeconds", 1.0000000000000002), ("cpuAvailableAt", .1), ("scoreFinishSeconds", 1.0000000000000002)):
            b = self.simple()
            self.row(b)[key] = value
            self.bad(b)

    def test_nonadvancing_compute_score_guard(self):
        now = float(2**53)
        b = self.bundle(lambda: one_task(now=now, finish=now + 2, vms=((2, 10),)))
        install(b, {10: spec([candidate(2, 0, now, 1, now)])})
        self.bad(b, "nonadvancing")

    def test_explicit_config_mode_policy_not_algorithm_contract_only(self):
        b = self.simple()
        config = b.manifest["configuration"]
        config["algorithmContract"] = dict(dataflowAssignment=config.pop("dataflowAssignment"))
        self.bad(b)
        for change in (None, {}, dict(mode=MODE), dict(mode=MODE, policy="other"), dict(mode="other", policy=POLICY)):
            b = self.simple()
            b.manifest["configuration"]["dataflowAssignment"] = change
            self.bad(b)

    def test_old_default_rejects_new_root_and_undeclared_actions(self):
        b = self.bundle(h2.publication)
        b.manifest["dataflowComputeRequests"] = []
        self.bad(b, "explicit configuration")
        b = self.simple()
        b.manifest["configuration"].pop("dataflowAssignment")
        b.manifest["configuration"]["planningAlgorithm"] = "RANDOM"
        b.manifest.pop("dataflowComputeRequests")
        self.bad(b, "assignment action requires")

    def test_unknown_legacy_assignment_flag_never_becomes_disabled(self):
        b = self.simple()
        b.manifest["configuration"]["dataMovementModel"]["kind"] = "LEGACY_UNKNOWN"
        b.manifest["configuration"].pop("networkEvidence")
        b.manifest.pop("dataflowPlan")
        b.manifest["artifacts"] = [r for r in b.manifest["artifacts"] if r["role"] != "file-lifecycle"]
        self.bad(b, "known coherent")

    def test_online_off_or_missing_recording_mode_is_explicitly_unsupported(self):
        for storage in (False, True):
            for variant in ("absent", "missing-mode", "off", "wrong"):
                b = self.roundtrip(True) if storage else self.simple()
                if variant == "absent":
                    b.manifest["configuration"].pop("networkEvidence")
                elif variant == "missing-mode":
                    b.manifest["configuration"]["networkEvidence"].pop("mode")
                else:
                    b.manifest["configuration"]["networkEvidence"]["mode"] = "OFF" if variant == "off" else "FLUID_GROUP_LEDGER_V1"
                self.bad(b)

    def test_full_capture_status_budget_drop_and_quiescence_required(self):
        for mutation in (lambda d: d["capture"].update(status="TRUNCATED"), lambda d: d["capture"].update(status="DISABLED"),
                         lambda d: d["capture"].update(droppedRecords=1), lambda d: d["recording"].update(maxTraceRecords=999),
                         lambda d: d["capture"].update(retainedRecords=0),
                         lambda d: h3.seal(dict(d, events=d["events"][:6]))):
            b = self.roundtrip(True)
            changed = mutation(b.ledger)
            if changed is not None:
                b.ledger = changed
            self.bad(b)

    def test_existing_main_anchors_and_hardened_scope_rules_remain_enforced(self):
        for change in (lambda b: b.manifest["configuration"].update(planningAlgorithm="RANDOM"),
                       lambda b: b.manifest["configuration"].update(schedulingAlgorithm="MINMIN"),
                       lambda b: b.manifest["configuration"]["clustering"].update(method="HORIZONTAL"),
                       lambda b: b.manifest["configuration"]["overheadModel"].update(queueDelays={"1": 1}),
                       lambda b: b.main_event("JOB_DISPATCHED", 11)["attributes"].update(queueDelaySeconds=1),
                       lambda b: b.events.remove(b.main_event("TASK_EXECUTION_MODELED", 11)),
                       lambda b: b.main_event("DATA_STAGE_IN_MODELED", 11)["attributes"].update(modeledTransferSeconds=0)):
            b = self.roundtrip(True)
            change(b)
            self.bad(b)

    def test_rehashed_bundle_artifact_roles_utf8_and_metrics_are_still_strict(self):
        for mutation in (lambda b: b.manifest["artifacts"].pop(),
                         lambda b: b.manifest["artifacts"][-1].update(role="file-lifecycle"),
                         lambda b: b.metrics["metrics"].update(makespanSeconds=999)):
            b = self.roundtrip(True)
            mutation(b)
            self.bad(b)
        for role in ("events", "storage-lifecycle", "metrics"):
            b = self.roundtrip(True)
            name = next(r["path"] for r in b.manifest["artifacts"] if r["role"] == role)
            (b.root / name).write_bytes(b"\xff\xfe")
            b.rehash()
            self.bad(b, "UTF-8", write=False)

    def test_bundle_hash_size_containment_and_duplicate_json_keys(self):
        for key, value in (("sha256", "0" * 64), ("sizeBytes", 0), ("path", "../escape.json"), ("path", "/tmp/escape.json")):
            b = self.simple()
            b.manifest["artifacts"][0][key] = value
            b.save_manifest()
            self.bad(b, write=False)
        b = self.simple()
        path = b.root / next(r["path"] for r in b.manifest["artifacts"] if r["role"] == "events")
        text = path.read_text(encoding="utf-8")
        path.write_text(text.replace('"assignmentSequence": 1', '"assignmentSequence": 1, "assignmentSequence": 1', 1), encoding="utf-8")
        b.rehash()
        self.bad(b, "duplicate JSON", write=False)

    def test_zero_byte_external_inputs_publish_metadata_and_retry_cache(self):
        b = self.bundle(h2.zeros)
        install(b, {0: spec([candidate(9, 0, 1, 1, 2), candidate(10, 0, 1, 100, 101), candidate(11, 0, 1, 100, 101)]),
                    1: spec([candidate(9, 0, 2, 100, 102), candidate(10, 0, 2, 1, 3), candidate(11, 0, 2, 100, 102)]),
                    2: spec([candidate(9, 0, 3, 100, 103), candidate(10, 0, 3, 100, 103), candidate(11, 0, 3, 1, 4)]),
                    3: spec([candidate(9, 0, 4, 1, 5)], binding="RETRY_REUSE")},
                matrix=costs({1: {9: 1, 10: 100, 11: 100}, 2: {9: 100, 10: 1, 11: 100}, 3: {9: 100, 10: 100, 11: 1}}))
        report = self.good(b)
        self.assertEqual(0, report["admissionCount"])
        self.assertTrue(all(row["inputSeconds"] == 0 for f in report["assignmentFacts"] for row in f["candidates"]))
        self.row(b, 0)["inputSeconds"] = .1
        self.bad(b, "outside nominal")

    def test_zero_output_commits_source_while_sink_upload_remains_pending(self):
        def timeline():
            doc = h3.zero_and_sink()
            doc["policies"].update(inputAccess=h3.STORE, selection=h3.STORE)
            doc["filePlan"]["tasks"].append(h3.task(1, [0], [("zero", 1)]))
            doc["events"][-1:-1] = [h3.request(11, 1, 2, 2), h3.resolve(11, "zero", "ZERO", None, 2),
                                     h3.ready(11, 2), h3.cpu(11, 2, 2), h3.finish(11, 1, 2, 3)]
            return h3.seal(doc)
        b = install(self.bundle(timeline, storage=True), {
            10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 100, 100), candidate(3, 0, 0, 100, 100)]),
            11: spec([candidate(1, 0, 2, 100, 102), candidate(2, 0, 2, 1, 3), candidate(3, 0, 2, 100, 102)], copies=1, pending=1)},
            matrix=costs({1: {1: 1, 2: 100, 3: 100}, 2: {1: 100, 2: 1, 3: 100}}))
        fact = self.good(b)["assignmentFacts"][1]
        self.assertEqual(1, fact["observation"]["pendingOutputFileCount"])
        self.assertFalse(fact["candidates"][1]["conditionalStoreWait"])
        self.action(b, 11)["attributes"]["observation"]["pendingOutputFileCount"] = 2
        self.bad(b, "pendingOutputFileCount")

    def test_v3_source_commit_clears_obligation_then_input_copy_can_join(self):
        def timeline():
            original = h3.replica("data", 1, 1, "TASK_OUTPUT", 0, 10, 1, 1)
            stored = h3.replica("data", None, 4, "COPY_SETTLEMENT", 0, 10, 1, 1, h3.loc(1), 1)
            return h3.document([h3.task(0, outputs=["data"]), h3.task(1, [0], [("data", 1)]),
                                h3.task(2, [0], [("data", 2)])], [h3.file("data", 20, 0)], [
                h3.request(10, 0, 1, 0), h3.ready(10, 0), h3.cpu(10, 1, 0), h3.finish(10, 0, 1, 1),
                h3.admit(1, "data", original, None, "OUTPUT", 10, 20, ["VM:1", h3.NIC, h3.WRITE], 10, 2, 1),
                h3.output(10, 0, "data", "NEW_COPY", 1, 1, 1), h3.request(11, 1, 2, 2), h3.wait(11, "data", 2),
                h3.settle(1, 3, 4), h3.admit(2, "data", stored, 2, "INPUT", 11, 20, [h3.READ, h3.NIC, "VM:2"], 20, 1, 4),
                h3.resolve(11, "data", "NEW_COPY", None, 4, 2), h3.request(12, 2, 2, 4.5),
                h3.resolve(12, "data", "JOIN_EXISTING", None, 4.5, 2, 2), h3.settle(2, 5, 6),
                h3.ready(11, 6), h3.ready(12, 6), h3.cpu(11, 2, 6), h3.cpu(12, 2, 6),
                h3.finish(11, 1, 2, 7), h3.finish(12, 2, 2, 7)], store_inputs=True)
        b = install(self.bundle(timeline, storage=True), {
            10: spec([candidate(1, 0, 0, 1, 1), candidate(2, 0, 0, 100, 100), candidate(3, 0, 0, 100, 100)]),
            11: spec([candidate(1, 1, 2, 100, 103, True), candidate(2, 2, 2, 1, 5, True), candidate(3, 2, 2, 100, 104, True)], copies=1, pending=1),
            12: spec([candidate(1, 0, 4.5, 100, 104.5), candidate(2, .5, 5.5, 1, 6.5, joined=1), candidate(3, 1, 4.5, 100, 105.5)], copies=1, jobs=1)},
            matrix=costs({1: {1: 1, 2: 100, 3: 100}, 2: {1: 100, 2: 1, 3: 100}, 3: {1: 100, 2: 1, 3: 100}}))
        row = self.good(b)["assignmentFacts"][2]
        self.assertEqual(0, row["observation"]["pendingOutputFileCount"])
        self.assertEqual(1, row["candidates"][1]["joinedInputCopies"])
        self.assertFalse(row["candidates"][1]["conditionalStoreWait"])
        self.row(b, 12, 1)["inputSeconds"] = 0
        self.bad(b, "outside nominal")

    def test_retry_after_failed_input_attempt_reuses_visible_cache(self):
        b = install(self.bundle(h2.failure_retry), {
            10: spec([candidate(1, 10, 0, 1, 11), candidate(2, 5, 0, 100, 105), candidate(3, 2, 0, 100, 102)]),
            20: spec([candidate(1, 9, 2, 1, 11, joined=1), candidate(2, 5, 1, 100, 106), candidate(3, 2, 1, 100, 103)], copies=1, jobs=1),
            11: spec([candidate(1, 0, 14, 1, 15)], binding="RETRY_REUSE"),
            12: spec([candidate(1, 0, 15, 100, 115), candidate(2, 4, 15, 1, 20), candidate(3, 4, 15, 100, 119)])},
            matrix=costs({1: {1: 1, 2: 100, 3: 100}, 2: {1: 100, 2: 1, 3: 100}, 3: {1: 1, 2: 100, 3: 100}}))
        facts = self.good(b)["assignmentFacts"]
        self.assertEqual("RETRY_REUSE", facts[2]["binding"])
        self.assertEqual(0, facts[2]["candidates"][0]["inputSeconds"])
        self.assertEqual(0, facts[2]["observation"]["activeJobCount"])

    def test_positive_input_addition_must_advance_even_for_unselected_vm(self):
        now = float(2**53)
        def timeline():
            original = h2.replica("x", None, 0, "EXTERNAL_SEED")
            return h2.document([h2.task(0, inputs=[("x", 1)])], [h2.file("x", 2)], [h2.seed("x"),
                h2.request(10, 0, 2, now), h2.admit(1, "x", original, 2, 2, ["VM:2"], 1, 2, now),
                h2.resolve(10, "x", "NEW_COPY", None, now, 1), h2.settle(1, now + 2, now + 2),
                h2.ready(10, now + 2), h2.cpu(10, 2, now + 2), h2.finish(10, 0, 2, now + 4)],
                h2.endpoints(((2, 1), (10, 2))))
        b = install(self.bundle(timeline), {10: spec([candidate(2, 2, now, 2, now + 4), candidate(10, 1, now, 4, now + 4)])},
                    matrix=costs({1: {2: 2, 10: 4}}), effective={10: 2})
        self.bad(b, "nonadvancing candidate")

    def test_positive_cpu_work_addition_must_advance(self):
        now = float(2**53)
        def timeline():
            return h2.document([h2.task(0), h2.task(1)], [], [h2.request(10, 0, 2, now - 2), h2.ready(10, now - 2),
                h2.request(11, 1, 2, now), h2.ready(11, now), h2.cpu(10, 2, now + 2), h2.finish(10, 0, 2, now + 4),
                h2.cpu(11, 2, now + 4), h2.finish(11, 1, 2, now + 6)], h2.endpoints(((2, 1),)))
        b = install(self.bundle(timeline), {10: spec([candidate(2, 0, now - 2, 1, now - 1)]),
                    11: spec([candidate(2, 0, now, 2, now + 2)], jobs=1)}, lengths={1: 1, 2: 2})
        self.bad(b, "nonadvancing CPU")

    def test_finite_components_may_not_overflow_final_score(self):
        b = install(self.bundle(lambda: one_task(now=1e308, finish=1.5e308, vms=((2, 10),))),
                    {10: spec([candidate(2, 0, 1e308, 1e308, 1.5e308)])}, mips={2: 1e-308})
        self.bad(b, "nonfinite/nonadvancing")

    def test_conditional_upper_addition_can_absorb_tiny_positive_write(self):
        def timeline():
            at = 1e-20
            original = h3.replica("x", 1, at, "TASK_OUTPUT", 0, 10, 1, at)
            stored = h3.replica("x", None, 2e-18, "COPY_SETTLEMENT", 0, 10, 1, at, h3.loc(1), 1)
            return h3.document([h3.task(0, outputs=["x"]), h3.task(1, [0], [("x", 1)])], [h3.file("x", 1, 0)], [
                h3.request(10, 0, 1, 0), h3.ready(10, 0), h3.cpu(10, 1, 0), h3.finish(10, 0, 1, at),
                h3.admit(1, "x", original, None, "OUTPUT", 10, 1, ["VM:1", h3.NIC, h3.WRITE], 1e18, 1e-18, at),
                h3.output(10, 0, "x", "NEW_COPY", 1, at, 1), h3.request(11, 1, 2, at), h3.wait(11, "x", at),
                h3.settle(1, 1.01e-18, 2e-18), h3.admit(2, "x", stored, 2, "INPUT", 11, 1, [h3.READ, h3.NIC, "VM:2"], 1, 1, 2e-18),
                h3.resolve(11, "x", "NEW_COPY", None, 2e-18, 2), h3.settle(2, 1, 1), h3.ready(11, 1),
                h3.cpu(11, 2, 1), h3.finish(11, 1, 2, 2)],
                h3.fabric(((1, 1e18, 10), (2, 1e18, 20)), read_mb=.000001, write_mb=1e12, nic_mb=1e12), store_inputs=True)
        b = install(self.bundle(timeline, storage=True), {
            10: spec([candidate(1, 0, 0, 1e-20, 1e-20), candidate(2, 0, 0, 1, 1)]),
            11: spec([incompatible(1), candidate(2, 1, 1e-20, 1, 2, True)], copies=1, pending=1)},
            mips={1: 1e20}, pes={2: 2}, request_pes={2: (2, 2)})
        bounds = self.good(b)["assignmentFacts"][1]["candidates"][1]["inputBounds"]
        self.assertEqual(dict(lowerSeconds=1, upperSeconds=1, lowerExclusive=False, knownExact=True), bounds)

    def test_fixed_typed_source_and_complete_matching_lifecycle_grammar(self):
        for mutation in (lambda d: d["fabric"]["locations"][-1].update(sourceId="other"),
                         lambda d: d.update(schema="workflowsim-file-lifecycle-v2"),
                         lambda d: d["policies"].update(inputAccess=h3.LOCAL),
                         lambda d: d["filePlan"]["files"][0].update(bytes=21_000_000)):
            b = self.roundtrip(True)
            mutation(b.ledger)
            self.bad(b)

    def test_retry_creation_must_precede_action_not_only_job_ready(self):
        b = self.retry()
        created = b.main_event("RETRY_JOB_CREATED", 11)
        b.events.remove(created)
        b.events.insert(b.events.index(self.action(b, 11)) + 1, created)
        self.bad(b, "preceding failed")

    def test_cli_reports_honest_scope_and_invalid_online_off(self):
        b = self.roundtrip(True)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, cli.main([str(b.path)]))
        self.assertIn("dataflowAssignmentContextChecked=true liveProgressReplayed=false", output.getvalue())
        self.assertIn(SCOPE, output.getvalue())
        b.manifest["configuration"].pop("networkEvidence")
        b.save_manifest()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, cli.main([str(b.path), "--json"]))
        self.assertEqual("EVIDENCE_INVALID", json.loads(output.getvalue())["status"])


if __name__ == "__main__":
    unittest.main()
