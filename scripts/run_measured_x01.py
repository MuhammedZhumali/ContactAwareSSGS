"""Frozen serial X01 latency experiment. No implicit retries or budget changes."""
from __future__ import annotations
import argparse, datetime, hashlib, json, os, platform, shutil, subprocess, sys, time, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/measured-delay-x01-20261028'
DOC=ROOT/'docs/measured-delay-x01'
OLD=ROOT/'target/aitu-revision-v2/controllers'
FROZEN=ROOT/'target/final-research/final-v2.jar'
HARNESS=ROOT/'target/aitu-revision/harness-v1'
JDK=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21.0.10'))
JAVA=JDK/'bin/java.exe'
PKG='org.contactaware.security.simulation.'
SOURCE=ROOT/'src/main/java/org/contactaware/security/simulation/PlanningLatencyController.java'

def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def sha(p):
 with Path(p).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def load(p):return json.loads(Path(p).read_text(encoding='utf-8'))
def rel(p):return Path(p).relative_to(ROOT).as_posix()
def save(p,v):
 p=Path(p);p.parent.mkdir(parents=True,exist_ok=True)
 tmp=p.with_suffix(p.suffix+'.tmp');tmp.write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8');tmp.replace(p)
def once(p,v):
 if Path(p).exists():raise ValueError('Refusing overwrite '+str(p))
 save(p,v)
def git(*args):return subprocess.check_output(['git',*args],cwd=ROOT,text=True).strip()

