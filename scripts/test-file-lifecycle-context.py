#!/usr/bin/env python3
"""Disposable hand-built V2 bundle tests; no Java, production replay or subprocess."""
import contextlib
import copy
from decimal import Decimal
import hashlib
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
from _network_audit import CheckError, decode_json, inspect_path, verify_document as verify_v1
from _file_lifecycle_audit import verify_document as verify_lifecycle
from _file_lifecycle_context import inspect_path as inspect_context, verify_context


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


hand = load("hand_lifecycle_fixtures", HERE / "test-file-lifecycle.py")
cli = load("network_cli_context_test", HERE / "verify-network-ledger.py")


def model_document(factory=hand.publication):
    """Adapt only units/Task numbering: parser Tasks are contiguous from one;
    platform bandwidth is integer decimal MB/s. All lifecycle times are literal.
    """
    doc = factory()
    ids = {row["taskId"]: index + 1 for index, row in enumerate(sorted(doc["filePlan"]["tasks"], key=lambda r: (r["workflowInputIndex"], r["taskId"])))}
    for row in doc["filePlan"]["tasks"]:
        row["taskId"] = ids[row["taskId"]]
        row["parents"] = [ids[p] for p in row["parents"]]
    for row in doc["filePlan"]["files"]:
        row["bytes"] *= 1000000
        if row["producerTaskId"] is not None:
            row["producerTaskId"] = ids[row["producerTaskId"]]
    for row in doc["fabric"]["resources"]:
        row["capacityBytesPerSecond"] *= 1000000
    if doc["fabric"]["topology"] is not None:
        doc["fabric"]["topology"]["linkBandwidthBytesPerSecond"] *= 1000000
    for event in doc["events"]:
        p = event["payload"]
        if "taskId" in p:
            p["taskId"] = ids[p["taskId"]]
        if "taskIds" in p:
            p["taskIds"] = [ids[t] for t in p["taskIds"]]
        if event["type"] == "COPY_ADMITTED":
            p["bytes"] *= 1000000
            p["standaloneRate"] *= 1000000
            origin = p["sourceReplica"]["origin"]
            if origin["producerTaskId"] is not None:
                origin["producerTaskId"] = ids[origin["producerTaskId"]]
        if event["type"] == "COPY_SETTLED":
            p["remainingAfterService"] *= 1000000
    return doc


def key(row):
    return row["workflowInputIndex"], row["name"]


