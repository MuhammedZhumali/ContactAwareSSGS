"""Registered AITU-v2 roots and conditional full executions; one JVM, no retries."""
import argparse,datetime,json,os,subprocess,sys
from pathlib import Path
from run_research_validation import ROOT,load,save,sha,relative
from run_security_evaluation_se21 import child
from analyze_aitu_revision import normalized
from analyze_pc2 import score_key

BASE=ROOT/'target/aitu-revision-v2';DOC=ROOT/'docs/aitu-revision-v2'
OLD=ROOT/'target/aitu-revision';JAR=ROOT/'target/final-research/final-v2.jar'
JDK=Path(os.environ.get('JAVA_HOME','C:/Program Files/Java/jdk-21.0.10'))
JAVA=str(JDK/'bin/java.exe');PKG='org.contactaware.security.simulation.'
ORIGIN=OLD/'stage1/runs/root-autonomous-t0-H2-CONTINUE_WAIT'

def now():return datetime.datetime.now(datetime.timezone.utc).isoformat()
def once(p,v):
 if p.exists():raise ValueError('Refusing overwrite '+str(p))
 save(p,v)
def classpath(root):
 return os.pathsep.join(map(str,([OLD/'harness-v1/diagnostic-overlay',BASE/'harness/classes'] if root else [])+[OLD/'harness-v1/classes',JAR]))

def register():
 dest=BASE/'harness';dest.mkdir(exist_ok=False);classes=dest/'classes';classes.mkdir()
 source=ROOT/'scripts/aitu-v2/AituBudgetRootMain.java'
 command=[str(JDK/'bin/javac.exe'),'--release','21','-cp',classpath(False),'-d',str(classes),str(source)]
 r=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 (dest/'build.log').write_text(r.stdout);assert r.returncode==0,r.stdout
 frozen=load(ROOT/'target/final-research/final-v2-sources.json')
 for p,h in frozen.items():
  if p.startswith('src/main/'):assert sha(ROOT/p)==h,p
 preserved={relative(p):sha(p) for directory in [ROOT/'docs/aitu-revision',OLD] for p in directory.rglob('*') if p.is_file()}
 for p in (ROOT/'scripts').glob('*aitu*.py'):
  if 'v2' not in p.name:preserved[relative(p)]=sha(p)
 for p in (ROOT/'target/aitu-revision-delivery').glob('*.zip'):preserved[relative(p)]=sha(p)
 preserved.update(load(OLD/'preservation.json'))
 once(BASE/'preservation.json',preserved)
 source_obs=load(ORIGIN/'observation.json')
 paired=load(OLD/'stage1/runs/root-autonomous-t0-H2-CANONICAL/observation.json')
 assert normalized(source_obs)==normalized(paired)
 assert source_obs['at']=='2026-01-01T00:00:00Z' and source_obs['horizon']==2
 cases=[dict(id=f'root-{rule}-N{n}',kind='root',rule=rule,nodes=n,watchdogSeconds=45)
        for rule in ['CANONICAL','CONTINUE_WAIT'] for n in [100000,150000,200000]]
 inputs={relative(p):sha(p) for p in [ORIGIN/'observation.json',ORIGIN/'initial-checkpoint.json',ROOT/'target/final-research/models/X5.json',JAR,source]}
 for rule in ['CANONICAL','CONTINUE_WAIT']:
  p=OLD/f'stage1/runs/root-autonomous-t0-H2-{rule}/practical-audit.json';inputs[relative(p)]=sha(p)
 baseline=load(ORIGIN/'practical-audit.json')[0]['selected']['evaluation'];assert score_key(baseline)==(-2,-38,score_key(baseline)[2],160,0)
 once(dest/'manifest.json',dict(createdUtc=now(),sources={relative(source):sha(source)},classes={relative(p):sha(p) for p in classes.rglob('*.class')},compileCommand=command,
      frozenHarness=load(OLD/'harness-v1/manifest.json'),instrumentation='Existing observational overlay only for roots; no offered plan'))
 once(BASE/'identity.json',dict(createdUtc=now(),commit=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),tree=subprocess.check_output(['git','status','--porcelain=v1'],text=True),
      java=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT,text=True),python=sys.version,jarSha256=sha(JAR),frozenProductionSourceCount=sum(p.startswith('src/main/') for p in frozen),
      historicalPublicPackage='Existing research release is the previous baseline, not this revision'))
 once(BASE/'protocol.json',dict(version='AITU-v2',registeredUtc=now(),protocolSha256=sha(DOC/'protocol.md'),runnerSha256=sha(__file__),inputs=inputs,
      harnessManifestSha256=sha(dest/'manifest.json'),cases=cases,plannedRootSearches=6,maxConditionalControllerRuns=4,oldPracticalReference=baseline,
      searchSeconds=30,decisionSeconds=32,controllerSeconds=180,controllerWatchdogSeconds=210,conditionalRule='For each tie rule choose the smallest registered node allowance whose selected replay-valid score strictly beats oldPracticalReference; then H1/H2 pair',
      matchedSavedObservations=True,order='serial by tie rule then node allowance',heapMiB=1024))
 print('REGISTERED six roots; conditional maximum four controllers',flush=True)

