# R1: narrow exact-search diagnosis and bounded-mode boundary

Date: 2026-10-25. Baseline source HEAD: `3621333d8093b4cb0c232c7db9164c66e273053c`.
Scope: the exact planner, its bound/key/traversal/cache, controller selection and continuation guard,
and the saved SE2.5 high-demand runs. This is not a new literature review or a full repository audit.

## Evidence and its limits

The saved series registration is
`target/security-evaluation-remaining-demand/registration.json`: eight attempts, no retries,
2,000,000 nodes / 30 s per search, 60 s controller budget, 90 s external watchdog,
`SERVICE_FIRST`, continuation reuse on, `V2_EVENT_BOUNDARIES`. Its sourceIdentity explicitly says
the unchanged SE2.4 JAR was reused; its build commit is
`67870e66f043ce63f8370f1333b564184a6793c1`. The series base commit is `3621333...`.
These are historical measurements, not measurements of the R1 change below.

Read the last `INCOMPLETE` entry of each run's `actual.decisions.json`, not the controller's
top-level phase counters (the latter are all zero and their whole time is unattributed):

| Multi case | Last search nodes | Expanded | Memo hits / entries | Bound-pruned / tie-break-pruned | Last search seconds |
|---|---:|---:|---:|---:|---:|
| none / CANONICAL | 300,972 | 142,307 | 11,131 / 1,579 | 145,955 / 356 | 30.000427 |
| late-detected / CANONICAL | 298,612 | 141,385 | 11,084 / 1,577 | 144,566 / 356 | 30.000409 |
| none / CONTINUE_WAIT | 263,321 | 165,506 | 15,674 / 6,447 | 75,693 / 1,389 | 30.000464 |
| late-detected / CONTINUE_WAIT | 261,820 | 164,526 | 15,517 / 6,412 | 75,364 / 1,382 | 30.000626 |

All four stop with `TIME_LIMIT` at `2026-10-12T00:08:37.296295166Z`, C1 AOS. They have no final
simulation/security metrics. All four Next attempts finish in 2.000–3.691 controller seconds,
with 32–39 searches and 39–41 proven suffix reuses. Multi executes 2 or 4 searches and zero
suffix reuses before the stop. `final-checks.json` records equal stopped observations for the
none/detected pair within each rule; it does not establish equality between the two rules.

Disjoint phase measurements for the two no-attack stopped searches:

| Phase | CANONICAL seconds (% of search) | CONTINUE_WAIT seconds (% of search) |
|---|---:|---:|
| bound | 8.012 (26.7%) | 8.868 (29.6%) |
| execution | 7.334 (24.4%) | 7.100 (23.7%) |
| generation | 6.350 (21.2%) | 6.619 (22.1%) |
| stateKey | 4.388 (14.6%) | 3.755 (12.5%) |
| candidateProcessing | 2.061 (6.9%) | 2.133 (7.1%) |
| completion + evaluation | 0.175 (0.6%) | 0.214 (0.7%) |
| unattributed | 1.679 (5.6%) | 1.310 (4.4%) |

Thread CPU is 29.141 / 29.109 s. JVM-wide GC collection-time deltas are 220 / 283 ms;
they are not exclusive allocation or pause profiles. The phase data support CPU work spread
over bound/execution/generation/key construction, not a GC- or checkpoint-I/O-dominated diagnosis.
No allocation profiler or method-level sampling was run for this audit.

The historical isolated probe in `docs/reviews/research-flow-audit-checks/` supplies a second,
strictly limited observation. `logs/demand-multi-canonical.log` records COMPLETE at 1,061,822
nodes / 63.0 s; the score is `(3, 410 s, 1.73 s, 3240 bytes, 2 keys)`, first action INSTALL.
`logs/demand-multi-cw.log` records INCOMPLETE at 6,000,000 nodes / 418.8 s, incumbent
`(3, 410 s, 2.13 s, 3240 bytes, 6 keys)`, first action PREPARE. `DemandRootProbe.java` solves
only one reconstructed root and has no elapsed limit; it does not run the controller.
The logs print security actions, not the entire action sequence. They cannot independently
prove complete-plan equality. The registered CANONICAL and CONTINUE_WAIT trajectories have
different pre-AOS work (four created keys versus zero), so these score increments must not
be compared as an isolated effect of changing the tie-break on one identical root.

## Concrete causes supported by code and counters

1. **The difficult decision is a newly enlarged frozen horizon.** At AOS the observation changes
   from two visible contacts to three and its evaluation end extends to
   `2026-10-12T01:49:19.758453369Z`. `ContinuationReuseGuard.checkStep` rejects
   `FORECAST_CHANGED` before attempting a one-frame replay. The preceding action was WAIT
   or setup outside the current contact, not a supported one-frame CONTINUE. Broadening the
   guard would require a new optimality argument; it cannot supply a proven solution here.
