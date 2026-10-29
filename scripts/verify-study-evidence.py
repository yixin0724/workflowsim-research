#!/usr/bin/env python3
"""Independently audit saved WorkflowSim bundles; this is not a simulation/network replay."""
from __future__ import annotations

import argparse
import collections
import math
import sys
from fractions import Fraction
from decimal import Decimal
from pathlib import Path

from _study_audit import (Audit, CheckError, EXECUTION_SEMANTICS, LONG_MAX, RUN_METRICS,
                          contained_file, decode_json, digest, exact_equal, integer, java_round,
                          mean, needed, number, ordered_sum, parse_workflow, percentile,
                          protect_output, read_json, resolve_input, union_length, write_report)

SUCCESS, FAILED, COMPUTE, STAGE_IN = 4, 5, 2, 1
PRE_MODELS = {"PRE_EXECUTION_TRANSFER_DELAY_V1", "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1",
              "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"}


def tolerant_wallclock_nanos(events, event_type, attribute):
    """Sum a wall-clock nanos attribute exactly like SimulationMetrics:
    non-numeric values are skipped and numeric values truncate toward
    zero (Java Number.longValue()). Matched in test-study-verifiers.py."""
    total = 0
    for event in events:
        if event["type"] != event_type:
            continue
        value = events_attribute(event, attribute)
        # JSON 数值经 decode_json 解析为 int/Decimal；bool 与字符串不属 Java Number。
        if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
            continue
        total += int(value)
    return total


def events_attribute(event, attribute):
    attrs = event.get("attributes")
    return attrs.get(attribute) if isinstance(attrs, dict) else None


CS_SCOPE = "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED"
CS_OUTSIDE = "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE"


