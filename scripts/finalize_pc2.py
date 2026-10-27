"""Freeze provenance after serial PC2 executions and targeted validation; never edits history."""
import datetime,json,os,platform,subprocess,sys,zipfile
from pathlib import Path
from run_pc2 import ROOT,OUT,PC1,load,save,sha,relative,verify,JAVA,JAVA_HOME

def main():
    verify()
    binary=load(OUT/'candidate-v4-binary.json');tested=load(OUT/'candidate-v4-sources.json')
    current={relative(p):sha(p) for part in ['src','scripts','docs/practical-controller/pc2'] for p in (ROOT/part).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    current['README.md']=sha(ROOT/'README.md');save(OUT/'final-sources.json',current)
    for name,value in tested.items():
        if name.startswith('src/'):assert sha(ROOT/name)==value,('Untested source change',name)
    old=load(OUT/'before-sources.json')
    changed={name:dict(before=value,after=sha(ROOT/name)) for name,value in old.items() if name.startswith('src/main/') and sha(ROOT/name)!=value}
    assert set(changed)=={'src/main/java/org/contactaware/security/simulation/'+name for name in ['BudgetedHorizonPolicy.java','PlanningLatencyController.java','ResearchValidationMain.java']}
    added={name:value for name,value in current.items() if name.startswith('src/main/') and name not in old}
    assert set(added)=={'src/main/java/org/contactaware/security/simulation/'+name for name in ['FeasibleSeedPlanBuilder.java','PracticalPlanPortfolio.java']}
    save(OUT/'model-invariants.json',dict(changedProduction=changed,addedProduction=added,unchangedExistingCoreFiles=sum(name.startswith('src/main/') for name in old)-len(changed),
        exactSolverGeneratorComparatorExecutorConstraints='BYTE_IDENTICAL_TO_PC1',modelSha256=sha(ROOT/'target/research-validation/models/ISS46.json'),testedProduction='Exact match candidate-v4 isolated build; offline candidate-v2 differs only in unused measured controller path'))
    v2=load(OUT/'candidate-v2-sources.json')
    v3changes=[name for name,value in tested.items() if name.startswith('src/main/') and v2.get(name)!=value]
    assert v3changes==['src/main/java/org/contactaware/security/simulation/PlanningLatencyController.java'],v3changes
    final_archive=OUT/'final-source-snapshot.zip'
    with zipfile.ZipFile(final_archive,'w',zipfile.ZIP_DEFLATED) as z:
        for name in current:z.write(ROOT/name,name)
    hashes={relative(p):sha(p) for base in [OUT,ROOT/'docs/practical-controller/pc2'] for p in base.rglob('*') if p.is_file()
            and not any(x.endswith('-build') for x in p.parts) and p not in [OUT/n for n in ['manifest.json','evidence-file-hashes.json','completion-audit.json']]}
    for version in ['v2','v4']:
        for p in (OUT/f'candidate-{version}-build/target').glob('pc2-oracle-*.json'):hashes[relative(p)]=sha(p)
    save(OUT/'evidence-file-hashes.json',hashes)
    series={name:dict(protocol=load(OUT/name/'protocol.json'),rows=load(OUT/name/'analysis.json')) for name in ['iss46-v1','measured-v4','measured-v4b']}
    for s in series.values():assert {r['id'] for r in s['rows']}.issubset({c['id'] for c in s['protocol']['cases']})
    rows=[row for s in series.values() for row in s['rows']]
    tests=load(OUT/'candidate-v4-tests.json');assert tests['failures']==tests['errors']==tests['exitCode']==0
    archive=OUT/'candidate-v4-sources.zip'
    environment=dict(java=subprocess.check_output([JAVA,'-version'],stderr=subprocess.STDOUT,text=True).strip(),python=sys.version,
        platform=platform.platform(),maven='3.9.9 via pinned .mvn wrapper',javaExecutableSha256=sha(Path(JAVA)),wrapperPropertiesSha256=sha(ROOT/'.mvn/wrapper/maven-wrapper.properties'))
    save(OUT/'manifest.json',dict(stage='PC2',completedAtUtc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
        baseCommit=load(OUT/'starting-version.json')['commit'],treeState='Dirty pre-existing checkout, no new commit; exact source archives distinguish this version',
        startingVersion='starting-version.json',environment=environment,binary=binary,offlineBinary=load(OUT/'candidate-v2-binary.json'),sourceArchive=dict(file=relative(archive),sha256=sha(archive)),
        sourceFileHashes='candidate-v4-sources.json',currentSourceHashes='final-sources.json',evidenceHashes='evidence-file-hashes.json',
        finalSourceArchive=dict(file=relative(final_archive),sha256=sha(final_archive)),
        protocols={name:dict(file=relative(OUT/name/'protocol.json'),sha256=sha(OUT/name/'protocol.json'),binary=s['protocol']['binary']) for name,s in series.items()},model=series['iss46-v1']['protocol']['model'],
        originalArtifactsPreserved=len(load(OUT/'preserved-inputs.json')),attempts=len(rows),statuses=dict(__import__('collections').Counter(r['status'] for r in rows)),
        targetedTests=tests,
        testsScope='Final isolated targeted clean verify only. Earlier overlapping counts are not summed. Failed v3 tests omitted mandatory STOP in expectations; only test expectations changed in v4. Full suite not rerun.',
        rootAblation='candidate-v2-root-ablation/results/results.json',modelInvariants='model-invariants.json'))
    save(OUT/'completion-audit.json',dict(productionIdentity='PASS',historicalArtifacts='PASS',
        registeredCases=sum(len(s['protocol']['cases']) for s in series.values()),attemptedCases=len(rows),statuses=dict(__import__('collections').Counter(r['status'] for r in rows)),
        notRun={name:[c['id'] for c in s['protocol']['cases'] if c['id'] not in {r['id'] for r in s['rows']}] for name,s in series.items()},
        selection={name:load(OUT/name/'comparison-audit.json') for name in series},journalChecks=sum(r.get('audit')=='PASS' for r in rows),
        missedContacts={name:len(load(OUT/name/'missed-contacts.json')) for name in series},note='FINISHED and finite-oracle COMPLETE have distinct meanings; no unbounded optimality/security claim. Initial negative measured runs are preserved, final measured comparison uses the separately registered correction.'))
    print('Final manifest verified',len(rows),'registered attempts',flush=True)

if __name__=='__main__':main()
