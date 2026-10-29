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
    event("SCHEDULING_CYCLE", 0.1, decisionElapsedNanos=100_000)
    event("SCHEDULING_DECISION", 0.1, 1)
    event("JOB_RETURNED", 0.21, 1)
    event("JOB_READY", start, 0, [1])
    event("SCHEDULING_CYCLE", start, decisionElapsedNanos=250_750)
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
        "totalSchedulingDecisionWallClockNanos": 350_750, "totalPlanningDecisionWallClockNanos": 0,
        "deadlineObservationEnabled": False, "deadlineObservationOutcome": "NOT_REQUESTED",
        "deadlineObservationTimeBasis": "SIMULATION_END_SECONDS", "deadlineMet": False,
        "deadlineSlackSeconds": 0.0, "deadlineTardinessSeconds": 0.0,
        "controlledSharedStorageCriticalPathReferenceAvailable": False,
        "controlledSharedStorageCriticalPathReferenceScope": "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE",
        "controlledSharedStorageCriticalPathLowerBoundSeconds": 0.0,
        "controlledSharedStorageScheduleLengthRatio": 0.0,
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


    def test_deadline_family_recompute_matches_java_semantics(self):
        # 链 E：构造请求 deadline 的 bundle，验证 6 字段的 Python 精确复算。
        manifest = make_bundle(self.root / "deadline", 1.0, 1000, False)
        doc = json.loads(manifest.read_text())
        doc["configuration"]["deadline"] = 200  # 2× 100 就位（阈值数输入）
        m = doc["metrics"]
        m.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MET",
                 deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=True,
                 deadlineSlackSeconds=200 - m["makespanSeconds"], deadlineTardinessSeconds=0.0)
        bind_sidecars(manifest, doc)
        audit = Audit()
        result = evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        # 第二轮：MISSED_LATE 分支
        manifest2 = make_bundle(self.root / "deadline-late", 1.0, 1000, False)
        doc2 = json.loads(manifest2.read_text())
        doc2["configuration"]["deadline"] = 1  # 1 < makespan(1.21) → 迟到
        m2 = doc2["metrics"]
        slack2 = 1 - m2["makespanSeconds"]  # 负值 −0.21
        m2.update(deadlineObservationEnabled=True, deadlineObservationOutcome="MISSED_LATE",
                  deadlineObservationTimeBasis="SIMULATION_END_SECONDS", deadlineMet=False,
                  deadlineSlackSeconds=slack2, deadlineTardinessSeconds=-slack2)  # +0.21
        bind_sidecars(manifest2, doc2)
        audit2 = Audit()
        result2 = evidence.inspect_manifest(manifest2, audit2)
        self.assertEqual(0, audit2.issue_count, audit2.report())
        for key in ("deadlineObservationEnabled", "deadlineObservationOutcome",
                    "deadlineObservationTimeBasis", "deadlineMet",
                    "deadlineSlackSeconds", "deadlineTardinessSeconds"):
            self.assertIn(key, result["coverage"]["checkedMetrics"], key)
            self.assertIn(key, result2["coverage"]["checkedMetrics"], key)

    def test_wallclock_nanos_recompute_matches_java_number_semantics(self):
        manifest = make_bundle(self.root / "wallclock", 1.0, 1000, False)
        doc = json.loads(manifest.read_text())
        events_path = manifest.parent / "run.events.jsonl"
        events = [json.loads(line) for line in events_path.read_text().splitlines()]
        # 1) 数值截断：250.75 纳秒按 Java Number.longValue() 截为 250。
        end = float(doc["result"]["simulationEndSeconds"])
        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": 250.75}})
        # 2) 非数值（字符串）按 instanceof Number 语义跳过，不判失败。
        events.append({"sequence": len(events), "simulationTime": end, "type": "SCHEDULING_CYCLE",
                       "jobId": None, "taskIds": [], "attributes": {"decisionElapsedNanos": "not-a-number"}})
        # 3) 显式规划事件计 7 纳秒；4) 非显式事件携带 99 纳秒必须被排除（Java 同口径）。
        events.append({"sequence": len(events), "simulationTime": end, "type": "PLANNING_COMPLETED",
                       "jobId": None, "taskIds": [], "attributes": {"explicitPlannerDecision": True,
                                                                    "planningDecisionElapsedNanos": 7}})
        events.append({"sequence": len(events), "simulationTime": end, "type": "PLANNING_COMPLETED",
                       "jobId": None, "taskIds": [], "attributes": {"explicitPlannerDecision": False,
                                                                    "planningDecisionElapsedNanos": 99}})
        events_path.write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        metrics = doc["metrics"]
        metrics.update(schedulingCycleCount=4, totalSchedulingDecisionWallClockNanos=351_000,
                       totalPlanningDecisionWallClockNanos=7, explicitPlannerDecisionObservationCount=1)
        doc["events"]["eventCount"] = len(events)
        bind_sidecars(manifest, doc)
        audit = Audit()
        result = evidence.inspect_manifest(manifest, audit)
        self.assertEqual(0, audit.issue_count, audit.report())
        expected = {"totalSchedulingDecisionWallClockNanos": 351_000, "totalPlanningDecisionWallClockNanos": 7}
        for key, value in expected.items():
            self.assertEqual(value, result["recomputedMetrics"][key], key)
            self.assertIn(key, result["coverage"]["checkedMetrics"])

    def test_energy_conservation_recompute_and_corruption_catch(self):
        """BAT-E2（2026-10-27）：能耗守恒复算：正例（带功率平台）通过；篡改总能耗被击杀。

        数学（Beloglazov Eq.(2) 二元特化，单一 VM idle=100W max=400W）：
        E = 100×T_sim + (400−100)×T_busy = 121 + 333 = 454 J（默认时距）。
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "powered"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            # 平台补功率字段
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            end = manifest["metrics"]["makespanSeconds"]
            busy = manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"]
            expected_total = 100.0 * end + 300.0 * busy
            manifest["energyMetrics"] = {
                "totalEnergyJoules": expected_total,
                "meanVmEnergyJoules": expected_total,
                "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": expected_total * end,
                "vmEnergyJoules": {"0": expected_total},
            }
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            report_path = Path(tmp) / "energy-report.json"
            evidence.main(["--output", str(report_path), str(root / "run.manifest.json")])
            self.assertEqual(json.loads(report_path.read_text(encoding="utf-8"))["status"], "PASS")
            # 反例：篡改总能耗 → 守恒律击杀
            manifest["energyMetrics"]["totalEnergyJoules"] = expected_total + 500.0
            (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            bad_report = Path(tmp) / "energy-bad.json"
            evidence.main(["--output", str(bad_report), str(root / "run.manifest.json")])
            bad = json.loads(bad_report.read_text(encoding="utf-8"))
            self.assertEqual(bad["status"], "FAIL")
            self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total energy must be caught: " + json.dumps(bad["issues"][:2]))

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


def inject_contract_events(manifest, terminal_delta=0.0, start_delta=0.0, duplicate_start=False):
    """Insert the F-006/F-007 revision events into a make_bundle fixture.

    Elementary quantities only: the job envelope start is the fixture's known 0.21 and the
    modeled post-stage start equals envelope + effective stage. The tampering knobs corrupt
    exactly one derivation or identity, proving the independent auditor rejects each."""
    doc = json.loads(manifest.read_text())
    end = float(doc["result"]["simulationEndSeconds"])
    events = [json.loads(line) for line in (manifest.parent / "run.events.jsonl").read_text().splitlines() if line.strip()]
    insert_at = next(i for i, e in enumerate(events) if e["type"] == "TASK_EXECUTION_MODELED")
    start_event = {"sequence": insert_at, "simulationTime": 0.21, "type": "JOB_EXECUTION_STARTED",
                   "jobId": 0, "taskIds": [1],
                   "attributes": {"jobEnvelopeStartSeconds": 0.21, "effectiveDataStageSeconds": 0.0,
                                  "requestedDataStageSeconds": 5e-07,
                                  "modeledJobStartSeconds": 0.21 + start_delta}}
    events.insert(insert_at, start_event)
    if duplicate_start:
        events.insert(insert_at + 1, dict(start_event))
    events.append({"sequence": len(events), "simulationTime": end + terminal_delta,
                   "type": "SIMULATION_TERMINATED", "jobId": None, "taskIds": [],
                   "attributes": {"simulationEndSeconds": end + terminal_delta}})
    for index, event in enumerate(events):
        event["sequence"] = index
    (manifest.parent / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
    doc["events"]["eventCount"] = len(events)
    bind_sidecars(manifest, doc)
    return manifest


class ContractEventAuditTests(unittest.TestCase):
    """F-006/F-007 (adjudicated 2026-10-23): terminal and execution-start event cross-checks.

    Legacy streams without the revision events must keep passing unchanged; revision streams
    must be validated for terminal-clock identity, per-instance uniqueness, derivation
    arithmetic, and executed-job coverage."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_healthy_revision_bundle_and_legacy_bundle_both_pass(self):
        legacy_report = Audit()
        evidence.inspect_manifest(make_bundle(self.root / "legacy"), legacy_report)
        self.assertEqual(0, legacy_report.issue_count, legacy_report.report())
        revision_report = Audit()
        revision_manifest = inject_contract_events(make_bundle(self.root / "revisioned"))
        evidence.inspect_manifest(revision_manifest, revision_report)
        self.assertEqual(0, revision_report.issue_count, revision_report.report())

    def test_terminal_clock_identity_mismatch_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "terminal-mismatch"), terminal_delta=0.05)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("terminal-clock-identity", 0), 0, audit.report())

    def test_job_start_derivation_mismatch_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "start-mismatch"), start_delta=0.05)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("job-start-derivation", 0), 0, audit.report())

    def test_duplicate_job_execution_started_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "duplicate-start"), duplicate_start=True)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("duplicate-JOB_EXECUTION_STARTED", 0), 0, audit.report())

    def test_job_start_missing_for_executed_job_is_detected(self):
        manifest = inject_contract_events(make_bundle(self.root / "missing-start"))
        events = [json.loads(line) for line in (manifest.parent / "run.events.jsonl").read_text().splitlines()]
        start_at = next(i for i, e in enumerate(events) if e["type"] == "JOB_EXECUTION_STARTED")
        del events[start_at]
        for index, event in enumerate(events):
            event["sequence"] = index
        (manifest.parent / "run.events.jsonl").write_text(''.join(json.dumps(e) + '\n' for e in events), encoding="utf-8")
        doc = json.loads(manifest.read_text())
        doc["events"]["eventCount"] = len(events)
        bind_sidecars(manifest, doc)
        audit = Audit()
        evidence.inspect_manifest(manifest, audit)
        self.assertGreater(audit.counts.get("job-start-coverage", 0), 0, audit.report())