def build():
 dest=BASE/'build';dest.mkdir(parents=True,exist_ok=False)
 classes=dest/'controller-classes';classes.mkdir()
 cmd=[str(JDK/'bin/javac.exe'),'--release','21','-cp',str(FROZEN),'-d',str(classes),str(SOURCE)]
 r=subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (dest/'compile-controller.log').write_text(r.stdout,encoding='utf-8');assert r.returncode==0,r.stdout
 replacements={p.relative_to(classes).as_posix():p.read_bytes() for p in classes.rglob('*.class')}
 jar=dest/'measured-x01.jar'
 with zipfile.ZipFile(FROZEN) as source,zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as output:
  for info in source.infolist():
   if info.filename not in replacements:output.writestr(info,source.read(info))
  for name,data in replacements.items():output.writestr(name,data)
 adapter=(HARNESS/'AituRunMain.java').read_text(encoding='utf-8').replace('AituRunMain','MeasuredX01Main')
 old='exported.put("disposition",d.disposition());'
 assert adapter.count(old)==1
 adapter=adapter.replace(old,old+'exported.put("continuation",d.continuation());')
 old='result.put("decisionDeadlineMisses",decisionTimings.stream().filter(SecurityPolicyController.DecisionTiming::deadlineMiss).count());'
 assert adapter.count(old)==1
 adapter=adapter.replace(old,'''result.put("decisionDeadlineMisses",delay>0||scale>0
                ? delays.stream().filter(PlanningLatencyController.Decision::deadlineMiss).count()
                : decisionTimings.stream().filter(SecurityPolicyController.DecisionTiming::deadlineMiss).count());
        result.put("effectiveDecisionBudgetNanos",delay>0||scale>0
                ? Duration.ofMillis(Long.parseLong(f.get("deadline-ms"))).toNanos() : decisionBudget.toNanos());''')
 adapter_source=dest/'MeasuredX01Main.java';adapter_source.write_text(adapter,encoding='utf-8')
 adapter_classes=dest/'adapter-classes';adapter_classes.mkdir()
 compile_adapter=[str(JDK/'bin/javac.exe'),'--release','21','-cp',os.pathsep.join(map(str,[jar,HARNESS/'classes'])),'-d',str(adapter_classes),str(adapter_source)]
 r=subprocess.run(compile_adapter,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (dest/'compile-adapter.log').write_text(r.stdout,encoding='utf-8');assert r.returncode==0,r.stdout
 with zipfile.ZipFile(FROZEN) as a,zipfile.ZipFile(jar) as b:
  unchanged=[n for n in a.namelist() if n not in replacements]
  assert all(a.read(n)==b.read(n) for n in unchanged)
 once(dest/'identity.json',{'createdUtc':now(),'baseJar':rel(FROZEN),'baseJarSha256':sha(FROZEN),'newJar':rel(jar),'newJarSha256':sha(jar),
  'source':{rel(SOURCE):sha(SOURCE)},'replacedEntries':sorted(replacements),'unchangedJarEntries':len(unchanged),
  'adapterSource':{rel(adapter_source):sha(adapter_source)},'classes':{rel(p):sha(p) for p in adapter_classes.rglob('*.class')},
  'commands':[cmd,compile_adapter],'scope':'Only measured controller compatibility and additive export; frozen planner/portfolio/offline controller byte-identical'})
 print('BUILD PASS',sha(jar),flush=True)

def register():
 identity=load(BASE/'build/identity.json')
 tests=load(BASE/'focused-tests.json');assert tests['status']=='PASS'
 assert sha(BASE/'inputs/X01.json')==sha(ROOT/'target/final-research/models/X5.json')
 original=load(OLD/'controller-CONTINUE_WAIT-N150000-H2/invocation.json')['command']
 flags=dict(zip(original[original.index('--model')::2],original[original.index('--model')+1::2]))
 assert flags['--initialization']=='autonomous' and flags['--nodes']=='150000' and flags['--search-ms']=='30000' and flags['--decision-budget-ms']=='32000'
 configurations=[]
 for phase,mode,repeats in [('control','offline',1),('pilot','measured',1),('main','measured',20)]:
  for rule in ['CONTINUE_WAIT','CANONICAL']:
   for repeat in range(1,repeats+1):
    for h in ([1,2] if repeat%2 else [2,1]):
     pair=f'{phase}-{rule}-{repeat:02d}';rid=f'{pair}-H{h}'
     science={k.removeprefix('--'):v for k,v in flags.items() if k!='--out'}
     science.update(model=rel(BASE/'inputs/X01.json'),horizon=str(h),rule=rule)
     science.update({'host-scale':'1' if mode=='measured' else '0','decision-budget-ms':'0' if mode=='measured' else '32000'})
     case={'run_id':rid,'pair_id':pair,'phase':phase,'tie_rule':rule,'horizon':h,'mode':mode,'repeat':repeat,'flags':science,
       'watchdogSeconds':210,'jvmArgs':['-Xmx1024m'],'jarSha256':identity['newJarSha256']}
     path=BASE/'configs'/f'{rid}.json';once(path,case)
     configurations.append(dict(case,config=rel(path),config_hash=sha(path)))
 preserved={rel(p):sha(p) for folder in [ROOT/'docs/aitu-revision-v2',ROOT/'target/aitu-revision-v2',ROOT/'target/aitu-revision-v2-wording-delivery'] for p in folder.rglob('*') if p.is_file()}
 preserved[rel(ROOT/'docs/aitu-revision-v2/article.docx')]=sha(ROOT/'docs/aitu-revision-v2/article.docx')
 dependencies={rel(p):sha(p) for p in [FROZEN,BASE/'build/measured-x01.jar',BASE/'inputs/X01.json',BASE/'inputs/instruction.docx',SOURCE,
  Path(__file__),DOC/'protocol.md',BASE/'build/MeasuredX01Main.java',BASE/'focused-tests.json']}
 dependencies.update({rel(p):sha(p) for p in (HARNESS/'classes').rglob('*.class')})
 dependencies.update(identity['classes'])
 once(BASE/'manifest.json',{'version':'measured-X01-1','registeredUtc':now(),'sourceCommit':git('rev-parse','HEAD'),'tree':git('status','--porcelain=v1'),
  'java':subprocess.check_output([str(JAVA),'-version'],stderr=subprocess.STDOUT,text=True),'javaExecutable':str(JAVA),'python':sys.version,
  'hardware':load(BASE/'hardware.json'),'warmup':'none; fresh JVM for every attempt; default JIT and GC','inputs':dependencies,'preserved':preserved,
  'build':identity,'cases':configurations,'planned':{'control':4,'pilot':4,'main':80},'archivedStartCorrection':'-2s retained; 0s is root diagnostic only',
  'archiveReference':'https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/aitu-v2-20261028',
  'limits':{'nodes':150000,'searchSeconds':30,'effectiveDecisionSeconds':32,'controllerSeconds':180,'watchdogSeconds':210},
  'oneHeavyJvm':True,'automaticRetries':False,'articleApprovalRequired':True})
 print('REGISTERED',len(configurations),'attempts',flush=True)

def verify(m):
 for p,h in m['inputs'].items():assert sha(ROOT/p)==h,'Changed registered input: '+p
 for case in m['cases']:assert sha(ROOT/case['config'])==case['config_hash']

def run(phase):
 m=load(BASE/'manifest.json');verify(m)
 if phase in ['pilot','main']:
  gate=load(BASE/'control-gate.json');assert gate['status']=='PASS'
 if phase=='main':assert load(BASE/'pilot-gate.json')['status']=='PASS'
 for c in m['cases']:
  if c['phase']!=phase:continue
  dest=BASE/'runs'/c['run_id']
  if (dest/'process.json').exists():continue
  if dest.exists():raise RuntimeError('Existing unfinished attempt; inspect live process before doing anything: '+str(dest))
  dest.mkdir(parents=True)
  cp=os.pathsep.join(map(str,[BASE/'build/adapter-classes',HARNESS/'classes',BASE/'build/measured-x01.jar']))
  command=[str(JAVA),*c['jvmArgs'],'-cp',cp,PKG+'MeasuredX01Main']
  for k,v in c['flags'].items():command+=['--'+k,v]
  command+=['--out',rel(dest)]
  once(dest/'invocation.json',dict(command=command,startedUtc=now(),configHash=c['config_hash'],manifestHash=sha(BASE/'manifest.json')))
  print('START',c['run_id'],flush=True);started=time.monotonic_ns()
  with (dest/'process.log').open('wb') as stream:
   process=subprocess.Popen(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
   once(dest/'process-handle.json',{'pid':process.pid,'startedUtc':now()})
   try:code=process.wait(timeout=c['watchdogSeconds']);status='EXITED' if code==0 else 'PROCESS_ERROR'
   except subprocess.TimeoutExpired:process.kill();code=process.wait(timeout=10);status='PROCESS_TIMEOUT'
  once(dest/'process.json',{'processStatus':status,'exitCode':code,'processElapsedNanos':time.monotonic_ns()-started,
   'externalLimitSeconds':c['watchdogSeconds'],'pid':process.pid,'finishedUtc':now()})
  result=load(dest/'result.json') if (dest/'result.json').exists() else {}
  print('DONE',c['run_id'],result.get('status',status),'S',result.get('simulationMetrics',{}).get('servedContacts') if result.get('simulationMetrics') else None,flush=True)

if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('action',choices=['build','register','run']);p.add_argument('--phase',choices=['control','pilot','main']);a=p.parse_args()
 if a.action=='run':
  if not a.phase:p.error('--phase required')
  run(a.phase)
 else:globals()[a.action]()
