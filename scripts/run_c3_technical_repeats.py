#!/usr/bin/env python3
"""Exactly three separately registered timing-anomaly repeats; preserve every original attempt."""
import argparse
from run_security_evaluation_c3 import ROOT, OUT, MAIN, JAVA, verify
from run_security_evaluation_se21 import child, sha, load, write, rel
from pathlib import Path

NAMES=['C3-9-X-next-original-detected-continue_wait','C3-9-X-multi-original-detected-continue_wait',
       'C3-9-X-next-p70-detected-canonical']

def register():
    assert not (OUT/'technical-repeat-registration.json').exists()
    m=verify();primary=load(OUT/'summary.json');assert all(r['status']!='NOT_RUN' for r in primary.values())
    cases=[];inputs={rel(OUT/'summary.json'):sha(OUT/'summary.json'),rel(Path(__file__)):sha(__file__)}
    for name in NAMES:
        r=primary[name];assert r['status']=='CONTROLLER_TIME_LIMIT'
        assert r['process']['processElapsedNanos']>90*10**9
        assert r['result']['measurement']['threadCpuNanos']<5*10**9
        folder=OUT/'runs'/name
        for filename in ('result.json','process.json','process.log','gc.log','checkpoint.json'):
            p=folder/filename;inputs[rel(p)]=sha(p)
        cases.append(dict(name=name,sourceAttempt=rel(folder),modelFile=r['modelFile'],policy=r['policy'],rule=r['rule'],
            elapsedSeconds=r['process']['processElapsedNanos']/1e9,cpuSeconds=r['result']['measurement']['threadCpuNanos']/1e9))
    write(OUT/'technical-repeat-registration.json',dict(cases=cases,inputs=inputs,build=m['build'],limits=m['limits'],options=m['options'],
        reason='Elapsed90s watchdog overshoot with <5s measured controller-thread CPU and long gaps between JVM log records. Exact cause unknown; not evidence of CPU search complexity. Same model/JAR/budgets, no ordinary SEARCH_INCOMPLETE repeats.',
        selection='Use FINISHED attempt-2 for outcome comparisons when available; retain original partial row in attempts table. No further automatic repeats.'))
    write(OUT/'technical-repeat-summary.json',{n:{'status':'NOT_RUN'} for n in NAMES})
    print('Three technical repeats registered, primary summary immutable')

def run():
    verify();m=load(OUT/'technical-repeat-registration.json');summary=load(OUT/'technical-repeat-summary.json')
    assert all(r['status']=='NOT_RUN' for r in summary.values())
    for p,h in m['inputs'].items():assert sha(ROOT/p)==h,p
    primary=load(OUT/'summary.json')
    for spec in m['cases']:
        name=spec['name'];dest=OUT/'technical-repeats'/name/'attempt-2';dest.mkdir(parents=True,exist_ok=False)
        command=[JAVA,'-Xmx1024m','-Xlog:gc*,safepoint:file='+rel(dest/'gc.log')+':time,uptimemillis,level,tags',
                 '-cp',str(ROOT/m['build']['jar']),MAIN,'--model',str(OUT/spec['modelFile']),
                 '--policy',spec['policy'],'--rule',spec['rule'],'--out',str(dest)]
        print('START',name,flush=True);process=child(command,90,dest/'process.log');write(dest/'process.json',process)
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        failure=load(dest/'failure.json') if (dest/'failure.json').exists() else None
        row=dict(primary[name],status=(result or failure or {}).get('status',process['processStatus']),result=result,process=process,
                 failure=failure,attempt=2,sourceAttempt=spec['sourceAttempt'])
        if result:
            e=result['summary'];folder=dest.relative_to(OUT)
            row.update(eventsFile=(folder/e['actualEventsFile']).as_posix(),actualFile=(folder/e['actualResultFile']).as_posix(),
                decisionsFile=(folder/e['compactDecisionsFile']).as_posix(),simulationMetrics=result['simulationMetrics'],securityMetrics=result['securityMetrics'])
        else:
            for field in ('eventsFile','actualFile','decisionsFile','simulationMetrics','securityMetrics'):row.pop(field,None)
        summary[name]=row;write(OUT/'technical-repeat-summary.json',summary)
        if result:
            import audit_security_evaluation as audit
            import analyze_security_evaluation_se222 as provenance
            provenance.OUT=OUT
            actual=audit.load(OUT/'technical-repeat-summary.json')[name]
            enriched=dict(actual,pairedDelta=None,**{k:result['summary'][k] for k in ('decisions','searchNanos','exploredNodes')})
            audit.audit_case(audit.Inputs(OUT),name,enriched)
            write(dest/'audit-status.json',dict(status='PASS',proofAudit=provenance.proof_audit(row)))
        print('DONE',name,row['status'],flush=True)
    for p,h in m['inputs'].items():assert sha(ROOT/p)==h,p
    verify()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['register','run']);globals()[p.parse_args().mode]()
