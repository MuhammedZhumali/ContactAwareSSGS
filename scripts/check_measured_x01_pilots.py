"""Verify registered pilot journals; write gate only after explicit review flag."""
import argparse, collections, copy, datetime, json
from pathlib import Path
from analyze_measured_x01 import analyze, load, ns, duration_ns, sha, save_json, require

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/measured-delay-x01-20261028'

def normalized_initial(snapshot):
 # This field is a Java Set<ContextId>; JSON array iteration order is not state.
 # Preserve every other array/order, including contacts, plans and event queues.
 result=copy.deepcopy(snapshot)
 contexts=result['scenario']['constraints']['authorizedContexts']
 result['scenario']['constraints']['authorizedContexts']=sorted(contexts,key=lambda c:(c['satelliteId'],c['groundStationId']))
 return result

def check(reviewed=False):
 runs,contacts,decisions,pairs,summary=analyze()
 manifest=load(BASE/'manifest.json')
 pilots=[c for c in manifest['cases'] if c['phase']=='pilot']
 require(len(pilots)==4,'Expected four pilots')
 evidence=[]
 for case in pilots:
  dest=BASE/'runs'/case['run_id'];process=load(dest/'process.json');result=load(dest/'result.json')
  require(process is not None and result is not None,'Pilot process/export missing')
  require(process['processStatus']=='EXITED','Pilot export process did not exit normally; inspect retained prefix')
  initial=load(dest/'initial-checkpoint.json')['snapshot']
  control=load(BASE/'runs'/f"control-{case['tie_rule']}-01-H{case['horizon']}"/'initial-checkpoint.json')['snapshot']
  require(normalized_initial(initial)==normalized_initial(control),'Measured initial snapshot differs from matched offline control')
  initial_keys=initial['securityState']['keys']
  require(len(initial_keys)==4 and all(k['status']=='ACTIVE' for k in initial_keys.values()),'Autonomous old keys differ')
  require(not initial['journal'] and not initial['pendingOperations'],'Hidden initial work or incurred costs')
  require(not initial['scenario']['plan'] and not initial['scenario']['disclosures'],'Unexpected prescribed plan or attack')
  require(result['effectiveDecisionBudgetNanos']==32_000_000_000,'Effective deadline differs')
  records=load(dest/'latency-decisions.json');require(records,'No measured decisions to verify')
  require(all(d['continuation'] is not None for d in records),'Proof verification audit missing')
  require(any(d['continuation']['guard'] is not None for d in records),'Guard not assessed')
  require(sum(d['hostNanos'] for d in records)==result['computation']['selectionNanos'],'Whole-cycle time accounting differs')
  require(result['decisionDeadlineMisses']==sum(d['deadlineMiss'] for d in records),'Deadline export differs')
  final=load(dest/'checkpoint.json')['snapshot'];events=load(dest/'actual.events.json')
  windows={c['window']['id']:c['window'] for c in initial['scenario']['contacts']}
  usage=collections.Counter();starts=[];operations_during_decision=0
  for e in events:
   if e['type']=='ACTION_STARTED' and e['operationId'] is not None:starts.append(e['operationId'])
   if e['type']=='PAYLOAD_TRANSFERRED':
    start=ns(e['at'])-duration_ns(e['usefulTime']);end=ns(e['at']);w=windows[e['contactId']]
    require(ns(w['start'])<=start<end<=ns(w['end']),'Delivered frame exceeds contact')
    for direction,name in [('uplink','uplinkFrames'),('downlink','downlinkFrames')]:
     count=e[name]
     if not count:continue
     key_id=e['keyIds'][direction];usage[key_id]+=count;key=final['securityState']['keys'][key_id]
     require(key['activatedAt'] is not None and ns(key['activatedAt'])<=start,'Frame precedes activation')
     require(end<=ns(key['notAfter']),'Frame exceeds key deadline')
     require(end<=ns(key['createdAt'])+duration_ns(initial['scenario']['constraints']['maxMaterialAge']),'Frame exceeds material age')
     require(end<=ns(key['activatedAt'])+duration_ns(initial['scenario']['constraints']['cryptoperiod']),'Frame exceeds cryptoperiod')
   if e['type']=='ACTION_COMPLETED' and any(ns(d['origin']['at'])<ns(e['at'])<=ns(d['availableAt']) for d in records):operations_during_decision+=1
  require(len(starts)==len(set(starts)),'Repeated actual operation ID')
  for key_id,key in final['securityState']['keys'].items():
   baseline=initial_keys.get(key_id,{}).get('nextNonceCounter',0)
   require(key['nextNonceCounter']>=baseline+usage[key_id],'Counter lost actual delivered frames')
  evidence.append({'run_id':case['run_id'],'status':result['status'],'decisions':len(records),
   'searches':result['actualSearchAttempts'],'deadlineMisses':result['decisionDeadlineMisses'],
   'dispositions':dict(collections.Counter(d['disposition'] for d in records)),
   'guardReasons':result['computation']['guardReasons'],'chargedNanos':sum(d['hostNanos'] for d in records),
   'operationsCompletedDuringChargedIntervals':operations_during_decision,
   'journalHash':sha(dest/'actual.events.json'),'latencyHash':sha(dest/'latency-decisions.json'),
   'checks':'Initial state (authorizedContexts Java Set order normalized only), per-cycle latency/admission mapping, event-derived five metrics, frame boundaries/deadlines, monotonic counters, unique operation starts'})
 report={'status':'PASS','checkedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
  'manifestSha256':sha(BASE/'manifest.json'),'validatorSha256':sha(__file__),'pilots':evidence,
  'manualReviewRecorded':reviewed,'limitations':'No hidden traffic, new attacks or events added; deadline/resource regression evidence also in 13 focused tests. Background host interference not attributed.'}
 save_json(BASE/'pilot-validation.json',report)
 if reviewed:save_json(BASE/'pilot-gate.json',report)
 print(json.dumps(report,default=str,indent=2))

if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('--approve-reviewed-pilots',action='store_true');args=parser.parse_args()
 check(args.approve_reviewed_pilots)
