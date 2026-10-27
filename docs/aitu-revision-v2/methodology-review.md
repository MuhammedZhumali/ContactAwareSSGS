# AITU v2: preregistration and negative-case review

Date: 2026-10-28. Scope: the new request's Sections 2 and 4; current `PracticalPlanPortfolio.java`; saved Stage 1 autonomous t=0 H2 root artifacts; and the four relevant Stage 4 table rows. No JVM, experiments, production edits, or new-result inspection. This file is the independent reviewer's sole owned output. **Disposition: proposed bounded design is suitable with the trigger clarification below. New numerical results await a later independent check.**

## Frozen comparison evidence

Both saved `root-autonomous-t0-H2-{CANONICAL,CONTINUE_WAIT}/observation.json` files have identical SHA-256:

`7913287d76aa1af808e4f4f6a2267d990eb1c15fc64e1de3eecb17b3345b3246`.

The observation is autonomous X, t=0, H=2, evaluationEnd=t=85 s. Reusing the CW copy for both comparator rules therefore introduces no observed cross-rule input difference. Preserve this identity and exact deserialization/round-trip equality for all six new roots. Do not substitute the autonomous t=-2 root, which has different visibility.

The old fresh practical portfolio has SEED `(2,38,0.32,160,2)`, selected SEARCH `(2,38,0.12,160,0)`, and `NO_RETAINED_PLAN` under both rules. Its search stops at 5000 nodes with NODE_LIMIT. The old exact instrumentation records the first strictly better five-score at node134269: `(3,37,0.83,1240,2)`, at 8.1449003/8.2851468 s for CANONICAL/CW. CW completes at134638; CANONICAL remains incomplete at its30 s timer. These are historical observations of this traversal/root, not a node-budget lower bound or guaranteed new outcome.

## Six-search registration

Register six cells before execution: CANONICAL and CONTINUE_WAIT crossed with100000,150000,200000 allowed nodes, search30 s, whole-decision32 s, external watchdog45 s. Fix serial execution order, heap, classpath/source hashes, traversal options, decision-time model, observation hash, candidate generator and ID rules. Run the entire six-cell diagnostic block before choosing follow-up cells; no result-driven replacements, repeats, or budget increases.

Use a fresh ordinary `PracticalPlanPortfolio(SEED_AND_RETENTION)` per root. The shared generator/evaluator/comparator and the finite action set must remain unchanged. A missing retained candidate is **not** a zero-score candidate or a rejection: report `NO_RETAINED_PLAN`, as expected from `PracticalPlanPortfolio.java:67-68,82`. This fresh-root experiment does not by itself validate retention behavior on an already evolving trajectory.

The new32 s enclosing scope must replace every old2 s decision scope, and the planner must receive30 s rather than1 s. Existing350 ms seed and retained-replay phase caps remain visible and unchanged (`PracticalPlanPortfolio.java:72,86`). Thirty seconds plus these phase allowances fits nominally inside32 s, but generation, comparison, prediction and export still consume host time; no wall-time allowance guarantees consumption of N nodes. Preserve TIME_LIMIT, DECISION_TIME_LIMIT, process-watchdog and technical-failure outcomes separately. A process killed before export is incomplete, not evidence that no improvement existed. The frozen350 ms seed may itself fail under host contention; preserve its status and do not invent a seed score.

The observational overlay is acceptable if its original source/hash and incumbent-only modification remain identified. Its event records include improvements in the **full** comparator, including pure plan tie changes. Therefore report separately:

1. Strict five-score changes relative to the previous search incumbent (`compareMetrics<0`).
2. The first search candidate strictly beating the current completed seed's five-score, when that seed exists.
3. The first search candidate strictly beating the frozen old practical five-score.
4. Final portfolio selection, source, score, full plan, first action, actual nodes/stop reason and certificate presence.

Do not call a tie-order update a strict five-score improvement. Replaying the final selected plan through ordinary BranchEngine admission and ContinuationEvaluator must reach the same endpoint and reproduce its complete score/plan. Perform this diagnostic validation after ordinary selection; it must not supply an incumbent, retained suffix or action to that selection. Record certificate presence from the actual selection: a feasible bounded incumbent is not certified merely because its score matches an older exact result (`PracticalPlanPortfolio.java:148-152`).

## Trigger and bounded full executions

The parent accepted this preregistered trigger during review: **the replay-validated selected practical result must strictly improve the frozen ordinary practical five-score `(2,38,0.12,160,0)`**. Improvement over the seed alone does not qualify: the known elimination of unnecessary preparation already improves `(2,38,0.32,160,2)` without improving autonomous service.

For each tie rule independently, choose the smallest of the three registered node allowances satisfying that trigger. This selection algorithm is fixed before the six searches; the selected numerical allowance is necessarily determined afterward. If none qualifies, schedule no full runs for that rule. An improved feasible incumbent may qualify despite incomplete search, provided selection and replay succeed; label its search status explicitly. It is only the smallest **tested allowance under these timers**, not a globally minimal sufficient node budget.

Run exactly one Next/Multi autonomous-X pair for each qualifying rule, using the same selected allowance, search30 s, decision32 s, controller180 s and watchdog210 s: zero, two, or four follow-up executions. Start from the frozen autonomous initial state, not the prepared state or an oracle-derived snapshot. Use fresh policies with no inherited diagnostic state. Compare their final five-score tuples only when both executions finish on the entire common schedule. Preserve controller/process/search incompleteness as unresolved; a successful t=0 continuation is not an additional served contact until actual execution demonstrates it.

