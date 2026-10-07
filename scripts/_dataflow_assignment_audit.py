"""Independent stdlib NF005B online-assignment action/context audit.

Scope: BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1. Visibility, binding, PE,
compute, observed-start reservations and logged score/argmin are checked. A
lifecycle has no service-progress/rate-epoch witness: active-copy estimates are
BOUNDED, not replayed as exact live balances or fluid counterfactuals. Within-
bound logged progress may differ without invalidating this declared scope.

verify_context(manifest, lifecycle_doc, main_events) returns detached facts.
Existing V2/V3 contexts invoke the private action hook after their normal joins;
their inspect_path retains hash/size/strict UTF-8/contained-reference/metrics
checks. No production algorithm, JVM, subprocess or modified standalone grammar.
"""
from __future__ import annotations

from fractions import Fraction
import math

from _network_audit import CheckError as Invalid, array, f64, keys, need, required, whole, word
from _file_lifecycle_audit import SOURCE, _Fabric, _Plan, _file_id, _location

MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1"
POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1"
SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1"
INPUT_SEMANTICS = "MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1"
CPU_SEMANTICS = "ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1"
ACTION = "DATAFLOW_VM_ASSIGNED"
INT_MAX, LONG_MAX = (1 << 31) - 1, (1 << 63) - 1
MAX_EFFECTIVE_MI = 9_223_372_036_854
V2_MODELS = ("COHERENT_FILE_DATAFLOW_V2", "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2")
V3_MODELS = ("COHERENT_STORAGE_DATAFLOW_V3", "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3")
_EVENT_FIELDS = ("sequence", "type", "simulationTime", "jobId", "vmId", "classType", "taskIds", "attributes")
_ATTR_FIELDS = ("auditScope", "assignmentMode", "assignmentPolicy", "inputEstimateSemantics",
                "cpuReservationSemantics", "assignmentSequence", "binding", "selectedVmId", "observation", "candidates")
_OBSERVATION_FIELDS = ("contract", "status", "storageVersion", "storeBackedInputs", "interFlowSharing",
                       "observedThrough", "serviceThrough", "activeCopyCount", "activeJobCount",
                       "pendingOutputFileCount", "scopedFileCount")
_CANDIDATE_FIELDS = ("vmId", "compatible", "inputSeconds", "cpuAvailableAt", "computeSeconds",
                     "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies")


def _obj(value):
    need(type(value) is dict, "expected assignment JSON object")
    return value


def _id(value):
    return whole(value, 0, INT_MAX)


def _positive_int(value):
    return whole(value, 1, INT_MAX)


def _bool(value):
    need(type(value) is bool, "expected assignment JSON boolean")
    return value


def _time(value):
    return f64(value, nonnegative=True)


def _configuration(manifest):
    """Closed opt-in hook; absence preserves RANDOM, not hidden action evidence."""
    config = _obj(required(_obj(manifest), "configuration"))
    if "dataflowAssignment" not in config:
        need("dataflowComputeRequests" not in manifest,
             "dataflowComputeRequests requires explicit configuration.dataflowAssignment")
        return False
    option = keys(config["dataflowAssignment"], ("mode", "policy"), "dataflowAssignment")
    need(word(option["mode"]) == MODE and word(option["policy"]) == POLICY,
         "unsupported dataflow assignment mode/policy")
    model = word(required(_obj(required(config, "dataMovementModel")), "kind"))
    need(model in V2_MODELS + V3_MODELS, "online assignment requires a known coherent V2/V3 model")
    need(word(required(config, "planningAlgorithm")) == "INVALID" and
         word(required(config, "schedulingAlgorithm")) == "STATIC",
         "online assignment requires INVALID planning and STATIC scheduling")
    # A runnable raw kernel with recording OFF is not a supported full artifact.
    need("networkEvidence" in config, "online assignment artifacts require matching complete lifecycle capture; OFF is unsupported")
    recording = keys(config["networkEvidence"], ("mode", "maxTraceRecords"), "online recording")
    expected = "FILE_STORAGE_LIFECYCLE_V3" if model in V3_MODELS else "FILE_LIFECYCLE_V2"
    need(word(recording["mode"]) == expected, "online assignment requires its matching lifecycle recording mode")
    whole(recording["maxTraceRecords"], 1, INT_MAX)
    array(required(manifest, "dataflowComputeRequests"))
    return True


