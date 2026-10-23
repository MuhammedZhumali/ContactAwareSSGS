#!/usr/bin/env python3
"""SE2.3 frozen C3-duration series: 40 independent one-shot children, one JVM at a time."""
import argparse, copy, csv, hashlib, json, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from run_security_evaluation_se21 import ROOT, child, sha, load, write, rel
from run_security_evaluation_se22 import JAVA
OUT=ROOT/'target/security-evaluation-c3'
DOC=ROOT/'docs/security-evaluation-c3'
MAIN='org.contactaware.security.simulation.SecurityEvaluationC3Main'
ATTACKS=['none','original-detected','p70-detected','p90-detected','gap-midpoint-detected']

def normalized(s):
    s=copy.deepcopy(s)
    s['constraints']['authorizedContexts'].sort(key=lambda c:(c['satelliteId'],c['groundStationId']))
    s['constraints']['blockedKeyIds'].sort()
    return s

def differences(a,b,path=''):
    if isinstance(a,dict) and isinstance(b,dict):
        assert a.keys()==b.keys(),path
        return sum((differences(a[k],b[k],path+'/'+k) for k in a),[])
    if isinstance(a,list) and isinstance(b,list):
        assert len(a)==len(b),path
        return sum((differences(x,y,path+'/'+str(i)) for i,(x,y) in enumerate(zip(a,b))),[])
    return [] if a==b else [dict(path=path,before=a,after=b)]

def preserve():
    data=load(OUT/'preserved-inputs.json')
    for p,h in data.items():assert sha(ROOT/p)==h,p
    return len(data)

def configure():
    if OUT.exists():raise ValueError('Refusing to replace existing stage')
    OUT.mkdir(parents=True);DOC.mkdir(parents=True,exist_ok=True)
    prior=load(ROOT/'target/security-evaluation-se222/preserved-inputs.json')
    for base in (ROOT/'target/security-evaluation-se222', ROOT/'docs/security-evaluation-se222'):
        for p in base.rglob('*'):
            if p.is_file() and 'test-tmp' not in p.relative_to(base).parts:prior[rel(p)]=sha(p)
    write(OUT/'preserved-inputs.json',prior);preserve()
    table=list(csv.DictReader((ROOT/'docs/security-evaluation-se222/comparison.csv').open(encoding='utf-8')))
    assert len(table)==20 and all(r['status']=='FINISHED' for r in table)
    baseline=[]; models={}; hashes={}; baseline_diffs=[]
    for r in table:
        source=ROOT/r['source'];old=load(source)[r['case']];model=source.parent/old['modelFile']
        s=normalized(load(model)['scenario']);models[r['case']]=s
        hashes[rel(source)]=sha(source);hashes[rel(model)]=sha(model)
        baseline.append(dict(name='C3-5-'+r['case'],legacyName=r['case'],duration=5,source=r['source'],
                             commit=r['commit'],version=r['version'],policy=r['policy'],rule=r['rule'],attack=r['attack']))
    reference=models['X-next-none-canonical']
    # Across original policies/rules only incident fields/name may differ; within an attack nothing differs.
    for attack in ATTACKS:
        first=models['X-next-'+attack+'-canonical']
        for policy in ('next','multi'):
            for rule in ('canonical','continue_wait'):
                name='X-'+policy+'-'+attack+'-'+rule
                delta=differences(first,models[name]);assert not delta,(name,delta)
                baseline_diffs.append(dict(case=name,withinAttackDifferences=delta))
        a,b=copy.deepcopy(reference),copy.deepcopy(first)
        a.pop('name');b.pop('name');a.pop('disclosures');b.pop('disclosures')
        assert a==b,attack
        expected={'original-detected':10,'p70-detected':14,'p90-detected':18,'gap-midpoint-detected':25}
        if attack!='none':
            incident=first['disclosures'][0]
            from audit_security_evaluation import instant
            epoch=instant(first['contacts'][0]['window']['start'])
            assert instant(incident['at'])-epoch==expected[attack]
            assert instant(incident['detectionAt'])-instant(incident['at'])==2
    cases=[];deltas=[]
    for duration in (3,9):
        for attack in ATTACKS:
            for rule in ('CANONICAL','CONTINUE_WAIT'):
                for policy in ('next','multi'):
                    oldname='X-'+policy+'-'+attack+'-'+rule.lower();s=copy.deepcopy(models[oldname])
                    s['end']=f'2026-01-01T00:01:{20+duration:02d}Z';s['contacts'][2]['window']['end']=s['end']
                    delta=differences(models[oldname],s)
                    assert {d['path'] for d in delta}=={'/end','/contacts/2/window/end'}
                    name='C3-'+str(duration)+'-'+oldname;path=OUT/'models'/(name+'.json')
                    write(path,dict(scenario=s,provenance='Only C3.window.end and scenario.end changed from '+oldname))
                    spec=dict(name=name,legacyName=oldname,duration=duration,attack=attack,policy=policy,horizon=1 if policy=='next' else 2,
                              rule=rule,fixture='X',mode='none' if attack=='none' else 'detected',modelFile=path.relative_to(OUT).as_posix(),
                              experimentEnd=s['end'],censorAt=s['end'])
                    cases.append(spec);deltas.append(dict(case=name,differences=delta));hashes[rel(path)]=sha(path)
    assert len(cases)==40
    write(OUT/'configurations.json',dict(cases=cases,baseline=baseline,inputs=hashes))
    write(DOC/'configuration-audit.json',dict(status='PASS',baselineComparisons=baseline_diffs,newComparisons=deltas,
        permittedPaths=['/end','/contacts/2/window/end'],payload=reference['contacts'][2]['payload'],
        note='JSON order of Set authorizedContexts/blockedKeyIds normalized; all other arrays and values compared exactly.'))
    print('Configured 40 new and 20 retained cases; prior hashes',len(prior))

