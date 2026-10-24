#!/usr/bin/env python3
"""SE2.4: exactly two offline days and sixteen bounded, independent policy attempts."""
import argparse
import copy
import datetime as dt
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from run_security_evaluation_se21 import ROOT, child, load, write, sha, rel
from run_security_evaluation_se22 import JAVA
from run_security_evaluation_c3 import normalized, differences
from check_iss_transfer_selection import preserved as preserved_before_transfer

OUT = ROOT/'target/security-evaluation-new-days'
DOC = ROOT/'docs/security-evaluation-new-days'
MAIN = 'org.contactaware.security.simulation.SecurityEvaluationNewDaysMain'
OLD = ROOT/'data/runs/iss-20261010'
TESTS = 'OrekitContactGeneratorTest,ContactScheduleSelectorTest,SecurityEvaluationSe2MainTest,ContinuationReuseControllerTest,ContinuationReuseGuardTest'


def preserve():
    hashes = load(OUT/'preserved-inputs.json')
    for name, value in hashes.items():
        assert sha(ROOT/name) == value, name
    return len(hashes)


def choose(contacts):
    stations = [w['groundStationId'] for w in contacts]
    for i in range(len(stations)-2):
        if stations[i] == stations[i+2] != stations[i+1]:
            return i, 3, 'ABA'
    for i in range(len(stations)-3):
        if stations[i] == stations[i+3] and stations[i] not in stations[i+1:i+3]:
            return i, 4, 'FOUR_CONTACT_RETURN'
    if len(stations) < 3:
        raise ValueError('Insufficient contacts; no additional days may be generated')
    return 0, 3, 'CONTROL_NO_REQUIRED_RETURN'


def init():
    assert not OUT.exists(), 'Refusing to replace prior evidence'
    # Check all selection branches, including priority of a later triple over an earlier four-window return.
    fixture = lambda names: [dict(groundStationId=n) for n in names]
    assert choose(fixture('ABAC')) == (0, 3, 'ABA')
    assert choose(fixture('ABCA')) == (0, 4, 'FOUR_CONTACT_RETURN')
    assert choose(fixture('ABCDE')) == (0, 3, 'CONTROL_NO_REQUIRED_RETURN')
    assert choose(fixture('ABCADEDF')) == (4, 3, 'ABA')
    history = preserved_before_transfer()
    for base in (ROOT/'docs/security-evaluation-iss-transfer', ROOT/'target/security-evaluation-iss-transfer'):
        history.update({rel(p): sha(p) for p in base.rglob('*') if p.is_file()})
    schedule, geometry = load(OLD/'schedule/schedule.json'), load(OLD/'orekit/dataset.json')
    assert schedule['interval'] == geometry['interval'] and len(geometry['stations']) == 32
    end = dt.datetime.fromisoformat(schedule['interval']['end'].replace('Z','+00:00'))
    fmt = lambda t: t.isoformat().replace('+00:00','Z')
    days = [dict(id='day'+str(i+1), start=fmt(end+dt.timedelta(days=i)), end=fmt(end+dt.timedelta(days=i+1))) for i in range(2)]
    sources = {}
    for source in geometry['sources']:
        path = OLD/'orekit'/source['file']
        assert sha(path) == source['sha256'], path
        sources[rel(path)] = source['sha256']
    # CLI station path retains the original saved SatNOGS dataset provenance, without fetching it.
    for path in (OLD/'satnogs/stations.json', OLD/'satnogs/dataset.json', OLD/'schedule/schedule.json', OLD/'orekit/dataset.json'):
        sources[rel(path)] = sha(path)
    assert sha(OLD/'satnogs/stations.json') == sha(OLD/'orekit/raw/stations.json')
    OUT.mkdir(parents=True); DOC.mkdir(parents=True, exist_ok=True)
    write(OUT/'preserved-inputs.json', history)
    write(OUT/'generation-registration.json', dict(days=days, sources=sources, oldInterval=schedule['interval'],
        settings=geometry['parameters']['settings'], orbitEpochUtc=geometry['parameters']['orbitEpochUtc'],
        norad=geometry['noradId'], selector=schedule['configuration'], selectorAlgorithm=schedule['selectionAlgorithm'],
        selectionRule='first consecutive ABA; else first four-window return with no interior A; else first three control',
        selectionBranchChecks='PASS: four fixed cases', network='OFFLINE; no fetch commands',
        baselineCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()))
    print(days, 'preserved files', preserve())


