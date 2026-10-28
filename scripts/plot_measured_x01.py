"""Representative actual contact timelines, selected by a declared median rule."""
import json, statistics
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from analyze_measured_x01 import ns

ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'target/measured-delay-x01-20261028'
OUT=ROOT/'docs/measured-delay-x01/figures'
EPOCH=ns('2026-01-01T00:00:00Z')
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sec(t):return (ns(t)-EPOCH)/1e9

def main():
 manifest=read(BASE/'manifest.json');groups={}
 for c in manifest['cases']:
  if c['phase']!='main':continue
  p=BASE/'runs'/c['run_id']/'result.json'
  if not p.exists():continue
  r=read(p)
  if r['status']=='FINISHED':groups.setdefault(c['pair_id'],{})[c['horizon']]=(c,r)
 candidates={}
 for pair,p in groups.items():
  if set(p)!={1,2}:continue
  a,b=[p[h][1]['simulationMetrics'] for h in (1,2)]
  ds=b['servedContacts']-a['servedContacts'];du=b['usefulSecureTime']-a['usefulSecureTime']
  outcome='win' if ds>0 else 'loss' if ds<0 else 'tie'
  candidates.setdefault((p[1][0]['tie_rule'],outcome),[]).append((pair,du,p))
 selected=[]
 for key,items in sorted(candidates.items()):
  middle=statistics.median(x[1] for x in items)
  item=min(items,key=lambda x:(abs(x[1]-middle),x[0]));selected.append((key,item))
 if not selected:raise ValueError('No completed main pair: timeline selection unavailable')
 OUT.mkdir(parents=True,exist_ok=True)
 plt.rcParams.update({'font.family':'serif','font.size':9,'pdf.fonttype':42,'ps.fonttype':42})
 fig,axes=plt.subplots(len(selected),1,figsize=(10,2.6*len(selected)),squeeze=False,sharex=True)
 provenance=[]
 for ax,(key,(pair,du,policies)) in zip(axes.flat,selected):
  for start,end,label in [(0,20,'C1 · A'),(30,50,'C2 · B'),(80,85,'C3 · A')]:
   ax.axvspan(start,end,color='#e8edf1',zorder=0)
   ax.text((start+end)/2,6.1,label,ha='center',fontsize=9)
   ax.axvline(end,color='#89949e',lw=.5,ls=':')
  summaries=[]
  for h in (1,2):
   c,r=policies[h];base=3 if h==1 else 0;dest=BASE/'runs'/c['run_id']
   delays=read(dest/'latency-decisions.json');events=read(dest/'actual.events.json');actual=read(dest/'actual.decisions.json')
   for d in delays:
    start=sec(d['origin']['at']);end=sec(d['availableAt'])
    if end>start:ax.broken_barh([(start,end-start)],(base+1.65,.48),facecolors='#d99335',edgecolors='none')
   for d in actual:
    a=d.get('executedAction')
    if not a or not a.get('securityOperation'):continue
    if a['kind'] not in ['PREPARE','INSTALL','SWITCH','OPEN','REUSE']:continue
    start=sec(a['at']);end=min(sec(a['until']),85)
    ax.broken_barh([(start,max(0,end-start))],(base+.92,.48),facecolors='#77528d' if a['kind']=='INSTALL' else '#75808a',edgecolors='none')
   for e in events:
    if e['type']!='PAYLOAD_TRANSFERRED':continue
    end=sec(e['at']);duration=float(e['usefulTime'])
    ax.broken_barh([(end-duration,duration)],(base+.19,.48),facecolors='#247f70',edgecolors='none')
   m=r['simulationMetrics'];summaries.append(('Next' if h==1 else 'Multi')+f": S={m['servedContacts']}, U={m['usefulSecureTime']:g}s")
  ax.set_yticks([.43,1.16,1.89,3.43,4.16,4.89],['Multi payload','Multi management','Multi planning','Next payload','Next management','Next planning'])
  ax.set_ylim(-.15,6.55);ax.set_xlim(-2,85)
  ax.set_title(f"{pair}: {summaries[0]}; {summaries[1]}",loc='left',fontsize=10,pad=13)
  for s in ['top','right','left']:ax.spines[s].set_visible(False)
  ax.tick_params(axis='y',length=0);ax.grid(axis='x',alpha=.15)
  provenance.append({'pair_id':pair,'rule':key[0],'outcomeByServedContacts':key[1],'deltaUSeconds':du,
   'selection':'Nearest to median DeltaU among completed pairs with same rule and S outcome; ties by pair_id','run_ids':[policies[h][0]['run_id'] for h in (1,2)]})
 axes[-1,0].set_xlabel('Model time relative to C1 start (s)')
 fig.legend(handles=[Patch(color='#d99335',label='Charged decision interval'),Patch(color='#77528d',label='INSTALL'),
  Patch(color='#75808a',label='Other lifecycle operations'),Patch(color='#247f70',label='Delivered payload frames')],
  loc='lower center',ncol=4,frameon=False,bbox_to_anchor=(.5,0))
 fig.tight_layout(rect=(0,.06,1,1))
 for ext in ['png','pdf','svg']:fig.savefig(OUT/f'measured-timelines.{ext}',dpi=300,bbox_inches='tight')
 (OUT/'provenance.json').write_text(json.dumps(provenance,indent=2)+'\n',encoding='utf-8')
 print('PLOTTED',len(selected),'representative pairs')

if __name__=='__main__':main()
