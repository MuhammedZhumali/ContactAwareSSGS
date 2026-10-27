# Stopped-run quality isolation review

Checked 2026-10-25. Scope: the new stopped-run branch in `analyze_final_research.py`, its existing `checkpoint_prefix` helper, and current report/plot consumers. Only terminal case `028-core-ISS46-medium-offline-multi-H2-canonical` output metadata was inspected. No checkpoint contents, active run files, factual journals, JVM or experiments were read/executed.

**Finding: no final-quality or completed-pair leakage found for this stopped-run path.** This is a static flow check with limited terminal metadata confirmation, not a full audit of the saved physical prefix.

## Evidence

- The terminal [process record](../../target/final-research/runs/028-core-ISS46-medium-offline-multi-H2-canonical/process.json) reports `PROCESS_TIMEOUT`, 600.047 seconds elapsed under the 600-second external limit. The directory has no `result.json` or final exports. Its [invocation](../../target/final-research/runs/028-core-ISS46-medium-offline-multi-H2-canonical/invocation.json) identifies practical H2/CANONICAL, the medium ISS model, and the registered limits.
- [analyze_final_research.py:43](../../scripts/analyze_final_research.py) enters the stopped branch, calls `checkpoint_prefix`, explicitly sets `fiveStatus='UNAVAILABLE'`, appends the row and continues before any final metric assignment. Original `PROCESS_TIMEOUT` status is retained.
- [analyze_pc2.py:20](../../scripts/analyze_pc2.py) returns `five=None` and a nested `prefixFive`, not top-level `servedContacts`, `usefulSecureTime`, `controlCpu`, `managementBytes` or `newKeys`. Pending-operation accrued costs remain inside that explicitly partial diagnostic object.
- Analyzer lines 52-54 store this evidence under `stoppedRuns`, with an explicit no-final-quality/no-full-audit scope. It is not added to `runs=evidence` or `audited=len(evidence)`. Invocation/process hashes and existing checkpoint/write-log hashes are recorded separately.
- Analyzer lines 137-144 compute a pair outcome/deltas only if both statuses are FINISHED. Case 028 therefore makes its Next/Multi pair `INCOMPLETE_PAIR` without objective outcome. Factor comparisons have the same completion guard at lines 154-157.
- [write_final_research_report.py:18](../../scripts/write_final_research_report.py) returns the process status for unfinished quality cells. Seed comparisons are gated at lines 57-60; the paper table emits I at line 109. Coverage explicitly says “audited exported runs” and excludes prefix scores from completed comparisons (lines 40-43).
- [plot_final_research.py:21](../../scripts/plot_final_research.py) defines `done` as FINISHED. Service/cost cells show I rather than prefix bars; runtime can show actual process elapsed time with an incomplete marker. No plot reads `prefixFive`. A stopped security row would similarly receive I/unavailable, not inferred zero exposure/recovery.

The last line of this terminal case's checkpoint-write diagnostic log parses as JSON and reports time `2026-10-10T20:59:18.699615479Z`, decision count 665. This is only a log-tail check; it does not establish the checkpoint's internal consistency or validate all log lines. Those checks remain the main audit's responsibility.

## Reviewed identities

| File | SHA-256 |
| --- | --- |
| `scripts/analyze_final_research.py` | `2b5cb83c93734bc9bc114cb31c8483474b320b47e997050153089b1fb403ddea` |
| `scripts/analyze_pc2.py` | `69dc6cf02839da78d9748625b755811bde206316ebf38fdf83c2550413076d93` |
| `scripts/plot_final_research.py` | `a24adb363e724a1c98ddb09b535b43333d86ab953a2d58665d071366098601f0` |
| `scripts/write_final_research_report.py` | `3b50888658ccd24a0c31de38631cdb5ea1a3a1ffb063196bd6e5a96966d8d9f5` |
| Case 028 `process.json` | `522e71afdb38156a51389ffc0df504871b44e513a2f37930936dd93b4358e4a1` |
| Case 028 `invocation.json` | `e9065c54fa8cd3ddf0fae497373e9547fab714b78e5aadf9b543ab088184474c` |

No source changes are requested by this review. The final analyzer still needs to run after the campaign, and actual output/render checks remain necessary. Do not describe this timeout as a policy-quality loss or its saved prefix as a final service result.