def prepare():
    preserve(); assert not (OUT/'build-preflight.json').exists()
    assert not subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True).strip()
    commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    base = ROOT/'target/new-days-clean-build'/commit; base.mkdir(parents=True, exist_ok=False)
    source, archive = base/'source', base/'source.zip'
    subprocess.run(['git','archive','--format=zip',f'--output={archive}',commit],cwd=ROOT,check=True)
    with zipfile.ZipFile(archive) as z: z.extractall(source)
    (base/'tmp').mkdir()
    test_data = {}
    for directory in ('earth-20261011','celestrak-iss-20261011'):
        src = ROOT/'data/sources'/directory; dst = source/'data/sources'/directory
        shutil.copytree(src,dst)
        for p in src.iterdir():
            if p.is_file(): test_data[rel(p)] = sha(p); assert sha(dst/p.name) == sha(p)
    write(OUT/'build-preflight.json',dict(commit=commit,sourceDirectory=str(source),archive=rel(archive),
        tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),rootStatus='clean',testData=test_data))
    print(source)


def freeze():
    assert not (OUT/'build.json').exists()
    b = load(OUT/'build-preflight.json'); source = Path(b['sourceDirectory'])
    assert 'BUILD SUCCESS' in (OUT/'build.log').read_text(encoding='utf-8')
    hashes = {}
    with zipfile.ZipFile(ROOT/b['archive']) as z:
        for e in z.infolist():
            if not e.is_dir():
                hashes[e.filename] = hashlib.sha256(z.read(e.filename)).hexdigest()
                assert sha(source/e.filename) == hashes[e.filename]
    reports = list((source/'target/surefire-reports').glob('TEST-*.xml'))
    assert len(reports) == 5
    counts = {k:sum(int(ET.parse(p).getroot().attrib[k]) for p in reports) for k in ('tests','failures','errors','skipped')}
    assert counts['tests'] > 0 and counts['failures'] == counts['errors'] == counts['skipped'] == 0
    jar = OUT/('new-days-'+b['commit'][:12]+'.jar'); shutil.copy2(source/'target/contact-aware-contacts.jar',jar)
    write(OUT/'build.json',dict(b,sourceFileHashes=hashes,jar=rel(jar),jarSha256=sha(jar),tests=counts,
        archiveSha256=sha(ROOT/b['archive']),buildLogSha256=sha(OUT/'build.log'),
        javaVersion=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT).decode(),pythonVersion=sys.version,
        mavenVersion=(OUT/'build-environment.log').read_text(encoding='utf-8'),
        command='mvnw -o -B -ntp -Dmaven.repo.local=<root>/.tools/m2 -DargLine=<Xmx1024m isolated tmp> -Dtest='+TESTS+' clean verify'))
    print(counts, sha(jar)); preserve()


def java_step(args, log):
    outcome = child([JAVA,'-Xmx1024m',*args],90,log)
    write(log.with_suffix('.process.json'),outcome)
    assert outcome['processStatus'] == 'EXITED' and outcome['processElapsedNanos'] <= 95*10**9, outcome


def generate():
    preserve(); g = load(OUT/'generation-registration.json'); b = load(OUT/'build.json')
    assert not (OUT/'fragments.json').exists()
    for name, value in g['sources'].items(): assert sha(ROOT/name) == value, name
    jar = str(ROOT/b['jar']); fragments=[]
    for day in g['days']:
        dest = OUT/day['id']; dest.mkdir(exist_ok=False)
        settings = g['settings']
        args = ['-jar',jar,'orekit','--norad',str(g['norad']),'--start',day['start'],'--end',day['end'],
            '--orbit',str(OLD/'orekit/raw/orbit/gp.json'),'--stations',str(OLD/'satnogs/stations.json'),
            '--orekit-data',str(OLD/'orekit/raw/earth'),'--min-elevation-deg',str(settings['minElevationDeg']),
            '--max-check-seconds',str(settings['maxCheckSeconds']),'--root-tolerance-seconds',str(settings['rootToleranceSeconds']),
            '--max-epoch-distance-hours',str(settings['maxEpochDistanceHours']),'--out',str(dest/'orekit')]
        write(dest/'geometry-command.json',args); java_step(args,dest/'geometry.log')
        args = ['-jar',jar,'schedule-select','--dataset',str(dest/'orekit/dataset.json'),
                '--switch-guard-seconds',str(g['selector']['switchGuardSeconds']),'--out',str(dest/'schedule')]
        write(dest/'selection-command.json',args); java_step(args,dest/'selection.log')
        ds, schedule = load(dest/'orekit/dataset.json'), load(dest/'schedule/schedule.json')
        original = load(OLD/'orekit/dataset.json')
        assert ds['parameters']['settings'] == original['parameters']['settings'] and ds['stations'] == original['stations']
        assert schedule['configuration'] == g['selector'] and schedule['selectionAlgorithm'] == g['selectorAlgorithm']
        assert ds['interval'] == schedule['interval'] == {k:day[k] for k in ('start','end')}
        from_index, count, kind = choose(schedule['contacts'])
        windows = schedule['contacts'][from_index:from_index+count]
        fragment = dict(day=day['id'],kind=kind,fromIndex=from_index,count=count,indices=list(range(from_index,from_index+count)),
            scheduleFile=rel(dest/'schedule/schedule.json'),scheduleSha256=sha(dest/'schedule/schedule.json'),
            candidateContacts=len(ds['contacts']),selectedContacts=len(schedule['contacts']),windows=windows,multiH=count-1,
            geometryParameters=ds['parameters'])
        write(dest/'fragment.json',fragment); fragments.append(fragment)
        print(day['id'],len(ds['contacts']),len(schedule['contacts']),kind,fragment['indices'],flush=True)
    write(OUT/'fragments.json',fragments)


