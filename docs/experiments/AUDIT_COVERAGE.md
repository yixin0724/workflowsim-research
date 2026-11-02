# Audit Coverage Ledger

This page answers one question: **what has actually been verified, by which command, and what is
deliberately out of scope.** It is a ledger of coverage, not a claim that no defect exists. Every
row names the test or command that fails when the property breaks.

Last sweep: 2026-11-02 (review rounds 1-7 plus the dedicated coverage goal).

## 1. Evidence dimensions: a positive case and a tamper case each

| Dimension | Positive (must pass) | Negative (tampering must be refused) |
|---|---|---|
| Energy conservation | `CarbonAccountingTest.intensityUnitLawHoldsOnARealRun`; independent recompute in `verify-study-evidence.py` | `CarbonAccountingTest` corruption cases; auditor self-test `energy-bad` bundle |
| Carbon (intensity) | `CarbonAccountingTest.tieredPlatformIntensityRidesTheConservedPerTierEnergy` | `carbon.totalGrams` / `perVmCarbonGrams` / intensity tampering killed by the auditor (fuzz sweep, §4) |
| Carbon (budget windows) | `CarbonAccountingTest.tieredPlatformBudgetCarbonUsesThePerTierFleetPowerCurve`, `mixedFleetBudgetCarbonMixesTieredAndFlatContributions` | `window.greenWatts` / `totalCarbonExcessWattSeconds` tampering killed (fuzz sweep) |
| DVFS per-tier ledger | `network`-independent: auditor per-tier recompute; `CarbonWindowDefer...` not applicable | `tier[0].mips`, `tier[0].maxPowerWatts`, `job.tierMips` tampering killed (fuzz sweep); `tier-speed-stale` / `tier-undeclared` self-test bundles |
| Network ledger | CI `verify-network-ledger.py` + producer fixtures | `test-network-ledger.py` negative fixtures |
| File lifecycle V2 | CI `verify` fixtures + `_file_lifecycle_audit` | `test-file-lifecycle.py` negative cases |
| Storage lifecycle V3 | CI fixtures + `_storage_lifecycle_audit` | `test-storage-lifecycle.py` negative cases |
| Event stream | `EventStream` ordering/sequence/count checks | out-of-order time, duplicate sequence, blank line, eventCount, first/lastSequence — all refused (Python self-test + Java validator test) |

## 2. Rerun path: which tampering yields which verdict

Measured against a real bundle with `RerunDiffExecutor` (see the fuzz harness note in §4).

| Tampering | Verdict | Exit |
|---|---|---|
| `configuration.vmCount`, `schema`, artifact hash/size, missing artifact, `workflowProfile.taskCount` | `EVIDENCE_INVALID` | 5 |
| input `sha256` | `INPUT_HASH_MISMATCH` | 3 |
| `platform.vms[].mips`, `energyMetrics`, scheduling label, or `rootSeed` on a stochastic workload | `DIVERGED` | 1 |
| malformed tier table, unknown/non-string `frequencyPolicy`, windowless deferral label | `RECONSTRUCTION_REJECTED` | 4 |
| unchanged evidence | `IDENTICAL_CORE` | 0 |

The seed row is proven both ways by `SeedSensitivityRerunIntegrationTest`: a stochastic
failure/retry workload reruns `IDENTICAL_CORE`, and bumping the recorded seed diverges on the
result face. (A tampered seed on a *deterministic* workload cannot diverge — the rerun faithfully
reproduces the recorded conditions, so both sides carry the same seed; that is consistency, not a
miss.)

## 3. Mutation testing: do the tests actually detect breakage?

| Mutant | Outcome |
|---|---|
| Repository gate (`make mutation-gate`, 11 registered mutants) | 11/11 KILLED |
| B1: `mutable()` stops copying (copy-on-write disabled) | KILLED by `RuntimeHistoryIsolationTest` |
| A2: budget always takes the v1 path | KILLED by `CarbonAccountingTest` |
| A1: `frequencyTiers` rebuilt as empty | KILLED by six rerun tests |
| C1: window comparison flipped `>` → `>=` | KILLED by the deferral suite |
| C1: per-Job deferral bound removed | KILLED after the ladder test was strengthened (round 5) |
| Auditor: intensity unit conversion `3.6e6` → `3.6e5` | KILLED by the auditor self-test |
| Auditor: event-sequence-bound check disabled | KILLED by the new self-test case |
| Auditor: per-tier busy-sum assertion disabled | **survives** — redundancy, not a hole: any tampering that could break the equality (same-VM overlap across tiers) is already killed by `vm-overlap` and the attribution checks |

## 4. Cross-language fuzz sweep

Harness: take a real bundle (intensity model and tiered+budget model), mutate one field at a time,
copy the whole run directory, and require `verify-study-evidence.py` to reject. The harness had to
copy the events sidecar; a manifest-only copy makes every case "fail" vacuously.

- 23 mutations per model covering energy, metrics, job intervals/identities, carbon grams and
  excess, window table, tier table and tier identity, intensity, event count and sequence bounds.
- Result: **zero survivors** on fields that feed a recomputed quantity.
- Two fields are declaration-only and cannot be cross-checked from the bundle: a stage-in
  envelope's `taskIds` (empty by construction) and `platform.vms[].pes` (enters no audited formula
  for single-PE workloads). See the reproducibility contract's "Declaration-only fields" note.

## 5. Deliberate boundaries (not defects)

- Budget windows are excluded from the v1 Workbench surface, so `CARBON_WINDOW_DEFER` is rejected at
  Workbench validation with a pointer to the `SimulationRunner` API.
- The frozen `benchmark-standard-dvfs-v1` tar predates the tier rebuild (A1); it remains a valid
  record, and freshly produced tiered evidence now re-runs.
- The deferral engine's bound is per Job, not per workflow: a later ready wave may hop once more,
  until no cleaner declared window remains.

## 6. Commands that constitute the gate

```bash
mvn -Pjavadoc clean verify                                   # unit + integration + coverage + javadoc
python3 -B scripts/check-docs.py                             # link/anchor validation
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py   # 39 auditor self-tests
for t in scripts/test-*.py; do PYTHONDONTWRITEBYTECODE=1 python3 -B "$t"; done
node scripts/verify-report.cjs <fixtures>/browser-fixtures.json     # offline report acceptance
python3 -B scripts/mutation-gate.py --output <new>.json       # registered mutants
```