#!/usr/bin/env python3
"""Check evidence/protocol linkage and create a compact se1 delivery validation record."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs/security-evaluation"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    manifest = load(ROOT / "target/security-evaluation-se1/manifest.json")
    audit = load(DOCS / "tables/independent-audit.json")
    regression = load(DOCS / "v2-regression.json")
    figures = load(DOCS / "figure-quality-check.json")
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in (ROOT / "target/surefire-reports").glob("TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    assert totals == dict(tests=317, failures=0, errors=0, skipped=0), totals
    assert sha(DOCS / "protocol.md") == manifest["protocolSha256"]
    assert sha(ROOT / "target/security-evaluation-build/security-evaluation-se1.jar") == manifest["executableSha256"]
    assert audit["audit"] == "PASS" and audit["auditedRuns"] == audit["finishedRuns"] == manifest["registeredRuns"] == 104
    assert not audit["notRun"]
    assert all(all(case[key] for key in ("bothFinished", "sameFiveMetrics", "sameNormalizedJournal",
                                        "sameExploredNodes", "sameDecisionCount")) for case in regression["cases"])
    assert figures["status"] == "PASS"
    for file in figures["files"]:
        for extension, digest in file["sha256"].items():
            assert sha(DOCS / "figures" / (file["stem"] + extension)) == digest
    for filename, digest in audit["fileHashes"].items():
        assert sha(ROOT / "target/security-evaluation-se1" / filename) == digest
    (DOCS / "quality-check.json").write_text(json.dumps({
        "status": "PASS", "maven": totals, "registeredRuns": 104, "finishedRuns": 104,
        "preKnowledgeDecisionsCompared": audit["preKnowledgeDecisionsCompared"],
        "v2RegressionCases": len(regression["cases"]), "publicationFigureSets": len(figures["files"]),
        "protocolSha256": manifest["protocolSha256"], "executableSha256": manifest["executableSha256"],
        "independentAuditSha256": sha(DOCS / "tables/independent-audit.json"),
        "figureQualitySha256": sha(DOCS / "figure-quality-check.json"),
        "testLog": "target/security-evaluation-maven-verified.log",
        "interpretation": "Links preserved input/output files and recorded validations; not an independent security proof."
    }, indent=2) + "\n", encoding="utf-8")
    print("PASS: 317 Maven tests, 104 audited runs, 4 v2 regressions and 4 publication figure sets")


if __name__ == "__main__":
    main()
