# SE2.2 bounded audit-source check

Read-only inspection, 2026-10-23. No solver/build/experiment was run for this note.
Original audit files and logs were not modified.

## Exact cases and source evidence

- `docs/reviews/research-audit.md:118-128` reports the X tie mechanism and recovery table.
- `docs/reviews/research-audit-checks/java/org/contactaware/security/simulation/TieBreakSensitivity.java:17-18`
  fixes X/Y disclosure times relative to `2026-01-01T00:00:00Z`: p10=2 s,
  p30=6 s, original=10 s, **p70=14 s, p90=18 s, gap-midpoint=25 s**.
  The program uses existing `incidentScenario(..., "detected")`, so GS notice is +2 s.
- `logs/tiebreak.log:15-28` records X Multi canonical versus LAZY. Original attack
  recovery changes 2.65→69.25 s; p70 2.65→66.65 s; p90 remains 62.65 s;
  gap remains 55.65 s. Next is censored for all three late attacks. No-attack
  whole-run vectors agree between rules for each horizon.
- The audit did not test the alternative on ISS CP5400 (`research-audit.md:128`).

## Programs to preserve and repeat

`TieProbe.java` and `TieBreakSensitivity.java` are standalone package-private-API
consumers, not Maven tests. Compile both together against the chosen frozen JAR.
PowerShell example (root chooses new directories and a watchdog):

```powershell
$javaBin = 'C:/Program Files/Java/jdk-21.0.10/bin'
$jarPath = 'target/security-evaluation-se21-build/se21.jar'
$classPath = 'target/security-evaluation-se22/audit-classes'
New-Item -ItemType Directory -Force $classPath | Out-Null
& "$javaBin/javac.exe" --release 21 -encoding UTF-8 -cp $jarPath -d $classPath docs/reviews/research-audit-checks/java/org/contactaware/security/simulation/TieProbe.java docs/reviews/research-audit-checks/java/org/contactaware/security/simulation/TieBreakSensitivity.java
& "$javaBin/java.exe" -Xmx2g -cp "$classPath;$jarPath" org.contactaware.security.simulation.TieProbe X 2
& "$javaBin/java.exe" -Xmx2g -cp "$classPath;$jarPath" org.contactaware.security.simulation.TieBreakSensitivity
```

The original programs hardcode **2,000,000 nodes and no elapsed limit** per search
(`TieProbe:19`, `TieBreakSensitivity:43`). An external process deadline is needed;
report their runs as audit replication, separate from the SE2.2 registered main
node budget. Do not silently edit originals to alter budgets. `TieBreakSensitivity`
has no CLI case filter: it runs 56 X/Y controller cases (8 combinations × 7 modes).

## Key observations for a bounded first-action study

Frozen `target/security-evaluation-se2/X-multi-none.decisions.json`:

| Zero-based decision | Time | Point | Known factual prefix |
|---|---:|---|---|
| 3 | t=1 | before PREPARE A | OPEN just completed; no payload yet; no spare; A SAs operational on old pair; no pending operation/frame |
| 12 | t=3 | before INSTALL A | PREPARE completed; fresh A g1 pair PRE_ACTIVE and not installed; eight frames delivered (400 B each direction); old A SAs operational; no pending operation/frame |

Old A expires at t=70, before C3=[80,85); old B expires at t=120. At t=1,
old A counters remain 1/1; at t=3 they are 5/5. New A IDs are
`planner-key:10:WITNESS-SC:1:A:g1:up/down`, created at t=3, counters 0/0.
Final-state activation at t=81 must not be backfilled into the t=3 observation.

The audit's repeated whole-run vector `(3,37 s,.83 s,1240 B,2)` is not the
continuation score relative to either observation. Subtract factual accrued
progress/costs: t=1 root-relative expected `(3,37,.77,1160,2)`; t=3 expected
`(2,35,.57,1160,0)`. These are arithmetic predictions from the saved no-attack
run; the new fixed-first searches must establish the actual optimal sets.

## Interpretation risks to resolve explicitly

1. Audit LAZY is a **first-action wrapper**, not a full-plan alternate comparator.
   Its ranking is group CONTINUE / WAIT / service, then `until`, with stable
   generator ordering for residual ties (`TieBreakSensitivity:53-55`). It stops
   when reaching the original canonical first action. A new integrated rule
   needs an explicit residual order and consistent pruning/memo semantics;
   agreement with original LAZY on X should be checked, not assumed.
2. `TieProbe` reconstructs a child observation and skips changed evaluation ends
   (`:32-36`), rather than proving all first-action continuations under one frozen
   root horizon. The new enumerator should keep the original observation/end.
3. Both original helpers accept a nonnull continuation score without requiring
   COMPLETE (`TieProbe:38`, `TieBreakSensitivity:74`). A feasible incumbent equal
   to a proved root optimum witnesses that action's tie, but an inferior
   incomplete incumbent cannot prove the action is not optimal. To publish the
   entire optimal-first-action set, enumerate every generated root candidate
   and retain completion status; otherwise label it incomplete.
4. The audit log has no saved per-candidate `TieProbe` transcript. Reproduce it
   into a new SE2.2 log; do not manufacture an old audit log.
