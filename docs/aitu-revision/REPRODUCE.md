# Reproducing the AITU revision

This is a separate bounded campaign. Do not overwrite the historical `target/final-research` campaign or its archives. All commands below run from the extracted package root. Java21 is required. Set `JAVA_HOME` to the installed JDK21.0.10 when checking byte-identical compilation. Python 3.11 or newer with the standard library suffices for rebuilding/replaying a cell and for numeric postprocessing. Document authoring additionally requires python-docx; figures require NumPy/Matplotlib and Times New Roman.

## Inspect without running experiments

Read `docs/aitu-revision/report.md`, the four stage protocols, tables and audits. `manifest.json` hashes package payloads. `target/aitu-revision/*/protocol.json` is the registered experiment, and `invocation.json` preserves the actual command in each cell. Absolute machine paths in historical invocations are provenance, not portable paths to execute verbatim.

The110 new full-controller attempts and12 paired-root diagnostics are distinct populations. Six reused cells point to Stage1; they are not new attempts. Do not merge these counts with the old108-attempt final campaign.

## Build the same adapters in a new directory

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
python -X utf8 scripts/reproduce_aitu.py build --out target/aitu-rebuild
```

The builder uses explicit registered sources. This avoids the original development builder's directory glob, which would now also pick up later fixture/seed-check sources. The original builder and its registration hash remain preserved. The new builder verifies source and JAR hashes, compiles ordinary, root-diagnostic and seed-only adapters separately, and compares26 class files with the registered binaries. This comparison passed locally. A missing, altered or empty registered class inventory is rejected before compilation, so zero comparisons cannot be reported as success. It does not rebuild or replace the frozen production JAR. Its corresponding source ZIP and Maven build/test metadata are included.

## Replay one registered cell into a new directory

Inspect the command first:

```powershell
python -X utf8 scripts/reproduce_aitu.py replay --stage stage3 --id X5-offline-N0-H1 --built target/aitu-rebuild --out target/my-replay --dry-run
```

Remove `--dry-run` to execute that one cell. Choose any new-case ID from its stage protocol. Reused cells are replayed through their original Stage1 ID. The script refuses to reuse an output directory, preserves scientific flags and node/time budgets, enforces the registered process watchdog, and records the replay separately. Run one JVM at a time. Do not increase limits simply to obtain COMPLETE. Host-timed outcomes and measured-delay trajectories can vary across machines or repeated runs; exact completed results require the same modeled state, candidate rules and comparator, not matching wall time.

## Recompute journal-derived tables

```powershell
python -X utf8 scripts/analyze_aitu_revision.py stage1
python -X utf8 scripts/analyze_aitu_revision.py stage2
python -X utf8 scripts/analyze_aitu_revision.py stage3
python -X utf8 scripts/analyze_aitu_revision.py stage4
python -X utf8 scripts/plot_aitu_revision.py
```

These operations do not rerun policies. Perform them in an extracted copy if preserving delivered exports byte-for-byte matters. The analyzer preserves incomplete statuses, reconstructs operation costs and complete-frame service, checks actual counters/deadlines, and postprocesses confidentiality/recovery. `recovery-readiness.csv` and `overlapping-wait-conditions.csv` flatten interval records; overlap flags must not be summed as independent causes.

## Manuscript

The editable submission-format candidate is `docs/aitu-revision/article.docx`; the supplement is beside it. `article-tex/main.tex`, `supplement.tex` and `figures/` are the corresponding LaTeX sources. Both are compiled with the desktop LaTeX editor; no PDF is a deliverable. For Overleaf upload the contents of `article-tex` and select `main.tex` (or `supplement.tex` for the supplement).

`write_aitu_content.py` produces the article's single content source; `build_aitu_documents.py` creates both DOCX and LaTeX using the retained official template. Word rendering is performed only for internal QA. The bundled DOCX renderer could not find LibreOffice on this Windows runtime; Microsoft Word16 COM and bundled Poppler were used instead. Native editable OMML equations are not MathType objects. See `submission-checklist.md` before submission.

The public release at https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research contains the prior baseline package, not these new results. The revision has not been published or assigned a DOI.
