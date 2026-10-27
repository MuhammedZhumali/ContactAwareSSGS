# Stage 1 independent evidence review

2026-10-28. Status: **central explanation supported; evidence-export follow-up pending**. This review read saved Stage 1 inputs/outputs and narrowly inspected their diagnostic instrumentation. It ran read-only JSON comparisons and hashes, not Java, tests, experiments, or the campaign analyzer. It did not inspect Stage 3 output.

## Findings

The interpretation in `stage1-results.md:28-34` separates initialization from search sufficiently: the prepared treatment removes the observed service gap for this configuration, while the autonomous bounded treatment does not recover the additional service. It does not claim that preparation is the only cause, that an unfinished exact execution lost, or that finite-horizon optimality establishes whole-mission optimality. Root tuples are never added or directly ranked across H1/H2.

Independent structural comparisons passed for all **16 initial file pairs**: snapshot and observation for each of eight matched exact/practical configurations. Only the five explicitly declared Set-valued field names were sorted; other list/event/action order and every value were preserved. Four additional comparisons confirmed that the autonomous t=0 H2 diagnostic observation equals the first actual t=0 observation in each corresponding exact/practical controller trace. All 12 root outputs report unchanged snapshot/observation/actions; all 11 COMPLETE root replay plans equal their saved exact winners. These are saved-output checks, not a fresh replay execution.

## Discovery, validity, and tie resolution

At the autonomous t=0 H2 root, both rules record their first incumbent at node **163**, with `(2,38,0.12,160,0)`. The first strict five-score improvement is at **134269**, with `(3,37,0.83,1240,2)`. Recorded elapsed times are **8.1449003 s CANONICAL** and **8.2851468 s CONTINUE_WAIT**. This directly verifies the report's first-discovery number. The corresponding practical diagnostic searches each exhaust **5000 nodes**, stopping on **NODE_LIMIT**, after 0.4964998 and 0.5750573 s respectively. The 1 s timer is an allowance, not the observed stopping cause in these roots.

CONTINUE_WAIT finishes at **134638 nodes**. Its 164-action complete plan equals both the ordinary BranchEngine replay plan and the diagnostic portfolio's selected retained plan. The replay score equals the exact score; `exactVsPracticalOrder=-1`; diagnostic provenance is `source=RETAINED`, `retention=FEASIBLE_RETAINED`. The inspected harness requires endpoint/score/plan equality, uses the ordinary evaluator/comparator, and constructs a separate oracle-assisted portfolio (`scripts/aitu/AituRootMain.java:56-75`). No oracle plan enters an ordinary controller on this path.

The actual practical t=0 portfolios have SEED and RETAINED candidates `(2,38,0.32,160,2)` and a selected SEARCH candidate `(2,38,0.12,160,0)`. Therefore the fresh-root diagnostic's absent retention does not hide a stronger retained candidate in the actual controller at this root.

CANONICAL records **1027 incumbent updates but only two distinct five-score tuples**: after the improvement at 134269, the remaining 1025 updates improve the plan tie order. It times out after 507296 explored nodes in the instrumented diagnostic. The separate actual exact controller's t=0 search also has a three-contact incumbent but stops on TIME_LIMIT after **415167 nodes**; its final mission metrics correctly remain unavailable. This supports distinguishing finding useful service from completing the full objective/tie-order proof. It does not prove that tie handling is the sole source of the timeout.

The instrumentation source differs from the ordinary planner only by an observational incumbent callback in `finish`; all five manifest source hashes match. The saved equivalence control matches plan/score/action/status/nodes at 1544 nodes with no internal wall timer. This supports the narrow instrumentation check, not universal zero-overhead equivalence. Instrumented host times must retain their stated limitation.

## Connection to actual execution

The t=0 diagnostic is a common planning root; **both first actions there are OPEN**. It is not the first executed-action disagreement.

For autonomous CONTINUE_WAIT, the actual traces first differ at **decision index 54 (zero-based), t=13.75 s**, after identical preceding executed actions. Their observations at this decision are equal. Exact executes `PREPARE(A, planner-key:10:WITNESS-SC:1:A:g1)` until 15.75 s. Its source is `SUFFIX_REUSE`, with `rootProofReference=decision:3`, `suffixOffset=51`, `checkedProofReference=decision:3`, `checkedOffset=50`, and guard `ALLOWED / ONE_FRAME_NOMINAL`. Practical executes `CONTINUE` until 14 s, with source `RULE`, reason `PC2_SEARCH`, and guard `UNSUPPORTED / NO_PROOF`. This is the saved execution-level connection between maintaining a proved favorable continuation and the eventual three-versus-two-contact outcome.

Evidence: `target/aitu-revision/stage1/runs/run-autonomous-{exact,practical}-H2-CONTINUE_WAIT/actual.full-trace.json`, `decisions[54]`. The equality checked here is the full permitted observation; the trace does not export a full execution snapshot for every decision, so this must not be relabeled a fresh per-decision snapshot equality proof.

## Denominator and remaining export work

There are **28 registered and attempted process cells**, with zero reused/not-run cells: 16 full-controller attempts (15 FINISHED, one SEARCH_INCOMPLETE) and 12 paired-root diagnostics (11 exact COMPLETE, one exact INCOMPLETE). The report's separate status labels preserve this distinction; do not call all 26 completed cells completed mission executions.

All **64 hashes present in the inspected audit** match. However, that audit version hashes no root files and omits full traces/initial observations used by equality checks. The parent has expanded analyzer hash coverage and clarified the observed practical stopping cause; the generated audit/report still awaited refresh at the final read for this review. Regenerate those evidence exports and verify their added hashes before closing the reproducibility item. This requires no new policy experiment. Include the first-action divergence above in the final explanation or supplement.

Critical inspected file hashes, captured independently here:

| File under `target/aitu-revision/stage1/runs/` | SHA-256 |
|---|---|
| `root-autonomous-t0-H2-CANONICAL/exact-improvements.json` | `42105b014ce3b08bb5c82d798d57e27c69021c373c7c30adbba6f364e2ea1763` |
| `root-autonomous-t0-H2-CONTINUE_WAIT/exact-improvements.json` | `410855586689f745444d147f84f597631fb2b49cfcd80cca8472296fe6755bb2` |
| `root-autonomous-t0-H2-CONTINUE_WAIT/root-result.json` | `8fbc5f51c4e3a0689937d2b07d0dd9775e2957dbdd4748252afb59cc8d3d05c8` |

No unsupported numerical result or unfair same-root objective comparison was found within this bounded review. The remaining issues concern evidence coverage and explicit linkage of planning-root and execution-root claims.


Parent integration note, 2026-10-28: the final analyzer audit and `validation.json` now verify 978 input/evidence hashes, including root and trace exports. This closes the previously noted export-hash coverage gap without changing the independent numerical findings.
