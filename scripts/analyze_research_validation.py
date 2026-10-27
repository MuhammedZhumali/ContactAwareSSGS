"""Independent R1 journal accounting. Old auditors and historical results stay unchanged."""
import collections, csv, json
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as a
import confidential_service_metrics as confidential
import recovery_metrics_se21 as recovery
import recovery_metrics_r1
from run_research_validation import ROOT, OUT, load, save, sha, relative

DOC=ROOT/'docs/research-audit/results'
SERIES=['baseline','exact','practical','security','disruptions','sensitivity','controls','deadlines']
SERIES += [s+'-v2' for s in SERIES[2:]]
SERIES += ['deadlines-v3']

def audit(scenario,events,actual,result,legacy=False):
    contacts={c['window']['id']:c for c in scenario['contacts']}
    costs=a.zero_usage();useful=D(0);created=0;starts={};frames=[];seen=set();radio=[]
    counters={k['id']:k['nextNonceCounter'] for k in scenario['initialState']['keys'].values()}
    last=a.instant(scenario['start']);down={};outages=[]
    for i,e in enumerate(events):
        at=a.instant(e['at']);assert e['sequence']==i and last<=at<=a.instant(actual['at']);last=at
        for f in a.USAGE: costs[f]+=e['usage'][f]
        useful+=e['usefulTime'];created+=e['createdKeys']
        identity=e['operationId'],e['action']
        if e['type']=='CHANNEL_DOWN': down[e['contactId']]=at
        if e['type']=='CHANNEL_UP':
            if e['contactId'] in down: outages.append((e['contactId'],down.pop(e['contactId']),at))
        if e['type']=='STEP_STARTED':
            assert identity not in starts;starts[identity]=at
            if e['action'] in {'INSTALL','SWITCH','OPEN','REUSE','NOTIFY_REVOCATION'}:
                w=contacts[e['contactId']]['window'];assert a.context(w)==a.context(e['contextId'])
                assert e['contactId'] not in down,'Online management started on unavailable channel'
        if e['type'] in a.TERMINALS:
            begin=starts.pop(identity)
            op=scenario['costs']['operations'][e['action']]
            assert a.consumed(op,at-begin)==e['usage'],'Phase integral mismatch'
            cursor=begin
            for phase in op['phases']:
                end=cursor+D(phase['duration']);until=min(end,at)
                if phase['resource'] in {'UPLINK','DOWNLINK'} and cursor<until: radio.append((cursor,until,e['contactId']))
                cursor=end
        else: assert e['usage']==a.zero_usage()
        if e['type']=='STEP_COMPLETED' and e['action']=='PREPARE':
            assert e['createdKeys']==2
            for k in e['keyIds'].values(): assert k not in counters;counters[k]=0
        else: assert e['createdKeys']==0
        if e['type'] not in {'PAYLOAD_TRANSFERRED','PAYLOAD_ABORTED'}: continue
        f=a.read_frame(e,contacts);identity=f['contactId'],f['from'],f['direction'];assert identity not in seen;seen.add(identity)
        key=actual['securityState']['keys'][f['keyId']]
        assert key['direction']==f['direction'] and a.context(key['contextId'])==a.context(f['contextId'])
        assert a.instant(key['activatedAt'])<=f['from']<=f['until']<=a.deadline(key,scenario)
        if f['delivered']:
            assert key['deactivatedAt'] is None or f['until']<=a.instant(key['deactivatedAt'])
        if f['delivered'] or not legacy:counters[f['keyId']]+=1
        frames.append(f)
    for cid,begin in down.items():outages.append((cid,begin,a.instant(actual['at'])))
    for f in frames:
        assert all(f['contactId']!=cid or f['until']<=begin or f['from']>=end for cid,begin,end in outages),'Frame during outage'
        assert all(f['until']<=begin or f['from']>=end for begin,end,cid in radio),'Frame overlaps management radio'
    for begin,end,cid in radio:
        assert all(cid!=c or end<=lo or begin>=hi for c,lo,hi in outages),'Management during outage'
    for k in actual['securityState']['keys'].values():assert counters[k['id']]==k['nextNonceCounter'],'Nonce conservation '+k['id']
    for context in actual['securityState']['contexts'].values():
        for direction in ['uplink','downlink']:
            sa=context[direction]
            if sa['keyId'] is not None:
                assert sa['nextSequenceNumber']==counters[sa['keyId']],'SA nonce differs from bound key'
    service=confidential.measure(scenario,events,result['status'],actual['at'])
    five=dict(servedContacts=service['observedPrefix']['totals']['servedContacts'],usefulSecureTime=useful,
        controlCpu=costs['spacecraftCpu']+costs['groundCpu'],managementBytes=costs['uplinkBytes']+costs['downlinkBytes'],newKeys=created)
    if result['status']=='FINISHED':
        m=result['simulationMetrics'];assert m==actual['finalMetrics']
        assert m['controlCosts']==costs and m['usefulSecureTime']==useful and m['createdKeys']==created and m['servedContacts']==five['servedContacts']
        assert actual['at']==scenario['end'] and sum(e['type']=='SIMULATION_ENDED' for e in events)==1
    else:
        assert result['simulationMetrics'] is None and result['securityMetrics'] is None
        assert actual['finalMetrics'] is None,'Incomplete execution has final metrics'
        assert not any(e['type']=='SIMULATION_ENDED' for e in events)
        assert actual['partialProgress']['recordedCosts']==costs
        accrued=dict(costs)
        for p in actual['pendingOperations']:
            used=a.consumed(scenario['costs']['operations'][p['steps'][p['stepIndex']]],a.instant(actual['at'])-a.instant(p['stepStartedAt']))
            for field in a.USAGE:accrued[field]+=used[field]
        assert actual['partialProgress']['accruedCosts']==accrued
    security=a.reconstruct_security(scenario,events,frames)
    if result['status']=='FINISHED': assert a.normalize_security(result['securityMetrics'])==security
    rec=(recovery if legacy else recovery_metrics_r1).measure(scenario,events,actual,result['status'])
    prepared=[set(e['keyIds'].values()) for e in events if e['type']=='STEP_COMPLETED' and e['action']=='PREPARE']
    activated={k for e in events if e['type']=='STEP_COMPLETED' and e['action']=='SWITCH' for k in e['keyIds'].values()}
    delivered_keys={frame['keyId'] for frame in frames if frame['delivered']}
    installs=collections.Counter(tuple(sorted(e['keyIds'].values())) for e in events if e['type']=='STEP_STARTED' and e['action']=='INSTALL')
    partial=a.zero_usage()
    for e in events:
        if e['type'] in {'STEP_FAILED','STEP_INTERRUPTED'}:
            for field in a.USAGE:partial[field]+=e['usage'][field]
    return dict(five=five if result['status']=='FINISHED' else None,prefixFive=five,confidential=service,recovery=rec,
        security=security if result['status']=='FINISHED' else None,frames=len(frames),abortedFrames=sum(not f['delivered'] for f in frames),
        failures=dict(collections.Counter(e['failureCode'] for e in events if e['type']=='ACTION_FAILED')),
        outages=len(outages),journalEvents=len(events),nonceAccounting='legacy delivered only' if legacy else 'delivered plus aborted committed frames',
        observedInstallRetries=sum(max(0,n-1) for n in installs.values()),failedOperationCosts=partial,
        neverActivatedPreparedPairs=sum(not (pair & activated) for pair in prepared) if result['status']=='FINISHED' else None,
        noDeliveredUsePreparedPairs=sum(not (pair & delivered_keys) for pair in prepared) if result['status']=='FINISHED' else None)

