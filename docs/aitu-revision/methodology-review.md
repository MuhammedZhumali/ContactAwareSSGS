# Bounded independent methodology review

Date: 2026-10-28. Scope: source/document inspection only; no JVM, experiment, test, or modification of historical results. This note proposes a registry before new outcomes are examined. The parent owns implementation and registration. Source references below are repository-relative.

## 1. Initialization is a real confound, with additional implementation differences

The prepared X reference is an automatic exact receding controller **after** prescribed common PREPARE(-2,0), with its completed journal and two created directional keys retained. Its saved whole-execution tuples include that preparation exactly once (`docs/final-research/prepared-reference-check.md:9-28`). The saved reference allows 2,000,000 nodes/decision, no elapsed search deadline, `PlannerOptions.accelerated()`, and disabled controller continuation reuse (`:9-11`). The current autonomous runner instead directly calls `SimulationEngine.start(scenario)` (`ResearchValidationMain.java:47`), uses `acceleratedServiceFirst()` (`:32-33`), and enables controller continuation reuse (`:70`). The practical campaign additionally has seed/retention/search budgets and a portfolio (`docs/final-research/protocol.md:45`; `PracticalPlanPortfolio.java:63-153`).

Thus old X versus autonomous X5 does not isolate the optimizer. New within-block comparisons should share the exact snapshot, controller implementation/reuse setting, traversal options, timing model, and allowed observations. Their deliberate difference is exact versus bounded candidate selection and its declared search allowance. Block A hands the same actual post-PREPARE snapshot to both. Block B hands the same autonomous snapshot with no spare pair to both. Record the exact physical origin of each block; if both are compared at t=0, advance the unprepared branch idly to t=0 rather than silently removing the [-2,0] accounting interval. In A, retain warmup in final actual metrics and subtract it only through the common incremental evaluator when evaluating root continuations.

Root comparisons require equal H and endpoint; whole-policy comparisons use the same entire physical schedule. Never rank H1 and H2 root objective values against one another. The saved final campaign already found four completed core pairs tied and five incomplete (`docs/final-research/report.md:3-5`); none may be rewritten as a new-run result.

## 2. Minimal diagnostic evidence

At each declared decisive root, save:

1. Full immutable execution snapshot, its canonical content hash, and journal hash; separately save the permitted `PlanningObservation` and evaluationEnd. The full snapshot must never become planner input. Check exact equality between paired controllers, including costs, completed payload, in-flight work, queues, reservations, notice state, and issued IDs. `PracticalPlanPortfolio.anchorMismatch` already enumerates many pertinent observable fields (`:159-175`), but its permitted horizon growth is inappropriate for a same-H equality assertion.
2. Generated admissible actions, complete model/constraint identities, traversal options, tie rule, key-ID allocation inputs, and candidate order. Generation is stateless; PREPARE derives IDs from issued/current/blocked IDs (`SecurityActionGenerator.java:8-15,27-35`). Preserve reduced traversal candidates separately from the full admissible action set: an exact reduction is not an action permission difference.
3. Every available seed/retained/search terminal candidate, chosen full plan and score, pairwise comparator sign, and decisive criterion. Existing `PracticalPlanPortfolio.Audit` contains candidates, source, root H/end, phase timings/transitions, and retention reason (`:12-18,143-146`). Search status, explored nodes, elapsed time, and stop checkpoint come from `HorizonPlanResult` (`ExactHorizonPlanner.java:307-319`). A source-SEARCH preference after total comparator equality also exists (`PracticalPlanPortfolio.java:126-128`); preserve that provenance rule.
4. Replay a COMPLETE exact plan from a fresh branch created from the **identical** observation, using `BranchEngine.execute` (which revalidates generated-action membership, `BranchEngine.java:18-21`) then `ContinuationEvaluator.evaluate`. Require the declared endpoint and exact equality with the exact score. Compare the replayed result with every ordinary practical candidate using the same `PlanningResultComparator`. Its five priorities and full plan order are explicit (`PlanningResultComparator.java:18-68,82-100`). `ContinuationEvaluator.java:14-15` explicitly excludes a privileged full-run journal from this continuation evaluation.

Interpretation: accepted replay + equal score + better comparator rank establishes that the advantageous continuation was available and preferable at that root but absent from the practical available set; a validation failure, score mismatch, or different comparator result requires a different explanation. It does not establish whole-execution dominance. `offerForReplay` mutates retention (`PracticalPlanPortfolio.java:179-181`); if used, instantiate a disposable diagnostic portfolio, label it oracle-assisted, and exclude its result from policy tables.

