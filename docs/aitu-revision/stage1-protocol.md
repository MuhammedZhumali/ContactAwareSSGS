# Stage 1 registration specification

Written before new policy runs. Machine registration and content hashes will be saved in `target/aitu-revision/stage1/protocol.json` before execution.

## Common inputs and comparison

Use the frozen final-v2 X5 scenario, retaining its costs, payload, constraints, initial keys and contact geometry. No attacks. A: actual common PREPARE(A) at -2 seconds, execute to t=0 with journal and costs preserved. B: autonomous start at -2 seconds, no prescribed operations and no spare pair. In each initialization compare exact/practical, H1/H2, CANONICAL/CONTINUE_WAIT: 16 full-controller cells. Both use the same SecurityPolicyController with suffix reuse enabled, the same acceleratedServiceFirst search, v2 decision times, resource constraints, full comparator, and actual execution engine. Only solver allowance/acceptance and the declared practical portfolio differ. An INCOMPLETE exact search stops the exact controller; it is not a policy loss.

Root diagnostics use the paired initial snapshot for each block and an additional autonomous t=0 snapshot obtained by waiting without payload or PREPARE. The latter is labelled a diagnostic common root, not the independent autonomous policy's actual path. At -2, H2 sees only two future contacts; at t=0, H2 sees all three. Root comparisons must preserve this distinction. Three roots x two horizons x two rules = 12 paired diagnostics.

Every paired root saves the full actual snapshot, PlanningObservation, admissible root actions, two solver results and the practical candidate audit. Both solvers receive the very same immutable observation instance. Before and after hashes and snapshot/observation equality verify branch isolation. Practical uses SEED_AND_RETENTION; fresh initial roots have no retained candidate and this is recorded explicitly. Full-controller logs provide retained/search/seed candidates at subsequent decisive roots.

## Budgets

- Exact: 2,000,000 nodes and 30 seconds per search; 120 seconds cumulative controller allowance; 150 seconds process watchdog.
- Practical: 5,000 nodes and 1 second per search; 2 seconds per decision; existing 350 ms seed/retained stage limits and 10,000 rollout transitions; 120 seconds controller; 150 seconds watchdog.
- Root diagnostics: 60 second watchdog per paired exact/practical root plus replay. The exact/practical search allowances above apply independently.
- JVM: one at a time, 1024 MiB heap. Offline search does not advance model time.
- No retry or budget increase following an incomplete outcome. Infrastructure failures are preserved and investigated, not silently replaced.

The time cap differs from the old unlimited-time exact test. A result is called exact only on COMPLETE. Both limits and their actual trigger are reported. Diagnostics are not compared as speed measurements against uninstrumented historical runs.

## Diagnostic replay and first discovery

For each COMPLETE exact winner, replay all actions using BranchEngine's admissibility checks, require the common evaluationEnd, evaluate with ContinuationEvaluator, and compare with PlanningResultComparator under the same rule. Also offer the plan ONLY to a separate diagnostic PracticalPlanPortfolio instance and record whether it is accepted, rejected, or interrupted by its fixed replay stage limit. This instance cannot provide actions to any ordinary controller.

A separately identified instrumentation overlay records every improvement of the terminal incumbent with explored node count and elapsed time. It does not change actions, scores, pruning, traversal or tie breaking. First discovery is reported for the requested useful continuation when observed, otherwise as not found within the allowance. A small equivalence control compares instrumented and frozen root results including the full plan and node count with wall-time stopping disabled and a fixed small node limit; it is a harness validation, not another campaign cell.

## Interpretation and completion

Full-run outcomes are comparable only on the same full schedule/initialization. Root scores are compared only within the same H/evaluationEnd. Never directly rank Next and Multi horizon scores. All completed and incomplete attempts remain in denominators. Final metrics include common warmup exactly once; incremental forecast scores are never summed. Changed initialization, search discovery, candidate validation and controller behavior are analyzed separately.