2. **Tree work remains large after useful exact reductions.** The two no-attack searches generate
   868,308 / 789,729 actions and execute 301,007 / 263,365 transitions; maximum depth is 71 / 75.
   Pre-execution WAIT coalescing already skips 567,268 / 526,274 actions. Repeated sibling
   transitions and post-execution WAIT collapses are zero. Further WAIT deduplication is not
   the supported primary remedy.
3. **Cache capacity is not the bottleneck shown by these runs.** Memo entries are far below the
   50,000 cap and `memoStoreSkips=0`. `finish` correctly publishes only fully solved local
   suffixes; global-bound pruning marks the node and ancestors non-cacheable. Most subtrees
   are pruned using the global incumbent, so a low exact-cache entry count is expected.
   Increasing the cap does not address the recorded stop. The state key deliberately retains
   history, identifiers, event ordering, progress and costs; removing them is not a safe shortcut.
4. **Repeated journal folds are an observable implementation cost, not yet a measured method
   bottleneck.** Before R1, every new key invokes `recordedCosts`, `usefulSecureTime`,
   `createdKeys` and a rejection scan. Each non-pruned bound invokes accrued/created/useful
   folds again, sometimes useful time three times, then scans journal events for per-context
   online work, preparation work and created keys. This is concrete redundant work inside
   the measured bound/stateKey phases. Their combined 41–42% share is an upper limit on
   the affected phase opportunity, not the fraction removable by the change.
5. **Traversal and final ordering are separate.** `SERVICE_FIRST` is already used; the final
   comparator still orders all five metrics and then the entire plan under the selected rule.
   `CONTINUE_WAIT` may leave a much larger set of prefixes competitive. The old probes do
   not show that any different traversal will finish this case, and this patch changes none.
6. **No general cross-decision memo exists.** `plan` creates a fresh `Search`; the only cross-decision
   reuse is the guarded COMPLETE continuation. This matters later in a controller trajectory,
   but it is not the cause of the initial C1 search failing before a reusable proof exists.

## R1 implementation: exact journal summary reuse

Authorized scope: `ExactHorizonPlanner`, `PlanningOptimisticBound`, `PlanningStateKey`, one new
package-private helper and one test class. No controller, observation, engine, comparator,
candidate generator, planner options, bound formula or cache-eligibility change.

`PlanningJournalSummary` computes in one ordered pass over one immutable snapshot:

- carried recorded usage plus all journal usage;
- carried useful duration plus journal useful durations;
- carried created-key total plus all journal created-key values;
- the existing ACTION_FAILED/PAYLOAD_REJECTED disjunction;
- journal-only per-context online usage, PREPARE usage and positive created-key counts.

An exact search constructs the summary once for each executed candidate (and once for its root),
uses it to construct the unchanged state key and retains it with the DFS visit for the bound.
It is not stored in memoized solutions or shared across searches. Its `source` identity check
rejects accidental use with another snapshot. Per-context maps are immutable copies; they
retain null-context behavior of the old HashMap folds for diagnostic events.

Correctness argument: all reductions retain the same initial values, predicates, journal order,
`ResourceUsage.plus`, `Duration.plus` and `Math.addExact`. Pending costs remain separate and
are integrated from the original step start using the unchanged operation cost. The bound's
per-context residual accounting is unchanged; it begins from the derived journal maps and
adds pending usage exactly as before. No key field is deleted or normalized. The five-metric
evaluation still uses the unchanged independent `ContinuationEvaluator`; complete-plan comparison,
strict prefix pruning, final status, fixed-first-action scope and certification are unchanged.

This lowers repeated work per state; it does **not** reduce the feasible tree or establish that
the hard case will finish. Small trees may pay more for the per-context summaries than they save.
Measure before making a speed claim, and keep a regression if the representative case slows down.

`PlanningJournalSummaryTest` adds three checks:

1. Traverse a bounded small full tree and compare each summary/key with the original snapshot
   folds, covering pending operations, payload and created material.
2. Check partial preparation, carried totals, both rejection types, null diagnostic contexts,
   map immutability and rejection of a summary for another snapshot.
3. Compare unoptimized full DFS against accelerated search for both full-plan rules, normal
   and reversed traversal, comparing all five metrics, first action and every plan action;
   replay the entire returned plan with the original engine/evaluator.

