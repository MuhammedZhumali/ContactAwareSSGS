"""Insert the supplied X01 mechanism figure, preserving the current DOCX design."""
import copy
import hashlib
import json
import re
import shutil
import zipfile
from pathlib import Path

from lxml import etree as E
from PIL import Image

import build_aitu_v2_documents as builder

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'target/article-mechanism-restored-20261028'
DOCS = ROOT / 'docs/aitu-revision-v2'
SOURCE = Path('C:/Users/Muhalek/Downloads/contact_horizon_mechanism.png')
BASE_SHA = '9df9f58edfd696eb4a010a3dbda4ab2a31c18c06c597191a9ca4d2481719748f'
CAPTION = ('Fig. 1. X01 horizon mechanism: earlier installation can reduce the time '
           'needed to serve C3 from 8 s to 4 s, provided all lifecycle constraints hold.')
REFERENCE = ' Figure 1 illustrates the X01 timing mechanism.'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
      'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
      'wp': 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing',
      'pic': 'http://schemas.openxmlformats.org/drawingml/2006/picture',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'}
W = '{' + NS['w'] + '}'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def text(node):
    return ''.join(node.xpath('.//w:t/text()', namespaces=NS))


def renumber(value):
    return re.sub(r'Fig\. ([123])(?=[. ,])', lambda m: 'Fig. ' + str(int(m[1]) + 1), value)


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    for relative, backup in [('article.docx', 'article-before.docx'),
                             ('article-content.json', 'article-content-before.json'),
                             ('article-tex/main.tex', 'main-before.tex')]:
        dest = WORK / backup
        if not dest.exists():
            shutil.copyfile(DOCS / relative, dest)
    original = (WORK / 'article-before.docx').read_bytes()
    assert sha(original) == BASE_SHA
    image_path = DOCS / 'figures/contact_horizon_mechanism.png'
    if SOURCE.exists():
        shutil.copyfile(SOURCE, image_path)
    supplied_image = image_path.read_bytes()
    shutil.copyfile(image_path, DOCS / 'article-tex/figures/contact_horizon_mechanism.png')
    with zipfile.ZipFile(WORK / 'article-before.docx') as zin:
        parts = {info.filename: zin.read(info) for info in zin.infolist()}
    root = E.fromstring(parts['word/document.xml'])
    body = root.find(W + 'body')
    old_nodes = list(body)
    old_serialized = [E.tostring(node, method='c14n') for node in old_nodes]
    changed = []
    for index, node in enumerate(old_nodes):
        before = text(node)
        for run_text in node.xpath('.//w:t', namespaces=NS):
            run_text.text = renumber(run_text.text or '')
        if text(node) != before:
            changed.append({'index': index, 'before': before, 'after': text(node)})
    anchors = [p for p in body if text(p).startswith('The synthetic family uses C1 at A')]
    assert len(anchors) == 1
    anchor = anchors[0]
    anchor.xpath('.//w:t', namespaces=NS)[-1].text += REFERENCE
    figures = [p for p in body if p.xpath('.//w:drawing', namespaces=NS)]
    assert len(figures) == 3
    figure = copy.deepcopy(figures[0])
    caption_source = body[list(body).index(figures[0]) + 1]
    assert text(caption_source).startswith('Fig. 2.')
    caption = copy.deepcopy(caption_source)
    nodes = caption.xpath('.//w:t', namespaces=NS)
    nodes[0].text = CAPTION
    for node in nodes[1:]:
        node.text = ''
    for number, node in enumerate([figure, caption], 1):
        for key in node.attrib:
            if key.endswith('}paraId') or key.endswith('}textId'):
                node.set(key, f'BC00000{number}')
    rels = E.fromstring(parts['word/_rels/document.xml.rels'])
    rel_id = 'rId' + str(max(int(r.get('Id')[3:]) for r in rels) + 1)
    E.SubElement(rels, '{' + REL + '}Relationship', Id=rel_id,
                 Type=NS['r'] + '/image', Target='media/contact_horizon_mechanism.png')
    figure.xpath('.//a:blip', namespaces=NS)[0].set('{' + NS['r'] + '}embed', rel_id)
    with Image.open(image_path) as img:
        width, height = img.size
    cx = 5868000  # existing manuscript picture width: 16.3 cm
    cy = round(cx * height / width)
    for node in figure.xpath('.//wp:extent|.//a:xfrm/a:ext', namespaces=NS):
        node.set('cx', str(cx))
        node.set('cy', str(cy))
    doc_id = max(int(x.get('id')) for x in root.xpath('.//wp:docPr', namespaces=NS)) + 1
    for node in figure.xpath('.//wp:docPr|.//pic:cNvPr', namespaces=NS):
        node.set('id', str(doc_id))
        node.set('name', 'contact_horizon_mechanism.png')
        node.set('descr', 'X01 visible contact horizons and illustrative timing of key installation before C3.')
    position = list(body).index(anchor) + 1
    body.insert(position, figure)
    body.insert(position + 1, caption)
    allowed = {item['index'] for item in changed} | {old_nodes.index(anchor)}
    assert all(E.tostring(node, method='c14n') == old_serialized[i]
               for i, node in enumerate(old_nodes) if i not in allowed)
    parts['word/document.xml'] = E.tostring(root, xml_declaration=True, encoding='UTF-8', standalone=True)
    parts['word/_rels/document.xml.rels'] = E.tostring(rels, xml_declaration=True, encoding='UTF-8', standalone=True)
    parts['word/media/contact_horizon_mechanism.png'] = supplied_image
    with zipfile.ZipFile(DOCS / 'article.docx', 'w', zipfile.ZIP_DEFLATED) as zout:
        for name, data in parts.items():
            zout.writestr(name, data)
    with zipfile.ZipFile(DOCS / 'article.docx') as zout, zipfile.ZipFile(WORK / 'article-before.docx') as zin:
        assert zout.testzip() is None
        assert all(zout.read(name) == zin.read(name) for name in zin.namelist()
                   if name not in ['word/document.xml', 'word/_rels/document.xml.rels'])
        assert zout.read('word/media/contact_horizon_mechanism.png') == supplied_image
    content = json.loads((WORK / 'article-content-before.json').read_text(encoding='utf-8'))
    rebuilt = []
    for block in content['blocks']:
        for key in ['text', 'caption']:
            if key in block:
                block[key] = renumber(block[key])
        rebuilt.append(block)
        if block.get('text', '').startswith('The synthetic family uses C1 at A'):
            block['text'] += REFERENCE
            rebuilt.append({'type': 'figure', 'name': 'contact_horizon_mechanism', 'caption': CAPTION})
    content['blocks'] = rebuilt
    assert sum(b['type'] == 'figure' for b in rebuilt) == 4
    (DOCS / 'article-content.json').write_text(json.dumps(content, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    builder.base.build_tex(content, 'main.tex')
    record = {'status': 'STRUCTURAL_PASS', 'sourceDocxSha256': BASE_SHA,
              'docxSha256': sha((DOCS / 'article.docx').read_bytes()),
              'suppliedImageSha256': sha(supplied_image), 'imageEmbeddedWithoutModification': True,
              'caption': CAPTION, 'originalFigureCount': 3, 'figureCount': 4,
              'referenceChanges': changed, 'referenceAdded': REFERENCE.strip(),
              'unchangedOriginalBodyElements': len(old_nodes) - len(allowed),
              'unchangedOriginalPackageParts': len(parts) - 3,
              'newScientificAttempts': 0, 'renderReview': 'PENDING'}
    (WORK / 'validation.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in record.items() if k != 'referenceChanges'}, indent=2))


if __name__ == '__main__':
    main()
