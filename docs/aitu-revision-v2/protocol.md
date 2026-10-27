# AITU revision 2: bounded budget clarification

Registered before new outcomes. No objective, security constraint, action set, cost,
input scenario, ID rule, final tie order, or traversal order is changed.

## Owner map and scope

- Parent: preservation, harness, registration, all serial JVMs, integration and final QA.
- Methodology reviewer: only `methodology-review.md`, independent numerical checks.
- Method/format reviewer: only `method-and-aitu-review.md` and `method-description.json`.
- Production sources and all prior campaign artifacts are read-only for this task.

## Six main root searches

Use the saved autonomous X observation at t=0, H=2 from
`target/aitu-revision/stage1/runs/root-autonomous-t0-H2-CONTINUE_WAIT/observation.json`.
The paired saved CANONICAL observation must be semantically equal. Restore/check
the matching initial checkpoint but supply only PlanningObservation to planning.
Record hashes and the equality check before search. Both policies use the same
frozen final-v2 code, V2_EVENT_BOUNDARIES and acceleratedServiceFirst traversal.

Cartesian product is limited to CANONICAL/CONTINUE_WAIT x 100000/150000/200000 nodes.
Each fresh process uses an ordinary fresh PracticalPlanPortfolio with
SEED_AND_RETENTION. Thus the initial retained candidate is absent, explicitly
recorded as NO_RETAINED_PLAN. No exact plan, external seed, replay offer, warmed
incumbent or cross-run memo is supplied. Existing seed and suffix limits remain
350ms/10000 transitions. Search limit=30s, whole selection limit=32s, external
process watchdog=45s, heap=1024MiB. One JVM at a time. No automatic retries.

The previously validated observational ExactHorizonPlanner overlay records all
incumbent changes. It changes no branching/comparison; timings include its
overhead. The original frozen planner remains in full-controller executions.
The former 134269-node discovery is an observation, not a budget lower bound.

Record allowed/visited nodes, search and portfolio elapsed/CPU telemetry, stop
reason, seed/retained/search/selected evaluations and complete plans, first action,
all incumbents and first strict five-coordinate improvements (versus preceding
incumbent, current seed when present, and the frozen old practical winner).
Replay the selected continuation through BranchEngine/ContinuationEvaluator to
the same endpoint, checking the full plan and all five coordinates. Record an
actual in-process certificate boolean; do not serialize it as a reusable proof.
Time-capped outcomes remain time-capped even below the node allowance.

## Conditional full executions (zero to four)

For each tie rule independently, select the smallest registered node budget whose
ordinary selected and replay-validated root score is strictly better in the five
criteria than the frozen ordinary t=0 practical score (2,38,0.12,160,0).
Improvement over only the seed (2,38,0.32,160,2) is insufficient to trigger this block.
If no such budget exists, no full pair is run for that rule.

Run one matched offline autonomous Next/Multi pair per qualifying rule using
the unchanged X5 input, ordinary frozen planner, chosen node limit, search30s,
decision32s, controller180s, external watchdog210s, heap1024MiB. Initialization
remains the existing autonomous start (-2s), not an artificially improved t=0
checkpoint. Initial preparation, if selected, is therefore a real decision/cost.
Maximum four new controller executions. Limits are not increased after results.
Preserve prefix evidence and explicit stop statuses; unfinished trajectories are
not final mission scores or policy losses. A root gain is not a mission gain.

## Other work and interpretation

No new attack series. Recompute the two existing CANONICAL E-5/delay0,2 comparisons
from saved stage4 tables and journals. Distinguish five-coordinate service/resource
ordering, recovery delays, and confidential delivery.

Describe seed and complete tie order directly from frozen matching code, and
export ISS long/late inputs with exact IDs/timestamps, payload, initial states,
constraints, resources, paths and hashes. Clarify that the nine-case 2/5/2 result
uses exact controllers with common prescribed PREPARE.

Keep old110 controller attempts/12 root diagnostics/6 reused cells separate from
these six root searches and conditional controller pairs. Document generation and
postprocessing are not scientific attempts. Preserve old DOCX, TeX, figures,
results and archives; place revision2 artifacts in separate directories.
