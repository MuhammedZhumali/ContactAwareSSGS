#!/usr/bin/env python3
"""Verify deliverables against saved results; read-only to historical artifacts."""
import csv
import json
import re
from collections import Counter
from decimal import Decimal as D
from pypdf import PdfReader
import audit_security_evaluation as audit
from summarize_research_service import ROOT,OUT,DOC,FIVE,verify
from run_security_evaluation_se21 import sha,rel,load
from analyze_security_evaluation_se222 import save


def main():
    registration=verify(); rows=audit.load(OUT/'runs.json');extras=audit.load(OUT/'extra-attempts.json')
    pairs=audit.load(OUT/'comparisons.json');evidence=audit.load(OUT/'journal-evidence.json')
    mechanisms=audit.load(OUT/'mechanisms.json')
    # Evidence JSON intentionally writes Decimal as exact strings; CSV retains the same text.
    for obj in rows+extras+pairs:
        for k,v in obj.items():
            numeric=(k in FIVE or k.startswith('delta_') or k.endswith(('_next_seconds','_multi_seconds')))
            if numeric and isinstance(v,str):obj[k]=D(v)
    assert len(rows)==84 and len(extras)==3 and len(evidence)==87
    assert Counter(r['status'] for r in rows)==dict(FINISHED=63,SEARCH_INCOMPLETE=20,CONTROLLER_TIME_LIMIT=1)
    assert all(r['status']=='CONTROLLER_TIME_LIMIT' for r in extras)
    assert sum(r['historical'] for r in rows)==20
    assert sum(p['status']=='COMPARABLE' for p in pairs)==21 and len(pairs)==42
    for file,objects in [('runs',rows),('extra-attempts',extras),('comparisons',pairs)]:
        with (DOC/(file+'.csv')).open(encoding='utf-8',newline='') as stream:csv_data=list(csv.DictReader(stream))
        assert len(csv_data)==len(objects)
        for serialized,obj in zip(csv_data,objects):
            for k,v in obj.items():assert serialized[k]==('' if v is None else str(v)),(file,k)
    for r in rows+extras:
        e=evidence[r['case']]['confidentialService']
        assert e['status']==r['confidentialServiceStatus']
        for k in ('confidentialServedContacts','confidentialDeliveredBytes')+FIVE:
            if r['status']!='FINISHED':assert r[k] is None
        if r['status']=='FINISHED':
            assert 0 <= r['confidentialServedContacts'] <= r['servedContacts']
            assert r['confidentialServedContacts']==sum(c['confidentialServed'] for c in e['observedPrefix']['contacts'])
            if not e['disclosedDirectionalKeys']:assert r['servedContacts']==r['confidentialServedContacts']
    by_case={r['case']:r for r in rows}
    for p in pairs:
        a,b=by_case[p['nextCase']],by_case[p['multiCase']]
        ok=a['status']==b['status']=='FINISHED'
        assert (p['status']=='COMPARABLE')==ok and p['sameScenario']
        for k in FIVE+('confidentialServedContacts','confidentialDeliveredBytes'):
            assert p['delta_'+k]==(b[k]-a[k] if ok else None)
    # The concrete statements printed in the short document, including negative results.
    for rule in ('CANONICAL','CONTINUE_WAIT'):
        p=next(p for p in pairs if (p['family'],p['duration'],p['attack'],p['rule'])==('X',5,'none',rule))
        assert (p['servedContacts_next'],p['servedContacts_multi'],p['confidentialServedContacts_next'],p['confidentialServedContacts_multi'])==(2,3,2,3)
        assert (p['delta_usefulSecureTime'],p['delta_controlCpu'],p['delta_managementBytes'],p['delta_newKeys'])==(D(-1),D('.71'),1080,2)
        for attack in ('p70-detected','p90-detected','gap-midpoint-detected'):
            p=next(p for p in pairs if (p['family'],p['duration'],p['attack'],p['rule'])==('X',5,attack,rule))
            assert (p['servedContacts_next'],p['servedContacts_multi'],p['confidentialServedContacts_next'],p['confidentialServedContacts_multi'])==(2,3,1,2)
    for p in pairs:
        if p['family']=='X' and p['duration']==9 and p['rule']=='CONTINUE_WAIT':
            assert all(p['delta_'+k]==0 for k in FIVE+('confidentialServedContacts','confidentialDeliveredBytes'))
            assert all(p[k+'_next_seconds']==p[k+'_multi_seconds'] and p[k+'_next_status']==p[k+'_multi_status']
                for k in ('spacecraft_notice','independent_pair','independent_sa','independent_frame'))
        if p['stage']=='SE2.4' and p['status']=='COMPARABLE':
            assert all(p['delta_'+k]==0 for k in FIVE+('confidentialServedContacts','confidentialDeliveredBytes'))
        if p['stage']=='SE2.5':assert p['status']=='NOT_COMPARABLE' and p['multiStatus']=='SEARCH_INCOMPLETE'
    assert by_case['C3-5-X-multi-original-detected-canonical']['confidentialServedContacts']==3
    assert by_case['C3-5-X-multi-original-detected-continue_wait']['confidentialServedContacts']==2
    assert all(r['confidentialServedContacts']==3 for r in rows if r['stage']=='SE2.5' and r['status']=='FINISHED')
    assert sum(r['servedContacts']!=r['confidentialServedContacts'] for r in rows if r['status']=='FINISHED')==42
    # Check the service-completion times from full delivered frames, independently of report prose.
    def threshold_at(name,contact):
        counts={'uplink':0,'downlink':0}
        sc=audit.load(ROOT/by_case[name]['modelFile'])['scenario'];p=next(c['payload'] for c in sc['contacts'] if c['window']['id']==contact)
        for e in mechanisms[name]['completeFrames']:
            if e['contactId']!=contact:continue
            for d in counts:counts[d]+=e[d+'PayloadBytes']
            if counts['uplink']>=p['minimumUplinkBytes'] and counts['downlink']>=p['minimumDownlinkBytes']:return e['at']
        return None
    for rule in ('canonical','continue_wait'):
        assert threshold_at('C3-5-X-multi-none-'+rule,'C3')=='2026-01-01T00:01:24Z'
        assert threshold_at('C3-5-X-next-none-'+rule,'C3') is None
    for policy in ('next','multi'):
        assert threshold_at('C3-9-X-'+policy+'-none-continue_wait','C3')=='2026-01-01T00:01:28Z'
        assert audit.instant(threshold_at('C3-9-X-'+policy+'-p90-detected-continue_wait','C3'))==audit.instant('2026-01-01T00:01:28.4Z')
    # New README section and new documentation must have real relative link targets.
    checked_links=0
    docs=list(DOC.glob('*.md'))+[ROOT/'README.md']
    for path in docs:
        text=path.read_text(encoding='utf-8')
        if path.name=='README.md' and path.parent==ROOT:text=text.split('## SE2.2.1:')[0]
        for link in re.findall(r'\[[^\]]+\]\(([^)]+)\)',text):
            if link.startswith(('https:','http:','#')):continue
            assert (path.parent/link.split('#')[0]).exists(),(rel(path),link)
            checked_links+=1
    pdf=PdfReader(DOC/'report.pdf');assert len(pdf.pages)==3
    assert all(len(p.extract_text())>2000 for p in pdf.pages)
    assert 'Ran 8 tests' in (OUT/'metric-tests.log').read_text() and (OUT/'metric-tests.log').read_text().rstrip().endswith('OK')
    for path in list(DOC.glob('*.md'))+list((ROOT/'scripts').glob('*research_synthesis.py'))+list((ROOT/'scripts').glob('*confidential_service_metrics.py')):
        assert all(line==line.rstrip() for line in path.read_text(encoding='utf-8').splitlines()),path
    checks=dict(status='PASS',selectedRows=84,extraOriginalAttempts=3,finished=63,allAuditedJournals=87,
        completedPairs=21,allPairs=42,metricTests=8,documentPages=3,localLinksChecked=checked_links,
        ordinaryAndConfidentialCrosschecks='PASS',incompleteFinalFields='UNAVAILABLE',
        reportClaimsCheckedAgainstSavedJournals='PASS',newExperiments=0,newMavenRuns=0,
        preservedFiles=len(load(OUT/'preserved-inputs.json')),protectedSourceFiles=len(load(OUT/'protected-sources.json')),
        visualReview='All three PNG pages inspected: readable tables, no clipping, page numbers correct',
        scope='Only retrospective postprocessing; no sixth criterion, policy, budget or model change')
    save(OUT/'final-checks.json',checks)
    files=[*sorted(DOC.glob('*')),*[ROOT/'scripts'/p for p in ('confidential_service_metrics.py','test_confidential_service_metrics.py',
        'summarize_research_service.py','render_research_synthesis.py','verify_research_synthesis.py')],ROOT/'README.md',
        OUT/'registration.json',OUT/'final-checks.json',OUT/'metric-tests.log',OUT/'analysis.log',OUT/'journal-evidence.json',OUT/'mechanisms.json']
    save(OUT/'manifest.json',dict(baseCommit=registration['baseCommit'],mode='POSTPROCESS_ONLY',
        files={rel(p):sha(p) for p in files if p.is_file()},inputs=registration['inputs'],checks=checks))
    verify();print(json.dumps(checks,indent=2))


if __name__=='__main__':main()
