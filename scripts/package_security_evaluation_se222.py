#!/usr/bin/env python3
"""Archive only the new evidence; legacy archives are referenced by hash, never copied."""
import shutil
import subprocess
import zipfile
from pathlib import Path
from analyze_security_evaluation_se222 import save
from run_security_evaluation_se222 import ROOT, OUT, DOC, verify
from run_security_evaluation_se21 import load, sha


def main():
    registration = verify()
    build = registration['build']
    archive = DOC/'se222-new-evidence.zip'
    if archive.exists(): raise ValueError('Refusing to replace an existing evidence archive')
    assert not subprocess.check_output(['git','diff',build['commit'],'--','src/main/java','src/test/java'],cwd=ROOT).strip()
    clean_source = Path(build['sourceDirectory'])
    reports = OUT/'verification-reports'; reports.mkdir(exist_ok=True)
    for path in (clean_source/'target/surefire-reports').glob('TEST-*.xml'):
        shutil.copy2(path,reports/path.name)
    focused = OUT/'focused-reports'; focused.mkdir(exist_ok=True)
    for path in (ROOT/'target/surefire-reports').glob('*se222-focused*'):
        if path.is_file(): shutil.copy2(path,focused/path.name)
    changed = subprocess.check_output(['git','diff-tree','--no-commit-id','--name-only','-r',build['commit']],cwd=ROOT,text=True).splitlines()
    files = set()
    for p in OUT.rglob('*'):
        if (p.is_file() and 'test-tmp' not in p.relative_to(OUT).parts
                and p.name not in ('manifest.json', 'package.log') and p.suffix != '.zip'):
            files.add(p)
    for p in DOC.rglob('*'):
        if p.is_file() and p.suffix != '.zip' and p.name not in ('artifact-manifest.json','evidence-file-hashes.json'): files.add(p)
    for name in ('run_security_evaluation_se222.py','analyze_security_evaluation_se222.py','package_security_evaluation_se222.py',
                 'audit_security_evaluation.py','recovery_metrics_se21.py','run_security_evaluation_se21.py','run_security_evaluation_se22.py'):
        files.add(ROOT/'scripts'/name)
    manifest = dict(sourceCommit=build['commit'],sourceTree=build['tree'],jar=build['jar'],jarSha256=build['jarSha256'],
        isolatedBuild=OUT.joinpath('build.json').relative_to(ROOT).as_posix(),registrationSha256=sha(OUT/'registration.json'),
        testCounts=build['tests'],validation=load(DOC/'validation.json'),legacyFilesPreserved=len(load(OUT/'preserved-inputs.json')),
        files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)},
        changedSourceFiles={p:sha(clean_source/p) for p in changed},
        note='New attempts, logs, JAR, changed source files, postprocessors and reports only. Old archives remain external hash-addressed inputs. Full repository is recoverable from the source commit.')
    save(OUT/'manifest.json',manifest)
    files.add(OUT/'manifest.json')
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(files): z.write(p,p.relative_to(ROOT).as_posix())
        for name in changed: z.write(clean_source/name,'source-at-executable-commit/'+name)
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for p in files:
            import hashlib
            assert hashlib.sha256(z.read(p.relative_to(ROOT).as_posix())).hexdigest() == sha(p)
        assert not any(n.endswith('.zip') for n in z.namelist())
    save(DOC/'artifact-manifest.json',dict(sourceCommit=build['commit'],jarSha256=build['jarSha256'],
        archive=archive.name,archiveBytes=archive.stat().st_size,archiveSha256=sha(archive),
        manifest='../../target/security-evaluation-se222/manifest.json',manifestSha256=sha(OUT/'manifest.json'),
        archiveIntegrity='PASS',preservedLegacyFiles=manifest['legacyFilesPreserved']))
    verify()
    print(archive,archive.stat().st_size,sha(archive))


if __name__=='__main__': main()
