# Saved R1 decision diagnosis

Date: 2026-10-25. Read-only analysis of production code and existing R1 artifacts; no JVM, simulation, search replay, or new experiment was run. Array indexes below are zero based. `attempt` indexes address `practical-searches.json`; `decision` indexes address `actual.decisions.json`. Scores are `(servedContacts, usefulSecureTime seconds, controlCpu seconds, managementBytes, newKeys)` relative to each search's own origin. They must not be compared across different origins as if they were whole-run scores.

Evidence root: `target/research-validation/practical-v2/runs/`. Compact extracted records are in `target/practical-controller/diagnosis/r1-decision-evidence.json`.

## Observed outcomes

| Saved run | Status | Actual final score | Interpretation |
|---|---|---|---|
| 005/007 X5 H1, either rule | FINISHED | `(2,38,0.12,160,0)` | C3 unserved. |
| 006/008 X5 H2, either rule | FINISHED | `(2,38,0.12,160,0)` | C3 unserved; this is not an observed H2 loss against H1. |
| 017/019 demand-none H1, either rule | FINISHED | `(3,410,2.13,3240,6)` | Three PREPARE/INSTALL/SWITCH/OPEN operations each. |
| 018 demand-none H2 CANONICAL | FINISHED | `(3,410,2.78,4240,8)` | Same service, one extra PREPARE/INSTALL/SWITCH cycle: +0.65 CPU s, +1000 management bytes, +2 keys. |
| 020 demand-none H2 CONTINUE_WAIT | FINISHED | `(2,40,1.42,2160,4)` | First contact skipped; loses 370 useful seconds against H1. |
| 025–028 ISS46, H1/H2 and both rules | CONTROLLER_TIME_LIMIT | No final score | Incomplete executions under the old 20-second controller cap; not complete-policy losses. |

Final scores and operation totals come from each run's `result.json`. Its `summary.searchStatuses` describes searches exported as SEARCH decisions, not every practical attempt. For example 006 reports ten COMPLETE searches there, but `practical-searches.json` contains thirteen attempts, including three INCOMPLETE searches whose actions were exported as RULE decisions. The latter ledger is required for diagnosis.

## Three concrete decision witnesses

### 1. X5: the only evaluated terminal plan is retained through the preparation opportunity

Run `006-X5-practical-H2-CANONICAL`, decision/attempt **3**, at **2026-01-01T00:00:01Z**, selects **CONTINUE A until 00:00:01.25Z**. Attempt status is INCOMPLETE, stop reason NODE_LIMIT, explored nodes 5000, terminalPlans **1**, score **`(2,38,0.06,80,0)`**. Its best plan contains no PREPARE/INSTALL/SWITCH. It serves C1 and C2 and waits through C3.

Decision 1 at 00:00:00Z first sees three contacts (evaluation end 00:01:25Z); its only evaluated terminal plan is also the no-renewal plan, selecting OPEN A at TIME_LIMIT after 3767 nodes. Decision 2 again has one terminal plan at 5000 nodes. Decision 4 at 00:00:01.25Z starts feasible suffix reuse from attempt 3. The saved audit has 75 such reuses, carrying that plan through C1. Attempt 4 at 00:00:20Z is COMPLETE from the resulting state, with only C2 service remaining.

`target/research-validation/models/X5.json` fixes A's old-key expiry at 00:01:10Z, before C3 at 00:01:20–00:01:25Z. INSTALL (4 s), SWITCH (1 s), OPEN (1 s) alone exceed that five-second return contact. The ledger contains no renewal during C1, so later COMPLETE decisions cannot repair the lost installation opportunity. Run 008 reproduces the same actions and scores under CONTINUE_WAIT; its first limited attempt stops after 1661 nodes instead.

**Established:** bounded search returns one terminal incumbent, then feasible reuse commits its no-renewal continuation. **Not established here:** the exact best alternative plan or its score at this root. No fixed-first-action replay or exhaustive comparison was performed. Opening A at decision 1 is not itself proved wrong; the consequential evidence is failure to renew during C1 and commitment to that continuation.