def _indexed(rows, field):
    result = {}
    for row in array(rows):
        ident = _id(required(_obj(row), field))
        need(ident not in result, "duplicate assignment context " + field)
        result[ident] = row
    return result


def _requests(manifest, jobs):
    attempts = _indexed(required(manifest["result"], "tasks"), "jobId")
    rows, logical = {}, {}
    for row in array(manifest["dataflowComputeRequests"]):
        keys(row, ("jobId", "taskId", "taskPes", "jobPes", "lengthMi"), "dataflowComputeRequests row")
        jid, tid = _id(row["jobId"]), _id(row["taskId"])
        task_pes, job_pes = _positive_int(row["taskPes"]), _positive_int(row["jobPes"])
        length = whole(row["lengthMi"], 1, LONG_MAX)
        need(jid not in rows and jid in jobs and jobs[jid]["taskIds"] == [tid],
             "compute request Job/Task identity or coverage differs")
        need(jid in attempts and _id(required(attempts[jid], "taskId")) == tid and
             whole(required(attempts[jid], "lengthMi"), 1, LONG_MAX) == length,
             "compute request raw length/Task differs from final result")
        metadata = (length, task_pes, job_pes)
        need(logical.setdefault(tid, metadata) == metadata,
             "logical Task compute metadata changes across retries")
        rows[jid] = dict(task=tid, taskPes=task_pes, jobPes=job_pes, lengthMi=length)
    need(set(rows) == set(jobs) == set(attempts), "compute request coverage must equal final compute attempts")
    return rows, attempts


def _compute_platform(manifest, tasks):
    vms = _indexed(required(manifest["platform"], "vms"), "id")
    for vm in vms.values():
        need(word(required(vm, "schedulerMode")) == "SPACE_SHARED",
             "online assignment requires exact SPACE_SHARED VM schedulerMode")
    normalized = {ident: dict(pes=_positive_int(required(vm, "pes")),
                              mips=f64(required(vm, "mips"), positive=True))
                  for ident, vm in vms.items()}
    raw_matrix = manifest["configuration"].get("taskCostMatrix")
    matrix = None
    if raw_matrix is not None:
        keys(raw_matrix, ("unit", "runtimeConversion", "entries"), "taskCostMatrix")
        need(word(raw_matrix["unit"]) == "EXECUTION_SECONDS" and
             word(raw_matrix["runtimeConversion"]) == "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI",
             "unsupported taskCostMatrix unit/runtime conversion")
        matrix = {}
        for row in array(raw_matrix["entries"]):
            keys(row, ("taskId", "vmId", "executionSeconds"), "taskCostMatrix entry")
            coordinate = _id(row["taskId"]), _id(row["vmId"])
            need(coordinate not in matrix, "duplicate task-cost coordinate")
            matrix[coordinate] = f64(row["executionSeconds"], positive=True)
        # The core format permits extra nonnegative Task/VM coordinates. Require
        # all actual coordinates, without allocating a Cartesian grid or mutating
        # the declaration to discard legal extra entries.
        need(all((tid, vm) in matrix for tid in tasks for vm in vms),
             "taskCostMatrix lacks complete Task/actual-VM coverage")
    return normalized, matrix


def _round_positive_binary64(product):
    """Exact positive-ties-up rounding of an already multiplied binary64."""
    need(math.isfinite(product) and product > 0, "unrepresentable task-cost binary64 product")
    # float + .5 is wrong at 2**52 and can introduce an extra rounding.
    rounded = Fraction.from_float(product) + Fraction(1, 2)
    return rounded.numerator // rounded.denominator


