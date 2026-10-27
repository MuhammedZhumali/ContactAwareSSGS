# R1 saved search, quality, and host-primitive measurements

Analysis date: 2026-10-25. This document reads existing evidence only. No Java process, planner experiment, or cryptographic benchmark was launched while preparing it.

The four bounded search probes preserve the observed plans and all five scores, but **do not demonstrate an overall speedup**. The candidate takes 1.89% more search wall time under CANONICAL and 2.47% more under CONTINUE_WAIT in these individual observations. Lower bound-phase time is offset by higher time elsewhere. These probes neither partition nor explain the previously reported 3361 s controller execution.

**Fixed-work search comparison.** Sources are [diagnostics/protocol.json](../../target/research-validation/diagnostics/protocol.json), [summary.json](../../target/research-validation/diagnostics/summary.json), [comparison.json](../../target/research-validation/diagnostics/comparison.json), and the unchanged [RootSearchProbe.java](probes/RootSearchProbe.java). Each version/rule pair has one attempt, using the same saved day-2 Multi C1 checkpoint, horizon 2, `acceleratedServiceFirst`, and `V2_EVENT_BOUNDARIES`. The observation is at `2026-10-12T00:08:37.296295166Z`, with three visible contacts. Limits are 20,000 nodes, 30 s per search, and 45 s per process. This is a root-search probe, not a complete controller run. There are no repeated timing trials or confidence intervals.

The frozen baseline is `baseline-restored.jar`, SHA-256 `75e8ee307092fdb5b989aa1a44907eaa6a758933aead3bf0689b87b5b64fbe8c`; the candidate is `candidate.jar`, SHA-256 `3e8a7d55c168c7643e2244d2ca6a6176bb057dcb696ad44ac02a363598c864f0`. Both metadata records cite base commit `3621333d8093b4cb0c232c7db9164c66e273053c`; the JAR identities distinguish the versions. These are not the later practical-v2 executable.

| Rule | Version | Process wall s | Search wall s | Search-thread CPU s | Post-search used heap MiB | Search-window GC count / ms |
|---|---|---:|---:|---:|---:|---:|
| CANONICAL | before | 4.657 | 3.4206441 | 3.328125 | 69.762 | 13 / 48 |
| CANONICAL | after | 4.781 | 3.4852910 | 3.390625 | 85.621 | 12 / 48 |
| CONTINUE_WAIT | before | 4.718 | 3.5623870 | 3.406250 | 111.365 | 13 / 50 |
| CONTINUE_WAIT | after | 4.843 | 3.6503505 | 3.531250 | 125.235 | 12 / 49 |

CPU rises by 1.88% and 3.67%, respectively. `heapUsedBytes` is a single `Runtime.totalMemory() - freeMemory()` sample immediately after search; it is **not peak heap, retained live set, allocation volume, or process RSS**. GC values here are JVM-wide MXBean deltas over the measured search window; they are not uniquely attributable to the search thread. Process wall time includes startup and output work outside search.

| Search phase, ms | Before CANONICAL | After CANONICAL | Before CONTINUE_WAIT | After CONTINUE_WAIT |
|---|---:|---:|---:|---:|
| Initialization | 28.102 | 30.454 | 24.022 | 32.961 |
| Generation | 854.225 | 986.365 | 911.426 | 1048.456 |
| Execution | 932.585 | 1056.737 | 995.669 | 1101.138 |
| Bound | 695.418 | 485.959 | 695.676 | 496.997 |
| State key | 318.041 | 283.588 | 332.083 | 304.332 |
| Candidate processing | 279.343 | 307.124 | 296.763 | 329.804 |
| Evaluation | 14.889 | 16.528 | 13.427 | 15.101 |
| Completion | 35.691 | 36.976 | 32.245 | 34.077 |
| Unattributed | 262.349 | 281.560 | 261.077 | 287.485 |

These are `runtimeTelemetry.phaseElapsedNanos`, plus its residual, rounded to 0.001 ms. Separate legacy diagnostic timers are not added to them. Generation plus execution account for 58.62% and 58.89% of candidate search wall time. Bound and state-key work remain measurable, but these observations support no inference that their local reductions improve full-run latency.

All four searches stop `INCOMPLETE / NODE_LIMIT` at exactly 20,000 explored nodes. Before/after observations, full incumbent plans, and scores are equal within each rule. Each incumbent score is `(servedContacts=3, usefulSecureTime=410 s, controlCpu=1.73 s, managementBytes=3240, newKeys=2)`. This is a feasible incumbent, not a proven optimum. Non-timing counters are unchanged within each rule:

| Rule | Expanded | Generated actions | Executed transitions | Terminal plans / memo entries | Memo hits | Bound prunes | Canonical bound prunes | Pre-execution WAIT skips |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| CANONICAL | 11640 | 52978 | 20046 | 679 | 1114 | 6567 | 45 | 32886 |
| CONTINUE_WAIT | 12576 | 55404 | 20044 | 632 | 1232 | 5560 | 205 | 35317 |

Maximum depth is 71 and root candidate count is 2 throughout. Executed-transition counts and explored-node counts are distinct counters; the former exceed the node limit in the saved output.

**JFR observations.** The four `diagnostics/{before,after}-{CANONICAL,CONTINUE_WAIT}-samples.json` files contain exported `jdk.ExecutionSample`, `jdk.ObjectAllocationSample`, `jdk.GarbageCollection`, and `jdk.GCPhasePause` events; corresponding `.jfr` files are retained. For method summaries below, each stack contributes once to its first frame whose class starts with `org/contactaware/`, walking from the sampled top frame toward its callers. Java/library frames are skipped, and recursive application frames are not counted again. This locates the nearest application caller; it is not an exclusive method CPU measurement.

| Recording | Execution samples, all / with application frame | Most frequent nearest application methods (counts) | Allocation samples, all / with application frame |
|---|---:|---|---:|
| before CANONICAL | 49 / 28 | `ResourceUsage.plus` 5; `PlanningOptimisticBound.upperBound` 4; `ExactHorizonPlanner$Search.search` 3; `SecurityState.<init>` 3 | 1071 / 659 |
| after CANONICAL | 44 / 22 | `PlannedOperation.<init>` 2; `ResourceUsage.plus` 2; all other application methods individually 1 | 1092 / 662 |
| before CONTINUE_WAIT | 50 / 26 | `ExactHorizonPlanner$Search.search` 4; `ResourceUsage.plus` 4; `PlanningOptimisticBound.ceilDivide` 2; `PlanningOptimisticBound.upperBound` 2 | 1131 / 660 |
| after CONTINUE_WAIT | 49 / 30 | `PlanningOptimisticBound.upperBound` 7; `PlanningJournalSummary.from` 3; `ExactHorizonPlanner$Search.search`, `SecurityState.<init>`, `SecurityConstraintChecker.checkRadio` 2 each | 1124 / 683 |

The sample counts are small and cover each recording, including activity outside the search telemetry window. They identify exercised paths, not stable percentages or a ranking of full-controller bottlenecks.

| Recording | Largest sums of allocation-sample weights by nearest application method, MiB |
|---|---|
| before CANONICAL | `ResourceUsage.plus` 499.642; `PlanningOptimisticBound.openingDelay` 77.911; `ResourceUsage.<init>` 70.420 |
| after CANONICAL | `ResourceUsage.plus` 254.464; `PlanningOptimisticBound.upperBound` 223.488; `PlanningOptimisticBound.ceilDivide` 81.407 |
| before CONTINUE_WAIT | `ResourceUsage.plus` 242.444; `BranchEngine.executeGenerated` 197.273; `PlanningOptimisticBound.nanos` 174.301 |
| after CONTINUE_WAIT | `ResourceUsage.<init>` 305.629; `ResourceUsage.plus` 305.314; `PlanningOptimisticBound.upperBound` 168.308 |

These are sums of JFR `weight` values, representing sampled allocation estimates attributed through stacks. They are not an exact allocation census, per-class live memory, or peak memory. Their variation cannot establish an allocation reduction from one recording per case.

| Recording | JFR garbage collections | Sum of recorded pauses ms | Longest recorded pause ms |
|---|---:|---:|---:|
| before CANONICAL | 14 | 52.8044 | 5.2454 |
| after CANONICAL | 12 | 48.7408 | 5.3438 |
| before CONTINUE_WAIT | 13 | 49.3515 | 4.5550 |
| after CONTINUE_WAIT | 12 | 48.9972 | 6.3513 |

Every recorded collection has cause `G1 Evacuation Pause`. Sums use `jdk.GarbageCollection.sumOfPauses`, without adding the overlapping `GCPhasePause` events. JFR and MXBean totals have different scopes and precision; in particular, the before-CANONICAL recording includes 14 collections while the search window reports 13. These observations do not show GC dominating the bounded searches.

**Small completed-reference quality comparison.** [quality-protocol.json](../../target/research-validation/quality-protocol.json) and [r1-small-quality.json](../../target/research-validation/practical-v2-verified-build/target/r1-small-quality.json) record 12 rows: two existing suffix witnesses, two rules, and practical node caps 1, 50, and 5000. The oracle uses `PlannerOptions.reference()` full DFS at every remaining receding-horizon decision, with a 250,000-node / 30 s search cap. Every reference search in the retained successful comparison completed. Practical searches have the selected node cap and a 30 s limit. Both use horizon 2.

