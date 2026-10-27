# Final output coverage review

Static review on 2026-10-25. Scope: final report/figure writers, fixed registration, timing definitions, and only the directly needed analyzer field mappings. This note is the sole change. No JVM, campaign execution, checkpoint, run journal, generated numeric table, or render was read/run. The parent's supplied progress (50 attempted/audited cases, case 051 active) is context, not independently verified here; later cases remain unknown. The frozen JAR and 108 registered attempts are unchanged.

## Findings requiring a small output change

1. **P2 — Recovery rows need their execution status and a prefix guard.** `scripts/write_final_research_report.py:93-99` labels rows by incident/recovery state but omits execution status and prints milestone statuses/seconds without checking FINISHED. `scripts/analyze_final_research.py:92-93` can export observed recovery milestones for an unfinished controller export; final security fields are separately unavailable (`scripts/analyze_research_validation.py:97-98`). Thus an unfinished export can show a numeric or `censored` milestone beside `UNAVAILABLE`, with no local explanation that it is only a prefix. This is a latent output path, not an observed security result. Add an execution-status column and use I/unavailable for non-FINISHED milestones, or explicitly label every such value as prefix-only. Reserve final censoring for FINISHED rows. The plot's completion guard at `plot_final_research.py:109-115` already handles this correctly.

2. **P2 — Actual directional delivery and complete-frame totals are absent from the delivered report/table interface.** The writer's service cells contain only the five scores (`write_final_research_report.py:45-51`); its security table gives exposure and confidential delivered bytes but neither actual delivered UL/DL nor complete frames (`:93-99`). Analyzer rows export aggregate `deliveredBytes` but no directional delivery/frame totals (`analyze_final_research.py:85-91`). The audited source already retains directional bytes by contact and delivered-frame flags (`confidential_service_metrics.py:19-20,33-40,49-58`). For FINISHED rows only, aggregate those existing records into delivered UL/DL bytes and complete-frame counts, and expose them in a compact table with demand/ceiling references. Keep stopped totals unavailable or explicitly partial. Label exposure as **decryptable emitted bytes** and state that aborted prefixes mean exposure and confidential delivery are not a partition of delivered bytes. No replay or model change is necessary.

3. **P2 — The final plotting entry point does not enforce its final/audited precondition.** `plot_final_research.py:18-20` accepts whatever tables/protocol currently exist; `:43,74,90,111` maps any non-FINISHED row, including NOT_RUN, to I. The saved provenance nevertheless says all values are audited final rows (`:117-118`). Unlike the report's explicit attempt/PASS guard (`write_final_research_report.py:22-24`), it can publish a partial-campaign figure set that collapses unattempted and attempted-incomplete cases. Before creating files, require current validation PASS, exact registered case coverage, every registered attempt accounted for, and no NOT_RUN; bind validation to the loaded protocol and table/audit identities. A separate explicitly interim output would need distinct labels and destinations. This finding does not imply existing figures were produced prematurely.

## Coverage already present and closed findings

| Requested evidence | Current output behavior |
| --- | --- |
| Five scores | Full Markdown core and noncore tables; service/cost plots jointly cover all five. Paper fragment intentionally shows two components and points to complete supplement CSV. |
| Security and recovery | All ten registered security rows appear; four recovery stages retain status plus time. Plot recovery scenarios come from configured detection, not successful outcomes. The report prefix guard above is the remaining issue. |
| Interrupted operation and partial resource cost | Management rows report actual interrupted action counts, aborted frames, partial CPU and bytes (`write_final_research_report.py:100-107`); fixed intervention and fail-closed interpretation are stated. |
| Measured delay | Fifteen measured ISS rows appear in the generic noncore table and measured service plot. `decisionHostSeconds`, disposition counts and deadlines exist in runs CSV; adding charged decision delay to the readable table would make the attribution clearer than controller wall time alone. |
| Sensitivity | All nine rows appear, and explicit baseline/factor deltas are linked (`write_final_research_report.py:81-86,109`; analyzer `:147-164`). H3 remains outside the H1/H2 pair denominator. No sensitivity or management figure exists; neither is required if tables/text retain every registered cell. |
| Computational effort | Process runtime plot includes unfinished attempts; report directs readers to CSV for search counts, incomplete searches, nodes, deadlines and checkpoint cost. Timing definitions distinguish modeled CPU from host/controller/search/charged-delay intervals and disclose mismatched aggregate CPU/GC sampling. No unsupported utilization ratio is introduced by these writers. |
| Provenance | Each figure saves its selected case IDs, status and journal path, plus table/protocol hashes (`plot_final_research.py:23-27,117-118`). This identifies plotted source cases; repeated IDs reflect repeated metrics, not extra runs. Report provenance hashes tables, pairs and audit (`write_final_research_report.py:130-131`). Strengthen the precondition above; no missing case-ID provenance finding remains. |

