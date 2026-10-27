# R1 execution, cost provenance and interpretation

## Unchanged research contract

The five criteria are served contacts, useful secure time, negative spacecraft-plus-ground operation CPU, negative UL+DL management bytes, and negative newly created directional keys, compared lexicographically. CANONICAL and CONTINUE_WAIT resolve only full equality. Neither the exact nor practical mode sees future disclosures, actual outages or unknown outcomes. Within a matched pair only H changes. FixedPeriod requests one pending renewal at last successful SWITCH + T; delayed activation resets the next period, but does not extend a key deadline. PerContact uses a fresh unused pair with explicit just-in-time preparation.

## Exact and practical versions

The candidate exact implementation folds journal aggregates once per immutable snapshot and shares that fold with the existing bound/key construction. It does not change bound formulas, state equivalence, candidate actions or the comparator. Full-DFS differential tests retain the complete canonical plan, not just its score. The fixed-node before/after diagnostic is a separate host probe, not a general speed claim.

`BudgetedHorizonPolicy` is an explicitly uncertified practical mode. It uses the same exact engine within a smaller registered budget. COMPLETE retains its certificate. On local node/time exhaustion a fully evaluated terminal incumbent may supply the first admissible action; otherwise a common deterministic local service/bootstrap fallback is used. Controller cancellation and global limits never authorize fallback.

Practical-v2 may reuse that feasible incumbent's remaining steps after a completed CONTINUE strictly inside the same contact. It requires equality of the full predicted and actual `PlanningObservation`, including costs, IDs, counters, committed work, horizon and payload. The exact next action must be currently generated, including times and IDs. A service step ends this reuse chain. This retains feasibility in the supported nominal continuation; it does not prove the incomplete incumbent optimal. Audit records keep its original INCOMPLETE search and source index. This mode is separate from the pre-existing certified suffix guard.

## Deployment-oriented delay sensitivity

The modeled planner is a logical ground coordinator with a fixed predicted schedule. New actual events become available when observed. PREPARE creates ground material; INSTALL/SWITCH/OPEN/REUSE require the matching actual link. It cannot install A's pair through B under the present authorization model. Local expiry/STOP and observed emergency response use the existing executor.

The reference freezes model time during search. `PlanningLatencyController` instead advances the common physical timeline by `fixedLatency + ceil(hostRuntimeScale * measuredSelectionTime)` at each decision. Existing committed work and actual expiry/contact/incident events continue; it admits no new payload during this wait. When the result becomes available, its intent must match a fresh admissible candidate. The registered delay series uses 50 or 500 ms and a 250 ms deadline, with hostRuntimeScale=0. A missed deadline or stale intent selects WAIT; an expired key is never used. The delay is attached to actual decision points, not subtracted from every contact.

These are synthetic end-to-end decision/dispatch delays. There is no implemented distributed telemetry protocol, remote clock synchronization, queueing network, authenticated plan-command message layout, or calibrated command overhead beyond modeled operations. This closes a zero-time sensitivity gap, not deployment validation. Host elapsed/CPU/GC measurements are reported separately from modeled operation CPU. JVM-wide GC counters cannot identify a search's exclusive pause cause; unavailable fields remain null.

The latency adapter does not populate the legacy computation-summary object. Its zero defaults are marked unavailable by the R1 analyzer (`legacyComputationSummaryAvailable=false`); enclosing measurements and per-search ledgers supply the actual elapsed/CPU values. Two 50 ms Multi runs have raw `SEARCH_INCOMPLETE` status with `CONTROLLER_TIME_LIMIT` in the ledger. They are global-budget stops, not evidence of a local-search limit or zero computation. This reporting limitation remains explicit.

## Parameter provenance

| Parameter | Classification | Scope |
|---|---|---|
| Security operation phase duration, SC/GS CPU, radio occupancy, UL/DL bytes | Synthetic | Original frozen profile remains the control. INSTALL .5x/2x, REUSE 2x, SWITCH 2x vary phase duration only; byte counts are unchanged. |
| Cryptoperiod, material age, reuse gap, FixedPeriod T | Synthetic experiment constraint | Not derived from CCSDS as recommended numeric values. Initial material's explicitly stored notAfter is preserved during sensitivity. |
| Frame size, offered UL/DL demand, service minima, frame duration | Synthetic | Offer and minimum change on separate axes; only whole delivered frames count. |
| Orbit GP and station-coordinate snapshots | Public source data | Appropriate epochs, hashes and acquisition protocols are saved; station visibility does not imply permission to communicate. |
| SDLS/EP/key-management lifecycle concepts | Literature-based abstraction | Not a wire-level standards conformance implementation. |
| AES-256 generation / AES-GCM 1000 B timing | Measured host primitive, if run succeeds | One Java21 JVM, 2000 warmup iterations per mechanism, five 10000-operation samples, unique GCM nonce. Allocation/init included; no JMH forks. |
| Planner wall/thread CPU, heap samples, GC deltas | Measured host | Ryzen 7 5800HS and Java21; elapsed depends on host scheduling. Heap samples are not peak live-memory measurements. |
| Decision latency / actual outages / attack detection times | Synthetic | Explicit registered interventions, identical within a pair; no probabilistic detector or RF jammer model. |

Primitive timings are not substituted for PREPARE/INSTALL handshakes. Online elapsed cost still requires a specified message/channel/ACK model. Synthetic parameters remain explicitly labeled in both models and the article.

## Evidence boundaries

All final service, exposure and recovery values require FINISHED execution. Search completeness is a separate field: a practical run can finish using incomplete searches. A failed exact continuation is not a policy loss. An achieved activation milestone can later expire or lose its SA; readiness intervals are retained. Missing milestones are censored or not applicable, never silently zero. Overlapping waiting conditions are not summed into a latency decomposition.

The new journal replay counts an aborted committed frame's nonce without incrementing delivered bytes or successful-session history. Legacy artifact replay keeps its original accounting. Actual outages interrupt radio work and retain partial charges and uncertain endpoint state; recovery does not silently roll back a remote operation. Reconciliation of uncertain SWITCH/OPEN/REUSE remains an open capability; retrying a safely staged INSTALL is distinct.
