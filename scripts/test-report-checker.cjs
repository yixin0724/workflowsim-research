'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { spawnSync } = require('node:child_process');

// Verify the verifier using disposable copies only; original reports and evidence remain untouched.
const input = process.argv[2];
if (!input) throw new Error('Usage: node scripts/test-report-checker.cjs <good-report.html|browser-fixtures.json>');
let report = input, fixtures;
if (input.endsWith('.json')) {
  fixtures = JSON.parse(fs.readFileSync(input, 'utf8'));
  assert.equal(fixtures.schema, 'workflowsim-browser-fixtures-v1');
  assert.ok(fixtures.reports.length > 0);
  report = fixtures.reports[0].path;
}
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'workflowsim-checker-negative-'));
const results = [];
function rejected(name, html, pattern, specification) {
  const target = path.join(temporary, `${name}.html`); fs.writeFileSync(target, html);
  let input = target;
  if (specification) {
    input = path.join(temporary, `${name}.json`);
    fs.writeFileSync(input, JSON.stringify({ schema: 'workflowsim-browser-fixtures-v1', reports: [{ ...specification, path: target }] }));
  }
  const checked = spawnSync(process.execPath, [path.join(__dirname, 'verify-report.cjs'), input], { encoding: 'utf8', timeout: 60000 });
  assert.ifError(checked.error);
  assert.equal(checked.status, 1, `${name} must fail the report gate:\n${checked.stdout}\n${checked.stderr}`);
  assert.match(checked.stderr, pattern);
  results.push({ check: name, exitCode: checked.status });
  return checked;
}
function inject(source, script) {
  assert.ok(source.includes('</body>'));
  return source.replace('</body>', `<script>${script}</script></body>`);
}
try {
  const source = fs.readFileSync(report, 'utf8');
  const late = rejected('late-viewport-http-is-rejected', inject(source,
    'addEventListener("resize",()=>{if(innerWidth<500)fetch("https://offline-check.invalid/resize").catch(()=>{});});'),
    /Offline reports must remain offline through the final viewport change/);
  assert.match(late.stderr, /offline-check\.invalid\/resize/);
  // The positive checker aborts all HTTP requests before actual network access.
  const network = fixtures && (fixtures.reports.find(r => r.name === 'network-typed-stress')
    || fixtures.reports.find(r => r.network && r.network.some(n => n.state === 'COMPLETE')));
  if (network) {
    const content = fs.readFileSync(network.path, 'utf8');
    rejected('network-exact-text', inject(content,
      'function spoilText(){document.getElementById("network-admitted").textContent="CORRUPTED";}spoilText();document.getElementById("run-select").addEventListener("change",spoilText);'),
      /Exact network text differs at network-admitted/, network);
    const needle = '"externalTransferId":"9223372036854775807"';
    if (network.name === 'network-typed-stress') {
      assert.ok(content.includes(needle), 'Typed stress fixture must contain the exact maximum long ID');
      rejected('network-numeric-coercion', content.replace(needle, '"externalTransferId":9223372036854775807'),
        /Network exact values must be strings/, network);
    }
    rejected('network-hidden-panel', inject(content,
      'document.getElementById("network-section").style.display="none";'),
      /Network evidence panel must remain visible/, network);
    rejected('network-over-preview-cap', inject(content,
      'function spoilRows(){const t=document.getElementById("network-flow-table");if(t.firstElementChild)while(t.children.length<=64)t.appendChild(t.firstElementChild.cloneNode(true));}spoilRows();document.getElementById("run-select").addEventListener("change",spoilRows);'),
      /Network flows rendered row count/, network);
    const local = path.join(temporary, 'forbidden-sidecar.json'); fs.writeFileSync(local, '{}');
    rejected('network-secondary-file-fetch', inject(content,
      `addEventListener("resize",()=>{if(innerWidth<500)fetch(${JSON.stringify(pathToFileURL(local).href)}).catch(()=>{});});`),
      /Offline reports must not fetch secondary files/, network);
  }
  console.log(JSON.stringify({ status: 'PASSED', checks: results }, null, 2));
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
