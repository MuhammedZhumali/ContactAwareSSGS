# PC2: contract and scope

The model, finite candidate generator, exact DFS, five objective components, and both full-plan tie-breaks are unchanged. PC2 adds a portfolio around the existing bounded search. A heuristic determines only which feasible continuation is proposed; it does not remove legal search branches or add a reward.

## Common root and selection

For actual permitted observation `o` and endpoint `e=o.evaluationEnd`, construct only nominal branches restored from `o`. An eligible candidate must reach `e` and pass the common continuation evaluator. Its vector is

`J_o(p) = (newly served contacts, additional useful time, -additional CPU, -additional management bytes, -additional directional keys)`.

Each difference is relative to `o`, including partial committed costs via `accruedCosts`. Already served contacts, completed PREPARE, and past traffic are sunk progress; they are not counted again. Candidate evaluation uses actual delivered frames and both directional service thresholds.

Compare eligible seed, replayed retained plan, and terminal search incumbent using `PlanningResultComparator`. The winning full plan is the minimum under that comparator (maximum five-component objective, then the configured complete canonical plan order). When the **entire plan and all five values** are identical, SEARCH may supply metadata/certificate for that same plan. There is no objective tie preference for retention or Multi.

For each completed selection with at least one eligible candidate, the returned candidate cannot rank below any eligible candidate considered on that same root. This is a local invariant, independently checked on every exported portfolio decision. It does not imply global optimality, mission-wise monotonicity with horizon, or full service. Cancellation/deadline rejection yields no authorized portfolio action and is logged separately. A local SAFE_FALLBACK is explicitly not a fully evaluated continuation.

## Seed

`FeasibleSeedPlanBuilder` follows a deterministic service chain through generated actions and `BranchEngine`. It continues usable traffic, opens/reuses material, or progresses SWITCH/INSTALL/PREPARE. It stops generating service after the finite offered demand is exhausted. In gaps it can prepare an unkeyed nearest demand context at the existing JIT boundary; otherwise it waits for the earliest legal event. An optimistic chain-fit check affects only this one proposed rollout, never the generator/search space.

The builder settles committed frames and operations by WAIT, does not treat preparation as confirmation, cannot install A through B, and cannot reset material clocks, counters, or IDs. It uses an isolated nominal branch and never mutates actual execution. Only a complete admissible rollout receives a score. The deterministic transition cap is 10,000 attempted transitions and the stage wall cap is 350 ms, inside the common decision budget.

## Retention through operations and frames

After selection, predict exactly the first generated transition. Store its observable child and the remaining plan, not its old score. At the next policy call compare the actual observation after event processing against that expected child. Check exact time, H, model, current contact, all security state/counters/revocation facts, pending work, reservations, uncertainty, in-flight frame, costs, progress and issued IDs. Every still-visible old contact must remain an identical prefix. Conservative rejection is allowed; no hidden input is consulted.

If the receipt matches, restore a new branch from the **actual** observation and replay each remaining action through the generator and executor. Preserve its original timestamps and key IDs. Invalid/stale actions reject the candidate, without silently moving a service action. If the horizon grew, replay to the old endpoint and extend using the same seed procedure until the new endpoint. The replay has a 350 ms stage cap. A failed/partial extension has no competing score. Shrinking or otherwise changed forecasts are rejected conservatively.

Known expiry at a frame boundary is included in the predicted processed state; it need not invalidate a matching continuation. Unexpected expiry, counters, outage, notice, or ACK uncertainty are mismatches. Already started work is preserved by ordinary snapshot restoration. No fictional reconciliation is performed.

The result is `FEASIBLE_RETAINED`, not an optimality certificate. `CERTIFIED_SUFFIX` remains the existing strict proof path for a COMPLETE unrestricted exact plan and its original guard. A certified path can bypass the portfolio; the next portfolio call may conservatively reject its now-stale internal receipt. Retained practical state is invocation-local, not persisted in checkpoint. Checkpoint/resume restores physical state exactly and safely rebuilds a seed; it does not claim warm-cache identity.

## Time, observability and measured execution

One search still has 5,000 nodes / 1 s. Seed, retained replay/extension, prediction, search and selection run under the same 2 s decision scope, with stage caps reserving time for search and admission. Whole-run watchdog is 600 s; the cumulative controller cap is disabled. New seed/replay/prediction transitions are reported separately, without redefining search nodes. Attempted transitions interrupted before completion remain visible in stop diagnostics. Normal actual execution and checkpoint I/O are outside the optimization deadline.

No rollout is performed after expiration to manufacture a result. Parent cancellation/controller interruption propagates and cannot be converted to a feasible answer. Cooperative scopes do not bound an OS pause/native block; the external watchdog remains independent.

In measured-host-delay mode (fixed 0, scale 1), measured observation/generation/portfolio work advances physical time before first-action admission. The ordinary fresh candidate check can reject or admit the same intent at the new instant. WAIT is bound to the earlier of its requested target and the original observation's first wakeup. If that event has already occurred, candidate-v4 requests a new decision (`WAIT_EVENT_REACHED_REPLAN`); it cannot substitute a different WAIT until contact end. If the effective wake changed or disappeared, it requests `WAIT_EVENT_CHANGED_REPLAN` instead. The latter is covered by a controlled interruption unit test. A service intent no longer present among fresh candidates likewise triggers `STALE_INTENT_REPLAN` after actual time has advanced. Both additional decisions are measured and charged. Zero modeled latency retains ordinary wakeup execution. This narrow correction follows the preserved negative candidate-v2 measured series; both protocols and versions are retained.

The old complete plan is not shifted: its receipt generally becomes stale and the next decision recomputes. No old-root score authorizes a stale execution. Controls use their own measured observation/generation/rule-selection delay, not an artificial one-second search cost. A deadline miss still discards the result and uses ordinary safe waiting, rather than doing free rollout after the deadline.

Model operation CPU is separate from host time. Portfolio and exact search have thread-CPU measurements when available; outer observation/admission/rule CPU is not separately instrumented. Whole-run telemetry also contains actual execution and export overhead and must not be mislabeled as optimization-only CPU. Wall time of the measured planning pipeline excludes checkpoint I/O, actual execution and final admission; this remains a host sensitivity model, not an onboard benchmark.
