#!/usr/bin/env python3
"""Handcrafted V3 storage lifecycle oracles; stdlib only, no production/JVM data.

Run: python3 -B scripts/test-storage-lifecycle.py
Routes, event order, clocks, and provenance below are independent literal
oracles. The helpers only assemble JSON, never call a verifier to create events.
"""
import copy
from decimal import Decimal
import hashlib
import json
import math
from pathlib import Path
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
from _network_audit import CheckError, decode_json
from _file_lifecycle_audit import verify_document as verify_v2
from _storage_lifecycle_audit import Invalid, inspect_path, verify_document

INT_MAX, LONG_MAX = 2147483647, 9223372036854775807
READ, WRITE, NIC = "STORE:source:READ", "STORE:source:WRITE", "STORE:source:NIC"
LOCAL, STORE = "LOCAL_VISIBLE_REPLICA_V3", "COMMITTED_STORE_THEN_VM_READ_CACHE_V3"


def fid(name="data", scope=0):
    return dict(workflowInputIndex=scope, name=name)


def loc(vm=None):
    return dict(kind="SOURCE" if vm is None else "VM", vmId=vm,
                sourceId="source" if vm is None else None)


def file(name, size, producer=None, scope=0):
    return dict(fileId=fid(name, scope), bytes=size, producerTaskId=producer)


def task(ident, parents=(), inputs=(), outputs=(), scope=0):
    return dict(taskId=ident, workflowInputIndex=scope, parents=list(parents),
                inputs=[dict(fileId=fid(name, scope), referenceCount=count) for name, count in inputs],
                outputs=[fid(name, scope) for name in outputs])


def resource(key, rate):
    return dict(key=key, capacityBytesPerSecond=rate)


def fabric(vms=((1, 40, 10), (2, 20, 20), (3, 50, 10)), store_host=10,
           read_mb=.00002, write_mb=.00001, nic_mb=.00005):
    return dict(locations=[loc(vm) for vm, _, _ in vms] + [loc()],
                resources=[resource("VM:" + str(vm), rate) for vm, rate, _ in vms] +
                          [resource(READ, read_mb * 1e6), resource(WRITE, write_mb * 1e6), resource(NIC, nic_mb * 1e6)],
                vmHostAssignments=[dict(vmId=vm, hostId=host) for vm, _, host in vms], topology=None,
                sourceStorage=dict(attachmentHostId=store_host, readBandwidthMbPerSecond=read_mb,
                                   writeBandwidthMbPerSecond=write_mb, networkBandwidthMbPerSecond=nic_mb))


def fat_fabric(k=2, cores=1, placements=((10, 0, 0), (20, 1, 0)), store_host=10, link_rate=5):
    """Declare full link inventory only. No helper computes an expected copy route."""
    result = fabric(store_host=store_host)
    result["topology"] = dict(kind="FAT_TREE", k=k, coreSwitchCount=cores,
                              linkBandwidthBytesPerSecond=link_rate,
                              hostPlacements=[dict(hostId=h, pod=p, edge=e) for h, p, e in placements])
    half = k // 2
    pairs = [(f"ACC:{h}", f"EDGE:{p}:{e}") for h, p, e in placements]
    pairs += [(f"EDGE:{p}:{e}", f"AGG:{p}:{a}") for p in range(k) for e in range(half) for a in range(half)]
    pairs += [(f"AGG:{p}:{c // half}", f"CORE:{c}") for p in range(k) for c in range(cores)]
    for a, b in pairs:
        result["resources"].extend((resource(f"LINK:{a}->{b}", link_rate), resource(f"LINK:{b}->{a}", link_rate)))
    return result


CROSS = ["LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->AGG:0:0", "LINK:AGG:0:0->CORE:0",
         "LINK:CORE:0->AGG:1:0", "LINK:AGG:1:0->EDGE:1:0", "LINK:EDGE:1:0->ACC:20"]
REVERSE = ["LINK:ACC:20->EDGE:1:0", "LINK:EDGE:1:0->AGG:1:0", "LINK:AGG:1:0->CORE:0",
           "LINK:CORE:0->AGG:0:0", "LINK:AGG:0:0->EDGE:0:0", "LINK:EDGE:0:0->ACC:10"]


def e(kind, now, **payload):
    return dict(sequence=0, observedTime=now, type=kind, payload=payload)


def request(job, tid, vm, now):
    return e("JOB_INPUT_REQUESTED", now, jobId=job, taskIds=[tid], destinationVmId=vm)


def ready(job, now):
    return e("JOB_DATA_READY", now, jobId=job)


def cpu(job, vm, now):
    return e("JOB_CPU_STARTED", now, jobId=job, vmId=vm)


def finish(job, tid, vm, now, success=True):
    return e("TASK_FINISHED", now, jobId=job, taskId=tid, vmId=vm, success=success)


def seed(name, scope=0):
    return e("EXTERNAL_SEEDED", 0, fileId=fid(name, scope), location=loc())


def replica(name, vm, visible, acquisition, producer=None, attempt=None,
            origin_vm=None, origin_at=0, copied=None, ordinal=None, scope=0):
    return dict(fileId=fid(name, scope), location=loc(vm), visibleAt=visible, acquisition=acquisition,
                origin=dict(producerTaskId=producer, jobAttemptId=attempt, location=loc(origin_vm), observedAt=origin_at),
                copiedFrom=copied, copyOrdinal=ordinal)


def admit(ordinal, name, source, vm, purpose, owner, size, path, rate, seconds, now, scope=0):
    return e("COPY_ADMITTED", now, copyOrdinal=ordinal, fileId=fid(name, scope), bytes=size,
             sourceReplica=source, destination=loc(vm), purpose=purpose, ownerJobId=owner,
             resources=list(path), standaloneRate=rate, isolatedSeconds=seconds)


def resolve(job, name, resolution, vm, now, ordinal=None, count=1, scope=0):
    return e("INPUT_RESOLVED", now, jobId=job, fileId=fid(name, scope), referenceCount=count,
             resolution=resolution, copyOrdinal=ordinal, source=loc(vm))


def wait(job, name, now, count=1, scope=0):
    return e("INPUT_WAITING_FOR_STORE", now, jobId=job, fileId=fid(name, scope), referenceCount=count)


def output(job, tid, name, resolution, vm, now, ordinal=None, scope=0):
    return e("OUTPUT_RESOLVED", now, jobId=job, taskId=tid, fileId=fid(name, scope),
             resolution=resolution, copyOrdinal=ordinal, source=loc(vm))


def settle(ordinal, effective, observed, residual=0):
    return e("COPY_SETTLED", observed, copyOrdinal=ordinal, effectiveTime=effective, remainingAfterService=residual)


def seal(doc, through=None):
    """Fix bookkeeping after edits: semantic deletions cannot hide behind old counters."""
    for index, event in enumerate(doc["events"], 1):
        event["sequence"] = index
    doc["capture"]["retainedRecords"] = len(doc["events"])
    doc["capture"]["observedThrough"] = (doc["events"][-1]["observedTime"] if doc["events"] else 0) if through is None else through
    return doc


def document(tasks=(), files=(), events=(), physical=None, store_inputs=False, isolated=False):
    return seal(dict(schema="workflowsim-storage-lifecycle-v3",
        modelKind="COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3" if isolated else "COHERENT_STORAGE_DATAFLOW_V3",
        recording=dict(mode="FILE_STORAGE_LIFECYCLE_V3", maxTraceRecords=1000),
        certificateScope="STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3",
        policies=dict(fileIdentity="SCOPED_RESOLVED_WRITE_ONCE_FILES_V2", release="DEPENDENCY_READY_AT_OBSERVATION_V2",
                      visibility="PER_FILE_SETTLEMENT_OBSERVATION_V2",
                      selection=STORE if store_inputs else "VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2",
                      sourceAccess="BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3",
                      sharing="ISOLATED_PATH_BOTTLENECK" if isolated else "SHARED_MAX_MIN",
                      inputAccess=STORE if store_inputs else LOCAL, outputCommit="ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3"),
        capture=dict(status="COMPLETE", observedThrough=0, retainedRecords=0, droppedRecords=0),
        filePlan=dict(contractVersion="SCOPED_RESOLVED_WRITE_ONCE_FILES_V2", tasks=list(tasks), files=list(files)),
        fabric=fabric() if physical is None else physical, events=list(events)))


