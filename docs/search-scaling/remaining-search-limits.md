# Remaining exact-search limits in frozen v2

Date: 22 October 2026. This is a read-only diagnosis of four completed tier-2 attempts, not a new experiment or an optimizer change. The main comparison is in [report.md](report.md). The formal bound is specified in [planner-foundations.md](../planner-foundations.md).

All four attempts use the first 12 unchanged selected ISS contact windows, nine station contexts, cryptoperiod 7200 s, synthetic cost scale 1, 10 s complete payload frames, and the same exact solver. Next uses H=1 and Multi H=2. Their per-decision limits are 2,000,000 nodes and 60 s; each reported stop is `TIME_LIMIT`, before the node limit. The artifact records Java 21.0.10, `-Xmx1024m`, and `RENEWAL_FIRST` traversal with all v2 exact accelerations enabled.

Frozen executable: `target/search-scaling-build/experiment-v2.jar`, SHA256 `789261d4e7ad697c69c65f17f5a73cd498d578be64f187b20933715f680d80cd`. Input schedule SHA256: `fe8a56aebdae09d31dcb3097f1c03ed960a21860edb727643db4310788c64862`. These identities and the exact command are recorded in each `attempt.json`. Runtime measurements below belong to these attempts and this executable; they are not claims about another machine or later source revisions.

## Observed stopping points

Times are UTC on 10 October 2026. C1…C12 denote positions in the selected prefix, not generated replacements for the source contact IDs.

| Traffic / policy | Stopped at | Visible contacts | Completed searches before stop | Nodes in stopped search | Max depth | Total search time across controller run |
|---|---|---|---:|---:|---:|---:|
| Moderate / Next | C8 AOS, 06:17:34.340972900 | C8–C9 | 110 | 1,188,280 | 26 | 62.766 s |
| Moderate / Multi | C7 AOS, 05:32:00.939788818 | C7–C9 | 97 | 1,038,131 | 38 | 63.584 s |
| Saturated / Next | C2 AOS, 00:22:33.719329834 | C2–C3 | 29 | 793,589 | 89 | 90.528 s |
| Saturated / Multi | C1 AOS, 00:07:54.680328369 | C1–C3 | 3 | 750,795 | 114 | 67.525 s |

Each stopped search took approximately 60.0001 s. No action from its incomplete result was executed. All four actual snapshots have no pending operation, no frame in flight, and no uncertain context at the stopping point. Pending operations discussed below belong to *nominal descendants explored inside search*, not to the stopped actual execution.

The partial actual results are respectively 7 served / 420 s useful / 3.67 s CPU / 5560 B / 10 created keys; 6 / 360 / 3.61 / 5480 / 10; 1 / 160 / 0.91 / 1080 / 4; and 0 / 0 / 0.40 / 0 / 4. These are prefixes of interrupted controller runs. All four exports have `finalMetrics: null`; these partial values must not be used as final policy-comparison scores.

## Observed tree work in the stopped searches

| Traffic / policy | Root candidates after WAIT reduction | Raw root PREPARE | Terminal plans evaluated | Memo hits / entries | Bound-pruned nodes | Canonical-bound prunes |
|---|---:|---:|---:|---:|---:|---:|
| Moderate / Next | 12 | 9 | 10,013 | 10,184 / 10,013 | 1,007,870 | 1 |
| Moderate / Multi | 12 | 9 | 6,193 | 11,992 / 6,193 | 879,718 | 1 |
| Saturated / Next | 10 | 8 | 40 | 0 / 40 | 378,379 | 1 |
| Saturated / Multi | 9 | 7 | 40 | 0 / 40 | 358,570 | 1 |

`rootGeneratedByAction` describes raw candidates, while `rootCandidateCount` is recorded after equivalent WAIT targets are reduced. Their sums therefore need not coincide. Cache capacity was not reached: all four have `memoStoreSkips=0`. The saturated searches did not obtain any memo hit; the compact export does not identify which specific state fields prevented reuse. In addition, a subtree pruned against the global incumbent is deliberately not stored as an exact independently solved suffix, as required by the memoization contract.

