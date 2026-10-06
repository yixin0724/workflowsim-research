"""Independent, complete-history V2 file-lifecycle verifier (stdlib only).

Use ``_network_audit.decode_json`` before ``verify_document`` so integer IDs and
counters are checked as exact JSON numbers, not after lossy float conversion.
Only that module's lexical/number helpers are shared; no V1 verifier, JVM,
production selector, replica state, allocator, or simulation is invoked.

``verify_document(document)`` returns detached, JSON-serializable summary/facts
or raises ``Invalid`` (the existing audit CheckError, a ValueError subclass).
It does not mutate the caller. COMPLETE certifies a consistent captured history,
not authentication, fluid-service accounting, or completion of the enclosing
run. An operation-boundary prefix may still have active copies/waiting jobs.
Manifest, Task/result, workflow-arrival and main-event joins belong to the
artifact-context validator, not this standalone document verifier.
"""
from __future__ import annotations

from collections import Counter, deque
from dataclasses import dataclass, field
import math
from pathlib import Path
import sys

from _network_audit import (CheckError as Invalid, decode_json,
                            f64 as _number, whole as _integer)

SCHEMA = "workflowsim-file-lifecycle-v2"
MODE = "FILE_LIFECYCLE_V2"
CERTIFICATE_SCOPE = "FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2"
FILE_IDENTITY = "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2"
SHARED = "COHERENT_FILE_DATAFLOW_V2"
ISOLATED = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2"
INT_MAX, LONG_MAX = (1 << 31) - 1, (1 << 63) - 1
SOURCE = (1, "source")                 # Typed order: numeric VMs, then SOURCE.
POLICIES = dict(fileIdentity=FILE_IDENTITY,
                release="DEPENDENCY_READY_AT_OBSERVATION_V2",
                visibility="PER_FILE_SETTLEMENT_OBSERVATION_V2",
                selection="VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2",
                sourceAccess="UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2")
PAYLOAD_KEYS = {
    "EXTERNAL_SEEDED": ("fileId", "location"),
    "TASK_FINISHED": ("taskId", "jobId", "vmId", "success"),
    "JOB_INPUT_REQUESTED": ("jobId", "taskIds", "destinationVmId"),
    "INPUT_RESOLVED": ("jobId", "fileId", "referenceCount", "resolution", "copyOrdinal", "source"),
    "COPY_ADMITTED": ("copyOrdinal", "fileId", "bytes", "sourceReplica", "destinationVmId",
                      "resources", "standaloneRate", "isolatedSeconds"),
    "COPY_SETTLED": ("copyOrdinal", "effectiveTime", "remainingAfterService"),
    "JOB_DATA_READY": ("jobId",),
    "JOB_CPU_STARTED": ("jobId", "vmId"),
}
ACQUISITIONS = {"EXTERNAL_SEED", "TASK_OUTPUT", "COPY_SETTLEMENT", "ZERO_BYTE_REFERENCE"}


def _need(condition, message):
    if not condition:
        raise Invalid(message)


def _object(value, keys, name):
    _need(type(value) is dict and set(value) == set(keys), name + ": missing/unknown fields")
    return value


def _array(value):
    _need(type(value) is list, "expected JSON array")
    return value


def _text(value):
    _need(type(value) is str, "expected JSON text")
    return value


def _id(value):
    return _integer(value, 0, INT_MAX)


def _count(value, positive=False):
    return _integer(value, 1 if positive else 0, LONG_MAX)


def _time(value):
    return _number(value, nonnegative=True)


def _capacity(value):
    result = _number(value, positive=True)
    _need(result >= sys.float_info.min, "capacity/rate must be a positive normal binary64")
    return result


def _file_id(raw):
    _object(raw, ("workflowInputIndex", "name"), "fileId")
    scope, name = _id(raw["workflowInputIndex"]), _text(raw["name"])
    _need(bool(name), "empty file name")
    return scope, name


def _file_order(ident):
    # The writer's String ordering is UTF-16 code-unit order, not Python's
    # Unicode-code-point order. Identity itself remains the exact unsplit name.
    return ident[0], ident[1].encode("utf-16-be", "surrogatepass")


def _file_json(ident):
    return dict(workflowInputIndex=ident[0], name=ident[1])


def _location(raw):
    _object(raw, ("kind", "vmId", "sourceId"), "location")
    kind = _text(raw["kind"])
    if kind == "VM":
        _need(raw["sourceId"] is None, "VM location must have null sourceId")
        return 0, _id(raw["vmId"])
    _need(kind == "SOURCE" and raw["vmId"] is None and
          _text(raw["sourceId"]) == "source", "only the default typed SOURCE is supported")
    return SOURCE


