#!/usr/bin/env python3
"""60-row journal-based comparison; no policy execution, no imputed final outcomes."""
import json
from collections import Counter
from decimal import Decimal as D
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
import analyze_security_evaluation_se222 as provenance
from analyze_security_evaluation_se222 import save, csv_file
from run_security_evaluation_c3 import ROOT, OUT, DOC, ATTACKS, verify

FIVE=('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')
SECURITY=('deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes')

def c3_entry(scenario,events,actual):
    window=scenario['contacts'][2]['window'];at=audit.instant(window['start']);cid=audit.context(window)
    if audit.instant(actual['at'])<at:return dict(status='NOT_OBSERVED',at=window['start'])
    disclosed={k for e in events if e['type']=='KEY_DISCLOSED' for k in e['keyIds'].values()}
    replay=recovery.Replay(scenario,disclosed)
    for e in events:
        if audit.instant(e['at'])>at or (audit.instant(e['at'])==at and e['type']=='STEP_STARTED'):break
        replay.apply(e)
    valid=lambda k: (audit.context(k['contextId'])==cid and k['id'] not in replay.disclosed|replay.blocked
        and k['status'] in ('PRE_ACTIVE','ACTIVE') and at<k['deadline'])
    pairs=[p for p in replay.prepared_pairs if all(valid(replay.keys[k]) for k in p.values())]
    installed=[p for p in pairs if all(replay.keys[k]['installedOnSpacecraft'] for k in p.values())]
    active=replay.pair_ready(cid,at)
    state='ACTIVE' if active else 'INSTALLED' if installed else 'PREPARED' if pairs else 'NO_INDEPENDENT_PAIR'
    ground=next((e for e in events if e['type']=='REVOCATION_RECEIVED_GROUND' and audit.instant(e['at'])<=at),None)
    actions=[e for e in events if audit.instant(e['at'])>=at and e.get('contextId') and audit.context(e['contextId'])==cid
             and e['type'] in ('STEP_STARTED','STEP_COMPLETED','STEP_FAILED','STEP_INTERRUPTED','REVOCATION_RECEIVED_SPACECRAFT')]
    return dict(status='OBSERVED',at=window['start'],material=state,noticePending=bool(ground and replay.sc_notice is None),
        context=replay.contexts[cid],keys=[k for k in replay.keys.values() if audit.context(k['contextId'])==cid],
        validPreparedPairs=pairs,validInstalledPairs=installed,activeIndependent=active,actions=actions)

def shown(value):
    if value is None:return 'unavailable'
    if isinstance(value,D):return format(value.normalize(),'f')
    return str(value)

def milestone(row,key):
    value=row[key+'_seconds'];status=row[key+'_status']
    return shown(value)+(' (already ready)' if status=='ALREADY-READY-AT-ORIGIN' else '') if value is not None else status

