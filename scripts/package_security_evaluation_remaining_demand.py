#!/usr/bin/env python3
"""Compact SE2.5 evidence, with unchanged executable and historical input identities."""
import hashlib
import zipfile
from analyze_security_evaluation_se222 import save
from run_security_evaluation_remaining_demand import ROOT, OUT, DOC, OLD, verify
from run_security_evaluation_se21 import load, sha, rel


def main():
    r=verify(); archive=OUT/'remaining-demand-evidence.zip'; assert not archive.exists()
    files={p for d in (OUT,DOC) for p in d.rglob('*') if p.is_file() and p.name not in
        ('manifest.json','artifact-manifest.json','package.log') and p.suffix!='.zip'}
    for name in ('run_security_evaluation_remaining_demand.py','analyze_security_evaluation_remaining_demand.py',
                 'package_security_evaluation_remaining_demand.py','test_remaining_demand_metrics.py',
                 'run_security_evaluation_new_days.py','analyze_security_evaluation_new_days.py',
                 'run_security_evaluation_se21.py','run_security_evaluation_se22.py','run_security_evaluation_c3.py',
                 'check_iss_transfer_selection.py','run_security_evaluation_se222.py','analyze_security_evaluation_se222.py',
                 'audit_security_evaluation.py','recovery_metrics_se21.py'):
        files.add(ROOT/'scripts'/name)
    files.add(ROOT/r['build']['jar'])
    for name in ('registration.json','day2/models/none.json','day2/models/late-detected.json'):
        files.add(OLD/name)
    files.add(ROOT/'src/main/java/org/contactaware/security/simulation/SecurityEvaluationNewDaysMain.java')
    manifest=dict(baseCommit=r['baseCommit'],sourceCommit=r['build']['commit'],jarSha256=r['build']['jarSha256'],
        registrationSha256=sha(OUT/'registration.json'),files={rel(p):sha(p) for p in sorted(files)},
        preservedFiles=len(load(OUT/'preserved-inputs.json')),validation=load(DOC/'validation.json'),
        freshChecks='Three Python metric boundary tests, input equivalence, watchdog, journal accounting/recovery/reuse; no Maven rerun',
        historicalBuildTests=r['build']['tests'],note='Historical build validation is not a fresh Maven run; no old archives embedded')
    save(OUT/'manifest.json',manifest);files.add(OUT/'manifest.json')
    with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(files): z.write(p,rel(p))
    with zipfile.ZipFile(archive) as z:
        assert z.testzip() is None
        for p in files: assert hashlib.sha256(z.read(rel(p))).hexdigest()==sha(p)
    save(DOC/'artifact-manifest.json',dict(sourceCommit=r['build']['commit'],baseCommit=r['baseCommit'],jarSha256=r['build']['jarSha256'],
        archive=rel(archive),archiveBytes=archive.stat().st_size,archiveSha256=sha(archive),manifestSha256=sha(OUT/'manifest.json'),
        files=len(files),preservedFiles=manifest['preservedFiles'],integrity='PASS'))
    verify();print(rel(archive),archive.stat().st_size,sha(archive))


if __name__=='__main__': main()
