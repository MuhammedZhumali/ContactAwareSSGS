# SE2.1 recovery milestones from frozen SE2 journals

This is descriptive postprocessing of the already attempted SE2 grid, not a new
experiment or a preregistered SE2 outcome. No policy decision, executor event,
traffic offer, probe frame, attack instant or lifecycle transition is added.
The source remains `target/security-evaluation-se2`. All times below are exact
decimal seconds from factual GS notice in the attacked context.

## Four separate measurements

| Column prefix | First observed condition at or after GS notice |
|---|---|
| `spacecraft_notice` | `REVOCATION_RECEIVED_SPACECRAFT`, supported by the preceding completed `NOTIFY_REVOCATION` at the same instant and for the disclosed pair. A GS notice alone is insufficient. |
| `independent_pair` | Both directional bound traffic keys are independent, active, installed at both peers, unexpired, authorized, unblocked, and synchronized with their SAs; both counters retain capacity. Normally this first becomes true at completed `SWITCH`. |
| `independent_sa` | The same joint pair condition plus two actually `OPERATIONAL` SAs from completed `OPEN`/`REUSE` (or an initial open state), with a matching currently open physical contact. |
| `independent_frame` | First delivered factual target-context frame ending strictly after GS notice, using an independent pair and an admissible open SA at admission. The frame ends no later than either original key deadline. No demand is invented to test readiness. |

Pair readiness is a material-and-binding condition. It does not assert immediate
radio availability or that `OPEN` is admissible without a matching contact.
Contact-specific reuse gaps are validated on actual `OPEN`/`REUSE` using each
direction's previous successful contact end. This separation makes key readiness,
actual session availability, and delivered service independently interpretable.

Independence means **both key IDs differ from every captured ID** in the factual
`KEY_DISCLOSED` pair, within the model's assumption of independent fresh
generations and uncompromised authenticated management protection. It is not a
cryptographic inference from names, creation time, or key age. Prepared or
installed `PRE_ACTIVE` keys do not satisfy the active-pair milestone. Revocation
notice and key activation remain distinct even if an independent pair was
already active when the GS learned of the incident.

## Status and missingness

Each milestone has `status`, `at`, `seconds`, `eventSequence`, `censorAt`, and
`reason`. CSV empty cells and JSON null are missing values, never numeric zero.

| Status | Meaning |
|---|---|
| `already-ready-at-origin` | The reconstructed pair/SA already satisfied the condition immediately before the GS notice event. Delay is genuinely zero; `at` is GS notice, not a claimed new activation timestamp. |
| `event` | A factual event first established the condition at/after the origin. Evidence sequence identifies the row in the original journal. A zero-delay event would remain `event`. |
| `censored` | An applicable origin was observed but this milestone was not observed by the execution's actual end. `seconds` is null and `censorAt` gives that end. |
| `not-applicable` | No attack, explicitly observed `DISCLOSURE_NO_TARGET`, or an undetected attack with no GS-notice origin by design. |
| `unavailable` | Required evidence or the GS-notice origin has not been observed; e.g. a search prefix ends before notice. This does not assert zero recovery or a physical NO_TARGET outcome. |

All 488 registered attempts have rows. Only `FINISHED` rows have
`qualityEligible=true`; all 14 `SEARCH_INCOMPLETE` rows have
`evidenceScope=observed-prefix` and are excluded from final-quality aggregates.
Observed prefix milestones may be examined only with that label; censoring ends
at `actual.at`, never the planned end. Controls and undetected attacks retain
their rows and not-applicable metrics. The JSON names all excluded cases.

## Reconstruction and loss of readiness

Replay starts from `scenario.initialState`, not the final state. Completed
`PREPARE`, `INSTALL`, `SWITCH`, `OPEN`, `REUSE`, `STOP`, `EXPIRE_CONTEXT`, notices,
and confirmed delivered-frame counters update the relevant state. Ground notice
retires the captured pair locally; it does not imply spacecraft confirmation.
Fresh `SWITCH` never resurrects a deactivated key. Final key records are used
only to check inventory, counter conservation, and original deadlines after the
reconstruction; they are not backfilled into earlier states.

An initial key keeps
`min(notAfter, createdAt + maxMaterialAge, activatedAt + cryptoperiod)`;
the activation term is absent before activation. Fresh preparation fixes its
material-age deadline, and first activation may only shorten it. Reuse, close,
reopen, or new SA bindings never extend that deadline or reset counters. An
instantaneous admission at the deadline is forbidden; a factual frame may end
exactly at the deadline. Counter capacity, endpoint synchronization, context,
direction, authorization and known blocks are checked for both directions.

