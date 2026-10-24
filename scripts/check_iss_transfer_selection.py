#!/usr/bin/env python3
"""Read-only schedule eligibility check. Never launches a policy or changes a model."""
import argparse
import csv
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'target/security-evaluation-iss-transfer'
DOC = ROOT / 'docs/security-evaluation-iss-transfer'
SCHEDULE = ROOT / 'data/runs/iss-20261010/schedule/schedule.json'
SELECTED = SCHEDULE.with_name('selected-contacts.json')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def rel(path):
    return path.relative_to(ROOT).as_posix()


def preserved():
    previous = ROOT / 'target/security-evaluation-c3'
    hashes = read(previous / 'preserved-inputs.json')
    hashes.update(read(previous / 'manifest.json')['files'])
    artifact_path = ROOT / 'docs/security-evaluation-c3/artifact-manifest.json'
    artifact = read(artifact_path)
    hashes[rel(previous / 'manifest.json')] = artifact['manifestSha256']
    hashes[rel(artifact_path.parent / artifact['archive'])] = artifact['archiveSha256']
    hashes[rel(artifact_path)] = sha(artifact_path)
    for name, expected in hashes.items():
        assert sha(ROOT / name) == expected, name
    return hashes


def scan():
    if OUT.exists():
        raise ValueError('Refusing to replace existing selection evidence')
    history = preserved()
    schedule = read(SCHEDULE)
    contacts = schedule['contacts']
    assert len(contacts) == 46 and contacts == read(SELECTED)
    # Compare ISO instants at nanosecond precision, without floating-point conversion.
    from audit_security_evaluation import instant
    assert all(instant(a['start']) <= instant(b['start']) for a, b in zip(contacts, contacts[1:]))
    assert all(instant(w['start']) < instant(w['end']) for w in contacts)
    old_manifest = ROOT / 'docs/security-evaluation-se2/manifest.json'
    previous = read(old_manifest)
    assert previous['scheduleSha256'] == sha(SCHEDULE)
    old_starts = sorted({int(c['provenance']['fragmentStartIndex']) for c in previous['configurations']
                         if 'fragmentStartIndex' in c['provenance']
                         and c['provenance']['fragmentCount'] == '3'})
    assert old_starts == [0, 3, 6]
    metrics_path = ROOT / 'docs/security-evaluation-se2/tables/metrics.csv'
    with metrics_path.open(encoding='utf-8', newline='') as f:
        old_rows = [dict(r, csvLine=i) for i, r in enumerate(csv.DictReader(f), 2)
                    if r['case'] in {f'iss3-cp{cp}-{p}-none' for cp in (5400, 7200) for p in ('next', 'multi')}]
    assert len(old_rows) == 4 and all(r['status'] == 'FINISHED' for r in old_rows)
    rows = []
    for i in range(len(contacts) - 2):
        w = contacts[i:i + 3]
        aba = w[0]['groundStationId'] == w[2]['groundStationId'] != w[1]['groundStationId']
        row = dict(startIndex=i, endIndex=i + 2, isABA=aba, previouslyStudiedTriple=i in old_starts,
                   eligible=aba and i not in old_starts,
                   reason='ALREADY_STUDIED' if aba and i in old_starts else 'ELIGIBLE' if aba else 'NOT_ABA')
        for j, window in enumerate(w, 1):
            for field in ('id', 'groundStationId', 'start', 'end'):
                row[f'c{j}_{field}'] = window[field]
        rows.append(row)
    eligible = [r for r in rows if r['eligible']]
    aba_starts = [r['startIndex'] for r in rows if r['isABA']]
    assert aba_starts == [0] and not eligible, 'Inputs differ from this bounded no-eligible-fragment finding'
    alternatives = sorted((j - i + 1, i, j) for i in range(len(contacts)) for j in range(i + 2, len(contacts))
        if contacts[i]['groundStationId'] == contacts[j]['groundStationId']
        and any(w['groundStationId'] != contacts[i]['groundStationId'] for w in contacts[i:j + 1])
        and not (j == i + 2 and i in old_starts))
    length, first, last = alternatives[0]
    assert (length, first, last) == (4, 3, 6)
    station_path = ROOT / 'data/runs/iss-20261010/orekit/stations.json'
    stations = {s['id']: s['name'] for s in read(station_path)}
    inputs = [SCHEDULE, SELECTED, station_path, old_manifest, metrics_path,
              ROOT / 'docs/security-evaluation/protocol.md', ROOT / 'docs/security-evaluation-se2/protocol.md',
              ROOT / 'docs/security-evaluation-se22/protocol.md', Path(__file__)]
    OUT.mkdir(parents=True)
    DOC.mkdir(parents=True, exist_ok=True)
    save(OUT / 'preserved-inputs.json', history)
    # Compact copy of the 46 source windows makes the enumeration independently inspectable.
    (OUT / 'selected-contacts.json').write_bytes(SELECTED.read_bytes())
    result = dict(status='NO_ELIGIBLE_FRAGMENT', selectionDate='2026-10-24',
        rule='Earliest consecutive, previously unstudied three-contact A-B-A with A != B; no window removal',
        schedule=rel(SCHEDULE), scheduleSha256=sha(SCHEDULE), sourceContacts=46, checkedTriples=len(rows),
        abaTriples=len(aba_starts), eligibleTriples=len(eligible), previousThreeContactStarts=old_starts,
        excludedTriple=dict(indices=[0, 1, 2], contacts=contacts[:3], historicalFinishedRows=old_rows),
        proposedAlternative=dict(status='PROPOSED_NOT_REGISTERED_NOT_EXECUTED', indices=list(range(first, last + 1)),
            contacts=contacts[first:last + 1], stationNames={w['groundStationId']: stations[w['groundStationId']] for w in contacts[first:last + 1]},
            criterion='Fewest contacts in another contiguous return block; earliest start breaks ties',
            horizonCaveat='In C1 H=2 cannot see C4. To test anticipation of the return from C1, Multi would require H=3; not authorized or applied here.'),
        policyRuns=0, registeredRuns=0, newJar=None, mavenTests='NOT_RUN_NO_CODE_CHANGE', watchdog='NOT_RUN_NO_SERIES',
        gitBaseCommit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        gitStatusAtScan=subprocess.check_output(['git', 'status', '--short'], cwd=ROOT, text=True),
        pythonVersion=sys.version, inputs={rel(p): sha(p) for p in inputs}, preservedFileCount=len(history))
    save(DOC / 'selection.json', result)
    with (DOC / 'triples.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    save(OUT / 'preservation-check.json', dict(status='PASS', files=len(preserved())))
    print(json.dumps({k: result[k] for k in ('status', 'checkedTriples', 'abaTriples', 'eligibleTriples', 'policyRuns', 'preservedFileCount')}))


def package():
    result = read(DOC / 'selection.json')
    for name, expected in result['inputs'].items():
        assert sha(ROOT / name) == expected, name
    assert preserved() == read(OUT / 'preserved-inputs.json')
    files = [p for p in DOC.iterdir() if p.is_file() and p.name != 'artifact-manifest.json']
    files += [p for p in OUT.iterdir() if p.is_file() and p.suffix not in ('.zip', '.log') and p.name != 'manifest.json']
    files.append(Path(__file__))
    manifest = dict(status=result['status'], gitBaseCommit=result['gitBaseCommit'], newJar=None,
                    policyRuns=0, files={rel(p): sha(p) for p in files})
    save(OUT / 'manifest.json', manifest)
    files.append(OUT / 'manifest.json')
    archive = OUT / 'selection-evidence.zip'
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as z:
        for p in files:
            z.write(p, rel(p))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for p in files:
            assert hashlib.sha256(z.read(rel(p))).hexdigest() == sha(p)
    save(DOC / 'artifact-manifest.json', dict(archive=rel(archive), archiveSha256=sha(archive),
        archiveBytes=archive.stat().st_size, manifestSha256=sha(OUT / 'manifest.json'), integrity='PASS',
        policyRuns=0, preservedFiles=len(preserved())))
    print('PASS', rel(archive), archive.stat().st_size, sha(archive))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['scan', 'package'])
    globals()[parser.parse_args().mode]()
