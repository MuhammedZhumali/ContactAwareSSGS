"""Versioned AITU v2 payload, preflight extraction and final archives.

The prior archive is read only; collisions must have identical bytes. No PDF,
installed runtime, user-directory scan or unrelated working-tree file is added.
"""
import argparse,datetime,hashlib,json,shutil,zipfile
from pathlib import Path,PurePosixPath
from package_aitu_revision import collect,local_import_closure
ROOT=Path(__file__).resolve().parents[1];BASE=ROOT/'target/aitu-revision-v2';DOC=ROOT/'docs/aitu-revision-v2'
OUT=ROOT/'target/aitu-revision-v2-delivery'
OLD=ROOT/'target/aitu-revision-delivery/ContactAwareSS-AITU-reproduction-20261028.zip'
OLD_SHA='04fe7be64b9ef725a3d3789f31020c97e99960df6703c2e3e3b06cd54b36aa8c'
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def save(p,x):p.write_text(json.dumps(x,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
def safe(name):
 p=PurePosixPath(name)
 assert not p.is_absolute() and '..' not in p.parts and ':' not in name and '\\' not in name and '.git' not in p.parts,name
 return name
def inventory():
 assert sha(OLD)==OLD_SHA
 mapping={}
 with zipfile.ZipFile(OLD) as z:
  oldmanifest=json.loads(z.read('manifest.json'))
  for name,record in oldmanifest['files'].items():
   safe(name);mapping[name]=('old',name,record)
  data=z.read('manifest.json')
  mapping['provenance/aitu-v1-manifest.json']=('old','manifest.json',{'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
 entries=list((ROOT/'scripts').glob('*aitu*v2*.py'))
 scripts=local_import_closure(entries)
 files=collect(DOC,{'__pycache__'})|collect(BASE,{'document-qa','__pycache__'})|scripts|collect(ROOT/'scripts/aitu-v2')
 files|={ROOT/'scripts/render_aitu_v2_word.ps1',BASE/'document-qa/final4/render.json'}
 frozen=load(ROOT/'target/final-research/final-v2-sources.json')
 files|={ROOT/p for p in frozen if p.startswith('src/main/')}
 for p in sorted(files):
  name=safe(p.relative_to(ROOT).as_posix());record={'bytes':p.stat().st_size,'sha256':sha(p)}
  if name in mapping:assert mapping[name][2]==record,('Changed prior payload',name)
  mapping[name]=('local',p,record)
 for name,h in load(BASE/'protocol.json')['inputs'].items():assert mapping[name][2]['sha256']==h
 return mapping,sorted(p.relative_to(ROOT).as_posix() for p in scripts)
def materialize(dest,mapping):
 dest=dest.resolve();dest.mkdir(parents=True,exist_ok=False)
 with zipfile.ZipFile(OLD) as z:
  for name,(kind,source,record) in mapping.items():
   target=dest/safe(name);target.parent.mkdir(parents=True,exist_ok=True)
   if kind=='local':shutil.copy2(source,target)
   else:
    with z.open(source) as src,target.open('wb') as dst:shutil.copyfileobj(src,dst)
   assert sha(target)==record['sha256'],name
 print(f'Materialized and SHA-verified {len(mapping)} files in {dest}')
def package(name,mapping,scope):
 manifest={'identity':name,'createdUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':scope,
  'priorPackageSha256':OLD_SHA,'newDenominators':{'roots':{'executed':6,'complete':2,'incomplete':4},'controllers':{'executed':4,'finished':3,'controllerTimeLimit':1}},
  'priorPopulations':'AITU v1:110 new controllers,12 root diagnostics,6 reused logical cells. Historical final-v2:108/93/15. All separate.',
  'publication':'New AITU v2 package not publicly deposited; URL/DOI pending. Historical release is not this package.',
  'executionIdentity':load(BASE/'identity.json'),'files':{k:v[2] for k,v in sorted(mapping.items())}}
 path=OUT/(name+'.zip')
 with zipfile.ZipFile(OLD) as old,zipfile.ZipFile(path,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  z.writestr('manifest.json',json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
  for target,(kind,source,record) in sorted(mapping.items()):
   if kind=='local':z.write(source,target)
   else:
    with old.open(source) as src,z.open(target,'w',force_zip64=True) as dst:shutil.copyfileobj(src,dst)
 with zipfile.ZipFile(path) as z:
  assert len(z.namelist())==len(mapping)+1
  for target,(_,_,record) in mapping.items():
   assert z.getinfo(target).file_size==record['bytes']
   with z.open(target) as src:assert hashlib.file_digest(src,'sha256').hexdigest()==record['sha256'],target
 result={'path':path.relative_to(ROOT).as_posix(),'bytes':path.stat().st_size,'sha256':sha(path),'payloadFiles':len(mapping),'allPayloadHashesAndCRC':'PASS'}
 (OUT/(name+'.sha256')).write_text(f"{result['sha256']}  {path.name}\n",encoding='utf-8')
 save(OUT/(name+'-manifest.json'),manifest);print(json.dumps(result),flush=True)
 return result
def local_mapping(files,flatten=None):
 return {safe(p.relative_to(flatten or ROOT).as_posix()):('local',p,{'bytes':p.stat().st_size,'sha256':sha(p)}) for p in files}
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--preflight-directory');args=parser.parse_args()
 full,closure=inventory()
 if args.preflight_directory:materialize(Path(args.preflight_directory),full);return
 assert load(DOC/'validation.json')['status']=='PASS'
 assert load(BASE/'portable-validation.json')['status']=='PASS'
 for p,h in load(BASE/'preservation.json').items():assert sha(ROOT/p)==h,p
 OUT.mkdir(parents=True,exist_ok=True)
 results=[package('ContactAwareSS-AITU-v2-article-20261028',local_mapping(collect(DOC,{'__pycache__'})),'Article, supplement, editable sources and evidence summaries; raw results in reproduction package.'),
  package('ContactAwareSS-AITU-v2-Overleaf-20261028',local_mapping(collect(DOC/'article-tex'),DOC/'article-tex'),'Upload to Overleaf; select main.tex or supplement.tex.'),
  package('ContactAwareSS-AITU-v2-reproduction-20261028',full,'Preserved v1 payload plus separately registered v2:6 roots and4 controllers, frozen sources/JAR, adapters, analysis, inputs and incomplete attempts.')]
 save(OUT/'package-validation.json',{'status':'PASS','archives':results,'localPythonImportClosure':closure,'portableValidation':load(BASE/'portable-validation.json'),'preservedPriorFiles':len(load(BASE/'preservation.json')),'boundary':'No policy experiments executed by packaging. All payload hashes and ZIP CRC checked.'})

if __name__=='__main__':main()