| Traffic / policy | PREPARE transitions executed | WAIT transitions executed | INSTALL / SWITCH / OPEN / REUSE | CONTINUE transitions | WAITs skipped before execution |
|---|---:|---:|---:|---:|---:|
| Moderate / Next | 965,746 | 160,212 | 32,255 / 665 / 3 / 0 | 29,402 | 410,002 |
| Moderate / Multi | 844,959 | 140,228 | 27,256 / 521 / 4 / 1 | 25,177 | 350,249 |
| Saturated / Next | 378,338 | 415,170 | 1 / 1 / 2 / 1 | 77 | 1,807,583 |
| Saturated / Multi | 358,529 | 392,184 | 2 / 2 / 3 / 1 | 93 | 1,712,492 |

These are search transitions, not actual security operations. In particular, hundreds of thousands of search PREPARE transitions do not mean that the actual spacecraft experiment created that many keys.

| Traffic / policy | Generation time | Branch execution time | Bound evaluation time |
|---|---:|---:|---:|
| Moderate / Next | 13.332 s | 25.182 s | 10.423 s |
| Moderate / Multi | 12.236 s | 22.950 s | 13.400 s |
| Saturated / Next | 11.283 s | 30.166 s | 9.571 s |
| Saturated / Multi | 11.411 s | 25.904 s | 12.995 s |

These instrumented categories do not cover every part of the 60 s search, and are separate from synthetic GS/spacecraft CPU. The observed workload is dominated by creating and processing PREPARE/WAIT branches, despite substantial exact pruning. Depth alone is not an adequate explanation: the moderate runs fail with considerably shallower trees.

## Moderate traffic: useful delivery outside the mandatory service set

This section distinguishes recorded inputs from deductions about the bound. The exported compact decisions contain diagnostics but do **not** contain the incomplete incumbent plan/score or a per-node bound trace. The tuples below are reconstructed from the frozen implementation and exported model/state; they are not values read from an incumbent trace or results of an additional search.

Moderate traffic offers and requires three 1000 B frames in each direction: 60 s of payload per served contact. C9, `orekit-25544-SATNOGS-4954-1`, lasts only 58.977355957 s, from 06:44:50.547637939 to 06:45:49.524993896. It cannot be served even with no security overhead. The geometric set S correctly excludes it.

However, C9 can still contribute five frames, or 50 s, to the second objective. Its station has no keys in either stopped snapshot. After PREPARE before AOS and INSTALL 4 s + SWITCH 1 s + OPEN 1 s inside C9, 52.977355957 s remain, which fit those five frames. The pair costs 0.71 s CPU, 1080 B and two created keys, including PREPARE. This construction is consistent with the nominal model and allowed event starts; it was not separately executed in this diagnostic pass.

At the Next stop, C8 is station SATNOGS-3905, whose active pair remains valid until 06:42:12.939910889, after C8 ends. C8 can supply its full 60 s with one OPEN. At the Multi stop, C7 is SATNOGS-4856 with an active pair valid until 05:55:53.766021729, also after its contact ends; C8 has the same valid pair as above. Thus C7/C8 do not require renewal to supply 60 s each.

| Root | Reconstructed v2 optimistic tuple: served / useful / CPU / bytes / new keys | Direct nominal construction including C9 setup |
|---|---|---|
| Moderate Next, C8–C9 | 1 / 110 s / 0.06 s / 80 / 0 | 1 / 110 s / 0.77 s / 1160 / 2 |
| Moderate Multi, C7–C9 | 2 / 170 s / 0.12 s / 160 / 0 | 2 / 170 s / 0.83 s / 1240 / 2 |

The gap is intentional conservatism: the conditional cost bound proves costs required to serve every member of S. It makes no claim that C9 must be opened merely to reach the first objective, because C9 is outside S. It retains C9's useful capacity, but currently does not derive additional mandatory costs conditional on attaining the **second** objective. As a result, the optimistic tuple can retain the same service and useful-time values while understating the setup needed to obtain C9's 50 s.

The GCD is already 10 s and 110/170 s are exact multiples, so final rounding cannot close this cost gap. Already-started unnecessary PREPARE work is charged, but several preparations can fit under the remaining optimistic cost slack. Nine contexts offer PREPARE at each stopped root. This explains a concrete mechanism allowing many preparation combinations to survive; the compact aggregate diagnostics alone cannot attribute an exact fraction of the runtime to it or show the order of every incumbent improvement.

## Saturated traffic: pending setup and pooling across separate windows

Here S contains every visible contact: the minimum is only one frame in each direction, or 20 s. Useful-time demand is much larger. All calculations below use the exported window durations and the synthetic profile, not measured hardware costs.

