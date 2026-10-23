# Mutation Gate (Registered-Mutant Survival)

The mutation gate is a **test-battery honesty check**, not a coverage tool: it verifies that
the maintained test battery still *kills* every registered semantic mutation. A green build
only carries meaning if its tests actually fail when semantics break; this gate proves that
sensitivity on the core decision surfaces, one registered canary per axis.

The registered set lives **in code** — `REGISTRY` in
[scripts/mutation-gate.py](<../../scripts/mutation-gate.py>) — and this page documents scope,
usage, and ledger policy. The concept and the original nine canaries were validated by the
2026-10 mutation pilot (engineering archive), whose two survived mutants exposed real test
blind spots (fluid-completion tolerance magnification; balanced-merge tail unlink) and were
closed with dedicated boundary/conservation tests at that time.

## Registered mutant ledger (scope)

| ID | Semantic break injected | Axis guarded |
|---|---|---|
| M1 | HEFT upward rank takes the first child instead of the maximum | planning rank computation |
| M2 | MCT drops the availability term | static independent-task cost model |
| M3 | Dependency gate inverted (only unsatisfied jobs pass) | engine dependency gating |
| M4 | Fat-tree aggregation index picks `halfK` instead of `availableAggregateCount` | topology routing determinism |
| M5 | Fluid completion tolerance magnified 1000x | numerical tolerance boundary |
| M6 | MI conversion `floor` → `round` at the parser boundary | input quantization semantics |
| M7 | Balanced merge drops the child-side tail unlink | clustering conservation law |
| M8 | Online assignment halves the compute estimate | coherent binding nominal score |
| M9 | Task execution window offset by 1e-6 s | timing-window exactness |

The set is deliberately small and **registered**: it is a canary battery over the semantic
axes that carry research claims, not an exhaustive mutation score for the whole codebase.
One semantic axis, one canary; growth is by deliberate ledger addition (policy below).

## Usage

```bash
# Full battery (~3–5 min; requires a clean Git tree and exclusive run — no parallel builds)
python3 -B scripts/mutation-gate.py --output <new-report.json>

# Ledger + anchor liveness only (no mutation, no Maven; cheap periodic preflight)
python3 -B scripts/mutation-gate.py --output <new-report.json> --dry-run

# Subset by id
python3 -B scripts/mutation-gate.py --output <new-report.json> --ids M5,M9

# Convenience target (report lands in the gitignored engineering archive)
make mutation-gate

# Self-tests (no Maven involved)
python3 -B scripts/test-mutation-gate.py
```

Exit codes: **0** all registered mutants KILLED; **1** a mutant SURVIVED or the build
infrastructure failed while the mutant was applied; **2** at least one registered anchor is
stale (source drifted; the ledger must be updated first). The report is JSON, written only
to a path that does not yet exist (never-overwrite semantics, same discipline as the
independent auditors).

## Execution model and safety

- Each mutant is applied **in place** to the working tree; every target file is restored
  from an in-memory backup in a `finally` block and re-verified byte-equal after restore.
- Full mode refuses to start when `git status` is dirty.
- Per mutant, the registered **killer test set** runs first (fast); if it does not kill, the
  full simulator module battery runs as escalation. KILLED is claimed only on Maven exit
  non-zero **with** test failures; a non-zero exit without failures is an INFRA_FAILURE,
  not a kill.
- Anchor uniqueness: each registered anchor must occur **exactly once** in the current tree.
  Style-only refactors therefore make an anchor stale — the gate exits 2 and points at the
  mutant id; update the anchor to the current text (drift repair is not a semantic change).
- Interruption recovery: the clean-tree precondition makes recovery deterministic — if the
  run is killed mid-mutant, the single dirty file left behind **is** the applied mutant;
  restore with `git checkout -- <file>` and re-run in the foreground. Do not run this gate
  as a killable background job.

## Adding a mutant (ledger policy)

1. Register in `REGISTRY` with: unique `M<n>` id, rationale-bearing `name`, target `file`,
   exact `old`/`new` anchor (unique in tree), and the killer test classes.
2. Demonstrate the kill **red-first**: run the gate for that id alone and record KILLED.
3. Extend this page's ledger table with the guarded axis.
4. If the anchor is later re-shaped by mechanical refactors, repair the anchor without
   changing the mutant's semantics; the dry-run preflight makes drift cheap to detect.

Adding a mutant to fix a *survived* canary means a gap was found: first write the failing
killer test (expectation from literature/contract/conservation law, per the repo's
three-source rule), then register the mutant that it kills.
