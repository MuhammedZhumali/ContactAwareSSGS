"""Exact frozen ISS long/late inputs; no guessed station names or hardware rates."""
import json
from pathlib import Path
from run_research_validation import ROOT,load,save,sha,relative
BASE=ROOT/'target/aitu-revision-v2';DOC=ROOT/'docs/aitu-revision-v2'
paths=[ROOT/'target/final-research/models/demand-none.json',ROOT/'target/final-research/models/demand-late-detected.json']
paths+=sorted((ROOT/'target/aitu-revision/stage4-models').glob('*.json'))
records=[]
for p in paths:
 d=load(p);s=d['scenario'];state=s['initialState']
 records.append(dict(path=relative(p),sha256=sha(p),name=s['name'],start=s['start'],end=s['end'],
  contacts=s['contacts'],constraints=s['constraints'],costs=s['costs'],initialState=state,
  preparedPairs=0 if not state['keys'] else None,prescribedPlan=s['plan'],disclosures=s.get('disclosures'),
  channelInterruptionsField=s.get('channelInterruptions','NOT_PRESENT_IN_INPUT'),inputMetadata=d.get('input'),provenance=d.get('provenance')))
common=records[0]
for r in records[1:]:
 for field in ['contacts','constraints','costs','initialState','prescribedPlan','start','end']:assert r[field]==common[field],(r['path'],field)
assert not common['initialState']['keys'] and not common['prescribedPlan']
contexts=common['initialState']['contexts']
assert len(contexts)==2
for c in contexts.values():
 assert c['activeContact'] is None and c['openedAt'] is None
 for direction in ['uplink','downlink']:assert c[direction]['status']=='UNKEYED' and c[direction]['keyId'] is None and c[direction]['nextSequenceNumber']==0 and not c[direction]['peerSynchronized']
sourcefiles=['SecurityActionGenerator','SimulationEngine','LifecycleTransitions','FeasibleSeedPlanBuilder','PlanningResultComparator','PlanTieBreak','PlannerOptions','PracticalPlanPortfolio','PlanningLatencyController']
sources={}
for name in sourcefiles:
 p=ROOT/f'src/main/java/org/contactaware/security/{name}.java'
 if not p.exists():p=ROOT/f'src/main/java/org/contactaware/security/simulation/{name}.java'
 sources[relative(p)]=sha(p)
frozen=load(ROOT/'target/final-research/final-v2-sources.json')
assert all(frozen[p]==h for p,h in sources.items())
value={'models':records,'sameNominalInputsAcrossAll':True,'codeSourceHashesMatchFrozen':sources,
 'resources':{'source':'frozen Java rules, not JSON capacity parameters','phaseExclusive':['ground:<stationId>','spacecraft','radio'],
 'radio':'One shared uplink/downlink management/payload radio; per-phase reservations rechecked by executor',
 'spare':'Generator blocks PREPARE when any PRE_ACTIVE material or pending PREPARE exists for that context; one spare pair on generated paths, not a total storage/RAM bound',
 'waiting':'WAIT reserves no resource; already committed work is preserved'},
 'boundary':'bytesPerSecond is modeled payload throughput; null window.linkRateBps is not replaced by a measured radio bitrate. SATNOGS IDs are identifiers, not inferred station names.'}
save(BASE/'input-descriptors.json',value);save(DOC/'input-descriptors.json',value)
print('Verified',len(records),'frozen long/late configurations; identical nominal inputs; only disclosure timing differs')
