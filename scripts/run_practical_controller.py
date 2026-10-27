"""PC1: fixed full-schedule practical runs; independent experiment and decision budgets."""
import argparse, datetime, json, shutil, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path
from run_research_validation import ROOT, JAVA, JAVA_HOME, sha, load, save, relative
from run_security_evaluation_se21 import child

OUT=ROOT/'target/practical-controller'
OLD=ROOT/'target/research-validation'
DOC=ROOT/'docs/practical-controller'
TESTS='DecisionDeadlineControllerTest,BudgetedHorizonPolicyTest,ControllerBudgetAndCheckpointTest,SecurityPolicyControllerTest,ContinuationReuseControllerTest,RecedingHorizonControllerTest,PlanningLatencyControllerTest,ResearchValidationExportTest'

def freeze():
    OUT.mkdir(exist_ok=True)
    assert not (OUT/'preserved-inputs.json').exists()
    old=load(OLD/'preserved-inputs.json')
    for name,value in load(OLD/'evidence-file-hashes.json').items():
        if name.startswith(('target/research-validation/','docs/paper-r1/','docs/research-audit/')) or name=='RESEARCH_VALIDATION_RESULTS.md':
            old[name]=value
    for name in ['package.json','r1-reproduction.zip','package-restoration-validation.json']:
        p=OLD/name;old[relative(p)]=sha(p)
    for name,value in old.items():assert sha(ROOT/name)==value,name
    save(OUT/'preserved-inputs.json',old)
    save(OUT/'starting-version.json',dict(baseCommit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        dirty=subprocess.check_output(['git','status','--porcelain=v1'],cwd=ROOT,text=True),r1Binary=load(OLD/'delay-export-binary.json'),
        r1SourceArchiveSha256=sha(OLD/'delay-export-sources.zip')))
    print('Preserved',len(old),'historical artifacts',flush=True)

def build():
    build_version('deadline',TESTS)

def build_latency():
    build_version('latency-verified',TESTS+',MeasuredPlanningLatencyTest,PracticalManagementRecoveryTest,ChannelInterruptionTest,SearchCancellationTest')

def build_version(label,tests):
    import os
    folder=OUT/(label+'-build');folder.mkdir(exist_ok=False)
    for part in ['src','.mvn']:shutil.copytree(ROOT/part,folder/part)
    for part in ['pom.xml','mvnw.cmd']:shutil.copy2(ROOT/part,folder/part)
    fixtures=load(OLD/'baseline-restored-build/fixture-manifest.json')
    for name,value in fixtures.items():
        p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,p)
    sources={str(p.relative_to(folder)).replace('\\','/'):sha(p) for part in ['src','.mvn'] for p in (folder/part).rglob('*') if p.is_file()}
    sources.update({name:sha(folder/name) for name in ['pom.xml','mvnw.cmd']})
    save(OUT/(label+'-build-sources.json'),sources);save(folder/'fixture-manifest.json',fixtures)
    temp=folder/'tmp';temp.mkdir()
    env=dict(os.environ,JAVA_HOME=str(JAVA_HOME),TEMP=str(temp),TMP=str(temp));env['PATH']=str(JAVA_HOME/'bin')+os.pathsep+env['PATH']
    command=[str(folder/'mvnw.cmd'),'-o','-B','-ntp','-Dmaven.repo.local='+str(ROOT/'.tools/m2'),
             '-DargLine=-Djava.io.tmpdir='+str(temp),'-Dtest='+tests,'clean','verify']
    with (OUT/(label+'-build.log')).open('wb') as stream:
        code=subprocess.run(command,cwd=folder,env=env,stdout=stream,stderr=subprocess.STDOUT).returncode
    if code:raise RuntimeError('Maven failed; retained '+label+'-build.log')
    reports=list((folder/'target/surefire-reports').glob('TEST-*.xml'))
    counts={k:sum(int(ET.parse(p).getroot().get(k,0)) for p in reports) for k in ['tests','failures','errors']};counts['classes']=len(reports)
    assert counts['failures']==counts['errors']==0
    jar=OUT/(label+'-v1.jar');shutil.copy2(folder/'target/contact-aware-contacts.jar',jar)
    save(OUT/(label+'-tests.json'),counts)
    save(OUT/(label+'-binary.json'),dict(jar=relative(jar),sha256=sha(jar),sources=label+'-build-sources.json',command=command,tests=counts))
    print(counts,flush=True)

