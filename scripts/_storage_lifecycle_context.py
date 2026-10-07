"""Independent manifest-v4 joins for bounded-storage V3 evidence (stdlib only).

The V3 entrypoints are closed: model/profile, input access, bounded storage,
recording/roles and main-event anchors cannot fall back to V2 or legacy OFF.
Only private, independently implemented V2 context machinery is reused. No JVM,
production algorithm, workflow input, allocator or service replay is invoked.

``verify_context`` joins decoded snapshots; ``inspect_path`` additionally checks
contained artifact names, strict UTF-8/JSON, hash/size and metrics equality using
the same bytes it validated. Neither function authenticates artifacts or claims
unrecorded fluid-service accounting. Failure-policy checks are static necessary
conditions, not a reconstruction or probabilistic certificate of random samples.
OFF certifies core context, not zero traffic.
"""
from __future__ import annotations

import math
from pathlib import Path

from _network_audit import CheckError as Invalid, array, decode_json, keys, need, read_document, whole, word
from _file_lifecycle_audit import _capacity, _file_id, _file_order, _number
from _file_lifecycle_context import (
    INT_MAX, LONG_MAX, COMPUTE, SUCCESS, FAILED, _bool, _field, _id, _ids, _jobs, _main_context, _main_sequence,
    _obj, _rows, _same, _sidecars, _text_is, _time,
    _core as _common_core, _platform as _common_platform,
)
from _storage_lifecycle_audit import ISOLATED, SHARED, MODE, LOCAL_INPUTS, STORE_INPUTS, verify_document

ROLE = "storage-lifecycle"
SOURCE_ROUTING = "BOUNDED_STORAGE_HOST_ATTACHMENT_V3"
FULL_SCOPE = "STORAGE_LIFECYCLE_AND_RUN_CONTEXT_NOT_FLUID_SERVICE_ACCOUNTING"
OFF_SCOPE = "V3_CORE_PLAN_CONTEXT_ONLY_NO_LIFECYCLE_CAPTURE_NOT_ZERO_TRAFFIC"
_STORAGE_FIELDS = ("attachmentHostId", "readBandwidthMbPerSecond", "writeBandwidthMbPerSecond",
                   "networkBandwidthMbPerSecond")
_MODEL_FIELDS = ("kind", "accessLinkBandwidthMbPerSecond", "accessLinkLatencySeconds",
                 "sourceEndpointBandwidthMbPerSecond", "contentionSemantics", "transferStartSemantics", "bandwidthUnit")

_FAILURE_FIELDS = ("clusteringAlgorithm", "monitorMode", "generatorMode", "distributionFamily",
                   "maxTotalRetryJobs", "generatorAddressing", "generators", "generatorsByVmId")
_FAILURE_MODES = {"FAILURE_NONE", "FAILURE_ALL", "FAILURE_VM", "FAILURE_JOB", "FAILURE_VM_JOB"}
_DISTRIBUTIONS = {"LOGNORMAL", "GAMMA", "WEIBULL", "NORMAL"}
_GENERATOR_FIELDS = ("family", "scale", "shape", "priorShape", "priorScale", "likelihoodPrior")


