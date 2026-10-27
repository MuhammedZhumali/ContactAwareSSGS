# Prepared exact X reference: claim check

Checked 2026-10-25 by source inspection and the saved final-v2 build records. Only this note was changed. No JVM, experiment, new test run, active checkpoint or campaign outcome file was read/executed.

**Conclusion: both automatic exact-controller execution and prescribed-plan execution exist in distinct tests. The quoted prepared X five-score tuples are directly asserted by the automatic exact-controller test; they are not supported solely by a fixed plan. No factual correction is required to the current diagnosis or manuscript statement.**

## Automatic exact receding execution

`ControlledWitnessIntegrationTest.originalXYReplanToExperimentEndAndMatchTheIndependentWitnessTotals` invokes `ControlledWitnessMain` with `2000000` nodes per decision and `0` milliseconds (`src/test/java/org/contactaware/security/simulation/ControlledWitnessIntegrationTest.java:12-15`). `SearchLimits.java:6-14` defines zero as unlimited. This is a larger **allowance**, not a claim that every search consumed two million nodes.

`ControlledWitnessMain.java:23-39` constructs `PlannerOptions.accelerated()`, `ExactHorizonPlanner`, Next H1/Multi H2, and `RecedingHorizonController`; the planner lambda actually calls `exact.plan(observation)`. The controller adapter uses zero controller elapsed limit and continuation reuse disabled (`RecedingHorizonController.java:10-22`). `HorizonPlanner.selectUnrestricted:9-11` permits a first action only from a COMPLETE search. No prescribed post-warmup payload/security plan is supplied on this path.

The integration test asserts:

- FINISHED and the actual snapshot at t=85 (`:25-26`).
- X/Next `(served=2, useful=38 s, operation CPU=0.32 s, management=160 B, new directional keys=2)` and X/Multi `(3,37 s,0.83 s,1240 B,2)` (`:17-18,27-35`).
- No operation failures; **every** decision search is COMPLETE and executed action equals the search's first action (`:36-39`).
- X/Multi actually INSTALLs in C1; Y/Multi does not (`:49-53`).

These are complete physical runs produced by repeated exact finite-horizon decisions. COMPLETE at each observed root is not a whole-mission global optimality assertion.

## Fixture and cost boundary

`HorizonPlannerMain.checkpoint:23-30` copies the witness initial state/contacts/costs but replaces its prescribed plan with **only** PREPARE(A,new pair) at t=-2, then advances the engine to t=0 while retaining the actual journal. Thus OPEN/INSTALL/SWITCH choices after t=0 are automatic.

`HorizonWitness.java:20-21,29-46,54-72` defines CP150 s/material age400 s/reuse gap200 s; existing A/B key histories; contacts A[0,20], B[30,50], A[80,85] for X; and simulation interval [-2,85]. A's old activation is -80, giving deadline70; B's is -30. Historical provisioning predates the simulation and is an initial condition, not a charged operation in these totals. `SecurityCostProfile.java:94` makes the common PREPARE 0.20 s ground CPU plus 1.80 s wait, zero management bytes, and two new directional keys.

`ControlledWitnessMain.java:42-46` states that final metrics come from the full actual journal and include warmup exactly once; forecast scores are incremental and must not be summed. Search host time does not advance this offline physical timeline. The controller-specific `RecedingHorizonControllerTest.completedWarmupIsIncludedOnceInActualFinalMetrics:105-111` separately checks one PREPARE and inherited two-key accounting on its smaller fixture; its helper uses 200000 nodes and no elapsed search limit (`:295-297`). That smaller test is not the source of the quoted X tuples.

## Separate prescribed-plan test

`HorizonWitnessIntegrationTest.executesFixedPlansAgainstIndependentEnumerationIncludingWarmup:19-52` executes `SimulationEngine.run(HorizonWitness.scenario(sequence, plan))` for X/NEXT, X/MULTI, Y/NEXT, Y/MULTI and X/STAGED. It compares actual metrics with the saved independent enumeration and checks the one warmup. `HorizonWitness.scenario:59-70` supplies those fixed action plans. This test does **not** invoke ExactHorizonPlanner and therefore has no COMPLETE or planner-budget assertion. It validates the prescribed-plan simulation/accounting independently of the automatic-controller test.

## Saved final-v2 evidence and identity

- `target/final-research/final-v2-build/target/surefire-reports/TEST-org.contactaware.security.simulation.ControlledWitnessIntegrationTest.xml:2,64-66`: one test, zero errors/failures/skips; the named automatic test and console output containing both matching X results at t=85. X/Next reports 162 decisions/73716 total explored nodes; X/Multi 162/50100. These totals are distinct from the per-decision cap.
- The corresponding `HorizonWitnessIntegrationTest.xml:2,66-70` records 12 passing tests, including the five fixed-plan parameterizations. `RecedingHorizonControllerTest.xml:2,75` records 16 passing tests and the warmup accounting test.
- `target/final-research/final-v2-build.log:84-90,130-131,174-175,203,243` records these classes, all 478 tests passing, and BUILD SUCCESS. This is evidence from the completed freeze build, not a fresh rerun during this review.
- Current hashes for ControlledWitnessMain, HorizonPlannerMain, HorizonWitness, RecedingHorizonController, SearchLimits and the three named test source files all match their entries in `target/final-research/final-v2-sources.json`. The inspected assertions therefore match the saved final-v2 source identity rather than relying only on an older test name.

## Documentation disposition

`docs/final-research/regression-diagnosis.md:31-35` accurately distinguishes the prepared automatic exact controller from the autonomous bounded X5 campaign, including the warmup, limits, whole-execution assertions and saved build provenance. `docs/paper-final/main.tex:211` is also supported. Optional precision: call it an **automatic exact receding-controller reference after a prescribed common PREPARE**, and state **2000000 nodes per decision with no search wall-time deadline**. Keep the historical initial provisioning outside the charged [-2,85] window clear if expanding the accounting explanation. No change to claimed tuples or test conclusions is needed.

Owner/status: final_output_coverage — DONE; no further source/test work requested by this check.
