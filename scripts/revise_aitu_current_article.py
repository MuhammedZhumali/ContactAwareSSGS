"""Editorial update from the author-confirmed DOCX; no scientific execution.

Preserves the original package design and unedited body elements. Historical
inputs remain under target/aitu-current-editorial/inputs; outputs have new names.
"""
import copy
import hashlib
import json
import re
import zipfile
from pathlib import Path
from lxml import etree as E
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / 'target/aitu-current-editorial'
OUT = ROOT / 'docs/aitu-revised-final'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main',
      'a': 'http://schemas.openxmlformats.org/drawingml/2006/main',
      'wp': 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing',
      'pic': 'http://schemas.openxmlformats.org/drawingml/2006/picture',
      'r': 'http://schemas.openxmlformats.org/officeDocument/2006/relationships',
      'm': 'http://schemas.openxmlformats.org/officeDocument/2006/math'}
W = '{' + NS['w'] + '}'
R = 'http://schemas.openxmlformats.org/package/2006/relationships'
URL = 'https://github.com/MuhammedZhumali/ContactAwareSSGS/releases/tag/x01-measured-delay-20261028'

REPLACE = {
0: 'DOI: assigned by the journal upon publication',
39: 'Seeds and retained candidates use the same admissibility checks as search. Retention replays from the current root, preserving progress, costs, counters and issued IDs; it never reuses the old score. If no terminal candidate exists, an admissible local fallback is used. The Supplement gives the complete seed filters and source tie handling.',
40: 'Experimental design',
41: 'The primary latency test is the registered X01 campaign: four offline controls, four pilots and 80 main executions (Table 2). Pilots are excluded from main comparisons. Earlier offline experiments establish the mechanism, preparation effect and plan discovery; their histories are in the Supplement. The current fixed-budget series does not define a budget-quality curve.',
42: 'Table 2. X01 attempts; the only unfinished run is the offline CANONICAL Multi control.',
45: 'The offline reference family uses exact search with a 2,000,000-node and 30-second limit; bounded comparisons also test a 5,000-node, one-second search. The subsequent autonomous root checks increase the search-time cap to 30 seconds. These are finite limits, not claims of full enumeration at every allowance. The Supplement separates all reference budgets from the current measured campaign.',
48: 'Fig. 1. X01 horizon mechanism: earlier installation can reduce the time needed to serve C3 from 8 s to 4 s if all lifecycle constraints hold. Illustrative timing requirements, not an execution trace.',
57: 'Autonomous root diagnostics restore the same t=0, H=2 observation for both tie rules and independently allow 100,000, 150,000 or 200,000 nodes with a 30-second search cap. The ordinary seed/retention/search portfolio receives no oracle plan or external incumbent. The smallest tested allowance whose replay-valid score improves on (2, 38, 0.12, 160, 0) is then used in an offline Next/Multi execution. Detailed limits and provenance are in the Supplement.',
59: 'The measured campaign starts autonomously at t=-2 seconds without a prepared spare pair; the earlier t=0 root diagnostics are a separate task. Physical costs, payload, key deadlines, actions, tie rules, traversal and objective are retained. Before registration, correction b2d04b8 included the existing certified-continuation checks and preparation in the measured controller cycle. It changes neither the offline solver nor the planning objective; implementation evidence is retained in the Supplement.',
60: 'There are 20 Next/Multi pairs under each tie rule. Fresh Java 21 processes run serially with default JIT/GC, a 1,024 MiB heap and no explicit warmup; Next runs first in odd pairs and Multi in even pairs. Each search allows 150,000 nodes and 30 seconds. Whole-selection, controller and external-watchdog limits are 32, 180 and 210 seconds. These limits were registered before outcomes; there are no scientific retries or outcome-driven budget increases.',
61: 'The charged monotonic interval begins before observation and candidate construction and ends after continuation-audit construction. It includes proof checking and preparation, seed/retention work, bounded search and final comparison. Committed work and physical events advance during that interval, followed by fresh intent admission. Checkpoint I/O, fresh admission, actual execution, JVM startup/deserialization and final export are outside the charged interval. Selection elapsed time, total controller elapsed time and modeled operation CPU are distinct quantities. The measurement is not a complete model of onboard computing overhead. Final service is reconstructed from delivered frames; unfinished executions have labeled prefix metrics and no final score.',
77: 'Discovering the offline improvement',
78: 'The historical one-second offline budget control finds no horizon advantage at its tested allowances; Supplement Fig. S1 preserves that result without its older measured-delay points. In autonomous X, both horizons obtain (2, 38, 0.12, 160, 0) at every positive allowance; the long ISS control ties at (3, 410, 2.13, 3240, 6). This does not characterize larger search-time budgets. Table 4 therefore examines the same autonomous root with a 30-second search cap.',
94: 'Median useful times for Next/Multi are 32.75/17.75 seconds under CONTINUE_WAIT and 32.75/18.125 under CANONICAL. Median host controller elapsed times are 6.857/22.860 and 6.905/21.598 seconds, respectively. These are whole-controller host measurements, not modeled operation CPU or individual selection durations. Supplementary timing statistics retain that distinction. Fig. 3 shows actual execution in the representative pairs.',
95: 'The representative traces expose missed preparation opportunities. In main-CONTINUE_WAIT-01, Multi first returns OPEN at t=14.0855122 s; the next selection ends at t=21.636010 s, after C1 closes. No new material or PREPARE/INSTALL is pending. Sequential PREPARE (2 s) and INSTALL (4 s) must start strictly before t=14 s because INSTALL must finish strictly before t=20 s; even that bound is only necessary if later decisions add delay. It is crossed at the first selection in main-CANONICAL-01 (t=15.2737277 s) and the second in main-CANONICAL-13 (t=18.9458863 s). The latter Multi run delivers 0.75 s in C1 without reaching both service thresholds; CANONICAL-01 delivers 2 s and serves C1. These selected witnesses support the timing mechanism without isolating the cost of DFS or proving it for every run.',
101: 'Earlier activation need not improve delivery. For CANONICAL at E-5 with immediate detection, generation g2 activates after 1.4 s but expires before the return; g3 supplies the first independent frame after 5,559.212 s, when Next also delivers. With detection delays of either 0 or 2 s, Next obtains (3, 410, 2.17, 3336, 6), while Multi obtains (3, 400, 2.82, 4336, 8); both deliver 4,000 confidential bytes. Thus these cases incur additional lifecycle cost without earlier independent delivery. The Supplement retains the full milestones and readiness intervals.',
106: 'The preserved ISS baseline also contains regimes without a horizon advantage: four completed nominal pairs tie on all five criteria, while five remain unresolved. Main-profile windows leave enough time for later security work, and many same-context return gaps exceed the 5,400-second cryptoperiod. With initially empty keys, positive service thresholds in both directions and no admissible reuse across served returns, each served contact needs two fresh directional keys, giving (3). This conditional count does not establish optimality for useful time, CPU, bytes or search cost.',
109: 'The 4-versus-8-second requirements in Fig. 1 identify a testable mechanism, not a general scheduling theorem: admissibility still depends on event boundaries, resources and key validity. Likewise, retaining a proved suffix is sound only under its guarded state and horizon conditions; it does not authorize skipping replanning after unforeseen changes.',
116: 'This research received no specific external funding. The author declares no competing interests. No human or animal participant study was conducted; this work uses simulation and archived orbital/station inputs.',
117: 'Data and code availability: the X01 reproduction release contains all 88 attempts, registered inputs, frozen executables and SHA-256 manifests. Download its named reproduction ZIP; GitHub source-only archives omit raw journals and runtime artifacts. No repository DOI has been assigned.'
}
DELETE = {50, 70, 71, 79, 80, 81, 82, 83, 102}
CLARIFICATIONS = {
    17: [('Our abstract operations', 'The abstract operations')],
    21: [('We formulate joint', 'The study formulates joint'),
         ('and provide matched evidence', 'and provides matched evidence')],
    49: [('At 100 bytes per frame',
          'X01 offers effectively unlimited payload in both directions in all three contacts '
          '(Long.MAX_VALUE, or 9,223,372,036,854,775,807 bytes per direction per contact). '
          'This offered demand is distinct from the service threshold of 400 bytes per direction. '
          'At 100 bytes per frame')],
    63: [('The disclosure series changes only attack timing',
          'The separate 36-run offline disclosure series changes only attack timing'),
         ('36 attempts. The common rule',
          '36 attempts. The practical controller allows 5,000 nodes and 1 s per search, '
          'with a 2 s action-selection limit. Computation time is not charged to contact windows; '
          'these runs are separate from the measured-delay campaign. The common rule')],
    97: [('All 36 disclosure attempts', 'All 36 attempts in the offline disclosure series'),
         ('this new grid', 'this offline disclosure series')],
    100: [('Fig. 4. Multi minus Next', 'Fig. 4. Offline disclosure series: Multi minus Next')]
}
PSEUDO = [
    'Practical controller with measured decision time',
    '1. Persist the checkpoint. Start the monotonic timer before constructing the permitted observation, wakeups and admissible candidates.',
    '2. Check the saved proof against the observation and rules. If allowed, derive its continuation and select its first action.',
    '3. Otherwise, construct a seed, validate/replay retained candidates, run bounded search and select by the five criteria and full-plan tie rule.',
    '4. Prepare a certificate only for a matching unrestricted COMPLETE search; check original admissibility, finish the decision scope and construct its audit. Stop the timer.',
    '5. Advance committed work and events by the measured interval, clipped at the scenario end.',
    '6. Rebuild the observation and candidates; recheck the selected intent and WAIT wakeup. Discard stale or late choices; observed incident response takes precedence.',
    '7. A shifted action receives no old optimality certificate. Retained original evidence must pass the full guard at the next decision.',
    '8. Execute only the admitted first action and repeat. A seed or feasible bounded-search result alone carries no optimality proof.'
]
FIG3_CAPTION = ('Fig. 3. Actual measured-delay executions in X01. Each pair is nearest to the median '
                'Delta U within its tie-rule/service-outcome group, with ties resolved by pair ID. '
                'Separate lanes show charged selections, executed lifecycle operations and delivered frames; '
                'shading marks contacts. S is served contacts and U is useful payload time. No PREPARE or INSTALL executes in these traces.')


