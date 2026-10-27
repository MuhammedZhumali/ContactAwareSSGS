"""Generate final report and paper result tables only after every registered attempt exists."""
import collections
from decimal import Decimal as D
from run_final_research import ROOT,OUT,load,save,sha
from analyze_final_research import FIVE,rank
from final_supplement_tables import render_supplement_results

DOC=ROOT/'docs/final-research'; PAPER=ROOT/'docs/paper-final'

def fmt(value):
    if value is None:return 'NA'
    try:
        n=D(str(value));return f'{n:.2f}'.rstrip('0').rstrip('.') if n!=int(n) else str(int(n))
    except Exception:return str(value)

def tex(value):
    return str(value).replace('_',r'\_').replace('%',r'\%').replace('&',r'\&')

def quality(row):
    return '/'.join(fmt(row.get(k)) for k in FIVE) if row['status']=='FINISHED' else row['status']

def main():
    rows=load(OUT/'tables.json');pairs=load(OUT/'pairs.json');v=load(DOC/'validation.json')
    assert v['registered']==v['attempted']==len(rows)==108 and not any(r['status']=='NOT_RUN' for r in rows)
    assert v['status']=='PASS'
    assert v['tableSha256']==sha(OUT/'tables.json') and v['auditSha256']==sha(OUT/'audit.json')
    assert v['summarySha256']==sha(OUT/'summary.json') and v['protocolSha256']==sha(OUT/'protocol.json')
    m=load(OUT/'manifest.json');core=[r for r in rows if r['block']=='core']
    cp=[p for p in pairs if p['block']=='core'];outcomes=collections.Counter(p.get('objectiveOutcome',p['status']) for p in cp)
    comparable=sum(p['status']=='COMPARABLE' for p in cp)
    verdict=(f"Among {comparable} completed core Next/Multi pairs, Multi is better in {outcomes['MULTI_BETTER']}, "
             f"equal in {outcomes['EQUAL']}, and worse in {outcomes['NEXT_BETTER']} under the five priorities. "
             f"{outcomes['INCOMPLETE_PAIR']} core pairs remain incomplete.")
    conclusion=("The final core does not establish a practical advantage from the extra horizon at the fixed budget. "
                "This does not disprove the conditional exact short-return mechanism or rank unfinished pairs."
                if not outcomes['MULTI_BETTER'] else
                "An extra horizon helps some completed configurations, but the measured costs, losses and incomplete cells limit that finding to the registered settings.")
    policies=['FixedPeriod','PerContact','Next','Multi','Seed H1','Seed H2']
    groups=list(dict.fromkeys((r['scenario'],r['load']) for r in core))
    lines=['# Final research campaign','',verdict,'',conclusion,'',
        f"One frozen JAR: `{m['jarSha256']}`. Full Maven suite: {m['tests']['tests']} passing tests; "
        "the sandbox loopback failure and successful identical-source rebuild are both preserved.",'',
        '## Coverage and interpretation','',f"Registered/attempted: {v['registered']}/{v['attempted']}; statuses: {v['statuses']}. "
        f"Independently audited exported runs: {v['audited']}; same-root full-comparator checks: {v['sameRootComparatorChecks']}.",'',
        'FINISHED means full physical execution, not globally optimal planning. Incomplete final scores are unavailable; '
        'checkpoint prefixes do not enter completed quality comparisons. Costs and traffic remain synthetic. '
        'Host execution times are single observations on this machine, not onboard measurements or independent statistical samples.','',
        '## All primary mission/load cells','',
        'Each finished cell is **served / useful seconds / operation CPU seconds / management bytes / new directional keys**. '
        'An unfinished cell retains its status. Full timing, nodes, deadline, checkpoint and security data are in runs.csv. '
        'That table also reports complete delivered frame counts and bytes separately for uplink/downlink, '
        'with retrospective confidential bytes in each direction. These final totals are unavailable for unfinished executions.','',
        '| Mission | Load | '+' | '.join(policies)+' |','|---|---|'+'---|'*len(policies)]
    for scenario,workload in groups:
        rr=[next(r for r in core if r['scenario']==scenario and r['load']==workload and r['policy']==p) for p in policies]
        lines.append('| '+scenario+' | '+workload+' | '+' | '.join(quality(r) for r in rr)+' |')
    seed_comparisons=[]
    for scenario,workload in groups:
        for seed in ['Seed H1','Seed H2']:
            a=next(r for r in core if r['scenario']==scenario and r['load']==workload and r['policy']==seed)
            b=next(r for r in core if r['scenario']==scenario and r['load']==workload and r['policy']=='Multi')
            complete=a['status']==b['status']=='FINISHED'
            seed_comparisons.append(dict(scenario=scenario,load=workload,seed=seed,seedId=a['id'],multiId=b['id'],
                status='COMPARABLE' if complete else 'INCOMPLETE_PAIR',
                outcome=None if not complete else 'MULTI_BETTER' if rank(b)<rank(a) else 'SEED_BETTER' if rank(a)<rank(b) else 'EQUAL'))
    save(OUT/'seed-comparisons.json',seed_comparisons)
    lines+=['','## Search and seed control','',
        f"Matched core horizon outcomes: {dict(outcomes)}. Seed/Multi comparison coverage: "
        f"{dict(collections.Counter(x['outcome'] or x['status'] for x in seed_comparisons))}.",'',
        'Same-root portfolio dominance does not imply whole-mission dominance. A seed may follow a different future trajectory; '
        'two five-score ties can have different physical slack when actual computation consumes time. '
        'The final search ledger, not the legacy COMPLETE-only computation summary, supplies attempted searches and incomplete counts.','',
        '## Unfinished execution diagnostics','',
        'The following numbers describe only the last saved physical prefix. They are not final service results '
        'and do not enter policy rankings. A watchdog may terminate the process before its in-memory search ledger '
        'is exported; unavailable node, CPU and decision counts remain unavailable. Checkpoint I/O diagnostics '
        'are retained when independently saved.','',
        '| Case | Status | Process s | Saved model time | Contacts started | Served in prefix | Checkpoint retries |',
        '|---|---|---:|---|---:|---:|---:|']
    for r in rows:
        if r['status']=='FINISHED':continue
        lines.append('| '+r['id']+' | '+r['status']+' | '+fmt(r.get('processSeconds'))+' | '+str(r.get('stoppedAt','NA'))+
            ' | '+fmt(r.get('contactsStarted'))+' | '+fmt((r.get('prefixFive') or {}).get('servedContacts'))+
            ' | '+fmt(r.get('checkpointRetries'))+' |')
    lines += ['',
        '## Regression, measured delay, sensitivity and security','',
        '| Case | Status | Five components | Host controller s | Search nodes | Deadline misses |',
        '|---|---|---|---:|---:|---:|']
    for r in rows:
        if r['block']=='core':continue
        lines.append(f"| {r['id']} | {r['status']} | {quality(r)} | {fmt(r.get('controllerSeconds'))} | {r.get('nodes','NA')} | {r.get('deadlineMisses','NA')} |")
    lines+=['','## Disclosure and recovery stages','',
        'Times originate at GS notice. A valid independent replacement pair excludes disclosed material, respects its original '
        'material-age/activation deadlines and counter constraints, and is confirmed at both endpoints. '
        'SA readiness additionally requires an actually open admissible session. Frame recovery requires an actual delivered '
        'independent complete frame. Already-ready states, censoring, unavailable origins and no-target events remain explicit. '
        'Readiness intervals and overlapping blocking conditions are retained in audit.json; first readiness does not imply continuous availability.','',
        'Only FINISHED executions have final recovery milestones here. For other statuses, observed events remain '
        'prefix evidence in audit.json; missing future events are not presented as final censoring.','',
        '| Case | Execution status | Incident/recovery state | SC notice | Independent keys | Independent SA | First independent frame |',
        '|---|---|---|---|---|---|---|']
    for r in rows:
        if r['block']!='security':continue
        milestones=['spacecraft_notice','independent_pair','independent_sa','independent_frame']
        complete=r['status']=='FINISHED'
        stage=[str(r.get(k+'Status','UNAVAILABLE'))+':'+fmt(r.get(k+'Seconds')) if complete else 'UNAVAILABLE (unfinished)' for k in milestones]
        lines.append('| '+r['id']+' | '+r['status']+' | '+str(r.get('recoveryStatus','UNAVAILABLE') if complete else 'UNAVAILABLE (prefix only)')+' | '+' | '.join(stage)+' |')
    lines+=['','## Delivered traffic and emitted exposure','',
        'UL/DL delivered bytes and frames count only complete delivered frames. Confidential delivery is retrospective '
        'through experiment end. Decryptable emitted bytes include recorded traffic before disclosure and emitted '
        'traffic afterward, including aborted prefixes. Exposure and confidential delivery therefore do not partition '
        'the same delivered total. I means unavailable final totals for an unfinished execution.','',
        '| Case | Status | Delivered UL/DL B | Complete UL/DL frames | Confidential UL/DL B | Decryptable emitted before/after B |',
        '|---|---|---:|---:|---:|---:|']
    for r in rows:
        if r['block'] not in ['security','management']:continue
        fields=[('deliveredUplinkBytes','deliveredDownlinkBytes'),('deliveredUplinkFrames','deliveredDownlinkFrames'),
                ('confidentialUplinkBytes','confidentialDownlinkBytes'),('decryptableBeforeBytes','decryptableAfterBytes')]
        values=['/'.join(fmt(r.get(k)) for k in pair) if r['status']=='FINISHED' else 'I' for pair in fields]
        lines.append('| '+r['id']+' | '+r['status']+' | '+' | '.join(values)+' |')
    lines+=['','## Management interruption','']
    for r in rows:
        if r['block']=='management':lines.append(f"- `{r['id']}`: {r['status']}; interrupted={r.get('interruptedOperations','unavailable')}; "
            f"aborted frames={r.get('abortedFrames','unavailable')}; partial CPU={fmt(r.get('partialOperationCpu'))} s, "
            f"partial management={fmt(r.get('partialManagementBytes'))} B; five={quality(r)}.")
    lines+=['','The loss interval is fixed in the registration. It is not shifted to force a particular operation to fail. '
        'Interrupted INSTALL may be retried under existing rules; uncertain SWITCH/OPEN/REUSE remains fail-closed without reconciliation. '
        'No safety claim beyond the verified model and interventions follows.','',
        '## Evidence and limits','',
        '- [Protocol](protocol.md), [all runs](runs.csv), [matched horizons](pairs.csv), [factor deltas](factor-comparisons.csv).',
        '- [Frozen workload descriptors](workload-descriptors.md) and [timing measurement boundaries](timing-definitions.md).',
        '- [Regression diagnosis](regression-diagnosis.md) separates prepared exact reference from the autonomous bounded campaign.',
        '- [Reproduction](reproduction.md), [reference verification](../paper-final/reference-verification.md).',
        '- No outcomes were used to select new model parameters or increase budgets. No unfinished comparison is called a policy loss.',
        '- Historical SE2/R1/PC2 files and archives retain their identities; the final suite is not added to overlapping historical test counts.']
    (DOC/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    # Compact paper table; complete five-score tables stay in the reproducibility supplement CSV.
    fragment=[tex(verdict)+' '+tex(conclusion),'',r'\begin{table*}[t]',r'\centering\footnotesize',
        r'\caption{Final offline core: served contacts / useful secure seconds. I denotes unavailable final quality; its process status is retained in the complete table.}',
        r'\label{tab:core}',r'\begin{tabular}{llrrrrrr}\toprule',
        r'Mission & Load & FixedPeriod & PerContact & Next & Multi & Seed H1 & Seed H2 \\ \midrule']
    for scenario,workload in groups:
        rr=[next(r for r in core if r['scenario']==scenario and r['load']==workload and r['policy']==p) for p in policies]
        cells=[fmt(r['servedContacts'])+'/'+fmt(r['usefulSecureTime']) if r['status']=='FINISHED' else 'I' for r in rr]
        fragment.append(tex(scenario)+' & '+tex(workload)+' & '+' & '.join(cells)+r' \\')
    fragment += [r'\bottomrule\end{tabular}\end{table*}','',
        f"Of 108 registered attempts, {v['statuses'].get('FINISHED',0)} finish; the remaining statuses are "
        +'; '.join(tex(k)+': '+str(n) for k,n in v['statuses'].items() if k!='FINISHED')+'. '+
        f"The event audit checks {v['audited']} exported runs and {v['sameRootComparatorChecks']} full-comparator selections."]
    (PAPER/'final-results.tex').write_text('\n'.join(fragment)+'\n',encoding='utf-8')
    (PAPER/'supplement-results.tex').write_text(render_supplement_results(rows,v),encoding='utf-8')
    save(OUT/'report-provenance.json',dict(tableSha256=sha(OUT/'tables.json'),pairsSha256=sha(OUT/'pairs.json'),
        auditSha256=sha(OUT/'audit.json'),coreOutcomes=dict(outcomes),verdict=verdict,conclusion=conclusion,
        mainResultsSha256=sha(PAPER/'final-results.tex'),supplementResultsSha256=sha(PAPER/'supplement-results.tex')))
    print(verdict)

if __name__=='__main__':main()