def register():
    folder=OUT/'full-iss-v1';folder.mkdir(exist_ok=False)
    model=OLD/'models/ISS46.json';scenario=load(model)['scenario']
    assert len(scenario['contacts'])==46
    cases=[dict(id=f'{i+1:02d}-ISS46-H{h}-{rule}',horizon=h,rule=rule) for i,(rule,h) in
           enumerate((rule,h) for rule in ['CANONICAL','CONTINUE_WAIT'] for h in [1,2])]
    protocol=dict(series='PC1 full fixed ISS schedule; budget separation only',registeredAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        binary=load(OUT/'deadline-binary.json'),model=dict(file=relative(model),sha256=sha(model)),cases=cases,
        budgets=dict(nodesPerSearch=5000,searchMilliseconds=1000,decisionDeadlineMilliseconds=2000,controllerSeconds=0,processSeconds=600),
        mode='practical; existing R1-v2 bounded incumbent/fallback and suffix rules',physicalPlanningDelay='offline zero-time reference',
        workload='Exactly the R1 ISS46 full chronological schedule/model, unchanged constraints, demand and costs',
        objective=['servedContacts','usefulSecureTime','-controlCpu','-managementBytes','-newKeys'],
        comparison='H1/H2 same algorithm, bounds, actions, tie rule and budgets; no policy-dependent contact filtering',
        stopping='One serial JVM per case, one attempt; no automatic retry or budget increase. Stop series on PROCESS_ERROR. Retain external timeouts and checkpoints.',
        rationale='R1 stopped cumulative controller time at 20 s. Here 600 s is a separate experiment failsafe, not a decision deadline; search budget remains 5000 nodes/1 s. 2 s cooperative whole-decision deadline reserves overhead beyond search.',
        exclusions='None. Incomplete physical runs receive no final service/security metrics.',seed='Deterministic physical model; host deadline outcomes depend on measured runtime.')
    save(folder/'protocol.json',protocol)
    print('Registered',len(cases),'full ISS attempts',flush=True)

def register_latency():
    p=load(OUT/'full-iss-v1/protocol.json')
    folder=OUT/'measured-iss-v1';folder.mkdir(exist_ok=False)
    p.update(series='PC1 measured host planning latency; same full ISS input and search budgets',
        registeredAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),binary=load(OUT/'latency-verified-binary.json'),
        physicalPlanningDelay='fixed=0, scale=1 times measured observation/candidate/selection pipeline at every decision',
        rationale='Follow-on after the completed offline full-schedule comparison. Actual host delay advances physical events before intent revalidation; no subtraction of aggregate runtime.',
        limitations='Host observations, not onboard timing; checkpoint I/O, actual executor and final admission costs outside this delay model. Decision deadline checked at result availability, no hard real-time guarantee.')
    p['budgets']['decisionDeadlineMilliseconds']=0
    p['budgets']['modeledDecisionDeadlineMilliseconds']=2000
    p['hostScale']=1
    save(folder/'protocol.json',p)
    print('Registered four measured-latency ISS attempts',flush=True)

def run():
    run_series('full-iss-v1')

def run_latency():
    run_series('measured-iss-v1')

