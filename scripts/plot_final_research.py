"""Standalone scientific PDF/SVG/PNG figures from final audited rows only."""
import collections, sys
from pathlib import Path
from run_final_research import ROOT,OUT,load,save,sha,relative
if (ROOT/'.tools/figure-python').exists():sys.path.insert(0,str(ROOT/'.tools/figure-python'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.lines import Line2D

DEST=ROOT/'docs/paper-final/figures'
POLICIES=['FixedPeriod','PerContact','Next','Multi','Seed H1','Seed H2']
COLORS=dict(zip(POLICIES,['#6a4c93','#31856c','#2166ac','#d26927','#666666','#b5a032']))
plt.rcParams.update({'font.size':8,'axes.titlesize':9,'legend.fontsize':7,'pdf.fonttype':42,
                     'svg.fonttype':'none','savefig.dpi':250,'axes.spines.top':False,'axes.spines.right':False})

def render():
    rows=load(OUT/'tables.json');protocol=load(OUT/'protocol.json');used={}
    validation=load(ROOT/'docs/final-research/validation.json')
    assert validation['status']=='PASS' and validation['registered']==validation['attempted']==len(rows)==108
    assert {r['id'] for r in rows}=={c['id'] for c in protocol['cases']} and all(r['status']!='NOT_RUN' for r in rows)
    assert validation['tableSha256']==sha(OUT/'tables.json') and validation['auditSha256']==sha(OUT/'audit.json')
    assert validation['summarySha256']==sha(OUT/'summary.json') and validation['protocolSha256']==sha(OUT/'protocol.json')
    DEST.mkdir(parents=True,exist_ok=True)
    def done(r):return r and r['status']=='FINISHED'
    def find(**fields):return next((r for r in rows if all(r.get(k)==v for k,v in fields.items())),None)
    def finish(fig,name,selected):
        fig.tight_layout(pad=.9)
        for ext in ['pdf','svg','png']:fig.savefig(DEST/(name+'.'+ext),bbox_inches='tight')
        plt.close(fig)
        used[name]=[dict(id=r['id'],status=r['status'],journal=r.get('journal')) for r in selected if r]
    # Infer registered labels, preserving their original order, never sorting by an outcome.
    core=[r for r in rows if r['block']=='core']
    missions=list(dict.fromkeys(r['scenario'] for r in core))
    loads=list(dict.fromkeys(r['load'] for r in core))
    for name,metrics in [('service_matrix',[('servedContacts','Served contacts'),('usefulSecureTime','Useful payload time (s)')]),
                         ('cost_matrix',[('controlCpu','Modeled operation CPU (s)'),('managementBytes','Management bytes'),('newKeys','New directional keys')])]:
        fig,axes=plt.subplots(len(metrics),len(missions),figsize=(7.2,2.1*len(metrics)),squeeze=False);selected=[]
        for col,mission in enumerate(missions):
            for j,(metric,label) in enumerate(metrics):
                ax=axes[j,col]
                for i,policy in enumerate(POLICIES):
                    for k,workload in enumerate(loads):
                        r=find(block='core',scenario=mission,load=workload,policy=policy);selected.append(r)
                        x=k+(i-2.5)*.13
                        if done(r):ax.bar(x,float(r[metric]),width=.12,color=COLORS[policy])
                        elif r:ax.text(x,.025,'×',ha='center',transform=ax.get_xaxis_transform(),color=COLORS[policy],fontweight='bold')
                ax.set_xticks(range(len(loads)),loads);ax.set_ylabel(label);ax.set_title(mission)
        fig.legend(handles=[Patch(color=COLORS[p],label=p) for p in POLICIES]+
                   [Line2D([0],[0],ls='',label='×: incomplete; final metric unavailable')],
                   loc='upper center',ncol=4,bbox_to_anchor=(.5,1.08))
        finish(fig,name,selected)
    fig,axes=plt.subplots(1,len(missions),figsize=(7.2,2.8),squeeze=False);selected=[]
    for ax,mission in zip(axes[0],missions):
        for i,policy in enumerate(POLICIES):
            for k,workload in enumerate(loads):
                r=find(block='core',scenario=mission,load=workload,policy=policy);selected.append(r)
                if r and r.get('processSeconds') is not None:
                    ax.scatter(k+(i-2.5)*.13,float(r['processSeconds']),marker='o' if done(r) else 'x',color=COLORS[policy],s=24)
        ax.set_yscale('log');ax.set_xticks(range(len(loads)),loads);ax.set_title(mission);ax.set_ylabel('Process elapsed time (s)')
        ax.axhline(protocol['budgets']['processSeconds'],color='#999',ls=':',lw=.8)
    fig.legend(handles=[Patch(color=COLORS[p],label=p) for p in POLICIES]+
               [Line2D([0],[0],marker='x',ls='',color='black',label='Incomplete'),
                Line2D([0],[0],color='#999',ls=':',label='Process watchdog')],loc='upper center',ncol=4,bbox_to_anchor=(.5,1.13))
    finish(fig,'runtime_completion',selected)

    measured=[r for r in rows if r['block']=='measured']
    if measured:
        fig,axes=plt.subplots(2,1,figsize=(7.2,4.3));selected=[]
        for ax,metric,label in zip(axes,['servedContacts','usefulSecureTime'],['Served contacts','Useful payload time (s)']):
            for j,workload in enumerate(loads):
                for i,policy in enumerate(POLICIES[:5]):
                    measuredrow=next((r for r in measured if r['load']==workload and r['policy']==policy),None)
                    offline=find(block='core',scenario=missions[0],load=workload,policy=policy)
                    for offset,r,hatch in [(-.035,offline,None),(.035,measuredrow,'///')]:
                        selected.append(r);x=j+(i-2)*.16+offset
                        if done(r):ax.bar(x,float(r[metric]),width=.065,color=COLORS[policy],hatch=hatch,edgecolor='white',lw=.3)
                        elif r:ax.text(x,.025,'×',ha='center',transform=ax.get_xaxis_transform(),fontsize=7)
            ax.set_xticks(range(len(loads)),loads);ax.set_ylabel(label)
        axes[0].legend(handles=[Patch(color=COLORS[p],label=p) for p in POLICIES[:5]],loc='upper center',ncol=5,bbox_to_anchor=(.5,1.3))
        axes[1].legend(handles=[Patch(facecolor='white',edgecolor='black',label='Offline'),Patch(facecolor='white',edgecolor='black',hatch='///',label='Measured decision delay'),
                               Line2D([0],[0],ls='',label='×: incomplete; unavailable')],loc='upper center',ncol=3,bbox_to_anchor=(.5,-.2))
        finish(fig,'measured_iss',selected)

    security=[r for r in rows if r['block']=='security']
    scenarios=list(dict.fromkeys(r['scenario'] for r in security))
    fig,ax=plt.subplots(figsize=(7.2,3));selected=[]
    for j,scenario in enumerate(scenarios):
        for policy,offset in [('Next',-.17),('Multi',.17)]:
            r=find(block='security',scenario=scenario,policy=policy);selected.append(r);x=j+offset
            if done(r):
                before=float(r['decryptableBeforeBytes']);after=float(r['decryptableAfterBytes'])
                ax.bar(x,before,.3,color=COLORS[policy]);ax.bar(x,after,.3,bottom=before,color=COLORS[policy],hatch='///',edgecolor='white',lw=.3)
            elif r:ax.text(x,.02,'I',transform=ax.get_xaxis_transform(),ha='center')
    ax.set_xticks(range(len(scenarios)),scenarios,rotation=20,ha='right');ax.set_ylabel('Decryptable emitted bytes')
    legend1=ax.legend(handles=[Patch(color=COLORS[p],label=p) for p in ['Next','Multi']],loc='lower left',bbox_to_anchor=(0,1.03));ax.add_artist(legend1)
    ax.legend(handles=[Patch(facecolor='white',edgecolor='black',label='Recorded before disclosure'),Patch(facecolor='white',edgecolor='black',hatch='///',label='Emitted after disclosure'),
                       Line2D([0],[0],ls='',label='I: incomplete; final exposure unavailable')],loc='lower right',bbox_to_anchor=(1,1.03))
    finish(fig,'detected_exposure',selected)
    fig,ax=plt.subplots(figsize=(7.2,3.4));selected=[]
    milestones=[('spacecraft_notice','SC notice','o'),('independent_pair','Independent keys','s'),('independent_sa','Open SA','^'),('independent_frame','Delivered frame','D')]
    recovery_scenarios=[]
    for scenario in scenarios:
        case=next(r for r in security if r['scenario']==scenario)
        model=load(ROOT/case['model'])['scenario']
        if any(incident.get('detectionAt') is not None for incident in model.get('disclosures',[])):
            recovery_scenarios.append(scenario)
    for j,scenario in enumerate(recovery_scenarios):
        for policy,offset in [('Next',-.18),('Multi',.18)]:
            r=find(block='security',scenario=scenario,policy=policy);selected.append(r)
            for k,(metric,_,marker) in enumerate(milestones):
                x=j+offset+(k-1.5)*.035
                status=r.get(metric+'Status') if r else None;value=r.get(metric+'Seconds') if r else None
                if done(r) and value is not None:ax.scatter(x,float(value),marker=marker,color=COLORS[policy],s=28)
                else:
                    mark='I' if not done(r) else 'C' if status=='censored' else 'N/A' if status=='not-applicable' else 'U'
                    ax.text(x,.02,mark,ha='center',transform=ax.get_xaxis_transform(),fontsize=6,color=COLORS[policy])
    ax.set_yscale('symlog',linthresh=1);ax.set_ylabel('Seconds after GS notice');ax.set_xticks(range(len(recovery_scenarios)),recovery_scenarios,rotation=15,ha='right')
    ax.legend(handles=[Line2D([0],[0],marker=m,color='#444',ls='',label=l) for _,l,m in milestones]+[Patch(color=COLORS[p],label=p) for p in ['Next','Multi']],ncol=3,loc='lower center',bbox_to_anchor=(.5,1.12))
    ax.set_title('C: censored; I: incomplete; U: unavailable; N/A: no target (not zero)',fontsize=8)
    finish(fig,'recovery',selected)
    save(DEST/'provenance.json',dict(tableSha256=sha(OUT/'tables.json'),protocolSha256=sha(OUT/'protocol.json'),figures=used,
                                  interpretation='Cross symbols in service/cost/measured panels mean unavailable final quality; recovery C/U/I not zero. All chart values derive from audited final rows.'))

if __name__=='__main__':render()
