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
  assert.equal(await page.locator('#network-section').isVisible(), true, 'Network evidence panel must remain visible');
  const state = networkState(run);
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
    if (specification.network) assert.equal(specification.network.length, runCount);
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
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true, 'Long network values must remain inside mobile layout');
    }
    assert.equal(await page.locator('img').count(), 0, 'Network labels must remain literal text after run switching');
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, [], 'Offline reports must remain offline through the final viewport change');
    assert.deepEqual(secondaryFiles, [], 'Offline reports must not fetch secondary files');
    return { name: specification.name || path.basename(specification.path), runCount, successful,
      allRows, vmRows, networkPanelChecked: hasNetworkPanel, externalRequests: requests.length, secondaryFiles: secondaryFiles.length, browserErrors: errors.length };
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
