# Controlled channel availability and aborted-frame nonce accounting

Implementation checkpoint: 2026-10-25. This document specifies the new model and tests; it does not report executed experiment results. The implementing subtask ran no JVM or Maven build. Integrated compilation/test execution belongs to the main task.

## Input and compatibility

`SimulationScenario.channelInterruptions` is an optional immutable list of `ChannelInterruption(contactId, from, until)`. Each interval is half-open `[from, until)`, has positive duration, names a forecast contact, and stays within that contact's original `[AOS, LOS]` bounds. Overlapping intervals for one contact are rejected; adjacent intervals are allowed. Lists are sorted deterministically. Existing eight- and nine-argument scenario constructors remain available. A missing/null JSON list means empty; an empty list is omitted during JSON serialization.

The forecast `contacts` list and its windows are never modified. Examples:

| Actual condition | Interruption inside original `[AOS, LOS)` |
| --- | --- |
| Missed contact | `[AOS, LOS)` |
| Delayed availability | `[AOS, actualAOS)` |
| Early loss | `[actualLOS, LOS)` |
| Temporary outage | `[down, up)` |

An empty interruption list adds no channel events and preserves existing no-abort execution behavior. The separately documented nonce correction below changes executions that abort frames even if their interruption list is empty. Historical journals, reports, and checkpoints are not rewritten.

## Factual events and admission

Only real execution initializes `CHANNEL_DOWN` and `CHANNEL_UP` queue entries. Their timestamps are privileged scenario/queue data. `CHANNEL_DOWN` records the observed loss, aborts a committed frame for that contact, interrupts an online primitive with existing partial-cost/failure semantics, and performs a local STOP if its context is operational. Ground-only PREPARE remains available; a PREPARE primitive already in a compound action can finish, but any following online step is blocked while down. A new online compound request is rejected while down.

INSTALL interrupted before its confirmation does not become installed. Its executed work remains charged, and an explicit retry may proceed after restoration. SWITCH, OPEN, and REUSE interruption retain the existing conservative uncertainty rule. `CHANNEL_UP` records availability only: it does not confirm work, clear uncertainty, resurrect keys, restart payload, or reconcile endpoints. A policy must explicitly reopen a valid, certain context. Reconciliation of uncertain peer state remains unimplemented.

The runtime reconstructs current availability from factual journal events when restoring a checkpoint. There is no new snapshot availability field. Serialized queue events retain future restoration boundaries. A controlled `manualAdvance` stops at actual DOWN/UP events even when the requested WAIT extends farther; its existing next-event selection includes the new event kinds. A restored channel does not authorize a link outside its original forecast window.

## Equal-time order

The event priorities are: complete frame (0), hidden disclosure (5), key deadline (10), ground notice (15), forecast contact end (20), channel up (21), channel down (22), explicit operation interruption (25), forecast contact start (30), step confirmation (40), requested action start (50).

- A complete frame ending exactly at loss is delivered under the existing complete-frame boundary convention; no next frame starts before all events at that instant settle.
- Expiry/LOS still precede acknowledgements. Channel loss also precedes an ACK at the same timestamp, so that online step is not confirmed.
- UP before DOWN at a shared boundary keeps adjacent outages unavailable after the full timestamp settles.
- DOWN at AOS is processed before online admission; UP at LOS does not create a service opportunity after closure.
- Aborting removes only the queued completion matching that frame's contact and original completion instant. Unexpected unmatched frame completions still fail; no blanket stale-frame suppression was introduced.

## Planner information boundary

`PlanningObservation.from` first computes the original H-limited forecast prefix: current plus H additional contacts when geometrically inside a contact, or H nearest contacts in a gap. Only then does it remove the current contact if a factual past DOWN lacks a subsequent factual UP. It does not replace that contact with a farther contact. For H=0, removal can leave an empty visible set with evaluation end equal to the current instant.

The observation contains neither the interruption list nor restoration times. Future windows retain their nominal forecast. Branch scenarios contain only the projected contacts and no interruption events, so a currently unavailable contact remains conservatively absent in that branch. Actual UP wakes the executor and a fresh observation can include the current contact again. This is a conservative response to already observed loss, not prediction of the outage duration. It does not model late notice, asymmetric channel knowledge, fading, or probabilistic detection.

## Separate correction: consume a nonce for an aborted committed frame

The pre-existing abort path recorded emitted ciphertext without consuming the directional key's counter. Reopening the same key after a temporary outage would therefore allow the counter assigned to that committed transmission to be reused. `LifecycleTransitions.recordAbortedTransmission(state, keyId, at)` now advances that key's counter exactly once and updates a still-bound SA's mirrored counter. It preserves key status, binding, successful session-frame counts, delivered bytes, useful time, and successful-use history.

The transition records an already admitted transmission; it is not permission to send with a retired key. A downlink already in flight when ground revocation is received can be locally rejected and finish emitting later. Its retired key's counter is consumed without reactivation or rebinding. Even a zero-byte prefix conservatively spends the committed nonce. `PAYLOAD_ABORTED` continues to have zero delivered frames/bytes/useful time, with emitted bytes in `frameEvidence`.

Consequently, counter audits for new executions must use:

`final key counter = initial counter + delivered frames under that key + aborted committed frames under that key`.

Aborted frames do not refresh reuse history. This change applies to channel-loss and existing revocation abort paths. It does not introduce actual encryption, replay processing, or a distributed nonce-reservation protocol. Replaying a legacy executable's evidence is still a historical calculation; new runs/checkpoints must identify the updated executable. Reading an older checkpoint does not retroactively repair old counter records.

## Verification cases added

`ChannelInterruptionTest` covers input validation and missing-list JSON; nominal empty-list behavior; byte-identical observations before loss and during losses with different hidden restoration times; original-prefix filtering and branch noninterference; controlled WAIT interruption; partial ciphertext/counter retention and reopening with the same key; partial INSTALL cost/retry; fail-closed SWITCH/OPEN/REUSE; PREPARE during outage; missed/delayed/early contact availability; frame/ACK/expiry boundaries; adjacent outages; checkpoint round trips before/during/after loss; retired downlink accounting; and zero-byte conservative nonce use.

These tests were authored but not run by this subtask. No new forecast-robustness, onboard deadline, or cryptographic-strength result follows from this implementation alone.
