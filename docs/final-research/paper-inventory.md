# Final-campaign manuscript and source inventory

Inspected 2026-10-25. Owner: paper-sources subtask. Scope: read existing manuscript, bibliography, evidence/provenance and export code; identify final-update dependencies. This file is the only change made by this subtask. No JVM, experiment, build, figure regeneration, manuscript edit, or PDF compilation was performed.

## Authoritative revision baseline

The latest repository manuscript is [docs/paper-r1/main.tex](../paper-r1/main.tex), with [r1-results.tex](../paper-r1/r1-results.tex), [supplement.tex](../paper-r1/supplement.tex), [README.md](../paper-r1/README.md), and six figure families. Its README explicitly identifies it as a standalone R1 revision of the supplied article. This is the baseline to carry forward into the final campaign.

Preserve the earlier artifacts:

- [docs/paper-2026-10-19/main.tex](../paper-2026-10-19/main.tex) is an older draft that describes implementation as future work. It is not the current article.
- `C:/Users/Muhalek/Downloads/Contact_Aware_Research.pdf` and `ContactAwareSS_SE25.tex` were audited as different textual versions; the TeX is not established as the PDF's exact compilation source. Identity, hashes and differences are in [article-sources.md](../research-audit/article-sources.md).
- R1 is already a frozen, separately packaged evidence revision. To preserve it, create the final manuscript in `docs/paper-final/` from its source structure and redirect final generators to that destination. This is a proposed destination, not a file created by this inventory.

Current R1 source/PDF hashes match [saved compilation validation](../../target/research-validation/latex/validation.json). That record reports eight main pages and four supplement pages and prior rendered-page review. This subtask rechecked identity only; it does not claim a new compilation or visual review.

## Exact integration surfaces after the final matrix is audited

| R1 source surface | Current location | Final revision dependency |
| --- | --- | --- |
| Abstract | `main.tex:20-22` | Replace the R1 181 attempts / 135 finished / 55 of 84 pairs and its full-workload outcome statements with final registered totals and final bounded conclusions. Keep historical claims labeled if retained. |
| Contribution / comparison | `main.tex:27-59` | Keep the matched action, executor, constraints, costs, objective and full tie-rule boundary; revise empirical scope only from final data. Do not promote contact prediction or lifecycle states to new primitives. |
| Experiment design | `main.tex:179-192` | State all preregistered ISS/NOAA-19/HST load cells, horizon/tie-rule modes, complete chronological schedules, demand definitions, security/disruption subset and budgets. Link the final registration and frozen input/JAR identities. |
| Results | `main.tex:194-216` plus generated `r1-results.tex` | Separate prior synthesis/R1 from final campaign. Generate the final fragment from audited final tables; keep all incomplete/failed/not-attempted cells. Update six figure includes/captions to final files and provenance. |
| Verification | `main.tex:218-221`; `\\RoneTestCount` at line 10 | Replace historical test assertions with the actual final full-suite result and exact source/build identity. Focused historical test counts overlap and must not be added. |
| Discussion | `main.tex:223-230` | Interpret every load and mission, costs, incomplete pairs, search/controller limits, disclosure/recovery, and interruptions; distinguish physical execution completion from search certification. |
| Conclusion | `main.tex:232-233` | Derive from the complete final grid. Remove stale claims about final continuous workloads if new evidence changes them; do not erase their historical R1 outcomes. |
| Bibliography | `main.tex:235-244` | Inline `thebibliography` contains eight entries; there is no separate `.bib` in this manuscript. FHAP and LK-AKA are named in Related Work but have no bibliography entries. Either provide bounded, properly sourced entries in the final revision or remove uncited detailed comparisons. |
| Supplement and README | `supplement.tex`; `README.md` | Record final source/data/JAR/test/attempt identities, matrix inclusion rules, package contents, fresh compile/render evidence and reproduction commands. Keep prior history separate. |

The R1 result writer contains manually authored numeric prose as well as generated table counts. Updating input tables alone will not update all claims. Audit every numeric statement in the final Abstract, Results, Discussion, Conclusion, README and supplement against final tables.

## Existing export and reproduction pipeline

The preserved R1 dependency chain is:

