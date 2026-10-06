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
  const lifecycle = fixtures && (fixtures.reports.find(r => r.name === 'file-lifecycle-typed-stress')
    || fixtures.reports.find(r => r.fileLifecycle && r.fileLifecycle.some(e => e.state === 'COMPLETE')));
  if (lifecycle) {
    const content = fs.readFileSync(lifecycle.path, 'utf8');
    rejected('file-lifecycle-exact-text', inject(content,
      'function spoilFileText(){document.getElementById("file-lifecycle-admitted").textContent="CORRUPTED";}spoilFileText();document.getElementById("run-select").addEventListener("change",spoilFileText);'),
      /Exact V2 text differs at file-lifecycle-admitted/, lifecycle);
    const ordinal = '"copyOrdinal":"1"';assert.ok(content.includes(ordinal), 'V2 fixture must include an exact copy ordinal');
    rejected('file-lifecycle-numeric-coercion', content.replace(ordinal, '"copyOrdinal":1'), /Network exact values must be strings/, lifecycle);
    rejected('file-lifecycle-hidden-panel', inject(content, 'document.getElementById("file-lifecycle-section").style.display="none";'), /V2 panel visibility differs/, lifecycle);
    rejected('file-lifecycle-over-preview-cap', inject(content,
      'function spoilFileRows(){const t=document.getElementById("file-lifecycle-copy-table");if(t.firstElementChild)while(t.children.length<=64)t.appendChild(t.firstElementChild.cloneNode(true));}spoilFileRows();document.getElementById("run-select").addEventListener("change",spoilFileRows);'),
      /V2 copies row count/, lifecycle);
    rejected('file-lifecycle-false-v1-off', inject(content,
      'function spoilFileMode(){document.getElementById("network-section").hidden=false;document.getElementById("network-status").dataset.state="OFF";}spoilFileMode();document.getElementById("run-select").addEventListener("change",spoilFileMode);'),
      /V2 must not be mislabeled as V1\/OFF/, lifecycle);
    const local = path.join(temporary, 'forbidden-lifecycle.json');fs.writeFileSync(local, '{}');
    rejected('file-lifecycle-secondary-fetch', inject(content,
      `addEventListener("resize",()=>{if(innerWidth<500)fetch(${JSON.stringify(pathToFileURL(local).href)}).catch(()=>{});});`),
      /Offline reports must not fetch secondary files/, lifecycle);
  }
  const storage = fixtures && (fixtures.reports.find(r => r.name === 'storage-lifecycle-typed-stress')
    || fixtures.reports.find(r => r.storageLifecycle && r.storageLifecycle.some(e => e.state === 'COMPLETE')));
  if (storage) {
    const content = fs.readFileSync(storage.path, 'utf8');
    rejected('storage-lifecycle-exact-text', inject(content,
      'function spoilStorageText(){document.getElementById("storage-lifecycle-output-admitted").textContent="CORRUPTED";}spoilStorageText();document.getElementById("run-select").addEventListener("change",spoilStorageText);'),
      /Exact V3 text differs at storage-lifecycle-output-admitted/, storage);
    const ordinal = '"copyOrdinal":"1"';assert.ok(content.includes(ordinal), 'V3 fixture must contain an exact copy ordinal');
    rejected('storage-lifecycle-numeric-coercion', content.replace(ordinal, '"copyOrdinal":1'), /Network exact values must be strings/, storage);
    rejected('storage-lifecycle-hidden-panel', inject(content, 'document.getElementById("storage-lifecycle-section").style.display="none";'), /V3 storage panel visibility differs/, storage);
    for (const [kind, id] of [['copies', 'storage-lifecycle-copy-table'], ['jobs', 'storage-lifecycle-job-table']]) rejected('storage-lifecycle-over-'+kind+'-cap', inject(content,
      `function spoilStorageRows(){const t=document.getElementById(${JSON.stringify(id)});if(t.firstElementChild)while(t.children.length<=64)t.appendChild(t.firstElementChild.cloneNode(true));}spoilStorageRows();document.getElementById("run-select").addEventListener("change",spoilStorageRows);`),
      new RegExp('V3 '+kind+' row count'), storage);
    rejected('storage-lifecycle-false-v1-off', inject(content,
      'function spoilStorageMode(){document.getElementById("network-section").hidden=false;document.getElementById("network-status").dataset.state="OFF";}spoilStorageMode();document.getElementById("run-select").addEventListener("change",spoilStorageMode);'),
      /V3 must not be mislabeled as V1\/OFF/, storage);
    const local = path.join(temporary, 'forbidden-storage.json');fs.writeFileSync(local, '{}');
    rejected('storage-lifecycle-secondary-fetch', inject(content,
      `addEventListener("resize",()=>{if(innerWidth<500)fetch(${JSON.stringify(pathToFileURL(local).href)}).catch(()=>{});});`),
      /Offline reports must not fetch secondary files/, storage);
  }
  console.log(JSON.stringify({ status: 'PASSED', checks: results }, null, 2));
} finally {
  fs.rmSync(temporary, { recursive: true, force: true });
}
