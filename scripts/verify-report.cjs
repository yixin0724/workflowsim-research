'use strict';
const { chromium } = require('playwright-core');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

const NETWORK_LIMITS = { flows: 64, resources: 64, events: 128 };
const NETWORK_TABLES = { flows: '#network-flow-table', resources: '#network-resource-table', events: '#network-event-table' };
const exact = value => value === null ? '—' : value;
function networkState(run) {
  if (!run.manifest) return 'NO_RESULT';
  if (storageLifecyclePanel(run)) return 'V3';
  if (fileLifecyclePanel(run)) return 'V2';
  if (!run.networkEvidence) return 'OFF';
  return run.networkEvidence.captureStatus === 'TRUNCATED' ? 'TRUNCATED'
    : run.networkEvidence.recordedAdmissionCount === '0' ? 'ZERO' : 'COMPLETE';
}
function networkValues(view) {
  const t = view.totals, f = view.fct, l = view.locality;
  return { 'network-admitted': t.admittedPayloadBytes, 'network-serviced': t.servicedBalanceDeltaBytes,
    'network-area': t.modeledRateAreaBytes, 'network-residual': t.completionResidualBytes,
    'network-remaining': t.remainingLedgerBytes, 'network-adjustment': t.rateAreaMinusBalanceDeltaBytes,
    'network-fct-count': f.completedSamples, 'network-fct-mean': f.meanEffectiveSeconds,
    'network-fct-p95': f.p95EffectiveSeconds, 'network-notification-lag': f.meanNotificationLagSeconds,
    'network-local-count': l.referenceCount === null ? null : `${l.referenceCount} / ${l.localReferenceCount}`,
    'network-local-bytes': l.localReferenceBytes, 'network-local-fraction': l.localByteFraction,
    'network-grouping-gap': l.admittedMinusTransferableReferenceBytes };
}
function assertNetworkTypes(value, location = 'networkEvidence') {
  if (Array.isArray(value)) value.forEach((v, i) => assertNetworkTypes(v, `${location}/${i}`));
  else if (value && typeof value === 'object') Object.entries(value).forEach(([k, v]) => assertNetworkTypes(v, `${location}/${k}`));
  else assert.notEqual(typeof value, 'number', `Network exact values must be strings at ${location}`);
}
async function checkNetwork(page, run, expected, hasPanel) {
  const required = !!expected || !!run.networkEvidence;
  if (!hasPanel) { assert.equal(required, false, 'Expected network evidence panel is missing'); return; }
  const state = networkState(run);
  if (state === 'V2' || state === 'V3') { if (expected) assert.equal(expected.state, state, 'Unexpected version replacement of a V1 fixture'); assert.equal(await page.locator('#network-section').isVisible(), false, `${state} must not be mislabeled as V1/OFF`); assert.equal(await page.locator('#network-status').getAttribute('data-state'), state); for (const table of Object.values(NETWORK_TABLES)) assert.equal(await page.locator(`${table} tr`).count(), 0, 'Version switch must clear stale V1 rows'); return; }
  assert.equal(await page.locator('#network-section').isVisible(), true, 'Network evidence panel must remain visible');
  if (expected) assert.equal(state, expected.state, 'Network payload state differs from producer fixture expectation');
  assert.equal(await page.locator('#network-status').getAttribute('data-state'), state, 'Selected network state must match');
  const note = await page.locator('#network-note').textContent();
  if (state === 'OFF' || state === 'NO_RESULT') {
    for (const table of Object.values(NETWORK_TABLES)) assert.equal(await page.locator(`${table} tr`).count(), 0, 'Unavailable run must clear stale network rows');
    assert.equal((await page.locator('#network-admitted').textContent()).trim(), '—');
    assert.ok(note.includes(state === 'OFF' ? '不等于零' : '没有已验证'), 'OFF/no-result must not be presented as zero traffic');
    return;
  }
  const view = run.networkEvidence;
  assert.equal(view.schema, 'workflowsim-network-display-v1');
  assertNetworkTypes(view);
  for (const field of ['traceSnapshot', 'bindings', 'evidence']) assert.equal(Object.hasOwn(view, field), false, 'No unbounded raw ledger may be embedded beside previews');
  if (expected) assert.equal(view.contextValidated, expected.contextValidated);
  if (!view.contextValidated) assert.ok(note.includes('仅验证账本内部一致性'), 'Typed projection must not claim run-context validation');
  for (const [kind, limit] of Object.entries(NETWORK_LIMITS)) {
    const preview = view[kind];
    assert.ok(Array.isArray(preview.rows));assert.equal(preview.limit, String(limit));
    assert.ok(preview.rows.length <= limit, `Network ${kind} preview exceeds display cap`);
    assert.equal(preview.shown, String(preview.rows.length));
    if (preview.total === null) { assert.notEqual(kind, 'events');assert.equal(state, 'TRUNCATED');assert.equal(preview.rows.length, 0);assert.equal(preview.omitted, null); }
    else { assert.match(preview.total, /^(0|[1-9][0-9]*)$/);assert.equal(BigInt(preview.total), BigInt(preview.shown) + BigInt(preview.omitted)); }
    assert.equal(await page.locator(`${NETWORK_TABLES[kind]} tr`).count(), preview.rows.length, `Network ${kind} rendered row count`);
  }
  if (expected) {
    assert.equal(view.flows.total, expected.flowTotal);assert.equal(view.flows.shown, expected.flowShown);
    assert.equal(view.resources.total, expected.resourceTotal);assert.equal(view.resources.shown, expected.resourceShown);
    assert.equal(view.events.total, expected.eventTotal);assert.equal(view.events.shown, expected.eventShown);
    assert.deepEqual(networkValues(view), expected.values, 'Network exact summaries must match independently decoded producer data');
    assert.deepEqual(view.flows.rows.map(f => f.externalTransferId), expected.flowIds);
    assert.deepEqual(view.flows.rows.map(f => f.admissionOrdinal), expected.flowOrdinals);
    assert.deepEqual(view.flows.rows.map(f => f.jobId), expected.flowJobs, 'Bindings must join by ordinal rather than reusable transfer ID');
  }
  for (const [id, value] of Object.entries(networkValues(view))) assert.equal(await page.locator(`#${id}`).textContent(), exact(value), `Exact network text differs at ${id}`);
  assert.deepEqual(await page.locator('#network-flow-table tr td:nth-child(2)').allTextContents(), view.flows.rows.map(f => f.externalTransferId));
  assert.deepEqual(await page.locator('#network-flow-table tr td:first-child').allTextContents(), view.flows.rows.map(f => f.admissionOrdinal));
  const jobCells = await page.locator('#network-flow-table tr td:nth-child(3)').allTextContents();
  view.flows.rows.forEach((flow, index) => assert.ok(jobCells[index].startsWith(flow.jobId + ' / '), 'Displayed Job binding differs from admission ordinal'));
  assert.deepEqual(await page.locator('#network-resource-table tr td:first-child').allTextContents(), view.resources.rows.map(r => r.resourceKey));
  assert.deepEqual(await page.locator('#network-event-table tr td:nth-child(3)').allTextContents(), view.events.rows.map(e => exact(e.externalTransferId) + ' / ' + exact(e.admissionOrdinal)));
  assert.deepEqual(await page.locator('#network-event-table tr td:last-child').allTextContents(), view.events.rows.map(e => e.detail));
  const coverage = await page.locator('#network-coverage').textContent();assert.ok(coverage.includes(`实际丢弃 ${view.droppedRecords} 条`));
  if (state === 'TRUNCATED') { assert.ok(note.includes('截断'));assert.equal(view.metricsAvailable, false);assert.ok((await page.locator('#network-flow-note').textContent()).includes('不可用')); }
  else { assert.equal(view.metricsAvailable, true);assert.ok((await page.locator('#network-flow-note').textContent()).includes(`预览省略 ${view.flows.omitted} 项`)); }
  if (state === 'ZERO') { assert.ok(note.includes('记录已开启'));assert.equal(view.totals.admittedPayloadBytes, '0'); }
}