class CarbonDimensionAuditTests(unittest.TestCase):
    """BAT-C2（2026-10-28）：碳维度复算自测——期望值全部来自量纲定律（E[J]/3.6e6 × I[g/kWh]）
    与预算超额积分（Σ max(0, P_fleet(t)−green)×dt），即 CARBON_DIMENSION_DESIGN.md §1 的
    两族模型契约；手推导演，绝不用实现输出当期望值。声明→必查（declared→required）镜像
    energyMetrics 的 powered→required 规则（corruption 必须被击杀）。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def cli(self, script, source, output):
        return subprocess.run([sys.executable, str(HERE / script), str(source), "--output", str(output)],
                              capture_output=True, text=True)

    def audit_status(self, manifest, report_name):
        """跑一次独立审计 CLI，返回报告 dict。"""
        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest)])
        return json.loads(report.read_text(encoding="utf-8"))

    @staticmethod
    def elementary_energy(manifest):
        """初级量：E = 100×end + 300×busy（fixture 平台 idle=100W max=400W）。"""
        end = float(manifest["metrics"]["makespanSeconds"])
        busy = float(manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"])
        return 100.0 * end + 300.0 * busy, end, busy

    def test_carbon_intensity_recompute_and_corruption(self):
        """正例：强度 380 g/kWh → grams = E/3.6e6 × 380 通过审计；篡改 +5g 必须被捕杀。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-intensity"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            energy, end, _busy = self.elementary_energy(manifest)
            # energyMetrics 也是声明的（powered→required，已有规则）。
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            grams = energy / 3.6e6 * 380.0  # 单位律：J → kWh 除 3.6e6，× g/kWh
            manifest["carbonMetrics"] = {
                "totalCarbonGrams": grams, "meanVmCarbonGrams": grams / 1,
                "carbonMakespanProductGramSeconds": grams * end, "perVmCarbonGrams": {"0": grams},
                "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
            }
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            good = self.audit_status(bundle, "carbon-good")
            self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
            # 反例：篡改总克数 → 复算差异 > 容差 → 审计失败且命中 totalCarbonGrams
            manifest["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad = self.audit_status(bundle, "carbon-bad")
            self.assertEqual("FAIL", bad["status"])
            self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                            "corrupted total carbon must be caught: " + json.dumps(bad["issues"][:2]))

    def test_carbon_missing_when_intensities_declared(self):
        """BAT-C2 declared→required：声明了碳强度但缺 carbonMetrics 节 → 审计必须失败。"""
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-missing"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            energy, end, _busy = self.elementary_energy(manifest)
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            # 不写 carbonMetrics（ oxidative：声明→必查）
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            report = self.audit_status(bundle, "carbon-missing")
            self.assertEqual("FAIL", report["status"],
                             "intensity declarations without carbonMetrics must fail (BAT-C2 declared→required)")
            self.assertTrue(any("carbonMetrics" in str(i) for i in report["issues"]),
                            "failure must cite the carbonMetrics section: " + json.dumps(report["issues"][:3]))

    def test_carbon_budget_window_recompute_and_corruption(self):
        """预算模型：手推导演 fleet 时间线（计算 Job 区间 +Σidle/并发×ΔP）与逐窗口超额。

        数学（fixture 单 VM idle=100W max=400W；计算 Job 区间 [0.21, 1.21)）：
        P(t)=400W 于 [0.21,1.21)，100W 其余；窗口 [0,1.5) 绿 250W → 超额 (400−250)×1.0=150 W·s；
        窗口 [2,5) 绿 250W → 覆盖区间外不叠加 → 0。总量 150 与窗口子图 {"0":150} 一致。
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "carbon-budget"
            make_bundle(root)
            manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
            manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
            manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
            manifest["platform"]["carbonWindows"] = [
                {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 250.0},
                {"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 250.0},
            ]
            energy, end, _busy = self.elementary_energy(manifest)
            manifest["energyMetrics"] = {
                "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
            }
            manifest["carbonMetrics"] = {
                "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
                "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
                "totalCarbonExcessWattSeconds": 150.0, "windowExcessWattSeconds": {"0": 150.0},
            }
            bundle = root / "run.manifest.json"
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            good = self.audit_status(bundle, "budget-good")
            self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
            # 反例一：总量超额被篡改 → 击杀
            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 155.0
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad1 = self.audit_status(bundle, "budget-bad-total")
            self.assertEqual("FAIL", bad1["status"])
            self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad1["issues"]),
                            "corrupted total excess must be caught: " + json.dumps(bad1["issues"][:2]))
            # 反例二：窗口子图与总量不一致（子图 ≠ 复算的逐窗值）→ 击杀
            manifest["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 150.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 100.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            bad2 = self.audit_status(bundle, "budget-bad-window")
            self.assertEqual("FAIL", bad2["status"])
            self.assertTrue(any("windowExcess" in str(i) for i in bad2["issues"]),
                            "corrupted window sub-map must be caught: " + json.dumps(bad2["issues"][:2]))
            # 反例三：两模型同声明（per-VM 强度 + 平台窗口）→ 契约互斥条款击杀
            manifest["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
            manifest["carbonMetrics"]["windowExcessWattSeconds"] = {"0": 150.0}
            bundle.write_text(json.dumps(manifest), encoding="utf-8")
            both = self.audit_status(bundle, "budget-bad-both")
            self.assertEqual("FAIL", both["status"])
            self.assertTrue(any("mutually exclusive" in str(i) or "intensity" in str(i) for i in both["issues"]),
                            "dual declaration must be rejected citing the exclusion clause: "
                            + json.dumps(both["issues"][:2]))

    def test_carbon_windows_must_be_sorted_non_overlapping(self):
        """窗口重排序校验：乱序/重叠 → 契约条款击杀（镜像 Java builder 校验）。"""
        for where, windows in (
            ("overlap", [{"startSeconds": 0.0, "endSeconds": 2.0, "greenWatts": 250.0},
                         {"startSeconds": 1.0, "endSeconds": 5.0, "greenWatts": 100.0}]),
            ("unsorted", [{"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 250.0},
                          {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 250.0}]),
        ):
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp) / ("carbon-window-" + where)
                make_bundle(root)
                manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
                manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
                manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
                manifest["platform"]["carbonWindows"] = windows
                energy, end, _busy = self.elementary_energy(manifest)
                manifest["energyMetrics"] = {
                    "totalEnergyJoules": energy, "meanVmEnergyJoules": energy, "peakPowerWatts": 400.0,
                    "energyMakespanProductJouleSeconds": energy * end, "vmEnergyJoules": {"0": energy},
                }
                manifest["carbonMetrics"] = {
                    "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
                    "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
                    "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
                }
                bundle = root / "run.manifest.json"
                bundle.write_text(json.dumps(manifest), encoding="utf-8")
                report = self.audit_status(bundle, "window-" + where)
                self.assertEqual("FAIL", report["status"], where)
                self.assertTrue(any("sorted" in str(i) or "overlapping" in str(i) for i in report["issues"]),
                                where + ": window order clause must fire: " + json.dumps(report["issues"][:2]))


class DvfsTierAuditTests(unittest.TestCase):
    """BAT-D3（2026-10-29）：DVFS 逐档能量/碳复算自测——期望值全部来自扩展守恒律手推
    （DVFS_DIMENSION_DESIGN.md §2.3：E_vm = P_idle(默认档)×T_sim + Σ档[(P_max(档)−
    P_idle(默认档))×busy(档)]）与逐档舰队功率超额积分，手推数字写在断言旁注释；
    绝不用实现输出当期望值。正例通过、逐档归因/能耗/档表篡改各自被击杀、v1 惰性
    语义位不变（tierless 平台 + 平面功率 → D1 能量律原值通过）。"""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def audit_status(self, manifest_path, report_name):
        """跑一次独立审计 CLI，返回报告 dict。"""
        report = Path(self.temp.name) / (report_name + ".json")
        evidence.main(["--output", str(report), str(manifest_path)])
        return json.loads(report.read_text(encoding="utf-8"))

    # ── fixture：2 档平台（2000 默认档 100/400W；1000 档 90/200W），VM 基速 1000 ──
    # 计算 Job（1000 MI，基速 1000 → 1.0s）挂 1000 档（== 基速 → 时间面与 make_bundle
    # 逐位同构，频档证据面叠加其上）；stage-in 簿记信封（0.11s）缺档 → 归默认档。
    TWO_TIERS = [{"mips": 2000.0, "idlePowerWatts": 100.0, "maxPowerWatts": 400.0},
                 {"mips": 1000.0, "idlePowerWatts": 90.0, "maxPowerWatts": 200.0}]

    def make_tier_manifest(self, name="dvfs"):
        """带频档平台的合成 manifest 路径；调用方按需写入 energyMetrics 等。"""
        root = self.root / name
        make_bundle(root, 1.0, 1000, False)
        doc = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in self.TWO_TIERS]
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 1000.0  # 非默认档（默认档 = 表首 2000）
        return root / "run.manifest.json", doc

    def store_energy(self, doc, total):
        """手推四元组：单 VM 总能量 total → 均值/峰值/EDP 同源手算。"""
        end = float(doc["metrics"]["makespanSeconds"])
        doc["energyMetrics"] = {
            "totalEnergyJoules": total,
            "meanVmEnergyJoules": total,  # 单 VM 均值 = 总量
            "peakPowerWatts": 400.0,      # 默认档（表首）P_max —— Java 侧 fleet peak 口径
            "energyMakespanProductJouleSeconds": total * end,
            "vmEnergyJoules": {"0": total},
        }

    def test_tiered_energy_recompute_matches_hand_law(self):
        """正例：E = 100×1.21 + 0.11×(400−100) + 1.0×(200−100) = 121+33+100 = 254 J。

        逐档归因：stage-in 簿记 [0.1,0.21) 缺档→默认档 0.11s；计算 Job [0.21,1.21)
        携带 1000 档 1.0s（非默认档——D1 全默认档口径 = 121+300×1.11 = 454 J，两者
        必须被区分：本用例存 254 通过、备注记录 honest 观察值 454。）
        """
        bundle, doc = self.make_tier_manifest("dvfs-good")
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        note = next((n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"), None)
        self.assertIsNotNone(note, "DVFS 观察备注必须存在")
        # 观察值非断言口径：手推值带浮点容差对（453.99999999999994 级末位噪声合法）。
        self.assertAlmostEqual(254.0, note["perTierTotalEnergyJoules"], delta=1e-9)
        self.assertAlmostEqual(454.0, note["allAtDefaultEnergyJoules"], delta=1e-9)
        self.assertTrue(note["nonDefaultTierBusyObserved"])

    def test_tiered_energy_discriminates_all_at_default_forgery(self):
        """反例一（口径判别）：清单记录 D1 全默认档值 454 J（逐档真实归因仍在）→ 击杀。

        若审计器退回 D1 口径（或 v1 平面功率口径 0 J），454 都不该被判 PASS——
        与上一用例（254 通过）成对，唯一可同时满足两者的复算律即逐档扩展律。
        """
        bundle, doc = self.make_tier_manifest("dvfs-454")
        self.store_energy(doc, 454.0)  # 伪造：能量按"全默认档"口径报关，归因证据却是 split
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-454")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "forged all-at-default energy must be caught: " + json.dumps(report["issues"][:2]))

    def test_tier_attribution_corruption_is_killed(self):
        """反例二（"改动一个档的 busy"）：计算 Job 的档归因改挂默认档 → 档 busy 重排
        （1000 档 0s / 默认档 1.11s）→ 复算能量 454 ≠ 存档 254 → 击杀。"""
        bundle, doc = self.make_tier_manifest("dvfs-retier")
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 2000.0  # 篡改归因（仍为已声明档——档身份合法、能量证据断裂）
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-retier")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("totalEnergyJoules" in str(i.get("location", "")) for i in report["issues"]),
                        "re-tiered attribution must break the conserved energy: "
                        + json.dumps(report["issues"][:2]))

    def test_undeclared_tier_mips_is_chain_broken(self):
        """反例三：档 mips=1500 不在声明表内 → 镜像 Java tierByMips 的证据链条款击杀。"""
        bundle, doc = self.make_tier_manifest("dvfs-undeclared")
        compute = next(j for j in doc["result"]["jobs"] if j["classType"] == 2)
        compute["tierMips"] = 1500.0
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-undeclared")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("frequency tier" in str(i) or "tier evidence" in str(i) for i in report["issues"]),
                        "undeclared tier mips must be rejected citing the chain clause: "
                        + json.dumps(report["issues"][:2]))

    def test_tier_table_ordering_is_validated(self):
        """反例四：档表非严格降序（1000 在 2000 前）→ 表序契约（BAT-D1）击杀。"""
        bundle, doc = self.make_tier_manifest("dvfs-unsorted")
        doc["platform"]["vms"][0]["frequencyTiers"] = [dict(t) for t in reversed(self.TWO_TIERS)]
        self.store_energy(doc, 254.0)
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        report = self.audit_status(bundle, "tier-unsorted")
        self.assertEqual("FAIL", report["status"])
        self.assertTrue(any("descending" in str(i) for i in report["issues"]),
                        "non-descending tier table must be rejected: " + json.dumps(report["issues"][:2]))

    def test_tiered_carbon_budget_uses_per_tier_fleet_power(self):
        """预算模型逐档舰队功率：手推时间线（计算 Job [0.21,1.21) @1000 档）。

        P(t) = P_idle(默认档)=100（静态）+ 100（1000 档 dyn=200−100）= 200W 于
        [0.21,1.21)；窗口 [0,1.5) 绿 150W → 超额 (200−150)×1.0 = 50 W·s，窗口
        [2,5) 同绿 → 区间外 0；总量 50 = 窗口子图 {"0":50}。若沿用 v1 均一 ΔP
        （tiered VM 平面功率=0 → ΔP=0 → 全程 0W）期望超额将恒为 0 ≠ 50——本用例
        以 50 通过唯一裁定逐档口径生效（Java 侧预算模型的频档化是后续批次，
        D3 前向口径见 DVFS_DIMENSION_DESIGN.md §1.5）。
        """
        bundle, doc = self.make_tier_manifest("dvfs-budget")
        doc["platform"]["carbonWindows"] = [
            {"startSeconds": 0.0, "endSeconds": 1.5, "greenWatts": 150.0},
            {"startSeconds": 2.0, "endSeconds": 5.0, "greenWatts": 150.0},
        ]
        self.store_energy(doc, 254.0)
        doc["carbonMetrics"] = {
            "totalCarbonGrams": 0.0, "meanVmCarbonGrams": 0.0,
            "carbonMakespanProductGramSeconds": 0.0, "perVmCarbonGrams": {},
            "totalCarbonExcessWattSeconds": 50.0, "windowExcessWattSeconds": {"0": 50.0},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        good = self.audit_status(bundle, "tier-budget-good")
        self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
        # 反例：总量超额篡改 +1 → 击杀
        doc["carbonMetrics"]["totalCarbonExcessWattSeconds"] = 51.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-budget-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonExcessWattSeconds" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier fleet excess must be caught: " + json.dumps(bad["issues"][:2]))

    def test_tiered_carbon_intensity_rides_per_tier_energy(self):
        """强度模型 ride-through：grams = E/3.6e6 × I（E = 逐档律 254 J，强度 380 g/kWh）。

        该用例同时锚定 powered 判据的频档扩展（平面字段全 0 的 tiered VM 被 v1 判据
        误判为 unpowered → 半声明击杀；逐档功率闸幂（平面或频档任一）后按量纲律通过）。
        """
        bundle, doc = self.make_tier_manifest("dvfs-intensity")
        doc["platform"]["vms"][0]["carbonIntensityGramsPerKWh"] = 380.0
        self.store_energy(doc, 254.0)
        grams = 254.0 / 3.6e6 * 380.0  # 单位律：J → kWh 除 3.6e6，× g/kWh
        doc["carbonMetrics"] = {
            "totalCarbonGrams": grams, "meanVmCarbonGrams": grams,
            "carbonMakespanProductGramSeconds": grams * float(doc["metrics"]["makespanSeconds"]),
            "perVmCarbonGrams": {"0": grams},
            "totalCarbonExcessWattSeconds": 0.0, "windowExcessWattSeconds": {},
        }
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        good = self.audit_status(bundle, "tier-intensity-good")
        self.assertEqual("PASS", good["status"], json.dumps(good["issues"][:3]))
        # 反例：篡改 +5g → 击杀
        doc["carbonMetrics"]["totalCarbonGrams"] = grams + 5.0
        bundle.write_text(json.dumps(doc), encoding="utf-8")
        bad = self.audit_status(bundle, "tier-intensity-bad")
        self.assertEqual("FAIL", bad["status"])
        self.assertTrue(any("totalCarbonGrams" in str(i.get("location", "")) for i in bad["issues"]),
                        "corrupted per-tier ridden grams must be caught: " + json.dumps(bad["issues"][:2]))

    def test_plain_v1_fixture_stays_on_the_v1_law(self):
        """v1 惰性位锚：无 frequencyTiers、平面功率 100/400 的同一 fixture →
        D1 能量律原值（121+300×1.11=454 J）通过；审计行为与 D3 前逐位一致。"""
        root = self.root / "v1-plain"
        make_bundle(root)
        manifest = json.loads((root / "run.manifest.json").read_text(encoding="utf-8"))
        manifest["platform"]["vms"][0]["idlePowerWatts"] = 100.0
        manifest["platform"]["vms"][0]["maxPowerWatts"] = 400.0
        end = manifest["metrics"]["makespanSeconds"]
        busy = manifest["metrics"]["vmMetrics"]["0"]["modeledBusyIntervalSeconds"]
        expected = 100.0 * end + 300.0 * busy  # = 121 + 333 = 454 J（D1 律手推）
        manifest["energyMetrics"] = {
            "totalEnergyJoules": expected, "meanVmEnergyJoules": expected,
            "peakPowerWatts": 400.0, "energyMakespanProductJouleSeconds": expected * end,
            "vmEnergyJoules": {"0": expected},
        }
        (root / "run.manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        report = self.audit_status(root / "run.manifest.json", "v1-plain-good")
        self.assertEqual("PASS", report["status"], json.dumps(report["issues"][:3]))
        # v1 平台不携带任何 DVFS 观察备注（短路位）。
        self.assertFalse([n for n in report["notes"] if n["code"] == "dvfs-tier-accounting-observation"],
                         str(report["notes"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
