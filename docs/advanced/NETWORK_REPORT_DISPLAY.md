# Offline Network and Action Report

**English** · [简体中文](<NETWORK_REPORT_DISPLAY.zh.md>)

## A Single Validation Source

Workbench's regular run report and `Workbench report <manifest> <new-report.html>` use the same loading chain: artifact references, content, lifecycle/ledger, and run context are validated first, and the display projections are then built from the frozen results. The original manifest and experiment index are not polluted by display fields.

The browser reads no neighboring JSON files, starts no simulation, and depends on no services or CDNs. Tampered or inconsistent artifacts are rejected before the standalone report target is created; a stale sidecar not referenced by the manifest cannot turn OFF into ON. Operating commands are in the [Workbench guide](<../getting-started/WORKBENCH.md>).

## Panels and Statuses

| Panel | Data and meaning |
|---|---|
| V1 network ledger | Parent/external-group-level service, effective/observed FCT, notification lag, resource accounting, and locality; not a per-file replica proof |
| File lifecycle V2 | Actual file sources, fixed paths, merges, settlement, and visibility; not per-interval service areas |
| Storage lifecycle V3 | Input reads, SOURCE waiting, successful-output write-back, CPU and output tails; same-VM caching cannot cross the SHARED commit barrier |
| Online target assignment | INITIAL/RETRY_REUSE per attempt, the selected VM, candidate scores, and observation summaries; coexists with the matching V2/V3 physical panel |

The statuses must be told apart explicitly:

- **Unrecorded:** not certified zero traffic. Non-online default modes may legitimately end up with an OFF report.
- **Complete with zero positive replication:** the records are complete but no corresponding positive-payload replication occurred; zero-byte references, storage commits, or declared resources may still be present.
- **Complete capture:** all records are retained; a component-level capture may be a snapshot with work still active, which does not mean the whole simulation has ended. The formal bundle must additionally pass the final-state context check.
- **V1 truncated prefix:** only the prefix and its diagnostics are displayed; whole-run byte/FCT/utilization summaries are unavailable and are not replaced with zeros. V2/V3 and online certification require a complete capture, and formal runs with insufficient budget are recorded as export failures.
- **No valid result, or projection missing:** old tables and selections are cleared first. A declared online mode without a matching projection must display as invalid and must not be dressed up as OFF or as zero actions.

## Exact Text and Bounded Previews

Display data consists only of strings, structural booleans, null, and deeply immutable collections. Identities, ordinals, counts, times, rates, ratios, and exact accounting quantities are converted into exact strings before entering `JSON.parse`; JavaScript `Number`, `parseFloat`, or localized formatting must not truncate these values.

| Projection | Cap |
|---|---|
| V1 | 64 flows/resources each, 128 events |
| V2 | 64 replications/resources each, 128 events |
| V3 | 64 replications/resources/Job each, 128 events |
| Online actions | 64 actions, 12 candidates previewed per action |
| File/resource details | 12 entries at each nested Task/path-list location; trimmed labels are at most 256 UTF-16 units, avoiding split surrogate pairs |

Every preview retains the total, the shown count, and the omitted count; UI trimming never changes the COMPLETE/TRUNCATED capture status. Exact numeric text is never truncated, and the full raw history is not embedded into the HTML a second time. A selected candidate must be looked up in the complete candidate set even when it lies beyond the preview cap.

## Preventing Misreads and Unsafe Content

- Balance deltas, rate areas, numeric residuals, outstanding balances, repeated path occupancy, and reference locality are displayed separately; none of them may be conflated into delivered traffic.
- Effective completions, observed completions, CPU completions, and output tails each keep their own timing basis. The actual schedule is not back-inferred from nominal candidate scores.
- The online panel explicitly displays `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1` together with `liveProgressReplayed=false`; it must not claim certified exact balances, future contention, or global optimality.
- A component-level projection that passed only the internal checks must retain `contextValidated=false`; it must not pass itself off as formal-bundle context validation.
- Embedded JSON retains HTML-character and U+2028/U+2029 escaping; DOM content uses `textContent`. Labels must never turn into images, scripts, or event handlers.
- The defensive manifest snapshot and the immutable decode results from the same validation run are reused; paths that may since have been swapped are not re-read, and no large ledger is parsed or recomputed again for display.

## Maintenance Verification

The [browser checker](<../../scripts/verify-report.cjs>) checks the desktop/narrow-screen layout, the all-runs toggle, the exact cells, the preview counts, and the absence of extra files and HTTP requests. The [counterexample check](<../../scripts/test-report-checker.cjs>) deliberately breaks text, numeric types, panels, scope statements, and preview caps, and must fail. Test commands are maintained together in the [build guide](<../getting-started/BUILD.md>).
