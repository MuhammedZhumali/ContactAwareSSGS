"""Freeze R1 evidence and sources after runs; preserve all historical bytes."""
import json, shutil, zipfile
from run_research_validation import ROOT,OUT,load,save,sha,relative,verify,cmd

def package():
    verify()
    tests=OUT/'test-evidence';tests.mkdir(exist_ok=False)
    for name,source in [('candidate','candidate-build'),('practical-v2','practical-v2-verified-build'),('delay-export','delay-export-build')]:
        shutil.copytree(OUT/source/'target/surefire-reports',tests/name)
    shutil.copy2(OUT/'practical-v2-verified-build/target/r1-small-quality.json',tests/'r1-small-quality.json')
    for name,source in [('baseline','baseline-restored-build'),('candidate','candidate-build'),('practical-v2','practical-v2-verified-build'),('delay-export','delay-export-build')]:
        base=OUT/source;dest=OUT/(name+'-sources.zip')
        assert not dest.exists()
        with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,6) as archive:
            for part in ['src','.mvn']:
                for p in (base/part).rglob('*'):
                    if p.is_file():archive.write(p,str(p.relative_to(base)))
            for part in ['pom.xml','mvnw.cmd','fixture-manifest.json']:archive.write(base/part,part)
    source_files=[p for part in ['src','scripts','.mvn','docs/research-audit','docs/paper-r1','docs/formal-cost-model-and-horizon'] for p in (ROOT/part).rglob('*')
        if p.is_file() and '__pycache__' not in p.parts]
    source_files += [ROOT/name for name in ['pom.xml','mvnw.cmd','README.md','RESEARCH_VALIDATION_RESULTS.md']]
    save(OUT/'final-sources.json',{relative(p):sha(p) for p in source_files})
    # Current final production sources must be the tested practical binary's source bytes.
    mismatches=[relative(p) for p in (ROOT/'src/main').rglob('*') if p.is_file() and sha(p)!=sha(OUT/'delay-export-build'/p.relative_to(ROOT))]
    assert not mismatches,mismatches
    (OUT/'working-tree.patch').write_text(cmd(['git','diff','--no-ext-diff','--','README.md','src','scripts']),encoding='utf-8')
    fixture=load(OUT/'baseline-restored-build/fixture-manifest.json')
    data_files=[ROOT/p for p in fixture]
    data_files += [ROOT/v['source'] for v in load(OUT/'models/manifest.json').values()]
    data_files += [ROOT/c['model'] for c in load(OUT/'baseline/protocol.json')['cases']]
    # Four frozen historical journals are mandatory fixtures of the Python suite.
    historical=ROOT/'target/security-evaluation-se2'
    summary=load(historical/'summary.json');data_files.append(historical/'summary.json')
    for name in ['X-next-original-detected','X-multi-original-detected',
                 'iss3-cp5400-next-p30-detected','iss3-cp5400-multi-p30-detected']:
        data_files += [historical/summary[name][field] for field in ['modelFile','eventsFile','actualFile']]
    # Include compact historical references linked by the supplement, not old archives.
    for directory in ['research-synthesis','security-evaluation-se222','security-evaluation-c3',
                      'security-evaluation-new-days','security-evaluation-remaining-demand']:
        data_files += [p for p in (ROOT/'docs'/directory).rglob('*')
                       if p.is_file() and p.suffix in {'.md','.json','.csv','.tex','.txt'}]
    data_files += list((ROOT/'docs').glob('*.md'))
    save(OUT/'manifest.json',dict(baseCommit=cmd(['git','rev-parse','HEAD']),finalDirtyState=cmd(['git','status','--porcelain=v1']),
        sourceManifest='final-sources.json',models='models/manifest.json',environment='environment.json',
        binaries={k:load(OUT/(k+'-binary.json')) for k in ['baseline','candidate','practical-v2','delay-export']},
        historicalPreservation=dict(count=len(load(OUT/'preserved-inputs.json')),status='PASS'),
        tests=dict(candidate=load(OUT/'tests.json'),practicalFocused=load(OUT/'practical-tests.json'),
                   exportFocused=load(OUT/'delay-export-tests.json'),python=67,notAdditive=True),
        results=load(ROOT/'docs/research-audit/results/validation.json'),
        caveat='Dirty working-source snapshot; not a clean git release. Exact/practical identities and historical results are separate.'))
    files=set(source_files+data_files)
    for p in OUT.rglob('*'):
        if not p.is_file():continue
        parts=p.relative_to(OUT).parts
        if any(x.endswith('-build') or x in ['__pycache__','temp'] for x in parts):continue
        # Keep fetched third-party papers locally; package only their access metadata/URLs/hashes.
        if parts[0]=='literature' and p.suffix.lower() in ['.pdf','.txt']:continue
        # The packaging process's own redirected log changes after the freeze.
        if p.name in ['r1-reproduction.zip','package.json','evidence-file-hashes.json','package-build.log']:continue
        files.add(p)
    hashes={relative(p):sha(p) for p in sorted(files)}
    save(OUT/'evidence-file-hashes.json',hashes);files.add(OUT/'evidence-file-hashes.json')
    destination=OUT/'r1-reproduction.zip';assert not destination.exists()
    with zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED,6) as archive:
        for p in sorted(files):archive.write(p,relative(p))
    with zipfile.ZipFile(destination) as archive:assert archive.testzip() is None
    save(OUT/'package.json',dict(file=relative(destination),sha256=sha(destination),bytes=destination.stat().st_size,files=len(files),
        sources='final-sources.json',evidenceHashes='evidence-file-hashes.json',zipIntegrity='PASS',oldArtifactsPreserved=len(load(OUT/'preserved-inputs.json'))))
    print('Package PASS',destination.stat().st_size,len(files))

if __name__=='__main__':package()
