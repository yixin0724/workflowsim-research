"""Independent fluid-ledger audit using stdlib binary64 + exact rational arithmetic.

No JVM, WorkflowSim allocator, simulation replay or transport model is invoked.
Truncated tails get necessary checks only, never full-capture certification.
"""
from __future__ import annotations

from collections import Counter
from decimal import Decimal, localcontext, ROUND_HALF_EVEN, InvalidOperation
from fractions import Fraction
import hashlib
import json
import math
from pathlib import Path
import sys

SCHEMA = "workflowsim-network-ledger-v1"
PROFILE = "BINARY64_SCALAR_MAXMIN_8ULP_CAPPED_1E_MINUS12_V1"
ACCOUNTING = "EXACT_BINARY64_INPUT_DECIMAL_V1"
SCOPE = "V1_GROUP_INPUT_REFERENCES"
ENDPOINT = "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1"
FAT = "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"
PAYLOADS = {"CAPACITY": "capacity", "START": "start", "RATE_CHANGE": "rateChange",
            "SERVICE_SEGMENT": "serviceSegment", "COMPLETE": "complete"}
LONG_MIN, LONG_MAX, INT_MAX = -(1 << 63), (1 << 63)-1, (1 << 31)-1


class CheckError(ValueError):
    """Invalid or contradictory network evidence, not a missing optional observation."""


def need(ok, message):
    if not ok:
        raise CheckError(message)


def keys(value, expected, name="object"):
    need(isinstance(value, dict) and set(value) == set(expected), f"{name}: missing/unknown fields")
    return value


def required(value, key):
    need(isinstance(value, dict) and key in value, f"missing {key}")
    return value[key]


def num(value):
    need(type(value) in (int, float, Decimal), "expected JSON number, not a coercion")
    try:
        token = str(value)
        need(len(token) <= 4096, "numeric token exceeds limit")
        result = Decimal(token)
        need(result.is_finite(), "non-finite JSON number")
        digits = result.as_tuple()
        need(len(digits.digits) <= 4096 and abs(digits.exponent) <= 4096, "numeric exponent/precision exceeds limit")
        return result
    except (ValueError, OverflowError) as error:
        raise CheckError("invalid numeric value") from error


def whole(value, lo=LONG_MIN, hi=LONG_MAX):
    n = num(value)
    need(n == n.to_integral_value() and lo <= n <= hi, "expected exact in-range integer")
    return int(n)


def f64(value, nonnegative=False, positive=False):
    n = num(value)
    try:
        result = float(n)
    except (OverflowError, ValueError) as error:
        raise CheckError("unrepresentable binary64") from error
    need(math.isfinite(result) and not (result == 0 and n != 0), "unrepresentable binary64")
    need(not nonnegative or result >= 0, "negative scalar")
    need(not positive or result > 0, "non-positive scalar")
    return result


def q(value):
    """Exact rational of an already-normalized binary64 scalar."""
    return Fraction.from_float(float(value))


def wire_fraction(value):
    return Fraction(num(value))


def word(value):
    need(type(value) is str, "expected text")
    return value


def array(value):
    need(isinstance(value, list), "expected array")
    return value


def words(value):
    return [word(item) for item in array(value)]


def same64(a, b, message):
    need(a == b, message)


def mean34(value, count):
    if not count:
        return None
    ratio = value / count
    with localcontext() as ctx:
        ctx.prec, ctx.rounding = 34, ROUND_HALF_EVEN
        return float(Decimal(ratio.numerator) / Decimal(ratio.denominator))


def decimal_text(value):
    if value.denominator == 1:
        return str(value.numerator)
    with localcontext() as ctx:
        ctx.prec = 10000
        return str(Decimal(value.numerator) / Decimal(value.denominator))


def decode_json(text):
    need(type(text) is str, "JSON input must be text")
    def pairs(items):
        result = {}
        for key, value in items:
            need(key not in result, "duplicate JSON key")
            result[key] = value
        return result

    def numeric_token(token):
        need(len(token) <= 4096, "raw numeric token exceeds limit")
        return num(Decimal(token))

    def integer_token(token):
        return int(numeric_token(token))

    def invalid_constant(value):
        raise CheckError("invalid JSON constant: " + value)

    try:
        doc = json.loads(text, parse_float=numeric_token, parse_int=integer_token,
                         parse_constant=invalid_constant, object_pairs_hook=pairs)
        def depth(value, level=0):
            need(level <= 64, "JSON nesting exceeds limit")
            if isinstance(value, dict):
                for child in value.values(): depth(child, level+1)
            elif isinstance(value, list):
                for child in value: depth(child, level+1)
        depth(doc)
        return doc
    except (ValueError, RecursionError, UnicodeError, OverflowError, InvalidOperation) as error:
        if isinstance(error, CheckError):
            raise
        raise CheckError("invalid network JSON") from error


