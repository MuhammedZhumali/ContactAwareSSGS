SE2: security-evaluation robustness

Protocol: protocol.md (frozen before execution)
Results and interpretation: report.md
Registration: manifest.json; complete models/journals/decisions in evidence ZIP and target/security-evaluation-se2
Tables: tables/metrics.csv, pairwise.csv, diagnostics.csv, coverage.csv,
        recovery-summary.csv, attack-times.csv, win-tie-loss.csv, excluded.csv,
        search-decisions.csv, search-timing-anomalies.json
Independent checks: tables/independent-audit.json, tables/se1-overlap.json
Figures: figures/*.pdf (vector), *.svg (editable text), *.png (300 dpi)
Mechanism explanations: mechanisms.md (with exact factual operation/frame times)

Recorded validation: Maven verify 324 tests, 0 failures/errors/skips; combined
Python unittest suite 25 PASS. All 488 runs attempted: 474 FINISHED and
14 SEARCH_INCOMPLETE. Independent factual audit: 488 journals and 41,310 matching
pre-notification decisions. These counts describe this recorded experiment;
wall-time-limited searches may stop differently when repeated on another host.

Java 21 / Maven, from repository root:
  .\mvnw.cmd verify
  New-Item -ItemType Directory -Path target/security-evaluation-se2-repeat-build -ErrorAction Stop
  Copy-Item target/contact-aware-contacts.jar target/security-evaluation-se2-repeat-build/se2.jar
  java -Xmx1024m -cp target/security-evaluation-se2-repeat-build/se2.jar org.contactaware.security.simulation.SecurityEvaluationSe2Main --register --out target/security-evaluation-se2-repeat
  java -Xmx1024m -cp target/security-evaluation-se2-repeat-build/se2.jar org.contactaware.security.simulation.SecurityEvaluationSe2Main --run --out target/security-evaluation-se2-repeat

Registration must use a new or empty directory. The separate run verifies the JAR,
protocol, models, schedule, Java version, heap and all cases, and refuses to replace
an existing summary. Preserve the JAR between registration and execution.
The original target/security-evaluation-se2-build/se2.jar must never be replaced
by a new build. Use the separate repeat-build location above, or execute the
already frozen original JAR directly against a new registration directory.
For an offline build use -Dmaven.repo.local=.tools/m2 -o if dependencies are cached.
The verified ISS schedule and source snapshots are required; the evidence package
preserves their relative paths. Java tests that validate this registered ISS grid
also require that dataset. The experiment does not fetch fresh geometry.

Independent audit and plots:
  python -X utf8 scripts/audit_security_evaluation_se2.py target/security-evaluation-se2-repeat --out target/security-evaluation-se2-repeat-audit
  python -X utf8 scripts/plot_security_evaluation_se2.py --audit target/security-evaluation-se2-repeat-audit/independent-audit.json --manifest target/security-evaluation-se2-repeat/manifest.json --output target/security-evaluation-se2-repeat-figures
  python -X utf8 -m unittest discover -s scripts -p "test_*security_evaluation*.py"

Validation and packaging of this fixed local run, after the complete audit,
all six figures, visual review, report and actual test logs exist:
  python -X utf8 scripts/verify_security_artifacts_se2.py
  python -X utf8 scripts/package_security_evaluation_se2.py
The latter refuses to overwrite existing SE2 ZIPs. Both preserve the frozen
SE1/v2 artifacts; their hashes are checked. For comparison with existing SE1
evidence add --se1-reference target/security-evaluation-se1 to the audit command.
These preservation checks require the prior SE1/v2 JARs and archives in the
original workspace. Their hashes, but not those legacy archives themselves,
are included in the SE2 package. Extracting only the SE2 evidence ZIP supports
SE2 reproduction and independent audit; it does not provide every legacy file
required by verify_security_artifacts_se2.py or the optional SE1-overlap audit.

Python audit uses the standard library and reuses unchanged SE1 accounting.
Plots use scripts/requirements-figures.txt and can use the existing local
.tools/figure-python packages. No files are uploaded by these scripts.

New Java classes:
  SecurityEvaluationSe2Fixtures: immutable physical configurations, preserved
    witness history, verified ISS fragments, schedule-only attack points, explicit
    notice-duration profiles.
  SecurityEvaluationSe2Main: registration, verified execution, shared policy
    adapters, own-policy controls and explicit incomplete/error status.
No executor, policy objective, lifecycle constraints or compromise semantics were changed.

Interpretation:
  PREPARE is distinct from INSTALL and SWITCH. Exposure before and after disclosure
  is separate. Censored recovery is not zero, NO_TARGET is not successful recovery,
  incomplete searches have no final quality score. Grid fractions are descriptive,
  not probabilities. Hardware costs and independent fresh keys/management protection
  remain model assumptions. Search wall time does not consume simulated contacts.
  The five-second search limit is cooperative and applies per decision. It is
  neither a hard external deadline nor a limit on the total controller runtime.
  Alternative WAIT can cause thousands of decisions during ISS contact gaps.
  search-timing-anomalies.json flags elapsed times above twice the registered
  budget for explanation only; it does not remove or alter observations.