def roundtrip(store_inputs=False, isolated=False, topology=False):
    original = replica("data", 1, 1, "TASK_OUTPUT", 0, 10, 1, 1)
    stored = replica("data", None, 4, "COPY_SETTLEMENT", 0, 10, 1, 1, loc(1), 1)
    events = [request(10, 0, 1, 0), ready(10, 0), cpu(10, 1, 0), finish(10, 0, 1, 1),
              admit(1, "data", original, None, "OUTPUT", 10, 20, ["VM:1", NIC, WRITE], 10, 2, 1),
              output(10, 0, "data", "NEW_COPY", 1, 1, 1), request(11, 1, 2, 2)]
    if store_inputs:
        at, effective, observed, source = 4, 8 if topology else 5, 9 if topology else 6, stored
        path = [READ, NIC] + (CROSS if topology else []) + ["VM:2"]
        events += [wait(11, "data", 2, 3), settle(1, 3, 4)]
    else:
        at, effective, observed, source = 2, 6 if topology else 3, 7 if topology else 5, original
        path = ["VM:1"] + (CROSS if topology else []) + ["VM:2"]
    events += [admit(2, "data", source, 2, "INPUT", 11, 20, path, 5 if topology else 20, 4 if topology else 1, at),
               resolve(11, "data", "NEW_COPY", None if store_inputs else 1, at, 2, 3)]
    if not store_inputs:
        events += [settle(1, 3, 4)]
    events += [settle(2, effective, observed), ready(11, observed), cpu(11, 2, observed), finish(11, 1, 2, observed + 1)]
    return document([task(0, outputs=["data"]), task(1, parents=[0], inputs=[("data", 3)])],
                    [file("data", 20, 0)], events, fat_fabric() if topology else None, store_inputs, isolated)


def same_vm_gate():
    doc = roundtrip(store_inputs=True)
    doc["events"] = doc["events"][:6] + [request(11, 1, 1, 2), wait(11, "data", 2, 3),
        settle(1, 3, 4), resolve(11, "data", "LOCAL", 1, 4, count=3), ready(11, 4), cpu(11, 1, 4), finish(11, 1, 1, 5)]
    return seal(doc)


def zero_and_sink():
    original = replica("unused", 1, 1, "TASK_OUTPUT", 0, 10, 1, 1)
    return document([task(0, outputs=["zero", "unused"])], [file("zero", 0, 0), file("unused", 20, 0)],
        [request(10, 0, 1, 0), ready(10, 0), cpu(10, 1, 0), finish(10, 0, 1, 1),
         admit(1, "unused", original, None, "OUTPUT", 10, 20, ["VM:1", NIC, WRITE], 10, 2, 1),
         output(10, 0, "unused", "NEW_COPY", 1, 1, 1), output(10, 0, "zero", "ZERO", 1, 1), settle(1, 3, 4)])


def cohorts():
    a = replica("a", 1, 1, "TASK_OUTPUT", 0, 10, 1, 1)
    b = replica("b", 1, 1, "TASK_OUTPUT", 0, 10, 1, 1)
    sa = replica("a", None, 4, "COPY_SETTLEMENT", 0, 10, 1, 1, loc(1), 1)
    sb = replica("b", None, 4, "COPY_SETTLEMENT", 0, 10, 1, 1, loc(1), 2)
    return document([task(0, outputs=["a", "b"]), task(1, [0], [("a", 2), ("b", 1)]),
                     task(2, [0], [("a", 1)]), task(3, [0], [("a", 1)])],
        [file("a", 10, 0), file("b", 20, 0)],
        [request(10, 0, 1, 0), ready(10, 0), cpu(10, 1, 0), finish(10, 0, 1, 1),
         admit(1, "a", a, None, "OUTPUT", 10, 10, ["VM:1", NIC, WRITE], 10, 1, 1),
         output(10, 0, "a", "NEW_COPY", 1, 1, 1),
         admit(2, "b", b, None, "OUTPUT", 10, 20, ["VM:1", NIC, WRITE], 10, 2, 1),
         output(10, 0, "b", "NEW_COPY", 1, 1, 2), request(11, 1, 2, 1), wait(11, "a", 1, 2), wait(11, "b", 1),
         request(12, 2, 2, 1.5), wait(12, "a", 1.5), request(13, 3, 1, 1.5), wait(13, "a", 1.5),
         settle(1, 2, 4), admit(3, "a", sa, 2, "INPUT", 11, 10, [READ, NIC, "VM:2"], 20, .5, 4),
         resolve(11, "a", "NEW_COPY", None, 4, 3, 2), resolve(12, "a", "JOIN_EXISTING", None, 4, 3),
         resolve(13, "a", "LOCAL", 1, 4), ready(13, 4), settle(2, 3, 4),
         admit(4, "b", sb, 2, "INPUT", 11, 20, [READ, NIC, "VM:2"], 20, 1, 4),
         resolve(11, "b", "NEW_COPY", None, 4, 4), settle(3, 4.5, 6), ready(12, 6),
         settle(4, 5, 6), ready(11, 6), cpu(13, 1, 6), cpu(12, 2, 6), cpu(11, 2, 6),
         finish(13, 3, 1, 7), finish(12, 2, 2, 7), finish(11, 1, 2, 7)], store_inputs=True)


def failed_retry():
    external = replica("external", None, 0, "EXTERNAL_SEED")
    out = replica("out", 1, 5, "TASK_OUTPUT", 0, 11, 1, 5)
    stored = replica("out", None, 8, "COPY_SETTLEMENT", 0, 11, 1, 5, loc(1), 2)
    return document([task(0, inputs=[("external", 2)], outputs=["out"]), task(1, [0], [("out", 1)])],
        [file("external", 20), file("out", 20, 0)],
        [seed("external"), request(10, 0, 1, 0),
         admit(1, "external", external, 1, "INPUT", 10, 20, [READ, NIC, "VM:1"], 20, 1, 0),
         resolve(10, "external", "NEW_COPY", None, 0, 1, 2), settle(1, 1, 2), ready(10, 2), cpu(10, 1, 2),
         finish(10, 0, 1, 3, False), request(11, 0, 1, 4), resolve(11, "external", "LOCAL", 1, 4, count=2),
         ready(11, 4), cpu(11, 1, 4), finish(11, 0, 1, 5),
         admit(2, "out", out, None, "OUTPUT", 11, 20, ["VM:1", NIC, WRITE], 10, 2, 5),
         output(11, 0, "out", "NEW_COPY", 1, 5, 2), request(12, 1, 2, 6), wait(12, "out", 6), settle(2, 7, 8),
         admit(3, "out", stored, 2, "INPUT", 12, 20, [READ, NIC, "VM:2"], 20, 1, 8),
         resolve(12, "out", "NEW_COPY", None, 8, 3), settle(3, 9, 10), ready(12, 10), cpu(12, 2, 10), finish(12, 1, 2, 11)],
        store_inputs=True)


def cached_peer():
    original = replica("x", None, 0, "EXTERNAL_SEED")
    cache = replica("x", 2, 12, "COPY_SETTLEMENT", copied=loc(), ordinal=1)
    return document([task(0, inputs=[("x", 1)]), task(1, inputs=[("x", 3)])], [file("x", 100)],
        [seed("x"), request(10, 0, 2, 0), admit(1, "x", original, 2, "INPUT", 10, 100, [READ, NIC, "VM:2"], 10, 10, 0),
         resolve(10, "x", "NEW_COPY", None, 0, 1), settle(1, 10, 12), ready(10, 12), cpu(10, 2, 12), finish(10, 0, 2, 13),
         request(11, 1, 3, 14), admit(2, "x", cache, 3, "INPUT", 11, 100, ["VM:2", "VM:3"], 50, 2, 14),
         resolve(11, "x", "NEW_COPY", 2, 14, 2, 3), settle(2, 16, 17), ready(11, 17), cpu(11, 3, 17), finish(11, 1, 3, 18)],
        fabric(((1, 40, 10), (2, 100, 20), (3, 50, 30)), read_mb=.00001, nic_mb=.0001))


def output_joins():
    original = replica("x", 10, 1, "TASK_OUTPUT", 0, 10, 10, 1)
    peer = replica("x", 2, 2, "TASK_OUTPUT", 0, 20, 2, 2)
    return document([task(0, outputs=["x"]), task(1, [0], [("x", 1)])], [file("x", 20, 0)],
        [request(10, 0, 10, 0), ready(10, 0), cpu(10, 10, 0), finish(10, 0, 10, 1),
         admit(1, "x", original, None, "OUTPUT", 10, 20, ["VM:10", NIC, WRITE], 10, 2, 1),
         output(10, 0, "x", "NEW_COPY", 10, 1, 1), request(20, 0, 2, 1), ready(20, 1), cpu(20, 2, 1), finish(20, 0, 2, 2),
         output(20, 0, "x", "JOIN_EXISTING", 2, 2, 1), request(30, 1, 99, 2),
         admit(2, "x", peer, 99, "INPUT", 30, 20, ["VM:2", "VM:99"], 10, 2, 2),
         resolve(30, "x", "NEW_COPY", 2, 2, 2), settle(1, 3, 4), settle(2, 4, 5), ready(30, 5), cpu(30, 99, 5),
         finish(30, 1, 99, 6), request(21, 0, 2, 7), ready(21, 7), cpu(21, 2, 7), finish(21, 0, 2, 8),
         output(21, 0, "x", "ALREADY_STORED", 2, 8)], fabric(((2, 10, 20), (10, 10, 10), (99, 20, 30))))