def verify(protocol):
 assert sha(DOC/'protocol.md')==protocol['protocolSha256']
 for p,h in protocol['inputs'].items():assert sha(ROOT/p)==h,p
 m=load(BASE/'harness/manifest.json');assert sha(BASE/'harness/manifest.json')==protocol['harnessManifestSha256']
 for inventory in [m['classes'],m['frozenHarness']['classFiles']]:
  for p,h in inventory.items():assert sha(ROOT/p)==h,p

def roots():
 p=load(BASE/'protocol.json');verify(p)
 rows=load(BASE/'root-summary.json') if (BASE/'root-summary.json').exists() else []
 done={r['id'] for r in rows}
 for c in p['cases']:
  if c['id'] in done:continue
  dest=BASE/'roots'/c['id'];dest.mkdir(parents=True,exist_ok=False)
  flags=dict(observation=relative(ORIGIN/'observation.json'),checkpoint=relative(ORIGIN/'initial-checkpoint.json'),out=relative(dest),rule=c['rule'],nodes=c['nodes'])
  command=[JAVA,'-Xmx1024m','-cp',classpath(True),PKG+'AituBudgetRootMain']
  for k,v in flags.items():command+=['--'+k,str(v)]
  once(dest/'invocation.json',dict(command=command,startedUtc=now(),protocolSha256=sha(BASE/'protocol.json')))
  print('START',c['id'],flush=True);result=child(command,c['watchdogSeconds'],dest/'process.log');once(dest/'process.json',result)
  r=load(dest/'root-result.json') if (dest/'root-result.json').exists() else {}
  rows.append(dict(**c,process=result,status=r.get('status',result['processStatus'])))
  save(BASE/'root-summary.json',rows);print('DONE',c['id'],rows[-1]['status'],round(result['processElapsedNanos']/1e9,2),flush=True)

def controllers():
 p=load(BASE/'protocol.json');verify(p);summary=load(BASE/'root-summary.json');assert len(summary)==6
 select=[];triggers={}
 for rule in ['CANONICAL','CONTINUE_WAIT']:
  eligible=[]
  for c in summary:
   dest=BASE/'roots'/c['id']
   if c['rule']!=rule or not (dest/'selected.json').exists():continue
   r=load(dest/'root-result.json');s=load(dest/'selected.json')
   if r['replayAccepted'] and score_key(s['evaluation'])<score_key(p['oldPracticalReference']):eligible.append(c['nodes'])
  triggers[rule]=min(eligible) if eligible else None
  if eligible:
   select += [dict(id=f'controller-{rule}-N{min(eligible)}-H{h}',kind='controller',rule=rule,nodes=min(eligible),horizon=h,watchdogSeconds=p['controllerWatchdogSeconds']) for h in [1,2]]
 selection=BASE/'controller-registration.json'
 if not selection.exists():once(selection,dict(registeredUtc=now(),sourceProtocolSha256=sha(BASE/'protocol.json'),rootSummarySha256=sha(BASE/'root-summary.json'),triggers=triggers,cases=select))
 else:assert load(selection)['cases']==select
 rows=load(BASE/'controller-summary.json') if (BASE/'controller-summary.json').exists() else []
 for c in select:
  if c['id'] in {x['id'] for x in rows}:continue
  dest=BASE/'controllers'/c['id'];dest.mkdir(parents=True,exist_ok=False)
  flags=dict(model='target/final-research/models/X5.json',initialization='autonomous',mode='practical',horizon=c['horizon'],rule=c['rule'],nodes=c['nodes'],
    **{'search-ms':30000,'decision-budget-ms':32000,'controller-s':180,'period-s':3600,'portfolio':'SEED_AND_RETENTION','latency-ms':0,'host-scale':0,'deadline-ms':32000,'out':relative(dest)})
  command=[JAVA,'-Xmx1024m','-cp',classpath(False),PKG+'AituRunMain']
  for k,v in flags.items():command+=['--'+k,str(v)]
  once(dest/'invocation.json',dict(command=command,startedUtc=now(),registrationSha256=sha(selection)))
  print('START',c['id'],flush=True);result=child(command,c['watchdogSeconds'],dest/'process.log');once(dest/'process.json',result)
  r=load(dest/'result.json') if (dest/'result.json').exists() else {}
  rows.append(dict(**c,process=result,status=r.get('status',result['processStatus'])))
  save(BASE/'controller-summary.json',rows);print('DONE',c['id'],rows[-1]['status'],round(result['processElapsedNanos']/1e9,2),flush=True)
 if not select:save(BASE/'controller-summary.json',[])

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('action',choices=['register','roots','controllers']);args=parser.parse_args();globals()[args.action]()
