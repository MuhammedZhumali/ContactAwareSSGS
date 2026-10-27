"""Replay one frozen final configuration into a NEW directory with the original watchdog."""
import argparse,os
from pathlib import Path
from run_final_research import ROOT,OUT,JAVA,load,save,sha,relative,child,now

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case',required=True)
    parser.add_argument('--out',type=Path,required=True)
    args=parser.parse_args()
    protocol=load(OUT/'protocol.json')
    case=next(c for c in protocol['cases'] if c['id']==args.case)
    assert sha(ROOT/protocol['jar'])==protocol['jarSha256']
    assert sha(ROOT/case['model'])==case['modelSha256']
    dest=args.out.resolve();dest.relative_to(ROOT);dest.mkdir(parents=True,exist_ok=False)
    budgets=protocol['budgets']
    flags=dict(mode=case['mode'],model=case['model'],rule=case['rule'],horizon=case['horizon'],
        portfolio=case['portfolio'],nodes=budgets['nodes'],
        **{'search-ms':budgets['searchMilliseconds'],'decision-budget-ms':0 if case['measured'] else budgets['decisionMilliseconds'],
           'controller-s':0,'period-s':case['period'],'latency-ms':0,'host-scale':1 if case['measured'] else 0,
           'deadline-ms':budgets['decisionMilliseconds'],'out':relative(dest)})
    java=str(Path(os.environ['JAVA_HOME'])/'bin/java.exe') if os.environ.get('JAVA_HOME') else JAVA
    command=[java,'-Xmx1024m','-cp',str(ROOT/protocol['jar']),'org.contactaware.security.simulation.ResearchValidationMain']
    for key,value in flags.items():command.extend(['--'+key,str(value)])
    save(dest/'replay-registration.json',dict(case=case,originalProtocolSha256=sha(OUT/'protocol.json'),
        registeredAtUtc=now(),command=command,note='Separate replay; never substitutes for a registered final attempt'))
    process=child(command,budgets['processSeconds'],dest/'process.log')
    save(dest/'process.json',process);print(process)

if __name__=='__main__':main()
