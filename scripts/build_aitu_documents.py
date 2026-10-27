"""Build editable DOCX and equivalent LaTeX from the audited AITU revision."""
import json,re,hashlib,zipfile,shutil,copy
from pathlib import Path
from decimal import Decimal
from docx import Document
from docx.shared import Pt,Cm,RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT,WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.style import WD_STYLE_TYPE
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'docs/aitu-revision';DATA=ROOT/'target/aitu-revision'
TEMPLATE=DATA/'reference-materials/aitu-article-template.docx'
PRESERVE=['word/theme/theme1.xml','word/fontTable.xml','word/numbering.xml','word/webSettings.xml','word/header1.xml','word/footnotes.xml','word/endnotes.xml']
assert hashlib.sha256(TEMPLATE.read_bytes()).hexdigest()=='d30c35b2d97c04dd69453d517d701cd4d8b78c7963e57001b6f31d3803a7587d'
content=json.loads((OUT/'article-content.json').read_text(encoding='utf-8'))
def load(p):return json.loads(p.read_text(encoding='utf-8'))
def fmt(x):
 if x is None:return '—'
 try:
  d=Decimal(str(x));return format(d.quantize(Decimal('.001')),'f').rstrip('0').rstrip('.')
 except:return str(x)
def score(r):return ', '.join(fmt(r.get(k)) for k in ['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys']) if r.get('fiveStatus')=='FINAL' else 'unavailable'
def reference_parts(ref):
 spans={'iota':'Computers, Materials & Continua, 86','cgr':'Journal of Network and Computer Applications, 174','zhao':'Research Briefs on Information and Communication Technology Evolution, 6','kwon':'IEEE Transactions on Information Forensics and Security, 20','madoery':'IEEE Journal of Radio Frequency Identification, 8','bpsec_analysis':'IACR Communications in Cryptology, 1','rhc':'2010 IEEE International Symposium on Computer-Aided Control System Design','bermuda':'ICT systems security and privacy protection'}
 text=ref['apa'];token=spans.get(ref['key'],ref['title']);start=text.find(token,text.find('). ')+3)
 if start<0:return [(text,False)]
 return [(text[:start],False),(token,True),(text[start+len(token):],False)]
