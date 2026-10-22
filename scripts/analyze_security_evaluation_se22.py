#!/usr/bin/env python3
"""Independent journal accounting and the required X checkpoint, without policy reruns."""
import argparse
from collections import Counter
import json
from pathlib import Path
import audit_security_evaluation as audit
import recovery_metrics_se21 as recovery
from run_security_evaluation_se22 import ROOT, OUT, DOC, SOURCE, verify

def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, default=str)+'\n', encoding='utf-8')

def csv(path, rows):
    audit.write_csv(path, rows, list(dict.fromkeys(k for row in rows for k in row)))

def main():
    p = argparse.ArgumentParser(); p.add_argument('stage', choices=['x','all']); args = p.parse_args()
    manifest = verify()
    summary = audit.load(OUT/'summary.json')
    source_summary = audit.load(SOURCE/'summary.json')
    inputs = audit.Inputs(OUT)
    table, milestones, intervals, keyrows, canonical_checks, audits = [], [], [], [], [], {}
    search_rows, failures, conditions, control_pairs, checked_cases = [], [], [], [], {}
    statuses = Counter()
    for spec in manifest['cases']:
        if args.stage == 'x' and spec['stage'] != 'x': continue
        name = spec['name']; row = summary[name]; statuses[row['status']] += 1
        base = {k: spec[k] for k in ('name','fixture','policy','horizon','rule','mode','attackPoint','attackAt')}
        base['status'] = row['status']
        base['qualityEligible'] = row['status']=='FINISHED'
        if not row.get('result'):
            log = OUT/(name+'.log')
            message = log.read_text(encoding='utf-8', errors='replace') if log.exists() else ''
            checkpoint = ROOT/row['checkpoint'] if row.get('checkpoint') else None
            cp = audit.load(checkpoint) if checkpoint and checkpoint.exists() else None
            failure = dict(case=name, status=row['status'], checkpoint=row.get('checkpoint'),
                error='CHECKPOINT_ACCESS_DENIED' if 'AccessDeniedException' in message else message[:500] or None,
                checkpointAt=cp.get('snapshot',{}).get('at') if cp else None,
                process=row.get('process'))
            failures.append(failure)
            base['failureReason'] = failure['error']
            table.append(base); continue
        exported = row['result']['summary']
        row.update(decisions=exported['decisions'], searchNanos=exported['searchNanos'], exploredNodes=exported['exploredNodes'], pairedDelta=None)
        checked = audit.audit_case(inputs, name, row)
        checked_cases[name]=checked
        audits[name] = {k: v for k,v in checked.items() if not k.startswith('_')}
        events = inputs.read(row['eventsFile']); actual = inputs.read(row['actualFile'])
        scenario = inputs.read(row['modelFile'])['scenario']
        recovered = recovery.measure(scenario, events, actual, row['status'])
        milestone = dict(base, originAt=recovered['originAt'])
        for kind, values in recovered['milestones'].items():
            milestone.update({kind+'_'+k: v for k,v in values.items()})
        milestones.append(milestone)
        for interval in recovered['readinessIntervals']:
            intervals.append(dict(case=name, **interval))
        for interval in recovered['conditionIntervals']:
            conditions.append(dict(case=name, qualityEligible=row['status']=='FINISHED',
                **{k:v for k,v in interval.items() if k!='conditions'}, **interval['conditions']))
        runtime = row['result']['measurement']
        base.update(controllerElapsedSeconds=runtime['elapsedNanos']/1e9,
                    threadCpuSeconds=runtime['threadCpuNanos']/1e9 if runtime['threadCpuNanos'] is not None else None,
                    gcCount=runtime['gcCollectionCountDelta'], gcMillis=runtime['gcCollectionTimeMillisDelta'],
                    decisions=row['decisions'], nodes=row['exploredNodes'], searchSeconds=row['searchNanos']/1e9,
                    processStatus=row['process']['processStatus'], observedUntil=actual['at'])
        ds = inputs.read(row['decisionsFile'])
        for index, d in enumerate(ds):
            diag=d.get('searchDiagnostics') or {}; telemetry=diag.get('runtimeTelemetry') or {}
            if d.get('searchStatus') is not None:
                search_rows.append(dict(case=name, decisionIndex=index, at=d['at'],
                    status=d['searchStatus'], stopReason=d.get('searchStopReason'), nodes=d.get('exploredNodes'),
                    elapsedNanos=d.get('searchNanos'), threadCpuNanos=telemetry.get('threadCpuNanos'),
                    gcCount=telemetry.get('gcCollectionCountDelta'), gcMillis=telemetry.get('gcCollectionTimeMillisDelta'),
                    checkpoint=telemetry.get('stoppedAtCheckpoint'), **telemetry.get('phaseElapsedNanos',{})))
        ts=[(d.get('searchDiagnostics') or {}).get('runtimeTelemetry') for d in ds if d.get('searchStatus') is not None]
        cpus=[t['threadCpuNanos'] for t in ts if t and t.get('threadCpuNanos') is not None]
        base['searchCpuSeconds']=sum(cpus)/1e9 if len(cpus)==len(ts) else None
        base['searchCpuAvailableDecisions']=len(cpus)
        base['stopReasons'] = json.dumps(dict(Counter(d['searchStopReason'] for d in ds if d.get('searchStopReason') not in (None,'NONE'))))
        for index,d in enumerate(ds):
            action = d.get('executedAction')
            if action and action['kind'] in ('PREPARE','INSTALL','SWITCH'):
                keyrows.append(dict(case=name, decisionIndex=index, at=d['at'], resumedAt=d['resumedAt'],
                    kind=action['kind'], context=json.dumps(action['contextId']), keys=json.dumps(action['keys']),
                    searchStatus=d.get('searchStatus'), nodes=d.get('exploredNodes'), elapsedNanos=d.get('searchNanos')))
        if row['status'] == 'FINISHED':
            base.update(checked['five'])
            s = checked['security']
            base.update({k:s[k] for k in ('deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes',
                'decryptableAfterBytes','compromisedDeliveredBytes','bytesAfterGroundNotice','bytesAfterSpacecraftNotice',
                'recoveryStatus','detectionToRecovery')})
            for kind in recovery.MILESTONES:
                base[kind+'_seconds'] = recovered['milestones'][kind]['seconds']
                base[kind+'_status'] = recovered['milestones'][kind]['status']
            if spec['rule'] == 'CANONICAL':
                old = source_summary[spec['sourceCase']]
                same_events = events == audit.load(SOURCE/old['eventsFile'])
                same_metrics = row['simulationMetrics'] == old['simulationMetrics'] and row['securityMetrics'] == old['securityMetrics']
                canonical_checks.append({'case':name,'journalEqual':same_events,'metricsEqual':same_metrics})
                assert same_events and same_metrics, 'Canonical SE2 regression: '+name
        table.append(base)
    for spec in manifest['cases']:
        name=spec['name']; control=spec['baselineCase']
        if spec['mode']=='none' or name==control or name not in checked_cases: continue
        a=checked_cases[name]; b=checked_cases.get(control)
        paired=dict(case=name, baselineCase=control, comparable=b is not None and a['status']==b['status']=='FINISHED')
        if paired['comparable']:
            x,y,sx,sy=b['five'],a['five'],b['security'],a['security']
            delta=dict(lostServedContacts=x['servedContacts']-y['servedContacts'],
                lostDeliveredBytes=sx['deliveredBytes']-sy['deliveredBytes'],
                lostUsefulTime=x['usefulSecureTime']-y['usefulSecureTime'],
                extraControlCpu=y['controlCpu']-x['controlCpu'],
                extraManagementBytes=y['managementBytes']-x['managementBytes'], extraCreatedKeys=y['newKeys']-x['newKeys'])
            summary[name]['pairedDelta']=delta; paired.update(delta)
        if b is not None:
            paired['unchangedPreNoticeDecisions']=audit.pair_checks(name,summary[name],a,b)
        control_pairs.append(paired)
    probes, sets = [], []
    for spec in manifest['mechanismProbes']:
        path = OUT/'probes'/f'index{spec["index"]}-H{spec["horizon"]}'
        if not (path/'result.json').exists():
            sets.append(dict(spec, status='INCOMPLETE', reason='No final probe result')); continue
        data = audit.load(path/'result.json')
        for rule in ('CANONICAL','CONTINUE_WAIT'):
            rows = [r for r in data['searches'] if r['rule']==rule]
            roots = [r for r in rows if r['kind']=='ROOT']
            root = roots[0]['result'] if roots else None
            complete = root is not None and root['status']=='COMPLETE'
            alts = [r for r in rows if r['kind']=='FIXED_FIRST']
            complete &= len(alts)==len(data['candidates']) and all(a['result']['status']=='COMPLETE' for a in alts)
            ties = []
            for r in alts:
                result = r['result']; action = r['firstAction']
                if result['status']=='COMPLETE': assert result['firstAction']==action and result['bestPlan'][0]==action
                tied = r['provedEqualOptimum']
                if tied: ties.append(r['candidateIndex'])
                probes.append(dict(index=spec['index'], horizon=spec['horizon'], rule=rule, at=data['at'],
                    candidateIndex=r['candidateIndex'], action=json.dumps(action), status=result['status'],
                    tiedToProvedOptimum=tied, nodes=result['exploredNodes'], elapsedNanos=result['elapsedNanos'],
                    **(result['score'] or {})))
            sets.append(dict(index=spec['index'], horizon=spec['horizon'], rule=rule, at=data['at'],
                status='COMPLETE' if complete else 'INCOMPLETE', candidateCount=len(data['candidates']), evaluated=len(alts),
                optimalCandidateIndices=ties, optimalActions=[data['candidates'][i] for i in ties],
                optimum=root['score'] if root and root['status']=='COMPLETE' else None,
                chosen=root['firstAction'] if root and root['status']=='COMPLETE' else None))
    oracle_checks=[]
    for h in (1,2):
        for lazy in (False,True):
            key=f'X-original-H{h}-'+('lazy' if lazy else 'canonical')
            path=OUT/'claude-replay'/(key+'.json')
            rule='continue_wait' if lazy else 'canonical'
            name=f'X-{"next" if h==1 else "multi"}-original-detected-{rule}'
            oracle = audit.load(path) if path.exists() else None
            row = summary[name]
            comparable = oracle and oracle['status']=='FINISHED' and row['status']=='FINISHED'
            eq = oracle['metrics']==row['simulationMetrics'] and oracle['security']==row['securityMetrics'] if comparable else None
            actions = [d.get('executedAction') for d in inputs.read(row['decisionsFile'])] if comparable else None
            action_equal = actions == oracle['actions'] if comparable else None
            oracle_checks.append(dict(case=name, comparable=bool(comparable), metricsEqual=eq, actionsEqual=action_equal))
            if comparable: assert eq and action_equal, 'Claude disagreement: '+name
    out = DOC/'tables'; out.mkdir(parents=True, exist_ok=True)
    paired=[]
    by_cell={(r['fixture'],r['attackPoint'],r['mode'],r['rule'],r['policy']):r for r in table}
    scalar=('servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys',
        'deliveredBytes','confidentialDeliveredBytes','decryptableBeforeBytes','decryptableAfterBytes',
        'spacecraft_notice_seconds','independent_pair_seconds','independent_sa_seconds','independent_frame_seconds')
    for key, next_row in by_cell.items():
        fixture,point,mode,rule,policy=key
        if policy!='next': continue
        multi=by_cell.get((fixture,point,mode,rule,'multi'))
        complete=next_row['status']=='FINISHED' and multi is not None and multi['status']=='FINISHED'
        delta=dict(fixture=fixture,attackPoint=point,mode=mode,rule=rule,comparable=complete,
                   nextStatus=next_row['status'],multiStatus=multi['status'] if multi else 'NOT_RUN')
        for field in scalar:
            delta['multiMinusNext_'+field]=multi[field]-next_row[field] if complete and multi.get(field) is not None and next_row.get(field) is not None else None
        paired.append(delta)
    for file, rows in [('four-combinations.csv',table), ('recovery-milestones.csv',milestones),
        ('readiness-intervals.csv',intervals), ('key-decisions.csv',keyrows), ('fixed-first-searches.csv',probes),
        ('search-decisions.csv',search_rows), ('overlapping-conditions.csv',conditions), ('own-control-differences.csv',control_pairs)]:
        csv(out/file, rows)
    csv(out/'paired-horizon-differences.csv',paired)
    write(out/'optimal-first-action-sets.json', sets)
    write(out/'failed-attempts.json', failures)
    write(out/'independent-audit.json', {'audit':'PASS','cases':audits,'fileHashes':inputs.hashes,
        'auditHelperSha256':audit.sha(Path(audit.__file__)), 'recoveryHelperSha256':audit.sha(Path(recovery.__file__))})
    result={'status':'PASS', 'stage':args.stage, 'coverage':dict(statuses), 'canonicalChecks':canonical_checks,
            'claudeOriginalChecks':oracle_checks, 'optimalSetStatuses':dict(Counter(s['status'] for s in sets)),
            'summarySha256':audit.sha(OUT/'summary.json'), 'analysisSha256':audit.sha(Path(__file__)),
            'manifestSha256':audit.sha(OUT/'manifest.json')}
    if args.stage=='x':
        if (DOC/'x-intermediate.json').exists(): raise ValueError('Preserve previous X checkpoint')
        write(DOC/'x-intermediate.json', result)
        # Preserve exact intermediate tables before extension rewrites the final aggregate.
        import shutil
        intermediate=OUT/'x-intermediate'; intermediate.mkdir()
        for file in out.iterdir():
            if file.is_file(): shutil.copyfile(file, intermediate/file.name)
    else: write(DOC/'validation.json', result)
    print(json.dumps(result,indent=2))

if __name__=='__main__': main()
