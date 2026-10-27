"""Publication figures from independently audited R1 tables; no partial quality imputation."""
import json, collections, sys
from pathlib import Path
from run_research_validation import ROOT,OUT,load,save,sha,relative
if (ROOT/'.tools/figure-python').exists():sys.path.insert(0,str(ROOT/'.tools/figure-python'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from audit_security_evaluation import instant

DEST=ROOT/'docs/paper-r1/figures'
COLORS={1:'#2364aa',2:'#d26527',3:'#26875e'}
plt.rcParams.update({'font.size':8,'axes.titlesize':9,'axes.labelsize':8,'legend.fontsize':7,'pdf.fonttype':42,'svg.fonttype':'none',
    'axes.spines.top':False,'axes.spines.right':False,'savefig.dpi':200})

def render():
    DEST.mkdir(parents=True,exist_ok=True)
    rows=load(OUT/'tables.json');pairs=load(OUT/'pairs.json');audit=load(OUT/'audit.json')['runs'];used={}
    numeric=['usefulSecureTime','controlCpu','managementBytes','controllerSeconds','decryptableBeforeBytes','decryptableAfterBytes']
    for row in rows:
        for field in numeric:
            if row.get(field) is not None:row[field]=float(row[field])
    def find(series,model,h,rule='CANONICAL'):
        return next((r for r in rows if r['series']==series and r['model']==model and r.get('horizon')==h and r.get('rule')==rule),None)
    def finish(fig,name,cases):
        fig.tight_layout(pad=.8)
        for ext in ['pdf','svg','png']:fig.savefig(DEST/(name+'.'+ext),bbox_inches='tight')
        plt.close(fig);used[name]=[dict(series=r['series'],case=r['case'],status=r['status'],journal=r.get('journal')) for r in cases if r]
    def legends(ax):
        ax.legend(handles=[Line2D([0],[0],color=COLORS[h],marker='o',label=('Next H=1' if h==1 else 'Multi H='+str(h))) for h in [1,2]],loc='best')

    fig,(ax,bx)=plt.subplots(2,1,figsize=(3.5,4.4),gridspec_kw={'height_ratios':[1.4,1]})
    models=['X5','demand-none','ISS5400-none','ISS46','33591-base','20580-base'];labels=['X5','ISS demand','ISS CP5400','ISS 46','NOAA 38','HST 48'];selected=[]
    for j,(series,offset,marker) in enumerate([('exact',-.18,'o'),('practical-v2',.18,'s')]):
        for i,m in enumerate(models):
            for h in [1,2]:
                r=find(series,m,h)
                if not r:continue
                selected.append(r);x=i+offset+(h-1.5)*.09
                ax.scatter(x,r['controllerSeconds'],edgecolors=COLORS[h],marker=marker,s=24,
                    facecolors=COLORS[h] if r['status']=='FINISHED' else 'none',linewidths=1.2)
                if r['status']!='FINISHED':ax.annotate('I',(x,r['controllerSeconds']),xytext=(0,4),textcoords='offset points',ha='center',fontsize=6)
    ax.set_xticks(range(len(models)),labels,rotation=35,ha='right');ax.set_ylabel('Host controller time (s)');ax.set_yscale('log');ax.set_title('CANONICAL: filled = finished; I = incomplete')
    ax.legend(handles=[Line2D([0],[0],color=COLORS[h],lw=2,label='H='+str(h)) for h in [1,2]]+
        [Line2D([0],[0],color='#555',marker=m,ls='',label=l) for m,l in [('o','Exact'),('s','Practical')]],ncols=2)
    series=['exact','practical-v2','security-v2','disruptions-v2','sensitivity-v2','deadlines-v3']
    for i,s in enumerate(series):
        batch=[r for r in rows if r['series']==s];n=sum(r['status']=='FINISHED' for r in batch);missing=len(batch)-n
        bx.barh(i,n,color='#34876a');bx.barh(i,missing,left=n,color='#c9cdd3',hatch='//');bx.text(len(batch)+.3,i,f'{n}/{len(batch)}',va='center',fontsize=7)
    bx.set_yticks(range(len(series)),['Exact','Practical','Disclosure','Outages','Sensitivity','Delay']);bx.invert_yaxis();bx.set_xlim(0,53);bx.set_xlabel('Finished / attempted executions')
    finish(fig,'runtime_completion',selected)

    fig,axes=plt.subplots(2,2,figsize=(3.5,3.7));selected=[]
    for rule,style in [('CANONICAL','-'),('CONTINUE_WAIT','--')]:
        values=[find('practical-v2','X5',h,rule) if h<3 else find('sensitivity-v2','X5',h,rule) for h in [1,2,3]];selected+=values
        for ax,metric,label in zip(axes.flat,['servedContacts','usefulSecureTime','controlCpu','managementBytes'],['Served contacts','Useful time (s)','Operation CPU (s)','Management bytes']):
            x=[r['horizon'] for r in values if r and r['status']=='FINISHED'];y=[r[metric] for r in values if r and r['status']=='FINISHED']
            ax.plot(x,y,style,marker='o',label=rule,color='#2364aa' if rule=='CANONICAL' else '#d26527');ax.set_ylabel(label);ax.set_xticks([1,2,3]);ax.set_xlabel('H')
    reference=[find('exact','X5',h,'CONTINUE_WAIT') for h in [1,2]];selected+=reference
    for ax,metric in zip(axes.flat,['servedContacts','usefulSecureTime','controlCpu','managementBytes']):
        valid=[r for r in reference if r and r['status']=='FINISHED']
        ax.plot([r['horizon'] for r in valid],[r[metric] for r in valid],':',marker='^',color='#333',label='Exact CW')
    axes[0,0].set_ylim(0,3.4);axes[0,0].set_yticks([0,1,2,3]);axes[1,0].set_ylim(0,1);axes[1,1].set_ylim(0,1400)
    axes[0,0].legend(fontsize=5.5);fig.suptitle('X5: exact CW and practical controllers',fontsize=9,y=1.02)
    finish(fig,'service_cost_horizon',selected)

    factors=['X-cost-INSTALL-0.5','X-cost-INSTALL-2','X-cost-REUSE-2','X-cost-SWITCH-2','X-cryptoperiod-90','X-cryptoperiod-240',
        'X-maxMaterialAge-200','X-maxReuseGap-50','X-light','X-medium','X-minimum800','X-frame200']
    labels=['INSTALL ×0.5','INSTALL ×2','REUSE ×2','SWITCH ×2','CP 90 s','CP 240 s','Age 200 s','Reuse gap 50 s','Offer 400 B/dir','Offer 1600 B/dir','Minimum 800 B/dir','Frame 200 B']
    fig,(ax,bx)=plt.subplots(1,2,figsize=(3.5,4),sharey=True);selected=[]
    for i,m in enumerate(factors):
        for h in [1,2]:
            r=find('sensitivity-v2',m,h);selected.append(r)
            if r and r['status']=='FINISHED':
                ax.scatter(r['servedContacts'],i+(h-1.5)*.18,color=COLORS[h],s=19);bx.scatter(r['controlCpu'],i+(h-1.5)*.18,color=COLORS[h],s=19)
            else:ax.text(0,i+(h-1.5)*.18,'I',color=COLORS[h],fontsize=6)
    ax.set_yticks(range(len(labels)),labels,fontsize=7);ax.invert_yaxis();ax.set_xlabel('Served contacts');ax.set_xlim(-.2,3.2);bx.set_xlabel('CPU (s)');legends(bx)
    fig.suptitle('One factor at a time; CANONICAL practical mode',fontsize=8,y=1.01);finish(fig,'sensitivity',selected)

    fig,ax=plt.subplots(figsize=(3.5,2.8));selected=[]
    opcolors={'PREPARE':'#a76cc0','INSTALL':'#d7a426','SWITCH':'#d26527','OPEN':'#607580','REUSE':'#607580','PAYLOAD':'#34876a'}
    for j,h in enumerate([1,2]):
        r=find('exact','X5',h,'CONTINUE_WAIT');selected.append(r)
        if not r or r['status']!='FINISHED':continue
        events=load(ROOT/r['journal']);origin=instant('2026-01-01T00:00:00Z');starts={}
        for e in events:
            t=float(instant(e['at'])-origin);key=e['operationId'],e['action']
            if e['type']=='STEP_STARTED':starts[key]=t
            if e['type']=='STEP_COMPLETED' and e['action'] in opcolors and key in starts:
                ax.broken_barh([(starts[key],t-starts[key])],(j-.28,.56),facecolors=opcolors[e['action']])
            if e['type']=='PAYLOAD_TRANSFERRED':ax.broken_barh([(t-float(e['usefulTime']),float(e['usefulTime']))],(j-.28,.56),facecolors=opcolors['PAYLOAD'])
    timeline_model=load(OUT/'models/X5.json')['scenario']
    origin=instant('2026-01-01T00:00:00Z')
    for contact in timeline_model['contacts']:
        window=contact['window'];lo=float(instant(window['start'])-origin);hi=float(instant(window['end'])-origin)
        label=window['id']+' '+window['groundStationId']
        ax.axvspan(lo,hi,alpha=.05,color='k');ax.text((lo+hi)/2,1.62,label,ha='center',fontsize=7)
    ax.set_ylim(-.5,1.9);ax.set_yticks([0,1],['Next','Multi']);ax.set_xlabel('Model time (s)');ax.set_xlim(-3,87)
    ax.legend(handles=[Patch(color=opcolors[k],label=k) for k in ['PREPARE','INSTALL','SWITCH','OPEN','PAYLOAD']],fontsize=6,ncols=3,loc='upper center',bbox_to_anchor=(.5,-.25))
    ax.set_title('X5, CONTINUE_WAIT, exact execution');finish(fig,'mechanism_timeline',selected)

    fig,axes=plt.subplots(2,1,figsize=(3.5,4));selected=[]
    milestones=['spacecraft_notice','independent_pair','independent_sa','independent_frame'];labels=['SC notice','Key activation','SA open','First frame']
    for ax,series,model,title in [(axes[0],'security-v2','X-detected2','X: detection delay 2 s, practical'),(axes[1],'exact','ISS5400-p30-detected','ISS CP5400 p30, exact')]:
        observed=[]
        for h in [1,2]:
            r=find(series,model,h);selected.append(r)
            for i,k in enumerate(milestones):
                value=r.get(k+'Seconds') if r else None
                if r and r['status']=='FINISHED' and value is not None:
                    observed.append(float(value));ax.scatter(float(value),i+(h-1.5)*.15,color=COLORS[h],s=22)
                else:ax.text(0,i+(h-1.5)*.15,'C' if r and r['status']=='FINISHED' else 'I',fontsize=6,color=COLORS[h])
        ax.set_yticks(range(4),labels);ax.invert_yaxis();ax.set_xscale('symlog',linthresh=1);ax.set_xlim(0,max(observed or [1])*1.5);ax.set_xlabel('Seconds from GS notice (symlog)');ax.set_title(title);legends(ax)
    finish(fig,'recovery',selected)

    fig,ax=plt.subplots(figsize=(3.5,3));selected=[];models=['X-detected0','X-detected2','X-detected8','X-gap'];labels=['Delay 0','Delay 2','Delay 8','Gap']
    for i,m in enumerate(models):
        for h in [1,2]:
            r=find('security-v2',m,h);selected.append(r);x=i+(h-1.5)*.32
            if not r or r['status']!='FINISHED':ax.text(x,0,'I',ha='center');continue
            prior=r['decryptableBeforeBytes'];after=r['decryptableAfterBytes']
            ax.bar(x,prior,.29,color=COLORS[h],edgecolor='white');ax.bar(x,after,.29,bottom=prior,color=COLORS[h],hatch='////',edgecolor='white')
    ax.set_xticks(range(4),labels);ax.set_ylabel('Decryptable emitted bytes');ax.set_title('Detected disclosure\nCANONICAL practical mode');ax.set_ylim(0,10000)
    policy=ax.legend(handles=[Patch(color=COLORS[h],label='Next' if h==1 else 'Multi') for h in [1,2]],loc='upper left',fontsize=7);ax.add_artist(policy)
    ax.legend(handles=[Patch(facecolor='#ddd',label='Before disclosure'),Patch(facecolor='#ddd',hatch='////',label='After disclosure')],loc='upper right',fontsize=6)
    finish(fig,'detected_exposure',selected)
    save(DEST/'provenance.json',dict(tables=dict(file=relative(OUT/'tables.json'),sha256=sha(OUT/'tables.json')),figures=used,
        interpretation='Actual configured deterministic executions; I=incomplete, C=censored/not reached; no inferred zeros for unavailable quality'))
    print('Rendered',len(used),'figures')

if __name__=='__main__':render()