def configure():
    preserve(); assert not (OUT/'configurations.json').exists()
    build = load(OUT/'build.json'); cases=[]; checks=[]; hashes={}
    ref = normalized(load(ROOT/'target/security-evaluation-se2/iss3-cp5400-none.model.json')['scenario'])
    for fragment in load(OUT/'fragments.json'):
        dest = OUT/fragment['day']; models=dest/'models'
        java_step(['-cp',str(ROOT/build['jar']),MAIN,'--mode','configure','--schedule',str(ROOT/fragment['scheduleFile']),
                   '--from',str(fragment['fromIndex']),'--count',str(fragment['count']),'--out',str(models)],dest/'configuration.log')
        control = normalized(load(models/'none.json')['scenario']); attacked = normalized(load(models/'late-detected.json')['scenario'])
        a,c = copy.deepcopy(control),copy.deepcopy(attacked)
        a.pop('name');c.pop('name');a.pop('disclosures');c.pop('disclosures');assert a == c
        assert control['costs'] == ref['costs']
        for key in ('cryptoperiod','maxMaterialAge','maxReuseGap','blockedKeyIds'): assert control['constraints'][key] == ref['constraints'][key]
        assert all(x['payload'] == ref['contacts'][0]['payload'] for x in control['contacts'])
        assert [x['window'] for x in control['contacts']] == fragment['windows']
        assert not control['initialState']['keys'] and not control['plan']
        from audit_security_evaluation import instant
        assert instant(control['start']) == instant(fragment['windows'][0]['start'])-2
        assert control['end'] == fragment['windows'][-1]['end']
        incident = attacked['disclosures'][0]; first=fragment['windows'][0]
        nanos=int((instant(first['end'])-instant(first['start']))*10**9)
        assert instant(incident['at']) == instant(first['start']) + __import__('decimal').Decimal(nanos*90//100)/10**9
        assert instant(incident['detectionAt'])-instant(incident['at']) == 2
        checks.append(dict(day=fragment['day'],status='PASS',controlAttackChangedPaths=differences(control,attacked),
            unchangedPayload=control['contacts'][0]['payload'],initialKeys=[],initialization=rel(models/'initialization.json'),
            comparison='Costs, age/reuse constraints and finite payload equal to existing ISS CP5400 LIGHT; original windows retained'))
        for attack in ('none','late-detected'):
            for rule in ('CANONICAL','CONTINUE_WAIT'):
                for policy in ('next','multi'):
                    name=f"{fragment['day']}-{policy}-{attack}-{rule.lower()}"
                    cases.append(dict(name=name,day=fragment['day'],fixture=fragment['day'],attack=attack,
                        mode='none' if attack=='none' else 'detected',rule=rule,policy=policy,
                        horizon=1 if policy=='next' else fragment['multiH'],fragmentKind=fragment['kind'],
                        modelFile=(models/(attack+'.json')).relative_to(OUT).as_posix(),
                        experimentEnd=control['end'],censorAt=control['end']))
    assert len(cases)==16
    for p in OUT.rglob('*'):
        if p.is_file() and p.suffix == '.json' and any(k in p.parts for k in ('day1','day2')): hashes[rel(p)]=sha(p)
    write(OUT/'configurations.json',dict(cases=cases,inputs=hashes))
    write(DOC/'configuration-audit.json',dict(status='PASS',checks=checks,
        withinAttack='All four policy/rule combinations reference the exact same model file and initial state'))
    print('16 configurations frozen; no policies executed')


def register():
    assert not (OUT/'registration.json').exists(); preserve()
    c,b=load(OUT/'configurations.json'),load(OUT/'build.json'); hashes=dict(c['inputs'])
    for p in (DOC/'protocol.md',DOC/'configuration-audit.json',OUT/'input-review.txt',OUT/'generation-registration.json',
              OUT/'fragments.json',OUT/'configurations.json',Path(__file__),ROOT/b['jar']): hashes[rel(p)] = sha(p)
    write(OUT/'registration.json',dict(build=b,cases=c['cases'],inputs=hashes,
        options=dict(traversal='SERVICE_FIRST',reuse=True,timing='V2_EVENT_BOUNDARIES'),
        limits=dict(nodes=2000000,searchSeconds=30,controllerSeconds=60,watchdogSeconds=90),
        stopDiagnostics=dict(processOvershootSeconds=5,controllerOvershootSeconds=5,
                             meaning='Diagnostic tolerance for stop/serialization, not extra solver budget; stop series on excess'),
        execution='Each registered combination independent; no retries or budget escalation; stop on correctness failure or new stopping anomaly'))
    write(OUT/'summary.json',{s['name']:{**s,'status':'NOT_RUN'} for s in c['cases']})
    verify(); print('Registered 16 cases before first policy run')


def verify():
    preserve(); m=load(OUT/'registration.json')
    for name,h in m['inputs'].items(): assert sha(ROOT/name)==h,name
    return m


def watchdog():
    verify(); assert not (OUT/'watchdog.json').exists()
    # Event.wait() never completes by itself, has no output and does not consume a CPU core.
    command=[sys.executable,'-c','import threading; threading.Event().wait()']
    outcome=child(command,2,OUT/'watchdog.log')
    passed=outcome['processStatus']=='PROCESS_TIMEOUT' and outcome['exitCode'] is not None and (OUT/'watchdog.log').stat().st_size==0 and outcome['processElapsedNanos'] < 7*10**9
    write(OUT/'watchdog.json',dict(outcome,status='PASS' if passed else 'FAIL',silentBytes=(OUT/'watchdog.log').stat().st_size,
        childReaped=outcome['exitCode'] is not None,command=command,
        interpretation='Bounded silent-child kill/reap check only; does not identify causes of prior elapsed anomalies'))
    if not passed: write(OUT/'series-stop.json',dict(reason='WATCHDOG_PROBE_FAILED')); raise RuntimeError('Watchdog probe failed; no series')
    print('PASS: silent never-finishing child killed and reaped',outcome)


def run():
    m=verify(); assert load(OUT/'watchdog.json')['status']=='PASS'
    assert not (OUT/'series-started.json').exists(), 'No automatic retries or resuming a stopped series'
    write(OUT/'series-started.json',dict(registrationSha256=sha(OUT/'registration.json')))
    summary=load(OUT/'summary.json')
    for spec in m['cases']:
        name=spec['name'];dest=OUT/'runs'/name;dest.mkdir(parents=True,exist_ok=False)
        print('START',name,flush=True)
        command=[JAVA,'-Xmx1024m','-Xlog:gc*,safepoint:file='+rel(dest/'gc.log')+':time,uptimemillis,level,tags',
            '-cp',str(ROOT/m['build']['jar']),MAIN,'--mode','run','--model',str(OUT/spec['modelFile']),
            '--policy',spec['policy'],'--rule',spec['rule'],'--out',str(dest)]
        process=child(command,90,dest/'process.log');write(dest/'process.json',process)
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        failure=load(dest/'failure.json') if (dest/'failure.json').exists() else None
        status=(result or failure or {}).get('status',process['processStatus'])
        row=dict(spec,status=status,result=result,process=process,failure=failure)
        if result:
            e=result['summary'];folder=dest.relative_to(OUT)
            row.update(eventsFile=(folder/e['actualEventsFile']).as_posix(),actualFile=(folder/e['actualResultFile']).as_posix(),
                decisionsFile=(folder/e['compactDecisionsFile']).as_posix(),simulationMetrics=result['simulationMetrics'],securityMetrics=result['securityMetrics'])
        summary[name]=row;write(OUT/'summary.json',summary)
        try:
            if result:
                import audit_security_evaluation as audit
                import analyze_security_evaluation_se222 as proof
                import recovery_metrics_se21 as recovery
                proof.OUT=OUT; actual=audit.load(OUT/'summary.json')[name];inputs=audit.Inputs(OUT)
                enriched=dict(actual,pairedDelta=None,**{k:result['summary'][k] for k in ('decisions','searchNanos','exploredNodes')})
                checked=audit.audit_case(inputs,name,enriched)
                recovery.measure(checked['_scenario'],inputs.read(row['eventsFile']),inputs.read(row['actualFile']),status)
                write(dest/'audit-status.json',dict(status='PASS',proofAudit=proof.proof_audit(actual)))
            assert process['processElapsedNanos'] <= 95*10**9, 'New external stop overshoot'
            if result: assert result['computation']['elapsedNanos'] <= 65*10**9, 'New controller stop overshoot'
            assert process['processStatus'] != 'PROCESS_ERROR' and failure is None, 'Unexpected process/checkpoint failure'
        except Exception as error:
            write(OUT/'series-stop.json',dict(case=name,reason=repr(error),remaining='NOT_RUN; no replacement experiment'))
            raise
        print('DONE',name,status,flush=True)
    verify()


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['init','prepare','freeze','generate','configure','register','verify','watchdog','run'])
    globals()[p.parse_args().mode]()
