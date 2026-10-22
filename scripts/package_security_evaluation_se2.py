#!/usr/bin/env python3
"""Package audited SE2 artifacts and exact verified inputs, without overwriting prior archives."""
import json
from pathlib import Path
import zipfile

import verify_security_artifacts_se2 as validation

ROOT, DOCS, RAW = validation.ROOT, validation.DOCS, validation.RAW
sha, load, require = validation.sha, validation.load, validation.require


def collect_tree(mapping, path):
    require(path.is_dir(), "Missing evidence directory: " + str(path))
    for file in sorted(path.rglob("*")):
        if file.is_file() and "__pycache__" not in file.parts and file.suffix != ".zip":
            mapping[file.relative_to(ROOT).as_posix()] = file


def archive(name, files):
    path = DOCS / name
    if path.exists():
        raise FileExistsError("Refusing to overwrite SE2 archive: " + str(path))
    with zipfile.ZipFile(path, "x", zipfile.ZIP_DEFLATED, compresslevel=9) as output:
        for relative, file in sorted(files.items()):
            output.write(file, relative)
    with zipfile.ZipFile(path) as opened:
        require(opened.testzip() is None, "ZIP CRC check failed: " + name)
        require(set(opened.namelist()) == set(files), "ZIP entry inventory mismatch: " + name)
    return {"file": name, "bytes": path.stat().st_size, "entries": len(files), "sha256": sha(path)}


def include_verified_inputs(evidence):
    # Exact transitive set consumed by ScheduleFiles.readVerified; retained relative layout.
    schedule_path = ROOT / "data/runs/iss-20261010/schedule/schedule.json"
    schedule = load(schedule_path)
    dataset_path = (schedule_path.parent / schedule["datasetSource"]).resolve()
    require(dataset_path.is_relative_to(ROOT), "Dataset leaves repository")
    require(sha(dataset_path) == schedule["inputDatasetSha256"], "Schedule dataset hash mismatch")
    dataset = load(dataset_path)
    selected = {schedule_path, dataset_path}
    for source in dataset["sources"]:
        file = validation.within(dataset_path.parent, source["file"])
        require(sha(file) == source["sha256"], "Verified dataset source hash mismatch: " + str(file))
        selected.add(file)
        sidecar = file.with_name(file.name + ".source.json")
        if sidecar.exists():
            selected.add(sidecar)
    for file in selected:
        evidence[file.relative_to(ROOT).as_posix()] = file
    return {file.relative_to(ROOT).as_posix(): sha(file) for file in sorted(selected)}


def main():
    names = ("research-figures-se2.zip", "experiment-evidence-se2.zip")
    for name in names:
        require(not (DOCS / name).exists(), "Use a new archive version; refusing to replace " + name)
    quality = validation.verify()
    quality_path = DOCS / "quality-check.json"
    quality_path.write_text(json.dumps(quality, indent=2) + "\n", encoding="utf-8")
    publication = {}
    for path in (DOCS / "figures", DOCS / "tables"):
        collect_tree(publication, path)
    for name in ("report.md", "mechanisms.md", "protocol.md", "manifest.json", "quality-check.json", "README.txt"):
        publication[(DOCS / name).relative_to(ROOT).as_posix()] = DOCS / name
    evidence = dict(publication)
    for path in (RAW, ROOT / "src", ROOT / "scripts", ROOT / "target/surefire-reports", ROOT / ".mvn/wrapper"):
        collect_tree(evidence, path)
    for relative in ("pom.xml", "README.md", "mvnw", "mvnw.cmd",
                     "target/security-evaluation-se2-build/se2.jar",
                     "target/security-evaluation-se2-maven.log", "target/security-evaluation-se2-python-tests.log",
                     "target/security-evaluation-se2-run.log"):
        file = ROOT / relative
        require(file.is_file(), "Missing reproducibility input: " + relative)
        evidence[relative] = file
    inputs = include_verified_inputs(evidence)
    hashes = {relative: sha(path) for relative, path in sorted(evidence.items())}
    hashes_path = DOCS / "evidence-file-hashes.json"
    hashes_path.write_text(json.dumps(hashes, indent=2) + "\n", encoding="utf-8")
    evidence[hashes_path.relative_to(ROOT).as_posix()] = hashes_path
    archives = [archive(names[0], publication), archive(names[1], evidence)]
    result = {"archives": archives, "protocolSha256": quality["protocolSha256"],
              "executableSha256": quality["executableSha256"], "frozenSe1V2AndInputsVerified": validation.FROZEN,
              "registeredRuns": quality["registeredRuns"], "runStatuses": quality["runStatuses"],
              "inputFilesIncluded": inputs, "evidenceFileHashesSha256": sha(hashes_path),
              "qualityCheckSha256": sha(quality_path),
              "scope": "Local research package; no upload or publication. All registered statuses retained; final quality excludes unfinished runs."}
    (DOCS / "artifact-manifest.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
