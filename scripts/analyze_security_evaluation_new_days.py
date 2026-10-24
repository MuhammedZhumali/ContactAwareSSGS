#!/usr/bin/env python3
"""SE2.4 journal postprocessing; existing auditors, no solver calls or metric changes."""
import json
from collections import Counter
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
import analyze_security_evaluation_se222 as proof
from analyze_security_evaluation_se222 import save, csv_file
from run_security_evaluation_new_days import ROOT, OUT, DOC, verify

FIVE = ('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')
SECURITY = ('deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes')


def shown(x):
    return 'unavailable' if x is None else format(x.normalize(),'f') if isinstance(x,D) else str(x)


def milestone(row, key):
    return shown(row[key+'_seconds']) if row[key+'_seconds'] is not None else row[key+'_status']


def schedules():
    fragments=audit.load(OUT/'fragments.json'); rows=[]
    lines=['# Полные расписания и выбранные фрагменты','',
        'UTC; расчётные окна Orekit по сохранённым источникам. Полные выбранные контакты также в [CSV](schedules.csv). '
        'Индексы с нуля. ID контактов локальны каждому дню; для объединения использовать day + id + start.', '']
    for f in fragments:
        schedule=audit.load(ROOT/f['scheduleFile']); epoch=audit.instant(f['geometryParameters']['orbitEpochUtc']+'Z')
        link='../../'+f['scheduleFile']
        lines += [f"## {f['day']}", '',f"{schedule['interval']['start']} … {schedule['interval']['end']}; "
            f"{f['candidateContacts']} исходных окон, {f['selectedContacts']} выбранных. [Полное расписание]({link}). "
            f"SHA-256 `{f['scheduleSha256']}`. Расстояние границ от эпохи: "
            f"{shown((audit.instant(schedule['interval']['start'])-epoch)/3600)} … {shown((audit.instant(schedule['interval']['end'])-epoch)/3600)} h.", '',
            f"Фрагмент {f['kind']}, индексы {f['indices']}, Multi H={f['multiH']}.", '',
            '| Индекс | ID | Станция | Начало | Конец | Длительность,s | Предыдущий промежуток,s |',
            '| ---: | --- | --- | --- | --- | ---: | ---: |']
        for i,w in enumerate(schedule['contacts']):
            duration=audit.instant(w['end'])-audit.instant(w['start'])
            gap=audit.instant(w['start'])-audit.instant(schedule['contacts'][i-1]['end']) if i else None
            rows.append(dict(day=f['day'],index=i,selectedFragment=i in f['indices'],**w,durationSeconds=duration,previousGapSeconds=gap))
            if i in f['indices']:
                lines.append(f"| {i} | {w['id']} | {w['groundStationId']} | {w['start']} | {w['end']} | {shown(duration)} | {shown(gap) if i!=f['indices'][0] else 'NA'} |")
        lines += ['']
    csv_file(DOC/'schedules.csv',rows)
    (DOC/'schedules.md').write_text('\n'.join(lines).rstrip()+'\n',encoding='utf-8')


def mechanism(scenario, events, actual, full):
    first = scenario['contacts'][0]['window']; target = audit.context(first)
    window = next((c['window'] for c in scenario['contacts'][1:] if audit.context(c['window']) == target), None)
    operations = [e for e in events if e['type'] in ('STEP_STARTED','STEP_COMPLETED','STEP_FAILED','STEP_INTERRUPTED',
        'KEY_EXPIRED','KEY_DISCLOSED','DISCLOSURE_NO_TARGET','REVOCATION_RECEIVED_GROUND','REVOCATION_RECEIVED_SPACECRAFT')]
    decisions = [dict(index=i,at=d['observation']['at'],horizon=d['observation']['horizon'],
        visibleContacts=[c['window']['id'] for c in d['observation']['visibleContacts']],evaluationEnd=d['observation']['evaluationEnd'],
        source=d['source'],action=d['executedAction']) for i,d in enumerate(full['decisions'])]
    entry = dict(status='CONTROL_NO_REQUIRED_RETURN')
    if window:
        at=audit.instant(window['start']); entry=dict(status='NOT_OBSERVED',at=window['start'],contactId=window['id'])
        if audit.instant(actual['at'])>=at:
            disclosed={k for e in events if e['type']=='KEY_DISCLOSED' for k in e['keyIds'].values()}
            replay=recovery.Replay(scenario,disclosed)
            for e in events:
                if audit.instant(e['at'])>at or (audit.instant(e['at'])==at and e['type']=='STEP_STARTED'):break
                replay.apply(e)
            valid=lambda k: audit.context(k['contextId'])==target and k['id'] not in replay.disclosed|replay.blocked and k['status'] in ('PRE_ACTIVE','ACTIVE') and at<k['deadline']
            pairs=[p for p in replay.prepared_pairs if all(valid(replay.keys[k]) for k in p.values())]
            installed=[p for p in pairs if all(replay.keys[k]['installedOnSpacecraft'] for k in p.values())]
            active=replay.pair_ready(target,at)
            entry.update(status='OBSERVED',material='ACTIVE' if active else 'INSTALLED' if installed else 'PREPARED' if pairs else 'NO_INDEPENDENT_PAIR',
                context=replay.contexts[target],keys=[k for k in replay.keys.values() if audit.context(k['contextId'])==target],
                preparedPairs=pairs,installedPairs=installed,activeIndependent=active,
                noticePending=any(e['type']=='REVOCATION_RECEIVED_GROUND' and audit.instant(e['at'])<=at for e in events) and replay.sc_notice is None)
    return dict(returnEntry=entry,operations=operations,decisions=decisions,finalKeys=actual['securityState']['keys'])


