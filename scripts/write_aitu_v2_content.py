"""Revision-2 content from frozen v1 and new, separately audited observations."""
import copy,json,re,shutil
from pathlib import Path
from decimal import Decimal
from run_research_validation import ROOT,load,save,sha
OLD=ROOT/'docs/aitu-revision';OUT=ROOT/'docs/aitu-revision-v2';DATA=ROOT/'target/aitu-revision-v2'

def p(text):return {'type':'p','text':text}
def heading(text):return {'type':'subheading','text':text}
def table(caption,headers,rows,widths):return dict(type='table',caption=caption,headers=headers,rows=rows,widths=widths)
def fmt(x):return format(Decimal(str(x)).quantize(Decimal('.001')),'f').rstrip('0').rstrip('.')
def score(e):
 return 'unavailable' if e is None else ', '.join(fmt(e[k]) for k in ['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys'])
def model_table():
 model=load(DATA/'input-descriptors.json')['models'][0]
 return table('Table 3. Common ISS long and late contact geometry; UTC on 12 October 2026. IDs have prefix orekit-25544-SATNOGS-.',
  ['ID suffix','Station ID','Start UTC','End UTC'],[[x['window']['id'].removeprefix('orekit-25544-SATNOGS-'),x['window']['groundStationId'].removeprefix('SATNOGS-'),x['window']['start'][11:-1],x['window']['end'][11:-1]] for x in model['contacts']],[2.2,2,6.1,6.2])