### 2. demand-none CONTINUE_WAIT: one bounded WAIT discards the whole first contact

Run `020-demand-none-practical-H2-CONTINUE_WAIT`, decision/attempt **1**, at **2026-10-12T00:08:37.296295166Z**, selects **WAIT until 00:14:59.990386963Z**, exactly the first contact's end. Attempt status is INCOMPLETE, stop reason TIME_LIMIT, nodes **3909**, score **`(2,40,1.42,2160,4)`**. The actual decision resumes at that contact end, so no intervening replanning can bootstrap service in that contact.

Decision 0 had been COMPLETE over two future contacts, score `(2,390,1.42,2160,4)`. At decision 1 the current contact plus two future contacts become visible. This is a changed horizon; its earlier COMPLETE score cannot be reused as a certificate for the new origin. Nevertheless, the prior plan documents a service-capable continuation which could be replayed and extended as an uncertified seed within the new budget. The new search's best plan instead bootstraps only the two later contacts. This record proves loss caused by the executed WAIT, not that CONTINUE_WAIT changes the five-metric objective.

### 3. demand-none CANONICAL: replanning activates staged material too early

Run `018-demand-none-practical-H2-CANONICAL`, decision **50** / attempt **14**, at **2026-10-12T00:14:57.296295166Z**, selects **SWITCH SATNOGS-3634 to g2**, completing at 00:14:58.296295166Z. Attempt status is INCOMPLETE, stop reason NODE_LIMIT, nodes **5000**, score **`(2,40,1.43,2440,2)`**.

The trace establishes how this extra cycle arose:

1. Attempt 9 at 00:08:43.296295166Z already held a terminal plan with score `(3,410,1.22,2160,2)`. It PREPAREs g2 at 00:13:53, INSTALLs at 00:14:23, and delays SWITCH until the return at **01:47:18.202056885Z**.
2. Decision 40 executes that PREPARE via feasible suffix reuse. The reuse chain ends at this security action. Attempt 10 replans at 00:13:53 with preparation pending; its NODE_LIMIT incumbent instead INSTALLs g2 at 00:14:53, SWITCHes at 00:14:57, and prepares g3 for the return. The old plan is not passed in as an incumbent.
3. Decision 48 executes INSTALL via a suffix of attempt 12; decision 50 executes SWITCH. At decision 52, a COMPLETE search now chooses PREPARE g3 from the already-changed state.
4. Event **95**, `STEP_COMPLETED/EXPIRE_CONTEXT`, retires g2 at **01:44:58.296295166Z**, before the return. The fixed model's cryptoperiod is **5400 s**, exactly 90 minutes after g2 activation. No payload frame uses g2 between the early switch and expiry. The result therefore pays for g2's preparation, installation, and switch without service benefit, then uses g3 at the return.

**Established:** feasible suffix replacement changes delayed activation to early activation, expiry precedes the return, and the final ledger adds one complete key cycle. **Not established here:** a same-origin replay score proving the previously retained suffix dominates attempt 10; its old score has a different origin. Replay/rebase is the required check before using that dominance claim in code or a report.

## Code mechanism and ISS corroboration

`ResearchValidationMain` constructs `acceleratedServiceFirst()` and a new bounded search at every uncached decision. Its configuration uses 5000 nodes, 1000 ms per search, and a 20 s outer controller cap. `deadline-ms=250` is present in configuration but, with zero latency and no host scale, the latency-controller branch is not selected; it is not the search deadline in these R1 runs.

`ExactHorizonPlanner` performs depth-first search. SERVICE_FIRST ranks CONTINUE, SWITCH, INSTALL, OPEN, REUSE, WAIT, then PREPARE; same-rank actions use the canonical action comparator. Only a completed terminal evaluation or solved memo suffix can update the global incumbent. The final five-metric comparator and configured plan tie-break still determine which encountered terminal plan wins. A bounded traversal can therefore explore a deep service/wait subtree before useful preparation siblings, or eagerly activate installed material before exploring delayed activation.

