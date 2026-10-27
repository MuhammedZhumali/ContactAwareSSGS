# Reproducing the final campaign

The authoritative registration is `target/final-research/protocol.json`. It fixes every case,
model hash, policy, horizon, tie rule and budget before the first campaign experiment.
`manifest.json` identifies the tested working-source snapshot and the one final-v2 JAR.
The Git commit alone is insufficient because the frozen source tree contained documented changes.
Prior R1/PC2 artifacts are separate versions and must not be substituted for this JAR.

## Build and test

Java 21 and Maven 3.9.9 were used. The exact command and 478-test result are saved in
`target/final-research/final-v2-tests.json`; full logs and Surefire XML are retained.
The first sandboxed full-suite attempt had five loopback permission errors in `HttpImportTest`;
the second clean build used the identical 181-file source manifest with local HTTP permitted.
All 478 tests then passed. No test was skipped to obtain the JAR.

From the restored package, build into a new isolated directory. This preserves saved evidence
and puts test fixtures outside the new build's `target` directory, so Maven `clean` cannot erase them:

```powershell
python -X utf8 scripts/rebuild_final_source.py --out target/final-rebuild-01
```

The helper copies only the hash-verified frozen source and required fixtures; it does not run
Maven clean at the archive root. The output directory must not exist. Set `JAVA_HOME` for the
installed Java21 runtime on another machine. The original build used `-o` and a host-local Maven dependency cache; a new machine may need
network access once to resolve the pinned dependencies from `pom.xml`. HTTP import tests use
a local loopback test server. The private temporary-directory paths in the saved command are
host-specific. A fresh build need not have byte-identical ZIP timestamps; use the packaged JAR
to reproduce the exact executable identity.

## Run a saved case without overwriting evidence

From the package/project root on Windows, with Java 21 and Python 3.11 or newer available
(`hashlib.file_digest` is used for identity checks):

```powershell
python -X utf8 scripts/replay_final_case.py `
  --case 001-regression-X5-offline-next-H1-canonical `
  --out target/final-replays/X5-next-01
```

Choose any exact ID from `protocol.json`; each new destination must not exist.
The helper verifies model/JAR hashes, preserves the original case budgets and applies the same
600-second watchdog. It never changes or replaces a registered result. Set `JAVA_HOME` for
Java21 on another host; without it the helper uses this host's recorded default installation.
All original invocations are also recorded beside their run journals.

To repeat the full fixed matrix, invoke the helper once for each registered ID in protocol order,
using separate new destinations and one process at a time. Do not delete or reuse saved results.
Wall-time cutoffs and measured delay depend on the host and its load; identical inputs do not
promise identical time-limited search paths on every machine. Repeated executions are not
independent samples of policy effectiveness.

## Audit and figures

```powershell
python -X utf8 scripts/analyze_final_research.py
python -X utf8 scripts/plot_final_research.py
```

The analyzer reconstructs event costs, complete and aborted frames, counters, disclosure and
four separate recovery milestones. It checks checkpoint/export equality and available-candidate
selection under the full comparator. Final quality stays unavailable for incomplete executions;
saved prefix metrics are clearly separate. Recovery censoring is not zero.
Matplotlib is required only for PDF/SVG/PNG figures. CSV/JSON tables and figure provenance
identify the actual source cases. Figure scripts never read future events on behalf of a policy.

The final paper is in `docs/paper-final/`; compile `main.tex` and `supplement.tex` separately
with IEEEtran/LaTeX or Tectonic. Final compilation and render checks have their own saved record.
The reproduction archive includes source, inputs, new raw evidence, manifests, tests, commands
and manuscript; external Maven/Python dependencies are declared rather than bundled as binaries.