def main():
    registration=verify(); schedules(); summary=audit.load(OUT/'summary.json'); rows=[]; evidence={}; mechanisms={}; hashes={}
    proof.OUT=OUT
    for spec in registration['cases']:
        name=spec['name']; row=summary[name]; scenario=audit.load(OUT/spec['modelFile'])['scenario']
        item=dict(case=name,day=spec['day'],fragmentKind=spec['fragmentKind'],policy=spec['policy'],H=spec['horizon'],
            rule=spec['rule'],attack=spec['attack'],status=row['status'],commit=registration['build']['commit'],
            modelFile=spec['modelFile'],eventsFile=row.get('eventsFile'),experimentStart=scenario['start'],experimentEnd=scenario['end'],
            censorAt=scenario['end'],originAt=None,observationEnd=None,attackOutcome='UNAVAILABLE',
            controllerSeconds=None,controllerThreadCpuSeconds=None,processSeconds=None,searchSeconds=None,
            guardSeconds=None,preparationSeconds=None,searches=None,suffixReuses=None,exploredNodes=None,
            stopReason=None,returnEntryMaterial=None)
        five={}; security={}; stages={k:dict(status='unavailable',seconds=None) for k in recovery.MILESTONES}
        check=dict(auditStatus='NOT_AUDITABLE',reason='No exported actual journal; no imputed final metrics')
        if row.get('process'):item['processSeconds']=D(row['process']['processElapsedNanos'])/10**9
        if row.get('eventsFile'):
            inputs=audit.Inputs(OUT); result=row['result']; s=result['summary']
            enriched=dict(row,pairedDelta=None,**{k:s[k] for k in ('decisions','searchNanos','exploredNodes')})
            check=audit.audit_case(inputs,name,enriched)
            actual,events=inputs.read(row['actualFile']),inputs.read(row['eventsFile'])
            recovered=recovery.measure(scenario,events,actual,row['status'])
            check.update(auditStatus='PASS',recoveryObservedPrefix=recovered,proofAudit=proof.proof_audit(row))
            full=audit.load(OUT/Path(row['actualFile']).parent/'actual.full-trace.json')
            mechanisms[name]=mechanism(scenario,events,actual,full)
            item.update(originAt=recovered['originAt'],observationEnd=actual['at'],returnEntryMaterial=mechanisms[name]['returnEntry'].get('material'))
            if row['status']=='FINISHED':
                five,security,stages=check['five'],check['security'],recovered['milestones']
                item['attackOutcome']='NO_ATTACK' if spec['attack']=='none' else 'DISCLOSURE_NO_TARGET' if any(e['type']=='DISCLOSURE_NO_TARGET' for e in events) else 'KEY_DISCLOSED' if any(e['type']=='KEY_DISCLOSED' for e in events) else 'NOT_OBSERVED'
            comp=result['computation']; telemetry=result['measurement']; cpu=telemetry.get('threadCpuNanos')
            item.update(controllerSeconds=D(comp['elapsedNanos'])/10**9,controllerThreadCpuSeconds=D(cpu)/10**9 if cpu is not None else None,
                searchSeconds=D(s['searchNanos'])/10**9,guardSeconds=D(comp['guardNanos'])/10**9,preparationSeconds=D(comp['preparationNanos'])/10**9,
                searches=comp['searches'],suffixReuses=comp['suffixReuses'],exploredNodes=s['exploredNodes'])
            failed=[d['search'] for d in full['decisions'] if d.get('search') and d['search']['status']!='COMPLETE']
            if failed:item['stopReason']=failed[-1].get('diagnostics',{}).get('stopReason')
            check.pop('_scenario');check.pop('_decisions')
            hashes.update({rel: value for rel,value in ((str((OUT/p).relative_to(ROOT)).replace('\\','/'),h) for p,h in inputs.hashes.items())})
        item.update({k:five.get(k) for k in FIVE});item.update({k:security.get(k) for k in SECURITY})
        for k in recovery.MILESTONES:
            item[k+'_status']=stages[k]['status'].upper();item[k+'_seconds']=stages[k]['seconds']
        rows.append(item);evidence[name]=check
    assert len(rows)==16
    pairs=[]
    for day in ('day1','day2'):
        for attack in ('none','late-detected'):
            for rule in ('CANONICAL','CONTINUE_WAIT'):
                a,b=[next(r for r in rows if r['day']==day and r['attack']==attack and r['rule']==rule and r['policy']==p) for p in ('next','multi')]
                valid=a['status']==b['status']=='FINISHED'
                d=dict(day=day,attack=attack,rule=rule,nextH=a['H'],multiH=b['H'],status='COMPARABLE' if valid else 'NOT_COMPARABLE',nextStatus=a['status'],multiStatus=b['status'])
                for k in FIVE+SECURITY:d['delta_'+k]=b[k]-a[k] if valid else None
                for k in recovery.MILESTONES:
                    d[k+'_next']=milestone(a,k);d[k+'_multi']=milestone(b,k)
                    d['delta_'+k]=b[k+'_seconds']-a[k+'_seconds'] if valid and a[k+'_seconds'] is not None and b[k+'_seconds'] is not None else None
                d.update(nextReturnEntry=a['returnEntryMaterial'],multiReturnEntry=b['returnEntryMaterial'])
                pairs.append(d)
    csv_file(DOC/'comparison.csv',rows);csv_file(DOC/'differences.csv',pairs)
    save(OUT/'independent-audit.json',evidence);save(OUT/'mechanisms.json',mechanisms);save(OUT/'analysis-input-hashes.json',hashes)
    validation=dict(cases=16,statuses=dict(Counter(r['status'] for r in rows)),
        audited=sum(e['auditStatus']=='PASS' for e in evidence.values()),
        reuseLinks=sum(e.get('proofAudit',{}).get('verifiedReuseLinks',0) for e in evidence.values()),
        pairedComparisons=8,completedComparisons=sum(d['status']=='COMPARABLE' for d in pairs),
        noTarget=sum(r['attackOutcome']=='DISCLOSURE_NO_TARGET' for r in rows),
        interpretation='Final metrics only FINISHED; missing events censored/not-applicable by existing contract; unfinished/not-run unavailable')
    save(DOC/'validation.json',validation)
    lines=['# SE2.4 — 16 зарегистрированных сочетаний','',
        'Пять целей: served / useful s / CPU GS+SC s / management B / keys. Трафик: confidential / exposed before / after, B. '
        'Recovery: КА / пара / SA / кадр от ground notice. NA = not-applicable. Полные originAt, censorAt, observationEnd и CPU потока — в [CSV](comparison.csv).', '',
        '| День | Политика H / правило | Атака | Статус | Пять целей | Трафик B | Recovery s | SEARCH / REUSE / узлы | Controller s |',
        '| --- | --- | --- | --- | --- | --- | --- | --- | ---: |']
    for r in rows:
        metrics=' / '.join(shown(r[k]) for k in FIVE) if r['servedContacts'] is not None else 'unavailable'
        traffic=' / '.join(shown(r[k]) for k in SECURITY[1:]) if r['servedContacts'] is not None else 'unavailable'
        stages=' / '.join(milestone(r,k).replace('NOT-APPLICABLE','NA') for k in recovery.MILESTONES)
        compute=' / '.join(shown(r[k]) for k in ('searches','suffixReuses','exploredNodes'))
        lines.append(f"| {r['day']} | {r['policy']} H{r['H']} / {r['rule']} | {r['attack']} | {r['status']} | {metrics} | {traffic} | {stages} | {compute} | {shown(r['controllerSeconds'])} |")
    (DOC/'comparison.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    lines=['# Multi − Next: восемь пар','', 'Положительные CPU/bytes/keys — дополнительные расходы Multi. Полные четыре этапа и статусы — в [CSV](differences.csv).','',
        '| День | H Next/Multi | Атака / правило | Статус | Δ served / useful / CPU / bytes / keys | Δ confidential | Кадр Next / Multi,s | Готовность при возврате Next / Multi |',
        '| --- | --- | --- | --- | --- | ---: | --- | --- |']
    for d in pairs:
        values=' / '.join(shown(d['delta_'+k]) for k in FIVE)
        lines.append(f"| {d['day']} | {d['nextH']} / {d['multiH']} | {d['attack']} / {d['rule']} | {d['status']} | {values} | {shown(d['delta_confidentialDeliveredBytes'])} | {d['independent_frame_next']} / {d['independent_frame_multi']} | {shown(d['nextReturnEntry'])} / {shown(d['multiReturnEntry'])} |")
    (DOC/'differences.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    verify();print(json.dumps(validation,indent=2))


if __name__=='__main__':main()
