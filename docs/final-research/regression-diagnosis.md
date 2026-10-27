# Final regression diagnosis: closed cases 001-018

Read-only diagnosis on 2026-10-25, using only completed final-series cases 001-018 and the already completed final-v2 test evidence. No new JVM, simulation, parameter change or retry was performed. The running series and its active checkpoints were not inspected. Array indices below are zero based. Scores are `(served contacts, useful seconds, modeled security-operation CPU seconds, management bytes, new directional keys)`.

## X5: the available bounded candidates did not recover the third contact

Final offline X5 Next and Multi, under both CANONICAL and CONTINUE_WAIT, finish with `(2,38,0.12,160,0)`. C1 and C2 each deliver 19 s; C3 delivers nothing. The actual runs perform two OPENs, no PREPARE and no INSTALL. A's original pair expires at t=70, before the t=80 return, and no replacement is installed for C3. Reaching scenario end is a completed execution, not a claim that these choices are optimal.

The decisive saved root is t=0. Before C1, at t=-2, H2 sees only the two future contacts C1/C2, with evaluation end t=50. When C1 opens, the visible set expands to current C1 plus C2/C3 and evaluation end t=85. The old certified continuation is rejected for `FORECAST_CHANGED`; the portfolio separately replays/extends a feasible retained plan for the new root. These are distinct mechanisms: invalidating a certificate does not mean the practical retained candidate is absent.

| Saved root at t=0 | Case 002 CANONICAL | Case 004 CONTINUE_WAIT |
| --- | ---: | ---: |
| Search status / stop | INCOMPLETE / NODE_LIMIT | INCOMPLETE / NODE_LIMIT |
| Explored nodes / completed terminal plans | 5000 / 1 | 5000 / 1 |
| Search elapsed seconds | 0.9120896 | 0.9870704 |
| Executed INSTALL transitions in this search | 0 | 0 |
| SEED score | `(2,38,0.32,160,2)` | same |
| RETAINED score after extension | `(2,38,0.32,160,2)` | same |
| SEARCH score, selected | `(2,38,0.12,160,0)` | same |

The SEED and RETAINED candidates at this root postpone PREPARE until t=78 and contain no INSTALL. They create a spare too late to make the five-second C3 usable. SEARCH supplies the same two-contact service without that unused preparation, so the unchanged comparator correctly prefers its lower CPU/key count among the available candidates. Its selected first action is OPEN on C1. A three-contact installation continuation is not present in this saved portfolio; the comparator does not reject a known three-contact plan in favor of CPU savings.

At t=0, immediate INSTALL is not generated because no new pair is prepared; PREPARE branches are present. The search ledger records zero INSTALL transitions before this 5000-node stop. Thus the evidence supports a bounded exploration/seed-quality explanation, not a policy prohibition on installing. It does not prove that a three-contact plan is infeasible, that every alternative was explored, or that a different budget/traversal would produce any particular result. No such new run was made.

Both Multi executions contain 78 entries in `practical-searches.json`: 11 COMPLETE and 67 INCOMPLETE/NODE_LIMIT. The compact controller summary reports only the 11 certified search selections; the remaining bounded selections appear as RULE decisions with reasons such as `PC2_SEARCH: best available fully evaluated continuation; no optimality certificate`. Use the practical-search ledger when counting actual search incompleteness, rather than reading the compact summary's `searchStatuses` as a complete tally. All final metrics still come from actual execution.

**Evidence anchor 1.** [Case 002 practical-searches](../../target/final-research/runs/002-regression-X5-offline-multi-H2-canonical/practical-searches.json), index 1, and [its portfolio](../../target/final-research/runs/002-regression-X5-offline-multi-H2-canonical/practical-portfolio.json), index 1, are the t=0 search/candidate comparison. [Its compact decisions](../../target/final-research/runs/002-regression-X5-offline-multi-H2-canonical/actual.decisions.json), indices 0-1, establish the forecast expansion and executed OPEN. The corresponding files/index 1 in [case 004](../../target/final-research/runs/004-regression-X5-offline-multi-H2-continue_wait/practical-searches.json) establish the same outcome under CONTINUE_WAIT. The initial t=-2 search is index 0: COMPLETE, 618 nodes, but only to t=50.

## Why the passing original witness test is consistent with this result

The original `ControlledWitnessIntegrationTest` tests a different execution contract. `HorizonPlannerMain.checkpoint` performs a common PREPARE from t=-2 to t=0 before optimization; the prepared pair and its 0.20 s CPU/two new keys already exist. `ControlledWitnessMain` then uses the exact receding controller with a 2,000,000-node per-decision limit and no search wall-time deadline. The test requires COMPLETE at every decision, reaches t=85, and asserts X/Next `(2,38,0.32,160,2)` and X/Multi `(3,37,0.83,1240,2)`, including that warmup exactly once. It also asserts an INSTALL in C1 for X/Multi.

The final X5 model instead starts at t=-2 without prescribed preparation and uses the practical seed/retention portfolio, SERVICE_FIRST traversal, 5000-node/1000-ms search budgets and the 2000-ms decision budget. Both preparation state and algorithmic/execution conditions differ from the original test. That test establishes the preserved mechanism on its specified fixture; it does not promise that every practical autonomous execution will discover it. Likewise, the final lower CPU and zero new keys reflect work never executed, not a cheaper way to serve the third contact.

