#!/usr/bin/env python3
"""Disposable handcrafted V3 bundle/context oracles; stdlib, no JVM/subprocess.

The lifecycle timelines come from independent hand-declared storage tests.
Manifest/main expectations are assembled from those declarations, never from
verifier facts or generated Java artifacts. All negative bundles repair hashes.
"""
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
from _file_lifecycle_context import verify_context as verify_v2_context
from _storage_lifecycle_audit import verify_document as verify_lifecycle
from _storage_lifecycle_context import inspect_path as inspect_context, verify_context


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


hand = load('storage_context_hand_oracles', HERE / 'test-storage-lifecycle.py')
cli = load('storage_context_cli', HERE / 'verify-network-ledger.py')


def key(row):
    return row['workflowInputIndex'], row['name']


def ordered(row):
    return row['workflowInputIndex'], row['name'].encode('utf-16-be', 'surrogatepass')


def model_document(factory, byte_scale=1000000):
    """Only adapt parser numbering and VM bandwidth units, not lifecycle behavior."""
    # JSON has values, not Python aliases: one hand replica dict may have been
    # reused in several events. Detach occurrences before renumbering origins.
    doc = json.loads(json.dumps(factory()))
    ids = {row['taskId']: i + 1 for i, row in enumerate(sorted(doc['filePlan']['tasks'],
           key=lambda r: (r['workflowInputIndex'], r['taskId'])))}
    for row in doc['filePlan']['tasks']:
        row['taskId'] = ids[row['taskId']]
        row['parents'] = [ids[parent] for parent in row['parents']]
    for row in doc['filePlan']['files']:
        row['bytes'] *= byte_scale
        if row['producerTaskId'] is not None:
            row['producerTaskId'] = ids[row['producerTaskId']]
    for row in doc['fabric']['resources']:
        row['capacityBytesPerSecond'] *= 1000000
    for name in ('readBandwidthMbPerSecond', 'writeBandwidthMbPerSecond', 'networkBandwidthMbPerSecond'):
        doc['fabric']['sourceStorage'][name] *= 1000000
    if doc['fabric']['topology'] is not None:
        doc['fabric']['topology']['linkBandwidthBytesPerSecond'] *= 1000000
    for event in doc['events']:
        p = event['payload']
        if 'taskId' in p:
            p['taskId'] = ids[p['taskId']]
        if 'taskIds' in p:
            p['taskIds'] = [ids[t] for t in p['taskIds']]
        if event['type'] == 'COPY_ADMITTED':
            p['bytes'] *= byte_scale
            p['standaloneRate'] *= 1000000
            if byte_scale != 1000000:
                p['isolatedSeconds'] = p['bytes'] / p['standaloneRate']
            origin = p['sourceReplica']['origin']
            if origin['producerTaskId'] is not None:
                origin['producerTaskId'] = ids[origin['producerTaskId']]
        elif event['type'] == 'COPY_SETTLED':
            p['remainingAfterService'] *= byte_scale
    if factory is hand.deferred_magnitudes:
        # Complete the hand prefix at a legal late observation. Scalar-service
        # certification is intentionally not upgraded into a fluid proof.
        at = 9007199254740992 + 9007199254.740992
        doc['events'] += [hand.settle(4, at, at), hand.ready(11, at), hand.cpu(11, 2, at), hand.finish(11, 2, 2, at + 4)]
        hand.seal(doc)
    return doc


def failure_generator(family='WEIBULL'):
    return dict(family=family, scale=2, shape=1, priorShape=None, priorScale=None, likelihoodPrior=None)


def failure_policy(mode='FAILURE_NONE', budget=0, family='WEIBULL', dense=None, keyed=None):
    if dense is None:
        dense = [] if mode == 'FAILURE_NONE' or keyed else [[failure_generator(family)]]
    keyed = {} if keyed is None else keyed
    return dict(clusteringAlgorithm='FTCLUSTERING_NOOP', monitorMode='MONITOR_NONE',
                generatorMode=mode, distributionFamily=family, maxTotalRetryJobs=budget,
                generatorAddressing='VM_ID_KEYED_ROWS' if keyed else 'DENSE_VM_ID_MATRIX',
                generators=copy.deepcopy(dense), generatorsByVmId=copy.deepcopy(keyed))


def retry_only(failures=1, zero_output=False):
    """Input-free attempts isolate STATIC VM inheritance from replica locality."""
    events = []
    for attempt in range(failures + 1):
        jid, start = 10 + attempt, 2 * attempt
        events += [hand.request(jid, 0, 1, start), hand.ready(jid, start), hand.cpu(jid, 1, start),
                   hand.finish(jid, 0, 1, start + 1, success=attempt == failures)]
    if zero_output:
        events.append(hand.output(10 + failures, 0, 'zero', 'ZERO', 1, 2 * failures + 1))
    return hand.document([hand.task(0, outputs=['zero'] if zero_output else [])],
                         [hand.file('zero', 0, 0)] if zero_output else [], events,
                         hand.fabric(((1, 40, 10), (2, 40, 20))))


def remap_failed_attempt(bundle, job=10, vm=2):
    """Repair every per-attempt VM anchor, but deliberately violate retry inheritance."""
    for row in bundle.manifest['result']['jobs'] + bundle.manifest['result']['tasks']:
        if row['jobId'] == job:
            row['vmId'] = vm
    for row in bundle.events:
        if row['jobId'] == job:
            row['vmId'] = vm
    for event in bundle.ledger['events']:
        p = event['payload']
        if p.get('jobId') == job:
            if event['type'] == 'JOB_INPUT_REQUESTED':
                p['destinationVmId'] = vm
            elif 'vmId' in p:
                p['vmId'] = vm


