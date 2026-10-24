#!/usr/bin/env python3
"""Bundle only SE2.4 schedules, copied inputs, attempts and reproducibility evidence."""
import hashlib
import shutil
import subprocess
import zipfile
from pathlib import Path
from analyze_security_evaluation_se222 import save
from run_security_evaluation_new_days import ROOT, OUT, DOC, verify, preserve
from run_security_evaluation_se21 import load, sha


def main():
    m=verify(); b=m['build']; archive=OUT/'new-days-evidence.zip'
    assert not archive.exists()
    assert not subprocess.check_output(['git','diff',b['commit'],'--','src/main/java','src/test/java'],cwd=ROOT).strip()
    source=Path(b['sourceDirectory']); reports=OUT/'verification-reports';reports.mkdir(exist_ok=True)
    for p in (source/'target/surefire-reports').glob('TEST-*.xml'):shutil.copy2(p,reports/p.name)
    files={p for p in OUT.rglob('*') if p.is_file() and p.suffix!='.zip' and p.name not in ('manifest.json','package.log')}
    files.update(p for p in DOC.iterdir() if p.is_file() and p.name!='artifact-manifest.json')
    for name in ('run_security_evaluation_new_days.py','analyze_security_evaluation_new_days.py','package_security_evaluation_new_days.py',
        'run_security_evaluation_c3.py','check_iss_transfer_selection.py','run_security_evaluation_se21.py','run_security_evaluation_se22.py',
        'run_security_evaluation_se222.py','analyze_security_evaluation_se222.py','audit_security_evaluation.py','recovery_metrics_se21.py'):
        files.add(ROOT/'scripts'/name)
    java=ROOT/'src/main/java/org/contactaware/security/simulation/SecurityEvaluationNewDaysMain.java';files.add(java)
    manifest=dict(sourceCommit=b['commit'],runnerCommit=m['runnerCommit'],sourceTree=b['tree'],
        jar=b['jar'],jarSha256=b['jarSha256'],registrationSha256=sha(OUT/'registration.json'),
        files={p.relative_to(ROOT).as_posix():sha(p) for p in sorted(files)},tests=b['tests'],
        validation=load(DOC/'validation.json'),preservedFiles=preserve(),
        note='Only new evidence and required postprocessors; no old archive copies. Complete source identities in build.json.')
    save(OUT/'manifest.json',manifest);files.add(OUT/'manifest.json')
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(files):z.write(p,p.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for p in files:assert hashlib.sha256(z.read(p.relative_to(ROOT).as_posix())).hexdigest()==sha(p)
        assert not any(n.endswith('.zip') for n in z.namelist())
    save(DOC/'artifact-manifest.json',dict(sourceCommit=b['commit'],runnerCommit=m['runnerCommit'],jarSha256=b['jarSha256'],
        archive=archive.relative_to(ROOT).as_posix(),archiveBytes=archive.stat().st_size,archiveSha256=sha(archive),
        manifestSha256=sha(OUT/'manifest.json'),integrity='PASS',preservedFiles=preserve()))
    print(archive,archive.stat().st_size,sha(archive))


if __name__=='__main__':main()