def supplement():
 b=[]
 def p(t):
  t=re.sub(r'\b([A-Za-z_]{2,})(?=\d)',lambda m:m[0] if m[0] in ['stage','SHA','CP'] else m[0]+' ',t)
  t=re.sub(r'(?<=\d)(?=(?:s\b|ms\b|nodes\b|rollout\b|FINISHED\b|attempts\b))',' ',t)
  t=re.sub(r':(?=[A-Za-z])',': ',t)
  t=re.sub(r';(?=\S)','; ',t)
  t=re.sub(r'(?<=[a-z])(?=\()', ' ',t)
  t=re.sub(r'(?<=\))(?=[a-z])',' ',t)
  for a,z in [('andfive','and five'),('andtwo','and two'),('onlytwo','only two'),('at−2','at −2'),('from−2','from −2'),('with10','with 10'),('1GiBheap','1 GiB heap'),('JVM,1','JVM, 1'),('attempts:105','attempts: 105'),('node 134269:from','node 134269: from')]:t=t.replace(a,z)
  b.append({'type':'p','text':t})
 def h(t):b.append({'type':'heading','text':t})
 def table(t,headers,rows,widths=None):b.append({'type':'table','caption':t,'headers':headers,'rows':rows,'widths':widths})
 h('Scope and evidence identity')
 p('This supplement belongs to the AITU revision of When Future Contacts Improve Satellite Key and Security Association Management. It documents a new bounded campaign and preserves the prior final-v2 campaign separately. Full per-cell tables are provided as CSV and JSON; raw events, checkpoints, observations, plans, candidate portfolios, invocations and process logs are in the reproduction package. Rounded values here do not replace the machine-readable precision.')
 p('The prior campaign has108 attempts,93 FINISHED and15 PROCESS_TIMEOUT. New execution comprises110 full-controller attempts:105 FINISHED and5 SEARCH_INCOMPLETE. Twelve additional paired-root diagnostics have11 exact COMPLETE and1 exact INCOMPLETE. Six reused logical cells have explicit origins:four prepared exact X01 cells in the synthetic family andtwo autonomous practical X/CANONICAL/5000-node cells in the budget series. No registered new attempt is missing.')
 p('The frozen final-v2 JAR and its SHA256 are identified in the reproduction manifest. A separate harness exports matched initial states. A separate root-only planner overlay records incumbent changes; normal controllers never load it. Focused validation matched the frozen planner’s complete plan, score, first action, status and node count on a fixed-node example. This is a bounded instrumentation check, not universal zero-overhead equivalence.')
 h('Matched initialization and diagnostic replay')
 p('Table S1 reports full controller outcomes. Values are S, U seconds, C modeled CPU seconds, B management bytes, K newly created directional keys. Preparation at−2 through0 is actual execution and remains charged. CAN=CANONICAL; CW=CONTINUE_WAIT; E=exact; P=practical. Unfinished scores are unavailable, not the best incumbent projected as a final mission result.')
 r=load(DATA/'stage1/tables.json')
 table('Table S1. Matched full-controller runs.',['Initial','E/P','H','Rule','Status','S, U, C, B, K'],[[x['initialization'],'E' if x['mode']=='exact' else 'P',str(x['horizon']),'CAN' if x['rule']=='CANONICAL' else 'CW','FINISHED' if x['status']=='FINISHED' else 'INCOMPLETE',score(x)] for x in r],[2.8,1.2,0.7,1.3,3,7.5])
 p('The analyzer checks eight exact/practical initial snapshot pairs and eight observation pairs at fixed H/rule. All state values and ordered lists are retained; only explicitly Set-valued fields are normalized for JSON ordering. Four actual t=0 controller observations also equal the corresponding autonomous H2 diagnostic observations. This is stronger than comparing SecurityState or five scores alone.')
 p('At autonomous t=0/H2, the first five-score improvement occurs at node134269:from(2,38,0.12,160,0)to(3,37,0.83,1240,2). It takes8.1449003s CAN and8.2851468s CW with instrumentation. Practical searches stop at NODE_LIMIT5000 after0.4964998s and0.5750573s; their one-second timer has not fired. CW proves COMPLETE at134638nodes. CAN remains INCOMPLETE at30s after507296nodes, despite finding the service-improving tuple. Its1027 incumbent updates contain onlytwo distinct five-tuples; later updates improve the full plan order. This does not establish tie handling as the sole source of runtime.')
 p('The COMPLETE164-action CW exact plan passes ordinary BranchEngine admissibility and ContinuationEvaluator with identical score and plan. The selected comparator prefers it to the ordinary practical result. A disposable diagnostic portfolio accepts it as FEASIBLE_RETAINED; ordinary controllers receive no oracle. Actual practical t=0 seed and retained candidates both score(2,38,0.32,160,2); its selected search candidate scores(2,38,0.12,160,0).')
 p('The t=0 first actions are both OPEN. The first actual disagreement is decision54 at13.75s:exact PREPARE(A,g1) until15.75, practical CONTINUE until14. Observations are equal. Exact uses SUFFIX_REUSE from decision3, suffixOffset51, checkedOffset50, guard ALLOWED/ONE_FRAME_NOMINAL. Full per-decision snapshots are not exported, so this observation equality is not relabeled a fresh snapshot-equality proof. Evidence:stage1/runs/run-autonomous-{exact,practical}-H2-CONTINUE_WAIT/actual.full-trace.json.')
 h('Finite synthetic family')
 p('All settings were chosen from executor rules before outcomes. Base C1=A[0,20], C2=B[30,50], C3=A[T,T+L], age400, reuse gap200 seconds. The2-second window is below even the3-second already-active REUSE chain; the430-second return is after the age deadline of every key created by C1’s end. Initial deadlines and new-key deadlines are independently checked against creation/activation history. Both tie rules agree in status and final five-tuples for every listed row.')
 r=load(DATA/'stage2/tables.json');models=[(80,5,150),(80,2,150),(80,9,150),(60,5,150),(180,5,150),(80,5,180),(80,5,90),(180,5,240),(430,5,150)]
 rows=[]
 for i,(t,l,cp) in enumerate(models,1):
  pair=[next(x for x in r if x['family']==f'X{i:02d}' and x['horizon']==hh and x['rule']=='CANONICAL') for hh in [1,2]]
  rows.append([f'X{i:02d}',f'{t}/{l}/{cp}',score(pair[0]),score(pair[1]) if pair[1]['status']=='FINISHED' else 'SEARCH_INCOMPLETE'])
 table('Table S2. Synthetic configuration and final five-tuples; both rules.',['Case','T/L/P (s)','Next','Multi'],rows,[1.3,2.5,6.35,6.35])
 h('Computational budget and timing')
 p('Exact:2,000,000nodes/30s; practical:specified node allowance/1s search/2s decision. Seed and retention each350ms with10,000rollout transitions. Controller120s; independent process watchdog150s. Root diagnostic watchdog60s. Single JVM,1GiBheap, no outcome-driven budget escalation. The zero-node control has DFS disabled while preserving seeds and naturally retained suffixes. The original SEED_WITHOUT_SEARCH setting was unsuitable because it also disabled retention; a named separate adapter passed zero-planner-call and ordinary-portfolio equivalence checks. Its first compile failure and corrected v2 artifacts are retained, not treated as experiment attempts.')
 p('The budget series is CANONICAL only. X is autonomous; long is the three-contact original-window ISS demand control, not a full-day campaign. Table S3 separates modeled CPU from host CPU. Host figures are single-run observations, not a statistical speed benchmark. Search node counts are summed across actual decisions; a nominal per-search limit is not a total-run budget.')
 r=sorted(load(DATA/'stage3/tables.json'),key=lambda x:(x['caseLabel'],x['measured'],x['nodes'],x['horizon']))
 table('Table S3. Full-run objective and charged decision delay.',['Case','Delay','Node cap','H','S, U, C, B, K','Charged (s)'],[[x['caseLabel'],'measured' if x['measured'] else 'offline',str(x['nodes']),str(x['horizon']),score(x),fmt(x['chargedDecisionDelay'])] for x in r],[1.3,2,2,0.7,8,2.5])
 table('Table S4. Search and controller measurements for the same cells; O=offline, M=measured.',['Case','Mode/cap/H','Calls','Nodes','Host (s)','CPU (s)','Stops'],[[x['caseLabel'],f"{'M' if x['measured'] else 'O'}/{x['nodes']}/{x['horizon']}",str(x['searches']),str(x['searchNodes']),fmt(x['controllerSeconds']),fmt(x['hostThreadCpuSeconds']),', '.join(k.replace('_LIMIT','')+':'+str(v) for k,v in json.loads(x['searchStopReasons']).items())] for x in r],[1.2,3,1.5,2.3,2,2,4.5])
 p('Detailed tables additionally retain search elapsed/thread CPU, search statuses, decisions, process elapsed, untruncated computed latency and charged decision intervals. No-search cells have0 search calls, not a claim of exact completion. Charged delay sums model availability minus decision origin, clipped by actual execution; it is distinct from total host runtime. CPU telemetry may differ slightly from elapsed at short durations because of measurement resolution.')
 h('Disclosure model and metric definitions')
 p('E=2026-10-12T00:14:59.990386963Z. Compromise E−40/E−20/E−5, delay0/2/20 seconds, same active-pair target rule and geometry for all policies. All36 runs FINISHED, all36 targets DISCLOSED, all36 independent-frame milestones observed. No NO_TARGET or censored values occurred in this grid. The measurement implementation still represents missing events explicitly and does not extrapolate incomplete runs.')
 p('Each milestone’s origin is actual ground notice. Spacecraft notice is confirmed revocation receipt. Independent pair requires both directional keys to be agreed active, valid, and outside the disclosed set. Independent SA requires those materials bound to an open admissible context. Independent frame requires complete actual delivery. Earlier readiness is marked already_ready when applicable, never imputed from a missing event. Full delay is compromise-to-ground plus ground-to-frame, using actual timestamps. Subsequent expiration and closure delimit readiness; the first milestone is not continuous readiness.')
 p('Confidential delivered bytes count complete delivered frames whose keys are not disclosed during the modeled run. Exposure instead integrates emitted byte ranges, including incomplete frames, split before and after compromise. Retrospective disclosure can therefore invalidate confidentiality of earlier deliveries. These metrics do not measure attack probability or implementation-level cryptographic security. Directional replacements are modeled independent, and the management channel remains protected.')
 r=load(DATA/'stage4/tables.json')
 for rule in ['CANONICAL','CONTINUE_WAIT']:
  rr=[x for x in r if x['rule']==rule]
  table('Table S5'+('a' if rule=='CANONICAL' else 'b')+'. Recovery milestones under '+rule+'; all values seconds after ground notice except Total.',['E offset / delay','H','SC notice','Pair','SA','Frame','Total'],[[f"−{x['secondsBeforeContactEnd']}/{x['detectionDelaySeconds']}",str(x['horizon']),fmt(x['spacecraft_noticeSeconds']),fmt(x['independent_pairSeconds']),fmt(x['independent_saSeconds']),fmt(x['independent_frameSeconds']),fmt(x['compromiseToIndependentFrame'])] for x in rr],[2.5,.7,2.3,2.7,2.7,2.8,2.8])
  table('Table S6'+('a' if rule=='CANONICAL' else 'b')+'. Service and traffic under '+rule+'.',['Offset / delay / H','S, U, C, B, K','Confid. B','Exposed before B','Exposed after B'],[[f"−{x['secondsBeforeContactEnd']}/{x['detectionDelaySeconds']}/{x['horizon']}",score(x),str(x['confidentialDeliveredBytes']),str(x['decryptableBeforeBytes']),str(x['decryptableAfterBytes'])] for x in rr],[3,6.3,2.3,2.7,2.2])
 p('CANONICAL Multi has earlier independent delivery in7/9 pairs and1,000 additional confidential bytes in5/9; CONTINUE_WAIT ties both metrics in9/9. Spacecraft notice is later for Multi in three CANONICAL pairs, demonstrating that the stages need not improve together. All original served counts are3. Complete search-cost columns and raw timestamps for every disclosure run remain in stage4/tables.csv rather than duplicating those columns here.')
 h('Readiness and overlapping waits')
 p('At E−5/delay0/CANONICAL/H2, ground notice is00:14:54.990386963. Spacecraft notice follows0.4s later; g2 activates at00:14:56.390386963,1.4s after detection. It remains valid until01:44:56.390386963 and expires before return. Generation g3 activates at01:47:23.202056885; its SA opens one second later; first frame arrives01:47:34.202056885. Total ground-to-frame is5559.211669922s, identical to Next. Table S7 gives all independent readiness intervals for this example; the complete per-run interval records are in stage4/audit.json.')
 a=load(DATA/'stage4/audit.json')['runs']['Eminus5-delay0-H2-CANONICAL']['recovery']
 table('Table S7. Independent readiness in the expiry-before-return example.',['Kind / generation','From UTC','Until UTC','Duration (s)','End'],[[x['kind'].replace('independent_','')+' / '+('g2' if ':g2:' in x['keyIds']['uplink'] else 'g3'),x['from'].split('T')[1],x['until'].split('T')[1],fmt(x['durationSeconds']),x['endReason']] for x in a['readinessIntervals']],[3,4,4,2,3.5])
 p('Condition intervals are simultaneous predicates, not additive independent causes. In this example no target contact lasts5538.211669922s and no offered target frame5633.768066406s across the full observation window, overlapping each other and the material/SA waits. The lack of independent material lasts145.811669922s. Summing these values would double count intervals and mix pre-delivery and post-delivery observation. Readiness/condition CSVs retain explicit interval endpoints for every run.')
 h('Analytical boundaries and scientific interpretation')
 p('The installed/prepared timing totals apply to the named chains with the declared profile and full-frame service thresholds. They do not prove a universal minimum contact length. The Knew≥2S bound assumes empty initial keys, two positive directional thresholds, and no valid cross-return reuse. It constrains created directional keys, not the remaining objective coordinates. COMPLETE refers to the finite root problem; neither practical FINISHED nor a receding sequence of root optima proves global mission optimality.')
 p('Common preparation removes the observed prepared exact/practical gap. Autonomous CW establishes a valid but undiscovered practical improvement; autonomous CAN remains unresolved under its exact budget. Seven complete synthetic settings yieldtwo gains andfive ties; two settings remain unknown. Offline budget cases do not justify Multi; selected measured delays worsen three pairs. Disclosure gains are not robust across the two tested tie orders, and two rules cannot characterize all possible tie-breaking schemes.')
 h('Reproduction and publication boundaries')
 p('Registered input models, budget files, runtime identities, all process statuses and diagnostics, and postprocessing code are provided separately from the historical archives. See report.md, REPRODUCE.md and manifest.json. New source adapters do not modify the production controller or frozen JAR. Independent event accounting reconstructs costs, complete-frame service, confidentiality and recovery; full comparator checks cover saved portfolio candidates. Historical preservation hashes are checked again at packaging. No full Maven campaign or new orbital propagation was required for this harness-based revision.')
 p('Only Mukhammed Zhumali and262937@astanait.edu.kz are confirmed by the supplied manuscript. Degree, position, department, organization, country, ORCID, corresponding-author designation, funding and competing interests require author confirmation. The bibliography has20 relevant entries, but the Scopus/Web of Science share has not been certified and several necessary older sources exceed age guidance. Native editable Word equations are not MathType objects. These issues, listed in submission-checklist.md, prevent a claim that the article is submission-ready.')
 return {'title':'Supplementary evidence for satellite security lifecycle planning','author':content['author'],'email':content['email'],'blocks':b,'references':[]}