class Bundle:
    def __init__(self, root, factory=lambda: hand.roundtrip(store_inputs=True), enabled=True, byte_scale=1000000):
        self.root, self.enabled = Path(root), enabled
        self.path = self.root / 'result.manifest.json'
        self.ledger = model_document(factory, byte_scale)
        plan = self.ledger['filePlan']
        tasks = {t['taskId']: t for t in plan['tasks']}
        files = {key(f['fileId']): f['bytes'] for f in plan['files']}
        copies = {e['payload']['copyOrdinal']: e['payload'] for e in self.ledger['events'] if e['type'] == 'COPY_ADMITTED'}
        resolutions, starts, terminals, requests = {}, {}, {}, {}
        for event in self.ledger['events']:
            p = event['payload']
            if event['type'] == 'INPUT_RESOLVED':
                resolutions.setdefault(p['jobId'], []).append(p)
            elif event['type'] == 'JOB_CPU_STARTED':
                starts[p['jobId']] = event['observedTime']
            elif event['type'] == 'TASK_FINISHED':
                terminals[p['jobId']] = event
            elif event['type'] == 'JOB_INPUT_REQUESTED':
                requests[p['jobId']] = event
        self.events, jobs, attempts, failed = [], [], [], {}
        for event in self.ledger['events']:
            p, now = event['payload'], event['observedTime']
            jid = p.get('jobId')
            if event['type'] == 'JOB_INPUT_REQUESTED':
                tid, vm = p['taskIds'][0], p['destinationVmId']
                attrs = {}
                if tid in failed:
                    prior = failed.pop(tid)
                    self.events.append(self.main('RETRY_JOB_CREATED', now, jid, tid, vm, failedJobId=prior))
                    attrs['retryOfFailedJobId'] = prior
                self.events.append(self.main('JOB_READY', now, jid, tid, vm, **attrs))
                status = 4 if terminals[jid]['payload']['success'] else 5
                start, finish = starts[jid], terminals[jid]['observedTime']
                jobs.append(dict(jobId=jid, taskIds=[tid], taskCount=1, vmId=vm, classType=2, status=status,
                                 startTime=start, finishTime=finish))
                attempts.append(dict(jobId=jid, taskId=tid, vmId=vm, jobStatus=status, taskStatus=status,
                                     startTime=start, finishTime=finish, exactJobTiming=True))
            elif event['type'] == 'JOB_DATA_READY':
                req = requests[jid]
                tid, vm = req['payload']['taskIds'][0], req['payload']['destinationVmId']
                inputs = tasks[tid]['inputs']
                count, size, seconds, created, joined = sum(r['referenceCount'] for r in inputs), 0.0, 0.0, 0, 0
                for item in sorted(inputs, key=lambda r: ordered(r['fileId'])):
                    size += float(files[key(item['fileId'])]) * item['referenceCount']
                for item in resolutions.get(jid, []):
                    if item['copyOrdinal'] is not None:
                        seconds += copies[item['copyOrdinal']]['isolatedSeconds']
                        created += item['resolution'] == 'NEW_COPY'
                        joined += item['resolution'] == 'JOIN_EXISTING'
                self.events.append(self.main('DATA_STAGE_IN_MODELED', now, jid, tid, vm,
                    modeledTransferFileCount=count, requiredFileBytes=size, modeledTransferSeconds=seconds,
                    newFileCopies=created, joinedFileCopies=joined, transferUnit='LOGICAL_FILE_STORAGE_V3',
                    dataMovementModel=self.ledger['modelKind'], observedInputPreparationSeconds=now - req['observedTime']))
            elif event['type'] == 'JOB_CPU_STARTED':
                tid, vm = terminals[jid]['payload']['taskId'], p['vmId']
                self.events += [self.main('SCHEDULING_DECISION', now, jid, tid, vm, queueDelaySeconds=0, schedulingAlgorithm='STATIC'),
                    self.main('JOB_DISPATCHED', now, jid, tid, vm, queueDelaySeconds=0),
                    self.main('TASK_EXECUTION_MODELED', now, jid, tid, vm, taskId=tid, taskStartTime=now,
                              taskFinishTime=terminals[jid]['observedTime'], modeledStageInSecondsBeforeTask=0,
                              requestedDataStageInSecondsForJob=0)]
            elif event['type'] == 'TASK_FINISHED':
                tid, vm, status = p['taskId'], p['vmId'], 4 if p['success'] else 5
                self.events.append(self.main('JOB_RETURNED', now, jid, tid, vm, jobStatus=status, taskStatuses=[status], postDelaySeconds=0))
                if status == 5:
                    failed[tid] = jid
                    self.events.append(self.main('JOB_FAILED', now, jid, tid, vm, failedStatus=True))
        self.metrics = dict(schema='workflowsim-simulation-metrics-v2', metrics={
            'makespanSeconds': max(e['observedTime'] for e in terminals.values()), 'meanJobVmQueueWaitingTimeSeconds': 0,
            'meanJobVmLevelSlowdown': 1, 'meanJobResponseTimeSeconds': 1,
            'meanComputeTotalWaitingTimeSeconds': 1, 'meanComputeTrueSlowdown': 1})
        physical = self.ledger['fabric']
        actual = {row['vmId']: row['hostId'] for row in physical['vmHostAssignments']}
        hosts = set(actual.values()) | {physical['sourceStorage']['attachmentHostId']}
        capacities = {row['key']: row['capacityBytesPerSecond'] for row in physical['resources']}
        topology, declared = physical['topology'], None
        if topology is not None:
            hosts = {p['hostId'] for p in topology['hostPlacements']}
            half = topology['k'] // 2
            declared = dict(kind='FAT_TREE', k=topology['k'], coreSwitchCount=topology['coreSwitchCount'],
                linkBandwidthMbPerSecond=topology['linkBandwidthBytesPerSecond'] / 1000000,
                hostEdgePlacements={str(p['hostId']): p['pod'] * half + p['edge'] for p in topology['hostPlacements']},
                defaultPlacementPolicy='HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES', routingPolicy='DETERMINISTIC_AL_FARES_FAT_TREE_V1',
                linkDirectionality='INDEPENDENT_DIRECTED_LINKS', externalSourceRouting='BOUNDED_STORAGE_HOST_ATTACHMENT_V3')
        vms = [dict(id=vm, bandwidth=int(capacities['VM:' + str(vm)] / 1000000), preflightHostId=actual[vm]) for vm in sorted(actual)]
        depths, remaining = {}, dict(tasks)
        while remaining:
            for tid, row in list(remaining.items()):
                if all(p in depths for p in row['parents']):
                    depths[tid] = 1 + max((depths[p] for p in row['parents']), default=0)
                    del remaining[tid]
        graph = [dict(taskId=tid, type='hand', depth=depths[tid], parentIds=row['parents'],
                      childIds=[t for t, child in tasks.items() if tid in child['parents']]) for tid, row in sorted(tasks.items())]
        paths, inputs, outcomes = [], [], []
        for scope in sorted({t['workflowInputIndex'] for t in tasks.values()}):
            ids = sorted(t for t, row in tasks.items() if row['workflowInputIndex'] == scope)
            path = '/hand/workflow' + str(scope) + '.dax'
            paths.append(path)
            inputs.append(dict(path=path, taskCount=len(ids)))
            outcomes.append(dict(index=scope, path=path, arrivalSecond=0, firstTaskId=ids[0], lastTaskId=ids[-1], taskCount=len(ids)))
        shared = self.ledger['modelKind'] == 'COHERENT_STORAGE_DATAFLOW_V3'
        profile = dict(kind=self.ledger['modelKind'], accessLinkBandwidthMbPerSecond=0, accessLinkLatencySeconds=0,
            sourceEndpointBandwidthMbPerSecond=0, contentionSemantics='STORAGE_FILES_CHECKED_SHARED_MAX_MIN_V3' if shared else
            'STORAGE_FILES_ISOLATED_PATH_BOTTLENECK_V3', bandwidthUnit='DECIMAL_MB_PER_SECOND',
            transferStartSemantics='CONTROL_READY_REQUEST;STORE_COMMIT_GATE_IF_SHARED;ASYNC_ALL_SUCCESSFUL_OUTPUTS_V3')
        retry_count = sum(not event['payload']['success'] for event in terminals.values())
        failure = failure_policy('FAILURE_ALL' if retry_count else 'FAILURE_NONE', retry_count)
        self.manifest = dict(schema='workflowsim-experiment-manifest-v4',
            configuration=dict(dataMovementModel=profile, vmCount=len(vms), planningAlgorithm='RANDOM', schedulingAlgorithm='STATIC',
                fileSystem='SHARED' if self.ledger['policies']['inputAccess'] == hand.STORE else 'LOCAL',
                clustering=dict(method='NONE'), workflowPaths=paths, workflowArrivalSeconds=[0] * len(paths),
                workflowArrivalSemantics='PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO',
                failureModel=failure,
                overheadModel=dict(workflowEngineDelayInterval=0, bandwidth=0, workflowEngineDelays={}, queueDelays={}, postDelays={}, clusteringDelays={})),
            dataflowPlan=copy.deepcopy(plan), workflowGraph=graph,
            workflowProfile=dict(taskCount=len(tasks), edgeCount=sum(len(t['parents']) for t in tasks.values())),
            inputs=inputs, provenance=dict(execution=dict(workingDirectory='/hand')),
            platform=dict(hosts=[dict(id=h) for h in sorted(hosts)], vms=vms, hostCount=len(hosts), vmCount=len(vms),
                          networkTopology=declared, sourceStorage=copy.deepcopy(physical['sourceStorage'])),
            result=dict(simulationEndSeconds=self.ledger['capture']['observedThrough'], workflowCompletedSuccessfully=True,
                        jobs=jobs, tasks=attempts, actualVmHostAssignments={str(vm): host for vm, host in actual.items()}, workflowOutcomes=outcomes),
            metrics=copy.deepcopy(self.metrics['metrics']), events={}, artifacts=[])
        if enabled:
            self.manifest['configuration']['networkEvidence'] = copy.deepcopy(self.ledger['recording'])
        for role, suffix in [('metrics', 'metrics.json'), ('events', 'events.jsonl')] + ([('storage-lifecycle', 'storage-lifecycle.json')] if enabled else []):
            self.manifest['artifacts'].append(dict(role=role, path='result.' + suffix, sha256='', sizeBytes=0))
        self.resequence()
        self.write()

    @staticmethod
    def main(kind, now, jid, tid, vm, **attrs):
        return dict(sequence=0, type=kind, simulationTime=now, jobId=jid, vmId=vm, classType=2, taskIds=[tid], attributes=attrs)

    def main_event(self, kind, job=11):
        return next(e for e in self.events if e['type'] == kind and e['jobId'] == job)

    def resequence(self):
        for index, event in enumerate(self.events):
            event['sequence'] = index
        self.manifest['events'] = dict(schema='workflowsim-simulation-events-v1', eventCount=len(self.events),
                                       firstSequence=0, lastSequence=len(self.events) - 1)

    def save_manifest(self):
        self.path.write_text(json.dumps(self.manifest, allow_nan=False), encoding='utf-8')

    def rehash(self):
        for row in self.manifest['artifacts']:
            raw = (self.root / row['path']).read_bytes()
            row.update(sha256=hashlib.sha256(raw).hexdigest(), sizeBytes=len(raw))
        self.save_manifest()

    def write(self):
        if self.enabled:
            (self.root / 'result.storage-lifecycle.json').write_text(json.dumps(self.ledger, allow_nan=False), encoding='utf-8')
        (self.root / 'result.metrics.json').write_text(json.dumps(self.metrics, allow_nan=False), encoding='utf-8')
        (self.root / 'result.events.jsonl').write_text(''.join(json.dumps(e, allow_nan=False) + '\n' for e in self.events), encoding='utf-8')
        self.rehash()


