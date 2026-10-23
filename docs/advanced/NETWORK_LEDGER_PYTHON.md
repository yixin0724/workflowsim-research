# Independent Python Network and Dataflow Evidence Checker

**English** · [简体中文](<NETWORK_LEDGER_PYTHON.zh.md>)

## How to Run

[verify-network-ledger.py](<../../scripts/verify-network-ledger.py>) depends only on the Python standard library (Python 3.9+; CI runs 3.12). A check invokes no JVM, Maven, production solver, or network service, and rewrites no input files.

```bash
# 推荐：核对完整工件及其运行上下文
python3 -B scripts/verify-network-ledger.py path/to/result.manifest.json --json

# 单独检查侧车内部契约；不冒称验证外层运行
python3 -B scripts/verify-network-ledger.py path/to/result.network-ledger.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.file-lifecycle.json --json
python3 -B scripts/verify-network-ledger.py path/to/result.storage-lifecycle.json --json
```

Manifest mode recognizes only the registered artifact roles, checking the constrained relative paths, SHA256 hashes, file sizes, configuration, and context; a stale sidecar sitting unreferenced in the directory cannot turn OFF into ON. The fixture-generation commands for tests and for the production side are maintained together in the [build guide](<../getting-started/BUILD.md>).

## Dispatch and Scope of Claims

| Input contract | What is checked | What must not be claimed |
|---|---|---|
| V1 group-level ledger | Fluid-record syntax, capacity/max-min certificates of the stable assignments, exact accounting, derived metrics, and available context | Per-file provenance or TCP-connection replay |
| File lifecycle V2 | File plans, sources/paths, replication/visibility, input and CPU causality | Unrecorded per-interval fluid service accounting |
| Storage lifecycle V3 | V2-style per-file checks, plus constrained SOURCE, commit waiting, successful-output obligations, and the matching context | Intricate storage consistency, cache eviction, or storage faults |
| Explicit online target assignment | Bindings under full-lifecycle support, PE/MI, CPU reservations, nominal input bounds, and logged scores | Exact in-flight balances, future contention, or global optimality |

Even after the online-action checks pass, `liveProgressReplayed=false` is still reported explicitly. An ON ledger, together with the recognized V2/V3/online extensions, enters the corresponding mode, physical-Kind, filesystem, and role checks; the incomplete declarations of these known extensions must not be waved through as an ordinary OFF.

The plain OFF entrypoint for ordinary legacy modes is narrower in scope: it checks only the manifest version, the artifact path/hash/size, and the recorded switch/role relations, and then returns `contextualRunChecked=false`; it neither validates the full configuration support matrix nor guarantees rejection of unknown model Kinds. When complete configuration/run validation is required, use the corresponding Java artifact validator; the `DISABLED` returned here must not be taken as an all-configuration attestation.

## Result Statuses

- `VALID_COMPLETE`: the complete capture passed the checks within the declared scope; a complete prefix in standalone mode still does not mean that the whole simulation has ended.
- `VALID_PREFIX`: the necessary constraints of the V1 truncated prefix held, `completeCaptureCertified=false`; no whole-run traffic/FCT certification is published, and no unknown suffix is proven to exist.
- `DISABLED`: an allowed unrecorded mode passed the corresponding basic checks; this is not "certified-zero traffic". V2/V3 may retain core plans, but online-action certification cannot substitute OFF for the complete lifecycle.
- `EVIDENCE_INVALID`: inconsistent format, references, numbers, or semantics; the CLI exits non-zero.

Also read the scope and context markers in the output. A standalone sidecar carries no complete run result, and its check scope must not be conflated with that of the manifest entrypoint.

## Independence and Numerical Handling

The implementation entrypoint is [_network_audit.py](<../../scripts/_network_audit.py>), which dispatches by contract to specialized checkers such as [_file_lifecycle_audit.py](<../../scripts/_file_lifecycle_audit.py>), [_storage_lifecycle_audit.py](<../../scripts/_storage_lifecycle_audit.py>), and [_dataflow_assignment_audit.py](<../../scripts/_dataflow_assignment_audit.py>).

- Times, rates, and balances are checked under the declared binary64 scalar rules; exact int/long and decimal aggregations never pass through a lossy double conversion.
- V1 obtains the exact rational of the binary64 values in the records via `Fraction.from_float` and accumulates balance deltas, rate areas, and capacity loads; the rounding of means/utilizations follows its dedicated contract.
- Max-min stable assignments are verified through bottleneck certificates without re-invoking the production allocator; a truncated tail checks only the fixed facts and the necessary lower/upper bounds, without searching unknown history.
- V2/V3 independently rebuild logical files, provenance, paths, and lifecycles; online actions check nominal bounds and scores against the pre-request state. Progress changes that stay within the permitted bounds still surface as core differences under [exact rerun](<../experiments/RERUN_DIFF_CONTRACT.md>).
- Duplicate JSON keys, non-standard constants, type spoofing, missing fields, over-deep structures, out-of-range numbers, directory escapes, and NUL file names are rejected. Minimal binary64 numeral spellings that stay equivalent across runtimes are allowed, but a non-zero value silently underflowing to zero is not accepted.

This is not a cryptographic authenticity proof for evidence that has been consistently rewritten as a whole. General workflow statistics and research-level aggregation are handled separately by the [study checking tool](<../../scripts/STUDY_AUDIT.md>); each tool is accountable only for its own declared scope.
