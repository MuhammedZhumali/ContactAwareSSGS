# Timing and CPU measurement boundaries

These definitions describe the frozen final-v2 harness. They clarify the reported fields without
changing the executable, budgets or measured-delay model.

| Field | Actual interval and interpretation |
| --- | --- |
| `processSeconds` | Parent monotonic time from before launching the Java child through exit or watchdog kill/reap. Includes JVM startup, input/setup, execution, checkpoint I/O and final serialization when reached. Available for stopped processes. The external limit is 600 s; launch/kill overhead can make the recorded interval slightly longer. |
| `controllerSeconds` | `ResearchValidationMain` wall clock immediately before calling the selected controller until that call returns. Includes controller checkpoint callbacks. Excludes initial engine creation, the extra final checkpoint, and subsequent result serialization. Unavailable after a watchdog that prevents final export. |
| `threadCpuSeconds`, `gcMillis` | Harness-level telemetry starts just before the controller call, but its final sample is taken after the final checkpoint and most exports. These counters therefore cover a longer interval than `controllerSeconds`. Thread CPU covers the calling thread only; GC deltas are JVM-wide. Do not divide this CPU by controller wall time as a matched-interval utilization measure, or attribute all GC time to search. |
| `searchSeconds`, `searchThreadCpuSeconds` | Sum of each exported actual search invocation's own elapsed/CPU telemetry. Includes COMPLETE and INCOMPLETE invocations. Unavailable when the final in-memory search ledger is lost at process termination. No-search controls have an empty ledger and zero search work. |
| `decisionHostSeconds` | Sum of measured-controller decision intervals. Observation, candidate generation, seed, retained-plan replay, search and selection contribute to the charged delay. Checkpoint I/O and actual executor work are outside this modeled decision-delay interval. |
| `checkpointSeconds` | Sum of independently recorded checkpoint-write elapsed durations, including retry/backoff when present. This overlaps controller/process time; it must not be added to either as disjoint extra wall time. The final explicit write also appears here when the harness reaches it. |
| `controlCpu` | Synthetic GS+SC lifecycle-operation CPU reconstructed from actually consumed operation phases. This is a model cost, not Java runtime, payload-encryption CPU or hardware calibration. |

Source boundaries: `scripts/run_security_evaluation_se21.py:54-69` owns the child timer and
watchdog. In `ResearchValidationMain.java`, the recorder/start precede the controller call,
`elapsed` is frozen when that call returns, then the final checkpoint and exports run before
`measurement.snapshot(elapsed, null)`. `SearchRuntimeTelemetry.Recorder.snapshot` samples
current-thread CPU and GC counters at invocation, not at the supplied elapsed timestamp.
Consequently the harness CPU/GC interval mismatch is a real reporting limitation, retained
explicitly rather than repaired by estimating or subtracting unknown export CPU.

The reported policy comparisons use the five modeled objective components. Runtime figures
use process wall time; quoted controller times use their labeled wall interval. Host timing
is a single observation for each registered attempt, with no onboard-equivalence or statistical
runtime-ratio claim. No recorded CPU/GC value is used to infer why a specific root was slow
without further evidence.
