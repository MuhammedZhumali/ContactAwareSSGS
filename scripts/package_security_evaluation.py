#!/usr/bin/env python3
"""Package se1 evidence, pinned runtime inputs and publication figures without changing v2."""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "docs/security-evaluation"
RAW = ROOT / "target/security-evaluation-se1"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def collect_tree(mapping, path):
    for file in sorted(path.rglob("*")):
        if file.is_file() and "__pycache__" not in file.parts and file.suffix != ".zip":
            mapping[file.relative_to(ROOT).as_posix()] = file


def archive(name, files):
    path = DOCS / name
    if path.exists():
        raise FileExistsError("Use a new archive version; refusing to replace " + str(path))
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as out:
        for rel, file in sorted(files.items()):
            out.write(file, rel)
    with zipfile.ZipFile(path) as opened:
        if opened.testzip() is not None:
            raise ValueError("ZIP CRC check failed")
    return {"file": name, "bytes": path.stat().st_size, "entries": len(files), "sha256": sha(path)}


def main():
    audit = json.loads((DOCS / "tables/independent-audit.json").read_text(encoding="utf-8"))
    manifest = json.loads((RAW / "manifest.json").read_text(encoding="utf-8"))
    if audit["audit"] != "PASS" or audit["auditedRuns"] != manifest["registeredRuns"]:
        raise ValueError("All registered runs require an independent audit")
    if sha(DOCS / "protocol.md") != manifest["protocolSha256"]:
        raise ValueError("Protocol changed after registration")
    jar = ROOT / "target/security-evaluation-build/security-evaluation-se1.jar"
    if sha(jar) != manifest["executableSha256"]:
        raise ValueError("Executable changed after measurement")
    frozen = {
        "target/search-scaling-build/experiment-v2.jar": "789261d4e7ad697c69c65f17f5a73cd498d578be64f187b20933715f680d80cd",
        "docs/search-scaling/research-figures-v2.zip": "1ee9b3cc9e7e574781081440db0e969149720df3de8a4db3d93808863f337160",
        "docs/search-scaling/experiment-evidence-v2.zip": "0003bb8c897e543d5c8034acc483e00f8890c476963ff984904335cd377ed537",
        "data/runs/iss-20261010/schedule/schedule.json": "fe8a56aebdae09d31dcb3097f1c03ed960a21860edb727643db4310788c64862",
    }
    for rel, expected in frozen.items():
        if sha(ROOT / rel) != expected:
            raise ValueError("Frozen v2/input artifact changed: " + rel)
    publication = {}
    for path in (DOCS / "figures", DOCS / "tables", DOCS / "time-model"):
        collect_tree(publication, path)
    for filename in ("report.md", "protocol.md", "normative-basis.md", "v2-regression.json", "quality-check.json", "figure-quality-check.json", "README.txt"):
        publication["docs/security-evaluation/" + filename] = DOCS / filename
    evidence = dict(publication)
    for path in (RAW, ROOT / "target/security-time-model", ROOT / "src", ROOT / "scripts", ROOT / "target/surefire-reports"):
        collect_tree(evidence, path)
    for rel in ("pom.xml", "README.md", "mvnw", "mvnw.cmd", "target/security-evaluation-build/security-evaluation-se1.jar",
                "target/security-time-model-build/time-model.jar", "target/security-evaluation-maven-verified.log",
                "target/security-evaluation-se1-run.log", "target/security-time-model-tests.log"):
        evidence[rel] = ROOT / rel
    collect_tree(evidence, ROOT / ".mvn/wrapper")
    # Read-only exact transitive file set required by ScheduleFiles.readVerified.
    # Includes provenance/attribution already embedded in the public source snapshots.
    schedule_path = ROOT / "data/runs/iss-20261010/schedule/schedule.json"
    schedule = json.loads(schedule_path.read_text(encoding="utf-8"))
    source_path = (schedule_path.parent / schedule["datasetSource"]).resolve()
    if sha(source_path) != schedule["inputDatasetSha256"]:
        raise ValueError("Schedule dataset hash mismatch")
    source = json.loads(source_path.read_text(encoding="utf-8"))
    for file in (schedule_path, source_path):
        evidence[file.relative_to(ROOT).as_posix()] = file
    for item in source["sources"]:
        file = (source_path.parent / item["file"]).resolve()
        if not file.is_relative_to(source_path.parent) or sha(file) != item["sha256"]:
            raise ValueError("Input snapshot hash/path mismatch")
        evidence[file.relative_to(ROOT).as_posix()] = file
        sidecar = file.with_name(file.name + ".source.json")
        if sidecar.exists():
            evidence[sidecar.relative_to(ROOT).as_posix()] = sidecar
    file_hashes = {rel: sha(path) for rel, path in sorted(evidence.items())}
    hashes_path = DOCS / "evidence-file-hashes.json"
    hashes_path.write_text(json.dumps(file_hashes, indent=2) + "\n", encoding="utf-8")
    evidence[hashes_path.relative_to(ROOT).as_posix()] = hashes_path
    archives = [archive("research-figures-se1.zip", publication), archive("experiment-evidence-se1.zip", evidence)]
    report = {"archives": archives, "frozenV2AndInputsVerified": frozen,
              "protocolSha256": manifest["protocolSha256"], "executableSha256": manifest["executableSha256"],
              "inputFilesIncluded": "Original schedule, verified dataset, all sources required by readVerified, retained relative layout",
              "scope": "Local research package; no artifact was uploaded or published"}
    (DOCS / "artifact-manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
