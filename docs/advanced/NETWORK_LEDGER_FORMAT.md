# Network Ledger V1 Codec Contract

**English** · [简体中文](<NETWORK_LEDGER_FORMAT.zh.md>)

## Purpose and Interfaces

`NetworkLedgerCodec` performs the strict JSON encoding/decoding and the immutable reconstruction of the V1 group ledger; `ExperimentArtifactWriter` writes the complete artifacts, and `ExperimentArtifactValidator` checks the ledger content and the run context. The [independent Python checker](<NETWORK_LEDGER_PYTHON.md>) invokes no Java. The [rerun tooling](<../experiments/RERUN_DIFF_CONTRACT.md>) compares the scientific fields of the complete ledger, and [Workbench](<../getting-started/WORKBENCH.md>) displays only the verified bounded projection.

This document defines only the V1 group ledger. Per-file V2, Storage V3, and online actions each use their own contracts, as shown in the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>), and must not be treated as interchangeable under one semantics.

```java
String json = NetworkLedgerCodec.encode(report.getNetworkEvidence());
NetworkLedgerCodec.Decoded checked = NetworkLedgerCodec.decode(json);
NetworkRunEvidence frozen = checked.getEvidence();
NetworkRunMetrics recomputed = checked.getMetrics();
```

## Formal Export and Validation

```java
// config已选择受支持的流体模型、静态mapper/dispatch和对应平台。
SimulationConfig observed = config.toBuilder()
    .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(50000)).build();
SimulationReport report = new SimulationRunner().run(observed, platform);
ExperimentArtifactWriter.ExperimentArtifacts files =
    ExperimentArtifactWriter.write(report, outputDirectory, "run");
ExperimentArtifactValidator.ValidationResult checked =
    ExperimentArtifactValidator.validate(files.getManifest());
Path ledger = checked.getNetworkLedger(); // OFF为null，不凭目录中的旧文件猜测
```

ON writes the original metrics/events plus the new `run.network-ledger.json`, then registers its role/hash/size in the manifest and commits last. OFF still writes the original three files, adding no networkEvidence field and no network role. When a directory is reused, old unreferenced ledgers are not arbitrarily deleted; readers trust only manifest roles.

- Any network semantic or context error must fail before the directory is created or any existing file is modified.
- Every file is atomically replaced on its own, and the manifest is written last; this is not a filesystem transaction across multiple files. A mid-way I/O failure is caught by the later hash/content checks.
- A standalone `ExperimentManifestWriter.writeJson` still must not drop the ON ledger; the full-bundle export must be used.
- The v4 networkEvidence object must coexist with the network-ledger role; null, false, an OFF object, or an illegal budget must not be treated as the default OFF.
- Artifact sizes and event sequence numbers are checked as exact longs; decimal truncation or out-of-range conversion is not accepted.

Topology validation covers same-edge, cross-pod, explicit placement, and reduced-core cases and does not allocate an entire topology for an untrustworthy large `k`.

## Document Header

- schema: `workflowsim-network-ledger-v1`
- Artifact role: `network-ledger`, file name `<runId>.network-ledger.json`
- flowUnit: `V1_PARENT_OR_EXTERNAL_GROUP`
- units: time=`SIMULATION_SECONDS`, bytes=`MODELED_BYTES`, rate=`BYTES_PER_SECOND`
- numericProfile: `BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`
- accountingVersion: `EXACT_BINARY64_INPUT_DECIMAL_V1`
- evidence: the configuration, the existing model kind, the raw traceSnapshot, immutable bindings, engineCreated, and optionally the raw inputDemand aggregation observations.
- metrics: NetworkRunMetrics recomputed from the reconstructed evidence; the summary submitted in the document is not trusted.

This schema contains no working directory, source-code paths, or wall-clock measurements. It neither upgrades nor changes the legacy manifest-v4, metrics-v2, and events-v1 formats.

## Strict Reading

1. Reject duplicate JSON keys, lenient JSON forms, trailing document content, missing or unknown fields, wrong enums, and string/boolean values masquerading as numbers.
2. All nullable fields must appear explicitly. The CAPACITY identity is empty; other event identities are required, and exactly one payload matching the type is non-null.
3. int/long identities and budgets are read as exact integers and must not be converted to double first; the budget must be positive, and the mode must be `FLUID_GROUP_LEDGER_V1`.
4. The trace's binary64 fields must be finite and representable; non-zero values that underflow to 0 must not be silently accepted. When different runtimes spell the same smallest double as `4.9e-324` or `5e-324`, comparison goes by binary64 value; no particular JDK's shortest string is taken as the only accepted form.
5. BigDecimal aggregates compare as exact decimals and may exceed Double.MAX_VALUE; a signed accounting difference must not be treated as an illegal negative flow, nor must its absolute value be taken.
6. JSON nesting depth is capped at 64, and number token length, precision, and absolute scale at 4096. The writer checks the same limits, so it cannot produce documents that it cannot itself read. This is not a byte-memory cap on the whole trace.
7. The immutable DTO factories are invoked explicitly; relying on the default 0/null fields left behind after Gson bypasses private constructors is prohibited.
8. Independent trace semantic/capacity certificate validation runs; retained STARTs are exactly correlated with bindings, input-count partitions are checked, and every metrics field is recomputed and compared.
9. Extra unretained bindings in truncated evidence are not silently filtered out by the reader. engineCreated=false must not mask non-empty records, times, or input observations.

`parseDocument(String)` provides a strict, bounded, lexical JSON tree, preserving exact numeric types beyond the length of Gson's streaming buffer; it does not replace `decode`'s semantic/metrics validation. Raw number lengths, Unicode escapes, duplicate keys after decoding, and JSON depth are all checked; long numbers are never treated as strings by resorting to a lenient mode.

Object member order does not affect semantics; the order of arrays such as events, paths, and Task lists is preserved. binary64 fields compare by value; exact integer and decimal aggregate fields never pass through lossy floating-point comparison.

## Guarantees and Non-Guarantees

- `Decoded` returns only the immutable evidence and the recomputed immutable metrics; modifying the source JSON tree does not affect them.
- COMPLETE, TRUNCATED, zero observations without an engine, missing input counts, and similar states keep distinct meanings.
- The pure codec holds no Job results, VM placements, or main events from the manifest; formal artifact validation additionally checks the configuration, the actual VM placements, Job/Task identities, source/deterministic paths, platform capacity, Job-ready/input-completion/Job-start times, and per-Job group counts. Input reference counts agree with the main events; the number of positively-transferring groups must not exceed the number of non-local references; each Job attempt carries at most one V1 external-input group (this also binds retained prefixes). Exact referenced bytes are checked against the original double grouping quantities within explicit accumulation-error bounds; the permitted ±1 grouping-rounding difference must not be taken as evidence of locality.
- Legacy v4 tools may verify only the extra role's hash without understanding these network semantics. ON evidence must use the upgraded validation/rerun chain; the dedicated rerun is upgraded and compares the sidecar field by field; both the normal and the standalone Workbench reports embed the bounded, verified network display projection.
- Internal consistency is not cryptographic authenticity and does not prove the existence of an unknown truncated suffix.

The definitions of the service grammar, the max-min certificate, and the derived metrics are in the [independent validation rules](<NETWORK_LEDGER_VALIDATION.md>); the unified commands for running the tests are in the [build guide](<../getting-started/BUILD.md>).
