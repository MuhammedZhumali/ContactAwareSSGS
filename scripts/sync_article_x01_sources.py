"""Mirror approved article text into the existing JSON/TeX without rebuilding DOCX."""
import copy, json, hashlib
from pathlib import Path
import update_article_x01 as revision
import build_aitu_v2_documents as builder

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/article-x01-revision-20261028'
DOC=ROOT/'docs/aitu-revision-v2'

def main():
    original=json.loads((BASE/'article-content-before.json').read_text(encoding='utf-8'))
    body={p['index']:p for p in json.loads((BASE/'before-body.json').read_text(encoding='utf-8'))}
    source=copy.deepcopy(original)
    matched=set();blocks=[]
    changes={body[i]['text']:(i,value) for i,value in revision.REPLACEMENTS.items()}
    def p(t):return {'type':'p','text':t}
    def h(t):return {'type':'subheading','text':t}
    for block in source['blocks']:
        old=block.get('text')
        if old in changes:
            i,new=changes[old];matched.add(i);block['text']=new
        blocks.append(block)
        if old==body[55]['text']:
            blocks.extend([h('Measured decision-time protocol')]+[p(t) for t in revision.METHODS])
        if old==body[82]['text']:
            blocks.extend([h('Measured decision latency in X01'),p(revision.RESULTS[0]),{
              'type':'table','caption':'Table 5. Main measured-delay series. CW=CONTINUE_WAIT; CAN=CANONICAL. Delta U is Multi minus Next; no main pair is incomplete.',
              'headers':['Rule','Complete pairs','Multi S wins / ties / losses','Delta U (s), median [min; max]'],
              'rows':[['CW','20 / 20','0 / 0 / 20','-15 [-15.75; -13]'],['CAN','20 / 20','0 / 5 / 15','-14.25 [-15.5; -11]']],
              'widths':[1300,1400,2900,3754]},*[p(t) for t in revision.RESULTS[1:]]])
        if old==body[91]['text']:blocks.append(p(revision.DISCUSSION))
    assert matched==set(revision.REPLACEMENTS)
    source['blocks']=blocks
    source['authorFields']=body[3]['text']
    source['abstract']=revision.ABSTRACT
    assert original['references']==source['references']
    old_docx=hashlib.sha256((DOC/'article.docx').read_bytes()).hexdigest()
    (DOC/'article-content.json').write_text(json.dumps(source,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    builder.base.build_tex(source,'main.tex')
    assert hashlib.sha256((DOC/'article.docx').read_bytes()).hexdigest()==old_docx
    # The content mirror must use the actual author's manuscript fields.
    tex=(DOC/'article-tex/main.tex').read_text(encoding='utf-8')
    assert '0009-0008-4052-0672' in tex and 'Measured decision latency in X01' in tex
    assert 'remains untested' not in tex and '[TO BE ADDED AFTER PUBLIC DEPOSITION]' not in tex
    (BASE/'source-sync.json').write_text(json.dumps({'status':'PASS','blocks':len(blocks),
      'referencesPreserved':20,'docxUnchangedByTexBuild':True,
      'authorFieldsFromProvidedManuscript':True,'newScientificAttempts':0},indent=2)+'\n')
    print('PASS: JSON and TeX synchronized; DOCX not regenerated.')

if __name__=='__main__':main()
