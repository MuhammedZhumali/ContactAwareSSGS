SE2.2.1: limited repeat and diagnostic commands

All commands run from the repository root. Existing SE2/SE2.1/SE2.2 directories
are immutable inputs. Keep their frozen JARs and raw logs. Use Python with -X utf8.
No network data acquisition or new contact generation is required.

Source versions:
  c5a301c — checkpoint fix only, deterministic tests and notes.
  07defaf — stage A harness, registration protocol and isolated-build scripts.
  97cbe11 — opt-in traversal option and finite exact-plan tests (stage B).
Each build/registration records the full commit, source archive, per-file hashes,
dataset hashes, test result and distinct JAR. No JAR replaces a previous version.

New APIs:
  ExecutionCheckpointFiles.writeWithDiagnostics / writeAtomicWithDiagnostics:
    unique sibling temp, atomic replacement only, bounded AccessDenied retries;
    WriteFailure.diagnostics carries outcome, attempts, delays and elapsed time.
    AtomicMoveNotSupported now fails closed instead of unsafe non-atomic fallback.
  PlannerOptions.acceleratedServiceFirst(): opt-in, changes only traversal.
    Default accelerated() remains RENEWAL_FIRST. Final comparator is unchanged.
  SecurityEvaluationSe221Main: controller/search harness using existing executor,
    costs, incidents and horizon policies. Outputs checkpoint trace and raw evidence.

Before controller repetitions:
  Se221ObservationAudit.java, compiled against frozen SE2.2 JAR, projects all five
  failed snapshots and compares every serialized PlanningObservation field.
  Output: target/security-evaluation-se221/observations/equivalence.json.
  No historical searches are performed.

Already saved evidence can be checked without rerunning controllers:
  python -X utf8 scripts/run_security_evaluation_se221.py verify
  python -X utf8 scripts/analyze_security_evaluation_se221.py

First execution in a NEW result workspace, never over the saved attempts:
  python -X utf8 scripts/build_security_evaluation_se221.py a prepare
  [Maven clean verify in that committed source directory; selected checkpoint tests]
  python -X utf8 scripts/build_security_evaluation_se221.py a freeze
  python -X utf8 scripts/run_security_evaluation_se221.py register-a
  python -X utf8 scripts/run_security_evaluation_se221.py run-a
  [commit stage B, then prepare/build/freeze b; full Maven verify]
  python -X utf8 scripts/run_security_evaluation_se221.py register-b
  python -X utf8 scripts/run_security_evaluation_se221.py diagnose
  python -X utf8 scripts/run_security_evaluation_se221.py run-b
  python -X utf8 scripts/analyze_security_evaluation_se221.py

Prepare uses current HEAD, so select the intended saved commit in a separate
checkout when reproducing an old stage. Supply preserved-inputs.json and original
source datasets. Dataset and tool dependencies are recorded, not downloaded.
The runner refuses overwriting registrations, attempt folders or prior outcomes.

Java21 and Maven3.9.9, wrapper offline; .tools/m2 is the preserved local cache.
Set JAVA_HOME and an explicit workspace java.io.tmpdir. Initial test invocation
without it was denied rename in the managed sandbox Temp; the log is preserved.
Windows refusal alone does not identify the program or service holding a file.

Every controller: 2M nodes/30s per search, 60s cumulative, 90s process watchdog.
Representative searches: 2M/30s, 45s process watchdog. One heavy JVM at a time.
No automatic budget escalation. Search elapsed remains outside simulated time.
Checkpoint retries consume actual controller time, with per-write JSONL traces.

Raw evidence: target/security-evaluation-se221/stage-a|stage-b/runs/<old-name>/.
checkpoint-writes.jsonl: attempts, AccessDenied count, backoff, total/rename elapsed.
actual.events.json, actual.decisions.json, actual.actual.json: execution evidence.
key-decisions.json: PREPARE/INSTALL observations and chosen continuations.
diagnostic/<order>/: original observation, result, full plan, replay check, GC log.
New tables and report: docs/security-evaluation-se221/.
Combined tables label old SE2.2 evidence separately; 40 completed old controllers
are reused rather than rerun. This is not a new full-grid performance benchmark.
