#!/usr/bin/env python3
"""Independently audit saved WorkflowSim bundles; this is not a simulation/network replay."""
from __future__ import annotations

import argparse
import collections
import math
import sys
from pathlib import Path

from _study_audit import (Audit, CheckError, EXECUTION_SEMANTICS, LONG_MAX, RUN_METRICS,
                          contained_file, decode_json, digest, exact_equal, integer, java_round,
                          mean, needed, number, ordered_sum, parse_workflow, percentile,
                          protect_output, read_json, resolve_input, union_length, write_report)

SUCCESS, FAILED, COMPUTE, STAGE_IN = 4, 5, 2, 1
PRE_MODELS = {"PRE_EXECUTION_TRANSFER_DELAY_V1", "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1",
              "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"}


def inspect_manifest(path, audit, historical=False, input_roots=(), cache=None):
    path = Path(path).resolve()
    before_checks, before_issues = audit.checks, audit.issue_count
    prefix = str(path)
    cache = cache if cache is not None else {}
    coverage = {"rawInputs": [], "networkReplay": "NOT_PERFORMED_NO_COMPLETE_PER_FLOW_LEDGER",
                "scope": "STANDARD_RUNNER_NONE_CLUSTERING_SPACE_SHARED_ONE_JOB_PER_VM"}
    derived = {}
    try:
        doc = read_json(path)
        if needed(doc, "schema") != "workflowsim-experiment-manifest-v4":
            raise CheckError("only manifest v4 is supported; old schemas are not silently upgraded")
        config, platform = needed(doc, "configuration"), needed(doc, "platform")
        result, metrics = needed(doc, "result"), needed(doc, "metrics")
        marker = config.get("executionSemantics")
        old = marker is None
        audit.ensure(not old or historical, "historical-mode-required", prefix)
        audit.ensure(old or marker == EXECUTION_SEMANTICS, "execution-model-identity", prefix, marker, EXECUTION_SEMANTICS)
        coverage["executionMode"] = "HISTORICAL_DECLARATION" if old else marker
        if needed(needed(config, "clustering"), "method") != "NONE":
            raise CheckError("clustering is outside the supported independent raw-task/job projection")
        vms = {integer(needed(v, "id"), "vm id", True): v for v in needed(platform, "vms")}
        audit.ensure(len(vms) == len(platform["vms"]), "unique-vm-ids", prefix)
        audit.ensure(len(vms) == integer(needed(config, "vmCount"), "vmCount", True), "vm-count", prefix)
        for vm in vms.values():
            if needed(vm, "schedulerMode") != "SPACE_SHARED":
                raise CheckError("only standard SPACE_SHARED evidence is supported")
            number(needed(vm, "mips"), "VM MIPS", positive=True)
        seed = integer(needed(config, "rootSeed"), "rootSeed")
        audit.ensure(-(1 << 63) <= seed <= LONG_MAX, "signed-long-root-seed", prefix, seed)
        end = number(needed(result, "simulationEndSeconds"), "simulation end", nonnegative=True)
        def tolerance(*timestamps):
            # A large absolute clock must not buy a proportional amount of missing CPU work.
            magnitude = max((abs(float(value)) for value in timestamps), default=0.0)
            return audit.abs_tol + 4.0 * math.ulp(magnitude)

        def time_equal(actual, expected, *timestamps):
            try:
                a = number(actual, "time observation", nonnegative=True)
                b = number(expected, "expected time", nonnegative=True)
            except CheckError:
                return False
            return abs(a - b) <= tolerance(*(timestamps or (a, b)))

        def time_metric(record, key, expected, location, *timestamps):
            if key not in record:
                audit.problem("missing-time-observation", location + "/" + key)
                return
            audit.ensure(time_equal(record[key], expected, *timestamps), "time-observation-mismatch",
                         location + "/" + key, record[key], expected)

        refs = {}
        for item in needed(doc, "artifacts"):
            role = needed(item, "role")
            if role in refs:
                raise CheckError("duplicate artifact role: " + role)
            referenced = contained_file(path.parent, needed(item, "path"))
            refs[role] = referenced
            sha, size = digest(referenced)
            audit.ensure(sha == needed(item, "sha256"), "artifact-sha256", str(referenced), sha, item["sha256"])
            audit.ensure(size == integer(needed(item, "sizeBytes"), "artifact size", True), "artifact-size", str(referenced), size, item["sizeBytes"])
        if "metrics" not in refs or "events" not in refs:
            raise CheckError("bundle must contain metrics and events sidecars")
        side = read_json(refs["metrics"])
        audit.ensure(needed(side, "schema") == "workflowsim-simulation-metrics-v2", "metrics-schema", prefix)
        audit.ensure(exact_equal(needed(side, "metrics"), metrics), "manifest-sidecar-metrics", prefix)

        events, by_type = [], collections.defaultdict(dict)
        previous = 0.0
        with refs["events"].open(encoding="utf-8") as stream:
            for sequence, line in enumerate(stream):
                if not line.strip():
                    raise CheckError("blank JSONL event line")
                event = decode_json(line)
                audit.ensure(integer(needed(event, "sequence"), "event sequence", True) == sequence,
                             "event-sequence", prefix + ":event:" + str(sequence), event.get("sequence"), sequence)
                time = number(needed(event, "simulationTime"), "event time", nonnegative=True)
                audit.ensure(time + tolerance(time, previous) >= previous and time <= end + tolerance(time, end),
                             "event-clock-order", prefix + ":event:" + str(sequence), time, {"previous": previous, "end": end})
                previous = time
                typ = needed(event, "type")
                if not isinstance(typ, str) or not isinstance(needed(event, "attributes"), dict):
                    raise CheckError("event type/attributes have invalid JSON types")
                event["taskIds"] = [integer(tid, "event task id", True) for tid in needed(event, "taskIds")]
                if typ in ("JOB_READY", "SCHEDULING_DECISION", "JOB_RETURNED", "DATA_STAGE_IN_MODELED", "STAGE_IN_JOB_CREATED"):
                    jid = integer(needed(event, "jobId"), "event jobId", True)
                    audit.ensure(set(event) >= {"attributes", "taskIds"}, "event-required-members", prefix)
                    audit.ensure(jid not in by_type[typ], "duplicate-" + typ, prefix, jid)
                    by_type[typ][jid] = event
                events.append(event)
        audit.ensure(len(events) == integer(needed(needed(doc, "events"), "eventCount"), "event count", True),
                     "event-count", prefix, len(events), doc["events"].get("eventCount"))

        raw_tasks, raw_external = {}, collections.OrderedDict()
        inputs = needed(doc, "inputs")
        declared_paths = needed(config, "workflowPaths")
        audit.ensure(isinstance(inputs, list) and bool(inputs), "nonempty-inputs", prefix)
        audit.ensure(exact_equal(declared_paths, [needed(entry, "path") for entry in inputs]), "ordered-input-paths", prefix)
        arrivals = needed(config, "workflowArrivalSeconds")
        audit.ensure(len(arrivals) == len(inputs), "arrival-count", prefix)
        first_id = 1
        for index, entry in enumerate(inputs):
            try:
                source, tried = resolve_input(doc, path, entry, input_roots)
                stat = source.stat()
                key = (str(source), stat.st_size, stat.st_mtime_ns, first_id, index, len(inputs),
                       str(config.get("runtimeScale")), str(config.get("runtimeReferenceMips")))
                if cache.get(key) is None:
                    fingerprint = digest(source)
                    parsed = parse_workflow(source, first_id, "workflow-" + str(index) if len(inputs) > 1 else None, config)
                    cache[key] = fingerprint, parsed
                (sha, size), (tasks, kind, version, warnings) = cache[key]
                audit.ensure(sha == needed(entry, "sha256") and size == integer(needed(entry, "sizeBytes"), "input size", True),
                             "input-fingerprint", prefix + ":input:" + str(index), [sha, size], [entry.get("sha256"), entry.get("sizeBytes")])
                audit.ensure(kind == needed(entry, "format"), "raw-input-format", prefix, kind, entry.get("format"))
                audit.ensure(version == needed(entry, "declaredVersion"), "raw-input-version", prefix, version, entry.get("declaredVersion"))
                audit.ensure(len(tasks) == integer(needed(entry, "taskCount"), "input task count", True), "raw-input-task-count", prefix)
                arrival = number(arrivals[index], "workflow arrival", nonnegative=True)
                for task in tasks.values():
                    raw_tasks[task["taskId"]] = dict(task, arrival=arrival)
                coverage["rawInputs"].append({"recorded": entry["path"], "resolved": str(source), "sha256": sha,
                                               "taskCount": len(tasks), "format": kind, "normalizationNotes": warnings})
                first_id += len(tasks)
            except (CheckError, OSError, KeyError, ValueError, TypeError) as error:
                audit.problem("raw-input-reparse-unavailable-or-invalid", prefix + ":input:" + str(index), str(error))
                coverage["rawInputs"].append({"recorded": entry.get("path"), "status": "UNAVAILABLE", "reason": str(error)})
                first_id += integer(needed(entry, "taskCount"), "input task count", True)
        parsed_complete = len(raw_tasks) == sum(integer(needed(i, "taskCount"), "input task count", True) for i in inputs)
        all_outputs = {name for task in raw_tasks.values() for name, kind, _ in task["files"] if kind == "output"}
        for task in raw_tasks.values():
            for name, kind, size in task["files"]:
                if kind == "input" and name not in all_outputs:
                    raw_external.setdefault(name, size)
        graph = {integer(needed(n, "taskId"), "graph task id", True): n for n in needed(doc, "workflowGraph")}
        audit.ensure(len(graph) == len(doc["workflowGraph"]), "unique-graph-task-ids", prefix)
        if parsed_complete:
            audit.ensure(set(graph) == set(raw_tasks), "raw-graph-task-ids", prefix)
            for tid, task in raw_tasks.items():
                if tid not in graph:
                    continue
                audit.ensure(set(needed(graph[tid], "parentIds")) == task["parents"]
                             and set(needed(graph[tid], "childIds")) == task["children"], "raw-dag-dependencies", prefix, tid)
            audit.ensure(integer(needed(needed(doc, "workflowProfile"), "taskCount"), "profile task count", True) == len(raw_tasks),
                         "raw-profile-task-count", prefix)
        logical_ids = set(raw_tasks) if parsed_complete else set(graph)

        jobs, task_outcomes = needed(result, "jobs"), needed(result, "tasks")
        jobs_by_id, jobs_by_task, vm_intervals = {}, collections.defaultdict(list), collections.defaultdict(list)
        first_success = {}
        computes = []
        for job in jobs:
            jid = integer(needed(job, "jobId"), "jobId", True)
            audit.ensure(jid not in jobs_by_id, "unique-job-id", prefix, jid)
            jobs_by_id[jid] = job
            vmid = integer(needed(job, "vmId"), "job vmId", True)
            if vmid not in vms:
                raise CheckError("job uses undeclared VM: " + str(vmid))
            status = integer(needed(job, "status"), "job status", True)
            cls = integer(needed(job, "classType"), "job class", True)
            audit.ensure(status in (SUCCESS, FAILED) and cls in (COMPUTE, STAGE_IN), "supported-job-outcome", prefix, [jid, status, cls])
            submission, start, finish = [number(needed(job, f), f, nonnegative=True) for f in ("submissionTime", "startTime", "finishTime")]
            audit.ensure(submission <= start + tolerance(submission, start) and start <= finish + tolerance(start, finish)
                         and finish <= end + tolerance(finish, end), "job-interval", prefix, {"jobId": jid, "submission": submission, "start": start, "finish": finish, "end": end})
            vm_intervals[vmid].append((start, finish))
            for name, expected in (("waitingTime", max(0.0, start - submission)), ("executionTime", max(0.0, finish - start)),
                                   ("responseTime", max(0.0, finish - submission)), ("cpuTime", max(0.0, finish - start))):
                time_metric(job, name, expected, prefix + ":job:" + str(jid), submission, start, finish)
            for typ in ("JOB_READY", "SCHEDULING_DECISION", "JOB_RETURNED"):
                audit.ensure(jid in by_type[typ], "missing-" + typ, prefix, jid)
            if jid in by_type["JOB_READY"] and jid in by_type["SCHEDULING_DECISION"]:
                ready = number(by_type["JOB_READY"][jid]["simulationTime"])
                decision = number(by_type["SCHEDULING_DECISION"][jid]["simulationTime"])
                audit.ensure(ready <= decision + tolerance(ready, decision) and decision <= start + tolerance(decision, start), "ready-decision-start", prefix, jid)
            tids = [integer(tid, "job task id", True) for tid in needed(job, "taskIds")]
            job["taskIds"] = tids
            audit.ensure(len(tids) == integer(needed(job, "taskCount"), "job task count", True) and len(set(tids)) == len(tids), "job-task-list", prefix, jid)
            if cls == COMPUTE:
                computes.append(job)
                audit.ensure(len(tids) == 1 and all(tid in logical_ids for tid in tids), "unclustered-job-source-task", prefix, [jid, tids])
                audit.ensure(jid in by_type["DATA_STAGE_IN_MODELED"], "missing-data-stage-in", prefix, jid)
                for tid in tids:
                    jobs_by_task[tid].append(job)
                    if status == SUCCESS:
                        first_success[tid] = min(finish, first_success.get(tid, math.inf))
            else:
                audit.ensure(not tids, "stage-in-has-no-logical-task", prefix, jid)
                minimum = 110.0 / number(vms[vmid]["mips"])
                allowance = 1.0 / number(vms[vmid]["mips"]) + tolerance(start, finish)
                audit.ensure(finish - start + allowance >= minimum, "stage-in-cpu-lower-bound", prefix, jid, minimum)

        matrix = config.get("taskCostMatrix")
        costs = {}
        if matrix is not None:
            for entry in needed(matrix, "entries"):
                key = (integer(needed(entry, "taskId"), "matrix task id", True), integer(needed(entry, "vmId"), "matrix vm id", True))
                audit.ensure(key not in costs, "duplicate-cost-coordinate", prefix, key)
                costs[key] = number(needed(entry, "executionSeconds"), "matrix seconds", positive=True)
            audit.ensure(set(costs) >= {(tid, vmid) for tid in logical_ids for vmid in vms}, "matrix-complete-coverage", prefix)
        seen_attempts = set()
        for task in task_outcomes:
            tid, jid = integer(needed(task, "taskId"), "task id", True), integer(needed(task, "jobId"), "task job id", True)
            audit.ensure((tid, jid) not in seen_attempts, "unique-task-attempt", prefix, [tid, jid])
            seen_attempts.add((tid, jid))
            if jid not in jobs_by_id:
                raise CheckError("task outcome has unknown Job")
            job = jobs_by_id[jid]
            vmid = integer(needed(job, "vmId"))
            mips = number(vms[vmid]["mips"], positive=True)
            audit.ensure(tid in job["taskIds"] and integer(needed(task, "vmId")) == vmid,
                         "task-job-vm-identity", prefix, [tid, jid])
            audit.ensure(integer(needed(task, "jobStatus")) == job["status"], "task-job-status", prefix, [tid, jid])
            raw_mi = integer(needed(task, "lengthMi"), "raw task MI", True)
            if tid in raw_tasks:
                audit.ensure(raw_mi == raw_tasks[tid]["lengthMi"], "raw-runtime-mi", prefix, [tid, raw_mi], raw_tasks[tid]["lengthMi"])
            if matrix is not None and (tid, vmid) not in costs:
                audit.problem("missing-effective-cost-coordinate", prefix, [tid, vmid])
                continue
            effective = java_round(costs[(tid, vmid)] * mips) if matrix is not None else raw_mi
            audit.ensure(0 < effective <= LONG_MAX // 1_000_000, "representable-effective-mi", prefix, [tid, effective])
            if not old or "effectiveExecutionLengthMi" in task:
                audit.ensure("effectiveExecutionLengthMi" in task, "missing-effective-mi-declaration", prefix, tid)
                if "effectiveExecutionLengthMi" in task:
                    audit.ensure(integer(task["effectiveExecutionLengthMi"], "effective MI", True) == effective, "effective-mi-declaration", prefix, [tid, task["effectiveExecutionLengthMi"]], effective)
            start, finish = number(needed(task, "startTime"), nonnegative=True), number(needed(task, "finishTime"), nonnegative=True)
            jstart, jfinish = number(job["startTime"]), number(job["finishTime"])
            expected_seconds = effective / mips
            allowance = 1.0 / mips + tolerance(start, finish, jstart, jfinish)
            audit.ensure(start + tolerance(start, jstart) >= jstart and finish <= jfinish + allowance and finish + tolerance(start, finish) >= start,
                         "task-window-within-job", prefix, {"taskId": tid, "jobId": jid, "start": start, "finish": finish, "jobStart": jstart, "jobFinish": jfinish}, allowance)
            audit.ensure(time_equal(finish - start, expected_seconds, start, finish), "effective-task-window", prefix, [tid, finish - start], expected_seconds)
            audit.ensure(jfinish - jstart + allowance >= expected_seconds + max(0.0, start - jstart), "cpu-lower-bound", prefix,
                         {"taskId": tid, "jobId": jid, "actualEnvelope": jfinish - jstart, "allowance": allowance}, expected_seconds + max(0.0, start - jstart))
            if needed(task, "exactJobTiming") is True:
                audit.ensure(time_equal(start, jstart) and time_equal(finish, jfinish), "exact-task-timing-claim", prefix, tid)
        expected_attempts = {(tid, j["jobId"]) for j in computes for tid in j["taskIds"]}
        audit.ensure(seen_attempts == expected_attempts, "task-attempt-coverage", prefix)
        execution_events = [e for e in events if e["type"] == "TASK_EXECUTION_MODELED"]
        event_attempts = collections.Counter()
        for event in execution_events:
            jid = integer(needed(event, "jobId"), "task event job id", True)
            attrs = needed(event, "attributes")
            tids = needed(event, "taskIds")
            for tid in event["taskIds"]:
                event_attempts[(tid, jid)] += 1
                matches = [t for t in task_outcomes if t["taskId"] == tid and t["jobId"] == jid]
                if len(matches) != 1:
                    audit.problem("task-event-outcome-identity", prefix, [tid, jid])
                    continue
                task = matches[0]
                for ek, tk in (("taskStartTime", "startTime"), ("taskFinishTime", "finishTime")):
                    time_metric(attrs, ek, number(task[tk]), prefix + ":task-event:" + str(tid))
                if not old:
                    audit.ensure(integer(needed(attrs, "effectiveExecutionLengthMi"), "event effective MI", True)
                                 == integer(needed(task, "effectiveExecutionLengthMi")), "task-event-effective-mi", prefix, tid)
        audit.ensure(set(event_attempts) == expected_attempts and all(n == 1 for n in event_attempts.values()), "task-event-coverage", prefix)
        for tid, node in graph.items():
            for job in jobs_by_task[tid]:
                for parent in needed(node, "parentIds"):
                    audit.ensure(parent in first_success and first_success[parent] <= number(job["startTime"]) + tolerance(first_success[parent], job["startTime"]),
                                 "dag-parent-before-child", prefix, [parent, tid, job["jobId"]])
                if tid in raw_tasks:
                    audit.ensure(number(job["startTime"]) + tolerance(job["startTime"], raw_tasks[tid]["arrival"]) >= raw_tasks[tid]["arrival"], "workflow-arrival-gate", prefix, tid)

        def field_values(rows, field):
            return [number(needed(row, field), field, nonnegative=True) for row in rows]
        waits, slow, ready_dec, dec_start, success_waits, success_slow = [], [], [], [], [], []
        for job in computes:
            jid = job["jobId"]
            if jid not in by_type["JOB_READY"] or jid not in by_type["JOB_RETURNED"] or jid not in by_type["SCHEDULING_DECISION"]:
                continue  # Explicit issues above make the run fail; never claim full coverage.
            ready = number(by_type["JOB_READY"][jid]["simulationTime"])
            decision = number(by_type["SCHEDULING_DECISION"][jid]["simulationTime"])
            start, finish = number(job["startTime"]), number(job["finishTime"])
            waiting = max(0.0, start - ready)
            slowdown = max(1.0, (waiting + max(0.0, finish - start)) / max(10.0, finish - start))
            waits.append(waiting); slow.append(slowdown)
            ready_dec.append(max(0.0, decision - ready)); dec_start.append(max(0.0, start - decision))
            if job["status"] == SUCCESS:
                success_waits.append(waiting); success_slow.append(slowdown)
        data = list(by_type["DATA_STAGE_IN_MODELED"].values())
        demand_values, transfer_seconds, file_counts = [], [], []
        for event in data:
            attrs = needed(event, "attributes")
            demand = number(needed(attrs, "requiredFileBytes"), "required bytes", nonnegative=True)
            seconds = number(needed(attrs, "modeledTransferSeconds"), "transfer seconds", nonnegative=True)
            count = integer(needed(attrs, "modeledTransferFileCount"), "input file count", True)
            demand_values.append(demand); transfer_seconds.append(seconds); file_counts.append(count)
            job = jobs_by_id[integer(event["jobId"])]
            if parsed_complete and len(job["taskIds"]) == 1:
                files = raw_tasks[job["taskIds"][0]]["files"]
                outputs = {n for n, k, _ in files if k == "output"}
                required = [size for n, k, size in files if k == "input" and n not in outputs]
                expected_demand = ordered_sum(required)
                audit.ensure(math.isclose(demand, expected_demand, rel_tol=0.0, abs_tol=1e-8)
                             and (expected_demand <= 0.0 or demand > 0.0), "raw-file-demand", prefix,
                             {"jobId": job["jobId"], "bytes": demand}, expected_demand)
                audit.ensure(count == len(required), "raw-demand-file-count", prefix, count, len(required))
        if parsed_complete:
            stage_events = list(by_type["STAGE_IN_JOB_CREATED"].values())
            audit.ensure(len(stage_events) == 1, "single-standard-stage-in", prefix, len(stage_events), 1)
            for event in stage_events:
                attrs = needed(event, "attributes")
                audit.metric(attrs, "inputFileCount", len(raw_external), prefix + ":stage-in-created")
                actual_bytes = number(needed(attrs, "inputBytes"), "stage-in input bytes", nonnegative=True)
                expected_bytes = ordered_sum(raw_external.values())
                audit.ensure(math.isclose(actual_bytes, expected_bytes, rel_tol=0.0, abs_tol=1e-8)
                             and (expected_bytes <= 0.0 or actual_bytes > 0.0), "raw-stage-in-bytes", prefix, actual_bytes, expected_bytes)
        total_costs = collections.defaultdict(list)
        for job in jobs:
            vm = vms[job["vmId"]]
            prices = needed(platform, "costs") if needed(config, "costModel") == "DATACENTER" else needed(vm, "costs")
            cpu = number(needed(prices, "cpuPerSecond"), "CPU price", nonnegative=True) * number(job["cpuTime"], nonnegative=True)
            declared_bytes = number(needed(job, "modeledDeclaredFileBytes"), "declared bytes", nonnegative=True)
            if parsed_complete:
                expected_bytes = ordered_sum(raw_external.values()) if job["classType"] == STAGE_IN else ordered_sum(
                    size for tid in job["taskIds"] for _, _, size in raw_tasks[tid]["files"])
                audit.ensure(math.isclose(declared_bytes, expected_bytes, rel_tol=0.0, abs_tol=1e-8), "raw-billable-file-bytes", prefix,
                             {"jobId": job["jobId"], "actual": declared_bytes}, expected_bytes)
            bandwidth_cost = number(needed(prices, "bandwidth"), "bandwidth price", nonnegative=True) * declared_bytes / 1_000_000.0
            for key, expected in (("modeledCpuEnvelopeCost", cpu), ("modeledDeclaredFileBandwidthCost", bandwidth_cost),
                                  ("modeledProcessingCost", cpu + bandwidth_cost)):
                audit.metric(job, key, expected, prefix + ":job:" + str(job["jobId"]))
                total_costs[key].append(expected)
            total_costs["modeledDeclaredFileBytes"].append(declared_bytes)
        retry_ids = {integer(e["jobId"]) for e in events if e["type"] == "RETRY_JOB_CREATED"}
        retry_jobs = [j for j in computes if j["jobId"] in retry_ids]
        failed_jobs = [j for j in computes if j["status"] == FAILED]
        complete = bool(logical_ids) and logical_ids <= set(first_success)
        completion = max(first_success.values()) if complete else None
        runtime = [max(0.0, number(j["finishTime"]) - number(j["startTime"])) for j in computes]
        vm_slows = [(number(j["finishTime"]) - number(j["submissionTime"])) / duration for j, duration in zip(computes, runtime) if duration > 0.001]
        derived.update(makespanSeconds=end, jobOutcomeCount=len(jobs), computeJobOutcomeCount=len(computes),
                       stageInJobOutcomeCount=len(jobs) - len(computes), successfulJobOutcomeCount=sum(j["status"] == SUCCESS for j in jobs),
                       failedJobOutcomeCount=sum(j["status"] == FAILED for j in jobs), successfulComputeJobOutcomeCount=sum(j["status"] == SUCCESS for j in computes),
                       failedComputeJobOutcomeCount=len(failed_jobs), logicalTaskCount=len(logical_ids),
                       successfullyCompletedLogicalTaskCount=len(first_success), logicalTasksNotYetSuccessfullyCompletedCount=len(logical_ids - set(first_success)),
                       allLogicalTasksCompletedSuccessfully=complete, logicalTaskCompletionSeconds=completion,
                       logicalTaskCompletionStatus="COMPLETED_SUCCESSFULLY" if complete else ("INCOMPLETE_LOGICAL_TASKS" if logical_ids else "NO_LOGICAL_TASKS"),
                       terminalLifecycleTailSeconds=max(0.0, end - completion) if complete else None,
                       initialComputeJobOutcomeCount=len(computes) - len(retry_jobs), retryJobCreatedCount=len(retry_ids),
                       completedRetryComputeJobOutcomeCount=len(retry_jobs), logicalTaskAttemptCount=sum(len(j["taskIds"]) for j in computes),
                       retriedLogicalTaskCount=len({tid for j in retry_jobs for tid in j["taskIds"]}),
                       failedComputeAttemptEnvelopeSeconds=math.fsum(number(j["finishTime"]) - number(j["startTime"]) for j in failed_jobs),
                       failedComputeAttemptModeledProcessingCost=math.fsum(number(j["modeledProcessingCost"]) for j in failed_jobs),
                       retryComputeAttemptModeledProcessingCost=math.fsum(number(j["modeledProcessingCost"]) for j in retry_jobs),
                       meanComputeJobRunTimeSeconds=mean(runtime), meanComputeTotalWaitingTimeSeconds=mean(waits), meanComputeTrueSlowdown=mean(slow),
                       totalWaitingTimeObservationCount=len(waits), trueSlowdownObservationCount=len(slow),
                       meanComputeReadyToDecisionDelaySeconds=mean(ready_dec), meanComputeDecisionToStartDelaySeconds=mean(dec_start),
                       readyToDecisionObservationCount=len(ready_dec), decisionToStartObservationCount=len(dec_start),
                       meanJobVmQueueWaitingTimeSeconds=mean([max(0.0, number(j["startTime"]) - number(j["submissionTime"])) for j in computes]),
                       meanJobResponseTimeSeconds=mean([max(0.0, number(j["finishTime"]) - number(j["submissionTime"])) for j in computes]),
                       meanJobVmLevelSlowdown=mean(vm_slows), vmLevelSlowdownObservationCount=len(vm_slows),
                       computeJobOutcomeThroughputPerSecond=len(computes) / end if end > 0 else 0.0,
                       successfulComputeJobOutcomeRate=sum(j["status"] == SUCCESS for j in computes) / len(computes) if computes else 0.0,
                       successfulLogicalTaskCompletionRate=len(first_success) / len(logical_ids) if logical_ids else 0.0,
                       retryAmplificationRatio=sum(len(j["taskIds"]) for j in computes) / len(logical_ids) if logical_ids else 0.0,
                       successOnlyMeanComputeTotalWaitingTimeSeconds=mean(success_waits), successOnlyMeanComputeTrueSlowdown=mean(success_slow),
                       successOnlyWaitingObservationCount=len(success_waits),
                       dataStageInModelObservationCount=len(data), modeledDataTransferFileCount=sum(file_counts),
                       totalModeledRequiredInputBytes=ordered_sum(demand_values), totalModeledDataTransferSeconds=ordered_sum(transfer_seconds),
                       meanModeledDataTransferSeconds=mean(transfer_seconds), schedulingCycleCount=sum(e["type"] == "SCHEDULING_CYCLE" for e in events),
                       explicitPlannerDecisionObservationCount=sum(e["type"] == "PLANNING_COMPLETED" and e["attributes"].get("explicitPlannerDecision") is True for e in events))
        for base, values in (("ComputeTotalWaitingTimeSeconds", waits), ("ComputeTrueSlowdown", slow)):
            for label, quantile in (("median", 0.5), ("p95", 0.95), ("max", 1.0)):
                derived[label + base] = percentile(values, quantile)
        for key, source in (("totalModeledProcessingCost", "modeledProcessingCost"), ("totalModeledCpuEnvelopeCost", "modeledCpuEnvelopeCost"),
                            ("totalModeledDeclaredFileBandwidthCost", "modeledDeclaredFileBandwidthCost"), ("totalModeledDeclaredFileBytes", "modeledDeclaredFileBytes")):
            derived[key] = math.fsum(total_costs[source])
        saved_vms = needed(metrics, "vmMetrics")
        audit.ensure(set(saved_vms) == {str(v) for v in vms}, "vm-metric-key-set", prefix)
        busy_values = []
        for vmid in vms:
            intervals = sorted(vm_intervals[vmid])
            previous_finish = None
            for start, finish in intervals:
                audit.ensure(previous_finish is None or start + tolerance(start, previous_finish) >= previous_finish,
                             "vm-overlap", prefix, {"vmId": vmid, "start": start, "previousFinish": previous_finish})
                previous_finish = max(finish, previous_finish if previous_finish is not None else finish)
            busy = union_length(intervals)
            busy_values.append(busy)
            saved = needed(saved_vms, str(vmid))
            vm_jobs = [j for j in jobs if j["vmId"] == vmid]
            for key, expected in (("vmId", vmid), ("jobOutcomeCount", len(vm_jobs)), ("modeledBusyIntervalSeconds", busy),
                                  ("modeledIntervalUtilization", busy / end if end > 0 else 0.0),
                                  ("reportedCpuTimeSeconds", math.fsum(number(j["cpuTime"]) for j in vm_jobs)),
                                  ("reportedCpuTimeOverMakespan", math.fsum(number(j["cpuTime"]) for j in vm_jobs) / end if end > 0 else 0.0)):
                audit.metric(saved, key, expected, prefix + ":VM:" + str(vmid))
        avg_busy = mean(busy_values)
        cv = math.sqrt(mean([(x - avg_busy) ** 2 for x in busy_values])) / avg_busy if avg_busy > 0 else 0.0
        fairness = math.fsum(busy_values) ** 2 / (len(busy_values) * math.fsum(x*x for x in busy_values)) if any(busy_values) else 1.0
        derived.update(totalVmModeledBusyIntervalSeconds=math.fsum(busy_values), meanVmModeledIntervalUtilization=avg_busy / end if end > 0 else 0.0,
                       vmModeledBusyTimeCoefficientOfVariation=cv, vmUtilizationJainFairnessIndex=fairness,
                       exactTaskTimingObservationCount=sum(t.get("exactJobTiming") is True for t in task_outcomes),
                       modeledApproximateTaskTimingObservationCount=sum(t.get("exactJobTiming") is not True for t in task_outcomes))
        for key, expected in derived.items():
            audit.metric(metrics, key, expected, prefix + ":metrics")
        coverage["checkedMetrics"] = sorted(derived)
        coverage["outsideMetricScope"] = sorted(set(metrics) - set(derived) - {"vmMetrics"})
        coverage["outsideMetricScopeNote"] = "Wall-clock timers, deadline/critical-path references, and fields not listed above are not certified."
    except (CheckError, OSError, ValueError, KeyError, TypeError, ArithmeticError) as error:
        audit.problem("invalid-or-unsupported-bundle", prefix, str(error))
        coverage["aborted"] = str(error)
    return {"manifest": prefix, "checks": audit.checks - before_checks, "issueCount": audit.issue_count - before_issues,
            "coverage": coverage, "recomputedMetrics": derived}


def audit_sources(paths, audit, historical=False, input_roots=()):
    results, cache, seen = [], {}, set()
    for source in paths:
        source = Path(source).resolve()
        try:
            doc = read_json(source)
            schema = needed(doc, "schema")
            if schema == "workflowsim-experiment-manifest-v4":
                manifests = [(source, None)]
            elif schema in ("workflowsim-network-study-v1", "workflowsim-workbench-experiment-v1"):
                rows = needed(doc, "runs")
                audit.ensure(bool(rows), "nonempty-study", str(source))
                planned = needed(doc["plan"], "runCount") if schema == "workflowsim-network-study-v1" else needed(doc, "plannedRuns")
                audit.ensure(len(rows) == integer(planned, "planned runs", True), "index-run-count", str(source), len(rows), planned)
                manifests = []
                for row in rows:
                    if row.get("status") != "COMPLETED_SUCCESSFULLY" or "manifest" not in row:
                        audit.problem("failed-or-unretained-index-run", str(source), row.get("runId", row.get("candidate")))
                        continue
                    manifests.append((contained_file(source.parent, row["manifest"]), row))
            else:
                raise CheckError("unsupported evidence input schema: " + str(schema))
            for manifest, row in manifests:
                if manifest in seen:
                    audit.problem("duplicate-manifest-input", str(manifest))
                    continue
                seen.add(manifest)
                results.append(inspect_manifest(manifest, audit, historical, input_roots, cache))
                if row is not None:
                    evidence = read_json(manifest)
                    checked = RUN_METRICS if schema == "workflowsim-network-study-v1" else {"makespanSeconds": "makespanSeconds"}
                    for key, metric_key in checked.items():
                        audit.metric(row, key, needed(evidence["metrics"], metric_key), str(source) + ":run")
                    audit.ensure(integer(needed(row, "seed"), "index seed") == integer(needed(evidence["configuration"], "rootSeed"), "rootSeed"),
                                 "index-seed", str(manifest))
        except (CheckError, OSError, ValueError, KeyError, TypeError) as error:
            audit.problem("invalid-source", str(source), str(error))
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="study/workbench index JSON or a single manifest")
    parser.add_argument("--output", required=True, type=Path, help="new JSON report outside all source study/bundle directories")
    parser.add_argument("--historical", action="store_true", help="explicitly permit old v4 model declarations; physics failures still fail")
    parser.add_argument("--input-root", action="append", default=[], type=Path, help="explicit relocation root for unavailable original inputs")
    parser.add_argument("--rel-tol", type=float, default=1e-10)
    parser.add_argument("--abs-tol", type=float, default=1e-8)
    parser.add_argument("--max-issues", type=int, default=200)
    args = parser.parse_args(argv)
    try:
        if not all(math.isfinite(v) and v >= 0 for v in (args.rel_tol, args.abs_tol)) or args.max_issues < 1:
            raise CheckError("tolerances must be finite/nonnegative and max-issues must be positive")
        output = protect_output(args.output, args.inputs)
        audit = Audit(args.rel_tol, args.abs_tol, args.max_issues)
        runs = audit_sources(args.inputs, audit, args.historical, args.input_root)
        report = {"schema": "workflowsim-independent-evidence-audit-v1", "scope": "RAW_EVIDENCE_AND_METRICS_NOT_SIMULATION_OR_NETWORK_REPLAY",
                  "tolerance": {"relative": args.rel_tol, "absolute": args.abs_tol,
                                "relativeScope": "metric/cost comparisons only, never absolute clocks",
                                "physicalTimeSeconds": "abs_tol + 4 * ulp(max absolute checked timestamp)",
                                "cpuQuantizationSeconds": "1 / assigned VM MIPS, added to physicalTimeSeconds"},
                  "inputs": list(map(str, args.inputs)), "runs": runs, **audit.report()}
        write_report(output, report)
        print("{}: {} bundles, {} issues; {}".format(report["status"], len(runs), audit.issue_count, output))
        return 1 if audit.issue_count else 0
    except (CheckError, OSError) as error:
        print("audit refused: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