def read_document(path):
    try:
        return decode_json(Path(path).read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as error:
        raise CheckError("cannot read valid UTF-8 evidence: " + str(path)) from error


def option(raw):
    keys(raw, ("mode", "maxTraceRecords"), "networkEvidence")
    need(raw["mode"] == "FLUID_GROUP_LEDGER_V1", "unsupported recording mode")
    return whole(raw["maxTraceRecords"], 1, INT_MAX)


def profile(scale, terms):
    return min(q(math.ulp(scale))*8*(terms+1), q(scale)/10**12)


def near(a, b, terms):
    return abs(q(a)-q(b)) <= profile(max(a, b), terms)


def tolerance(size):
    return min(size*.5, max(1e-9*size, 4.0*math.ulp(size)))


def normalize_event(raw, index):
    keys(raw, ("sequence", "type", "effectiveTime", "observedTime", "transferId", "admissionOrdinal", *PAYLOADS.values()), "event")
    kind = word(raw["type"])
    need(kind in PAYLOADS and whole(raw["sequence"], 1) == index+1, "event type/sequence")
    effective, observed = f64(raw["effectiveTime"], nonnegative=True), f64(raw["observedTime"], nonnegative=True)
    need(effective <= observed, "effective time exceeds observation")
    for key in PAYLOADS.values():
        if key != PAYLOADS[kind]: need(raw[key] is None, "extra event payload")
    body = raw[PAYLOADS[kind]]
    fields = {"CAPACITY": ("resourceKey", "capacityBytesPerSecond"),
              "START": ("bytes", "occupiedResources", "nominalRateBytesPerSecond", "initialRateBytesPerSecond"),
              "RATE_CHANGE": ("previousRateBytesPerSecond", "rateBytesPerSecond"),
              "SERVICE_SEGMENT": ("intervalStart", "intervalEnd", "elapsed", "rateBytesPerSecond", "remainingBefore", "remainingAfter"),
              "COMPLETE": ("remainingAfterService",)}[kind]
    keys(body, fields, "event payload")
    normalized = {}
    for key, value in body.items():
        if key == "resourceKey": normalized[key] = word(value)
        elif key == "occupiedResources": normalized[key] = words(value)
        else: normalized[key] = f64(value, nonnegative=True, positive=key in
                                      ("capacityBytesPerSecond", "bytes", "nominalRateBytesPerSecond", "initialRateBytesPerSecond",
                                       "previousRateBytesPerSecond", "rateBytesPerSecond", "elapsed"))
    if kind == "CAPACITY":
        need(raw["transferId"] is None and raw["admissionOrdinal"] is None and normalized["resourceKey"], "capacity identity/key")
        tid = ordinal = None
    else:
        tid, ordinal = whole(raw["transferId"]), whole(raw["admissionOrdinal"], 1)
    if kind in ("CAPACITY", "START"): same64(effective, observed, "capacity/admission clock mismatch")
    if kind == "START":
        duration = normalized["bytes"] / normalized["nominalRateBytesPerSecond"]
        need(duration > 0 and math.isfinite(duration), "unrepresentable nominal duration")
        need(normalized["initialRateBytesPerSecond"] <= normalized["nominalRateBytesPerSecond"], "initial rate exceeds nominal")
    if kind == "SERVICE_SEGMENT": same64(effective, normalized["intervalEnd"], "service effective/end mismatch")
    return dict(kind=kind, effective=effective, observed=observed, id=tid, ordinal=ordinal, body=normalized)


class TraceAudit:
    """Protocol phase reader; allocations are checked by bottleneck witnesses, not recomputed by filling."""
    def __init__(self, snapshot, budget):
        keys(snapshot, ("status", "events", "droppedCount", "engineTime"), "trace")
        self.status = word(snapshot["status"])
        need(self.status in ("COMPLETE", "TRUNCATED", "DISABLED"), "unknown capture status")
        self.clock = f64(snapshot["engineTime"], nonnegative=True)
        self.dropped = whole(snapshot["droppedCount"], 0)
        raw = array(snapshot["events"]); budget = whole(budget, 0, INT_MAX)
        if self.status == "DISABLED": need(budget == 0 and not raw and self.dropped == 0, "nonempty disabled capture")
        else:
            need(budget > 0 and len(raw) <= budget, "capture budget")
            need((self.dropped > 0 and len(raw) == budget) if self.status == "TRUNCATED" else self.dropped == 0, "status/drop mismatch")
        self.rows = [normalize_event(e, n) for n, e in enumerate(raw)]
        previous_effective = previous_observed = 0.0
        for e in self.rows:
            need(previous_effective <= e["effective"] <= e["observed"] <= self.clock and previous_observed <= e["observed"], "nonmonotonic event times")
            previous_effective, previous_observed = e["effective"], e["observed"]
        self.pos, self.ordinal, self.completed, self.certified, self.minimum = 0, 1, 0, 0, 0
        self.cursor, self.until, self.tail, self.partial = 0.0, None, "READY", False
        self.active, self.all, self.ids, self.caps, self.resources = {}, {}, {}, {}, {}
        self.epochs = 0

    def resource(self, name):
        if name not in self.resources:
            self.resources[name] = dict(resourceKey=name, lastDeclaredCapacityBytesPerSecond=None,
                                        rateAreaBytes=Fraction(0), boundedRateAreaBytes=Fraction(0), capacityAreaBytes=Fraction(0))
        return self.resources[name]

    def identity(self, e, f):
        need(e["id"] == f["id"] and e["ordinal"] == f["ordinal"], "wrong admission identity/order")

    def cut(self, phase, missing):
        need(self.status == "TRUNCATED", "complete capture ends inside " + phase)
        need(self.dropped >= missing, "dropped count cannot cover mandatory records")
        self.partial, self.tail, self.minimum = True, phase, missing
        return False

    def plan(self, horizon):
        before = list(self.active.values())
        duration, first = math.inf, None
        for f in before:
            value = f["remaining"] / f["rate"]
            if value < duration: duration, first = value, f["ordinal"]
        elapsed = min(horizon-self.cursor, duration)
        end = min(horizon, self.cursor+elapsed)
        after = {f["ordinal"]: max(0.0, f["remaining"]-f["rate"]*elapsed) for f in before}
        settled = [f for f in before if after[f["ordinal"]] <= tolerance(f["bytes"]) or
                   (f["ordinal"] == first and elapsed >= duration)]
        need(elapsed > 0 or (elapsed == 0 and duration == 0 and settled), "unjustified zero step")
        return before, elapsed, end, after, settled

    def progress_minimum(self, horizon, unknown_rate):
        before, elapsed, end, after, settled = self.plan(horizon)
        count = len(before)
        direct = (count if elapsed > 0 else 0) + len(settled) + (count-len(settled) if end < horizon else 0)
        return max(count, min(direct, count+(1 if unknown_rate else 2)))

    def loads(self, fixed=None):
        resources = {}
        for f in self.active.values():
            for name, weight in f["path"].items():
                if name not in self.caps: continue
                r = resources.setdefault(name, dict(load=Fraction(0), terms=0, maximum=0.0))
                r["terms"] += weight
                if fixed is None or f["ordinal"] in fixed:
                    r["load"] += q(f["rate"])*weight
                    r["maximum"] = max(r["maximum"], f["rate"])
        return resources

    def certify(self):
        resources = self.loads(); count = len(self.active)
        for name, r in resources.items(): need(r["load"] <= q(self.caps[name])+profile(self.caps[name], r["terms"]), "resource capacity exceeded")
        for f in self.active.values():
            need(0 < f["rate"] <= f["nominal"], "nominal rate cap")
            if near(f["rate"], f["nominal"], count): continue
            witnesses = []
            for name in f["path"]:
                if name not in resources: continue
                r = resources[name]
                witnesses.append(abs(r["load"]-q(self.caps[name])) <= profile(self.caps[name], r["terms"]) and
                                 q(r["maximum"])-q(f["rate"]) <= profile(max(r["maximum"], f["rate"]), count))
            need(any(witnesses), "no max-min bottleneck witness")

    def fixed_bounds(self, variable):
        fixed = set(self.active)-variable; resources = self.loads(fixed); uppers = {}
        for name, r in resources.items(): need(r["load"] <= q(self.caps[name])+profile(self.caps[name], r["terms"]), "fixed prefix exceeds capacity")
        for ordinal in variable:
            f = self.active[ordinal]; upper = q(f["nominal"])
            for name, weight in f["path"].items():
                if name not in resources: continue
                r = resources[name]; available = q(self.caps[name])+profile(self.caps[name], r["terms"])-r["load"]
                need(available > 0, "no positive capacity for variable prefix rate")
                upper = min(upper, available/weight)
            uppers[ordinal] = upper
        for ordinal in fixed:
            f = self.active[ordinal]
            if near(f["rate"], f["nominal"], len(self.active)): continue
            ceiling = q(f["rate"])/Fraction(999999999999, 1000000000000)
            possible = False
            for name in f["path"]:
                if name not in resources: continue
                r = resources[name]
                if q(r["maximum"])-q(f["rate"]) > profile(max(r["maximum"], f["rate"]), len(self.active)): continue
                upper = r["load"] + sum((min(uppers[v], ceiling)*self.active[v]["path"].get(name, 0) for v in variable), Fraction(0))
                if q(self.caps[name])-upper <= profile(self.caps[name], r["terms"]): possible = True; break
            need(possible, "fixed prefix has no possible bottleneck")

    def rates(self, excluded, observed, phase):
        last = 0
        while self.pos < len(self.rows) and self.rows[self.pos]["kind"] == "RATE_CHANGE":
            e = self.rows[self.pos]; f = self.active.get(e["ordinal"])
            need(f is not None and f["ordinal"] != excluded and f["ordinal"] > last, "invalid rate-change order")
            self.identity(e, f); last = f["ordinal"]
            same64(e["effective"], self.cursor, "rate boundary"); same64(e["observed"], observed, "rate observation")
            p = e["body"]; same64(p["previousRateBytesPerSecond"], f["rate"], "rate history")
            need(p["rateBytesPerSecond"] != f["rate"] and p["rateBytesPerSecond"] <= f["nominal"], "unchanged/over-cap rate")
            f["rate"] = p["rateBytesPerSecond"]; self.pos += 1
        if self.pos == len(self.rows) and self.status == "TRUNCATED":
            variable = {o for o in self.active if o > last and o != excluded}
            if variable:
                self.fixed_bounds(variable)
                stable = True
                try: self.certify()
                except CheckError: stable = False
                target = self.clock if self.until is None else self.until
                progress = (self.progress_minimum(target, True) if stable else len(self.active)) if self.active and self.cursor < target else 0
                return self.cut(phase, (0 if stable else 1)+progress)
        self.certify(); self.certified = self.pos
        return True

    def step(self):
        target = self.until
        need(self.active and target > self.cursor, "invalid pending advance")
        before, elapsed, end, after, settled = self.plan(target)
        continuation = len(before)-len(settled) if end < target else 0
        if elapsed > 0:
            self.epochs += 1
            for r in self.resources.values():
                if r["lastDeclaredCapacityBytesPerSecond"] is not None: r["capacityAreaBytes"] += q(r["lastDeclaredCapacityBytesPerSecond"])*q(elapsed)
            for offset, f in enumerate(before):
                if self.pos == len(self.rows): return self.cut("SERVICE_BATCH", len(before)-offset+len(settled)+continuation)
                e = self.rows[self.pos]; need(e["kind"] == "SERVICE_SEGMENT", "missing service in batch"); self.identity(e, f)
                p = e["body"]
                for actual, expected in ((e["effective"], end), (e["observed"], target), (p["intervalStart"], self.cursor),
                                         (p["intervalEnd"], end), (p["elapsed"], elapsed), (p["rateBytesPerSecond"], f["rate"]),
                                         (p["remainingBefore"], f["remaining"]), (p["remainingAfter"], after[f["ordinal"]])):
                    same64(actual, expected, "scalar service discontinuity")
                f["debit"] += q(p["remainingBefore"])-q(p["remainingAfter"])
                area = q(f["rate"])*q(elapsed); f["area"] += area
                for name, weight in f["path"].items():
                    r = self.resource(name); r["rateAreaBytes"] += area*weight
                    if r["lastDeclaredCapacityBytesPerSecond"] is not None: r["boundedRateAreaBytes"] += area*weight
                self.pos += 1
        for f in before: f["remaining"] = after[f["ordinal"]]
        self.cursor = end
        for offset, f in enumerate(settled):
            if self.pos == len(self.rows): return self.cut("COMPLETION_COHORT", len(settled)-offset+continuation)
            e = self.rows[self.pos]; need(e["kind"] == "COMPLETE", "missing expected completion"); self.identity(e, f)
            same64(e["effective"], self.cursor, "completion boundary"); same64(e["observed"], target, "completion observation")
            same64(e["body"]["remainingAfterService"], f["remaining"], "settlement residual mismatch")
            f["residual"] += q(f["remaining"]); f["remaining"] = 0.0; f["end"], f["observedEnd"] = self.cursor, target
            del self.active[f["ordinal"]]; del self.ids[f["id"]]; self.completed += 1; self.pos += 1
        if settled: return self.rates(None, target, "COMPLETION_RATE_CHANGES")
        self.certified = self.pos
        return True

    def run(self):
        if self.status == "DISABLED": self.tail = "DISABLED"; return self.metrics()
        while self.pos < len(self.rows) and not self.partial:
            if self.until is not None:
                if self.active and self.cursor < self.until:
                    if not self.step(): break
                    continue
                self.cursor, self.until = self.until, None
            e = self.rows[self.pos]; p = e["body"]
            if e["kind"] == "CAPACITY":
                need(not self.active and e["effective"] >= self.cursor, "capacity changed with active flows")
                self.cursor = e["effective"]; self.caps[p["resourceKey"]] = p["capacityBytesPerSecond"]
                self.resource(p["resourceKey"])["lastDeclaredCapacityBytesPerSecond"] = p["capacityBytesPerSecond"]
                self.pos += 1; self.certified = self.pos
            elif e["kind"] == "START":
                need(e["ordinal"] == self.ordinal and e["id"] not in self.ids, "admission ordinal/active-ID reuse")
                if self.active: same64(e["effective"], self.cursor, "unexplained active time gap")
                else: need(e["effective"] >= self.cursor, "admission before cursor"); self.cursor = e["effective"]
                f = dict(id=e["id"], ordinal=self.ordinal, bytes=p["bytes"], remaining=p["bytes"], nominal=p["nominalRateBytesPerSecond"],
                         rate=p["initialRateBytesPerSecond"], path=Counter(p["occupiedResources"]), start=self.cursor, end=None, observedEnd=None,
                         debit=Fraction(0), area=Fraction(0), residual=Fraction(0))
                self.active[self.ordinal] = self.all[self.ordinal] = f; self.ids[f["id"]] = self.ordinal
                for name in f["path"]: self.resource(name)
                self.ordinal += 1; self.pos += 1
                if not self.rates(f["ordinal"], e["observed"], "ADMISSION_RATE_CHANGES"): break
            elif e["kind"] in ("SERVICE_SEGMENT", "COMPLETE"):
                need(self.active and e["observed"] > self.cursor, "service/removal without later advance")
                self.until = e["observed"]
                if not self.step(): break
            else: raise CheckError("rate outside allocation block")
        if not self.partial:
            if self.until is not None:
                if self.active and self.cursor < self.until:
                    self.step()
                    if self.partial: return self.metrics()
                self.cursor, self.until = self.until, None
            if self.status == "COMPLETE" and self.active: same64(self.cursor, self.clock, "active service missing at watermark")
            if self.status == "TRUNCATED" and self.active and self.cursor < self.clock:
                self.cut("UNOBSERVED_TIME_PROGRESS", self.progress_minimum(self.clock, False))
            else:
                need(self.clock >= self.cursor, "watermark precedes cursor")
                self.tail = "PREFIX_AT_BOUNDARY" if self.status == "TRUNCATED" else "READY"
        return self.metrics()

    def metrics(self):
        validation = dict(captureStatus=self.status, recordCount=len(self.rows), admissionCount=self.ordinal-1,
                          completionCount=self.completed, openFlowCount=len(self.active), certifiedThroughSequence=self.certified,
                          tailPhase=self.tail, minimumMissingRecords=self.minimum)
        result = dict(accountingVersion=ACCOUNTING, validation=validation, droppedRecordCount=self.dropped, engineTime=self.clock)
        names = ("admittedPayloadBytes", "completedDemandBytes", "servicedBalanceDeltaBytes", "modeledRateAreaBytes",
                 "completionResidualBytes", "remainingLedgerBytes", "rateAreaMinusBalanceDeltaBytes", "integrationEpochCount",
                 "completedFctSampleCount", "meanEffectiveFctSeconds", "p95EffectiveFctSeconds", "maxEffectiveFctSeconds", "meanNotificationLagSeconds")
        if self.status != "COMPLETE": result.update({name: None for name in names}); result.update(flows=[], resources=[]); return result
        flows, times, lags = [], [], []
        for f in self.all.values():
            done = f["end"] is not None
            need(q(f["bytes"]) == f["debit"]+f["residual"]+q(f["remaining"]), "exact byte balance")
            row = dict(externalTransferId=f["id"], admissionOrdinal=f["ordinal"], complete=done, admissionTime=f["start"],
                       completionEffectiveTime=f["end"], completionObservedTime=f["observedEnd"],
                       effectiveFctSeconds=f["end"]-f["start"] if done else None,
                       observedFctSeconds=f["observedEnd"]-f["start"] if done else None,
                       notificationLagSeconds=f["observedEnd"]-f["end"] if done else None,
                       demandBytes=q(f["bytes"]), servicedBalanceDeltaBytes=f["debit"], modeledRateAreaBytes=f["area"],
                       completionResidualBytes=f["residual"], remainingBytes=q(f["remaining"]))
            flows.append(row)
            if done: times.append(row["effectiveFctSeconds"]); lags.append(row["notificationLagSeconds"])
        result.update(admittedPayloadBytes=sum((f["demandBytes"] for f in flows), Fraction(0)),
                      completedDemandBytes=sum((f["demandBytes"] for f in flows if f["complete"]), Fraction(0)),
                      servicedBalanceDeltaBytes=sum((f["servicedBalanceDeltaBytes"] for f in flows), Fraction(0)),
                      modeledRateAreaBytes=sum((f["modeledRateAreaBytes"] for f in flows), Fraction(0)),
                      completionResidualBytes=sum((f["completionResidualBytes"] for f in flows), Fraction(0)),
                      remainingLedgerBytes=sum((f["remainingBytes"] for f in flows), Fraction(0)))
        times.sort(); resources = []
        for value in self.resources.values():
            row = dict(value)
            row["integrationEpochUtilization"] = mean34(row["boundedRateAreaBytes"]/row["capacityAreaBytes"], 1) if row["capacityAreaBytes"] else None
            resources.append(row)
        result.update(rateAreaMinusBalanceDeltaBytes=result["modeledRateAreaBytes"]-result["servicedBalanceDeltaBytes"],
                      integrationEpochCount=self.epochs, completedFctSampleCount=len(times),
                      meanEffectiveFctSeconds=mean34(sum(map(q, times), Fraction(0)), len(times)),
                      p95EffectiveFctSeconds=times[(95*len(times)+99)//100-1] if times else None,
                      maxEffectiveFctSeconds=times[-1] if times else None,
                      meanNotificationLagSeconds=mean34(sum(map(q, lags), Fraction(0)), len(lags)), flows=flows, resources=resources)
        return result


def audit_trace(snapshot, budget):
    return TraceAudit(snapshot, budget).run()


def compare(expected, actual, path="metrics"):
    if expected is None: need(actual is None, "expected explicit null at " + path)
    elif isinstance(expected, dict):
        keys(actual, expected, path)
        for key, value in expected.items(): compare(value, actual[key], path+"/"+key)
    elif isinstance(expected, list):
        need(isinstance(actual, list) and len(expected) == len(actual), "array mismatch at " + path)
        for index, value in enumerate(expected): compare(value, actual[index], path+"/"+str(index))
    elif isinstance(expected, Fraction): need(expected == wire_fraction(actual), "exact decimal mismatch at " + path)
    elif type(expected) is float: same64(expected, f64(actual), "binary64 mismatch at " + path)
    elif type(expected) is int: need(expected == whole(actual), "integer mismatch at " + path)
    elif type(expected) is bool: need(type(actual) is bool and expected == actual, "boolean mismatch at " + path)
    else: need(expected == word(actual), "text mismatch at " + path)


def input_counters(raw):
    if raw is None: return None
    keys(raw, ("scope", "referenceCount", "localReferenceCount", "requiredReferenceBytes", "localReferenceBytes", "transferableReferenceBytes"), "input counters")
    need(raw["scope"] == SCOPE, "input reference scope")
    count, local = whole(raw["referenceCount"], 0), whole(raw["localReferenceCount"], 0)
    required_bytes, local_bytes, transfer = (wire_fraction(raw[key]) for key in ("requiredReferenceBytes", "localReferenceBytes", "transferableReferenceBytes"))
    need(local <= count and 0 <= local_bytes <= required_bytes and transfer == required_bytes-local_bytes, "input reference partition")
    need((count != 0 or required_bytes == 0) and (local != 0 or local_bytes == 0) and
         (count != local or local_bytes == required_bytes), "input count/byte inconsistency")
    return count, local, required_bytes, local_bytes, transfer


def _verified(doc):
    keys(doc, ("schema", "flowUnit", "numericProfile", "accountingVersion", "units", "evidence", "metrics"), "ledger")
    need(doc["schema"] == SCHEMA and doc["flowUnit"] == "V1_PARENT_OR_EXTERNAL_GROUP" and
         doc["numericProfile"] == PROFILE and doc["accountingVersion"] == ACCOUNTING, "ledger contract identity")
    need(doc["units"] == dict(time="SIMULATION_SECONDS", bytes="MODELED_BYTES", rate="BYTES_PER_SECOND"), "ledger units")
    e = keys(doc["evidence"], ("config", "modelKind", "traceSnapshot", "bindings", "engineCreated", "inputDemand"), "evidence")
    budget = option(e["config"]); need(e["modelKind"] in (ENDPOINT, FAT), "unsupported data model")
    need(type(e["engineCreated"]) is bool, "engineCreated must be boolean")
    engine = TraceAudit(e["traceSnapshot"], budget)
    need(engine.status != "DISABLED", "ON ledger cannot contain disabled trace")
    metrics = engine.run(); observed = input_counters(e["inputDemand"])
    rows = array(e["bindings"]); starts = [r for r in engine.rows if r["kind"] == "START"]
    need(len(rows) == len(starts) and len(rows) <= budget, "binding/retained START count")
    for binding, start_row in zip(rows, starts):
        keys(binding, ("externalTransferId", "admissionOrdinal", "jobId", "taskIds", "parentJobId", "groupKind", "sourceScope", "sourceEndpoint", "destinationEndpoint", "occupiedResources"), "binding")
        need(whole(binding["externalTransferId"]) == start_row["id"] and whole(binding["admissionOrdinal"], 1, budget) == start_row["ordinal"], "binding identity")
        whole(binding["jobId"], -(1 << 31), INT_MAX)
        for task in array(binding["taskIds"]): whole(task, -(1 << 31), INT_MAX)
        need(binding["groupKind"] in ("EXTERNAL_GROUP_V1", "PARENT_GROUP_V1") and binding["sourceScope"] == "MODELED_CONSTRAINED_SOURCE", "binding scope")
        if binding["groupKind"] == "EXTERNAL_GROUP_V1": need(binding["parentJobId"] is None, "external parent must be null")
        else: whole(binding["parentJobId"], -(1 << 31), INT_MAX)
        word(binding["sourceEndpoint"]); word(binding["destinationEndpoint"])
        need(words(binding["occupiedResources"]) == start_row["body"]["occupiedResources"], "binding path differs from START")
    if not e["engineCreated"]:
        need(not engine.rows and not rows and engine.status == "COMPLETE" and engine.clock == 0 and observed == (0, 0, 0, 0, 0), "false empty-engine capture")
    locality = "TRUNCATED_TRACE" if engine.status != "COMPLETE" else "INPUT_COUNTERS_UNAVAILABLE" if observed is None else "AVAILABLE"
    expected = dict(localityScope=SCOPE, transferMetrics=metrics, localityStatus=locality,
                    inputReferenceCount=None, localInputReferenceCount=None, requiredInputReferenceBytes=None,
                    localInputReferenceBytes=None, transferableInputReferenceBytes=None, admittedMinusTransferableReferenceBytes=None,
                    localByteFraction=None, localReferenceFraction=None)
    if locality == "AVAILABLE":
        count, local, required_bytes, local_bytes, transfer = observed
        expected.update(inputReferenceCount=count, localInputReferenceCount=local, requiredInputReferenceBytes=required_bytes,
                        localInputReferenceBytes=local_bytes, transferableInputReferenceBytes=transfer,
                        admittedMinusTransferableReferenceBytes=metrics["admittedPayloadBytes"]-transfer,
                        localByteFraction=mean34(local_bytes/required_bytes, 1) if required_bytes else None,
                        localReferenceFraction=mean34(Fraction(local, count), 1) if count else None)
    compare(expected, doc["metrics"])
    return engine, metrics


def verify_document(doc):
    engine, metrics = _verified(doc)
    complete = engine.status == "COMPLETE"
    return dict(status="VALID_COMPLETE" if complete else "VALID_PREFIX", completeCaptureCertified=complete,
                scope="FLUID_LEDGER_INTERNAL_CONSISTENCY_NOT_AUTHENTICATION", contextualRunChecked=False,
                captureStatus=engine.status, retainedRecords=len(engine.rows), droppedRecords=engine.dropped,
                admissionCount=engine.ordinal-1, completionCount=engine.completed,
                certifiedThroughSequence=engine.certified, tailPhase=engine.tail,
                exactBytes={key: decimal_text(metrics[key]) for key in
                            ("admittedPayloadBytes", "servicedBalanceDeltaBytes", "modeledRateAreaBytes", "completionResidualBytes", "remainingLedgerBytes")} if complete else None,
                meanEffectiveFctSeconds=metrics["meanEffectiveFctSeconds"])


# Manifest/context checks: no generic CPU/statistical-study certification is implied.

def _sum_rounding(scale, terms):
    return Fraction(0) if scale < sys.float_info.min else q(math.ulp(scale))*2*(terms+1)


class Topology:
    """Small arithmetic view; never allocate the untrusted topology's full link graph."""
    def __init__(self, raw, hosts):
        need(isinstance(raw, dict) and raw.get("kind") == "FAT_TREE", "Fat-tree declaration")
        self.k = whole(required(raw, "k"), 2, INT_MAX)
        need(self.k % 2 == 0 and self.k*self.k//2 <= INT_MAX, "Fat-tree size")
        self.half = self.k//2
        cores = required(raw, "coreSwitchCount")
        self.cores = self.half*self.half if cores is None else whole(cores, 1, self.half*self.half)
        self.available = (self.cores+self.half-1)//self.half
        self.capacity = f64(required(raw, "linkBandwidthMbPerSecond"), positive=True)*1000000.0
        need(math.isfinite(self.capacity), "link capacity overflow")
        explicit = required(raw, "hostEdgePlacements")
        if explicit is None:
            placements = {host: index % (self.k*self.half) for index, host in enumerate(sorted(hosts))}
        else:
            need(isinstance(explicit, dict) and set(explicit) == {str(h) for h in hosts}, "host placement coverage")
            placements = {h: whole(explicit[str(h)], 0, self.k*self.half-1) for h in hosts}
        need(len(hosts) <= self.k**3//4 and max(Counter(placements.values()).values(), default=0) <= self.half, "host placement capacity")
        self.placement = {h: divmod(edge, self.half) for h, edge in placements.items()}

    def link_count(self):
        return 2*len(self.placement) + 2*self.k*self.half*self.half + 2*self.k*self.cores

    def route(self, source, destination):
        need(source in self.placement and destination in self.placement, "unplaced host")
        if source == destination: return []
        s, d = self.placement[source], self.placement[destination]
        se, de = f"EDGE:{s[0]}:{s[1]}", f"EDGE:{d[0]}:{d[1]}"
        path = [f"LINK:ACC:{source}->{se}"]
        if se != de:
            agg = s[1] % self.available
            path.append(f"LINK:{se}->AGG:{s[0]}:{agg}")
            if s[0] != d[0]:
                count = min(self.half, self.cores-agg*self.half)
                core = agg*self.half + sum(s+d) % count
                path.extend((f"LINK:AGG:{s[0]}:{agg}->CORE:{core}", f"LINK:CORE:{core}->AGG:{d[0]}:{agg}"))
            path.append(f"LINK:AGG:{d[0]}:{agg}->{de}")
        return path+[f"LINK:{de}->ACC:{destination}"]

    @staticmethod
    def node(raw):
        parts = raw.split(":")
        need(len(parts) in (2, 3), "invalid network node")
        values = []
        for value in parts[1:]:
            need(len(value) <= 10 and value.isascii() and value.isdigit() and str(int(value)) == value, "noncanonical network identity")
            values.append(whole(int(value), 0, INT_MAX))
        return parts[0], values

    def link(self, key):
        if not key.startswith("LINK:") or key.count("->") != 1: return False
        a, b = (self.node(n) for n in key[5:].split("->"))
        def direction(x, y):
            kind, indexes = x; other, coordinates = y
            if kind == "ACC" and other == "EDGE" and len(indexes) == 1 and len(coordinates) == 2:
                return self.placement.get(indexes[0]) == tuple(coordinates)
            if kind == "EDGE" and other == "AGG" and len(indexes) == len(coordinates) == 2:
                return indexes[0] == coordinates[0] < self.k and indexes[1] < self.half and coordinates[1] < self.half
            if kind == "AGG" and other == "CORE" and len(indexes) == 2 and len(coordinates) == 1:
                return indexes[0] < self.k and indexes[1] < self.half and coordinates[0] < self.cores and indexes[1] == coordinates[0]//self.half
            return False
        return direction(a, b) or direction(b, a)


def check_context(manifest, document, events):
    e = document["evidence"]; snap = e["traceSnapshot"]
    config, platform, result = (required(manifest, key) for key in ("configuration", "platform", "result"))
    need(option(required(config, "networkEvidence")) == option(e["config"]), "manifest/ledger budget mismatch")
    model = required(required(config, "dataMovementModel"), "kind")
    need(model == e["modelKind"], "manifest/ledger model mismatch")
    fs = required(config, "fileSystem"); need(fs in ("LOCAL", "SHARED"), "file system")
    end = f64(required(result, "simulationEndSeconds"), nonnegative=True)
    need(f64(snap["engineTime"], nonnegative=True) <= end, "network watermark after run end")
    hosts = [whole(required(h, "id"), 0, INT_MAX) for h in array(required(platform, "hosts"))]
    need(len(set(hosts)) == len(hosts), "duplicate host")
    vms = {}; assignments = required(result, "actualVmHostAssignments")
    for vm in array(required(platform, "vms")):
        ident, host = whole(required(vm, "id"), 0, INT_MAX), whole(required(vm, "preflightHostId"), 0, INT_MAX)
        bandwidth = whole(required(vm, "bandwidth"), 1)
        need(ident not in vms and host in hosts and whole(required(assignments, str(ident))) == host, "VM placement/identity")
        capacity = float(bandwidth)*1000000.0
        need(math.isfinite(capacity), "VM capacity")
        vms[ident] = (host, capacity)
    need(len(assignments) == len(vms), "extra VM placement")
    topology = Topology(required(platform, "networkTopology"), hosts) if model == FAT else None
    if topology: need(fs == "LOCAL", "Fat-tree file system")
    else: need(required(platform, "networkTopology") is None, "topology/model mismatch")
    jobs = {}
    for raw in array(required(result, "jobs")):
        ident = whole(required(raw, "jobId"), 0, INT_MAX)
        job = dict(vm=whole(required(raw, "vmId"), -(1 << 31), INT_MAX), type=whole(required(raw, "classType"), 0, INT_MAX),
                   start=f64(required(raw, "startTime"), nonnegative=True), finish=f64(required(raw, "finishTime"), nonnegative=True),
                   tasks=[whole(t, 0, INT_MAX) for t in array(required(raw, "taskIds"))])
        need(ident not in jobs and job["start"] <= job["finish"] <= end, "Job identity/times")
        need(job["type"] != 2 or job["vm"] in vms, "Job VM")
        jobs[ident] = job
    stages, ready, refs, required_total, error = {}, {}, 0, Fraction(0), Fraction(0)
    for index, event in enumerate(events):
        need(whole(required(event, "sequence"), 0) == index, "main event sequence")
        kind = word(required(event, "type"))
        if kind not in ("JOB_READY", "DATA_STAGE_IN_MODELED"): continue
        if whole(required(event, "classType"), 0, INT_MAX) != 2: continue
        jid = whole(required(event, "jobId"), 0, INT_MAX); job = jobs.get(jid)
        need(job is not None and job["type"] == 2 and whole(required(event, "vmId")) == job["vm"], "main Job/VM")
        need([whole(t, 0, INT_MAX) for t in array(required(event, "taskIds"))] == job["tasks"], "event Task IDs")
        time = f64(required(event, "simulationTime"), nonnegative=True)
        need(time <= job["start"], "modeling/readiness after Job start")
        if kind == "JOB_READY": need(jid not in ready, "duplicate readiness"); ready[jid] = time; continue
        attributes = required(event, "attributes")
        need(required(attributes, "dataMovementModel") == model, "input model identity")
        files, groups = (whole(required(attributes, key), 0, INT_MAX) for key in ("modeledTransferFileCount", "contentionTransferGroupCount"))
        size = f64(required(attributes, "requiredFileBytes"), nonnegative=True)
        need(jid not in stages and groups <= files and not (groups > 0 and size == 0), "input grouping")
        stages[jid] = dict(time=time, files=files, groups=groups, size=size)
        refs += files; required_total += q(size); error += _sum_rounding(size, files+groups+1)
    for jid, job in jobs.items():
        if job["type"] == 2: need(jid in ready and jid in stages and ready[jid] == stages[jid]["time"], "missing readiness/input event")
    need(e["engineCreated"] or not stages, "input stages require engine")
    demand = input_counters(e["inputDemand"]); need(demand is not None, "missing actual input observations")
    need(demand[0] == refs and abs(demand[2]-required_total) <= error, "input observations/main demand mismatch")
    need(sum(stage["groups"] for stage in stages.values()) <= demand[0]-demand[1],
         "positive input groups require distinct nonlocal group references")
    bindings = {whole(b["admissionOrdinal"], 1): b for b in e["bindings"]}
    external_jobs = set()
    for b in bindings.values():
        jid = whole(b["jobId"], 0, INT_MAX); job = jobs.get(jid)
        need(job is not None and job["type"] == 2 and [whole(t, 0, INT_MAX) for t in b["taskIds"]] == job["tasks"], "binding Job/Task")
        need(b["destinationEndpoint"] == f"VM:{job['vm']}", "binding destination")
        if b["groupKind"] == "EXTERNAL_GROUP_V1":
            need(jid not in external_jobs, "V1 permits only one external input group per Job attempt")
            external_jobs.add(jid)
            need(b["sourceEndpoint"] == "source" and b["parentJobId"] is None, "external source")
            expected = ([] if topology else ["source"])+[b["destinationEndpoint"]]
        else:
            parent = jobs.get(whole(b["parentJobId"], 0, INT_MAX)); need(parent is not None and parent["vm"] in vms, "producer Job")
            source = f"VM:{parent['vm']}" if fs == "LOCAL" else "source"
            need(source == b["sourceEndpoint"], "modeled constrained source mismatch")
            expected = [source, b["destinationEndpoint"]]
            if topology: expected += topology.route(vms[parent["vm"]][0], vms[job["vm"]][0])
        need(expected == b["occupiedResources"], "declared deterministic path mismatch")
    cap_keys, completions, counts, payloads = set(), set(), Counter(), {}
    admitted, biggest, started, links = Fraction(0), 0.0, False, 0
    for index, raw in enumerate(snap["events"]):
        row = normalize_event(raw, index); p = row["body"]
        if row["kind"] == "CAPACITY":
            name = p["resourceKey"]
            need(not started and row["effective"] == 0 and name not in cap_keys, "run capacities must be initialized once")
            cap_keys.add(name)
            if name.startswith("VM:"):
                suffix = name[3:]; need(len(suffix) <= 10 and suffix.isascii() and suffix.isdigit() and str(int(suffix)) == suffix, "noncanonical VM resource")
                vm = vms.get(int(suffix)); need(vm is not None, "unknown VM capacity")
                expected = vm[1]
            else:
                need(topology is not None and topology.link(name), "unknown/unmodeled capacity resource")
                expected = topology.capacity; links += 1
            same64(p["capacityBytesPerSecond"], expected, "declared capacity mismatch")
        elif row["kind"] == "START":
            started = True; b = bindings.get(row["ordinal"]); need(b is not None, "unbound START")
            jid = whole(b["jobId"]); stage = stages.get(jid)
            need(stage is not None and stage["time"] == row["observed"], "START before/after Job-ready")
            if b["parentJobId"] is not None: need(jobs[whole(b["parentJobId"])]["finish"] <= row["observed"], "transfer before parent finish")
            counts[jid] += 1; amount = q(p["bytes"]); admitted += amount; biggest = max(biggest, p["bytes"])
            payloads[jid] = payloads.get(jid, Fraction(0))+amount
        elif row["kind"] == "COMPLETE":
            b = bindings.get(row["ordinal"]); need(b is not None, "unbound COMPLETE")
            need(row["observed"] <= jobs[whole(b["jobId"])]["start"], "Job starts before observed input completion")
            completions.add(row["ordinal"])
    complete = snap["status"] == "COMPLETE"
    if e["engineCreated"] and (complete or started):
        needed = topology.link_count() if topology else 0
        need({f"VM:{ident}" for ident in vms} <= cap_keys and links == needed and len(cap_keys) == len(vms)+needed, "missing/extra initial capacities")
    for jid, stage in stages.items():
        need(counts[jid] <= stage["groups"] and (not complete or counts[jid] == stage["groups"]), "group count mismatch")
        need(payloads.get(jid, Fraction(0))-q(stage["size"]) <= _sum_rounding(stage["size"], stage["files"]+stage["groups"]+1), "payload exceeds input demand")
    if complete:
        need(len(completions) == len(bindings), "unfinished run input groups")
        need(demand[4] == 0 if admitted == 0 else abs(admitted-demand[4]) <= _sum_rounding(biggest, refs+1), "admission/reference rounding gap too large")


def inspect_path(path):
    path = Path(path).absolute(); doc = read_document(path)
    need(isinstance(doc, dict), "root must be an object")
    if doc.get("schema") == SCHEMA: return verify_document(doc)
    # Lazy closed V2/V3 dispatch: the V1 document/context checks stay unchanged.
    if doc.get("schema") == "workflowsim-storage-lifecycle-v3":
        from _storage_lifecycle_audit import verify_document as verify_storage_lifecycle
        return verify_storage_lifecycle(doc)
    if doc.get("schema") == "workflowsim-file-lifecycle-v2":
        from _file_lifecycle_audit import verify_document as verify_file_lifecycle
        return verify_file_lifecycle(doc)
    config = doc.get("configuration")
    if (isinstance(config, dict) and "dataflowAssignment" in config) or "dataflowComputeRequests" in doc:
        # Fail closed before legacy OFF dispatch; online is an explicit, known
        # coherent configuration, never an algorithmContract-only declaration.
        from _dataflow_assignment_audit import _configuration
        _configuration(doc)
    model = config.get("dataMovementModel") if isinstance(config, dict) else None
    recording = config.get("networkEvidence") if isinstance(config, dict) else None
    artifacts = doc.get("artifacts")
    platform = doc.get("platform")
    storage_kinds = ("COHERENT_STORAGE_DATAFLOW_V3", "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3")
    storage_model = isinstance(model, dict) and model.get("kind") in storage_kinds
    storage_mode = isinstance(recording, dict) and recording.get("mode") == "FILE_STORAGE_LIFECYCLE_V3"
    storage_role = isinstance(artifacts, list) and any(
        isinstance(item, dict) and item.get("role") == "storage-lifecycle" for item in artifacts)
    storage_platform = isinstance(platform, dict) and "sourceStorage" in platform
    standalone_mode = isinstance(doc.get("recording"), dict) and doc["recording"].get("mode") == "FILE_STORAGE_LIFECYCLE_V3"
    if storage_model or storage_mode or storage_role or storage_platform or doc.get("modelKind") in storage_kinds or standalone_mode:
        from _storage_lifecycle_context import inspect_path as inspect_storage_lifecycle_context
        return inspect_storage_lifecycle_context(path, doc)
    coherent = isinstance(model, dict) and model.get("kind") in (
        "COHERENT_FILE_DATAFLOW_V2", "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2")
    lifecycle_mode = isinstance(recording, dict) and recording.get("mode") == "FILE_LIFECYCLE_V2"
    lifecycle_role = isinstance(artifacts, list) and any(
        isinstance(item, dict) and item.get("role") == "file-lifecycle" for item in artifacts)
    if coherent or lifecycle_mode or lifecycle_role or "dataflowPlan" in doc:
        from _file_lifecycle_context import inspect_path as inspect_file_lifecycle_context
        return inspect_file_lifecycle_context(path, doc)
    need(doc.get("schema") == "workflowsim-experiment-manifest-v4", "only network ledger-v1, file lifecycle-v2, storage lifecycle-v3 or manifest-v4 is supported")
    config, references = required(doc, "configuration"), {}
    for item in array(required(doc, "artifacts")):
        role, name = word(required(item, "role")), word(required(item, "path"))
        relative = Path(name)
        need(name not in ("", ".", "..") and "\x00" not in name and not relative.is_absolute() and len(relative.parts) == 1 and relative.name == name, "artifact path must be a contained filename")
        need(role not in references, "duplicate artifact role")
        target = path.parent/relative
        try: raw = target.read_bytes()
        except OSError as error: raise CheckError("missing artifact: " + name) from error
        need(hashlib.sha256(raw).hexdigest() == required(item, "sha256") and len(raw) == whole(required(item, "sizeBytes"), 0), "artifact hash/size mismatch")
        references[role] = target
    enabled = "networkEvidence" in config
    need(enabled == ("network-ledger" in references), "recording option/role mismatch")
    if not enabled:
        return dict(status="DISABLED", completeCaptureCertified=False, contextualRunChecked=False,
                    scope="NO_NETWORK_EVIDENCE_REQUESTED_NOT_ZERO_TRAFFIC")
    option(config["networkEvidence"])
    document = read_document(references["network-ledger"]); report = verify_document(document)
    need("events" in references and "metrics" in references, "complete bundle roles required")
    try:
        lines = references["events"].read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as error: raise CheckError("invalid main event stream") from error
    need(all(line.strip() for line in lines), "blank main event line")
    events = [decode_json(line) for line in lines]
    check_context(doc, document, events)
    report["contextualRunChecked"] = True
    report["scope"] = "FLUID_LEDGER_AND_RUN_NETWORK_CONTEXT_NOT_GENERAL_SIMULATION_AUDIT"
    return report
