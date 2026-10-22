#!/usr/bin/env python3
"""Bind the clean archive build to a local commit and a unique executable identity."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from run_security_evaluation_se22 import ROOT, OUT, JAVA, JAVAC
from run_security_evaluation_se21 import sha, load, write, rel

def main():
    if (OUT/'build.json').exists(): raise ValueError('Already frozen')
    before=load(OUT/'build-preflight.json'); source=Path(before['sourceDirectory'])
    assert (source/'target/contact-aware-contacts.jar').exists()
    assert 'BUILD SUCCESS' in (OUT/'clean-build.log').read_text(encoding='utf-8')
    sources={}
    with zipfile.ZipFile(before['archive']) as archive:
        for info in archive.infolist():
            if info.is_dir(): continue
            digest=hashlib.sha256(archive.read(info.filename)).hexdigest()
            assert sha(source/info.filename)==digest, info.filename+' changed in clean build directory'
            sources[info.filename]=digest
    reports=list((source/'target/surefire-reports').glob('TEST-*.xml'))
    tests={k:sum(int(ET.parse(p).getroot().attrib[k]) for p in reports) for k in ('tests','failures','errors','skipped')}
    assert tests==dict(tests=361,failures=0,errors=0,skipped=0), tests
    jar=ROOT/'target/security-evaluation-se22-build'/('se22-'+before['commit'][:12]+'.jar')
    jar.parent.mkdir(parents=True,exist_ok=True)
    if jar.exists(): raise ValueError('JAR already exists')
    shutil.copyfile(source/'target/contact-aware-contacts.jar',jar)
    env=dict(os.environ,JAVA_HOME=str(Path(JAVA).parent.parent))
    # The wrapper is invoked through PowerShell, exactly as in the recorded clean build.
    mvn=subprocess.check_output(['powershell','-NoProfile','-Command',"& './mvnw.cmd' --version"],
        cwd=source,env=env,stderr=subprocess.STDOUT).decode(errors='replace')
    record={**before,'baselineCommit':'d3a3a4b77a3c9afa71420222d5fb70813b4d841a',
        'sourceArchiveSha256':sha(before['archive']),'sourceFileHashes':sources,
        'testDatasetHashes':load(OUT/'test-dataset-hashes.json'), 'jar':rel(jar),'jarSha256':sha(jar),
        'rootStatusAtFreeze':subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),
        'tools':{'java':subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT).decode(),
                 'javac':subprocess.check_output([JAVAC,'-version'],stderr=subprocess.STDOUT).decode(),
                 'maven':mvn,'python':sys.version}, 'tests':tests,
        'cleanBuildCommand':"mvnw.cmd -Dmaven.repo.local=C:/Users/Muhalek/ContactAwareSS/.tools/m2 -o -B -ntp -DargLine=<Xmx1024m and isolated test tmp> clean verify",
        'buildLogs':{rel(p):sha(p) for p in (OUT/'clean-build.log',OUT/'clean-build-missing-test-data.log')},
        'buildNote':'First attempt lacked ignored test data. Added 37 separately hashed data files; same committed archive sources, then clean verify passed. No old target/classes copied. Postprocessing scripts created after compile do not enter this JAR.'}
    write(OUT/'build.json',record)
    print(record['commit'],record['jarSha256'],tests)

if __name__=='__main__': main()
