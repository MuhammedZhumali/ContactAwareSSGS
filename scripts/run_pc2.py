"""PC2 immutable registration, isolated build and serial comparisons; no automatic retries."""
import argparse, datetime, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path
from run_research_validation import ROOT, JAVA, JAVA_HOME, sha, load, save, relative
from run_security_evaluation_se21 import child
OUT=ROOT/'target/practical-controller-pc2'
PC1=ROOT/'target/practical-controller'
TESTS='FeasibleSeedPlanBuilderTest,PracticalPlanPortfolioTest,MeasuredWaitReplanTest,BudgetedHorizonPolicyTest,DecisionDeadlineControllerTest,ControllerBudgetAndCheckpointTest,SecurityPolicyControllerTest,ContinuationReuseControllerTest,RecedingHorizonControllerTest,PlanningLatencyControllerTest,ResearchValidationExportTest,MeasuredPlanningLatencyTest,PracticalManagementRecoveryTest,ChannelInterruptionTest,SearchCancellationTest'

def build():
    import xml.etree.ElementTree as ET
    label=os.environ.get('PC2_BUILD_LABEL','candidate-v1')
    folder=OUT/(label+'-build');folder.mkdir(exist_ok=False)
    for part in ['src','.mvn']:shutil.copytree(ROOT/part,folder/part)
    for part in ['pom.xml','mvnw.cmd']:shutil.copy2(ROOT/part,folder/part)
    fixtures=load(ROOT/'target/research-validation/baseline-restored-build/fixture-manifest.json')
    for name in fixtures:
        p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,p)
    shutil.copytree(OUT/'fixtures',folder/'target/practical-controller-pc2/fixtures')
    # clean removes target, so PC2 evidence fixtures also live in test resources of this isolated copy.
    shutil.copytree(OUT/'fixtures',folder/'pc2-fixtures')
    sources={str(p.relative_to(folder)).replace('\\','/'):sha(p) for part in ['src','.mvn'] for p in (folder/part).rglob('*') if p.is_file()}
    sources.update({n:sha(folder/n) for n in ['pom.xml','mvnw.cmd']});save(OUT/(label+'-sources.json'),sources)
    temp=folder/'tmp';temp.mkdir()
    env=dict(os.environ,JAVA_HOME=str(JAVA_HOME),TEMP=str(temp),TMP=str(temp));env['PATH']=str(JAVA_HOME/'bin')+os.pathsep+env['PATH']
    command=[str(folder/'mvnw.cmd'),'-o','-B','-ntp','-Dmaven.repo.local='+str(ROOT/'.tools/m2'),
        '-DargLine=-Xmx1024m -Djava.io.tmpdir='+str(temp),'-Dpc2.fixtures='+str(folder/'pc2-fixtures'),'-Dtest='+os.environ.get('PC2_TESTS',TESTS),'clean','verify']
    with (OUT/(label+'-build.log')).open('wb') as stream:code=subprocess.run(command,cwd=folder,env=env,stdout=stream,stderr=subprocess.STDOUT).returncode
    reports=list((folder/'target/surefire-reports').glob('TEST-*.xml'))
    counts={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in reports) for k in ['tests','failures','errors']};counts['classes']=len(reports)
    save(OUT/(label+'-tests.json'),dict(**counts,exitCode=code,command=command));print(counts,flush=True)
    if code:raise RuntimeError('Maven failed; retained '+label+'-build.log')
    jar=OUT/(label+'.jar');shutil.copy2(folder/'target/contact-aware-contacts.jar',jar)
    save(OUT/(label+'-binary.json'),dict(jar=relative(jar),sha256=sha(jar),sources=label+'-sources.json',command=command,tests=counts))
    with zipfile.ZipFile(OUT/(label+'-sources.zip'),'w',zipfile.ZIP_DEFLATED) as z:
        for name in sources:z.write(folder/name,name)

