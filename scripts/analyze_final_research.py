"""Final campaign: independent event accounting, complete-case comparisons, no zero imputation."""
import collections, csv, hashlib, json
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as a
from analyze_research_validation import audit, csv_write
from analyze_pc2 import checkpoint_prefix, result_key
from run_final_research import ROOT, OUT, load, save, sha, relative

DOC=ROOT/'docs/final-research'
FIVE=['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys']

def rank(r):
    return (-D(str(r['servedContacts'])),-D(str(r['usefulSecureTime'])),
            D(str(r['controlCpu'])),D(str(r['managementBytes'])),D(str(r['newKeys'])))

def delivery_totals(checked, status):
    """Project audited complete frames into final directional totals, never a prefix score."""
    if status!='FINISHED':return dict(deliveryStatus='UNAVAILABLE')
    contacts=checked['confidential']['observedPrefix']['contacts']
    frames=checked['confidential']['frames']
    totals=dict(deliveryStatus='FINAL')
    for direction in ['Uplink','Downlink']:
        field=direction.lower()+'Bytes'
        delivered=[f for f in frames if f['direction']==direction.upper() and f['delivered']]
        totals['delivered'+direction+'Bytes']=sum(c[field] for c in contacts)
        totals['delivered'+direction+'Frames']=len(delivered)
        totals['confidential'+direction+'Bytes']=sum(c['confidential'+direction+'Bytes'] for c in contacts)
        assert totals['delivered'+direction+'Bytes']==sum(f['frameBytes'] for f in delivered)
        assert totals['confidential'+direction+'Bytes']==sum(f['frameBytes'] for f in delivered if f['confidential'])
    assert totals['confidentialUplinkBytes']+totals['confidentialDownlinkBytes']==checked['confidential']['final']['confidentialDeliveredBytes']
    return totals


