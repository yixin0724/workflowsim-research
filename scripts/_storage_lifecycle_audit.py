"""Independent, stdlib-only V3 bounded-storage lifecycle sidecar verifier.

Decode with ``_network_audit.decode_json`` before ``verify_document``. The closed
V3 grammar reuses only PRIVATE independent V2 lexical/plan/path/replay machinery;
it never invokes a production selector, replica runtime, allocator, or JVM.
V2 public entrypoints remain strictly V2. ``Invalid`` is the existing CheckError.

COMPLETE means a consistent operation-boundary history, not a completed run or
authentication. A prefix may retain output copies or deferred inputs. Scalar
service/residual bounds are necessary conditions, NOT fluid-service accounting.
Platform membership without a topology and all manifest/result joins belong to
the separate contextual validator. No dispatch or bundle loading occurs here.
"""
from __future__ import annotations

from collections import Counter, deque
from pathlib import Path

from _file_lifecycle_audit import (
    Invalid, SOURCE, FILE_IDENTITY, ACQUISITIONS as _V2_ACQUISITIONS,
    PAYLOAD_KEYS as _V2_PAYLOAD_KEYS, _Contract, _Fabric, _Replay, _Origin, _Replica,
    _capacity, _count, _file_json, _file_order, _id, _location_json,
    _need, _number, _object, _text, _verify_document,
)
from _network_audit import decode_json

SCHEMA = "workflowsim-storage-lifecycle-v3"
MODE = "FILE_STORAGE_LIFECYCLE_V3"
CERTIFICATE_SCOPE = "STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3"
SHARED = "COHERENT_STORAGE_DATAFLOW_V3"
ISOLATED = "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3"
LOCAL_INPUTS = "LOCAL_VISIBLE_REPLICA_V3"
STORE_INPUTS = "COMMITTED_STORE_THEN_VM_READ_CACHE_V3"
STORE_READ, STORE_WRITE, STORE_NIC = ("STORE:source:" + suffix for suffix in ("READ", "WRITE", "NIC"))

_POLICIES = dict(fileIdentity=FILE_IDENTITY,
                 release="DEPENDENCY_READY_AT_OBSERVATION_V2",
                 visibility="PER_FILE_SETTLEMENT_OBSERVATION_V2",
                 selection="VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2",
                 sourceAccess="BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3",
                 inputAccess=LOCAL_INPUTS, outputCommit="ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3")
_STORE_POLICIES = dict(_POLICIES, selection=STORE_INPUTS, inputAccess=STORE_INPUTS)
_PAYLOAD_KEYS = dict(_V2_PAYLOAD_KEYS,
    COPY_ADMITTED=("copyOrdinal", "fileId", "bytes", "sourceReplica", "destination", "purpose",
                   "ownerJobId", "resources", "standaloneRate", "isolatedSeconds"),
    INPUT_WAITING_FOR_STORE=("jobId", "fileId", "referenceCount"),
    OUTPUT_RESOLVED=("jobId", "taskId", "fileId", "resolution", "copyOrdinal", "source"))


class _StorageFabric(_Fabric):
    _fields = (*_Fabric._fields, "sourceStorage")
    _requires_hosts = True

    def _extra_resources(self, raw):
        store = _object(raw["sourceStorage"], ("attachmentHostId", "readBandwidthMbPerSecond",
                        "writeBandwidthMbPerSecond", "networkBandwidthMbPerSecond"), "sourceStorage")
        self.store_host = _id(store["attachmentHostId"])
        _need(not self.has_topology or self.store_host in self.placements,
              "SOURCE attachment host is not placed in topology")
        # MB/s itself may be subnormal, but its exact binary64 multiplication
        # by decimal 1e6 must produce a positive normal finite B/s capacity.
        for field, key in (("readBandwidthMbPerSecond", STORE_READ),
                           ("writeBandwidthMbPerSecond", STORE_WRITE),
                           ("networkBandwidthMbPerSecond", STORE_NIC)):
            expected = _capacity(_number(store[field], positive=True) * 1_000_000.0)
            _need(self.capacities.get(key) == expected, "store resource/decimal-MB bandwidth mismatch: " + key)
        return STORE_READ, STORE_WRITE, STORE_NIC

    def route(self, source, destination):
        _need(source in self.locations and destination in self.locations and source != destination,
              "invalid remote storage endpoints")
        reading, writing = source == SOURCE, destination == SOURCE
        _need(not (reading and writing), "store-to-store copy is unsupported")
        path = [STORE_READ, STORE_NIC] if reading else ["VM:" + str(source[1])]
        sh = self.store_host if reading else self.hosts[source[1]]
        dh = self.store_host if writing else self.hosts[destination[1]]
        path.extend(self._links(sh, dh))
        path.extend((STORE_NIC, STORE_WRITE) if writing else ("VM:" + str(destination[1]),))
        rate = min(self.capacities[key] / count for key, count in Counter(path).items())
        return tuple(path), rate