function fileLifecyclePanel(run) {
  if (!run.manifest || storageLifecyclePanel(run)) return false;
  const c = run.manifest.configuration || {}, kind = (c.dataMovementModel || {}).kind;
  return !!run.fileLifecycle || ['COHERENT_FILE_DATAFLOW_V2', 'COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2'].includes(kind) || (c.networkEvidence || {}).mode === 'FILE_LIFECYCLE_V2';
}
function fileLifecycleState(run) {
  if (!run.manifest) return 'NO_RESULT';
  if (!fileLifecyclePanel(run)) return 'HIDDEN';
  if (!run.fileLifecycle) return ((run.manifest.configuration || {}).networkEvidence || {}).mode === 'FILE_LIFECYCLE_V2' ? 'INVALID' : 'OFF';
  return run.fileLifecycle.copyCount === '0' ? 'ZERO' : 'COMPLETE';
}
function fileLifecycleValues(view) {
  const t = view.totals;
  return { 'file-lifecycle-plan-count': `${view.fileCount} / ${view.taskCount}`,
    'file-lifecycle-job-count': `${view.requestedJobCount} / ${view.completedJobCount}`,
    'file-lifecycle-copy-count': `${view.copyCount} / ${view.completedCopyCount} / ${view.activeCopyCount}`,
    'file-lifecycle-reference-count': `${t.referenceCount} / ${t.localReferenceCount} / ${t.joinedReferenceCount}`,
    'file-lifecycle-reference-bytes': t.requiredReferenceBytes, 'file-lifecycle-local-bytes': t.localReferenceBytes,
    'file-lifecycle-admitted': t.admittedPayloadBytes, 'file-lifecycle-settled': t.settledPayloadBytes,
    'file-lifecycle-residual': t.completionResidualBytes, 'file-lifecycle-observed': view.observedThrough };
}
async function checkFileLifecycle(page, run, expected, hasPanel) {
  if (!hasPanel) { assert.equal(!!expected || fileLifecyclePanel(run), false, 'Expected V2 lifecycle panel is missing'); return; }
  const state = fileLifecycleState(run), visible = !['HIDDEN', 'NO_RESULT'].includes(state);
  if (expected) assert.equal(state, expected.state, 'V2 payload state differs from producer expectation');
  assert.equal(await page.locator('#file-lifecycle-section').isVisible(), visible, 'V2 panel visibility differs');
  assert.equal(await page.locator('#file-lifecycle-status').getAttribute('data-state'), state === 'HIDDEN' ? 'OFF' : state);
  const tables = { copies: '#file-lifecycle-copy-table', resources: '#file-lifecycle-resource-table', events: '#file-lifecycle-event-table' };
  if (['HIDDEN', 'NO_RESULT', 'OFF'].includes(state)) {
    for (const table of Object.values(tables)) assert.equal(await page.locator(`${table} tr`).count(), 0, 'Switch must clear stale file lifecycle rows');
    assert.equal(await page.locator('#file-lifecycle-admitted').textContent(), '—');
    if (state === 'OFF') assert.ok((await page.locator('#file-lifecycle-note').textContent()).includes('不等于零'));
    return;
  }
  assert.notEqual(state, 'INVALID', 'Requested V2 evidence cannot silently disappear');
  const view = run.fileLifecycle;assert.equal(view.schema, 'workflowsim-file-lifecycle-display-v2');assert.equal(view.captureStatus, 'COMPLETE');assertNetworkTypes(view, 'fileLifecycle');
  for (const field of ['filePlan', 'fabric', 'evidence', 'traceSnapshot']) assert.equal(Object.hasOwn(view, field), false, 'Do not embed the unbounded V2 document');
  const note = await page.locator('#file-lifecycle-note').textContent();assert.ok(note.includes('不是逐区间流体服务'), 'Lifecycle scope must not claim fluid service accounting');
  if (!view.contextValidated) assert.ok(note.includes('未声明与本页面CPU运行上下文绑定'));
  if (!view.quiescent) assert.ok(note.includes('完整捕获仍包含'));
  for (const [kind, limit] of Object.entries({ copies: 64, resources: 64, events: 128 })) {
    const preview = view[kind];assert.equal(preview.limit, String(limit));assert.ok(preview.rows.length <= limit);assert.equal(preview.shown, String(preview.rows.length));assert.equal(BigInt(preview.total), BigInt(preview.shown) + BigInt(preview.omitted));
    assert.equal(await page.locator(`${tables[kind]} tr`).count(), preview.rows.length, `V2 ${kind} row count`);
  }
  assert.equal(view.copies.total, view.copyCount);
  if (expected) {
    assert.equal(view.contextValidated, expected.contextValidated);
    for (const key of ['copyCount', 'completedCopyCount', 'activeCopyCount', 'fileCount', 'taskCount']) if (Object.hasOwn(expected, key)) assert.equal(view[key], expected[key]);
    if (expected.copySources) assert.deepEqual(view.copies.rows.map(copy => copy.source), expected.copySources);
    if (expected.values) for (const [key, value] of Object.entries(expected.values)) assert.equal(fileLifecycleValues(view)[key], value, `V2 producer quantity ${key}`);
  }
  for (const [id, value] of Object.entries(fileLifecycleValues(view))) assert.equal(await page.locator(`#${id}`).textContent(), exact(value), `Exact V2 text differs at ${id}`);
  const copies = view.copies.rows;
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:first-child').allTextContents(), copies.map(copy => copy.copyOrdinal));
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:nth-child(2)').allTextContents(), copies.map(copy => copy.fileId));
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:nth-child(3)').allTextContents(), copies.map(copy => `${copy.source} → ${copy.destination}`));
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:nth-child(4)').allTextContents(), copies.map(copy => copy.producerTaskId === null ? `外部来源 ${copy.originLocation}` : `Task ${copy.producerTaskId} / Job ${copy.producerJobAttemptId} / ${copy.originLocation}`));
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:nth-child(13)').allTextContents(), copies.map(copy => exact(copy.effectiveFctSeconds)));
  assert.deepEqual(await page.locator('#file-lifecycle-copy-table tr td:nth-child(14)').allTextContents(), copies.map(copy => exact(copy.observedFctSeconds)));
  assert.deepEqual(await page.locator('#file-lifecycle-resource-table tr td:nth-child(2)').allTextContents(), view.resources.rows.map(resource => resource.capacityBytesPerSecond));
  assert.deepEqual(await page.locator('#file-lifecycle-event-table tr td:last-child').allTextContents(), view.events.rows.map(event => event.detail));
  assert.ok((await page.locator('#file-lifecycle-copy-note').textContent()).includes(`预览省略 ${view.copies.omitted} 项`));
}

