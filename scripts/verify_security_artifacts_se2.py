#!/usr/bin/env python3
"""Bind SE2 registration, factual audit, figures and actual test reports; preserve SE1/v2."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs/security-evaluation-se2"
RAW = ROOT / "target/security-evaluation-se2"
JAR = ROOT / "target/security-evaluation-se2-build/se2.jar"
MAVEN_LOG = ROOT / "target/security-evaluation-se2-maven.log"
PYTHON_LOG = ROOT / "target/security-evaluation-se2-python-tests.log"
FROZEN = {
    "target/security-evaluation-build/security-evaluation-se1.jar": "b304138d4f6df1b040725affabc6e0a1905d826aa8f299bfe32f2832fa2a38aa",
    "docs/security-evaluation/research-figures-se1.zip": "62f0e0db371fdea46fdd719da0ec2329e7a68991e5b16d1776ea013e8d70d6c7",
    "docs/security-evaluation/experiment-evidence-se1.zip": "93f3038e7f8d054f7c97056c765e13a7403e3569317764a41fb22d468ef8c440",
    "target/search-scaling-build/experiment-v2.jar": "789261d4e7ad697c69c65f17f5a73cd498d578be64f187b20933715f680d80cd",
    "docs/search-scaling/research-figures-v2.zip": "1ee9b3cc9e7e574781081440db0e969149720df3de8a4db3d93808863f337160",
    "docs/search-scaling/experiment-evidence-v2.zip": "0003bb8c897e543d5c8034acc483e00f8890c476963ff984904335cd377ed537",
    "data/runs/iss-20261010/schedule/schedule.json": "fe8a56aebdae09d31dcb3097f1c03ed960a21860edb727643db4310788c64862",
}


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def within(base, relative):
    path = (base / relative).resolve()
    require(path.is_relative_to(base.resolve()), "Evidence path leaves its directory: " + str(relative))
    return path


def check_tests():
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    reports = sorted((ROOT / "target/surefire-reports").glob("TEST-*.xml"))
    require(bool(reports), "Missing Maven XML reports")
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    log = MAVEN_LOG.read_text(encoding="utf-8")
    require("BUILD SUCCESS" in log and "BUILD FAILURE" not in log, "Maven verify log is not successful")
    recorded = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", log)
    require(bool(recorded) and tuple(map(int, recorded[-1])) == tuple(totals.values()),
            "Maven log total disagrees with current XML reports")
    require(totals["tests"] > 0 and all(totals[k] == 0 for k in ("failures", "errors", "skipped")),
            "Maven contains failed, errored or skipped tests")
    python = PYTHON_LOG.read_text(encoding="utf-8")
    runs = re.findall(r"Ran (\d+) tests? in", python)
    require(len(runs) == 1 and int(runs[0]) > 0 and re.search(r"(?m)^OK\s*$", python),
            "Missing successful combined Python unittest report")
    return {"maven": totals, "mavenSuites": len(reports), "python": {"tests": int(runs[0]), "status": "PASS"},
            "testLogs": {MAVEN_LOG.relative_to(ROOT).as_posix(): sha(MAVEN_LOG),
                         PYTHON_LOG.relative_to(ROOT).as_posix(): sha(PYTHON_LOG)}}


def verify():
    manifest, summary = load(RAW / "manifest.json"), load(RAW / "summary.json")
    audit = load(DOCS / "tables/independent-audit.json")
    figures = load(DOCS / "figures/provenance.json")
    require(manifest["version"] == "se2", "Wrong registration version")
    cases = {c["name"] for c in manifest["cases"]}
    require(len(cases) == len(manifest["cases"]) == manifest["registeredRuns"] == 488,
            "SE2 requires exactly its 488 preregistered runs")
    require(set(summary) == cases and not audit["notRun"], "Not all registered runs were attempted")
    require(audit["audit"] == "PASS" and audit["independentOfJavaCollectors"] is True
            and audit["auditedRuns"] == len(cases) and set(audit["runs"]) == cases,
            "Every attempted case needs an independent status/evidence audit")
    statuses = Counter(row["status"] for row in summary.values())
    require(set(statuses) <= {"FINISHED", "SEARCH_INCOMPLETE", "NO_FEASIBLE_PLAN", "EXECUTION_STALLED", "ERROR"},
            "Unexpected final run status")
    require(audit["finishedRuns"] == statuses["FINISHED"]
            and audit["journalAuditedRuns"] == len(cases) - statuses["ERROR"], "Audit coverage disagrees")
    for name, result in summary.items():
        require(audit["runs"][name]["status"] == result["status"], "Audited status changed: " + name)
        if result["status"] != "FINISHED":
            require(all(result[k] is None for k in ("simulationMetrics", "securityMetrics", "pairedDelta")),
                    "Unfinished case has final quality metrics: " + name)
    require(sha(DOCS / "protocol.md") == manifest["protocolSha256"] == audit["protocolSha256"],
            "Protocol changed after registration")
    require(sha(JAR) == manifest["executableSha256"] == audit["executableSha256"],
            "Frozen executable changed")
    require(sha(DOCS / "manifest.json") == sha(RAW / "manifest.json"), "Documentation manifest differs from registration")
    for relative, digest in audit["fileHashes"].items():
        require(sha(within(RAW, relative)) == digest, "Audited raw evidence changed: " + relative)
    for relative, digest in manifest["modelSha256"].items():
        require(sha(within(RAW, relative)) == digest, "Preregistered model changed: " + relative)
    require(figures["sourceAuditSha256"] == sha(DOCS / "tables/independent-audit.json")
            and figures["sourceManifestSha256"] == sha(RAW / "manifest.json")
            and figures["protocolSha256"] == manifest["protocolSha256"]
            and figures["executableSha256"] == manifest["executableSha256"], "Figure source bindings changed")
    require(figures["scriptSha256"] == sha(ROOT / "scripts/plot_security_evaluation_se2.py"), "Figure script changed after plotting")
    for relative, digest in figures["files"].items():
        require(sha(within(DOCS / "figures", relative)) == digest, "Figure artifact changed: " + relative)
    structure = load(DOCS / "figures/artifact-qa.json")
    visual = load(DOCS / "figures/visual-review.json")
    require(structure["status"] == visual["status"] == "PASS", "Missing structural/visual figure review")
    figure_names = {f["name"] for f in figures["figures"]}
    require(len(figure_names) == len(figures["figures"]) and bool(figure_names), "Missing/duplicate publication figures")
    require({f["name"] for f in structure["checks"]} == figure_names
            and set(visual["reviewedFigures"]) == figure_names, "Figure QA coverage differs")
    require("visual-review.json" in figures["files"], "Visual review is not hash-bound to figure provenance")
    for name in figure_names:
        require(all(name + extension in figures["files"] for extension in (".pdf", ".svg", ".png")),
                "Missing publication figure format: " + name)
    for relative, digest in FROZEN.items():
        require(sha(ROOT / relative) == digest, "Frozen SE1/v2/input artifact changed: " + relative)
    require(manifest["scheduleSha256"] == FROZEN["data/runs/iss-20261010/schedule/schedule.json"], "Wrong frozen schedule")
    for name in ("report.md", "mechanisms.md", "README.txt"):
        require((DOCS / name).is_file() and (DOCS / name).stat().st_size > 0, "Missing final deliverable: " + name)
    tests = check_tests()
    return {"status": "PASS", **tests, "registeredRuns": len(cases), "runStatuses": dict(sorted(statuses.items())),
            "finishedRuns": statuses["FINISHED"], "notRun": 0,
            "preKnowledgeDecisionsCompared": audit["preKnowledgeDecisionsCompared"],
            "publicationFigureSets": len(figure_names), "protocolSha256": manifest["protocolSha256"],
            "executableSha256": manifest["executableSha256"],
            "independentAuditSha256": sha(DOCS / "tables/independent-audit.json"),
            "figureProvenanceSha256": sha(DOCS / "figures/provenance.json"),
            "frozenSe1V2AndInputsVerified": FROZEN,
            "interpretation": "Binds recorded validation and preserved artifacts. Incomplete/ERROR cases remain excluded from final quality comparisons. Not a security proof."}


def main():
    result = verify()
    (DOCS / "quality-check.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