def _failure_policy(root, config):
    """Check modern serialized configuration, never construct/sample a generator."""
    policy = _obj(_field(config, "failureModel"))
    keys(policy, _FAILURE_FIELDS, "V3 failureModel")
    mode, family = word(policy["generatorMode"]), word(policy["distributionFamily"])
    need(mode in _FAILURE_MODES, "unknown failure generatorMode")
    need(family in _DISTRIBUTIONS, "unknown failure distributionFamily")
    # The modern builder normalizes disabled defaults to zero. A negative raw
    # legacy budget is NOT a serialized modern unlimited-retry declaration.
    budget = whole(policy["maxTotalRetryJobs"], 0, INT_MAX)
    dense, keyed = array(policy["generators"]), _obj(policy["generatorsByVmId"])
    addressing = "VM_ID_KEYED_ROWS" if keyed else "DENSE_VM_ID_MATRIX"
    need(word(policy["generatorAddressing"]) == addressing, "failure generator addressing/layout mismatch")
    depths = [_id(_field(row, "depth")) for row in array(_field(root, "workflowGraph"))]
    need(all(depth > 0 for depth in depths), "V3 parsed Task depths must be positive and one-based")
    if mode == "FAILURE_NONE":
        need(budget == 0 and not dense and not keyed, "FAILURE_NONE requires budget zero and empty generators")
        return
    need(budget > 0, "enabled failure model requires a positive explicit retry budget")
    need(bool(dense) != bool(keyed), "enabled failure model requires exactly one generator layout")
    need(family != "NORMAL", "enabled failure inter-arrival generators cannot use NORMAL")
    need(not keyed or mode in ("FAILURE_VM", "FAILURE_VM_JOB"), "VM-ID-keyed generators require a VM failure mode")
    rows = {}
    if keyed:
        for key, row in keyed.items():
            text = word(key)
            need(0 < len(text) <= 10 and text.isascii() and text.isdigit() and str(int(text)) == text,
                 "noncanonical failure generator VM ID")
            rows[_id(int(text))] = row
    else:
        rows = dict(enumerate(dense))
    for row in rows.values():
        need(bool(array(row)), "failure generator row must be nonempty")
        for raw in row:
            cell = _obj(raw)
            keys(cell, _GENERATOR_FIELDS, "failure generator")
            _text_is(cell, "family", family)
            _number(cell["scale"], positive=True)
            _number(cell["shape"], positive=True)
            priors = [cell[key] for key in ("priorShape", "priorScale", "likelihoodPrior")]
            need(all(p is None for p in priors) or all(p is not None for p in priors),
                 "failure distribution priors must be complete or all null")
            for value in priors:
                if value is not None:
                    _number(value)  # DistributionSpec permits any finite prior, not only positive priors.
    # Dense rows/columns use actual VM ID and DAG depth, never sorted rank.
    # Coverage is symbolic over existing declarations: no VM/depth-sized arrays
    # or Cartesian products are allocated from untrusted numerical identities.
    vms = _rows(_field(_field(root, "platform"), "vms"), "id")
    need(mode not in ("FAILURE_JOB", "FAILURE_VM_JOB") or depths, "failure mode requires Task depth coverage")
    need(mode not in ("FAILURE_VM", "FAILURE_VM_JOB") or vms, "failure mode requires VM coverage")
    vm_indices = vms if mode in ("FAILURE_VM", "FAILURE_VM_JOB") else (0,)
    column = max(depths) if mode in ("FAILURE_JOB", "FAILURE_VM_JOB") else 0
    need(all(vm in rows and len(rows[vm]) > column for vm in vm_indices),
         "failure generator layout does not cover declared VM IDs/Task depths")


def _failure_history(config, jobs, events):
    """Necessary final-run policy and STATIC/NOOP retry joins, including OFF."""
    policy = config["failureModel"]       # already checked by _failure_policy
    disabled = policy["generatorMode"] == "FAILURE_NONE"
    budget = whole(policy["maxTotalRetryJobs"], 0, INT_MAX)
    need(not disabled or all(job["status"] != FAILED for job in jobs.values()),
         "FAILURE_NONE cannot produce failed compute attempts")
    task_vms = {}
    for job in jobs.values():
        need(task_vms.setdefault(job["task"], job["vm"]) == job["vm"],
             "STATIC NOOP attempts of one logical Task cannot use different VMs")
    # A result-only lower bound remains necessary even with recording OFF or
    # omitted retry events. One initial attempt per distinct logical Task is free.
    need(len(jobs) - len(task_vms) <= budget, "compute attempts exceed the declared retry budget")
    retried, predecessors = set(), set()
    for event in events:
        kind = event["type"]
        if kind not in ("JOB_FAILED", "RETRY_JOB_CREATED") or _id(_field(event, "classType")) != COMPUTE:
            continue
        need(not disabled, "FAILURE_NONE cannot produce failure/retry events")
        ident = _id(_field(event, "jobId"))
        need(ident in jobs, "failure/retry event names an unknown compute attempt")
        job = jobs[ident]
        need(_id(_field(event, "vmId")) == job["vm"] and _ids(_field(event, "taskIds")) == [job["task"]],
             "failure/retry event differs from its final attempt")
        if kind == "JOB_FAILED":
            need(job["status"] == FAILED, "failure event names a successful compute attempt")
            continue
        prior = _id(_field(_field(event, "attributes"), "failedJobId"))
        need(prior != ident and prior in jobs and jobs[prior]["status"] == FAILED and jobs[prior]["task"] == job["task"] and
             ident not in retried and prior not in predecessors, "invalid or duplicate NOOP retry predecessor")
        need(jobs[prior]["vm"] == job["vm"], "STATIC NOOP retry cannot remap the failed attempt's VM")
        retried.add(ident)
        predecessors.add(prior)
    need(len(retried) <= budget, "observed retry creation count exceeds maxTotalRetryJobs")


