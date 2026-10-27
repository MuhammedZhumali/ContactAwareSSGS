"""Completion gates for the complete final campaign; no simulation or replacement attempts."""
import collections,json
from pathlib import Path
from run_final_research import ROOT,OUT,load,sha,save

def main():
    protocol=load(OUT/'protocol.json');manifest=load(OUT/'manifest.json')
    rows=load(OUT/'tables.json');audit=load(OUT/'audit.json');validation=load(ROOT/'docs/final-research/validation.json')
    attempts=load(OUT/'summary.json');expected={c['id'] for c in protocol['cases']}
    assert len(expected)==108 and {r['id'] for r in rows}=={r['id'] for r in attempts}==expected
    assert len(rows)==len(attempts)==108 and all(r['status']!='NOT_RUN' for r in rows)
    assert manifest['tests']==dict(tests=478,failures=0,errors=0,skipped=0,classes=64)
    assert sha(ROOT/manifest['jar'])==manifest['jarSha256']==protocol['jarSha256']
    for name,wanted in load(ROOT/manifest['sourcesFile']).items():assert sha(ROOT/name)==wanted,name
    for name,wanted in protocol['inputs'].items():assert sha(ROOT/name)==wanted,name
    for name,wanted in audit['inputHashes'].items():assert sha(ROOT/name)==wanted,name
    assert audit['status']==validation['status']=='PASS'
    assert validation['tableSha256']==sha(OUT/'tables.json') and validation['auditSha256']==sha(OUT/'audit.json')
    assert validation['summarySha256']==sha(OUT/'summary.json') and validation['protocolSha256']==sha(OUT/'protocol.json')
    assert validation['attempted']==108 and len(audit['runs'])==validation['audited']
    for case in protocol['cases']:
        dest=OUT/'runs'/case['id'];invocation=load(dest/'invocation.json')
        assert invocation['protocolSha256']==sha(OUT/'protocol.json')
        cmd=invocation['command'];start=cmd.index('org.contactaware.security.simulation.ResearchValidationMain')+1
        assert Path(cmd[cmd.index('-cp')+1]).resolve()==(ROOT/protocol['jar']).resolve()
        flags=dict(zip(cmd[start::2],cmd[start+1::2]))
        assert flags['--model']==case['model'] and flags['--horizon']==str(case['horizon'])
        assert flags['--portfolio']==case['portfolio'] and flags['--rule']==case['rule'] and flags['--mode']==case['mode']
        assert flags['--nodes']=='5000' and flags['--search-ms']=='1000' and flags['--deadline-ms']=='2000'
        assert flags['--decision-budget-ms']==('0' if case['measured'] else '2000')
        assert flags['--controller-s']=='0' and flags['--host-scale']==('1' if case['measured'] else '0')
        process=load(dest/'process.json');assert process['externalLimitSeconds']==600
    # A marker/manifest is not proof of rendering; enumerate actual exported files and reviewed pages.
    paper=ROOT/'docs/paper-final';latex=load(OUT/'latex/validation.json')
    report=load(OUT/'report-provenance.json')
    assert report['tableSha256']==sha(OUT/'tables.json') and report['auditSha256']==sha(OUT/'audit.json')
    assert report['mainResultsSha256']==sha(paper/'final-results.tex')
    assert report['supplementResultsSha256']==sha(paper/'supplement-results.tex')
    for name in ['main','supplement']:
        record=latex['documents'][name];pdf=paper/(name+'.pdf')
        assert sha(pdf)==record['pdfSha256'] and sha(paper/(name+'.tex'))==record['sourceSha256']
        assert record['renderedPagesReviewed']==list(range(1,record['pages']+1))
        assert not record['overfullBoxes'] and not record['unresolvedReferences']
        # Binding only main.tex would miss changed generated tables or embedded figures.
        inputs=record['inputs']
        required={str((paper/(name+'.tex')).relative_to(ROOT)).replace('\\','/'),
                  str((paper/('final-results.tex' if name=='main' else 'supplement-results.tex')).relative_to(ROOT)).replace('\\','/')}
        assert required<=inputs.keys()
        for path,digest in inputs.items():assert sha(ROOT/path)==digest,path
        source=(paper/(name+'.tex')).read_text(encoding='utf-8')
        assert 'FINAL_EVIDENCE_REQUIRED' not in source and 'pendingevidence' not in source
    figure_provenance=load(paper/'figures/provenance.json')
    assert figure_provenance['tableSha256']==sha(OUT/'tables.json')
    assert figure_provenance['protocolSha256']==sha(OUT/'protocol.json')
    assert set(figure_provenance['figures'])=={'service_matrix','cost_matrix','runtime_completion','measured_iss','detected_exposure','recovery'}
    for name in figure_provenance['figures']:
        for extension in ['pdf','svg','png']:assert (paper/'figures'/(name+'.'+extension)).is_file()
    for name in ['report.md','runs.csv','pairs.csv','factor-comparisons.csv','reproduction.md','requirements-audit.md']:
        assert (ROOT/'docs/final-research'/name).is_file()
    assert (paper/'reference-verification.md').is_file()
    result=dict(status='PASS',scope='All registered cells attempted, frozen executable/configs, independent exported-run audit and declared render evidence checked',
        attempts=108,statuses=dict(collections.Counter(r['status'] for r in rows)),
        protocolSha256=sha(OUT/'protocol.json'),jarSha256=manifest['jarSha256'],
        remainingManualGate='Read requirements-audit.md and assess scientific claims, visual review, package integrity and historical hash verification; this script does not prove those by existence alone')
    save(OUT/'completion-gates.json',result);print(json.dumps(result,indent=2))

if __name__=='__main__':main()