class StorageContextTests(unittest.TestCase):
    def bundle(self, factory=lambda: hand.roundtrip(store_inputs=True), enabled=True, byte_scale=1000000):
        temp = tempfile.TemporaryDirectory(prefix='storage-context-hand-')
        self.addCleanup(temp.cleanup)
        return Bundle(temp.name, factory, enabled, byte_scale)

    def bad(self, bundle, write=True):
        if write:
            bundle.write()
        with self.assertRaises(CheckError):
            inspect_path(bundle.path)

    def test_eight_models_access_topology_and_standalone_dispatch(self):
        for isolated in (False, True):
            for store in (False, True):
                for topo in (False, True):
                    b = self.bundle(lambda: hand.roundtrip(store, isolated, topo))
                    with self.subTest(isolated=isolated, store=store, topology=topo):
                        r = inspect_path(b.path)
                        self.assertEqual('VALID_COMPLETE', r['status'])
                        self.assertTrue(r['contextualRunChecked'])
                        self.assertTrue(r['corePlanContextChecked'])
                        self.assertTrue(r['artifactReferencesChecked'])
                        self.assertFalse(r['fluidServiceAccountingCertified'])
                        self.assertEqual((2, 0, 0), (r['admissionCount'], r['waitingStoreInputCount'], r['pendingOutputFileCount']))
                        self.assertEqual(60000000, r['facts']['jobs'][1]['requiredReferenceBytes'])
                        standalone = inspect_path(b.root / 'result.storage-lifecycle.json')
                        self.assertFalse(standalone['contextualRunChecked'])
                        self.assertNotIn('artifactReferencesChecked', standalone)

    def test_zero_unused_sink_outputs_and_cpu_precedes_storage_tail(self):
        b = self.bundle(hand.zero_and_sink)
        r = inspect_path(b.path)
        self.assertEqual((1, 4, 1), (r['facts']['jobs'][0]['finishedAt'], r['observedThrough'], b.metrics['metrics']['makespanSeconds']))
        self.assertTrue(r['lifecycleQuiescent'])
        self.assertEqual(1, b.main_event('JOB_RETURNED', 10)['simulationTime'])
        b.main_event('JOB_RETURNED', 10)['simulationTime'] = 4
        self.bad(b)  # CPU return is not async writeback completion.
        b = self.bundle(hand.zero_and_sink)
        del b.ledger['events'][6]
        hand.seal(b.ledger)
        self.bad(b)
        b = self.bundle(hand.zero_and_sink)
        b.ledger = hand.prefix(b.ledger, 7)
        self.assertFalse(verify_lifecycle(b.ledger)['lifecycleQuiescent'])
        self.bad(b)

    def test_cohorts_cached_inputs_failure_retry_and_same_vm_gate(self):
        for factory in (hand.cohorts, hand.cached_peer, hand.failed_retry, hand.same_vm_gate):
            with self.subTest(factory=factory.__name__):
                b = self.bundle(factory)
                self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        b = self.bundle(hand.same_vm_gate)
        stage = b.main_event('DATA_STAGE_IN_MODELED')
        self.assertEqual((4, 0, 2), (stage['simulationTime'], stage['attributes']['modeledTransferSeconds'],
                                   stage['attributes']['observedInputPreparationSeconds']))
        stage['simulationTime'] = 2
        self.bad(b)

    def test_stage_anchors_and_nominal_versus_observed_wait_are_distinct(self):
        b = self.bundle()
        stage = b.main_event('DATA_STAGE_IN_MODELED')
        self.assertEqual((2, 6, 1, 4), (b.main_event('JOB_READY')['simulationTime'], stage['simulationTime'],
            stage['attributes']['modeledTransferSeconds'], stage['attributes']['observedInputPreparationSeconds']))
        for field, value in (('modeledTransferSeconds', 4), ('observedInputPreparationSeconds', 1),
                             ('requiredFileBytes', 20000000), ('modeledTransferFileCount', 1),
                             ('newFileCopies', 0), ('joinedFileCopies', 1), ('transferUnit', 'LOGICAL_FILE_V2'),
                             ('dataMovementModel', 'COHERENT_FILE_DATAFLOW_V2'), ('contentionTransferGroupCount', 1)):
            broken = self.bundle()
            broken.main_event('DATA_STAGE_IN_MODELED')['attributes'][field] = value
            self.bad(broken)
        stage['simulationTime'] = 2
        b.events.sort(key=lambda e: e['simulationTime'])
        b.resequence()
        self.bad(b)  # Monotonic and rehashed, but using the V2 request-time stage anchor.
        b = self.bundle()
        b.main_event('JOB_READY')['simulationTime'] = 6
        b.events.sort(key=lambda e: e['simulationTime'])
        b.resequence()
        self.bad(b)

    def test_deferred_byte_order_and_resolution_order_seconds(self):
        b = self.bundle(hand.deferred_magnitudes, byte_scale=1)
        stage = b.main_event('DATA_STAGE_IN_MODELED')['attributes']
        self.assertEqual(9007199254740992, stage['requiredFileBytes'])
        expected = (1e-6 + 1e-6) + 9007199254.740992
        wrong_order = (9007199254.740992 + 1e-6) + 1e-6
        self.assertNotEqual(expected, wrong_order)
        self.assertEqual(expected, stage['modeledTransferSeconds'])
        self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        stage['modeledTransferSeconds'] = wrong_order
        self.bad(b)
        b = self.bundle(hand.deferred_magnitudes, byte_scale=1)
        b.main_event('DATA_STAGE_IN_MODELED')['attributes']['requiredFileBytes'] = 9007199254740994
        self.bad(b)

    def test_scoped_same_name_inputs_and_staggered_arrivals(self):
        def workflows():
            name = 'input[7]/scope:unchanged'
            return hand.document([hand.task(0, inputs=[(name, 1)]), hand.task(1, inputs=[(name, 1)], scope=1)],
                [hand.file(name, 10), hand.file(name, 20, scope=1)],
                [hand.seed(name), hand.seed(name, 1), hand.request(10, 0, 1, 0),
                 hand.admit(1, name, hand.replica(name, None, 0, 'EXTERNAL_SEED'), 1, 'INPUT', 10, 10,
                            [hand.READ, hand.NIC, 'VM:1'], 20, .5, 0), hand.resolve(10, name, 'NEW_COPY', None, 0, 1),
                 hand.settle(1, .5, 1), hand.ready(10, 1), hand.cpu(10, 1, 1), hand.finish(10, 0, 1, 2),
                 hand.request(11, 1, 2, 5), hand.admit(2, name, hand.replica(name, None, 0, 'EXTERNAL_SEED', scope=1),
                    2, 'INPUT', 11, 20, [hand.READ, hand.NIC, 'VM:2'], 20, 1, 5, scope=1),
                 hand.resolve(11, name, 'NEW_COPY', None, 5, 2, scope=1), hand.settle(2, 6, 6),
                 hand.ready(11, 6), hand.cpu(11, 2, 6), hand.finish(11, 1, 2, 7)], store_inputs=True)
        b = self.bundle(workflows)
        b.manifest['configuration']['workflowArrivalSeconds'][1] = 5
        b.manifest['result']['workflowOutcomes'][1]['arrivalSecond'] = 5
        b.write()
        report = inspect_path(b.path)
        self.assertEqual((2, 2), (report['fileCount'], report['admissionCount']))
        self.assertTrue(report['contextualRunChecked'])
        b.manifest['configuration']['workflowArrivalSeconds'][1] = 6
        b.manifest['result']['workflowOutcomes'][1]['arrivalSecond'] = 6
        self.bad(b)

    def test_output_uploads_never_enter_input_stage_counters(self):
        b = self.bundle(hand.zero_and_sink)
        stage = b.main_event('DATA_STAGE_IN_MODELED', 10)['attributes']
        self.assertEqual((0, 0, 0, 0), tuple(stage[k] for k in
                         ('modeledTransferFileCount', 'requiredFileBytes', 'modeledTransferSeconds', 'newFileCopies')))
        for field, value in (('modeledTransferFileCount', 1), ('requiredFileBytes', 20000000),
                             ('modeledTransferSeconds', 2), ('newFileCopies', 1), ('joinedFileCopies', 1)):
            b = self.bundle(hand.zero_and_sink)
            b.main_event('DATA_STAGE_IN_MODELED', 10)['attributes'][field] = value
            self.bad(b)
        b = self.bundle(hand.cohorts)
        joined = b.main_event('DATA_STAGE_IN_MODELED', 12)['attributes']
        self.assertEqual((0, 1, .5), tuple(joined[k] for k in ('newFileCopies', 'joinedFileCopies', 'modeledTransferSeconds')))
        joined['joinedFileCopies'] = 0
        self.bad(b)

    def test_off_checks_storage_core_and_is_not_zero_traffic(self):
        for isolated in (False, True):
            for store in (False, True):
                b = self.bundle(lambda: hand.roundtrip(store, isolated), enabled=False)
                r = inspect_path(b.path)
                self.assertEqual('DISABLED', r['status'])
                self.assertTrue(r['corePlanContextChecked'])
                self.assertTrue(r['artifactReferencesChecked'])
                self.assertFalse(r['contextualRunChecked'])
                self.assertFalse(r['completeCaptureCertified'])
                self.assertFalse(r['fluidServiceAccountingCertified'])
                self.assertNotIn('admissionCount', r)
                self.assertNotIn('facts', r)
                self.assertIn('NOT_ZERO_TRAFFIC', r['scope'])
        for change in (lambda b: b.manifest['platform'].pop('sourceStorage'),
                       lambda b: b.manifest['platform']['sourceStorage'].update(attachmentHostId=999),
                       lambda b: b.manifest['configuration']['dataMovementModel'].update(contentionSemantics='V2'),
                       lambda b: b.manifest['dataflowPlan']['tasks'][0].update(workflowInputIndex=9),
                       lambda b: b.manifest['result']['actualVmHostAssignments'].pop('1')):
            b = self.bundle(enabled=False)
            change(b)
            self.bad(b)

    def test_exact_model_profile_and_storage_fields(self):
        for name, value in (('accessLinkBandwidthMbPerSecond', 1), ('accessLinkLatencySeconds', 1),
            ('sourceEndpointBandwidthMbPerSecond', 1), ('bandwidthUnit', 'MEGABITS_PER_SECOND'),
            ('contentionSemantics', 'COHERENT_FILES_CHECKED_SHARED_MAX_MIN_V2'),
            ('transferStartSemantics', 'DEPENDENCY_READY_AT_OBSERVATION_V2')):
            b = self.bundle()
            b.manifest['configuration']['dataMovementModel'][name] = value
            self.bad(b)
        for enabled in (False, True):
            b = self.bundle(enabled=enabled)
            b.manifest['configuration']['dataMovementModel']['extra'] = 0
            self.bad(b)
            b = self.bundle(enabled=enabled)
            b.manifest['platform']['sourceStorage']['sourceId'] = 'source'
            self.bad(b)
        b = self.bundle()
        del b.manifest['configuration']['dataMovementModel']['bandwidthUnit']
        self.bad(b)
        for value in (0, -1, True, '20', None, 1e303):
            b = self.bundle()
            b.manifest['platform']['sourceStorage']['readBandwidthMbPerSecond'] = value
            self.bad(b)
        for field in ('attachmentHostId', 'readBandwidthMbPerSecond', 'writeBandwidthMbPerSecond', 'networkBandwidthMbPerSecond'):
            b = self.bundle()
            b.manifest['platform']['sourceStorage'][field] += 1
            self.bad(b)

    def test_access_policy_independent_of_resource_sharing(self):
        for isolated in (False, True):
            b = self.bundle(lambda: hand.roundtrip(True, isolated))
            b.manifest['configuration']['fileSystem'] = 'LOCAL'
            self.bad(b)
            b = self.bundle(lambda: hand.roundtrip(False, isolated))
            b.manifest['configuration']['fileSystem'] = 'SHARED'
            self.bad(b)
        b = self.bundle(hand.same_vm_gate)
        # Fabric and VM cache remain valid, but the shared commit barrier cannot vanish.
        b.ledger['events'] = b.ledger['events'][:7] + [hand.resolve(11, 'data', 'LOCAL', 1, 2, count=3), hand.ready(11, 2)]
        hand.seal(b.ledger)
        self.bad(b)

    def test_platform_attachment_and_vm_mapping_without_topology(self):
        b = self.bundle()
        b.manifest['platform']['sourceStorage']['attachmentHostId'] = 20  # real host, wrong fabric attachment
        self.bad(b)
        b = self.bundle()
        b.ledger['fabric']['sourceStorage']['attachmentHostId'] = 999
        b.manifest['platform']['sourceStorage']['attachmentHostId'] = 999
        self.assertEqual('VALID_COMPLETE', verify_lifecycle(b.ledger)['status'])
        self.bad(b)  # standalone cannot know undeclared platform host membership
        for change in (lambda b: b.manifest['platform']['vms'][0].update(bandwidth=41),
                       lambda b: b.manifest['platform']['vms'][0].update(preflightHostId=20),
                       lambda b: b.manifest['platform']['vms'][0].update(pinnedHostId=20),
                       lambda b: b.manifest['result']['actualVmHostAssignments'].update({'1': 20}),
                       lambda b: b.ledger['fabric']['vmHostAssignments'][0].update(hostId=20),
                       lambda b: b.manifest['platform'].update(hostCount=99)):
            b = self.bundle()
            change(b)
            self.bad(b)
        b = self.bundle()
        b.manifest['result']['actualVmHostAssignments']['01'] = b.manifest['result']['actualVmHostAssignments'].pop('1')
        self.bad(b)

    def test_topology_source_routing_defaults_and_physical_placement(self):
        b = self.bundle(lambda: hand.roundtrip(True, topology=True))
        b.manifest['platform']['networkTopology'].update(hostEdgePlacements=None, coreSwitchCount=None)
        b.write()
        self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        for change in (lambda t: t.update(externalSourceRouting='BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY'),
                       lambda t: t.update(k=34), lambda t: t.update(k=3), lambda t: t.update(coreSwitchCount=2),
                       lambda t: t.update(linkBandwidthMbPerSecond=6), lambda t: t.update(linkBandwidthMbPerSecond=1e308),
                       lambda t: t.update(routingPolicy='ADAPTIVE'), lambda t: t.update(linkDirectionality='UNDIRECTED'),
                       lambda t: t['hostEdgePlacements'].update({'10': 1, '20': 0})):
            b = self.bundle(lambda: hand.roundtrip(True, topology=True))
            change(b.manifest['platform']['networkTopology'])
            self.bad(b)
        b = self.bundle(lambda: hand.roundtrip(True, topology=True))
        b.manifest['platform']['networkTopology'] = None
        self.bad(b)

    def test_modes_roles_budgets_and_schemas_cannot_downgrade_to_disabled(self):
        changes = (lambda b: b.manifest['configuration'].pop('networkEvidence'),
            lambda b: b.manifest['configuration']['networkEvidence'].update(mode='FILE_LIFECYCLE_V2'),
            lambda b: b.manifest['configuration']['networkEvidence'].update(mode='FLUID_GROUP_LEDGER_V1'),
            lambda b: b.manifest['configuration']['networkEvidence'].update(mode='OFF'),
            lambda b: b.manifest['configuration']['networkEvidence'].update(maxTraceRecords=999),
            lambda b: b.manifest['artifacts'][-1].update(role='file-lifecycle'),
            lambda b: b.manifest['artifacts'][-1].update(role='network-ledger'),
            lambda b: b.manifest['artifacts'].append(copy.deepcopy(b.manifest['artifacts'][-1])),
            lambda b: b.manifest.update(schema='workflowsim-experiment-manifest-v3'),
            lambda b: b.manifest['configuration']['dataMovementModel'].update(kind='COHERENT_FILE_DATAFLOW_V2'),
            lambda b: b.ledger.update(schema='workflowsim-file-lifecycle-v2'),
            lambda b: b.ledger['recording'].update(mode='FILE_LIFECYCLE_V2'))
        for change in changes:
            b = self.bundle()
            change(b)
            self.bad(b)
        for enabled in (False, True):
            b = self.bundle(enabled=enabled)
            b.manifest['configuration']['dataMovementModel']['kind'] = 'LEGACY_WORKFLOWSIM_V1'
            self.bad(b)
            b = self.bundle(enabled=enabled)
            b.manifest['schema'] = 'workflowsim-experiment-manifest-v3'
            self.bad(b)

    def test_every_v3_dispatch_marker_prevents_legacy_off(self):
        for marker in ('model', 'mode', 'role', 'sourceStorage'):
            b = self.bundle()
            b.manifest.pop('dataflowPlan')
            b.manifest['configuration']['dataMovementModel']['kind'] = 'LEGACY_WORKFLOWSIM_V1'
            b.manifest['configuration'].pop('networkEvidence')
            b.manifest['artifacts'].pop()
            b.manifest['platform'].pop('sourceStorage')
            if marker == 'model':
                b.manifest['configuration']['dataMovementModel']['kind'] = 'COHERENT_STORAGE_DATAFLOW_V3'
            elif marker == 'mode':
                b.manifest['configuration']['networkEvidence'] = dict(mode='FILE_STORAGE_LIFECYCLE_V3', maxTraceRecords=1000)
            elif marker == 'role':
                b.manifest['artifacts'].append(dict(role='storage-lifecycle', path='result.storage-lifecycle.json', sha256='', sizeBytes=0))
            else:
                b.manifest['platform']['sourceStorage'] = None
            with self.subTest(marker=marker):
                self.bad(b)
        b = self.bundle()
        b.ledger['schema'] = 'workflowsim-storage-lifecycle-v99'
        b.write()
        with self.assertRaises(CheckError):
            inspect_path(b.root / 'result.storage-lifecycle.json')

    def test_rehashed_output_owner_purpose_provenance_and_paths(self):
        for path, value in ((('events', 4, 'payload', 'ownerJobId'), 11), (('events', 4, 'payload', 'purpose'), 'INPUT'),
            (('events', 4, 'payload', 'sourceReplica', 'origin', 'jobAttemptId'), 11),
            (('events', 4, 'payload', 'destination'), hand.loc(2)),
            (('events', 9, 'payload', 'purpose'), 'OUTPUT'), (('events', 9, 'payload', 'resources'), ['VM:2']),
            (('events', 5, 'payload', 'resolution'), 'ALREADY_STORED'), (('capture', 'status'), 'TRUNCATED')):
            b = self.bundle()
            b.ledger = hand.changed(b.ledger, path, value)
            self.bad(b)
        for index in (4, 5, 7, 8, 9, 10):
            b = self.bundle()
            del b.ledger['events'][index]
            hand.seal(b.ledger)
            self.bad(b)
        b = self.bundle()
        b.ledger = hand.prefix(b.ledger, 11)
        self.assertEqual(1, verify_lifecycle(b.ledger)['activeCopyCount'])
        self.bad(b)

    def test_core_plan_config_graph_scope_and_order_checks(self):
        changes = (lambda b: b.manifest['configuration'].update(planningAlgorithm='HEFT'),
            lambda b: b.manifest['configuration'].update(schedulingAlgorithm='MINMIN'),
            lambda b: b.manifest['configuration']['clustering'].update(method='HORIZONTAL'),
            lambda b: b.manifest['configuration']['failureModel'].update(clusteringAlgorithm='FTCLUSTERING_SR'),
            lambda b: b.manifest['configuration']['failureModel'].update(monitorMode='MONITOR_ALL'),
            lambda b: b.manifest['configuration']['overheadModel'].update(bandwidth=1),
            lambda b: b.manifest['configuration']['overheadModel']['postDelays'].update({'1': {}}),
            lambda b: b.manifest['workflowGraph'][1].update(parentIds=[]),
            lambda b: b.manifest['workflowGraph'][0].update(childIds=[]),
            lambda b: b.manifest['workflowProfile'].update(taskCount=3),
            lambda b: b.manifest['result']['workflowOutcomes'][0].update(firstTaskId=0),
            lambda b: b.manifest['result']['workflowOutcomes'][0].update(taskCount=2147483647),
            lambda b: b.manifest['inputs'][0].update(path='/different.dax'),
            lambda b: b.manifest['dataflowPlan']['tasks'].reverse())
        for change in changes:
            b = self.bundle()
            change(b)
            self.bad(b)
        b = self.bundle()
        b.manifest['configuration']['workflowArrivalSeconds'][0] = 1
        b.manifest['result']['workflowOutcomes'][0]['arrivalSecond'] = 1
        self.bad(b)
        b = self.bundle()
        b.manifest['dataflowPlan']['files'][0]['bytes'] = 'TOKEN'
        b.save_manifest()
        b.path.write_text(b.path.read_text(encoding='utf-8').replace('"TOKEN"', '20000000.00000000000001'), encoding='utf-8')
        self.bad(b, False)

    def test_final_attempt_coverage_windows_status_and_simulation_end(self):
        for change in (lambda b: b.manifest['result']['jobs'].pop(), lambda b: b.manifest['result']['tasks'].pop(),
            lambda b: b.manifest['result']['jobs'].append(copy.deepcopy(b.manifest['result']['jobs'][0])),
            lambda b: b.manifest['result']['tasks'][1].update(taskStatus=5),
            lambda b: b.manifest['result']['tasks'][1].update(finishTime=8),
            lambda b: b.manifest['result']['tasks'][1].update(finishTime=5),
            lambda b: b.manifest['result']['jobs'][1].update(startTime=5),
            lambda b: b.manifest['result']['jobs'][1].update(taskCount=2),
            lambda b: b.manifest['result']['jobs'][1].update(vmId=1),
            lambda b: b.manifest['result'].update(workflowCompletedSuccessfully=False),
            lambda b: b.manifest['result'].update(simulationEndSeconds=6),
            lambda b: b.manifest['result']['tasks'][0].update(exactJobTiming=False)):
            b = self.bundle()
            change(b)
            self.bad(b)
        b = self.bundle()
        b.manifest['result']['tasks'][1].update(finishTime=6.5, exactJobTiming=False)
        b.main_event('TASK_EXECUTION_MODELED')['attributes']['taskFinishTime'] = 6.5
        b.write()
        self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        b = self.bundle(hand.zero_and_sink)
        b.manifest['result']['simulationEndSeconds'] = 1
        self.bad(b)

    def test_main_cpu_envelope_sequences_required_events_and_membership(self):
        for kind, field, value in (('TASK_EXECUTION_MODELED', 'modeledStageInSecondsBeforeTask', 1),
            ('TASK_EXECUTION_MODELED', 'requestedDataStageInSecondsForJob', 1),
            ('TASK_EXECUTION_MODELED', 'taskStartTime', 5), ('TASK_EXECUTION_MODELED', 'taskFinishTime', 6.5),
            ('TASK_EXECUTION_MODELED', 'taskId', 1), ('SCHEDULING_DECISION', 'queueDelaySeconds', 1),
            ('JOB_DISPATCHED', 'queueDelaySeconds', 1), ('JOB_RETURNED', 'postDelaySeconds', 1),
            ('JOB_RETURNED', 'jobStatus', 5), ('JOB_RETURNED', 'taskStatuses', [5])):
            b = self.bundle()
            b.main_event(kind)['attributes'][field] = value
            self.bad(b)
        for field, value in (('vmId', 1), ('taskIds', [1]), ('classType', 1), ('jobId', 999)):
            b = self.bundle()
            b.main_event('TASK_EXECUTION_MODELED')[field] = value
            self.bad(b)
        for kind in ('JOB_READY', 'DATA_STAGE_IN_MODELED', 'SCHEDULING_DECISION', 'JOB_DISPATCHED', 'TASK_EXECUTION_MODELED', 'JOB_RETURNED'):
            b = self.bundle()
            b.events.remove(b.main_event(kind))
            b.resequence()
            self.bad(b)
        b = self.bundle()
        a, c = b.events.index(b.main_event('SCHEDULING_DECISION')), b.events.index(b.main_event('JOB_DISPATCHED'))
        b.events[a], b.events[c] = b.events[c], b.events[a]
        b.resequence()
        self.bad(b)
        b = self.bundle()
        b.events[0]['sequence'] = 1
        self.bad(b)

    def test_none_failure_policy_forbids_observed_failures_and_retries(self):
        for capture in (True, False):
            with self.subTest(capture=capture):
                b = self.bundle(hand.failed_retry, enabled=capture)
                self.assertEqual('VALID_COMPLETE' if capture else 'DISABLED', inspect_path(b.path)['status'])
                b.manifest['configuration']['failureModel'] = failure_policy()
                # Everything except the external failure policy is unchanged;
                # a complete standalone lifecycle remains individually valid.
                self.assertTrue(verify_lifecycle(b.ledger)['lifecycleQuiescent'])
                self.bad(b)

    def test_enabled_retry_budget_equality_and_lower_positive_limit(self):
        for capture in (True, False):
            with self.subTest(capture=capture):
                b = self.bundle(lambda: retry_only(2, zero_output=True), enabled=capture)
                self.assertEqual(2, len([e for e in b.events if e['type'] == 'RETRY_JOB_CREATED']))
                self.assertEqual(2, b.manifest['configuration']['failureModel']['maxTotalRetryJobs'])
                self.assertEqual('VALID_COMPLETE' if capture else 'DISABLED', inspect_path(b.path)['status'])
                b.manifest['configuration']['failureModel']['maxTotalRetryJobs'] = 3
                b.write()
                self.assertEqual('VALID_COMPLETE' if capture else 'DISABLED', inspect_path(b.path)['status'])
                b.manifest['configuration']['failureModel']['maxTotalRetryJobs'] = 1
                self.bad(b)

    def test_static_noop_retry_rejects_consistently_remapped_failed_attempt(self):
        for zero_output in (False, True):
            for capture in (True, False):
                with self.subTest(zero_output=zero_output, capture=capture):
                    b = self.bundle(lambda: retry_only(zero_output=zero_output), enabled=capture)
                    self.assertEqual('VALID_COMPLETE' if capture else 'DISABLED', inspect_path(b.path)['status'])
                    remap_failed_attempt(b)
                    self.assertTrue(verify_lifecycle(b.ledger)['lifecycleQuiescent'])
                    self.bad(b)  # Repaired hashes/each-attempt VM checks cannot hide retry remapping.

    def test_off_result_rows_enforce_retry_lower_bound_and_static_vm(self):
        for remap in (False, True):
            b = self.bundle(lambda: retry_only(1 if remap else 2), enabled=False)
            b.events = [e for e in b.events if e['type'] != 'RETRY_JOB_CREATED']
            for event in b.events:
                event['attributes'].pop('retryOfFailedJobId', None)
            if remap:
                remap_failed_attempt(b)
            else:
                b.manifest['configuration']['failureModel']['maxTotalRetryJobs'] = 1
            b.resequence()
            self.bad(b)  # Deleting main retry rows cannot weaken necessary result-only checks.
        b = self.bundle(lambda: retry_only(0), enabled=False)
        for name in ('jobs', 'tasks'):
            extra = copy.deepcopy(b.manifest['result'][name][0])
            extra.update(jobId=11, startTime=2, finishTime=3)
            b.manifest['result'][name].append(extra)
        b.manifest['result']['simulationEndSeconds'] = 3
        self.bad(b)  # NONE budget0 cannot have another attempt even if both claim success.

    def test_zero_disabled_budget_and_enabled_no_failure_controls(self):
        for capture in (True, False):
            b = self.bundle(lambda: retry_only(0), enabled=capture)
            self.assertEqual('FAILURE_NONE', b.manifest['configuration']['failureModel']['generatorMode'])
            self.assertEqual(0, b.manifest['configuration']['failureModel']['maxTotalRetryJobs'])
            report = inspect_path(b.path)
            self.assertFalse(report['fluidServiceAccountingCertified'])
            # Enabling a generator does not require a failure to have occurred;
            # no random samples or failure probabilities are being certified.
            b.manifest['configuration']['failureModel'] = failure_policy('FAILURE_ALL', 1)
            b.write()
            self.assertEqual('VALID_COMPLETE' if capture else 'DISABLED', inspect_path(b.path)['status'])
        b = self.bundle(lambda: retry_only(0))
        b.manifest['configuration']['failureModel'] = failure_policy(family='NORMAL')
        b.write()
        self.assertTrue(inspect_path(b.path)['contextualRunChecked'])  # unused disabled family may be NORMAL

    def test_modern_failure_budgets_and_generator_layouts_are_strict(self):
        for value in (-1, -2, 0, 2147483648, True, '1', None, .5):
            for capture in (True, False):
                b = self.bundle(lambda: retry_only(0), enabled=capture)
                b.manifest['configuration']['failureModel'] = failure_policy('FAILURE_ALL', 1)
                b.manifest['configuration']['failureModel']['maxTotalRetryJobs'] = value
                self.bad(b)
        changes = (lambda p: p.update(generatorMode='FAILURE_UNKNOWN'),
                   lambda p: p.update(generatorMode='failure_none'),
                   lambda p: p.update(generatorMode=None),
                   lambda p: p.update(generatorAddressing='UNKNOWN'),
                   lambda p: p.update(generatorAddressing='VM_ID_KEYED_ROWS'),
                   lambda p: p.update(generators=[]),
                   lambda p: p.update(generators=[[]]),
                   lambda p: p.update(generators=[None]),
                   lambda p: p.update(generatorsByVmId={'1': [failure_generator()]}),
                   lambda p: p.update(generators=None),
                   lambda p: p.update(generatorsByVmId=None))
        for change in changes:
            b = self.bundle(lambda: retry_only(0))
            b.manifest['configuration']['failureModel'] = failure_policy('FAILURE_ALL', 1)
            change(b.manifest['configuration']['failureModel'])
            self.bad(b)
        for mode in ('FAILURE_ALL', 'FAILURE_JOB'):
            b = self.bundle(lambda: retry_only(0))
            b.manifest['configuration']['failureModel'] = failure_policy(mode, 1, keyed={'1': [failure_generator()]})
            self.bad(b)
        for change in (lambda p: p.update(maxTotalRetryJobs=1), lambda p: p.update(maxTotalRetryJobs=-1),
                       lambda p: p.update(generators=[[failure_generator()]]),
                       lambda p: p.update(generatorsByVmId={'1': [failure_generator()]}),
                       lambda p: p.update(generatorAddressing='VM_ID_KEYED_ROWS')):
            b = self.bundle(lambda: retry_only(0))
            change(b.manifest['configuration']['failureModel'])
            self.bad(b)

    def test_enabled_failure_modes_families_specs_and_symbolic_coverage(self):
        g = failure_generator()
        valid = [failure_policy('FAILURE_ALL', 1),
                 failure_policy('FAILURE_JOB', 1, dense=[[g, g]]),
                 failure_policy('FAILURE_VM', 1, dense=[[g], [g], [g]]),
                 failure_policy('FAILURE_VM', 1, keyed={'1': [g], '2': [g]}),
                 failure_policy('FAILURE_VM_JOB', 1, keyed={'1': [g, g], '2': [g, g], '99': [g, g]})]
        for policy in valid:
            b = self.bundle(lambda: retry_only(0))
            b.manifest['configuration']['failureModel'] = policy
            b.write()
            self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        for family in ('WEIBULL', 'GAMMA', 'LOGNORMAL'):
            b = self.bundle(lambda: retry_only(0))
            p = failure_policy('FAILURE_ALL', 1, family=family)
            p['generators'][0][0].update(priorShape=0, priorScale=-1, likelihoodPrior=2)
            b.manifest['configuration']['failureModel'] = p
            b.write()
            self.assertTrue(inspect_path(b.path)['contextualRunChecked'])
        invalid = [failure_policy('FAILURE_ALL', 1, family='NORMAL'), failure_policy('FAILURE_ALL', 1, family='UNKNOWN'),
                   failure_policy('FAILURE_JOB', 1), failure_policy('FAILURE_VM', 1),
                   failure_policy('FAILURE_VM', 1, keyed={'1': [g]}),
                   failure_policy('FAILURE_VM_JOB', 1, keyed={'1': [g], '2': [g]}),
                   failure_policy('FAILURE_VM', 1, keyed={'01': [g], '2': [g]}),
                   failure_policy('FAILURE_VM', 1, keyed={'-1': [g], '1': [g], '2': [g]}),
                   failure_policy('FAILURE_VM', 1, keyed={'1': [], '2': [g]})]
        for policy in invalid:
            b = self.bundle(lambda: retry_only(0))
            b.manifest['configuration']['failureModel'] = policy
            self.bad(b)
        for field, value in (('family', 'GAMMA'), ('scale', 0), ('scale', True), ('shape', -1),
                             ('priorShape', 1), ('scale', None), ('shape', '1')):
            b = self.bundle(lambda: retry_only(0))
            p = failure_policy('FAILURE_ALL', 1)
            p['generators'][0][0][field] = value
            b.manifest['configuration']['failureModel'] = p
            self.bad(b)

    def test_failure_policy_exact_fields_and_integer_tokens(self):
        for mode, budget in (('FAILURE_NONE', 0), ('FAILURE_ALL', 1)):
            b = self.bundle(lambda: retry_only(0))
            b.manifest['configuration']['failureModel'] = failure_policy(mode, budget)
            b.manifest['workflowGraph'][0]['depth'] = 0
            self.bad(b)
        for field in failure_policy():
            b = self.bundle(lambda: retry_only(0))
            del b.manifest['configuration']['failureModel'][field]
            self.bad(b)
        b = self.bundle(lambda: retry_only(0))
        b.manifest['configuration']['failureModel']['extra'] = None
        self.bad(b)
        b = self.bundle(lambda: retry_only(0))
        b.manifest['configuration']['failureModel'] = failure_policy('FAILURE_ALL', 1)
        b.manifest['configuration']['failureModel']['maxTotalRetryJobs'] = 'TOKEN'
        b.save_manifest()
        raw = b.path.read_text(encoding='utf-8').replace('"TOKEN"', '1.00000000000000000000001')
        b.path.write_text(raw, encoding='utf-8')
        self.bad(b, False)

    def test_v2_failure_policy_and_retry_remapping_acceptance_stays_pinned(self):
        b = self.bundle(retry_only)
        remap_failed_attempt(b)
        root, doc, events = copy.deepcopy((b.manifest, b.ledger, b.events))
        kind = 'COHERENT_FILE_DATAFLOW_V2'
        root['configuration']['failureModel'] = failure_policy()  # intentionally impossible in V3, legacy V2 scope unchanged
        root['configuration']['dataMovementModel'].update(kind=kind,
            contentionSemantics='COHERENT_FILES_CHECKED_SHARED_MAX_MIN_V2',
            transferStartSemantics='DEPENDENCY_READY_AT_OBSERVATION_V2;PER_FILE_SETTLEMENT_OBSERVATION_V2;COALESCED_FILE_DESTINATION')
        root['configuration']['networkEvidence']['mode'] = 'FILE_LIFECYCLE_V2'
        root['platform'].pop('sourceStorage')
        doc.update(schema='workflowsim-file-lifecycle-v2', modelKind=kind,
                   certificateScope='FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2')
        doc['recording']['mode'] = 'FILE_LIFECYCLE_V2'
        doc['policies'].pop('inputAccess')
        doc['policies'].pop('outputCommit')
        doc['policies']['sourceAccess'] = 'UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2'
        doc['fabric'].pop('sourceStorage')
        doc['fabric']['vmHostAssignments'] = []
        doc['fabric']['resources'] = [r for r in doc['fabric']['resources'] if r['key'].startswith('VM:')]
        for event in events:
            if event['type'] == 'DATA_STAGE_IN_MODELED':
                event['attributes'].update(dataMovementModel=kind, transferUnit='LOGICAL_FILE_V2')
                event['attributes'].pop('observedInputPreparationSeconds')
        self.assertTrue(verify_v2_context(root, doc, events)['contextualRunChecked'])

    def test_retry_predecessor_recovery_and_creation_order(self):
        for change in (lambda b: b.main_event('RETRY_JOB_CREATED')['attributes'].update(failedJobId=12),
            lambda b: b.main_event('JOB_READY')['attributes'].update(retryOfFailedJobId=12),
            lambda b: b.events.remove(b.main_event('RETRY_JOB_CREATED')),
            lambda b: b.events.remove(b.main_event('JOB_FAILED', 10))):
            b = self.bundle(hand.failed_retry)
            change(b)
            b.resequence()
            self.bad(b)
        b = self.bundle(hand.failed_retry)
        retry = b.main_event('RETRY_JOB_CREATED')
        b.events.remove(retry)
        retry['simulationTime'] = 2
        b.events.insert(b.events.index(b.main_event('JOB_RETURNED', 10)), retry)
        b.resequence()
        self.bad(b)
        b = self.bundle()
        b.main_event('JOB_READY', 10)['attributes']['retryOfFailedJobId'] = 10
        self.bad(b)

    def test_hash_size_paths_symlinks_and_metrics_snapshot_integrity(self):
        b = self.bundle()
        b.manifest['artifacts'][-1]['sha256'] = '0' * 64
        b.save_manifest()
        self.bad(b, False)
        b = self.bundle()
        b.manifest['artifacts'][-1]['sizeBytes'] += 1
        b.save_manifest()
        self.bad(b, False)
        for path in ('../outside.json', '/outside.json', 'a/b.json', 'a\\b.json', '', '.', '..', 'a\x00b'):
            b = self.bundle()
            b.manifest['artifacts'][-1]['path'] = path
            b.save_manifest()
            self.bad(b, False)
        b = self.bundle()
        outside = self.bundle()
        target = b.root / 'escaped.json'
        target.symlink_to(outside.root / 'result.storage-lifecycle.json')
        b.manifest['artifacts'][-1]['path'] = target.name
        b.rehash()
        self.bad(b, False)
        for change in (lambda b: b.metrics['metrics'].update(makespanSeconds=123),
                       lambda b: b.metrics.update(schema='workflowsim-simulation-metrics-v1')):
            b = self.bundle()
            change(b)
            self.bad(b)
        b = self.bundle()
        del b.metrics['metrics']['meanComputeTrueSlowdown']
        b.manifest['metrics'] = copy.deepcopy(b.metrics['metrics'])
        self.bad(b)

    def test_rehashed_utf8_duplicate_json_and_blank_main_lines(self):
        for name in ('result.storage-lifecycle.json', 'result.metrics.json', 'result.events.jsonl'):
            b = self.bundle()
            (b.root / name).write_bytes(b'\xff')
            b.rehash()
            self.bad(b, False)
        b = self.bundle()
        raw = b.path.read_text(encoding='utf-8')
        b.path.write_text('{"schema":"workflowsim-experiment-manifest-v4",' + raw[1:], encoding='utf-8')
        self.bad(b, False)
        for name, field, value in (('result.storage-lifecycle.json', 'schema', '"workflowsim-storage-lifecycle-v3"'),
            ('result.metrics.json', 'schema', '"workflowsim-simulation-metrics-v2"'), ('result.events.jsonl', 'sequence', '0')):
            b = self.bundle()
            path = b.root / name
            path.write_text('{"' + field + '":' + value + ',' + path.read_text(encoding='utf-8')[1:], encoding='utf-8')
            b.rehash()
            self.bad(b, False)
        b = self.bundle()
        path = b.root / 'result.events.jsonl'
        path.write_text(path.read_text(encoding='utf-8') + '\n', encoding='utf-8')
        b.rehash()
        self.bad(b, False)

    def test_exact_fractional_ids_counts_and_bool_rejection(self):
        for field, token in (('modeledTransferFileCount', '3.0000000000000000000001'),
                             ('newFileCopies', '1.000000000000000000001')):
            b = self.bundle()
            b.main_event('DATA_STAGE_IN_MODELED')['attributes'][field] = 'TOKEN'
            b.write()
            path = b.root / 'result.events.jsonl'
            path.write_text(path.read_text(encoding='utf-8').replace('"TOKEN"', token), encoding='utf-8')
            b.rehash()
            self.bad(b, False)
        b = self.bundle()
        b.manifest['platform']['sourceStorage']['attachmentHostId'] = 'TOKEN'
        b.save_manifest()
        b.path.write_text(b.path.read_text(encoding='utf-8').replace('"TOKEN"', '10.00000000000000001'), encoding='utf-8')
        self.bad(b, False)
        b = self.bundle()
        b.manifest['result']['workflowCompletedSuccessfully'] = 1
        self.bad(b)
        b = self.bundle()
        b.main_event('DATA_STAGE_IN_MODELED')['attributes']['observedInputPreparationSeconds'] = True
        self.bad(b)

    def test_snapshot_read_once_and_detached_public_api(self):
        b = self.bundle(hand.cohorts)
        root, doc, events = (decode_json(json.dumps(v)) for v in (b.manifest, b.ledger, b.events))
        before = copy.deepcopy((root, doc, events))
        with mock.patch('subprocess.Popen', side_effect=AssertionError('no subprocess')):
            result = verify_context(root, doc, events)
        self.assertEqual(before, (root, doc, events))
        result['facts']['jobs'][1]['sourceWaits'][0]['fileId']['name'] = 'changed'
        self.assertEqual(before, (root, doc, events))
        reads = {}
        original = Path.read_bytes
        def snapshot(path):
            reads[path.name] = reads.get(path.name, 0) + 1
            raw = original(path)
            if path.name == 'result.storage-lifecycle.json':
                path.write_text('{"invalid":true}', encoding='utf-8')
            return raw
        with mock.patch.object(Path, 'read_bytes', snapshot):
            self.assertTrue(inspect_path(b.path)['artifactReferencesChecked'])
        self.assertEqual({'result.metrics.json': 1, 'result.events.jsonl': 1, 'result.storage-lifecycle.json': 1}, reads)
        with self.assertRaises(CheckError):
            verify_v2_context(root, doc, events)
        with self.assertRaises(CheckError):
            verify_v1(doc)
        b.write()
        with mock.patch.object(Path, 'read_text', side_effect=AssertionError('manifest already parsed')):
            self.assertTrue(inspect_context(b.path, root)['contextualRunChecked'])

    def test_cli_text_json_off_and_invalid_are_honest(self):
        b = self.bundle()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(b.path)]))
        self.assertIn('STORAGE_LIFECYCLE_CHECK VALID_COMPLETE', output.getvalue())
        self.assertIn('fluidServiceAccountingCertified=false contextualRunChecked=true', output.getvalue())
        self.assertIn('copies=2', output.getvalue())
        self.assertIn('pendingOutputFiles=0 waitingStoreInputs=0', output.getvalue())
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(b.path), '--json']))
        self.assertTrue(json.loads(output.getvalue())['contextualRunChecked'])
        off = self.bundle(enabled=False)
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, cli.main([str(off.path)]))
        self.assertIn('STORAGE_LIFECYCLE_CHECK DISABLED', output.getvalue())
        self.assertNotIn('copies=', output.getvalue())
        b.ledger['events'][4]['payload']['purpose'] = 'INPUT'
        b.write()
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, cli.main([str(b.path), '--json']))
        self.assertEqual('EVIDENCE_INVALID', json.loads(output.getvalue())['status'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
