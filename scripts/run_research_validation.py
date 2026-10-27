"""R1 evidence runner. New outputs only; every series is registered before execution."""
import argparse, hashlib, json, os, platform, shutil, subprocess, sys, time
from pathlib import Path
from run_security_evaluation_se21 import child

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'target/research-validation'
JAVA_HOME = Path('C:/Program Files/Java/jdk-21.0.10')
JAVA = str(JAVA_HOME/'bin/java.exe')

def sha(p):
    return hashlib.file_digest(open(p, 'rb'), 'sha256').hexdigest()
def load(p): return json.loads(Path(p).read_text(encoding='utf-8-sig'))
def save(p, v):
    p=Path(p); p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(v, ensure_ascii=False, indent=2, default=str)+'\n', encoding='utf-8')
def relative(p): return str(Path(p).relative_to(ROOT)).replace('\\','/')
def cmd(args): return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.STDOUT).strip()

def environment():
    import ctypes, winreg
    class Memory(ctypes.Structure):
        _fields_=[('length',ctypes.c_ulong),('load',ctypes.c_ulong)]+[(n,ctypes.c_ulonglong) for n in ['total','available','page','availPage','virtual','availVirtual','extended']]
    mem=Memory(); mem.length=ctypes.sizeof(mem); ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(mem))
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,r'HARDWARE\DESCRIPTION\System\CentralProcessor\0') as key: cpu=winreg.QueryValueEx(key,'ProcessorNameString')[0]
    save(OUT/'environment.json',dict(commit=cmd(['git','rev-parse','HEAD']),dirty=cmd(['git','status','--porcelain=v1']),
        platform=platform.platform(),python=sys.version,java=cmd([JAVA,'-version']),
        hardware=dict(cpu=cpu,logicalProcessors=os.cpu_count(),ramBytes=mem.total,method='registry + GlobalMemoryStatusEx; CIM unavailable in sandbox'),
        previousSynthesis=load(ROOT/'docs/research-synthesis/validation.json'),userDate='2026-10-25',hostTimestamp=time.time()))

def freeze():
    OUT.mkdir(exist_ok=False)
    history=load(ROOT/'target/research-synthesis/preserved-inputs.json')
    for d in ['docs','target/research-synthesis','target/security-evaluation-remaining-demand']:
        for p in (ROOT/d).rglob('*'):
            if p.is_file() and 'research-audit' not in p.parts: history[relative(p)]=sha(p)
    save(OUT/'preserved-inputs.json',history)
    original={relative(p):sha(p) for base in ['src','scripts','.mvn'] for p in (ROOT/base).rglob('*') if p.is_file() and '__pycache__' not in p.parts}
    for name in ['pom.xml','mvnw.cmd','README.md']: original[name]=sha(ROOT/name)
    save(OUT/'original-sources.json',original)
    shutil.copy2(ROOT/'README.md',OUT/'original-README.md')
    downloads=Path.home()/'Downloads'
    for name in ['Contact_Aware_Research.pdf','ContactAwareSS_SE25.tex']:
        if (downloads/name).exists(): shutil.copy2(downloads/name,OUT/name)
    environment()
    print('Frozen',len(history),'historical files;',len(original),'source files')

def fixtures(base):
    inputs={}
    for name in ['data/sources/earth-20261011','data/sources/celestrak-iss-20261011','data/runs/iss-20261010/orekit','data/runs/iss-20261010/schedule','docs/formal-cost-model-and-horizon']:
        shutil.copytree(ROOT/name,base/name,dirs_exist_ok=True)
        for p in (ROOT/name).rglob('*'):
            if p.is_file(): inputs[relative(p)]=sha(p)
    save(base/'fixture-manifest.json',inputs)

