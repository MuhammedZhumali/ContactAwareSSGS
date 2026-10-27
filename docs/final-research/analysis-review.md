# Final analysis and figure static review

Review date: 2026-10-25. Scope: `scripts/analyze_final_research.py`, `scripts/plot_final_research.py`, and their directly used journal, recovery, comparator, registration and export contracts. Only this report was written. No experiment, Java process, checkpoint, factual run journal, or large result file was read or executed. Findings describe source behavior, not observed campaign outcomes.

The analyzer was revised by the main agent during review. The follow-up read inspected SHA-256 `f494006143b5245af4dbdb3a973404b2e46a175fa6043537a2a4e58c9a1ada25`; the initial plot snapshot was `505f771aac0142e670c1ef4fa1c7d9a8e2d45c750c3c47e8d68782604be3a1ae`. Original-finding line references below identify those snapshots.

## Follow-up status: all reported static findings resolved

A second source-only read on 2026-10-25 checked `plot_final_research.py` SHA-256 `a24adb363e724a1c98ddb09b535b43333d86ab953a2d58665d071366098601f0`. The three P2 changes and the P3 legend changes are implemented:

- **Recovery coverage, resolved:** current lines 97-102 select scenarios by configured `disclosures[].detectionAt` from each registered model, independent of observed `groundNoticeAt` or FINISHED status. Incomplete registered detected scenarios therefore remain in the figure loop.
- **Recovery status, resolved:** current lines 108-115 check full-run completion before assigning C, render non-FINISHED as I, and distinguish `not-applicable` as N/A from U. The title defines these states. Numeric recovery remains restricted to FINISHED rows with a value.
- **Exposure zero imputation, resolved:** current line 88 uses direct indexed fields and numeric conversion. Missing keys or null values now fail instead of producing a measured zero; actual numeric zero remains valid.
- **Standalone legends, resolved statically:** current lines 45-47 and 77-78 define I in the service/cost and measured figures; lines 58-60 label the process watchdog.

No reported static finding remains open in these reviewed snapshots. This is not rendered-layout approval or a claim that the campaign audit completed. Actual legend fit, marker readability, panel bounds and clipping still require inspection after rendering. Later source changes require their own check.

## Original findings and requested fixes (resolved above)

### [P2] Derive recovery coverage from registration, not successful observations

**Location:** [plot_final_research.py:92](../../scripts/plot_final_research.py), recovery scenario selection.

`recovery_scenarios` includes a scenario only if some row has `groundNoticeAt`. The analyzer exports `groundNoticeAt` only from `checked['security']`, and the shared auditor returns that object only for FINISHED executions (`analyze_research_validation.py:98`). Consequently, a registered detected-disclosure scenario disappears from the figure if both policies are incomplete, even if an observed prefix contained ground notice. Both finished NO_TARGET cases also disappear without an explicit coverage statement. This is outcome-dependent figure coverage, not a numerical zero, but it hides a class of unsuccessful/missing observations.

**Fix:** derive the intended detected-disclosure rows from registered model/incident metadata, independently of result status. Render all registered cells and distinguish incomplete/unavailable and not-applicable/no-target. Retain their case IDs in figure provenance. If an intentionally narrower complete-case chart is chosen, state the exact excluded count and selection rule visibly.

### [P2] Require completed execution before labeling a recovery milestone censored

**Location:** [plot_final_research.py:98](../../scripts/plot_final_research.py) through the next two lines.

The numeric branch checks `done(r)`, but its fallback labels any milestone with `status == 'censored'` as `C`, irrespective of full-run status. `recovery_metrics_r1.measure` accepts `run_status` but initializes missing milestones as censored at the observed-prefix endpoint (`recovery_metrics_r1.py:37,87`) and does not convert them for incomplete executions. Therefore a partial run can be displayed as a censored completed-experiment milestone. Separately, `not-applicable` is currently collapsed into `U`, although undetected/no-target is a defined condition rather than unavailable evidence.

**Fix:** branch on execution completion first: non-FINISHED becomes I/U; only a FINISHED missing milestone is C. Use N/A for `not-applicable`, with its reason available in the table/provenance. Keep all numeric values conditional on completed execution if that remains the figure's stated contract.

### [P2] Reject absent exposure values instead of converting them to zero

**Location:** [plot_final_research.py:83](../../scripts/plot_final_research.py).

