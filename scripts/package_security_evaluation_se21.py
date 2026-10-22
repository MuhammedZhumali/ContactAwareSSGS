#!/usr/bin/env python3
"""Verify preserved inputs and package SE2.1 evidence without replacing SE2."""
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile
from run_security_evaluation_se21 import ROOT, OUT, DOC, RAW, OLD, NEW, load, sha, write, preserve_se2, rel

def archive(destination, files):
    if destination.exists(): raise ValueError('Refusing to replace ' + str(destination))
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(set(files)):
            z.write(path, rel(path))
    with zipfile.ZipFile(destination) as z:
        assert z.testzip() is None
        import hashlib
        for path in files:
            assert hashlib.sha256(z.read(rel(path))).hexdigest() == sha(path)
    return {'file': destination.name, 'sha256': sha(destination), 'bytes': destination.stat().st_size,
            'entries': len(set(files)), 'readbackHashes': 'PASS'}

def main():
    preserved = preserve_se2()
    validation = load(DOC / 'repeat-validation.json')
    assert validation['status'] == 'PASS' and validation['sourceSummarySha256'] == sha(OUT / 'summary.json')
    recovery = load(DOC / 'tables/recovery-metrics.json')
    assert recovery['registeredAttempts'] == 488 and recovery['qualityEligible'] == 474
    for path, digest in recovery['fileHashes'].items(): assert sha(RAW / path) == digest
    assert recovery['postprocessorSha256'] == sha(ROOT / 'scripts/recovery_metrics_se21.py')
    assert recovery['auditHelperSha256'] == sha(ROOT / 'scripts/audit_security_evaluation.py')
    suites = list((ROOT / 'target/surefire-reports').glob('TEST-*.xml'))
    counts = {k: sum(int(ET.parse(p).getroot().attrib[k]) for p in suites) for k in ('tests', 'failures', 'errors', 'skipped')}
    assert counts == dict(tests=344, failures=0, errors=0, skipped=0)
    python_log = ROOT / 'target/security-evaluation-se21-python.log'
    assert re.search(r'Ran 14 tests', python_log.read_text(encoding='utf-8'))
    assert python_log.read_text(encoding='utf-8').rstrip().endswith('OK')
    maven_log = ROOT / 'target/security-evaluation-se21-maven.log'
    assert 'BUILD SUCCESS' in maven_log.read_text(encoding='utf-8')
    assert load(DOC / 'figures/artifact-qa.json')['status'] == 'PASS'
    for filename, digest in load(DOC / 'figures/provenance.json')['files'].items():
        assert sha(DOC / 'figures' / filename) == digest
    assert load(OUT / 'restoration-checks/controller-budget/result.json')['status'] == 'RESTORED'
    write(DOC / 'quality-check.json', {'status': 'PASS', 'maven': counts, 'mavenSuites': len(suites),
        'python': {'tests': 14, 'status': 'PASS'}, 'frozenSE2FilesVerified': len(preserved),
        'sourceJournalFilesVerified': len(recovery['fileHashes']), 'repeatValidation': 'PASS',
        'controllerCheckpointRestored': True, 'figureStructure': 'PASS',
        'visualReview': 'PNG inspected: separate policy and hatch legends, readable axes, no clipped labels.',
        'testLogs': {rel(maven_log): sha(maven_log), rel(python_log): sha(python_log)},
        'scope': '344 tests and the registered 30-attempt diagnostic series; no new full SE2 grid.'})
    documents = [p for p in DOC.rglob('*') if p.is_file() and p.suffix != '.zip'
                 and p.name not in ('artifact-manifest.json', 'evidence-file-hashes.json')]
    research = archive(DOC / 'research-results-se21.zip', documents)
    scripts = [p for p in (ROOT / 'scripts').glob('*.py') if 'se21' in p.name]
    scripts += [ROOT / 'scripts' / n for n in ('audit_security_evaluation.py', 'plot_security_evaluation_se2.py')]
    # Include frozen original raw evidence, old archives and JAR as immutable inputs.
    # This is intentionally larger than the publication bundle.
    evidence = documents + scripts + [ROOT / p for p in preserved] + [NEW, OLD, maven_log, python_log]
    evidence += [p for p in OUT.rglob('*') if p.is_file()]
    evidence += list((ROOT / 'src').rglob('*.java')) + list((ROOT / 'src/test/resources').rglob('*'))
    evidence += suites + [ROOT / p for p in ('pom.xml', 'mvnw.cmd', 'mvnw', '.mvn/wrapper/maven-wrapper.properties',
                                           'README.md', 'docs/planner-foundations.md')]
    evidence = sorted({p for p in evidence if p.is_file()})
    hashes = {rel(p): sha(p) for p in evidence}
    write(DOC / 'evidence-file-hashes.json', hashes)
    evidence.append(DOC / 'evidence-file-hashes.json')
    full = archive(DOC / 'experiment-evidence-se21.zip', evidence)
    write(DOC / 'artifact-manifest.json', {'archives': [research, full], 'sourceFiles': len(hashes),
        'legacyJarSha256': sha(OLD), 'currentJarSha256': sha(NEW),
        'scope': 'Publication bundle and local raw-evidence/source snapshot. Full Maven reproduction also needs repository datasets and Maven dependencies; tooling runtimes/caches are not bundled.'})
    print(json.dumps([research, full], indent=2))

if __name__ == '__main__':
    main()
