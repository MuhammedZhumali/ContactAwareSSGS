"""Rebuild the registered adapters or replay ONE registered cell to a new directory.

Never overwrite campaign evidence or silently change a registered budget.
Use JAVA_HOME for portability; the original toolchain path is the local fallback.
"""
import argparse,hashlib,json,os,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];BASE=ROOT/'target/aitu-revision';JAR=ROOT/'target/final-research/final-v2.jar'
JAVA_HOME=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21.0.10'))
BIN=JAVA_HOME/'bin';SUFFIX='.exe' if os.name=='nt' else ''
def sha(p):
 with open(p,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p,d):p.write_text(json.dumps(d,indent=2,default=str)+'\n',encoding='utf-8')
def registered_classes(manifests):
 expected={}
 for manifest in manifests:
  entries=manifest.get('classFiles',manifest.get('classes',{}))
  if not entries:raise ValueError('Missing registered class-file inventory')
  expected.update(entries)
 if len(expected)!=26:raise ValueError(f'Expected 26 registered classes, got {len(expected)}')
 for p,h in expected.items():
  if not (ROOT/p).is_file() or sha(ROOT/p)!=h:
   raise ValueError(f'Missing or altered registered class: {p}')
 return expected

def build(dest):
 dest=Path(dest).resolve();dest.mkdir(parents=True,exist_ok=False)
 m=read(BASE/'harness-v1/manifest.json');s=read(BASE/'seed-harness-v2/manifest.json')
 assert sha(JAR)==m['jarSha256']==s['jarSha256']
 for manifest in [m,s]:
  for p,h in manifest['sources'].items():assert sha(ROOT/p)==h,p
 expected=registered_classes([m,s])
 normal=dest/'classes';overlay=dest/'diagnostic-overlay';seed=dest/'seed';normal.mkdir();overlay.mkdir();seed.mkdir()
 cp=lambda paths:os.pathsep.join(map(str,paths))
 commands=[
  [str(BIN/('javac'+SUFFIX)),'--release','21','-cp',str(JAR),'-d',str(normal),*[str(ROOT/'scripts/aitu'/n) for n in ['AituInitialState.java','AituRootMain.java','IncumbentProbe.java']],str(BASE/'harness-v1/AituRunMain.java')],
  [str(BIN/('javac'+SUFFIX)),'--release','21','-cp',cp([normal,JAR]),'-d',str(overlay),str(BASE/'harness-v1/ExactHorizonPlanner.java')],
  [str(BIN/('javac'+SUFFIX)),'--release','21','-cp',cp([normal,JAR]),'-d',str(seed),str(BASE/'seed-harness-v2/AituSeedRetentionPortfolio.java'),str(BASE/'seed-harness-v2/AituSeedRunMain.java'),str(ROOT/'scripts/aitu/AituSeedRetentionCheck.java')]]
 for i,command in enumerate(commands):
  r=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
  (dest/f'compile-{i}.log').write_text(r.stdout,encoding='utf-8');assert r.returncode==0,r.stdout
 checks={}
 for old,new in [(BASE/'harness-v1/classes',normal),(BASE/'harness-v1/diagnostic-overlay',overlay),(BASE/'seed-harness-v2/classes',seed)]:
  for f in old.rglob('*.class'):
   g=new/f.relative_to(old);checks[f.relative_to(ROOT).as_posix()]=sha(f)==sha(g)
 if set(checks)!=set(expected):raise ValueError('Rebuild comparison does not cover the registered classes')
 save(dest/'validation.json',dict(status='PASS' if all(checks.values()) else 'BYTECODE_DIFFERS',checks=checks,commands=commands,jarSha256=sha(JAR),java=subprocess.check_output([str(BIN/('java'+SUFFIX)),'-version'],stderr=subprocess.STDOUT,text=True)))
 print('Rebuilt',len(checks),'classes; byte-identical:',all(checks.values()),flush=True)
def replay(stage,cell,dest,built,dry):
 protocol=read(BASE/stage/'protocol.json');case=next(c for c in protocol['cases'] if c['id']==cell)
 inv=read(BASE/stage/'runs'/cell/'invocation.json');args=inv['command'];assert sha(ROOT/case['model'])==case['modelSha256'];assert sha(JAR)==protocol['jarSha256']
 # Preserve all scientific flags; replace only machine paths and the output location.
 mainidx=next(i for i,a in enumerate(args) if a.startswith('org.contactaware.security.simulation.'))
 main=args[mainidx];flags=args[mainidx+1:];root=case.get('kind')=='root';seedonly=case.get('nodes')==0
 paths=[Path(built)/'classes',JAR]
 if root:paths.insert(0,Path(built)/'diagnostic-overlay')
 if seedonly:paths.insert(0,Path(built)/'seed')
 for flag,value in [('--model',str(ROOT/case['model'])),('--out',str(Path(dest).resolve()))]:flags[flags.index(flag)+1]=value
 command=[str(BIN/('java'+SUFFIX)),'-Xmx1024m','-cp',os.pathsep.join(map(str,paths)),main,*flags]
 if dry:print(json.dumps(dict(command=command,watchdogSeconds=case['watchdogSeconds'],case=cell),indent=2));return
 dest=Path(dest).resolve();dest.mkdir(parents=True,exist_ok=False)
 save(dest/'registered-replay.json',dict(origin=f'{stage}/{cell}',protocolSha256=sha(BASE/stage/'protocol.json'),command=command,watchdogSeconds=case['watchdogSeconds']))
 started=time.monotonic()
 with open(dest/'process.log','w',encoding='utf-8') as log:
  proc=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
  try:rc=proc.wait(timeout=case['watchdogSeconds']);status='EXITED' if rc==0 else 'PROCESS_ERROR'
  except subprocess.TimeoutExpired:proc.kill();rc=proc.wait();status='PROCESS_TIMEOUT'
 save(dest/'replay-process.json',dict(status=status,returnCode=rc,elapsedSeconds=time.monotonic()-started,checkpointPreserved=(dest/'checkpoint.json').exists()))
 print(status)
if __name__=='__main__':
 a=argparse.ArgumentParser();sub=a.add_subparsers(dest='cmd',required=True)
 b=sub.add_parser('build');b.add_argument('--out',required=True)
 r=sub.add_parser('replay');r.add_argument('--stage',choices=['stage1','stage2','stage3','stage4'],required=True);r.add_argument('--id',required=True);r.add_argument('--out',required=True);r.add_argument('--built',required=True);r.add_argument('--dry-run',action='store_true')
 q=a.parse_args()
 if q.cmd=='build':build(q.out)
 else:replay(q.stage,q.id,q.out,q.built,q.dry_run)