`series protocol + models + frozen JAR -> actual events/checkpoints/results -> analyzer -> tables/pairs/audit -> figures + result fragment/report -> TeX/PDF -> package manifest`.

| Existing file | Reads / writes and relevant limitation |
| --- | --- |
| [run_research_validation.py](../../scripts/run_research_validation.py) | `OUT` is hardcoded to `target/research-validation` (line 7). Records source/build identities and preserves historical inputs. |
| [r1_experiments.py](../../scripts/r1_experiments.py) | `geometry`, `configure`, `register`, `run`; registration records model/JAR hashes and budgets; refuses existing series/attempt paths. Final series must have a new identity. |
| [analyze_research_validation.py](../../scripts/analyze_research_validation.py) | Fixed R1 series list (lines 12-14); derives journal evidence, `target/research-validation/{audit,tables,pairs}.json`, and `docs/research-audit/results/{matrix,validation}.json` plus runs/pairs CSV (lines 196-198). Cannot ingest a new final campaign without explicit adaptation. |
| [plot_research_validation.py](../../scripts/plot_research_validation.py) | Reads R1 tables/pairs/audit; `DEST` is `docs/paper-r1/figures` (line 14). Case selection and axis limits are configured for R1 cases and are not a generic mission/load matrix plotter. |
| [write_research_validation_report.py](../../scripts/write_research_validation_report.py) | Writes `RESEARCH_VALIDATION_RESULTS.md` (line 102) and `docs/paper-r1/r1-results.tex` (line 119). Contains fixed R1 prose and counts. Do not run unmodified for the new campaign. |
| [package_research_validation.py](../../scripts/package_research_validation.py) | One-shot R1 packager with fixed prior build names; writes R1 source/evidence manifests and `r1-reproduction.zip`; refuses existing archives/test-evidence. Final packaging requires a distinct destination and exact final tested-source identity. |
| [reproduction.md](../research-audit/reproduction.md) | Correct R1 saved-version execution, data restoration, Python analysis/export, and compiler instructions. Its saved JARs and histories remain reproducible separately. |
| [reproduce.ps1](../../scripts/reproduce.ps1) | Reproduces the earlier ISS contact-geometry pipeline and numerical convergence checks. It is not the R1 or final policy-matrix runner. |

The recorded R1 derivation commands are `python -X utf8 scripts/analyze_research_validation.py`, then `plot_research_validation.py`, then `write_research_validation_report.py`. They are documentation of the old flow, not commands to run on the final campaign unchanged. Final generators should have explicit final input/output paths so that older manifests remain valid.

Six existing figure stems are `runtime_completion`, `service_cost_horizon`, `sensitivity`, `mechanism_timeline`, `recovery`, and `detected_exposure`; each has PDF/SVG/PNG exports in [figures/](../paper-r1/figures/). [provenance.json](../paper-r1/figures/provenance.json) records the table SHA-256 and selected case/journal references. The table hash matches on this inspection. Final plots should retain completion conditioning, censored recovery versus incomplete execution, and no imputed zero quality; add mission-by-load coverage/service/cost views for the complete registered grid.

Compile the new main and supplement separately with the new paper directory as project root. The prior build used Tectonic 0.17.0; `latexmk -pdf main.tex` and `latexmk -pdf supplement.tex` are the documented toolchain alternatives. Fresh final PDF/source/result-fragment hashes, compile diagnostics, page counts, and rendered-page review must replace assumptions based on R1's saved record.

## Primary-reference evidence and remaining checks

The controlling prior evidence is [related-work.md](../research-audit/related-work.md), dated 2026-10-25. Its later Detke full-text follow-up supersedes the earlier access limitation in [article-sources.md](../research-audit/article-sources.md), without changing that historical audit.

