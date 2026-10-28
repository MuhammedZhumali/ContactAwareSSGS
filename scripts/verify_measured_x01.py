"""Final evidence checks without rerunning controllers or editing the article."""
import collections,json
from pathlib import Path
from analyze_measured_x01 import analyze,load,ns,duration_ns,require,sha,save_json
from run_measured_x01 import verify
from check_measured_x01_pilots import normalized_initial

ROOT=Path(__file__).resolve().parents[1];BASE=ROOT/'target/measured-delay-x01-20261028'
def main():
 m=load(BASE/'manifest.json');verify(m)
 runs,contacts,decisions,pairs,summary=analyze()
 require(len(runs)==88 and all(r['status'] not in ['NOT_RUN','IN_PROGRESS_OR_INTERRUPTED'] for r in runs),'Incomplete registered series')
 require(sum(r['phase']=='main' for r in runs)==80,'Main denominator changed')
 before=None;payloads=0;initial_by_h={};run_checks=[]
 for case in m['cases']:
  dest=BASE/'runs'/case['run_id'];inv=load(dest/'invocation.json');proc=load(dest/'process.json');r=load(dest/'result.json')
  require(inv['manifestHash']==sha(BASE/'manifest.json'),'Wrong registration')
  if before is not None:require(inv['startedUtc']>=before,'Owned experimental JVMs overlap or order changed')
  before=proc['finishedUtc']
  initial=load(dest/'initial-checkpoint.json')['snapshot'];current=load(dest/'checkpoint.json')['snapshot']
  normalized=normalized_initial(initial)
  if case['horizon'] not in initial_by_h:initial_by_h[case['horizon']]=normalized
  require(normalized==initial_by_h[case['horizon']],'Changed initial state across repetitions/rules')
  require(len(initial['securityState']['keys'])==4 and not initial['pendingOperations'] and not initial['journal'],'Nonautonomous initialization')
  events=load(dest/'actual.events.json');windows={c['window']['id']:c['window'] for c in initial['scenario']['contacts']}
  counts=collections.Counter();operation_ids=[]
  for e in events:
   if e['type']=='ACTION_STARTED' and e['operationId'] is not None:operation_ids.append(e['operationId'])
   if e['type']!='PAYLOAD_TRANSFERRED':continue
   payloads+=1;end=ns(e['at']);start=end-duration_ns(e['usefulTime']);w=windows[e['contactId']]
   require(ns(w['start'])<=start<end<=ns(w['end']),'Frame exceeds its contact')
   for direction,count_name in [('uplink','uplinkFrames'),('downlink','downlinkFrames')]:
    count=e[count_name]
    if not count:continue
    keyid=e['keyIds'][direction];counts[keyid]+=count;key=current['securityState']['keys'][keyid]
    require(key['contextId']['groundStationId']==w['groundStationId'],'Cross-station key use')
    require(key['activatedAt'] is not None and ns(key['activatedAt'])<=start,'Payload before key activation')
    require(end<=ns(key['notAfter']),'Payload after key deadline')
    require(end<=ns(key['createdAt'])+duration_ns(initial['scenario']['constraints']['maxMaterialAge']),'Material age exceeded')
    require(end<=ns(key['activatedAt'])+duration_ns(initial['scenario']['constraints']['cryptoperiod']),'Cryptoperiod exceeded')
  require(len(operation_ids)==len(set(operation_ids)),'Repeated actual operation ID')
  final_keys=current['securityState']['keys']
  for kid,key in final_keys.items():
   old=initial['securityState']['keys'].get(kid,{}).get('nextNonceCounter',0)
   require(key['nextNonceCounter']>=old+counts[kid],'Lost counters')
  for context in current['securityState']['contexts'].values():
   for direction in ['uplink','downlink']:
    sa=context[direction]
    if sa['keyId'] is not None:require(sa['nextSequenceNumber']==final_keys[sa['keyId']]['nextNonceCounter'],'SA/key counter mismatch')
  if r['status']=='FINISHED':
   require(len(final_keys)-4==r['simulationMetrics']['createdKeys'],'Created-key inventory mismatch')
  run_checks.append({'run_id':case['run_id'],'status':r['status'],'checks':'PASS','journalSha256':sha(dest/'actual.events.json')})
 preservation={p:sha(ROOT/p)==h for p,h in m['preserved'].items()}
 require(all(preservation.values()),'Previous evidence changed: '+str([p for p,v in preservation.items() if not v]))
 require(sha(ROOT/'docs/aitu-revision-v2/article.docx')==sha(BASE/'inputs/article-before.docx'),'User article changed')
 result={'status':'PASS','registeredAttempts':88,'mainAttempts':80,'runStatuses':summary['run_statuses'],
  'verifiedDecisionRecords':len(decisions),'verifiedContactRows':len(contacts),'verifiedDeliveredFrames':payloads,
  'registeredOrderAndSerialExecution':'PASS','initialStateAcrossRepetitions':'PASS','payloadBoundariesAndKeyDeadlines':'PASS',
  'operationIdUniquenessAndCounters':'PASS','previousFilesVerified':len(preservation),'userArticleUnchanged':True,
  'runChecks':run_checks,'scope':'Journal and snapshot invariants plus13targeted runtime tests; no full-suite or universal proof claim'}
 save_json(BASE/'final-validation.json',result)
 print(json.dumps({k:v for k,v in result.items() if k!='runChecks'},indent=2))

if __name__=='__main__':main()
