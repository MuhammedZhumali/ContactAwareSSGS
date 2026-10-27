"""Restore the frozen source/fixtures into a NEW isolated folder and run the full Maven suite."""
import argparse,os,shutil,subprocess
from pathlib import Path
from run_final_research import ROOT,OUT,load,sha,save,JAVA_HOME

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out',type=Path,required=True)
    parser.add_argument('--offline',action='store_true')
    parser.add_argument('--maven-repo',type=Path)
    args=parser.parse_args()
    folder=args.out.resolve();folder.relative_to(ROOT)
    folder.mkdir(parents=True,exist_ok=False)
    manifest=load(OUT/'manifest.json')
    source_manifest=ROOT/manifest['sourcesFile']
    assert sha(source_manifest)==manifest['sourcesSha256'],'Source manifest digest mismatch'
    for name,wanted in load(source_manifest).items():
        source=ROOT/name;assert sha(source)==wanted,name
        destination=folder/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
    fixtures=load(ROOT/'target/research-validation/baseline-restored-build/fixture-manifest.json')
    for name,wanted in fixtures.items():
        source=ROOT/name;assert sha(source)==wanted,name
        destination=folder/name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,destination)
    # Deliberately outside this build's target/: clean must not erase required evidence fixtures.
    pc2=ROOT/'target/practical-controller-pc2/fixtures'
    for name,wanted in load(pc2/'manifest.json')['files'].items():
        assert sha(pc2/name)==wanted,name
    shutil.copytree(pc2,folder/'pc2-fixtures')
    temp=folder/'tmp';temp.mkdir()
    java=Path(os.environ.get('JAVA_HOME',str(JAVA_HOME)))
    env=dict(os.environ,JAVA_HOME=str(java),TEMP=str(temp),TMP=str(temp))
    env['PATH']=str(java/'bin')+os.pathsep+env['PATH']
    command=[str(folder/'mvnw.cmd'),'-B','-ntp','-DargLine=-Xmx1024m -Djava.io.tmpdir="'+str(temp)+'"',
             '-Dpc2.fixtures='+str(folder/'pc2-fixtures')]
    if args.offline:command.append('-o')
    if args.maven_repo:command.append('-Dmaven.repo.local='+str(args.maven_repo.resolve()))
    command.extend(['clean','verify'])
    save(folder/'rebuild-registration.json',dict(command=command,sourceManifestSha256=manifest['sourcesSha256'],
        originalJarSha256=manifest['jarSha256'],scope='Separate rebuild; never replaces registered final executable/evidence'))
    with (folder/'rebuild.log').open('wb') as log:
        code=subprocess.run(command,cwd=folder,env=env,stdout=log,stderr=subprocess.STDOUT).returncode
    save(folder/'rebuild-exit.json',dict(exitCode=code))
    print('Maven exit',code,'log',folder/'rebuild.log')
    raise SystemExit(code)

if __name__=='__main__':main()
