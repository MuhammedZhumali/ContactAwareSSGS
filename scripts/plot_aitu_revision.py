"""Publication figures from independently audited new-campaign tables only."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'.tools/figure-python'))
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, Patch
from matplotlib.lines import Line2D
from run_research_validation import ROOT, sha, save, relative

DATA=ROOT/'target/aitu-revision';OUT=ROOT/'docs/aitu-revision/figures'
plt.rcParams.update({'font.family':'Times New Roman','font.size':10,'axes.titlesize':11,
    'axes.labelsize':10,'legend.fontsize':9,'figure.dpi':120,'savefig.dpi':600,
    'axes.spines.top':False,'axes.spines.right':False})
colors={1:'#24618e',2:'#c45920'}
def read(stage):return json.loads((DATA/stage/'tables.json').read_text())
def export(fig,name):
    OUT.mkdir(exist_ok=True)
    for ext in ['png','svg','jpg']:fig.savefig(OUT/(name+'.'+ext),dpi=600,bbox_inches='tight',facecolor='white')
    plt.close(fig)

rows=read('stage2')
fig,ax=plt.subplots(figsize=(6.5,3.5))
ax.set_xlim(-.5,2.5);ax.set_ylim(8.5,-.5)
ax.set_xticks([0,1,2],['Next served','Multi served','Multi minus Next'])
labels=['X01  T80 / L5 / P150','X02  T80 / L2 / P150','X03  T80 / L9 / P150',
        'X04  T60 / L5 / P150','X05  T180 / L5 / P150','X06  T80 / L5 / P180',
        'X07  T80 / L5 / P90','X08  T180 / L5 / P240','X09  T430 / L5 / P150']
ax.set_yticks(range(9),labels)
for i in range(9):
    pair=[]
    for h in [1,2]:
        matches=[r for r in rows if r['family']==f'X{i+1:02d}' and r['horizon']==h]
        assert len(matches)==2
        assert matches[0]['status']==matches[1]['status']
        for k in ['servedContacts','usefulSecureTime','controlCpu','managementBytes','newKeys']:
            assert matches[0].get(k)==matches[1].get(k)
        r=matches[0];v=int(r['servedContacts']) if r['status']=='FINISHED' else None;pair.append(v)
        ax.add_patch(Rectangle((h-1.48,i-.45),.96,.9,facecolor=colors[h] if v is not None else '#eeeeee',
            alpha=.14 if v is not None else 1,hatch=None if v is not None else '///',edgecolor='#aaaaaa',lw=.4))
        ax.text(h-1,i,str(v) if v is not None else 'incomplete',ha='center',va='center',fontsize=10 if v is not None else 8)
    d=pair[1]-pair[0] if None not in pair else None
    ax.text(2,i,f'{d:+d}' if d is not None else 'unknown',ha='center',va='center',fontweight='bold' if d else 'normal')
ax.tick_params(length=0);ax.set_title('Conditional horizon effect after common preparation')
ax.text(.5,-.14,'Both tie rules agree in completed pairs. T: return start; L: window length; P: cryptoperiod (s).',
    ha='center',transform=ax.transAxes,fontsize=8)
fig.tight_layout();export(fig,'horizon_conditions')

rows=read('stage3');levels=[0,1000,5000,20000,100000]
fig,axes=plt.subplots(2,2,figsize=(6.5,5.3),sharex='col')
for col,label in enumerate(['X5','long']):
    for h in [1,2]:
        seq=[next(r for r in rows if r['caseLabel']==label and r['nodes']==n and r['horizon']==h and not r['measured']) for n in levels]
        y=[float(r['usefulSecureTime']) if r['status']=='FINISHED' else np.nan for r in seq]
        axes[0,col].plot(range(5),y,'o-' if h==1 else 's-',color=colors[h],lw=1.6,
            markersize=4 if h==1 else 7,markerfacecolor=colors[h] if h==1 else 'none',label='Next' if h==1 else 'Multi',zorder=2+h)
        axes[1,col].plot(range(5),[float(r['controllerSeconds']) for r in seq],'o-' if h==1 else 's-',color=colors[h],lw=1.6,
            markersize=4 if h==1 else 7,markerfacecolor=colors[h] if h==1 else 'none')
        measured=[next(r for r in rows if r['caseLabel']==label and r['nodes']==n and r['horizon']==h and r['measured']) for n in [1000,20000]]
        axes[0,col].plot([1,3],[float(r['usefulSecureTime']) for r in measured],'x--',color=colors[h],lw=1,markersize=6)
    axes[0,col].set_title('Short autonomous X' if label=='X5' else 'Long-window ISS control')
    axes[1,col].set_xticks(range(5),['No DFS','1k','5k','20k','100k'],rotation=20)
    axes[1,col].set_xlabel('Per-search node allowance')
    for ax in axes[:,col]:ax.grid(axis='y',alpha=.2)
axes[0,0].set_ylabel('Useful payload time (s)');axes[1,0].set_ylabel('Offline controller elapsed (s)')
handles=[Line2D([0],[0],color=colors[1],label='Next'),Line2D([0],[0],color=colors[2],label='Multi'),
         Line2D([0],[0],color='#333333',marker='o',label='Offline'),Line2D([0],[0],color='#333333',marker='x',ls='--',label='Measured delay')]
fig.legend(handles=handles,loc='lower center',ncol=4,frameon=False,bbox_to_anchor=(.5,-.01))
fig.suptitle('Quality and computation under a fixed one-second search timer',y=1.01)
fig.tight_layout(rect=[0,.06,1,1]);export(fig,'quality_budget')

rows=read('stage4');fig,axes=plt.subplots(2,2,figsize=(6.5,5.3))
timevalues=[];bytevalues=[]
for rule in ['CANONICAL','CONTINUE_WAIT']:
    for before in [40,20,5]:
        for delay in [0,2,20]:
            pair=[next(r for r in rows if r['rule']==rule and r['secondsBeforeContactEnd']==before and r['detectionDelaySeconds']==delay and r['horizon']==h) for h in [1,2]]
            if all(r['status']=='FINISHED' and r.get('compromiseToIndependentFrame') is not None for r in pair):
                timevalues.append(float(pair[1]['compromiseToIndependentFrame'])-float(pair[0]['compromiseToIndependentFrame']))
            if all(r['status']=='FINISHED' for r in pair):bytevalues.append(float(pair[1]['confidentialDeliveredBytes'])-float(pair[0]['confidentialDeliveredBytes']))
for ri,rule in enumerate(['CANONICAL','CONTINUE_WAIT']):
    for j,field in enumerate(['compromiseToIndependentFrame','confidentialDeliveredBytes']):
        values=timevalues if j==0 else bytevalues;limit=max([abs(v) for v in values]+[1])
        mat=np.full((3,3),np.nan);labels={}
        for i,before in enumerate([40,20,5]):
            for k,delay in enumerate([0,2,20]):
                pair=[next(r for r in rows if r['rule']==rule and r['secondsBeforeContactEnd']==before and r['detectionDelaySeconds']==delay and r['horizon']==h) for h in [1,2]]
                if not all(r['status']=='FINISHED' for r in pair):labels[i,k]='incomplete'
                elif any(r.get('targetStatus')=='NO_TARGET' for r in pair):labels[i,k]='no target'
                elif any(r.get(field) is None for r in pair):labels[i,k]='censored'
                else:
                    mat[i,k]=float(pair[1][field])-float(pair[0][field]);labels[i,k]=f'{mat[i,k]:+.2f}' if j==0 else f'{mat[i,k]:+.0f}'
        ax=axes[ri,j];cmap=plt.get_cmap('RdBu' if j==0 else 'RdBu_r').copy();cmap.set_bad('#eeeeee')
        ax.imshow(mat,cmap=cmap,vmin=-limit,vmax=limit,aspect='auto')
        for (i,k),label in labels.items():
            ax.text(k,i,label,ha='center',va='center',fontsize=9,color='white' if np.isfinite(mat[i,k]) and abs(mat[i,k])>.65*limit else 'black')
            if not np.isfinite(mat[i,k]):ax.add_patch(Rectangle((k-.5,i-.5),1,1,fill=False,hatch='///',edgecolor='#999999',lw=0))
        ax.set_xticks(range(3),['0','2','20']);ax.set_yticks(range(3),['E-40','E-20','E-5']);ax.set_xlabel('Detection delay (s)')
        if j==0:ax.set_ylabel(rule+'\nCompromise time')
        if ri==0:ax.set_title('Delivery delay difference (s)\nNegative favors Multi' if j==0 else 'Confidential byte difference\nPositive favors Multi')
fig.suptitle('Disclosure sensitivity: Multi minus Next',y=1.01)
fig.legend(handles=[Patch(facecolor='#eeeeee',hatch='///',edgecolor='#999999',label='No paired numeric estimate; status shown')],
           loc='lower center',bbox_to_anchor=(.5,-.025),frameon=False)
fig.tight_layout(rect=[0,.035,1,1]);export(fig,'disclosure_sensitivity')
save(OUT/'provenance.json',{'inputs':{relative(DATA/s/'tables.json'):sha(DATA/s/'tables.json') for s in ['stage2','stage3','stage4']},
    'scriptSha256':sha(__file__),'formats':['600dpi PNG','600dpi JPG','SVG'],'sourceCampaign':'AITU revision only'})
