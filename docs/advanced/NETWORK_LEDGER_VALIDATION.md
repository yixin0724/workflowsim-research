# V1 Fluid Ledger Validation and Exact Metrics

## Scope and Interfaces

The Java-side semantic validation of the V1 group-level ledger does not invoke the production-side progressive-filling solver to compute "expected rates"; the [Python checker](<NETWORK_LEDGER_PYTHON.md>) independently implements the same contract. A formal export requires the complete bundle, consisting of capture, bindings, aggregated input observations, and derived metrics; OFF produces no network roles. The format is defined in the [encoding/decoding contract](<NETWORK_LEDGER_FORMAT.md>); for display and replay see the [offline report](<NETWORK_REPORT_DISPLAY.md>) and the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>) respectively.

```java
NetworkRunEvidence evidence = report.getNetworkEvidence(); // OFF为null
if (evidence != null) {
    NetworkRunMetrics runMetrics = NetworkRunMetrics.calculate(evidence);
    NetworkTraceMetrics metrics = runMetrics.getTransferMetrics();
    TransferTraceValidator.Result checked = metrics.getValidation(); // 已先校验
    if (metrics.isAvailable()) { // 仅COMPLETE capture发布完整捕获总量
        BigDecimal service = metrics.getServicedBalanceDeltaBytes();
    }
}
```

- `COMPLETE` means that all records are retained; it does not mean that all flows have finished. Active flows may sit inside a complete mid-run snapshot.
- `TRUNCATED` means that a deterministic prefix is retained; it must not be used to issue whole-run traffic, mean-FCT, or utilization certifications.
- `certifiedThroughSequence` marks only the stable boundary up to which the full assignment certificate has been obtained. The tail may sit in a rate change on admitted flows, a service batch, the completion queue, or a window pending advancement.
- For truncated tails only the already-observed syntax and the necessary lower/upper bounds are checked; **it proves neither that any unknown suffix exists, nor reconstructs the discarded history**.
- This is an internal-consistency check, not a cryptographic attestation of the evidence as a whole under a consistent rewrite. Configuration, Job bindings, main events, and provenance still require joint verification at the artifact layer.
- Flows in the current run capture are `PARENT_GROUP_V1` or `EXTERNAL_GROUP_V1`, not per-file flows. Their source tag is `MODELED_CONSTRAINED_SOURCE`, which is not the same as the per-file replica actually selected by the nominal estimator.

## Exact Derived Metrics

`NetworkTraceMetrics.calculate` first invokes the independent verifier and then reads the frozen snapshot; it touches no live engine and advances no time. The accounting version is `EXACT_BINARY64_INPUT_DECIMAL_V1`: the doubles in the records are represented exactly as BigDecimal, then multiplied and accumulated without rounding.

| Quantity | Definition and constraint |
| --- | --- |
| admittedPayloadBytes | The demand bytes B across all START records |
| servicedBalanceDeltaBytes | Exact accumulation of before-after, denoted D |
| modeledRateAreaBytes | Exact accumulation of rate × original elapsed, denoted A; never replaced by a clock difference |
| completionResidualBytes | The numeric removal R at completion; not network service |
| remainingLedgerBytes | The ledger balance Q not yet completed |
| rateAreaMinusBalanceDeltaBytes | Signed A-D, of either sign; never absolute-valued or forced to zero |
| completedDemandBytes | The raw demand of completed flows; not equal to the transferred serviced volume |

Every flow is checked against the exact `B=D+R+Q`; it is never falsely asserted that `A=D`. A large balance minus 0.5 may still be the same double: in that case D=0 and A=0.5, and the difference must be retained. BigDecimal also keeps cross-flow totals or capacity integrals beyond `Double.MAX_VALUE` from being emitted as Infinity.

