"""Predeclare remaining small blocks. No outcome-dependent factor selection or policy runs."""
import copy, json, sys
from decimal import Decimal
from pathlib import Path
from aitu_revision import ROOT, OUT, DOC, JAR, now, once
from run_research_validation import load, sha, relative
from recovery_metrics_se21 import instant, utc

RULES=['CANONICAL','CONTINUE_WAIT']

def common(name,model,mode,h,rule,init='autonomous',nodes=5000,measured=False):
    return dict(id=name,kind='controller',model=relative(model),modelSha256=sha(model),mode=mode,
                initialization=init,horizon=h,rule=rule,watchdogSeconds=150,nodes=nodes,
                searchMs=30000 if mode=='exact' else 1000,decisionMs=0 if mode=='exact' else 2000,
                controllerSeconds=120,measured=measured,portfolio='SEED_AND_RETENTION')

def register(stage,cases,reused=None,extra=None):
    base=OUT/stage;base.mkdir(exist_ok=False)
    once(base/'protocol.json',dict(stage=stage,registeredUtc=now(),cases=cases,reusedCells=reused or [],
        plannedLogicalCells=len(cases)+len(reused or []),plannedNewAttempts=len(cases),
        protocolTextSha256=sha(DOC/(stage+'-protocol.md')),jarSha256=sha(JAR),
        harnessManifestSha256=sha(OUT/'harness-v1/manifest.json'),
        registrationScriptSha256=sha(__file__),runnerSha256=sha(ROOT/'scripts/aitu_revision.py'),
        heapMiB=1024,execution='serial JVMs; no retry or automatic budget escalation',**(extra or {})))
    print('Registered',stage,len(cases),'new;',len(reused or []),'reused',flush=True)

def stage2():
    cases=[];reused=[]
    for i in range(1,10):
        model=ROOT/'target/final-research/models/X5.json' if i==1 else OUT/f'stage2-models/X{i:02d}.json'
        for h in [1,2]:
            for rule in RULES:
                c=common(f'X{i:02d}-exact-H{h}-{rule}',model,'exact',h,rule,'prepared',2000000)
                c['family']=f'X{i:02d}'
                if i==1:
                    source=OUT/f'stage1/runs/run-prepared-exact-H{h}-{rule}'
                    conf=load(source/'result.json')['configuration']
                    expected={'model':c['model'],'initialization':c['initialization'],'mode':'exact','nodes':'2000000',
                        'horizon':str(h),'rule':rule,'search-ms':'30000','controller-s':'120','decision-budget-ms':'0',
                        'host-scale':'0','portfolio':c['portfolio']}
                    assert all(conf[k]==v for k,v in expected.items()),conf
                    c['reuseFrom']=relative(source);c['sourceResultSha256']=sha(source/'result.json');reused.append(c)
                else:cases.append(c)
    extra={'generatorSourceSha256':sha(ROOT/'scripts/aitu/AituFixtureMain.java'),
           'generatorClassHashes':{relative(p):sha(p) for p in (OUT/'fixture-build').rglob('*.class')},
           'deadlineAudits':{relative(p):sha(p) for p in (OUT/'stage2-models').glob('*-deadlines.json')}}
    register('stage2',cases,reused,extra)

def stage4():
    source=ROOT/'target/final-research/models/demand-late-detected.json'
    original=load(source);s=original['scenario'];c=s['contacts'][0]
    assert c['payload']['frameBytes']==1000 and c['payload']['bytesPerSecond']==100
    end=instant(c['window']['end']);start=instant(c['window']['start'])
    modeldir=OUT/'stage4-models';modeldir.mkdir(exist_ok=False);cases=[]
    for before in [40,20,5]:
        for delay in [0,2,20]:
            data=copy.deepcopy(original);scenario=data['scenario'];event=scenario['disclosures'][0]
            event['at']=utc(end-Decimal(before));event['detectionAt']=utc(end-Decimal(before)+Decimal(delay))
            assert start<instant(event['at'])<end
            assert event['contextId']=={k:c['window'][k] for k in ['satelliteId','groundStationId']}
            model=modeldir/f'Eminus{before}-delay{delay}.json'
            once(model,data)
            for h in [1,2]:
                for rule in RULES:
                    case=common(f'Eminus{before}-delay{delay}-H{h}-{rule}',model,'practical',h,rule)
                    case.update(secondsBeforeContactEnd=before,detectionDelaySeconds=delay);cases.append(case)
    register('stage4',cases,extra={'baseModel':relative(source),'baseModelSha256':sha(source),
                                  'attackTimesFromGeometry':True,'frameSeconds':10})

def stage3():
    assert load(OUT/'seed-harness-v2/validation.json')['status']=='PASS'
    cases=[];reused=[]
    for label,filename in [('X5','X5.json'),('long','demand-none.json')]:
        model=ROOT/'target/final-research/models'/filename
        for nodes in [0,1000,5000,20000,100000]:
            for h in [1,2]:
                mode='seedonly' if nodes==0 else 'practical'
                c=common(f'{label}-offline-N{nodes}-H{h}',model,mode,h,'CANONICAL',nodes=nodes)
                c['budgetLabel']='seed plus retained continuations; DFS disabled' if nodes==0 else str(nodes)
                c['caseLabel']=label
                if label=='X5' and nodes==5000:
                    source=OUT/f'stage1/runs/run-autonomous-practical-H{h}-CANONICAL'
                    c['reuseFrom']=relative(source);c['sourceResultSha256']=sha(source/'result.json');reused.append(c)
                else:cases.append(c)
        for nodes in [1000,20000]:
            for h in [1,2]:
                c=common(f'{label}-measured-N{nodes}-H{h}',model,'practical',h,'CANONICAL',nodes=nodes,measured=True)
                c['caseLabel']=label;c['budgetLabel']=str(nodes);cases.append(c)
    register('stage3',cases,reused,extra={'seedHarnessManifestSha256':sha(OUT/'seed-harness-v2/manifest.json'),
        'seedHarnessValidationSha256':sha(OUT/'seed-harness-v2/validation.json'),
        'actualRunner':'scripts/run_aitu_budget.py','actualRunnerSha256':sha(ROOT/'scripts/run_aitu_budget.py')})

if __name__=='__main__':globals()[sys.argv[1]]()