The shared starting states are produced by the existing witnesses' actual warm-up: `OPEN`, then `WAIT`, and an additional `CONTINUE` for `one-frame-suffix`. The reference and practical controllers receive the same warmed state for each comparison. The metrics below are the saved final simulation totals, including the common history already present in that state.

| Fixture | Reference/final practical five-tuple: contacts, useful s, CPU s, management bytes, new keys | Rule | Cap 1 searches | Cap 50 searches | Cap 5000 searches |
|---|---|---|---:|---:|---:|
| `one-frame-suffix` | `(2, 4, 4, 0, 0)` | CANONICAL | 12 incomplete/fallback | 10 complete | 10 complete |
| `one-frame-suffix` | `(2, 4, 4, 0, 0)` | CONTINUE_WAIT | 12 incomplete/fallback | 10 complete | 10 complete |
| `one-frame-prepare-suffix` | `(1, 2, 3, 64, 2)` | CANONICAL | 10 incomplete/fallback | 9 complete | 9 complete |
| `one-frame-prepare-suffix` | `(1, 2, 3, 64, 2)` | CONTINUE_WAIT | 10 incomplete/fallback | 9 complete | 9 complete |

Every one of the 12 rows has zero practical-minus-reference difference in **each** of served contacts, useful-time nanoseconds, control-CPU nanoseconds, management bytes, and new keys. There is no weighted or percentage aggregate score. At cap 1, all 44 recorded attempts use `SAFE_FALLBACK`; at caps 50 and 5000, all 76 attempts use `EXACT_COMPLETE`. Consequently these rows do not measure the quality of an incomplete feasible incumbent or its suffix reuse. The saved focused test report records one `R1SmallQualityTest` test, zero failures/errors/skips, elapsed 2.760 s. This analysis did not rerun it.

The initial attempt started at fresh scenario roots. It failed its expectation of `FINISHED`, receiving `SEARCH_INCOMPLETE` under the 250,000-node / 30 s oracle cap; the registration identifies the node cap as the limiting condition. The failed [practical-v2-build.log](../../target/research-validation/practical-v2-build.log) and initial test source in `practical-v2-build/src/test/java/.../R1SmallQualityTest.java` remain available. The successful warmed-state comparison is a narrowed scope, not completion of that original root-oracle experiment. No optimal reference or quality gap is available from the failed attempt. The 12 successful rows establish equality only on these two warmed witnesses; they do not prove arbitrary-workload quality, the fresh-root result, high-demand optimality, or Next-versus-Multi superiority.

**Host cryptographic primitive measurements.** [host-crypto/result.json](../../target/research-validation/host-crypto/result.json) reports Java `21.0.10+8-LTS-217`, provider `SunJCE version 21`, 2000 warm-up operations per mechanism, then five batches of 10,000 operations in one JVM. The table gives the observed range and median of the five batch means, not individual-operation latency percentiles.

| Mechanism | Wall ns/op min | Median | Max | Thread CPU ns/op observed min / median / max |
|---|---:|---:|---:|---:|
| AES-256 key generation | 770.04 | 892.73 | 3146.06 | 0 / 1562.5 / 3125 |
| AES-256 GCM encryption, 1000-byte plaintext | 1416.99 | 1651.05 | 3362.49 | 0 / 1562.5 / 3125 |

The timed key-generation loop includes key generation and encoding. GCM includes IV allocation, a new nonce, cipher initialization, and encryption; output/checksum work is also inside the loops. Zero reported CPU batch means do not mean zero CPU cost: the saved counter deltas are coarse, and cannot resolve those short batches reliably. This is a host primitive microbenchmark with allocations and initialization, without JMH isolation or repeated JVM trials. It measures neither spacecraft hardware nor INSTALL/SWITCH/OPEN/NOTIFY exchanges, radio transfer, ACK delays, or a key-management handshake. It provides no replacement or calibration for the unchanged synthetic lifecycle cost profiles.

**Evidence fingerprints.** Paths below are relative to `target/research-validation/`; hashes were calculated from the saved bytes while writing this document.

| File | SHA-256 |
|---|---|
| `diagnostics/summary.json` | `9e0c27d6d9023bd34ef754825477192f828fef3b8dadcc741b66a3ab60a39fb9` |
| `diagnostics/comparison.json` | `11dd1a1797c8d72d8b7d54741adaed7f2b87d3b0570998fc183805263ca8e87e` |
| `practical-v2-verified-build/target/r1-small-quality.json` | `28e89b33f2f3799600d5d8043b3d186f6b3e854f8a14d5847584fc031d0688d7` |
| `quality-protocol.json` | `115ff52864f900dd3f9e4c5ecc59b4760dbaa43b9f52a6fa86bfe6b273421113` |
| `host-crypto/result.json` | `8081d8e69269e6575251eef417b73483772db521d927ac1439d86a0bb83423da` |
