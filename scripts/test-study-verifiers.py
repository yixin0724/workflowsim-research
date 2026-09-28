#!/usr/bin/env python3
"""Small self-contained stdlib tests. No JVM, Maven, retained study, or network is used."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from fractions import Fraction

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from _study_audit import Audit, EXECUTION_SEMANTICS, exact_equal, java_round, layered_xml, registered_plan


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


evidence = load("independent_evidence", "verify-study-evidence.py")
statistics = load("independent_statistics", "verify-study-statistics.py")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")


def fingerprint(path):
    raw = path.read_bytes()
    return hashlib.sha256(raw).hexdigest(), len(raw)


def bind_sidecars(manifest_path, doc):
    metrics = manifest_path.parent / "run.metrics.json"
    events = manifest_path.parent / "run.events.jsonl"
    write_json(metrics, {"schema": "workflowsim-simulation-metrics-v2", "metrics": doc["metrics"]})
    doc["artifacts"] = [{"role": role, "path": path.name, "sha256": fingerprint(path)[0], "sizeBytes": fingerprint(path)[1]}
                        for role, path in (("metrics", metrics), ("events", events))]
    write_json(manifest_path, doc)


def make_bundle(root, duration=1.0, effective=1000, matrix=False):
    """Hand-calculated single task: 110 MI stage-in, then one 1000-MI declared task.

    Optional matrix duration/effective MI are explicitly supplied by each test. The input is 0.5 B.
    Expected metrics below use these elementary quantities, never a checker calculation.
    """
    root.mkdir(parents=True)
    source = root / "flow.dax"
    source.write_text('<adag version="2.1"><job id="root" name="root" runtime="1"><uses file="x" link="input" size="0.5"/></job></adag>', encoding="utf-8")
    start, end = 0.21, 0.21 + duration
    busy = 0.11 + duration
    transfer = 0.5 / 1_000_000.0
    bw = 0.1 * 0.5 / 1_000_000.0
    def job(jid, cls, a, b, cpu, tids):
        return {"jobId": jid, "vmId": 0, "status": 4, "classType": cls, "submissionTime": a,
                "startTime": a, "finishTime": b, "waitingTime": 0.0, "executionTime": cpu,
                "responseTime": cpu, "cpuTime": cpu, "modeledCpuEnvelopeCost": 3.0 * cpu,
                "modeledDeclaredFileBandwidthCost": bw, "modeledDeclaredFileBytes": 0.5,
                "modeledProcessingCost": 3.0 * cpu + bw, "taskCount": len(tids), "taskIds": tids}
    jobs = [job(1, 1, 0.1, 0.21, 0.11, []), job(0, 2, start, end, duration, [1])]
    task = {"taskId": 1, "jobId": 0, "vmId": 0, "jobStatus": 4, "taskStatus": 4, "lengthMi": 1000,
            "effectiveExecutionLengthMi": effective, "startTime": start, "finishTime": end, "exactJobTiming": True, "depth": 1}
    events = []
    def event(kind, time, jid=None, tids=(), **attrs):
        events.append({"sequence": len(events), "simulationTime": time, "type": kind, "jobId": jid,
                       "taskIds": list(tids), "attributes": attrs})
    event("STAGE_IN_JOB_CREATED", 0.0, 1, inputFileCount=1, inputBytes=0.5)
    event("JOB_READY", 0.1, 1)
    event("SCHEDULING_CYCLE", 0.1)
    event("SCHEDULING_DECISION", 0.1, 1)
    event("JOB_RETURNED", 0.21, 1)
    event("JOB_READY", start, 0, [1])
    event("SCHEDULING_CYCLE", start)
    event("SCHEDULING_DECISION", start, 0, [1])
    event("DATA_STAGE_IN_MODELED", start, 0, [1], requiredFileBytes=0.5, modeledTransferSeconds=transfer, modeledTransferFileCount=1)
    event("TASK_EXECUTION_MODELED", end, 0, [1], taskStartTime=start, taskFinishTime=end, effectiveExecutionLengthMi=effective)
    event("JOB_RETURNED", end, 0, [1])
    (root / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    metrics = {
        "makespanSeconds": end, "logicalTaskCompletionSeconds": end, "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY",
        "terminalLifecycleTailSeconds": 0.0, "jobOutcomeCount": 2, "computeJobOutcomeCount": 1, "stageInJobOutcomeCount": 1,
        "successfulJobOutcomeCount": 2, "failedJobOutcomeCount": 0, "successfulComputeJobOutcomeCount": 1,
        "failedComputeJobOutcomeCount": 0, "successfulComputeJobOutcomeRate": 1.0,
        "logicalTaskCount": 1, "successfullyCompletedLogicalTaskCount": 1, "logicalTasksNotYetSuccessfullyCompletedCount": 0,
        "successfulLogicalTaskCompletionRate": 1.0, "allLogicalTasksCompletedSuccessfully": True,
        "initialComputeJobOutcomeCount": 1, "retryJobCreatedCount": 0, "completedRetryComputeJobOutcomeCount": 0,
        "logicalTaskAttemptCount": 1, "retriedLogicalTaskCount": 0, "failedComputeAttemptEnvelopeSeconds": 0.0,
        "failedComputeAttemptModeledProcessingCost": 0.0, "retryComputeAttemptModeledProcessingCost": 0.0,
        "computeJobOutcomeThroughputPerSecond": 1.0 / end, "meanComputeJobRunTimeSeconds": duration,
        "meanJobVmQueueWaitingTimeSeconds": 0.0, "meanJobResponseTimeSeconds": duration, "meanJobVmLevelSlowdown": 1.0,
        "vmLevelSlowdownObservationCount": 1, "meanComputeTotalWaitingTimeSeconds": 0.0, "meanComputeTrueSlowdown": 1.0,
        "trueSlowdownObservationCount": 1, "totalWaitingTimeObservationCount": 1,
        "medianComputeTotalWaitingTimeSeconds": 0.0, "p95ComputeTotalWaitingTimeSeconds": 0.0, "maxComputeTotalWaitingTimeSeconds": 0.0,
        "medianComputeTrueSlowdown": 1.0, "p95ComputeTrueSlowdown": 1.0, "maxComputeTrueSlowdown": 1.0,
        "successOnlyMeanComputeTotalWaitingTimeSeconds": 0.0, "successOnlyMeanComputeTrueSlowdown": 1.0,
        "successOnlyWaitingObservationCount": 1, "retryAmplificationRatio": 1.0,
        "meanComputeReadyToDecisionDelaySeconds": 0.0, "meanComputeDecisionToStartDelaySeconds": 0.0,
        "readyToDecisionObservationCount": 1, "decisionToStartObservationCount": 1, "schedulingCycleCount": 2,
        "explicitPlannerDecisionObservationCount": 0, "dataStageInModelObservationCount": 1, "modeledDataTransferFileCount": 1,
        "totalModeledDataTransferSeconds": transfer, "totalModeledRequiredInputBytes": 0.5, "meanModeledDataTransferSeconds": transfer,
        "totalModeledProcessingCost": 3.0 * busy + 2 * bw, "totalModeledCpuEnvelopeCost": 3.0 * busy,
        "totalModeledDeclaredFileBandwidthCost": 2 * bw, "totalModeledDeclaredFileBytes": 1.0,
        "vmMetrics": {"0": {"vmId": 0, "jobOutcomeCount": 2, "reportedCpuTimeSeconds": busy,
                             "modeledBusyIntervalSeconds": busy, "modeledIntervalUtilization": busy / end,
                             "reportedCpuTimeOverMakespan": busy / end}},
        "totalVmModeledBusyIntervalSeconds": busy, "meanVmModeledIntervalUtilization": busy / end,
        "vmModeledBusyTimeCoefficientOfVariation": 0.0, "vmUtilizationJainFairnessIndex": 1.0,
        "exactTaskTimingObservationCount": 1, "modeledApproximateTaskTimingObservationCount": 0,
    }
    config = {"executionSemantics": EXECUTION_SEMANTICS, "vmCount": 1, "rootSeed": 9007199254740993,
              "workflowPaths": [str(source)], "workflowArrivalSeconds": [0.0], "runtimeScale": 1.0, "runtimeReferenceMips": 1000.0,
              "clustering": {"method": "NONE"}, "costModel": "DATACENTER", "taskCostMatrix": None}
    if matrix:
        config["taskCostMatrix"] = {"entries": [{"taskId": 1, "vmId": 0, "executionSeconds": duration}]}
    doc = {"schema": "workflowsim-experiment-manifest-v4", "configuration": config,
           "platform": {"vms": [{"id": 0, "mips": 1000.0, "pes": 1, "schedulerMode": "SPACE_SHARED"}],
                        "costs": {"cpuPerSecond": 3.0, "bandwidth": 0.1}},
           "inputs": [{"path": str(source), "sha256": fingerprint(source)[0], "sizeBytes": fingerprint(source)[1],
                       "format": "DAX_XML", "declaredVersion": "2.1", "taskCount": 1}],
           "workflowProfile": {"taskCount": 1}, "workflowGraph": [{"taskId": 1, "parentIds": [], "childIds": []}],
           "result": {"simulationEndSeconds": end, "jobs": jobs, "tasks": [task]}, "metrics": metrics,
           "events": {"eventCount": len(events)}, "provenance": {"execution": {"workingDirectory": str(root)}}}
    manifest = root / "run.manifest.json"
    bind_sidecars(manifest, doc)
    return manifest


def make_large_clock_bundle(root, lost_cpu_seconds=0.0):
    """One 300-MI task arrives near 1e9 s; optional 0.02 s loss exceeds the 0.001 s MI quantum."""
    manifest = make_bundle(root, duration=0.3, effective=300, matrix=True)
    doc = json.loads(manifest.read_text())
    job = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
    task = doc["result"]["tasks"][0]
    origin = 1_000_000_000.0
    honest_end = origin + job["finishTime"]
    job["submissionTime"] += origin
    job["startTime"] += origin
    job["finishTime"] = honest_end - lost_cpu_seconds
    observed_cpu = job["finishTime"] - job["startTime"]
    job.update(executionTime=observed_cpu, responseTime=observed_cpu, cpuTime=observed_cpu,
               modeledCpuEnvelopeCost=3.0 * observed_cpu,
               modeledProcessingCost=3.0 * observed_cpu + job["modeledDeclaredFileBandwidthCost"])
    task["startTime"] += origin
    task["finishTime"] = honest_end  # Full declared 300-MI modeled window remains unchanged.
    task["exactJobTiming"] = lost_cpu_seconds == 0.0
    doc["configuration"]["workflowArrivalSeconds"] = [job["startTime"]]
    doc["result"]["simulationEndSeconds"] = honest_end
    events_path = manifest.parent / "run.events.jsonl"
    events = [json.loads(line) for line in events_path.read_text().splitlines()]
    first_compute = next(e["sequence"] for e in events if e["type"] == "JOB_READY" and e["jobId"] == 0)
    for event in events:
        if event["sequence"] >= first_compute:
            event["simulationTime"] += origin
        if event["type"] == "TASK_EXECUTION_MODELED":
            event["attributes"]["taskStartTime"] = task["startTime"]
            event["attributes"]["taskFinishTime"] = task["finishTime"]
    events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    # Elementary accounting from the edited observations, not an invocation of the checker.
    stage = next(j for j in doc["result"]["jobs"] if j["classType"] == 1)
    busy = (stage["finishTime"] - stage["startTime"]) + observed_cpu
    reported_cpu = stage["cpuTime"] + observed_cpu
    m = doc["metrics"]
    m.update(makespanSeconds=honest_end, logicalTaskCompletionSeconds=job["finishTime"],
             terminalLifecycleTailSeconds=honest_end - job["finishTime"], computeJobOutcomeThroughputPerSecond=1.0 / honest_end,
             meanComputeJobRunTimeSeconds=observed_cpu, meanJobResponseTimeSeconds=observed_cpu,
             totalModeledCpuEnvelopeCost=stage["modeledCpuEnvelopeCost"] + job["modeledCpuEnvelopeCost"],
             totalModeledProcessingCost=stage["modeledProcessingCost"] + job["modeledProcessingCost"],
             totalVmModeledBusyIntervalSeconds=busy, meanVmModeledIntervalUtilization=busy / honest_end,
             exactTaskTimingObservationCount=1 if lost_cpu_seconds == 0.0 else 0,
             modeledApproximateTaskTimingObservationCount=0 if lost_cpu_seconds == 0.0 else 1)
    m["vmMetrics"]["0"].update(reportedCpuTimeSeconds=reported_cpu, modeledBusyIntervalSeconds=busy,
                                 modeledIntervalUtilization=busy / honest_end,
                                 reportedCpuTimeOverMakespan=reported_cpu / honest_end)
    bind_sidecars(manifest, doc)
    return manifest


def make_statistics_study(root):
    """Registered smoke declaration with elementary, manually chosen deterministic observations.

    Registration data supplies only fixture identifiers/parameters; all expected results below
    are literal 100/90/95 or 200/180/190 seconds and exact 10%/5%, p=Holm p=1.
    """
    root.mkdir(parents=True)
    _, _, plan, _ = registered_plan("peft-comparison-r12-v2", "smoke")
    for w in plan["workflows"]:
        w["path"] = str(root / w["path"])
    write_json(root / "protocol.json", plan)
    sha = fingerprint(root / "protocol.json")[0]
    rows, aggregates, comparisons = [], [], []
    for workflow in plan["workflows"]:
        baseline = 100.0 if workflow["id"] == "paper-10" else 200.0
        for network in ("endpoint", "fat-tree-constrained", "fat-tree-wide"):
            for planner, ratio in (("LOCAL_HEFT", 1.0), ("LOCAL_CPOP", 0.9), ("LOCAL_PEFT", 0.95)):
                seconds = baseline * ratio
                rid = workflow["id"] + "-v4-" + network + "-" + planner + "-s11"
                relative = "runs/" + rid + "/result.manifest.json"
                row = {"runId": rid, "workflowId": workflow["id"], "family": workflow["family"], "population": workflow["population"],
                       "vmCount": 4, "network": network, "planner": planner, "seed": 11, "status": "COMPLETED_SUCCESSFULLY",
                       "manifest": relative, "inputSha256": workflow["sha256"], "taskCount": 10 if workflow["id"] == "paper-10" else 16,
                       "makespanSeconds": seconds, "logicalCompletionSeconds": seconds, "meanWaitingSeconds": 0.0,
                       "p95WaitingSeconds": 0.0, "meanVmUtilization": 0.5}
                rows.append(row)
                aggregate = {k: row[k] for k in ("workflowId", "family", "population", "vmCount", "network", "planner")}
                aggregate.update(observedRuns=1, meanSeconds=seconds, medianSeconds=seconds, minimumSeconds=seconds, maximumSeconds=seconds)
                aggregates.append(aggregate)
                config = {"vmCount": 4, "rootSeed": 11, "planningAlgorithm": planner, "schedulingAlgorithm": "STATIC", "fileSystem": "LOCAL",
                          "runtimeScale": 1, "runtimeReferenceMips": 1000, "cloudSimMinEventIntervalSeconds": 0.1, "deadline": 0,
                          "taskCostMatrix": None, "reduceMethod": None, "costModel": "DATACENTER", "workflowArrivalSeconds": [0],
                          "workflowPaths": [workflow["path"]], "executionSemantics": EXECUTION_SEMANTICS,
                          "clustering": {"method": "NONE", "clustersNum": 0, "clustersSize": 0, "code": None},
                          "overheadModel": {"workflowEngineDelayInterval": 0, "bandwidth": 0, "workflowEngineDelays": {}, "queueDelays": {}, "postDelays": {}, "clusteringDelays": {}},
                          "failureModel": {"generatorMode": "FAILURE_NONE", "clusteringAlgorithm": "FTCLUSTERING_NOOP", "monitorMode": "MONITOR_NONE",
                                           "distributionFamily": "WEIBULL", "maxTotalRetryJobs": 0, "generatorAddressing": "DENSE_VM_ID_MATRIX", "generators": [], "generatorsByVmId": {}},
                          "algorithmContract": {"planner": {"id": planner}, "scheduler": {"id": "STATIC"}},
                          "dataMovementModel": {"kind": "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1" if network == "endpoint" else "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1",
                                                "accessLinkBandwidthMbPerSecond": 0, "accessLinkLatencySeconds": 0, "sourceEndpointBandwidthMbPerSecond": 0,
                                                "transferStartSemantics": "ALL_GROUPS_START_AT_JOB_READY;NO_RETROACTIVE_PARENT_PROGRESS", "bandwidthUnit": "DECIMAL_MB_PER_SECOND"}}
                platform = {"hostCount": 4, "vmCount": 4,
                            "hosts": [{"id": i, "pes": 2, "mipsPerPe": 2000, "ramMb": 2048, "bandwidth": 10000, "storageMb": 1000000} for i in range(4)],
                            "vms": [{"id": i, "mips": 1000, "pes": 1, "ramMb": 512, "bandwidth": 1, "imageSizeMb": 10000, "vmm": "Xen",
                                     "schedulerMode": "SPACE_SHARED", "pinnedHostId": i, "preflightHostId": i, "costs": None} for i in range(4)],
                            "storage": {"capacityMb": 1000000000000, "maxTransferRateMbPerSecond": 15},
                            "costs": {"cpuPerSecond": 3, "memory": 0.05, "storage": 0.1, "bandwidth": 0.1},
                            "networkTopology": None if network == "endpoint" else {"kind": "FAT_TREE", "k": 4,
                                "linkBandwidthMbPerSecond": 0.125 if network == "fat-tree-constrained" else 1.25, "coreSwitchCount": None, "hostEdgePlacements": None,
                                "defaultPlacementPolicy": "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES",
                                "routingPolicy": "DETERMINISTIC_AL_FARES_FAT_TREE_V1",
                                "linkDirectionality": "INDEPENDENT_DIRECTED_LINKS",
                                "externalSourceRouting": "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY"}}
                write_json(root / relative, {"schema": "workflowsim-experiment-manifest-v4", "configuration": config, "platform": platform,
                    "result": {"workflowCompletedSuccessfully": True, "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY", "actualVmHostAssignments": {str(i): i for i in range(4)}},
                    "inputs": [{"path": workflow["path"], "sha256": workflow["sha256"], "taskCount": row["taskCount"]}],
                    "metrics": {"makespanSeconds": seconds, "logicalTaskCompletionSeconds": seconds, "meanComputeTotalWaitingTimeSeconds": 0.0,
                                "p95ComputeTotalWaitingTimeSeconds": 0.0, "meanVmModeledIntervalUtilization": 0.5,
                                "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY", "allLogicalTasksCompletedSuccessfully": True},
                    "provenance": {"study": {"id": plan["protocol"], "protocol": {"logicalId": plan["protocol"], "available": True, "sha256": sha},
                                             "component": {"groupId": "org.workflowsim", "artifactId": "network-study", "version": "1.0", "anchorClass": "org.workflowsim.experiments.network.NetworkStudyExecutor"}}}})
            for candidate, effect in (("LOCAL_CPOP", 10.0), ("LOCAL_PEFT", 5.0)):
                comparisons.append({"population": workflow["population"], "vmCount": 4, "network": network, "baseline": "LOCAL_HEFT", "candidate": candidate,
                                    "dagPairs": 1, "wins": 1, "ties": 0, "losses": 0, "medianImprovementPercent": effect, "pValue": 1.0,
                                    "holmAdjustedPValue": 1.0, "significantAfterHolm": False, "test": "EXACT_TWO_SIDED_SIGN_TEST_ON_DAG_SEED_MEANS",
                                    "inferenceScope": "EXPLORATORY_SELECTED_CORPUS_NOT_RANDOM_WORKLOAD_POPULATION"})
    index = {"schema": "workflowsim-network-study-v1", "plan": plan, "runs": rows,
             "summary": {"runCount": 18, "failedRunCount": 0, "status": "COMPLETE", "aggregates": aggregates, "comparisons": comparisons}}
    write_json(root / "network-study.json", index)
    return root / "network-study.json"


class IndependentAuditorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, script, source, output):
        return subprocess.run([sys.executable, str(HERE / script), str(source), "--output", str(output)], capture_output=True, text=True)

    def test_java_round_ties_and_near_ties(self):
        self.assertEqual(0, java_round(0.49999999999999994))
        self.assertEqual(1, java_round(0.5))
        self.assertEqual(0, java_round(-0.5))
        self.assertEqual(2**52 + 1, java_round(float(2**52 + 1)))

    def test_independent_registration_counts_and_frozen_synthetic_hashes(self):
        for protocol, counts in (("network-limited-r10-v3", (504, 36)), ("peft-comparison-r12-v2", (126, 18)), ("sensitivity-response-r13-v2", (546, 30))):
            for mode, count in zip(("full", "smoke"), counts):
                self.assertEqual(count, len(registered_plan(protocol, mode)[3]))
        self.assertEqual("2a7a23bed50abc515523036ea220f292be0a8196a554e8dafeb2e8aba2407ee6", hashlib.sha256(layered_xml(8).encode()).hexdigest())
        self.assertEqual("342347cfb5cb139b52d903a611cb84ff205078250d3bdb754152cfb239af97e2", hashlib.sha256(layered_xml(32).encode()).hexdigest())

    def test_exact_sign_and_holm(self):
        self.assertEqual(Fraction(1, 32), statistics.exact_sign_test(6, 0))
        self.assertEqual(Fraction(1), statistics.exact_sign_test(3, 3))
        self.assertIsNone(statistics.exact_sign_test(0, 0))
        rows = [{"pExact": p} for p in (Fraction(2, 100), Fraction(1, 100), Fraction(4, 100))]
        statistics.holm(rows)
        self.assertEqual([Fraction(4, 100), Fraction(3, 100), Fraction(4, 100)], [r["holmExact"] for r in rows])

    def test_healthy_single_manifest_and_matrix_override(self):
        for name, duration, effective, matrix in (("plain", 1.0, 1000, False), ("matrix", 0.5, 500, True)):
            manifest = make_bundle(self.root / name, duration, effective, matrix)
            audit = Audit()
            evidence.inspect_manifest(manifest, audit)
            self.assertEqual(0, audit.issue_count, audit.report())

    def test_large_clock_fractional_duration_control_remains_valid(self):
        manifest = make_large_clock_bundle(self.root / "large-valid")
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())

    def test_large_clock_cannot_hide_cpu_shortfall_beyond_one_mi(self):
        manifest = make_large_clock_bundle(self.root / "large-short", lost_cpu_seconds=0.02)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts["cpu-lower-bound"], 0,
                           "20 MI of lost CPU work must be detected independently of other checks: " + str(audit.report()))

    def test_cli_failure_is_nonzero_for_coherently_missing_required_metric(self):
        manifest = make_bundle(self.root / "bundle")
        doc = json.loads(manifest.read_text())
        del doc["metrics"]["p95ComputeTotalWaitingTimeSeconds"]
        bind_sidecars(manifest, doc)
        result = self.cli("verify-study-evidence.py", manifest, self.root / "reports" / "bad.json")
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        report = json.loads((self.root / "reports" / "bad.json").read_text())
        self.assertGreater(report["issueCounts"].get("missing-required-metric", 0), 0)

    def test_overlap_and_raw_mi_forgery_are_detected(self):
        manifest = make_bundle(self.root / "bundle")
        doc = json.loads(manifest.read_text())
        doc["result"]["jobs"][1]["startTime"] = 0.15
        doc["result"]["tasks"][0]["lengthMi"] = 2000
        write_json(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts["vm-overlap"], 0, audit.report())
        self.assertGreater(audit.counts["raw-runtime-mi"], 0, audit.report())
        self.assertGreater(audit.counts["cpu-lower-bound"], 0, audit.report())

    def test_report_never_overwrites_or_writes_into_bundle(self):
        manifest = make_bundle(self.root / "bundle")
        before = fingerprint(manifest)
        forbidden = self.cli("verify-study-evidence.py", manifest, manifest.parent / "audit.json")
        self.assertEqual(2, forbidden.returncode)
        self.assertFalse((manifest.parent / "audit.json").exists())
        target = self.root / "report.json"
        first = self.cli("verify-study-evidence.py", manifest, target)
        self.assertEqual(0, first.returncode, first.stdout + first.stderr)
        saved = target.read_bytes()
        second = self.cli("verify-study-evidence.py", manifest, target)
        self.assertEqual(2, second.returncode)
        self.assertEqual(saved, target.read_bytes())
        self.assertEqual(before, fingerprint(manifest))

    def test_statistics_reject_each_changed_or_extra_topology_semantic(self):
        source = make_statistics_study(self.root / "topology-study")
        index = json.loads(source.read_text())
        row = next(r for r in index["runs"] if r["network"] == "fat-tree-constrained")
        manifest = source.parent / row["manifest"]
        original = json.loads(manifest.read_text())
        for field in ("defaultPlacementPolicy", "routingPolicy", "linkDirectionality", "externalSourceRouting", "unregisteredSemantic"):
            changed = copy.deepcopy(original)
            changed["platform"]["networkTopology"][field] = "WRONG_OR_UNREGISTERED"
            write_json(manifest, changed)
            audit = Audit()
            statistics.inspect_index(source, audit)
            self.assertTrue(any(i["code"] == "fixed-condition" and i["location"].endswith("platform/networkTopology")
                                for i in audit.issues), (field, audit.report()))
        write_json(manifest, original)

    def test_statistics_emit_full_per_dag_effects_and_reject_pruning(self):
        source = make_statistics_study(self.root / "study")
        audit = Audit()
        report, _ = statistics.inspect_index(source, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        self.assertEqual(12, len(report["perDagEffects"]))
        self.assertEqual({5.0, 10.0}, {r["improvementPercent"] for r in report["perDagEffects"]})
        doc = json.loads(source.read_text())
        doc["plan"]["workflows"] = []
        doc["plan"]["runCount"] = 0
        doc["runs"] = []
        write_json(source.parent / "protocol.json", doc["plan"])
        write_json(source, doc)
        outcome = self.cli("verify-study-statistics.py", source, self.root / "pruned.json")
        self.assertEqual(1, outcome.returncode, outcome.stdout + outcome.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