The saturated Next snapshot at C2 contains an uninstalled prepared pair for SATNOGS-2987 and an active pair for SATNOGS-3634, valid until 02:07:59.680328369. The saturated Multi snapshot at C1 contains uninstalled prepared pairs for both stations. The relevant windows are:

| Contact / station | Duration | Required initial online work in this nominal continuation | Per-window full-frame capacity after that work |
|---|---:|---:|---:|
| C1 / SATNOGS-3634 | 170.937194824 s | INSTALL + SWITCH + OPEN = 6 s | 160 s |
| C2 / SATNOGS-2987 | 404.280395508 s | INSTALL + SWITCH + OPEN = 6 s | 390 s |
| C3 / SATNOGS-3634 | 390.961303711 s | OPEN = 1 s | 380 s |

A direct nominal continuation can finish setup immediately and transmit those frames; the activated 3634 pair reaches the end of C3 under CP=7200. Therefore 770 s for the Next C2–C3 horizon and 930 s for the Multi C1–C3 horizon are supported by per-window arithmetic and a direct construction. This is not a claim that the incomplete solver certified the canonical plan, and no replay was performed for this diagnosis.

At the stopped roots themselves, the local opening-delay bound observes the required setup. The weakness appears in a nominal child after INSTALL starts. `openingDelay` deliberately grants instantaneous access whenever the same context has an online operation pending; the global/per-context mandatory-work bounds compensate conservatively for its remaining work. Their pooled capacity can nevertheless combine leftover fractions from physically separate windows.

- **Next child with INSTALL for 2987 pending:** local frame capacities become 400 s for C2 plus 380 s for C3. The total time pool also allows `10 * floor((404.280395508 + 390.961303711 − 7) / 10) = 780 s`. This is 10 s above the separate-window capacity of 390 + 380 = 770 s.
- **Multi child with INSTALL for 3634 pending:** the current implementation grants zero local opening delay to both visible 3634 windows while that context's operation is pending. Local capacities become 170 + 390 + 390 = 950 s. The pooled bound likewise allows `10 * floor((170.937194824 + 404.280395508 + 390.961303711 − 13) / 10) = 950 s`, above 160 + 390 + 380 = 930 s.

The nominal pending operation still executes with its real duration and resource reservations. This discrepancy is only a loose search bound; it does not permit extra frames in actual simulation. GCD rounding removes a fractional remainder smaller than one frame from the combined cap, but it cannot infer that unused fractions in different windows cannot be assembled into another complete frame.

When useful time remains optimistically above a completed candidate, lexicographic comparison cannot prune a branch merely because it has more CPU, bytes or keys. The recorded transitions are consistent with extensive enumeration in this region: the saturated Next search executed only one INSTALL and one SWITCH, but 378,338 PREPARE and 415,170 WAIT transitions; saturated Multi executed two INSTALL and two SWITCH transitions, with 358,529 PREPARE and 392,184 WAIT. This supports the identified mechanism but does not replace a node-level trace identifying each pruning decision.

## An additional amplifier: the known station catalog grows with the prefix

The fixture creates a context for each distinct station in the selected experiment prefix. Consequently, prefix sizes 3 / 6 / 12 / 24 / 46 have 2 / 5 / 9 / 14 / 17 known contexts, respectively. This is directly determined by the unchanged selected schedule and `PolicyExperimentFixtures.selectedSchedule`; the current prefix experiment varies both the total number of contacts and the available context catalog. Its timing results cannot isolate the effect of contact count alone.

An observed light-traffic example with CP=5400 and Multi H=2 makes this visible:

| Prefix | Known contexts | First-C1 search visibility | PREPARE candidates at first C1 AOS | First-C1 search result | First-C1 nodes / search time |
|---|---:|---|---:|---|---:|
| 3 contacts | 2 | C1/C2/C3 | 0 | COMPLETE | 9,713 / 1.458 s |
| 6 contacts | 5 | Same C1/C2/C3 | 3 | INCOMPLETE, TIME_LIMIT | 697,186 / 60.0004 s |

In both cases the first two stations' pairs were already prepared at the initial handoff to C1. The six-contact fixture additionally knows SATNOGS-3905, SATNOGS-4856 and SATNOGS-6, whose contexts have no material yet. They add three root PREPARE choices, even though their contact windows are not in this search's three-contact horizon. The three-contact controller subsequently finishes, with 37 COMPLETE searches; the six-contact controller stops at this first C1 AOS with three preceding COMPLETE searches.

