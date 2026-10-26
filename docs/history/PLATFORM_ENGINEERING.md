# Platform Engineering History

This page is the single narrative record of the engineering waves that shaped the current
platform. It absorbs the durable facts of the review rounds so that contract documents stay
contractual: positioning lives in the [science position statement](<../SCIENCE-POSITION.md>),
behavioral boundaries in their contracts, and this page keeps the story of how the platform
was hardened. Process-level detail remains in the engineering archive (git-ignored workspace)
and in Git history; nothing here overrides a contract.

## Wave 1 — correctness-bearing defect repairs (2026-10-19)

- 6 defects fixed red-first: whitelist-regex derivation (F-001), deep-chain stack
  overflow (F-004), comparator 2^32 wraparound (F-005), clustering self-loop (F-008),
  half-commit parser pollution (F-009), plus the R13 dependency-gating defect.
- 2 fenced as deliberate contracts (F-002/F-003); test blind spots M5/M7 closed with new
  boundary/conservation killers: 10 new test classes, 25 cases.
- Mutation pilot: 9 of 9 curated semantic mutations killed, validating the battery.

## Wave 2 — scientific-instrument uplift (2026-10-20)

- Independent Python recomputation reached 78/78 covered metrics (deadline family across
  all three branches; controlledSharedStorage critical-path four, with matrix semantics and
  (long)-truncation parity).
- Literature anchoring sealed: Braun et al. 2001 golden table 6/6 for the independent-task
  mappers; HEFT, CPOP, and PEFT carried zero-tolerance planning-execution mirrors; DLS/ETF
  DOI-anchored semantics locked.
- Six-chain audit closed with the science position statement and the paper-metrics
  conventions card.

## Wave 3 — code-regularization campaigns (2026-10-22/23)

- Whole-repository reflow to a single canonical formatter; Checkstyle Tier-A expanded to
  15 error rules with full-tree zero arrears: RightCurly, LineLength, ImportOrder,
  AvoidStarImport, NeedBraces, and WhitespaceAround all promoted.
- The 12 substantive MethodLength candidates extracted behavior-preserving;
  SimulationMetrics declared a documented irreducible metric ledger.
- Grouped-workflows module gained the BUNDLE coverage ratchet; the mutation gate became a
  regular zero-survival instrument (`scripts/mutation-gate.py`, `make mutation-gate`).

## Wave 4 — evidence-observability and recertification (2026-10-23)

- events v1 grew `SIMULATION_TERMINATED` and `JOB_EXECUTION_STARTED`: the
  makespan/terminal-tail/deadline-slack and decision-to-start families became recompute-
  able from the event stream, with Python cross-checks `terminal-clock-identity` and
  `job-start-derivation` baked into the independent auditor.
- SHARED_STORAGE all five planners locked by a zero-tolerance planning-to-execution
  mirror (309 integration tests); Python 78/78已被 covered.
- R13 v2 re-certified at 546 runs and cross-checked bit-exact against the impact layer,
  directly confirming the behavior-neutrality of the style campaign.
- The SHARED-family zero-download and events-observability initiatives closed with
  adjudication records; whitespace arrears retired with Tier-A promotion.
- Registered external algorithms grew from SPI discovery through the fail-fast matrix to
  a validated evidence bundle — the "grad-student exercise".

## Open record

- All numbered TORs (F-001..F-009) have dispositions in the engineering archive (BUGS
  ledger); none is pending. The canary ledger (11 registered mutants) is documented at the
  [mutation gate](<../advanced/MUTATION_GATE.md>).
