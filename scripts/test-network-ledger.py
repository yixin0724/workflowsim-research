#!/usr/bin/env python3
"""Independent stdlib network tests: hand oracles only; no JVM, producer, retained study or network."""
import copy
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from fractions import Fraction

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from _network_audit import CheckError, audit_trace, decode_json, inspect_path, verify_document

MODEL = "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1"
PAYLOADS = ("capacity", "start", "rateChange", "serviceSegment", "complete")
BIG_ID = (1 << 63) - 1


def event(kind, seq, effective=0.0, observed=None, tid=BIG_ID, ordinal=1, **data):
    key = {"CAPACITY": "capacity", "START": "start", "RATE_CHANGE": "rateChange",
           "SERVICE_SEGMENT": "serviceSegment", "COMPLETE": "complete"}[kind]
    row = dict(sequence=seq, type=kind, effectiveTime=effective,
               observedTime=effective if observed is None else observed,
               transferId=None if kind == "CAPACITY" else tid,
               admissionOrdinal=None if kind == "CAPACITY" else ordinal,
               **{name: None for name in PAYLOADS})
    row[key] = data
    return row


def start(seq=2, size=1000000, nominal=1000000, rate=None, path=None, **kw):
    return event("START", seq, bytes=size, occupiedResources=["source", "VM:7"] if path is None else path,
                 nominalRateBytesPerSecond=nominal, initialRateBytesPerSecond=nominal if rate is None else rate, **kw)


def service(seq=3, a=0.0, b=1.0, observed=2.0, elapsed=1.0, rate=1000000, before=1000000, after=0, **kw):
    return event("SERVICE_SEGMENT", seq, b, observed, intervalStart=a, intervalEnd=b, elapsed=elapsed,
                 rateBytesPerSecond=rate, remainingBefore=before, remainingAfter=after, **kw)


def ledger():
    """One 1 MB modeled flow: effective finish1, observed2; independent elementary metrics."""
    rows = [event("CAPACITY", 1, resourceKey="VM:7", capacityBytesPerSecond=1000000), start(), service(),
            event("COMPLETE", 4, 1.0, 2.0, remainingAfterService=0)]
    validation = dict(captureStatus="COMPLETE", recordCount=4, admissionCount=1, completionCount=1,
                      openFlowCount=0, certifiedThroughSequence=4, tailPhase="READY", minimumMissingRecords=0)
    flow = dict(externalTransferId=BIG_ID, admissionOrdinal=1, complete=True, admissionTime=0.0,
                completionEffectiveTime=1.0, completionObservedTime=2.0, effectiveFctSeconds=1.0,
                observedFctSeconds=2.0, notificationLagSeconds=1.0, demandBytes=1000000,
                servicedBalanceDeltaBytes=1000000, modeledRateAreaBytes=1000000, completionResidualBytes=0, remainingBytes=0)
    resources = [dict(resourceKey=key, lastDeclaredCapacityBytesPerSecond=capacity, rateAreaBytes=1000000,
                      boundedRateAreaBytes=bound, capacityAreaBytes=bound, integrationEpochUtilization=util)
                 for key, capacity, bound, util in (("VM:7", 1000000.0, 1000000, 1.0), ("source", None, 0, None))]
    metrics = dict(accountingVersion="EXACT_BINARY64_INPUT_DECIMAL_V1", validation=validation,
                   droppedRecordCount=0, engineTime=2.0, admittedPayloadBytes=1000000, completedDemandBytes=1000000,
                   servicedBalanceDeltaBytes=1000000, modeledRateAreaBytes=1000000, completionResidualBytes=0,
                   remainingLedgerBytes=0, rateAreaMinusBalanceDeltaBytes=0, integrationEpochCount=1,
                   completedFctSampleCount=1, meanEffectiveFctSeconds=1.0, p95EffectiveFctSeconds=1.0,
                   maxEffectiveFctSeconds=1.0, meanNotificationLagSeconds=1.0, flows=[flow], resources=resources)
    return dict(schema="workflowsim-network-ledger-v1", flowUnit="V1_PARENT_OR_EXTERNAL_GROUP",
                numericProfile="BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1",
                accountingVersion="EXACT_BINARY64_INPUT_DECIMAL_V1",
                units=dict(time="SIMULATION_SECONDS", bytes="MODELED_BYTES", rate="BYTES_PER_SECOND"),
                evidence=dict(config=dict(mode="FLUID_GROUP_LEDGER_V1", maxTraceRecords=50), modelKind=MODEL,
                              traceSnapshot=dict(status="COMPLETE", events=rows, droppedCount=0, engineTime=2.0),
                              bindings=[dict(externalTransferId=BIG_ID, admissionOrdinal=1, jobId=10, taskIds=[1],
                                             parentJobId=None, groupKind="EXTERNAL_GROUP_V1", sourceScope="MODELED_CONSTRAINED_SOURCE",
                                             sourceEndpoint="source", destinationEndpoint="VM:7", occupiedResources=["source", "VM:7"])],
                              engineCreated=True, inputDemand=dict(scope="V1_GROUP_INPUT_REFERENCES", referenceCount=1,
                                                                   localReferenceCount=0, requiredReferenceBytes=1000000,
                                                                   localReferenceBytes=0, transferableReferenceBytes=1000000)),
                metrics=dict(localityScope="V1_GROUP_INPUT_REFERENCES", transferMetrics=metrics, localityStatus="AVAILABLE",
                             inputReferenceCount=1, localInputReferenceCount=0, requiredInputReferenceBytes=1000000,
                             localInputReferenceBytes=0, transferableInputReferenceBytes=1000000,
                             admittedMinusTransferableReferenceBytes=0, localByteFraction=0.0, localReferenceFraction=0.0))


