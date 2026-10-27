"""Package the bounded AITU revision without overwriting any earlier archive.

Only explicitly selected research material is included; no home-directory scan,
internal QA PDFs, installed dependencies, or unrelated working-tree changes.
"""
import ast
import datetime
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'target/aitu-revision'
DOC = ROOT / 'docs/aitu-revision'
OUT = ROOT / 'target/aitu-revision-delivery'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def collect(directory, excluded=()):
    return {p for p in directory.rglob('*') if p.is_file()
            and not any(part in excluded for part in p.relative_to(directory).parts)
            and p.suffix.lower() not in {'.pdf', '.pyc'}
            and not p.name.startswith('~$')}


def local_import_closure(entries):
    found, pending = set(), list(entries)
    while pending:
        path = pending.pop()
        if path in found:
            continue
        found.add(path)
        tree = ast.parse(path.read_text(encoding='utf-8-sig'), filename=str(path))
        names = []
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                names.extend(alias.name.split('.')[0] for alias in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                names.append(node.module.split('.')[0])
        pending.extend(ROOT / 'scripts' / (name + '.py') for name in names
                       if (ROOT / 'scripts' / (name + '.py')).is_file())
    return found


def package(name, files, kind, note, flatten=None):
    entries = {}
    for path in sorted(files):
        relative = path.relative_to(flatten or ROOT).as_posix()
        assert path.is_file() and not relative.startswith('../')
        assert '.git' not in path.parts and path.suffix.lower() != '.pdf'
        entries[relative] = {'bytes': path.stat().st_size, 'sha256': sha(path)}
    manifest = {
        'identity': name, 'kind': kind,
        'createdUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope': note, 'environment': read(BASE / 'final-environment.json'),
        'counts': {'newControllers': 110, 'finished': 105, 'searchIncomplete': 5,
                   'rootDiagnostics': 12, 'rootComplete': 11, 'rootIncomplete': 1,
                   'reusedLogicalCells': 6},
        'historicalCampaign': '108 attempts / 93 FINISHED / 15 PROCESS_TIMEOUT; separate population',
        'publicationStatus': 'New revision not yet publicly deposited; no new DOI',
        'files': entries,
    }
    path = OUT / (name + '.zip')
    with zipfile.ZipFile(path, 'x', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        archive.writestr('manifest.json', json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
        for source in sorted(files):
            archive.write(source, source.relative_to(flatten or ROOT).as_posix())
    # Stream every payload through SHA256; reading also checks ZIP CRC values.
    with zipfile.ZipFile(path) as archive:
        assert len(archive.namelist()) == len(entries) + 1
        for name_, record in entries.items():
            assert archive.getinfo(name_).file_size == record['bytes']
            with archive.open(name_) as stream:
                assert hashlib.file_digest(stream, 'sha256').hexdigest() == record['sha256'], name_
    digest = sha(path)
    (OUT / (name + '.sha256')).write_text(f'{digest}  {path.name}\n', encoding='utf-8')
    (OUT / (name + '-manifest.json')).write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    result = {'file': path.relative_to(ROOT).as_posix(), 'bytes': path.stat().st_size,
              'sha256': digest, 'payloadFiles': len(entries), 'allPayloadHashesAndCRC': 'PASS'}
    print(json.dumps(result), flush=True)
    return result


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    assert read(DOC / 'validation.json')['status'] == 'PASS'
    assert read(DOC / 'visual-review.json')['status'] == 'PASS'
    # Verify preservation before making any new archive.
    preserved = read(BASE / 'preservation.json')
    for name, expected in preserved.items():
        assert sha(ROOT / name) == expected, name
    docs = collect(DOC, {'__pycache__'})
    entry_scripts = list((ROOT / 'scripts').glob('*aitu*.py'))
    scripts = local_import_closure(entry_scripts)
    evidence = collect(BASE, {'document-qa', 'template-qa', '__pycache__'})
    # Render statistics are useful evidence; PDFs/preview images remain internal.
    evidence.add(BASE / 'document-qa/final2/render.json')
    evidence.add(BASE / 'template-qa/template-structure.json')
    frozen_names = ['final-v2.jar', 'final-v2-sources.zip', 'final-v2-sources.json',
                    'final-v2-manifest.json', 'final-v2-tests.json', 'final-v2-build.log',
                    'build-validation.json', 'protocol.json', 'summary.json', 'tables.json',
                    'workload-descriptors.json']
    frozen = {ROOT / 'target/final-research' / name for name in frozen_names}
    frozen |= {ROOT / 'target/final-research/models' / name
               for name in ['X5.json', 'demand-none.json', 'demand-late-detected.json']}
    frozen |= collect(ROOT / 'docs/final-research')
    full = docs | scripts | evidence | frozen | collect(ROOT / 'scripts/aitu')
    full.add(ROOT / 'scripts/render_aitu_word.ps1')
    # Every registered input/source/class must be shipped; no silent partial archive.
    for stage in ['stage1', 'stage2', 'stage3', 'stage4']:
        protocol = read(BASE / stage / 'protocol.json')
        for case in protocol['cases'] + protocol.get('reusedCells', []):
            assert ROOT / case['model'] in full
    for harness in ['harness-v1', 'seed-harness-v2']:
        manifest = read(BASE / harness / 'manifest.json')
        for group in ['sources', 'classFiles', 'classes']:
            for path in manifest.get(group, {}):
                assert ROOT / path in full, path
    results = [
        package('ContactAwareSS-AITU-article-20261028', docs, 'article and supplement',
                'Editable DOCX, LaTeX, figures, compact tables and reports; raw experiment evidence is in the separate reproduction ZIP.'),
        package('ContactAwareSS-AITU-Overleaf-20261028', collect(DOC / 'article-tex'), 'Overleaf sources',
                'Upload ZIP to Overleaf. Select main.tex for the article or supplement.tex for supplementary tables.', DOC / 'article-tex'),
        package('ContactAwareSS-AITU-reproduction-20261028', full, 'bounded campaign reproduction',
                'All new registered attempts, including incomplete results; frozen final-v2 executable and corresponding sources; no repeated legacy campaign logs or internal PDFs.')]
    validation = {'status': 'PASS', 'archives': results, 'historicalFilesUnchanged': len(preserved),
                  'pythonLocalImportClosure': [p.relative_to(ROOT).as_posix() for p in sorted(scripts)],
                  'boundary': 'Payload hash/CRC and dependency inventory verification. Not a repeated scientific campaign.'}
    (OUT / 'package-validation.json').write_text(json.dumps(validation, indent=2) + '\n', encoding='utf-8')
