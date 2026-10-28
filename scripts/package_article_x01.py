"""Validate the approved document edit and extend the frozen X01 evidence ZIP.

No Java execution or scientific rerun. Requires the retained article revision
inputs and final Word/Poppler rendering; no public release asset is overwritten.
"""
import ast
import hashlib
import json
import subprocess
import zipfile
from pathlib import Path

from lxml import etree as E
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'target/article-x01-revision-20261028'
BASE = ROOT / 'target/measured-delay-x01-release/ContactAwareSS-X01-measured-delay-reproduction-20261028.zip'
EXPECTED_BASE = '903175976ba3e563e1eae757f411ed5b2f6d54bff3166ba49aa852f6c48ae3ee'
OUT = ROOT / 'target/article-x01-delivery'
DOC = ROOT / 'docs/aitu-revision-v2/article.docx'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
      'm': 'http://schemas.openxmlformats.org/officeDocument/2006/math'}
W = '{' + NS['w'] + '}'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def canonical(node):
    return E.tostring(node, method='c14n') if node is not None else None


def validate_document():
    rev = json.loads((WORK / 'revision.json').read_text(encoding='utf-8'))
    assert sha(DOC.read_bytes()) == rev['docxSha256']
    assert sha((WORK / 'article-before.docx').read_bytes()) == rev['sourceSha256']
    with zipfile.ZipFile(WORK / 'article-before.docx') as old, zipfile.ZipFile(DOC) as new:
        assert set(old.namelist()) == set(new.namelist())
        changed = [n for n in old.namelist() if old.read(n) != new.read(n)]
        assert changed == ['word/document.xml']
        before = E.fromstring(old.read('word/document.xml')).find(W + 'body')
        after = E.fromstring(new.read('word/document.xml')).find(W + 'body')
    changes = {v['bodyIndex'] for v in rev['replacements']}
    assert len(after) == len(before) + 12
    for index, node in enumerate(before):
        updated = after[index + (4 if index > 55 else 0)
                        + (7 if index > 82 else 0) + (1 if index > 91 else 0)]
        if index not in changes:
            assert canonical(node) == canonical(updated), index
        else:
            assert canonical(node.find(W + 'pPr')) == canonical(updated.find(W + 'pPr'))
            assert [canonical(r.find(W + 'rPr')) for r in node.findall(W + 'r')] == [
                canonical(r.find(W + 'rPr')) for r in updated.findall(W + 'r')]
    assert len(after.xpath('.//m:oMath', namespaces=NS)) == 3
    content = json.loads((ROOT / 'docs/aitu-revision-v2/article-content.json').read_text(encoding='utf-8'))
    abstract = content['abstract']
    assert len(abstract.split()) == 261 and len(abstract) == 1668
    pdf = WORK / 'final-word-v2/article.pdf'
    pages = PdfReader(pdf).pages
    assert len(pages) == 15
    assert all((WORK / f'final-word-v2/page-{n:02}.png').exists() for n in range(1, 16))
    rendered = ' '.join(' '.join(p.extract_text().split()) for p in pages)
    for text in ['Measured decision-time protocol', 'Measured decision latency in X01',
                 '0 / 0 / 20', '0 / 5 / 15', '2.537', '32.426', 'all 40',
                 'CONTROLLER_TIME_LIMIT', 'without isolating', '0009-0008-4052-0672']:
        assert text in rendered, text
    assert 'remains untested' not in rendered
    assert '[TO BE ADDED AFTER PUBLIC DEPOSITION]' not in rendered
    # This is the recorded manual review, not an automated visual judgement.
    review = {'status': 'PASS', 'pagesInspected': list(range(1, 16)),
              'method': 'Microsoft Word read-only PDF export, Poppler 110 dpi; every PNG inspected',
              'observations': 'No clipping or overlap; repeated table headers, captions, figures, equations and page numbers readable.',
              'latexCompile': 'SUCCESS via built-in Codex compiler after final text synchronization'}
    write_json(WORK / 'visual-review.json', review)
    summary = json.loads((ROOT / 'docs/measured-delay-x01/tables/summary.json').read_text())
    assert summary['attempted_runs'] == 88
    assert summary['rules']['CONTINUE_WAIT']['S_losses'] == 20
    assert summary['rules']['CANONICAL']['S_ties'] == 5
    assert summary['rules']['CANONICAL']['S_losses'] == 15
    for name in ['update_article_x01.py', 'sync_article_x01_sources.py', 'package_article_x01.py']:
        ast.parse((ROOT / 'scripts' / name).read_text(encoding='utf-8'))
    validation = {k: v for k, v in rev.items() if k not in
                  ['replacements', 'methodsAdded', 'resultsAdded', 'discussionAdded', 'newTable', 'renderReview']}
    validation.update(status='PASS', pagesBefore=13, pagesAfter=15,
                      pdfSha256=sha(pdf.read_bytes()), renderReview=review,
                      synchronizedJsonAndTex=True, pythonSyntax='PASS',
                      mavenRerun=False, publishedArchivesModified=False,
                      historicalAudit='../aitu-revision-v2/validation.json')
    write_json(ROOT / 'docs/article-x01-update/validation.json', validation)
    return validation


