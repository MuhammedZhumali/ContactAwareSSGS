# Preregistered schedule experiment grid

Protocol version: 2026-10-22, before the new workload/prefix experiments below. Earlier X/Y witnesses and exploratory schedule runs are development evidence, not results collected under this protocol. This document fixes inputs and reporting rules; it does not claim that the matrix has been executed.

## Question and common conditions

Does a longer visible sequence of spacecraft–ground-station contacts change feasible key/SA lifecycle choices and the resulting service/cost trade-off? Next and Multi use the same exact finite-space solver, action generator, security constraints, costs and canonical tie-break. Only the visible horizon differs. FixedPeriod and PerContact are declared rule baselines, with no search-optimality certificate.

Use the verified, chronologically ordered 46-contact ISS schedule. Record its SHA256 and the original dataset provenance. Prefixes contain the first **3, 6, 12, 24 and 46** original windows, preserving their identifiers, stations and nanosecond timestamps. Every policy starts with empty keys and the same configured station contexts. The simulation starts one PREPARE duration before the first window. Operation costs remain the synthetic reference profile (`costScale=1`); an extra cost sensitivity study requires a separate declared grid.

## Fixed factor levels

| Factor | Levels / definition |
| --- | --- |
| Contact prefix | 3, 6, 12, 24, 46 |
| Payload rate and frame | 100 B/s; 1000 B complete frames; 10 s per frame |
| Light traffic | Offer and require 1 frame per direction: 20 s useful demand/contact |
| Moderate traffic | Offer and require 3 frames per direction: 60 s useful demand/contact |
| Saturated traffic | Require 1 frame per direction; offer `floor(window duration / 10 s)` frames **per direction**, with a minimum offered budget of 1 frame. This finite budget cannot limit any physically possible full-contact payload run. |
| Cryptoperiod | 5400 s, 7200 s |
| FixedPeriod | T = 1800 s, 3600 s, 7200 s; deadline anchored to the last actual pair activation, not preparation or contact start |
| PerContact | JUST_IN_TIME preparation, fresh pair activation in each contact |
| Next | H = 1 future contact beyond the current contact |
| Multi | H = 2 and H = 3 future contacts beyond the current contact |

Moderate traffic deliberately includes windows shorter than 60 s. Export a per-window **payload-only feasibility ceiling**: required complete frames × frame duration must fit in the original window. Report the count of structurally eligible contacts alongside service count. This is an optimistic ceiling which excludes management overhead, key availability, cryptoperiod and counters; it is not a promise that any policy can serve those contacts. Saturated traffic distinguishes high offered demand from high minimum service demand. A T=7200 s baseline with a 5400 s cryptoperiod deliberately includes a period in which the strict periodic rule may wait for its own next deadline; it must never reuse expired keys.

The full factor grid has 30 schedule/traffic/cryptoperiod cells and 7 distinct policy configurations per cell (three FixedPeriod T values, one PerContact, Next H1, Multi H2, Multi H3): **210 policy runs**. T is not a factor for policies which do not use T. Reuse already completed identical configurations rather than rerunning them to manufacture extra samples.

## Staged execution, fixed before measurement

1. **Growth check:** all five prefixes, light traffic, cryptoperiod 7200 s, FixedPeriod T=3600 s, PerContact JIT, Next H1, Multi H2.
2. **Demand check:** prefixes 3/6/12, moderate and saturated traffic, cryptoperiod 7200 s, the same four policy configurations.
3. **Lifecycle check:** prefixes 3/6/12, all three workloads, both cryptoperiods, the same four policies plus FixedPeriod T=1800/7200 s. Reuse matching cells from stages 1–2.
4. **Horizon check:** add Multi H3 to prefixes 3/6/12, all workloads and both cryptoperiods. Compare H1/H2/H3 only within the same cell.
5. **Extended grid:** remaining prefix-24/46 configurations are a separately reported extension. Completion of stages 1–4 does not imply completion of the 210-run matrix.