def critical_path_reference(config, platform, raw_tasks):
    """链 E（2026-10-20）：controlledSharedStorage 关键路径下界的独立复算。

    Java WorkflowModelReference.calculate 的 Python 等价：受控范围六条件 →
    最快 MIPS → stage-in（110 MI，含 SimulationTiming 保护）→ 拓扑遍历
    （每任务取全部 PE 兼容 VM 的最小信封秒数）→ lowerBound。全部
    UNAVAILABLE 分支语义与 Java 字面一致。
    """
    import math

    def unavailable(scope):
        return {"available": False, "scope": scope, "lowerBound": None}

    movement = config.get("dataMovementModel")
    movement_kind = movement.get("kind") if isinstance(movement, dict) else movement
    if movement_kind not in ("LEGACY_WORKFLOWSIM_V1", "FIXED_ENDPOINT_NO_CONTENTION_V1"):
        return unavailable(CS_OUTSIDE)
    clustering = config.get("clustering")
    if not isinstance(clustering, dict) or clustering.get("method") != "NONE":
        return unavailable(CS_OUTSIDE)
    failure = config.get("failureModel")
    if isinstance(failure, dict) and (failure.get("generatorMode") not in (None, "FAILURE_NONE")):
        return unavailable(CS_OUTSIDE)
    if str(config.get("fileSystem", "")).upper() != "SHARED":
        return unavailable(CS_OUTSIDE)
    overhead = config.get("overheadModel")
    if not isinstance(overhead, dict):
        return unavailable(CS_OUTSIDE)
    if (overhead.get("workflowEngineDelayInterval") not in (None, 0)
            or overhead.get("bandwidth") not in (None, 0.0)
            or bool(overhead.get("workflowEngineDelays"))
            or bool(overhead.get("queueDelays"))
            or bool(overhead.get("postDelays"))
            or bool(overhead.get("clusteringDelays"))):
        return unavailable(CS_OUTSIDE)
    storage = platform.get("storage")
    transfer_rate = storage.get("maxTransferRateMbPerSecond") if isinstance(storage, dict) else None
    if not transfer_rate or number(transfer_rate, "storage rate") <= 0:
        return unavailable(CS_OUTSIDE)
    vms = platform.get("vms")
    if not isinstance(vms, list) or not vms:
        return unavailable(CS_OUTSIDE)
    for vm in vms:
        if vm.get("schedulerMode") != "SPACE_SHARED" or number(vm.get("mips"), "vm mips") <= 0.0:
            return unavailable(CS_OUTSIDE)
    if not raw_tasks:
        return unavailable("UNAVAILABLE_NO_SOURCE_TASKS")

    vms = sorted(vms, key=lambda v: integer(v.get("id"), "vm id"))
    fastest_mips = max(number(v.get("mips"), "vm mips") for v in vms)

    min_interval = number(config.get("cloudSimMinEventIntervalSeconds", 0.1), "min interval")
    # SimulationTiming.earliestCloudletCompletionTime(0, 110/mips, minInterval)
    # = max(predicted, 0 + minInterval + 0.01)（Java 保护值 0.01），随后引擎再加 minInterval。
    stage_raw = 110.0 / fastest_mips
    stage_secs = max(stage_raw, min_interval + 0.01) + min_interval

    matrix = {}
    matrix_spec = config.get("taskCostMatrix")
    if isinstance(matrix_spec, dict):
        for ent in matrix_spec.get("entries", []):
            matrix[(integer(ent["taskId"], "matrix task"), integer(ent["vmId"], "matrix vm"))] = (
                number(ent["executionSeconds"], "matrix seconds"))

    finish_times = {}
    remaining = {tid: len(task["parents"]) for tid, task in raw_tasks.items()}
    ready = [tid for tid in sorted(raw_tasks) if remaining[tid] == 0]
    lower_bound = 0.0
    processed = 0
    while ready:
        tid = ready.pop(0)
        task = raw_tasks[tid]
        ready_time = stage_secs if not task["parents"] else max(
            finish_times[parent] for parent in task["parents"])
        # Java transferSeconds：任务文件表中真实外部输入（同任务同名输出不计）
        external_seconds = 0.0
        for name, kind, size in task["files"]:
            if kind != "input":
                continue
            if any(n2 == name and k2 == "output" for (n2, k2, _) in task["files"]):
                continue
            external_seconds += number(size, "file size") / (1_000_000.0 * number(transfer_rate, "rate"))
        if not math.isfinite(external_seconds) or external_seconds < 0.0:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        best = None
        unsupported = False
        for vm in vms:
            vm_id = integer(vm.get("id"), "vm id")
            mips = number(vm.get("mips"), "vm mips")
            # Java TaskExecutionModel.executionLengthMi：矩阵权威；缺坐标显式不可表示。
            if matrix:
                key = (tid, vm_id)
                if key not in matrix:
                    unsupported = True
                    break
                compute_mi = java_round(matrix[key] * mips)
                if compute_mi <= 0:
                    unsupported = True
                    break
            else:
                compute_mi = task["lengthMi"]
                if compute_mi <= 0:
                    return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
            # Java (long) cast is truncation toward zero, not rounding.
            transfer_mi = int(mips * external_seconds)
            envelope_mi = compute_mi + max(transfer_mi, 0)
            candidate = envelope_mi / mips
            if not math.isfinite(candidate) or candidate <= 0.0:
                return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
            if best is None or candidate < best:
                best = candidate
        if unsupported:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        if best is None:
            return unavailable("UNAVAILABLE_NO_COMPATIBLE_VM")
        finish_time = ready_time + best
        if not math.isfinite(finish_time) or finish_time < 0.0:
            return unavailable("UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST")
        finish_times[tid] = finish_time
        lower_bound = max(lower_bound, finish_time)
        processed += 1
        for child in sorted(task["children"]):
            remaining[child] -= 1
            if remaining[child] == 0:
                ready.append(child)
    if processed != len(raw_tasks):
        return unavailable("UNAVAILABLE_CYCLIC_SOURCE_TASK_GRAPH")
    return {"available": True, "scope": CS_SCOPE, "lowerBound": lower_bound}


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
        executed_job_ids, terminal_events = set(), []
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
                if typ in ("JOB_READY", "SCHEDULING_DECISION", "JOB_RETURNED", "DATA_STAGE_IN_MODELED",
                           "STAGE_IN_JOB_CREATED", "JOB_EXECUTION_STARTED"):
                    jid = integer(needed(event, "jobId"), "event jobId", True)
                    audit.ensure(set(event) >= {"attributes", "taskIds"}, "event-required-members", prefix)
                    audit.ensure(jid not in by_type[typ], "duplicate-" + typ, prefix, jid)
                    by_type[typ][jid] = event
                if typ == "TASK_EXECUTION_MODELED":
                    executed_job_ids.add(integer(needed(event, "jobId"), "event jobId", True))
                if typ == "SIMULATION_TERMINATED":
                    terminal_events.append(event)
                events.append(event)
        audit.ensure(len(events) == integer(needed(needed(doc, "events"), "eventCount"), "event count", True),
                     "event-count", prefix, len(events), doc["events"].get("eventCount"))
        # F-006 contract revision (2026-10-23): when the terminal event is present (revision
        # streams), the makespan family becomes event-recomputable; bind the clocks exactly.
        # Legacy streams without the event keep the declared manifest-authority boundary.
        if terminal_events:
            audit.ensure(len(terminal_events) == 1, "duplicate-SIMULATION_TERMINATED", prefix, len(terminal_events))
            terminal = terminal_events[0]
            clock = number(needed(terminal, "simulationTime"), "terminal event time", nonnegative=True)
            declared_end = number(needed(needed(terminal, "attributes"), "simulationEndSeconds"),
                                  "terminal simulationEndSeconds", nonnegative=True)
            audit.ensure(clock == end and declared_end == end, "terminal-clock-identity",
                         prefix + ":event:" + str(terminal.get("sequence")), [clock, declared_end], end)
            audit.ensure(events[-1]["type"] == "SIMULATION_TERMINATED", "terminal-event-position", prefix,
                         events[-1]["type"], "SIMULATION_TERMINATED")
        # F-007 contract revision (2026-10-23): each executed Job instance carries exactly one
        # JOB_EXECUTION_STARTED (uniqueness enforced by the typed family above); validate its
        # envelope/stage derivation and its coverage of every executed instance.
        started_jobs = by_type.get("JOB_EXECUTION_STARTED", {})
        for jid, event in started_jobs.items():
            attributes = needed(event, "attributes")
            envelope = number(needed(attributes, "jobEnvelopeStartSeconds"), "job envelope start", nonnegative=True)
            effective = number(needed(attributes, "effectiveDataStageSeconds"), "effective data stage", nonnegative=True)
            number(needed(attributes, "requestedDataStageSeconds"), "requested data stage", nonnegative=True)
            modeled = number(needed(attributes, "modeledJobStartSeconds"), "modeled job start", nonnegative=True)
            audit.ensure(modeled == envelope + effective, "job-start-derivation",
                         prefix + ":event:" + str(event.get("sequence")), modeled, [envelope, effective])
        if started_jobs or terminal_events:
            # Revision streams must cover every executed instance; legacy streams (neither
            # marker present) keep the declared manifest-authority boundary from 2026-10-19.
            audit.ensure(executed_job_ids.issubset(set(started_jobs)), "job-start-coverage", prefix,
                         sorted(set(started_jobs)), sorted(executed_job_ids))

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

        # ── BAT-D6（2026-10-29）：功率/频档平台面置前解析（任务窗与能量两路复用）──
        # 平面功率字段（BAT-E2）与频档表（BAT-D3）都在 Job 循环前就位：①逐 Job 频档
        # 身份的采集随 Job 循环进行（缺档=默认档/表首档；表外档=证据链断裂）；②任务窗
        # 复算的期望秒数改用本 Job 的指派档速（工作不变，时长 = work/档速——与 Java
        # WorkflowDatacenter.updateTaskExecTime 的 BAT-D6 口径严格同源；无档 VM 上的
        # Job 沿用 VM 声明速 = v1 逐位不变）。能量块沿用此处已建的三张表。
        vm_power = {}
        for vmid, vm in vms.items():
            idle = number(vm.get("idlePowerWatts", 0.0), "VM idlePowerWatts", nonnegative=True)
            peak = number(vm.get("maxPowerWatts", 0.0), "VM maxPowerWatts", nonnegative=True)
            if peak < idle:
                raise CheckError("maxPowerWatts below idlePowerWatts on VM " + str(vmid))
            vm_power[vmid] = (idle, peak)
        vm_tiers = {}            # vmid → [(mips, idle, max), ...]（声明降序）
        tier_max_by_vm = {}      # vmid → {档 mips → P_max}（档按 MIPS 唯一的身份索引）
        for vmid, vm in vms.items():
            raw_tiers = vm.get("frequencyTiers")
            if raw_tiers is None:
                continue
            if not isinstance(raw_tiers, list) or not raw_tiers:
                raise CheckError("VM frequencyTiers must be a non-empty list when declared (BAT-D3)")
            tiers = []
            for item in raw_tiers:
                if not isinstance(item, dict):
                    raise CheckError("frequencyTiers entries must be objects on VM " + str(vmid))
                tier_mips = number(needed(item, "mips"), "tier mips", positive=True)
                tier_idle = number(needed(item, "idlePowerWatts"), "tier idlePowerWatts", nonnegative=True)
                tier_max = number(needed(item, "maxPowerWatts"), "tier maxPowerWatts", nonnegative=True)
                if tier_max < tier_idle:
                    raise CheckError("tier maxPowerWatts below idlePowerWatts on VM " + str(vmid))
                if tiers and tier_mips >= tiers[-1][0]:
                    raise CheckError(
                        "frequencyTiers must be strictly descending by mips on VM " + str(vmid)
                        + " (BAT-D1 表序契约)")
                tiers.append((tier_mips, tier_idle, tier_max))
            if vm_power[vmid][0] > 0.0 or vm_power[vmid][1] > 0.0:
                raise CheckError("VM " + str(vmid)
                                 + " declares BOTH frequencyTiers and plain power fields — one source of"
                                 + " truth required (BAT-D1)")
            vm_tiers[vmid] = tiers
            tier_max_by_vm[vmid] = {t[0]: t[2] for t in tiers}
        tier_of_job = {}          # jobId → 档 MIPS（频档平台上的全部 Job——含簿记信封）
        tier_intervals_by_vm = {}  # vmid → {档 mips → [(start, finish), ...]}
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
            # BAT-D6（2026-10-29）：逐 Job 频档身份采集（原 BAT-D3 能量块内的重建规则
            # 原样上移）：无档 VM 上的 Job 不得携带 tierMips；有档 VM 上的 Job 缺档归
            # 默认档（表首档）——簿记信封/v1 兼容同口径；表外档 = 证据链断裂。
            tiers = vm_tiers.get(vmid)
            raw = job.get("tierMips")  # 可选键；null/缺键 = v1 兼容/簿记信封
            if tiers is None:
                if raw is not None:
                    raise CheckError("job " + str(jid) + " on non-tiered VM " + str(vmid)
                                     + " carries tierMips: the per-Job tier evidence chain is"
                                     " broken (BAT-D3)")
            else:
                if raw is None:
                    tier_mips = tiers[0][0]  # 缺档归默认档（表首档）——Java 台账同口径
                else:
                    tier_mips = number(raw, "job tierMips", positive=True)
                    if tier_mips not in tier_max_by_vm[vmid]:
                        raise CheckError(
                            "job " + str(jid) + " carries tier mips " + str(raw)
                            + " which matches no declared frequency tier of VM " + str(vmid)
                            + ": the per-Job tier evidence chain is broken (BAT-D2)")
                tier_of_job[jid] = tier_mips
                buckets = tier_intervals_by_vm.setdefault(vmid, {})
                buckets.setdefault(tier_mips, []).append((start, finish))
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
            # BAT-D6（2026-10-29）：期望窗秒数用执行速 = 本 Job 的指派档速（有档 VM 上
            # 的 Job 必有 tier_of_job 记录；无档 Job/v1 沿用 VM 声明速）——与 Java 侧
            # 任务窗模型（updateTaskExecTime 取 job.getTierMips()）及 Job 云let 长度的
            # 档速缩放（时长 = work/档速）同律。量化容差口径（1/VM MIPS）不变：Job
            # 云let 仍在 VM 声明速上执行（缩放后的长度）。
            expected_seconds = effective / (tier_of_job[jid] if jid in tier_of_job else mips)
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
                       explicitPlannerDecisionObservationCount=sum(e["type"] == "PLANNING_COMPLETED" and e["attributes"].get("explicitPlannerDecision") is True for e in events),
                       totalSchedulingDecisionWallClockNanos=tolerant_wallclock_nanos(events, "SCHEDULING_CYCLE", "decisionElapsedNanos"),
                       totalPlanningDecisionWallClockNanos=tolerant_wallclock_nanos(
                           [e for e in events if e["type"] == "PLANNING_COMPLETED"
                            and e["attributes"].get("explicitPlannerDecision") is True],
                           "PLANNING_COMPLETED", "planningDecisionElapsedNanos"))
        for base, values in (("ComputeTotalWaitingTimeSeconds", waits), ("ComputeTrueSlowdown", slow)):
            for label, quantile in (("median", 0.5), ("p95", 0.95), ("max", 1.0)):
                derived[label + base] = percentile(values, quantile)
        for key, source in (("totalModeledProcessingCost", "modeledProcessingCost"), ("totalModeledCpuEnvelopeCost", "modeledCpuEnvelopeCost"),
                            ("totalModeledDeclaredFileBandwidthCost", "modeledDeclaredFileBandwidthCost"), ("totalModeledDeclaredFileBytes", "modeledDeclaredFileBytes")):
            derived[key] = math.fsum(total_costs[source])
        saved_vms = needed(metrics, "vmMetrics")
        audit.ensure(set(saved_vms) == {str(v) for v in vms}, "vm-metric-key-set", prefix)
        busy_values = []
        busy_by_vm = {}
        for vmid in vms:
            intervals = sorted(vm_intervals[vmid])
            previous_finish = None
            for start, finish in intervals:
                audit.ensure(previous_finish is None or start + tolerance(start, previous_finish) >= previous_finish,
                             "vm-overlap", prefix, {"vmId": vmid, "start": start, "previousFinish": previous_finish})
                previous_finish = max(finish, previous_finish if previous_finish is not None else finish)
            busy = union_length(intervals)
            busy_values.append(busy)
            busy_by_vm[vmid] = busy
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
        # BAT-E2（2026-10-27）：能耗守恒律复算（Beloglazov FGCS 2012 Eq.(2) 二元 SPACE_SHARED
        # 特化：E_vm = P_idle×T_sim + (P_max−P_idle)×T_busy；无功率字段的 VM 贡献为 0；
        # 旧 manifest 无 energyMetrics 键 → 每台 VM 全零 → 总耗能必须为 0。
        # BAT-D6（2026-10-29）：vm_power/vm_tiers/tier_of_job 已在 Job 循环前置解析（任务
        # 窗与能量两路同源），此处直接消费。
        expected_vm_energy = {}
        expected_total_energy = 0.0
        expected_peak_power = 0.0
        for vmid, (idle, peak) in vm_power.items():
            e = idle * end + (peak - idle) * busy_by_vm[vmid]
            expected_vm_energy[vmid] = e
            expected_total_energy += e
            expected_peak_power += peak
        powered = any(p[0] > 0.0 or p[1] > 0.0 for p in vm_power.values())
        # ── BAT-D3（2026-10-29）：DVFS 逐档能量复算（DVFS_DIMENSION_DESIGN.md §2.3/§2.4）──
        # 平台节的可选键 platform.vms[].frequencyTiers（{mips,idlePowerWatts,maxPowerWatts}
        # 表序降序；缺键/空表 = v1 单档平台 = 未声明）。任一 VM 声明频档 → DVFS 模式：
        # 声明 VM 改用扩展守恒律
        #   E_vm = P_idle(默认档/表首档)×T_sim + Σ档[(P_max(档)−P_idle(默认档))×busy(档)]
        # （代数恒等于 idle(默认)×T_idle + Σ档 P_max(档)×busy(档)——逐 Job 一个恒功率
        # 矩形，与 Java EnergyAccounting 的 BAT-D2 扩展分支同律）。逐档忙秒由审计自身
        # 的 [start,finish) Job 区间按 Job 携带的 tierMips 分组重建：缺档（v1 兼容运行/
        # stage-in 簿记信封）归默认档；每 VM 逐档取该档区间的并集（SPACE_SHARED 串行
        # 下与 Java 台账逐档 executionTime 之和同值）；逐档之和必须覆盖该 VM 总忙并集
        #（1e-9 秒容差，镜像 Java TIER_SUM_SLACK_SECONDS 的证据链条款）。
        # 无任何频档声明 → 本块整体短路，审计行为与 D3 前逐位一致（v1 合法缺省）。
        # BAT-D6（2026-10-29）：vm_tiers/tier_max_by_vm/tier_of_job/tier_intervals_by_vm
        # 已在 Job 循环前置解析/采集（任务窗复算同用），此处原样消费，不再重复构建。
        if vm_tiers:
            # 频档声明 = 功率声明（镜像 Java BAT-D2 对 D1 死闸的修复：平面字段或频档表
            # 任一声明即 powered——tiered VM 的平面功率字段恒 0，单一真源互斥）。
            powered = True
            # 逐档守恒律复算：整体替换 v1 复算值（flat VM 保持同式同算——两路真源互斥）。
            expected_vm_energy = {}
            expected_total_energy = 0.0
            expected_peak_power = 0.0
            all_at_default_total = 0.0
            nondefault_busy_observed = False
            for vmid, (idle, peak) in vm_power.items():
                tiers = vm_tiers.get(vmid)
                if tiers is None:
                    e = idle * end + (peak - idle) * busy_by_vm[vmid]
                    expected_peak_power += peak
                    all_at_default_total += e
                else:
                    default_mips, default_idle, default_max = tiers[0]
                    # MIPS 升序 = 确定性求和序（镜像 Java TreeMap；AGENTS #9）。
                    ordered_tiers = sorted(
                        (m, union_length(w)) for m, w in tier_intervals_by_vm.get(vmid, {}).items())
                    attributed = ordered_sum([busy for _, busy in ordered_tiers])
                    if abs(attributed - busy_by_vm[vmid]) > 1e-9:
                        raise CheckError(
                            "per-tier busy seconds of VM " + str(vmid) + " (sum=" + str(attributed)
                            + ") do not equal its total modeled busy interval ("
                            + str(busy_by_vm[vmid]) + "): the per-tier conservation evidence chain"
                            + " is broken (BAT-D2)")
                    e = default_idle * end
                    for tier_mips, tier_busy in ordered_tiers:
                        e += (tier_max_by_vm[vmid][tier_mips] - default_idle) * tier_busy
                        if tier_mips != default_mips and tier_busy > 0.0:
                            nondefault_busy_observed = True
                    expected_peak_power += default_max
                    all_at_default_total += (default_idle * end
                                             + (default_max - default_idle) * busy_by_vm[vmid])
                expected_vm_energy[vmid] = e
                expected_total_energy += e
            # 诚实观察（非断言，D3 任务契约）：任何落在非默认档的忙秒都使逐档律偏离
            # D1"整段默认档"口径；全默认档运行（DEFAULT 策略/缺档归因）两律恒等——
            # 两条都是台账可证的性质，审计不把"存在分档"升级为断言（守恒律断言兜底）。
            audit.notes.append({
                "code": "dvfs-tier-accounting-observation",
                "perTierTotalEnergyJoules": expected_total_energy,
                "allAtDefaultEnergyJoules": all_at_default_total,
                "nonDefaultTierBusyObserved": nondefault_busy_observed,
            })
        energy_doc = doc.get("energyMetrics")
        if powered and energy_doc is None:
            raise CheckError("powered VMs require an energyMetrics section")
        if powered:
            for key, expected in (("totalEnergyJoules", expected_total_energy),
                                  ("meanVmEnergyJoules", expected_total_energy / len(vm_power)),
                                  ("peakPowerWatts", expected_peak_power),
                                  ("energyMakespanProductJouleSeconds", expected_total_energy * end)):
                audit.metric(energy_doc, key, expected, prefix + ":energyMetrics")
            stored_per_vm = needed(energy_doc, "vmEnergyJoules")
            for vmid, expected in expected_vm_energy.items():
                audit.metric(stored_per_vm, str(vmid), expected, prefix + ":energyMetrics:VM:" + str(vmid))
        # ── BAT-C2（2026-10-28）：碳维度复算（CARBON_DIMENSION_DESIGN.md §2.2 两族模型）──
        # 声明→必查、未声明→跳过（legacy manifest 不受影响）；两种模型互斥（BAT-C1 条款）。
        # 两族都骑在“已过能耗守恒律”的量上：强度模型直接乘 audited E（expected_vm_energy），
        # 预算模型由同一批计算 Job 区间重建舰队功率时间线 —— 物理同源，无独立漂移面。
        # 范围诚实说明：强度分支复算 grams 族字段、预算分支复算 excess 族字段；另一族在
        # 写端恒为零（ExperimentManifestWriter 仅在 accounting 非空时导出碳节），不逐字段
        # 复算常量零 —— 护漂移的锚点在 C5 注册演示器的集成测试与量纲恒等式。
        vm_intensities = {}
        for vmid, vm in vms.items():
            raw = vm.get("carbonIntensityGramsPerKWh")  # 可选键；null/缺键=未声明（legacy 语义）
            if raw is not None:
                vm_intensities[vmid] = number(raw, "VM carbonIntensityGramsPerKWh", nonnegative=True)
        raw_windows = platform.get("carbonWindows")
        if raw_windows is not None and not isinstance(raw_windows, list):
            raise CheckError("platform.carbonWindows must be a list of window objects")
        carbon_windows = []
        for window in raw_windows or []:
            if not isinstance(window, dict):
                raise CheckError("carbonWindows entries must be objects")
            wstart = number(needed(window, "startSeconds"), "window startSeconds", nonnegative=True)
            wend = number(needed(window, "endSeconds"), "window endSeconds", nonnegative=True)
            green = number(needed(window, "greenWatts"), "window greenWatts", nonnegative=True)
            if wend <= wstart:
                raise CheckError("carbon window endSeconds must exceed startSeconds: " + str(window))
            carbon_windows.append((wstart, wend, green))
        for earlier, later in zip(carbon_windows, carbon_windows[1:]):
            # 镜像 Java PlatformProfile.carbonWindow 构建时校验（排序、不重叠）。
            if later[0] < earlier[1]:
                raise CheckError("carbonWindows must be sorted and non-overlapping: window start "
                                 + str(later[0]) + " overlaps previous end " + str(earlier[1]))
        if vm_intensities and carbon_windows:
            # BAT-C1 互斥条款：Java SimulationReport 构造期同语义拒绝（低强度电网与绿电预算
            # 平台会给出相互矛盾的“更绿”调度，模型不可互换 —— 设计文档 §1.3）。
            raise CheckError("Both carbon models declared (per-VM carbonIntensityGramsPerKWh AND"
                             " carbonWindows) — the two accountings are mutually exclusive by contract (BAT-C1)")
        carbon_doc = doc.get("carbonMetrics")
        if not vm_intensities and not carbon_windows:
            # 未声明任何碳模型却携带 carbonMetrics 节 → 伪造面（Java 写出器只在声明时导出）。
            audit.ensure(carbon_doc is None, "carbon-metrics-without-declared-carbon-model",
                        prefix + ":carbonMetrics")
        if vm_intensities:
            # 强度模型（CASPER 族）：grams_vm = E_vm[J] / 3.6e6 × I_vm[g/kWh]（kWh = J/3.6e6）。
            # 半声明即 fail-fast：镜像 Java CarbonAccounting.intensityModel 的每 VM 完整
            # 声明要求（每台 VM 都要功率+强度），契约破坏而非零贡献。
            # BAT-D3：频档 VM 视为已声明功率（其能量已按逐档律复算进 expected_vm_energy，
            # 强度模型直接乘同一能量数——物理同源"ride-through"，无独立复算面）。
            unpowered = [vmid for vmid, (idle, peak) in vm_power.items()
                         if not (idle > 0.0 or peak > 0.0) and vmid not in vm_tiers]
            half_declared = [vmid for vmid in vms if vmid not in vm_intensities]
            if unpowered or half_declared:
                raise CheckError("Intensity carbon model requires powered VMs with declared"
                                 " carbonIntensityGramsPerKWh on every VM (BAT-C1); unpowered VMs: "
                                 + str(unpowered) + "; VMs missing the intensity: " + str(half_declared))
            if carbon_doc is None:
                raise CheckError("platform carbon intensity declarations require a carbonMetrics"
                                 " section (BAT-C2 declared->required)")
            expected_vm_grams = {}
            total_grams = 0.0
            for vmid, intensity in vm_intensities.items():
                grams = expected_vm_energy[vmid] / 3.6e6 * intensity
                expected_vm_grams[vmid] = grams
                total_grams += grams
            for key, expected in (("totalCarbonGrams", total_grams),
                                  ("meanVmCarbonGrams", total_grams / len(vm_power)),
                                  ("carbonMakespanProductGramSeconds", total_grams * end)):
                audit.metric(carbon_doc, key, expected, prefix + ":carbonMetrics")
            stored_carbon_vm = needed(carbon_doc, "perVmCarbonGrams")
            for vmid, expected in expected_vm_grams.items():
                audit.metric(stored_carbon_vm, str(vmid), expected,
                             prefix + ":carbonMetrics:VM:" + str(vmid))
        elif carbon_windows:
            # 预算模型（CWM/CaWoSched 族）：从计算 Job（classType 2）区间端点折叠舰队并发
            # 时间线 —— 与 Java CarbonAccounting.budgetModel 逐句同口径（端点合并 +1/−1，
            # 段内并发数恒定）；P(t) = Σ idle + concurrent × ΔP，ΔP 取首台 VM 的 max−idle
            # （v1 舰队动态功率均一假设，设计文档 §2.1 诚实边界 —— 归因见该文档 §3）。
            if not powered:
                raise CheckError("carbonWindows declarations require platform power fields"
                                 " (idlePowerWatts/maxPowerWatts); the fleet-power curve cannot be"
                                 " built on an unpowered profile (BAT-C1)")
            if carbon_doc is None:
                raise CheckError("platform carbonWindows declarations require a carbonMetrics"
                                 " section (BAT-C2 declared->required)")
            idle_sum = 0.0
            if vm_tiers:
                # BAT-D3：频档平台的舰队功率时间线。每台 VM 的静态分量 = P_idle(默认档)
                #（卡顿频率的怠速功率，与守恒律同源）；活跃 Job 以其档的动态分量
                #(P_max(其档) − P_idle(默认档)) 叠加——端点折叠的 ±1 计数改为 ±该 Job
                # 动态瓦特，段内总功率 = Σ idle(默认档) + Σ(活跃档动态) 逐时刻恒等于
                # Σ卡顿 VM P_max(其档) + Σ闲置 VM P_idle(默认档)（DVFS_DIMENSION_
                # DESIGN.md §1.5 "per-tier fleet-power curve"）。档身份 = 逐档守恒块
                # 已校验过的 tierMips 归因（同一归因，一次校验）；flat VM 保持
                # max−idle 的 v1 贡献（混合舰队合法）。Java 端（CarbonAccounting
                # .budgetModel）v1 只认平面功率字段、频档平台在报告构造期即被拒绝
                # ——本分支是 D3 前向口径，真机 manifest 尚不能同时携带两者（Java
                # 侧对齐落在后续批次；自测以手推台账锚定本分支）。
                for vmid, (idle, _peak) in vm_power.items():  # platform 序列化序：镜像 Java profile 求和序
                    idle_sum += vm_tiers[vmid][0][1] if vmid in vm_tiers else idle
                fleet = {}
                for job in computes:  # computes = classType 2 的全部 Job（忙区间只由计算 Job 定义）
                    a = number(job["startTime"], "job start", nonnegative=True)
                    b = number(job["finishTime"], "job finish", nonnegative=True)
                    if b > a:
                        vmid = integer(job["vmId"], "job vmId", True)
                        if vmid in vm_tiers:
                            job_tier = tier_of_job[job["jobId"]]  # 逐档守恒块已完成归因与校验
                            dyn = tier_max_by_vm[vmid][job_tier] - vm_tiers[vmid][0][1]
                        else:
                            dyn = vm_power[vmid][1] - vm_power[vmid][0]
                        fleet[a] = fleet.get(a, 0.0) + dyn
                        fleet[b] = fleet.get(b, 0.0) - dyn
                segments = []
                dynamic = 0.0
                prev = 0.0
                for t in sorted(fleet):
                    if t > prev:
                        segments.append((prev, t, idle_sum + dynamic))
                    dynamic += fleet[t]
                    prev = t
                if prev < end:
                    segments.append((prev, end, idle_sum + dynamic))
            else:
                for idle, _peak in vm_power.values():  # platform 序列化序：镜像 Java profile 求和序
                    idle_sum += idle
                # ΔP 取首台 VM 的 max−idle（BAT-C2 与 Java 对齐口径）：v1 假设舰队动态功率均一
                # （设计文档 §2.1 诚实边界）；异质舰队下取首/末台会漂移，两侧统一取首台。
                first_idle, first_peak = next(iter(vm_power.values()))
                dynamic_per_vm = first_peak - first_idle
                fleet = {}
                for job in computes:  # computes = classType 2 的全部 Job（忙区间只由计算 Job 定义）
                    a = number(job["startTime"], "job start", nonnegative=True)
                    b = number(job["finishTime"], "job finish", nonnegative=True)
                    if b > a:
                        fleet[a] = fleet.get(a, 0) + 1
                        fleet[b] = fleet.get(b, 0) - 1
                segments = []
                concurrent = 0
                prev = 0.0
                for t in sorted(fleet):
                    if t > prev:
                        segments.append((prev, t, idle_sum + concurrent * dynamic_per_vm))
                    concurrent += fleet[t]
                    prev = t
                if prev < end:
                    segments.append((prev, end, idle_sum + concurrent * dynamic_per_vm))
            per_window_excess = []
            total_excess = 0.0
            for wstart, wend, green in carbon_windows:
                excess = 0.0
                for lo, hi, power in segments:
                    a = max(lo, wstart)
                    b = min(hi, wend)
                    if b > a:
                        excess += max(0.0, power - green) * (b - a)
                per_window_excess.append(excess)
                if excess > 0.0:  # Java 只在正超额时累计（max(0,·) 保证非负）
                    total_excess += excess
            audit.metric(carbon_doc, "totalCarbonExcessWattSeconds", total_excess,
                         prefix + ":carbonMetrics")
            stored_windows = needed(carbon_doc, "windowExcessWattSeconds")
            for index, excess in enumerate(per_window_excess):
                key = str(index)
                if excess > 0.0 or key in stored_windows:
                    # Java 只落正超额窗口；>0 的必须精确对上，单独出现的 0 值键也要对上 0。
                    audit.metric(stored_windows, key, excess,
                                 prefix + ":carbonMetrics:window:" + key)
            for key in stored_windows:
                if not (key.isdigit() and int(key) < len(carbon_windows)):
                    audit.problem("invalid-window-index", prefix + ":carbonMetrics:windowExcessWattSeconds", key)
            window_sum = math.fsum(number(value, "window excess", nonnegative=True)
                                   for value in stored_windows.values())
            saved_total = number(needed(carbon_doc, "totalCarbonExcessWattSeconds"),
                                "totalCarbonExcessWattSeconds", nonnegative=True)
            audit.ensure(math.isclose(window_sum, saved_total, rel_tol=audit.rel_tol, abs_tol=audit.abs_tol),
                         "carbon-window-sum-equals-total", prefix + ":carbonMetrics:windowExcessWattSeconds",
                         window_sum, saved_total)
        derived.update(totalVmModeledBusyIntervalSeconds=math.fsum(busy_values), meanVmModeledIntervalUtilization=avg_busy / end if end > 0 else 0.0,
                       vmModeledBusyTimeCoefficientOfVariation=cv, vmUtilizationJainFairnessIndex=fairness,
                       exactTaskTimingObservationCount=sum(t.get("exactJobTiming") is True for t in task_outcomes),
                       modeledApproximateTaskTimingObservationCount=sum(t.get("exactJobTiming") is not True for t in task_outcomes))
        # 审查工程链 E（2026-10-20）：deadline 六字段独立复算（Java Metrics L486-498
        # 精确语义：BigDecimal.valueOf(long) − new BigDecimal(makespan)；Python 用
        # Fraction 的等价闭环，含正负号分支与 NOT_REQUESTED/MISSED_INCOMPLETE_WORKFLOW）。
        deadline_seconds = config.get("deadline")
        deadline_threshold = 0 if deadline_seconds is None else int(deadline_seconds)
        if deadline_threshold > 0:
            frac_deadline = Fraction(deadline_threshold)
            frac_make = Fraction(end)
            slack_frac = frac_deadline - frac_make
            slack_double = float(slack_frac)
        else:
            slack_frac = Fraction(0)
            slack_double = 0.0
        workflow_completed = metrics.get("logicalTaskCompletionStatus") == "COMPLETED_SUCCESSFULLY"
        if deadline_threshold <= 0:
            deadline_outcome = "NOT_REQUESTED"
        elif not workflow_completed:
            deadline_outcome = "MISSED_INCOMPLETE_WORKFLOW"
        else:
            deadline_outcome = "MET" if slack_frac >= 0 else "MISSED_LATE"
        derived.update(deadlineObservationEnabled=deadline_threshold > 0,
                       deadlineObservationOutcome=deadline_outcome,
                       deadlineObservationTimeBasis="SIMULATION_END_SECONDS",
                       deadlineMet=deadline_outcome == "MET",
                       deadlineSlackSeconds=slack_double,
                       deadlineTardinessSeconds=max(0.0, -slack_double))
        # ── 链 E 尾项（2026-10-20）：controlledSharedStorage 4 项独立复算 ──
        # Java WorkflowModelReference.calculate 的 Python 等价：受控范围检查 →
        # 最快 MIPS → 110 MI stage-in → 拓扑遍历（与 raw_tasks lengthMi 一致的
        # 明细模型），全部 UNAVAILABLE 分支语义对齐。
        cp = critical_path_reference(config, platform, raw_tasks)
        derived.update(controlledSharedStorageCriticalPathReferenceAvailable=cp["available"],
                       controlledSharedStorageCriticalPathReferenceScope=cp["scope"])
        if cp["available"]:
            derived["controlledSharedStorageCriticalPathLowerBoundSeconds"] = cp["lowerBound"]
            derived["controlledSharedStorageScheduleLengthRatio"] = (
                end / cp["lowerBound"] if cp["lowerBound"] > 0.0 else 0.0)
        else:
            derived["controlledSharedStorageCriticalPathLowerBoundSeconds"] = 0.0
            derived["controlledSharedStorageScheduleLengthRatio"] = 0.0

        for key, expected in derived.items():
            audit.metric(metrics, key, expected, prefix + ":metrics")
        coverage["checkedMetrics"] = sorted(derived)
        coverage["outsideMetricScope"] = sorted(set(metrics) - set(derived) - {"vmMetrics"})
        coverage["outsideMetricScopeNote"] = ("Decision wall-clock nanos + deadline family are recomputed "
                                              "(审查工程 C10 + 链 E, 2026-10-19/20); "
                                              "critical-path references and fields not listed above remain uncertified.")
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