`float(r.get('decryptableBeforeBytes') or 0)` and the corresponding after-disclosure expression convert missing fields or JSON null into measured zero. With the current successful auditor, every valid FINISHED security row does have numeric exposure fields, so this review found no demonstrated corrupt current row. Nevertheless, the plotting path violates the explicit no-imputation contract if a table is incomplete/stale or a field is removed: it draws a zero-exposure bar rather than failing or marking unavailable.

**Fix:** for FINISHED rows assert that both fields exist and are non-null before plotting; preserve actual numeric zero. Alternatively mark unavailable and record the reason. This should not turn NO_ATTACK/NO_TARGET into missing data: their audited numeric exposure can legitimately be zero, accompanied by `recoveryStatus`/incident status.

### [P3] Explain incomplete markers and watchdog line in standalone figures

**Locations:** [plot_final_research.py:43](../../scripts/plot_final_research.py), line 70, and [plot_final_research.py:55](../../scripts/plot_final_research.py).

Service/cost and measured-delay figures print `I` but their own legends do not explain it; the meaning exists only in the saved provenance. The runtime figure's dotted process limit is also unlabeled. Detached PNG/PDF exports therefore lose these definitions.

**Fix:** add a compact visible legend/note for I = unavailable final quality and identify the dotted external-process watchdog. Keep I distinct from a valid zero bar. This is a presentation clarification, not a claim that values are wrong.

## Issues addressed by the main agent during review

1. **Attempt and execution identity:** the initial analyzer built an ID dictionary without duplicate/unknown checks and did not verify final result status/configuration against the registered case. The revised lines 18-23, 30-33 and 45-54 now check unique registered/attempt IDs, registered membership, case metadata, JAR/model hashes, invocation protocol hash, result status/mode/horizon/rule, physical-time mode and exact configuration. This closes the identified static gap for completed exports. It is not a claim that the final data audit has run.
2. **H3 sensitivity omission:** the initial pair builder silently skipped the single H3 sensitivity cell. Revised lines 121-124 and 134-156 retain its unpaired identity and build explicit factor comparisons against the same policy's core baseline, including H3 versus H2. The current fixed registration makes these reference selections unambiguous. The H3 cell must not enter the H1/H2 matched-pair denominator.

## Checks with no defect found in this scope

- The five-component quality rank (`analyze_final_research.py:13-15`) matches `PlanningResultComparator.compareMetrics`: maximize service, maximize useful time, minimize operation CPU, management bytes and new directional keys.
- The reused `analyze_pc2.result_key` matches the complete CANONICAL/CONTINUE_WAIT action-tuple ordering for the current valid nonempty identifiers, including nanosecond timestamps and proper plan prefixes. Its result is a comparator check on exported candidates, not an independent proof that every candidate was globally optimal.
- The shared journal auditor returns final five/security values only for FINISHED results and verifies their equality with actual/exported metrics. The final analyzer does not use prefix five as final quality. Current pair deltas are calculated only when both executions finish.
- `measurement.elapsedNanos`, nullable `threadCpuNanos`, GC time, search telemetry, `deadlineMiss`, measured `hostNanos`, and portfolio `source` match the current Java export fields. FixedPeriod/PerContact legitimately export empty practical-search/portfolio arrays; zero search count is not an absent completed-run service score.
- Current core plot lookups are unique because the fixed registration has one CANONICAL offline cell per mission/load/policy. The measured subset is explicitly ISS46 with the first five policies, so its current selection agrees with registration. Expanding the registration to more rules/missions would require stronger lookup keys/uniqueness assertions.
- Exposure colors and before/after hatching use separate legends. A zero audited exposure for NO_ATTACK/NO_TARGET is distinct from the null-to-zero issue above; final captions must retain that incident interpretation.
- There is no figure for the sensitivity/management blocks in this snapshot. Those registered outcomes remain in rows and, after the main agent's fix, sensitivity factor tables. Final Results must cover them in tables/text rather than imply that the six figures display every registered block.

## Validation boundary

This was source inspection and cross-checking of API field names/semantics only, followed by a source-only verification of the implemented fixes. No runtime or rendered-figure claim follows from it. Before delivery, run the analyzer on the completed immutable campaign, check retained failures/not-run coverage, render all figures, and inspect labels and scale clipping. Experiment outcomes and timing comparisons remain the main agent's responsibility.
