# Dataflow State Structure and Performance Measurement

## Scope of Transactional State Copying

The coherent dataflow runners separate immutable history from the active working set, reducing the repeated copying of completed records and of the entire file cache on every transaction. This structure does not change the physical model, the source/routing choice, the allocator, the VM assignment policy, the floating-point aggregation order, or the recording budget.

- The [history index](<../../simulator/src/main/java/org/workflowsim/data/v2/PersistentHistoryMap.java>) stores records in immutable binary merge blocks. Levels are counted by writes rather than by the deduplicated map size; a new write overrides the old value, and lookups consult the newer levels first. Callers supply immutable keys and values; no iteration or deletion is exposed outward.
- [Replica state](<../../simulator/src/main/java/org/workflowsim/data/v2/DataReplicaState.java>) copies only the ordered position rows of the files that gained a newly published replica. A snapshot retains the immutable root together with the watermark and active tickets at capture; a fork still copies the active index independently, keeping branch-independent clocks, sequence numbers, and ticket identities.
- The [runtime coordinator](<../../simulator/src/main/java/org/workflowsim/data/v2/CoherentDataflowRuntime.java>) copies the active Jobs inside a transaction and archives the immutable terminal summary only after all outputs are processed successfully. Historical preparation results keep the same object, preparation-time deltas are still computed against the original instants, and the duplicate-Job-ID check covers both the active and terminal sides.
- Observation and quiescence decisions do not iterate terminated Jobs; the orderings and barriers of the ready queue, input waiting, SOURCE waiting, output obligations, and active copies are still preserved independently.

A history lookup inspects O(log W) blocks, assuming expected constant-time hash lookup within a block; an update amortized over a single history copies O(log W) entries, but one large merge can still be O(W), and the large merges of repeated forks do not share the amortization. Do not describe this as worst-case logarithmic updates.

The active Job set still requires isolation; large batches of wide tasks active at the same time can retain near-quadratic copy costs. Lifecycle logs use incremental appends after the transaction, and the checked services too still keep their own transactional copies. This structure promises no speedup for every DAG, parsing, solving, or reporting phase.

## Repeatable Benchmarks

[DataflowStateProfile](<../../simulator/src/test/java/org/workflowsim/data/v2/DataflowStateProfile.java>) is a controller-layer probe, not an algorithm-ranking study and not a CI time threshold. Comparisons use the same compiled benchmark class, loading the explicitly versioned baseline and candidate core implementations separately, with the JDK, heap, inputs, instants, warmup, and sampling method fixed.

| Scenario | Workload |
|---|---|
| FILELESS | Fileless chains, including repeated quiescence and empty file-scope observations |
| FILES_V2 | Chains with independent external inputs/outputs per task and a persistently growing cache set, not a high-hit-rate load |
| STORE_V3 | File chains plus restricted reads/writes and the required outputs landing in the store |
| ONLINE_V3 | Storage chains plus control-ready assignment, observation, and reservation scoring |
| WIDE_V2 | Large numbers of simultaneously active fileless tasks, observing the active-working-set cost |

The capture parameter is `OFF`, `TRUNCATED` (budget 1), or `FULL`. Each group warms up first with three 100-task runs; the last command argument is the number of samples. Scale, capture, and sampling must agree on both sides.

## Metric Conventions

- `elapsedNanos` measures only the coordinator's state transitions; it excludes file-plan construction, initialization, final semantic encoding, and fingerprint computation, and is not the end-to-end elapsed time of the whole SimulationRunner run.
- `threadAllocatedBytes` is the measuring thread's **cumulative allocated bytes**; it is neither resident RAM, nor peak RSS, nor the required heap capacity, and stays null when unavailable.
- `heapPoolPeakSumBytes` is the sum of the per-heap-pool peaks and does not claim to be the process peak at a single instant.
- The SHA256 of the state, the capture, and the online actions is used to compare the semantics bit by bit. Time or allocation improvements do not substitute for these comparisons and constitute neither real-platform calibration nor a proof of statistical significance.

## Running and Comparing

First build the two versions to compare following the [build guide](<../getting-started/BUILD.md>). Compile the current benchmark source once; `BASELINE_CP` and `CANDIDATE_CP` point to the respective core JARs plus the Gson/JDOM/Commons Math dependencies, and `PROFILE_CLASSES` and `PROFILE_DIR` are the freshly created benchmark-class and output directories.

```bash
javac --release 8 -cp "$BASELINE_CP" -d "$PROFILE_CLASSES" \
  simulator/src/test/java/org/workflowsim/data/v2/DataflowStateProfile.java

java -Xms512m -Xmx512m -cp "$PROFILE_CLASSES:$BASELINE_CP" \
  org.workflowsim.data.v2.DataflowStateProfile ONLINE_V3 250,500,1000,2000 OFF 3 \
  > "$PROFILE_DIR/before-ONLINE_V3-OFF.jsonl"

java -Xms512m -Xmx512m -cp "$PROFILE_CLASSES:$CANDIDATE_CP" \
  org.workflowsim.data.v2.DataflowStateProfile ONLINE_V3 250,500,1000,2000 OFF 3 \
  > "$PROFILE_DIR/after-ONLINE_V3-OFF.jsonl"

python3 -B scripts/compare-dataflow-profile.py "$PROFILE_DIR" \
  "$PROFILE_DIR/comparison.json" "$PROFILE_DIR/comparison.md"
```

The [comparison tool](<../../scripts/compare-dataflow-profile.py>) first checks the scenario, the trials, the JVM/heap, the actual copy counts, and every group's full set of semantic fingerprints, and only then emits bounded statistics. Missing data, type contradictions, fingerprint drift, or an already existing output path fail the run; no easily fluctuating speed-up pass threshold is set.

The raw JSONL, the environment and version identities, and the statistical report should be kept together in each measurement's own directory; numbers from a single machine must not be written up as long-term interface guarantees. Changing the state structure additionally requires verifying parent/child and sibling branch isolation, old snapshots, ticket identity, rollback on partial-output/delayed-read failure, terminal-state queries, waiter order, and the different capture modes; the full build, evidence replay, and browser gates cannot be skipped.
