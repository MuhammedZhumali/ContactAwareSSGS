# SE2.2.1 — bounded search review

Scope: `PlannerOptions`, existing `ExactHorizonPlanner` traversal/pruning/memo,
and four new finite-oracle test invocations. No objective, comparator, bound, generator,
candidate filtering or physical model changes. No JVM or Maven runs by this
subagent; root owns the single-heavy-process schedule and records actual outcomes.

## Representative input

Root reconstructed decision index 1, t=0, for all five stopped Multi
CONTINUE_WAIT X controllers. Their complete PlanningObservation JSON trees are
identical; evidence: `target/security-evaluation-se221/observations/equivalence.json`
and the five observation files. Thus no attack-dependent forecast is introduced.
The representative is `X-multi-none-continue_wait`; prior controller decisions
were replayed without searching their histories.

## Confirmed old work and code mechanism

The saved representative decision in SE2.2 visited 264414 nodes before TIME_LIMIT
at 30.0005115 s: 84164 expansions, 630473 generated actions, 264536 executed
transitions, 255 terminal plans, maximum depth 164. There were 5592 memo hits
but only 374 memo entries, no capacity-store skips. 174402 nodes were bound-pruned,
including 14424 canonical-prefix prunes. These observations do not indicate
cache capacity exhaustion. They also do not identify a Windows pause or explain
the unrelated earlier 3361-second interval.

`renewalRank` visits INSTALL first and visible PREPARE before CONTINUE. This
quickly finds a useful early-renewal plan, but CONTINUE_WAIT prefers later
management when all five metrics tie. A branch beginning with CONTINUE is then
lexicographically better than an early-management incumbent: the existing
equal-bound prefix proof cannot discard it. DFS can repeatedly find more
deferred equal-score continuations. This is a code-level explanation for the
ordering conflict, not a claim that every visited node comes from that cause.

Memo intentionally cannot solve this by caching incumbent-pruned subtrees.
`finish` propagates `cacheable=false` to parents after a bound prune. Such a
subtree suffices for global pruning but is not an independently solved optimal
suffix. Relaxing that rule would risk changing the exact answer; SE2.2.1 does
not do so. Major measured phases and the before/after representative profile
are recorded by root; no broad solver tuning was performed here.

## Limited option

`PlannerOptions.acceleratedServiceFirst()` preserves every accelerated flag and
the 50000-entry cache cap, selecting existing SERVICE_FIRST traversal only:
CONTINUE, SWITCH, INSTALL, OPEN, REUSE, WAIT, PREPARE, with the original canonical
action tuple as secondary traversal ordering. Existing defaults stay unchanged.
The same option applies to Next/Multi and both final tie-break rules. It is a
fixed traversal heuristic, not a change to the CONTINUE_WAIT final comparator.
In particular WAIT remains after service actions; the new order is not claimed
to be a globally ideal exploration order or to guarantee completion.

Correctness rationale: `ordered` still validates an exact permutation of every
generated action. All finite continuations remain available. WAIT representatives,
metric comparisons, equal-bound prefix proof and memo suffix winners still use
the selected final comparator. The prefix proof is valid for any incumbent and
the exact cache is published only for fully solved subtrees. Hence only the order
in which legal work/incumbents are encountered changes; upon COMPLETE, the five
metrics and full selected plan must be identical. INCOMPLETE remains explicitly
incomplete; a different incumbent under a budget is not an equivalence proof.

## Finite checks supplied to root

`Se221PlannerTraversalTest` contains two completed nontrivial fixtures under each rule
(four test invocations):

1. Fresh material must follow PREPARE → INSTALL → SWITCH → OPEN and deliver both
   directions. The independently expected score is (1, 2 s, 4 s CPU, 0 B, 2 keys).
2. Prepared material yields a genuine INSTALL/CONTINUE tie: (2, 4 s, 3 s CPU,
   0 B, 0 keys). CANONICAL picks INSTALL; CONTINUE_WAIT picks CONTINUE.

For each fixture the complete unaccelerated DFS is the reference, with a
250000-node guard and no wall-clock guard; COMPLETE is asserted. Both old/new
accelerated options are checked with ordinary and reversed traversal, comparing
the complete action list, first action and all five metrics. Every result is
replayed to the same horizon end and rescored. No performance ratio or wall-time
assertion is imposed. Root must record the actual test result before using the
option in the five newly registered controllers.

## Unsuccessful oracle calibration, retained explicitly

A third proposed fixture combined cheap one-second PREPARE, a live old pair,
and a return after its expiry. With C1=[0,6], C2=[80,84], cryptoperiod150,
the unaccelerated reference hit its 250000-node guard under both rules. A single
bounded simplification used C2=[8,12], cryptoperiod87, making the historical
activation at -80 expire at7; this reference also hit the same guard for both
rules. Evidence is retained in `target/security-evaluation-se221/focused-tests.log`
and `target/security-evaluation-se221/traversal-tests.log`.

These are failed attempts to calibrate a sufficiently small exhaustive oracle,
not five-metric/full-plan correctness disagreements: execution stopped at the
reference COMPLETE assertion before comparing accelerated answers. Budgets were
not raised, reference incompleteness was not accepted as success, and no further
fixture tuning was attempted. The too-large proposed test was removed from the
unit suite; the two nontrivial fixtures above completed all four invocations in
both focused runs. Existing expiry/lifecycle tests remain unchanged. Root's
final test run records the reduced suite separately from both retained failures.
