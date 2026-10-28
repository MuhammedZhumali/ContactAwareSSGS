"""Add the completed X01 campaign to a copy of the published reproduction payload."""
import hashlib,json,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/measured-delay-x01-20261028'
DOC=ROOT/'docs/measured-delay-x01'
OLD=ROOT/'target/aitu-revision-v2-wording-delivery/ContactAwareSS-AITU-v2-wording-reproduction-20261028.zip'
EXPECTED='7d96531f6fae98bbd40898ce40d158f386990087209f3e33d49b310fd3a4e721'
OUT=ROOT/'target/measured-delay-x01-delivery'

def sha(path):
 with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
 assert sha(OLD)==EXPECTED
 registration=json.loads((BASE/'manifest.json').read_text())
 assert all((BASE/'runs'/c['run_id']/'process.json').exists() for c in registration['cases']),'Do not package unfinished series'
 preservation={p:sha(ROOT/p)==h for p,h in registration['preserved'].items()}
 assert all(preservation.values()),[p for p,ok in preservation.items() if not ok]
 article=ROOT/'docs/aitu-revision-v2/article.docx'
 assert sha(article)==sha(BASE/'inputs/article-before.docx'),'User article changed; inspect before packaging'
 OUT.mkdir(exist_ok=True)
 dest=OUT/'ContactAwareSS-X01-measured-delay-20261028.zip'
 if dest.exists():raise ValueError('Refusing existing archive')
 additions={}
 for folder in [BASE,DOC]:
  for p in folder.rglob('*'):
   if not p.is_file() or '__pycache__' in p.parts or p.suffix=='.tmp':continue
   if p==BASE/'inputs/article-before.docx':continue  # user's unapproved draft is preserved locally only
   additions[p.relative_to(ROOT).as_posix()]=p
 for pattern in ['*measured_x01*.py']:
  for p in (ROOT/'scripts').glob(pattern):additions[p.relative_to(ROOT).as_posix()]=p
 for p in [ROOT/'src/main/java/org/contactaware/security/simulation/PlanningLatencyController.java',
  ROOT/'src/test/java/org/contactaware/security/simulation/MeasuredContinuationVerificationTest.java']:
  additions[p.relative_to(ROOT).as_posix()]=p
 payload={}
 with zipfile.ZipFile(OLD) as source,zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as output:
  for info in source.infolist():
   if info.is_dir() or info.filename in additions:continue
   name='provenance/aitu-v2-original-manifest.json' if info.filename=='manifest.json' else info.filename
   data=source.read(info);output.writestr(name,data)
   payload[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
  for name,p in sorted(additions.items()):
   data=p.read_bytes();output.writestr(name,data);payload[name]={'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}
  note='X01 measured-delay addendum. Start with docs/measured-delay-x01/README.md.\nThe archived AITU manuscript predates these experiments. The user-modified article is not included pending approval.\nHistorical source manifests identify the frozen baseline; the new campaign manifest identifies the compatibility overlay.\n'
  output.writestr('X01-START-HERE.txt',note);payload['X01-START-HERE.txt']={'bytes':len(note.encode()),'sha256':hashlib.sha256(note.encode()).hexdigest()}
  manifest={'version':'X01-measured-delay-20261028','baselineArchiveSha256':EXPECTED,'campaignManifestSha256':sha(BASE/'manifest.json'),
   'articleUpdated':False,'articleApprovalPending':True,'payload':payload}
  output.writestr('manifest.json',json.dumps(manifest,indent=2)+'\n')
 with zipfile.ZipFile(dest) as verify:
  assert verify.testzip() is None
  for name,v in payload.items():
   data=verify.read(name);assert len(data)==v['bytes'] and hashlib.sha256(data).hexdigest()==v['sha256'],name
 digest=sha(dest);dest.with_suffix('.sha256').write_text(digest+'  '+dest.name+'\n')
 validation={'status':'PASS','archive':str(dest),'bytes':dest.stat().st_size,'sha256':digest,'payloadFiles':len(payload),
  'priorArchiveUnchanged':sha(OLD)==EXPECTED,'priorFilesVerified':len(preservation),'userArticleUnchanged':True,'noNewScientificAttempts':True}
 (OUT/'validation.json').write_text(json.dumps(validation,indent=2)+'\n')
 print(json.dumps(validation,indent=2))

if __name__=='__main__':main()
