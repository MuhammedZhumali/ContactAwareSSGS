"""Fixed-node before/after probe and one host crypto microbenchmark; one JVM at a time."""
import json, subprocess
from run_research_validation import ROOT,OUT,JAVA,JAVA_HOME,load,save,sha,relative
from run_security_evaluation_se21 import child

def run():
    folder=OUT/'diagnostics';folder.mkdir(exist_ok=False)
    checkpoint=ROOT/'target/security-evaluation-remaining-demand/runs/demand-day2-multi-none-canonical/checkpoint.json'
    source=ROOT/'docs/research-audit/probes/RootSearchProbe.java'
    baseline=load(OUT/'baseline-binary.json');candidate=load(OUT/'candidate-binary.json')
    cases=[dict(version=v,rule=r,jar=b) for v,b in [('before',baseline),('after',candidate)] for r in ['CANONICAL','CONTINUE_WAIT']]
    save(folder/'protocol.json',dict(cases=cases,checkpoint=dict(file=relative(checkpoint),sha256=sha(checkpoint)),
        probe=dict(file=relative(source),sha256=sha(source)),nodes=20000,searchSeconds=30,processSeconds=45,
        selection='Same saved day2 Multi C1 root for all four probes; node cap, both rules; no controller rerun',
        purpose='Check identical node-limited incumbent/plan and diagnostic phase costs, not optimality or a statistical speed estimate',
        stop='One attempt each, no raised limits, all outcomes retained'))
    classes=folder/'classes';classes.mkdir()
    compile=subprocess.run([str(JAVA_HOME/'bin/javac.exe'),'-cp',str(ROOT/baseline['jar']),'-d',str(classes),str(source)],capture_output=True,text=True)
    (folder/'compile.log').write_text(compile.stdout+compile.stderr,encoding='utf-8');assert compile.returncode==0
    rows=[]
    for c in cases:
        name=c['version']+'-'+c['rule'];output=folder/(name+'.json');jfr=folder/(name+'.jfr')
        process=child([JAVA,'-Xmx1024m','-XX:StartFlightRecording=filename='+str(jfr).replace('\\','/')+',settings=profile,dumponexit=true',
            '-cp',str(classes)+';'+str(ROOT/c['jar']['jar']),'org.contactaware.security.simulation.RootSearchProbe',
            str(checkpoint),c['rule'],str(output)],45,folder/(name+'.log'))
        rows.append(dict(**c,process=process,result=load(output) if output.exists() else None))
        if jfr.exists():
            with (folder/(name+'-samples.json')).open('w',encoding='utf-8') as f:
                subprocess.run([str(JAVA_HOME/'bin/jfr.exe'),'print','--json','--events','jdk.ExecutionSample,jdk.GarbageCollection,jdk.GCPhasePause,jdk.ObjectAllocationSample',str(jfr)],stdout=f,check=True)
        save(folder/'summary.json',rows)
    comparisons=[]
    for rule in ['CANONICAL','CONTINUE_WAIT']:
        a,b=[r['result'] for r in rows if r['rule']==rule]
        comparisons.append(dict(rule=rule,available=a is not None and b is not None,
            observationEqual=a['observation']==b['observation'] if a and b else None,
            planEqual=a['search']['bestPlan']==b['search']['bestPlan'] if a and b else None,
            scoreEqual=a['search']['score']==b['search']['score'] if a and b else None,
            beforeStatus=a['search']['status'] if a else None,afterStatus=b['search']['status'] if b else None,
            beforeNodes=a['search']['exploredNodes'] if a else None,afterNodes=b['search']['exploredNodes'] if b else None))
    save(folder/'comparison.json',comparisons);print(comparisons,flush=True)
    crypto=OUT/'host-crypto';crypto.mkdir(exist_ok=False)
    save(crypto/'protocol.json',dict(jar=candidate,mechanisms=['AES-256 key generation','AES/GCM/NoPadding 1000 byte encrypt, unique 12 byte nonce'],
        warmupPerMechanism=2000,samples=5,operationsPerSample=10000,processSeconds=45,
        scope='One host JVM, includes Java allocation/init overhead, no JMH forks; no handshake/radio/onboard calibration; synthetic costs unchanged'))
    process=child([JAVA,'-Xmx512m','-cp',str(ROOT/candidate['jar']),'org.contactaware.security.simulation.HostCryptoMicrobenchmark',str(crypto/'result.json')],45,crypto/'process.log')
    save(crypto/'process.json',process)

if __name__=='__main__':run()
