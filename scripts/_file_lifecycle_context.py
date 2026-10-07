"""Independent manifest-v4 joins for V2 file-lifecycle evidence; stdlib only.

No Java, simulator, allocator, V1 grouping formulas, external workflow inputs,
or production replay is used. The lifecycle verifier supplies validated facts;
this module joins them to the declared core plan, platform, final attempts and
main events. It checks internal consistency, not authentication or fluid service.
OFF validates the core/artifact context but cannot certify a lifecycle.
"""
from __future__ import annotations

from collections import Counter
from decimal import Decimal
import hashlib
import math
import os
from pathlib import Path
import sys

from _network_audit import (CheckError as Invalid, array, decode_json, f64, keys,
                            need, num, read_document, required, whole, word)
from _file_lifecycle_audit import ISOLATED, SHARED, MODE, _Plan, _file_id, verify_document

MANIFEST = "workflowsim-experiment-manifest-v4"
INT_MAX, LONG_MAX = (1 << 31) - 1, (1 << 63) - 1
COMPUTE, SUCCESS, FAILED = 2, 4, 5
MAIN_TYPES = {"JOB_READY", "DATA_STAGE_IN_MODELED", "SCHEDULING_DECISION", "JOB_DISPATCHED",
              "TASK_EXECUTION_MODELED", "JOB_RETURNED", "JOB_FAILED", "RETRY_JOB_CREATED"}
METRIC_FIELDS = {"makespanSeconds", "meanJobVmQueueWaitingTimeSeconds", "meanJobVmLevelSlowdown",
                 "meanJobResponseTimeSeconds", "meanComputeTotalWaitingTimeSeconds", "meanComputeTrueSlowdown"}


def _obj(value):
    need(type(value) is dict, "expected context JSON object")
    return value


def _field(obj, name):
    return required(_obj(obj), name)


def _id(value):
    return whole(value, 0, INT_MAX)


def _time(value):
    return f64(value, nonnegative=True)


def _bool(value):
    need(type(value) is bool, "expected context JSON boolean")
    return value


def _rows(value, key):
    result = {}
    for row in array(value):
        ident = _id(_field(row, key))
        need(ident not in result, "duplicate " + key)
        result[ident] = row
    return result


def _ids(value):
    result = [_id(item) for item in array(value)]
    need(len(set(result)) == len(result), "duplicate context identity")
    return result


def _same(a, b, subject):
    """Exact JSON numeric equality, without bool/int or float-rounding aliases."""
    numeric = (int, float, Decimal)
    if type(a) in numeric and type(b) in numeric:
        need(num(a) == num(b), subject)
    elif type(a) is dict and type(b) is dict:
        need(set(a) == set(b), subject)
        for key in a:
            _same(a[key], b[key], subject)
    elif type(a) is list and type(b) is list:
        need(len(a) == len(b), subject)
        for left, right in zip(a, b):
            _same(left, right, subject)
    else:
        need(type(a) is type(b) and a == b, subject)


def _text_is(obj, key, expected):
    need(word(_field(obj, key)) == expected, "unsupported/mismatched " + key)