def _core(root):
    config, result, model, plan, arrivals = _common_core(root, models=(SHARED, ISOLATED), filesystems=("LOCAL", "SHARED"))
    profile = _obj(_field(config, "dataMovementModel"))
    keys(profile, _MODEL_FIELDS, "V3 dataMovementModel")
    for key in ("accessLinkBandwidthMbPerSecond", "accessLinkLatencySeconds", "sourceEndpointBandwidthMbPerSecond"):
        need(_time(profile[key]) == 0, "storage V3 does not use fixed-endpoint parameters")
    _text_is(profile, "bandwidthUnit", "DECIMAL_MB_PER_SECOND")
    _text_is(profile, "contentionSemantics", "STORAGE_FILES_CHECKED_SHARED_MAX_MIN_V3" if model == SHARED else
             "STORAGE_FILES_ISOLATED_PATH_BOTTLENECK_V3")
    _text_is(profile, "transferStartSemantics", "CONTROL_READY_REQUEST;STORE_COMMIT_GATE_IF_SHARED;ASYNC_ALL_SUCCESSFUL_OUTPUTS_V3")
    _failure_policy(root, config)
    return config, result, model, plan, arrivals


def _platform(root, fabric=None):
    platform = _obj(_field(root, "platform"))
    store = _obj(_field(platform, "sourceStorage"))
    keys(store, _STORAGE_FIELDS, "platform.sourceStorage")
    host = _id(store["attachmentHostId"])
    hosts = _rows(_field(platform, "hosts"), "id")
    need(host in hosts, "SOURCE attachment references an undeclared platform host")
    for key in _STORAGE_FIELDS[1:]:
        _capacity(_number(store[key], positive=True) * 1_000_000.0)
    if fabric is not None:
        _same(store, fabric["sourceStorage"], "platform/lifecycle sourceStorage declaration differs")
    return _common_platform(root, fabric, source_routing=SOURCE_ROUTING, complete_host_map=True)


def _recording(config):
    option = _obj(_field(config, "networkEvidence"))
    keys(option, ("mode", "maxTraceRecords"), "V3 recording")
    _text_is(option, "mode", MODE)
    return whole(option["maxTraceRecords"], 1, INT_MAX)


def _input_totals(document, facts):
    """Actual INPUT_RESOLVED order matters for a binary64 sum; outputs never count."""
    copies = {row["copyOrdinal"]: row for row in facts["copies"]}
    totals = {row["jobId"]: [0.0, 0, 0] for row in facts["jobs"]}
    for event in document["events"]:
        if event["type"] != "INPUT_RESOLVED":
            continue
        p = event["payload"]
        resolution = p["resolution"]
        if resolution not in ("NEW_COPY", "JOIN_EXISTING"):
            continue
        copy = copies[whole(p["copyOrdinal"], 1, LONG_MAX)]
        need(copy["purpose"] == "INPUT", "output copy cannot contribute input preparation")
        total = totals[_id(p["jobId"])]
        total[0] += copy["isolatedSeconds"]
        need(math.isfinite(total[0]), "unrepresentable summed nominal input time")
        total[1] += resolution == "NEW_COPY"
        total[2] += resolution == "JOIN_EXISTING"
    return totals


def _stage_checker(totals):
    def check(attrs, fact, plan, copies, model):
        task = plan.tasks[fact["taskIds"][0]]
        count, size = sum(task.inputs.values()), 0.0
        # Request bytes are canonical plan order, not deferred resolution order.
        for fid in sorted(task.inputs, key=_file_order):
            size += plan.files[fid].size * task.inputs[fid]
            need(math.isfinite(size), "unrepresentable ordered request bytes")
        seconds, created, joined = totals[fact["jobId"]]
        need(count == fact["referenceCount"] and size == fact["requiredReferenceBytes"] and
             seconds == fact["isolatedInputSeconds"], "standalone/context input quantities disagree")
        _text_is(attrs, "transferUnit", "LOGICAL_FILE_STORAGE_V3")
        _text_is(attrs, "dataMovementModel", model)
        need(whole(_field(attrs, "modeledTransferFileCount"), 0, LONG_MAX) == count and
             _time(_field(attrs, "requiredFileBytes")) == size and
             _time(_field(attrs, "modeledTransferSeconds")) == seconds and
             _id(_field(attrs, "newFileCopies")) == created and _id(_field(attrs, "joinedFileCopies")) == joined and
             _time(_field(attrs, "observedInputPreparationSeconds")) == fact["dataReadyAt"] - fact["requestedAt"] and
             "contentionTransferGroupCount" not in attrs,
             "V3 main input quantities/observed preparation differ from lifecycle")
    return check


