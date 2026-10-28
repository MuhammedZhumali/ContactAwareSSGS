"""Apply the approved X01 results to the author's DOCX without rebuilding its design.

The retained source is distributed with the revised reproduction package. Only
word/document.xml is edited; every other DOCX part is copied byte-for-byte.
"""
import copy, hashlib, json, re, zipfile
from pathlib import Path
from lxml import etree as E

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/article-x01-revision-20261028'
SOURCE=BASE/'article-before.docx'
DEST=ROOT/'docs/aitu-revision-v2/article.docx'
EXPECTED='de3eaaf53bf4a786eb36539f9ab9c9d58911180491bb353786bdfed5286ed09f'
NS={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
 'm':'http://schemas.openxmlformats.org/officeDocument/2006/math'}
W='{'+NS['w']+'}'
def sha(data):return hashlib.sha256(data).hexdigest()
def text(p):return ''.join(p.xpath('.//w:t/text()',namespaces=NS))
def canon(p):return E.tostring(p,method='c14n')
def set_text(p,value):
    assert not p.xpath('.//w:drawing|.//w:fldChar|.//m:oMath|.//w:hyperlink',namespaces=NS)
    nodes=p.xpath('.//w:t',namespaces=NS);assert nodes
    nodes[0].text=value
    for node in nodes[1:]:node.text=''