def prepare():
    preserve()
    if (OUT/'build-preflight.json').exists():raise ValueError('Build exists')
    assert not subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True).strip()
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    base=ROOT/'target/c3-clean-build'/commit;base.mkdir(parents=True,exist_ok=False)
    source=base/'source';archive=base/'source.zip'
    subprocess.run(['git','archive','--format=zip',f'--output={archive}',commit],cwd=ROOT,check=True)
    with zipfile.ZipFile(archive) as z:z.extractall(source)
    (base/'tmp').mkdir()
    write(OUT/'build-preflight.json',dict(commit=commit,sourceDirectory=str(source),archive=rel(archive),
        tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),rootStatus='clean'))
    print(source)

def freeze():
    assert not (OUT/'build.json').exists()
    b=load(OUT/'build-preflight.json');source=Path(b['sourceDirectory']);log=OUT/'build.log'
    assert 'BUILD SUCCESS' in log.read_text(encoding='utf-8')
    hashes={}
    with zipfile.ZipFile(ROOT/b['archive']) as z:
        for e in z.infolist():
            if not e.is_dir():
                hashes[e.filename]=hashlib.sha256(z.read(e.filename)).hexdigest();assert sha(source/e.filename)==hashes[e.filename]
    counts={k:sum(int(ET.parse(p).getroot().attrib[k]) for p in (source/'target/surefire-reports').glob('TEST-*.xml')) for k in ('tests','failures','errors','skipped')}
    assert counts['tests']==23 and counts['failures']==counts['errors']==counts['skipped']==0
    jar=OUT/('c3-'+b['commit'][:12]+'.jar');shutil.copy2(source/'target/contact-aware-contacts.jar',jar)
    write(OUT/'build.json',dict(b,sourceFileHashes=hashes,jar=rel(jar),jarSha256=sha(jar),tests=counts,
        archiveSha256=sha(ROOT/b['archive']),buildLogSha256=sha(log),javaVersion=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT).decode(),
        pythonVersion=sys.version,mavenVersion=(OUT/'build-environment.log').read_text(encoding='utf-8'),
        command='mvnw -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 -DargLine=<Xmx1024m isolated tmp> -Dtest=ContinuationReuseControllerTest,ContinuationReuseGuardTest,ContinueSuffixOptimalityTest,ContinueSuffixPrepareOptimalityTest clean verify'))
    print(counts,sha(jar));preserve()