def runfont(run,bold=None):
 run.font.name='Times New Roman';run.font.size=Pt(12);run.font.color.rgb=RGBColor(0,0,0)
 if bold is not None:run.bold=bold
 run._element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'),'Times New Roman')
def para(doc,text='',style=None,bold=False,center=False,indent=True):
 p=doc.add_paragraph(style=style);p.paragraph_format.line_spacing=1;p.paragraph_format.space_before=Pt(0);p.paragraph_format.space_after=Pt(0)
 p.paragraph_format.first_line_indent=Cm(1.25 if indent else 0);p.paragraph_format.widow_control=True
 p.alignment=WD_ALIGN_PARAGRAPH.CENTER if center else WD_ALIGN_PARAGRAPH.JUSTIFY
 runfont(p.add_run(text),bold);return p
def mathrun(text):
 r=OxmlElement('m:r');pr=OxmlElement('m:rPr');sty=OxmlElement('m:sty');sty.set(qn('m:val'),'p');pr.append(sty);r.append(pr)
 wr=OxmlElement('w:rPr');sz=OxmlElement('w:sz');sz.set(qn('w:val'),'24');wr.append(sz);r.append(wr)
 t=OxmlElement('m:t');t.text=text;r.append(t);return r
def subscript(base,sub):
 s=OxmlElement('m:sSub');e=OxmlElement('m:e');e.append(mathrun(base));u=OxmlElement('m:sub');u.append(mathrun(sub));s.extend([e,u]);return s
