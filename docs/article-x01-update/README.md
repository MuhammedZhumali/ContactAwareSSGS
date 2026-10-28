# Approved article update with measured X01 results

The author subsequently requested restoration of the supplied
`contact_horizon_mechanism.png`. It is now Fig. 1 in the X01 methods description,
with a brief conditional caption; existing figures and their references are
renumbered 2–4. The image is embedded without modification. The current Word
article has 16 pages, all visually inspected, and the synchronized LaTeX compiles.
See [`mechanism-figure-validation.json`](mechanism-figure-validation.json) for the
current artifact identity. The record below and `validation.json` describe the
preceding 15-page revision. Existing archives remain unchanged and predate this
figure restoration. The edit is reproducible with `scripts/restore_article_mechanism.py`.

On 28 October 2026, after discussion of the results, the author explicitly requested
the article update. The current deliverables are
[`article.docx`](../aitu-revision-v2/article.docx) and the synchronized
[`main.tex`](../aitu-revision-v2/article-tex/main.tex).

The experiment design, results, discussion and conclusion were updated first;
the abstract was revised last. Table 5 adds the registered 40 main pairs. The text
separates offline scheduling potential, discovery under a search budget, and
execution when measured decision time consumes contact opportunities. No claim
attributes all delay to DFS or predicts that a faster solver will restore the gain.

The edit preserves the supplied author's fields, formatting, section geometry,
existing tables and figures, all 20 references and three native OMML equations.
Only `word/document.xml` changes inside DOCX; the other 18 parts are byte-identical.
New paragraphs and Table 5 reuse the existing design. Added content increases the
Word rendering from 13 to 15 pages. Every final page was visually inspected.
The abstract is 261 words and 1,668 characters. LaTeX compilation succeeded.
See [`validation.json`](validation.json) for current checks. The earlier
`../aitu-revision-v2/validation.json` remains an historical audit of its own version.

The retained source DOCX has SHA-256
`de3eaaf53bf4a786eb36539f9ab9c9d58911180491bb353786bdfed5286ed09f`.
The article update uses `scripts/update_article_x01.py`, followed by
`scripts/sync_article_x01_sources.py`; neither rebuilds the DOCX design from a
generic template. Their original inputs are included in the new local archive
under `target/article-x01-revision-20261028/`.

The distinct local materials archive is
`target/article-x01-delivery/ContactAwareSS-X01-article-updated-20261028.zip`.
It extends the verified published X01 package with the approved article, its
original inputs and validation, and the three-page pre-approval results report.
That report's approval status is historical; its scientific observations are
unchanged. The new archive has its own manifest and SHA-256 sidecar. The base ZIP
and its manifest are separately identified; all raw attempts, executable artifacts
and registered inputs are preserved. `scripts/package_article_x01.py` creates and
verifies the new package without running experiments.

The public [X01 release](https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/x01-measured-delay-20261028)
already supplies the complete experimental evidence. It is unchanged and still
contains the article before this approved edit. The new local article package has
not been uploaded as a new release. No DOI is claimed.

No scientific attempt or Maven test was rerun for this document-only update.
Funding, acknowledgments, competing interests and corresponding-author designation
still require the author's confirmation; the editorial DOI placeholder is retained.