def _compute(request, vm_id, vm, matrix):
    effective = request["lengthMi"]
    if matrix is not None:
        effective = _round_positive_binary64(matrix[request["task"], vm_id] * vm["mips"])
    need(0 < effective <= MAX_EFFECTIVE_MI // request["taskPes"],
         "effective MI exceeds the positive PE-scaled execution limit")
    seconds = effective / vm["mips"]
    need(math.isfinite(seconds) and seconds > 0, "unrepresentable positive compute time")
    return effective, seconds


def _duration(size, rate):
    result = size / rate
    need(math.isfinite(result) and result > 0, "unrepresentable positive nominal input duration")
    return result


class _Prefix:
    """Minimal visible/active state from ALREADY validated lifecycle events.

    No per-request snapshots or service simulation. Final CPU finish times are
    never consulted for reservations; removal follows TASK_FINISHED observation.
    """
    def __init__(self, plan, fabric, storage, store_inputs):
        self.plan, self.fabric = plan, fabric
        self.storage, self.store_inputs = storage, store_inputs
        self.visible, self.copies, self.targets, self.jobs, self.outputs = {}, {}, {}, {}, set()

    def publish(self, fid, location):
        self.visible.setdefault(fid, set()).add(location)
        if location == SOURCE:
            self.outputs.discard(fid)

    def accept(self, event):
        p, kind = event["payload"], event["type"]
        if kind == "EXTERNAL_SEEDED":
            self.publish(_file_id(p["fileId"]), SOURCE)
        elif kind == "COPY_ADMITTED":
            ordinal, fid = whole(p["copyOrdinal"], 1, LONG_MAX), _file_id(p["fileId"])
            destination = _location(p["destination"]) if self.storage else (0, _id(p["destinationVmId"]))
            copy = dict(file=fid, source=_location(p["sourceReplica"]["location"]), destination=destination,
                        rate=f64(p["standaloneRate"], positive=True), size=f64(p["bytes"], positive=True),
                        purpose=p["purpose"] if self.storage else "INPUT")
            self.copies[ordinal] = copy
            self.targets[fid, destination] = copy
        elif kind == "COPY_SETTLED":
            copy = self.copies.pop(whole(p["copyOrdinal"], 1, LONG_MAX))
            del self.targets[copy["file"], copy["destination"]]
            self.publish(copy["file"], copy["destination"])
        elif kind == "JOB_CPU_STARTED":
            self.jobs[_id(p["jobId"])]["started"] = _time(event["observedTime"])
        elif kind == "TASK_FINISHED":
            jid, tid, vm = _id(p["jobId"]), _id(p["taskId"]), _id(p["vmId"])
            if p["success"]:
                for fid in self.plan.tasks[tid].outputs:
                    self.publish(fid, (0, vm))
                    if self.storage and SOURCE not in self.visible[fid]:
                        self.outputs.add(fid)
            del self.jobs[jid]
        elif kind == "INPUT_RESOLVED" and p["resolution"] == "ZERO":
            self.publish(_file_id(p["fileId"]), (0, self.jobs[_id(p["jobId"])]["vm"]))
        elif kind == "OUTPUT_RESOLVED" and p["resolution"] in ("ZERO", "ALREADY_STORED"):
            fid = _file_id(p["fileId"])
            if p["resolution"] == "ALREADY_STORED":
                need(SOURCE in self.visible.get(fid, ()), "ALREADY_STORED lacks visible SOURCE")
            self.publish(fid, SOURCE)

    def reservation(self, vm, now):
        remaining = 0.0
        # Dict insertion order is lifecycle request order, not final Job ID or
        # CPU-start order. Sum first, then add now, binary64 at every step.
        for job in self.jobs.values():
            if job["vm"] != vm:
                continue
            amount = job["compute"] if job["started"] is None else max(0.0, job["compute"] - (now - job["started"]))
            remaining += amount
            need(math.isfinite(remaining), "unrepresentable ordered CPU reservation sum")
        available = now + remaining
        need(math.isfinite(available) and (remaining == 0 or available > now),
             "nonfinite/nonadvancing CPU availability")
        return available

    def file_bound(self, fid, target):
        holders = self.visible.get(fid, set())
        size = self.plan.files[fid].size
        if self.store_inputs and SOURCE not in holders:
            upload = self.targets.get((fid, SOURCE))
            need(upload is not None and upload["purpose"] == "OUTPUT",
                 "uncommitted SHARED input has no active SOURCE output")
            post = 0.0 if target in holders else _duration(size, self.fabric.route(SOURCE, target)[1])
            upper = _duration(size, upload["rate"]) + post
            need(math.isfinite(upper) and upper > 0, "unrepresentable conditional store upper bound")
            # Tiny positive upload may be absorbed by addition to positive
            # postRead; only the zero-postRead lower bound is strictly open.
            return post, upper, post == 0, True, 0
        if target in holders:
            return 0.0, 0.0, False, False, 0
        eligible = {SOURCE} if self.store_inputs and SOURCE in holders else holders
        need(bool(eligible), "input has no eligible visible source")
        if size == 0:
            return 0.0, 0.0, False, False, 0
        active = self.targets.get((fid, target))
        if active is not None:
            need(active["purpose"] == "INPUT", "target join is not an INPUT copy")
            return 0.0, _duration(size, active["rate"]), True, False, 1
        rate = max(self.fabric.route(source, target)[1] for source in eligible)
        exact = _duration(size, rate)
        return exact, exact, False, False, 0

    def input_bounds(self, task, vm):
        lower = upper = 0.0
        strict, conditional, joined = False, False, 0
        for fid in self.plan.tasks[task].inputs:
            lo, hi, opened, wait, count = self.file_bound(fid, (0, vm))
            if lo > lower:
                lower, strict = lo, opened
            elif lo == lower:
                strict = strict or opened
            upper = max(upper, hi)
            conditional, joined = conditional or wait, joined + count
        return dict(lowerSeconds=lower, upperSeconds=upper, lowerExclusive=strict,
                    knownExact=lower == upper and not strict), conditional, joined


