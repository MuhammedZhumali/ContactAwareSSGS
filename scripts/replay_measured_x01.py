"""Replay one immutable registered attempt into a new directory, never overwrite."""
import argparse,json,os,shutil,subprocess,time
from pathlib import Path
from run_measured_x01 import ROOT,BASE,load,save,sha,verify

def main():
 p=argparse.ArgumentParser();p.add_argument('--id',required=True);p.add_argument('--out',required=True)
 p.add_argument('--java',default=shutil.which('java'));p.add_argument('--dry-run',action='store_true');a=p.parse_args()
 m=load(BASE/'manifest.json');verify(m)
 case=next(c for c in m['cases'] if c['run_id']==a.id)
 dest=Path(a.out).resolve()
 if dest.exists():raise ValueError('Refusing existing output '+str(dest))
 if not a.java:raise ValueError('JDK21 java executable required')
 cp=os.pathsep.join(map(str,[BASE/'build/adapter-classes',ROOT/'target/aitu-revision/harness-v1/classes',BASE/'build/measured-x01.jar']))
 cmd=[a.java,*case['jvmArgs'],'-cp',cp,'org.contactaware.security.simulation.MeasuredX01Main']
 for k,v in case['flags'].items():cmd+=['--'+k,str(ROOT/v) if k=='model' else v]
 cmd+=['--out',str(dest)]
 description={'original':a.id,'configHash':case['config_hash'],'command':cmd,'watchdogSeconds':case['watchdogSeconds']}
 if a.dry_run:print(json.dumps(description,indent=2));return
 dest.mkdir(parents=True);save(dest/'replay-invocation.json',description);started=time.monotonic_ns()
 with (dest/'process.log').open('wb') as stream:
  proc=subprocess.Popen(cmd,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
  try:code=proc.wait(timeout=case['watchdogSeconds']);status='EXITED' if code==0 else 'PROCESS_ERROR'
  except subprocess.TimeoutExpired:proc.kill();code=proc.wait(timeout=10);status='PROCESS_TIMEOUT'
 save(dest/'process.json',{'processStatus':status,'exitCode':code,'processElapsedNanos':time.monotonic_ns()-started})
 print(status)

if __name__=='__main__':main()
