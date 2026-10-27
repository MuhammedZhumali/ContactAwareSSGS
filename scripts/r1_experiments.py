"""Compact preregistered R1 matrix; serial isolated JVMs, no automatic retries."""
import argparse, copy, datetime as dt, json, shutil, subprocess, sys
from pathlib import Path
from run_research_validation import ROOT, OUT, JAVA, load, save, sha, relative
from run_security_evaluation_se21 import child

MAIN='org.contactaware.security.simulation.ResearchValidationMain'
GEOMETRY=OUT/'geometry-v2'

def binary(): return load(OUT/'candidate-binary.json')
def jrun(args, timeout, log, identity=None):
    b=identity or binary(); assert sha(ROOT/b['jar'])==b['sha256']
    return child([JAVA,'-Xmx1024m','-cp',str(ROOT/b['jar']),*args],timeout,log)

def geometry():
    folder=GEOMETRY; folder.mkdir(exist_ok=False)
    for norad in [33591,20580]:
        source=OUT/f'orbit-sources/{norad}/gp.json'; dest=folder/f'orbit-adapter/{norad}/gp.json'
        dest.parent.mkdir(parents=True);shutil.copy2(source,dest)
        old=load(source.with_suffix('.json.source.json'))
        save(dest.with_suffix('.json.source.json'),dict(url=old['url'],retrievedAt=old['retrievedAtUtc'],file='gp.json',sha256=old['sha256'],responseHeaders=old['responseHeaders']))
        assert sha(source)==sha(dest)
    specifications=[]
    for norad in [33591,20580]:
        # Same date derived from the two accepted source epochs; see orbit acquisition protocol.
        for name,mask,guard,cohort in [('base',10,0,32),('elevation20',20,0,32),('guard30',10,30,32),('cohort16',10,0,16)]:
            specifications.append(dict(id=f'{norad}-{name}',norad=norad,mask=mask,guard=guard,cohort=cohort))
    save(folder/'protocol.json',dict(specifications=specifications,selection='Original acquisition protocol; all chronological contacts, no policy-dependent thinning',
        interval=['2026-10-25T00:00:00Z','2026-10-26T00:00:00Z'],cohort='Sort original station IDs lexicographically; take first16 for cohort sensitivity',
        sources={relative(p):sha(p) for p in (OUT/'orbit-sources').rglob('*') if p.is_file()},jar=binary(),limits=dict(processSeconds=90)))
    stations=load(ROOT/'data/runs/iss-20261010/orekit/raw/stations.json')
    save(folder/'cohort16/stations.json',sorted(stations,key=lambda s:s['id'])[:16])
    results=[]
    for c in specifications:
        out=folder/c['id']; out.mkdir()
        dataset=out/'orekit/dataset.json'
        if c['id'].endswith('guard30'):
            base=folder/f"{c['norad']}-base/orekit"; shutil.copytree(base,out/'orekit')
        else:
            args=['org.contactaware.contacts.Main','orekit','--norad',str(c['norad']),'--start','2026-10-25T00:00:00Z','--end','2026-10-26T00:00:00Z',
                '--orbit',str(folder/f"orbit-adapter/{c['norad']}/gp.json"),'--stations',str(folder/'cohort16/stations.json' if c['cohort']==16 else ROOT/'data/runs/iss-20261010/orekit/raw/stations.json'),
                '--orekit-data',str(ROOT/'data/sources/earth-20261011'),'--min-elevation-deg',str(c['mask']),'--max-epoch-distance-hours','72','--out',str(out/'orekit')]
            p=jrun(args,90,out/'geometry.log')
            if not dataset.exists():
                results.append(dict(**c,status='GENERATION_FAILED',process=p));save(folder/'summary.json',results);continue
        p=jrun(['org.contactaware.contacts.Main','schedule-select','--dataset',str(dataset),'--switch-guard-seconds',str(c['guard']),'--out',str(out/'schedule')],90,out/'selection.log')
        schedule=out/'schedule/schedule.json'
        results.append(dict(**c,status='GENERATED' if schedule.exists() else 'SELECTION_FAILED',process=p,
            geometric=len(load(dataset)['contacts']),selected=len(load(schedule)['contacts']) if schedule.exists() else None,
            schedule=relative(schedule),sha256=sha(schedule) if schedule.exists() else None))
        save(folder/'summary.json',results);print(c['id'],results[-1]['status'],results[-1].get('selected'),flush=True)