The frozen planner does **not** export when an improving continuation was first discovered. The smallest source-level observational addition is a callback at each actual incumbent update (`ExactHorizonPlanner.java:283-287`) recording explored node index, terminal count, elapsed nanos, score, and plan hash; it must never influence ordering or limits. Both terminal and memo-composed continuations reach this point (`:159-175`). A frozen-JAR harness cannot honestly reconstruct this timestamp from final elapsed time. If source instrumentation is out of scope, report it unavailable, or preregister a separate deterministic node-prefix diagnostic that gives an interval rather than an exact first-found timestamp.

## 3. Model-derived X family: nine nominal configurations

Keep C1=A[0,20], C2=B[30,50], C3=A[T,T+L], frame/rate/demand/cost profiles and initial-history timestamps common (`HorizonWitness.java:29-46,54-58`). The two initialization blocks are treatments, not extra nominal geometry configurations. Register the exact subset of block/optimizer combinations to be run and its denominator before execution; use H1/H2 and both tie rules for every declared matched policy comparison.

Witness payload is 100-byte frames at 400 B/s, with minimum 400 bytes in **each** direction (`SimulationContact.java:24-27`): eight complete frames require 2 s. The declared serial chains are:

- already installed PRE_ACTIVE entry: SWITCH(1)+OPEN(1)+payload(2)=4 s;
- merely prepared entry: INSTALL(4)+SWITCH(1)+OPEN(1)+payload(2)=8 s;
- already ACTIVE independent material: REUSE(1)+payload(2)=3 s.

The operation durations follow `SecurityCostProfile.java:94-98,105-109`. The third chain matters: a 3 s window is insufficient for the first two chains but may still be served. Therefore choose L=2,5,9 s, rather than claiming L=3 is universally infeasible. These are sampled regimes, not a newly proved universal threshold. Contact-requiring management must finish **strictly before** LOS, and admission is checked both at start and completion (`SecurityActionGenerator.java:66-80`). Complete payload intervals may end at LOS or key deadline, but cannot start at deadline (`SecurityConstraintChecker.java:63,129-143`). Reserve/phase overlap is also checked (`SecurityActionGenerator.java:82-98`). Actual branch execution, integer frames, key state, and counters must still validate each claimed chain.

| ID | T (s) | L (s) | CP (s) | Material age (s) | Predeclared purpose |
|---|---:|---:|---:|---:|---|
| X01 | 80 | 5 | 150 | 400 | Baseline installed-entry region |
| X02 | 80 | 2 | 150 | 400 | Too short for all three stated entry chains |
| X03 | 80 | 9 | 150 | 400 | Enough duration for both installed/prepared chains |
| X04 | 60 | 5 | 150 | 400 | Earlier return while original A material can remain valid |
| X05 | 180 | 5 | 150 | 400 | Early-activated new material expires; installed pre-active material can survive |
| X06 | 80 | 5 | 180 | 400 | Longer CP preserves original A material through return |
| X07 | 80 | 5 | 90 | 400 | Short CP; original A deadline moves into C1 at 10 s |
| X08 | 180 | 5 | 240 | 400 | Preselected CP/return crossing: early-activated new material survives, old A expires at 160 s |
| X09 | 430 | 5 | 150 | 400 | Every A key created by C1 end has reached its material-age limit by return |

Reuse gap remains 200 s. X07 deliberately alters C1 renewal pressure; it is not a clean late-contact-only intervention. X09 is synthetic elapsed-time sensitivity, not a new orbital campaign. The T=430 choice is deliberate: T=410 would not guarantee expiry for autonomous preparation after t=10, whose creation+400 deadline can exceed 410.

Reconstruct historical state through lifecycle transitions under each configuration, retaining A creation=-90/activation=-80/last use=-60 and B=-40/-30/-20. Do not edit `notAfter` independently. Preparation sets creation+material-age; activation freezes the minimum with activation+CP; subsequent `hardDeadline` remains the minimum of frozen notAfter, creation+configured age, and activation+configured CP (`KeyState.java:41-54,62-66`). Raising CP alone in serialized constraints cannot extend an already frozen deadline. In particular X06 must rebuild initial A deadline=100 and X08 deadline=160 from the same history; otherwise these nominal treatments are not implemented. Record creation/activation/frozen/effective deadlines for every initial and new key.

## 4. Predeclared late-disclosure geometry