def _location_json(location):
    return dict(kind="VM" if location[0] == 0 else "SOURCE",
                vmId=location[1] if location[0] == 0 else None,
                sourceId=None if location[0] == 0 else "source")


@dataclass(frozen=True)
class _File:
    size: float
    producer: int | None


@dataclass(frozen=True)
class _Task:
    scope: int
    parents: tuple
    inputs: dict
    outputs: tuple


class _Plan:
    """Keyed declarations plus iterative graph checks; never expand references."""
    def __init__(self, raw):
        _object(raw, ("contractVersion", "tasks", "files"), "filePlan")
        _need(_text(raw["contractVersion"]) == FILE_IDENTITY, "unsupported file plan contract")
        self.files, self.tasks = {}, {}
        for row in _array(raw["files"]):
            _object(row, ("fileId", "bytes", "producerTaskId"), "file declaration")
            ident = _file_id(row["fileId"])
            _need(ident not in self.files, "duplicate file declaration")
            producer = None if row["producerTaskId"] is None else _id(row["producerTaskId"])
            self.files[ident] = _File(_number(row["bytes"], nonnegative=True), producer)
        for row in _array(raw["tasks"]):
            _object(row, ("taskId", "workflowInputIndex", "parents", "inputs", "outputs"), "Task")
            ident, scope = _id(row["taskId"]), _id(row["workflowInputIndex"])
            _need(ident not in self.tasks, "duplicate Task")
            parents = [_id(p) for p in _array(row["parents"])]
            _need(len(set(parents)) == len(parents) and ident not in parents, "duplicate/self parent")
            inputs, outputs = {}, set()
            for item in _array(row["inputs"]):
                _object(item, ("fileId", "referenceCount"), "input reference")
                fid = self.file(item["fileId"])
                _need(fid[0] == scope and fid not in inputs, "duplicate/cross-workflow input")
                inputs[fid] = _count(item["referenceCount"], positive=True)
            for item in _array(row["outputs"]):
                fid = self.file(item)
                _need(fid[0] == scope and fid not in outputs, "duplicate/cross-workflow output")
                _need(fid not in inputs, "in-place input/output file")
                _need(self.files[fid].producer == ident, "output has a different/no declared producer")
                outputs.add(fid)
            self.tasks[ident] = _Task(scope, tuple(sorted(parents)), inputs,
                                     tuple(sorted(outputs, key=_file_order)))
        consumers = set()
        children = {tid: [] for tid in self.tasks}
        indegrees = {}
        for tid, task in self.tasks.items():
            consumers.update(task.inputs)
            indegrees[tid] = len(task.parents)
            for parent in task.parents:
                _need(parent in self.tasks and self.tasks[parent].scope == task.scope,
                      "unknown/cross-workflow parent")
                children[parent].append(tid)
        for fid, definition in self.files.items():
            producer = definition.producer
            if producer is None:
                _need(fid in consumers, "external file has no declared input reference")
            else:
                _need(producer in self.tasks and fid in self.tasks[producer].outputs,
                      "unknown producer or missing producer output")
        queue = deque(tid for tid, degree in indegrees.items() if degree == 0)
        visited = 0
        while queue:
            tid = queue.popleft()
            visited += 1
            for child in children[tid]:
                indegrees[child] -= 1
                if indegrees[child] == 0:
                    queue.append(child)
        _need(visited == len(self.tasks), "Task graph contains a cycle")
        # Search only ancestors needed by this consumer; no recursive DFS or
        # dense all-pairs transitive closure (and no dependence on multiplicity).
        for task in self.tasks.values():
            needed = {self.files[fid].producer for fid in task.inputs} - {None}
            seen, queue = set(), deque(task.parents)
            while needed and queue:
                parent = queue.popleft()
                if parent in seen:
                    continue
                seen.add(parent)
                needed.discard(parent)
                queue.extend(self.tasks[parent].parents)
            _need(not needed, "input producer is not a control ancestor")

    def file(self, raw):
        ident = _file_id(raw)
        _need(ident in self.files, "unknown file identity")
        return ident