Prefer the frozen non-observational planner for full runs. The root overlay's ThreadLocal incumbent list is cleared explicitly by the diagnostic caller, so carrying it into ordinary full execution without a declared per-search clearing/export scheme risks accumulated memory and timing overhead. If full runs use any overlay, register that separately rather than calling the implementation identical without qualification.

Maintain separate denominators for six planned root searches and the conditionally selected full executions. Report why a follow-up was not scheduled; it is distinct from a planned execution that failed. Keep v1 one-second-budget results, v2 root diagnostics and v2 full runs in separate tables/labels. Do not promote this finite experiment to a universal practical advantage or a universal134269-node requirement.

## Independently checked negative late-disclosure rows

Source: `target/aitu-revision/stage4/tables.json`, IDs `Eminus5-delay{0,2}-H{1,2}-CANONICAL`. All four rows are FINISHED and RECOVERED. Tuple order is served contacts / useful seconds / lifecycle CPU seconds / management bytes / new directional keys.

| Detection delay | Next tuple | Multi tuple | Confidential delivered bytes, Next / Multi | Compromise to first independent frame, Next / Multi |
|---:|---|---|---:|---|
| 0 s | `(3,410,2.17,3336,6)` | `(3,400,2.82,4336,8)` | `4000 / 4000` | `5559.211669922 / 5559.211669922 s` |
| 2 s | `(3,410,2.17,3336,6)` | `(3,400,2.82,4336,8)` | `4000 / 4000` | `5559.211669922 / 5559.211669922 s` |

Multi loses under the five-component objective in both cells: served contacts tie and useful time is10 s lower; CPU is additionally0.65 s higher, management1000 B higher and new keys two higher. Confidential delivery and final recovery latency tie. These three outcomes must not be merged into a single claim of benefit.

| Delay | Policy | Delivered / emitted B | Exposed emitted before / after disclosure B | Compromised delivered B | Aborted frames |
|---:|---|---:|---:|---:|---:|
| 0 s | Next | `41000 / 41000` | `37000 / 0` | 37000 | 0 |
| 0 s | Multi | `40000 / 40769` | `36769 / 0` | 36000 | 1 |
| 2 s | Next | `41000 / 41000` | `37000 / 0` | 37000 | 0 |
| 2 s | Multi | `40000 / 40969` | `36769 / 200` | 36000 | 1 |

For delay0, ground-relative notice/pair/SA/frame delays are Next `(0.4,5548.211669922,5549.211669922,5559.211669922)` s and Multi `(0.4,1.4,5549.211669922,5559.211669922)` s. For delay2 they are Next `(0.4,5546.211669922,5547.211669922,5557.211669922)` and Multi `(0.4,1.4,5547.211669922,5557.211669922)` s. Adding the2 s ground-detection delay recovers the same compromise-origin total. Earlier first independent activation therefore gives no independent-SA or delivered-frame advantage in either row. The already preserved v1 readiness-interval analysis explains why early material can expire before later delivery; the table alone must not be presented as a continuous-readiness proof.

Smaller exposed totals in Multi coexist with lower ordinary delivered traffic and aborted prefixes; they are not an increase in confidential delivered bytes. These are consequences under the fixed disclosure model and do not estimate compromise probability. No new attack experiment is needed.

## Follow-up status

Preregistration and the four historical negative rows are reviewed. New six-search outputs, their replays/certificates, conditional budget selection, and any subsequent full executions are **not yet reviewed**. Await the parent's bounded handoff for that final numerical check.

## Final independent numerical check, 2026-10-28

This section supersedes the pending follow-up status above. No JVM or experiment was run. The review checked the v2 protocol, registration, summaries/tables, audit and their necessary raw artifacts. All 130 `audit.json.inputHashes` matched current files; registration links and invocation hashes matched. The denominator remains **six root searches plus four full controller attempts**, with three completed missions and one controller timeout. Historical v1 attempts are separate.

All six root invocations consume the same frozen observation. Their exported observations differ only in `constraints.authorizedContexts` array order for CW150k/200k; sorting that array makes all six equal. They are not all byte-identical exports. Every `portfolio.json` records `FEASIBLE_TERMINAL`, `NO_RETAINED_PLAN`, and only SEED/SEARCH candidates. Each selected full plan and five-score equals its search, portfolio and replay artifacts; all six `root-result.json` files report accepted replay and unchanged root.

Both 100k searches select `(2,38,.12,160,0)`; both 150k/200k rules select `(3,37,.83,1240,2)`. Independently comparing `improvements.json` five-scores confirms first improvement over the frozen practical reference at node134269, 9.1303918–9.6537307 s, in those four searches. CAN150k/200k have53/208 comparator updates but only one strict five-score update each. CAN searches stop at node limits without certificates; CW150k/200k finish at134638 with actual certificates. Replay feasibility does not confer certification. The registered trigger correctly selects150k for each rule; this is the smallest tested allowance, not a general threshold.

At150k, both Next missions finish with `(2,38,.12,160,0)`; CW Multi finishes with `(3,37,.83,1240,2)` in32.4259253 controller seconds. CAN Multi stops at180.0153904 controller seconds, physical t=4.75 s: its `(1,3.75,.06,80,0)` is a prefix and `finalScore=null`. Independently recomputing four `actual.events.json` journals confirms the five metrics/prefixes,563 event sequence entries and18 final key counters. CPU/management costs come from journal usage, keys from creation records, service from delivered directional minima; frame counts reconcile counters. Full-controller invocations omit the root observational overlay and charge zero host delay.

No blocker found. Root continuation quality does not establish mission completion; the30 s search regime does not revise the historical1 s result. CAN incompleteness is unresolved, not a loss. CW's lexicographic improvement serves one extra contact while useful time falls1 s and CPU/management/new-key costs increase. It is neither improvement in every metric nor a measured-delay or general security advantage.
