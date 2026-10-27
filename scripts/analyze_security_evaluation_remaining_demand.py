#!/usr/bin/env python3
"""SE2.5: journal-only accounting and day2 LIGHT historical comparison."""
from collections import Counter
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
import analyze_security_evaluation_se222 as proof
from analyze_security_evaluation_se222 import save, csv_file
from analyze_security_evaluation_new_days import FIVE, SECURITY, shown, milestone, mechanism
from run_security_evaluation_remaining_demand import ROOT, OUT, DOC, OLD, verify
from run_security_evaluation_se21 import sha

EXTRA = ('remainingUplinkAtNotice','remainingDownlinkAtNotice','confidentialC1AfterNotice',
         'independentFrameInC1','remainingC1AtPairSeconds','remainingC1AtSaSeconds')


def demand_metrics(scenario, events, recovered, status):
    values=dict.fromkeys(EXTRA)
    stages={k:'UNAVAILABLE' for k in EXTRA}
    details=dict(frames=[],groundSequence=None)
    if status != 'FINISHED': return values,stages,details
    if not scenario.get('disclosures') or any(e['type']=='DISCLOSURE_NO_TARGET' for e in events):
        return values,dict.fromkeys(EXTRA,'NOT-APPLICABLE'),details
    ground=next((e for e in events if e['type']=='REVOCATION_RECEIVED_GROUND'),None)
    if ground is None: return values,dict.fromkeys(EXTRA,'CENSORED'),details
    first=scenario['contacts'][0]; end=audit.instant(first['window']['end']); origin=audit.instant(ground['at'])
    assert audit.instant(first['window']['start']) <= origin < end
    details['groundSequence']=ground['sequence']
    contacts={c['window']['id']:c for c in scenario['contacts']}
    frames=[(e,audit.read_frame(e,contacts)) for e in events
            if e['type'] in ('PAYLOAD_TRANSFERRED','PAYLOAD_ABORTED') and e['contactId']==first['window']['id']]
    disclosed={k for e in events if e['type']=='KEY_DISCLOSED' for k in e['keyIds'].values()}
    for d in ('Uplink','Downlink'):
        delivered=sum(f['frameBytes'] for e,f in frames if e['sequence']<ground['sequence']
                      and f['delivered'] and f['direction']==d.upper())
        values['remaining'+d+'AtNotice']=first['payload']['offered'+d+'Bytes']-delivered
        assert values['remaining'+d+'AtNotice'] >= 0
    chosen=[(e,f) for e,f in frames if f['delivered'] and f['until']>origin and f['keyId'] not in disclosed]
    values['confidentialC1AfterNotice']=sum(f['frameBytes'] for e,f in chosen)
    frame=recovered['milestones']['independent_frame']
    within=frame['at'] is not None and audit.instant(frame['at']) <= end
    assert within == bool(chosen), 'Recovery milestone and independent C1 complete frames disagree'
    values['independentFrameInC1']=within
    for k in EXTRA[:3]: stages[k]='OBSERVED'
    stages['independentFrameInC1']='EVENT' if within else 'CENSORED'
    for name,m in (('remainingC1AtPairSeconds','independent_pair'),('remainingC1AtSaSeconds','independent_sa')):
        value=recovered['milestones'][m]
        stages[name]=value['status'].upper()
        if value['at'] is not None: values[name]=end-audit.instant(value['at'])
    details.update(frames=[dict(sequence=e['sequence'],**f) for e,f in chosen],
                   c1FrameAudit=[dict(sequence=e['sequence'],**f) for e,f in frames],c1CensorAt=first['window']['end'])
    return values,stages,details


