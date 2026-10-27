# R1 — issue matrix (2026-10-25)

This is an execution ledger, not a declaration that caveats close engineering gaps.
Latest supplied article: `C:/Users/Muhalek/Downloads/Contact_Aware_Research.pdf`.
Frozen original: `target/research-validation/Contact_Aware_Research.pdf`.
Base commit: `3621333d8093b4cb0c232c7db9164c66e273053c`, dirty tree recorded before changes.
Final dispositions and measured counts are in `../../RESEARCH_VALIDATION_RESULTS.md`.

| Issue | Article / present evidence | Actual implementation and evidence | Required change / closure criterion | Initial status |
| --- | --- | --- | --- | --- |
| Search and scale | 21/42 complete pairs; high-demand Multi incomplete | `docs/research-synthesis/validation.json`, SE2.5 telemetry; exact DFS with finite bounds, memo, guarded suffix reuse | Profile; semantics-preserving acceleration verified against exhaustive full-plan oracle; bounded practical mode separately labelled; rerun failed families | OPEN |
| Horizon benefit | Conditional short-window effect, not general superiority | X5 both rules; X9 CW equal; ISS LIGHT equal service; `docs/research-synthesis/mechanisms.md` | Preserve five criteria, both rules, complete paired executions and negative controls; incomplete never counted as a loss | PARTIALLY_CLOSED |
| Disclosure/recovery | Traffic-key disclosure and independent replacements under protected management assumption | `TrafficKeyDisclosure`, `IncidentResponse`, `SecurityMetricsCollector`, Python four-stage and confidential-service postprocessors | Regression and additional registered detection delays/undetected/gap controls; no claims about persistent root compromise | PARTIALLY_CLOSED |
| Channel / forecast errors | No physical interference model | `OperationOutcomes` can interrupt selected operations/drop ACK; no separate forecast/actual availability model | Controlled simulator outage and recovery, early LOS/delayed AOS/missed contact, no future leak; partial charges and uncertainty tested | OPEN |
| LEO workloads | Selected ISS fragments / several days | Original 46 contacts, other saved days; same ISS epoch, no other validated missions | Continuous stateful sequences, fixed selection rules, suitable public orbit snapshots for two other spacecraft if available | OPEN |
| Costs and load | Synthetic cost profile | `SecurityCostProfile`, whole-frame quantization; old LIGHT has finite exhausted demand | Parameter provenance and compact registered sensitivity; host crypto timing must not be called handshake/onboard timing | OPEN |
| Compute deadlines | Runtime excluded from modeled clock | `SecurityPolicyController`, nested `SearchControl`, process watchdog | Separate offline reference and explicit delay/deadline/fallback mode; report search and execution completion separately | OPEN |
| Reproducibility | Historical mixed versions | Preserved manifests/JARs/journals, untracked local data and newer PDF than repository TeX | Fresh source snapshot/build, hash chain, immutable protocols, explicit data-restoration requirements | PARTIALLY_CLOSED |
| Related work / paper | Latest PDF is newer than available TeX sources | Repository `docs/paper-2026-10-19/main.tex` is an earlier draft; source checks separate | Verify primary sources including Detke et al.; update a separately identified R1 manuscript, compile, trace numbers | OPEN |

## Contracts checked directly in code

- FixedPeriod is activation-relative: `FixedPeriodPolicy.lastSuccessfulActivationAt` uses successful paired key `activatedAt`; due = activation + T. Failed PREPARE/INSTALL do not reset the clock. It is not a calendar-epoch policy.
- H is future contacts beyond current. `PlanningObservation.from`: inside a contact, current + H; in a gap, H nearest. Every exact invocation fixes `evaluationEnd` and visible inputs.
- Both tie-breaks apply only after all five criteria. `PlanningResultComparator`, `PlanTieBreak`; traversal is a separate `PlannerOptions` setting.
- Existing defaults retain nominal future acknowledgements, material/counter constraints and a single spare pair. Prior guarded suffix reuse is supported only after a completed frame with matching full observation and rules.

## Ownership / execution status

- Main: baseline, changes to practical execution, experiment registrations/runs, integration, final evidence and README.
- `r1_search_diagnosis`: narrow exact aggregate optimization and differential tests; no experimental JVM.
- `r1_paper_sources`: article and primary-source audit only; no code/build.
- One heavy JVM at a time. All new evidence goes under `target/research-validation/`.

## Final integration disposition

All assigned subtasks have returned; integration, serial experiments and artifact checks are complete. R1 retains 181 post-change attempts (135 FINISHED), 55/84 complete selected horizon pairs and a separate 12-attempt original-source baseline. The final issue-by-issue statuses are recorded in [RESEARCH_VALIDATION_RESULTS.md](../../RESEARCH_VALIDATION_RESULTS.md): the nine broad groups remain partially closed except reproducibility of the saved R1 evidence, which is verified. Full-sequence scalability, distributed reconciliation, mission-calibrated costs and outstanding primary-literature access remain open. The historical 3361-second stall was not reproduced. Neither guarded reuse nor the journal-fold change is reported as a demonstrated speedup.

Validation artifacts identify 423 passing candidate Java tests, 74 overlapping practical tests, 10 export-repair tests and 67 Python tests. These counts are not additive. The compiled main paper has eight pages, its supplement four; six figures retain table/journal provenance. The archive preserves executable/source identities and required local fixtures, while the original workspace's 4195 protected historical files remain byte-identical.
