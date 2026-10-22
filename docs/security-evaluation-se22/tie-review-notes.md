# Bounded SE2.2 comparator/cache review

Read-only review of the SE2.1 implementation before the SE2.2 patch. No build,
experiment, source-code change, or nested agent was run for this check.

## Required propagation

- `PlanningResultComparator.java:15–27`: keep the five-metric comparison unchanged;
  select the plan order only after `compareMetrics == 0`.
- `PlanningResultComparator.java:44–60,73–83`: the audited alternative must match
  `TieBreakSensitivity.java:53–54`: group CONTINUE=0, WAIT=1, all service=2;
  then `until`; then the original canonical action tuple. The final tuple makes
  the audit's stable sort over canonical generator output explicit. This is
  broader than changing kind ranks: among service actions an earlier `until`
  precedes original kind order. Preserve the proper-prefix convention.
- `ExactHorizonPlanner.java:227,242`: both global best and parent best need the
  selected comparator. Parent best determines the cached canonical suffix.
- `PlanningActionReduction.java:17–25,37–44`: currently both helpers instantiate
  the old comparator internally; inject the selected comparator. Prefix pruning
  remains legal only for an equal optimistic metric bound and a strictly worse
  first differing action. A proper prefix is still inconclusive.
- `ExactHorizonPlanner.java:135,164,173`: pass the selected rule to prefix pruning,
  pre-execution WAIT coalescing, and executed equivalent-WAIT representative
  selection. WAIT-only ranks happen to be unchanged under these two rules, but
  explicit propagation prevents a misleading mixed-rule implementation.
- `ExactHorizonPlanner.java:265–275`: use a separate fixed traversal comparator
  for SERVICE_FIRST/RENEWAL_FIRST secondary ordering and permutation validation.
  `SecurityActionGenerator.java:59` may retain its original canonical output
  sorting. This keeps traversal common rather than silently changing it with
  the final preference.

## Cache obligation

`memo` is instantiated inside each Search (`ExactHorizonPlanner.java:63`), not
shared between invocations. Thus a fixed rule per Search is sufficient; the
rule need not be inserted in `PlanningStateKey`. Its equivalence remains the
same. If caching ever spans searches, include rule and root/horizon identity.

At lookup (`106–111`), preserve the current prefix and append only the cached
suffix. At storage (`228–241`), retain the existing complete-subtree requirement
and propagation of `cacheable=false` after incumbent-based pruning. Lexicographic
suffix order is independent of a fixed prefix. The selected comparator for
parent best is therefore sufficient to choose the correct reusable suffix.

## Fixed-first-action evaluation

Prefer a diagnostic planner entrypoint that restricts **only the original root**
to one exact generated action. Validate membership in the unfiltered root list,
then filter before WAIT reductions, so a forced noncanonical WAIT is not removed
in favor of another equivalent WAIT. Descendants retain the ordinary complete
candidate space and chosen rule. Keep the original PlanningObservation,
evaluationEnd, baseline accrued costs, payload progress, and PlanningBranch.origin.
The returned plan must include the forced action and its score must include its
actual costs and useful progress.

Do not execute the first action and call ordinary `plan(child.observation())`:
that changes the score origin, can drop a just-finished contact, and can change
the served-contact increment. The accounting contract is explicit in
`ContinuationEvaluator.java:30–42`; `PlanningBranch.java:11–19,24–32` preserves
the forecast and carried totals.

An action is proven tied-optimal only if its COMPLETE score equals a proven
global optimum. An INCOMPLETE first-action search is unknown, even if its
incumbent matches. The full set is complete only when all admissible root actions
are resolved (COMPLETE or INFEASIBLE); list unresolved actions separately. This
diagnostic belongs at selected snapshots, not each controller decision.

## Focused checks recommended

1. For both rules, each of the five strict metric advantages overrides a
   contrary action preference; equal metrics select the documented action rank.
2. Reference DFS equals each acceleration and all combined under both rules,
   including reverse candidate traversal, full plan and five metrics. Add a
   service/service tie where the earlier `until` reverses canonical kind order,
   to distinguish the actual audited rule from a kind-only approximation.
3. Reversed-prefix cache fixture analogous to
   `PlannerAccelerationTest.memoSuffixFromReusePrefixCannotReplaceTheCanonicalOpenPrefix`,
   and WAIT representative tests under both rules.
4. Equal-bound prefix pruning in both directions for PREPARE/CONTINUE and
   PREPARE/WAIT; proper-prefix cases cannot prune.
5. In a small complete tree, exhaustive fixed-first results recover the ordinary
   optimum, all optimal first actions, and exactly include the forced action.
   Include a frame reaching the current contact service threshold and a pending
   operation to guard against changed score origin/double accounting.
6. Existing cancellation/cache-safety tests remain applicable; interruption does
   not prove a first action infeasible or a tie set complete.
