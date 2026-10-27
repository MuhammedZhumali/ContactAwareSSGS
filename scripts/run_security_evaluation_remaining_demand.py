#!/usr/bin/env python3
"""SE2.5: day2 C1 offered-demand sensitivity; reuse the frozen SE2.4 executable."""
import argparse
import copy
import json
import re
import subprocess
from pathlib import Path
import audit_security_evaluation as audit
import run_security_evaluation_new_days as baseline
from run_security_evaluation_se21 import ROOT, load, write, sha, rel

OLD = ROOT/'target/security-evaluation-new-days'
OLD_DOC = ROOT/'docs/security-evaluation-new-days'
OUT = ROOT/'target/security-evaluation-remaining-demand'
DOC = ROOT/'docs/security-evaluation-remaining-demand'


def changes(a, b, path=''):
    if isinstance(a, dict) and isinstance(b, dict):
        assert a.keys() == b.keys(), path
        return sum((changes(a[k], b[k], path+'/'+k) for k in a), [])
    if isinstance(a, list) and isinstance(b, list):
        assert len(a) == len(b), path
        return sum((changes(x, y, path+'/'+str(i)) for i, (x,y) in enumerate(zip(a,b))), [])
    return [] if a == b else [dict(path=path, before=a, after=b)]


def configure():
    assert not OUT.exists(), 'Never replace prior attempts'
    baseline.verify()
    history = load(OLD/'preserved-inputs.json')
    history.update({rel(p):sha(p) for d in (OLD, OLD_DOC) for p in d.rglob('*') if p.is_file()})
    OUT.mkdir(parents=True); (OUT/'models').mkdir(); DOC.mkdir(parents=True, exist_ok=True)
    write(OUT/'preserved-inputs.json',history)
    checks=[]
    for attack in ('none','late-detected'):
        source=OLD/'day2/models'/(attack+'.json')
        original=audit.load(source); first=original['scenario']['contacts'][0]
        p=first['payload']; duration=(audit.instant(first['window']['end'])-audit.instant(first['window']['start']))*10**9
        assert duration == int(duration)
        numerator=p['frameBytes']*10**9
        assert numerator % p['bytesPerSecond'] == 0
        frame_ns=numerator//p['bytesPerSecond']; n=int(duration)//frame_ns; offered=p['frameBytes']*(n+1)
        assert (n+1)*frame_ns > duration and n*frame_ns <= duration
        # Preserve every original numeric token; do not round timestamps/durations through float.
        text=source.read_text(encoding='utf-8')
        for field in ('offeredUplinkBytes','offeredDownlinkBytes'):
            text,count=re.subn(r'("'+field+r'"\s*:\s*)\d+',lambda m:m[1]+str(offered),text,count=1)
            assert count == 1
        dest=OUT/'models'/(attack+'.json'); dest.write_text(text,encoding='utf-8')
        modified=audit.load(dest); diff=changes(original,modified)
        assert {d['path'] for d in diff} == {'/scenario/contacts/0/payload/'+f for f in ('offeredUplinkBytes','offeredDownlinkBytes')}
        checks.append(dict(attack=attack,source=rel(source),sourceSha256=sha(source),model=rel(dest),modelSha256=sha(dest),
            durationNanos=int(duration),frameDurationNanos=frame_ns,frameBytes=p['frameBytes'],floorFrames=n,B=offered,
            minimumUplinkBytes=p['minimumUplinkBytes'],minimumDownlinkBytes=p['minimumDownlinkBytes'],differences=diff))
    a,b=[audit.load(OUT/'models'/(attack+'.json')) for attack in ('none','late-detected')]
    for x in (a,b): x['scenario'].pop('name'); x['scenario'].pop('disclosures')
    assert a == b
    old_cases=[s for s in load(OLD/'registration.json')['cases'] if s['day']=='day2']
    cases=[]
    for s in old_cases:
        c=copy.deepcopy(s); c.update(name='demand-'+s['name'],historicalCase=s['name'],modelFile='models/'+s['attack']+'.json')
        cases.append(c)
    assert len(cases)==8
    write(OUT/'configurations.json',dict(cases=cases))
    write(DOC/'configuration-audit.json',dict(status='PASS',checks=checks,changedModelFields=2,
        names='Unchanged scenario names and provenance retained verbatim from LIGHT; this sidecar defines the new offered workload',
        outputPaths='New target/security-evaluation-remaining-demand only',hiddenAttackProjection='none/late differ only by existing name and disclosure input'))
    print('Configured eight cases; B =',offered,'bytes per direction; no policies run')


def bind():
    baseline.OUT=OUT; baseline.DOC=DOC


def register():
    bind(); assert not (OUT/'registration.json').exists(); baseline.preserve()
    old=load(OLD/'registration.json'); build=old['build']; cases=load(OUT/'configurations.json')['cases']
    files=[*sorted((OUT/'models').glob('*.json')),OUT/'configurations.json',DOC/'configuration-audit.json',
           DOC/'protocol.md',OUT/'input-review.txt',Path(__file__),ROOT/'scripts/run_security_evaluation_new_days.py',
           ROOT/'scripts/run_security_evaluation_se21.py',ROOT/build['jar']]
    assert not subprocess.check_output(['git','diff',build['commit'],'--','src/main/java','src/test/java'],cwd=ROOT).strip()
    write(OUT/'registration.json',dict(build=build,cases=cases,inputs={rel(p):sha(p) for p in files},
        baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        sourceIdentity='Unchanged SE2.4 JAR; new Python wrapper hash recorded above; no rebuild',
        workingTree=subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),
        historicalRegistration=rel(OLD/'registration.json'),historicalRegistrationSha256=sha(OLD/'registration.json'),
        options=old['options'],limits=old['limits'],stopDiagnostics=old['stopDiagnostics'],
        execution='Eight attempts in listed order; no retries or escalation; inherited verified watchdog/run/auditors'))
    write(OUT/'summary.json',{s['name']:{**s,'status':'NOT_RUN'} for s in cases})
    baseline.verify();print('Registered eight cases before any policy execution')


def verify():
    bind()
    return baseline.verify()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['configure','register','verify','watchdog','run'])
    mode=p.parse_args().mode
    if mode in ('configure','register','verify'): globals()[mode]()
    else:
        bind(); getattr(baseline,mode)()