def equation(doc,b):
 p=para(doc,indent=False);p.paragraph_format.tab_stops.add_tab_stop(Cm(15.8));m=OxmlElement('m:oMath')
 if b['number']==1:nodes=[mathrun('d = min(c + A, a + P, '),subscript('d','committed'),mathrun(')')]
 elif b['number']==2:nodes=[subscript('maximize','lex'),mathrun(' J = (S, U, −C, −B, −K)')]
 else:nodes=[subscript('K','new'),mathrun(' ≥ 2S')]
 for n in nodes:m.append(n)
 p._p.append(m);runfont(p.add_run('\t('+str(b['number'])+')'));p.paragraph_format.space_before=Pt(5);p.paragraph_format.space_after=Pt(5)
def addtable(doc,b):
 cap=para(doc,b['caption'],indent=False);cap.paragraph_format.keep_with_next=True;cap.paragraph_format.space_before=Pt(5)
 t=doc.add_table(rows=1,cols=len(b['headers']));t.alignment=WD_TABLE_ALIGNMENT.CENTER;t.autofit=False
 widths=b.get('widths') or [16.5/len(b['headers'])]*len(b['headers']);scale=16.5/sum(widths);widths=[w*scale for w in widths]
 for col,w in zip(t.columns,widths):col.width=Cm(w)
 for rownum,values in enumerate([b['headers']]+b['rows']):
  row=t.rows[0] if rownum==0 else t.add_row()
  trpr=row._tr.get_or_add_trPr();cant=OxmlElement('w:cantSplit');trpr.append(cant)
  if rownum==0:trpr.append(OxmlElement('w:tblHeader'))
  for j,(cell,value) in enumerate(zip(row.cells,values)):
   cell.width=Cm(widths[j]);cell.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
   cp=cell._tc.get_or_add_tcPr();marg=OxmlElement('w:tcMar')
   for side,val in [('top',55),('bottom',55),('left',65),('right',65)]:
    el=OxmlElement('w:'+side);el.set(qn('w:w'),str(val));el.set(qn('w:type'),'dxa');marg.append(el)
   cp.append(marg)
   if rownum==0:
    shade=OxmlElement('w:shd');shade.set(qn('w:fill'),'E7E6E6');cp.append(shade)
   p=cell.paragraphs[0];p.paragraph_format.first_line_indent=Cm(0);p.paragraph_format.line_spacing=1;p.paragraph_format.space_after=Pt(0);p.paragraph_format.space_before=Pt(0)
   p.alignment=WD_ALIGN_PARAGRAPH.LEFT if j==0 or len(str(value))>22 else WD_ALIGN_PARAGRAPH.CENTER
   runfont(p.add_run(str(value)),rownum==0)
  # Keep individual rows intact, but let a long table span pages.
 borders=OxmlElement('w:tblBorders')
 for side in ['top','left','bottom','right','insideH','insideV']:
  e=OxmlElement('w:'+side);e.set(qn('w:val'),'single');e.set(qn('w:sz'),'4');e.set(qn('w:color'),'D9D9D9');borders.append(e)
 t._tbl.tblPr.append(borders)
 after=para(doc,'',indent=False);after.paragraph_format.line_spacing=0.35;after.paragraph_format.space_after=Pt(0)