def text(node):
    return ''.join(node.xpath('.//w:t/text()', namespaces=NS))


def canon(node):
    return E.tostring(node, method='c14n')


def set_text(p, value):
    assert not p.xpath('.//m:oMath|.//w:drawing', namespaces=NS)
    first = p.find(W+'r')
    style = copy.deepcopy(first.find(W+'rPr')) if first is not None and first.find(W+'rPr') is not None else None
    for child in list(p):
        if child.tag != W+'pPr':
            p.remove(child)
    r = E.SubElement(p, W+'r')
    if style is not None:
        r.append(style)
    for i, line in enumerate(value.split('\n')):
        if i:
            E.SubElement(r, W+'br')
        E.SubElement(r, W+'t', {'{http://www.w3.org/XML/1998/namespace}space': 'preserve'}).text = line


class Package:
    def __init__(self, source):
        self.source = source
        with zipfile.ZipFile(source) as z:
            self.parts = {n:z.read(n) for n in z.namelist()}
        self.root = E.fromstring(self.parts['word/document.xml'])
        self.body = self.root.find(W+'body')
        self.original = list(self.body)
        self.rels = E.fromstring(self.parts['word/_rels/document.xml.rels'])

    def add_rel(self, typ, target, external=False):
        rid = 'rId' + str(max(int(r.get('Id')[3:]) for r in self.rels) + 1)
        r = E.SubElement(self.rels, '{'+R+'}Relationship', Id=rid, Type=NS['r']+'/'+typ, Target=target)
        if external:
            r.set('TargetMode', 'External')
        return rid

    def hyperlink(self, p, label, url):
        whole = text(p)
        before, after = whole.split(label, 1)
        set_text(p, before)
        link = E.SubElement(p, W+'hyperlink', {'{'+NS['r']+'}id': self.add_rel('hyperlink', url, True)})
        r = E.SubElement(link, W+'r'); prop=E.SubElement(r,W+'rPr')
        E.SubElement(prop,W+'rFonts',{W+'ascii':'Times New Roman',W+'hAnsi':'Times New Roman'})
        E.SubElement(prop,W+'sz',{W+'val':'24'})
        E.SubElement(prop,W+'color',{W+'val':'0563C1'});E.SubElement(prop,W+'u',{W+'val':'single'})
        E.SubElement(r,W+'t').text=label
        r=E.SubElement(p,W+'r');E.SubElement(r,W+'t',{'{http://www.w3.org/XML/1998/namespace}space':'preserve'}).text=after

    def picture(self, template, path):
        p=copy.deepcopy(template)
        rid=self.add_rel('image','media/'+path.name)
        self.parts['word/media/'+path.name]=path.read_bytes()
        p.xpath('.//a:blip',namespaces=NS)[0].set('{'+NS['r']+'}embed',rid)
        with Image.open(path) as im: width,height=im.size
        cx=5868000; cy=round(cx*height/width)
        for n in p.xpath('.//wp:extent|.//a:xfrm/a:ext',namespaces=NS):
            n.set('cx',str(cx));n.set('cy',str(cy))
        doc_id=max([int(n.get('id')) for n in self.root.xpath('.//wp:docPr',namespaces=NS)]+[0])+1
        for n in p.xpath('.//wp:docPr|.//pic:cNvPr',namespaces=NS):
            n.set('id',str(doc_id));n.set('name',path.name)
        return p

    def save(self, path):
        self.parts['word/document.xml']=E.tostring(self.root,encoding='UTF-8',xml_declaration=True,standalone=True)
        self.parts['word/_rels/document.xml.rels']=E.tostring(self.rels,encoding='UTF-8',xml_declaration=True,standalone=True)
        # Supplement originally has no PNGs; register the actual content type.
        ct=E.fromstring(self.parts['[Content_Types].xml'])
        if not any(n.get('Extension')=='png' for n in ct):
            E.SubElement(ct,'{http://schemas.openxmlformats.org/package/2006/content-types}Default',Extension='png',ContentType='image/png')
            self.parts['[Content_Types].xml']=E.tostring(ct,encoding='UTF-8',xml_declaration=True,standalone=True)
        with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
            for name,data in self.parts.items():z.writestr(name,data)