The final-v2 full suite actually reran this test successfully: its [Surefire XML](../../target/final-research/final-v2-build/target/surefire-reports/TEST-org.contactaware.security.simulation.ControlledWitnessIntegrationTest.xml) records one test, zero failures/errors/skips. The [build log](../../target/final-research/final-v2-build.log) records this class at lines 84-90 and all 478 tests passing at line 203, followed by BUILD SUCCESS at line 243. The assertions are in `src/test/java/org/contactaware/security/simulation/ControlledWitnessIntegrationTest.java:15-41`; warmup construction is in `HorizonPlannerMain.checkpoint`. This is read evidence from the completed freeze build, not a second test run by this diagnosis.

## Measured X5: fewer complete frames, unchanged operation costs

| Final measured CANONICAL result | Next, case 013 | Multi, case 014 |
| --- | ---: | ---: |
| Served contacts | 2 | 2 |
| C1 useful seconds / complete frames | 15.75 / 63 | 9.25 / 37 |
| C2 useful seconds / complete frames | 18.00 / 72 | 18.25 / 73 |
| Total useful seconds | 33.75 | 27.50 |
| Operation CPU / management bytes / new keys | 0.12 / 160 / 0 | 0.12 / 160 / 0 |
| Sum of recorded modeled decision latencies, seconds | 4.6169243 | 11.3924867 |
| Largest modeled decision latency, seconds | 0.3640746 | 1.1236288 |
| Deadline misses in the latency ledger | 0 | 0 |

At C1 AOS, Next's OPEN decision becomes available at t=0.2662676; Multi's at t=1.1236288. Their first complete C1 frames finish at t=1.7757867 and t=2.8281293. Subsequent decision delays also advance physical time before a new frame can start. Each full X frame consumes 0.25 s; the actual journals contain 135 versus 110 complete frames, exactly accounting for 33.75 versus 27.50 useful seconds. Multi loses 26 frames in C1 and gains one in C2, a net loss of 25 frames/6.25 s. Both still exceed the four-frame minimum in each direction for C1/C2, so served count remains two.

All modeled delays remain below the two-second deadline; meeting that deadline does not make computation free. Both executions still perform the same two OPENs, hence the same operation costs. These CPU costs exclude planner host CPU. The sum of decision latencies is not itself the useful-time loss: computation during waits or already committed work can overlap elapsed scenario time, and frame granularity and replanning change the path. The measured result is host-specific, not onboard performance or an intrinsic six-second horizon penalty.

Case 014 also records one `STALE_INTENT_REPLAN` at latency index 40: a CONTINUE chosen at t=19.7403336 becomes available at t=19.7564473. A fresh 0.25 s frame from that point would end after C1's t=20 boundary. The controller therefore replans instead of executing the stale intent. No deadline miss is necessary for this loss of feasibility.

**Evidence anchor 2.** [Case 013 latency-decisions](../../target/final-research/runs/013-regression-X5-measured-next-H1-canonical/latency-decisions.json), indices 1 and 3, and [case 014 latency-decisions](../../target/final-research/runs/014-regression-X5-measured-multi-H2-canonical/latency-decisions.json), indices 1, 3 and 40, record the availability/revalidation facts. The adjacent `result.json` and `actual.events.json` files supply the per-contact frame/operation totals. `PlanningLatencyController` advances committed work to `availableAt` and regenerates/revalidates candidates before execution; it does not subtract host time directly from an already computed final score.

## The prior high-demand witness now ties on all five final objectives

Cases 009-012 (offline, both rules) and 017-018 (measured CANONICAL) all finish with `(3,410,2.13,3240,6)`. Each delivers 37 complete 10-second frames in C1 and one frame per direction in each of C2/C3: 370+20+20=410 useful seconds. Each executes exactly three PREPARE/INSTALL/SWITCH/OPEN cycles. The operation arithmetic is therefore `3*(0.20+0.30+0.15+0.06)=2.13` CPU seconds and `3*(800+200+80)=3240` management bytes, with six directional keys.

Equal frame counts do not imply equal timing or computation. Measured Next's last C1 frame finishes at `2026-10-12T00:14:55.604648566Z`; Multi's at `00:14:57.867201666Z`; C1 ends at `00:14:59.990386963Z`. Both still fit the same 37 complete frames. The small finite demand on C2/C3 also fits for both. This saved witness therefore shows equality at the registered final version and budgets, while its prior adverse/incomplete versions remain historical evidence. It does not establish that higher demand always benefits or never harms either horizon, nor that the whole candidate portfolio is globally optimal.

**Evidence anchor 3.** [Case 017 result](../../target/final-research/runs/017-regression-demand-none-measured-next-H1-canonical/result.json) and [case 018 result](../../target/final-research/runs/018-regression-demand-none-measured-multi-H2-canonical/result.json), `summary.finalMetrics` and `simulationMetrics.contacts/completedOperations`, contain the common score and operation count. Their adjacent `actual.events.json` files contain the differing final C1 frame times. Cases 009-012 have the identical five-metric score in the same fields.

The evidence supports three distinct discussion points: the practical search can preserve service yet fail to find an installation-dependent horizon benefit; computation can lower useful delivery even when deadlines and security-operation costs are unchanged; and a formerly adverse high-demand case can become a tie after the frozen controller improvements. None requires retuning this final matrix, hiding its negative cases, or revising the original controlled witness claim.