def build_doc(data,name):
 path=OUT/name;shutil.copy2(TEMPLATE,path);doc=Document(path)
 body=doc._element.body
 for el in list(body):
  if el.tag!=qn('w:sectPr'):body.remove(el)
 for sec in doc.sections:
  sec.page_width=Cm(21);sec.page_height=Cm(29.7);sec.top_margin=Cm(2);sec.bottom_margin=Cm(2.5);sec.left_margin=Cm(3);sec.right_margin=Cm(1.5)
  sec.header_distance=Cm(.6);sec.footer_distance=Cm(1)
  for p in sec.footer.paragraphs:
   p.clear();p.alignment=WD_ALIGN_PARAGRAPH.CENTER
  pp=sec.footer.paragraphs[0];r=pp.add_run();f=OxmlElement('w:fldSimple');f.set(qn('w:instr'),'PAGE');r._r.addnext(f)
 for name_ in ['Normal','Title','Heading 1','Heading 2','Abstract/Summary','Authors','Paragraph','References and notes','figure caption']:
  if name_ not in doc.styles:doc.styles.add_style(name_,WD_STYLE_TYPE.PARAGRAPH)
  s=doc.styles[name_];s.font.name='Times New Roman';s.font.size=Pt(12);s.font.color.rgb=RGBColor(0,0,0)
  s.font.small_caps=False;s.font.all_caps=False
  if name_ in ['Heading 1','Heading 2']:
   s.font.italic=False
   pp=s.element.get_or_add_pPr()
   for el in list(pp):
    if el.tag==qn('w:numPr'):pp.remove(el)
   np=OxmlElement('w:numPr');nid=OxmlElement('w:numId');nid.set(qn('w:val'),'0');np.append(nid);pp.append(np)
  pf=s.paragraph_format;pf.line_spacing=1;pf.space_before=Pt(0);pf.space_after=Pt(0)
  if s.element.pPr is not None:
   for el in list(s.element.pPr):
    if el.tag==qn('w:pBdr'):s.element.pPr.remove(el)
 doc.styles['Title'].font.bold=True
 para(doc,'DOI: [assigned by the editors]',indent=False)
 para(doc,data.get('author',''),style='Authors',bold=True,indent=False)
 para(doc,data.get('email',''),indent=False)
 if data.get('authorFields'):para(doc,data['authorFields'],indent=False)
 titlep=para(doc,data['title'],style='Title',bold=True,center=True,indent=False);titlep.paragraph_format.space_before=Pt(10);titlep.paragraph_format.space_after=Pt(8)
 if data.get('abstract'):
  p=para(doc,'Abstract: '+data['abstract']);p.runs[0].bold=False
  para(doc,'Keywords: '+data['keywords'])
 for b in data['blocks']:
  typ=b['type']
  if typ=='p':para(doc,b['text'])
  elif typ in ['heading','subheading']:
   p=para(doc,b['text'],style='Heading 1' if typ=='heading' else 'Heading 2',bold=True);p.paragraph_format.space_before=Pt(8 if typ=='heading' else 5);p.paragraph_format.keep_with_next=True
  elif typ=='equation':equation(doc,b)
  elif typ=='table':addtable(doc,b)
  elif typ=='figure':
   p=para(doc,center=True,indent=False);p.paragraph_format.keep_with_next=True;p.paragraph_format.space_before=Pt(4)
   p.add_run().add_picture(str(OUT/'figures'/(b['name']+'.png')),width=Cm(16.3))
   cap=para(doc,b['caption'],center=True,indent=False);cap.paragraph_format.space_after=Pt(5)
 if data.get('references'):
  p=para(doc,'References',style='Heading 1',bold=True);p.paragraph_format.keep_with_next=True;p.paragraph_format.space_before=Pt(8)
  for ref in data['references']:
   p=para(doc,f"[{ref['number']}] ",indent=False)
   for value,italic in reference_parts(ref):
    run=p.add_run(value);runfont(run);run.italic=italic
   p.alignment=WD_ALIGN_PARAGRAPH.LEFT;p.paragraph_format.left_indent=Cm(.7);p.paragraph_format.first_line_indent=Cm(-.7);p.paragraph_format.keep_together=True
 upd=OxmlElement('w:updateFields');upd.set(qn('w:val'),'true');doc.settings.element.append(upd)
 doc.core_properties.title=data['title'];doc.core_properties.author=data.get('author','');doc.core_properties.subject='AITU revision with registered bounded experiments'
 doc.save(path)
 # Restore preserve-only template parts byte for byte, leaving intended body/styles/footer changes.
 with zipfile.ZipFile(TEMPLATE) as z:preserved={p:z.read(p) for p in PRESERVE}
 with zipfile.ZipFile(path) as z:parts={n:z.read(n) for n in z.namelist()}
 parts.update(preserved)
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
  for n,raw in parts.items():z.writestr(n,raw)
 with zipfile.ZipFile(path) as z:assert all(z.read(p)==raw for p,raw in preserved.items())
 return path