def main():
    assert sha(BASE.read_bytes()) == EXPECTED_BASE
    validation = validate_document()
    OUT.mkdir(parents=True, exist_ok=True)
    output = OUT / 'ContactAwareSS-X01-article-updated-20261028.zip'
    if output.exists():
        raise FileExistsError('Keep existing archives; select a distinct output version.')
    overlay_names = [
        'README.md', 'docs/aitu-revision-v2/article.docx',
        'docs/aitu-revision-v2/article-content.json', 'docs/aitu-revision-v2/article-tex/main.tex',
        'docs/aitu-revision-v2/submission-checklist.md', 'docs/github-publication.md',
        'docs/measured-delay-x01/report.md', 'docs/measured-delay-x01/progress.md',
        'docs/measured-delay-x01/proposed-article-updates.md',
        'docs/article-x01-update/README.md', 'docs/article-x01-update/validation.json',
        'scripts/update_article_x01.py', 'scripts/sync_article_x01_sources.py',
        'scripts/package_article_x01.py',
        'target/measured-delay-x01-review/output/pdf/ContactAwareSS-X01-results-discussion.pdf',
        'target/measured-delay-x01-review/build_report.py',
        'target/measured-delay-x01-review/report-validation.json']
    for name in ['article-before.docx', 'article-content-before.json', 'before-body.json',
                 'main-before.tex', 'artifact.md', 'revision.json', 'source-sync.json',
                 'visual-review.json', 'render_word.ps1', 'packaged-render.log',
                 'packaged-render-authorized.log', 'final-word-v2/render.json']:
        overlay_names.append(f'target/article-x01-revision-20261028/{name}')
    overlays = {name: (ROOT / name).read_bytes() for name in overlay_names}
    overlays['ARTICLE-UPDATE-START-HERE.txt'] = (
        'Approved X01 manuscript update, 28 October 2026.\n'
        'Read docs/article-x01-update/README.md and validation.json.\n'
        'The article is updated; the experimental attempts and registered model are unchanged.\n'
        'The three-page results report was prepared before article approval and is retained as review history.\n'
        'Old validators and approval-status statements describe their original revisions.\n'
        'The published X01 asset is unchanged. This local package has its own manifest and checksum.\n'
        'The source scripts and retained original document inputs allow the edit to be reconstructed.\n'
        'No scientific or Maven rerun was performed for this document update.\n').encode()
    overlays['X01-START-HERE.txt'] = (
        'Start with ARTICLE-UPDATE-START-HERE.txt and docs/measured-delay-x01/README.md.\n'
        'All 88 X01 attempts, frozen executables and registered inputs are preserved.\n'
        'The article now includes the approved X01 results; its supplied predecessor is retained.\n').encode()
    payload = {}
    changed = []
    preserved = 0
    with zipfile.ZipFile(BASE) as source, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as dest:
        old_manifest_bytes = source.read('manifest.json')
        old_manifest = json.loads(old_manifest_bytes)
        overlays['provenance/x01-published-manifest.json'] = old_manifest_bytes

        def add(name, data):
            assert name not in payload, name
            dest.writestr(name, data)
            payload[name] = {'bytes': len(data), 'sha256': sha(data)}

        for name, expected in old_manifest['payload'].items():
            data = source.read(name)
            assert len(data) == expected['bytes'] and sha(data) == expected['sha256'], name
            if name in overlays:
                new_data = overlays.pop(name)
                if new_data != data:
                    add('provenance/x01-before-article-update/' + name, data)
                    changed.append(name)
                else:
                    preserved += 1
                add(name, new_data)
            else:
                add(name, data)
                preserved += 1
        for name, data in overlays.items():
            add(name, data)
        registration = json.loads(source.read('target/measured-delay-x01-20261028/manifest.json'))
        for name, expected in registration['inputs'].items():
            assert payload[name]['sha256'] == expected, name
        # Every original evidence byte is either still in place or preserved above.
        assert not any(name.startswith(('src/', 'target/')) for name in changed)
        head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        manifest = {'version': 'x01-article-updated-20261028',
                    'baseArchiveSha256': EXPECTED_BASE,
                    'baseSourceCommit': old_manifest['sourceCommit'],
                    'workspaceHeadAtPackaging': head,
                    'sourceTreeCleanAtPackaging': False,
                    'identityNote': 'Approved uncommitted document/scripts overlay; file hashes identify the update.',
                    'articleSha256': validation['docxSha256'],
                    'articleUpdatedWithX01': True,
                    'newScientificAttempts': 0,
                    'registeredInputsVerified': len(registration['inputs']),
                    'basePayloadFilesPreservedInPlace': preserved,
                    'changedBasePathsWithOriginalsPreserved': changed,
                    'payload': payload}
        dest.writestr('manifest.json', json.dumps(manifest, indent=2, ensure_ascii=False) + '\n')
    with zipfile.ZipFile(output) as check:
        assert check.testzip() is None
        assert len(check.namelist()) == len(set(check.namelist())) == len(payload) + 1
        for name, expected in payload.items():
            data = check.read(name)
            assert len(data) == expected['bytes'] and sha(data) == expected['sha256'], name
    assert sha(BASE.read_bytes()) == EXPECTED_BASE
    digest = sha(output.read_bytes())
    output.with_suffix('.sha256').write_text(f'{digest}  {output.name}\n', encoding='ascii')
    record = {'status': 'PASS', 'archive': str(output.relative_to(ROOT)),
              'bytes': output.stat().st_size, 'sha256': digest,
              'payloadFilesVerified': len(payload), 'baseArchiveUnchanged': True,
              'basePayloadFilesPreservedInPlace': preserved,
              'changedBasePathsWithOriginalsPreserved': changed,
              'registeredInputsVerified': len(registration['inputs']),
              'newScientificAttempts': 0, 'uploaded': False}
    write_json(OUT / 'package-validation.json', record)
    print(json.dumps(record, indent=2))


if __name__ == '__main__':
    main()
