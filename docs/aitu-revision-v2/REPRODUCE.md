# Reproducing AITU revision 2

This separate revision keeps the preceding AITU campaign and the frozen final-v2
campaign intact. Public URL of **this revision**: **[TO BE ADDED AFTER PUBLIC
DEPOSITION]**. The historical `research` release does not contain these new runs.

Run commands from the extracted reproduction package root. Python3.11+ and JDK21
are required; JDK21.0.10 reproduces the bytecode check. No Maven installation is
needed for adapter rebuilds. The corresponding frozen production source ZIP and
its original build/test metadata are retained; no new production JAR was built.

## Evidence without rerunning policies

Read `docs/aitu-revision-v2/report.md`, `docs/aitu-revision-v2/protocol.md`, and
`target/aitu-revision-v2/protocol.json`.
Six new root searches and four conditional controller executions are distinct
denominators. Four root searches are bounded INCOMPLETE incumbents; two are
COMPLETE. Three controllers FINISHED; CANONICAL Multi reached CONTROLLER_TIME_LIMIT.
Its prefix is preserved but is not a final mission score. The older110 controllers,
12 paired-root diagnostics and six reused cells retain their original locations.

```powershell
python -X utf8 scripts/analyze_aitu_v2.py
python -X utf8 scripts/describe_aitu_v2_inputs.py
```

These commands use saved artifacts only and reconstruct the new tables. Run in
an extracted copy to preserve supplied files byte-for-byte. `audit.json` records
full-plan replay, comparator checks, journal reconstruction and source hashes.
Replays were performed during the six registered root runs with the shared Java
executor; Python independently checks their exports and incumbent ordering.

## Build and optionally replay one cell

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
python -X utf8 scripts/reproduce_aitu_v2.py build --out target/my-v2-build
python -X utf8 scripts/reproduce_aitu_v2.py replay --kind root --id root-CONTINUE_WAIT-N150000 --built target/my-v2-build --out target/my-root-replay --dry-run
python -X utf8 scripts/reproduce_aitu_v2.py replay --kind controller --id controller-CONTINUE_WAIT-N150000-H2 --built target/my-v2-build --out target/my-controller-replay --dry-run
```

Remove `--dry-run` to execute that one cell. Never use an existing output directory.
The helper preserves the registered scientific flags, search/decision/controller
budgets and watchdog. It does not perform retries, widen budgets, or automatically
run the full series. Keep one heavy JVM at a time. Host time and timer-sensitive
trajectories can vary; byte-identical inputs do not guarantee identical wall time.

The root harness uses the retained observational planner overlay to record
incumbents. Ordinary full controllers use the frozen planner, with no overlay or
oracle. Both share the exact archived observation/model and comparator semantics.
The fresh root portfolio has no retained input; this is not a claim that a real
controller has no retained candidate at later decisions.

## Manuscript and supplement

```powershell
python -X utf8 scripts/write_aitu_v2_content.py
python -X utf8 scripts/build_aitu_v2_documents.py
```

Document creation also needs python-docx. It preserves the original AITU template
and v1 generator, applies v2 content, fixed-width wide table panels and corrected
LaTeX URL punctuation. Figures are the preserved first-revision figures, explicitly
labeled by their regimes; their original plotting code and inputs remain included.
Upload `article-tex` to Overleaf and select `main.tex` or `supplement.tex`.

Word16 read-only COM plus Poppler provided page QA because the bundled renderer
could not find `soffice.exe`. Internal PDFs are not deliverables. Native OMML is
editable but is not MathType; see the submission checklist for unresolved items.

`manifest.json` at archive root hashes the payload. Absolute paths inside original
invocations are provenance; use the replay helper for portable path replacement.
Preservation checks referring to old external archives require those archives and
are recorded as local verification, not silently treated as absent files passing.

The supplied `portable-validation.json` records a clean payload check: all27
adapter classes rebuilt byte-identically, both one-cell replay commands checked
in dry-run, and the six new table/audit/input-descriptor outputs regenerated
byte-for-byte. No additional scientific searches are part of that check.
`scripts/finalize_aitu_v2.py` and packaging helpers are local release tools; their
preservation checks intentionally require the prior external archives. Ordinary
table reconstruction and the adapter build commands above are self-contained.