REPLACEMENTS={
19:'The research question is: under identical lifecycle actions, security constraints, costs and plan tie-breaking, when does additional predicted contact visibility improve achievable spacecraft-ground service, and when can a bounded receding-horizon controller realize that improvement after its measured decision time is charged to contact opportunities?',
21:'The objectives are to isolate initialization from search, test timing and validity conditions, measure full-run quality under fixed computational budgets, and distinguish recovery milestones after key disclosure. We formulate joint directional-key and security-association lifecycle scheduling and provide matched evidence identifying useful and ineffective regimes. The experiments separate the existence and discovery of a service-improving plan from its timely execution, and separate efficiency from disclosure consequences. Implementation checks support reproducibility; neither a new cryptographic protocol nor universal superiority of the longer horizon is claimed.',
41:'The first AITU revision used a separate harness against the unchanged Java 21 final-v2 JAR: 110 new full-controller attempts, twelve paired-root diagnostics and six explicitly reused cells. These populations remain separate from the preserved baseline of 108 attempts (93 FINISHED, 15 PROCESS_TIMEOUT). The subsequent budget study added six ordinary practical root searches and four conditional full executions. The measured-delay campaign reported below adds 88 separately registered attempts: four offline controls, four measured pilots and 80 main executions. No attack series is repeated. Table 2 describes the first revision only.',
48:'The earlier budget series uses autonomous X and the three original ISS windows in Table 3. It compares no DFS and allowances of 1,000, 5,000, 20,000 and 100,000 nodes, always with the one-second search cap. Seed, retention and comparison stay common. Selected 1,000/20,000-node cases charge measured decision time. Search elapsed/CPU, controller elapsed/CPU and modeled lifecycle CPU are separate. Earlier reports describe the host as an AMD Ryzen 7 5800HS (8 cores, 16 logical processors), nominally 16 GiB RAM, using Java 21.0.10; those hardware observations followed that campaign.',
49:'Geometric contact inputs in the baseline were derived from archived CelesTrak general-perturbations data [18], SatNOGS station metadata [19] and Orekit propagation [20]. They represent visibility, not reserved ground-station service. The archived models identify dates, stations, payload assumptions and hashes. ISS controls reuse those windows, while X01 is synthetic; no new full-day propagation campaign is performed. Before the measured-delay campaign, the host was recorded as an AMD Ryzen 7 5800HS, 8 physical cores/16 logical processors, 16,541,487,104 bytes of OS-reported physical memory, Windows 11 build 26200 and Java 21.0.10. These desktop measurements are not onboard latency predictions.',
55:'The preceding budget diagnostic restores one saved autonomous t=0, H=2 observation for both tie rules, then independently allows 100,000, 150,000 or 200,000 nodes: six searches. Search time is 30 seconds, whole selection 32 seconds, process watchdog 45 seconds. A fresh ordinary seed/retention/search portfolio receives no exact plan, offered continuation or external incumbent; retained input is absent. The observational overlay records discoveries. For each rule, the smallest tested allowance whose replay-valid selected score strictly beats the old practical (2, 38, 0.12, 160, 0) triggers one offline Next/Multi pair. Those four full runs use the original autonomous start, frozen ordinary planner, 180-second controller and 210-second watchdog. All limits were registered before outcomes.',
97:'The study remains bounded by synthetic costs, discrete start opportunities, limited forecast horizons, nominal future operations and host-specific timing. Only completed certified searches establish finite-model root optima; practical results are feasible outcomes under registered limits. The nine configurations and 36 disclosure runs are not a statistical population sample. The new measured series adds repeated observations of one synthetic scenario on one desktop/JVM, with ordinary background activity neither disabled nor causally attributed. It does not compare alternative solvers or separate the costs of individual decision-pipeline components. Unresolved exact cells, timer-constrained searches and tie-dependent recovery benefits remain substantive limits.',
100:'The completed offline CONTINUE_WAIT execution serves three contacts with Multi and two with Next; the corresponding CANONICAL Multi offline comparison remains unresolved at the controller limit. The new measured-delay campaign tests whether that offline gain survives charging the decision pipeline. All 80 main executions finish. Across 20 pairs per rule, Multi has zero service wins: CONTINUE_WAIT gives 20 losses, while CANONICAL gives five ties and 15 losses. Useful time is lower for Multi in every pair, so all 40 full five-criterion comparisons favor Next. This bounds the tested implementation, host and X01 timing regime, without refuting the scheduling potential of additional visibility or establishing that a faster search would necessarily realize it. Disclosure outcomes also depend on tie order; early activation can expire before delivery. Neither lower compromise probability nor global mission optimality is established.',
103:'Data and code availability: the combined reproduction materials are publicly available at https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/x01-measured-delay-20261028. Download the named reproduction ZIP and its SHA-256 file; GitHub source-only archives omit raw journals and frozen executables. The package contains the earlier AITU evidence and all 88 X01 attempts, registered inputs, hardware/runtime information, analysis and executable hashes. Earlier research and aitu-v2-20261028 releases remain unchanged. The source publication commit is 439bb2e; the measured-controller compatibility commit is b2d04b8. No DOI has been assigned.'
}
METHODS=[
'The X01 measured-delay campaign uses the archived autonomous initial state at t=-2 seconds, without a prepared spare pair. The t=0 state belongs to the preceding root diagnostics; these are different starting observations. All physical costs, payload, key deadlines, actions, tie rules, traversal and objective are retained. Before registration, a compatibility correction included the existing certified-continuation guard and preparation in the measured controller cycle (commit b2d04b8). Thirteen focused tests passed. The new JAR changes only measured-controller classes; 6,703 other JAR entries, including the planner, portfolio and offline controller, remain byte-identical to final-v2.',
'The registered sequence is four offline controls, four measured pilots and 80 main attempts: 20 Next/Multi pairs under each tie rule. Pilots are excluded from main statistics. Fresh Java 21 processes run serially with default JIT/GC, a 1,024 MiB heap and no explicit warmup; Next runs first in odd pairs and Multi in even pairs. Every search allows 150,000 nodes and 30 seconds; whole selection, controller and external watchdog limits are 32, 180 and 210 seconds. The 30/32-second limits were checked in archived full-execution commands rather than inferred from root diagnostics. No scientific attempt is retried and no budget is increased.',
'Monotonic elapsed time covers observation/candidate construction, certified-continuation verification and preparation, seed/retained-plan work, bounded search and final comparison. The executor advances already committed work and physical events by this interval before revalidating the chosen intent. A shifted action cannot inherit an old optimality certificate. Checkpoint I/O, fresh admission, actual execution, JVM startup and final export are excluded from the charged selection interval and reported through their respective host/process measurements. This is not a full model of every onboard computing overhead. Final service scores come from delivered frames; unfinished executions retain labeled prefix metrics and no final score.'
]
RESULTS=[
'The four new offline controls reproduce the CONTINUE_WAIT scores (2, 38, 0.12, 160, 0) for Next and (3, 37, 0.83, 1240, 2) for Multi. CANONICAL Next finishes with the same Next score; CANONICAL Multi reaches the 180-second controller limit and has no final comparison score. All four pilots and all 80 main measured executions finish. Thus 87 of the 88 registered attempts are FINISHED, with the one incomplete offline control preserved separately.',
'Table 5 reports every planned main pair. Next serves two contacts in all 40 main executions. CONTINUE_WAIT Multi serves one in all 20; CANONICAL Multi serves one in 15 and two in five. Useful time is lower for Multi in every pair. The five service ties therefore become losses at the second criterion, and Next wins all 40 full five-coordinate comparisons. Modeled GS+SC operation CPU, management bytes and new directional keys are respectively 0.12 seconds, 160 bytes and zero in every main execution; the differences arise in service and useful time, not these three resource coordinates.',
'Median useful times for Next/Multi are 32.75/17.75 seconds under CONTINUE_WAIT and 32.75/18.125 under CANONICAL. Median host controller elapsed times are 6.857/22.860 and 6.905/21.598 seconds, respectively. These host times differ from modeled operation CPU. Across individual decisions, median [minimum, maximum] elapsed milliseconds are 28.779 [1.083, 728.280] for CW Next, 14.182 [1.541, 15035.710] for CW Multi, 28.461 [1.151, 779.657] for CAN Next and 15.180 [1.269, 18540.615] for CAN Multi. A lower pooled decision median does not imply a cheaper controller: trajectories and decision counts differ, while expensive early decisions consume critical windows.',
'The reconstructed timelines identify missed preparation opportunities in selected witnesses. In main-CONTINUE_WAIT-01, Multi first returns OPEN at t=14.0855122 seconds; its next selection ends at t=21.636010, after C1 closes at t=20. After the first selection there is no new material or pending PREPARE/INSTALL. Even with free resources, PREPARE plus INSTALL needs six seconds and can no longer fit. This necessary timing bound is crossed at the first selection in main-CANONICAL-01 (t=15.2737277) and the second in main-CANONICAL-13 (t=18.9458863). Witnesses are chosen within each rule/service-outcome group by proximity to median Delta U, then pair ID. They support the timing mechanism without isolating the causal cost of DFS or proving it for every run.'
]
DISCUSSION='The new results distinguish a scheduling opportunity from its realization by a particular bounded controller. The unchanged offline control retains a three-versus-two-contact gain, while measured execution realizes no service gain in either tested tie order. Delays in the whole decision pipeline consume time needed for lifecycle work and can invalidate an intended continuation. This is evidence about this implementation and timing regime, not an impossibility result for multi-contact planning. Nor does it prove that replacing DFS alone would restore the gain: no component ablation, alternative solver or faster-platform comparison was performed. The contribution is controlled evidence linking horizon, validity, tie choice and computational timing to realized service, with recovery and confidentiality assessed separately.'
ABSTRACT='Short satellite contacts must carry payload and the work needed to use valid traffic keys. We ask when seeing more future contacts improves joint key and security association scheduling, and whether a bounded controller can realize that gain in time. Next and Multi share actions, safety rules, costs and five ordered service and resource criteria. Exact search applies to a finite event model; the practical controller compares seeds, retained plans and bounded search. In nine exact cases with prescribed preparation, Multi serves one extra contact in two cases, ties in five and leaves two unresolved. Larger budget checks find an autonomous service-improving plan under both tie rules, but only one rule completes the root proof. An offline full run under CONTINUE_WAIT serves three contacts with Multi and two with Next. We then register 88 additional attempts: four offline controls, four measured delay pilots and 80 main runs, with 20 Next/Multi pairs per tie rule. All main runs finish. When the measured decision pipeline consumes contact time, Multi has zero wins and 20 losses in served contacts under CONTINUE_WAIT; CANONICAL has zero wins, five ties and 15 losses. Useful time is lower for Multi in every pair. Selected traces show decision delays consuming time needed for early key work. This tests one controller and host, without isolating the cost of each search component. In 36 earlier disclosure runs, recovery and confidential delivery depend on tie order. The contribution is a joint lifecycle model and controlled evidence separating feasible horizon gains from their timely execution, rather than a universal scheduling or security advantage.'