`readiness-intervals.csv` and the JSON retain each positive-duration pair/SA
interval, its pair IDs, original deadlines, start evidence, and end reason.
Readiness is reevaluated at original deadlines and contact boundaries as well as
journal timestamps; synthetic analysis boundaries are not inserted into the
executor. `key-deadline`, `counter-exhausted`, `session-closed`,
`binding-changed`, `readiness-lost`, and `observation-end` distinguish ends.
An early activation is therefore not interpreted as uninterrupted readiness to
the first delivered frame. Intervals are clipped to `[GS notice, actual end)`.

## Overlapping conditions, not additive causal delays

`overlapping-conditions.csv` partitions the observed interval into contiguous
segments carrying simultaneous booleans:

- no target contact;
- spacecraft notice pending;
- no independent installed material pair (including live `PRE_ACTIVE` material);
- independent active pair not ready;
- independent SA not actually open;
- no full offered target frame remaining in a currently open target contact.

The last flag is true both outside a target contact and when that contact's
directional offers are exhausted. Offers and delivered progress come from the
original model and factual frames. An unfinished or aborted frame does not
invent successful delivery or additional offered traffic. These flags describe
concurrent necessary conditions, not exclusive causes: e.g. a contact gap can
overlap pending notice, unavailable material, and absent current demand. Their
durations **must not be added** or interpreted as causal shares of the recovery
latency. They cover the entire observation after notice, including times after
the first frame. An in-flight independent frame or management resource use can
also delay the next delivery; no additive causal decomposition is claimed.

## Manually checked examples

The values below are independently recalculated from original journal event
times and are regression assertions in `test_recovery_metrics_se21.py`.

| Case | SC notice | Active pair | Open SA | Delivered frame |
|---|---:|---:|---:|---:|
| X original Next | 0.4 | 7.4 | 69 | 69.25 |
| X original Multi | 0.4 | 1.4 | 2.4 | 2.65 |
| ISS CP5400 p30 Next | 0.4 | 7.4 | 5661.427337647 | 5671.427337647 |
| ISS CP5400 p30 Multi | 0.4 | 1.4 | 5657.427337647 | 5667.427337647 |

X has origin `2026-01-01T00:00:12Z`. Next activates at `00:00:19.4Z`
(sequence 69), first opens the independent pair in C3 at `00:01:21Z`
(160), and delivers at `00:01:21.25Z`. Multi activates at `00:00:13.4Z`
(53), opens at `00:00:14.4Z` (57), and delivers at `00:00:14.65Z`.
Its first SA interval closes at C1 end; the underlying pair stays valid through
the gap and opens again in C3. Next's 69.25 seconds is therefore not its time to
independent key activation.

ISS p30 has origin `2026-10-10T00:08:47.961486816Z`. Both C1 offered frames
were delivered before notice. Multi g2 is active from `00:08:49.361486816Z`
to `01:38:49.361486816Z`; Next g2 from `00:08:55.361486816Z` to
`01:38:55.361486816Z`. Each lasts exactly 5400 seconds, then expires before
C3 AOS `01:43:03.388824463Z`. Neither opens a replacement SA in C1 after
exhausting its offer. In C3, Multi activates g3 at `01:43:04.388824463Z`,
opens at `01:43:05.388824463Z`, and delivers at `01:43:15.388824463Z`;
Next activates g3 at `01:43:08.388824463Z`, opens at `01:43:09.388824463Z`,
and delivers at `01:43:19.388824463Z`. The earlier C1 activation and later
four-second frame advantage are separate observations, with a readiness gap
between them. The frozen mechanism audit identifies Multi's earlier INSTALL g3
as the C3 difference; this postprocessor does not claim a causal intervention.

## Coverage, provenance, and reproduction

Among 474 finished runs, 188 have applicable detected origins; the remaining
286 are controls or undetected attacks. SC notice is observed in 180 and censored
in 8; active pair in 167/21; open independent SA in 148/40; delivered independent
frame in 148/40. There are no already-ready-at-origin examples in this finite
grid; synthetic unit fixtures explicitly exercise that status. No NO_TARGET
case occurred in the grid.

The postprocessor checks all finished delivered-frame milestones against frozen
SE2 `securityMetrics.firstIndependentFrameAt`: all agree. JSON records SHA-256
for every read manifest/model/summary/journal/actual-state input, the new
postprocessor, and the unchanged SE1 audit helper. Models are checked against
their registered hashes. These hashes identify evidence bytes; they are not an
independent proof of the simulator or cryptography. The postprocessor reuses the
SE1 exact-time/frame parser and independently reconstructs readiness state.

From the repository root, using an available Python 3 runtime:

```text
python -X utf8 -m unittest discover -s scripts -p test_recovery_metrics_se21.py -v
python -X utf8 scripts/recovery_metrics_se21.py target/security-evaluation-se2 --out docs/security-evaluation-se21/tables
```

Verification: 12 focused Python tests pass, including four manually checked
frozen runs; the postprocessor completes all 488 attempts. No Java build, policy
rerun, executor changes or new traffic were used for these metrics.
