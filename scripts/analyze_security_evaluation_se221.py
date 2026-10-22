#!/usr/bin/env python3
"""Audit the twelve new attempts and combine them with explicitly labeled old evidence."""
import csv,json
from collections import Counter
from decimal import Decimal
from pathlib import Path
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
from run_security_evaluation_se221 import ROOT,OUT,DOC,OLD,verify

def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,indent=2,default=str)+'\n',encoding='utf-8')
def table(path,rows):
    audit.write_csv(path,rows,list(dict.fromkeys(k for r in rows for k in r)))
def n(value):
    if value is None:return '—'
    if isinstance(value,(Decimal,float)):return format(Decimal(str(value)).quantize(Decimal('0.001')),'f').rstrip('0').rstrip('.')
    return str(value)

def inspect(name,row,inputs,version):
    base={k:row[k] for k in ('name','fixture','policy','horizon','rule','mode','attackPoint')}
    base.update(version=version,status=row['status'],qualityEligible=row['status']=='FINISHED')
    if not row.get('result'):return base,None,None
    export=row['result']['summary']
    row=dict(row,decisions=export['decisions'],searchNanos=export['searchNanos'],exploredNodes=export['exploredNodes'],pairedDelta=None)
    checked=audit.audit_case(inputs,name,row)
    actual=inputs.read(row['actualFile']);events=inputs.read(row['eventsFile']);scenario=inputs.read(row['modelFile'])['scenario']
    recovered=recovery.measure(scenario,events,actual,row['status'])
    ds=inputs.read(row['decisionsFile'])
    ts=[(d.get('searchDiagnostics')or{}).get('runtimeTelemetry') for d in ds if d.get('searchStatus')]
    cpus=[t['threadCpuNanos'] for t in ts if t and t.get('threadCpuNanos') is not None]
    base.update(decisions=row['decisions'],nodes=row['exploredNodes'],searchSeconds=Decimal(row['searchNanos'])/10**9,
        searchCpuSeconds=Decimal(sum(cpus))/10**9 if len(cpus)==len(ts) else None,
        stopReasons=json.dumps(dict(Counter(d.get('searchStopReason') for d in ds if d.get('searchStopReason') not in (None,'NONE')))),
        observedUntil=actual['at'])
    if row['status']=='FINISHED':
        base.update(checked['five'])
        for k in ('deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes'):
            base[k]=checked['security'][k]
    for k,v in recovered['milestones'].items():
        base[k+'_seconds']=v['seconds'];base[k+'_status']=v['status']
    return base,recovered,checked