function storageLifecyclePanel(run) {
  if (!run.manifest) return false;
  const c = run.manifest.configuration || {}, kind = (c.dataMovementModel || {}).kind;
  return !!run.storageLifecycle || ['COHERENT_STORAGE_DATAFLOW_V3', 'COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3'].includes(kind) || (c.networkEvidence || {}).mode === 'FILE_STORAGE_LIFECYCLE_V3';
}
function storageLifecycleState(run) {
  if (!run.manifest) return 'NO_RESULT';if (!storageLifecyclePanel(run)) return 'HIDDEN';
  if (!run.storageLifecycle) return ((run.manifest.configuration || {}).networkEvidence || {}).mode === 'FILE_STORAGE_LIFECYCLE_V3' ? 'INVALID' : 'OFF';
  return run.storageLifecycle.copyCount === '0' ? 'ZERO' : 'COMPLETE';
}
function storageLifecycleValues(v) {
  const s = v.store, t = v.totals;
  return { 'storage-lifecycle-host': s.attachmentHostId, 'storage-lifecycle-read-rate': s.readCapacityBytesPerSecond,
    'storage-lifecycle-write-rate': s.writeCapacityBytesPerSecond, 'storage-lifecycle-nic-rate': s.networkCapacityBytesPerSecond,
    'storage-lifecycle-plan-count': `${v.fileCount} / ${v.taskCount}`, 'storage-lifecycle-job-count': `${v.requestedJobCount} / ${v.completedJobCount}`,
    'storage-lifecycle-copy-count': `${v.copyCount} / ${v.completedCopyCount} / ${v.activeCopyCount}`, 'storage-lifecycle-obligations': `${v.pendingOutputFileCount} / ${v.waitingStoreInputCount}`,
    'storage-lifecycle-input-admitted': t.admittedInputPayloadBytes, 'storage-lifecycle-output-admitted': t.admittedOutputPayloadBytes,
    'storage-lifecycle-input-settled': t.settledInputPayloadBytes, 'storage-lifecycle-output-settled': t.settledOutputPayloadBytes,
    'storage-lifecycle-reference-count': `${t.referenceCount} / ${t.localReferenceCount} / ${t.joinedReferenceCount}`,
    'storage-lifecycle-reference-bytes': t.requiredReferenceBytes, 'storage-lifecycle-local-bytes': t.localReferenceBytes, 'storage-lifecycle-residual': t.completionResidualBytes,
    'storage-lifecycle-last-cpu': v.lastCpuFinishTime, 'storage-lifecycle-last-commit': v.lastStoreCommitTime,
    'storage-lifecycle-tail': v.completedObservedOutputTailSeconds, 'storage-lifecycle-observed': v.observedThrough };
}
async function checkStorageLifecycle(page, run, expected, hasPanel) {
  if (!hasPanel) { assert.equal(!!expected || storageLifecyclePanel(run), false, 'Expected V3 storage panel is missing'); return; }
  const state = storageLifecycleState(run), visible = !['HIDDEN', 'NO_RESULT'].includes(state);
  if (expected) assert.equal(state, expected.state, 'V3 payload state differs from producer expectation');
  assert.equal(await page.locator('#storage-lifecycle-section').isVisible(), visible, 'V3 storage panel visibility differs');
  assert.equal(await page.locator('#storage-lifecycle-status').getAttribute('data-state'), state === 'HIDDEN' ? 'OFF' : state);
  const tables = { copies: '#storage-lifecycle-copy-table', resources: '#storage-lifecycle-resource-table', events: '#storage-lifecycle-event-table', jobs: '#storage-lifecycle-job-table' };
  if (['HIDDEN', 'NO_RESULT', 'OFF'].includes(state)) {
    for (const table of Object.values(tables)) assert.equal(await page.locator(`${table} tr`).count(), 0, 'Switch must clear stale storage rows');
    assert.equal(await page.locator('#storage-lifecycle-output-admitted').textContent(), '—');
    if (state === 'OFF') assert.ok((await page.locator('#storage-lifecycle-note').textContent()).includes('不等于零流量'));
    return;
  }
  assert.notEqual(state, 'INVALID', 'Requested V3 evidence cannot silently disappear');const v = run.storageLifecycle;
  assert.equal(v.schema, 'workflowsim-storage-lifecycle-display-v3');assert.equal(v.captureStatus, 'COMPLETE');assertNetworkTypes(v, 'storageLifecycle');
  for (const field of ['filePlan', 'fabric', 'evidence', 'traceSnapshot']) assert.equal(Object.hasOwn(v, field), false, 'Do not embed unbounded storage evidence');
  const note = await page.locator('#storage-lifecycle-note').textContent();assert.ok(note.includes('不是逐区间流体服务面积'), 'Storage scope must not claim fluid service accounting');
  if (!v.contextValidated) assert.ok(note.includes('未声明与本页面CPU运行上下文绑定'));if (!v.quiescent) assert.ok(note.includes('完整捕获仍有未完成状态'));
  for (const [kind, limit] of Object.entries({ copies: 64, resources: 64, events: 128, jobs: 64 })) {
    const preview = v[kind];assert.equal(preview.limit, String(limit));assert.ok(preview.rows.length <= limit);assert.equal(preview.shown, String(preview.rows.length));assert.equal(BigInt(preview.total), BigInt(preview.shown) + BigInt(preview.omitted));assert.equal(await page.locator(`${tables[kind]} tr`).count(), preview.rows.length, `V3 ${kind} row count`);
  }
  assert.equal(v.copies.total, v.copyCount);assert.equal(v.jobs.total, v.requestedJobCount);
  if (expected) {
    assert.equal(v.contextValidated, expected.contextValidated);
    for (const key of ['copyCount', 'completedCopyCount', 'activeCopyCount', 'fileCount', 'taskCount', 'pendingOutputFileCount', 'waitingStoreInputCount']) if (Object.hasOwn(expected, key)) assert.equal(v[key], expected[key]);
    if (expected.copyPurposes) assert.deepEqual(v.copies.rows.map(c => c.purpose), expected.copyPurposes);
    if (expected.copyOwners) assert.deepEqual(v.copies.rows.map(c => c.ownerJobId), expected.copyOwners);
    if (expected.copySources) assert.deepEqual(v.copies.rows.map(c => c.source), expected.copySources);
    if (expected.values) for (const [key, value] of Object.entries(expected.values)) assert.equal(storageLifecycleValues(v)[key], value, `V3 producer quantity ${key}`);
  }
  for (const [id, value] of Object.entries(storageLifecycleValues(v))) assert.equal(await page.locator(`#${id}`).textContent(), exact(value), `Exact V3 text differs at ${id}`);
  const copies = v.copies.rows;assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:first-child').allTextContents(), copies.map(c => c.copyOrdinal));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(2)').allTextContents(), copies.map(c => c.purpose));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(3)').allTextContents(), copies.map(c => c.ownerJobId));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(4)').allTextContents(), copies.map(c => c.fileId));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(5)').allTextContents(), copies.map(c => `${c.source} → ${c.destination}`));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(6)').allTextContents(), copies.map(c => c.producerTaskId === null ? `外部来源 ${c.originLocation}` : `Task ${c.producerTaskId} / Job ${c.producerJobAttemptId} / ${c.originLocation}`));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(15)').allTextContents(), copies.map(c => exact(c.effectiveFctSeconds)));
  assert.deepEqual(await page.locator('#storage-lifecycle-copy-table tr td:nth-child(16)').allTextContents(), copies.map(c => exact(c.observedFctSeconds)));
  for (const [column, key] of [[1, 'jobId'], [4, 'requestedAt'], [5, 'dataReadyAt'], [6, 'cpuStartedAt'], [7, 'finishedAt'], [8, 'nominalInputSeconds'], [9, 'observedPreparationSeconds'], [10, 'storeGateWaitSeconds']]) assert.deepEqual(await page.locator(`#storage-lifecycle-job-table tr td:nth-child(${column})`).allTextContents(), v.jobs.rows.map(j => exact(j[key])));
  assert.deepEqual(await page.locator('#storage-lifecycle-resource-table tr td:nth-child(2)').allTextContents(), v.resources.rows.map(r => r.capacityBytesPerSecond));
  assert.deepEqual(await page.locator('#storage-lifecycle-event-table tr td:last-child').allTextContents(), v.events.rows.map(e => e.detail));
  assert.ok((await page.locator('#storage-lifecycle-job-note').textContent()).includes('不是可与其他时长相加的分解'));
  assert.ok((await page.locator('#storage-lifecycle-copy-note').textContent()).includes(`预览省略 ${v.copies.omitted} 项`));
}

