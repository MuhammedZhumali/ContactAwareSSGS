# PC2 execution record

One heavy JVM at a time. Previous artifacts are protected by `target/practical-controller-pc2/preserved-inputs.json` (8,832 files). No local commit is created; the dirty base commit, exact source archives and each JAR hash identify versions.

| Work | Owner | Status |
|---|---|---|
| Extract three historical roots and provenance | pc2_fixtures | Complete; JSON extraction and actual replay verified |
| Common feasible seed and unit tests | pc2_seed | Complete; first isolated build passed |
| Retention, comparator selection, timing and integration | main | Implemented and targeted tests passed |
| Oracle, invalidation, checkpoint and horizon tests | pc2_fixtures | Complete, including actual hidden-C3 retention |
| Registered ISS46 runs, controls and journal audit | main | Complete:22 FINISHED journals audited,1 separate PROCESS_ERROR checkpoint preserved |
| Measured WAIT regression tests | pc2_seed | Complete; controlled-clock tests passed |
| README generator / final integration | pc2_fixtures / main | Complete; root and PC2 README,23-row CSV, provenance manifest |

First isolated candidate-v1: 116 targeted tests passed. Candidate-v2: 121 passed, including endpoint/expiry/cancellation and interrupted-work telemetry. Three root reconstructions and same-root replay ablations passed. Counts overlap and are not summed.

The initial measured series exposed a distinct temporal-admission bug: a WAIT whose original wake passed during calculation was reinterpreted as WAIT until LOS. Candidate-v3 added replanning for passed/changed WAIT events and stale intent, but three new test expectations omitted mandatory STOP at contact end. Its failed build/logs are preserved. Candidate-v4 changes only those test expectations; the same production correction passes 124 targeted tests in 15 classes. No full Maven suite was run. Initial16 results are preserved; six corrected measured cases were separately registered against candidate-v4 before execution. No offline rerun or budget increase.

Initial audit: all16 finished; 6,666 complete portfolio comparisons agree with the independently implemented full comparator. All16 actual journals pass costs, nonce, delivery, security-gate and checkpoint/export checks. Four offline Next/Multi and four offline seed-without-search runs each served46/46. Initial measured practical results were15/3/45/33; controls46/23. Of111 missed contacts,110 have explicit elapsed-WAIT retarget-to-LOS evidence; the remaining one has insufficient delivered traffic. These negative measured results remain part of the evidence.

The first corrected measured attempt stopped with PROCESS_ERROR: atomic checkpoint rename AccessDeniedException, ACCESS_DENIED_TIME_EXHAUSTED. The preserved last valid checkpoint contains11 served /12 started contacts, not final mission metrics. Cause/locking actor remains unknown. Its registration stopped before the other five cases. A separate technical-repeat registration permits one repeat of this failed case and first attempts of the remaining five, using the identical candidate-v4 JAR and budgets; no active checkpoint reads during these runs. A second PROCESS_ERROR stops further work without another retry. Checkpoint production behavior and its existing retry limits are unchanged.

The separately registered measured-v4b series finished all six attempts: four Next/Multi combinations and both controls served46/46. Practical five-component outcomes equal offline:46 contacts,920 useful seconds,32.66 model operation CPU seconds,49,680 management bytes,92 keys. Measured practical process seconds (CAN H1/H2, CW H1/H2):336.61 /593.25 /129.88 /417.41. FixedPeriod uses122 keys and35.66 operation CPU seconds; PerContact matches practical costs. This establishes no mission-wise Multi advantage on this load. The H2/CAN process is close to its unchanged600s cap.

Final corrected audit:2,554 complete portfolio comparisons and all6 actual journals passed, no missed contact and no decision deadline miss. Across the two executed full series there are9,220 independently checked selections and22 FINISHED executions; the additional PROCESS_ERROR has only checkpoint-prefix metrics. The original directional shortfall was traced separately: an intentional retained WAIT tied the seed on all five nominal metrics and won CONTINUE_WAIT, but later actual compute delay consumed its small frame-completion slack. This is a measured/nominal-model limitation, not an incorrect comparator or a silently retargeted WAIT.

Registered full-series design: eight PC1 combinations; four physical control runs (FixedPeriod T=3600 s and PerContact JUST_IN_TIME in offline/measured modes); four cheap offline seed-without-search executions to check whether local improvements worsen full mission service. Budgets remain 5000 nodes, 1 s search, 2 s decision and 600 s process, no cumulative controller cap. Seed and retained replay receive at most 350 ms each inside the decision, followed by the unchanged search. No automatic retry or increase.