- FCT is kept separately per admission ordinal, even when an external ID is reused. For an active flow, the completion time/FCT is null, a censored sample.
- effective FCT, observed FCT, and notification lag are retained separately. A zero effective FCT may come with positive service; no fictitious or infinite throughput is emitted.
- Means are first averaged in DECIMAL128 and then converted to double; p95 uses nearest-rank computed on integers, without interpolation.
- OFF/TRUNCATED keep the validation diagnostics, but the byte totals, FCT sample totals, and utilization statistics are unavailable; they are not replaced with zeros. COMPLETE with zero flows has a genuine zero byte total, but no FCT or utilization samples.
- Resource integrals are charged according to the repeated weights along the original paths; the sum of the integrals over all resources must not be equated with the delivered bytes.
- Utilization is `boundedRateArea/capacityArea`; **the capacity area is counted exactly once per positive-elapsed service batch**, global idle gaps are excluded, and resources registered at the time but unoccupied are charged zero load.
- A SOURCE that was never constrained has a flow area but no utilization denominator. When a resource registers capacity only later, the traffic of the earlier uncapped phase does not enter the utilization numerator of the capped period.

## Actual Locality Observations

Locality is not inferred from "demand bytes minus serviced bytes". The original input loop performs exactly one local-replica check, reusing **the same boolean result** for both the original skip branch and the optional observation counter; it does not query the catalog again and does not change the admission/release/replica policy.

`NetworkInputDemandTracker` keeps only a fixed number of long/BigDecimal aggregates and keeps no per-file or per-Job history. Its frozen snapshot is scoped to `V1_GROUP_INPUT_REFERENCES`: it includes repeated references within the parent group, renewed references from different Job attempts, and zero-byte references, and must not be interpreted as a unique-file count or a finite-cache model.

- requiredReferenceBytes accumulates exactly the double file sizes as observed; localReferenceBytes accumulates only the references actually skipped; transferableReferenceBytes covers the remaining references.
- Counts and bytes each satisfy their own exact partition relations. Zero references carry no hit-rate denominator; a zero-byte reference still increments the reference count but contributes no byte-hit-rate denominator.
- `NetworkRunMetrics` reports the reference-count hit rate and the byte hit rate separately, together with `admittedMinusTransferableReferenceBytes`.
- The last quantity is the **signed difference** between the legacy V1 double-arithmetic per-group admission volume and the exact non-local reference volume; it is not a locality gain. For example, `1e16+1` or `1e16+3` within the same group yields a -1 or +1 admission rounding difference while the actual local count remains 0.
- The raw counters keep aggregating in O(1) after the trace budget is exhausted, but TRUNCATED does not publish complete-capture locality metrics.
- When the legacy low-level `NetworkRunEvidence.capture` overloads take no observations, locality is `INPUT_COUNTERS_UNAVAILABLE`; no zero-hit rate is fabricated. OFF is also distinct from a complete capture with zero demand.

Input observations are aggregate evidence, not a per-file completion timeline; at the artifact layer they still must be verified together with the main events, the bindings, and the source identities.

## Validated Syntax of a Complete Capture

1. Record sequence is the contiguous 1..N, and each START's admissionOrdinal is a contiguous admission ordinal. An external ID may be reused after the earlier admitted flow completes, but never simultaneously.
2. Each record carries exactly one payload matching its type; the numbers, identities, resource lists, and time domains must be legal.
3. CAPACITY may be registered or overwritten only while no flow is active.
4. A START carries the new flow's final initial rate, followed by the RATE_CHANGE records of old flows in existing admission order. A later rate change must not revise the new flow's initial rate already declared by that START.
5. A positive-elapsed integration step must carry exactly one SERVICE for each flow active before the step, in admission order. Batches are delimited by the number of active flows, never grouped coarsely by equal timestamps or contiguous record types.
6. elapsed, before/after, and the internal boundaries are re-derived independently with the original binary64 scalar formulas; the elapsed recorded in the record is retained and must not be replaced with `end-start`.
7. The completion queue is decided by the relative/ULP byte tolerances and the earliest-flow forced-zeroing rule. The residual comes from the remaining before forced zeroing and does not count as network serviced traffic.
8. Survivor rate changes are processed only after the completion queue; capacity and the max-min certificate are verified after the complete rate-change block ends, so a transient overload introduced by serialization must not be misjudged as a final overload.
9. Integration must continue as long as a flow is known to remain active within the observed horizon; windows must not be silently skipped. A complete EOF must close all known required phases, with the active state consistent with engineTime.