class _Fabric:
    """Independent bounded Fat-tree arithmetic and exact resource-key closure."""
    def __init__(self, raw):
        _object(raw, ("locations", "resources", "vmHostAssignments", "topology"), "fabric")
        locations = [_location(item) for item in _array(raw["locations"])]
        _need(len(set(locations)) == len(locations) and SOURCE in locations,
              "duplicate locations or missing default SOURCE")
        self.locations = set(locations)
        self.vms = {loc[1] for loc in locations if loc[0] == 0}
        self.capacities = {}
        for item in _array(raw["resources"]):
            _object(item, ("key", "capacityBytesPerSecond"), "resource")
            key = _text(item["key"])
            _need(key and key not in self.capacities, "empty/duplicate resource key")
            self.capacities[key] = _capacity(item["capacityBytesPerSecond"])
        self.hosts, self.placements = {}, {}
        assignments = _array(raw["vmHostAssignments"])
        expected = {"VM:" + str(vm) for vm in self.vms}
        self.has_topology = raw["topology"] is not None
        if not self.has_topology:
            _need(not assignments, "host assignments require a topology")
        else:
            topology = raw["topology"]
            _object(topology, ("kind", "k", "coreSwitchCount", "linkBandwidthBytesPerSecond",
                               "hostPlacements"), "topology")
            _need(_text(topology["kind"]) == "FAT_TREE", "unsupported topology")
            self.k = _integer(topology["k"], 2, 32)
            _need(self.k % 2 == 0, "Fat-tree k must be even")
            self.half = self.k // 2
            self.cores = _integer(topology["coreSwitchCount"], 1, self.half * self.half)
            bandwidth = _capacity(topology["linkBandwidthBytesPerSecond"])
            placements = _array(topology["hostPlacements"])
            _need(0 < len(placements) <= self.k ** 3 // 4, "Fat-tree host count")
            per_edge = Counter()
            for item in placements:
                _object(item, ("hostId", "pod", "edge"), "host placement")
                host = _id(item["hostId"])
                pod = _integer(item["pod"], 0, self.k - 1)
                edge = _integer(item["edge"], 0, self.half - 1)
                _need(host not in self.placements, "duplicate host placement")
                self.placements[host] = pod, edge
                per_edge[pod, edge] += 1
                _need(per_edge[pod, edge] <= self.half, "too many hosts at one edge")
            for item in assignments:
                _object(item, ("vmId", "hostId"), "VM host assignment")
                vm, host = _id(item["vmId"]), _id(item["hostId"])
                _need(vm in self.vms and vm not in self.hosts and host in self.placements,
                      "unknown/duplicate VM or unplaced host")
                self.hosts[vm] = host
            _need(set(self.hosts) == self.vms, "VM host assignments must cover all endpoints")
            # k was bounded BEFORE these loops. Include even currently unused
            # physical links, both directions, and access links for placed hosts.
            links = set()
            for host, (pod, edge) in self.placements.items():
                self._duplex(links, "ACC:" + str(host), f"EDGE:{pod}:{edge}")
            for pod in range(self.k):
                for edge in range(self.half):
                    for agg in range(self.half):
                        self._duplex(links, f"EDGE:{pod}:{edge}", f"AGG:{pod}:{agg}")
                for core in range(self.cores):
                    self._duplex(links, f"AGG:{pod}:{core // self.half}", "CORE:" + str(core))
            _need(all(self.capacities.get(key) == bandwidth for key in links),
                  "missing link or inconsistent link bandwidth")
            expected.update(links)
        _need(set(self.capacities) == expected, "missing/extra fabric capacity key")

    @staticmethod
    def _duplex(keys, a, b):
        keys.add("LINK:" + a + "->" + b)
        keys.add("LINK:" + b + "->" + a)

    def location(self, raw):
        result = _location(raw)
        _need(result in self.locations, "unknown fabric location")
        return result

    def vm(self, raw):
        result = _id(raw)
        _need(result in self.vms, "unknown VM endpoint")
        return result

    def route(self, source, destination):
        _need(source in self.locations and destination in self.locations and
              destination[0] == 0 and source != destination, "invalid remote input endpoints")
        path = []
        if source[0] == 0:
            path.append("VM:" + str(source[1]))
            if self.has_topology:
                sh, dh = self.hosts[source[1]], self.hosts[destination[1]]
                if sh != dh:
                    sp, se = self.placements[sh]
                    dp, de = self.placements[dh]
                    source_edge, destination_edge = f"EDGE:{sp}:{se}", f"EDGE:{dp}:{de}"
                    path.append(f"LINK:ACC:{sh}->{source_edge}")
                    if (sp, se) != (dp, de):
                        available = (self.cores + self.half - 1) // self.half
                        agg = se % available
                        path.append(f"LINK:{source_edge}->AGG:{sp}:{agg}")
                        if sp != dp:
                            choices = min(self.half, self.cores - agg * self.half)
                            core = agg * self.half + (se + de + sp + dp) % choices
                            path.extend((f"LINK:AGG:{sp}:{agg}->CORE:{core}",
                                         f"LINK:CORE:{core}->AGG:{dp}:{agg}"))
                        path.append(f"LINK:AGG:{dp}:{agg}->{destination_edge}")
                    path.append(f"LINK:{destination_edge}->ACC:{dh}")
        # SOURCE deliberately has no endpoint/access/link charge. A VM source
        # shares its one VM resource between sending and receiving.
        path.append("VM:" + str(destination[1]))
        rate = min(self.capacities[key] / count for key, count in Counter(path).items())
        return tuple(path), rate


@dataclass(frozen=True)
class _Origin:
    task: int | None
    job: int | None
    location: tuple
    observed: float


@dataclass(frozen=True)
class _Replica:
    file: tuple
    location: tuple
    visible: float
    acquisition: str
    origin: _Origin
    copied_from: tuple | None = None
    ordinal: int | None = None

    def json(self):
        return dict(fileId=_file_json(self.file), location=_location_json(self.location),
                    visibleAt=self.visible, acquisition=self.acquisition,
                    origin=dict(producerTaskId=self.origin.task, jobAttemptId=self.origin.job,
                                location=_location_json(self.origin.location), observedAt=self.origin.observed),
                    copiedFrom=None if self.copied_from is None else _location_json(self.copied_from),
                    copyOrdinal=self.ordinal)


@dataclass
class _Job:
    task: int
    vm: int
    requested: float
    requested_sequence: int
    reference_count: int
    reference_bytes: float
    resolutions: dict = field(default_factory=dict)
    pending: set = field(default_factory=set)
    isolated_seconds: float = 0.0
    ready: float | None = None
    ready_sequence: int | None = None
    started: float | None = None
    started_sequence: int | None = None
    finished: float | None = None
    finished_sequence: int | None = None
    success: bool | None = None
    ready_queued: bool = False


@dataclass
class _Copy:
    ordinal: int
    job: int
    source: _Replica
    destination: tuple
    release: float
    size: float
    path: tuple
    rate: float
    isolated: float
    admitted_sequence: int
    effective: float | None = None
    observed: float | None = None
    residual: float | None = None
    settled_sequence: int | None = None


def _settlement_bound(copy, effective, residual):
    """Necessary scalar bound, NOT a reconstruction of contended service.

    The subtraction, division and absolute finish use binary64 operations.
    Clock slack is 8 ULPs of the largest clock spacing, capped at 1e-12 of
    the absolute clock scale. Keep this explicit: arbitrary relative epsilons
    (or requiring all B bytes instead of B-residual) change the certificate.
    """
    lower = copy.release + ((copy.size - residual) / copy.rate)
    _need(math.isfinite(lower), "unrepresentable minimum settlement time")
    allowance = min(8.0 * max(math.ulp(copy.release), math.ulp(effective), math.ulp(lower)),
                    1e-12 * max(copy.release, effective, lower))
    _need(effective >= lower or lower - effective <= allowance,
          "settlement precedes the residual-aware standalone minimum")


class _Replay:
    def __init__(self, plan, fabric, observed_through):
        self.plan, self.fabric, self.through = plan, fabric, observed_through
        self.replicas, self.jobs, self.copies, self.active = {}, {}, {}, {}
        self.waiters, self.successful = {}, set()
        self.seed_queue = deque(sorted((fid for fid, f in plan.files.items() if f.producer is None),
                                       key=_file_order))
        self.seed_count = 0
        self.current_request = None
        self.inputs_left = deque()
        self.unlinked_copy = None
        self.ready_due = deque()
        self.previous_time = 0.0

    def accept(self, raw, index):
        _object(raw, ("sequence", "observedTime", "type", "payload"), "event")
        sequence = _count(raw["sequence"], positive=True)
        _need(sequence == index + 1, "nonconsecutive event sequence")
        now = _time(raw["observedTime"])
        _need(self.previous_time <= now <= self.through, "event observation order/watermark")
        self.previous_time = now
        kind = _text(raw["type"])
        _need(kind in PAYLOAD_KEYS, "unknown lifecycle event type")
        payload = _object(raw["payload"], PAYLOAD_KEYS[kind], kind + " payload")
        if self.seed_queue:
            _need(kind == "EXTERNAL_SEEDED" and now == 0, "missing initial external seed")
        elif kind == "EXTERNAL_SEEDED":
            raise Invalid("duplicate/late external seed")
        elif self.ready_due:
            _need(kind == "JOB_DATA_READY", "missing immediate data-ready release")
        elif self.current_request is not None:
            _need(kind in ("COPY_ADMITTED", "INPUT_RESOLVED"), "atomic input request interrupted")
            _need(now == self.jobs[self.current_request].requested, "input request observation changed")
        else:
            _need(kind not in ("COPY_ADMITTED", "INPUT_RESOLVED"), "orphan copy/input resolution")
        getattr(self, "_" + kind.lower())(payload, now, sequence)

    def _job(self, raw):
        ident = _id(raw)
        _need(ident in self.jobs, "unknown/unrequested Job")
        return ident, self.jobs[ident]

    def _source_replica(self, raw):
        _object(raw, ("fileId", "location", "visibleAt", "acquisition", "origin", "copiedFrom",
                      "copyOrdinal"), "sourceReplica")
        origin = _object(raw["origin"], ("producerTaskId", "jobAttemptId", "location", "observedAt"), "origin")
        task = None if origin["producerTaskId"] is None else _id(origin["producerTaskId"])
        job = None if origin["jobAttemptId"] is None else _id(origin["jobAttemptId"])
        root = _Origin(task, job, self.fabric.location(origin["location"]), _time(origin["observedAt"]))
        acquisition = _text(raw["acquisition"])
        _need(acquisition in ACQUISITIONS, "unknown replica acquisition")
        copied = None if raw["copiedFrom"] is None else self.fabric.location(raw["copiedFrom"])
        ordinal = None if raw["copyOrdinal"] is None else _count(raw["copyOrdinal"], positive=True)
        return _Replica(self.plan.file(raw["fileId"]), self.fabric.location(raw["location"]),
                        _time(raw["visibleAt"]), acquisition, root, copied, ordinal)

    def _select(self, fid, destination, now):
        holders = self.replicas.get(fid, {})
        _need(bool(holders) and destination not in holders, "missing visible remote source / already local")
        _need(all(replica.visible <= now for replica in holders.values()), "future source replica")
        if self.plan.files[fid].size == 0:
            # Zero-byte references need no service: every visible holder ties.
            return holders[min(holders)], None, None
        candidates = []
        for location, replica in holders.items():
            path, rate = self.fabric.route(location, destination)
            candidates.append((-rate, location, replica, path))
        negative_rate, _, replica, path = min(candidates, key=lambda item: item[:2])
        return replica, path, -negative_rate

    def _queue_ready(self, ident, now):
        job = self.jobs[ident]
        _need(job.ready is None and not job.ready_queued, "duplicate readiness trigger")
        _need(len(job.resolutions) == len(self.plan.tasks[job.task].inputs) and not job.pending,
              "readiness before all inputs are resolved/visible")
        job.ready_queued = True
        self.ready_due.append((ident, now))

    def _publish(self, replica, now):
        holders = self.replicas.setdefault(replica.file, {})
        # First visibility/provenance wins, including an output appearing while
        # an older copy to that destination is still active.
        holders.setdefault(replica.location, replica)
        target = replica.file, replica.location
        for ident in self.waiters.pop(target, ()):
            job = self.jobs[ident]
            _need(replica.file in job.pending, "inconsistent file waiter")
            job.pending.remove(replica.file)
            if not job.pending:
                self._queue_ready(ident, now)

    def _external_seeded(self, p, now, sequence):
        fid, location = self.plan.file(p["fileId"]), self.fabric.location(p["location"])
        _need(self.seed_queue and fid == self.seed_queue[0] and location == SOURCE and now == 0,
              "external seed identity/location/order")
        self.seed_queue.popleft()
        self._publish(_Replica(fid, SOURCE, now, "EXTERNAL_SEED", _Origin(None, None, SOURCE, now)), now)
        self.seed_count += 1

    def _job_input_requested(self, p, now, sequence):
        ident, vm = _id(p["jobId"]), self.fabric.vm(p["destinationVmId"])
        members = [_id(tid) for tid in _array(p["taskIds"])]
        _need(len(members) == 1 and members[0] in self.plan.tasks, "Job must request one known Task")
        _need(ident not in self.jobs, "Job attempt already requested")
        task = self.plan.tasks[members[0]]
        _need(all(parent in self.successful for parent in task.parents), "request before control-parent success")
        count = sum(task.inputs.values())
        _need(count <= INT_MAX, "requested references exceed the V2 core-event int32 limit")
        reference_bytes = 0.0
        for fid in sorted(task.inputs, key=_file_order):
            reference_bytes += self.plan.files[fid].size * task.inputs[fid]
            _need(math.isfinite(reference_bytes), "unrepresentable requested reference bytes")
        self.jobs[ident] = _Job(members[0], vm, now, sequence, count, reference_bytes)
        self.current_request = ident
        self.inputs_left = deque(sorted(task.inputs, key=_file_order))
        if not self.inputs_left:
            self.current_request = None
            self._queue_ready(ident, now)

    def _copy_admitted(self, p, now, sequence):
        _need(self.current_request is not None and self.inputs_left and self.unlinked_copy is None,
              "copy admission is not the next unresolved request input")
        fid = self.plan.file(p["fileId"])
        job = self.jobs[self.current_request]
        destination = 0, self.fabric.vm(p["destinationVmId"])
        _need(fid == self.inputs_left[0] and destination == (0, job.vm), "orphan/wrong-target admission")
        size = _number(p["bytes"], positive=True)
        _need(size == self.plan.files[fid].size, "admission bytes differ from file declaration")
        target = fid, destination
        _need(destination not in self.replicas.get(fid, {}) and target not in self.active,
              "copy admitted to local/already-active target")
        ordinal = _count(p["copyOrdinal"], positive=True)
        _need(ordinal == len(self.copies) + 1, "copy ordinals must be consecutive from one")
        expected, path, rate = self._select(fid, destination, now)
        source = self._source_replica(p["sourceReplica"])
        _need(source == expected, "sourceReplica differs from selected first-visible holder/provenance")
        declared_path = tuple(_text(key) for key in _array(p["resources"]))
        _need(declared_path == path, "copy path differs from actual deterministic route")
        _need(_capacity(p["standaloneRate"]) == rate, "incorrect standalone bottleneck")
        isolated = size / rate
        _need(math.isfinite(isolated) and isolated > 0 and math.isfinite(now + isolated) and now + isolated > now,
              "unrepresentable positive isolated duration/absolute finish")
        _need(_number(p["isolatedSeconds"], positive=True) == isolated, "incorrect isolated seconds")
        copy = _Copy(ordinal, self.current_request, source, destination, now, size, path, rate, isolated, sequence)
        self.copies[ordinal], self.active[target] = copy, ordinal
        self.unlinked_copy = ordinal

    def _input_resolved(self, p, now, sequence):
        ident, job = self._job(p["jobId"])
        fid = self.plan.file(p["fileId"])
        _need(ident == self.current_request and self.inputs_left and fid == self.inputs_left[0],
              "duplicate/extra/out-of-order input resolution")
        count = _count(p["referenceCount"], positive=True)
        _need(count == self.plan.tasks[job.task].inputs[fid], "input reference multiplicity mismatch")
        resolution = _text(p["resolution"])
        _need(resolution in ("LOCAL", "ZERO", "NEW_COPY", "JOIN_EXISTING"), "unknown input resolution")
        ordinal = None if p["copyOrdinal"] is None else _count(p["copyOrdinal"], positive=True)
        source = self.fabric.location(p["source"])
        destination, target = (0, job.vm), (fid, (0, job.vm))
        local = self.replicas.get(fid, {}).get(destination)
        if local is not None:
            _need(resolution == "LOCAL" and ordinal is None and source == destination and self.unlinked_copy is None,
                  "LOCAL must use the already-visible destination")
        elif self.plan.files[fid].size == 0:
            expected, _, _ = self._select(fid, destination, now)
            _need(resolution == "ZERO" and ordinal is None and source == expected.location and self.unlinked_copy is None,
                  "ZERO must use the stable visible zero-byte source")
            self._publish(_Replica(fid, destination, now, "ZERO_BYTE_REFERENCE", expected.origin, source), now)
        else:
            active_ordinal = self.active.get(target)
            _need(active_ordinal is not None and ordinal == active_ordinal, "input lacks its active copy ticket")
            copy = self.copies[active_ordinal]
            expected_resolution = "NEW_COPY" if self.unlinked_copy is not None else "JOIN_EXISTING"
            _need(resolution == expected_resolution and source == copy.source.location,
                  "input resolution kind/source does not match the actual active ticket")
            if self.unlinked_copy is not None:
                _need(ordinal == self.unlinked_copy and copy.job == ident, "new copy belongs to another input")
                self.unlinked_copy = None
            job.pending.add(fid)
            self.waiters.setdefault(target, []).append(ident)
            job.isolated_seconds += copy.isolated
            _need(math.isfinite(job.isolated_seconds), "unrepresentable summed isolated input estimate")
        job.resolutions[fid] = (count, resolution, ordinal, source)
        self.inputs_left.popleft()
        if not self.inputs_left:
            _need(self.unlinked_copy is None, "unlinked admission at end of request")
            self.current_request = None
            if not job.pending:
                self._queue_ready(ident, now)

    def _copy_settled(self, p, now, sequence):
        ordinal = _count(p["copyOrdinal"], positive=True)
        _need(ordinal in self.copies, "unknown copy settlement")
        copy = self.copies[ordinal]
        target = copy.source.file, copy.destination
        _need(copy.observed is None and self.active.get(target) == ordinal, "duplicate/inactive copy settlement")
        effective, residual = _time(p["effectiveTime"]), _number(p["remainingAfterService"], nonnegative=True)
        _need(copy.release < effective <= now and copy.release < now, "settlement clocks do not advance admission")
        cap = min(copy.size * .5, max(copy.size * 1e-9, 4.0 * math.ulp(copy.size)))
        _need(residual <= cap, "completion residual exceeds scalar tolerance")
        _settlement_bound(copy, effective, residual)
        copy.effective, copy.observed, copy.residual, copy.settled_sequence = effective, now, residual, sequence
        del self.active[target]
        self._publish(_Replica(copy.source.file, copy.destination, now, "COPY_SETTLEMENT",
                               copy.source.origin, copy.source.location, ordinal), now)

    def _job_data_ready(self, p, now, sequence):
        ident, job = self._job(p["jobId"])
        _need(self.ready_due and self.ready_due[0] == (ident, now), "unexpected/late/duplicate data-ready event")
        _need(job.ready is None and not job.pending and
              len(job.resolutions) == len(self.plan.tasks[job.task].inputs), "data-ready before input coverage")
        _need(all((0, job.vm) in self.replicas.get(fid, {}) for fid in self.plan.tasks[job.task].inputs),
              "data-ready without visible input replicas")
        self.ready_due.popleft()
        job.ready, job.ready_sequence, job.ready_queued = now, sequence, False

    def _job_cpu_started(self, p, now, sequence):
        _, job = self._job(p["jobId"])
        vm = self.fabric.vm(p["vmId"])
        _need(vm == job.vm and job.ready is not None and job.started is None,
              "CPU requires one preexisting readiness release and its fixed VM")
        _need(all((0, vm) in self.replicas.get(fid, {}) for fid in self.plan.tasks[job.task].inputs),
              "CPU input replica not visible")
        job.started, job.started_sequence = now, sequence

    def _task_finished(self, p, now, sequence):
        ident, job = self._job(p["jobId"])
        task, vm = _id(p["taskId"]), self.fabric.vm(p["vmId"])
        success = p["success"]
        _need(type(success) is bool, "Task success must be a JSON boolean")
        _need(task == job.task and vm == job.vm and job.started is not None and job.finished is None,
              "Task finish requires matching started singleton Job/VM and one terminal outcome")
        job.finished, job.finished_sequence, job.success = now, sequence, success
        if success:
            self.successful.add(task)
            origin = _Origin(task, ident, (0, vm), now)
            for fid in self.plan.tasks[task].outputs:
                self._publish(_Replica(fid, (0, vm), now, "TASK_OUTPUT", origin), now)

    def finish(self):
        _need(not self.seed_queue, "COMPLETE capture omits initial external seeds")
        _need(self.current_request is None and not self.inputs_left and self.unlinked_copy is None,
              "COMPLETE capture ends halfway through an atomic input request")
        _need(not self.ready_due, "COMPLETE capture omits a required immediate data-ready release")

    def facts(self):
        jobs, copies, replicas = [], [], []
        for ident, job in sorted(self.jobs.items()):
            inputs = [dict(fileId=_file_json(fid), referenceCount=value[0], resolution=value[1],
                           copyOrdinal=value[2], source=_location_json(value[3]))
                      for fid, value in sorted(job.resolutions.items(), key=lambda item: _file_order(item[0]))]
            jobs.append(dict(jobId=ident, taskIds=[job.task], vmId=job.vm,
                             requestedAt=job.requested, requestedSequence=job.requested_sequence,
                             dataReadyAt=job.ready, dataReadySequence=job.ready_sequence,
                             cpuStartedAt=job.started, cpuStartedSequence=job.started_sequence,
                             finishedAt=job.finished, finishedSequence=job.finished_sequence, success=job.success,
                             referenceCount=job.reference_count, requiredReferenceBytes=job.reference_bytes,
                             isolatedInputSeconds=job.isolated_seconds, inputs=inputs,
                             pendingFiles=[_file_json(fid) for fid in sorted(job.pending, key=_file_order)]))
        for ordinal, copy in sorted(self.copies.items()):
            copies.append(dict(copyOrdinal=ordinal, requestingJobId=copy.job, fileId=_file_json(copy.source.file),
                               sourceReplica=copy.source.json(), destinationVmId=copy.destination[1],
                               bytes=copy.size, resources=list(copy.path), standaloneRate=copy.rate,
                               isolatedSeconds=copy.isolated, releasedAt=copy.release,
                               admittedSequence=copy.admitted_sequence, effectiveTime=copy.effective,
                               observedTime=copy.observed, remainingAfterService=copy.residual,
                               settledSequence=copy.settled_sequence, active=copy.observed is None))
        for fid in sorted(self.replicas, key=_file_order):
            replicas.extend(replica.json() for _, replica in sorted(self.replicas[fid].items()))
        return dict(jobs=jobs, copies=copies, replicas=replicas, successfulTaskIds=sorted(self.successful))


def verify_document(document):
    """Validate a strict-decoded V2 document; return detached facts or Invalid.

    IDs/counters are exact integers in their declared Java ranges. Byte/time/
    rate tokens model binary64 values. Declaration arrays are keyed collections;
    canonical request/seed/output order is derived independently, never from
    caller array order. No active-copy or unfinished-job tail is called DISABLED.
    """
    _object(document, ("schema", "modelKind", "recording", "certificateScope", "policies", "capture",
                       "filePlan", "fabric", "events"), "root")
    _need(_text(document["schema"]) == SCHEMA, "unsupported file-lifecycle schema")
    model = _text(document["modelKind"])
    _need(model in (SHARED, ISOLATED), "unsupported V2 model kind")
    _need(_text(document["certificateScope"]) == CERTIFICATE_SCOPE, "incorrect certificate scope")
    recording = _object(document["recording"], ("mode", "maxTraceRecords"), "recording")
    _need(_text(recording["mode"]) == MODE, "unsupported recording mode")
    budget = _integer(recording["maxTraceRecords"], 1, INT_MAX)
    policies = _object(document["policies"], (*POLICIES, "sharing"), "policies")
    for key, value in POLICIES.items():
        _need(_text(policies[key]) == value, "unsupported policy: " + key)
    sharing = "SHARED_MAX_MIN" if model == SHARED else "ISOLATED_PATH_BOTTLENECK"
    _need(_text(policies["sharing"]) == sharing, "sharing policy/model mismatch")
    capture = _object(document["capture"], ("status", "observedThrough", "retainedRecords", "droppedRecords"), "capture")
    _need(_text(capture["status"]) == "COMPLETE", "only COMPLETE V2 captures are supported (not TRUNCATED/DISABLED)")
    through = _time(capture["observedThrough"])
    events = _array(document["events"])
    _need(_count(capture["retainedRecords"]) == len(events) <= budget and
          _count(capture["droppedRecords"]) == 0, "capture counters/budget mismatch")
    plan, fabric = _Plan(document["filePlan"]), _Fabric(document["fabric"])
    replay = _Replay(plan, fabric, through)
    for index, event in enumerate(events):
        try:
            replay.accept(event, index)
        except Invalid as error:
            raise Invalid(f"event {index + 1}: {error}") from error
    replay.finish()
    jobs = list(replay.jobs.values())
    active = len(replay.active)
    finished = sum(job.finished is not None for job in jobs)
    return dict(status="VALID_COMPLETE", completeCaptureCertified=True, captureStatus="COMPLETE",
                certificateScope=CERTIFICATE_SCOPE, scope=CERTIFICATE_SCOPE,
                contextualRunChecked=False, fluidServiceAccountingCertified=False,
                modelKind=model, observedThrough=through, retainedRecords=len(events), droppedRecords=0,
                certifiedThroughSequence=len(events), tailPhase="OPERATION_BOUNDARY",
                taskCount=len(plan.tasks), fileCount=len(plan.files), locationCount=len(fabric.locations),
                resourceCount=len(fabric.capacities), seedCount=replay.seed_count,
                replicaCount=sum(len(holders) for holders in replay.replicas.values()),
                admissionCount=len(replay.copies), settlementCount=len(replay.copies) - active,
                activeCopyCount=active, requestedJobCount=len(jobs), finishedJobCount=finished,
                dataReadyJobCount=sum(job.ready is not None for job in jobs),
                cpuStartedJobCount=sum(job.started is not None for job in jobs),
                waitingJobCount=sum(job.ready is None for job in jobs),
                readyNotStartedJobCount=sum(job.ready is not None and job.started is None for job in jobs),
                runningJobCount=sum(job.started is not None and job.finished is None for job in jobs),
                unfinishedJobCount=len(jobs) - finished, successfulTaskCount=len(replay.successful),
                lifecycleQuiescent=active == 0 and finished == len(jobs), facts=replay.facts())


def inspect_path(path):
    """Read one UTF-8 V2 sidecar, not a manifest; invalid/unreadable input raises Invalid."""
    try:
        document = decode_json(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as error:
        raise Invalid("cannot read valid UTF-8 file-lifecycle evidence: " + str(path)) from error
    return verify_document(document)
