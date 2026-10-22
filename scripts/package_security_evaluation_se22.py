#!/usr/bin/env python3
"""Package this limited experiment, preserving all prior experiment files."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from run_security_evaluation_se22 import ROOT, OUT, DOC, verify
from run_security_evaluation_se21 import load, sha, write, rel
from package_security_evaluation_se21 import archive

def main():
    manifest=verify(); validation=load(DOC/'validation.json'); build=manifest['build']
    assert validation['status']=='PASS' and validation['summarySha256']==sha(OUT/'summary.json')
    assert sum(validation['coverage'].values())==52 and not validation['coverage'].get('NOT_RUN')
    assert validation['analysisSha256']==sha(ROOT/'scripts/analyze_security_evaluation_se22.py')
    assert load(DOC/'x-intermediate.json')['status']=='PASS'
    suites=list((Path(build['sourceDirectory'])/'target/surefire-reports').glob('TEST-*.xml'))
    counts={k:sum(int(ET.parse(p).getroot().attrib[k]) for p in suites) for k in ('tests','failures','errors','skipped')}
    assert counts==dict(tests=361,failures=0,errors=0,skipped=0)
    py=OUT/'python-tests.log'; assert 'Ran 14 tests' in py.read_text() and py.read_text().rstrip().endswith('OK')
    hashes=load(DOC/'tables/independent-audit.json')['fileHashes']
    assert all(sha(OUT/p)==h for p,h in hashes.items())
    write(DOC/'quality-check.json',dict(status='PASS',maven=counts,pythonTests=14,
        preservedInputsVerified=len(manifest['preservedInputs']),registeredControllers=52,
        coverage=validation['coverage'],completedCanonicalComparisons=len(validation['canonicalChecks']),
        xCheckpointPreserved=True,analysisSha256=validation['analysisSha256'],
        scope='Accounting/implementation checks passed; incomplete or failed controllers are not quality results.',
        logHashes={rel(py):sha(py)}))
    documents=[p for p in DOC.rglob('*') if p.is_file() and p.suffix!='.zip'
        and p.name not in ('artifact-manifest.json','evidence-file-hashes.json')]
    research=archive(DOC/'research-results-se22.zip',documents)
    scripts=[p for p in (ROOT/'scripts').glob('*.py') if 'se22' in p.name]
    scripts += [ROOT/'scripts'/n for n in ('run_security_evaluation_se21.py','package_security_evaluation_se21.py',
        'audit_security_evaluation.py','recovery_metrics_se21.py','test_recovery_metrics_se21.py','test_se21_watchdog.py')]
    originals=[p for p in (ROOT/'docs/reviews').rglob('*') if p.is_file()]
    evidence=documents+scripts+originals+suites+[Path(build['archive']),ROOT/build['jar'],ROOT/'README.md']
    evidence += [p for p in OUT.rglob('*') if p.is_file()]
    evidence += [ROOT/p for p in build['testDatasetHashes']]
    evidence=sorted(set(evidence))
    write(DOC/'evidence-file-hashes.json',{rel(p):sha(p) for p in evidence})
    evidence.append(DOC/'evidence-file-hashes.json')
    full=archive(DOC/'experiment-evidence-se22.zip',evidence)
    write(DOC/'artifact-manifest.json',dict(archives=[research,full],compiledCommit=build['commit'],
        jarSha256=build['jarSha256'],sourceArchiveSha256=build['sourceArchiveSha256'],
        preservedPriorInputs=len(manifest['preservedInputs']),
        dependencies='Preserved SE2/SE2.1 directories/JARs and Maven cache are required for reruns; their hashes are in manifest. Archives do not duplicate all prior archives or runtimes.'))
    print(json.dumps([research,full],indent=2))

if __name__=='__main__': main()