def trace(rows, clock, status="COMPLETE", dropped=0):
    return dict(status=status, events=rows, droppedCount=dropped, engineTime=clock)


def late_rows():
    return [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=100),
            start(2, 500, 100, path=["A"], tid=1), start(3, 1000, 100, 50, ["A"], tid=2, ordinal=2),
            event("RATE_CHANGE", 4, tid=1, previousRateBytesPerSecond=100, rateBytesPerSecond=50),
            service(5, 0, 10, 20, 10, 50, 500, 0, tid=1), service(6, 0, 10, 20, 10, 50, 1000, 500, tid=2, ordinal=2),
            event("COMPLETE", 7, 10, 20, tid=1, remainingAfterService=0),
            event("RATE_CHANGE", 8, 10, 20, tid=2, ordinal=2, previousRateBytesPerSecond=50, rateBytesPerSecond=100),
            service(9, 10, 15, 20, 5, 100, 500, 0, tid=2, ordinal=2),
            event("COMPLETE", 10, 15, 20, tid=2, ordinal=2, remainingAfterService=0)]


def prefix(count):
    doc = ledger()
    doc["evidence"]["config"]["maxTraceRecords"] = count
    doc["evidence"]["traceSnapshot"] = trace(doc["evidence"]["traceSnapshot"]["events"][:count], 2.0, "TRUNCATED", 4-count)
    if count == 1:
        doc["evidence"]["bindings"] = []
    m = doc["metrics"]["transferMetrics"]
    for key in list(m):
        if key not in ("accountingVersion", "validation", "droppedRecordCount", "engineTime", "flows", "resources"):
            m[key] = None
    m["droppedRecordCount"] = 4-count
    m["flows"], m["resources"] = [], []
    # Hand-derived phase endpoints for the four-record single-flow fixture.
    certified, phase, minimum = {1: (1, "PREFIX_AT_BOUNDARY", 0), 2: (2, "UNOBSERVED_TIME_PROGRESS", 2),
                                  3: (2, "COMPLETION_COHORT", 1)}[count]
    m["validation"] = dict(captureStatus="TRUNCATED", recordCount=count, admissionCount=int(count >= 2),
                            completionCount=0, openFlowCount=int(count >= 2), certifiedThroughSequence=certified,
                            tailPhase=phase, minimumMissingRecords=minimum)
    for key in doc["metrics"]:
        if key not in ("localityScope", "transferMetrics", "localityStatus"):
            doc["metrics"][key] = None
    doc["metrics"]["localityStatus"] = "TRUNCATED_TRACE"
    return doc


