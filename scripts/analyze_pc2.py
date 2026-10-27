"""Independent journal and same-root comparator checks for the registered PC2 series."""
import collections, csv, json, os
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as a
from analyze_research_validation import audit
from run_pc2 import ROOT,OUT,PC1,load,save,sha,relative

def score_key(e):
    return (-e['servedContacts'],-D(str(e['usefulSecureTime'])),D(str(e['controlCpu'])),e['managementBytes'],e['newKeys'])
def action_key(x,rule):
    ranks={n:i for i,n in enumerate(['PREPARE','INSTALL','SWITCH','OPEN','REUSE','CONTINUE','WAIT'])}
    context=x['contextId'];keys=x['keys'];until=a.instant(x['until'])
    standard=(ranks[x['kind']],a.instant(x['at']),'' if context is None else context['groundStationId'],
              () if context is None else (context['satelliteId'],context['groundStationId']),
              '' if keys is None else keys['uplink'],'' if keys is None else keys['downlink'],until,x['contactId'] or '')
    return ((0 if x['kind']=='CONTINUE' else 1 if x['kind']=='WAIT' else 2),until)+standard if rule=='CONTINUE_WAIT' else standard
def result_key(x,rule):return score_key(x['evaluation']),tuple(action_key(action,rule) for action in x['plan'])

def checkpoint_prefix(dest,scenario):
    """Recover only observed progress if watchdog prevented final exports."""
    path=dest/'checkpoint.json'
    if not path.exists():return dict(five=None,prefixFive=None,diagnostics='UNAVAILABLE: no checkpoint/export')
    snap=a.load(path)['snapshot'];events=snap['journal'];costs=a.zero_usage();useful=D(0);created=0;progress=collections.defaultdict(lambda:[0,0])
    for i,e in enumerate(events):
        assert e['sequence']==i
        for k in costs:costs[k]+=e['usage'][k]
        useful+=e['usefulTime'];created+=e['createdKeys']
        progress[e['contactId']][0]+=e['uplinkPayloadBytes'];progress[e['contactId']][1]+=e['downlinkPayloadBytes']
    accrued=dict(costs)
    for pending in snap['pendingOperations']:
        consumed=a.consumed(scenario['costs']['operations'][pending['steps'][pending['stepIndex']]],a.instant(snap['at'])-a.instant(pending['stepStartedAt']))
        for k in accrued:accrued[k]+=consumed[k]
    served=sum(progress[c['window']['id']][0]>=c['payload']['minimumUplinkBytes'] and progress[c['window']['id']][1]>=c['payload']['minimumDownlinkBytes'] for c in scenario['contacts'])
    return dict(five=None,prefixFive=dict(servedContacts=served,usefulSecureTime=useful,
        controlCpu=accrued['spacecraftCpu']+accrued['groundCpu'],managementBytes=accrued['uplinkBytes']+accrued['downlinkBytes'],newKeys=created),
        stoppedAt=snap['at'],contactsStarted=len({e['contactId'] for e in events if e['type']=='CONTACT_STARTED'}),
        checkpoint=relative(path),checkpointSha256=sha(path),diagnostics='Checkpoint prefix only; final metrics and in-memory search ledgers unavailable after process stop')

