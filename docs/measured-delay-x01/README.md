# Reproducing measured decision latency in X01

This addendum tests whether the AITU revision2 offline service gain survives
charging the measured decision pipeline. Read `protocol.md`, then `report.md`
when the registered series is complete. The user's manuscript is approval-gated
and is not modified by any command below.

## Fixed evidence

`target/measured-delay-x01-20261028/manifest.json` records all88attempts before
execution, hardware/runtime, input and executable hashes, limits, order and
preserved older evidence. The phases are4offlinecontrols,4measuredpilots and
80measuredmainattempts, with20paired Next/Multi repeats per tie rule. JVMs run
sequentially, with no explicit warmup and a fresh process for every attempt.
The controller uses150000nodes/search,30s search,32s full selection,180s controller
and210s external watchdog, heap1024MiB. No automatic retries or increased limits.

The exact archived model starts autonomously at t=-2; the instruction's t=0
summary describes the earlier diagnostic root. Full old/new configuration
comparison is in the protocol and `audits/config-audit.md`. The original final-v2
JAR is preserved. `build/measured-x01.jar` differs only in measured controller
compatibility classes; its planner, portfolio and offline controller remain
byte-identical. The experiment adapter adds proof and effective-deadline exports.
Commit `b2d04b8` records the compatibility fix and its focused tests.

## Reconstruct tables without running experiments

Use Python3.11+ from the extracted package root:

```powershell
python -X utf8 scripts/analyze_measured_x01.py --self-test
python -X utf8 scripts/analyze_measured_x01.py
python -m pip install -r docs/measured-delay-x01/plot-requirements.txt
python -X utf8 scripts/plot_measured_x01.py
```

Plotting used matplotlib3.11.2 and numpy2.5.4. CSV/JSON reconstruction uses only Python's standard
library. Use a copy to preserve supplied output bytes. `runs.csv` includes every
planned attempt; unfinished final scores are empty, with prefix values separate.
`contacts.csv` contains delivered UL/DL bytes/frames and service flags.
`decisions.csv` preserves nanosecond timestamps, charged durations, intent,
execution/rejection, raw bounded search nodes/stops, seed/retention and proof audit.
`pairs.csv` and `summary.json` keep controls/pilots distinct from the main20pairs.

## Replay one attempt

JDK21 is required; supplied classes target Java21. Set JAVA_HOME/PATH normally or
pass `--java` explicitly. Do not run multiple replay processes concurrently.

```powershell
python -X utf8 scripts/replay_measured_x01.py --id main-CONTINUE_WAIT-01-H1 --out target/my-x01-replay --dry-run
```

Remove `--dry-run` to execute. The helper refuses an existing output directory,
preserves the registered configuration and limits, and saves its command and
process result. Timings and therefore measured trajectories can vary across
machines and repeats. A replay is not a new member of the original registered
series. To reproduce that full series in a fresh research copy, retain its exact
configurations/order, use new run IDs/directories and label it as a new series;
do not overwrite historical attempts or copy their pilot approval into a new run.

`run_measured_x01.py build` documents/executes javac replacement of the compatibility
classes against the frozen JAR and rebuilds the experiment-only adapter. It refuses
an existing build directory. Its recorded commands, generated Java source and
class hashes are retained under `build/`. No Maven rebuild of the complete frozen
runtime is needed. The targeted Maven command was:

```powershell
.\mvnw.cmd '-Dtest=MeasuredContinuationVerificationTest,MeasuredPlanningLatencyTest,MeasuredWaitReplanTest,PlanningLatencyControllerTest,ResearchValidationExportTest' test
```

13tests passed; XML and logs are included. This is not a fresh full-suite claim.

`validation.json` records the completed workspace validation. The historical
preservation verifier `scripts/verify_measured_x01.py` also expects local old
delivery archives, document QA renders and the private working backup of the
author's article. Those redundant workspace files are not all distributed in the
release. It is not a standalone package validator. Verify the release's SHA-256
sidecar and root manifest, then use the analysis and replay commands above; their
required inputs are included. No experiment rerun is needed to check file hashes.

## Timing boundary and limitations

Charged time includes observation/candidates, certified-continuation verification
and preparation, seed/retained feasibility work, bounded search and final choice.
Checkpoint I/O, fresh admission, execution, startup and final export are not added
to contact time; they have separate controller/process elapsed measurements.
During charging, committed work/events advance normally, then intent is checked
again. Modeled operation CPU and host planning seconds are distinct quantities.
Guard/preparation durations are subsets of the selection interval, not extra
charges. An old proof never certifies an action shifted to a new model time.

The environment is the recorded desktop, not flight hardware. Ordinary background
OS activity is not disabled or causally attributed. Twenty pairs measure observed
variability for this one scenario, not universal superiority or onboard feasibility.