`BudgetedHorizonPolicy` accepts any admissible non-null terminal incumbent after NODE_LIMIT/TIME_LIMIT. Its fallback is only used when no such incumbent exists. Thus the observed bad WAIT cannot be repaired by changing the fallback alone. Feasible reuse validates the full observation and candidate membership but does not improve an incumbent; it follows the current suffix through nominal frames, executes a following security action, and ends the reuse chain there.

ISS46 H2 runs 026 and 028 show the same service-starvation pattern: attempt/decision 1 at **2026-10-10T00:07:54.680328369Z** returns WAIT to **00:10:45.617523193Z**, the first contact end, with NODE_LIMIT/5000 and score `(1,20,1.31,1080,8)`. Their final attempts 36/35 respectively stop with CONTROLLER_TIME_LIMIT and disposition STOP. This confirms the distinction between a harmful completed action and an external cap that prevents finishing the schedule. It does not provide a full ISS comparison.

## Bounded repair proposal and required proof

Use the same strategy, resource accounting, candidate set, constraints, costs, and comparator for H1 and H2; only visible contacts may differ.

* Preserve an old terminal suffix as a **candidate**, replay it against the new observation, complete it to the new evaluation end if needed, and evaluate it from the new origin before seeding the search. Charge replay and extension to the same decision deadline/node budget. Reject on changed facts or invalid actions. Do not retain an optimality certificate across a changed horizon, and never compare scores from different origins.
* Add a small deterministic portfolio of feasible rollout seeds, or bounded root diversification, common to both horizons. Include current-contact bootstrap and observation-derived renewal/delayed-activation choices. This is traversal/initial-incumbent work, not a new objective criterion or feasibility filter. Preserve all candidates for exact completion. A cheap local fallback alone cannot fix a bad existing incumbent.
* Keep process safety termination separate from the repeated decision deadline. A decision timeout may publish a feasible candidate; process cancellation/outer termination must still stop execution.

Focused tests should prove: (1) a replayed valid old suffix beats a deliberately worse new incumbent at the **same origin**, including PREPARE/pending-operation boundaries; changed observations reject the seed; (2) a bootstrap seed prevents the whole-contact WAIT witness and a delayed-activation seed avoids the g2 expiry cycle under fixed deterministic budgets; (3) when search completes, full plan and score match the exhaustive reference for both tie-breaks; no partial result gains a certificate; (4) total seeding plus search honors the decision budget, while cancellation and process cap cannot become fallback permission; (5) matched H1/H2 runs retain identical settings other than horizon. X5 needs an explicit root replay demonstrating that renewal is actually discovered within the common bounded strategy; the saved evidence alone is not that validation.

Validation performed for this note: parsed saved JSON and cross-checked decisions, attempt ledgers, event sequences, input timing/constraints, final operation counts, and the production control path. No tests or experiments were executed.

## Subsequent same-root replay by the main agent

After this read-only diagnosis, the separately registered `target/practical-controller/continuation-replay/` probe restored actual decision 41 of run 018, immediately after the selected PREPARE. It replayed the remaining 31 actions of attempt 9 and the 39 actions found by fresh attempt 10 through the common nominal executor, without any new search. Both continuations were admissible and reached the identical evaluationEnd. Restoration reproduced the complete available observation, including the pending preparation and allocated IDs.

| Continuation, evaluated from decision 41 | Newly served | Useful seconds | CPU seconds | Management bytes | New directional keys |
| --- | --- | --- | --- | --- | --- |
| Retained attempt-9 suffix | 2 | 100 | 1.22 | 2160 | 2 |
| Fresh attempt-10 incumbent | 2 | 100 | 1.93 | 3240 | 4 |

The unchanged comparator prefers the retained suffix on CPU, with 0.71 fewer CPU seconds, 1080 fewer management bytes and two fewer keys. These are same-origin incremental values: the already served contact and prior costs are not counted again. This confirms one concrete loss of a known feasible continuation, rather than inferring it from scores at different roots. Neither continuation is claimed optimal. The probe uses a fixed two-second cooperative/30-second process limit and its input, source, JAR, logs and full output are retained. No production retention behavior was changed in the budget-separation series.