def _observation(raw, state, model, now, task):
    keys(raw, _OBSERVATION_FIELDS, "assignment observation")
    need(word(raw["contract"]) == "OBSERVED_DATAFLOW_STATE_V1" and word(raw["status"]) == "BOUND",
         "assignment observation contract/status differs")
    for field, expected in (("storageVersion", state.storage), ("storeBackedInputs", state.store_inputs),
                            ("interFlowSharing", model in (V2_MODELS[0], V3_MODELS[0]))):
        need(_bool(raw[field]) == expected, "assignment observation " + field + " differs")
    for field in ("observedThrough", "serviceThrough"):
        need(_time(raw[field]) == now, "assignment observation watermark differs from request clock")
    # Active jobs/copies and SOURCE obligations are global prefix counts. The
    # scoped count instead covers this request's distinct canonical input IDs,
    # including local and zero-byte inputs, never reference multiplicity or all
    # declared files (which may include unrelated inputs and unused outputs).
    scoped_inputs = len(state.plan.tasks[task].inputs)
    for field, expected in (("activeCopyCount", len(state.copies)), ("activeJobCount", len(state.jobs)),
                            ("pendingOutputFileCount", len(state.outputs)), ("scopedFileCount", scoped_inputs)):
        need(_id(raw[field]) == expected, "assignment observation " + field + " differs from lifecycle prefix")
    return dict(contract="OBSERVED_DATAFLOW_STATE_V1", status="BOUND", storageVersion=state.storage,
                storeBackedInputs=state.store_inputs, interFlowSharing=model in (V2_MODELS[0], V3_MODELS[0]),
                observedThrough=now, serviceThrough=now, activeCopyCount=len(state.copies),
                activeJobCount=len(state.jobs), pendingOutputFileCount=len(state.outputs), scopedFileCount=scoped_inputs)