def csv_write(path,rows):
    keys=list(dict.fromkeys(k for r in rows for k in r))
    with path.open('w',encoding='utf-8',newline='') as f:
        w=csv.DictWriter(f,keys);w.writeheader();w.writerows(rows)

def main():
    DOC.mkdir(parents=True,exist_ok=True);table=[];evidence={};pairs=[];hashes={};statuses={}
    for series in SERIES:
        folder=OUT/series
        if not (folder/'protocol.json').exists():continue
        reg=load(folder/'protocol.json');summary=load(folder/'summary.json') if (folder/'summary.json').exists() else []
        status=collections.Counter(r['status'] for r in summary)
        statuses[series]=dict(planned=len(reg['cases']),attempted=len(summary),statuses=dict(status),notRun=len(reg['cases'])-len(summary),
            supersededBeforeAttempt=(not summary and (OUT/(series+'-v2')/'protocol.json').exists()))
        for row in summary:
            name=row.get('id',row.get('name')); run=folder/'runs'/name
            if row['result'] is None:
                table.append(dict(series=series,case=name,status=row['status'],model=row['model'],mode=row.get('mode','exact'),
                    horizon=row.get('horizon'),rule=row['rule'],fiveStatus='UNAVAILABLE'));continue
            model=ROOT/(row['model'] if series=='baseline' else row['input']['file'])
            scenario=a.load(model)['scenario'];events=a.load(run/'actual.events.json');actual=a.load(run/'actual.actual.json');result=a.load(run/'result.json')
            checked=audit(scenario,events,actual,result,series=='baseline');evidence[series+'/'+name]=checked
            checkpoint=run/'checkpoint.json'
            if checkpoint.exists():
                saved=a.load(checkpoint)['snapshot']
                for field in ['at','securityState','pendingOperations','inFlightFrame','uncertainContexts']:
                    assert saved[field]==actual[field], 'Checkpoint/export mismatch '+field
                assert saved['journal']==events, 'Checkpoint journal mismatch'
                checked['checkpointExportEquality']='PASS; restoration execution tested separately in Java'
                hashes[relative(checkpoint)]=sha(checkpoint)
            for p in [model,run/'actual.events.json',run/'actual.actual.json',run/'result.json']:hashes[relative(p)]=sha(p)
            data=dict(series=series,case=name,model=row['model'],mode=row.get('mode','exact'),horizon=row.get('horizon',1 if row.get('policy')=='next' else 2),
                rule=row['rule'],status=row['status'],contacts=len(scenario['contacts']),journal=relative(run/'actual.events.json'),
                fiveStatus='FINAL' if checked['five'] else 'UNAVAILABLE',**(checked['five'] or {}),
                confidentialServedContacts=checked['confidential']['final']['confidentialServedContacts'] if checked['confidential']['final'] else None,
                confidentialDeliveredBytes=checked['confidential']['final']['confidentialDeliveredBytes'] if checked['confidential']['final'] else None,
                processSeconds=D(row['process']['processElapsedNanos'])/10**9,controllerSeconds=D(result['measurement']['elapsedNanos'])/10**9,
                threadCpuSeconds=None if result['measurement']['threadCpuNanos'] is None else D(result['measurement']['threadCpuNanos'])/10**9,
                gcMillis=result['measurement']['gcCollectionTimeMillisDelta'],failures=json.dumps(checked['failures']),abortedFrames=checked['abortedFrames'])
            data.update(stoppedAt=actual['at'],contactsStarted=sum(e['type']=='CONTACT_STARTED' for e in events))
            data.update(installRetries=checked['observedInstallRetries'],neverActivatedPreparedPairs=checked['neverActivatedPreparedPairs'],
                noDeliveredUsePreparedPairs=checked['noDeliveredUsePreparedPairs'],partialOperationCpu=checked['failedOperationCosts']['spacecraftCpu']+checked['failedOperationCosts']['groundCpu'],
                partialManagementBytes=checked['failedOperationCosts']['uplinkBytes']+checked['failedOperationCosts']['downlinkBytes'])
            searches=load(run/'practical-searches.json') if (run/'practical-searches.json').exists() else []
            trace=load(run/'actual.full-trace.json') if (run/'actual.full-trace.json').exists() else None
            if searches:
                data.update(searches=len(searches),incompleteSearches=sum(x['search']['status']=='INCOMPLETE' for x in searches),
                    fallbackActions=sum(x['disposition']=='SAFE_FALLBACK' for x in searches),incumbentActions=sum(x['disposition']=='FEASIBLE_INCUMBENT' for x in searches),
                    nodes=sum(x['search']['exploredNodes'] for x in searches),maxObservedHeapBytes=max(x['heapUsedBytes'] for x in searches))
                diagnostics=[x['search']['diagnostics']['runtimeTelemetry'] for x in searches]
                data['searchSeconds']=sum(x['search']['elapsedNanos'] for x in searches)/10**9
                cpus=[d['threadCpuNanos'] for d in diagnostics]
                data['searchThreadCpuSeconds']=sum(cpus)/10**9 if all(c is not None for c in cpus) else None
                data['searchStopReasons']=json.dumps(dict(collections.Counter(x['search']['diagnostics']['stopReason'] for x in searches)))
                data['lastSearchStopReason']=searches[-1]['search']['diagnostics']['stopReason']
            else:
                data.update(searches=result['computation']['searches'],nodes=result['summary']['exploredNodes'],incompleteSearches=int(row['status']=='SEARCH_INCOMPLETE'))
            data['suffixReuses']=result['computation']['suffixReuses']
            data['feasibleSuffixReuses']=result.get('feasibleSuffixReuses',0)
            writes=run/'checkpoint-writes.jsonl'
            if writes.exists():
                entries=[json.loads(line)['diagnostics'] for line in writes.read_text(encoding='utf-8').splitlines()]
                data.update(checkpointWrites=len(entries),checkpointSeconds=sum(e['elapsedNanos'] for e in entries)/10**9,
                    checkpointRetries=sum(e['retries'] for e in entries),checkpointMaxSeconds=max(e['elapsedNanos'] for e in entries)/10**9)
            delays=load(run/'latency-decisions.json') if (run/'latency-decisions.json').exists() else []
            data['deadlineMisses']=sum(d['deadlineMiss'] for d in delays)
            data['legacyComputationSummaryAvailable']=not bool(delays)
            for k,v in checked['recovery']['milestones'].items():
                data[k+'Status']=v['status'];data[k+'Seconds']=v.get('seconds')
            sec=checked['security']
            if sec:
                for key in ['decryptableBeforeBytes','decryptableAfterBytes','compromisedDeliveredBytes','recoveryStatus','groundNoticeAt',
                            'bytesAfterGroundNotice','bytesAfterSpacecraftNotice']:data[key]=sec.get(key)
            table.append(data)
    for series in [s for s in SERIES if s!='baseline' and not s.startswith('controls')]:
        if series=='deadlines-v2' and (OUT/'deadlines-v3/protocol.json').exists():continue # Technical failed attempt remains in runs/matrix, not duplicated as a selected pair.
        rows=[r for r in table if r['series']==series and r.get('horizon') in [1,2] and r.get('mode') in ['exact','practical']]
        grouped=collections.defaultdict(dict)
        for r in rows:
            # latency profiles must remain separate; the registration order pairs 50/500 ms by H.
            extra=''
            if series.startswith('deadlines'):extra=str(load(OUT/series/'protocol.json')['cases'][int(r['case'].split('-')[0])-1]['latency_ms'])
            grouped[(r['model'],r['rule'],extra)][r['horizon']]=r
        for (model,rule,extra),p in grouped.items():
            complete=len(p)==2 and all(r['status']=='FINISHED' for r in p.values())
            q=dict(series=series,model=model,rule=rule,latencyMs=extra,status='COMPARABLE' if complete else 'INCOMPLETE_PAIR',
                   nextCase=p.get(1,{}).get('case'),multiCase=p.get(2,{}).get('case'))
            if complete:
                for k in ['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys','confidentialServedContacts','confidentialDeliveredBytes']:
                    q['deltaMultiMinusNext_'+k]=p[2][k]-p[1][k]
            pairs.append(q)
    save(OUT/'audit.json',dict(status='PASS',runs=evidence,inputHashes=hashes));save(OUT/'tables.json',table);save(OUT/'pairs.json',pairs)
    save(DOC/'matrix.json',statuses);csv_write(DOC/'runs.csv',table);csv_write(DOC/'pairs.csv',pairs)
    save(DOC/'validation.json',dict(status='PASS',auditedRuns=len(evidence),completedPairs=sum(p['status']=='COMPARABLE' for p in pairs),allPairs=len(pairs),series=statuses))
    print('Audited',len(evidence),'new runs;',sum(p['status']=='COMPARABLE' for p in pairs),'/',len(pairs),'pairs')

if __name__=='__main__':main()