def write_json(path, doc):
    path.write_text(json.dumps(doc, allow_nan=False), encoding="utf-8")


def bind(root, manifest):
    for item in manifest["artifacts"]:
        raw = (root/item["path"]).read_bytes()
        item.update(sha256=hashlib.sha256(raw).hexdigest(), sizeBytes=len(raw))
    write_json(root/"run.manifest.json", manifest)


def bundle(root):
    root.mkdir(parents=True, exist_ok=True)
    write_json(root/"run.network-ledger.json", ledger())
    write_json(root/"run.metrics.json", {"schema": "workflowsim-simulation-metrics-v2", "metrics": {}})
    events = [dict(sequence=0, type="JOB_READY", simulationTime=0.0, jobId=10, vmId=7, classType=2, taskIds=[1], attributes={}),
              dict(sequence=1, type="DATA_STAGE_IN_MODELED", simulationTime=0.0, jobId=10, vmId=7, classType=2, taskIds=[1],
                   attributes=dict(dataMovementModel=MODEL, requiredFileBytes=1000000, modeledTransferFileCount=1, contentionTransferGroupCount=1.0))]
    (root/"run.events.jsonl").write_text("".join(json.dumps(e)+"\n" for e in events), encoding="utf-8")
    manifest = dict(schema="workflowsim-experiment-manifest-v4",
                    configuration=dict(networkEvidence=dict(mode="FLUID_GROUP_LEDGER_V1", maxTraceRecords=50),
                                       dataMovementModel=dict(kind=MODEL), fileSystem="LOCAL"),
                    platform=dict(hosts=[dict(id=10)], vms=[dict(id=7, bandwidth=1, preflightHostId=10)], networkTopology=None),
                    result=dict(simulationEndSeconds=3.0, actualVmHostAssignments={"7": 10},
                                jobs=[dict(jobId=10, vmId=7, classType=2, startTime=2.0, finishTime=3.0, taskIds=[1])]),
                    artifacts=[dict(role=role, path="run."+suffix) for role, suffix in
                               (("metrics", "metrics.json"), ("events", "events.jsonl"), ("network-ledger", "network-ledger.json"))])
    bind(root, manifest)
    return manifest