def deferred_magnitudes():
    """a waits, b/c resolve first: request byte order differs from isolated-sum order."""
    huge = 9007199254740992  # 2**53: huge+1+1 rounds differently from 1+1+huge.
    original = replica('a', 1, 0, 'TASK_OUTPUT', 0, 10, 1, 0)
    stored = replica('a', None, huge, 'COPY_SETTLEMENT', 0, 10, 1, 0, loc(1), 1)
    return document([task(0, outputs=['a']), task(1, [0], [('a', 1), ('b', 1), ('c', 1)])],
        [file('a', huge, 0), file('b', 1), file('c', 1)],
        [seed('b'), seed('c'), request(10, 0, 1, 0), ready(10, 0), cpu(10, 1, 0), finish(10, 0, 1, 0),
         admit(1, 'a', original, None, 'OUTPUT', 10, huge, ['VM:1', NIC, WRITE], 1, huge, 0),
         output(10, 0, 'a', 'NEW_COPY', 1, 0, 1), request(11, 1, 2, 0), wait(11, 'a', 0),
         admit(2, 'b', replica('b', None, 0, 'EXTERNAL_SEED'), 2, 'INPUT', 11, 1, [READ, NIC, 'VM:2'], 1, 1, 0),
         resolve(11, 'b', 'NEW_COPY', None, 0, 2),
         admit(3, 'c', replica('c', None, 0, 'EXTERNAL_SEED'), 2, 'INPUT', 11, 1, [READ, NIC, 'VM:2'], 1, 1, 0),
         resolve(11, 'c', 'NEW_COPY', None, 0, 3), settle(2, 1, 1), settle(3, 1, 1), settle(1, huge, huge),
         admit(4, 'a', stored, 2, 'INPUT', 11, huge, [READ, NIC, 'VM:2'], 1, huge, huge),
         resolve(11, 'a', 'NEW_COPY', None, huge, 4)],
        fabric(((1, 1, 10), (2, 1, 20)), read_mb=.000001, write_mb=.000001, nic_mb=.000001),
        store_inputs=True, isolated=True)


def prefix(doc, length, through=None):
    result = copy.deepcopy(doc)
    result["events"] = result["events"][:length]
    return seal(result, through)


def changed(doc, path, value):
    result = copy.deepcopy(doc)
    item = result
    for key in path[:-1]:
        item = item[key]
    item[path[-1]] = value
    return result


def audit(doc):
    return verify_document(decode_json(json.dumps(doc, ensure_ascii=True, allow_nan=False)))