Run each search first with **100,000 visited nodes / 10 s per decision**. A stopped run may be retried from its original fixture with **2,000,000 nodes / 60 s per decision**, recording both attempts and their immutable output directories. These are resource limits, not approximation tolerances. Never execute an INCOMPLETE incumbent as if it were an exact selected action. If the second tier is still incomplete, retain it as incomplete; do not tune traffic, time units, cryptoperiod, T, objective, tie-break or observation horizon to obtain a preferred result.

Process increasing prefixes within each matching workload/cryptoperiod/policy configuration. A larger prefix beyond a two-tier search failure may be left unrun to bound total compute; record `NOT_RUN_AFTER_BUDGET_LIMIT` and the blocking predecessor. Do not silently remove these cells or describe the remaining set as the full grid. Rule baselines may still finish for such a cell. Time-limited outcomes are host dependent, so record machine/JVM settings, exact budgets and wall time; node-limit and exact structural counters are the reproducible work indicators.

## Recorded evidence and interpretation

Export the fixture model, original schedule hash, factor settings, per-contact payload demand and geometric service ceiling. Keep actual event journals and final execution results separate from forecast diagnostics.

For every decision report: simulation time and resumption time, decision source, executed action, search status, explicit stop reason, visible contact count/horizon end, root candidates, explored/expanded nodes, maximum depth, terminal continuations, generated/executed actions, WAIT collapsing, memo hits/storage, bound pruning, and generation/execution/bound/total search times. Root candidates and work counters describe actual computation; a collapsed or memoized subtree is not counted as if every conceptual leaf had been executed. Rule decisions have no search status or search certificate.

For FINISHED runs report all five final objectives separately: served contacts, useful secure time, GS+SC security CPU, UL+DL management bytes, and new directional keys. Include every actual preparation once, including unused keys. Algorithm search time is separate from security CPU and does not advance simulated time. A nonfinished run has partial progress and explicit status/reason, **not zero final metrics**. Forecast scores are never summed across decisions. Report mean/max/total search work and time per decision from the same decision records; do not hide a costly decision behind the whole-run mean.

No statistical confidence interval is implied by one deterministic run per cell. No final plot combines finished results with invented zeros for failed or unrun cells. COMPLETE establishes optimality only over the declared finite nominal horizon space; FINISHED establishes completion of actual execution. Neither proves global receding-policy optimality, hardware performance or lower compromise risk. Security compliance (deadline/counter/reuse constraints) and exposure duration remain distinct from service efficiency.

## Runner examples

Use new or empty output directories. The explicit protocol budget overrides the CLI's backwards-compatible default.

```powershell
mvn -q -DskipTests compile exec:java "-Dexec.mainClass=org.contactaware.security.simulation.FourPolicyExperimentMain" "-Dexec.args=--scope schedule --contact-count 6 --traffic light --cryptoperiod-seconds 7200 --fixed-period-seconds 3600 --per-contact just-in-time --multi-horizon 2 --max-nodes 100000 --max-seconds 10 --out target/grid/n6-light-cp7200-t3600-h2-tier1"
mvn -q -DskipTests compile exec:java "-Dexec.mainClass=org.contactaware.security.simulation.FourPolicyExperimentMain" "-Dexec.args=--scope schedule --contact-count 3 --traffic moderate --cryptoperiod-seconds 5400 --fixed-period-seconds 1800 --per-contact just-in-time --multi-horizon 3 --max-nodes 2000000 --max-seconds 60 --out target/grid/n3-moderate-cp5400-t1800-h3-tier2"
```

`schedule3` and `schedule46` remain compatible aliases; their original default is light traffic. `--scope schedule` requires an explicit contact count in 1..46. X/Y inputs stay unchanged and reject schedule-only factors. The fixture identifier preserves the previous light-traffic form; other workloads add their name so that exported files cannot be mistaken for light-traffic results.