def _verify_context(manifest, document, main_events, *, check_assignment=True):
    """Join strict-decoded V3 manifest/lifecycle/main snapshots without modifying them.

    Artifact hash/size/UTF-8/metrics verification belongs to ``inspect_path``.
    Logical CPU completion can precede the output storage tail; stage-in remains
    nominal isolated INPUT seconds, separate from actual observed preparation.
    """
    config, result, model, plan, arrivals = _core(manifest)
    budget = _recording(config)
    report = verify_document(document)
    need(model == report["modelKind"] and budget == whole(document["recording"]["maxTraceRecords"], 1, INT_MAX),
         "manifest/lifecycle model or recording budget differs")
    access = STORE_INPUTS if word(config["fileSystem"]) == "SHARED" else LOCAL_INPUTS
    need(document["policies"]["inputAccess"] == access, "fileSystem/lifecycle inputAccess differs")
    _same(manifest["dataflowPlan"], document["filePlan"], "core and lifecycle file plans differ")
    end = _time(_field(result, "simulationEndSeconds"))
    need(report["observedThrough"] <= end and report["lifecycleQuiescent"],
         "final storage lifecycle is not quiescent within run bounds")
    need(_bool(_field(result, "workflowCompletedSuccessfully")), "full V3 bundle requires logical workflow success")
    vms = _platform(manifest, document["fabric"])
    jobs = _jobs(result, plan, vms, end)
    facts = report["facts"]
    fact_jobs = {row["jobId"]: row for row in facts["jobs"]}
    need(set(fact_jobs) == set(jobs) and set(facts["successfulTaskIds"]) == set(plan.tasks),
         "lifecycle does not cover final attempts/all logical Task successes")
    committed = {_file_id(r["fileId"]) for r in facts["replicas"] if r["location"]["kind"] == "SOURCE"}
    need(all(fid in committed for task in plan.tasks.values() for fid in task.outputs),
         "full V3 context lacks SOURCE closure for a successful output")
    for ident, job in jobs.items():
        fact = fact_jobs[ident]
        need(fact["vmId"] == job["vm"] and fact["taskIds"] == [job["task"]] and
             fact["cpuStartedAt"] == job["start"] and fact["finishedAt"] is not None and
             fact["finishedAt"] >= job["finish"] and fact["success"] == (job["status"] == SUCCESS),
             "lifecycle Job/Task attempt differs from final result")
    events = array(main_events)
    _main_sequence(manifest, events, end)
    _main_context(manifest, events, jobs, dict(jobs=fact_jobs, copies=facts["copies"]), plan, arrivals, model,
                  stage_at_ready=True, stage_check=_stage_checker(_input_totals(document, facts)))
    _failure_history(config, jobs, events)
    report.update(contextualRunChecked=True, corePlanContextChecked=True, scope=FULL_SCOPE,
                  mainEventCount=len(events), validatedComputeAttemptCount=len(jobs))
    if check_assignment and "dataflowAssignment" in config:
        from _dataflow_assignment_audit import _verify_validated
        report.update(_verify_validated(manifest, document, events, report))
    return report


def verify_context(manifest, document, main_events):
    """Validate the V3 run context and, only on explicit opt-in, its actions."""
    return _verify_context(manifest, document, main_events)


def inspect_path(path, manifest=None):
    """Validate one V3 bundle; an optional decoded manifest avoids a second read."""
    path = Path(path).absolute()
    root = read_document(path) if manifest is None else manifest
    config, result, model, plan, _ = _core(root)
    enabled = "networkEvidence" in config
    if enabled:
        _recording(config)
    refs, events = _sidecars(path, root, enabled, lifecycle_role=ROLE)
    if enabled:
        report = verify_context(root, decode_json(refs[ROLE]), events)
    else:
        end = _time(_field(result, "simulationEndSeconds"))
        vms = _platform(root)
        jobs = _jobs(result, plan, vms, end)
        _main_sequence(root, events, end)
        _failure_history(config, jobs, events)
        report = dict(status="DISABLED", completeCaptureCertified=False, contextualRunChecked=False,
                      corePlanContextChecked=True, fluidServiceAccountingCertified=False,
                      modelKind=model, mainEventCount=len(events), scope=OFF_SCOPE)
    report["artifactReferencesChecked"] = True
    return report
