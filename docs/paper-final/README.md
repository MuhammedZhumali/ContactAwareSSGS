# Final evidence manuscript

Display-label revision (2026-10-25): `service_matrix` and `measured_iss` now label
the existing `usefulSecureTime` metric as **Useful payload time (s)**. Only the two
display strings changed in `scripts/plot_final_research.py`; calculations, source
tables, figure provenance and manuscript text are unchanged. The updated article
was compiled from the extracted `target/contactawaress-overleaf-labels-v2.zip`.
Current revision checks are in `target/figure-label-update/validation.json`;
the original compilation record below and previously delivered archives describe
the preserved earlier version. Both changed figures and article pages 8 and 11
were visually reviewed; the remaining article pages and all supplement pages
are pixel-identical to the previously reviewed version.

[Main paper](main.pdf) — 12 pages. [Reproduction and results supplement](supplement.pdf) — 13 pages.
Both are separate from the historical R1/SE2 manuscripts. No venue page limit was assumed.

The paper completes Abstract, Results, Discussion and Conclusion using one preregistered 108-attempt
campaign: 93 finished executions and 15 process timeouts. The independent event audit covers all
93 finished exports and 37,164 same-root full-comparator selections. All unfinished attempts remain
visible. Four completed core horizon pairs tie; five are incomplete. The conditional exact reference,
bounded autonomous limitation, measured-time loss and late-disclosure benefit retain distinct scopes.

Six scientific figures have PDF/SVG/PNG exports in `figures/`. The figure provenance identifies
source case IDs and final table/protocol hashes. Exposure has separate policy and hatching legends.
The supplement contains all five scores and host/search diagnostics for every case, directional
traffic, four recovery milestones, and partial interruption costs. NA, censored and unfinished values
are not replaced with zero.

Tectonic 0.17.0 compiled both documents. Poppler rendered every page at 115 dpi; all 25 pages were
visually inspected, including reinspection of the two pages changed during final formatting.
No overfull boxes or unresolved citations/references remain. Underfull-box and font-shape fallback
warnings are retained in the logs; rendered text/math is intact. Missing standard fonts were fetched
from the compiler's normal bundle, then the final build succeeded using the local cache.

Compilation, source/fragment/embedded-figure hashes and page-render evidence are recorded in
`target/final-research/latex/validation.json` from the project root. References were checked against
primary sources in [reference-verification.md](reference-verification.md). This is a scoped review,
not an exhaustive priority claim.

See [final report](../final-research/report.md), [registration](../final-research/protocol.md),
[reproduction commands](../final-research/reproduction.md), and
[requirement audit](../final-research/requirements-audit.md). Source is `main.tex` / `supplement.tex`;
the two result fragments are generated from the final audited table by
`scripts/write_final_research_report.py`. Java sources, JAR and model inputs were unchanged during
the campaign and paper preparation. Compiler/generated artifacts do not replace historical results.