Existing suites required at integration: `PlanningStateKeyTest`, `PlannerAccelerationTest`,
`PlanningOptimisticBoundTest`, `PlanningPreparationBoundTest`, `PlanningFrameCapacityBoundTest`,
`ExactHorizonPlannerTest`, plus controller budget/checkpoint and continuation-reuse tests.
The main agent owns builds and measurements. This subtask ran no JVM and no Maven command;
at its code checkpoint compilation/tests were pending. Scoped `git diff --check` passed.

For the bounded before/after measurement, use the same frozen checkpoint, H, timing, rule,
traversal, limits, JVM flags and uncontended machine conditions. Compare completed scores
and full plans where COMPLETE is attained. For node-limited probes, identical deterministic
node work should retain the same incumbent and traversal counters; elapsed-limited runs may
visit different node counts. Record wall/thread CPU, phases, nodes, incumbent and stop reason.
Do not call an equal incomplete incumbent proof of exact optimality. A full-controller success
requires a separate actually finished controller trajectory.

## Minimal explicit bounded/anytime route if exact remains unsuitable

This section is a proposal, not an implementation. Keep the existing exact mode fail-closed:
`HorizonPlanner.selectUnrestricted` withholds an action when search is INCOMPLETE, and
`SecurityPolicyController` stops on any non-COMPLETE search. Do not silently change that contract.

Introduce an explicit solver execution mode, shared by Next and Multi, and a bounded policy adapter:

1. Use the same exact search, traversal, cost/constraint model, five-metric comparator and selected
   full-plan tie-break. Give both policies the same per-decision node/time limits and controller
   limit. A bounded incumbent is the best terminal feasible continuation visited; its first action
   can be executed only for the same observation and only if present in the current candidates.
   Keep its search status INCOMPLETE and its stop reason. Do not give it a COMPLETE certificate.
2. Add explicit decision provenance such as `BOUNDED_INCUMBENT` and `SAFE_FALLBACK`, with
   attempted-search diagnostics retained even when a fallback action differs from the incumbent.
   This needs deliberate changes in `PolicySelection`, `ControlledSimulationResult.Decision`
   invariants and trace serialization: today they require the executed action to equal the search
   first action and tie a search result exclusively to `DecisionSource.SEARCH`. Hiding an attempted
   search inside a RULE reason would lose material evidence.
3. After local `NODE_LIMIT`/`TIME_LIMIT`, the adapter may use an incumbent or fallback. A parent
   `CONTROLLER_TIME_LIMIT`, cancellation or interruption must still terminate the controller;
   do not catch those and continue beyond the run budget. Reserve a documented common local
   decision allowance for selection/dispatch, and charge fallback computation to the controller.
4. If no incumbent exists, use one deterministic work-preserving fallback over the common
   admissible candidates. Preserve committed frames/operations by WAIT to the earliest candidate
   boundary; do not restart, cancel or duplicate them. With no committed work, use admissible
   CONTINUE for current demand; otherwise OPEN/REUSE with valid material, then the next needed
   INSTALL/SWITCH step for already prepared current-context material, then current-context
   PREPARE if needed. Choose within each applicable group by the shared action comparator;
   if service/setup is unavailable or demand is exhausted, choose earliest WAIT with canonical
   tie resolution. Off-contact fallback may simply WAIT. This is safe model execution, not an
   optimality or universal eventual-service guarantee. Mandatory observed incident response
   retains controller precedence. The fallback cannot access hidden future attacks/contacts.
5. Replan at the next actual decision. Do not feed a bounded continuation into
   `ContinuationReuseGuard` or relabel it as an exact suffix. Any heuristic incumbent seeding in
   a later optimization must be charged to the same common budget and compared by the existing
   full comparator; it is outside R1.

Required bounded-mode tests: exact mode still stops unchanged; a terminal incumbent under a local
limit executes only its admissible first action while retaining INCOMPLETE; no-incumbent fallback
preserves in-flight/ongoing work; unavailable/expired/uncertain material never emits protected
traffic; incident response wins; no zero-time retry cycle; global timeout/cancel/interrupt still
stops; identical observations/options produce identical fallback choices regardless of policy label;
same registered limits for Next/Multi; traces distinguish exact, bounded and fallback choices;
no incomplete certificate is accepted by suffix reuse.

Unsupported claims after R1 alone: solved high-demand Multi controller, complete CONTINUE_WAIT
optimum, equivalence of two different registered roots, universal sufficiency of journal caching,
optimality of fallback, safe removal/renaming of state-key fields, cross-horizon suffix reuse,
or any policy advantage inferred from an incomplete run. Report such cases as incomplete or
separately labeled bounded-policy outcomes, never as exact-policy losses or exact optima.
