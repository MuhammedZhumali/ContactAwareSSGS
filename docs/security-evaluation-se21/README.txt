ContactAwareSS SE2.1 — reproducible artifacts, Java 21 / Python 3
==============================================================

Start with report.md, metrics-spec.md and protocol.md. The experiment is finite,
synthetic and diagnostic. No claim of real spacecraft or cryptographic validation.
Original SE2 is immutable; SE2.1 files are separate.

New classes / responsibilities
-----------------------------
SearchControl: nested monotonic deadlines, explicit cancellation and interruption;
  deterministic clock/checkpoint hooks for tests. Duration.ZERO means unlimited.
SearchRuntimeTelemetry: current-thread CPU, JVM-wide GC deltas, disjoint phase
  timings, unattributed elapsed and stop checkpoint. Missing counters are null.
ExecutionCheckpointFiles: versioned snapshot JSON, context-map deserialization,
  explicit PlanningAction compatibility, atomic publication.
RecordedDecisionReplay: reconstruct a pre-decision snapshot using saved actions
  and common executor/incident response, checking factual event prefixes; no search.
SecurityEvaluationSe21Main: capture/search/controller/check-checkpoint/watchdog-probe.
SecurityPolicyController: optional cumulative budget + checkpoint listener; retain
  immutable last committed state when canceled. Existing default remains unlimited.
ExactHorizonPlanner and shared branch/simulator code: cooperative checks and
  diagnostics; action space, objective, tie-breaking and pruning rules unchanged.

Build/test (PowerShell, repository root)
---------------------------------------
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.10'
New-Item -ItemType Directory -Force target/se21-test-tmp | Out-Null
.\mvnw.cmd '-Dmaven.repo.local=.tools/m2' -o -B -ntp '-DargLine=-Xmx1024m -Djava.io.tmpdir=C:/Users/Muhalek/ContactAwareSS/target/se21-test-tmp' verify
python -X utf8 -m unittest discover -s scripts -p 'test*se21*.py'

Adapt JAVA_HOME and absolute test tmp directory to the machine. Offline Maven
requires the cached dependencies; omit -o in a fresh online environment.
Verified: 344 Maven tests; 14 Python tests. Maven log and XML reports are retained
in the evidence bundle. Workspace temp avoids this host's restricted OS-temp rename.

Postprocess frozen journals (no policy execution)
------------------------------------------------
python -X utf8 scripts/recovery_metrics_se21.py target/security-evaluation-se2 --out docs/security-evaluation-se21/tables

The generated recovery-milestones.csv, readiness-intervals.csv,
overlapping-conditions.csv and recovery-metrics.json include input hashes.

Registered repeat series (one-time fresh output only)
---------------------------------------------------
Frozen legacy JAR: target/security-evaluation-se2-build/se2.jar
Frozen current JAR: target/security-evaluation-se21-build/se21.jar
The new frozen JAR was copied from target/contact-aware-contacts.jar after verify.
Never replace either registered executable with a later build.

python -X utf8 scripts/run_security_evaluation_se21.py register
python -X utf8 scripts/run_security_evaluation_se21.py run
python -X utf8 scripts/analyze_security_evaluation_se21.py

register/run deliberately refuse existing manifest/summary/results. The saved
30-attempt series is already complete; these commands are for a fresh reproduction
workspace with immutable SE2 inputs and no SE2.1 outputs. Do not delete original
evidence to repeat a command. New experiments need a separately registered output.
The checked-in runner/protocol hashes are bound by manifest.json before repeats.

Single decision from a saved observation (choose a NEW output directory)
-----------------------------------------------------------------------
& "$env:JAVA_HOME/bin/java.exe" -Xmx1024m -cp target/security-evaluation-se21-build/se21.jar org.contactaware.security.simulation.SecurityEvaluationSe21Main --mode search --observation target/security-evaluation-se21/captures/iss3-cp5400-alt-wait-next-original-undetected/observation.json --checkpoint target/security-evaluation-se21/captures/iss3-cp5400-alt-wait-next-original-undetected/checkpoint.json --timing PREPARE_DURATION_WAIT --nodes 100000 --seconds 5 --out target/se21-manual-new-output

This direct CLI has cooperative search limits only. The registered Python runner
adds the external process deadline, GC logging and JFR. Legacy replay uses old JAR
first, new harness JAR second on classpath. No source actions are searched again.

Checkpoint verification (NEW output directory)
---------------------------------------------
& "$env:JAVA_HOME/bin/java.exe" -cp target/security-evaluation-se21-build/se21.jar org.contactaware.security.simulation.SecurityEvaluationSe21Main --mode check-checkpoint --checkpoint target/security-evaluation-se21/runs/iss3-cp5400-alt-wait-next-none-controller-budget/checkpoint.json --out target/se21-checkpoint-new-output

Figure / validation
-------------------
python -X utf8 scripts/plot_security_evaluation_se21.py
Requires matplotlib, Pillow, pypdf (uses .tools/figure-python when present), frozen
SE2 independent audit and manifest. Refuses an existing nonempty output directory.
Exports figures/detected_exposure_se21.pdf, .svg, .png (300 dpi), caption, hashes,
structural QA. Separate policy-color and traffic-hatching legends; original data.

Limits and next stage
---------------------
5 s/100000 nodes per search, controller 60 s for X or 10 s for bounded ISS,
external 20 s per isolated search / 90 s X / 30 s ISS / 2 s artificial probe.
No adaptive budget increases. 16 COMPLETE, 8 INCOMPLETE isolated searches;
4 FINISHED X; one CONTROLLER_TIME_LIMIT; one expected PROCESS_TIMEOUT probe.
The original 42/3361 s anomalies did not recur; original cause remains unknown.
Instrumentation adds overhead. Search still consumes no modeled contact time.
First independent activation is not continuous readiness or first delivered data.
Next: justify any proposed WAIT/state-space reduction separately and compare
completed canonical plans/five metrics before changing the finite search space.