def cloned(template,value,left=False):
    p=copy.deepcopy(template);set_text(p,value)
    if left:
        props=p.find(W+'pPr')
        if props is None:props=E.SubElement(p,W+'pPr')
        for tag, attrs in [('jc',{'val':'left'}),('ind',{'left':'0','right':'0','firstLine':'0'})]:
            node=props.find(W+tag)
            if node is None:node=E.SubElement(props,W+tag)
            for key,value in attrs.items():node.set(W+key,value)
    return p


def table2(table):
    rows=table.findall(W+'tr'); head,sample=copy.deepcopy(rows[0]),copy.deepcopy(rows[1])
    for r in rows:table.remove(r)
    widths=[4100,1600,1700,1954]
    grid=table.find(W+'tblGrid')
    for child in list(grid):grid.remove(child)
    for width in widths:E.SubElement(grid,W+'gridCol',{W+'w':str(width)})
    values=[['Phase','Planned','FINISHED','Unfinished'],['Offline controls','4','3','1'],
            ['Measured pilots','4','4','0'],['Main executions','80','80','0'],['Total','88','87','1']]
    for i,vals in enumerate(values):
        row=copy.deepcopy(head if i==0 else sample)
        cells=row.findall(W+'tc')
        for cell in cells[4:]:row.remove(cell)
        for cell,val,width in zip(cells,vals,widths):
            p=cell.find(W+'p');set_text(p,val)
            cell.find('w:tcPr/w:tcW',NS).set(W+'w',str(width))
            props=p.find(W+'pPr')
            if props is None:props=E.SubElement(p,W+'pPr')
            for n in props.findall(W+'keepNext'):props.remove(n)
            if i<len(values)-1:props.insert(0,E.Element(W+'keepNext'))
        table.append(row)


