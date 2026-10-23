#!/usr/bin/env python3
"""Frozen build and five one-shot, sequential X attempts. Never replace previous attempts."""
import argparse, hashlib, json, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from run_security_evaluation_se21 import ROOT, child, sha, load, write, rel
from run_security_evaluation_se22 import JAVA

OUT = ROOT/'target/security-evaluation-se222'
DOC = ROOT/'docs/security-evaluation-se222'
MAIN = 'org.contactaware.security.simulation.SecurityEvaluationSe222Main'
ATTACKS = ['none', 'original-detected', 'p70-detected', 'p90-detected', 'gap-midpoint-detected']

def preserve():
    data = load(OUT/'preserved-inputs.json')
    for p,h in data.items():
        if sha(ROOT/p) != h: raise ValueError('Old evidence changed: '+p)
    return len(data)

def prepare():
    preserve()
    if (OUT/'build-preflight.json').exists(): raise ValueError('Build already registered')
    if subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True).strip():
        raise ValueError('Commit final sources before preparing isolated build')
    commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    base = ROOT/'target/se222-clean-build'/commit
    base.mkdir(parents=True,exist_ok=False)
    source=base/'source'; archive=base/'source.zip'
    subprocess.run(['git','archive','--format=zip',f'--output={archive}',commit],cwd=ROOT,check=True)
    with zipfile.ZipFile(archive) as z: z.extractall(source)
    data=load(ROOT/'target/security-evaluation-se22/build.json')['testDatasetHashes']
    for p,h in data.items():
        assert sha(ROOT/p)==h
        dest=source/p;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/p,dest)
    (base/'tmp').mkdir()
    write(OUT/'build-preflight.json',dict(commit=commit,sourceDirectory=str(source),archive=rel(archive),
          testDatasetHashes=data,tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),rootStatus='clean'))
    print(source)

def freeze():
    if (OUT/'build.json').exists(): raise ValueError('Build already frozen')
    b=load(OUT/'build-preflight.json');source=Path(b['sourceDirectory'])
    log=OUT/'build.log';assert 'BUILD SUCCESS' in log.read_text(encoding='utf-8')
    hashes={}
    with zipfile.ZipFile(ROOT/b['archive']) as z:
        for e in z.infolist():
            if e.is_dir():continue
            hashes[e.filename]=hashlib.sha256(z.read(e.filename)).hexdigest()
            assert sha(source/e.filename)==hashes[e.filename]
    suites=list((source/'target/surefire-reports').glob('TEST-*.xml'))
    counts={k:sum(int(ET.parse(p).getroot().attrib[k]) for p in suites) for k in ('tests','failures','errors','skipped')}
    assert counts['tests']>=397 and counts['errors']==counts['failures']==counts['skipped']==0
    jar=OUT/('se222-'+b['commit'][:12]+'.jar');shutil.copy2(source/'target/contact-aware-contacts.jar',jar)
    write(OUT/'build.json',dict(b,sourceFileHashes=hashes,jar=rel(jar),jarSha256=sha(jar),tests=counts,
        archiveSha256=sha(ROOT/b['archive']),buildLogSha256=sha(log),javaVersion=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT).decode(),
        pythonVersion=sys.version,mavenVersion=(OUT/'build-environment.log').read_text(encoding='utf-8'),
        mavenWrapperProperties=(source/'.mvn/wrapper/maven-wrapper.properties').read_text(),
        command='mvnw.cmd -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 -DargLine=<Xmx1024m isolated tmp> clean verify'))
    preserve();print(counts,sha(jar))

def register():
    if (OUT/'registration.json').exists():raise ValueError('Series already registered')
    b=load(OUT/'build.json');old=load(ROOT/'target/security-evaluation-se22/summary.json')
    cases=[];inputs={b['jar']:b['jarSha256'],rel(DOC/'protocol.md'):sha(DOC/'protocol.md'),rel(Path(__file__)):sha(__file__)}
    for point in ATTACKS:
        name='X-multi-'+point+'-continue_wait';row=old[name]
        spec={k:row[k] for k in ('name','sourceCase','fixture','policy','horizon','rule','mode','attackPoint','attackAt','baselineCase')}
        spec.update(sourceAttempt='target/security-evaluation-se221/stage-b/runs/'+name,previousStatus='CONTROLLER_TIME_LIMIT')
        source=ROOT/'target/security-evaluation-se22'/row['modelFile'];dest=OUT/'models'/source.name
        dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest);spec['modelFile']=dest.relative_to(OUT).as_posix()
        inputs[rel(dest)]=sha(dest)
        cases.append(spec)
    source=ROOT/'target/security-evaluation-se2/summary.json';inputs[rel(source)]=sha(source)
    se2=load(source)
    for row in cases:
        p=ROOT/'target/security-evaluation-se2'/se2[row['sourceCase']]['modelFile'];inputs[rel(p)]=sha(p)
    registration=dict(build=b,cases=cases,inputs=inputs,limits=dict(nodes=2000000,searchSeconds=30,controllerSeconds=60,watchdogSeconds=90),
        options=dict(traversal='SERVICE_FIRST',reuse=True,tieBreak='CONTINUE_WAIT',timing='V2_EVENT_BOUNDARIES'),
        gate='Run four detected attacks only if none is FINISHED; never increase budgets or overwrite attempts',preservedFiles=preserve())
    write(OUT/'registration.json',registration)
    write(OUT/'summary.json',{r['name']:{**r,'status':'NOT_RUN'} for r in cases})
    print('Registered five cases',b['commit'])

def verify():
    preserve();m=load(OUT/'registration.json')
    for p,h in m['inputs'].items():assert sha(ROOT/p)==h,p
    return m

def run():
    m=verify();summary=load(OUT/'summary.json')
    if any(r['status']!='NOT_RUN' for r in summary.values()):raise ValueError('Refusing repeat of registered series')
    for i,spec in enumerate(m['cases']):
        name=spec['name']
        if i and summary[m['cases'][0]['name']]['status']!='FINISHED':
            summary[name].update(status='NOT_RUN_CONTROL_INCOMPLETE',reason='Control none did not finish')
            write(OUT/'summary.json',summary);continue
        dest=OUT/'runs'/name;dest.mkdir(parents=True,exist_ok=False)
        command=[JAVA,'-Xmx1024m','-Xlog:gc*,safepoint:file='+rel(dest/'gc.log')+':time,uptimemillis,level,tags',
            '-cp',str(ROOT/m['build']['jar']),MAIN,'--case',spec['sourceCase'],'--out',str(dest)]
        print('START',name,flush=True)
        process=child(command,90,dest/'process.log');write(dest/'process.json',process)
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        failure=load(dest/'failure.json') if (dest/'failure.json').exists() else None
        row={**spec,'status':(result or failure or {}).get('status',process['processStatus']),'result':result,'process':process,'failure':failure}
        if result:
            e=result['summary'];folder=dest.relative_to(OUT)
            row.update(eventsFile=(folder/e['actualEventsFile']).as_posix(),actualFile=(folder/e['actualResultFile']).as_posix(),
                decisionsFile=(folder/e['compactDecisionsFile']).as_posix(),simulationMetrics=result['simulationMetrics'],securityMetrics=result['securityMetrics'])
        summary[name]=row;write(OUT/'summary.json',summary)
        print('DONE',name,row['status'],flush=True)
    verify()

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('mode',choices=['prepare','freeze','register','run','verify'])
    globals()[parser.parse_args().mode]()
