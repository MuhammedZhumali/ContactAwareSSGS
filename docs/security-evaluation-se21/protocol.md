# SE2.1 fixed diagnostic protocol

Fixed before targeted repeats. This is a measurement/cancellation refinement,
not solver optimization. Four policies, threat model, action space, objective,
canonical ordering and physical time semantics remain unchanged. Search does
not advance contact time. Original SE2 files/JAR/ZIPs are immutable inputs.

## Existing-journal postprocessing

All 488 recorded attempts are retained with eligibility/status. Final recovery
quality uses finished runs only; incomplete prefixes are explicitly labeled.
Four distinct milestones from GS notice: confirmed SC notice, joint admissible
independent key activation, open independent SA, first independent delivered
frame. Missing events are censored/unavailable, never zero; readiness already
present at origin is labeled. Track later loss of readiness. No dummy traffic.
Definitions and overlapping conditions are in metrics-spec.md; no additive
causal decomposition is inferred from overlapping waits. Manual checks: original
X Next/Multi and ISS CP5400 p30 Next/Multi, including expiry before C3.

## Targeted computation repeats

Reconstruct the following pre-decision snapshots by executing recorded actions,
without re-running prior searches. Zero-based decision indices:

| Case | Index | Repeats per executable |
|---|---:|---:|
| X-next-none | 1 | 1 |
| X-multi-none | 1 | 1 |
| iss3-cp5400-alt-wait-next-none | 1819 | 2 |
| iss3-cp5400-alt-wait-next-original-undetected | 816 | 2 |
| iss3-cp5400-alt-wait-multi-none | 3 | 2 |
| iss3-cp5400-moderate-multi-none | 3 | 2 |
| iss3-cp5400-saturated-multi-none | 3 | 2 |

Run each with the original frozen SE2 solver and the new instrumented solver:
24 isolated searches total. Same limits: 100000 nodes, 5 s elapsed per search;
same accelerated options and case-specific WAIT model. No automatic increase.
External hard process deadline: 20 s including JVM startup, input loading and
export. Record killed/failed/incomplete attempts, never substitute a final score.
Both variants read the same serialized observation; the legacy harness loads
the old JAR first on its classpath. Completed results compare all five scores
and full canonical plans. A timeout cannot prove they differ.

Additionally execute four current full X controllers (Next/Multi, none/detected
at original SE1 point), each with 60 s cumulative controller limit and 90 s
external process limit. Compare physical five metrics and executed actions with
saved SE2. Execute one current ISS CP5400 alternative-WAIT Next/noattack controller
with 10 s cumulative limit and 30 s process limit, to exercise bounded aggregate
work; no full-mission outcome is expected or substituted if stopped.

A separate injected blocking process probe is killed after 2 s; it must have
published a restorable pre-search checkpoint first. This is a watchdog test,
not a measured natural solver stall. Unit tests use controlled clocks/checkpoint
injections for deterministic cancellation in initialization, bound generation,
candidate generation and execution, and test interrupted search/cache safety.

## Measurements, provenance and interpretation

One child JVM at a time, Java 21, -Xmx1024m. No concurrent heavy builds, audits
or rendering during timed repeats. Save raw elapsed, current search-thread CPU
if supported, nodes/decisions, phase timing, GC MXBean deltas, GC/safepoint logs
and JFR where available. Search is synchronous; search-thread CPU does not
include GC/compiler/JVM service threads. GC aggregates are JVM-wide and do not
prove causation. Missing instrumentation is explicitly unavailable. External
wall time includes startup/export; search elapsed does not.

Controller checkpoint publication is outside search elapsed but inside its
cumulative budget; publication overhead is not a spacecraft/GS security cost.
Write checkpoints atomically before a decision and after committed execution.
If cancellation interrupts a transition, keep the preceding immutable snapshot;
do not manufacture operation-abort or simulation-end events. A killed process
can lose the current uncommitted search, not the last durable checkpoint.

Before repeats, a separate machine manifest binds this protocol, source cases,
snapshot/observation hashes, old/new executable hashes, budgets and environment.
No adaptive selection based on results. Targeted repeats characterize only these
decisions. If 42/3361 s delays do not recur, their original causes remain unknown.
Full Maven and focused Python tests, frozen-input hashes and export QA are checked
after integration. Outputs live under target/security-evaluation-se21 and
docs/security-evaluation-se21, never the SE2 directories.