def collect(base, registration, specs, label):
    summary=audit.load(base/'summary.json'); rows=[]; evidence={}; mechanisms={}; hashes={}
    proof.OUT=base
    for spec in specs:
        name=spec['name']; row=summary[name]; scenario=audit.load(base/spec['modelFile'])['scenario']
        item=dict(case=name,workload=label,historicalCase=spec.get('historicalCase',name),policy=spec['policy'],H=spec['horizon'],
            rule=spec['rule'],attack=spec['attack'],status=row['status'],sourceCommit=registration['build']['commit'],
            jarSha256=registration['build']['jarSha256'],modelFile=str((base/spec['modelFile']).relative_to(ROOT)),
            eventsFile=str((base/row['eventsFile']).relative_to(ROOT)) if row.get('eventsFile') else None,
            experimentEnd=scenario['end'],originAt=None,observationEnd=None,attackOutcome='UNAVAILABLE',
            controllerSeconds=None,controllerThreadCpuSeconds=None,processSeconds=None,searchSeconds=None,
            guardSeconds=None,searches=None,suffixReuses=None,exploredNodes=None,stopReason=None)
        five={};security={};stages={k:dict(status='unavailable',seconds=None,at=None) for k in recovery.MILESTONES}
        values,extra_status,details=demand_metrics(scenario,[],{},'NOT_RUN')
        check=dict(auditStatus='NOT_AUDITABLE')
        if row.get('process'):item['processSeconds']=D(row['process']['processElapsedNanos'])/10**9
        if row.get('eventsFile'):
            inputs=audit.Inputs(base); result=row['result']; s=result['summary']
            check=audit.audit_case(inputs,name,dict(row,pairedDelta=None,**{k:s[k] for k in ('decisions','searchNanos','exploredNodes')}))
            events,actual=inputs.read(row['eventsFile']),inputs.read(row['actualFile'])
            recovered=recovery.measure(scenario,events,actual,row['status'])
            check.update(auditStatus='PASS',recoveryObservedPrefix=recovered,proofAudit=proof.proof_audit(row))
            full=audit.load(base/Path(row['actualFile']).parent/'actual.full-trace.json')
            mech=mechanism(scenario,events,actual,full)
            # Save complete proven plans for key root/service decisions; no additional searches.
            target=audit.context(scenario['contacts'][0]['window'])
            mech['keyNominalPlans']=[dict(index=i,at=d['observation']['at'],evaluationEnd=d['observation']['evaluationEnd'],
                executedAction=d['executedAction'],search=d['search']) for i,d in enumerate(full['decisions'])
                if d.get('search') and (i==0 or (d.get('executedAction') and d['executedAction']['kind'] in ('PREPARE','INSTALL','OPEN','SWITCH')
                    and audit.context(d['executedAction']['contextId'])==target))]
            mechanisms[name]=mech
            item.update(originAt=recovered['originAt'],observationEnd=actual['at'])
            if row['status']=='FINISHED':
                five,security,stages=check['five'],check['security'],recovered['milestones']
                item['attackOutcome']='NO_ATTACK' if spec['attack']=='none' else 'DISCLOSURE_NO_TARGET' if any(e['type']=='DISCLOSURE_NO_TARGET' for e in events) else 'KEY_DISCLOSED'
            values,extra_status,details=demand_metrics(scenario,events,recovered,row['status'])
            check['demandEvidence']=details
            comp=result['computation'];cpu=result['measurement'].get('threadCpuNanos')
            item.update(controllerSeconds=D(comp['elapsedNanos'])/10**9,controllerThreadCpuSeconds=D(cpu)/10**9 if cpu is not None else None,
                searchSeconds=D(s['searchNanos'])/10**9,guardSeconds=D(comp['guardNanos'])/10**9,
                searches=comp['searches'],suffixReuses=comp['suffixReuses'],exploredNodes=s['exploredNodes'])
            failed=[d['search'] for d in full['decisions'] if d.get('search') and d['search']['status']!='COMPLETE']
            if failed: item['stopReason']=failed[-1]['diagnostics'].get('stopReason')
            check.pop('_scenario');check.pop('_decisions')
            hashes.update({str((base/p).relative_to(ROOT)).replace('\\','/'):h for p,h in inputs.hashes.items()})
        item.update({k:five.get(k) for k in FIVE});item.update({k:security.get(k) for k in SECURITY})
        for k in recovery.MILESTONES:
            item[k+'_status']=stages[k]['status'].upper();item[k+'_seconds']=stages[k]['seconds'];item[k+'_at']=stages[k].get('at')
        item.update(values);item.update({k+'_status':extra_status[k] for k in EXTRA})
        rows.append(item);evidence[name]=check
    return rows,evidence,mechanisms,hashes