This is **not an equal-observation hidden-schedule comparison**: the observations contain different known context inventories/allowed-context configurations. No hidden contact timing has to leak for candidate sets to differ. It is a catalog-size effect permitted by the current model, and a plausible amplifier of preparation branching. The maximum depth is 34 in both stopped/comparable searches, further distinguishing breadth from depth in this example. The configuration is preserved throughout the grid; a separate future experiment could hold the known catalog fixed while varying the contact prefix.

The source evidence is [three-contact decisions](../../target/search-scaling-grid-v2/attempts/n03-light-cp5400-multi-h2/tier1/results/schedule3-cp5400-cost1-multi.decisions.json), [six-contact decisions](../../target/search-scaling-grid-v2/attempts/n06-light-cp5400-multi-h2/tier2/results/schedule6-cp5400-cost1-multi.decisions.json), their adjacent model/summary files, and [fixture construction](../../src/main/java/org/contactaware/security/simulation/PolicyExperimentFixtures.java). This comparison identifies a changed input and its observed search expansion, not the fraction of runtime causally attributable to that change in isolation.

## What these results establish—and what remains unproven

The v2 bounds and WAIT reduction perform substantial work reduction, but they do not make these four attempts finish within the declared budget. Two concrete residual relaxations have been identified: missing setup costs conditional on optional useful delivery outside S, and excess pooled frame capacity during pending online setup. Neither finding is a correctness failure: both relaxations make the bound more optimistic.

No candidate was removed, no heuristic substitute was used, and no incomplete incumbent was executed to produce a final score. A later improvement would need its own admissibility proof and exhaustive small-tree checks. In particular, removing preparation for a station without visible payload is not justified merely by these measurements: a preparation can create permitted decision events, as the existing counterexample in the specification demonstrates.

These files do not establish a universal scaling law, a minimal required budget, the canonical winners of the stopped searches, or which future improvement will be sufficient. They also do not compare final security-policy quality on all 12 contacts, since these controller runs stopped early. The report preserves their explicit `SEARCH_INCOMPLETE` status.

## Exact local evidence

Each directory below contains `attempt.json`, `results/manifest.json`, `results/summary.json`, a `.model.json`, `.actual.json`, `.events.json`, and `.decisions.json`. The `.decisions.json` last entry supplies the stopped-search counters; `.actual.json` supplies the factual state and partial progress; `.model.json` supplies timing, payload and costs. These generated artifacts are local under `target/` and should be archived with the frozen JAR when distributing the result.

| Attempt | Source directory | Compact decisions |
|---|---|---|
| Moderate Next | [tier2](../../target/search-scaling-grid-v2/attempts/n12-moderate-cp7200-next-h1/tier2/) | [decisions](../../target/search-scaling-grid-v2/attempts/n12-moderate-cp7200-next-h1/tier2/results/schedule12-cp7200-cost1-moderate-next.decisions.json) |
| Moderate Multi | [tier2](../../target/search-scaling-grid-v2/attempts/n12-moderate-cp7200-multi-h2/tier2/) | [decisions](../../target/search-scaling-grid-v2/attempts/n12-moderate-cp7200-multi-h2/tier2/results/schedule12-cp7200-cost1-moderate-multi.decisions.json) |
| Saturated Next | [tier2](../../target/search-scaling-grid-v2/attempts/n12-saturated-cp7200-next-h1/tier2/) | [decisions](../../target/search-scaling-grid-v2/attempts/n12-saturated-cp7200-next-h1/tier2/results/schedule12-cp7200-cost1-saturated-next.decisions.json) |
| Saturated Multi | [tier2](../../target/search-scaling-grid-v2/attempts/n12-saturated-cp7200-multi-h2/tier2/) | [decisions](../../target/search-scaling-grid-v2/attempts/n12-saturated-cp7200-multi-h2/tier2/results/schedule12-cp7200-cost1-saturated-multi.decisions.json) |

Implementation inspected: [PlanningOptimisticBound](../../src/main/java/org/contactaware/security/simulation/PlanningOptimisticBound.java), especially `upperBound`, `eligibleContactsServedRootBound`, `geometricallyEligibleContacts`, `frameQuantum`, and `openingDelay`; and [ExactHorizonPlanner](../../src/main/java/org/contactaware/security/simulation/ExactHorizonPlanner.java) for diagnostics semantics. These source links describe the current v2 implementation; the JAR hash above remains the experiment identity if sources later change.
