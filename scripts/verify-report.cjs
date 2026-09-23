const { chromium } = require('playwright-core');
const { pathToFileURL } = require('node:url');
const assert = require('node:assert/strict');

(async () => {
  const report = process.argv[2];
  if (!report) throw new Error('Usage: node verify-report.cjs /absolute/report.html [screenshot.png]');
  const browser = await chromium.launch({
    executablePath: process.env.WORKFLOWSIM_CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true
  });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
    const errors = [], requests = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('request', request => { if (/^https?:/.test(request.url())) requests.push(request.url()); });
    await page.goto(pathToFileURL(report).href);
    await page.waitForSelector('#run-table tr');
    const runCount = await page.locator('#run-select option').count();
    assert.ok(runCount > 0);
    assert.equal(await page.locator('#run-table tr').count(), runCount);
    assert.ok(await page.locator('#comparison svg rect').count() > 0);
    assert.ok(await page.locator('#timeline svg rect').count() > 0);
    assert.ok(await page.locator('#dag-chart svg rect').count() > 0);
    const graphNodes = await page.locator('#dag-chart svg rect').count();
    await page.locator('#graph-select').selectOption({ index: 1 });
    assert.ok(await page.locator('#dag-chart svg rect').count() > 0);
    assert.ok((await page.locator('#graph-note').textContent()).includes('直接前后关系'));
    await page.locator('#graph-select').selectOption('all');
    assert.equal(await page.locator('#dag-chart svg rect').count(), graphNodes);
    const allRows = await page.locator('#task-table tr').count();
    await page.locator('#vm-select').selectOption({ index: 1 });
    const vmRows = await page.locator('#task-table tr').count();
    assert.ok(vmRows <= allRows);
    await page.locator('#task-filter').fill('no-such-task');
    assert.equal(await page.locator('#task-table tr').count(), 0);
    await page.locator('#task-filter').fill('');
    await page.locator('#vm-select').selectOption('all');
    if (runCount > 1) {
      await page.locator('#run-select').selectOption({ index: 1 });
      assert.ok((await page.locator('#conditions').textContent()).length > 20);
    }
    assert.equal(errors.length, 0, errors.join('\n'));
    assert.equal(requests.length, 0, 'Offline reports must make no HTTP requests');
    if (process.argv[3]) await page.screenshot({ path: process.argv[3], fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await page.locator('#run-select').isVisible());
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), true);
    assert.equal(errors.length, 0, errors.join('\n'));
    console.log(JSON.stringify({ status: 'PASSED', runCount, allRows, vmRows, externalRequests: requests.length, browserErrors: errors.length }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
