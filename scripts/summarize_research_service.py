#!/usr/bin/env python3
"""Postprocess exactly the saved SE2.3/SE2.4/SE2.5 selection; never run Java."""
import argparse
import copy
import csv
import json
import subprocess
from collections import Counter
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
from confidential_service_metrics import measure
from analyze_security_evaluation_se222 import save, csv_file
from analyze_security_evaluation_c3 import c3_entry
from run_security_evaluation_c3 import normalized
from run_security_evaluation_se21 import ROOT, load, sha, rel

OUT=ROOT/'target/research-synthesis'
DOC=ROOT/'docs/research-synthesis'
FIVE=('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')


def csv_rows(path):
    with path.open(encoding='utf-8',newline='') as f:return list(csv.DictReader(f))


def selection():
    c3=load(ROOT/'target/security-evaluation-c3/registration.json')
    baseline={x['name']:x for x in c3['baseline']}; rows=[]
    for r in csv_rows(ROOT/'docs/security-evaluation-c3/comparison.csv'):
        s=baseline.get(r['case'])
        rows.append(dict(case=r['case'],family='X',duration=int(r['duration']),day='',stage='SE2.3 table',
            originStage=r['version'],sourceCommit=r['commit'],source=r['source'],sourceCase=s['legacyName'] if s else r['case'],
            selected=True,attempt=r['selectedAttempt'],historical=s is not None,policy=r['policy'],rule=r['rule'],attack=r['attack'],
            expectedStatus=r['status'],expectedEvents=r['events']))
    for folder,stage in [('security-evaluation-new-days','SE2.4'),('security-evaluation-remaining-demand','SE2.5')]:
        for r in csv_rows(ROOT/'docs'/folder/'comparison.csv'):
            rows.append(dict(case=r['case'],family='ISS',duration=None,day=r.get('day','day2'),stage=stage,
                originStage=stage,sourceCommit=r.get('commit') or r['sourceCommit'],source='target/'+folder+'/summary.json',
                sourceCase=r['case'],selected=True,attempt='1',historical=False,policy=r['policy'],rule=r['rule'],attack=r['attack'],
                expectedStatus=r['status'],expectedEvents=r['eventsFile'] if stage=='SE2.5' else 'target/'+folder+'/'+r['eventsFile']))
    assert len(rows)==84
    extra=[]
    for r in csv_rows(ROOT/'docs/security-evaluation-c3/attempts.csv'):
        if r['selected']=='False':
            s=next(x for x in rows if x['case']==r['case'])
            extra.append(dict(s,case=s['case']+':original-attempt-1',selected=False,attempt='1',
                source='target/security-evaluation-c3/summary.json',sourceCase=s['case'],
                expectedStatus=r['status'],expectedEvents='target/security-evaluation-c3/'+r['events']))
    assert len(extra)==3
    return rows+extra


def freeze():
    assert not (OUT/'registration.json').exists(), 'Do not replace the saved selection'
    OUT.mkdir(parents=True,exist_ok=True);DOC.mkdir(parents=True,exist_ok=True)
    old=ROOT/'target/security-evaluation-remaining-demand'
    preserved=load(old/'preserved-inputs.json')
    for directory in (old,ROOT/'docs/security-evaluation-remaining-demand',ROOT/'docs/reviews'):
        preserved.update({rel(p):sha(p) for p in directory.rglob('*') if p.is_file()})
    protected={rel(p):sha(p) for base in ('src/main','src/test') for p in (ROOT/base).rglob('*') if p.is_file()}
    save(OUT/'preserved-inputs.json',preserved)
    save(OUT/'protected-sources.json',protected)
    specs=selection(); sources={}
    for s in specs:
        path=ROOT/s['source']; row=load(path)[s['sourceCase']]
        assert row['status']==s['expectedStatus']
        for p in [path,*[path.parent/row[k] for k in ('modelFile','eventsFile','actualFile','decisionsFile')]]:sources[rel(p)]=sha(p)
        assert rel(path.parent/row['eventsFile'])==s['expectedEvents'].replace('\\','/'),s['case']
    for directory in ('security-evaluation-c3','security-evaluation-new-days','security-evaluation-remaining-demand'):
        p=ROOT/'docs'/directory/'comparison.csv';sources[rel(p)]=sha(p)
    save(OUT/'registration.json',dict(baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        mode='POSTPROCESS_ONLY',selection=specs,inputs=sources,
        scope='84 selected historical rows plus 3 unselected technical original attempts, never new experiments',
        objective='Existing five criteria unchanged; retrospective confidential service is reporting only'))
    verify();print('Frozen 84 selected rows + 3 original technical attempts; no simulations')