def _actions(main_events, jobs):
    actions, ready, retries = {}, {}, {}
    for event in array(main_events):
        kind = required(_obj(event), "type")
        if kind == ACTION:
            keys(event, _EVENT_FIELDS, "assignment event")
            need(_id(event["classType"]) == 2, "assignment action must be for a compute Job")
            jid, vm = _id(event["jobId"]), _id(event["vmId"])
            task_ids = [_id(tid) for tid in array(event["taskIds"])]
            need(jid in jobs and jid not in actions and vm == jobs[jid]["vmId"] and task_ids == jobs[jid]["taskIds"],
                 "assignment action Job/Task/VM identity or coverage differs")
            attrs = keys(event["attributes"], _ATTR_FIELDS, "assignment attributes")
            for field, expected in (("auditScope", SCOPE), ("assignmentMode", MODE), ("assignmentPolicy", POLICY),
                                    ("inputEstimateSemantics", INPUT_SEMANTICS), ("cpuReservationSemantics", CPU_SEMANTICS)):
                need(word(attrs[field]) == expected, "unsupported assignment " + field)
            need(whole(attrs["assignmentSequence"], 1, LONG_MAX) == len(actions) + 1,
                 "assignmentSequence must follow global main sequence from one")
            need(_id(attrs["selectedVmId"]) == vm, "selected VM differs from action/final binding")
            actions[jid] = event
        elif kind in ("JOB_READY", "RETRY_JOB_CREATED") and _id(required(event, "classType")) == 2:
            target = ready if kind == "JOB_READY" else retries
            jid = _id(required(event, "jobId"))
            need(jid not in target, "duplicate assignment main anchor")
            target[jid] = event
    need(set(actions) == set(jobs) == set(ready), "exactly one assignment action is required per compute attempt")
    for jid, action in actions.items():
        need(_time(action["simulationTime"]) == _time(ready[jid]["simulationTime"]) and
             whole(action["sequence"], 0, LONG_MAX) < whole(ready[jid]["sequence"], 0, LONG_MAX),
             "assignment must precede JOB_READY at the same request clock")
    return actions, retries


def _execution_metadata(main_events, attempts):
    """Online-only join of recorded compute MI/scope to validated Task results.

    The base context already joins identity, time and ordering. These additional
    mandatory fields close the online execution declaration without tightening
    old configuration-absence contexts or consulting actual future CPU finish.
    """
    seen = set()
    for event in main_events:
        if event["type"] != "TASK_EXECUTION_MODELED" or _id(required(event, "classType")) != 2:
            continue
        jid = _id(required(event, "jobId"))
        need(jid in attempts and jid not in seen, "online execution metadata Job coverage differs")
        attrs, result = _obj(required(event, "attributes")), attempts[jid]
        need(whole(required(attrs, "taskLengthMi"), 1, LONG_MAX) ==
             whole(required(result, "lengthMi"), 1, LONG_MAX),
             "online execution taskLengthMi differs from result Task raw length")
        need(whole(required(attrs, "effectiveExecutionLengthMi"), 1, LONG_MAX) ==
             whole(required(result, "effectiveExecutionLengthMi"), 1, LONG_MAX),
             "online execution effectiveExecutionLengthMi differs from result Task")
        need(word(required(attrs, "taskTimingScope")) == "MODEL_DERIVED_COMPUTE_WINDOW",
             "online execution taskTimingScope must be MODEL_DERIVED_COMPUTE_WINDOW")
        seen.add(jid)
    need(seen == set(attempts), "online execution metadata does not cover compute attempts")