| Reference | Existing primary evidence | Re-verification or final wording constraint |
| --- | --- | --- |
| CCSDS SDLS 355.0-B-2; EP 355.1-B-1; Symmetric Key Management 354.0-M-1 | Prior audit read official PDFs: [SDLS](https://ccsds.org/Pubs/355x0b2.pdf), [EP](https://ccsds.org/Pubs/355x1b1.pdf), [SKM](https://ccsds.org/Pubs/354x0m1.pdf). Covers: July 2022, February 2020, December 2023. EP Sec. 2.3/Figs. 2-1/2-2 and Secs. 3.2/3.3 establish existing lifecycle procedures. | These are identified editions, not a fresh claim of latest editions. No need to re-fetch for the unchanged bounded comparison; check new edition claims externally. Synthetic operation costs and timers are not prescribed by these standards; model-state similarities do not establish SDLS conformance. |
| IOTA Identity, Bernal et al. | Prior audit accessed [publisher full text](https://www.techscience.com/cmc/v86n1/64479/html), DOI 10.32604/cmc.2025.069746. CMC 86(1), 1-39, 2026; online issue date 10 Nov. 2025. Contact-refreshed allowlists, revocation and short-lived tokens are documented. | Keep distinction between device identity/access authorization and traffic-key/SA scheduling. Do not claim first contact-aware security-state updates. No new full-text verification in this subtask. |
| FHAP | Prior audit records DOI [10.1109/JIOT.2023.3262933](https://doi.org/10.1109/JIOT.2023.3262933) and inherited IEEE IoT Journal 10(15), 13959-13973, 2023, but no accessed primary full text. | Fresh DOI open in this subtask again returned inaccessible/internal error. Search results were secondary citations, not promoted to verification. Obtain IEEE/author primary copy before detailed mechanism/performance claims or declaring the full bibliography validated. Do not silently substitute metadata from secondary citations. |
| LK-AKA | Prior audit has [publisher record](https://www.sciencedirect.com/science/article/abs/pii/S0140366422004169), DOI 10.1016/j.comcom.2022.10.028: Yang, Cao, Ren, Niu, Zhang, Li; Computer Communications 197, 214-229, 1 Jan. 2023. Publisher search content supported S2S/location-key scope. | Fresh direct publisher open again returned HTTP 403. Full-text protocol/security/evaluation details remain unverified. Narrow S2S/location-context comparison is the supported scope; absence of a feature is not established by access failure. |
| DTN BPv7, BPSec, SABR | Prior audit used [RFC 9171](https://www.rfc-editor.org/rfc/rfc9171.html), [RFC 9172](https://www.rfc-editor.org/rfc/rfc9172.html), and [CCSDS 734.3-B-1](https://ccsds.org/Pubs/734x3b1.pdf). BPSec Sec. 6 leaves key management to separate network management; SABR uses scheduled contacts. | Direct spacecraft-ground frame model is distinct from a BP implementation. Do not generalize that DTN ignores key provisioning. RFC metadata/unchanged narrow claims are already recorded; no new external verification here. |
| Detke et al., STINT/WiSEE 2026 | [Full paper](https://stintworkshops.org/papers/stint2026-19-detke.pdf) and [slides](https://stintworkshops.org/slides/stint2026-slides-detke.pdf) were fetched and read in prior follow-up. Local PDFs/extractions and [access record](../../target/research-validation/literature/detke-access.json) exist. Both PDF SHA-256 values match that record now. | Closest reviewed precedent: time-feasible CGR routes coupled to offline scope provisioning. Prior audit locates formulation on physical pp. 4-5 and future key scheduling on p. 6 / slide 7. No DOI or proceedings pagination was verified; six physical pages are not a page range. DTKA/BERMUDA and its repository were not independently reviewed. Do not claim priority over all temporal key-management research or empirical superiority over an unimplemented external system. |

## Checks performed and handoff

- Read `data/AGENTS.md`, R1 main/result fragment/README, source and related-work audits, reproduction instructions, export scripts, and saved compilation/literature/figure manifests.
- Recomputed main/supplement TeX and PDF hashes: all four match their saved compilation record.
- Recomputed both Detke PDF hashes: both match the recorded retrieved files.
- Recomputed the figure source-table hash: matches `provenance.json`; six figure families registered.
- Retried only the two open literature access gaps: FHAP inaccessible; LK-AKA HTTP 403. No primary-access success was inferred from search snippets.
- Remaining main-agent work: complete the registered final matrix and independent audits, create separately identified final paper/export/package outputs, update all numerical prose from final evidence, compile and render-review the final PDFs, and preserve R1/history.