def markdown(path,title,columns,rows):
    lines=['# '+title,'','| '+' | '.join(columns)+' |','| '+' | '.join('---' for c in columns)+' |']
    lines += ['| '+' | '.join(shown(v) for v in row)+' |' for row in rows]
    path.write_text('\n'.join(lines)+'\n',encoding='utf-8')


def main():
    r=verify(); rows,evidence,mechanisms,hashes=collect(OUT,r,r['cases'],'C1_REMAINING_DEMAND')
    old=audit.load(OLD/'registration.json'); specs=[s for s in old['cases'] if s['day']=='day2']
    history,old_evidence,old_mechanisms,old_hashes=collect(OLD,old,specs,'HISTORICAL_LIGHT')
    assert len(rows)==len(history)==8
    pairs=[]
    metrics=FIVE+SECURITY+tuple(k for k in EXTRA if k!='independentFrameInC1')
    for attack in ('none','late-detected'):
        for rule in ('CANONICAL','CONTINUE_WAIT'):
            a,b=[next(x for x in rows if (x['attack'],x['rule'],x['policy'])==(attack,rule,p)) for p in ('next','multi')]
            ok=a['status']==b['status']=='FINISHED'
            d=dict(attack=attack,rule=rule,status='COMPARABLE' if ok else 'NOT_COMPARABLE',nextStatus=a['status'],multiStatus=b['status'])
            for k in metrics:d['delta_'+k]=b[k]-a[k] if ok and a[k] is not None and b[k] is not None else None
            for k in recovery.MILESTONES:
                d[k+'_next']=milestone(a,k);d[k+'_multi']=milestone(b,k)
                d['delta_'+k]=b[k+'_seconds']-a[k+'_seconds'] if ok and a[k+'_seconds'] is not None and b[k+'_seconds'] is not None else None
            d.update(nextControllerSeconds=a['controllerSeconds'],multiControllerSeconds=b['controllerSeconds'])
            pairs.append(d)
    historical=[]
    for row in rows:
        oldrow=next(h for h in history if h['case']==row['historicalCase'])
        ok=row['status']==oldrow['status']=='FINISHED'
        d=dict(case=row['case'],historicalCase=oldrow['case'],status='COMPARABLE' if ok else 'NOT_COMPARABLE',
            newStatus=row['status'],historicalStatus=oldrow['status'],sameExecutable=row['jarSha256']==oldrow['jarSha256'])
        for k in metrics:
            d[k+'_light']=oldrow[k];d[k+'_new']=row[k]
            d['delta_'+k]=row[k]-oldrow[k] if ok and row[k] is not None and oldrow[k] is not None else None
        historical.append(d)
    for filename,data in [('comparison',rows),('historical-light',history),('differences',pairs),('versus-light',historical)]:
        csv_file(DOC/(filename+'.csv'),data);save(OUT/(filename+'.json'),data)
    save(OUT/'independent-audit.json',evidence);save(OUT/'mechanisms.json',mechanisms)
    save(OUT/'historical-audit.json',old_evidence);save(OUT/'historical-mechanisms.json',old_mechanisms)
    save(OUT/'analysis-input-hashes.json',dict(hashes,**old_hashes))
    validation=dict(cases=8,statuses=dict(Counter(x['status'] for x in rows)),audited=sum(e['auditStatus']=='PASS' for e in evidence.values()),
        reuseLinks=sum(e.get('proofAudit',{}).get('verifiedReuseLinks',0) for e in evidence.values()),
        historicalCases=8,historicalAudited=sum(e['auditStatus']=='PASS' for e in old_evidence.values()),
        historicalReuseLinks=sum(e.get('proofAudit',{}).get('verifiedReuseLinks',0) for e in old_evidence.values()),
        completedPairs=sum(d['status']=='COMPARABLE' for d in pairs),
        interpretation='No new LIGHT execution; independent accounting/recovery/reuse auditors applied to saved journals; final outcomes only FINISHED')
    save(DOC/'validation.json',validation)
    stopped=[]
    summary=audit.load(OUT/'summary.json')
    for row in rows:
        if row['status']!='SEARCH_INCOMPLETE': continue
        raw=summary[row['case']]
        full=audit.load(OUT/Path(raw['actualFile']).parent/'actual.full-trace.json')
        decision=full['decisions'][-1]; search=decision['search']; diag=search['diagnostics']; telemetry=diag['runtimeTelemetry']
        stopped.append(dict(case=row['case'],at=decision['observation']['at'],status=search['status'],
            stopReason=diag['stopReason'],searchNodes=search['exploredNodes'],terminalPlans=diag['terminalPlans'],
            maxDepth=diag['maxDepth'],elapsedSeconds=D(telemetry['elapsedNanos'])/10**9,
            threadCpuSeconds=D(telemetry['threadCpuNanos'])/10**9 if telemetry['threadCpuNanos'] is not None else None,
            gcMillis=telemetry['gcCollectionTimeMillisDelta'],stopCheckpoint=telemetry['stoppedAtCheckpoint'],
            generationSeconds=D(diag['generationNanos'])/10**9,executionSeconds=D(diag['executionNanos'])/10**9,
            boundSeconds=D(diag['boundNanos'])/10**9,generatedActions=diag['generatedActions'],
            generatedWaits=diag['generatedByAction']['WAIT'],executedWaits=diag['executedByAction']['WAIT'],
            preExecutionWaitSkips=diag['preExecutionWaitSkips'],
            incumbentMeaning='Unproven continuation relative to this root; not an executed controller result'))
    if stopped: csv_file(DOC/'stopped-searches.csv',stopped)
    markdown(DOC/'comparison.md','Восемь новых попыток',
        ['Политика / правило','Атака','Статус','served / useful s / CPU s / management B / keys',
         'confidential / exposed before / after B','КА / пара / SA / кадр, s','SEARCH / REUSE / узлы','Controller s'],
        [[x['policy']+' / '+x['rule'],x['attack'],x['status'],' / '.join(shown(x[k]) for k in FIVE),
          ' / '.join(shown(x[k]) for k in SECURITY[1:]),' / '.join(milestone(x,k) for k in recovery.MILESTONES),
          ' / '.join(shown(x[k]) for k in ('searches','suffixReuses','exploredNodes')),x['controllerSeconds']] for x in rows])
    markdown(DOC/'post-notice.md','Фактическая доставка C1 после ground notice',
        ['Политика / правило','Атака','Статус','Остаток UL / DL B','Confidential C1 после notice B','Кадр в C1 / статус','Остаток C1 при паре / SA s'],
        [[x['policy']+' / '+x['rule'],x['attack'],x['status'],
          ' / '.join(shown(x[k]) if x[k] is not None else x[k+'_status'] for k in EXTRA[:2]),
          shown(x['confidentialC1AfterNotice']) if x['confidentialC1AfterNotice'] is not None else x['confidentialC1AfterNotice_status'],
          (str(x['independentFrameInC1'])+' / ' if x['independentFrameInC1'] is not None else '')+x['independentFrameInC1_status'],
          ' / '.join(shown(x[k]) if x[k] is not None else x[k+'_status'] for k in EXTRA[-2:])] for x in rows])
    markdown(DOC/'differences.md','Multi − Next: четыре пары',
        ['Атака / правило','Статус','Δ served / useful / CPU / bytes / keys','Δ confidential','Δ confidential C1 после notice'],
        [[d['attack']+' / '+d['rule'],d['status'],' / '.join(shown(d['delta_'+k]) for k in FIVE),
          d['delta_confidentialDeliveredBytes'],d['delta_confidentialC1AfterNotice']] for d in pairs])
    markdown(DOC/'versus-light.md','Новый спрос относительно исторического LIGHT',
        ['Случай','Статус сравнения','Пять целей LIGHT → новые','Confidential B LIGHT → новые','Confidential C1 после notice LIGHT → новые'],
        [[d['case'],d['status'],
          ' / '.join(shown(d[k+'_light']) for k in FIVE)+' → '+' / '.join(shown(d[k+'_new']) for k in FIVE),
          shown(d['confidentialDeliveredBytes_light'])+' → '+shown(d['confidentialDeliveredBytes_new']),
          shown(d['confidentialC1AfterNotice_light'])+' → '+shown(d['confidentialC1AfterNotice_new'])] for d in historical])
    verify();print(validation)


if __name__=='__main__':main()
