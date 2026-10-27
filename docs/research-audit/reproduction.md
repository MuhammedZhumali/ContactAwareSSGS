# Reproducing the R1 evidence

## Identities and preserved history

The base commit is `3621333d8093b4cb0c232c7db9164c66e273053c`; the checkout already had user changes. No claim is made that the R1 binaries are a clean build of that commit alone. `target/research-validation/environment.json` records the initial dirty state and host. `original-sources.json` hashes the original source files. Isolated build records and source archives identify each actual binary. Do not overwrite older SE2, SE2.1, SE2.2, SE2.3, SE2.4 or SE2.5 runs with these versions.

- `baseline-binary.json` / `baseline-restored.jar`: original production sources, restored local fixtures.
- `candidate-binary.json` / `candidate.jar`: exact aggregate and channel/nonce changes; the 40 exact experiments and geometry configuration.
- `practical-v2-binary.json` / `practical-v2-verified.jar`: feasible suffix reuse and exported audit; six practical/control/delay series.
- `delay-export-binary.json` / `delay-export.jar`: export-only repair after a retained certificate-serialization PROCESS_ERROR; eight new deadlines-v3 attempts. Final root sources match this build.

Each protocol embeds its JAR hash and each model hash. The runner verifies these bytes before starting. `manifest.json`, `evidence-file-hashes.json`, source ZIPs and `r1-reproduction.zip` are created after integration; their final fingerprints are recorded in `package.json`. Full traces and last-good checkpoints remain in each run folder. A checkpoint contains privileged simulator state and must not be passed directly to a planning policy.

## Data restoration

The final package contains the current `src/`, `scripts/`, Maven files, compact documents/figures, frozen source archives and JARs, new protocols/models/journals, public GP source snapshots, station geometry, and the required saved orbit/earth test fixtures. The original saved ISS schedule/source inputs and derived 44/45-contact schedules are included where referenced by new model provenance. Four frozen SE2 journal fixtures required by the Python tests and compact historical reference documents are included. Historical archives are not duplicated; their preservation hashes remain available in `preserved-inputs.json`. The historical preservation check requires the original workspace's old artifacts; it cannot be rerun against files deliberately omitted from the reproduction package.

Extract into a **new empty directory** to inspect or reproduce; do not overlay another research checkout. Existing `data/` is ignored by Git and is required for several tests. The package restores it. Without the package, recover the exact files and hashes in the isolated build `fixture-manifest.json`, plus `orbit-sources/*/gp.json` and `geometry-v2` metadata. Fetching today's orbit elements is not an equivalent replacement for a historical snapshot. Do not propagate one snapshot over arbitrary weeks.

The Java source archives contain the build-time source versions. `candidate-sources.zip`, `practical-v2-sources.zip` and `delay-export-sources.zip` are not interchangeable. The root source snapshot includes the export repair. Build dependencies are not bundled as an offline Maven repository: use a local populated Maven cache or permit the wrapper to download public dependencies. JAR execution uses the shaded saved binaries and needs only Java21. Python analysis additionally requires `matplotlib` (PDF inspection also uses `pypdf` and Poppler). This workflow targets the recorded Windows environment; launcher paths must be adjusted on another host.

## Build and focused validation

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\mvnw.cmd -B -ntp test
python -X utf8 -m unittest discover -s scripts -p 'test_*.py'
```

The recorded isolated build used offline Maven with `-Dmaven.repo.local=C:\Users\Muhalek\ContactAwareSS\.tools\m2` and a private temporary directory. Those are this host's paths, not required universal locations. The final integration exercised 423 tests, then 74 overlapping focused tests after practical-v2 and 10 after the export-only repair. The latter are not second complete suites. Loopback HTTP tests need permission to bind a local test server; a permission failure is not a logic failure.

## One saved-version execution

Run from the restored package root. Choose a new output folder; the harness refuses an existing checkpoint/result. This reproduces a deterministic physical input and bounded algorithm configuration, not identical wall-time cutoff behavior under every host load.

```powershell
java -Xmx1024m -cp target/research-validation/practical-v2-verified.jar `
  org.contactaware.security.simulation.ResearchValidationMain `
  --mode practical --model target/research-validation/models/X5.json `
  --rule CONTINUE_WAIT --horizon 2 --nodes 5000 --search-ms 1000 `
  --controller-s 20 --period-s 60 --latency-ms 0 --deadline-ms 250 `
  --out target/r1-replay-X5-multi
```

For exact reference use the candidate JAR, `--mode exact`, and the exact protocol's 2,000,000-node/30-s search, 60-s controller limits. For a new registered matrix, copy the cases into a **new** series registration and keep its new identity; do not rerun `register()` into the saved directories. The original one-attempt protocols and superseded unattempted registrations are evidence, not scratch files.

The Python `child` wrapper adds the separate external process limit (90 s exact / 35 s practical). `r1_experiments.run` reads the registered JAR identity, starts only one child at a time, retains incomplete attempts, and stops that series on a process error. Internal node/time/controller cancellation and external watchdog are different layers. An OS-level blocking rename cannot be preempted by a cooperative Java checkpoint.

## Derive tables, figures and paper

```powershell
python -X utf8 scripts/analyze_research_validation.py
python -X utf8 scripts/plot_research_validation.py
python -X utf8 scripts/write_research_validation_report.py
```

The analyzer reconstructs event costs, frames, nonces, disclosure consequences and recovery milestones; it verifies checkpoint/export equality and suppresses final quality for incomplete executions. `audit.json` and figure `provenance.json` link the source journals. The old SE2.1 replay default is intentionally preserved; R1's replay includes spent nonces for aborted committed frames.

Open `docs/paper-r1/` in the Codex LaTeX editor or compile `main.tex` with a LaTeX installation containing IEEEtran. `supplement.tex` is a separate document. Figures are generated PDF/SVG/PNG, with tables as CSV/JSON; the main text includes `r1-results.tex`. The supplied older PDF/SE25 TeX remain separate originals. Rendered-page review and compile diagnostics are part of the final package.

## Interpretation

`FINISHED` is physical execution completion. A practical execution can contain many `INCOMPLETE` searches. Only a fully completed exact search proves optimality within its finite visible model. Five-criterion practical quality, time/CPU/heap, fallback counts and suffix-reuse counts must be read together. Missing/censored recovery milestones are not zero, and an incomplete execution has no final service/security score. Hardware timings and deterministic repeat runs are not independent samples of policy effectiveness.
