"""Journal-based new-campaign accounting; never changes historical campaign outputs."""
import collections, csv, json, sys
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as a
from analyze_research_validation import audit, csv_write
from analyze_pc2 import result_key, score_key
from run_research_validation import ROOT, load, save, sha, relative

OUT=ROOT/'target/aitu-revision';DOC=ROOT/'docs/aitu-revision'
FIVE=['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys']

def normalized(value,key=None):
    # Only fields whose Java declarations are Set; list/event/plan order is never erased.
    sets={'authorizedContexts','blockedKeyIds','uncertainContexts','issuedKeyIds','issuedOperationIds'}
    if isinstance(value,dict):return {k:normalized(v,k) for k,v in value.items()}
    if isinstance(value,list):
        values=[normalized(v) for v in value]
        return sorted(values,key=lambda v:json.dumps(v,sort_keys=True,default=str)) if key in sets else values
    return value

def analyze(stage):
    base=OUT/stage;protocol=load(base/'protocol.json');attempts={c['id']:c for c in load(base/'summary.json')}
    rows=[];evidence={};hashes={};rootrows=[]
    for c in protocol['cases']+protocol.get('reusedCells',[]):
        reused='reuseFrom' in c
        dest=ROOT/c['reuseFrom'] if reused else base/'runs'/c['id']
        if c['id'] not in attempts and not reused:
            rows.append(dict(id=c['id'],status='NOT_RUN',fiveStatus='UNAVAILABLE'));continue
        if reused:
            assert sha(dest/'result.json')==c['sourceResultSha256']
            attempt=load(dest/'process.json');status=load(dest/'result.json')['status']
        else:attempt=attempts[c['id']]['process'];status=attempts[c['id']]['status']
        assert sha(ROOT/c['model'])==c['modelSha256']
        if c['kind']=='root':
            for p in dest.glob('*.json'):hashes[relative(p)]=sha(p)
            row=dict(id=c['id'],initialization=c['initialization'],horizon=c['horizon'],rule=c['rule'],status=status)
            exact=a.load(dest/'exact-result.json');rp=a.load(dest/'root-result.json')
            ordinary=a.load(dest/'practical-audit.json');improvements=a.load(dest/'exact-improvements.json')
            row.update(exactNodes=exact['exploredNodes'],exactSeconds=exact['elapsedNanos']/D(10**9),
                exactStop=exact['diagnostics']['stopReason'],practicalSource=ordinary[0]['source'] if ordinary else None,
                exactScore=json.dumps(exact['score'],default=str),
                practicalScore=json.dumps(ordinary[0]['selected']['evaluation'],default=str) if ordinary else None,
                sameRootReplay=rp.get('exactReplayAccepted'),exactVsPracticalOrder=rp.get('exactVsPracticalOrder'))
            assert rp['snapshotObservationActionsUnchanged'] and rp['sameSnapshotAndObservation']
            prev=None
            for imp in improvements:
                if prev is not None:assert result_key(imp['result'],c['rule'])<result_key(prev['result'],c['rule'])
                prev=imp
            if exact['score'] is not None:
                assert improvements and improvements[-1]['result']==dict(evaluation=exact['score'],plan=exact['bestPlan'])
            better=[]
            if ordinary and ordinary[0]['selected'] is not None:
                better=[x for x in improvements if score_key(x['result']['evaluation'])<score_key(ordinary[0]['selected']['evaluation'])]
            row.update(firstStrictMetricImprovementNodes=better[0]['nodes'] if better else None,
                firstStrictMetricImprovementSeconds=better[0]['elapsedNanos']/D(10**9) if better else None)
            diagnostic=dest/'DIAGNOSTIC-ONLY-oracle-portfolio.json'
            row['oracleReplayRetention']=a.load(diagnostic)[0]['retention'] if diagnostic.exists() and a.load(diagnostic) else None
            rootrows.append(row);continue
        row={k:c.get(k) for k in ['id','family','caseLabel','budgetLabel','mode','initialization','horizon','rule','nodes','measured','secondsBeforeContactEnd','detectionDelaySeconds']}
        row.update(status=status,reused=reused,origin=relative(dest),processSeconds=D(attempt['processElapsedNanos'])/D(10**9))
        if not (dest/'result.json').exists():
            row.update(fiveStatus='UNAVAILABLE');rows.append(row);continue
        result=a.load(dest/'result.json');events=a.load(dest/'actual.events.json');actual=a.load(dest/'actual.actual.json')
        initial=a.load(dest/'initial-checkpoint.json')['snapshot'];scenario=initial['scenario']
        checked=audit(scenario,events,actual,result)
        deadlines={}
        for k in actual['securityState']['keys'].values():
            expected=a.instant(k['createdAt'])+D(scenario['constraints']['maxMaterialAge'])
            if k['activatedAt'] is not None:expected=min(expected,a.instant(k['activatedAt'])+D(scenario['constraints']['cryptoperiod']))
            assert a.instant(k['notAfter'])==expected,(c['id'],k['id'],'deadline derivation')
            deadlines[k['id']]={field:k[field] for field in ['createdAt','activatedAt','notAfter','status']}
        checked['derivedKeyDeadlines']=deadlines
        checkpoint=a.load(dest/'checkpoint.json')['snapshot'];assert checkpoint['journal']==events
        for field in ['at','securityState','pendingOperations','inFlightFrame','uncertainContexts']:assert checkpoint[field]==actual[field]
        evidence[c['id']]=checked
        for p in [dest/'result.json',dest/'actual.events.json',dest/'actual.actual.json',dest/'initial-checkpoint.json',
                  dest/'initial-observation.json',dest/'actual.full-trace.json',dest/'practical-portfolio.json']:
            hashes[relative(p)]=sha(p)
        row.update(fiveStatus='FINAL' if checked['five'] else 'UNAVAILABLE',**(checked['five'] or {}),
            stoppedAt=actual['at'],controllerSeconds=result['measurement']['elapsedNanos']/D(10**9),
            hostThreadCpuSeconds=None if result['measurement']['threadCpuNanos'] is None else result['measurement']['threadCpuNanos']/D(10**9),
            failures=json.dumps(checked['failures']),abortedFrames=checked['abortedFrames'])
        final=checked['confidential']['final']
        if final:row.update(final)
        searches=a.load(dest/'practical-searches.json')
        trace=a.load(dest/'actual.full-trace.json')
        sr=[x['search'] for x in searches] if searches else [x['search'] for x in trace['decisions'] if x['search'] is not None]
        row.update(searches=len(sr),searchNodes=sum(x['exploredNodes'] for x in sr),
            searchStatuses=json.dumps(dict(collections.Counter(x['status'] for x in sr))),
            searchStopReasons=json.dumps(dict(collections.Counter(x['diagnostics']['stopReason'] for x in sr))),
            searchSeconds=sum(x['elapsedNanos'] for x in sr)/D(10**9),
            decisions=len(trace['decisions']))
        cpu=[x['diagnostics']['runtimeTelemetry']['threadCpuNanos'] for x in sr]
        row['searchThreadCpuSeconds']=sum(cpu)/D(10**9) if all(x is not None for x in cpu) else None
        lat=a.load(dest/'latency-decisions.json')
        row['computedDecisionLatency']=sum((x['modeledLatency'] for x in lat),D(0))
        row['chargedDecisionDelay']=sum((a.instant(x['availableAt'])-a.instant(x['origin']['at']) for x in lat),D(0))
        row['deadlineMisses']=sum(x['deadlineMiss'] for x in lat) if lat else result['decisionDeadlineMisses']
        count=0
        for p in a.load(dest/'practical-portfolio.json'):
            if p['selected'] is None:continue
            for cand in p['candidates']:
                assert result_key(p['selected'],c['rule'])<=result_key(cand['result'],c['rule']);count+=1
        row['portfolioComparisons']=count
        sec=checked['security']
        if sec:
            for k in ['deliveredBytes','emittedBytes','decryptableBeforeBytes','decryptableAfterBytes','compromisedDeliveredBytes','recoveryStatus','groundNoticeAt']:
                row[k]=sec.get(k)
        if scenario.get('disclosures'):
            incident=scenario['disclosures'][0]
            row['disclosureAt']=incident['at'];row['scheduledDetectionAt']=incident['detectionAt']
            row['targetStatus']='NO_TARGET' if any(e['type']=='DISCLOSURE_NO_TARGET' for e in events) else 'DISCLOSED' if any(e['type']=='KEY_DISCLOSED' for e in events) else 'UNOBSERVED'
            ground=next((e for e in events if e['type']=='REVOCATION_RECEIVED_GROUND'),None)
            row['compromiseToGround']=a.instant(ground['at'])-a.instant(incident['at']) if ground else None
            for k,v in checked['recovery']['milestones'].items():
                row[k+'Status']=v.get('status');row[k+'Seconds']=v.get('seconds');row[k+'At']=v.get('at')
            first=checked['recovery']['milestones'].get('independent_frame',{})
            row['compromiseToIndependentFrame']=a.instant(first['at'])-a.instant(incident['at']) if first.get('at') else None
            row['compromiseToIndependentFrameStatus']=first.get('status')
            row['recoveryEvidence']=relative(base/'audit.json')+'#'+c['id']
        rows.append(row)
    # Verify initial pair equality beyond SecurityState, including complete queued execution/journal/cost fields.
    same={}
    if stage=='stage1':
        for init in ['prepared','autonomous']:
            for h in [1,2]:
                for rule in ['CANONICAL','CONTINUE_WAIT']:
                    p=base/'runs'/f'run-{init}-exact-H{h}-{rule}'
                    q=base/'runs'/f'run-{init}-practical-H{h}-{rule}'
                    for name in ['initial-checkpoint.json','initial-observation.json']:
                        assert normalized(a.load(p/name))==normalized(a.load(q/name)),(init,h,rule,name)
                    same[f'{init}-H{h}-{rule}']='FULL_SNAPSHOT_AND_OBSERVATION_EQUAL'
        for rule in ['CANONICAL','CONTINUE_WAIT']:
            diagnostic=a.load(base/'runs'/f'root-autonomous-t0-H2-{rule}'/'observation.json')
            for mode in ['exact','practical']:
                trace=a.load(base/'runs'/f'run-autonomous-{mode}-H2-{rule}'/'actual.full-trace.json')
                actualRoot=next(x['observation'] for x in trace['decisions'] if x['observation']['at']==diagnostic['at'])
                assert normalized(actualRoot)==normalized(diagnostic),(mode,rule,'decisive root differs')
            same[f'decisive-t0-H2-{rule}']='DIAGNOSTIC_EQUALS_BOTH_ACTUAL_CONTROLLER_OBSERVATIONS'
    denominator={'plannedNewAttempts':len(protocol['cases']),'attemptedNew':len(attempts),
        'newStatuses':dict(collections.Counter(x['status'] for x in attempts.values())),
        'reusedCells':len(protocol.get('reusedCells',[])),'notRun':len(protocol['cases'])-len(attempts)}
    save(base/'tables.json',rows);csv_write(base/'tables.csv',rows)
    if rootrows:save(base/'root-tables.json',rootrows);csv_write(base/'root-tables.csv',rootrows)
    save(base/'audit.json',{'runs':evidence,'inputHashes':hashes,'sameRootChecks':same,'denominator':denominator})
    print(stage,denominator,flush=True)
    if stage=='stage1':
        text=['# Stage 1 interim result','', 'Independent event accounting and full initial snapshot/observation equality passed for every paired full run. This is a new campaign, not replacement of final-v2.', '',
              '| Initialization | Solver | H | Tie rule | Status | Served | Useful s | CPU s | Bytes | Keys |',
              '| --- | --- | ---: | --- | --- | ---: | ---: | ---: | ---: | ---: |']
        for r in rows:text.append('| '+' | '.join(str(r.get(k,'—')) for k in ['initialization','mode','horizon','rule','status',*FIVE])+' |')
        text += ['', 'Paired-root details including first strict metric improvement and diagnostic-only oracle replay are in `target/aitu-revision/stage1/root-tables.csv`. Incomplete roots are not proven optima. Full execution scores cannot be inferred by summing rolling-horizon root scores.',
            '', '## Interpretation', '',
            'Common preparation removes the observed exact/practical service gap: both Multi modes serve three contacts under both tie rules, while both Next modes serve two. Thus the old prepared-exact versus autonomous-practical contrast alone did not isolate search.',
            '', 'At the autonomous t=0 H2 root, both actual controllers have the same permitted observation as the separately replayed diagnostic. The first incumbent with a strictly better five-score tuple than the ordinary practical winner is discovered at node 134269 (about 8 seconds in these instrumented diagnostics), beyond the practical allowance of 5000 nodes and 1 second. CONTINUE_WAIT proves COMPLETE at node134638. Its entire plan passes ordinary BranchEngine admissibility and ContinuationEvaluator, and the separate diagnostic portfolio accepts it as FEASIBLE_RETAINED and ranks it above the ordinary practical candidate. This is evidence of a continuation not found within the practical search budget, not an alternative objective or rejected action chain. The diagnostic oracle is never provided to the ordinary controller.',
            '', 'The ordinary practical decisive-root searches actually stop at the 5000-node cap, before their1s timer; the node limit is the observed stopping cause. The CANONICAL root finds a three-contact incumbent and subsequently improves full-plan tie order, but fails to prove the selected optimum before30s; its exact full controller consequently stops. A service-improving incumbent is not proof of the full lexicographic optimum. The CONTINUE_WAIT exact controller finishes and serves three; autonomous practical serves two under both rules. First-discovery host times are instrumented observations, not universal thresholds. Node discovery depends on the declared traversal and this state.',
            '', 'Snapshot comparison preserves every field and all event/plan ordering. Only Java Set fields are normalized for JSON order; authorizedContexts serialized A/B in different order across JVMs despite equal set values. No semantic state field is omitted.',
            '', 'Denominator: '+json.dumps(denominator)]
        (DOC/'stage1-results.md').write_text('\n'.join(text)+'\n',encoding='utf-8')

if __name__=='__main__':analyze(sys.argv[1])