def texescape(text):
 text=str(text)
 # Let hyperref line-break URLs rather than treating them as long plain tokens.
 tokens=re.split(r'(https?://\S+)',text);out=[]
 for token in tokens:
  if token.startswith(('https://','http://')):out.append(r'\url{'+token+'}')
  else:
   mapping={'\\':r'\textbackslash{}','&':r'\&','%':r'\%','$':r'\$','#':r'\#','_':r'\_','{':r'\{','}':r'\}','~':r'\textasciitilde{}','^':r'\textasciicircum{}','−':'$-$','≥':r'$\geq$','→':r'$\rightarrow$','≤':r'$\leq$','–':'--','—':'---','…':r'\ldots{}','’':"'",'“':'``','”':"''"}
   out.append(''.join(mapping.get(c,c) for c in token))
 return ''.join(out)
def build_tex(data,name):
 t=[r'\documentclass[12pt,a4paper]{article}',r'\usepackage[top=2cm,bottom=2.5cm,left=3cm,right=1.5cm]{geometry}',r'\usepackage[T1]{fontenc}',r'\usepackage[utf8]{inputenc}',r'\usepackage{newtxtext,newtxmath}',r'\usepackage{graphicx,longtable,array,booktabs,amsmath}',r'\usepackage[hidelinks]{hyperref}',r'\usepackage{caption}',r'\captionsetup{font=normalsize}',r'\setlength{\parindent}{1.25cm}',r'\setlength{\parskip}{0pt}',r'\sloppy',r'\begin{document}',r'\noindent DOI: [assigned by the editors]\par',r'\noindent\textbf{'+texescape(data['author'])+r'}\par',r'\noindent '+texescape(data['email'])+r'\par']
 if data.get('authorFields'):t.append(r'\noindent '+texescape(data['authorFields'])+r'\par')
 t+=[r'\begin{center}\textbf{'+texescape(data['title'])+r'}\end{center}']
 if data.get('abstract'):t.extend([r'\textbf{Abstract:} '+texescape(data['abstract'])+'\n',r'\textbf{Keywords:} '+texescape(data['keywords'])+'\n'])
 for b in data['blocks']:
  if b['type']=='p':t.append(texescape(b['text'])+'\n')
  elif b['type'] in ['heading','subheading']:t.append(('\\section*{' if b['type']=='heading' else '\\subsection*{')+texescape(b['text'])+'}')
  elif b['type']=='equation':t.append(r'\begin{equation}'+b['tex']+r'\tag{'+str(b['number'])+r'}\end{equation}')
  elif b['type']=='figure':t.extend([r'\begin{figure}[htbp]\centering',r'\includegraphics[width=\linewidth]{figures/'+b['name']+'.png}',r'\caption*{'+texescape(b['caption'])+'}',r'\end{figure}'])
  elif b['type']=='table':
   n=len(b['headers']);ww=b.get('widths') or [16.5/n]*n;usable=15.5-n*.15;ww=[x/sum(ww)*usable for x in ww]
   t+=[r'\begin{longtable}{'+''.join(r'>{\raggedright\arraybackslash}p{'+str(round(x,2))+'cm}' for x in ww)+'}',r'\caption*{'+texescape(b['caption'])+r'}\\',r'\toprule',' & '.join(r'\textbf{'+texescape(x)+'}' for x in b['headers'])+r'\\\midrule\endfirsthead',' & '.join(r'\textbf{'+texescape(x)+'}' for x in b['headers'])+r'\\\midrule\endhead']
   t+=[' & '.join(texescape(x) for x in row)+r'\\' for row in b['rows']];t+=[r'\bottomrule\end{longtable}']
 if data.get('references'):
  t.append(r'\section*{References}')
  for r in data['references']:t.append(r'\noindent\hangindent=0.7cm ['+str(r['number'])+'] '+''.join((r'\textit{'+texescape(v)+'}') if italic else texescape(v) for v,italic in reference_parts(r))+r'\par')
 t.append(r'\end{document}')
 (OUT/'article-tex'/name).write_text('\n'.join(t)+'\n',encoding='utf-8')

if __name__=='__main__':
 sup=supplement();(OUT/'supplement-content.json').write_text(json.dumps(sup,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 paths=[build_doc(content,'article.docx'),build_doc(sup,'supplement.docx')]
 build_tex(content,'main.tex');build_tex(sup,'supplement.tex')
 (OUT/'article-tex/figures').mkdir(exist_ok=True)
 for f in (OUT/'figures').glob('*.png'):shutil.copy2(f,OUT/'article-tex/figures'/f.name)
 audit={'templateSha256':hashlib.sha256(TEMPLATE.read_bytes()).hexdigest(),'preserveOnlyParts':PRESERVE,'articleAbstractWords':len(content['abstract'].split()),'articleAbstractCharacters':len(content['abstract']),'titleCharacters':len(content['title']),'referenceCount':len(content['references']),'equationFormat':'editable native OMML, NOT MathType','files':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}}
 (DATA/'document-build.json').write_text(json.dumps(audit,indent=2)+'\n')
 print(json.dumps(audit,indent=2))
