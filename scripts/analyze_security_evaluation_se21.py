#!/usr/bin/env python3
"""Check the fixed SE2.1 repeats and export factual computation diagnostics."""
from collections import Counter
import csv
from decimal import Decimal
import json
from pathlib import Path
import re
from run_security_evaluation_se21 import ROOT, OUT, DOC, RAW, load, sha, write, preserve_se2

def csv_rows(name, rows):
    fields = list(dict.fromkeys(k for row in rows for k in row))
    with (DOC / 'tables' / name).open('w', encoding='utf-8', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

def gc_log(path):
    if not path.exists():
        return {'gcLogAvailable': False, 'maxLoggedGcPauseMillis': None, 'maxLoggedSafepointMillis': None}
    content = path.read_text(encoding='utf-8')
    pauses = [float(x) for x in re.findall(r'GC\(\d+\) Pause[^\n]*? ([\d.]+)ms\s*$', content, re.M)]
    safepoints = [int(x) / 1e6 for x in re.findall(r'Total: (\d+) ns', content)]
    return {'gcLogAvailable': True, 'maxLoggedGcPauseMillis': max(pauses, default=None),
            'maxLoggedSafepointMillis': max(safepoints, default=None),
            'loggedGcPauses': len(pauses), 'loggedSafepoints': len(safepoints)}

def source_five(metrics):
    costs = metrics['controlCosts']
    return dict(servedContacts=metrics['servedContacts'], usefulSecureTime=metrics['usefulSecureTime'],
                controlCpu=Decimal(str(costs['spacecraftCpu'])) + Decimal(str(costs['groundCpu'])),
                managementBytes=costs['uplinkBytes'] + costs['downlinkBytes'], newKeys=metrics['createdKeys'])

def same_five(a, b):
    return a is not None and b is not None and a.keys() == b.keys() and all(Decimal(str(a[k])) == Decimal(str(b[k])) for k in a)

def main():
    manifest, summary = load(OUT / 'manifest.json'), load(OUT / 'summary.json')
    assert set(summary) == {s['id'] for s in manifest['attempts']}, 'Missing/extra attempt'
    assert sha(DOC / 'protocol.md') == manifest['protocolSha256']
    assert sha(ROOT / 'scripts/run_security_evaluation_se21.py') == manifest['runnerSha256']
    for path, digest in manifest['executables'].items(): assert sha(ROOT / path) == digest
    for capture in manifest['captures']:
        for path, digest in capture['files'].items(): assert sha(ROOT / path) == digest
        replay = load(OUT / 'captures' / capture['case'] / 'replay.json')
        assert replay['historicalSearchesPerformed'] == 0 and replay['status'] == 'MATCHED_FACTUAL_PREFIX'
    searches, controllers, comparisons, phases, logs = [], [], [], [], []
    for name, attempt in summary.items():
        for path, digest in attempt['files'].items(): assert sha(ROOT / path) == digest
        result = attempt.get('result') or {}
        measurement = result.get('measurement') or {}
        search = result.get('search') or {}
        diag = search.get('diagnostics') or {}
        base = dict(attempt=name, case=attempt.get('case'), variant=attempt['variant'], status=attempt['status'],
                    processStatus=attempt['processStatus'], processElapsedSeconds=attempt['processElapsedNanos'] / 1e9,
                    elapsedSeconds=measurement.get('elapsedNanos', 0) / 1e9 if measurement else None,
                    threadCpuSeconds=measurement['threadCpuNanos'] / 1e9 if measurement.get('threadCpuNanos') is not None else None,
                    gcCollectionCountDelta=measurement.get('gcCollectionCountDelta'),
                    gcCollectionTimeMillisDelta=measurement.get('gcCollectionTimeMillisDelta'))
        log = gc_log(OUT / 'runs' / name / 'gc.log')
        logs.append(dict(attempt=name, **log, jfrAvailable=(OUT / 'runs' / name / 'trace.jfr').exists()))
        if attempt['mode'] == 'search':
            runtime = diag.get('runtimeTelemetry')
            searches.append(dict(base, repeat=attempt['repeat'], searchElapsedSeconds=search.get('elapsedNanos', 0) / 1e9,
                nodes=search.get('exploredNodes'), terminalPlans=diag.get('terminalPlans'), maxDepth=diag.get('maxDepth'),
                generatedActions=diag.get('generatedActions'), executedTransitions=diag.get('executedTransitions'),
                stopReason=diag.get('stopReason'), checkpoint=(runtime or {}).get('stoppedAtCheckpoint'),
                generationSeconds=diag.get('generationNanos', 0) / 1e9, executionSeconds=diag.get('executionNanos', 0) / 1e9,
                boundSeconds=diag.get('boundNanos', 0) / 1e9,
                completePlanComparable=search.get('status') == 'COMPLETE', **log))
            # Legacy has only its original three timers. Missing new phases are not reported as zero.
            phases.append(dict(attempt=name, scope='new disjoint phases' if runtime else 'unavailable in legacy',
                **{k: v / 1e9 for k, v in (runtime or {}).get('phaseElapsedNanos', {}).items()},
                unattributedSeconds=runtime['unattributedElapsedNanos'] / 1e9 if runtime else None))
        elif attempt['mode'] == 'controller':
            s = result.get('summary') or {}
            controllers.append(dict(base, decisions=s.get('decisions'), nodes=s.get('exploredNodes'),
                searchSeconds=s.get('searchNanos', 0) / 1e9, finalMetricsAvailable=s.get('finalMetrics') is not None,
                stoppedAt=s.get('stoppedAt'), checkpointPreserved=attempt['checkpointFile'] is not None))
    for c in manifest['captures']:
        complete = [a for a in summary.values() if a.get('case') == c['case'] and a['mode'] == 'search' and a['status'] == 'COMPLETE']
        ref = complete[0]['result']['search'] if complete else None
        scores = all(same_five(ref['score'], a['result']['search']['score']) for a in complete) if ref else None
        plans = all(ref['bestPlan'] == a['result']['search']['bestPlan'] for a in complete) if ref else None
        nodes = len({a['result']['search']['exploredNodes'] for a in complete}) == 1 if complete else None
        comparisons.append(dict(case=c['case'], comparison='completed legacy/current isolated searches',
            completed=len(complete), scoreEqual=scores, canonicalPlanEqual=plans, exploredNodesEqual=nodes,
            status='PASS' if ref and scores and plans and nodes else ('NOT_COMPARABLE' if ref is None else 'FAIL')))
    source = load(RAW / 'summary.json')
    for row in controllers:
        name = row['case']
        if not name.startswith('X-'): continue
        attempt = summary[row['attempt']]
        actual = attempt['result']['summary']
        scored = same_five(actual['finalMetrics'], source_five(source[name]['simulationMetrics']))
        old = load(RAW / source[name]['decisionsFile'])
        new = load(OUT / 'runs' / row['attempt'] / actual['compactDecisionsFile'])
        project = lambda data: [{k: d[k] for k in ('at', 'resumedAt', 'executedAction')} for d in data]
        plans = project(old) == project(new)
        events = load(RAW / source[name]['eventsFile']) == load(OUT / 'runs' / row['attempt'] / actual['actualEventsFile'])
        comparisons.append(dict(case=name, comparison='full controller vs frozen SE2', completed=int(row['status'] == 'FINISHED'),
            scoreEqual=scored, canonicalPlanEqual=plans, journalEqual=events,
            status='PASS' if scored and plans and events and row['status'] == 'FINISHED' else 'FAIL'))
    probe = summary['injected-watchdog-probe']
    assert probe['status'] == 'PROCESS_TIMEOUT' and probe['checkpointRestoration']['result']['status'] == 'RESTORED'
    cumulative = summary['iss3-cp5400-alt-wait-next-none-controller-budget']
    assert cumulative['status'] == 'CONTROLLER_TIME_LIMIT' and cumulative['checkpointFile']
    assert cumulative['result']['summary']['finalMetrics'] is None
    assert all(c['status'] != 'FAIL' for c in comparisons), comparisons
    preserved = preserve_se2()
    for filename, rows in [('search-repeats.csv', searches), ('controller-repeats.csv', controllers),
                           ('reference-comparisons.csv', comparisons), ('search-phases.csv', phases), ('jvm-pauses.csv', logs)]:
        csv_rows(filename, rows)
    report = {'status': 'PASS', 'attempts': len(summary), 'statusCounts': dict(Counter(a['status'] for a in summary.values())),
        'searchStatusCounts': dict(Counter(a['status'] for a in summary.values() if a['mode'] == 'search')),
        'comparisons': comparisons, 'restoredSourceSnapshots': len(manifest['captures']),
        'historicalSearchesPerformedForCapture': 0, 'frozenSE2FilesVerified': len(preserved),
        'cooperativeControllerStop': cumulative['status'], 'externalProbe': probe['checkpointRestoration']['result'],
        'sourceSummarySha256': sha(OUT / 'summary.json'), 'manifestSha256': sha(OUT / 'manifest.json'),
        'analysisSha256': sha(__file__),
        'timingCaveats': ['No original CPU/GC/pause trace exists for 42/3361s anomalies; their cause remains unknown.',
            'CPU counters can have coarse OS resolution; do not infer parallel search from small CPU/elapsed discrepancies.',
            'GC/safepoint logs include startup and export; MXBean deltas cover the timed call but are JVM-wide.',
            'Legacy new-phase counters are unavailable, not zero. Incomplete scores/plans are incumbents, not optima.']}
    write(DOC / 'repeat-validation.json', report)
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