def main():
 article=load(OLD/'article-content.json');sup=load(OLD/'supplement-content.json')
 roots=load(DATA/'root-tables.json');runs=load(DATA/'controller-tables.json');audit=load(DATA/'audit.json')
 assert len(roots)==6 and len(runs)==audit['controllers']['planned']==4
 narrative=[]
 for rule in ['CANONICAL','CONTINUE_WAIT']:
  pair=[next(r for r in runs if r['rule']==rule and r['horizon']==h) for h in [1,2]]
  if all(r['status']=='FINISHED' for r in pair):
   narrative.append(f"Under {rule}, the full Next and Multi runs finish with ({score(pair[0]['finalScore'])}) and ({score(pair[1]['finalScore'])}), respectively.")
  else:
   narrative.append(f"Under {rule}, Next is {pair[0]['status']} and Multi is {pair[1]['status']}; their full-run comparison remains unresolved.")
 fulltext=' '.join(narrative)
 # Abstract retains both the journal's word and character limits.
 article['abstract']='''Short satellite contacts must carry payload and the work needed to use valid traffic keys. A longer view of future contacts can help only if it changes a feasible plan and the controller can find and use that plan in time. We ask when extra contact visibility helps joint key and security association management. The compared horizons share actions, safety rules, costs, and five ordered service and resource criteria. Exact search is limited to a finite event model. A practical controller compares seeds, retained plans, and bounded search. The first revision adds 110 full controller attempts and twelve paired root checks to a preserved baseline; 105 controller attempts finish. In nine exact cases with common prescribed preparation, the longer horizon serves one extra contact in two cases, ties in five, and leaves two unresolved. A first budget series shows no offline gain under its one second search cap. Six new checks then keep the same autonomous root and allow up to thirty seconds. At 150,000 and 200,000 allowed nodes, practical search finds a useful plan under both tie rules, without an oracle. Only one rule completes the root proof within these limits. In four full runs, one rule confirms an extra contact; the other remains unresolved at the controller time limit. Earlier charged time tests give three longer horizon losses and one tie. In 36 disclosure runs, recovery and extra confidential delivery depend on the tie rule. Early key activation can expire before later delivery. The evidence supports a conditional scheduling benefit, bounded by key validity, contact windows, plan choice, and search cost, rather than a general security advantage.'''
 assert 250<=len(article['abstract'].split())<=280 and len(article['abstract'])<=1900,(len(article['abstract'].split()),len(article['abstract']))
 b=article['blocks']
 changes={
 26:'Both horizons use the same finite start events, executor, constraints, resources, generation IDs and traversal. Only exact equality of all five criteria invokes the full-plan tie rule. Times retain nanosecond precision. The CANONICAL action tuple is (kind rank, start, station ID, context ID, uplink key ID, downlink key ID, until, contact ID), ascending. Kind order is PREPARE, INSTALL, SWITCH, OPEN, REUSE, CONTINUE, WAIT. Context compares spacecraft then station; strings use lexical order, and null precedes a value. Plans compare at their first unequal action; a proper prefix precedes its extension.',
 29:'The first AITU revision used a separate harness against the unchanged Java 21 final-v2 JAR: 110 new full-controller attempts, twelve paired-root diagnostics, and six explicitly reused cells. These populations remain separate from the preserved baseline of 108 attempts (93 FINISHED, 15 PROCESS_TIMEOUT). The present revision adds six ordinary practical root searches and four conditionally registered full executions; no attack series is repeated. Table 2 describes the first revision only.',
 31:'In the first revision, exact searches allow 2,000,000 nodes and 30 seconds. Practical searches allow 5,000 nodes and one second, with a two-second whole-decision allowance. Seed construction and retained replay/extension each have a 350-millisecond scope. Seed rollout and retained extension have a 10,000-transition cap; the replay loop itself is not subject to that count cap. Full controllers have 120 seconds and a 150-second process watchdog. The budget series varies only the node allowance and retains the one-second search timer; its higher levels are often time-limited. These are finite limits, not claims of full enumeration at every allowance.',
 34:'The earlier budget series uses autonomous X and the three original ISS windows in Table 3. It compares no DFS and allowances of 1,000, 5,000, 20,000 and 100,000 nodes, always with the one-second search cap. Seed, retention and comparison stay common. Selected 1,000/20,000-node cases charge measured decision time. Search elapsed/CPU, controller elapsed/CPU, and modeled lifecycle CPU are separate. The host was an AMD Ryzen 7 5800HS (8 cores, 16 logical processors), 16 GiB physical RAM, Java 21.0.10; hardware was observed after the campaign.',
 44:'In the earlier instrumented t=0, H=2 searches, the first strictly better five-coordinate incumbent appeared at node 134,269, after about 8.1–8.3 seconds. The 5,000-node practical search had already stopped, before its one-second timer. This node count describes those particular traversals; it is not a universal minimum budget. A separate diagnostic replay accepted the exact plan through the ordinary executor, evaluator and comparator, without supplying it to the ordinary policy.',
 47:'Fig. 1 reports nine exact-controller configurations with a common prescribed PREPARE, not nine autonomous practical comparisons. Seven have completed Next/Multi pairs under both tie rules: Multi serves one extra contact in X01 and X08, and five configurations tie. The two remaining settings have unfinished Multi searches and are unknown, not losses or estimated gains.',
 51:'All 26 first-revision budget attempts finish. Under the fixed one-second search cap, Fig. 2 shows no offline horizon advantage at the tested node allowances in either workload. For X, no DFS yields (2, 38, 0.32, 160, 2); every positive allowance yields (2, 38, 0.12, 160, 0) for both horizons. The long control ties at (3, 410, 2.13, 3240, 6). This result does not characterize larger search-time budgets.',
 69:'Joint planning across predicted contacts can preserve an otherwise missed contact. The prescribed-preparation exact family establishes this in two configurations under both tested tie rules, with five ties and two unresolved settings. The new ordinary practical root checks also find the autonomous service-improving plan at 150,000 and 200,000 allowed nodes with a thirty-second search cap. CANONICAL supplies a feasible incumbent; CONTINUE_WAIT completes the finite root proof. These observations are specific to the preserved state and traversal.',
 70:'The previous one-second budget series has no offline practical gain; that finding must not be generalized to all budgets. '+fulltext+' Root quality and full receding-horizon execution are distinct results. Charged decision time reduced service in the tested lower-budget runs. Whether the newly observed offline gain survives charging computation time remains untested. Disclosure outcomes depend on tie order, and early activation can expire before delivery. Neither lower compromise probability nor global mission optimality is established.',
 73:'Data and code availability: the historical final-v2 package is at https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/research. That release does not contain the new AITU experiments. The separate AITU revision 2 reproduction archive includes registered inputs, all completed and incomplete attempts, analysis and SHA256 manifests. New package public URL: [TO BE ADDED AFTER PUBLIC DEPOSITION]. No new DOI is claimed.'}
 for index,text in changes.items():assert b[index]['type']=='p';b[index]['text']=text
 b[43]['text']=b[43]['text'].replace('Autonomous practical runs under both tie rules and exact Next runs instead give', 'In the first revision, autonomous practical (5,000 nodes/one second) and exact Next runs under both tie rules give',1)
 b[45]['text']='In those first-revision executions, '+b[45]['text'][0].lower()+b[45]['text'][1:]
 b[30]['caption']='Table 2. First AITU revision only: registered attempts and completion; reused cells are not new attempts.'
 b[52]['caption']='Fig. 2. First-revision CANONICAL full-run payload and host cost under the one-second search cap. No DFS retains seeds and feasible suffixes. Crosses denote measured-delay cases. Node values are allowances, not guaranteed work completed.'
 method=[
 p('CONTINUE_WAIT prepends (group, until) to the canonical tuple: group is CONTINUE=0, WAIT=1, all service operations=2. Thus until is compared before operation kind even within the service group. This complete order differs from tree visitation: SERVICE_FIRST visits CONTINUE, SWITCH, INSTALL, OPEN, REUSE, WAIT, PREPARE, with canonical secondary ordering. Seeds and retained plans are not injected as DFS incumbents.'),
 p('Practical controller pseudocode\n1. At an event, construct the permitted observation and admissible actions.\n2. Build a terminal seed; check, replay and extend any matching retained suffix from the same root.\n3. Run bounded unrestricted search; propagate global stops.\n4. Compare feasible terminal candidates by the five criteria, then the full-plan rule.\n5. If a plan exists, save its suffix with the predicted first-action child.\n6. In measured-delay mode, advance committed work/events during computation, rebuild admissibility, and recheck the chosen intent; discard stale or late decisions.\n7. Execute only the admissible first action and repeat. Mandatory incident response takes precedence.'),
 p('The seed waits while a frame or operation is already in flight. With complete-frame demand in the current contact, it prefers CONTINUE, OPEN, REUSE, SWITCH, INSTALL, PREPARE, subject to ordinary feasibility and an optimistic remaining-chain-plus-one-frame fit. Between contacts, it inspects the first eligible visible demand and prepares only an UNKEYED context at or after its just-in-time preparation boundary. Otherwise it takes the earliest generated WAIT. Retention replays from the new root, checking state, progress, costs, counters and issued IDs; it does not reuse the previous score. If no terminal candidate exists, an admissible local fallback is used. Supplement gives the full seed filters and source tie handling.')]
 inputs=[model_table(),
 p('Spacecraft NORAD-25544 uses stations SATNOGS-3634 and SATNOGS-2987. For all three ISS contacts, modeled payload throughput is 100 bytes/s and frames are 1,000 bytes (10 seconds). Offered UL/DL bytes are 39,000/39,000 in the first contact and 1,000/1,000 in each later contact; minimum UL/DL service is 1,000/1,000 in every contact. Window linkRateBps and expectedDataBytes are null; no measured physical link rate is inferred. Both station contexts start with no keys or prepared pairs, two UNKEYED SAs, zero counters and no open session. The scenario starts at 00:08:35.296295166 UTC and ends at the last listed contact end.'),
 p('The ISS cryptoperiod is 5,400 seconds, material-age limit 172,800 seconds, and maximum reuse gap 86,400 seconds. Only the two listed station contexts are authorized; the blocked-key set and prescribed operation plan are empty. Phase reservations enforce exclusive spacecraft CPU, station-local CPU and shared UL/DL radio occupancy. The generator permits one spare pair per context by rejecting PREPARE while PRE_ACTIVE material or pending preparation exists; this is not a total storage-capacity parameter. Exact file paths, SHA256 hashes, complete initial states and the nine derived late-disclosure configurations are in Supplement and input-descriptors.json.'),
 p('The new budget diagnostic restores one saved autonomous t=0, H=2 observation for both tie rules, then independently allows 100,000, 150,000 or 200,000 nodes: six searches. Search time is 30 seconds, whole selection 32 seconds, process watchdog 45 seconds. A fresh ordinary seed/retention/search portfolio receives no exact plan, offered continuation or external incumbent; retained input is absent. The observational overlay records discoveries. For each rule, the smallest tested allowance whose replay-valid selected score strictly beats the old practical (2, 38, 0.12, 160, 0) triggers one offline Next/Multi pair. These four full runs use the original autonomous start, frozen ordinary planner, 180-second controller and 210-second watchdog. All limits were registered before outcomes.')]
 root_table=table('Table 4. New autonomous H2 root diagnostics; allowed/visited nodes. CAN=CANONICAL; CW=CONTINUE_WAIT. All selected plans pass common replay.',
  ['Rule','Allowed / visited','Search status','Selected S, U, C, B, K'],[[('CAN' if r['rule']=='CANONICAL' else 'CW'),f"{r['nodes']} /\n{r['visitedNodes']}",r['status'],score(r['selectedScore'])] for r in roots],[1.7,3,3.2,8.6])
 newresults=[heading('Additional budget diagnostic and full execution'),root_table,
 p('At 100,000 nodes both selected results remain (2, 38, 0.12, 160, 0). At 150,000 and 200,000 nodes both rules select (3, 37, 0.83, 1240, 2). CANONICAL stops at the node allowance without a certificate; CONTINUE_WAIT completes at 134,638 visited nodes and has a root certificate. All six start with OPEN and pass full-plan replay. No search reaches the new 30-second cap. This establishes discovery in these ordinary practical runs, not a universal threshold.'),
 p('Both rules therefore select 150,000 as the smallest qualifying tested allowance for the four follow-up executions. '+fulltext+' In this offline comparison, observed controller elapsed time was 2.537 s for Next and 32.426 s for Multi. These host measurements were not charged to contact time and do not establish onboard feasibility. The original autonomous initialization, preparation costs and subsequent decisions remain part of these runs; a good root score alone is not credited as delivered service.')]
 negative=p('Two CANONICAL cells show an efficiency loss without a delivery-security gain: compromise at E−5 with detection delay 0 or 2 seconds gives Next (3, 410, 2.17, 3336, 6) and Multi (3, 400, 2.82, 4336, 8). Both deliver 4,000 confidential bytes and their compromise-to-first-independent-frame delay is 5,559.211669922 seconds. Multi loses at useful time, and also spends more CPU, bytes and keys. Its earlier first independent activation improves neither SA opening nor independent delivery in these cells. Objective, recovery and confidential-delivery gains are separate comparisons.')
 rebuilt=[]
 for i,block in enumerate(b):
  if i==58:continue
  rebuilt.append(block)
  if i==56:rebuilt.append(b[58])
  if i==27:rebuilt+=method
  if i==35:rebuilt+=inputs
  if i==54:rebuilt+=newresults
  if i==59:rebuilt.append(negative)
 article['blocks']=rebuilt
 save(OUT/'article-content.json',article)
 # Wide, independent panels replace the cramped recovery/traffic tables, at 12pt.
 sb=[]
 for block in sup['blocks']:
  if block['type']=='table' and block['caption'].startswith('Table S5'):
   tag='S5a' if block['caption'].startswith('Table S5a') else 'S5b'
   rule='CANONICAL' if tag=='S5a' else 'CONTINUE_WAIT'
   rows=block['rows']
   sb += [table(f'Table {tag}.1. {rule}: notice and activation, seconds after ground notice.',
     ['E offset / delay / H','SC notice','Independent pair'],[[r[0]+'/'+r[1],r[2],r[3]] for r in rows],[4.5,6,6]),
    table(f'Table {tag}.2. {rule}: SA and frame delays after ground notice; total starts at compromise.',
     ['E offset / delay / H','Independent SA','Independent frame','Total'],[[r[0]+'/'+r[1],r[4],r[5],r[6]] for r in rows],[3.6,4.3,4.3,4.3])]
  elif block['type']=='table' and block['caption'].startswith('Table S6'):
   tag='S6a' if block['caption'].startswith('Table S6a') else 'S6b';rule='CANONICAL' if tag=='S6a' else 'CONTINUE_WAIT'
   rows=block['rows']
   sb += [table(f'Table {tag}.1. {rule}: five-component objective (U and C in seconds; B in bytes).',
    ['Offset / delay / H','S','U','C','B','K'],[[r[0],*r[1].split(', ')] for r in rows],[4,1.3,2.5,3,3.7,2]),
    table(f'Table {tag}.2. {rule}: confidential delivery and exposed emitted traffic, bytes.',
    ['Offset / delay / H','Confidential','Exposed before','Exposed after'],[[r[0],r[2],r[3],r[4]] for r in rows],[4.5,4,4,4])]
  else:
   block=copy.deepcopy(block)
   if block['type']=='p':
    block['text']=block['text'].replace('Seed and retention each 350 ms with 10,000 rollout transitions.', 'Seed construction and retained replay/extension each have 350 ms; the 10,000-transition cap applies to seed rollout and retained extension, not the replay loop.')
    block['text']=block['text'].replace('New execution comprises','First-revision execution comprises')
    block['text']=block['text'].replace('Autonomous CW establishes a valid but undiscovered practical improvement; autonomous CAN remains unresolved under its exact budget.', 'In the first revision, the autonomous CW case established a feasible improvement that was not discovered under the original 5,000-node/one-second practical budget. Autonomous CAN remains unresolved under its exact budget.')
    block['text']=block['text'].replace('Offline budget cases do not justify Multi','The first-revision offline budget cases with a one-second search cap do not justify Multi')
    block['text']=block['text'].replace('Seven complete synthetic settings yieldtwo gains and five ties','Seven complete exact settings with prescribed PREPARE yield two gains and five ties')
   sb.append(block)
 sup_root_table=copy.deepcopy(root_table);sup_root_table['caption']='Table S8. Revision 2 autonomous H2 root diagnostics; allowed/visited nodes. All selected plans pass common replay.'
 sup_model_table=model_table();sup_model_table['caption']='Table S11. Common ISS long and late geometry; UTC on 12 October 2026. IDs have prefix orekit-25544-SATNOGS-.'
 sb += [heading('Revision 2 root budget and controller results'),
  p('This additional series contains six practical root searches, separate from the twelve prior paired-root diagnostics. Four conditional full-controller executions are also separate from the prior 110 attempts and six reused logical cells. No disclosure attempts were added. Search/decision/process limits are 30/32/45 seconds; full-controller/process limits are 180/210 seconds. The executable, comparator and model remain frozen. Registered protocol and all raw plans/incumbents are in target/aitu-revision-v2.'),
  sup_root_table,
  table('Table S9. Root timing, seed state and certificate. Seconds are observed instrumented host time.',
   ['Rule / cap','Search s','First better node / s','Seed','Cert.'],[[('CAN' if r['rule']=='CANONICAL' else 'CW')+'/'+str(r['nodes']),fmt(r['searchSeconds']),('none' if r['firstStrictVsOld'] is None else str(r['firstStrictVsOld']['nodes'])+' / '+fmt(r['firstStrictVsOld']['seconds'])),'feasible' if r['seedStatus']=='FEASIBLE_TERMINAL' else r['seedStatus'],'yes' if r['certificate'] else 'no'] for r in roots],[3.3,2.5,4.5,4.2,2]),
  p('The first-better column compares five metrics with the frozen ordinary practical winner, not merely with a seed. All initial retained candidates are absent. Complete incumbent histories, seed evaluations (including unavailable timed-out rollouts), source selection, first action, complete plan and independent replay are retained per search. First improvement of an incumbent, improvement over the current seed and improvement over the frozen practical result are separate exported fields. The old 134,269 observation is not a general lower bound; only three registered allowances are tested.'),
  table('Table S10. Conditional full executions at 150,000 nodes; controller limit denotes CONTROLLER_TIME_LIMIT.',
   ['Rule / H','Status','Final S, U, C, B, K','Host s'],[[('CAN' if r['rule']=='CANONICAL' else 'CW')+'/'+str(r['horizon']),'controller limit' if r['status']=='CONTROLLER_TIME_LIMIT' else r['status'],score(r.get('finalScore')),fmt(r.get('controllerSeconds',r['processSeconds']))] for r in runs],[2,3.8,8,2.7]),
  p(fulltext+' A stopped run has only prefix metrics in machine tables. Prefix values are not substituted for a final mission score. Root-only improvement is therefore not a completed-controller advantage. Search node counts, stop distributions and CPU are in the controller table exports.'),
  negative,
  heading('Reconstructing the method'),*method,
  p('Seed service-fit remainder is zero for CONTINUE, one frame for OPEN/REUSE, OPEN plus one frame for SWITCH, SWITCH+OPEN+one frame for INSTALL, and INSTALL+SWITCH+OPEN+one frame for PREPARE. This filter requires action.until plus the remainder to fit in the contact and rejects uncertain contexts. Between contacts it stops at the first eligible visible full-frame demand even if that keyed context needs no PREPARE; it does not prepare a farther context instead. At a current contact without full-frame demand it waits. On exact equality of score and complete plan, the portfolio prefers SEARCH; otherwise an identical seed precedes an identical retained candidate. This source choice is not a sixth objective.'),
  heading('Exact ISS long and late inputs'),sup_model_table,inputs[1],inputs[2],
  p('The historical late input contains compromise at 00:14:21.720977783 and detection at 00:14:23.720977783 UTC. The first-revision nine-configuration disclosure series replaces only these times by E minus 40, 20 or 5 seconds and detection delays 0, 2 or 20 seconds. The following inventories distinguish the historical base from the actually used derived configurations. Read each SHA256 without whitespace; paths are relative to the package root.')]
 for model in load(DATA/'input-descriptors.json')['models']:
  sb.append(p(model['path']+'\nSHA256: '+model['sha256'][:32]+'\n'+model['sha256'][32:]))
 sb += [p('The descriptor JSON and frozen files retain complete states and provenance. The 39,000-byte offers are demand, not delivery. The payload model uses 100 bytes/s; window bitrate is null. Resource and spare rules come from the hashed Java sources.'),
  heading('Revision 2 availability and unresolved submission items'),
  p('The new reproduction archive is a separate version and is not present in the historical research release. Public URL: [TO BE ADDED AFTER PUBLIC DEPOSITION]. Native Word OMML equations remain editable; no MathType conversion or complete journal compliance is claimed. Author declarations, source-age exceptions and the Scopus/Web of Science criterion remain subject to the documented submission checklist.')]
 sup['blocks']=sb;save(OUT/'supplement-content.json',sup)
 (OUT/'figures').mkdir(exist_ok=True);(OUT/'article-tex/figures').mkdir(parents=True,exist_ok=True)
 for f in (OLD/'figures').iterdir():
  if f.is_file():shutil.copy2(f,OUT/'figures'/f.name)
 for f in (OLD/'article-tex/figures').glob('*.png'):shutil.copy2(f,OUT/'article-tex/figures'/f.name)
 print('Content updated; abstract',len(article['abstract'].split()),'words,',len(article['abstract']),'characters')

if __name__=='__main__':main()