def _verify_validated(manifest, document, main_events, report):
    """Private normal-context hook; base lifecycle/context have already passed."""
    need(_configuration(manifest), "assignment audit requires explicit online configuration")
    need(report["completeCaptureCertified"] and report["lifecycleQuiescent"] and report["contextualRunChecked"],
         "assignment audit requires complete quiescent matching run lifecycle")
    model, config = report["modelKind"], manifest["configuration"]
    storage = model in V3_MODELS
    plan = _Plan(manifest["dataflowPlan"])
    if storage:
        from _storage_lifecycle_audit import _StorageFabric
        fabric = _StorageFabric(document["fabric"])
    else:
        fabric = _Fabric(document["fabric"])
    jobs = {row["jobId"]: row for row in report["facts"]["jobs"]}
    requests, attempts = _requests(manifest, jobs)
    vms, matrix = _compute_platform(manifest, plan.tasks)
    actions, retries = _actions(main_events, jobs)
    if not storage:
        # Old V2 absence stays unchanged. Modern online also checks explicit
        # failure layouts/budgets and STATIC NOOP VM inheritance.
        from _storage_lifecycle_context import _failure_policy, _failure_history
        _failure_policy(manifest, config)
        final_jobs = {jid: dict(task=row["taskIds"][0], vm=row["vmId"], status=4 if row["success"] else 5)
                      for jid, row in jobs.items()}
        _failure_history(config, final_jobs, main_events)
    state = _Prefix(plan, fabric, storage, storage and config["fileSystem"] == "SHARED")
    logical, facts = {}, []
    for event in document["events"]:
        if event["type"] != "JOB_INPUT_REQUESTED":
            state.accept(event)
            continue
        p, now = event["payload"], _time(event["observedTime"])
        jid, vm = _id(p["jobId"]), _id(p["destinationVmId"])
        request, action = requests[jid], actions[jid]
        attrs, tid = action["attributes"], request["task"]
        ordinal = whole(attrs["assignmentSequence"], 1, LONG_MAX)
        need(ordinal == len(facts) + 1 and _time(action["simulationTime"]) == now,
             "assignment order/clock differs from lifecycle request prefix")
        binding = word(attrs["binding"])
        if tid not in logical:
            need(binding == "INITIAL" and jid not in retries, "initial logical Task assignment has a retry origin")
            logical[tid] = vm
            expected_vms = sorted(vms)
        else:
            need(binding == "RETRY_REUSE" and logical[tid] == vm and jid in retries,
                 "retry must reuse its initial logical Task/VM binding")
            retry = retries[jid]
            prior = _id(required(retry["attributes"], "failedJobId"))
            need(prior in jobs and not jobs[prior]["success"] and jobs[prior]["taskIds"] == [tid] and
                 jobs[prior]["vmId"] == vm and whole(retry["sequence"], 0, LONG_MAX) < whole(action["sequence"], 0, LONG_MAX),
                 "retry assignment lacks its preceding failed same-Task/same-VM creation")
            expected_vms = [vm]
        observation = _observation(attrs["observation"], state, model, now, tid)
        rows = array(attrs["candidates"])
        need([_id(required(_obj(row), "vmId")) for row in rows] == expected_vms,
             "candidate rows must cover actual VM IDs in ascending order (retry: bound VM only)")
        candidates, scores, selected_compute, selected_mi = [], [], None, None
        for row in rows:
            keys(row, _CANDIDATE_FIELDS, "assignment candidate")
            candidate = _id(row["vmId"])
            platform_vm = vms[candidate]
            compatible = platform_vm["pes"] >= max(request["taskPes"], request["jobPes"])
            need(_bool(row["compatible"]) == compatible, "candidate PE compatibility differs")
            if not compatible:
                need(all(row[field] is None for field in _CANDIDATE_FIELDS[2:]), "incompatible candidate fields must all be null")
                candidates.append(dict(vmId=candidate, compatible=False, **{key: None for key in _CANDIDATE_FIELDS[2:]}))
                continue
            effective, compute = _compute(request, candidate, platform_vm, matrix)
            need(f64(row["computeSeconds"], positive=True) == compute, "candidate compute differs from independent effective MI/MIPS")
            cpu = state.reservation(candidate, now)
            need(_time(row["cpuAvailableAt"]) == cpu, "candidate CPU reservation differs from active observed-start prefix")
            bounds, conditional, joined = state.input_bounds(tid, candidate)
            input_seconds = _time(row["inputSeconds"])
            lower, upper = bounds["lowerSeconds"], bounds["upperSeconds"]
            need((input_seconds > lower if bounds["lowerExclusive"] else input_seconds >= lower) and input_seconds <= upper,
                 "logged input estimate is outside nominal lifecycle bounds")
            need(_bool(row["conditionalStoreWait"]) == conditional and _id(row["joinedInputCopies"]) == joined,
                 "candidate conditional-store/join count differs from prefix")
            input_at = now + input_seconds
            start = max(input_at, cpu)
            score = start + compute
            need(math.isfinite(input_at) and (input_seconds == 0 or input_at > now) and
                 math.isfinite(score) and score > start, "nonfinite/nonadvancing candidate score")
            need(f64(row["scoreFinishSeconds"], positive=True) == score,
                 "candidate score differs from binary64 max(now+input,cpuAvailable)+compute")
            scores.append((score, candidate))
            candidates.append(dict(vmId=candidate, compatible=True, inputSeconds=input_seconds, cpuAvailableAt=cpu,
                                   computeSeconds=compute, scoreFinishSeconds=score, conditionalStoreWait=conditional,
                                   joinedInputCopies=joined, inputBounds=bounds, effectiveExecutionLengthMi=effective))
            if candidate == vm:
                selected_compute, selected_mi = compute, effective
        need(scores and min(scores)[1] == vm and selected_compute is not None,
             "selected VM is not the compatible logged-score/VM-ID argmin")
        need(whole(required(attempts[jid], "effectiveExecutionLengthMi"), 1, LONG_MAX) == selected_mi,
             "selected effective MI differs from result Task")
        facts.append(dict(jobId=jid, taskId=tid, assignmentSequence=ordinal, binding=binding, selectedVmId=vm,
                          requestedAt=now, requestSequence=whole(event["sequence"], 1, LONG_MAX),
                          lengthMi=request["lengthMi"], taskPes=request["taskPes"], jobPes=request["jobPes"],
                          effectiveExecutionLengthMi=selected_mi, computeSeconds=selected_compute,
                          observation=observation, candidates=candidates))
        state.jobs[jid] = dict(vm=vm, compute=selected_compute, started=None)
    need(len(facts) == len(actions) and not state.jobs and not state.copies and not state.outputs,
         "assignment prefix reconstruction did not reach the complete quiescent run")
    _execution_metadata(main_events, attempts)
    return dict(dataflowAssignmentContextChecked=True, dataflowAssignmentScope=SCOPE,
                liveProgressReplayed=False, validatedAssignmentCount=len(facts), assignmentFacts=facts)


def verify_context(manifest, lifecycle_doc, main_events):
    """Validate the full decoded online triple; return detached summary/facts.

    Selection of existing base V2/V3 verification is explicit and closed. The
    private switch only avoids calling this action hook twice; ordinary public
    context entrypoints always invoke it when configuration opts in.
    """
    need(_configuration(manifest), "assignment audit requires explicit online configuration")
    model = manifest["configuration"]["dataMovementModel"]["kind"]
    if model in V3_MODELS:
        from _storage_lifecycle_context import _verify_context as verify_base
    else:
        from _file_lifecycle_context import _verify_context as verify_base
    report = verify_base(manifest, lifecycle_doc, main_events, check_assignment=False)
    report.update(_verify_validated(manifest, lifecycle_doc, main_events, report))
    return report