def main():
    OUT.mkdir(parents=True,exist_ok=True)
    article=Package(WORK/'inputs/article.docx');old=article.original
    assert len(old)==140 and text(old[89])=='Measured decision latency in X01'
    assert hashlib.sha256(article.source.read_bytes()).hexdigest()=='843ae2bfd60181aad1b15e547a2fbe1e9b1de8057226a5a803c4a82737525ebd'
    before=[canon(n) for n in old]
    for i,val in REPLACE.items():set_text(old[i],val)
    for i,replacements in CLARIFICATIONS.items():
        value=text(old[i])
        for previous,replacement in replacements:
            assert value.count(previous)==1, (i,previous)
            value=value.replace(previous,replacement)
        set_text(old[i],value)
    table2(old[43])
    pseudo=[cloned(old[39],s,left=True) for s in PSEUDO]
    # Keep the algorithm title with its first step, not the whole following section.
    pseudo[0].find(W+'pPr').append(E.Element(W+'keepNext'))
    pos=list(article.body).index(old[38])
    article.body.remove(old[38])
    for offset,p in enumerate(pseudo):article.body.insert(pos+offset,p)
    for i in DELETE:article.body.remove(old[i])
    figure=article.picture(old[79],OUT/'figures/fig3_measured_execution.png')
    cap=cloned(old[80],FIG3_CAPTION)
    at=list(article.body).index(old[94])+1
    article.body.insert(at,figure);article.body.insert(at+1,cap)
    article.hyperlink(old[117],'X01 reproduction release',URL)
    # Revise the abstract after the substantive sections.
    abstract=text(old[5]).replace('We then register 88 additional attempts:',
        'The primary delay campaign comprises 88 attempts:').replace(
        'Useful time is lower for Multi in every pair.',
        'Useful time is lower for Multi in every pair; all 40 lexicographic comparisons favor Next, while modeled costs tie.').replace(
        'We ask when', 'The study asks when').replace(
        'In 36 earlier disclosure runs', 'In the 36-run offline disclosure series')
    assert 250<=len(abstract.removeprefix('Abstract: ').split())<=280
    assert len(abstract.removeprefix('Abstract: '))<=1900
    set_text(old[5],abstract)
    changed=set(REPLACE)|set(CLARIFICATIONS)|DELETE|{5,38,43}
    assert all(canon(n)==before[i] for i,n in enumerate(old) if i not in changed)
    assert len(article.root.xpath('//m:oMath',namespaces=NS))==3
    article.save(OUT/'article_aitu_revised_final.docx')

    supp=Package(WORK/'inputs/supplement.docx');s=supp.original
    original_sup_xml=[canon(n) for n in s]
    edits={}
    for i,p in enumerate(s):
        t=text(p)
        if i==0:edits[i]='DOI: assigned by the journal upon publication'
        elif t.startswith('This supplement belongs'):
            edits[i]='This supplement preserves the historical offline and earlier measured-delay evidence and adds the current X01 campaign. The main article uses the 80-run campaign as its primary decision-latency test. Earlier measured-delay cases belong to the preceding implementation and timing boundary; they are retained as historical evidence, not declared erroneous or used as the current comparison. Machine-readable tables and raw journals retain full precision.'
        elif t=='Computational budget and timing':edits[i]='Historical one-second budget and timing'
        elif i==33:
            assert text(s[i-1])=='Disclosure model and metric definitions'
            edits[i]=('The 36-run offline disclosure series uses the practical controller with '
                '5,000 nodes and 1 s per search, and a 2 s action-selection limit. '
                'Computation time is not charged to contact windows. This series is separate '
                'from the current measured-delay campaign. '+t.replace('this grid','this offline disclosure series'))
        elif 'first-revision nine-configuration disclosure series' in t:
            edits[i]=t.replace('first-revision nine-configuration disclosure series',
                'first-revision nine-configuration offline disclosure series')
        elif t.startswith('Only Mukhammed Zhumali'):
            edits[i]='The current article preserves the author details supplied in the confirmed manuscript. The author confirms no specific external funding and no competing interests. Native editable OMML equations remain; a genuine MathType conversion is not available in this environment. Mattingley et al. (2010) remains substantively relevant; source-age exceptions and the journal indexing-share requirement need editorial confirmation. No complete journal-conformity claim is made.'
        elif t.startswith('The new reproduction archive is a separate version'):
            edits[i]='The X01 reproduction release provides the combined evidence, including the current 88-attempt campaign. Earlier releases and archives remain unchanged. Download the named reproduction ZIP and checksum rather than GitHub source-only archives. The editorial output accompanying this supplement has its own document identity; no repository DOI is assigned.'
    for i,val in edits.items():set_text(s[i],val)
    for i in edits:
        if 'X01 reproduction release' in text(s[i]):supp.hyperlink(s[i],'X01 reproduction release',URL)
    template=s[5];heading=s[4]
    addition=[('h','Current X01 measured-delay campaign'),
      ('p','The current campaign contains four offline controls, four measured pilots and 80 main executions: 20 Next/Multi pairs per tie rule. It yields 87 FINISHED and one CONTROLLER_TIME_LIMIT (offline CANONICAL Multi). All 80 main executions finish. CONTINUE_WAIT has zero Multi service wins, zero ties and 20 losses; CANONICAL has zero wins, five ties and 15 losses. All 40 full lexicographic comparisons favor Next. Each main execution has modeled operation CPU 0.12 s, 160 management bytes and zero new directional keys; it is incorrect to say Multi loses every individual resource criterion.'),
      ('p','The compatibility correction b2d04b8 includes existing continuation-proof verification and preparation in the measured cycle. Thirteen focused tests passed at that stage. The replacement JAR changes measured-controller compatibility classes; 6,703 other entries, including the planner, portfolio and offline controller, are byte-identical to final-v2. No solver change or new scientific run is performed by this editorial update.'),
      ('p','The timer begins before constructing observation, additional wakeups and candidates. The nominal path checks retained proof, derives an allowed continuation or invokes the ordinary seed/retention/bounded-search portfolio, prepares eligible COMPLETE-search evidence, checks original admissibility, closes the decision scope and constructs the continuation audit. The timer stops after that audit. Checkpoint persistence, fresh admission, execution, JVM startup/deserialization and final export are excluded. Guard and preparation times are subsets of the charged interval, not additional charges.'),
      ('p','During the measured interval the executor advances already committed operations, frames and physical events. It then reconstructs the permitted observation and admissible candidates, checks WAIT wakeups and selected intent, and rejects stale choices. Shifted actions do not inherit the old certificate. Retained original evidence must pass the full next-decision guard. A matching unrestricted COMPLETE search may certify a continuation after one nominal completed frame; feasible seeds, retained candidates and incomplete incumbents alone do not certify optimality. Local incident handling has priority and bypasses this latency path; X01 contains no incidents.'),
      ('p','Current limits are 150,000 nodes and 30 s per search, 32 s per selection, 180 s per controller and a 210 s process watchdog. The archived autonomous state starts at -2 s, with no spare pair. Offered X01 payload is Long.MAX_VALUE (9,223,372,036,854,775,807 bytes) in each direction in each of the three contacts; service requires 400 bytes per direction. Fresh Java 21 JVMs run serially with a 1,024 MiB heap, default JIT/GC and no explicit warmup; paired execution order alternates. These runs do not establish a budget-quality curve. Full registration, hardware and hashes are in target/measured-delay-x01-20261028/manifest.json.'),
      ('h','Current timing distributions and representative executions'),
      ('p','Across individual main decisions, elapsed median [minimum, maximum] in milliseconds is 28.779 [1.083, 728.280] for CW Next, 14.182 [1.541, 15035.710] for CW Multi, 28.461 [1.151, 779.657] for CAN Next and 15.180 [1.269, 18540.615] for CAN Multi. These pooled decision distributions mix different trajectories and decision counts; a lower pooled median is not evidence of a cheaper full controller. Main-article whole-controller medians and modeled operation CPU are different measurements.'),
      ('p','Representatives are chosen among completed main pairs in each (tie rule, S outcome) group by distance to median Delta U, then pair ID. The selected pairs are main-CONTINUE_WAIT-01 (Next S=2,U=32.5; Multi S=1,U=17.5), main-CANONICAL-13 (2,33.5 versus 1,18.75), and main-CANONICAL-01 (2,32.75 versus 2,20). Group medians are -15, -14.75 and -12.75 s. All six runs start charging at -2 s, execute two OPEN operations, two automatic STOP operations and one EXPIRE_CONTEXT. None executes PREPARE, INSTALL, SWITCH or REUSE.'),
      ('p','Fig. 3 uses origin.at to availableAt for charged intervals; actual STEP_STARTED/STEP_COMPLETED events joined by operationId provide executed operations. PAYLOAD_TRANSFERRED provides delivered-frame intervals. ACTION events are not added again, and predicted bestPlan actions are not drawn as execution. Separate lanes preserve overlaps. Frames are 100 bytes and last 0.25 s; 400 bytes in each direction is the contact service threshold. Very short selection intervals may be visually unresolved at print resolution; widths are not inflated. Source files and checksums are recorded in figures/provenance.json.'),
      ('h','Historical offline budget control'),
      ('p','Fig. S1 retains only the offline portion of the former main budget figure. It uses the original one-second search cap and tests No DFS, 1,000, 5,000, 20,000 and 100,000-node allowances. It contains no values from the current 150,000-node/30-second series. Historical measured points and the earlier three-loss/one-tie observation remain in the original journals and tables above, under their preceding implementation and timing boundary; they are not the current latency test.'),
      ('figure','figS1_offline_budget'),
      ('caption','Fig. S1. Historical offline payload quality and controller elapsed time under a one-second search cap. Only offline rows are plotted; node values are allowances, not guaranteed work completed.'),
      ('p','The offline reference and the current offline control both retain the CONTINUE_WAIT gain of three served contacts versus two. Under CANONICAL, the full offline Multi run remains incomplete at the controller limit. The measured boundary revision does not invalidate these offline observations. Detailed preparation, incumbent discovery, g2 expiry and g3 recovery evidence above is preserved to distinguish lifecycle readiness, delivery and confidentiality.')]
    for typ,val in addition:
        if typ=='figure':node=supp.picture(old[79],OUT/'figures'/f'{val}.png')
        elif typ=='caption':node=cloned(old[80],val)
        else:node=cloned(heading if typ=='h' else template,val)
        supp.body.insert(len(supp.body)-1,node)
    assert all(canon(n)==original_sup_xml[i] for i,n in enumerate(s) if i not in edits)
    supp.save(OUT/'supplement_aitu_revised_final.docx')
    all_text='\n'.join(text(p) for p in article.body)
    assert not re.search(r'\b(?:we|our|us)\b',all_text,re.IGNORECASE)
    assert 'this new grid' not in all_text and '36 earlier disclosure runs' not in all_text
    assert 'three worse Multi outcomes' not in all_text and 'Crosses denote measured-delay' not in all_text
    assert '[AUTHOR TO CONFIRM]' not in all_text and '[TO BE ADDED' not in all_text
    assert [text(n) for n in old[119:139]]==[text(E.fromstring(before[i])) for i in range(119,139)]
    record={'status':'STRUCTURAL_PASS','sourceArticleSha256':hashlib.sha256(article.source.read_bytes()).hexdigest(),
      'articleSha256':hashlib.sha256((OUT/'article_aitu_revised_final.docx').read_bytes()).hexdigest(),
      'sourceSupplementSha256':hashlib.sha256(supp.source.read_bytes()).hexdigest(),
      'supplementSha256':hashlib.sha256((OUT/'supplement_aitu_revised_final.docx').read_bytes()).hexdigest(),
      'articleEditedBodyIndices':sorted(changed),'articleUneditedBodyElements':len(old)-len(changed),
      'supplementEditedIndices':list(edits),'referencesPreserved':20,'nativeEditableEquations':3,
      'abstractWords':len(abstract.removeprefix('Abstract: ').split()),
      'abstractCharacters':len(abstract.removeprefix('Abstract: ')), 'abstractEditedLast':True,
      'sourceDocsPreserved':True,'newExperiments':0,'mathType':'No verified converter available; editable OMML retained',
      'renderReview':'PENDING'}
    (OUT/'validation.json').write_text(json.dumps(record,indent=2)+'\n')
    (WORK/'article-final-text.txt').write_text(all_text,encoding='utf-8')
    (WORK/'supplement-additions.json').write_text(json.dumps(addition,indent=2)+'\n')
    print(json.dumps(record,indent=2))


if __name__=='__main__':main()