def _core(root, *, models=(SHARED, ISOLATED), filesystems=("LOCAL",)):
    """Private shared graph/scope contract; public V2 callers use fixed defaults."""
    _text_is(root, "schema", MANIFEST)
    config, result = _obj(_field(root, "configuration")), _obj(_field(root, "result"))
    model = word(_field(_field(config, "dataMovementModel"), "kind"))
    need(model in models, "dataflowPlan/lifecycle requires its coherent model contract")
    from _dataflow_assignment_audit import _configuration
    online = _configuration(root)
    for key, value in (("planningAlgorithm", "INVALID" if online else "RANDOM"), ("schedulingAlgorithm", "STATIC")):
        _text_is(config, key, value)
    need(word(_field(config, "fileSystem")) in filesystems, "unsupported fileSystem")
    _text_is(_field(config, "clustering"), "method", "NONE")
    overhead = _obj(_field(config, "overheadModel"))
    keys(overhead, ("workflowEngineDelayInterval", "bandwidth", "workflowEngineDelays", "queueDelays", "postDelays", "clusteringDelays"), "overheadModel")
    need(whole(overhead["workflowEngineDelayInterval"]) == 0 and _time(overhead["bandwidth"]) == 0,
         "V2 overhead is unsupported")
    for key in ("workflowEngineDelays", "queueDelays", "postDelays", "clusteringDelays"):
        need(not _obj(overhead[key]), "V2 overhead maps must be empty")
    failure = _obj(_field(config, "failureModel"))
    _text_is(failure, "clusteringAlgorithm", "FTCLUSTERING_NOOP")
    _text_is(failure, "monitorMode", "MONITOR_NONE")
    plan = _Plan(_field(root, "dataflowPlan"))
    graph = _rows(_field(root, "workflowGraph"), "taskId")
    need(set(graph) == set(plan.tasks), "core plan/workflowGraph Task coverage mismatch")
    children = {tid: set() for tid in plan.tasks}
    depths = {tid: _id(_field(row, "depth")) for tid, row in graph.items()}
    for tid, task in plan.tasks.items():
        need(set(_ids(_field(graph[tid], "parentIds"))) == set(task.parents), "core plan/workflowGraph parents differ")
        for parent in task.parents:
            children[parent].add(tid)
            need(depths[parent] < depths[tid], "workflowGraph depth does not increase on an edge")
    for tid, row in graph.items():
        need(set(_ids(_field(row, "childIds"))) == children[tid], "workflowGraph child edges differ")
    profile = _obj(_field(root, "workflowProfile"))
    need(_id(_field(profile, "taskCount")) == len(plan.tasks) and
         _id(_field(profile, "edgeCount")) == sum(len(c) for c in children.values()), "workflowGraph/profile counts differ")
    arrivals = [_time(value) for value in array(_field(config, "workflowArrivalSeconds"))]
    outcomes = array(_field(result, "workflowOutcomes"))
    paths, inputs = array(_field(config, "workflowPaths")), array(_field(root, "inputs"))
    need(len(arrivals) > 0 and len(arrivals) == len(outcomes) == len(paths) == len(inputs),
         "workflow scopes/arrivals/inputs coverage mismatch")
    _text_is(config, "workflowArrivalSemantics", "PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO")
    cwd = word(_field(_field(_field(root, "provenance"), "execution"), "workingDirectory"))
    need(os.path.isabs(cwd) and "\x00" not in cwd, "invalid recorded working directory")
    by_scope = {}
    for tid, task in plan.tasks.items():
        by_scope.setdefault(task.scope, []).append(tid)
    first, covered = 1, set()
    for index, raw in enumerate(outcomes):
        outcome = _obj(raw)
        count = whole(_field(outcome, "taskCount"), 1, INT_MAX)
        last = first + count - 1
        need(last <= INT_MAX and _id(_field(outcome, "index")) == index and
             _id(_field(outcome, "firstTaskId")) == first and _id(_field(outcome, "lastTaskId")) == last,
             "invalid workflow Task ranges")
        scoped = sorted(by_scope.get(index, ()))
        # Do not allocate a range from an untrusted taskCount.
        need(len(scoped) == count and all(tid == first + n for n, tid in enumerate(scoped)),
             "workflow ranges/scopes differ from core plan")
        covered.update(scoped)
        need(_time(_field(outcome, "arrivalSecond")) == arrivals[index], "workflow arrival mismatch")
        declared, consumed, returned = word(paths[index]), word(_field(inputs[index], "path")), word(_field(outcome, "path"))
        need(all("\x00" not in path for path in (declared, consumed, returned)), "invalid workflow path")
        need(os.path.normpath(os.path.join(cwd, declared)) == os.path.normpath(consumed) ==
             os.path.normpath(os.path.join(cwd, returned)), "workflow input paths differ")
        if "taskCount" in inputs[index]:
            need(_id(inputs[index]["taskCount"]) == count, "input Task count differs from scope")
        if outcome.get("lastSuccessFinishSecond") is not None:
            need(_time(outcome["lastSuccessFinishSecond"]) >= arrivals[index], "workflow finish precedes arrival")
        first = last + 1
    need(covered == set(plan.tasks), "workflow scopes do not cover core plan")
    return config, result, model, plan, arrivals


