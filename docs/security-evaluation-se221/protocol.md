# SE2.2.1 limited protocol

Written before new controller attempts. Preserve SE2/SE2.1/SE2.2/audit artifacts
with SHA-256 and do not replace their results. Baseline commit is
4a80b9bbd103e262b54d34f7ead1f47a4678f025. Freeze new source/JAR identities in each
registration. No change of threats, incidents, reaction, actions, feasibility,
five objectives, tie-break rules, or modeled time.

## A: checkpoint repair

Commit checkpoint implementation separately. Compile/test in an isolated source
directory; stage A JAR uses the checkpoint repair with the original RENEWAL_FIRST
traversal. Register exactly the seven PROCESS_ERROR names from frozen SE2.2,
with source attempt links and input hashes, one new attempt per name.
Measure each checkpoint write's attempts, retry delays and elapsed duration.
Bound transient rename retries; retain the last successful file on permanent
failure and report an explicit checkpoint failure. No removal of the prior file.
Use injected rename refusal and controlled time for tests, including restoration.
Do not identify an external program as the cause of Windows sharing refusal.

## B: one observation and traversal diagnosis

Before representative search, compare complete serialized PlanningObservation
for all five stopped X Multi CONTINUE_WAIT runs at t=0, reconstructed from saved
checkpoints and verified against their final recorded decision boundary. Check
equivalence and input hashes. If equal, use X-multi-none-continue_wait alone.
The reference is original RENEWAL_FIRST and the single proposed optimization is
existing SERVICE_FIRST, now an explicit opt-in factory. It changes traversal only,
not final tie-break or candidate space; it is shared by both horizons/rules.

One search per order on the same observation, sequential processes, 2M nodes / 30s
search budget / 45s watchdog. Record score, complete plan, status, CPU/elapsed,
nodes, phases, pruning and memo counters. An incomplete reference's incumbent
cannot establish full-plan equivalence. Validate exact five metrics AND entire
winning plan against full DFS and reversed traversal on small nontrivial tasks
for both rules before the five controller repetitions.

## C: five previously incomplete controllers

Commit/freeze traversal option and verification with a distinct JAR and registration.
Repeat only the five frozen SEARCH_INCOMPLETE cases. Same input models and tie-break;
use SERVICE_FIRST. Stage A results remain distinct and are not silently rerun.
An inconclusive diagnosis may still be followed by the registered five finite
attempts after correctness tests; no open-ended solver tuning.

## Budgets and outputs

Both registrations keep SE2.2 budgets: 2M nodes and 30s per search, 60s cumulative
controller elapsed, 90s external watchdog, Java21 -Xmx1024m. Exactly one heavy JVM
at a time. Checkpoint retries consume actual controller elapsed. Do not increase
budgets on failure; preserve partial output and explicit stop/error reasons.
Search still consumes no model contact time. No old full grid or other scenarios.

Deliver twelve rows linked to old attempts, independent journal/recovery metrics,
canonical regression checks for completed repeats, preservation/restoration proof,
before/after representative profile, and late-X conclusions separated from missing
results. New files only in target/security-evaluation-se221 and docs/security-evaluation-se221.