def build(label, source=None, tests=None):
    base=OUT/(label+'-build'); base.mkdir(exist_ok=False)
    source=Path(source) if source else ROOT
    for name in ['src','.mvn']: shutil.copytree(source/name,base/name)
    for name in ['pom.xml','mvnw.cmd']: shutil.copy2(source/name,base/name)
    fixtures(base)
    env=os.environ.copy(); env['JAVA_HOME']=str(JAVA_HOME); env['PATH']=str(JAVA_HOME/'bin')+';'+env['PATH']
    tmp=base/'temp'; tmp.mkdir(); env['TEMP']=env['TMP']=str(tmp)
    args=[str(base/'mvnw.cmd'),'-o','-B','-ntp','-Dmaven.repo.local='+str(ROOT/'.tools/m2'),'-DargLine=-Djava.io.tmpdir='+str(tmp),'clean','verify']
    if tests: args.insert(-2,'-Dtest='+tests)
    with (OUT/(label+'-build.log')).open('w',encoding='utf-8') as log:
        p=subprocess.run(args,cwd=base,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
    save(OUT/(label+'-build.json'),dict(command=args,exit=p.returncode,sources={relative(p):sha(p) for p in (base/'src').rglob('*') if p.is_file()}))
    if p.returncode: raise SystemExit('Build failed; inspect '+str(OUT/(label+'-build.log')))
    jar=base/'target/contact-aware-contacts.jar'; shutil.copy2(jar,OUT/(label+'.jar'))
    save(OUT/(label+'-binary.json'),dict(jar=relative(OUT/(label+'.jar')),sha256=sha(jar),commit=cmd(['git','rev-parse','HEAD']),dirty=cmd(['git','status','--porcelain=v1'])))
    print('Build',label,'PASS',sha(jar))

def baseline_register():
    root=OUT/'baseline'; root.mkdir(exist_ok=False)
    cases=[]
    for name,model in [('X','target/security-evaluation-se22/models/X-none.model.json'),('Y','target/security-evaluation-se22/models/Y-none.model.json'),('ISS7200','target/security-evaluation-se22/models/iss3-cp7200-none.model.json')]:
        for rule in ['CANONICAL','CONTINUE_WAIT']:
            for policy in ['next','multi']:
                cases.append(dict(name=f'{name}-{rule}-{policy}',model=model,sha256=sha(ROOT/model),rule=rule,policy=policy))
    save(root/'protocol.json',dict(series='R1 baseline before production changes',cases=cases,
        jar=load(OUT/'baseline-binary.json'),limits=dict(nodes=2000000,searchSeconds=30,controllerSeconds=60,processSeconds=90),
        timing='offline zero planning delay',seed=None,stop='one attempt per case; no retries or raised budgets; process error stops series',
        objective='served, useful time, -CPU, -management bytes, -new directional keys',excluded=[],
        selection='X/Y and ISS7200 no-attack negative controls, both rules and horizons; original frozen models'))
    print('Registered',len(cases),'baseline attempts')

def baseline_run():
    root=OUT/'baseline'; protocol=load(root/'protocol.json'); jar=ROOT/protocol['jar']['jar']
    assert sha(jar)==protocol['jar']['sha256']
    summary=[]
    for c in protocol['cases']:
        out=root/'runs'/c['name']; out.mkdir(parents=True,exist_ok=False)
        assert sha(ROOT/c['model'])==c['sha256']
        args=[JAVA,'-Xmx1024m','-cp',str(jar),'org.contactaware.security.simulation.SecurityEvaluationC3Main',
              '--model',str(ROOT/c['model']),'--policy',c['policy'],'--rule',c['rule'],'--out',str(out)]
        process=child(args,90,out/'process.log')
        result=load(out/'result.json') if (out/'result.json').exists() else None
        row=dict(**c,process=process,result=result,status=result['status'] if result else process['processStatus'])
        summary.append(row); save(root/'summary.json',summary)
        print(c['name'],row['status'],flush=True)
        if result is None: break

def verify():
    history=load(OUT/'preserved-inputs.json')
    for name,h in history.items():
        assert sha(ROOT/name)==h,name
    print('Preserved',len(history),'files')

if __name__=='__main__':
    p=argparse.ArgumentParser(); p.add_argument('command',choices=['freeze','build','baseline-register','baseline-run','verify']); p.add_argument('--label',default='baseline'); a=p.parse_args()
    if a.command=='build': build(a.label)
    else: globals()[a.command.replace('-','_')]()