def configure():
    directory=OUT/'models'; directory.mkdir(exist_ok=False)
    sources={}
    def model(name,path,change=None):
        original=load(ROOT/path); value=copy.deepcopy(original)
        if change: change(value['scenario'])
        dest=directory/(name+'.json');save(dest,value)
        sources[name]=dict(file=relative(dest),sha256=sha(dest),source=path,sourceSha256=sha(ROOT/path),change='IDENTICAL JSON values' if change is None else name)
    old='target/security-evaluation-se22/models/'
    model('X5',old+'X-none.model.json');model('Y',old+'Y-none.model.json')
    for n in [3,9]:
        def duration(s,n=n):
            value=f'2026-01-01T00:01:{20+n:02d}Z';s['end']=value;s['contacts'][-1]['window']['end']=value
        model('X'+str(n),old+'X-none.model.json',duration)
    for attack in ['none','late-detected']:
        model('demand-'+attack,'target/security-evaluation-remaining-demand/models/'+attack+'.json')
        model('day1-'+attack,'target/security-evaluation-new-days/day1/models/'+attack+'.json')
    for attack in ['original-detected','p30-detected']:
        model('ISS5400-'+attack,old+'iss3-cp5400-'+attack+'.model.json')
    model('ISS5400-none',old+'iss3-cp5400-none.model.json')
    for name,at,notice in [('undetected',8,None),('detected0',8,8),('detected2',8,10),('detected8',8,16),('gap',60,62),('late',18,20)]:
        def incident(s,at=at,notice=notice):
            instant=lambda x:(dt.datetime(2026,1,1,tzinfo=dt.timezone.utc)+dt.timedelta(seconds=x)).isoformat().replace('+00:00','Z')
            s['disclosures']=[dict(id='R1-disclosure',contextId=dict(satelliteId='WITNESS-SC',groundStationId='A'),at=instant(at),detectionAt=None if notice is None else instant(notice))]
        model('X-'+name,old+'X-none.model.json',incident)
    # One factor at a time, offer and required minima are intentionally distinct axes.
    for factor,value in [('INSTALL',.5),('INSTALL',2),('REUSE',2),('SWITCH',2)]:
        def cost(s,factor=factor,value=value):
            for p in s['costs']['operations'][factor]['phases']: p['duration']*=value
            s['costs']['name']='R1-synthetic-'+factor+str(value);s['costs']['source']+='; duration-only sensitivity; bytes fixed'
        model(f'X-cost-{factor}-{value}',old+'X-none.model.json',cost)
    for field,value in [('cryptoperiod',90),('cryptoperiod',240),('maxMaterialAge',200),('maxReuseGap',50)]:
        model('X-'+field+'-'+str(value),old+'X-none.model.json',lambda s,field=field,value=value:s['constraints'].__setitem__(field,value))
    for name,offered,minimum,frame in [('light',400,400,100),('medium',1600,400,100),('minimum800',9223372036854775807,800,100),('frame200',9223372036854775807,400,200)]:
        def traffic(s,offered=offered,minimum=minimum,frame=frame):
            for c in s['contacts']:
                c['payload'].update(offeredUplinkBytes=offered,offeredDownlinkBytes=offered,minimumUplinkBytes=minimum,minimumDownlinkBytes=minimum,frameBytes=frame)
        model('X-'+name,old+'X-none.model.json',traffic)
    for name,begin,end in [('temporary',5,7),('early-los',15,20),('late-aos',0,4),('missed',0,20)]:
        def channel(s,begin=begin,end=end):
            s['channelInterruptions']=[dict(contactId='C1',from_=f'2026-01-01T00:00:{begin:02d}Z',until=f'2026-01-01T00:00:{end:02d}Z')]
            s['channelInterruptions'][0]['from']=s['channelInterruptions'][0].pop('from_')
        model('X-channel-'+name,old+'X-none.model.json',channel)
    schedules=[('ISS46','data/runs/iss-20261010/schedule/schedule.json'),('ISS44','target/security-evaluation-new-days/day1/schedule/schedule.json'),('ISS45','target/security-evaluation-new-days/day2/schedule/schedule.json')]
    schedules += [(r['id'],r['schedule']) for r in load(GEOMETRY/'summary.json') if r['status']=='GENERATED']
    save(OUT/'workload-configuration-protocol.json',dict(schedules=schedules,selection='All contacts, state retained; LIGHT CP5400; geometry perturbations in separate common schedules',jar=binary()))
    for name,schedule in schedules:
        dest=directory/(name+'.json')
        p=jrun([MAIN,'--mode','configure','--schedule',str(ROOT/schedule),'--count','0','--cp','5400','--workload','LIGHT','--out',str(dest)],30,directory/(name+'.log'))
        if not dest.exists(): raise RuntimeError((name,p))
        sources[name]=dict(file=relative(dest),sha256=sha(dest),source=schedule,sourceSha256=sha(ROOT/schedule),change='Java verified chronological full schedule, empty keys')
    save(directory/'manifest.json',sources)
    print('Configured',len(sources),'models',flush=True)

