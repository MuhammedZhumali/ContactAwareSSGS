#!/usr/bin/env python3
"""Re-audit 20 X combinations; no solver, new metrics or legacy artifact writes."""
import csv
import json
from collections import Counter
from decimal import Decimal
from pathlib import Path

import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
from run_security_evaluation_se222 import ROOT, OUT, DOC, ATTACKS, verify


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, default=str)+'\n', encoding='utf-8')


def csv_file(path, rows):
    with path.open('w', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def proof_audit(row):
    folder = OUT/Path(row['actualFile']).parent
    full = audit.load(folder/'actual.full-trace.json')
    actual_scenario = full['snapshot']['scenario']
    frozen_scenario = audit.load(OUT/row['modelFile'])['scenario']
    # SecurityConstraints.authorizedContexts is a Java Set: JSON iteration order is not semantic.
    def normalized(scenario):
        constraints = dict(scenario['constraints'])
        constraints['authorizedContexts'] = sorted(constraints['authorizedContexts'], key=audit.context)
        return dict(scenario, constraints=constraints)
    assert normalized(actual_scenario) == normalized(frozen_scenario)
    assert full['snapshot'] == audit.load(folder/'checkpoint.json')['snapshot']
    assert full['snapshot']['journal'] == audit.load(folder/'actual.events.json')
    decisions, computation = full['decisions'], full['computation']
    counts, reasons = Counter(), Counter()
    for i, decision in enumerate(decisions):
        counts[decision['source']] += 1
        trace = decision.get('continuation')
        if trace and trace['guard']:
            g = trace['guard']
            reasons[g['status']+':'+g['reason'].split(':', 1)[0]] += 1
        if decision['source'] != 'SUFFIX_REUSE':
            continue
        assert decision['search'] is None and trace['guard']['status'] == 'ALLOWED'
        reference, offset = trace['rootProofReference'], trace['suffixOffset']
        root_index = int(reference.removeprefix('decision:'))
        assert 0 <= root_index < i and offset >= 1
        root = decisions[root_index]
        assert root['source'] == 'SEARCH' and root['search']['status'] == 'COMPLETE'
        assert trace['checkedProofReference'] == reference and trace['checkedOffset'] == offset-1
        assert i-root_index == offset
        # Every link derives from the same unrestricted root and executed prefix.
        for j in range(offset+1):
            assert decisions[root_index+j]['executedAction'] == root['search']['bestPlan'][j]
        previous = decisions[i-1]
        assert previous['executedAction']['kind'] == 'CONTINUE'
        assert previous['resumedAt'] == decision['observation']['at']
        assert previous['continuation']['rootProofReference'] == reference
        assert previous['continuation']['suffixOffset'] == offset-1
    assert counts['SEARCH'] == computation['searches']
    assert counts['SUFFIX_REUSE'] == computation['suffixReuses']
    assert dict(reasons) == computation['guardReasons']
    for metric in ('guardNanos', 'preparationNanos'):
        logged = sum(d['continuation'][metric] for d in decisions if d.get('continuation'))
        assert computation[metric] >= logged
        if row['status'] == 'FINISHED': assert computation[metric] == logged
    return dict(status='PASS', sources=dict(counts), verifiedReuseLinks=counts['SUFFIX_REUSE'],
                guardReasons=dict(reasons), computation=computation)


def main():
    registration = verify()
    current = audit.load(OUT/'summary.json')
    old22_dir, old221_dir = ROOT/'target/security-evaluation-se22', ROOT/'target/security-evaluation-se221'
    old22, old221 = audit.load(old22_dir/'summary.json'), audit.load(old221_dir/'summary.json')
    build22, build221 = audit.load(old22_dir/'build.json'), audit.load(old221_dir/'build-a.json')
    evidence, table, compute, hashes = {}, [], [], {}
    for attack in ATTACKS:
        for rule in ('canonical', 'continue_wait'):
            for policy in ('next', 'multi'):
                name = 'X-'+policy+'-'+attack+'-'+rule
                if policy == 'multi' and rule == 'continue_wait':
                    row, base, version, build = current[name], OUT, 'SE2.2.2', registration['build']
                elif old221.get(name, {}).get('status') == 'FINISHED':
                    row, base, version, build = old221[name], old221_dir, 'SE2.2.1a', build221
                else:
                    row, base, version, build = old22[name], old22_dir, 'SE2.2', build22
                is_final = row['status'] == 'FINISHED'
                item = dict(case=name, attack=attack, policy=policy, rule=rule.upper(), status=row['status'],
                            version=version, commit=build['commit'], source=(base/'summary.json').relative_to(ROOT).as_posix(),
                            events=(base/row['eventsFile']).relative_to(ROOT).as_posix() if row.get('eventsFile') else None)
                five, security = {}, {}
                milestones = {m: {'status': 'unavailable', 'seconds': None} for m in recovery.MILESTONES}
                check = dict(status='NOT_AUDITABLE', reason='No actual journal', runStatus=row['status'])
                if row.get('eventsFile'):
                    summary = row['result']['summary']
                    enriched = dict(row, pairedDelta=None, **{k: summary[k] for k in ('decisions','searchNanos','exploredNodes')})
                    inputs = audit.Inputs(base)
                    check = audit.audit_case(inputs, name, enriched)
                    events, actual = inputs.read(row['eventsFile']), inputs.read(row['actualFile'])
                    recovered = recovery.measure(check['_scenario'], events, actual, row['status'])
                    # Prefix milestones are retained in evidence, never presented as final recovery.
                    check['recoveryObservedPrefix'] = recovered
                    if is_final:
                        five, security, milestones = check['five'], check['security'], recovered['milestones']
                    check.pop('_scenario'); check.pop('_decisions')
                    check['auditStatus'] = 'PASS'
                    hashes.update({(base/p).relative_to(ROOT).as_posix(): h for p,h in inputs.hashes.items()})
                    if version == 'SE2.2.2':
                        check['proofAudit'] = proof_audit(row)
                        c = row['result']['computation']
                        compute.append(dict(case=name, status=row['status'], controllerSeconds=Decimal(c['elapsedNanos'])/10**9,
                            searchSeconds=Decimal(summary['searchNanos'])/10**9,
                            guardSeconds=Decimal(c['guardNanos'])/10**9,
                            preparationSeconds=Decimal(c['preparationNanos'])/10**9,
                            searches=c['searches'], suffixReuses=c['suffixReuses'], nodes=summary['exploredNodes'],
                            guardReasons=json.dumps(c['guardReasons'], sort_keys=True),
                            measurement=json.dumps(row['result']['measurement'], default=str, sort_keys=True)))
                evidence[name] = check
                for key in ('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys'):
                    item[key] = five.get(key)
                for key in ('deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes'):
                    item[key] = security.get(key)
                for key in recovery.MILESTONES:
                    item[key+'_status'] = milestones[key]['status'].upper()
                    item[key+'_seconds'] = milestones[key]['seconds']
                table.append(item)
    assert len(table) == 20
    save(OUT/'independent-audit.json', evidence)
    save(OUT/'analysis-input-hashes.json', hashes)
    save(DOC/'validation.json', dict(audited=sum(e.get('auditStatus')=='PASS' for e in evidence.values()),
         final=sum(r['status']=='FINISHED' for r in table), total=20,
         newStatuses=dict(Counter(r['status'] for r in current.values())),
         verifiedReuseLinks=sum(e.get('proofAudit',{}).get('verifiedReuseLinks',0) for e in evidence.values()),
         inputs=OUT.joinpath('analysis-input-hashes.json').relative_to(ROOT).as_posix(),
         note='Independent journal accounting and provenance checks; not independent simulation or an optimality proof'))
    csv_file(DOC/'comparison.csv', table)
    if compute: csv_file(DOC/'computation.csv', compute)
    lines = ['# X: четыре сочетания горизонта и tie-break', '',
        'Смешанные сохранённые версии указаны явно. Это не единый benchmark времени. Полные источники и commit — в [CSV](comparison.csv).', '',
        'Пять показателей: обслуженные контакты / useful s / CPU GS+SC s / служебные B / новые ключи. '
        'Доставка и раскрытие — байты. Четыре задержки отсчитаны от уведомления НС. '
        'CENSORED означает отсутствие события до конца полного опыта; unavailable — нет итогового измерения; '
        'not-applicable — нет применимого инцидента. Ноль допустим только при подтверждённой готовности на старте.', '',
        '| Атака | Политика / правило | Статус; версия | Пять показателей | Конфиденциально / раскрыто до / после | КА / пара / SA / первый кадр, s |',
        '| --- | --- | --- | --- | --- | --- |']
    for row in table:
        values = [row[k] for k in ('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys')]
        score = ' / '.join(map(str,values)) if values[0] is not None else 'unavailable'
        security = ' / '.join(str(row[k]) for k in ('confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes')) if values[0] is not None else 'unavailable'
        stages=[]
        for m in recovery.MILESTONES:
            status, seconds = row[m+'_status'], row[m+'_seconds']
            stages.append(str(seconds)+(' (already ready)' if status=='ALREADY-READY-AT-ORIGIN' else '') if seconds is not None else status.lower())
        lines.append(f"| {row['attack']} | {row['policy']} / {row['rule']} | {row['status']}; {row['version']} | {score} | {security} | {' / '.join(stages)} |")
    (DOC/'comparison.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    verify()
    print(json.dumps(audit.load(DOC/'validation.json'), indent=2, default=str))


if __name__ == '__main__': main()
