"""Separate final-campaign build and serial runner. Never overwrite prior attempts."""
import argparse, datetime, json, os, shutil, subprocess, zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from run_research_validation import ROOT, JAVA, JAVA_HOME, sha, load, save, relative
from run_security_evaluation_se21 import child

OUT = ROOT / 'target/final-research'

def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def freeze_build():
    OUT.mkdir(exist_ok=True)
    label=os.environ.get('FINAL_BUILD_LABEL','final-v1')
    folder = OUT / (label+'-build')
    folder.mkdir(exist_ok=False)
    if (OUT/'preserved-inputs.json').exists():
        old=load(OUT/'preserved-inputs.json')
    else:
        old = load(ROOT / 'target/practical-controller-pc2/preserved-inputs.json')
        for base in ['target/practical-controller-pc2', 'docs/practical-controller/pc2',
                     'docs/paper-r1', 'docs/research-audit']:
            for p in (ROOT/base).rglob('*'):
                if p.is_file(): old[relative(p)] = sha(p)
        for name, expected in old.items():
            assert sha(ROOT/name) == expected, name
        save(OUT/'preserved-inputs.json', old)
        shutil.copy2(ROOT/'README.md', OUT/'README-before.md')
    for name in ['src', '.mvn']: shutil.copytree(ROOT/name, folder/name)
    for name in ['pom.xml', 'mvnw.cmd']: shutil.copy2(ROOT/name, folder/name)
    fixtures = load(ROOT/'target/research-validation/baseline-restored-build/fixture-manifest.json')
    for name, expected in fixtures.items():
        assert sha(ROOT/name) == expected, name
        target = folder/name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT/name, target)
    shutil.copytree(ROOT/'target/practical-controller-pc2/fixtures', folder/'pc2-fixtures')
    sources = {relative(p): sha(p) for p in (ROOT/'src').rglob('*') if p.is_file()}
    sources.update({relative(p): sha(p) for p in (ROOT/'.mvn').rglob('*') if p.is_file()})
    sources.update({n: sha(ROOT/n) for n in ['pom.xml', 'mvnw.cmd']})
    save(OUT/(label+'-sources.json'), sources)
    with zipfile.ZipFile(OUT/(label+'-sources.zip'), 'w', zipfile.ZIP_DEFLATED) as z:
        for name in sources: z.write(ROOT/name, name)
    temp = folder/'tmp'; temp.mkdir()
    env = dict(os.environ, JAVA_HOME=str(JAVA_HOME), TEMP=str(temp), TMP=str(temp))
    env['PATH'] = str(JAVA_HOME/'bin') + os.pathsep + env['PATH']
    def version(args):
        return subprocess.run(args, cwd=folder, env=env, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, text=True).stdout
    manifest = dict(stage='FINAL', identity=label, registeredAtUtc=now(),
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        tree=subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),
        java=version([JAVA,'-version']), maven=version([str(folder/'mvnw.cmd'),'-v']),
        sourcesFile=relative(OUT/(label+'-sources.json')),sourcesSha256=sha(OUT/(label+'-sources.json')),
        sourceArchiveSha256=sha(OUT/(label+'-sources.zip')), preservedFiles=len(old),
        note='Dirty working source frozen explicitly; commit alone is not binary identity. Full suite, no test filter.')
    save(OUT/(label+'-manifest.json'), manifest)
    save(OUT/'manifest.json', manifest)
    command = [str(folder/'mvnw.cmd'),'-o','-B','-ntp',
        '-Dmaven.repo.local='+str(ROOT/'.tools/m2'),
        '-DargLine=-Xmx1024m -Djava.io.tmpdir='+str(temp),
        '-Dpc2.fixtures='+str(folder/'pc2-fixtures'),'clean','verify']
    with (OUT/(label+'-build.log')).open('wb') as stream:
        process = subprocess.Popen(command,cwd=folder,env=env,stdout=stream,
                                   stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
        try: code = process.wait(timeout=1800)
        except subprocess.TimeoutExpired:
            subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],stdout=stream,stderr=stream)
            process.wait(); code = -1
    reports = list((folder/'target/surefire-reports').glob('TEST-*.xml'))
    counts = {k:sum(int(ET.parse(p).getroot().get(k,0)) for p in reports)
              for k in ['tests','failures','errors','skipped']}
    counts['classes'] = len(reports)
    save(OUT/(label+'-tests.json'),dict(**counts,exitCode=code,command=command))
    print('FULL SUITE',counts,'exit',code,flush=True)
    if code: raise RuntimeError('Full build failed; log and all evidence preserved')
    jar = OUT/(label+'.jar'); shutil.copy2(folder/'target/contact-aware-contacts.jar',jar)
    manifest.update(jar=relative(jar),jarSha256=sha(jar),tests=counts,buildFinishedUtc=now())
    save(OUT/(label+'-manifest.json'),manifest); save(OUT/'manifest.json',manifest)

def verify():
    for name, expected in load(OUT/'preserved-inputs.json').items():
        assert sha(ROOT/name)==expected, name
    m=load(OUT/'manifest.json')
    for name, expected in load(ROOT/m['sourcesFile']).items():
        assert sha(ROOT/name)==expected, name
    m=load(OUT/'manifest.json'); assert sha(ROOT/m['jar'])==m['jarSha256']
    print('Preserved historical files, final production/test source and JAR hashes verified',flush=True)

def run():
    p=load(OUT/'protocol.json'); manifest=load(OUT/'manifest.json')
    assert p['jarSha256']==manifest['jarSha256']==sha(ROOT/manifest['jar'])
    rows=load(OUT/'summary.json') if (OUT/'summary.json').exists() else []
    done={r['id'] for r in rows}
    for c in p['cases']:
        if c['id'] in done: continue
        dest=OUT/'runs'/c['id']; dest.mkdir(parents=True,exist_ok=False)
        assert sha(ROOT/c['model'])==c['modelSha256']
        flags=dict(mode=c['mode'],model=c['model'],rule=c['rule'],horizon=c['horizon'],
            portfolio=c['portfolio'],nodes=p['budgets']['nodes'],
            **{'search-ms':p['budgets']['searchMilliseconds'],
               'decision-budget-ms':0 if c['measured'] else p['budgets']['decisionMilliseconds'],
               'controller-s':0,'period-s':c['period'], 'latency-ms':0,
               'host-scale':1 if c['measured'] else 0,
               'deadline-ms':p['budgets']['decisionMilliseconds'],'out':relative(dest)})
        command=[JAVA,'-Xmx1024m','-cp',str(ROOT/manifest['jar']),
                 'org.contactaware.security.simulation.ResearchValidationMain']
        for key,value in flags.items(): command.extend(['--'+key,str(value)])
        save(dest/'invocation.json',dict(command=command,protocolSha256=sha(OUT/'protocol.json'),startedAtUtc=now()))
        proc=child(command,p['budgets']['processSeconds'],dest/'process.log')
        save(dest/'process.json',proc)
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        status=proc['processStatus'] if proc['processStatus']!='EXITED' else result['status'] if result else 'EXPORT_INCOMPLETE'
        row=dict(**c,process=proc,status=status);rows.append(row);save(OUT/'summary.json',rows)
        print(c['id'],status,round(proc['processElapsedNanos']/1e9,2),flush=True)
        # Independent attempts continue after a recorded technical failure; never retry it.

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['freeze_build','verify','run'])
    globals()[parser.parse_args().action]()