def _platform(root, fabric=None, *, source_routing="BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY",
              complete_host_map=False):
    config, platform, result = (_obj(_field(root, key)) for key in ("configuration", "platform", "result"))
    hosts, vms = _rows(_field(platform, "hosts"), "id"), _rows(_field(platform, "vms"), "id")
    need(hosts and vms, "V2 platform requires hosts and VMs")
    need(_id(_field(config, "vmCount")) == _id(_field(platform, "vmCount")) == len(vms) and
         _id(_field(platform, "hostCount")) == len(hosts), "platform counts differ")
    actual = _obj(_field(result, "actualVmHostAssignments"))
    need(set(actual) == {str(vm) for vm in vms}, "actual VM placement keys/coverage differ")
    vm_hosts, capacities = {}, {}
    for ident, vm in vms.items():
        host = _id(_field(vm, "preflightHostId"))
        need(host in hosts and _id(actual[str(ident)]) == host, "actual/preflight VM host mismatch")
        if vm.get("pinnedHostId") is not None:
            need(_id(vm["pinnedHostId"]) == host, "pinned VM host mismatch")
        vm_hosts[ident] = host
        rate = float(whole(_field(vm, "bandwidth"), 1, LONG_MAX)) * 1000000.0
        need(math.isfinite(rate) and rate >= sys.float_info.min, "invalid converted VM capacity")
        capacities["VM:" + str(ident)] = rate
    topology = _field(platform, "networkTopology")
    expected_topology = None
    if topology is not None:
        topology = _obj(topology)
        _text_is(topology, "kind", "FAT_TREE")
        k = whole(_field(topology, "k"), 2, 32)
        need(k % 2 == 0, "Fat-tree k must be even and <=32")
        half = k // 2
        raw_cores = _field(topology, "coreSwitchCount")
        cores = half * half if raw_cores is None else whole(raw_cores, 1, half * half)
        bandwidth = _time(_field(topology, "linkBandwidthMbPerSecond")) * 1000000.0
        need(math.isfinite(bandwidth) and bandwidth >= sys.float_info.min, "invalid converted topology bandwidth")
        for key, expected in (("defaultPlacementPolicy", "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES"),
                              ("routingPolicy", "DETERMINISTIC_AL_FARES_FAT_TREE_V1"),
                              ("linkDirectionality", "INDEPENDENT_DIRECTED_LINKS"),
                              ("externalSourceRouting", source_routing)):
            _text_is(topology, key, expected)
        explicit = _field(topology, "hostEdgePlacements")
        if explicit is not None:
            need(set(_obj(explicit)) == {str(host) for host in hosts}, "explicit host placements do not cover hosts")
        need(len(hosts) <= k ** 3 // 4, "too many Fat-tree hosts")
        placements, per_edge = [], Counter()
        for ordinal, host in enumerate(sorted(hosts)):
            edge = ordinal % (k * half) if explicit is None else whole(explicit[str(host)], 0, k * half - 1)
            per_edge[edge] += 1
            need(per_edge[edge] <= half, "too many hosts per Fat-tree edge")
            pod, local_edge = divmod(edge, half)
            placements.append(dict(hostId=host, pod=pod, edge=local_edge))
        expected_topology = dict(kind="FAT_TREE", k=k, coreSwitchCount=cores,
                                 linkBandwidthBytesPerSecond=bandwidth, hostPlacements=placements)
    if fabric is not None:
        locations = {tuple((row["kind"], row["vmId"], row["sourceId"])) for row in fabric["locations"]}
        need(locations == {("SOURCE", None, "source")} | {("VM", vm, None) for vm in vms},
             "lifecycle VM endpoints differ from platform")
        resource_caps = {row["key"]: _time(row["capacityBytesPerSecond"]) for row in fabric["resources"]}
        for key, rate in capacities.items():
            need(resource_caps.get(key) == rate, "lifecycle VM capacity differs from platform")
        observed_topology = fabric["topology"]
        need((observed_topology is None) == (expected_topology is None), "topology presence differs")
        if expected_topology is not None:
            for key in ("kind", "k", "coreSwitchCount", "linkBandwidthBytesPerSecond"):
                observed = observed_topology[key]
                expected = expected_topology[key]
                need((word(observed) == expected) if key == "kind" else _time(observed) == expected,
                     "lifecycle topology parameters differ")
            observed = _rows(observed_topology["hostPlacements"], "hostId")
            need(set(observed) == set(hosts), "lifecycle topology host coverage differs")
            for placement in expected_topology["hostPlacements"]:
                item = observed[placement["hostId"]]
                need(_id(item["pod"]) == placement["pod"] and _id(item["edge"]) == placement["edge"],
                     "lifecycle physical host placement differs")
        if expected_topology is not None or complete_host_map:
            assignments = _rows(fabric["vmHostAssignments"], "vmId")
            need(set(assignments) == set(vms) and all(_id(assignments[vm]["hostId"]) == vm_hosts[vm] for vm in vms),
                 "lifecycle route is not using actual VM host assignment")
    return vms


def _time_tolerance(value):
    return min(8.0 * math.ulp(value), value * 1e-12)


def _jobs(result, plan, vms, end):
    all_jobs = _rows(_field(result, "jobs"), "jobId")
    jobs = {}
    for ident, row in all_jobs.items():
        if _id(_field(row, "classType")) != COMPUTE:
            continue
        members = _ids(_field(row, "taskIds"))
        need(len(members) == _id(_field(row, "taskCount")) == 1 and members[0] in plan.tasks,
             "compute Job must contain exactly one known logical Task")
        vm, status = _id(_field(row, "vmId")), _id(_field(row, "status"))
        start, finish = _time(_field(row, "startTime")), _time(_field(row, "finishTime"))
        need(vm in vms and status in (SUCCESS, FAILED) and start <= finish <= end, "invalid final compute Job")
        jobs[ident] = dict(vm=vm, task=members[0], status=status, start=start, finish=finish)
    outcomes = _rows(_field(result, "tasks"), "jobId")
    need(set(outcomes) == set(jobs), "final Task attempts do not cover compute Jobs exactly")
    for ident, row in outcomes.items():
        job = jobs[ident]
        need(_id(_field(row, "taskId")) == job["task"] and _id(_field(row, "vmId")) == job["vm"] and
             _id(_field(row, "jobStatus")) == job["status"] == _id(_field(row, "taskStatus")),
             "singleton Task/Job identity or terminal status differs")
        start, finish = _time(_field(row, "startTime")), _time(_field(row, "finishTime"))
        need(start == job["start"] and start <= finish <= job["finish"] + _time_tolerance(job["finish"]),
             "Task model window differs from actual Job envelope")
        if "exactJobTiming" in row:
            need(_bool(row["exactJobTiming"]) == (start == job["start"] and finish == job["finish"]),
                 "Task exactJobTiming flag differs from its window")
        job["task_finish"] = finish
    return jobs


def _main_sequence(root, events, end):
    summary = _obj(_field(root, "events"))
    _text_is(summary, "schema", "workflowsim-simulation-events-v1")
    need(_id(_field(summary, "eventCount")) == len(events), "main event count differs from manifest")
    if events:
        need(whole(_field(summary, "firstSequence"), 0, LONG_MAX) == 0 and
             whole(_field(summary, "lastSequence"), 0, LONG_MAX) == len(events) - 1, "main event sequence summary differs")
    else:
        need("firstSequence" not in summary and "lastSequence" not in summary, "empty event stream has sequence anchors")
    previous = 0.0
    for index, row in enumerate(events):
        need(whole(_field(row, "sequence"), 0, LONG_MAX) == index, "nonconsecutive main event sequence")
        time = _time(_field(row, "simulationTime"))
        need(previous <= time <= end, "main event time is not monotonic within simulation bounds")
        kind = word(_field(row, "type"))
        need(kind != "DATAFLOW_VM_ASSIGNED" or "dataflowAssignment" in root["configuration"],
             "assignment action requires explicit configuration.dataflowAssignment")
        previous = time


def _v2_stage_quantities(attrs, fact, plan, copies, model):
    # Recompute reference quantities, not V1 groups or served byte area.
    count, size, seconds, created, joined = 0, 0.0, 0.0, 0, 0
    for item in fact["inputs"]:
        count += item["referenceCount"]
        size += plan.files[_file_id(item["fileId"])].size * item["referenceCount"]
        if item["resolution"] in ("NEW_COPY", "JOIN_EXISTING"):
            seconds += copies[item["copyOrdinal"]]["isolatedSeconds"]
            created += item["resolution"] == "NEW_COPY"
            joined += item["resolution"] == "JOIN_EXISTING"
    _text_is(attrs, "transferUnit", "LOGICAL_FILE_V2")
    _text_is(attrs, "dataMovementModel", model)
    need(whole(_field(attrs, "modeledTransferFileCount"), 0, LONG_MAX) == count and
         _time(_field(attrs, "requiredFileBytes")) == size and
         _time(_field(attrs, "modeledTransferSeconds")) == seconds and
         _id(_field(attrs, "newFileCopies")) == created and _id(_field(attrs, "joinedFileCopies")) == joined and
         "contentionTransferGroupCount" not in attrs, "V2 main input quantities differ from lifecycle references")


def _main_context(root, events, jobs, facts, plan, arrivals, model, *, stage_at_ready=False,
                  stage_check=_v2_stage_quantities):
    main = {kind: {} for kind in MAIN_TYPES}
    for event in events:
        kind = event["type"]
        if kind not in MAIN_TYPES or _id(_field(event, "classType")) != COMPUTE:
            continue
        ident = _id(_field(event, "jobId"))
        need(ident in jobs, "main event names an unknown final compute Job")
        job = jobs[ident]
        need(_id(_field(event, "vmId")) == job["vm"] and _ids(_field(event, "taskIds")) == [job["task"]],
             "main event Task membership/fixed VM differs")
        _obj(_field(event, "attributes"))
        need(ident not in main[kind], "duplicate main " + kind)
        main[kind][ident] = event
    mandatory = ("JOB_READY", "DATA_STAGE_IN_MODELED", "SCHEDULING_DECISION", "JOB_DISPATCHED",
                 "TASK_EXECUTION_MODELED", "JOB_RETURNED")
    for kind in mandatory:
        need(set(main[kind]) == set(jobs), "missing compute main event: " + kind)
    copies = {row["copyOrdinal"]: row for row in facts["copies"]}
    initial, retried = set(), set()
    for ident, job in jobs.items():
        fact = facts["jobs"][ident]
        ready, stage, decision, dispatch, execution, returned = (main[kind][ident] for kind in mandatory)
        stage_time = fact["dataReadyAt"] if stage_at_ready else fact["requestedAt"]
        need(_time(ready["simulationTime"]) == fact["requestedAt"] and
             _time(stage["simulationTime"]) == stage_time and
             _time(execution["simulationTime"]) == fact["cpuStartedAt"] and
             _time(returned["simulationTime"]) == fact["finishedAt"], "main/lifecycle observation anchors differ")
        need(fact["requestedAt"] >= arrivals[plan.tasks[job["task"]].scope], "request precedes workflow arrival")
        decided = _time(decision["simulationTime"])
        need(fact["dataReadyAt"] <= decided <= job["start"] and _time(dispatch["simulationTime"]) == decided,
             "dispatch precedes data readiness or differs from scheduling")
        sequences = [whole(row["sequence"], 0, LONG_MAX) for row in (ready, stage, decision, dispatch, execution, returned)]
        need(all(a < b for a, b in zip(sequences, sequences[1:])), "main Job anchors violate sequence causality")
        for event, key in ((decision, "queueDelaySeconds"), (dispatch, "queueDelaySeconds"), (returned, "postDelaySeconds")):
            need(_time(_field(event["attributes"], key)) == 0, "unsupported V2 queue/post delay")
        _text_is(decision["attributes"], "schedulingAlgorithm", "STATIC")
        attrs = execution["attributes"]
        need(_id(_field(attrs, "taskId")) == job["task"] and _time(_field(attrs, "taskStartTime")) == job["start"] and
             _time(_field(attrs, "taskFinishTime")) == job["task_finish"] and
             _time(_field(attrs, "modeledStageInSecondsBeforeTask")) == 0 and
             _time(_field(attrs, "requestedDataStageInSecondsForJob")) == 0,
             "CPU envelope contains input transfer or disagrees with Task result")
        stage_check(stage["attributes"], fact, plan, copies, model)
        need(_id(_field(returned["attributes"], "jobStatus")) == job["status"], "main return status differs")
        statuses = [_id(value) for value in array(_field(returned["attributes"], "taskStatuses"))]
        need(statuses == [job["status"]], "main return Task statuses differ")
        failed_event = main["JOB_FAILED"].get(ident)
        if job["status"] == FAILED:
            need(failed_event is not None and _time(failed_event["simulationTime"]) == fact["finishedAt"] and
                 returned["sequence"] < failed_event["sequence"] and
                 _bool(_field(failed_event["attributes"], "failedStatus")), "failed Job lacks its terminal failed event")
        else:
            need(failed_event is None, "successful Job has a failed event")
        retry = main["RETRY_JOB_CREATED"].get(ident)
        ready_attrs = ready["attributes"]
        if retry is None:
            need("retryOfFailedJobId" not in ready_attrs and job["task"] not in initial,
                 "multiple initial attempts or false retry origin")
            initial.add(job["task"])
        else:
            prior = _id(_field(retry["attributes"], "failedJobId"))
            need(prior in jobs and jobs[prior]["task"] == job["task"] and jobs[prior]["status"] == FAILED and prior not in retried,
                 "retry must name one same-Task failed predecessor")
            need(_id(_field(ready_attrs, "retryOfFailedJobId")) == prior, "retry ready origin differs")
            failed = main["JOB_FAILED"].get(prior)
            need(failed is not None and main["JOB_RETURNED"][prior]["sequence"] < failed["sequence"] <
                 retry["sequence"] < ready["sequence"], "retry creation does not follow failed return and precede retry ready")
            need(facts["jobs"][prior]["finishedAt"] <= _time(retry["simulationTime"]) <= fact["requestedAt"],
                 "retry creation time does not follow failed observation")
            retried.add(prior)
    need(initial == set(plan.tasks), "initial attempts do not cover every logical Task")
    need(retried == {ident for ident, job in jobs.items() if job["status"] == FAILED}, "unrecovered failed attempt")


def _verify_context(manifest, document, main_events, *, check_assignment=True):
    """Verify one already strict-decoded V2 manifest/lifecycle/main-event triple.

    Artifact bytes/hash/metrics joins are performed by inspect_path, not this
    in-memory convenience function. Inputs and returned facts are not mutated.
    """
    config, result, model, plan, arrivals = _core(manifest)
    option = _obj(_field(config, "networkEvidence"))
    keys(option, ("mode", "maxTraceRecords"), "V2 recording")
    _text_is(option, "mode", MODE)
    budget = whole(option["maxTraceRecords"], 1, INT_MAX)
    report = verify_document(document)
    need(model == report["modelKind"] and budget == whole(document["recording"]["maxTraceRecords"], 1, INT_MAX),
         "manifest/lifecycle model or recording budget differs")
    _same(manifest["dataflowPlan"], document["filePlan"], "core and lifecycle file plans differ")
    end = _time(_field(result, "simulationEndSeconds"))
    need(report["observedThrough"] <= end and report["lifecycleQuiescent"], "final lifecycle is not quiescent within run bounds")
    need(_bool(_field(result, "workflowCompletedSuccessfully")), "full V2 bundle requires logical workflow success")
    vms = _platform(manifest, document["fabric"])
    jobs = _jobs(result, plan, vms, end)
    facts = report["facts"]
    fact_jobs = {row["jobId"]: row for row in facts["jobs"]}
    need(set(fact_jobs) == set(jobs) and set(facts["successfulTaskIds"]) == set(plan.tasks),
         "lifecycle does not cover final attempts/all logical Task successes")
    for ident, job in jobs.items():
        fact = fact_jobs[ident]
        need(fact["vmId"] == job["vm"] and fact["taskIds"] == [job["task"]] and
             fact["cpuStartedAt"] == job["start"] and fact["finishedAt"] is not None and
             fact["finishedAt"] >= job["finish"] and fact["success"] == (job["status"] == SUCCESS),
             "lifecycle Job/Task attempt differs from final result")
    events = array(main_events)
    _main_sequence(manifest, events, end)
    _main_context(manifest, events, jobs, dict(jobs=fact_jobs, copies=facts["copies"]), plan, arrivals, model)
    report.update(contextualRunChecked=True, corePlanContextChecked=True,
                  scope="FILE_LIFECYCLE_AND_RUN_CONTEXT_NOT_FLUID_SERVICE_ACCOUNTING",
                  mainEventCount=len(events), validatedComputeAttemptCount=len(jobs))
    if check_assignment and "dataflowAssignment" in config:
        from _dataflow_assignment_audit import _verify_validated
        report.update(_verify_validated(manifest, document, events, report))
    return report


def verify_context(manifest, document, main_events):
    """Validate the V2 run context and, only on explicit opt-in, its actions."""
    return _verify_context(manifest, document, main_events)


def _references(path, manifest, enabled, *, lifecycle_role="file-lifecycle"):
    refs, names = {}, set()
    directory = path.parent.resolve()
    for row in array(_field(manifest, "artifacts")):
        keys(row, ("role", "path", "sha256", "sizeBytes"), "artifact reference")
        role, name, digest = word(row["role"]), word(row["path"]), word(row["sha256"])
        relative = Path(name)
        need(name not in ("", ".", "..") and "\x00" not in name and "\\" not in name and
             not relative.is_absolute() and len(relative.parts) == 1 and relative.name == name,
             "artifact path must be a contained filename")
        need(role not in refs and name not in names, "duplicate artifact role/path")
        need(len(digest) == 64 and all(char in "0123456789abcdef" for char in digest), "invalid artifact SHA-256")
        target = directory / relative
        try:
            need(target.resolve().parent == directory and target.is_file(), "artifact resolves outside bundle or is not a file")
            raw = target.read_bytes()
        except (OSError, RuntimeError) as error:
            raise Invalid("cannot read artifact: " + name) from error
        need(len(raw) == whole(row["sizeBytes"], 0, LONG_MAX) and hashlib.sha256(raw).hexdigest() == digest,
             "artifact size/hash mismatch: " + name)
        try:
            refs[role] = raw.decode("utf-8", errors="strict")
        except UnicodeError as error:
            raise Invalid("artifact is not strict UTF-8: " + name) from error
        names.add(name)
    expected = {"metrics", "events", lifecycle_role} if enabled else {"metrics", "events"}
    need(set(refs) == expected, "recording/known artifact roles disagree (no mixed lifecycle/network roles permitted)")
    return refs


def _sidecars(path, root, enabled, *, lifecycle_role="file-lifecycle"):
    """One byte snapshot per contained artifact, shared only by closed V2/V3 callers."""
    refs = _references(path, root, enabled, lifecycle_role=lifecycle_role)
    metrics = _obj(decode_json(refs["metrics"]))
    keys(metrics, ("schema", "metrics"), "metrics sidecar")
    _text_is(metrics, "schema", "workflowsim-simulation-metrics-v2")
    need(METRIC_FIELDS <= set(_obj(metrics["metrics"])), "missing metrics-v2 fields")
    _same(_obj(_field(root, "metrics")), metrics["metrics"], "manifest/sidecar metrics differ")
    lines = refs["events"].split("\n")
    if lines[-1] == "":
        lines.pop()
    need(all(line.strip() for line in lines), "blank main JSONL event")
    return refs, [decode_json(line) for line in lines]


def inspect_path(path, manifest=None):
    """Read/hash/decode a full V2 bundle once; optionally reuse a parsed manifest."""
    path = Path(path).absolute()
    root = read_document(path) if manifest is None else manifest
    config, result, model, plan, _ = _core(root)
    enabled = "networkEvidence" in config
    if enabled:
        option = _obj(config["networkEvidence"])
        keys(option, ("mode", "maxTraceRecords"), "V2 recording")
        _text_is(option, "mode", MODE)
        whole(option["maxTraceRecords"], 1, INT_MAX)
    refs, events = _sidecars(path, root, enabled)
    if enabled:
        report = verify_context(root, decode_json(refs["file-lifecycle"]), events)
    else:
        # OFF is not a claim of zero copies/waiting/service. There are no
        # lifecycle facts with which to verify these quantities or visibility.
        end = _time(_field(result, "simulationEndSeconds"))
        vms = _platform(root)
        _jobs(result, plan, vms, end)
        _main_sequence(root, events, end)
        report = dict(status="DISABLED", completeCaptureCertified=False, contextualRunChecked=False,
                      corePlanContextChecked=True, fluidServiceAccountingCertified=False,
                      modelKind=model, mainEventCount=len(events),
                      scope="V2_CORE_PLAN_CONTEXT_ONLY_NO_LIFECYCLE_CAPTURE_NOT_ZERO_TRAFFIC")
    report["artifactReferencesChecked"] = True
    return report
