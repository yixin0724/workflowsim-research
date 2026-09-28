'use strict';
const { chromium } = require('playwright-core');
const { pathToFileURL } = require('node:url');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');

async function checkReport(browser, specification, screenshot) {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.setDefaultTimeout(10000);
  const errors = [], requests = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('dialog', async dialog => { errors.push(`Unexpected dialog: ${dialog.message()}`); await dialog.dismiss(); });
  page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
  await page.route(/^https?:\/\//, route => route.abort());
  try {
    await page.goto(pathToFileURL(path.resolve(specification.path)).href);
    await page.waitForSelector('#run-table tr');
    const payload = await page.locator('#payload').textContent();
    const runs = JSON.parse(payload).runs;
    assert.ok(Array.isArray(runs) && runs.length > 0);
    const runCount = await page.locator('#run-select option').count();
    assert.equal(runCount, runs.length);
    assert.equal(await page.locator('#run-table tr').count(), runCount);
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
    assert.deepEqual(errors, []);
    assert.deepEqual(requests, [], 'Offline reports must remain offline through the final viewport change');
    return { name: specification.name || path.basename(specification.path), runCount, successful,
      allRows, vmRows, externalRequests: requests.length, browserErrors: errors.length };
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