class StorageLifecycleOracles(unittest.TestCase):
    def bad(self, doc):
        with self.assertRaises(Invalid):
            verify_document(doc)

    def test_all_eight_model_access_topology_combinations(self):
        for isolated in (False, True):
            for store_inputs in (False, True):
                for topology in (False, True):
                    with self.subTest(isolated=isolated, store_inputs=store_inputs, topology=topology):
                        report = audit(roundtrip(store_inputs, isolated, topology))
                        self.assertEqual("VALID_COMPLETE", report["status"])
                        self.assertTrue(report["completeCaptureCertified"])
                        self.assertTrue(report["lifecycleQuiescent"])
                        self.assertFalse(report["contextualRunChecked"])
                        self.assertFalse(report["fluidServiceAccountingCertified"])
                        self.assertEqual((2, 2, 0, 0), tuple(report[k] for k in
                            ("admissionCount", "settlementCount", "pendingOutputFileCount", "waitingStoreInputCount")))
                        output_copy, input_copy = report["facts"]["copies"]
                        self.assertEqual(("OUTPUT", 10, loc(), None),
                            tuple(output_copy[k] for k in ("purpose", "ownerJobId", "destination", "destinationVmId")))
                        self.assertEqual("INPUT", input_copy["purpose"])
                        self.assertEqual(loc(2), input_copy["destination"])
                        self.assertEqual(loc() if store_inputs else loc(1), input_copy["sourceReplica"]["location"])
                        job = report["facts"]["jobs"][1]
                        self.assertEqual((3, 60, 1, 0), tuple(job[k] for k in
                            ("referenceCount", "requiredReferenceBytes", "newFileCopies", "joinedFileCopies")))
                        self.assertEqual(1 if store_inputs else 0, len(job["sourceWaits"]))

    def test_same_vm_gate_still_requires_real_store_charges(self):
        doc = same_vm_gate()
        result = audit(doc)
        self.assertEqual(["VM:1", NIC, WRITE], result["facts"]["copies"][0]["resources"])
        job = result["facts"]["jobs"][1]
        self.assertEqual((2, 4, 4), (job["sourceWaits"][0]["startedAt"], job["sourceWaits"][0]["resolvedAt"], job["dataReadyAt"]))
        self.assertEqual((0, "LOCAL"), (job["newFileCopies"], job["inputs"][0]["resolution"]))
        bypass = copy.deepcopy(doc)
        bypass["events"] = doc["events"][:7] + [resolve(11, "data", "LOCAL", 1, 2, count=3), ready(11, 2), cpu(11, 1, 2)]
        self.bad(seal(bypass))
        self.bad(changed(doc, ("events", 9, "observedTime"), 3))
        self.bad(changed(doc, ("events", 4, "payload", "resources"), ["VM:1"]))

    def test_source_commit_is_not_target_copy_readiness(self):
        doc = roundtrip(store_inputs=True)
        report = audit(prefix(doc, 11))
        self.assertEqual((0, 0, 1, 1), tuple(report[k] for k in
                         ("pendingOutputFileCount", "waitingStoreInputCount", "activeCopyCount", "waitingJobCount")))
        self.assertEqual([fid()], report["facts"]["jobs"][1]["pendingFiles"])
        self.assertIsNone(report["facts"]["jobs"][1]["dataReadyAt"])
        for event in (ready(11, 4), cpu(11, 2, 4)):
            bad = prefix(doc, 11)
            bad["events"].append(event)
            self.bad(seal(bad))
        self.bad(changed(doc, ("events", 9, "payload", "sourceReplica", "visibleAt"), 3))

    def test_zero_and_unused_sink_outputs_are_mandatory(self):
        doc = zero_and_sink()
        report = audit(doc)
        self.assertEqual((1, 2), (report["admissionCount"], report["outputResolutionCount"]))
        zero = next(r for r in report["facts"]["replicas"] if r["fileId"] == fid("zero") and r["location"] == loc())
        self.assertEqual(("ZERO_BYTE_OUTPUT", loc(1), None, 1),
                         (zero["acquisition"], zero["copiedFrom"], zero["copyOrdinal"], zero["origin"]["observedAt"]))
        pending = audit(prefix(doc, 7))
        self.assertEqual((1, 1, 1), (pending["pendingOutputFileCount"], pending["activeCopyCount"], pending["finishedJobCount"]))
        self.assertFalse(pending["lifecycleQuiescent"])
        for length in (4, 5, 6):
            self.bad(prefix(doc, length))
        for index in (4, 5, 6):
            broken = copy.deepcopy(doc)
            del broken["events"][index]
            self.bad(seal(broken))
        for key, value in (("resolution", "ALREADY_STORED"), ("copyOrdinal", 1), ("source", loc())):
            self.bad(changed(doc, ("events", 6, "payload", key), value))

    def test_source_waiters_coalesce_and_multiple_cohorts_resume_atomically(self):
        doc = cohorts()
        before = audit(prefix(doc, 15))
        self.assertEqual((4, 2), (before["waitingStoreInputCount"], before["pendingOutputFileCount"]))
        middle = audit(prefix(doc, 21))
        self.assertEqual((1, 1, 2, 1), tuple(middle[k] for k in
            ("waitingStoreInputCount", "pendingOutputFileCount", "activeCopyCount", "readyNotStartedJobCount")))
        report = audit(doc)
        jobs = {j["jobId"]: j for j in report["facts"]["jobs"]}
        self.assertEqual((2, 0, 1.5, 6), tuple(jobs[11][k] for k in
                         ("newFileCopies", "joinedFileCopies", "isolatedInputSeconds", "dataReadyAt")))
        self.assertEqual((0, 1, .5, 6), tuple(jobs[12][k] for k in
                         ("newFileCopies", "joinedFileCopies", "isolatedInputSeconds", "dataReadyAt")))
        self.assertEqual(4, jobs[13]["dataReadyAt"])
        self.assertEqual((2, 2), (report["inputCopyCount"], report["outputCopyCount"]))
        # All waiters must resume before another settlement, even in one outer observation.
        interrupted = copy.deepcopy(doc)
        interrupted["events"][16], interrupted["events"][21] = interrupted["events"][21], interrupted["events"][16]
        self.bad(seal(interrupted))
        swapped = copy.deepcopy(doc)
        swapped["events"][18:20] = reversed(swapped["events"][18:20])
        self.bad(seal(swapped))
        for count in (16, 17, 18, 19, 20, 22, 23, 25, 27):
            with self.subTest(prefix=count):
                self.bad(prefix(doc, count))

    def test_failed_attempt_does_not_publish_and_retry_keeps_cache(self):
        doc = failed_retry()
        failed = audit(prefix(doc, 8))
        self.assertTrue(failed["lifecycleQuiescent"])
        self.assertEqual((0, 0), (failed["outputCopyCount"], failed["pendingOutputFileCount"]))
        self.assertFalse(any(r["fileId"] == fid("out") for r in failed["facts"]["replicas"]))
        result = audit(doc)
        jobs = {j["jobId"]: j for j in result["facts"]["jobs"]}
        self.assertFalse(jobs[10]["success"])
        self.assertEqual("LOCAL", jobs[11]["inputs"][0]["resolution"])
        self.assertEqual(1, result["outputCopyCount"])
        self.assertEqual(11, result["facts"]["copies"][1]["sourceReplica"]["origin"]["jobAttemptId"])
        self.bad(changed(doc, ("events", 13, "payload", "ownerJobId"), 10))
        self.bad(changed(doc, ("events", 13, "payload", "sourceReplica", "origin", "jobAttemptId"), 10))
        premature = prefix(doc, 8)
        premature["events"].append(request(12, 1, 2, 3))
        self.bad(seal(premature))

    def test_local_policy_uses_fast_cached_peer_and_preserves_origin(self):
        doc = cached_peer()
        result = audit(doc)
        copied = result["facts"]["copies"][1]["sourceReplica"]
        self.assertEqual((loc(2), loc(), loc(), 1, 12),
            (copied["location"], copied["origin"]["location"], copied["copiedFrom"], copied["copyOrdinal"], copied["visibleAt"]))
        slow = copy.deepcopy(doc)
        slow["events"][9]["payload"].update(sourceReplica=replica("x", None, 0, "EXTERNAL_SEED"),
                                           resources=[READ, NIC, "VM:3"], standaloneRate=10, isolatedSeconds=10)
        slow["events"][10]["payload"]["source"] = loc()
        self.bad(slow)
        for key, value in (("copiedFrom", loc(1)), ("copyOrdinal", 2), ("visibleAt", 10), ("acquisition", "TASK_OUTPUT")):
            self.bad(changed(doc, ("events", 9, "payload", "sourceReplica", key), value))
        shared = copy.deepcopy(doc)
        shared["policies"].update(selection=STORE, inputAccess=STORE)
        self.bad(shared)  # Cache at a different VM must not bypass the committed store.
        shared["events"] = shared["events"][:9] + [
            admit(2, "x", replica("x", None, 0, "EXTERNAL_SEED"), 3, "INPUT", 11, 100, [READ, NIC, "VM:3"], 10, 10, 14),
            resolve(11, "x", "NEW_COPY", None, 14, 2, 3)]
        self.assertEqual(1, audit(seal(shared))["activeCopyCount"])

    def test_output_joins_current_owner_actual_vm_and_first_origin(self):
        doc = output_joins()
        report = audit(doc)
        resolutions = report["facts"]["outputResolutions"]
        self.assertEqual(["NEW_COPY", "JOIN_EXISTING", "ALREADY_STORED"], [r["resolution"] for r in resolutions])
        self.assertEqual([loc(10), loc(2), loc(2)], [r["source"] for r in resolutions])
        self.assertEqual(loc(2), report["facts"]["copies"][1]["sourceReplica"]["location"])  # numeric tie order
        source = next(r for r in report["facts"]["replicas"] if r["location"] == loc())
        vm2 = next(r for r in report["facts"]["replicas"] if r["location"] == loc(2))
        self.assertEqual((10, 1), (source["origin"]["jobAttemptId"], source["origin"]["observedAt"]))
        self.assertEqual((20, 2), (vm2["origin"]["jobAttemptId"], vm2["visibleAt"]))
        self.bad(changed(doc, ("events", 10, "payload", "source"), loc(10)))
        self.bad(changed(doc, ("events", 10, "payload", "resolution"), "NEW_COPY"))
        self.bad(changed(doc, ("events", 23, "payload", "resolution"), "JOIN_EXISTING"))
        tie = copy.deepcopy(doc)
        tie["events"][12]["payload"].update(sourceReplica=replica("x", 10, 1, "TASK_OUTPUT", 0, 10, 10, 1), resources=["VM:10", "VM:99"])
        tie["events"][13]["payload"]["source"] = loc(10)
        self.bad(tie)

    def test_read_write_nic_bottlenecks_are_independent(self):
        for read_mb, write_mb, nic_mb, read_rate, write_rate in (
                (.000003, .000007, .00005, 3, 7), (.00002, .00001, .000004, 4, 4),
                (.000008, .000002, .000009, 8, 2)):
            doc = document([task(0, inputs=[("x", 1)], outputs=["y"])], [file("x", 21), file("y", 14, 0)],
                [seed("x"), request(10, 0, 1, 0),
                 admit(1, "x", replica("x", None, 0, "EXTERNAL_SEED"), 1, "INPUT", 10, 21, [READ, NIC, "VM:1"], read_rate, 21 / read_rate, 0),
                 resolve(10, "x", "NEW_COPY", None, 0, 1), settle(1, 21 / read_rate, 10), ready(10, 10), cpu(10, 1, 10), finish(10, 0, 1, 11),
                 admit(2, "y", replica("y", 1, 11, "TASK_OUTPUT", 0, 10, 1, 11), None, "OUTPUT", 10, 14,
                       ["VM:1", NIC, WRITE], write_rate, 14 / write_rate, 11), output(10, 0, "y", "NEW_COPY", 1, 11, 2),
                 settle(2, 11 + 14 / write_rate, 20)], fabric(read_mb=read_mb, write_mb=write_mb, nic_mb=nic_mb))
            with self.subTest(read=read_rate, write=write_rate):
                copies = audit(doc)["facts"]["copies"]
                self.assertEqual([read_rate, write_rate], [c["standaloneRate"] for c in copies])
                self.bad(changed(doc, ("events", 2, "payload", "resources"), [NIC, READ, "VM:1"]))
                self.bad(changed(doc, ("events", 8, "payload", "resources"), ["VM:1", WRITE, NIC]))

    def test_fat_paths_bidirectional_read_write_and_same_host(self):
        # Store on host10 and producer VM2 on host20: uploads are the literal reverse path.
        original = replica("out", 2, 1, "TASK_OUTPUT", 0, 10, 2, 1)
        doc = document([task(0, outputs=["out"])], [file("out", 20, 0)],
            [request(10, 0, 2, 0), ready(10, 0), cpu(10, 2, 0), finish(10, 0, 2, 1),
             admit(1, "out", original, None, "OUTPUT", 10, 20, ["VM:2"] + REVERSE + [NIC, WRITE], 5, 4, 1),
             output(10, 0, "out", "NEW_COPY", 2, 1, 1), settle(1, 5, 6)], fat_fabric())
        self.assertEqual(["VM:2"] + REVERSE + [NIC, WRITE], audit(doc)["facts"]["copies"][0]["resources"])
        self.bad(changed(doc, ("events", 4, "payload", "resources"), ["VM:2"] + CROSS + [NIC, WRITE]))
        same = document([task(0, inputs=[("x", 1)])], [file("x", 20)],
            [seed("x"), request(10, 0, 3, 0), admit(1, "x", replica("x", None, 0, "EXTERNAL_SEED"), 3,
                "INPUT", 10, 20, [READ, NIC, "VM:3"], 20, 1, 0), resolve(10, "x", "NEW_COPY", None, 0, 1)], fat_fabric())
        self.assertEqual([READ, NIC, "VM:3"], audit(same)["facts"]["copies"][0]["resources"])
        self.bad(changed(same, ("events", 2, "payload", "resources"), ["VM:3"]))

    def test_same_edge_pod_reduced_core_and_actual_store_attachment_paths(self):
        cases = [
            (((10, 0, 1), (20, 0, 1)), 3, ["LINK:ACC:10->EDGE:0:1", "LINK:EDGE:0:1->ACC:20"]),
            (((10, 0, 1), (20, 0, 0)), 3, ["LINK:ACC:10->EDGE:0:1", "LINK:EDGE:0:1->AGG:0:1", "LINK:AGG:0:1->EDGE:0:0", "LINK:EDGE:0:0->ACC:20"]),
            (((10, 0, 1), (20, 3, 0)), 3, ["LINK:ACC:10->EDGE:0:1", "LINK:EDGE:0:1->AGG:0:1", "LINK:AGG:0:1->CORE:2",
                                         "LINK:CORE:2->AGG:3:1", "LINK:AGG:3:1->EDGE:3:0", "LINK:EDGE:3:0->ACC:20"]),
            (((10, 0, 1), (20, 2, 0)), 4, ["LINK:ACC:10->EDGE:0:1", "LINK:EDGE:0:1->AGG:0:1", "LINK:AGG:0:1->CORE:3",
                                         "LINK:CORE:3->AGG:2:1", "LINK:AGG:2:1->EDGE:2:0", "LINK:EDGE:2:0->ACC:20"])]
        for placements, cores, links in cases:
            doc = roundtrip(store_inputs=True, topology=True)
            doc["fabric"] = fat_fabric(4, cores, placements)
            doc["events"][9]["payload"]["resources"] = [READ, NIC] + links + ["VM:2"]
            with self.subTest(links=links):
                self.assertEqual([READ, NIC] + links + ["VM:2"], audit(doc)["facts"]["copies"][1]["resources"])
        doc = document([task(0, inputs=[("x", 1)])], [file("x", 20)],
            [seed("x"), request(0, 0, 1, 0), admit(1, "x", replica("x", None, 0, "EXTERNAL_SEED"), 1,
                "INPUT", 0, 20, [READ, NIC] + REVERSE + ["VM:1"], 5, 4, 0), resolve(0, "x", "NEW_COPY", None, 0, 1)],
            fat_fabric(store_host=20))
        self.assertEqual([READ, NIC] + REVERSE + ["VM:1"], audit(doc)["facts"]["copies"][0]["resources"])

    def test_complete_prefixes_can_be_active_deferred_ready_or_running(self):
        doc = roundtrip(store_inputs=True)
        for length in (0, 2, 3, 6, 8, 11, 13, 14, 15):
            with self.subTest(length=length):
                self.assertEqual("VALID_COMPLETE", audit(prefix(doc, length))["status"])
        self.assertEqual(1, audit(prefix(doc, 8))["waitingStoreInputCount"])
        self.assertEqual(1, audit(prefix(doc, 13))["readyNotStartedJobCount"])
        self.assertEqual(1, audit(prefix(doc, 14))["runningJobCount"])
        self.assertTrue(audit(document())["lifecycleQuiescent"])
        self.assertTrue(audit(document([task(99)]))["lifecycleQuiescent"])  # no claim of enclosing-run completeness
        for length in (1, 4, 5, 7, 9, 10, 12):
            self.bad(prefix(doc, length))

    def test_every_initial_reference_requires_one_decision(self):
        doc = cohorts()
        self.bad(prefix(doc, 10))  # request decided a but omitted b
        for index in (9, 10, 12, 14):
            altered = copy.deepcopy(doc)
            del altered["events"][index]
            self.bad(seal(altered))
        self.bad(changed(doc, ("events", 10, "payload", "fileId"), fid("a")))
        self.bad(changed(doc, ("events", 9, "payload", "referenceCount"), 1))
        self.bad(changed(doc, ("events", 9, "observedTime"), 1.1))
        self.bad(changed(roundtrip(), ("events", 8, "type"), "INPUT_WAITING_FOR_STORE"))
        committed = cached_peer()
        committed["policies"].update(selection=STORE, inputAccess=STORE)
        committed["events"] = [seed("x"), request(10, 0, 2, 0), wait(10, "x", 0)]
        self.bad(seal(committed))

    def test_recounted_rehashed_semantic_deletions_are_rejected(self):
        doc = roundtrip(store_inputs=True)
        before = hashlib.sha256(json.dumps(doc).encode()).hexdigest()
        # A whole completed-run suffix is not authenticated here; an omitted
        # final Task outcome is a valid running prefix. Internal causal deletions are not.
        for index in range(len(doc["events"]) - 1):
            broken = copy.deepcopy(doc)
            del broken["events"][index]
            seal(broken)
            encoded = json.dumps(broken, sort_keys=True, indent=1)
            self.assertNotEqual(before, hashlib.sha256(encoded.encode()).hexdigest())
            with self.subTest(deletion=index):
                self.bad(decode_json(encoded))
        for index in (3, 4, 5, 7, 8, 10, 11, 12, 13, 14):
            duplicate = copy.deepcopy(doc)
            duplicate["events"].insert(index + 1, copy.deepcopy(doc["events"][index]))
            self.bad(seal(duplicate))

    def test_output_resolution_and_copy_owner_mutations(self):
        base = roundtrip(store_inputs=True)
        mutations = [(4, "ownerJobId", 11), (4, "purpose", "INPUT"), (4, "destination", loc(2)),
                     (4, "copyOrdinal", 2), (4, "fileId", fid("unknown")), (4, "bytes", 0),
                     (5, "resolution", "JOIN_EXISTING"), (5, "resolution", "ALREADY_STORED"),
                     (5, "resolution", "ZERO"), (5, "copyOrdinal", None), (5, "source", loc(2)), (5, "taskId", 1),
                     (9, "purpose", "OUTPUT"), (9, "ownerJobId", 10), (9, "destination", loc(1)),
                     (9, "destination", loc()), (10, "resolution", "JOIN_EXISTING"), (10, "source", loc(1))]
        for index, key, value in mutations:
            with self.subTest(index=index, key=key, value=value):
                self.bad(changed(base, ("events", index, "payload", key), value))
        self.bad(changed(cohorts(), ("events", 18, "payload", "copyOrdinal"), 1))  # SOURCE vs VM ticket
        self.bad(changed(cohorts(), ("events", 18, "payload", "resolution"), "NEW_COPY"))

    def test_strict_roots_policy_pairing_and_payload_keys(self):
        doc = roundtrip(store_inputs=True)
        mutations = [(('schema',), 'workflowsim-file-lifecycle-v2'), (('modelKind',), 'COHERENT_FILE_DATAFLOW_V2'),
            (('recording', 'mode'), 'FILE_LIFECYCLE_V2'), (('recording', 'maxTraceRecords'), 0),
            (('recording', 'maxTraceRecords'), INT_MAX + 1), (('certificateScope',), 'FLUID_SERVICE'),
            (('policies', 'sharing'), 'ISOLATED_PATH_BOTTLENECK'), (('policies', 'inputAccess'), LOCAL),
            (('policies', 'selection'), 'VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2'),
            (('policies', 'sourceAccess'), 'UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2'),
            (('policies', 'outputCommit'), 'ONLY_CONSUMED_OUTPUTS'), (('capture', 'status'), 'TRUNCATED'),
            (('capture', 'status'), 'DISABLED'), (('capture', 'retainedRecords'), 999), (('capture', 'droppedRecords'), 1),
            (('capture', 'observedThrough'), 2), (('events', 0, 'type'), 'DATA_READY')]
        for path, value in mutations:
            self.bad(changed(doc, path, value))
        paths = [(), ('recording',), ('policies',), ('capture',), ('filePlan',), ('filePlan', 'tasks', 0),
                 ('filePlan', 'files', 0), ('filePlan', 'files', 0, 'fileId'), ('fabric',), ('fabric', 'sourceStorage'),
                 ('fabric', 'locations', 0), ('fabric', 'resources', 0), ('fabric', 'vmHostAssignments', 0),
                 ('events', 4), ('events', 4, 'payload'), ('events', 4, 'payload', 'sourceReplica'),
                 ('events', 4, 'payload', 'sourceReplica', 'origin'), ('events', 4, 'payload', 'destination'),
                 ('events', 5, 'payload'), ('events', 7, 'payload')]
        for path in paths:
            extra = copy.deepcopy(doc)
            item = extra
            for key in path:
                item = item[key]
            item['unknown'] = None
            with self.subTest(extra=path):
                self.bad(extra)
            for key in list(item):
                if key == 'unknown':
                    continue
                missing = copy.deepcopy(doc)
                target = missing
                for part in path:
                    target = target[part]
                del target[key]
                self.bad(missing)
        old_destination = copy.deepcopy(doc)
        old_destination['events'][4]['payload']['destinationVmId'] = None
        self.bad(old_destination)
        self.bad(changed(doc, ('recording', 'maxTraceRecords'), len(doc['events']) - 1))
        self.assertTrue(audit(changed(doc, ('recording', 'maxTraceRecords'), len(doc['events'])))['completeCaptureCertified'])

    def test_strict_v2_entrypoint_never_accepts_storage_union(self):
        self.assertIs(Invalid, CheckError)
        with self.assertRaises(Invalid):
            verify_v2(decode_json(json.dumps(roundtrip())))
        v2 = document()
        v2.update(schema='workflowsim-file-lifecycle-v2', modelKind='COHERENT_FILE_DATAFLOW_V2',
                  certificateScope='FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2')
        v2['recording']['mode'] = 'FILE_LIFECYCLE_V2'
        v2['policies']['sourceAccess'] = 'UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2'
        del v2['policies']['inputAccess'], v2['policies']['outputCommit']
        v2['fabric']['vmHostAssignments'] = []
        del v2['fabric']['sourceStorage']
        v2['fabric']['resources'] = v2['fabric']['resources'][:3]
        result = verify_v2(decode_json(json.dumps(v2)))
        self.assertEqual({'jobs', 'copies', 'replicas', 'successfulTaskIds'}, set(result['facts']))
        self.assertNotIn('pendingOutputFileCount', result)
        self.bad(v2)
        v2['fabric']['sourceStorage'] = fabric()['sourceStorage']
        with self.assertRaises(Invalid):
            verify_v2(decode_json(json.dumps(v2)))

    def test_exact_integer_ids_counts_and_no_reference_expansion(self):
        doc = document([task(INT_MAX, inputs=[('external', LONG_MAX)])], [file('external', 0)], [seed('external')])
        self.assertEqual(1, audit(doc)['seedCount'])
        numeric = changed(doc, ('filePlan', 'tasks', 0, 'inputs', 0, 'referenceCount'), 'TOKEN')
        valid = decode_json(json.dumps(numeric).replace('"TOKEN"', '9.223372036854775807e18'))
        self.assertEqual(1, verify_document(valid)['seedCount'])
        for token in ('9223372036854775807.1', '9223372036854775808', '1.000000000000000000000000001'):
            self.bad(decode_json(json.dumps(numeric).replace('"TOKEN"', token)))
        requested = copy.deepcopy(doc)
        requested['events'] += [request(0, INT_MAX, 1, 0), resolve(0, 'external', 'ZERO', None, 0, count=LONG_MAX), ready(0, 0)]
        self.bad(seal(requested))
        base = roundtrip(store_inputs=True)
        for path in (('fabric', 'sourceStorage', 'attachmentHostId'), ('fabric', 'vmHostAssignments', 0, 'hostId'),
                     ('events', 4, 'payload', 'ownerJobId'), ('events', 4, 'payload', 'copyOrdinal'),
                     ('events', 7, 'payload', 'referenceCount'), ('events', 5, 'payload', 'taskId'),
                     ('events', 4, 'payload', 'sourceReplica', 'origin', 'jobAttemptId'),
                     ('capture', 'retainedRecords'), ('recording', 'maxTraceRecords')):
            value = base
            for key in path:
                value = value[key]
            encoded = json.dumps(changed(base, path, 'TOKEN')).replace('"TOKEN"', str(value) + '.00000000000000000001')
            self.bad(decode_json(encoded))
            self.bad(changed(base, path, True))

    def test_binary64_normal_capacity_and_decimal_mb_conversion(self):
        base = document()
        path = ('fabric', 'sourceStorage', 'readBandwidthMbPerSecond')
        for value in (0, -1, True, '0.1', None, float('nan'), float('inf'), Decimal('1e-999'), 1e303, math.ulp(0.0)):
            self.bad(changed(base, path, value))
        # A positive subnormal MB/s token can convert to supported normal B/s.
        mb = sys.float_info.min * 2 / 1e6
        valid = changed(base, path, mb)
        valid['fabric']['resources'][3]['capacityBytesPerSecond'] = mb * 1e6
        self.assertEqual('VALID_COMPLETE', audit(valid)['status'])
        self.bad(changed(valid, ('fabric', 'resources', 3, 'capacityBytesPerSecond'), math.nextafter(mb * 1e6, math.inf)))
        for path in (('filePlan', 'files', 0, 'bytes'), ('events', 4, 'payload', 'bytes'),
                     ('events', 4, 'payload', 'standaloneRate'), ('events', 4, 'payload', 'isolatedSeconds'),
                     ('events', 8, 'payload', 'effectiveTime')):
            for value in (True, '20', None, Decimal('1e-999'), float('inf'), float('nan')):
                self.bad(changed(roundtrip(store_inputs=True), path, value))

    def test_scalar_residual_ulp_and_absolute_finish_bounds(self):
        base = roundtrip(store_inputs=True)
        for key, value in (('effectiveTime', 2), ('effectiveTime', 1), ('effectiveTime', 5),
                           ('remainingAfterService', -1), ('remainingAfterService', .0001)):
            self.bad(changed(base, ('events', 8, 'payload', key), value))
        near = changed(base, ('events', 8, 'payload', 'effectiveTime'), math.nextafter(3.0, 0))
        self.assertEqual(2, audit(near)['settlementCount'])
        self.bad(changed(base, ('events', 8, 'payload', 'effectiveTime'), 3 - 1e-10))
        residual = changed(base, ('events', 8, 'payload', 'remainingAfterService'), 2e-8)
        residual['events'][8]['payload']['effectiveTime'] = 2.999999998
        self.assertEqual(2, audit(residual)['settlementCount'])
        tiny = math.ulp(0.0)
        doc = document([task(0, inputs=[('x', 1)])], [file('x', 2 * tiny)],
            [seed('x'), request(10, 0, 1, 0), admit(1, 'x', replica('x', None, 0, 'EXTERNAL_SEED'), 1,
                'INPUT', 10, 2 * tiny, [READ, NIC, 'VM:1'], 1, 2 * tiny, 0),
             resolve(10, 'x', 'NEW_COPY', None, 0, 1), settle(1, tiny, 2 * tiny, tiny), ready(10, 2 * tiny)],
            fabric(((1, 1, 10),), read_mb=.000001, write_mb=.000001, nic_mb=.000001))
        self.assertEqual(1, audit(doc)['settlementCount'])
        self.bad(changed(doc, ('events', 4, 'payload', 'remainingAfterService'), 2 * tiny))
        impossible = copy.deepcopy(doc)
        for event in impossible['events'][1:]:
            event['observedTime'] = 1
        self.bad(seal(impossible))  # tiny positive duration cannot advance clock 1

    def test_physical_inventory_exact_closure_and_host_maps(self):
        doc = roundtrip(topology=True)
        mutations = [(('fabric', 'sourceStorage', 'attachmentHostId'), 99),
            (('fabric', 'sourceStorage', 'attachmentHostId'), -1), (('fabric', 'vmHostAssignments'), []),
            (('fabric', 'vmHostAssignments', 1, 'vmId'), 1), (('fabric', 'vmHostAssignments', 1, 'hostId'), 99),
            (('fabric', 'resources', 3, 'key'), 'STORE:other:READ'), (('fabric', 'resources', 3, 'capacityBytesPerSecond'), 21),
            (('fabric', 'topology', 'k'), 34), (('fabric', 'topology', 'k'), 3),
            (('fabric', 'topology', 'coreSwitchCount'), 2), (('fabric', 'topology', 'linkBandwidthBytesPerSecond'), 6),
            (('fabric', 'topology', 'hostPlacements', 1, 'pod'), 0),
            (('fabric', 'locations', 3, 'sourceId'), 'other'), (('fabric', 'locations', 3, 'vmId'), 1)]
        for path, value in mutations:
            self.bad(changed(doc, path, value))
        for key in ('locations', 'resources', 'vmHostAssignments'):
            duplicate = copy.deepcopy(doc)
            duplicate['fabric'][key].append(copy.deepcopy(duplicate['fabric'][key][0]))
            self.bad(duplicate)
        for index in (0, 3, 4, 5, -1):
            missing = copy.deepcopy(doc)
            del missing['fabric']['resources'][index]
            self.bad(missing)
        no_topo = document()
        self.bad(changed(no_topo, ('fabric', 'vmHostAssignments'), []))
        self.bad(changed(no_topo, ('fabric', 'vmHostAssignments', 0, 'hostId'), -1))
        # Without topology, membership is deliberately left for platform context.
        no_topo['fabric']['sourceStorage']['attachmentHostId'] = INT_MAX
        no_topo['fabric']['vmHostAssignments'][0]['hostId'] = INT_MAX - 1
        self.assertFalse(audit(no_topo)['contextualRunChecked'])
        no_topo['fabric']['resources'].append(resource('SOURCE:source', 10))
        self.bad(no_topo)

    def test_plan_scope_graph_grammar_and_utf16_order(self):
        base = roundtrip()
        for path, value in ((('filePlan', 'tasks', 1, 'parents'), []), (('filePlan', 'tasks', 0, 'parents'), [1]),
            (('filePlan', 'tasks', 1, 'parents'), [0, 0]), (('filePlan', 'tasks', 1, 'workflowInputIndex'), 1),
            (('filePlan', 'tasks', 0, 'outputs'), []), (('filePlan', 'files', 0, 'producerTaskId'), None),
            (('filePlan', 'files', 0, 'bytes'), -1), (('filePlan', 'files', 0, 'fileId', 'name'), '')):
            self.bad(changed(base, path, value))
        for key in ('tasks', 'files'):
            duplicate = copy.deepcopy(base)
            duplicate['filePlan'][key].append(copy.deepcopy(duplicate['filePlan'][key][0]))
            self.bad(duplicate)
        self.bad(document(files=[file('unreferenced-external', 0)]))
        names = ['\U00010000', '\ue000']
        doc = document([task(0, inputs=[(names[1], 1), (names[0], 1)])], [file(names[1], 0), file(names[0], 0)],
            [seed(names[0]), seed(names[1]), request(0, 0, 1, 0), resolve(0, names[0], 'ZERO', None, 0),
             resolve(0, names[1], 'ZERO', None, 0), ready(0, 0)], store_inputs=True)
        self.assertEqual(4, audit(doc)['replicaCount'])
        doc['events'][3:5] = reversed(doc['events'][3:5])
        self.bad(seal(doc))
        scoped = document([task(0, inputs=[('input[7]/x:y', 1)]), task(1, inputs=[('input[7]/x:y', 1)], scope=1)],
            [file('input[7]/x:y', 0), file('input[7]/x:y', 0, scope=1)],
            [seed('input[7]/x:y'), seed('input[7]/x:y', 1), request(1, 1, 1, 0),
             resolve(1, 'input[7]/x:y', 'ZERO', None, 0, scope=1), ready(1, 0)])
        self.assertEqual(2, audit(scoped)['fileCount'])
        self.bad(changed(scoped, ('events', 3, 'payload', 'fileId', 'workflowInputIndex'), 0))

    def test_declaration_and_json_order_are_semantically_irrelevant(self):
        doc = cohorts()
        expected = audit(doc)
        for key in ('tasks', 'files'):
            doc['filePlan'][key].reverse()
        for item in doc['filePlan']['tasks']:
            for key in ('parents', 'inputs', 'outputs'):
                item[key].reverse()
        for key in ('locations', 'resources', 'vmHostAssignments'):
            doc['fabric'][key].reverse()
        reordered = decode_json(json.dumps(doc, sort_keys=True, indent=3))
        self.assertEqual(expected, verify_document(reordered))

    def test_deferred_reference_bytes_and_isolated_seconds_use_different_orders(self):
        doc = deferred_magnitudes()
        report = audit(doc)
        job = report['facts']['jobs'][1]
        self.assertEqual(9007199254740992, job['requiredReferenceBytes'])
        self.assertEqual(9007199254740994, job['isolatedInputSeconds'])
        self.assertEqual((3, 3, 0), (job['referenceCount'], job['newFileCopies'], job['joinedFileCopies']))
        self.assertEqual(9007199254740992, job['sourceWaits'][0]['resolvedAt'])
        self.assertEqual([fid('a')], job['pendingFiles'])
        self.assertEqual((0, 0, 1), tuple(report[k] for k in
                         ('waitingStoreInputCount', 'pendingOutputFileCount', 'activeCopyCount')))
        before = audit(prefix(doc, 14))['facts']['jobs'][1]
        self.assertEqual((9007199254740992, 2, 1),
            (before['requiredReferenceBytes'], before['isolatedInputSeconds'], len(before['sourceWaits'])))
        # Reordering declaration arrays cannot change request accumulation order.
        doc['filePlan']['tasks'][1]['inputs'].reverse()
        self.assertEqual(job, audit(doc)['facts']['jobs'][1])
        duplicate_resolution = copy.deepcopy(doc)
        duplicate_resolution['events'][-1]['payload'].update(fileId=fid('b'), copyOrdinal=2, resolution='JOIN_EXISTING')
        self.bad(duplicate_resolution)

    def test_first_vm_publication_beats_inflight_copy_and_releases_inside_output_callback(self):
        doc = roundtrip()
        doc['events'] = doc['events'][:9] + [request(12, 0, 2, 2.5), ready(12, 2.5), cpu(12, 2, 2.5),
            finish(12, 0, 2, 3), ready(11, 3), output(12, 0, 'data', 'JOIN_EXISTING', 2, 3, 1),
            cpu(11, 2, 3), settle(1, 3, 4), settle(2, 3, 5), finish(11, 1, 2, 6)]
        report = audit(seal(doc))
        vm2 = next(r for r in report['facts']['replicas'] if r['location'] == loc(2))
        self.assertEqual(('TASK_OUTPUT', 3, 12, None),
                         (vm2['acquisition'], vm2['visibleAt'], vm2['origin']['jobAttemptId'], vm2['copyOrdinal']))
        jobs = {j['jobId']: j for j in report['facts']['jobs']}
        self.assertEqual(3, jobs[11]['dataReadyAt'])
        self.assertTrue(report['lifecycleQuiescent'])
        self.assertEqual(1, report['outputCopyCount'])
        # Readiness is an atomic notification before this attempt's output join.
        swapped = copy.deepcopy(doc)
        swapped['events'][13:15] = reversed(swapped['events'][13:15])
        self.bad(seal(swapped))
        self.bad(prefix(doc, 13))
        self.bad(prefix(doc, 14))
        self.assertEqual('VALID_COMPLETE', audit(prefix(doc, 15))['status'])

    def test_input_joins_keep_frozen_peer_after_faster_store_commit(self):
        doc = output_joins()
        doc['filePlan']['tasks'].append(task(2, [0], [('x', 1)]))
        prior = doc['events']
        doc['events'] = prior[:15] + [request(31, 2, 99, 4), resolve(31, 'x', 'JOIN_EXISTING', 2, 4, 2)] + \
                        prior[15:17] + [ready(31, 5)] + prior[17:] + [cpu(31, 99, 8), finish(31, 2, 99, 9)]
        result = audit(seal(doc))
        self.assertEqual((2, 1, 1), (result['admissionCount'], result['inputCopyCount'], result['outputCopyCount']))
        job = next(j for j in result['facts']['jobs'] if j['jobId'] == 31)
        self.assertEqual((1, 2, loc(2)),
                         (job['joinedFileCopies'], job['inputs'][0]['copyOrdinal'], job['inputs'][0]['source']))
        self.bad(changed(doc, ('events', 16, 'payload', 'source'), loc()))
        self.bad(changed(doc, ('events', 16, 'payload', 'resolution'), 'NEW_COPY'))

    def test_vm_publication_does_not_consume_a_source_waiter(self):
        doc = roundtrip(store_inputs=True)
        doc['events'] = doc['events'][:8] + [request(12, 0, 2, 2.5), ready(12, 2.5), cpu(12, 2, 2.5),
            finish(12, 0, 2, 3), output(12, 0, 'data', 'JOIN_EXISTING', 2, 3, 1),
            settle(1, 3, 4), resolve(11, 'data', 'LOCAL', 2, 4, count=3), ready(11, 4), cpu(11, 2, 4), finish(11, 1, 2, 5)]
        report = audit(seal(doc))
        self.assertEqual((1, 0), (report['outputCopyCount'], report['inputCopyCount']))
        job = next(j for j in report['facts']['jobs'] if j['jobId'] == 11)
        self.assertEqual(4, job['dataReadyAt'])
        self.assertEqual(4, job['sourceWaits'][0]['resolvedAt'])
        before = audit(prefix(doc, 13))
        self.assertEqual((1, 1), (before['waitingStoreInputCount'], before['pendingOutputFileCount']))
        premature = prefix(doc, 13)
        premature['events'].append(ready(11, 3))
        self.bad(seal(premature))

    def test_local_positive_store_rate_wins_but_vm_precedes_source_on_ties(self):
        for tie in (False, True):
            doc = prefix(output_joins(), 15)
            doc['filePlan']['tasks'].append(task(2, [0], [('x', 1)]))
            doc['fabric']['locations'].insert(0, loc(3))
            doc['fabric']['vmHostAssignments'].append(dict(vmId=3, hostId=30))
            doc['fabric']['resources'].append(resource('VM:3', 10 if tie else 40))
            source = (replica('x', 2, 2, 'TASK_OUTPUT', 0, 20, 2, 2) if tie else
                      replica('x', None, 4, 'COPY_SETTLEMENT', 0, 10, 10, 1, loc(10), 1))
            path = ['VM:2', 'VM:3'] if tie else [READ, NIC, 'VM:3']
            doc['events'] += [request(40, 2, 3, 4), admit(3, 'x', source, 3, 'INPUT', 40, 20, path,
                10 if tie else 20, 2 if tie else 1, 4), resolve(40, 'x', 'NEW_COPY', 2 if tie else None, 4, 3)]
            self.assertEqual(loc(2) if tie else loc(), audit(seal(doc))['facts']['copies'][2]['sourceReplica']['location'])
            other = copy.deepcopy(doc)
            if tie:
                other['events'][16]['payload'].update(sourceReplica=replica('x', None, 4, 'COPY_SETTLEMENT', 0, 10, 10, 1, loc(10), 1),
                                                      resources=[READ, NIC, 'VM:3'])
                other['events'][17]['payload']['source'] = loc()
            else:
                other['events'][16]['payload'].update(sourceReplica=replica('x', 2, 2, 'TASK_OUTPUT', 0, 20, 2, 2),
                    resources=['VM:2', 'VM:3'], standaloneRate=10, isolatedSeconds=2)
                other['events'][17]['payload']['source'] = loc(2)
            self.bad(other)

    def test_zero_external_stable_source_order_and_local_priority(self):
        for store_inputs in (False, True):
            selected = None if store_inputs else 9
            doc = document([task(0, inputs=[('z', 1)]), task(1, inputs=[('z', 1)]), task(2, inputs=[('z', 1)])],
                [file('z', 0)], [seed('z'), request(0, 0, 9, 0), resolve(0, 'z', 'ZERO', None, 0), ready(0, 0),
                 cpu(0, 9, 0), finish(0, 0, 9, 1, False), request(1, 1, 10, 1), resolve(1, 'z', 'ZERO', selected, 1),
                 ready(1, 1), cpu(1, 10, 1), finish(1, 1, 10, 2), request(2, 2, 9, 2),
                 resolve(2, 'z', 'LOCAL', 9, 2), ready(2, 2), cpu(2, 9, 2), finish(2, 2, 9, 3)],
                fabric(((9, 40, 10), (10, 20, 20))), store_inputs=store_inputs)
            report = audit(doc)
            self.assertEqual((0, 0), (report['admissionCount'], report['pendingOutputFileCount']))
            vm10 = next(r for r in report['facts']['replicas'] if r['location'] == loc(10))
            self.assertEqual(('ZERO_BYTE_REFERENCE', loc(selected), None),
                             (vm10['acquisition'], vm10['copiedFrom'], vm10['copyOrdinal']))
            self.bad(changed(doc, ('events', 7, 'payload', 'source'), loc(9 if store_inputs else None)))
            self.bad(changed(doc, ('events', 12, 'payload', 'resolution'), 'ZERO'))

    def test_zero_output_read_carries_original_provenance_and_no_ordinal(self):
        for store_inputs in (False, True):
            selected = None if store_inputs else 1
            doc = document([task(0, outputs=['z']), task(1, [0], [('z', 2)])], [file('z', 0, 0)],
                [request(10, 0, 1, 0), ready(10, 0), cpu(10, 1, 0), finish(10, 0, 1, 1),
                 output(10, 0, 'z', 'ZERO', 1, 1), request(11, 1, 2, 2), resolve(11, 'z', 'ZERO', selected, 2, count=2),
                 ready(11, 2), cpu(11, 2, 2), finish(11, 1, 2, 3)], store_inputs=store_inputs)
            result = audit(doc)
            vm2 = next(r for r in result['facts']['replicas'] if r['location'] == loc(2))
            self.assertEqual((loc(selected), loc(1), 10, 1, None),
                (vm2['copiedFrom'], vm2['origin']['location'], vm2['origin']['jobAttemptId'],
                 vm2['origin']['observedAt'], vm2['copyOrdinal']))
            self.assertTrue(result['lifecycleQuiescent'])
            self.assertEqual((0, 0), (result['admissionCount'], result['waitingStoreInputCount']))
            omitted = copy.deepcopy(doc)
            del omitted['events'][4]
            self.bad(seal(omitted))

    def test_large_requested_reference_count_stays_compressed_and_overflow_fails(self):
        doc = document([task(0, inputs=[('x', INT_MAX)])], [file('x', 0)],
            [seed('x'), request(10, 0, 1, 0), resolve(10, 'x', 'ZERO', None, 0, count=INT_MAX), ready(10, 0)])
        job = audit(doc)['facts']['jobs'][0]
        self.assertEqual((INT_MAX, 1, 0), (job['referenceCount'], len(job['inputs']), job['requiredReferenceBytes']))
        overflow = document([task(0, inputs=[('x', 2)])], [file('x', 1e308)], [seed('x'), request(10, 0, 1, 0)])
        self.bad(overflow)
        for value in (Decimal('1e-999'), -1, float('inf')):
            self.bad(changed(doc, ('capture', 'observedThrough'), value))

    def test_malformed_nested_container_types_fail_as_invalid(self):
        base = roundtrip(store_inputs=True, topology=True)
        def containers(value, path=()):
            if type(value) is dict:
                yield path, 'not-an-object'
                for key, child in value.items():
                    yield from containers(child, (*path, key))
            elif type(value) is list:
                yield path, {'not': 'an-array'}
                for index, child in enumerate(value):
                    yield from containers(child, (*path, index))
        for path, value in containers(base):
            with self.subTest(path=path):
                self.bad(value if not path else changed(base, path, value))

    def test_clock_causality_cpu_and_fixed_membership(self):
        base = roundtrip(store_inputs=True)
        for index, key, value in ((0, 'taskIds', []), (0, 'taskIds', [0, 1]), (0, 'taskIds', [99]),
            (6, 'jobId', 10), (13, 'vmId', 1), (14, 'vmId', 1), (14, 'taskId', 0), (14, 'success', 1)):
            self.bad(changed(base, ('events', index, 'payload', key), value))
        for index, value in ((0, -1), (6, .5), (7, 3), (9, 5), (12, 7)):
            self.bad(changed(base, ('events', index, 'observedTime'), value))
        swapped = copy.deepcopy(base)
        swapped['events'][12:14] = reversed(swapped['events'][12:14])
        self.bad(seal(swapped))
        self.bad(changed(base, ('events', 4, 'sequence'), 99))

    def test_inspection_utf8_strict_json_and_detached_facts(self):
        parsed = decode_json(json.dumps(cohorts()))
        before = copy.deepcopy(parsed)
        with mock.patch('subprocess.Popen', side_effect=AssertionError('no process')) as popen, \
             mock.patch('os.system', side_effect=AssertionError('no shell')) as system:
            report = verify_document(parsed)
            popen.assert_not_called()
            system.assert_not_called()
        self.assertEqual(before, parsed)
        report['facts']['jobs'][1]['sourceWaits'][0]['fileId']['name'] = 'changed'
        report['facts']['copies'][0]['destination']['sourceId'] = 'changed'
        report['facts']['outputResolutions'][0]['fileId']['name'] = 'changed'
        self.assertEqual(before, parsed)
        json.dumps(report, allow_nan=False)
        with mock.patch.object(Path, 'read_text', return_value=json.dumps(roundtrip())) as read:
            self.assertEqual('VALID_COMPLETE', inspect_path('oracle.storage-lifecycle.json')['status'])
            read.assert_called_once_with(encoding='utf-8')
        for error in (OSError('missing'), UnicodeError('invalid UTF8')):
            with mock.patch.object(Path, 'read_text', side_effect=error), self.assertRaises(Invalid):
                inspect_path('missing.json')
        for text in ('{"x":1,"x":2}', '{"schema":"workflowsim-experiment-manifest-v4"}',
                     '{"x":NaN}', '{"x":Infinity}', '{"x":1e99999}'):
            with mock.patch.object(Path, 'read_text', return_value=text), self.assertRaises(Invalid):
                inspect_path('invalid.json')


if __name__ == '__main__':
    unittest.main(verbosity=2)
