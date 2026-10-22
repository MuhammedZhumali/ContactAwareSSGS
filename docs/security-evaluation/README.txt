ContactAwareSS — security evaluation se1, 22 October 2026

Start with report.md (Russian), protocol.md (registered model and matrix), and
normative-basis.md (verified NIST Rev.5 basis and additional assumptions).

104 registered paired cases: 104 FINISHED. 317 Maven tests pass. Independent
Python audit reconstructs metrics from journals; 8 audit oracle tests pass.
The artifacts model consequences of stipulated key disclosure, not cryptographic
strength, real-world attack probability, production readiness or NIST compliance.

Publication artifacts:
  figures/01_no_attack_service_cost.{pdf,svg,png}
  figures/02_undetected_exposure_service.{pdf,svg,png}
  figures/03_detected_exposure_service.{pdf,svg,png}
  figures/04_detection_recovery_cost.{pdf,svg,png}
  figures/captions.txt, provenance.json
  tables/metrics.csv, pairwise.csv, win-tie-loss.csv, excluded.csv
  tables/independent-audit.json
PDF: vector, embedded fonts. SVG: editable text. PNG: 300 dpi.
Figures use English labels; F/P/N/M mean FixedPeriod/PerContact/Next/Multi.
Read exposure alongside service. Censored recovery is not zero. Net extra
costs/lost service are signed differences against the same policy without attack.

Frozen executable:
  target/security-evaluation-build/security-evaluation-se1.jar
  SHA256 b304138d4f6df1b040725affabc6e0a1905d826aa8f299bfe32f2832fa2a38aa
Raw matrix/journals/decisions/models:
  target/security-evaluation-se1/
Manifest records protocol hash, exact budgets, Java version and executable hash.

Reproduce from repository root with Java 21 (use a NEW output directory):
  java -Xmx1024m -cp target/security-evaluation-build/security-evaluation-se1.jar org.contactaware.security.simulation.SecurityEvaluationMain --out target/security-evaluation-repeat
  python -X utf8 scripts/audit_security_evaluation.py target/security-evaluation-repeat --out target/security-evaluation-repeat-audit
  python -X utf8 scripts/test_audit_security_evaluation.py
  python -X utf8 scripts/plot_security_evaluation.py --audit target/security-evaluation-repeat-audit/independent-audit.json --output target/security-evaluation-repeat-figures

Python audit requires only the standard library. Install plotting dependencies
from scripts/requirements-figures.txt when not already available. This workspace
also resolves them from .tools/figure-python. Search uses 100000 nodes/5s per
decision; timings can vary across machines and can change bounded completion.
Unfinished results must not be interpreted as final security/availability scores.

Build/test current sources:
  .\mvnw.cmd verify
Validated offline Windows command in this workspace:
  $env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
  .\mvnw.cmd '-Dmaven.repo.local=.tools/m2' -o -B -ntp '-DargLine=-Xmx1024m' verify
Existing HTTP import tests need a local loopback listener. No external API or
new orbital download is used. The initial sandboxed run blocked loopback; the
full repeat with local-listener permission passed all 317 tests in 57.375 s.
The log is target/security-evaluation-maven-verified.log.

Separate time-model sensitivity:
  java -Xmx1024m -cp target/security-time-model-build/time-model.jar org.contactaware.security.simulation.TimeModelSensitivityMain target/security-time-model-repeat
  python -X utf8 scripts/verify_time_model_sensitivity.py target/security-time-model-repeat --out target/security-time-model-repeat/audit.json
The frozen timing JAR predates incident support. Its default-v2 controls and
opt-in extra-WAIT measurements are archived separately; see time-model/report.md.
Normal se1 experiments use the original v2 timing rule. v2-regression.json checks
current no-attack X/Y behavior against prior frozen artifacts.

Packages:
  research-figures-se1.zip: figures, captions, tables, protocols, reports/audits.
  experiment-evidence-se1.zip: those files plus actual journals, both frozen new
  JARs, source/tests/scripts, Maven reports and all exact schedule input files
  needed by ScheduleFiles.readVerified, with original relative paths.
Extract the evidence ZIP into a new directory and run the frozen Java command
there. Original 46-window schedule and dataset/snapshot hashes are preserved;
only its first three contacts enter this security matrix. Public-source
attribution/license information remains in the input metadata and root README.
Nothing was uploaded or published. Archive CRCs and hashes are in artifact-manifest.json.

Scientific limits: independent new key generations and protected management
delivery are assumptions; keys are metadata only. No persistent compromise,
root compromise, injection, replay, jamming or unsolicited autonomous downlink.
An already committed downlink continues emitting until frame end after GS-only
notice, while useful delivery is rejected. Interrupted-frame key material is
irrevocably retired. Search latency is not charged to the physical simulation.
All numerical periods/CPU/radio parameters are model choices, not NIST mandates.