def run_series(series):
    folder=OUT/series;p=load(folder/'protocol.json');assert not (folder/'summary.json').exists()
    assert sha(ROOT/p['binary']['jar'])==p['binary']['sha256'];assert sha(ROOT/p['model']['file'])==p['model']['sha256']
    rows=[]
    for case in p['cases']:
        dest=folder/'runs'/case['id'];dest.mkdir(parents=True,exist_ok=False)
        b=p['budgets']
        flags=dict(mode='practical',model=p['model']['file'],rule=case['rule'],horizon=case['horizon'],nodes=b['nodesPerSearch'],
                   **{'search-ms':b['searchMilliseconds'],'decision-budget-ms':b['decisionDeadlineMilliseconds'],
                      'controller-s':b['controllerSeconds'],'period-s':60,'latency-ms':0,'host-scale':p.get('hostScale',0),'deadline-ms':b.get('modeledDecisionDeadlineMilliseconds',250),'out':relative(dest)})
        cmd=[JAVA,'-Xmx1024m','-cp',str(ROOT/p['binary']['jar']),'org.contactaware.security.simulation.ResearchValidationMain']
        for key,value in flags.items():cmd.extend(['--'+key,str(value)])
        save(dest/'invocation.json',dict(command=cmd,protocolSha256=sha(folder/'protocol.json')))
        proc=child(cmd,b['processSeconds'],dest/'process.log')
        result=load(dest/'result.json') if (dest/'result.json').exists() else None
        row=dict(**case,process=proc,status=result['status'] if result else proc['processStatus'],result=relative(dest/'result.json') if result else None)
        rows.append(row);save(folder/'summary.json',rows);print(case['id'],row['status'],round(proc['processElapsedNanos']/1e9,2),flush=True)
        if proc['processStatus']=='PROCESS_ERROR':break

def analyze():
    analyze_series('full-iss-v1')

def analyze_latency():
    analyze_series('measured-iss-v1')

def analyze_series(series):
    from analyze_research_validation import audit
    import audit_security_evaluation as legacy
    folder=OUT/series;protocol=load(folder/'protocol.json');scenario=legacy.load(ROOT/protocol['model']['file'])['scenario']
    rows=[]
    for r in load(folder/'summary.json'):
        dest=folder/'runs'/r['id'];row=dict(**r)
        if r['result']:
            result=legacy.load(dest/'result.json');events=legacy.load(dest/'actual.events.json');actual=legacy.load(dest/'actual.actual.json')
            checked=audit(scenario,events,actual,result)
            searches=load(dest/'practical-searches.json');timings=load(dest/'decision-timings.json')
            delays=load(dest/'latency-decisions.json')
            if delays:timings=[dict(d,elapsedNanos=d['hostNanos']) for d in delays]
            saved=legacy.load(dest/'checkpoint.json')['snapshot']
            for field in ['at','securityState','pendingOperations','inFlightFrame','uncertainContexts']:assert saved[field]==actual[field]
            assert saved['journal']==events
            if r['status']=='FINISHED':
                assert actual['at']==scenario['end'],'Incomplete physical timeline'
                assert {e['contactId'] for e in events if e['type']=='CONTACT_STARTED'}=={c['window']['id'] for c in scenario['contacts']},'A full-schedule contact was omitted'
            row.update(five=checked['five'],audit='PASS',decisions=len(timings),searches=len(searches),
                nodes=sum(a['search']['exploredNodes'] for a in searches),decisionDeadlineMisses=sum(d['deadlineMiss'] for d in timings),
                maxDecisionSeconds=max((d['elapsedNanos']/1e9 for d in timings),default=0),
                searchSeconds=sum(a['search']['elapsedNanos']/1e9 for a in searches),
                searchIncomplete=sum(a['search']['status']=='INCOMPLETE' for a in searches),
                certifiedSuffixes=result['computation']['suffixReuses'],feasibleSuffixes=result.get('feasibleSuffixReuses',0),
                stoppedAt=actual['at'],contactsStarted=sum(e['type']=='CONTACT_STARTED' for e in events),checkpoint='PASS')
            save(dest/'journal-audit.json',checked)
        rows.append(row)
    save(folder/'analysis.json',rows)
    print(json.dumps(rows,indent=2,default=str),flush=True)

def verify():
    old=load(OUT/'preserved-inputs.json')
    for name,value in old.items():assert sha(ROOT/name)==value,name
    print('Preserved',len(old),'historical artifacts',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['freeze','build','build_latency','register','register_latency','run','run_latency','analyze','analyze_latency','verify'])
    globals()[parser.parse_args().action]()
