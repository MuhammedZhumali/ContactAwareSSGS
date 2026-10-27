# R1 manuscript project

This is a standalone revision of the ContactAwareSS article. It is based on the content audit of the latest supplied `Contact_Aware_Research.pdf` and the available `ContactAwareSS_SE25.tex`, which are **not identical source/render versions**. It does not overwrite Downloads or `docs/paper-2026-10-19/`.

- `main.tex`: IEEE conference-style main manuscript. It keeps the objective, horizon convention, complete tie-rule distinction, ground logical coordinator, exact/practical split, delay assumptions, channel semantics, nonce correction, and bounded novelty claims together.
- `supplement.tex`: separately compilable supplement containing source hashes, historical versions/check counts, technical-attempt provenance, execution-contract details, and links to saved evidence.
- `r1-results.tex`: generated from the audited result tables by `scripts/write_research_validation_report.py`. It is loaded conditionally within `Results and Evidence Status / R1 Experiments`. Supply a fragment with prose, tables, and optional subsubsections, without a document preamble, bibliography, or top-level section.
- `figures/`: six generated charts in PDF/SVG/PNG: `runtime_completion`, `service_cost_horizon`, `sensitivity`, `mechanism_timeline`, `recovery`, and `detected_exposure`, with per-figure provenance.

The earlier 21/42 pairs remain historical. R1 records 181 new attempts, 135 finished executions and 55/84 selected complete horizon pairs, with a separate 12-attempt original-source baseline. Validation comprises 423 candidate tests, 74 overlapping practical tests, 10 focused export tests and 67 Python tests. The failed export attempt and seven unattempted cells remain recorded alongside the new eight-case delay registration.

The bibliography is deliberately bounded to the primary sources verified in [the literature audit](../research-audit/related-work.md). Detke et al.'s six-page conference paper and eight-page slide deck were retrieved and inspected; no DOI or publisher page range is invented. FHAP and LK-AKA detailed claims remain qualified by primary/full-text access limits. No external protocol is claimed to have been reimplemented or outperformed.

The integrated [main PDF](main.pdf) has eight pages and the [supplement PDF](supplement.pdf) has four. Both were compiled with Tectonic 0.17.0 and all pages were visually reviewed. No venue page limit is assumed. Compiler logs, PDF/source hashes, page counts and rendering evidence are in `target/research-validation/latex/` from the repository root. Minor underfull-box/font-configuration warnings remain; no overfull boxes or unresolved references were found and rendered text is intact.

Compile `main.tex` as the primary document and `supplement.tex` independently, keeping this directory as the project root. With an installed TeX toolchain, the corresponding commands are `latexmk -pdf main.tex` and `latexmk -pdf supplement.tex`; alternatively use the application's LaTeX project compiler. Repeat references as required by the compiler.

For subsequent revisions, regenerate the result fragment from audited tables, compile both documents and repeat the page review. Original source provenance and limitations are in [article-sources.md](../research-audit/article-sources.md) and [channel-model.md](../research-audit/channel-model.md).