def verify():
    r=load(OUT/'registration.json')
    for filename in ('preserved-inputs.json','protected-sources.json'):
        for path,digest in load(OUT/filename).items(): assert sha(ROOT/path)==digest,path
    for path,digest in r['inputs'].items():assert sha(ROOT/path)==digest,path
    return r


def main():
    registration=verify();rows=[];extra=[];contacts=[];evidence={};mechanisms={};scenarios={}
    for spec in registration['selection']:
        base=(ROOT/spec['source']).parent;raw=audit.load(ROOT/spec['source'])[spec['sourceCase']]
        inputs=audit.Inputs(base);summary=raw['result']['summary']
        checked=audit.audit_case(inputs,spec['case'],dict(raw,pairedDelta=None,**{k:summary[k] for k in ('decisions','searchNanos','exploredNodes')}))
        scenario=checked.pop('_scenario');checked.pop('_decisions')
        events=inputs.read(raw['eventsFile']);actual=inputs.read(raw['actualFile'])
        if spec['family']=='X':
            assert scenario['plan']==[] and all(k['status']=='ACTIVE' for k in scenario['initialState']['keys'].values()), 'Not autonomous X'
            assert not scenario['initialState'].get('preparedPairs'), 'Pre-prepared candidate must not enter this sample'
        secret=measure(scenario,events,raw['status'],actual['at'])
        prefix=secret['observedPrefix']['totals']
        assert prefix['servedContacts']==checked['five']['servedContacts']
        if raw['status']=='FINISHED':
            assert prefix['confidentialDeliveredBytes']==checked['security']['confidentialDeliveredBytes']
        recovered=recovery.measure(scenario,events,actual,raw['status'])
        finished=raw['status']=='FINISHED'
        item=dict(spec,status=raw['status'],modelFile=rel(base/raw['modelFile']),eventsFile=rel(base/raw['eventsFile']),
            actualFile=rel(base/raw['actualFile']),inputSha256=sha(base/raw['modelFile']),journalSha256=sha(base/raw['eventsFile']),
            observedEnd=actual['at'],experimentEnd=scenario['end'],originAt=recovered['originAt'],
            confidentialServiceStatus=secret['status'],confidentialServedContacts=secret['final']['confidentialServedContacts'] if finished else None,
            confidentialDeliveredBytes=prefix['confidentialDeliveredBytes'] if finished else None)
        for k in FIVE:item[k]=checked['five'][k] if finished else None
        for k in recovery.MILESTONES:
            m=recovered['milestones'][k] if finished else dict(status='unavailable',seconds=None,at=None)
            item[k+'_status']=m['status'].upper();item[k+'_seconds']=m['seconds'];item[k+'_at']=m.get('at')
        item['controllerSeconds']=D(raw['result'].get('computation',{}).get('elapsedNanos',summary.get('controllerWallNanos',0)))/10**9
        item['exploredNodes']=summary['exploredNodes']
        for c in secret['observedPrefix']['contacts']:
            # Final columns stay null for all incomplete runs; prefix facts live only in separate JSON.
            contacts.append(dict(case=spec['case'],stage=spec['stage'],selected=spec['selected'],status=raw['status'],
                contactId=c['contactId'],minimumUplinkBytes=c['minimumUplinkBytes'],minimumDownlinkBytes=c['minimumDownlinkBytes'],
                **{k:c[k] if finished else None for k in ('uplinkBytes','downlinkBytes','confidentialUplinkBytes','confidentialDownlinkBytes','served','confidentialServed')}))
        check=dict(accounting='PASS',confidentialService=secret,recovery=recovered)
        evidence[spec['case']]=check;scenarios[spec['case']]=scenario
        mechanisms[spec['case']]=dict(returnEntry=c3_entry(scenario,events,actual),
            actualOperations=[e for e in events if e['type'] in ('STEP_STARTED','STEP_COMPLETED','STEP_FAILED','STEP_INTERRUPTED','KEY_EXPIRED',
                'KEY_DISCLOSED','REVOCATION_RECEIVED_GROUND','REVOCATION_RECEIVED_SPACECRAFT')],
            completeFrames=[e for e in events if e['type']=='PAYLOAD_TRANSFERRED'])
        (rows if spec['selected'] else extra).append(item)
    pairs=[]
    for a in rows:
        if a['policy']!='next':continue
        b=next(b for b in rows if b['policy']=='multi' and all(b[k]==a[k] for k in ('stage','family','duration','day','rule','attack')))
        aa,bb=[normalized(copy.deepcopy(scenarios[x['case']])) for x in (a,b)]
        aa.pop('name');bb.pop('name');same=aa==bb
        assert same,'Inputs differ beyond name/horizon: '+a['case']
        valid=same and a['status']==b['status']=='FINISHED'
        pair=dict(stage=a['stage'],family=a['family'],duration=a['duration'],day=a['day'],attack=a['attack'],rule=a['rule'],
            nextCase=a['case'],multiCase=b['case'],status='COMPARABLE' if valid else 'NOT_COMPARABLE',
            nextStatus=a['status'],multiStatus=b['status'],sameScenario=True,
            nextOriginStage=a['originStage'],multiOriginStage=b['originStage'],sameCommit=a['sourceCommit']==b['sourceCommit'],
            versionCaveat='Mixed historical binaries; physical model equal, not a solver-runtime benchmark' if a['sourceCommit']!=b['sourceCommit'] else '')
        for k in FIVE+('confidentialServedContacts','confidentialDeliveredBytes'):
            pair[k+'_next']=a[k];pair[k+'_multi']=b[k];pair['delta_'+k]=b[k]-a[k] if valid else None
        for k in recovery.MILESTONES:
            pair[k+'_next_status']=a[k+'_status'];pair[k+'_multi_status']=b[k+'_status']
            pair[k+'_next_seconds']=a[k+'_seconds'];pair[k+'_multi_seconds']=b[k+'_seconds']
        pairs.append(pair)
    assert len(rows)==84 and len(extra)==3 and len(pairs)==42
    assert len({r['eventsFile'] for r in rows+extra})==87,'Duplicated physical attempts'
    for name,value in [('runs',rows),('extra-attempts',extra),('contacts',contacts),('comparisons',pairs)]:
        csv_file(DOC/(name+'.csv'),value);save(OUT/(name+'.json'),value)
    save(OUT/'journal-evidence.json',evidence);save(OUT/'mechanisms.json',mechanisms)
    finished=[r for r in rows if r['status']=='FINISHED']
    validation=dict(selectedRows=len(rows),selectedStatuses=dict(Counter(r['status'] for r in rows)),
        reusedHistoricalRows=sum(r['historical'] for r in rows),additionalOriginalAttempts=len(extra),
        additionalStatuses=dict(Counter(r['status'] for r in extra)),auditedJournals=len(evidence),
        comparablePairs=sum(p['status']=='COMPARABLE' for p in pairs),allPairs=len(pairs),
        finishedRowsWithDifferentServiceCounts=sum(r['servedContacts']!=r['confidentialServedContacts'] for r in finished),
        finishedNoDisclosureEquality='PASS',ordinaryServiceMatchesSavedMetrics='PASS',
        confidentialBytesMatchExistingThreatModel='PASS',sameScenarioInAllPairs='PASS',
        newSimulations=0,newMavenRuns=0,preservedFiles=len(load(OUT/'preserved-inputs.json')),
        historicalCounts='20 C3=5 rows reused once; 339/375 belongs to separate old SE2 audit, not this selection')
    save(DOC/'validation.json',validation);verify();print(json.dumps(validation,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['freeze','analyze','verify'])
    mode=p.parse_args().mode
    {'freeze':freeze,'analyze':main,'verify':verify}[mode]()