def register():
    assert not (OUT/'registration.json').exists()
    c=load(OUT/'configurations.json');b=load(OUT/'build.json');inputs=dict(c['inputs'])
    for p in (DOC/'protocol.md',DOC/'configuration-audit.json',OUT/'configurations.json',OUT/'input-review.txt',Path(__file__),ROOT/b['jar']):inputs[rel(p)]=sha(p)
    write(OUT/'registration.json',dict(build=b,cases=c['cases'],baseline=c['baseline'],inputs=inputs,
        options=dict(traversal='SERVICE_FIRST',reuse=True,timing='V2_EVENT_BOUNDARIES'),
        limits=dict(nodes=2000000,searchSeconds=30,controllerSeconds=60,watchdogSeconds=90),
        execution='Each case independent; no gates or automatic repeats; stop on correctness failure'))
    write(OUT/'summary.json',{r['name']:{**r,'status':'NOT_RUN'} for r in c['cases']});verify()
    print('Registered 40 independent attempts before first JVM')

def verify():
    preserve();m=load(OUT/'registration.json')
    for p,h in m['inputs'].items():assert sha(ROOT/p)==h,p
    return m

def run():
    m=verify();summary=load(OUT/'summary.json')
    # Resume the remaining NOT_RUN entries only; never replace an existing process/attempt.
    for spec in m['cases']:
        name=spec['name']
        if summary[name]['status']!='NOT_RUN':continue
        dest=OUT/'runs'/name;dest.mkdir(parents=True,exist_ok=False)
        print('START',name,flush=True)
        command=[JAVA,'-Xmx1024m','-Xlog:gc*,safepoint:file='+rel(dest/'gc.log')+':time,uptimemillis,level,tags',
                 '-cp',str(ROOT/m['build']['jar']),MAIN,'--model',str(OUT/spec['modelFile']),
                 '--policy',spec['policy'],'--rule',spec['rule'],'--out',str(dest)]
        process=child(command,90,dest/'process.log');write(dest/'process.json',process)
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        failure=load(dest/'failure.json') if (dest/'failure.json').exists() else None
        row={**spec,'status':(result or failure or {}).get('status',process['processStatus']),'result':result,'process':process,'failure':failure}
        if result:
            e=result['summary'];folder=dest.relative_to(OUT)
            row.update(eventsFile=(folder/e['actualEventsFile']).as_posix(),actualFile=(folder/e['actualResultFile']).as_posix(),
                decisionsFile=(folder/e['compactDecisionsFile']).as_posix(),simulationMetrics=result['simulationMetrics'],securityMetrics=result['securityMetrics'])
        summary[name]=row;write(OUT/'summary.json',summary)
        if result:
            # Existing independent accounting and proof-chain audit after each attempt, before next JVM.
            import audit_security_evaluation as audit
            import analyze_security_evaluation_se222 as provenance
            provenance.OUT=OUT
            check=dict(row,pairedDelta=None,**{k:result['summary'][k] for k in ('decisions','searchNanos','exploredNodes')})
            try:
                audited=audit.audit_case(audit.Inputs(OUT),name,audit.load(OUT/'summary.json')[name] | {k:check[k] for k in ('pairedDelta','decisions','searchNanos','exploredNodes')})
                proof=provenance.proof_audit(row)
                write(dest/'audit-status.json',dict(status='PASS',proofAudit=proof))
            except Exception as error:
                write(dest/'audit-status.json',dict(status='CORRECTNESS_REVIEW_REQUIRED',exception=repr(error)))
                raise
        elif process['processStatus']=='PROCESS_ERROR' and failure is None:
            raise ValueError('Unexpected exception: preserve reproduction and review before further runs: '+name)
        print('DONE',name,row['status'],flush=True)
    verify()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['configure','prepare','freeze','register','run','verify'])
    globals()[p.parse_args().mode]()