def freeze():
    OUT.mkdir(exist_ok=True)
    assert not (OUT/'preserved-inputs.json').exists()
    old=load(PC1/'preserved-inputs.json')
    for folder in [PC1,ROOT/'docs/practical-controller']:
        for p in folder.rglob('*'):
            if p.is_file() and 'pc2' not in p.parts: old[relative(p)]=sha(p)
    for name in ['scripts/run_practical_controller.py','scripts/write_practical_controller_report.py']:
        old[name]=sha(ROOT/name)
    for name,value in old.items(): assert sha(ROOT/name)==value,name
    save(OUT/'preserved-inputs.json',old)
    sources={relative(p):sha(p) for p in (ROOT/'src').rglob('*') if p.is_file() and p.name not in ['FeasibleSeedPlanBuilder.java','FeasibleSeedPlanBuilderTest.java']}
    save(OUT/'before-sources.json',sources)
    with zipfile.ZipFile(OUT/'before-sources.zip','w',zipfile.ZIP_DEFLATED) as z:
        for name in sources:z.write(ROOT/name,name)
    save(OUT/'starting-version.json',dict(commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        dirty=subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),pc1ManifestSha256=sha(PC1/'manifest.json'),
        registeredAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),historicalArtifacts=len(old)))
    print('Preserved',len(old),flush=True)

def verify():
    old=load(OUT/'preserved-inputs.json')
    for name,value in old.items(): assert sha(ROOT/name)==value,name
    print('Historical hashes unchanged:',len(old),flush=True)

def probe():
    label=os.environ.get('PC2_BUILD_LABEL','candidate-v1');binary=load(OUT/(label+'-binary.json'))
    dest=OUT/(label+'-root-ablation');dest.mkdir(exist_ok=False)
    source=ROOT/'docs/practical-controller/pc2/Pc2FixtureProbe.java'
    save(dest/'protocol.json',dict(binary=binary,sourceSha256=sha(source),fixturesManifestSha256=sha(OUT/'fixtures/manifest.json'),
        registeredAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),decisionMilliseconds=2000,processSeconds=30,
        comparisons='Saved PC1 terminal candidate, seed plus same saved candidate, seed+retained plus same saved candidate. No new search; isolate portfolio effects.'))
    classes=dest/'classes';classes.mkdir()
    compilecmd=[str(JAVA_HOME/'bin/javac.exe'),'-cp',str(ROOT/binary['jar']),'-d',str(classes),str(source)]
    with (dest/'compile.log').open('wb') as stream:subprocess.run(compilecmd,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,check=True)
    cmd=[JAVA,'-Xmx1024m','-cp',str(classes)+os.pathsep+str(ROOT/binary['jar']),'org.contactaware.security.simulation.Pc2FixtureProbe',str(OUT/'fixtures'),str(dest/'results')]
    save(dest/'execution.json',dict(command=cmd,process=child(cmd,30,dest/'process.log')))

def register():
    label=os.environ.get('PC2_BUILD_LABEL','candidate-v1');binary=load(OUT/(label+'-binary.json'))
    dest=OUT/'iss46-v1';dest.mkdir(exist_ok=False)
    cases=[]
    for measured in [False,True]:
        for rule in ['CANONICAL','CONTINUE_WAIT']:
            for h in [1,2]:cases.append(dict(mode='practical',measured=measured,rule=rule,horizon=h,portfolio='SEED_AND_RETENTION',period=3600))
        for mode in ['fixed','percontact']:
            cases.append(dict(mode=mode,measured=measured,rule='CANONICAL',horizon=1,portfolio='SEED_AND_RETENTION',period=3600))
    # Four cheap offline seed-only full-run diagnostics, not an enlarged search/sensitivity grid.
    for rule in ['CANONICAL','CONTINUE_WAIT']:
        for h in [1,2]:cases.append(dict(mode='practical',measured=False,rule=rule,horizon=h,portfolio='SEED_WITHOUT_SEARCH',period=3600))
    for i,c in enumerate(cases):c['id']=f"{i+1:02d}-{'measured' if c['measured'] else 'offline'}-{c['mode']}-H{c['horizon']}-{c['rule']}-{c['portfolio']}"
    model=ROOT/'target/research-validation/models/ISS46.json'
    save(dest/'protocol.json',dict(stage='PC2',registeredAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        binary=binary,model=dict(file=relative(model),sha256=sha(model)),cases=cases,
        budgets=dict(nodesPerSearch=5000,searchMilliseconds=1000,decisionMilliseconds=2000,seedStageMilliseconds=350,
            retainedStageMilliseconds=350,rolloutTransitionCap=10000,processSeconds=600,controllerSeconds=0),
        measured=dict(fixedMilliseconds=0,hostScale=1,decisionMilliseconds=2000,
            controls='Identical measured observation/generation/rule-selection pipeline, no artificial search cost'),
        controls='FixedPeriod activation-relative T=3600s (existing R1 control); PerContact JUST_IN_TIME. No parameter fitting.',
        seedOnly='Four offline full runs pre-registered solely to detect first trajectory divergence if local search worsens mission service.',
        objective=['servedContacts','usefulSecureTime','-controlCpu','-managementBytes','-newKeys'],
        exclusions='No attacks/outages/new schedules/model tuning. One attempt per case; all stopped attempts retained. One heavy JVM.',
        stopping='Stop on PROCESS_ERROR; no retry or automatic budget increase.',
        guarantee='Same-root feasible candidate dominance only; not mission-wise horizon dominance or bounded optimality.'))
    print('Registered',len(cases),'attempts',flush=True)

