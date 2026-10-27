"""Stage3 dispatcher: frozen controller for positive budgets, named no-DFS adapter only for zero."""
import os
from aitu_revision import ROOT, OUT, JAR, JAVA, PKG, cp, now, once
from run_research_validation import load, save, sha, relative
from run_security_evaluation_se21 import child

base=OUT/'stage3';protocol=load(base/'protocol.json')
assert sha(__file__)==protocol['actualRunnerSha256']
assert sha(JAR)==protocol['jarSha256']
for folder,key in [('harness-v1','classFiles'),('seed-harness-v2','classes')]:
    for p,h in load(OUT/folder/'manifest.json')[key].items():assert sha(ROOT/p)==h,p
rows=load(base/'summary.json') if (base/'summary.json').exists() else []
done={r['id'] for r in rows}
for c in protocol['cases']:
    if c['id'] in done:continue
    dest=base/'runs'/c['id'];dest.mkdir(parents=True,exist_ok=False)
    assert sha(ROOT/c['model'])==c['modelSha256']
    disabled=c['mode']=='seedonly'
    classpath=os.pathsep.join([str(OUT/'seed-harness-v2/classes'),cp()]) if disabled else cp()
    main=PKG+('AituSeedRunMain' if disabled else 'AituRunMain')
    command=[JAVA,'-Xmx1024m','-cp',classpath,main]
    flags={k:c[k] for k in ['initialization','horizon','rule','model','mode','nodes','portfolio']}
    flags.update(out=relative(dest),**{'search-ms':c['searchMs'],'decision-budget-ms':0 if c['measured'] else c['decisionMs'],
        'controller-s':c['controllerSeconds'],'period-s':3600,'latency-ms':0,'host-scale':1 if c['measured'] else 0,
        'deadline-ms':c['decisionMs']})
    for k,v in flags.items():command.extend(['--'+k,str(v)])
    once(dest/'invocation.json',dict(command=command,startedUtc=now(),protocolSha256=sha(base/'protocol.json'),
        noDfs=disabled,productionPlannerClassOrigin=relative(JAR)))
    print('START',c['id'],flush=True)
    process=child(command,c['watchdogSeconds'],dest/'process.log');once(dest/'process.json',process)
    result=load(dest/'result.json') if (dest/'result.json').exists() else None
    status=process['processStatus'] if process['processStatus']!='EXITED' else result['status'] if result else 'EXPORT_INCOMPLETE'
    rows.append(dict(**c,process=process,status=status));save(base/'summary.json',rows)
    print('DONE',c['id'],status,round(process['processElapsedNanos']/1e9,2),flush=True)