class _StorageReplay(_Replay):
    """Closed V3 state machine with separate SOURCE and typed-target wait indices.

    Inherited code checks exact numbers, immutable first visibility, VM-copy
    joins, CPU/Task transitions, and scalar settlement bounds. The extra queues
    below describe atomic callbacks, not predicted service or scheduling.
    """
    def __init__(self, plan, fabric, observed_through, contract, policies):
        super().__init__(plan, fabric, observed_through, contract, policies)
        self.store_inputs = policies["inputAccess"] == STORE_INPUTS
        self.store_waiters, self.source_waits = {}, {}
        self.resume_due = deque()                # (job, file, SOURCE observation), insertion order
        self.output_owner = None
        self.output_time = None
        self.outputs_left = deque()
        self.output_started = False
        self.output_obligations = set()           # unique files, not attempt/reference multiplicity
        self.output_resolutions = []
        self.copy_purposes = {}

    def _operation_event(self, kind, now):
        if self.seed_queue:
            _need(kind == "EXTERNAL_SEEDED" and now == 0, "missing initial external seed")
        elif kind == "EXTERNAL_SEEDED":
            raise Invalid("duplicate/late external seed")
        elif self.ready_due:
            _need(kind == "JOB_DATA_READY", "missing immediate data-ready release")
        elif self.resume_due:
            _need(kind in ("COPY_ADMITTED", "INPUT_RESOLVED") and now == self.resume_due[0][2],
                  "atomic SOURCE-commit input resumption interrupted/backdated")
        elif self.current_request is not None:
            _need(kind in ("COPY_ADMITTED", "INPUT_RESOLVED", "INPUT_WAITING_FOR_STORE") and
                  now == self.jobs[self.current_request].requested, "atomic input request interrupted")
        elif self.output_owner is not None:
            _need(self.output_started and kind in ("COPY_ADMITTED", "OUTPUT_RESOLVED") and
                  now == self.output_time, "atomic successful-output operation interrupted")
        else:
            _need(kind not in ("COPY_ADMITTED", "INPUT_RESOLVED", "INPUT_WAITING_FOR_STORE", "OUTPUT_RESOLVED"),
                  "orphan copy/input/output resolution")

    def _after_event(self, now):
        # All output VM replicas are published at TASK_FINISHED. Callback
        # notifications and output materialization follow canonical file order,
        # with any immediate readiness/store resumption before the next output.
        if (self.output_owner is not None and not self.output_started and
                not self.ready_due and not self.resume_due):
            if self.outputs_left:
                self.output_started = True
                self._visible(self.outputs_left[0], (0, self.jobs[self.output_owner].vm), now)
            else:
                self.output_owner = self.output_time = None

    def _job_input_requested(self, p, now, sequence):
        super()._job_input_requested(p, now, sequence)
        ident = _id(p["jobId"])
        job = self.jobs[ident]
        # Count/bytes are already computed in canonical plan order, NOT deferred
        # resolution order. Every input remains pending until its VM is visible.
        job.pending.update(self.plan.tasks[job.task].inputs)
        self.source_waits[ident] = {}

    def _next_input(self):
        if self.current_request is not None:
            _need(bool(self.inputs_left), "input request has no undecided file")
            return self.current_request, self.inputs_left[0]
        _need(bool(self.resume_due), "input lacks a request or SOURCE-commit resumption")
        ident, fid, _ = self.resume_due[0]
        return ident, fid

    def _input_context(self, p, now):
        ident, job = self._job(p["jobId"])
        fid = self.plan.file(p["fileId"])
        _need((ident, fid) == self._next_input() and fid in job.pending and fid not in job.resolutions,
              "duplicate/extra/out-of-order input resolution")
        _need(not self.store_inputs or SOURCE in self.replicas.get(fid, {}),
              "SHARED input must commit to SOURCE before even a local VM cache hit")
        return ident, job, fid

    def _input_waiting_for_store(self, p, now, sequence):
        ident, job = self._job(p["jobId"])
        fid = self.plan.file(p["fileId"])
        _need(self.store_inputs and ident == self.current_request and (ident, fid) == self._next_input(),
              "store waiting must decide the next requested SHARED input")
        _need(self.unlinked_copy is None and SOURCE not in self.replicas.get(fid, {}) and
              fid in job.pending and fid not in self.source_waits[ident] and fid not in job.resolutions,
              "store waiting requires one genuinely uncommitted undecided input")
        count = _count(p["referenceCount"], positive=True)
        _need(count == self.plan.tasks[job.task].inputs[fid], "deferred reference multiplicity mismatch")
        self.source_waits[ident][fid] = [count, now, None, sequence, None]
        self.store_waiters.setdefault(fid, []).append(ident)
        super()._input_done(ident, fid, now, sequence)

    def _select(self, fid, destination, now):
        if not self.store_inputs:
            return super()._select(fid, destination, now)
        holders = self.replicas.get(fid, {})
        _need(SOURCE in holders and destination not in holders, "store read lacks commitment / already local")
        source = holders[SOURCE]
        _need(source.visible <= now, "future SOURCE replica")
        if self.plan.files[fid].size == 0:
            return source, None, None
        path, rate = self.fabric.route(SOURCE, destination)
        return source, path, rate

    def _copy_admitted(self, p, now, sequence):
        _need(self.unlinked_copy is None, "copy admission lacks its preceding resolution")
        fid = self.plan.file(p["fileId"])
        owner, job = self._job(p["ownerJobId"])
        purpose = _text(p["purpose"])
        destination = self.fabric.location(p["destination"])
        if self.current_request is not None or self.resume_due:
            _need(purpose == "INPUT" and (owner, fid) == self._next_input() and
                  destination == (0, job.vm) and fid in job.pending and fid not in job.resolutions,
                  "INPUT copy owner/file/fixed VM does not match its pending input")
            _need(not self.store_inputs or SOURCE in self.replicas.get(fid, {}),
                  "store read admission precedes SOURCE commitment")
            expected, path, rate = self._select(fid, destination, now)
        else:
            _need(purpose == "OUTPUT" and owner == self.output_owner and self.output_started and
                  self.outputs_left and fid == self.outputs_left[0] and destination == SOURCE and
                  job.success is True and job.finished == now and fid in self.plan.tasks[job.task].outputs,
                  "OUTPUT copy lacks its successful owner/declared output/atomic callback")
            expected = self.replicas[fid][(0, job.vm)]
            path, rate = self.fabric.route(expected.location, SOURCE)
        self._admit_copy(p, now, sequence, fid, owner, destination, expected, path, rate)
        self.copy_purposes[self.unlinked_copy] = purpose

    def _input_done(self, ident, fid, now, sequence):
        job = self.jobs[ident]
        if job.resolutions[fid][1] in ("LOCAL", "ZERO"):
            job.pending.remove(fid)
        if self.current_request is not None:
            super()._input_done(ident, fid, now, sequence)
        else:
            _need(self.resume_due and self.resume_due[0] == (ident, fid, now), "missing deferred input trigger")
            self.resume_due.popleft()
            wait = self.source_waits[ident][fid]
            _need(wait[2] is None, "duplicate SOURCE-wait resolution")
            wait[2], wait[4] = now, sequence
            if not job.pending:
                self._queue_ready(ident, now)

    def _publish(self, replica, now):
        super()._publish(replica, now)
        if replica.location == SOURCE:
            self.output_obligations.discard(replica.file)
            for ident in self.store_waiters.pop(replica.file, ()):
                _need(replica.file in self.jobs[ident].pending and
                      self.source_waits[ident][replica.file][2] is None, "inconsistent SOURCE waiter")
                self.resume_due.append((ident, replica.file, now))

    def _publish_outputs(self, ident, job, now):
        _need(self.output_owner is None, "nested successful-output callback")
        origin = _Origin(job.task, ident, (0, job.vm), now)
        outputs = self.plan.tasks[job.task].outputs
        for fid in outputs:
            self._put_replica(_Replica(fid, (0, job.vm), now, "TASK_OUTPUT", origin))
            if SOURCE not in self.replicas[fid]:
                self.output_obligations.add(fid)
        self.output_owner, self.output_time = ident, now
        self.outputs_left = deque(outputs)
        self.output_started = False

    def _output_resolved(self, p, now, sequence):
        ident, job = self._job(p["jobId"])
        fid = self.plan.file(p["fileId"])
        task = _id(p["taskId"])
        _need(ident == self.output_owner and self.output_started and self.outputs_left and
              fid == self.outputs_left[0] and task == job.task and job.success is True and job.finished == now,
              "output resolution lacks a matching successful Task callback")
        source = self.fabric.location(p["source"])
        _need(source == (0, job.vm), "output source is not the actual producing VM")
        expected = self.replicas[fid][source]
        resolution = _text(p["resolution"])
        ordinal = None if p["copyOrdinal"] is None else _count(p["copyOrdinal"], positive=True)
        publish_zero = False
        if SOURCE in self.replicas[fid]:
            _need(resolution == "ALREADY_STORED" and ordinal is None and self.unlinked_copy is None,
                  "ALREADY_STORED must refer to an actually committed SOURCE object")
        elif self.plan.files[fid].size == 0:
            _need(resolution == "ZERO" and ordinal is None and self.unlinked_copy is None,
                  "zero output must commit metadata without a copy ordinal")
            publish_zero = True
        else:
            active_ordinal = self.active.get((fid, SOURCE))
            _need(active_ordinal is not None and ordinal == active_ordinal and
                  self.copy_purposes[active_ordinal] == "OUTPUT", "output lacks its active store-copy ticket")
            expected_resolution = "NEW_COPY" if self.unlinked_copy is not None else "JOIN_EXISTING"
            _need(resolution == expected_resolution, "output resolution does not match actual copy state")
            if self.unlinked_copy is not None:
                copy = self.copies[active_ordinal]
                _need(ordinal == self.unlinked_copy and copy.job == ident and copy.source == expected,
                      "new output copy belongs to another successful output")
                self.unlinked_copy = None
        self.output_resolutions.append((ident, task, fid, resolution, ordinal, source, now, sequence))
        self.outputs_left.popleft()
        self.output_started = False
        if publish_zero:
            self._publish(_Replica(fid, SOURCE, now, "ZERO_BYTE_OUTPUT", expected.origin, source), now)

    def finish(self):
        super().finish()
        _need(not self.resume_due, "COMPLETE capture omits atomic SOURCE-commit input resumption")
        _need(self.output_owner is None and not self.outputs_left,
              "COMPLETE capture omits atomic output resolution/commit")
        # Obligations may remain only as real active uploads. The history can
        # stop there, but cannot claim quiescence or invent a completed run.
        _need(all((fid, SOURCE) in self.active for fid in self.output_obligations),
              "successful output has neither SOURCE closure nor an active upload")

    def _waiting_store_count(self):
        return sum(wait[2] is None for waits in self.source_waits.values() for wait in waits.values())

    def _quiescent(self):
        return super()._quiescent() and not self.output_obligations and self._waiting_store_count() == 0

    def _summary(self):
        return dict(pendingOutputFileCount=len(self.output_obligations),
                    waitingStoreInputCount=self._waiting_store_count(),
                    inputCopyCount=sum(p == "INPUT" for p in self.copy_purposes.values()),
                    outputCopyCount=sum(p == "OUTPUT" for p in self.copy_purposes.values()),
                    outputResolutionCount=len(self.output_resolutions))

    def facts(self):
        result = super().facts()
        for row in result["jobs"]:
            row["sourceWaits"] = [dict(fileId=_file_json(fid), referenceCount=w[0], startedAt=w[1],
                                       resolvedAt=w[2], startedSequence=w[3], resolvedSequence=w[4])
                for fid, w in sorted(self.source_waits[row["jobId"]].items(), key=lambda item: _file_order(item[0]))]
            row["newFileCopies"] = sum(i["resolution"] == "NEW_COPY" for i in row["inputs"])
            row["joinedFileCopies"] = sum(i["resolution"] == "JOIN_EXISTING" for i in row["inputs"])
        for row in result["copies"]:
            copy = self.copies[row["copyOrdinal"]]
            row.update(purpose=self.copy_purposes[copy.ordinal], ownerJobId=copy.job,
                       destination=_location_json(copy.destination),
                       destinationVmId=copy.destination[1] if copy.destination[0] == 0 else None)
        result.update(self._summary())
        result["pendingOutputFiles"] = [_file_json(fid) for fid in sorted(self.output_obligations, key=_file_order)]
        result["outputResolutions"] = [dict(jobId=j, taskId=t, fileId=_file_json(f), resolution=r,
            copyOrdinal=o, source=_location_json(s), observedTime=at, sequence=seq)
            for j, t, f, r, o, s, at, seq in self.output_resolutions]
        return result


_V3 = _Contract(SCHEMA, MODE, CERTIFICATE_SCOPE, SHARED, ISOLATED,
                (tuple(_POLICIES.items()), tuple(_STORE_POLICIES.items())), tuple(_PAYLOAD_KEYS.items()),
                frozenset((*_V2_ACQUISITIONS, "ZERO_BYTE_OUTPUT")), _StorageFabric, _StorageReplay)


def verify_document(document):
    """Verify a strict-decoded V3 sidecar; return detached JSON summary/facts.

    Source waits describe per-file intervals, not an additive wall-clock or
    service-area decomposition. Copy ownership, purpose, provenance and typed
    destinations remain available to the independent enclosing-run validator.
    """
    return _verify_document(document, _V3)


def inspect_path(path):
    """Read exactly one standalone UTF-8 V3 sidecar; invalid input raises Invalid."""
    try:
        document = decode_json(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as error:
        raise Invalid("cannot read valid UTF-8 storage-lifecycle evidence: " + str(path)) from error
    return verify_document(document)