async function checkReport(browser, specification, screenshot) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(10000);
  const errors = [], requests = [], secondaryFiles = [];
  const documentUrl = pathToFileURL(path.resolve(specification.path)).href;
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', async dialog => { errors.push(`Unexpected dialog: ${dialog.message()}`); await dialog.dismiss(); });
  page.on('request', request => {
    if (/^https?:/.test(request.url())) requests.push(request.url());
    else if (request.url().startsWith('file:') && request.url() !== documentUrl) secondaryFiles.push(request.url());
  });
  await page.route(/^https?:\/\//, route => route.abort());
  try {
    await page.goto(documentUrl);
    await page.waitForSelector('#run-table tr');
    const payload = await page.locator('#payload').textContent();
    const runs = JSON.parse(payload).runs;
    assert.ok(Array.isArray(runs) && runs.length > 0);
    const runCount = await page.locator('#run-select option').count();
    assert.equal(runCount, runs.length);
    assert.equal(await page.locator('#run-table tr').count(), runCount);
    const hasNetworkPanel = await page.locator('#network-section').count() === 1;
    const hasFileLifecyclePanel = await page.locator('#file-lifecycle-section').count() === 1;
    const hasStorageLifecyclePanel = await page.locator('#storage-lifecycle-section').count() === 1;
    if (specification.network) assert.equal(specification.network.length, runCount);
    if (specification.fileLifecycle) assert.equal(specification.fileLifecycle.length, runCount);
    if (specification.storageLifecycle) assert.equal(specification.storageLifecycle.length, runCount);
    assert.equal(await page.locator('img').count(), 0, 'User text must not become executable HTML');
    if (specification.seeds) {
      assert.deepEqual(runs.map(run => run.seed), specification.seeds,
        'Display seeds must be exact decimal strings before and after JSON.parse');
      assert.deepEqual(await page.locator('#run-table tr td:nth-child(2)').allTextContents(), specification.seeds);
      assert.deepEqual(await page.locator('#run-table tr td:first-child').allTextContents(), specification.candidates);
      assert.deepEqual(runs.map(run => run.status), specification.statuses);
    }
    const successful = runs.filter(run => run.manifest && run.status === 'COMPLETED_SUCCESSFULLY').length;
    assert.equal(await page.locator('#comparison svg rect').count(), successful);
    assert.equal((await page.locator('#run-count').textContent()).trim(), `${successful} / ${runCount} 次成功`);
    let allRows = 0, vmRows = 0;
    for (let index = 0; index < runs.length; index++) {
      await page.locator('#run-select').selectOption(String(index));
      const run = runs[index];
      await checkNetwork(page, run, specification.network && specification.network[index], hasNetworkPanel);
      await checkFileLifecycle(page, run, specification.fileLifecycle && specification.fileLifecycle[index], hasFileLifecyclePanel);
      await checkStorageLifecycle(page, run, specification.storageLifecycle && specification.storageLifecycle[index], hasStorageLifecyclePanel);
      if (!run.manifest) {
        assert.equal(await page.locator('#failure').isVisible(), true);
        assert.ok((await page.locator('#failure').textContent()).length > 0);
        assert.equal(await page.locator('#task-table tr').count(), 0);
        assert.equal(await page.locator('#timeline svg rect').count(), 0);
        assert.equal(await page.locator('#dag-chart svg rect').count(), 0);
        continue;
      }
      assert.equal(await page.locator('#failure').isVisible(), false);
      assert.ok((await page.locator('#conditions').textContent()).includes(`seed ${run.seed}`));
      await page.locator('#task-filter').fill('');
      await page.locator('#vm-select').selectOption('all');
      const jobs = run.manifest.result.jobs.filter(job => job.classType === 2);
      const graph = run.manifest.workflowGraph || [];
      allRows = jobs.length;
      assert.equal(await page.locator('#task-table tr').count(), jobs.length);
      assert.equal(await page.locator('#timeline svg rect').count(), Math.min(250, jobs.length));
      assert.equal(await page.locator('#dag-chart svg rect').count(), Math.min(80, graph.length));
      if (graph.length > 0) {
        const focus = graph[graph.length - 1];
        const neighbours = new Set([focus.taskId, ...focus.parentIds, ...focus.childIds]);
        const shown = graph.filter(task => neighbours.has(task.taskId)).slice(0, 80);
        await page.locator('#graph-select').selectOption(String(focus.taskId));
        assert.deepEqual(await page.locator('#dag-chart svg g text').allTextContents(),
          shown.map(task => `任务 ${task.taskId}`));
        await page.locator('#graph-select').selectOption('all');
        assert.equal(await page.locator('#dag-chart svg rect').count(), Math.min(80, graph.length));
      }
      if (run.manifest.platform.vms.length > 0) {
        const id = run.manifest.platform.vms[0].id;
        await page.locator('#vm-select').selectOption(String(id));
        vmRows = jobs.filter(job => job.vmId === id).length;
        assert.equal(await page.locator('#task-table tr').count(), vmRows);
        await page.locator('#task-filter').fill('no-such-task');
        assert.equal(await page.locator('#task-table tr').count(), 0);
        await page.locator('#task-filter').fill('');
        await page.locator('#vm-select').selectOption('all');
      }
    }
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, [], 'Offline reports must make no HTTP requests');
    await page.locator('#run-select').selectOption('0');
    if (screenshot) {
      fs.mkdirSync(path.dirname(path.resolve(screenshot)), { recursive: true });
      await page.screenshot({ path: screenshot, fullPage: true });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    assert.equal(await page.locator('#run-select').isVisible(), true);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
    if (hasNetworkPanel) for (let index = 0; index < runs.length; index++) {
      await page.locator('#run-select').selectOption(String(index));
      await checkNetwork(page, runs[index], specification.network && specification.network[index], hasNetworkPanel);
      await checkFileLifecycle(page, runs[index], specification.fileLifecycle && specification.fileLifecycle[index], hasFileLifecyclePanel);
      await checkStorageLifecycle(page, runs[index], specification.storageLifecycle && specification.storageLifecycle[index], hasStorageLifecyclePanel);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'Long network values must remain inside mobile layout');
    }
    assert.equal(await page.locator('img').count(), 0, 'Network labels must remain literal text after run switching');
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, [], 'Offline reports must remain offline through the final viewport change');
    assert.deepEqual(secondaryFiles, [], 'Offline reports must not fetch secondary files');
    return { name: specification.name || path.basename(specification.path), runCount, successful,
      allRows, vmRows, networkPanelChecked: hasNetworkPanel, fileLifecyclePanelChecked: hasFileLifecyclePanel, storageLifecyclePanelChecked: hasStorageLifecyclePanel, externalRequests: requests.length, secondaryFiles: secondaryFiles.length, browserErrors: errors.length };
  } finally {
    await page.close();
  }
}

