"""Reconstruct v2 tables from saved results, keeping roots and full runs distinct."""
import collections,json
from decimal import Decimal as D
from pathlib import Path
import audit_security_evaluation as a
from analyze_aitu_revision import normalized
from analyze_pc2 import score_key,result_key,checkpoint_prefix
from analyze_research_validation import audit,csv_write
from run_research_validation import ROOT,load,save,sha,relative
BASE=ROOT/'target/aitu-revision-v2';DOC=ROOT/'docs/aitu-revision-v2';OLD=ROOT/'target/aitu-revision'

def first_event(events,predicate):
 for e in events:
  if predicate(e):return {'nodes':e['nodes'],'seconds':D(e['elapsedNanos'])/D(10**9),'evaluation':e['result']['evaluation']}
 return None

def main():
 protocol=load(BASE/'protocol.json');rootrows=[];evidence={};hashes={}
 for c in load(BASE/'root-summary.json'):
  dest=BASE/'roots'/c['id'];row={k:c[k] for k in ['id','rule','nodes','status']}
  row['processSeconds']=D(c['process']['processElapsedNanos'])/D(10**9)
  if not (dest/'root-result.json').exists():rootrows.append(row);continue
  r=a.load(dest/'root-result.json');search=a.load(dest/'search.json');portfolio=a.load(dest/'portfolio.json');events=a.load(dest/'improvements.json')
  obs=a.load(dest/'observation.json');source=a.load(ROOT/next(k for k in protocol['inputs'] if k.endswith('/observation.json')))
  assert normalized(obs)==normalized(source) and r['rootUnchanged']
  selected=a.load(dest/'selected.json') if (dest/'selected.json').exists() else None
  seed=next((x['result'] for x in portfolio[0]['candidates'] if x['source']=='SEED'),None) if portfolio else None
  retained=next((x['result'] for x in portfolio[0]['candidates'] if x['source']=='RETAINED'),None) if portfolio else None
  assert retained is None
  comparisons=0
  if selected:
   assert r['replayAccepted'] and selected==a.load(dest/'replay.json')
   for candidate in portfolio[0]['candidates']:
    assert result_key(selected,c['rule'])<=result_key(candidate['result'],c['rule']);comparisons+=1
   assert r['firstAction']==selected['plan'][0]
  for before,after in zip(events,events[1:]):assert result_key(after['result'],c['rule'])<result_key(before['result'],c['rule'])
  strict=[after for before,after in zip(events,events[1:]) if score_key(after['result']['evaluation'])<score_key(before['result']['evaluation'])]
  first_old=first_event(events,lambda e:score_key(e['result']['evaluation'])<score_key(protocol['oldPracticalReference']))
  first_seed=first_event(events,lambda e:seed is not None and score_key(e['result']['evaluation'])<score_key(seed['evaluation']))
  beats=selected is not None and score_key(selected['evaluation'])<score_key(protocol['oldPracticalReference'])
  assert r['certificatePresent']==bool(search['status']=='COMPLETE' and portfolio and portfolio[0]['source']=='SEARCH')
  row.update(visitedNodes=search['exploredNodes'],searchSeconds=search['elapsedNanos']/D(10**9),stopReason=search['diagnostics']['stopReason'],
   searchCpuSeconds=None if search['diagnostics']['runtimeTelemetry']['threadCpuNanos'] is None else search['diagnostics']['runtimeTelemetry']['threadCpuNanos']/D(10**9),
   selectionSeconds=r['runtime']['elapsedNanos']/D(10**9),seedStatus=portfolio[0]['seedStatus'] if portfolio else 'UNAVAILABLE',
   seedScore=seed['evaluation'] if seed else None,retainedScore=None,retention=portfolio[0]['retention'] if portfolio else None,
   selectedSource=portfolio[0]['source'] if portfolio else None,selectedScore=selected['evaluation'] if selected else None,
   selectedPlanActions=len(selected['plan']) if selected else None,firstAction=r.get('firstAction'),certificate=r['certificatePresent'],
   replayAccepted=r['replayAccepted'],beatsOldPractical=beats,firstStrictVsOld=first_old,firstStrictVsSeed=first_seed,
   firstStrictIncumbent=first_event(strict,lambda e:True),incumbentUpdates=len(events),strictIncumbentUpdates=len(strict))
  evidence[c['id']]={'candidateComparisons':comparisons,'oldReference':protocol['oldPracticalReference'],'selectedPlan':relative(dest/'selected.json'),'search':relative(dest/'search.json'),'replay':relative(dest/'replay.json')}
  for p in dest.glob('*.json'):hashes[relative(p)]=sha(p)
  rootrows.append(row)
 save(BASE/'root-tables.json',rootrows);csv_write(BASE/'root-tables.csv',[{k:json.dumps(v,default=str) if isinstance(v,(dict,list)) else v for k,v in r.items()} for r in rootrows])
 controllerrows=[]
 if (BASE/'controller-registration.json').exists():
  registration=load(BASE/'controller-registration.json');actual=load(BASE/'controller-summary.json') if (BASE/'controller-summary.json').exists() else []
  for c in actual:
   dest=BASE/'controllers'/c['id'];row={k:c[k] for k in ['id','rule','horizon','nodes','status']}
   row['processSeconds']=D(c['process']['processElapsedNanos'])/D(10**9)
   if not (dest/'result.json').exists():
    row.update(checkpoint_prefix(dest,a.load(ROOT/'target/final-research/models/X5.json')['scenario']));controllerrows.append(row);continue
   r=a.load(dest/'result.json');initial=a.load(dest/'initial-checkpoint.json')['snapshot'];events=a.load(dest/'actual.events.json');state=a.load(dest/'actual.actual.json')
   checked=audit(initial['scenario'],events,state,r);assert a.load(dest/'checkpoint.json')['snapshot']['journal']==events
   for p in a.load(dest/'practical-portfolio.json'):
    if p['selected']:
     for candidate in p['candidates']:assert result_key(p['selected'],c['rule'])<=result_key(candidate['result'],c['rule'])
   searches=[x['search'] for x in a.load(dest/'practical-searches.json')]
   row.update(finalScore=checked['five'],prefixScore=checked['prefixFive'],stoppedAt=state['at'],controllerSeconds=r['measurement']['elapsedNanos']/D(10**9),
    hostCpuSeconds=None if r['measurement']['threadCpuNanos'] is None else r['measurement']['threadCpuNanos']/D(10**9),searches=len(searches),
    searchNodes=sum(x['exploredNodes'] for x in searches),searchSeconds=sum(x['elapsedNanos'] for x in searches)/D(10**9),
    stops=dict(collections.Counter(x['diagnostics']['stopReason'] for x in searches)),decisions=len(a.load(dest/'actual.full-trace.json')['decisions']),
    chargedDecisionDelay=0,deadlineMisses=r['decisionDeadlineMisses'])
   evidence[c['id']]=checked
   for p in dest.glob('*.json'):hashes[relative(p)]=sha(p)
   controllerrows.append(row)
  denominator={'planned':len(registration['cases']),'executed':len(actual),'statuses':dict(collections.Counter(c['status'] for c in actual))}
 else:denominator={'planned':None,'executed':0,'statuses':{}}
 save(BASE/'controller-tables.json',controllerrows);csv_write(BASE/'controller-tables.csv',[{k:json.dumps(v,default=str) if isinstance(v,(dict,list)) else v for k,v in r.items()} for r in controllerrows]) if controllerrows else None
 save(BASE/'audit.json',{'roots':{'planned':6,'executed':len(rootrows),'statuses':dict(collections.Counter(r['status'] for r in rootrows))},'controllers':denominator,
  'evidence':evidence,'inputHashes':hashes,'historicalRowsNotNewAttempts':{'v1Controllers':110,'v1RootDiagnostics':12,'v1Reused':6,'finalV2Attempts':108}})
 (DOC/'tables').mkdir(exist_ok=True)
 import shutil
 for name in ['root-tables.json','root-tables.csv','controller-tables.json','controller-tables.csv']:
  if (BASE/name).exists():shutil.copy2(BASE/name,DOC/'tables'/name)
 print(json.dumps({'rootStatuses':dict(collections.Counter(r['status'] for r in rootrows)),'controllerDenominator':denominator},indent=2))

if __name__=='__main__':main()