def two_group_bundle(root, one_job=False):
    """Two 1MB flows share 1MB/s: 0.5MB/s each, effective2/observed3, exact totals2MB."""
    manifest = bundle(root); doc = ledger()
    rows = [event("CAPACITY", 1, resourceKey="VM:7", capacityBytesPerSecond=1000000), start(),
            start(3, rate=500000, tid=BIG_ID-1, ordinal=2),
            event("RATE_CHANGE", 4, previousRateBytesPerSecond=1000000, rateBytesPerSecond=500000),
            service(5, 0, 2, 3, 2, 500000, 1000000, 0), service(6, 0, 2, 3, 2, 500000, 1000000, 0, tid=BIG_ID-1, ordinal=2),
            event("COMPLETE", 7, 2, 3, remainingAfterService=0), event("COMPLETE", 8, 2, 3, tid=BIG_ID-1, ordinal=2, remainingAfterService=0)]
    doc["evidence"]["traceSnapshot"] = trace(rows, 3)
    second = copy.deepcopy(doc["evidence"]["bindings"][0]); second.update(externalTransferId=BIG_ID-1, admissionOrdinal=2,
                                                                          jobId=10 if one_job else 20, taskIds=[1] if one_job else [2])
    doc["evidence"]["bindings"].append(second)
    doc["evidence"]["inputDemand"].update(referenceCount=2, requiredReferenceBytes=2000000, transferableReferenceBytes=2000000)
    m = doc["metrics"]["transferMetrics"]
    m["validation"].update(recordCount=8, admissionCount=2, completionCount=2, certifiedThroughSequence=8)
    m.update(engineTime=3.0, admittedPayloadBytes=2000000, completedDemandBytes=2000000, servicedBalanceDeltaBytes=2000000,
             modeledRateAreaBytes=2000000, completedFctSampleCount=2, meanEffectiveFctSeconds=2.0, p95EffectiveFctSeconds=2.0, maxEffectiveFctSeconds=2.0)
    m["flows"][0].update(completionEffectiveTime=2.0, completionObservedTime=3.0, effectiveFctSeconds=2.0, observedFctSeconds=3.0)
    second_flow = copy.deepcopy(m["flows"][0]); second_flow.update(externalTransferId=BIG_ID-1, admissionOrdinal=2); m["flows"].append(second_flow)
    m["resources"][0].update(rateAreaBytes=2000000, boundedRateAreaBytes=2000000, capacityAreaBytes=2000000)
    m["resources"][1]["rateAreaBytes"] = 2000000
    doc["metrics"].update(inputReferenceCount=2, requiredInputReferenceBytes=2000000, transferableInputReferenceBytes=2000000)
    manifest["result"]["simulationEndSeconds"] = 4.0 if one_job else 5.0
    manifest["result"]["jobs"][0].update(startTime=3.0, finishTime=4.0)
    if not one_job: manifest["result"]["jobs"].append(dict(jobId=20, vmId=7, classType=2, startTime=4.0, finishTime=5.0, taskIds=[2]))
    events = []
    for jid, task, count in ([(10, 1, 2)] if one_job else [(10, 1, 1), (20, 2, 1)]):
        events.append(dict(sequence=len(events), type="JOB_READY", simulationTime=0.0, jobId=jid, vmId=7, classType=2, taskIds=[task], attributes={}))
        events.append(dict(sequence=len(events), type="DATA_STAGE_IN_MODELED", simulationTime=0.0, jobId=jid, vmId=7, classType=2, taskIds=[task],
                           attributes=dict(dataMovementModel=MODEL, requiredFileBytes=count*1000000, modeledTransferFileCount=count, contentionTransferGroupCount=count)))
    (root/"run.events.jsonl").write_text("".join(json.dumps(e)+"\n" for e in events), encoding="utf-8")
    write_json(root/"run.network-ledger.json", doc); bind(root, manifest)
    return manifest, doc


