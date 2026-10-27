"""Rebuild v2 adapters or replay one registered cell in a new output directory."""
import argparse,json,os,subprocess,time
from pathlib import Path
import reproduce_aitu as old
ROOT=Path(__file__).resolve().parents[1];BASE=ROOT/'target/aitu-revision-v2'
load=old.read;sha=old.sha;save=old.save

def build(dest):
 dest=Path(dest).resolve();dest.mkdir(parents=True,exist_ok=False)
 old.build(dest/'v1')
 manifest=load(BASE/'harness/manifest.json')
 for p,h in manifest['sources'].items():
  if sha(ROOT/p)!=h:raise ValueError('Changed adapter source '+p)
 classes=dest/'classes';classes.mkdir()
 command=[str(old.BIN/('javac'+old.SUFFIX)),'--release','21','-cp',os.pathsep.join(map(str,[dest/'v1/classes',old.JAR])),
          '-d',str(classes),*[str(ROOT/p) for p in manifest['sources']]]
 result=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (dest/'compile-v2.log').write_text(result.stdout,encoding='utf-8')
 if result.returncode:raise RuntimeError(result.stdout)
 checks={p:sha(ROOT/p)==sha(classes/(ROOT/p).relative_to(BASE/'harness/classes')) for p in manifest['classes']}
 assert len(checks)==1 and all(checks.values())
 save(dest/'validation.json',{'status':'PASS','newClasses':checks,'priorClasses':load(dest/'v1/validation.json')['checks'],'command':command})
 print('V2 adapter and 26 preserved adapter classes byte-identical')

def replay(kind,cell,built,dest,dry):
 source=load(BASE/('protocol.json' if kind=='root' else 'controller-registration.json'))
 case=next(x for x in source['cases'] if x['id']==cell)
 original=BASE/('roots' if kind=='root' else 'controllers')/cell
 invocation=load(original/'invocation.json')['command']
 i=next(i for i,x in enumerate(invocation) if x.startswith('org.contactaware.security.simulation.'))
 main=invocation[i];flags=list(invocation[i+1:]);built=Path(built).resolve()
 inputs=load(BASE/'protocol.json')['inputs']
 for path,h in inputs.items():
  if sha(ROOT/path)!=h:raise ValueError('Registered input differs: '+path)
 paths=[built/'v1/classes',old.JAR]
 if kind=='root':paths=[built/'v1/diagnostic-overlay',built/'classes',*paths]
 for flag in ['--model','--observation','--checkpoint']:
  if flag in flags:
   n=flags.index(flag)+1;flags[n]=str((ROOT/flags[n]).resolve())
 dest=Path(dest).resolve();flags[flags.index('--out')+1]=str(dest)
 command=[str(old.BIN/('java'+old.SUFFIX)),'-Xmx1024m','-cp',os.pathsep.join(map(str,paths)),main,*flags]
 if dry:print(json.dumps({'command':command,'watchdogSeconds':case['watchdogSeconds']},indent=2));return
 dest.mkdir(parents=True,exist_ok=False)
 save(dest/'replay-registration.json',{'origin':cell,'kind':kind,'command':command,'watchdogSeconds':case['watchdogSeconds']})
 start=time.monotonic()
 with (dest/'process.log').open('w',encoding='utf-8') as log:
  process=subprocess.Popen(command,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
  try:code=process.wait(timeout=case['watchdogSeconds']);status='EXITED' if code==0 else 'PROCESS_ERROR'
  except subprocess.TimeoutExpired:process.kill();code=process.wait();status='PROCESS_TIMEOUT'
 save(dest/'replay-process.json',{'status':status,'returnCode':code,'elapsedSeconds':time.monotonic()-start})
 print(status)

if __name__=='__main__':
 parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='action',required=True)
 b=sub.add_parser('build');b.add_argument('--out',required=True)
 r=sub.add_parser('replay');r.add_argument('--kind',choices=['root','controller'],required=True);r.add_argument('--id',required=True)
 r.add_argument('--built',required=True);r.add_argument('--out',required=True);r.add_argument('--dry-run',action='store_true')
 a=parser.parse_args()
 if a.action=='build':build(a.out)
 else:replay(a.kind,a.id,a.built,a.out,a.dry_run)
