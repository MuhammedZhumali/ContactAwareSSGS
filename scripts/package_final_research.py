"""Package final source, inputs, evidence and manuscript without touching historical archives."""
import datetime,json,zipfile
from pathlib import Path
from run_final_research import ROOT,OUT,load,save,sha,relative,verify

def main():
    verify()
    protocol=load(OUT/'protocol.json');manifest=load(OUT/'manifest.json')
    validation=load(ROOT/'docs/final-research/validation.json')
    assert validation['registered']==validation['attempted']==108
    assert validation['status']=='PASS'
    assert not (OUT/'final-reproduction.zip').exists(),'Refusing existing archive'
    sources=load(ROOT/manifest['sourcesFile'])
    paths={ROOT/name for name in sources}
    # Python dependencies are project scripts; standard libraries plus optional Matplotlib are documented.
    paths.update(p for p in (ROOT/'scripts').glob('*.py'))
    for folder in ['docs/final-research','docs/paper-final']:
        paths.update(p for p in (ROOT/folder).rglob('*') if p.is_file())
    paths.add(ROOT/'README.md')
    for p in OUT.rglob('*'):
        if not p.is_file() or p.suffix=='.zip':continue
        parts=p.relative_to(OUT).parts
        if any(part.endswith('-build') for part in parts):
            if ('surefire-reports' not in parts and p.name not in ['r1-small-quality.json','fixture-manifest.json']
                    and not p.name.startswith('pc2-oracle-')):continue
        paths.add(p)
    for name in load(ROOT/'target/research-validation/baseline-restored-build/fixture-manifest.json'):
        paths.add(ROOT/name)
    paths.add(ROOT/'target/research-validation/baseline-restored-build/fixture-manifest.json')
    for p in (ROOT/'target/practical-controller-pc2/fixtures').rglob('*'):
        if p.is_file():paths.add(p)
    for model in protocol['models'].values():paths.add(ROOT/model['source'])
    for folder in ['target/research-validation/orbit-sources',
                   'target/research-validation/geometry-v2/33591-base',
                   'target/research-validation/geometry-v2/20580-base']:
        paths.update(p for p in (ROOT/folder).rglob('*') if p.is_file() and p.suffix in ['.json','.txt'])
    # Keep the primary-source evidence linked by the final reference-verification note.
    paths.update(p for p in (ROOT/'target/research-validation/literature').glob('*')
                 if p.is_file() and p.suffix in ['.pdf','.txt','.json'])
    for name in ['data/runs/iss-20261010/schedule/schedule.json',
                 'docs/formal-cost-model-and-horizon/verification.json',
                 'docs/research-audit/related-work.md','docs/research-audit/workload-sources.md']:
        paths.add(ROOT/name)
    paths={p.resolve() for p in paths}
    hashes={relative(p):sha(p) for p in sorted(paths)}
    save(OUT/'reproduction-files.json',hashes)
    paths.add(OUT/'reproduction-files.json')
    archive=OUT/'final-reproduction.zip'
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as z:
        for i,p in enumerate(sorted(paths)):
            z.write(p,relative(p))
            if i and i%500==0:print('Packaged',i,'/',len(paths),flush=True)
    with zipfile.ZipFile(archive) as z:
        bad=z.testzip();assert bad is None,bad
    save(OUT/'package-manifest.json',dict(createdAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        archive=relative(archive),sha256=sha(archive),bytes=archive.stat().st_size,files=len(paths),
        jarSha256=manifest['jarSha256'],protocolSha256=sha(OUT/'protocol.json'),
        sourceManifestSha256=manifest['sourcesSha256'],validation=validation,
        historicalPreservation='All saved prior input hashes rechecked; no prior archive replaced',
        dependencies='Java21/Maven dependencies resolved from pom.xml; original build used offline local Maven cache; Matplotlib needed only for figures; sources and exact model/JAR bytes included'))
    print('Package integrity PASS',archive,flush=True)

if __name__=='__main__':main()
