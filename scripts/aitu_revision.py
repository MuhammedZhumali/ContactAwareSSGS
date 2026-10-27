"""Separate, immutable AITU revision registrations and serial experiment execution."""
from __future__ import annotations
import argparse, datetime, hashlib, json, os, subprocess, sys
from pathlib import Path
from run_research_validation import ROOT, JAVA, JAVA_HOME, load, save, sha, relative
from run_security_evaluation_se21 import child

OUT=ROOT/'target/aitu-revision'
DOC=ROOT/'docs/aitu-revision'
JAR=ROOT/'target/final-research/final-v2.jar'
PKG='org.contactaware.security.simulation.'

def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def once(path,value):
    if path.exists(): raise ValueError('Refusing overwrite: '+str(path))
    save(path,value)

def build():
    dest=OUT/'harness-v1'; dest.mkdir(parents=True,exist_ok=False)
    frozen=load(ROOT/'target/final-research/final-v2-sources.json')
    for name in ['ExactHorizonPlanner','ResearchValidationMain']:
        source=ROOT/f'src/main/java/org/contactaware/security/simulation/{name}.java'
        assert sha(source)==frozen[relative(source)],'Source differs from frozen final-v2: '+name
    text=(ROOT/'src/main/java/org/contactaware/security/simulation/ResearchValidationMain.java').read_text()
    text=text.replace('ResearchValidationMain','AituRunMain')
    old='var initial=new SimulationEngine().start(scenario);'
    assert text.count(old)==1
    text=text.replace(old,'''var initial=AituInitialState.create(scenario,f.getOrDefault("initialization","autonomous"));
        ExecutionCheckpointFiles.write(out.resolve("initial-checkpoint.json"),initial);
        JsonFiles.write(out.resolve("initial-observation.json"),PlanningObservation.from(initial,h));''')
    (dest/'AituRunMain.java').write_text(text)
    exact=(ROOT/'src/main/java/org/contactaware/security/simulation/ExactHorizonPlanner.java').read_text()
    old='if (solved != null && (best == null || comparator.compare(solved, best) < 0)) best = solved;'
    assert exact.count(old)==1
    exact=exact.replace(old,'''if (solved != null && (best == null || comparator.compare(solved, best) < 0)) {
                    best = solved;
                    IncumbentProbe.improved(explored, control.elapsedNanos(), solved);
                }''')
    (dest/'ExactHorizonPlanner.java').write_text(exact)
    common=dest/'classes'; overlay=dest/'diagnostic-overlay'; common.mkdir();overlay.mkdir()
    sources=list((ROOT/'scripts/aitu').glob('*.java'))+[dest/'AituRunMain.java']
    commands=[
        [str(JAVA_HOME/'bin/javac.exe'),'--release','21','-cp',str(JAR),'-d',str(common),*[str(p) for p in sources]],
        [str(JAVA_HOME/'bin/javac.exe'),'--release','21','-cp',os.pathsep.join(map(str,[common,JAR])),
         '-d',str(overlay),str(dest/'ExactHorizonPlanner.java')]]
    for i,command in enumerate(commands):
        run=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        (dest/f'compile-{i}.log').write_text(run.stdout)
        if run.returncode: raise RuntimeError(run.stdout)
    manifest={'createdUtc':now(),'jar':relative(JAR),'jarSha256':sha(JAR),
        'sourceManifest':relative(ROOT/'target/final-research/final-v2-sources.json'),
        'sources':{relative(p):sha(p) for p in sources+[dest/'ExactHorizonPlanner.java']},
        'classFiles':{relative(p):sha(p) for p in dest.rglob('*.class')},
        'commands':commands,'java':subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT,text=True),
        'gitCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        'gitStatus':subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),
        'productionModified':False,'ordinaryRunsUseOverlay':False}
    once(dest/'manifest.json',manifest)
    print('Harness compiled; frozen production JAR unchanged',flush=True)

def cp(overlay=False):
    paths=[OUT/'harness-v1/classes',JAR]
    if overlay:paths.insert(0,OUT/'harness-v1/diagnostic-overlay')
    return os.pathsep.join(map(str,paths))

def equivalence():
    base=OUT/'harness-equivalence';base.mkdir(exist_ok=False)
    for instrumented in [False,True]:
        dest=base/('instrumented' if instrumented else 'frozen');dest.mkdir()
        flags={'model':str(ROOT/'target/final-research/models/X5.json'),'initialization':'prepared',
               'horizon':1,'rule':'CONTINUE_WAIT','exact-nodes':5000,'exact-ms':0,'equivalence-only':'true','out':str(dest)}
        command=[JAVA,'-Xmx1024m','-cp',cp(instrumented),PKG+'AituRootMain']
        for k,v in flags.items():command += ['--'+k,str(v)]
        outcome=child(command,45,dest/'process.log');save(dest/'process.json',outcome)
        assert outcome['processStatus']=='EXITED',outcome
    a,b=[load(base/k/'exact-result.json') for k in ['frozen','instrumented']]
    fields=['bestPlan','score','firstAction','status','exploredNodes']
    checks={k:a[k]==b[k] for k in fields}
    assert all(checks.values()),checks
    once(base/'validation.json',{'checks':checks,'status':a['status'],'nodes':a['exploredNodes'],
         'scope':'Fixed-node instrumentation equivalence; not proof for all inputs',
         'protocol':'Prepared X H1 CONTINUE_WAIT, 5000 nodes, no internal timer, 45s watchdog; before campaign'})
    print('Overlay equivalence',checks,flush=True)

