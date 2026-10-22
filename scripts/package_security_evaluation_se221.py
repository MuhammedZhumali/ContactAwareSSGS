#!/usr/bin/env python3
"""Package new evidence only; old results remain immutable external inputs."""
import json,re
from pathlib import Path
import xml.etree.ElementTree as ET
from run_security_evaluation_se221 import ROOT,OUT,DOC,verify,preserve
from run_security_evaluation_se21 import load,write,sha,rel
from package_security_evaluation_se21 import archive

def main():
    builds=[verify(stage)['build'] for stage in ('a','b')]
    old=preserve();validation=load(DOC/'validation.json')
    assert validation['status']=='PASS' and validation['summarySha256']==sha(OUT/'summary.json')
    assert validation['analysisSha256']==sha(ROOT/'scripts/analyze_security_evaluation_se221.py')
    suites=[]
    for build,number in zip(builds,(13,374)):
        reports=list((Path(build['sourceDirectory'])/'target/surefire-reports').glob('TEST-*.xml'))
        counts={k:sum(int(ET.parse(p).getroot().attrib[k]) for p in reports) for k in ('tests','failures','errors','skipped')}
        assert counts==dict(tests=number,failures=0,errors=0,skipped=0)==build['tests']
        assert sha(build['archive'])==build['archiveSha256']
        assert sha(OUT/f'build-{build["stage"]}.log')==build['buildLogSha256']
        suites.extend(reports)
    pylog=OUT/'python-tests.log';content=pylog.read_text(encoding='utf-8')
    assert re.search(r'Ran 14 tests',content) and content.rstrip().endswith('OK')
    write(DOC/'quality-check.json',dict(status='PASS',frozenOldFilesVerified=len(old),
        builds=[dict(stage=b['stage'],commit=b['commit'],tests=b['tests'],jar=b['jar'],sha256=b['jarSha256']) for b in builds],
        pythonTests=14,controllerAttempts=12,representativeSearches=2,
        checkpointFinalStates=len(validation['checkpointChecks']),
        completeCanonicalRegressions=len(validation['canonicalChecks']),
        newControllerCoverage=validation['coverage'],combinedCoverage=validation['combinedCoverage'],
        scope='Two registrations, twelve new controller attempts, one observation/two traversal searches; no wider grid.',
        testLogs={rel(OUT/f'build-{s}.log'):sha(OUT/f'build-{s}.log') for s in ('a','b')}|{rel(pylog):sha(pylog)}))
    documents=[p for p in DOC.rglob('*') if p.is_file() and p.suffix!='.zip'
               and p.name not in ('artifact-manifest.json','evidence-file-hashes.json')]
    evidence=documents+[p for p in OUT.rglob('*') if p.is_file()]+suites
    evidence += [ROOT/'README.md']+list((ROOT/'scripts').glob('*.py'))
    for build in builds:
        evidence += [Path(build['archive']),ROOT/build['jar']]
        evidence += [ROOT/p for p in build['testDatasetHashes']]
    evidence=sorted(set(p for p in evidence if p.is_file()))
    hashes={rel(p):sha(p) for p in evidence}
    write(DOC/'evidence-file-hashes.json',hashes)
    research=archive(DOC/'research-results-se221.zip',documents)
    full=archive(DOC/'experiment-evidence-se221.zip',evidence+[DOC/'evidence-file-hashes.json'])
    write(DOC/'artifact-manifest.json',dict(archives=[research,full],sourceFiles=len(hashes),
        builds=[dict(stage=b['stage'],commit=b['commit'],tree=b['tree'],sourceArchiveSha256=b['archiveSha256'],
            jar=b['jar'],jarSha256=b['jarSha256'],javaVersion=b['javaVersion'],mavenVersion=b['mavenVersion']) for b in builds],
        oldInputs=dict(files=len(old),manifest=rel(OUT/'preserved-inputs.json'),sha256=sha(OUT/'preserved-inputs.json')),
        scope='New evidence, committed source archives, JARs and offline test datasets. Existing SE2/SE2.1/SE2.2 and audit files are external hash-verified dependencies, not duplicated in these ZIPs. Maven cache and runtimes are not bundled.'))
    preserve();print(json.dumps([research,full],indent=2))

if __name__=='__main__':main()