def main():
    protocol=load(OUT/'protocol.json'); raw_bytes=(OUT/'summary.json').read_bytes();raw=json.loads(raw_bytes)
    assert sha(ROOT/protocol['jar'])==protocol['jarSha256']
    assert len({r['id'] for r in protocol['cases']})==len(protocol['cases'])
    assert len({r['id'] for r in raw})==len(raw),'Duplicate attempt ID'
    attempts={r['id']:r for r in raw}
    assert set(attempts)<={r['id'] for r in protocol['cases']},'Unregistered attempt'
    rows=[]; evidence={}; stopped_evidence={}; hashes={}; proofs=0
    for case in protocol['cases']:
        row=dict(case); dest=OUT/'runs'/case['id']; attempt=attempts.get(case['id'])
        if not attempt:
            rows.append(dict(**row,status='NOT_RUN',fiveStatus='UNAVAILABLE'));continue
        row.update(status=attempt['status'],processSeconds=D(attempt['process']['processElapsedNanos'])/10**9)
        for key,value in case.items():assert attempt[key]==value,(case['id'],key)
        assert sha(ROOT/case['model'])==case['modelSha256']
        invocation=load(dest/'invocation.json')
        assert invocation['protocolSha256']==sha(OUT/'protocol.json')
        for name in ['invocation.json','process.json']:
            hashes[relative(dest/name)]=sha(dest/name)
        scenario=a.load(ROOT/case['model'])['scenario']
        row['contacts']=len(scenario['contacts'])
        row['payloadOnlyCeiling']=sum(
            (D(c['payload']['frameBytes'])/D(c['payload']['bytesPerSecond'])) *
            ((c['payload']['minimumUplinkBytes']+c['payload']['frameBytes']-1)//c['payload']['frameBytes']+
             (c['payload']['minimumDownlinkBytes']+c['payload']['frameBytes']-1)//c['payload']['frameBytes'])
            <=a.instant(c['window']['end'])-a.instant(c['window']['start']) for c in scenario['contacts'])
        if attempt['process']['processStatus']!='EXITED' or not (dest/'result.json').exists():
            row.update(checkpoint_prefix(dest,scenario));row['fiveStatus']='UNAVAILABLE'
            writes_path=dest/'checkpoint-writes.jsonl'
            if writes_path.exists():
                writes=[json.loads(line)['diagnostics'] for line in writes_path.read_text(encoding='utf-8').splitlines()]
                row.update(checkpointWrites=len(writes),checkpointRetries=sum(w['retries'] for w in writes),
                           checkpointSeconds=sum(w['elapsedNanos'] for w in writes)/10**9)
                hashes[relative(writes_path)]=sha(writes_path)
            if (dest/'checkpoint.json').exists():hashes[relative(dest/'checkpoint.json')]=sha(dest/'checkpoint.json')
            stopped_evidence[case['id']]=dict(process=attempt['process'],checkpoint=row.get('checkpoint'),
                prefixFive=row.get('prefixFive'),stoppedAt=row.get('stoppedAt'),
                scope='Saved physical prefix only; no final quality or full journal/selection audit')
            rows.append(row);continue
        result=a.load(dest/'result.json');events=a.load(dest/'actual.events.json');actual=a.load(dest/'actual.actual.json')
        assert result['status']==attempt['status']
        assert result['mode']==case['mode'] and result['horizon']==case['horizon'] and result['tieBreak']==case['rule']
        assert result['physicalTimeAdvancedBySearch']==case['measured']
        flags=result['configuration']; budgets=protocol['budgets']
        expected=dict(mode=case['mode'],model=case['model'],rule=case['rule'],horizon=case['horizon'],
            portfolio=case['portfolio'],nodes=budgets['nodes'],
            **{'search-ms':budgets['searchMilliseconds'],'decision-budget-ms':0 if case['measured'] else budgets['decisionMilliseconds'],
               'controller-s':0,'period-s':case['period'],'latency-ms':0,'host-scale':1 if case['measured'] else 0,
               'deadline-ms':budgets['decisionMilliseconds'],'out':relative(dest)})
        assert flags=={k:str(v) for k,v in expected.items()},case['id']
        checked=audit(scenario,events,actual,result)
        checkpoint=a.load(dest/'checkpoint.json')['snapshot']
        assert checkpoint['journal']==events
        for field in ['at','securityState','pendingOperations','inFlightFrame','uncertainContexts']:
            assert checkpoint[field]==actual[field],(case['id'],field)
        checked['checkpointExportEquality']='PASS'
        if result['status']=='FINISHED':
            assert len({e['contactId'] for e in events if e['type']=='CONTACT_STARTED'})==len(scenario['contacts'])
        row.update(fiveStatus='FINAL' if checked['five'] else 'UNAVAILABLE',**(checked['five'] or {}))
        row.update(controllerSeconds=D(result['measurement']['elapsedNanos'])/10**9,
            threadCpuSeconds=None if result['measurement']['threadCpuNanos'] is None else D(result['measurement']['threadCpuNanos'])/10**9,
            gcMillis=result['measurement']['gcCollectionTimeMillisDelta'],stoppedAt=actual['at'],
            journal=relative(dest/'actual.events.json'),abortedFrames=checked['abortedFrames'],
            failures=json.dumps(checked['failures']),outages=checked['outages'],
            partialOperationCpu=checked['failedOperationCosts']['spacecraftCpu']+checked['failedOperationCosts']['groundCpu'],
            partialManagementBytes=checked['failedOperationCosts']['uplinkBytes']+checked['failedOperationCosts']['downlinkBytes'],
            neverActivatedPreparedPairs=checked['neverActivatedPreparedPairs'],
            noDeliveredUsePreparedPairs=checked['noDeliveredUsePreparedPairs'])
        if checked['confidential']['final']:row.update(checked['confidential']['final'])
        row.update(delivery_totals(checked,result['status']))
        security=checked['security']
        if security:
            for key in ['deliveredBytes','emittedBytes','decryptableBeforeBytes','decryptableAfterBytes',
                        'compromisedDeliveredBytes','recoveryStatus','groundNoticeAt',
                        'bytesAfterGroundNotice','bytesAfterSpacecraftNotice']:
                row[key]=security.get(key)
        for key,value in checked['recovery']['milestones'].items():
            row[key+'Status']=value['status'];row[key+'Seconds']=value.get('seconds')
        row['interruptedOperations']=json.dumps(dict(collections.Counter(e['action'] for e in events if e['type']=='STEP_INTERRUPTED')))
        searches=load(dest/'practical-searches.json');portfolio=load(dest/'practical-portfolio.json')
        row.update(searches=len(searches),incompleteSearches=sum(s['search']['status']=='INCOMPLETE' for s in searches),
                   nodes=sum(s['search']['exploredNodes'] for s in searches),
                   searchSeconds=sum(s['search']['elapsedNanos'] for s in searches)/10**9,
                   searchStopReasons=json.dumps(dict(collections.Counter(s['search']['diagnostics']['stopReason'] for s in searches))))
        cpus=[s['search']['diagnostics']['runtimeTelemetry']['threadCpuNanos'] for s in searches]
        row['searchThreadCpuSeconds']=sum(cpus)/10**9 if all(v is not None for v in cpus) else None
        row['portfolioSources']=json.dumps(dict(collections.Counter(d['source'] for d in portfolio)))
        stopped=load(dest/'practical-portfolio-stops.json')
        for phase in ['seed','suffix','search','selection']:
            row[phase+'PortfolioSeconds']=sum(d[phase+'Nanos'] for d in portfolio)/10**9
        for phase in ['seed','suffix','search','prediction']:
            row[phase+'Transitions']=sum(d[phase+'Transitions'] for d in portfolio)+sum(d[phase+'Transitions'] for d in stopped)
        row['interruptedPortfolioSeconds']=sum(d['elapsedNanos'] for d in stopped)/10**9
        row['portfolioStops']=json.dumps(dict(collections.Counter(d['reason'] for d in stopped)))
        for decision in portfolio:
            selected=decision['selected']
            if selected is None:continue
            candidates=decision['candidates'];assert candidates
            assert result_key(selected,case['rule'])==min(result_key(c['result'],case['rule']) for c in candidates)
            proofs+=1
        timings=load(dest/('latency-decisions.json' if case['measured'] else 'decision-timings.json'))
        row['decisions']=len(timings)
        row['deadlineMisses']=sum(t['deadlineMiss'] for t in timings)
        if case['measured']:
            row['decisionHostSeconds']=sum(t['hostNanos'] for t in timings)/10**9
            row['delayDispositions']=json.dumps(dict(collections.Counter(t['disposition'] for t in timings)))
        writes=[json.loads(line)['diagnostics'] for line in (dest/'checkpoint-writes.jsonl').read_text(encoding='utf-8').splitlines()]
        row.update(checkpointWrites=len(writes),checkpointRetries=sum(w['retries'] for w in writes),
                   checkpointSeconds=sum(w['elapsedNanos'] for w in writes)/10**9)
        evidence[case['id']]=checked
        for name in ['actual.events.json','actual.actual.json','result.json','checkpoint.json','invocation.json']:
            hashes[relative(dest/name)]=sha(dest/name)
        rows.append(row)
    groups=collections.defaultdict(dict)
    for r in rows:
        if r['policy'] in ['Next','Multi']:
            groups[(r['block'],r['model'],r['rule'],r['measured'])][r['policy']]=r
    pairs=[];unpaired=[]
    for key,g in groups.items():
        if len(g)!=2:
            unpaired.extend(r['id'] for r in g.values());continue
        n,m=g['Next'],g['Multi'];complete=all(r['status']=='FINISHED' for r in [n,m])
        p=dict(block=key[0],model=key[1],rule=key[2],measured=key[3],nextId=n['id'],multiId=m['id'],
               status='COMPARABLE' if complete else 'INCOMPLETE_PAIR')
        if complete:
            p['objectiveOutcome']='MULTI_BETTER' if rank(m)<rank(n) else 'NEXT_BETTER' if rank(n)<rank(m) else 'EQUAL'
            for metric in FIVE+['controllerSeconds','confidentialServedContacts','confidentialDeliveredBytes']:
                p['deltaMultiMinusNext_'+metric]=D(str(m[metric]))-D(str(n[metric]))
            p['firstDifferentCriterion']=next((metric for metric in FIVE if D(str(m[metric]))!=D(str(n[metric]))),'NONE')
        pairs.append(p)
    # The preregistered H3-only factor has its H2 reference in the primary block.
    factors=[]
    for row in rows:
        if row['block']!='sensitivity':continue
        baseline=next(r for r in rows if r['block']=='core' and r['scenario']==row['scenario']
                      and r['load']==row['load'] and r['policy']==row['policy'])
        f=dict(case=row['id'],baseline=baseline['id'],factor=row['metadata']['modelKey'],
               horizon=row['horizon'],baselineHorizon=baseline['horizon'],
               status='COMPARABLE' if row['status']==baseline['status']=='FINISHED' else 'INCOMPLETE_PAIR')
        if f['status']=='COMPARABLE':
            for metric in FIVE+['controllerSeconds']:
                f['deltaVersusBaseline_'+metric]=D(str(row[metric]))-D(str(baseline[metric]))
        factors.append(f)
    save(OUT/'audit.json',dict(status='PASS',runs=evidence,stoppedRuns=stopped_evidence,
        sameRootComparatorChecks=proofs,inputHashes=hashes))
    save(OUT/'tables.json',rows);save(OUT/'pairs.json',pairs)
    save(OUT/'factor-comparisons.json',factors)
    csv_write(DOC/'runs.csv',rows);csv_write(DOC/'pairs.csv',pairs)
    csv_write(DOC/'factor-comparisons.csv',factors)
    validation=dict(status='PASS',registered=len(protocol['cases']),attempted=len(attempts),
        audited=len(evidence),statuses=dict(collections.Counter(r['status'] for r in rows)),
        pairOutcomes=dict(collections.Counter(p.get('objectiveOutcome',p['status']) for p in pairs)),
        sameRootComparatorChecks=proofs,protocolSha256=sha(OUT/'protocol.json'),
        tableSha256=sha(OUT/'tables.json'),auditSha256=sha(OUT/'audit.json'),
        summarySha256=hashlib.sha256(raw_bytes).hexdigest(),
        unpairedHorizonCells=unpaired,unpairedExplanation='The H3-only sensitivity is compared with primary H2 in factor-comparisons, never silently treated as H1/H2 pair.')
    save(DOC/'validation.json',validation);print(json.dumps(validation,indent=2))

if __name__=='__main__':main()