def run():
    run_series('iss46-v1')

def register_corrected():
    folder=OUT/'measured-v4b';folder.mkdir(exist_ok=False)
    p=load(OUT/'iss46-v1/protocol.json');p['stage']='PC2 measured temporal-intent correction'
    p['registeredAtUtc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    p['binary']=load(OUT/'candidate-v4-binary.json');p['cases']=[c for c in p['cases'] if c['measured']]
    p['correctionRegistration']=dict(file='docs/practical-controller/pc2/latency-correction-registration.json',sha256=sha(ROOT/'docs/practical-controller/pc2/latency-correction-registration.json'))
    p['technicalRepeatRegistration']=dict(file='docs/practical-controller/pc2/technical-repeat-registration.json',sha256=sha(ROOT/'docs/practical-controller/pc2/technical-repeat-registration.json'))
    p['supersedesForFinalMeasuredComparison']='iss46-v1 measured attempts preserved in full; same budgets and inputs, corrected temporal revalidation'
    save(folder/'protocol.json',p)
    print('Registered',len(p['cases']),'corrected measured attempts',flush=True)

def run_corrected():
    run_series('measured-v4b')

def run_series(series):
    folder=OUT/series;p=load(folder/'protocol.json');assert not (folder/'summary.json').exists()
    assert sha(ROOT/p['binary']['jar'])==p['binary']['sha256'];assert sha(ROOT/p['model']['file'])==p['model']['sha256']
    rows=[]
    for c in p['cases']:
        dest=folder/'runs'/c['id'];dest.mkdir(parents=True,exist_ok=False)
        flags=dict(mode=c['mode'],model=p['model']['file'],rule=c['rule'],horizon=c['horizon'],portfolio=c['portfolio'],nodes=5000,
            **{'search-ms':1000,'decision-budget-ms':0 if c['measured'] else 2000,'controller-s':0,'period-s':c['period'],
               'latency-ms':0,'host-scale':1 if c['measured'] else 0,'deadline-ms':2000,'out':relative(dest)})
        cmd=[JAVA,'-Xmx1024m','-cp',str(ROOT/p['binary']['jar']),'org.contactaware.security.simulation.ResearchValidationMain']
        for key,value in flags.items():cmd.extend(['--'+key,str(value)])
        save(dest/'invocation.json',dict(command=cmd,protocolSha256=sha(folder/'protocol.json')))
        proc=child(cmd,600,dest/'process.log');save(dest/'process.json',proc)
        result=None;export_error=None
        if (dest/'result.json').exists():
            try:result=load(dest/'result.json')
            except (OSError,json.JSONDecodeError) as error:export_error=str(error)
        status=proc['processStatus'] if proc['processStatus']!='EXITED' else result['status'] if result else 'EXPORT_INCOMPLETE'
        row=dict(**c,process=proc,status=status)
        if export_error:row['exportError']=export_error
        rows.append(row);save(folder/'summary.json',rows)
        print(c['id'],row['status'],round(proc['processElapsedNanos']/1e9,2),flush=True)
        if proc['processStatus']=='PROCESS_ERROR':break

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action');globals()[p.parse_args().action]()
