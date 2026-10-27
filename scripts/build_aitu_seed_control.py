"""Isolated named portfolio, no shadowing of any frozen production class."""
import subprocess
from aitu_revision import OUT, ROOT, JAR, JAVA, JAVA_HOME, once, now, cp
from run_research_validation import load, sha, relative

dest=OUT/'seed-harness-v2';dest.mkdir(exist_ok=False)
source=ROOT/'src/main/java/org/contactaware/security/simulation/PracticalPlanPortfolio.java'
assert sha(source)==load(ROOT/'target/final-research/final-v2-sources.json')[relative(source)]
original=source.read_text();code=original.replace('PracticalPlanPortfolio','AituSeedRetentionPortfolio')
assert code.count('PC1, SEED_ONLY, SEED_AND_RETENTION, SEED_WITHOUT_SEARCH')==1
code=code.replace('PC1, SEED_ONLY, SEED_AND_RETENTION, SEED_WITHOUT_SEARCH','PC1, SEED_ONLY, SEED_AND_RETENTION, SEED_WITHOUT_SEARCH, SEED_AND_RETENTION_WITHOUT_SEARCH')
assert code.count('variant==Variant.SEED_AND_RETENTION')==2
code=code.replace('variant==Variant.SEED_AND_RETENTION','(variant==Variant.SEED_AND_RETENTION || variant==Variant.SEED_AND_RETENTION_WITHOUT_SEARCH)')
assert code.count('variant!=Variant.SEED_WITHOUT_SEARCH')==1
code=code.replace('variant!=Variant.SEED_WITHOUT_SEARCH','variant!=Variant.SEED_WITHOUT_SEARCH && variant!=Variant.SEED_AND_RETENTION_WITHOUT_SEARCH')
(dest/'AituSeedRetentionPortfolio.java').write_text(code)
main=(OUT/'harness-v1/AituRunMain.java').read_text().replace('AituRunMain','AituSeedRunMain')
main=main.replace('SecurityPolicy policy=switch(f.get("mode")) {','''var seedPortfolio=new AituSeedRetentionPortfolio(planner,rule,
                AituSeedRetentionPortfolio.Variant.SEED_AND_RETENTION_WITHOUT_SEARCH);
        SecurityPolicy policy=switch(f.get("mode")) {
            case "seedonly" -> new SecurityPolicy() {
                public PolicyConfiguration configuration(){return horizon;}
                public PolicySelection select(PlanningObservation o,List<PlanningAction> a){return seedPortfolio.select(o,a).selection();}
            };''')
for old,new in [('bounded.portfolioAudit()','seedPortfolio.audits()'),('bounded.portfolioStops()','seedPortfolio.stoppedWork()')]:
    main=main.replace(old,'(f.get("mode").equals("seedonly") ? '+new+' : '+old+')')
(dest/'AituSeedRunMain.java').write_text(main)
classes=dest/'classes';classes.mkdir()
sources=[dest/'AituSeedRetentionPortfolio.java',dest/'AituSeedRunMain.java',ROOT/'scripts/aitu/AituSeedRetentionCheck.java']
cmd=[str(JAVA_HOME/'bin/javac.exe'),'--release','21','-cp',cp(),'-d',str(classes),*[str(s) for s in sources]]
proc=subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
(dest/'compile.log').write_text(proc.stdout)
if proc.returncode:raise RuntimeError(proc.stdout)
once(dest/'manifest.json',dict(createdUtc=now(),jarSha256=sha(JAR),productionModified=False,
    namedCopy='AituSeedRetentionPortfolio; original PracticalPlanPortfolio never shadowed',
    changes='class name; one enum value; exactly two retention guards and one search guard',
    sources={relative(p):sha(p) for p in sources},classes={relative(p):sha(p) for p in classes.rglob('*.class')},
    buildScriptSha256=sha(__file__)))
print('Built isolated seed-retention control; validation still required',flush=True)
