#!/usr/bin/env python3
"""Twelve linked retries only, two immutable registrations and one representative."""
import argparse,json
from pathlib import Path
from run_security_evaluation_se21 import ROOT,child,sha,load,write,rel
from run_security_evaluation_se22 import JAVA
OUT=ROOT/'target/security-evaluation-se221'
DOC=ROOT/'docs/security-evaluation-se221'
OLD=ROOT/'target/security-evaluation-se22'
MAIN='org.contactaware.security.simulation.SecurityEvaluationSe221Main'

def preserve():
    hashes=load(OUT/'preserved-inputs.json')
    for p,h in hashes.items():
        if sha(ROOT/p)!=h: raise ValueError('Prior evidence changed: '+p)
    return hashes

def verify(stage):
    preserve(); m=load(OUT/f'registration-{stage}.json')
    for p,h in m['inputs'].items():
        if sha(ROOT/p)!=h: raise ValueError('Registered input changed: '+p)
    return m

def register(stage):
    preserve(); path=OUT/f'registration-{stage}.json'
    if path.exists(): raise ValueError('Registration already exists')
    build=load(OUT/f'build-{stage}.json'); old=load(OLD/'summary.json')
    status='PROCESS_ERROR' if stage=='a' else 'SEARCH_INCOMPLETE'
    cases=[]; inputs={rel(Path(__file__)):sha(__file__),rel(DOC/'protocol.md'):sha(DOC/'protocol.md'),
        build['jar']:build['jarSha256']}
    summary=load(OUT/'summary.json') if (OUT/'summary.json').exists() else {}
    for name,row in old.items():
        if row['status']!=status: continue
        copied=OUT/'models'/row['modelFile']
        copied.parent.mkdir(parents=True,exist_ok=True)
        content=(OLD/row['modelFile']).read_bytes()
        if copied.exists() and copied.read_bytes()!=content: raise ValueError('Model mismatch')
        copied.write_bytes(content); inputs[rel(copied)]=sha(copied)
        spec={k:row[k] for k in ('name','sourceCase','fixture','policy','horizon','rule','mode','attackPoint','attackAt','baselineCase')}
        spec.update(stage=stage,sourceStatus=status,sourceAttempt=rel(OLD/'runs'/name),
            sourceSummary=rel(OLD/'summary.json'),modelFile=copied.relative_to(OUT).as_posix())
        cases.append(spec)
        if name in summary: raise ValueError('Duplicate attempt identity')
        summary[name]={**spec,'status':'NOT_RUN'}
    assert len(cases)==(7 if stage=='a' else 5)
    if stage=='b':
        assert load(OUT/'observations/equivalence.json')['status']=='PASS'
        for p in (OUT/'observations').glob('*.json'): inputs[rel(p)]=sha(p)
    manifest=dict(stage=stage,build=build,cases=cases,inputs=inputs,
        limits=dict(nodes=2000000,searchSeconds=30,controllerSeconds=60,watchdogSeconds=90),
        traversal='RENEWAL_FIRST' if stage=='a' else 'SERVICE_FIRST',
        diagnostic=dict(representative='X-multi-none-continue_wait',orders=['RENEWAL_FIRST','SERVICE_FIRST'],
            nodes=2000000,searchSeconds=30,watchdogSeconds=45) if stage=='b' else None,
        preservedInputsSha256=sha(OUT/'preserved-inputs.json'))
    write(path,manifest);write(DOC/path.name,manifest);write(OUT/'summary.json',summary)
    print('Registered',stage,len(cases),build['commit'],flush=True)

def command(m,dest,mode,flags):
    dest.mkdir(parents=True,exist_ok=True)
    cmd=[JAVA,'-Xmx1024m','-Xlog:gc*,safepoint:file='+rel(dest/'gc.log')+':time,uptimemillis,level,tags',
        '-cp',str(ROOT/m['build']['jar']),MAIN,'--mode',mode,'--out',str(dest)]
    for k,v in flags.items(): cmd.extend(['--'+k,str(v)])
    return cmd

def diagnose():
    m=verify('b')
    for order in m['diagnostic']['orders']:
        dest=OUT/'diagnostic'/order
        if dest.exists(): raise ValueError('Diagnostic already attempted')
        outcome=child(command(m,dest,'search',dict(traversal=order,rule='CONTINUE_WAIT',
            checkpoint=ROOT/'target/security-evaluation-se22/runs/X-multi-none-continue_wait/checkpoint.json')),
            45,OUT/('diagnostic-'+order+'.log'))
        write(dest/'process.json',outcome)
        print(order,outcome['processStatus'],flush=True)
    verify('b')

def run(stage):
    m=verify(stage); summary=load(OUT/'summary.json')
    if stage=='b':
        for order in ('RENEWAL_FIRST','SERVICE_FIRST'):
            if not (OUT/'diagnostic'/order/'process.json').exists(): raise ValueError('Run registered diagnosis first')
    for spec in m['cases']:
        name=spec['name']
        if summary[name]['status']!='NOT_RUN': raise ValueError('Refusing repeat')
        dest=OUT/('stage-'+stage)/'runs'/name
        if dest.exists(): raise ValueError('Attempt directory exists')
        print('START',stage,name,flush=True)
        outcome=child(command(m,dest,'controller',dict(case=spec['sourceCase'],rule=spec['rule'],traversal=m['traversal'])),
            90,OUT/(name+'.log'))
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        failure=load(dest/'failure.json') if (dest/'failure.json').exists() else None
        row={**spec,'status':(result or failure or {}).get('status',outcome['processStatus']),
            'process':outcome,'result':result,'failure':failure,'checkpoint':rel(dest/'checkpoint.json') if (dest/'checkpoint.json').exists() else None}
        if result:
            export=result['summary']; folder=dest.relative_to(OUT)
            row.update(eventsFile=(folder/export['actualEventsFile']).as_posix(),actualFile=(folder/export['actualResultFile']).as_posix(),
                decisionsFile=(folder/export['compactDecisionsFile']).as_posix(),simulationMetrics=result['simulationMetrics'],securityMetrics=result['securityMetrics'])
        summary[name]=row;write(OUT/'summary.json',summary)
        print('DONE',stage,name,row['status'],flush=True)
    verify(stage)

def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register-a','register-b','run-a','run-b','diagnose','verify']);a=p.parse_args()
    if a.mode.startswith('register-'): register(a.mode[-1])
    elif a.mode.startswith('run-'):run(a.mode[-1])
    elif a.mode=='diagnose':diagnose()
    else:
        preserve()
        for s in ('a','b'):
            if (OUT/f'registration-{s}.json').exists(): verify(s)
        print('Frozen evidence PASS')
if __name__=='__main__':main()