def register():
    sources=load(OUT/'models/manifest.json')
    def pair(models,mode='practical',rules=('CANONICAL','CONTINUE_WAIT')):
        return [dict(model=m,mode=mode,rule=r,horizon=h) for m in models for r in rules for h in [1,2]]
    exact=pair(['X3','X5','X9','Y','demand-none','demand-late-detected','day1-none','day1-late-detected','ISS5400-none','ISS5400-p30-detected'],mode='exact')
    practical=pair(['X3','X5','X9','Y','demand-none','demand-late-detected','ISS46','ISS44','ISS45','33591-base','20580-base'])
    security=pair(['X-undetected','X-detected0','X-detected2','X-detected8','X-gap','X-late'])
    disruptions=pair(['X-channel-'+n for n in ['temporary','early-los','late-aos','missed']])
    sensitivity=pair([m for m in sources if m.startswith(('X-cost-','X-cryptoperiod-','X-maxMaterialAge-','X-maxReuseGap-'))]+['X-light','X-medium','X-minimum800','X-frame200'],rules=('CANONICAL',))
    sensitivity += [dict(model='X5',mode='practical',rule=r,horizon=3) for r in ['CANONICAL','CONTINUE_WAIT']]
    sensitivity += pair(['33591-elevation20','33591-guard30','33591-cohort16','20580-elevation20','20580-guard30','20580-cohort16'],rules=('CANONICAL',))
    controls=[dict(model=m,mode='fixed',rule='CANONICAL',horizon=1,period_s=t) for m,periods in [('X5',[30,60,90,120]),('ISS46',[1800,3600,5400])] for t in periods]
    controls += [dict(model=m,mode='percontact',rule='CANONICAL',horizon=1) for m in ['X5','Y','ISS46']]
    deadlines=[dict(model='X5',mode='practical',rule=r,horizon=h,latency_ms=lat,deadline_ms=250) for r in ['CANONICAL','CONTINUE_WAIT'] for h in [1,2] for lat in [50,500]]
    for series,cases in [('exact',exact),('practical',practical),('security',security),('disruptions',disruptions),('sensitivity',sensitivity),('controls',controls),('deadlines',deadlines)]:
        path=OUT/series;path.mkdir(exist_ok=False)
        for i,c in enumerate(cases): c.update(id=f'{i+1:03d}-{c["model"]}-{c["mode"]}-H{c["horizon"]}-{c["rule"]}',input=sources[c['model']])
        limits=dict(nodes=2000000,searchMs=30000,controllerSeconds=60,processSeconds=90) if series=='exact' else dict(nodes=5000,searchMs=1000,controllerSeconds=20,processSeconds=35)
        save(path/'protocol.json',dict(series=series,jar=binary(),cases=cases,limits=limits,seed=None,
            objective=['served','useful','-CPU','-managementBytes','-newKeys'],policyDifferences='Only H within matched mode/rule/input/budget',
            stopping='One attempt each; no automatic budget escalation/retry; process error stops series for inspection, incomplete retained',
            exclusions=[],unit='Deterministic configured scenario, not repeated statistical samples',
            attack='One-time active directional traffic pair at specified external event; recorded ciphertext; management/root protected; no access to replacements',
            provenance='Historical source IDs retained; new model/identity, all costs synthetic; source profile changed only where manifest says so'))
        print('Registered',series,len(cases),flush=True)