def main():
    ma,mb=verify('a'),verify('b');summary=audit.load(OUT/'summary.json');old=audit.load(OLD/'summary.json')
    assert len(summary)==12 and all(r['status']!='NOT_RUN' for r in summary.values())
    oldinputs=audit.Inputs(OLD);inputs=audit.Inputs(OUT)
    original=audit.load(ROOT/'target/security-evaluation-se2/summary.json')
    rows=[];combined=[];checks=[];checkpoint_checks=[];writes=[];keyrows=[];phases=[];milestones=[];intervals=[];conditions=[];audits={};progress=[]
    for name,row in old.items():
        selected=summary.get(name,row);new=name in summary
        base,recovered,checked=inspect(name,selected,inputs if new else oldinputs,
            'SE2.2.1-'+selected['stage'] if new else 'SE2.2')
        base['sourceAttempt']='../security-evaluation-se22/'+name if not new else selected['sourceAttempt']
        combined.append(base)
        if not new:continue
        base.update(sourceStatus=row['status'],checkpoint=selected.get('checkpoint'),stage=selected['stage'])
        rows.append(base)
        dest=OUT/('stage-'+selected['stage'])/'runs'/name
        trace=dest/'checkpoint-writes.jsonl'
        entries=[json.loads(s) for s in trace.read_text(encoding='utf-8').splitlines()] if trace.exists() else []
        for e in entries:writes.append(dict(case=name,**e['diagnostics'],at=e['at'],completedDecisions=e['completedDecisions']))
        base.update(checkpointWrites=len(entries),checkpointRetries=sum(e['diagnostics']['retries'] for e in entries),
            checkpointWriteSeconds=Decimal(sum(e['diagnostics']['elapsedNanos'] for e in entries))/10**9,
            checkpointBackoffSeconds=Decimal(sum(e['diagnostics']['backoffNanos'] for e in entries))/10**9)
        if checked is None:continue
        saved=audit.load(ROOT/selected['checkpoint'])['snapshot']
        actual=inputs.read(selected['actualFile']);events=inputs.read(selected['eventsFile'])
        assert saved['at']==actual['at'] and saved['securityState']==actual['securityState'] and saved['journal']==events,name+' checkpoint mismatch'
        checkpoint_checks.append(dict(case=name,lastCheckpointMatchesActualStateAndJournal=True))
        audits[name]={k:v for k,v in checked.items() if not k.startswith('_')}
        milestone=dict(case=name,version=base['version'],runStatus=base['status'],originAt=recovered['originAt'])
        for k,v in recovered['milestones'].items():milestone.update({k+'_'+field:value for field,value in v.items()})
        milestones.append(milestone)
        intervals.extend(dict(case=name,**i) for i in recovered['readinessIntervals'])
        conditions.extend(dict(case=name,**{k:v for k,v in i.items() if k!='conditions'},**i['conditions']) for i in recovered['conditionIntervals'])
        ds=inputs.read(selected['decisionsFile'])
        export=selected['result']['summary'];measurement=selected['result']['measurement']
        pp=export.get('partialProgress') or {};cost=pp.get('accruedCosts') or {}
        progress.append(dict(case=name,status=selected['status'],observedUntil=actual['at'],
            decisions=export['decisions'],searchStatuses=json.dumps(export['searchStatuses']),
            controllerElapsedSeconds=measurement['elapsedNanos']/Decimal(10**9),
            controllerThreadCpuSeconds=measurement['threadCpuNanos']/Decimal(10**9) if measurement.get('threadCpuNanos') is not None else None,
            gcMillis=measurement.get('gcCollectionTimeMillisDelta'),
            servedSoFar=pp.get('servedContactsSoFar'),usefulTimeSoFar=pp.get('usefulSecureTimeSoFar'),
            controlCpuSoFar=cost.get('spacecraftCpu',0)+cost.get('groundCpu',0) if cost else None,
            managementBytesSoFar=cost.get('uplinkBytes',0)+cost.get('downlinkBytes',0) if cost else None,
            newKeysSoFar=pp.get('createdKeysSoFar'),lastSearchStatus=ds[-1].get('searchStatus'),
            lastStopReason=ds[-1].get('searchStopReason'),lastActionExecuted=ds[-1].get('executedAction') is not None))
        for i,d in enumerate(ds):
            a=d.get('executedAction');t=(d.get('searchDiagnostics') or {}).get('runtimeTelemetry') or {}
            if a and a['kind'] in ('PREPARE','INSTALL','SWITCH'):
                keyrows.append(dict(case=name,index=i,at=d['at'],action=a['kind'],context=json.dumps(a['contextId']),keys=json.dumps(a['keys'])))
            if d.get('searchStatus'):phases.append(dict(case=name,index=i,at=d['at'],status=d['searchStatus'],nodes=d['exploredNodes'],
                stopReason=d.get('searchStopReason'),elapsedNanos=d.get('searchNanos'),threadCpuNanos=t.get('threadCpuNanos'),
                gcMillis=t.get('gcCollectionTimeMillisDelta'),**t.get('phaseElapsedNanos',{})))
        if selected['status']=='FINISHED' and selected['rule']=='CANONICAL':
            ref=original[selected['sourceCase']]
            eq=inputs.read(selected['eventsFile'])==audit.load(ROOT/'target/security-evaluation-se2'/ref['eventsFile'])
            metrics=selected['simulationMetrics']==ref['simulationMetrics'] and selected['securityMetrics']==ref['securityMetrics']
            assert eq and metrics,name+' canonical mismatch'
            checks.append(dict(case=name,eventsEqual=eq,metricsEqual=metrics))
    assert len(rows)==12
    diag=[];obs=audit.load(OUT/'observations/X-multi-none-continue_wait.observation.json')
    for order in ('RENEWAL_FIRST','SERVICE_FIRST'):
        dest=OUT/'diagnostic'/order;process=audit.load(dest/'process.json')
        assert audit.load(dest/'observation.json')==obs
        result=audit.load(dest/'result.json') if (dest/'result.json').exists() else None
        if result and result['score'] is not None:
            replay=audit.load(dest/'plan-replay.json')
            assert replay['status']=='PASS' and replay['score']==result['score']
        diag.append(dict(traversal=order,process=process,result=result))
    reference,new=diag[0]['result'],diag[1]['result']
    equivalent=None
    if reference and new and reference['status']==new['status']=='COMPLETE':
        equivalent=reference['score']==new['score'] and reference['bestPlan']==new['bestPlan']
        assert equivalent
    # An incomplete reference remains unproved, even if its incumbent matches.
    oracle=[]
    for h in (1,2):
        for rule,lazy in (('CANONICAL',False),('CONTINUE_WAIT',True)):
            name=f'X-{"next" if h==1 else "multi"}-original-detected-{rule.lower()}'
            selected=summary.get(name,old[name]);inp=inputs if name in summary else oldinputs
            ref=audit.load(OLD/'claude-replay'/f'X-original-H{h}-{"lazy" if lazy else "canonical"}.json')
            comparable=selected['status']==ref['status']=='FINISHED'
            eq=ref['metrics']==selected['simulationMetrics'] and ref['security']==selected['securityMetrics'] if comparable else None
            actions=[d.get('executedAction') for d in inp.read(selected['decisionsFile'])] if comparable else None
            same_actions=actions==ref['actions'] if comparable else None
            if comparable:assert eq and same_actions,'Claude original mismatch: '+name
            oracle.append(dict(case=name,comparable=comparable,metricsEqual=eq,actionsEqual=same_actions,
                caveat='Original Claude tied-child helper did not reject all nonnull INCOMPLETE scores.'))
    tables=DOC/'tables';tables.mkdir(exist_ok=True)
    for filename,data in [('twelve-retries.csv',rows),('combined-comparisons.csv',combined),('checkpoint-writes.csv',writes),
        ('recovery-milestones.csv',milestones),('readiness-intervals.csv',intervals),('overlapping-conditions.csv',conditions),
        ('key-decisions.csv',keyrows),('search-decisions.csv',phases),('controller-progress.csv',progress)]:table(tables/filename,data)
    write(tables/'representative-search.json',dict(searches=diag,fullReferenceEquivalence=equivalent,
        incumbentScoreEqual=reference['score']==new['score'] if reference and new else None,
        incumbentPlanEqual=reference['bestPlan']==new['bestPlan'] if reference and new else None,
        caveat='Equality to an incomplete incumbent does not establish its optimality or tie-break optimality.'))
    write(tables/'independent-audit.json',dict(status='PASS',cases=audits,newFiles=inputs.hashes,oldFiles=oldinputs.hashes,
        auditHelperSha256=audit.sha(Path(audit.__file__)),recoveryHelperSha256=audit.sha(Path(recovery.__file__))))
    validation=dict(status='PASS',coverage=dict(Counter(r['status'] for r in rows)),combinedCoverage=dict(Counter(r['status'] for r in combined)),
        canonicalChecks=checks,checkpointChecks=checkpoint_checks,claudeOriginalChecks=oracle,representativeFullPlanEquivalence=equivalent,
        observationEquivalence='PASS',summarySha256=audit.sha(OUT/'summary.json'),analysisSha256=audit.sha(Path(__file__)))
    write(DOC/'validation.json',validation)
    md=['# Двенадцать отдельных повторов SE2.2.1','','A: исправление checkpoint, старый обход. B: исправление + SERVICE_FIRST.',
        'Прежние попытки сохранены; ссылки ведут на их отдельные журналы. Время поиска не входит в модельное время.','',
        '| Исходная попытка | Версия | Было → стало | Контакты; полезное s; CPU s; B; ключи | Поиск s / узлы | Повторы записи |',
        '|---|---|---|---|---|---:|']
    for r in rows:
        score='; '.join(n(r.get(k)) for k in ('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')) if r['status']=='FINISHED' else '—'
        path='../../target/security-evaluation-se22/'+r['name']+'.log'
        newpath='../../target/security-evaluation-se221/'+r['name']+'.log'
        md.append(f'| [{r["name"]}]({path}) · [повтор]({newpath}) | {r["stage"].upper()} | {r["sourceStatus"]} → {r["status"]} | {score} | {n(r.get("searchSeconds"))} / {n(r.get("nodes"))} | {r["checkpointRetries"]} |')
    md+=['','## X: объединённые полные исполнения','',
        'В каждой ячейке: версия; обслуженные контакты; первый независимый кадр (s от уведомления НС); конфиденциально доставленные B.',
        'SE2.2 — прежний завершённый запуск; A/B — только новый повтор. CENSORED не равен нулю.','',
        '| Атака | Next canonical | Multi canonical | Next alternative | Multi alternative |','|---|---|---|---|---|']
    by={(r['attackPoint'],r['policy'],r['rule']):r for r in combined if r['fixture']=='X'}
    for point in (None,'original','p70','p90','gap-midpoint'):
        parts=[]
        for policy,rule in [('next','CANONICAL'),('multi','CANONICAL'),('next','CONTINUE_WAIT'),('multi','CONTINUE_WAIT')]:
            r=by[(point,policy,rule)]
            frame=n(r.get('independent_frame_seconds')) if r.get('independent_frame_seconds') is not None else r.get('independent_frame_status','—')
            parts.append(r['status'] if r['status']!='FINISHED' else f'{r["version"]}; {r["servedContacts"]}; {frame}; {r["confidentialDeliveredBytes"]}')
        md.append('| '+(point or 'none')+' | '+' | '.join(parts)+' |')
    (DOC/'retry-table.md').write_text('\n'.join(md)+'\n',encoding='utf-8')
    print(json.dumps(validation,indent=2))
if __name__=='__main__':main()