class Bundle:
    """Manifest/main oracle assembled from hand fixtures, never verifier facts."""
    def __init__(self, root, factory=hand.publication, enabled=True, isolated=False):
        self.root, self.enabled = Path(root), enabled
        self.path = self.root / "run.manifest.json"
        self.ledger = model_document(factory)
        if isolated:
            self.ledger["modelKind"] = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2"
            self.ledger["policies"]["sharing"] = "ISOLATED_PATH_BOTTLENECK"
        plan = self.ledger["filePlan"]
        task_map = {t["taskId"]: t for t in plan["tasks"]}
        file_map = {key(f["fileId"]): f["bytes"] for f in plan["files"]}
        copied = {e["payload"]["copyOrdinal"]: e["payload"]["isolatedSeconds"] for e in self.ledger["events"] if e["type"] == "COPY_ADMITTED"}
        resolutions, starts, terminals = {}, {}, {}
        for event in self.ledger["events"]:
            p = event["payload"]
            if event["type"] == "INPUT_RESOLVED":
                resolutions.setdefault(p["jobId"], []).append(p)
            elif event["type"] == "JOB_CPU_STARTED":
                starts[p["jobId"]] = event["observedTime"]
            elif event["type"] == "TASK_FINISHED":
                terminals[p["jobId"]] = event
        self.events, jobs, attempts = [], [], []
        failed = {}
        for event in self.ledger["events"]:
            p, now = event["payload"], event["observedTime"]
            if event["type"] == "JOB_INPUT_REQUESTED":
                jid, tid, vm = p["jobId"], p["taskIds"][0], p["destinationVmId"]
                attrs = {}
                if tid in failed:
                    prior = failed.pop(tid)
                    self.events.append(self.main("RETRY_JOB_CREATED", now, jid, tid, vm, failedJobId=prior))
                    attrs["retryOfFailedJobId"] = prior
                self.events.append(self.main("JOB_READY", now, jid, tid, vm, **attrs))
                refs = resolutions.get(jid, [])
                count = sum(r["referenceCount"] for r in refs)
                size = sum(file_map[key(r["fileId"])] * r["referenceCount"] for r in refs)
                seconds = sum(copied[r["copyOrdinal"]] for r in refs if r["copyOrdinal"] is not None)
                self.events.append(self.main("DATA_STAGE_IN_MODELED", now, jid, tid, vm,
                                             modeledTransferFileCount=count, requiredFileBytes=size, modeledTransferSeconds=seconds,
                                             newFileCopies=sum(r["resolution"] == "NEW_COPY" for r in refs),
                                             joinedFileCopies=sum(r["resolution"] == "JOIN_EXISTING" for r in refs),
                                             transferUnit="LOGICAL_FILE_V2", dataMovementModel=self.ledger["modelKind"]))
                status = 4 if terminals[jid]["payload"]["success"] else 5
                start, finish = starts[jid], terminals[jid]["observedTime"]
                jobs.append(dict(jobId=jid, taskIds=[tid], taskCount=1, vmId=vm, classType=2,
                                 status=status, startTime=start, finishTime=finish))
                attempts.append(dict(jobId=jid, taskId=tid, vmId=vm, jobStatus=status, taskStatus=status,
                                     startTime=start, finishTime=finish, exactJobTiming=True))
            elif event["type"] == "JOB_CPU_STARTED":
                jid, vm = p["jobId"], p["vmId"]
                tid = terminals[jid]["payload"]["taskId"]
                self.events.extend([self.main("SCHEDULING_DECISION", now, jid, tid, vm,
                                              queueDelaySeconds=0, schedulingAlgorithm="STATIC"),
                                    self.main("JOB_DISPATCHED", now, jid, tid, vm, queueDelaySeconds=0),
                                    self.main("TASK_EXECUTION_MODELED", now, jid, tid, vm, taskId=tid,
                                              taskStartTime=now, taskFinishTime=terminals[jid]["observedTime"],
                                              modeledStageInSecondsBeforeTask=0, requestedDataStageInSecondsForJob=0)])
            elif event["type"] == "TASK_FINISHED":
                jid, tid, vm = p["jobId"], p["taskId"], p["vmId"]
                status = 4 if p["success"] else 5
                self.events.append(self.main("JOB_RETURNED", now, jid, tid, vm, jobStatus=status, taskStatuses=[status], postDelaySeconds=0))
                if status == 5:
                    failed[tid] = jid
                    self.events.append(self.main("JOB_FAILED", now, jid, tid, vm, failedStatus=True))
        self.metrics = dict(schema="workflowsim-simulation-metrics-v2", metrics={
            "makespanSeconds": self.ledger["capture"]["observedThrough"], "meanJobVmQueueWaitingTimeSeconds": 0,
            "meanJobVmLevelSlowdown": 1, "meanJobResponseTimeSeconds": 1,
            "meanComputeTotalWaitingTimeSeconds": 1, "meanComputeTrueSlowdown": 1})
        capacities = {r["key"]: r["capacityBytesPerSecond"] for r in self.ledger["fabric"]["resources"]}
        vm_ids = [l["vmId"] for l in self.ledger["fabric"]["locations"] if l["kind"] == "VM"]
        actual = {vm: 100 + vm for vm in vm_ids}
        topology = self.ledger["fabric"]["topology"]
        declared = None
        if topology is not None:
            actual = {row["vmId"]: row["hostId"] for row in self.ledger["fabric"]["vmHostAssignments"]}
            hosts = [p["hostId"] for p in topology["hostPlacements"]]
            half = topology["k"] // 2
            explicit = {str(p["hostId"]): p["pod"] * half + p["edge"] for p in topology["hostPlacements"]}
            declared = dict(kind="FAT_TREE", k=topology["k"], coreSwitchCount=topology["coreSwitchCount"],
                            linkBandwidthMbPerSecond=topology["linkBandwidthBytesPerSecond"] / 1000000,
                            hostEdgePlacements=explicit, defaultPlacementPolicy="HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES",
                            routingPolicy="DETERMINISTIC_AL_FARES_FAT_TREE_V1", linkDirectionality="INDEPENDENT_DIRECTED_LINKS",
                            externalSourceRouting="BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY")
        else:
            hosts = list(actual.values())
        vms = [dict(id=vm, bandwidth=int(capacities["VM:" + str(vm)] / 1000000), preflightHostId=actual[vm]) for vm in vm_ids]
        graph, depths = [], {}
        remaining = dict(task_map)
        while remaining:
            for tid, row in list(remaining.items()):
                if all(p in depths for p in row["parents"]):
                    depths[tid] = 1 + max((depths[p] for p in row["parents"]), default=0)
                    del remaining[tid]
        for tid, row in sorted(task_map.items()):
            graph.append(dict(taskId=tid, type="hand", depth=depths[tid], parentIds=row["parents"],
                              childIds=[t for t, child in task_map.items() if tid in child["parents"]]))
        scopes = sorted({row["workflowInputIndex"] for row in plan["tasks"]})
        inputs, outcomes, paths = [], [], []
        for scope in scopes:
            tasks = sorted(t for t, row in task_map.items() if row["workflowInputIndex"] == scope)
            path = "/hand/workflow" + str(scope) + ".dax"
            paths.append(path)
            inputs.append(dict(path=path, taskCount=len(tasks)))
            outcomes.append(dict(index=scope, path=path, arrivalSecond=0, firstTaskId=tasks[0], lastTaskId=tasks[-1], taskCount=len(tasks)))
        self.manifest = dict(schema="workflowsim-experiment-manifest-v4",
                             configuration=dict(dataMovementModel=dict(kind=self.ledger["modelKind"]), vmCount=len(vms),
                                                planningAlgorithm="RANDOM", schedulingAlgorithm="STATIC", fileSystem="LOCAL",
                                                clustering=dict(method="NONE"), workflowPaths=paths, workflowArrivalSeconds=[0] * len(scopes),
                                                workflowArrivalSemantics="PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO",
                                                failureModel=dict(clusteringAlgorithm="FTCLUSTERING_NOOP", monitorMode="MONITOR_NONE"),
                                                overheadModel=dict(workflowEngineDelayInterval=0, bandwidth=0, workflowEngineDelays={},
                                                                   queueDelays={}, postDelays={}, clusteringDelays={})),
                             dataflowPlan=copy.deepcopy(plan), workflowGraph=graph,
                             workflowProfile=dict(taskCount=len(task_map), edgeCount=sum(len(t["parents"]) for t in task_map.values())),
                             inputs=inputs, provenance=dict(execution=dict(workingDirectory="/hand")),
                             platform=dict(hosts=[dict(id=h) for h in hosts], vms=vms, hostCount=len(hosts), vmCount=len(vms), networkTopology=declared),
                             result=dict(simulationEndSeconds=self.ledger["capture"]["observedThrough"], workflowCompletedSuccessfully=True,
                                         jobs=jobs, tasks=attempts, actualVmHostAssignments={str(vm): host for vm, host in actual.items()},
                                         workflowOutcomes=outcomes), metrics=copy.deepcopy(self.metrics["metrics"]), events={}, artifacts=[])
        if enabled:
            self.manifest["configuration"]["networkEvidence"] = copy.deepcopy(self.ledger["recording"])
        for role, suffix in (("metrics", "metrics.json"), ("events", "events.jsonl")):
            self.manifest["artifacts"].append(dict(role=role, path="run." + suffix, sha256="", sizeBytes=0))
        if enabled:
            self.manifest["artifacts"].append(dict(role="file-lifecycle", path="run.file-lifecycle.json", sha256="", sizeBytes=0))
        self.resequence()
        self.write()

    @staticmethod
    def main(kind, now, jid, tid, vm, **attrs):
        return dict(sequence=0, type=kind, simulationTime=now, jobId=jid, vmId=vm, classType=2, taskIds=[tid], attributes=attrs)

    def resequence(self):
        for index, event in enumerate(self.events):
            event["sequence"] = index
        self.manifest["events"] = dict(schema="workflowsim-simulation-events-v1", eventCount=len(self.events),
                                       firstSequence=0, lastSequence=len(self.events) - 1)

    def main_event(self, kind, job=101):
        return next(row for row in self.events if row["type"] == kind and row["jobId"] == job)

    def save_manifest(self):
        self.path.write_text(json.dumps(self.manifest, allow_nan=False), encoding="utf-8")

    def rehash(self):
        for row in self.manifest["artifacts"]:
            raw = (self.root / row["path"]).read_bytes()
            row.update(sha256=hashlib.sha256(raw).hexdigest(), sizeBytes=len(raw))
        self.save_manifest()

    def write(self):
        if self.enabled:
            (self.root / "run.file-lifecycle.json").write_text(json.dumps(self.ledger, allow_nan=False), encoding="utf-8")
        (self.root / "run.metrics.json").write_text(json.dumps(self.metrics, allow_nan=False), encoding="utf-8")
        (self.root / "run.events.jsonl").write_text("".join(json.dumps(row, allow_nan=False) + "\n" for row in self.events), encoding="utf-8")
        self.rehash()


