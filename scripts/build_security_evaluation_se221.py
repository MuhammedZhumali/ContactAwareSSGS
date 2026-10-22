#!/usr/bin/env python3
"""Prepare a committed isolated source tree, then freeze its successful Maven artifact."""
import argparse,hashlib,os,shutil,subprocess,sys,zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from run_security_evaluation_se221 import ROOT,OUT,JAVA,preserve
from run_security_evaluation_se21 import sha,load,write,rel

def main():
    p=argparse.ArgumentParser();p.add_argument('stage',choices=['a','b']);p.add_argument('mode',choices=['prepare','freeze']);a=p.parse_args()
    stage=a.stage; before=OUT/f'build-{stage}-preflight.json'
    if a.mode=='prepare':
        preserve()
        if before.exists():raise ValueError('Build already prepared')
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        directory=ROOT/'target/se221-clean-build'/stage/commit
        directory.mkdir(parents=True)
        archive=directory/'source.zip'; source=directory/'source'
        subprocess.run(['git','archive','--format=zip',f'--output={archive}',commit],cwd=ROOT,check=True)
        with zipfile.ZipFile(archive) as z:z.extractall(source)
        data=load(ROOT/'target/security-evaluation-se22/build.json')['testDatasetHashes']
        for path,h in data.items():
            assert sha(ROOT/path)==h
            dest=source/path;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(ROOT/path,dest)
        (directory/'test-tmp').mkdir()
        write(before,dict(stage=stage,commit=commit,tree=subprocess.check_output(['git','rev-parse','HEAD^{tree}'],cwd=ROOT,text=True).strip(),
            sourceDirectory=str(source),archive=str(archive),testDatasetHashes=data,
            rootStatusAtPrepare=subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),
            testsSelected='CheckpointReliabilityTest,ControllerBudgetAndCheckpointTest' if stage=='a' else 'all'))
        print(source)
    else:
        if (OUT/f'build-{stage}.json').exists():raise ValueError('Artifact already frozen')
        b=load(before);source=Path(b['sourceDirectory']);log=OUT/f'build-{stage}.log'
        assert 'BUILD SUCCESS' in log.read_text(encoding='utf-8')
        hashes={}
        with zipfile.ZipFile(b['archive']) as z:
            for entry in z.infolist():
                if entry.is_dir():continue
                h=hashlib.sha256(z.read(entry.filename)).hexdigest();assert sha(source/entry.filename)==h
                hashes[entry.filename]=h
        suites=list((source/'target/surefire-reports').glob('TEST-*.xml'))
        counts={k:sum(int(ET.parse(s).getroot().attrib[k]) for s in suites) for k in ('tests','failures','errors','skipped')}
        assert counts['tests']>0 and counts['errors']==counts['failures']==counts['skipped']==0
        if stage=='b':assert counts['tests']>361
        jar=ROOT/'target/security-evaluation-se221-build'/f'se221-{stage}-{b["commit"][:12]}.jar'
        jar.parent.mkdir(parents=True,exist_ok=True)
        if jar.exists():raise ValueError('JAR identity exists')
        shutil.copyfile(source/'target/contact-aware-contacts.jar',jar)
        write(OUT/f'build-{stage}.json',dict(b,sourceFileHashes=hashes,archiveSha256=sha(b['archive']),
            jar=rel(jar),jarSha256=sha(jar),tests=counts,buildLogSha256=sha(log),
            javaVersion=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT).decode(),pythonVersion=sys.version,
            mavenVersion='3.9.9 (repository wrapper, unchanged from SE2.2)',
            buildCommand='mvnw.cmd -Dmaven.repo.local=<repo>/.tools/m2 -o -B -ntp -DargLine=<Xmx1024m isolated tmp> '
                +('-Dtest=CheckpointReliabilityTest,ControllerBudgetAndCheckpointTest ' if stage=='a' else '')+'clean verify'))
        preserve();print(stage,b['commit'],counts,sha(jar))
if __name__=='__main__':main()