def main():
    m=verify();primary=audit.load(OUT/'summary.json');new=dict(primary)
    repeats=audit.load(OUT/'technical-repeat-summary.json') if (OUT/'technical-repeat-summary.json').exists() else {}
    for name,row in repeats.items():
        if row['status']=='FINISHED':new[name]=row
    proofs=0;evidence={};rows=[];hashes={};entries={}
    specs=sorted(m['baseline']+m['cases'],key=lambda r:(r['duration'],ATTACKS.index(r['attack']),r['rule'],r['policy']=='multi'))
    for spec in specs:
        name=spec['name'];is_new=spec['duration']!=5
        if is_new:
            row=new[name];base=OUT;version='SE2.3';commit=m['build']['commit']
        else:
            base=(ROOT/spec['source']).parent;row=audit.load(ROOT/spec['source'])[spec['legacyName']]
            version=spec['version'];commit=spec['commit']
        scenario=audit.load(base/row['modelFile'])['scenario']
        item=dict(case=name,duration=spec['duration'],attack=spec['attack'],policy=spec['policy'],rule=spec['rule'],status=row['status'],
            version=version,commit=commit,source=(base/('technical-repeat-summary.json' if row.get('attempt')==2 else 'summary.json')).relative_to(ROOT).as_posix(),
            selectedAttempt=row.get('attempt',1) if is_new else 'historical',
            attemptStatuses=('1:'+primary[name]['status']+(';2:'+repeats[name]['status'] if name in repeats else '')) if is_new else 'historical:FINISHED',
            events=(base/row['eventsFile']).relative_to(ROOT).as_posix() if row.get('eventsFile') else None,
            experimentStart=scenario['start'],experimentEnd=scenario['end'],censorAt=scenario['end'],originAt=None,observationEnd=None,
            controllerSeconds=None,searches=None,suffixReuses=None,exploredNodes=None,searchSeconds=None,guardSeconds=None,preparationSeconds=None)
        check={'auditStatus':'UNAVAILABLE','reason':'No exported actual journal','status':row['status']};five={};security={}
        milestones={k:{'status':'unavailable','seconds':None} for k in recovery.MILESTONES}
        entry={'status':'NOT_OBSERVED'}
        if row.get('eventsFile'):
            summary=row['result']['summary'];inputs=audit.Inputs(base)
            enriched=dict(row,pairedDelta=None,**{k:summary[k] for k in ('decisions','searchNanos','exploredNodes')})
            check=audit.audit_case(inputs,name,enriched);events=inputs.read(row['eventsFile']);actual=inputs.read(row['actualFile'])
            recovered=recovery.measure(scenario,events,actual,row['status'])
            check['recoveryObservedPrefix']=recovered;check['auditStatus']='PASS'
            item.update(originAt=recovered['originAt'],observationEnd=actual['at'])
            # Final outcome columns are intentionally null for unfinished runs, even when a prefix milestone exists.
            if row['status']=='FINISHED':five=check['five'];security=check['security'];milestones=recovered['milestones']
            entry=c3_entry(scenario,events,actual)
            computation=row['result'].get('computation')
            decisions=check['_decisions']
            item.update(controllerSeconds=D(computation['elapsedNanos'] if computation else summary['controllerWallNanos'])/10**9,
                searches=sum(d['source']=='SEARCH' for d in decisions),suffixReuses=sum(d['source']=='SUFFIX_REUSE' for d in decisions),
                exploredNodes=summary['exploredNodes'],searchSeconds=D(summary['searchNanos'])/10**9)
            if computation:item.update(guardSeconds=D(computation['guardNanos'])/10**9,preparationSeconds=D(computation['preparationNanos'])/10**9)
            if is_new or version=='SE2.2.2':
                provenance.OUT=base
                check['proofAudit']=provenance.proof_audit(row)
                if is_new:proofs+=check['proofAudit']['verifiedReuseLinks']
            check.pop('_scenario');check.pop('_decisions')
            hashes.update({(base/p).relative_to(ROOT).as_posix():h for p,h in inputs.hashes.items()})
        item.update({k:five.get(k) for k in FIVE});item.update({k:security.get(k) for k in SECURITY})
        for k in recovery.MILESTONES:
            item[k+'_status']=milestones[k]['status'].upper();item[k+'_seconds']=milestones[k]['seconds']
        item['c3EntryMaterial']=entry.get('material');item['c3EntryNoticePending']=entry.get('noticePending')
        item['c3Served']=None
        if row['status']=='FINISHED':item['c3Served']=next(x['served'] for x in row['simulationMetrics']['contacts'] if x['contactId']=='C3')
        entries[name]=entry;evidence[name]=check;rows.append(item)
    assert len(rows)==60
    csv_file(DOC/'comparison.csv',rows)
    save(OUT/'independent-audit.json',evidence);save(OUT/'c3-entry-and-operations.json',entries);save(OUT/'analysis-input-hashes.json',hashes)
    lines=['# SE2.3 — 60 сочетаний', '',
        'Пять показателей: контакты / useful s / CPU GS+SC s / management B / новые ключи. '
        'Трафик: конфиденциально / раскрыто до / после. Recovery: КА / пара / SA / кадр, секунды от originAt (уведомление НС). '
        'NA = not-applicable; C = CENSORED; U = unavailable. Пустые числовые CSV-поля не являются нулём. '
        'Полные версии, времена originAt/censorAt/observationEnd и вычислительные расходы — в [CSV](comparison.csv). '
        'Смешанные версии 5 s не являются единым benchmark.', '',
        '| C3,s | Атака | Политика / правило | Статус; версия | Пять показателей | Трафик B | Recovery s | origin / план. censor, t от 00:00 | SEARCH / REUSE / узлы; controller s |',
        '| ---: | --- | --- | --- | --- | --- | --- | --- | --- |']
    epoch=audit.instant('2026-01-01T00:00:00Z')
    for r in rows:
        stages=[milestone(r,k).replace('NOT-APPLICABLE','NA').replace('CENSORED','C').replace('UNAVAILABLE','U') for k in recovery.MILESTONES]
        origin=shown(audit.instant(r['originAt'])-epoch) if r['originAt'] else 'NA' if r['attack']=='none' else 'U'
        end=shown(audit.instant(r['censorAt'])-epoch)
        metrics=' / '.join(shown(r[k]) for k in FIVE) if r['servedContacts'] is not None else 'unavailable'
        traffic=' / '.join(shown(r[k]) for k in SECURITY[1:]) if r['servedContacts'] is not None else 'unavailable'
        costs=' / '.join(shown(r[k]) for k in ('searches','suffixReuses','exploredNodes'))+'; '+shown(r['controllerSeconds'])
        attempts='; '+r['attemptStatuses'] if r['selectedAttempt']==2 else ''
        lines.append(f"| {r['duration']} | {r['attack']} | {r['policy']} / {r['rule']} | {r['status']}; {r['version']}{attempts} | {metrics} | {traffic} | {' / '.join(stages)} | {origin} / {end} | {costs} |")
    (DOC/'comparison.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    pairs=[]
    for duration in (3,5,9):
        for attack in ATTACKS:
            for rule in ('CANONICAL','CONTINUE_WAIT'):
                a,b=[next(r for r in rows if r['duration']==duration and r['attack']==attack and r['rule']==rule and r['policy']==p) for p in ('next','multi')]
                valid=a['status']==b['status']=='FINISHED'
                d=dict(duration=duration,attack=attack,rule=rule,status='COMPARABLE' if valid else 'NOT_COMPARABLE',
                       nextStatus=a['status'],multiStatus=b['status'])
                for k in FIVE+SECURITY:d['delta_'+k]=b[k]-a[k] if valid else None
                for k in recovery.MILESTONES:
                    d[k+'_next']=milestone(a,k);d[k+'_multi']=milestone(b,k)
                    d['delta_'+k]=b[k+'_seconds']-a[k+'_seconds'] if valid and a[k+'_seconds'] is not None and b[k+'_seconds'] is not None else None
                d.update(nextC3=a['c3EntryMaterial'],multiC3=b['c3EntryMaterial'],nextNoticePending=a['c3EntryNoticePending'],multiNoticePending=b['c3EntryNoticePending'])
                pairs.append(d)
    csv_file(DOC/'differences.csv',pairs)
    delta_lines=['# Multi − Next: 30 сравнений', '',
        'Знак CPU/bytes/keys: положительное значение означает дополнительные расходы Multi. '
        'Recovery здесь — первый независимый кадр, полный набор четырёх этапов в CSV. '
        'CENSORED не превращается в числовую разницу.', '',
        '| C3 | Атака | Правило | Сравнение | Δ контакты / useful s / CPU s / bytes / keys | Δ confidential B | Первый кадр Next / Multi,s | Готовность C3 Next / Multi |',
        '| ---: | --- | --- | --- | --- | ---: | --- | --- |']
    for d in pairs:
        values=' / '.join(shown(d['delta_'+k]) for k in FIVE)
        delta_lines.append(f"| {d['duration']} | {d['attack']} | {d['rule']} | {d['status']} | {values} | {shown(d['delta_confidentialDeliveredBytes'])} | {d['independent_frame_next']} / {d['independent_frame_multi']} | {shown(d['nextC3'])} / {shown(d['multiC3'])} |")
    (DOC/'differences.md').write_text('\n'.join(delta_lines)+'\n',encoding='utf-8')
    attempts=[];extra_audit={};all_links=0
    for name,row in list(primary.items())+list(repeats.items()):
        result=row.get('result');summary=(result or {}).get('summary')
        attempt=row.get('attempt',1)
        record=dict(case=name,attempt=attempt,status=row['status'],selected=attempt==new[name].get('attempt',1),
            events=row.get('eventsFile'),modelFile=row['modelFile'],experimentEnd=row['experimentEnd'],censorAt=row['censorAt'],
            originAt=None,observationEnd=None,processSeconds=D(row['process']['processElapsedNanos'])/10**9,
            controllerSeconds=D(result['computation']['elapsedNanos'])/10**9 if result else None,
            controllerThreadCpuSeconds=D(result['measurement']['threadCpuNanos'])/10**9 if result and result['measurement']['threadCpuNanos'] is not None else None,
            searches=result['computation']['searches'] if result else None,suffixReuses=result['computation']['suffixReuses'] if result else None,
            exploredNodes=summary['exploredNodes'] if summary else None,
            technicalReason='Elapsed/watchdog anomaly; exact cause unknown' if name in repeats and attempt==1 else None)
        check=evidence[name]
        if not record['selected'] and row.get('eventsFile'):
            enriched=dict(row,pairedDelta=None,**{k:summary[k] for k in ('decisions','searchNanos','exploredNodes')})
            inputs=audit.Inputs(OUT);check=audit.audit_case(inputs,name,enriched)
            check['recoveryObservedPrefix']=recovery.measure(check['_scenario'],inputs.read(row['eventsFile']),inputs.read(row['actualFile']),row['status'])
            provenance.OUT=OUT;check['proofAudit']=provenance.proof_audit(row)
            check.pop('_scenario');check.pop('_decisions');extra_audit[name+':'+str(attempt)]=check
            hashes.update({(OUT/p).relative_to(ROOT).as_posix():h for p,h in inputs.hashes.items()})
        recovered=check.get('recoveryObservedPrefix',{})
        record.update(originAt=recovered.get('originAt'),observationEnd=recovered.get('observationEnd'))
        for k in FIVE:record[k]=check['five'][k] if row['status']=='FINISHED' else None
        for k in SECURITY:record[k]=check['security'][k] if row['status']=='FINISHED' else None
        for k in recovery.MILESTONES:
            metric=recovered.get('milestones',{}).get(k,{}) if row['status']=='FINISHED' else {}
            record[k+'_status']=metric.get('status','unavailable').upper();record[k+'_seconds']=metric.get('seconds')
        all_links+=check.get('proofAudit',{}).get('verifiedReuseLinks',0)
        attempts.append(record)
    csv_file(DOC/'attempts.csv',attempts);save(OUT/'extra-attempt-audit.json',extra_audit);save(OUT/'analysis-input-hashes.json',hashes)
    validation=dict(totalRows=60,registeredCombinations=40,actualNewAttempts=len(attempts),
        primaryStatuses=dict(Counter(r['status'] for r in primary.values())),technicalRepeatStatuses=dict(Counter(r['status'] for r in repeats.values())),
        selectedNewStatuses=dict(Counter(r['status'] for r in new.values())),
        audited=sum(e['auditStatus']=='PASS' for e in evidence.values()),newVerifiedReuseLinks=proofs,
        extraAttemptsAudited=len(extra_audit),allAttemptReuseLinks=all_links,
        comparablePairs=sum(d['status']=='COMPARABLE' for d in pairs),
        note='All final metrics unavailable on unfinished runs; prefix facts preserved separately. Existing independent journal/recovery/provenance audit; no new simulation.')
    save(DOC/'validation.json',validation);verify();print(json.dumps(validation,indent=2))

if __name__=='__main__':main()