class ContextTests(unittest.TestCase):
    def bundle(self, factory=hand.publication, enabled=True, isolated=False):
        temp = tempfile.TemporaryDirectory(prefix="file-lifecycle-context-")
        self.addCleanup(temp.cleanup)
        return Bundle(temp.name, factory, enabled, isolated)

    def bad(self, bundle, write=True):
        if write:
            bundle.write()
        with self.assertRaises(CheckError):
            inspect_path(bundle.path)

    def test_all_models_topologies_and_standalone_dispatch(self):
        for isolated in (False, True):
            for factory in (hand.publication, hand.fat_document):
                with self.subTest(isolated=isolated, factory=factory.__name__):
                    bundle = self.bundle(factory, isolated=isolated)
                    report = inspect_path(bundle.path)
                    self.assertEqual("VALID_COMPLETE", report["status"])
                    self.assertTrue(report["contextualRunChecked"])
                    self.assertTrue(report["artifactReferencesChecked"])
                    self.assertFalse(report["fluidServiceAccountingCertified"])
                    standalone = inspect_path(bundle.root / "run.file-lifecycle.json")
                    self.assertFalse(standalone["contextualRunChecked"])
                    self.assertEqual(1, standalone["admissionCount"])
                    self.assertEqual((3, 300000000, 10), tuple(report["facts"]["jobs"][1][key] for key in
                                                              ("referenceCount", "requiredReferenceBytes", "isolatedInputSeconds")))

    def test_fanin_cache_zero_failure_and_retry_bundles(self):
        for factory in (hand.fanin, hand.zeros, hand.failure_retry):
            with self.subTest(factory=factory.__name__):
                bundle = self.bundle(factory)
                self.assertTrue(inspect_path(bundle.path)["contextualRunChecked"])

    def test_distinct_workflow_scopes_same_name_and_staggered_arrivals(self):
        def workflows():
            first = hand.replica("same", None, 0, "EXTERNAL_SEED")
            second = hand.replica("same", None, 0, "EXTERNAL_SEED", scope=1)
            return hand.document([hand.task(0, inputs=[("same", 1)]), hand.task(1, inputs=[("same", 1)], scope=1)],
                                 [hand.file("same", 10), hand.file("same", 10, scope=1)],
                                 [hand.seed("same"), hand.seed("same", 1), hand.request(0, 0, 1, 0),
                                  hand.admit(1, "same", first, 1, 10, ["VM:1"], 10, 1, 0),
                                  hand.resolve(0, "same", "NEW_COPY", None, 0, 1), hand.settle(1, 1, 2),
                                  hand.ready(0, 2), hand.cpu(0, 1, 2), hand.finish(0, 0, 1, 3),
                                  hand.request(1, 1, 2, 5), hand.admit(2, "same", second, 2, 10, ["VM:2"], 20, .5, 5, scope=1),
                                  hand.resolve(1, "same", "NEW_COPY", None, 5, 2, scope=1), hand.settle(2, 5.5, 6),
                                  hand.ready(1, 6), hand.cpu(1, 2, 6), hand.finish(1, 1, 2, 7)])
        bundle = self.bundle(workflows)
        bundle.manifest["configuration"]["workflowArrivalSeconds"][1] = 5
        bundle.manifest["result"]["workflowOutcomes"][1]["arrivalSecond"] = 5
        bundle.write()
        report = inspect_path(bundle.path)
        self.assertEqual((2, 2), (report["fileCount"], report["admissionCount"]))
        self.assertTrue(report["contextualRunChecked"])
        bundle.manifest["configuration"]["workflowArrivalSeconds"][1] = 6
        bundle.manifest["result"]["workflowOutcomes"][1]["arrivalSecond"] = 6
        self.bad(bundle)  # Both documents agree on scope, but the request is too early.

    def test_off_validates_core_and_never_claims_zero_traffic(self):
        bundle = self.bundle(enabled=False)
        report = inspect_path(bundle.path)
        self.assertEqual("DISABLED", report["status"])
        self.assertTrue(report["corePlanContextChecked"])
        self.assertFalse(report["completeCaptureCertified"])
        self.assertFalse(report["contextualRunChecked"])
        self.assertNotIn("admissionCount", report)
        self.assertIn("NOT_ZERO_TRAFFIC", report["scope"])
        bundle.manifest["dataflowPlan"]["tasks"][0]["workflowInputIndex"] = 1
        self.bad(bundle)

    def test_plan_config_graph_scope_and_arrival_mutations(self):
        mutations = [("planningAlgorithm", "HEFT"), ("schedulingAlgorithm", "MINMIN"), ("fileSystem", "SHARED")]
        for field, value in mutations:
            bundle = self.bundle()
            bundle.manifest["configuration"][field] = value
            self.bad(bundle)
        for mutate in (lambda b: b.manifest["configuration"]["clustering"].update(method="HORIZONTAL"),
                       lambda b: b.manifest["configuration"]["failureModel"].update(monitorMode="MONITOR_ALL"),
                       lambda b: b.manifest["configuration"]["failureModel"].update(clusteringAlgorithm="FTCLUSTERING_SR"),
                       lambda b: b.manifest["configuration"]["overheadModel"]["queueDelays"].update({"1": {}}),
                       lambda b: b.manifest["configuration"]["overheadModel"].update(bandwidth=1),
                       lambda b: b.manifest["workflowGraph"][1].update(parentIds=[]),
                       lambda b: b.manifest["workflowGraph"][0].update(childIds=[]),
                       lambda b: b.manifest["workflowProfile"].update(taskCount=3),
                       lambda b: b.manifest["result"]["workflowOutcomes"][0].update(firstTaskId=0),
                       lambda b: b.manifest["result"]["workflowOutcomes"][0].update(taskCount=2147483647),
                       lambda b: b.manifest["configuration"]["workflowArrivalSeconds"].__setitem__(0, 1),
                       lambda b: b.manifest["inputs"][0].update(path="/different.dax")):
            bundle = self.bundle()
            mutate(bundle)
            self.bad(bundle)
        bundle = self.bundle()
        bundle.manifest["configuration"]["workflowArrivalSeconds"][0] = 1
        bundle.manifest["result"]["workflowOutcomes"][0]["arrivalSecond"] = 1
        self.bad(bundle)  # Internally consistent scope declaration, but request0 precedes arrival1.

    def test_core_plan_compares_exact_json_numbers_not_just_binary64(self):
        bundle = self.bundle()
        bundle.manifest["dataflowPlan"]["files"][0]["bytes"] = "TOKEN"
        bundle.save_manifest()
        raw = bundle.path.read_text(encoding="utf-8").replace('"TOKEN"', "100000000.00000000000000001")
        bundle.path.write_text(raw, encoding="utf-8")
        self.bad(bundle, write=False)
        bundle = self.bundle()
        bundle.manifest["dataflowPlan"]["tasks"].reverse()
        self.bad(bundle)  # Core/sidecar arrays must match, even though standalone normalizes keyed declarations.

    def test_roles_modes_budget_and_schema_downgrades_cannot_be_disabled(self):
        for change in (lambda b: b.manifest["artifacts"][-1].update(role="network-ledger"),
                       lambda b: b.manifest["configuration"].pop("networkEvidence"),
                       lambda b: b.manifest["configuration"]["networkEvidence"].update(mode="OFF"),
                       lambda b: b.manifest["configuration"]["networkEvidence"].update(mode="FLUID_GROUP_LEDGER_V1"),
                       lambda b: b.manifest["configuration"]["networkEvidence"].update(mode="UNKNOWN_V3"),
                       lambda b: b.manifest["configuration"]["networkEvidence"].update(maxTraceRecords=999),
                       lambda b: b.manifest["configuration"]["dataMovementModel"].update(kind="LEGACY_WORKFLOWSIM_V1"),
                       lambda b: b.manifest.update(schema="workflowsim-experiment-manifest-v3"),
                       lambda b: b.manifest["artifacts"].append(copy.deepcopy(b.manifest["artifacts"][-1]))):
            bundle = self.bundle()
            change(bundle)
            self.bad(bundle)
        for enabled in (False, True):
            bundle = self.bundle(enabled=enabled)
            bundle.manifest["schema"] = "workflowsim-experiment-manifest-v3"
            self.bad(bundle)
        bundle = self.bundle(enabled=False)
        bundle.manifest["configuration"]["dataMovementModel"]["kind"] = "LEGACY_WORKFLOWSIM_V1"
        self.bad(bundle)  # dataflowPlan itself triggers rejection, not legacy OFF.

    def test_each_v2_dispatch_signal_prevents_silent_legacy_off(self):
        # Each signal must dispatch independently, even when the others are hidden.
        bundle = self.bundle()
        bundle.manifest.pop("dataflowPlan")
        bundle.manifest["configuration"]["dataMovementModel"]["kind"] = "LEGACY_WORKFLOWSIM_V1"
        bundle.manifest["configuration"].pop("networkEvidence")
        self.bad(bundle)  # file-lifecycle role alone
        bundle = self.bundle()
        bundle.manifest.pop("dataflowPlan")
        bundle.manifest["configuration"]["dataMovementModel"]["kind"] = "LEGACY_WORKFLOWSIM_V1"
        bundle.manifest["artifacts"].pop()
        self.bad(bundle)  # FILE_LIFECYCLE_V2 mode alone
        bundle = self.bundle(enabled=False)
        bundle.manifest.pop("dataflowPlan")
        self.bad(bundle)  # coherent kind alone

    def test_rehashed_source_path_truncation_and_active_prefix_fail(self):
        bundle = self.bundle()
        bundle.ledger["events"][5]["payload"]["sourceReplica"]["origin"]["jobAttemptId"] = 101
        self.bad(bundle)
        bundle = self.bundle(hand.fat_document)
        bundle.ledger["events"][5]["payload"]["resources"].reverse()
        self.bad(bundle)
        bundle = self.bundle()
        bundle.ledger["capture"]["status"] = "TRUNCATED"
        self.bad(bundle)
        bundle = self.bundle()
        bundle.ledger = hand.prefix(bundle.ledger, 7)
        self.assertEqual(1, verify_lifecycle(bundle.ledger)["activeCopyCount"])
        self.bad(bundle)

    def test_platform_capacity_and_actual_placement_mutations(self):
        for field, value in (("bandwidth", 11), ("preflightHostId", 999), ("id", 999)):
            bundle = self.bundle()
            bundle.manifest["platform"]["vms"][0][field] = value
            self.bad(bundle)
        bundle = self.bundle()
        bundle.manifest["result"]["actualVmHostAssignments"]["1"] = 102
        self.bad(bundle)
        bundle = self.bundle()
        bundle.manifest["result"]["actualVmHostAssignments"]["01"] = bundle.manifest["result"]["actualVmHostAssignments"].pop("1")
        self.bad(bundle)
        bundle = self.bundle()
        bundle.manifest["platform"]["hostCount"] = 99
        self.bad(bundle)
        bundle = self.bundle(enabled=False)
        bundle.manifest["platform"]["vms"][0]["bandwidth"] = 0
        self.bad(bundle)

    def test_topology_join_defaults_explicit_placements_and_safety(self):
        bundle = self.bundle(hand.fat_document)
        bundle.manifest["platform"]["networkTopology"]["hostEdgePlacements"] = None
        bundle.manifest["platform"]["networkTopology"]["coreSwitchCount"] = None
        bundle.write()
        self.assertTrue(inspect_path(bundle.path)["contextualRunChecked"])
        for mutate in (lambda t: t.update(k=34), lambda t: t.update(k=3),
                       lambda t: t.update(linkBandwidthMbPerSecond=11),
                       lambda t: t.update(linkBandwidthMbPerSecond=1e308),
                       lambda t: t.update(linkBandwidthMbPerSecond=0),
                       lambda t: t.update(coreSwitchCount=2),
                       lambda t: t["hostEdgePlacements"].update({"10": 1, "20": 0}),
                       lambda t: t["hostEdgePlacements"].update({"999": 0}),
                       lambda t: t.update(routingPolicy="ADAPTIVE")):
            bundle = self.bundle(hand.fat_document)
            mutate(bundle.manifest["platform"]["networkTopology"])
            self.bad(bundle)
        bundle = self.bundle(hand.fat_document)
        bundle.manifest["platform"]["networkTopology"] = None
        self.bad(bundle)

    def test_final_attempt_coverage_windows_and_status(self):
        for mutate in (lambda b: b.manifest["result"]["jobs"].pop(),
                       lambda b: b.manifest["result"]["tasks"].pop(),
                       lambda b: b.manifest["result"]["jobs"].append(copy.deepcopy(b.manifest["result"]["jobs"][0])),
                       lambda b: b.manifest["result"]["tasks"][0].update(taskStatus=5),
                       lambda b: b.manifest["result"]["tasks"][1].update(finishTime=14),
                       lambda b: b.manifest["result"]["tasks"][1].update(finishTime=17),
                       lambda b: b.manifest["result"]["jobs"][1].update(startTime=14),
                       lambda b: b.manifest["result"]["jobs"][1].update(taskCount=2),
                       lambda b: b.manifest["result"]["jobs"][1].update(vmId=1),
                       lambda b: b.manifest["result"].update(workflowCompletedSuccessfully=False),
                       lambda b: b.manifest["result"].update(simulationEndSeconds=15),
                       lambda b: b.manifest["result"]["tasks"][0].update(exactJobTiming=False)):
            bundle = self.bundle()
            mutate(bundle)
            self.bad(bundle)
        # Modeled Task finish may precede a coarse actual terminal observation.
        bundle = self.bundle()
        bundle.manifest["result"]["tasks"][1].update(finishTime=15.5, exactJobTiming=False)
        bundle.main_event("TASK_EXECUTION_MODELED")["attributes"]["taskFinishTime"] = 15.5
        bundle.write()
        self.assertTrue(inspect_path(bundle.path)["contextualRunChecked"])

    def test_rehashed_main_reference_quantities_are_independent(self):
        for field, value in (("requiredFileBytes", 100000000), ("modeledTransferFileCount", 1),
                             ("modeledTransferSeconds", 11), ("newFileCopies", 0), ("joinedFileCopies", 1),
                             ("contentionTransferGroupCount", 1), ("transferUnit", "V1_PARENT_GROUP"),
                             ("dataMovementModel", "LEGACY_WORKFLOWSIM_V1")):
            bundle = self.bundle()
            bundle.main_event("DATA_STAGE_IN_MODELED")["attributes"][field] = value
            self.bad(bundle)
        bundle = self.bundle(hand.fanin)
        bundle.main_event("DATA_STAGE_IN_MODELED", 12)["attributes"]["newFileCopies"] = 1
        self.bad(bundle)

    def test_cpu_transfer_envelope_queue_post_and_event_identity(self):
        for kind, field, value in (("TASK_EXECUTION_MODELED", "modeledStageInSecondsBeforeTask", 1),
                                   ("TASK_EXECUTION_MODELED", "requestedDataStageInSecondsForJob", 1),
                                   ("TASK_EXECUTION_MODELED", "taskFinishTime", 15.5),
                                   ("TASK_EXECUTION_MODELED", "taskStartTime", 14),
                                   ("TASK_EXECUTION_MODELED", "taskId", 1),
                                   ("SCHEDULING_DECISION", "queueDelaySeconds", 1),
                                   ("JOB_DISPATCHED", "queueDelaySeconds", 1),
                                   ("JOB_RETURNED", "postDelaySeconds", 1),
                                   ("JOB_RETURNED", "jobStatus", 5),
                                   ("JOB_RETURNED", "taskStatuses", [5])):
            bundle = self.bundle()
            bundle.main_event(kind)["attributes"][field] = value
            self.bad(bundle)
        for field, value in (("vmId", 1), ("taskIds", [1]), ("classType", 1), ("jobId", 999)):
            bundle = self.bundle()
            bundle.main_event("TASK_EXECUTION_MODELED")[field] = value
            self.bad(bundle)

    def test_main_time_sequence_anchor_order_and_required_events(self):
        bundle = self.bundle()
        bundle.main_event("SCHEDULING_DECISION")["simulationTime"] = 14
        bundle.main_event("JOB_DISPATCHED")["simulationTime"] = 14
        self.bad(bundle)  # Still monotonic, but before lifecycle data-ready15.
        for kind in ("JOB_READY", "DATA_STAGE_IN_MODELED", "SCHEDULING_DECISION", "JOB_DISPATCHED", "TASK_EXECUTION_MODELED", "JOB_RETURNED"):
            bundle = self.bundle()
            bundle.events.remove(bundle.main_event(kind))
            bundle.resequence()
            self.bad(bundle)
        bundle = self.bundle()
        a, b = bundle.events.index(bundle.main_event("SCHEDULING_DECISION")), bundle.events.index(bundle.main_event("JOB_DISPATCHED"))
        bundle.events[a], bundle.events[b] = bundle.events[b], bundle.events[a]
        bundle.resequence()
        self.bad(bundle)
        bundle = self.bundle()
        bundle.events[0]["sequence"] = 1
        self.bad(bundle)
        bundle = self.bundle()
        bundle.events[-1]["simulationTime"] = 17
        self.bad(bundle)
        bundle = self.bundle()
        bundle.main_event("JOB_READY")["simulationTime"] = 1
        self.bad(bundle)

    def test_retry_wrong_predecessor_creation_order_and_recovery(self):
        for change in (lambda b: b.main_event("RETRY_JOB_CREATED", 11)["attributes"].update(failedJobId=20),
                       lambda b: b.main_event("JOB_READY", 11)["attributes"].update(retryOfFailedJobId=20),
                       lambda b: b.events.remove(b.main_event("RETRY_JOB_CREATED", 11)),
                       lambda b: b.events.remove(b.main_event("JOB_FAILED", 10))):
            bundle = self.bundle(hand.failure_retry)
            change(bundle)
            bundle.resequence()
            self.bad(bundle)
        bundle = self.bundle(hand.failure_retry)
        retry = bundle.main_event("RETRY_JOB_CREATED", 11)
        bundle.events.remove(retry)
        retry["simulationTime"] = 12
        before = bundle.events.index(bundle.main_event("JOB_RETURNED", 10))
        bundle.events.insert(before, retry)
        bundle.resequence()
        self.bad(bundle)  # Monotonic/rehashed, but retry precedes the failed terminal.
        bundle = self.bundle(hand.failure_retry)
        retry, ready = bundle.main_event("RETRY_JOB_CREATED", 11), bundle.main_event("JOB_READY", 11)
        a, b = bundle.events.index(retry), bundle.events.index(ready)
        bundle.events[a], bundle.events[b] = ready, retry
        bundle.resequence()
        self.bad(bundle)
        bundle = self.bundle()
        bundle.main_event("JOB_READY", 100)["attributes"]["retryOfFailedJobId"] = 100
        self.bad(bundle)

    def test_hash_size_path_roles_and_metrics_integrity(self):
        bundle = self.bundle()
        bundle.manifest["artifacts"][-1]["sha256"] = "0" * 64
        bundle.save_manifest()
        self.bad(bundle, False)
        bundle = self.bundle()
        bundle.manifest["artifacts"][-1]["sizeBytes"] += 1
        bundle.save_manifest()
        self.bad(bundle, False)
        for path in ("../outside.json", "/outside.json", "a/b.json", "a\\b.json", "", "."):
            bundle = self.bundle()
            bundle.manifest["artifacts"][-1]["path"] = path
            bundle.save_manifest()
            self.bad(bundle, False)
        bundle = self.bundle()
        bundle.metrics["metrics"]["makespanSeconds"] = 123
        self.bad(bundle)
        bundle = self.bundle()
        bundle.metrics["schema"] = "workflowsim-simulation-metrics-v1"
        self.bad(bundle)
        bundle = self.bundle()
        del bundle.metrics["metrics"]["meanComputeTrueSlowdown"]
        bundle.manifest["metrics"] = copy.deepcopy(bundle.metrics["metrics"])
        self.bad(bundle)

    def test_rehashed_utf8_duplicate_json_keys_and_blank_main_lines(self):
        for name in ("run.file-lifecycle.json", "run.metrics.json", "run.events.jsonl"):
            bundle = self.bundle()
            (bundle.root / name).write_bytes(b"\xff")
            bundle.rehash()
            self.bad(bundle, False)
        bundle = self.bundle()
        raw = bundle.path.read_text(encoding="utf-8")
        bundle.path.write_text('{"schema":"workflowsim-experiment-manifest-v4",' + raw[1:], encoding="utf-8")
        self.bad(bundle, False)
        for name, field, value in (("run.file-lifecycle.json", "schema", '"workflowsim-file-lifecycle-v2"'),
                                   ("run.metrics.json", "schema", '"workflowsim-simulation-metrics-v2"'),
                                   ("run.events.jsonl", "sequence", "0")):
            bundle = self.bundle()
            path = bundle.root / name
            raw = path.read_text(encoding="utf-8")
            path.write_text('{"' + field + '":' + value + ',' + raw[1:], encoding="utf-8")
            bundle.rehash()
            self.bad(bundle, False)
        bundle = self.bundle()
        path = bundle.root / "run.events.jsonl"
        path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
        bundle.rehash()
        self.bad(bundle, False)

    def test_large_fractional_ids_counters_and_booleans_do_not_round(self):
        bundle = self.bundle()
        bundle.manifest["configuration"]["networkEvidence"]["maxTraceRecords"] = "TOKEN"
        bundle.save_manifest()
        raw = bundle.path.read_text(encoding="utf-8").replace('"TOKEN"', "1000.000000000000000000001")
        bundle.path.write_text(raw, encoding="utf-8")
        self.bad(bundle, False)
        for field, value in (("modeledTransferFileCount", "3.000000000000000000001"), ("newFileCopies", "1.000000000000000000001")):
            bundle = self.bundle()
            bundle.main_event("DATA_STAGE_IN_MODELED")["attributes"][field] = "TOKEN"
            bundle.write()
            path = bundle.root / "run.events.jsonl"
            path.write_text(path.read_text(encoding="utf-8").replace('"TOKEN"', value), encoding="utf-8")
            bundle.rehash()
            self.bad(bundle, False)
        bundle = self.bundle()
        bundle.manifest["result"]["workflowCompletedSuccessfully"] = 1
        self.bad(bundle)

    def test_verified_snapshots_are_immutable_and_cli_v2_is_honest(self):
        bundle = self.bundle()
        root = decode_json(json.dumps(bundle.manifest))
        doc = decode_json(json.dumps(bundle.ledger))
        events = decode_json(json.dumps(bundle.events))
        before = copy.deepcopy((root, doc, events))
        with mock.patch("subprocess.Popen", side_effect=AssertionError("no subprocess")):
            result = verify_context(root, doc, events)
        self.assertEqual(before, (root, doc, events))
        result["facts"]["jobs"][0]["taskIds"].append(999)
        self.assertEqual(before, (root, doc, events))
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(bundle.path)]))
        self.assertIn("FILE_LIFECYCLE_CHECK VALID_COMPLETE", output.getvalue())
        self.assertIn("fluidServiceAccountingCertified=false", output.getvalue())
        self.assertIn("copies=1", output.getvalue())
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(bundle.path), "--json"]))
        self.assertTrue(json.loads(output.getvalue())["contextualRunChecked"])
        with self.assertRaises(CheckError):
            verify_v1(bundle.ledger)  # V1 document contract did NOT become permissive.
        off = self.bundle(enabled=False)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(off.path)]))
        self.assertIn("FILE_LIFECYCLE_CHECK DISABLED", output.getvalue())
        self.assertNotIn("copies=", output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