def main():
    series=os.environ.get('PC2_SERIES','iss46-v1');folder=OUT/series;protocol=load(folder/'protocol.json');scenario=a.load(ROOT/protocol['model']['file'])['scenario']
    rows=[];missed=[];proofs=0
    for case in load(folder/'summary.json'):
        dest=folder/'runs'/case['id'];row=dict(**case,series=series)
        if not (dest/'result.json').exists() or case['process']['processStatus']!='EXITED' or case['status']=='EXPORT_INCOMPLETE':
            row.update(checkpoint_prefix(dest,scenario));rows.append(row);continue
        result=a.load(dest/'result.json');events=a.load(dest/'actual.events.json');actual=a.load(dest/'actual.actual.json')
        checked=audit(scenario,events,actual,result)
        saved=a.load(dest/'checkpoint.json')['snapshot'];assert saved['journal']==events
        for field in ['at','securityState','pendingOperations','inFlightFrame','uncertainContexts']:assert saved[field]==actual[field]
        started={e['contactId'] for e in events if e['type']=='CONTACT_STARTED'}
        if case['status']=='FINISHED':assert len(started)==46 and actual['at']==scenario['end']
        portfolio=load(dest/'practical-portfolio.json');searches=load(dest/'practical-searches.json');stops=load(dest/'practical-portfolio-stops.json')
        for decision in portfolio:
            if decision['selected'] is not None:
                candidates=decision['candidates'];assert candidates
                assert result_key(decision['selected'],case['rule'])==min(result_key(c['result'],case['rule']) for c in candidates)
                assert decision['selected'] in [c['result'] for c in candidates]
                proofs+=1
        timings=load(dest/'latency-decisions.json' if case['measured'] else dest/'decision-timings.json')
        elapsed_wait_targets=[]
        if case['measured']:
            for t in timings:
                proposed=t['selection']['action']
                wake=min([a.instant(proposed['until'])]+[a.instant(w) for w in t['origin']['wakeUps']]) if proposed is not None and proposed['kind']=='WAIT' else None
                if wake is not None and a.instant(t['availableAt'])>=wake:
                    elapsed_wait_targets.append(dict(originAt=t['origin']['at'],originalWait=proposed,availableAt=t['availableAt'],
                        effectiveWakeEpochSeconds=wake,currentContact=t['origin']['currentContact'],disposition=t['disposition'],hostNanos=t['hostNanos']))
        save(dest/'elapsed-wait-targets.json',elapsed_wait_targets)
        created={k for e in events if e['type']=='STEP_COMPLETED' and e['action']=='PREPARE' for k in e['keyIds'].values()}
        used=set();ul=dl=0;progress=collections.defaultdict(lambda:[0,0])
        for e in events:
            if e['type']!='PAYLOAD_TRANSFERRED':continue
            ul+=e['uplinkPayloadBytes'];dl+=e['downlinkPayloadBytes'];progress[e['contactId']][0]+=e['uplinkPayloadBytes'];progress[e['contactId']][1]+=e['downlinkPayloadBytes']
            if e['uplinkFrames']:used.add(e['keyIds']['uplink'])
            if e['downlinkFrames']:used.add(e['keyIds']['downlink'])
        keys={v['id']:v for v in actual['securityState']['keys'].values()}
        unused=created-used;discarded={k for k in unused if keys[k]['status'] in ['DEACTIVATED','DESTROYED']}
        trace=load(dest/'actual.full-trace.json')['decisions']
        for expired in elapsed_wait_targets:
            matches=[(i,d) for i,d in enumerate(trace) if d['observation']['at']==expired['availableAt']]
            expired['executed']=[dict(index=i,action=d['executedAction'],resumedAt=d['resumedAt']) for i,d in matches]
        save(dest/'elapsed-wait-targets.json',elapsed_wait_targets)
        for c in scenario['contacts']:
            w=c['window'];p=c['payload'];up,down=progress[w['id']]
            if up>=p['minimumUplinkBytes'] and down>=p['minimumDownlinkBytes']:continue
            lo,hi=a.instant(w['start']),a.instant(w['end'])
            ev=[e for e in events if e['contactId']==w['id']]
            ds=[d for d in trace if lo<=a.instant(d['observation']['at'])<hi]
            ops=collections.Counter(e['action'] for e in ev if e['type']=='STEP_STARTED')
            actions=collections.Counter(d['executedAction']['kind'] for d in ds if d['executedAction'])
            retargets=[x for x in elapsed_wait_targets if x['currentContact'] and x['currentContact']['window']['id']==w['id']
                and a.instant(x['availableAt'])<hi and any(y['action'] and y['action']['kind']=='WAIT'
                    and a.instant(y['resumedAt'])>=hi for y in x['executed'])]
            entry=dict(series=series,run=case['id'],contact=w['id'],window=w,uplink=up,downlink=down,requiredUplink=p['minimumUplinkBytes'],requiredDownlink=p['minimumDownlinkBytes'],
                decisionActions=dict(actions),managementStarts=dict(ops),firstDecision=ds[0] if ds else None,
                failureCodes=[e['failureCode'] for e in ev if e['type']=='ACTION_FAILED'],
                elapsedWaitRetargets=retargets,
                description='Delivered frames below directional thresholds; see exact decision/action evidence. No counterfactual serviceability claim.',
                cause='ELAPSED_WAIT_RETARGETED_TO_CONTACT_END' if retargets else 'NO_FRAMES_NO_ONLINE_SETUP' if up+down==0 and not any(k in ops for k in ['INSTALL','SWITCH','OPEN','REUSE']) else 'SETUP_OR_DELIVERY_BELOW_THRESHOLD')
            missed.append(entry)
        cpus=[d['runtime']['threadCpuNanos'] for d in portfolio]
        searchcpus=[d['search']['diagnostics']['runtimeTelemetry']['threadCpuNanos'] for d in searches]
        row.update(five=checked['five'],prefixFive=checked['prefixFive'],stoppedAt=actual['at'],audit='PASS',contactsStarted=len(started),uplinkBytes=ul,downlinkBytes=dl,
            usedCreatedKeys=len(created&used),unusedCreatedKeys=len(unused),discardedUnusedKeys=len(discarded),keyStatuses=dict(collections.Counter(k['status'] for k in keys.values())),
            sources=dict(collections.Counter(d['source'] for d in portfolio)),retention=dict(collections.Counter(d['retention'] for d in portfolio)),seedStatus=dict(collections.Counter(d['seedStatus'] for d in portfolio)),
            certifiedSuffixes=result['computation']['suffixReuses'],searches=len(searches),searchNodes=sum(s['search']['exploredNodes'] for s in searches),
            searchStopReasons=dict(collections.Counter(s['search']['diagnostics']['stopReason'] for s in searches)),
            seedSeconds=sum(d['seedNanos'] for d in portfolio)/1e9,suffixSeconds=sum(d['suffixNanos'] for d in portfolio)/1e9,
            searchSeconds=sum(s['search']['elapsedNanos'] for s in searches)/1e9,
            selectionSeconds=sum(d['selectionNanos'] for d in portfolio)/1e9,
            portfolioThreadCpuSeconds=sum(cpus)/1e9 if all(x is not None for x in cpus) else None,
            searchThreadCpuSeconds=sum(searchcpus)/1e9 if all(x is not None for x in searchcpus) else None,
            transitions=sum(sum(d[k] for k in ['seedTransitions','suffixTransitions','searchTransitions','predictionTransitions']) for d in portfolio),
            stoppedPortfolioWork=stops,controllerComputation=result['computation'],wholeRunTelemetry=result['measurement'],
            elapsedWaitTargets=len(elapsed_wait_targets),
            deadlineMisses=sum(t['deadlineMiss'] for t in timings),maxDecisionSeconds=max((t['hostNanos' if case['measured'] else 'elapsedNanos']/1e9 for t in timings),default=0),
            measuredDispositions=dict(collections.Counter(t['disposition'] for t in timings)),journal=relative(dest/'actual.events.json'))
        save(dest/'journal-audit.json',checked);rows.append(row)
    save(folder/'analysis.json',rows);save(folder/'missed-contacts.json',missed)
    save(folder/'comparison-audit.json',dict(sameRootFullComparatorChecks=proofs,failedChecks=0,scope='Every completed portfolio selection, including full tie-break. Feasibility separately verified by branch execution/tests and actual journal audit.'))
    allrows=[]
    for name in ['iss46-v1','measured-v4','measured-v4b']:
        if (OUT/name/'analysis.json').exists():allrows+=load(OUT/name/'analysis.json')
    flat=[]
    for r in allrows:
        flat.append(dict(series=r['series'],id=r['id'],status=r['status'],mode=r['mode'],measured=r['measured'],rule=r['rule'],horizon=r['horizon'],variant=r['portfolio'],
            **(r.get('five') or {}),processSeconds=r['process']['processElapsedNanos']/1e9,
            prefixOnly=json.dumps(r.get('prefixFive')) if r.get('five') is None else '',
            **{k:r.get(k) for k in ['contactsStarted','uplinkBytes','downlinkBytes','usedCreatedKeys','unusedCreatedKeys','discardedUnusedKeys','searches','searchNodes','seedSeconds','suffixSeconds','searchSeconds','portfolioThreadCpuSeconds','transitions','deadlineMisses','elapsedWaitTargets']}))
    doc=ROOT/'docs/practical-controller/pc2';doc.mkdir(exist_ok=True)
    with (doc/'runs.csv').open('w',encoding='utf-8',newline='') as f:
        fields=list(dict.fromkeys(k for r in flat for k in r));w=csv.DictWriter(f,fields);w.writeheader();w.writerows(flat)
    print(json.dumps([dict(id=r['id'],status=r['status'],five=r.get('five'),misses=r.get('deadlineMisses')) for r in rows],default=str,indent=2))

if __name__=='__main__':main()