def main():
    raw=SOURCE.read_bytes();assert sha(raw)==EXPECTED
    BASE.mkdir(exist_ok=True)
    with zipfile.ZipFile(SOURCE) as zin:
        parts={i.filename:zin.read(i) for i in zin.infolist()}
    root=E.fromstring(parts['word/document.xml']);body=root.find('w:body',NS)
    original=list(body);original_xml={i:canon(p) for i,p in enumerate(original)}
    replacements=[];counter=0
    def fresh(node):
        nonlocal counter
        for p in node.iter(W+'p'):
            counter+=1
            for key in list(p.attrib):
                if key.endswith('}paraId') or key.endswith('}textId'):p.set(key,f'{0xBA000000+counter:08X}')
        return node
    def new_p(value,template=55):
        p=fresh(copy.deepcopy(original[template]));set_text(p,value);return p
    def insert_after(anchor,nodes):
        index=list(body).index(anchor)+1
        for node in nodes:body.insert(index,node);index+=1
    for i,value in REPLACEMENTS.items():
        before=text(original[i]);assert before
        set_text(original[i],value);replacements.append({'bodyIndex':i,'before':before,'after':value})
    insert_after(original[55],[new_p('Measured decision-time protocol',77)]+[new_p(t) for t in METHODS])
    # Reuse the original table's design; only the new table gets a new grid.
    table=copy.deepcopy(original[79]);old_rows=table.findall(W+'tr');header=old_rows[0];sample=old_rows[1]
    for row in old_rows:table.remove(row)
    widths=[1300,1400,2900,3754]
    grid=table.find(W+'tblGrid')
    for x,w in zip(grid,widths):x.set(W+'w',str(w))
    cells=[['Rule','Complete pairs','Multi S wins / ties / losses','Delta U (s), median [min; max]'],
           ['CW','20 / 20','0 / 0 / 20','-15 [-15.75; -13]'],['CAN','20 / 20','0 / 5 / 15','-14.25 [-15.5; -11]']]
    for n,values in enumerate(cells):
        row=fresh(copy.deepcopy(header if n==0 else sample))
        for cell,value,w in zip(row.findall(W+'tc'),values,widths):
            paras=cell.findall(W+'p');assert paras
            set_text(paras[0],value)
            for p in paras[1:]:cell.remove(p)
            cell.find('w:tcPr/w:tcW',NS).set(W+'w',str(w))
        table.append(row)
    result_nodes=[new_p('Measured decision latency in X01',77),new_p(RESULTS[0]),
      new_p('Table 5. Main measured-delay series. CW=CONTINUE_WAIT; CAN=CANONICAL. Delta U is Multi minus Next; no main pair is incomplete.',28),
      table,new_p(RESULTS[1]),new_p(RESULTS[2]),new_p(RESULTS[3])]
    insert_after(original[82],result_nodes)
    insert_after(original[91],[new_p(DISCUSSION,91)])
    # The abstract is revised after the substantive sections, per author request.
    assert 250<=len(ABSTRACT.split())<=280 and len(ABSTRACT)<=1900
    before=text(original[5]);set_text(original[5],'Abstract: '+ABSTRACT)
    replacements.append({'bodyIndex':5,'before':before,'after':text(original[5])})
    changed={x['bodyIndex'] for x in replacements}
    assert all(canon(p)==original_xml[i] for i,p in enumerate(original) if i not in changed)
    for i in changed:
        # Existing paragraph and run formatting remain untouched.
        old=E.fromstring(original_xml[i]);new=original[i]
        assert canon(old.find(W+'pPr'))==canon(new.find(W+'pPr'))
        assert [canon(r.find(W+'rPr')) if r.find(W+'rPr') is not None else None for r in old.findall(W+'r')]==[canon(r.find(W+'rPr')) if r.find(W+'rPr') is not None else None for r in new.findall(W+'r')]
    xml=E.tostring(root,encoding='UTF-8',xml_declaration=True,standalone=True)
    tmp=BASE/'article-updated.docx'
    with zipfile.ZipFile(SOURCE) as zin,zipfile.ZipFile(tmp,'w',zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():zout.writestr(info,xml if info.filename=='word/document.xml' else zin.read(info))
    with zipfile.ZipFile(tmp) as z:
        assert z.testzip() is None
        assert all(z.read(name)==data for name,data in parts.items() if name!='word/document.xml')
    fulltext='\n'.join(root.xpath('//w:t/text()',namespaces=NS))
    assert 'Whether the newly observed offline gain survives charging computation time remains untested' not in fulltext
    assert '[TO BE ADDED AFTER PUBLIC DEPOSITION]' not in fulltext
    assert len(root.xpath('//m:oMath',namespaces=NS))==3
    assert canon(original[-1])==original_xml[len(original)-1]
    assert [text(p) for p in original[105:125]]==[text(E.fromstring(original_xml[i])) for i in range(105,125)]
    DEST.write_bytes(tmp.read_bytes())
    record={'status':'STRUCTURAL_PASS','sourceSha256':EXPECTED,'docxSha256':sha(DEST.read_bytes()),
      'changedParts':['word/document.xml'],'unchangedPackageParts':len(parts)-1,
      'unchangedOriginalBodyElements':len(original)-len(changed),'originalParagraphFormattingPreserved':True,
      'sectionPropertiesPreserved':True,'originalTablesAndImagesPreserved':True,'nativeEquations':3,'referencesUnchanged':20,
      'abstractWords':len(ABSTRACT.split()),'abstractCharacters':len(ABSTRACT),'abstractEditedLast':True,
      'replacements':replacements,'methodsAdded':METHODS,'resultsAdded':RESULTS,'discussionAdded':DISCUSSION,
      'newTable':cells,'newScientificAttempts':0,'userApproval':'Explicit article-edit request after result discussion, 2026-10-28',
      'renderReview':'PENDING'}
    (BASE/'revision.json').write_text(json.dumps(record,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')
    (BASE/'article-text.txt').write_text(fulltext,encoding='utf-8')
    print(json.dumps({k:v for k,v in record.items() if k not in ['replacements','methodsAdded','resultsAdded','discussionAdded','newTable']},indent=2))

if __name__=='__main__':main()