def register_stage1():
    assert (OUT/'harness-equivalence/validation.json').exists()
    base=OUT/'stage1';base.mkdir(exist_ok=False)
    model=ROOT/'target/final-research/models/X5.json'
    cases=[]
    for init in ['prepared','autonomous','autonomous-t0']:
        for h in [1,2]:
            for rule in ['CANONICAL','CONTINUE_WAIT']:
                cases.append(dict(id=f'root-{init}-H{h}-{rule}',kind='root',initialization=init,horizon=h,rule=rule,
                     model=relative(model),modelSha256=sha(model),watchdogSeconds=60))
    for init in ['prepared','autonomous']:
        for mode in ['exact','practical']:
            for h in [1,2]:
                for rule in ['CANONICAL','CONTINUE_WAIT']:
                    cases.append(dict(id=f'run-{init}-{mode}-H{h}-{rule}',kind='controller',mode=mode,
                       initialization=init,horizon=h,rule=rule,model=relative(model),modelSha256=sha(model),watchdogSeconds=150,
                       nodes=2000000 if mode=='exact' else 5000,searchMs=30000 if mode=='exact' else 1000,
                       decisionMs=0 if mode=='exact' else 2000,controllerSeconds=120,measured=False,
                       portfolio='SEED_AND_RETENTION'))
    preservation={relative(p):sha(p) for parent in ['docs/final-research','docs/paper-verified-20261027']
                  for p in (ROOT/parent).rglob('*') if p.is_file()}
    for name in ['target/final-research/summary.json','target/final-research/protocol.json',
                 'target/final-research/final-v2.jar','target/final-research/final-reproduction.zip',
                 'target/github-release/ContactAwareSS-final-v2-reproduction-public.zip']:
        p=ROOT/name
        if p.exists():preservation[name]=sha(p)
    once(OUT/'preservation.json',preservation)
    once(base/'protocol.json',{'stage':'AITU-1','registeredUtc':now(),'cases':cases,
       'plannedRootDiagnostics':12,'plannedControllerAttempts':16,
       'protocolTextSha256':sha(DOC/'stage1-protocol.md'),'harnessManifestSha256':sha(OUT/'harness-v1/manifest.json'),
       'runnerSha256':sha(__file__),'jarSha256':sha(JAR),'heapMiB':1024,'execution':'serial JVMs, offline'})
    print('Registered 12 paired roots and 16 full controller attempts',flush=True)

def run(stage):
    base=OUT/stage; protocol=load(base/'protocol.json')
    assert sha(JAR)==protocol['jarSha256']
    assert sha(OUT/'harness-v1/manifest.json')==protocol['harnessManifestSha256']
    for p,h in load(OUT/'harness-v1/manifest.json')['classFiles'].items(): assert sha(ROOT/p)==h,p
    rows=load(base/'summary.json') if (base/'summary.json').exists() else []
    done={r['id'] for r in rows}
    for c in protocol['cases']:
        if c['id'] in done:continue
        dest=base/'runs'/c['id'];dest.mkdir(parents=True,exist_ok=False)
        assert sha(ROOT/c['model'])==c['modelSha256']
        flags={k:c[k] for k in ['initialization','horizon','rule','model']}
        flags['out']=relative(dest)
        root=c['kind']=='root'
        if not root:
            flags.update(mode=c['mode'],nodes=c['nodes'],portfolio=c['portfolio'],**{
                'search-ms':c['searchMs'],'decision-budget-ms':0 if c['measured'] else c['decisionMs'],
                'controller-s':c['controllerSeconds'],'period-s':3600,'latency-ms':0,
                'host-scale':1 if c['measured'] else 0,'deadline-ms':c['decisionMs']})
        command=[JAVA,'-Xmx1024m','-cp',cp(root),PKG+('AituRootMain' if root else 'AituRunMain')]
        for k,v in flags.items():command += ['--'+k,str(v)]
        once(dest/'invocation.json',dict(command=command,startedUtc=now(),protocolSha256=sha(base/'protocol.json')))
        print('START',c['id'],flush=True)
        outcome=child(command,c['watchdogSeconds'],dest/'process.log')
        once(dest/'process.json',outcome)
        path=dest/('root-result.json' if root else 'result.json')
        result=load(path) if path.exists() else None
        status=outcome['processStatus'] if outcome['processStatus']!='EXITED' else (
            result.get('exactStatus','ROOT_DIAGNOSTIC_FINISHED') if root and result else result['status'] if result else 'EXPORT_INCOMPLETE')
        rows.append(dict(**c,process=outcome,status=status));save(base/'summary.json',rows)
        print('DONE',c['id'],status,round(outcome['processElapsedNanos']/1e9,2),flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['build','equivalence','register_stage1','run'])
    parser.add_argument('--stage',default='stage1');args=parser.parse_args()
    run(args.stage) if args.action=='run' else globals()[args.action]()
