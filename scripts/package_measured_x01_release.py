"""Freeze the reviewed X01 evidence and an exact Git source snapshot for release.

No experiments are run and existing archives are never overwritten. The input
archive is the validated local campaign package, not a GitHub source-only ZIP.
"""
import argparse
import hashlib
import io
import json
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'target/measured-delay-x01-delivery'
INPUT = BASE / 'ContactAwareSS-X01-measured-delay-20261028.zip'
INPUT_SHA = '56b8ceb2dda530a8064d056ffe4cb9f17e4cd6a08c628eee0b6403054c8840d3'
NAME = 'ContactAwareSS-X01-measured-delay-reproduction-20261028'
TAG = 'x01-measured-delay-20261028'
ARTICLE = 'docs/aitu-revision-v2/article.docx'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-commit', required=True)
    args = parser.parse_args()
    commit = subprocess.check_output(
        ['git', 'rev-parse', args.source_commit + '^{commit}'], cwd=ROOT, text=True).strip()
    assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip() == commit
    assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip(), 'Commit all publication changes first'
    assert file_sha(INPUT) == INPUT_SHA
    output_path = BASE / (NAME + '.zip')
    if output_path.exists():
        raise ValueError('Refusing to overwrite ' + str(output_path))
    source_bytes = subprocess.check_output(['git', 'archive', '--format=zip', commit], cwd=ROOT)
    payload = {}
    with zipfile.ZipFile(INPUT) as evidence, zipfile.ZipFile(io.BytesIO(source_bytes)) as source:
        old_manifest = json.loads(evidence.read('manifest.json'))
        assert evidence.testzip() is None
        for name, meta in old_manifest['payload'].items():
            content = evidence.read(name)
            assert len(content) == meta['bytes'] and digest(content) == meta['sha256'], name
        source_files = {i.filename for i in source.infolist() if not i.is_dir()}
        article = source.read(ARTICLE)
        assert article == (ROOT / 'target/measured-delay-x01-20261028/inputs/article-before.docx').read_bytes()
        with zipfile.ZipFile(output_path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as output:
            def put(name, data):
                assert name not in payload, name
                output.writestr(name, data)
                payload[name] = {'bytes': len(data), 'sha256': digest(data)}

            for item in evidence.infolist():
                if item.is_dir() or item.filename in source_files or item.filename in {'manifest.json', 'X01-START-HERE.txt'}:
                    continue
                put(item.filename, evidence.read(item))
            put('provenance/x01-prepublication-manifest.json', evidence.read('manifest.json'))
            put('provenance/aitu-v2-published-article.docx', evidence.read(ARTICLE))
            for name in sorted(source_files):
                put(name, source.read(name))
            note = (
                'Start with docs/measured-delay-x01/README.md and report.md.\n'
                'All 88 X01 attempts are retained, including the bounded incomplete offline control.\n'
                'The current article is the author-provided version, unchanged by this publication.\n'
                'It does not yet incorporate the X01 measured-delay results; the addendum is separate.\n'
                'The earlier published article is preserved under provenance/aitu-v2-published-article.docx.\n'
                'Frozen experiment identity and source publication commit are separate: consult manifests.\n'
            ).encode()
            put('X01-START-HERE.txt', note)
            manifest = {
                'version': TAG, 'sourceCommit': commit,
                'sourceTree': subprocess.check_output(['git', 'rev-parse', commit + '^{tree}'], cwd=ROOT, text=True).strip(),
                'sourceTreeCleanAtPackaging': True, 'sourceFiles': sorted(source_files),
                'inputArchiveSha256': INPUT_SHA,
                'campaignManifestSha256': old_manifest['campaignManifestSha256'],
                'articleSha256': digest(article), 'articleUpdatedWithX01': False,
                'articleIsUnchangedAuthorInput': True, 'payload': payload,
            }
            output.writestr('manifest.json', json.dumps(manifest, indent=2) + '\n')
    with zipfile.ZipFile(output_path) as verified:
        assert verified.testzip() is None
        for name, meta in payload.items():
            content = verified.read(name)
            assert len(content) == meta['bytes'] and digest(content) == meta['sha256'], name
    checksum = file_sha(output_path)
    output_path.with_suffix('.sha256').write_text(checksum + '  ' + output_path.name + '\n')
    result = {
        'status': 'PASS', 'sourceCommit': commit, 'tag': TAG,
        'archive': output_path.name, 'bytes': output_path.stat().st_size,
        'sha256': checksum, 'payloadFiles': len(payload), 'sourceFiles': len(source_files),
        'inputArchiveUnchanged': file_sha(INPUT) == INPUT_SHA,
        'articleUnchanged': digest((ROOT / ARTICLE).read_bytes()) == manifest['articleSha256'],
        'noNewScientificAttempts': True,
    }
    (BASE / 'release-validation.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