(async () => {
  const input = process.argv[2];
  if (!input) throw new Error('Usage: node scripts/verify-report.cjs <report.html|browser-fixtures.json> [screenshot.png|screenshot-directory]');
  const matrix = input.endsWith('.json');
  let reports;
  if (matrix) {
    const fixture = JSON.parse(fs.readFileSync(input, 'utf8'));
    assert.equal(fixture.schema, 'workflowsim-browser-fixtures-v1');
    assert.ok(Array.isArray(fixture.reports) && fixture.reports.length > 0);
    reports = fixture.reports;
  } else {
    reports = [{ path: input }];
  }
  const macChrome = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
  const executablePath = process.env.WORKFLOWSIM_CHROME
    || (process.platform === 'darwin' && fs.existsSync(macChrome) ? macChrome : undefined);
  const browser = await chromium.launch({ executablePath, headless: true });
  try {
    const results = [];
    for (const report of reports) {
      const screenshot = !process.argv[3] ? undefined : matrix
        ? path.join(process.argv[3], `${String(report.name).replace(/[^A-Za-z0-9_.-]/g, '_')}.png`)
        : process.argv[3];
      results.push(await checkReport(browser, report, screenshot));
    }
    console.log(JSON.stringify({ status: 'PASSED', reports: results }, null, 2));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
