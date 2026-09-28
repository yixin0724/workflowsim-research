'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

// Verify the verifier using a disposable copy; the original report is never modified.
const input = process.argv[2];
if (!input) throw new Error('Usage: node scripts/test-report-checker.cjs <good-report.html|browser-fixtures.json>');
let report = input;
if (input.endsWith('.json')) {
  const fixtures = JSON.parse(fs.readFileSync(input, 'utf8'));
  assert.equal(fixtures.schema, 'workflowsim-browser-fixtures-v1');
  assert.ok(fixtures.reports.length > 0);
  report = fixtures.reports[0].path;
}
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'workflowsim-checker-negative-'));
try {
  const source = fs.readFileSync(report, 'utf8');
  assert.ok(source.includes('</body>'));
  const injection = '<script>addEventListener("resize",()=>{if(innerWidth<500)fetch("https://offline-check.invalid/resize").catch(()=>{});});</script>';
  const negative = path.join(temporary, 'late-request.html');
  fs.writeFileSync(negative, source.replace('</body>', injection + '</body>'));
  const checked = spawnSync(process.execPath, [path.join(__dirname, 'verify-report.cjs'), negative], {
    encoding: 'utf8', timeout: 60000
  });
  assert.ifError(checked.error);
  assert.equal(checked.status, 1, `A late HTTP request must fail the offline gate:\n${checked.stdout}\n${checked.stderr}`);
  assert.match(checked.stderr, /Offline reports must remain offline through the final viewport change/);
  assert.match(checked.stderr, /offline-check\.invalid\/resize/);
  // verify-report.cjs aborts all HTTP requests before any network access.
  console.log(JSON.stringify({ status: 'PASSED', negativeReportExit: checked.status, check: 'late-viewport-http-is-rejected' }));
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