class NetworkCheckerTest(unittest.TestCase):
    def test_complete_hand_oracle_and_long_identity(self):
        result = verify_document(ledger())
        self.assertEqual("VALID_COMPLETE", result["status"])
        self.assertTrue(result["completeCaptureCertified"])
        self.assertEqual("1000000", result["exactBytes"]["servicedBalanceDeltaBytes"])
        self.assertEqual(1.0, result["meanEffectiveFctSeconds"])

    def test_prefix_oracles_never_claim_complete_totals(self):
        for count in (1, 2, 3):
            result = verify_document(prefix(count))
            self.assertEqual("VALID_PREFIX", result["status"])
            self.assertFalse(result["completeCaptureCertified"])
            self.assertIsNone(result["exactBytes"])

    def test_zero_capture_and_disabled_trace_are_distinct(self):
        empty = audit_trace(trace([], 0), 5)
        self.assertEqual(Fraction(0), empty["admittedPayloadBytes"])
        self.assertIsNone(empty["meanEffectiveFctSeconds"])
        disabled = audit_trace(trace([], 10, "DISABLED"), 0)
        self.assertIsNone(disabled["admittedPayloadBytes"])

    def test_json_duplicates_numbers_depth_and_coercions(self):
        for raw in ('{"x":1,"x":2}', '{"x":NaN}', '{"x":1e999999}', '['*100+'0'+']'*100, '{x:1}', '{}{}'):
            with self.assertRaises(CheckError):
                decode_json(raw)
        for key, value in (("maxTraceRecords", 0.5), ("maxTraceRecords", "50"), ("mode", "OFF")):
            doc = ledger(); doc["evidence"]["config"][key] = value
            with self.assertRaises(CheckError): verify_document(doc)

    def test_numeric_token_limit_is_checked_before_decimal_normalization(self):
        with self.assertRaises(CheckError):
            decode_json('{"x":0e' + '0'*5000 + '}')
        with self.assertRaises(CheckError):
            decode_json(None)

    def test_missing_zero_field_and_unknown_payload_are_rejected(self):
        doc = ledger(); del doc["evidence"]["traceSnapshot"]["events"][1]["effectiveTime"]
        with self.assertRaises(CheckError): verify_document(doc)
        doc = ledger(); doc["evidence"]["traceSnapshot"]["events"][1]["start"]["fake"] = 1
        with self.assertRaises(CheckError): verify_document(doc)

    def test_metric_and_locality_forgery(self):
        for key, value in (("admittedPayloadBytes", 999), ("meanEffectiveFctSeconds", 2), ("completionResidualBytes", 1000000)):
            doc = ledger(); doc["metrics"]["transferMetrics"][key] = value
            with self.assertRaises(CheckError): verify_document(doc)
        doc = ledger(); doc["metrics"]["localByteFraction"] = 1.0
        with self.assertRaises(CheckError): verify_document(doc)

    def test_wrong_binding_and_false_empty_engine(self):
        doc = ledger(); doc["evidence"]["bindings"][0]["externalTransferId"] -= 1
        with self.assertRaises(CheckError): verify_document(doc)
        doc = ledger(); doc["evidence"]["engineCreated"] = False
        with self.assertRaises(CheckError): verify_document(doc)

    def test_capacity_feasible_unfair_allocation_and_unused_capacity(self):
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=100),
                start(2, 100, 100, path=["A"], tid=1), start(3, 100, 100, 25, ["A"], tid=2, ordinal=2),
                event("RATE_CHANGE", 4, tid=1, previousRateBytesPerSecond=100, rateBytesPerSecond=75)]
        with self.assertRaises(CheckError): audit_trace(trace(rows, 0), 50)
        with self.assertRaises(CheckError): audit_trace(trace([start(1, 100, 100, 50, [])], 0), 50)

    def test_missing_service_completion_and_changed_rate_history(self):
        for index in (2, 3):
            rows = ledger()["evidence"]["traceSnapshot"]["events"]
            del rows[index]
            for i, row in enumerate(rows): row["sequence"] = i+1
            with self.assertRaises(CheckError): audit_trace(trace(rows, 2), 50)
        doc = ledger(); doc["evidence"]["traceSnapshot"]["events"][2]["serviceSegment"]["rateBytesPerSecond"] = 500000
        with self.assertRaises(CheckError): verify_document(doc)

    def test_duplicate_resource_occupancy(self):
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=100),
                start(2, 100, 10, 10, ["A", "A"], tid=1), start(3, 100, 100, 90, ["A"], tid=2, ordinal=2)]
        with self.assertRaises(CheckError): audit_trace(trace(rows, 0), 50)

    def test_late_completion_reclamation_and_every_prefix(self):
        rows = late_rows(); m = audit_trace(trace(rows, 20), 50)
        self.assertEqual(Fraction(1500), m["servicedBalanceDeltaBytes"])
        self.assertEqual(12.5, m["meanEffectiveFctSeconds"])
        self.assertEqual(15.0, m["p95EffectiveFctSeconds"])
        self.assertEqual(7.5, m["meanNotificationLagSeconds"])
        self.assertEqual(Fraction(1500), m["resources"][0]["capacityAreaBytes"])
        for count in range(1, len(rows)):
            partial = audit_trace(trace(rows[:count], 20, "TRUNCATED", len(rows)-count), count)
            self.assertIsNone(partial["admittedPayloadBytes"])
        with self.assertRaises(CheckError): audit_trace(trace(rows[:8], 20, "TRUNCATED", 1), 8)
        wrong = rows[:7] + [service(8, 10, 20, 20, 10, 50, 500, 0, tid=2, ordinal=2),
                            event("COMPLETE", 9, 20, 20, tid=2, ordinal=2, remainingAfterService=0)]
        with self.assertRaises(CheckError): audit_trace(trace(wrong, 20), 50)

    def test_positive_weighted_occupancy_is_not_delivered_bytes(self):
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=100),
                start(2, 100, 10, path=["A", "A"], tid=1), start(3, 800, 100, 80, ["A"], tid=2, ordinal=2),
                service(4, 0, 10, 10, 10, 10, 100, 0, tid=1), service(5, 0, 10, 10, 10, 80, 800, 0, tid=2, ordinal=2),
                event("COMPLETE", 6, 10, 10, tid=1, remainingAfterService=0), event("COMPLETE", 7, 10, 10, tid=2, ordinal=2, remainingAfterService=0)]
        m = audit_trace(trace(rows, 10), 50)
        self.assertEqual(Fraction(900), m["admittedPayloadBytes"])
        self.assertEqual(Fraction(1000), m["resources"][0]["rateAreaBytes"])
        self.assertEqual(1.0, m["resources"][0]["integrationEpochUtilization"])

    def test_relative_cap_prevents_subnormal_ulp_budget_from_hiding_overcommit(self):
        q = math.ulp(0.0)
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=3*q),
                start(2, 1, 1, 3*q, ["A"], tid=1), start(3, 1, 1, 2*q, ["A"], tid=2, ordinal=2),
                event("RATE_CHANGE", 4, tid=1, previousRateBytesPerSecond=3*q, rateBytesPerSecond=2*q)]
        with self.assertRaises(CheckError): audit_trace(trace(rows, 0), 50)

    def test_each_post_cohort_survivor_needs_progress_before_horizon(self):
        rows = [start(1, 1, 1, path=[], tid=1), start(2, 10, 1, path=[], tid=2, ordinal=2),
                start(3, 10, 1, path=[], tid=3, ordinal=3), service(4, 0, 1, 2, 1, 1, 1, 0, tid=1),
                service(5, 0, 1, 2, 1, 1, 10, 9, tid=2, ordinal=2), service(6, 0, 1, 2, 1, 1, 10, 9, tid=3, ordinal=3)]
        with self.assertRaises(CheckError): audit_trace(trace(rows, 2, "TRUNCATED", 2), 6)
        with self.assertRaises(CheckError): audit_trace(trace(rows[:4], 2, "TRUNCATED", 4), 4)

    def test_later_resource_registration_excludes_prior_unbounded_area(self):
        rows = [start(1, 100, 100, path=["A"], tid=1), service(2, 0, 1, 1, 1, 100, 100, 0, tid=1),
                event("COMPLETE", 3, 1, 1, tid=1, remainingAfterService=0), event("CAPACITY", 4, 1, resourceKey="A", capacityBytesPerSecond=100),
                start(5, 100, 100, path=["A"], tid=2, ordinal=2, effective=1), service(6, 1, 2, 2, 1, 100, 100, 0, tid=2, ordinal=2),
                event("COMPLETE", 7, 2, 2, tid=2, ordinal=2, remainingAfterService=0)]
        m = audit_trace(trace(rows, 20), 50); r = m["resources"][0]
        self.assertEqual(Fraction(200), r["rateAreaBytes"])
        self.assertEqual(Fraction(100), r["boundedRateAreaBytes"])
        self.assertEqual(Fraction(100), r["capacityAreaBytes"])
        self.assertEqual(1.0, r["integrationEpochUtilization"])

    def test_partial_fixed_rate_cannot_be_repaired_by_other_bottleneck(self):
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=100),
                event("CAPACITY", 2, resourceKey="B", capacityBytesPerSecond=10),
                start(3, 100, 100, 10, ["A", "B"], tid=1), start(4, 100, 100, 50, ["A"], tid=2, ordinal=2)]
        with self.assertRaises(CheckError): audit_trace(trace(rows, 0, "TRUNCATED", 1), 4)

    def test_prefix_drop_watermark_and_nonrepairable_first_rate(self):
        with self.assertRaises(CheckError): audit_trace(trace([start(1, 1, 1, path=[])], 2, "TRUNCATED", 1), 1)
        with self.assertRaises(CheckError): audit_trace(trace([start(1, 100, 100, 50, [])], 0, "TRUNCATED", 1), 1)
        doc = prefix(3); doc["evidence"]["traceSnapshot"]["status"] = "COMPLETE"; doc["evidence"]["traceSnapshot"]["droppedCount"] = 0
        with self.assertRaises(CheckError): verify_document(doc)

    def test_positive_elapsed_at_equal_large_clocks(self):
        clock = float(1 << 54); elapsed = 1.0/49.0; residual = 1.0-49.0*elapsed
        rows = [start(1, 1, 49, path=[], effective=clock),
                service(2, clock, clock, math.nextafter(clock, math.inf), elapsed, 49, 1, residual),
                event("COMPLETE", 3, clock, math.nextafter(clock, math.inf), remainingAfterService=residual)]
        m = audit_trace(trace(rows, math.nextafter(clock, math.inf)), 50)
        self.assertEqual(0.0, m["meanEffectiveFctSeconds"])
        self.assertGreater(m["modeledRateAreaBytes"], 0)
        rows[1]["serviceSegment"]["elapsed"] = 0
        with self.assertRaises(CheckError): audit_trace(trace(rows, math.nextafter(clock, math.inf)), 50)

    def test_zero_elapsed_adjacent_completions_are_distinct_iterations(self):
        q = math.ulp(0.0)
        rows = [start(1, 192*q, 128, path=[], tid=11), start(2, 64*q, 64, path=[], tid=22, ordinal=2),
                service(3, 0, q, 2*q, q, 128, 192*q, 64*q, tid=11),
                service(4, 0, q, 2*q, q, 64, 64*q, 0, tid=22, ordinal=2),
                event("COMPLETE", 5, q, 2*q, tid=22, ordinal=2, remainingAfterService=0),
                event("COMPLETE", 6, q, 2*q, tid=11, remainingAfterService=64*q)]
        m = audit_trace(trace(rows, 2*q), 50)
        self.assertEqual(Fraction.from_float(q)*64, m["completionResidualBytes"])
        self.assertEqual(1, m["integrationEpochCount"])
        rows[4], rows[5] = rows[5], rows[4]
        for i, row in enumerate(rows): row["sequence"] = i+1
        with self.assertRaises(CheckError): audit_trace(trace(rows, 2*q), 50)

    def test_exact_area_and_balance_cancellation_are_separate(self):
        rows = [start(1, 1e16, 1, path=[]), service(2, 0, .5, .5, .5, 1, 1e16, 1e16)]
        m = audit_trace(trace(rows, .5), 50)
        self.assertEqual(0, m["servicedBalanceDeltaBytes"])
        self.assertEqual(Fraction(1, 2), m["modeledRateAreaBytes"])
        self.assertEqual(Fraction(1, 2), m["rateAreaMinusBalanceDeltaBytes"])
        self.assertIsNone(m["meanEffectiveFctSeconds"])

    def test_exact_rate_area_can_be_below_the_smallest_binary64_value(self):
        q = math.ulp(0.0)
        rows = [event("CAPACITY", 1, resourceKey="A", capacityBytesPerSecond=q),
                start(2, 1, 1, q, ["A"]), service(3, 0, q, q, q, q, 1, 1)]
        m = audit_trace(trace(rows, q), 50)
        self.assertEqual(Fraction.from_float(q)**2, m["modeledRateAreaBytes"])
        self.assertGreater(m["modeledRateAreaBytes"], 0)
        self.assertEqual(0, m["servicedBalanceDeltaBytes"])
        self.assertEqual(1.0, m["resources"][0]["integrationEpochUtilization"])

    def test_reused_id_has_distinct_ordinal(self):
        rows = [start(1, 1, 1, path=[], tid=7), service(2, 0, 1, 1, 1, 1, 1, 0, tid=7),
                event("COMPLETE", 3, 1, 1, tid=7, remainingAfterService=0),
                start(4, 2, 1, path=[], tid=7, ordinal=2, effective=1),
                service(5, 1, 3, 3, 2, 1, 2, 0, tid=7, ordinal=2),
                event("COMPLETE", 6, 3, 3, tid=7, ordinal=2, remainingAfterService=0)]
        m = audit_trace(trace(rows, 3), 50)
        self.assertEqual([1, 2], [f["admissionOrdinal"] for f in m["flows"]])
        self.assertEqual(1.5, m["meanEffectiveFctSeconds"])

    def test_byte_aggregate_above_double_range_is_finite_exact(self):
        biggest = sys.float_info.max
        rows = [start(1, biggest, 1, path=[], tid=1), start(2, biggest, 1, path=[], tid=2, ordinal=2),
                service(3, 0, biggest, biggest, biggest, 1, biggest, 0, tid=1),
                service(4, 0, biggest, biggest, biggest, 1, biggest, 0, tid=2, ordinal=2),
                event("COMPLETE", 5, biggest, biggest, tid=1, remainingAfterService=0),
                event("COMPLETE", 6, biggest, biggest, tid=2, ordinal=2, remainingAfterService=0)]
        m = audit_trace(trace(rows, biggest), 50)
        self.assertEqual(Fraction.from_float(biggest)*2, m["admittedPayloadBytes"])
        self.assertEqual(biggest, m["meanEffectiveFctSeconds"])

    def test_manifest_context_hashes_and_no_file_modification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); bundle(root); before = {p.name: p.read_bytes() for p in root.iterdir()}
            self.assertEqual("VALID_COMPLETE", inspect_path(root/"run.manifest.json")["status"])
            self.assertEqual(before, {p.name: p.read_bytes() for p in root.iterdir()})
            (root/"run.network-ledger.json").write_text("{}", encoding="utf-8")
            with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")

    def test_rehashed_false_source_and_early_job_start_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest = bundle(root); doc = ledger(); doc["evidence"]["bindings"][0]["sourceEndpoint"] = "VM:99"
            write_json(root/"run.network-ledger.json", doc); bind(root, manifest)
            with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")
            write_json(root/"run.network-ledger.json", ledger()); manifest["result"]["jobs"][0]["startTime"] = 1.0; bind(root, manifest)
            with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")

    def test_context_requires_at_least_one_nonlocal_reference_per_positive_group(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest, doc = two_group_bundle(root)
            self.assertEqual("VALID_COMPLETE", verify_document(doc)["status"])
            self.assertEqual("VALID_COMPLETE", inspect_path(root/"run.manifest.json")["status"])
            doc["evidence"]["inputDemand"]["localReferenceCount"] = 1
            doc["metrics"].update(localInputReferenceCount=1, localReferenceFraction=.5)
            self.assertEqual("VALID_COMPLETE", verify_document(doc)["status"], "standalone counters do not contain main-stage group counts")
            write_json(root/"run.network-ledger.json", doc); bind(root, manifest)
            with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")

    def test_one_job_cannot_split_its_external_files_into_multiple_v1_groups(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest, doc = two_group_bundle(root, one_job=True)
            self.assertEqual("VALID_COMPLETE", verify_document(doc)["status"], "generic standalone flow evidence has no run-level grouping claim")
            with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")

    def test_manifest_off_and_path_escape_are_not_network_certificates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); manifest = bundle(root); del manifest["configuration"]["networkEvidence"]; manifest["artifacts"].pop(); bind(root, manifest)
            self.assertEqual("DISABLED", inspect_path(root/"run.manifest.json")["status"])
            manifest = bundle(root)
            for invalid in ("../run.network-ledger.json", "bad\x00name"):
                manifest["artifacts"][-1]["path"] = invalid; write_json(root/"run.manifest.json", manifest)
                with self.assertRaises(CheckError): inspect_path(root/"run.manifest.json")

    def test_cli_uses_python_only_and_prints_explicit_certification(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); bundle(root)
            completed = subprocess.run([sys.executable, str(HERE/"verify-network-ledger.py"), str(root/"run.manifest.json"), "--json"], text=True, capture_output=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            report = json.loads(completed.stdout)
            self.assertEqual("VALID_COMPLETE", report["status"])
            self.assertTrue(report["completeCaptureCertified"])


if __name__ == "__main__":
    unittest.main()
