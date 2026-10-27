# Stage 1 interim result

Independent event accounting and full initial snapshot/observation equality passed for every paired full run. This is a new campaign, not replacement of final-v2.

| Initialization | Solver | H | Tie rule | Status | Served | Useful s | CPU s | Bytes | Keys |
| --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |
| prepared | exact | 1 | CANONICAL | FINISHED | 2 | 38.000000000 | 0.320000000 | 160 | 2 |
| prepared | exact | 1 | CONTINUE_WAIT | FINISHED | 2 | 38.000000000 | 0.320000000 | 160 | 2 |
| prepared | exact | 2 | CANONICAL | FINISHED | 3 | 37.000000000 | 0.830000000 | 1240 | 2 |
| prepared | exact | 2 | CONTINUE_WAIT | FINISHED | 3 | 37.000000000 | 0.830000000 | 1240 | 2 |
| prepared | practical | 1 | CANONICAL | FINISHED | 2 | 38.000000000 | 0.320000000 | 160 | 2 |
| prepared | practical | 1 | CONTINUE_WAIT | FINISHED | 2 | 38.000000000 | 0.320000000 | 160 | 2 |
| prepared | practical | 2 | CANONICAL | FINISHED | 3 | 37.000000000 | 0.830000000 | 1240 | 2 |
| prepared | practical | 2 | CONTINUE_WAIT | FINISHED | 3 | 37.000000000 | 0.830000000 | 1240 | 2 |
| autonomous | exact | 1 | CANONICAL | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |
| autonomous | exact | 1 | CONTINUE_WAIT | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |
| autonomous | exact | 2 | CANONICAL | SEARCH_INCOMPLETE | — | — | — | — | — |
| autonomous | exact | 2 | CONTINUE_WAIT | FINISHED | 3 | 37.000000000 | 0.830000000 | 1240 | 2 |
| autonomous | practical | 1 | CANONICAL | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |
| autonomous | practical | 1 | CONTINUE_WAIT | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |
| autonomous | practical | 2 | CANONICAL | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |
| autonomous | practical | 2 | CONTINUE_WAIT | FINISHED | 2 | 38.000000000 | 0.120000000 | 160 | 0 |

Paired-root details including first strict metric improvement and diagnostic-only oracle replay are in `target/aitu-revision/stage1/root-tables.csv`. Incomplete roots are not proven optima. Full execution scores cannot be inferred by summing rolling-horizon root scores.

## Interpretation

Common preparation removes the observed exact/practical service gap: both Multi modes serve three contacts under both tie rules, while both Next modes serve two. Thus the old prepared-exact versus autonomous-practical contrast alone did not isolate search.

At the autonomous t=0 H2 root, both actual controllers have the same permitted observation as the separately replayed diagnostic. The first incumbent with a strictly better five-score tuple than the ordinary practical winner is discovered at node 134269 (about 8 seconds in these instrumented diagnostics), beyond the practical allowance of 5000 nodes and 1 second. CONTINUE_WAIT proves COMPLETE at node134638. Its entire plan passes ordinary BranchEngine admissibility and ContinuationEvaluator, and the separate diagnostic portfolio accepts it as FEASIBLE_RETAINED and ranks it above the ordinary practical candidate. This is evidence of a continuation not found within the practical search budget, not an alternative objective or rejected action chain. The diagnostic oracle is never provided to the ordinary controller.

The ordinary practical decisive-root searches actually stop at the 5000-node cap, before their1s timer; the node limit is the observed stopping cause. The CANONICAL root finds a three-contact incumbent and subsequently improves full-plan tie order, but fails to prove the selected optimum before30s; its exact full controller consequently stops. A service-improving incumbent is not proof of the full lexicographic optimum. The CONTINUE_WAIT exact controller finishes and serves three; autonomous practical serves two under both rules. First-discovery host times are instrumented observations, not universal thresholds. Node discovery depends on the declared traversal and this state.

Snapshot comparison preserves every field and all event/plan ordering. Only Java Set fields are normalized for JSON order; authorizedContexts serialized A/B in different order across JVMs despite equal set values. No semantic state field is omitted.

Denominator: {"plannedNewAttempts": 28, "attemptedNew": 28, "newStatuses": {"COMPLETE": 11, "INCOMPLETE": 1, "FINISHED": 15, "SEARCH_INCOMPLETE": 1}, "reusedCells": 0, "notRun": 0}