### Numerical Boundaries That Must Be Preserved

- The start and end double clocks of a positive-elapsed interval may be identical; a zero FCT is not zero service.
- A zero-elapsed iteration emits no SERVICE but may emit completion records; the forced-zeroing residual may exceed the usual completion tolerance.
- Two adjacent COMPLETE records at exactly the same time may also come from different iterations, and the completion order is not necessarily sorted by the original admission ordinal.
- With `q=Double.MIN_VALUE` and no resources, X=`192q@128` and Y=`64q@64` admitted at 0 and advanced to `2q` will first service once and complete Y, then complete X with zero elapsed, leaving residual `64q`. This is the executed regression control, not a record that should be "fixed" into more transferred bytes.

## Independent Max-Min Bottleneck Certificate

Each stable assignment is checked for:

- every flow's rate being finite, positive, and no greater than its nominal cap;
- every finite resource's weighted load not exceeding capacity, with repeated resource keys charged per occurrence;
- for every flow not limited by its nominal cap, at least one occupied saturated resource whose every co-user's rate is no higher than that flow's, within the explicit numerical profile.

The last criterion rejects capacity-feasible but unfair fabrications such as "capacity 100 with two identical demands granted 75/25", and it also detects histories in which capacity was not reclaimed after competitors completed.
Resources without a registered capacity do not constitute bottlenecks; a SOURCE's default infinite budget emits neither Infinity nor a fabricated utilization.

## Numerical Profile

Fixed identifier: `BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1`.

- Service and time scalar formulas are compared exactly under binary64 values; positive and negative zero clocks are treated as numerically equivalent.
- Capacity loads are computed by representing the doubles in the records exactly as BigDecimal, avoiding summation overflow.
- The capacity comparison budget is `min(8*(occupancyTerms+1)*ulp(capacity), capacity*1e-12)`.
- Rate comparisons use the same relative cap; the ULP term is computed from the number of active flows and the larger of the rates being compared.
- This is neither the completion tolerance for input bytes nor a global time epsilon.

The relative cap is indispensable: a bare multi-ULP budget can exceed the entire capacity at subnormal scale. An independent counterexample confirmed that the legacy engine could allocate `2q+2q` given capacity `3q` and two identical flows, an over-allocation of about 33%. **The verifier rejects such under-precise physical certificates; it does not modify the legacy engine to restore compatibility, and it does not relabel a large proportional error as rounding noise.** The newer physical models must still make their representable numerical domain explicit.

## Necessary Checks on a Truncated Tail

- Exactly the budgeted number of records must be retained and droppedCount must be positive; filling the budget exactly without dropping a record is still COMPLETE.
- Fixed flows include the newly admitted flows, the old flows whose rate changes have already been processed, and the old flows skipped past in sequence that can no longer change rate.
- The weighted-load lower bound of the fixed subset must not exceed capacity; every fixed flow not capped by its nominal cap must still have a bottleneck that can hold.
- A variable flow's upper bound is constrained jointly by its nominal cap, the compared rates, the remaining capacity of every occupied resource, the fixed occupancy, and the repeated weights; these are conservative necessary conditions that never invoke the allocator.
- If no legal pending rate-change candidate remains, that assignment must be closed and certified; TRUNCATED must not be used to cover already-determined errors.
- The missing service batches and completion queues already determined, plus the records required by each surviving flow in the subsequent observed window, all count toward the minimum droppedCount obligation.
- If only the snapshot watermark advanced and the matching observed horizon was not retained, only the conservative minimum obligation is computed; unknown new STARTs or rate changes could change the number of later batches, and the missing history must not be replayed on the checker's own authority.

## Test Entrypoints

Regression coverage includes complete traces, truncated prefixes, normal boundary cases, and tampering counterexamples after renumbering/rehashing. Acceptance commands are maintained together in the [build guide](<../getting-started/BUILD.md>); when the verifier is modified, positive controls and counterexamples must both be covered, and semantic changes must not be masked by tuning study parameters or refreshing golden results.
