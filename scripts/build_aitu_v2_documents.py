"""Versioned AITU v2 documents; preserve v1 generator and files unchanged."""
import hashlib,json,re
from pathlib import Path
import build_aitu_documents as base
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm
from docx.enum.text import WD_ALIGN_PARAGRAPH

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/aitu-revision-v2';DATA=ROOT/'target/aitu-revision-v2'
base.OUT=OUT
original_escape=base.texescape
def texescape(text):
    parts=re.split(r'(https?://\S+)',str(text));result=[]
    for part in parts:
        if part.startswith(('https://','http://')):
            url=part.rstrip('.,;:')
            result.append(r'\url{'+url+'}'+original_escape(part[len(url):]))
        else:result.append(original_escape(part).replace('\n',r'\newline{}'+'\n'))
    return ''.join(result)
base.texescape=texescape
original_para=base.para
def para(doc,text='',*args,**kwargs):
    # Keep each full SHA256 on one left-aligned line; all 64 digits fit at 12pt.
    # This removes a redundant hard wrap without changing the stored hash.
    lines=str(text).split('\n')
    if len(lines)==3 and lines[0].startswith('target/') and lines[1].startswith('SHA256: '):
        assert re.fullmatch('[0-9a-f]{64}',lines[1][8:]+lines[2])
        text=lines[0]+'\n'+lines[1]+lines[2]
    paragraph=original_para(doc,text,*args,**kwargs)
    if '\n' in str(text):
        paragraph.alignment=WD_ALIGN_PARAGRAPH.LEFT
        paragraph.paragraph_format.first_line_indent=Cm(0)
    return paragraph
base.para=para
original_table=base.addtable
def addtable(doc,block):
    original_table(doc,block)
    t=doc.tables[-1]
    properties=t._tbl.tblPr
    width=properties.find(qn('w:tblW'))
    if width is None:width=OxmlElement('w:tblW');properties.append(width)
    width.set(qn('w:type'),'dxa');width.set(qn('w:w'),str(Cm(16.5).twips))
    layout=properties.find(qn('w:tblLayout'))
    if layout is None:layout=OxmlElement('w:tblLayout');properties.append(layout)
    layout.set(qn('w:type'),'fixed')
base.addtable=addtable

def main():
    assert texescape('See https://example.org/a.')==r'See \url{https://example.org/a}.'
    assert texescape('https://example.org/a, next')==r'\url{https://example.org/a}, next'
    assert r'\newline{}' in texescape('a\nb')
    records={}
    for stem,tex in [('article','main.tex'),('supplement','supplement.tex')]:
        content=json.loads((OUT/(stem+'-content.json')).read_text(encoding='utf-8'))
        doc=base.build_doc(content,stem+'.docx');base.build_tex(content,tex)
        raw=(OUT/'article-tex'/tex).read_text(encoding='utf-8')
        assert not re.search(r'\\url\{[^}]*[.,;:]\}',raw),'Sentence punctuation inside URL'
        records[stem]={'docxSha256':hashlib.sha256(doc.read_bytes()).hexdigest(),'texSha256':hashlib.sha256((OUT/'article-tex'/tex).read_bytes()).hexdigest()}
    (DATA/'document-build.json').write_text(json.dumps({'files':records,'templateSha256':hashlib.sha256(base.TEMPLATE.read_bytes()).hexdigest(),
        'preserveOnlyParts':base.PRESERVE,'nativeEquations':'OMML, not MathType','urlPunctuationTests':'PASS','fixedTableWidths':True},indent=2)+'\n')
    print(json.dumps(records,indent=2))

if __name__=='__main__':main()