def run(series):
    folder=OUT/series;reg=load(folder/'protocol.json');b=reg['jar']; limits=reg['limits'];rows=[]
    assert sha(ROOT/b['jar'])==b['sha256']
    for c in reg['cases']:
        out=folder/'runs'/c['id'];out.mkdir(parents=True,exist_ok=False)
        assert sha(ROOT/c['input']['file'])==c['input']['sha256']
        flags=dict(mode=c['mode'],model=str(ROOT/c['input']['file']),rule=c['rule'],horizon=c['horizon'],nodes=limits['nodes'],
            **{'search-ms':limits['searchMs'],'controller-s':limits['controllerSeconds'],'out':str(out),
               'period-s':c.get('period_s',60),'latency-ms':c.get('latency_ms',0),'deadline-ms':c.get('deadline_ms',250)})
        args=[MAIN]+[str(x) for k,v in flags.items() for x in ['--'+k,v]]
        process=jrun(args,limits['processSeconds'],out/'process.log',b)
        result=load(out/'result.json') if (out/'result.json').exists() else None
        row=dict(**c,process=process,status=result['status'] if result else process['processStatus'],result=result)
        rows.append(row);save(folder/'summary.json',rows);print(series,c['id'],row['status'],flush=True)
        if result is None: break

def register_practical_v2():
    """Version change before any practical attempts; original unattempted protocols retained."""
    identity=load(OUT/'practical-v2-binary.json')
    for name in ['practical','security','disruptions','sensitivity','controls','deadlines']:
        original=OUT/name/'protocol.json'
        assert not (OUT/name/'summary.json').exists(), 'Cannot supersede attempted series'
        reg=load(original); reg['series']=name+'-v2';reg['jar']=identity
        reg['supersedesUnattemptedProtocol']=dict(file=relative(original),sha256=sha(original))
        reg['versionReason']='Separate practical feasible suffix reuse after completed CONTINUE; full observation match, no optimality certificate; unchanged limits and matrix'
        folder=OUT/(name+'-v2');folder.mkdir(exist_ok=False);save(folder/'protocol.json',reg)
        print('Registered',reg['series'],len(reg['cases']),flush=True)

def register_delay_export_repair():
    original=OUT/'deadlines-v2/protocol.json';reg=load(original)
    reg['series']='deadlines-v3';reg['jar']=load(OUT/'delay-export-binary.json')
    reg['previousAttempt']='deadlines-v2: first attempt PROCESS_ERROR during export of internal certificate; other seven stopped by protocol'
    reg['versionReason']='Export-only fix: serialize action/search/reason, never the internal CertifiedSearch capability; controller/model/limits unchanged'
    reg['supersedesProtocol']=dict(file=relative(original),sha256=sha(original))
    folder=OUT/'deadlines-v3';folder.mkdir(exist_ok=False);save(folder/'protocol.json',reg)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('command',choices=['geometry','configure','register','run']);p.add_argument('--series');a=p.parse_args()
    if a.command=='run':run(a.series)
    else:globals()[a.command]()