Use the frozen late-demand model and its explicitly designated attacked contact/context. Let E be that contact's end and f its payload-frame duration, verified directly from the frozen model before registration. The historical independent SA-to-frame interval is 10 s (`docs/final-research/final-security-check.md:50-51`), consistent with the 1000 B/100 B/s profile documented for the main traffic model (`docs/final-research/protocol.md:35`); this is not a substitute for checking the late-demand input. Choose compromise times E-4f, E-2f, E-f/2 (40,20,5 s before LOS if f=10 s) and detection delays 0, f/5, 2f (0,2,20 s if f=10 s). Verify all three compromise instants are inside the designated contact before accepting the registry; never move them based on actions or outcomes. Expand 3x3 x H1/H2 x two tie rules =36 attempts, with fixed input identities, budgets, scenario end, and target rule.

At each external event disclose the active directional pair in the same preselected context; no valid target becomes NO_TARGET, not zero exposure. Hide all future compromise/notice events from planning observations. The historic pair is useful because Multi's spacecraft notice was later while independent delivery was earlier (`docs/final-research/final-security-check.md:44-57`); a single scalar recovery time conceals that tradeoff.

Record compromise-to-ground notice, ground-to-spacecraft notice, independent activation, independent SA opening, first independent complete delivered frame, and compromise-to-that-frame. Preserve raw event timestamps/sequence IDs, confidential UL/DL delivered bytes, exposed recorded-before/emitted-after traffic, subsequent expirations, and independent readiness intervals. Mark each stage OBSERVED/ALREADY_READY/CENSORED/NO_TARGET/NO_ATTACK as applicable; execution incompleteness is an orthogonal status. Report all 36 attempts and censoring horizons; never average only recovered cases without its denominator and explicit selection caveat. Confidential delivered bytes and decryptable emitted bytes have different counting bases, including aborted prefixes (`docs/final-research/final-security-check.md:30-32,55`).

## 5. Boundaries for final interpretation

This review establishes implementation locations and a source-derived design, not new experimental results. Exact is conditional on COMPLETE within this discrete model; FINISHED is completion of actual execution. Same-root optimality, same-root portfolio dominance, whole-policy quality, and host-delay robustness are distinct claims. Preserve process/time/node stopping separately. No configuration above presupposes a Multi win. The frozen first-found timing limitation and any incomplete exact searches must remain explicit in the paper and closure table.

## 6. Stage 3 follow-up: seed-only must preserve retention

Source-only follow-up, 2026-10-28. The frozen `SEED_WITHOUT_SEARCH` skips DFS (`PracticalPlanPortfolio.java:111`) but also fails both retention guards (`:82,136`). Using it against positive budgets with `SEED_AND_RETENTION` would change two factors. The existing `SEED_ONLY` name is also unsuitable: its search guard still permits DFS (`:9,111`).

The proposed harness-only `SEED_AND_RETENTION_WITHOUT_SEARCH` variant is a justified minimal correction. Change only the enum and three guards: accept the new variant in retained-suffix replay and first-transition prediction, and exclude it from search. Preserve parentheses around the two retention alternatives before their existing `&&` conditions. Preserve seed/suffix limits, transition caps, anchor checks, comparator, fallback, timing accounting, and controller settings. Label the zero-node treatment **seed-only (seed plus retained seed-derived continuations; DFS disabled)**. Use a fresh policy/portfolio per cell, so it cannot inherit a searched or oracle-supplied suffix. A zero numeric search allowance must not stand in for disabling DFS: zero is the unlimited convention in the registered exact machinery.

Minimal validation, to be performed by the parent before the series:

1. Record hashes of the frozen source/JAR and harness copy; inspect a normalized source diff showing only renaming/plumbing, the enum addition, and the three guards. Use a distinct harness class name or isolated classpath. A same-name class earlier on the classpath would shadow the frozen class, making the claim that positive budgets use the frozen portfolio inaccurate. Save loaded class origin for the treatment and controls.
2. In a focused deterministic check, use a planner stub that fails if invoked: the new variant must produce a first seed decision, predict its child, then accept/replay a valid retained suffix at a second matching actual root, with zero planner calls and no SEARCH candidate. Validate the retained terminal result through the existing evaluator/comparator. Do not use `offerForReplay` to manufacture this retention check.
3. Compare the copied ordinary `SEED_AND_RETENTION` path against the original frozen implementation on the same two-root fixture and deterministic planner stub: selected plans, scores, candidates, source and retention status must agree (host timing fields excluded). The 1000/5000/20000/100000-node production cells should still instantiate the original frozen class. Check that the zero-DFS export reports zero search attempts/nodes while retaining seed/suffix/prediction work and host time.

These focused checks verify the new switch and preservation of the comparison contract; they do not establish whole-policy quality or measured-delay equivalence. The independent review did not execute them. Registration must identify this harness extension and the precise interpretation of seed-only before any Stage 3 outcomes are inspected.