The previous analysis-review findings for registration-derived recovery coverage, plot censoring, missing exposure imputed as zero, visible service/measured I legends, and watchdog labeling remain **closed** in the inspected plot snapshot. Do not reopen them. One small standalone presentation omission remains: the exposure figure prints I (`plot_final_research.py:90`) but its own legends (`:92-93`) do not define it. Add the same I = unavailable final metric legend used elsewhere.

The final paper insertion is intentionally narrower than the Markdown report: `write_final_research_report.py:116-129` generates only a compact core table and coverage statement. It does not insert noncore results or the six figures. `docs/paper-final/main.tex:207-208` includes that fragment, while its `optionalfigure` macro currently has no calls. Final manuscript integration must therefore explicitly add the measured/security/sensitivity/interruption results and selected figures; generating the fragment alone does not complete those Results requirements.

## Verification boundary and source identities

Checks performed: selective source reads, fixed protocol case-count inspection, comparison with prior resolved findings, and SHA-256 capture. No factual campaign outcome or rendered-layout assertion follows. Final output generation must wait for the parent's completed audit; inspect actual figure labels, overlapping recovery symbols and clipping after rendering.

| Source | SHA-256 |
| --- | --- |
| `scripts/write_final_research_report.py` | `00cf7aa135bcf5cf835736711e71fe62426382fddd1d20b33d7a9f4e8f2ffdc6` |
| `scripts/plot_final_research.py` | `a24adb363e724a1c98ddb09b535b43333d86ab953a2d58665d071366098601f0` |
| `scripts/analyze_final_research.py` | `2b5cb83c93734bc9bc114cb31c8483474b320b47e997050153089b1fb403ddea` |

Owner/status: final_output_coverage — DONE (review note); parent — pending minimal output fixes and eventual final generation/render/manuscript integration.

## Follow-up: output findings closed after minimal fixes

Source-only follow-up on 2026-10-25 inspected only the changed analyzer/report/plot areas. **All three actionable findings above are closed in the snapshots below.** The original findings remain as history, not an outstanding work list.

- **Recovery prefix guard — closed.** `write_final_research_report.py:97-106` adds execution status, explains prefix evidence, and emits unavailable rather than final numeric/censored milestones for every non-FINISHED row.
- **Directional delivery — closed.** `analyze_final_research.py:17-32` projects only FINISHED audited records into directional delivered byte/frame counts and confidential bytes, cross-checking contact sums against delivered-frame records and the existing confidential total. The projection is invoked at `:104`; no prefix receives final totals. `write_final_research_report.py:49-51` identifies these columns in runs CSV for all blocks; `:107-119` adds a readable security/management traffic table and explains the distinct emitted/delivered populations. Core and measured delivery remain in the linked CSV rather than extra wide report tables.
- **Final figure precondition — closed.** `plot_final_research.py:19-24` now requires PASS, 108 registered/attempted rows, exact case membership, no NOT_RUN, and matching table/audit/summary/protocol hashes before making the output directory. `analyze_final_research.py:36,187-189` hashes the exact summary bytes actually parsed, avoiding attribution to a subsequently advanced summary. Report generation checks those same identities at `write_final_research_report.py:23-26`.
- **Exposure I legend — closed.** `plot_final_research.py:98-99` now defines incomplete/unavailable final exposure in the standalone legend.

Remaining caveats are delivery steps, not new source defects found in this follow-up: the parent must finish the registered attempts and audit before final generation; inspect the actual rendered figures; integrate noncore results and figure calls into the manuscript. The readable noncore table still uses controller wall time while charged decision-delay details remain in CSV and timing definitions. This is disclosed, but final measured-results prose should use the correct interval. Pair/factor data and the report's outcome claims still need the normal final artifact verification; this static follow-up does not validate their numeric contents.

| Follow-up source | SHA-256 |
| --- | --- |
| `scripts/analyze_final_research.py` | `cf9840355293639006c3a7cebfba4390c6d95a4db0718eefd2c595f5d105fa18` |
| `scripts/write_final_research_report.py` | `777b455f4610a363db92942762a06491561261769e26dd3a545eeef3894d2417` |
| `scripts/plot_final_research.py` | `ff54c68b268f2edb469245a22406e82c2e2598ddf420476f4b1ffcc1885635d6` |

Follow-up checks: source inspection and recorded file hashes only; no JVM, results/checkpoints, analyzer invocation, figure generation or invented data. Owner/status: final_output_coverage — DONE; parent retains final campaign/audit/render/manuscript integration.
# Root runtime follow-up

After the bounded static review, invoking both writers against the actual unfinished campaign
found a Python local-name error: loop variable `load` shadowed the imported JSON loader in
both writer functions. It was renamed to `workload`. Both entry points now reach their gates
and reject the 51-attempt matrix before producing outputs. The new directional aggregation
passed on 47 audited complete executions; unfinished rows have no final directional totals.
Frozen production/test source and JAR hashes still match. The check is recorded in
`target/final-research/output-coverage-validation.json`; actual full rendering remains pending.

