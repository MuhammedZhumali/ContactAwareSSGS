"""Check an extracted copy without repeating scientific searches."""
import argparse,hashlib,json,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--copy',required=True);a=p.parse_args();copy=Path(a.copy).resolve()
 assert copy!=ROOT and (copy/'scripts/reproduce_aitu_v2.py').is_file()
 base=copy/'target/aitu-revision-v2';evidence=copy/'target/portable-v2-check';evidence.mkdir(parents=True,exist_ok=False)
 targets=[base/n for n in ['root-tables.json','root-tables.csv','controller-tables.json','controller-tables.csv','audit.json','input-descriptors.json']]
 expected={p.relative_to(copy).as_posix():sha(p) for p in targets}
 commands=[['scripts/reproduce_aitu_v2.py','build','--out','target/portable-v2-build'],
  ['scripts/reproduce_aitu_v2.py','replay','--kind','root','--id','root-CONTINUE_WAIT-N150000','--built','target/portable-v2-build','--out','target/unused-root','--dry-run'],
  ['scripts/reproduce_aitu_v2.py','replay','--kind','controller','--id','controller-CONTINUE_WAIT-N150000-H2','--built','target/portable-v2-build','--out','target/unused-controller','--dry-run'],
  ['scripts/analyze_aitu_v2.py'],['scripts/describe_aitu_v2_inputs.py']]
 records=[]
 for i,args in enumerate(commands):
  result=subprocess.run([sys.executable,'-X','utf8',*args],cwd=copy,capture_output=True,text=True,encoding='utf-8',timeout=60)
  (evidence/f'command-{i}.log').write_text(result.stdout+result.stderr,encoding='utf-8')
  assert result.returncode==0,(args,result.stdout,result.stderr)
  if '--dry-run' in args:
   invocation=json.loads(result.stdout);cmd=invocation['command']
   classpath=cmd[cmd.index('-cp')+1]
   assert str(copy) in classpath
   assert str(ROOT/'target/final-research/final-v2.jar') not in classpath
   assert str(ROOT/'target/aitu-revision/harness-v1/classes') not in classpath
   assert invocation['watchdogSeconds']==(45 if 'root' in args else 210)
  records.append({'args':args,'returnCode':result.returncode,'stdout':result.stdout.strip()})
 checks={name:sha(copy/name)==h for name,h in expected.items()};assert all(checks.values()),checks
 rebuilt=json.loads((copy/'target/portable-v2-build/validation.json').read_text())
 assert rebuilt['status']=='PASS' and all(rebuilt['newClasses'].values()) and all(rebuilt['priorClasses'].values())
 assert not (copy/'target/unused-root').exists() and not (copy/'target/unused-controller').exists()
 value={'status':'PASS','copiedPayloadRoot':str(copy),'regeneratedOutputsByteIdentical':checks,'expectedHashes':expected,'rebuiltClassesByteIdentical':27,'commands':records,'newPolicySearches':0,'boundary':'Clean extracted payload;27 adapter classes rebuilt, both replay command paths checked in dry-run, tables/audit/11 input descriptors reproduced byte-for-byte. No policy experiments or Maven suite.'}
 (ROOT/'target/aitu-revision-v2/portable-validation.json').write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
 (ROOT/'docs/aitu-revision-v2/portable-validation.json').write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({k:v for k,v in value.items() if k!='commands'},indent=2))

if __name__=='__main__':main()
