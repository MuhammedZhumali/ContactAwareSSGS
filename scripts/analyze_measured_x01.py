"""Strict, stdlib-only analysis of the registered X01 latency campaign.

No simulator is invoked. Missing planned attempts remain explicit. Only FINISHED
executions receive final scores; interrupted journals are labelled prefix data.
"""
from __future__ import annotations

import argparse
import calendar
import csv
import hashlib
import json
import re
import statistics
from collections import Counter, defaultdict, deque
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'target/measured-delay-x01-20261028'
OUT = ROOT / 'docs/measured-delay-x01/tables'
NS = 1_000_000_000
D = Decimal
ZERO = D(0)
META = ('run_id', 'pair_id', 'phase', 'tie_rule', 'horizon', 'mode', 'repeat', 'config_hash')
SCORES = ('S', 'U', 'control_cpu', 'management_bytes', 'new_keys')
EXECUTED = {'REVALIDATED_FIRST_ACTION', 'DEADLINE_MISS_WAIT', 'STALE_ACTION_WAIT'}
NO_EXECUTION = {'SEARCH_STOPPED', 'END_DURING_COMPUTATION', 'SUPERSEDED_BY_OBSERVED_INCIDENT',
                'WAIT_EVENT_REACHED_REPLAN', 'WAIT_EVENT_CHANGED_REPLAN', 'STALE_INTENT_REPLAN'}
EPOCH_RE = re.compile(r'^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z$')


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def load(path, default=None):
    path = Path(path)
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_float=D)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def instant_ns(value):
    """Preserve all nine fractional digits; datetime handles integral seconds only."""
    match = EPOCH_RE.fullmatch(value)
    require(match is not None, f'Unsupported UTC instant: {value!r}')
    whole = calendar.timegm(datetime.strptime(match[1], '%Y-%m-%dT%H:%M:%S').timetuple())
    return whole * NS + int((match[2] or '').ljust(9, '0'))


T0 = instant_ns('2026-01-01T00:00:00Z')
# Public alias shared by plotting code; returns an absolute integer nanosecond instant.
ns = instant_ns


def duration_ns(value):
    # Jackson exports Duration as decimal seconds, not a binary floating number.
    result = D(str(value)) * NS
    require(result == result.to_integral_value(), f'Subnanosecond duration: {value}')
    return int(result)


def seconds(value):
    return D(value) / NS


def serializable(value):
    if isinstance(value, D):
        return int(value) if value == value.to_integral_value() else float(value)
    if isinstance(value, dict):
        return {k: serializable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [serializable(v) for v in value]
    return value


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(serializable(value), indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def compact(value):
    return json.dumps(serializable(value), ensure_ascii=False, separators=(',', ':')) if value is not None else ''


def save_csv(path, rows, initial=()):
    fields = list(initial)
    for row in rows:
        fields.extend(k for k in row if k not in fields)
    with path.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows({k: '' if v is None else v for k, v in row.items()} for row in rows)


def stats(values):
    values = list(values)
    return {'n': len(values), 'median': statistics.median(values) if values else None,
            'min': min(values) if values else None, 'max': max(values) if values else None}


def metadata(case):
    return {key: case.get(key) for key in META}


def journal_scores(events, scenario):
    contacts = {}
    for contact in scenario['contacts']:
        window, payload = contact['window'], contact['payload']
        require(payload['minimumUplinkBytes'] == payload['minimumDownlinkBytes'] == 400,
                'X01 service thresholds changed')
        contacts[window['id']] = dict(contact_id=window['id'], station=window['groundStationId'],
            start=window['start'], end=window['end'], uplink_bytes=0, downlink_bytes=0,
            uplink_frames=0, downlink_frames=0, useful_time=ZERO, served=False,
            minimum_uplink_bytes=payload['minimumUplinkBytes'], minimum_downlink_bytes=payload['minimumDownlinkBytes'])
    result = dict(S=0, U=ZERO, control_cpu=ZERO, management_bytes=0, new_keys=0)
    last_time, last_sequence = None, None
    for event in events:
        at, sequence = instant_ns(event['at']), event['sequence']
        require(last_time is None or at >= last_time, 'Non-monotonic event time')
        require(last_sequence is None or sequence > last_sequence, 'Non-monotonic event sequence')
        last_time, last_sequence = at, sequence
        usage = event['usage']
        result['U'] += event['usefulTime']
        result['control_cpu'] += usage['groundCpu'] + usage['spacecraftCpu']
        result['management_bytes'] += usage['uplinkBytes'] + usage['downlinkBytes']
        result['new_keys'] += event['createdKeys']
        contact = contacts.get(event.get('contactId'))
        payload_total = sum(event.get(k, 0) for k in ('uplinkPayloadBytes', 'downlinkPayloadBytes', 'uplinkFrames', 'downlinkFrames'))
        require(contact is not None or (payload_total == 0 and event['usefulTime'] == 0),
                'Payload/useful time without a known contact')
        if contact is not None:
            for target, source in [('uplink_bytes', 'uplinkPayloadBytes'), ('downlink_bytes', 'downlinkPayloadBytes'),
                                   ('uplink_frames', 'uplinkFrames'), ('downlink_frames', 'downlinkFrames')]:
                contact[target] += event[source]
            contact['useful_time'] += event['usefulTime']
    for contact in contacts.values():
        contact['served'] = (contact['uplink_bytes'] >= contact['minimum_uplink_bytes']
                             and contact['downlink_bytes'] >= contact['minimum_downlink_bytes'])
        result['S'] += int(contact['served'])
        require(contact['uplink_bytes'] == 100 * contact['uplink_frames'], 'UL frame accounting differs')
        require(contact['downlink_bytes'] == 100 * contact['downlink_frames'], 'DL frame accounting differs')
    require(sum((c['useful_time'] for c in contacts.values()), ZERO) == result['U'], 'Contact U differs from total')
    return result, list(contacts.values())


def validate_finished(score, contacts, result):
    sim = result['simulationMetrics']
    require(sim is not None, 'FINISHED without simulationMetrics')
    costs = sim['controlCosts']
    expected = dict(S=sim['servedContacts'], U=sim['usefulSecureTime'],
        control_cpu=costs['groundCpu'] + costs['spacecraftCpu'],
        management_bytes=costs['uplinkBytes'] + costs['downlinkBytes'], new_keys=sim['createdKeys'])
    require(score == expected, f'Journal / simulationMetrics mismatch: {score} != {expected}')
    declared = result['summary']['finalMetrics']
    require(score == dict(S=declared['servedContacts'], U=declared['usefulSecureTime'],
        control_cpu=declared['controlCpu'], management_bytes=declared['managementBytes'], new_keys=declared['newKeys']),
        'Journal / summary finalMetrics mismatch')
    per_contact = {c['contactId']: c for c in sim['contacts']}
    require(set(per_contact) == {c['contact_id'] for c in contacts}, 'Contact set differs')
    for row in contacts:
        saved = per_contact[row['contact_id']]
        for ours, theirs in [('served', 'served'), ('useful_time', 'usefulTime'), ('uplink_bytes', 'uplinkBytes'),
                             ('downlink_bytes', 'downlinkBytes'), ('uplink_frames', 'uplinkFrames'), ('downlink_frames', 'downlinkFrames')]:
            require(row[ours] == saved[theirs], f'Contact {row["contact_id"]}: {ours} differs')


def action_fields(prefix, action):
    action = action or {}
    return {prefix + '_kind': action.get('kind'), prefix + '_contact': action.get('contactId'),
            prefix + '_at': action.get('at'), prefix + '_until': action.get('until'),
            prefix + '_json': compact(action) if action else ''}


def proof_fields(proof):
    proof = proof or {}
    guard = proof.get('guard') or {}
    return dict(proof_reference=proof.get('rootProofReference'), proof_offset=proof.get('suffixOffset'),
        checked_proof_reference=proof.get('checkedProofReference'), checked_proof_offset=proof.get('checkedOffset'),
        guard_status=guard.get('status'), guard_reason=guard.get('reason'), guard_nanos=proof.get('guardNanos'),
        proof_preparation_nanos=proof.get('preparationNanos'), proof_audit_json=compact(proof) if proof else '')


def search_fields(search):
    search = search or {}
    diagnostic = search.get('diagnostics') or {}
    return dict(nodes=search.get('exploredNodes'), search_nanos=search.get('elapsedNanos'),
        search_status=search.get('status'), search_stop=diagnostic.get('stopReason'),
        search_thread_cpu_nanos=(diagnostic.get('runtimeTelemetry') or {}).get('threadCpuNanos'))


def measured_rows(case, delays, actual, scenario):
    rows, cursor = [], 0
    previous_origin = None
    for index, delay in enumerate(delays):
        origin = delay['origin']['at']
        before, after = instant_ns(origin), instant_ns(delay['availableAt'])
        require(previous_origin is None or before >= previous_origin, 'Latency origins not monotonic')
        previous_origin = before
        host, charged = delay['hostNanos'], duration_ns(delay['modeledLatency'])
        disposition = delay['disposition']
        require(disposition in EXECUTED | NO_EXECUTION, f'Unknown disposition: {disposition}')
        require(host >= 0 and charged >= 0 and after >= before, 'Negative decision interval')
        if disposition == 'SEARCH_STOPPED':
            require(charged == 0 and after == before, 'Stopped search unexpectedly advanced model time')
        else:
            require(charged == host, f'host-scale1/fixed0 mismatch: {charged} != {host}')
            require(after == min(before + charged, instant_ns(scenario['end'])), 'Charged advance mismatch')
        executed, actual_index = None, None
        if disposition in EXECUTED:
            require(cursor < len(actual), f'Latency record {index} has no actual decision')
            executed, actual_index = actual[cursor], cursor
            require(instant_ns(executed['at']) == after and executed['reason'] == disposition,
                    f'Unmatched latency {index}/actual {cursor}: {delay["availableAt"]}, {disposition}')
            cursor += 1
        selection = delay['selection']
        selected_action = selection.get('action')
        executed_action = executed.get('executedAction') if executed else None
        if disposition == 'REVALIDATED_FIRST_ACTION':
            require(selected_action is not None and executed_action is not None, 'Revalidated action missing')
            for key in ('kind', 'contextId', 'keys', 'contactId'):
                require(selected_action.get(key) == executed_action.get(key), f'Revalidated intent changed: {key}')
        row = dict(metadata(case), decision_index=index, actual_decision_index=actual_index,
            model_before=origin, model_after_latency=delay['availableAt'],
            model_before_ns=before - T0, model_after_latency_ns=after - T0,
            model_before_s=seconds(before - T0), model_after_latency_s=seconds(after - T0),
            host_nanos=host, host_seconds=seconds(host), charged_latency_nanos=charged,
            physical_advance_nanos=after - before, decision_deadline_miss=delay['deadlineMiss'],
            disposition=disposition, revalidated=disposition == 'REVALIDATED_FIRST_ACTION',
            replan='REPLAN' in disposition, executed=executed is not None,
            execution_source=executed.get('source') if executed else None,
            resumed_at=executed.get('resumedAt') if executed else None,
            selection_reason=selection.get('reason'))
        row.update(search_fields(selection.get('search')))
        row.update(action_fields('selected', selected_action))
        row.update(action_fields('executed', executed_action))
        row.update(proof_fields(delay.get('continuation')))
        rows.append(row)
    require(cursor == len(actual), f'{len(actual) - cursor} actual decisions lack a latency record')
    return rows


def offline_rows(case, actual, timings):
    require(len(actual) <= len(timings), 'Offline executions exceed timing records')
    rows = []
    for index, timing in enumerate(timings):
        executed = actual[index] if index < len(actual) else None
        if executed:
            require(timing['at'] == executed['at'], 'Offline timing / execution order mismatch')
        at = instant_ns(timing['at'])
        row = dict(metadata(case), decision_index=index, actual_decision_index=index if executed else None,
            model_before=timing['at'], model_after_latency=timing['at'], model_before_ns=at - T0,
            model_after_latency_ns=at - T0, model_before_s=seconds(at - T0), model_after_latency_s=seconds(at - T0),
            host_nanos=timing['elapsedNanos'], host_seconds=seconds(timing['elapsedNanos']),
            charged_latency_nanos=0, physical_advance_nanos=0, decision_deadline_miss=timing['deadlineMiss'],
            disposition=timing['disposition'], revalidated=None, replan=False, executed=executed is not None,
            execution_source=executed.get('source') if executed else None,
            resumed_at=executed.get('resumedAt') if executed else None,
            selection_reason=executed.get('reason') if executed else None,
            nodes=executed.get('exploredNodes') if executed else None,
            search_nanos=executed.get('searchNanos') if executed else None,
            search_status=executed.get('searchStatus') if executed else None,
            search_stop=executed.get('searchStopReason') if executed else None)
        row.update(action_fields('selected', executed.get('executedAction') if executed else None))
        row.update(action_fields('executed', executed.get('executedAction') if executed else None))
        row.update(proof_fields(executed.get('continuation') if executed else None))
        rows.append(row)
    return rows


def add_portfolio(rows, portfolios, attempts, allow_unmatched=False):
    """Join raw bounded DFS attempts independently of selected proof status.

    A feasible INCOMPLETE incumbent is returned as a rule selection, so its
    selected search is null even though bounded DFS was performed. Timestamp and
    occurrence order identify the selection; a certified suffix never calls it.
    """
    eligible = [row for row in rows if row.get('guard_status') != 'ALLOWED'
                and row.get('execution_source') != 'SUFFIX_REUSE'
                and not (row.get('selection_reason') or '').startswith('First action of proven continuation')]
    def buckets():
        result = defaultdict(deque)
        for row in eligible:
            result[row['model_before']].append(row)
        return result
    def unmatched_allowed(at):
        return allow_unmatched and (not rows or instant_ns(at) >= instant_ns(rows[-1]['model_before']))
    search_buckets = buckets()
    unmatched_attempts = []
    for index, attempt in enumerate(attempts):
        matching = search_buckets[attempt['at']]
        if not matching:
            require(unmatched_allowed(attempt['at']), f'Unmatched nonterminal search attempt at {attempt["at"]}')
            unmatched_attempts.append(dict(index=index, at=attempt['at'], status=attempt['search']['status'],
                stop=attempt['search']['diagnostics']['stopReason'], reason='No completed exported decision interval'))
            continue
        row = matching.popleft()
        row.update(selected_search_status=row.get('search_status'), selected_search_stop=row.get('search_stop'),
                   selected_search_nodes=row.get('nodes'), search_attempt_index=index,
                   search_attempt_disposition=attempt['disposition'])
        row.update(search_fields(attempt['search']))
    portfolio_buckets = buckets()
    unmatched_portfolios = []
    for index, audit in enumerate(portfolios):
        matching = portfolio_buckets[audit['at']]
        if not matching:
            require(unmatched_allowed(audit['at']), f'Unmatched nonterminal portfolio at {audit["at"]}')
            unmatched_portfolios.append(dict(index=index, at=audit['at'], source=audit['source'],
                reason='No completed exported decision interval'))
            continue
        row = matching.popleft()
        row.update(portfolio_source=audit['source'], portfolio_retention=audit['retention'],
            seed_status=audit['seedStatus'], seed_nanos=audit['seedNanos'], suffix_nanos=audit['suffixNanos'],
            portfolio_search_nanos=audit['searchNanos'], portfolio_selection_nanos=audit['selectionNanos'])
    return dict(actual_search_attempts=len(attempts), portfolio_selections=len(portfolios),
                search_nodes_total=sum(a['search']['exploredNodes'] for a in attempts),
                search_nanos_total=sum(a['search']['elapsedNanos'] for a in attempts),
                search_stop_counts_json=compact(dict(Counter(a['search']['diagnostics']['stopReason'] for a in attempts))),
                unmapped_search_attempts=len(unmatched_attempts), unmapped_portfolios=len(unmatched_portfolios),
                unmapped_search_attempts_json=compact(unmatched_attempts), unmapped_portfolios_json=compact(unmatched_portfolios))


def read_case(case, base, scenario):
    directory = base / 'runs' / case['run_id']
    process = load(directory / 'process.json')
    row = dict(metadata(case), status='NOT_RUN', process_status=None, attempted=False,
               final_score_verified=False, metrics_scope='NONE', **{key: None for key in SCORES},
               U_seconds=None, payload_UL_bytes=None, payload_DL_bytes=None, lifecycle_CPU_seconds=None,
               controller_elapsed_seconds=None, decisions=None, stale_decisions=None, stop_reason=None)
    if not directory.exists():
        return row, [], []
    row.update(attempted=True)
    if process is None:
        row.update(status='IN_PROGRESS_OR_INTERRUPTED', metrics_scope='UNFINALIZED_PROCESS')
        return row, [], []
    row.update(process_status=process.get('processStatus', process.get('status')),
        process_exit_code=process.get('exitCode', process.get('returnCode')),
        process_elapsed_nanos=process.get('processElapsedNanos'),
        process_elapsed_seconds=seconds(process['processElapsedNanos']) if 'processElapsedNanos' in process else process.get('elapsedSeconds'))
    invocation = load(directory / 'invocation.json', {})
    if 'configHash' in invocation:
        require(invocation['configHash'] == case['config_hash'], 'Invocation config hash mismatch')
    result = load(directory / 'result.json', {})
    row['status'] = result.get('status', row['process_status'])
    finished = row['status'] == 'FINISHED'
    if finished:
        require(row['process_status'] == 'EXITED' and row['process_exit_code'] == 0,
                'FINISHED result from unsuccessful process')
    checkpoint = load(directory / 'checkpoint.json', {})
    events = load(directory / 'actual.events.json')
    if events is None:
        events = checkpoint.get('snapshot', {}).get('journal')
    contacts = []
    if events is not None:
        score, contacts = journal_scores(events, scenario)
        if finished:
            validate_finished(score, contacts, result)
            row.update(score, final_score_verified=True, metrics_scope='FINAL')
            row.update(U_seconds=score['U'], lifecycle_CPU_seconds=score['control_cpu'],
                payload_UL_bytes=sum(c['uplink_bytes'] for c in contacts),
                payload_DL_bytes=sum(c['downlink_bytes'] for c in contacts))
        else:
            row.update({'partial_' + k: v for k, v in score.items()}, metrics_scope='PREFIX_RECORDED')
            row.update(partial_U_seconds=score['U'], partial_lifecycle_CPU_seconds=score['control_cpu'],
                partial_payload_UL_bytes=sum(c['uplink_bytes'] for c in contacts),
                partial_payload_DL_bytes=sum(c['downlink_bytes'] for c in contacts))
        for contact in contacts:
            contact.update(metadata(case), run_status=row['status'], metrics_scope=row['metrics_scope'])
    else:
        require(not finished, 'FINISHED lacks event journal')
    summary = result.get('summary') or {}
    row.update(stopped_at=summary.get('stoppedAt', checkpoint.get('snapshot', {}).get('at')),
        controller_elapsed_nanos=summary.get('controllerWallNanos'),
        controller_elapsed_seconds=seconds(summary['controllerWallNanos']) if 'controllerWallNanos' in summary else None,
        stop_reason='NONE' if finished else row['status'],
        effective_decision_budget_nanos=result.get('effectiveDecisionBudgetNanos'))
    partial = summary.get('partialProgress') or {}
    accrued = partial.get('accruedCosts')
    if accrued:
        row.update(partial_accrued_control_cpu=accrued['groundCpu'] + accrued['spacecraftCpu'],
                   partial_accrued_management_bytes=accrued['uplinkBytes'] + accrued['downlinkBytes'])
    actual = load(directory / 'actual.decisions.json', [])
    if case['mode'] == 'measured':
        decisions = measured_rows(case, load(directory / 'latency-decisions.json', []), actual, scenario)
    else:
        decisions = offline_rows(case, actual, load(directory / 'decision-timings.json', []))
    row.update(add_portfolio(decisions, load(directory / 'practical-portfolio.json', []),
                             load(directory / 'practical-searches.json', []), allow_unmatched=not finished))
    row.update(decision_records=len(decisions), executed_decisions=len(actual),
        decisions=len(decisions), stale_decisions=sum(d['disposition'] in
            {'STALE_ACTION_WAIT', 'STALE_INTENT_REPLAN', 'WAIT_EVENT_CHANGED_REPLAN'} for d in decisions),
        decision_deadline_misses=sum(bool(d['decision_deadline_miss']) for d in decisions),
        charged_latency_nanos_total=sum(d['charged_latency_nanos'] for d in decisions),
        charged_physical_advance_nanos_total=sum(d['physical_advance_nanos'] for d in decisions),
        decision_host_nanos_total=sum(d['host_nanos'] for d in decisions),
        decision_host_nanos_median=statistics.median([d['host_nanos'] for d in decisions]) if decisions else None)
    return row, contacts, decisions


def score_order(row):
    return (-row['S'], -row['U'], row['control_cpu'], row['management_bytes'], row['new_keys'])


def pair_rows(runs):
    groups = defaultdict(dict)
    for run in runs:
        require(run['horizon'] not in groups[run['pair_id']], 'Duplicate horizon in pair')
        groups[run['pair_id']][run['horizon']] = run
    pairs = []
    for pair_id, members in groups.items():
        require(set(members) == {1, 2}, f'Unpaired registration: {pair_id}')
        one, two = members[1], members[2]
        complete = one['status'] == two['status'] == 'FINISHED'
        attempted = one['attempted'] or two['attempted']
        row = dict(pair_id=pair_id, phase=one['phase'], tie_rule=one['tie_rule'], repeat=one['repeat'],
            H1_run_id=one['run_id'], H2_run_id=two['run_id'], H1_status=one['status'], H2_status=two['status'],
            status='COMPLETE' if complete else 'INCOMPLETE' if attempted else 'NOT_RUN',
            S_outcome=None, score_outcome=None, delta_S=None, delta_U=None, relative_S_percent=None, relative_U_percent=None)
        if complete:
            ds, du = two['S'] - one['S'], two['U'] - one['U']
            row.update(delta_S=ds, delta_U=du,
                S_outcome='WIN' if ds > 0 else 'LOSS' if ds < 0 else 'TIE',
                score_outcome='WIN' if score_order(two) < score_order(one) else 'LOSS' if score_order(two) > score_order(one) else 'TIE',
                relative_S_percent=D(100) * ds / one['S'] if one['S'] else None,
                relative_U_percent=D(100) * du / one['U'] if one['U'] else None)
            for metric in SCORES:
                row['H1_' + metric], row['H2_' + metric] = one[metric], two[metric]
        pairs.append(row)
    return pairs


def summarize(runs, pairs, decisions):
    output = {'planned_runs': len(runs), 'run_statuses': dict(Counter(r['status'] for r in runs)),
              'attempted_runs': sum(bool(r['attempted']) for r in runs), 'rules': {}}
    for rule in ('CONTINUE_WAIT', 'CANONICAL'):
        selected = [p for p in pairs if p['phase'] == 'main' and p['tie_rule'] == rule]
        require(len(selected) == 20, f'{rule}: expected 20 registered main pairs')
        complete = [p for p in selected if p['status'] == 'COMPLETE']
        decision_rows = [d for d in decisions if d['phase'] == 'main' and d['tie_rule'] == rule]
        representatives = {}
        for outcome in ('WIN', 'TIE', 'LOSS'):
            group = [p for p in complete if p['S_outcome'] == outcome]
            if group:
                median = statistics.median(p['delta_U'] for p in group)
                representatives[outcome] = min(group, key=lambda p: (abs(p['delta_U'] - median), p['pair_id']))['pair_id']
        output['rules'][rule] = dict(planned_pairs=20, completed_pairs=len(complete),
            incomplete_pairs=sum(p['status'] == 'INCOMPLETE' for p in selected),
            not_run_pairs=sum(p['status'] == 'NOT_RUN' for p in selected),
            S_wins=sum(p['S_outcome'] == 'WIN' for p in complete),
            S_ties=sum(p['S_outcome'] == 'TIE' for p in complete),
            S_losses=sum(p['S_outcome'] == 'LOSS' for p in complete),
            ranking_denominator=len(complete), delta_U=stats(p['delta_U'] for p in complete),
            decision_host_nanos=stats(d['host_nanos'] for d in decision_rows),
            disposition_counts=dict(Counter(d['disposition'] for d in decision_rows)),
            deadline_misses=sum(bool(d['decision_deadline_miss']) for d in decision_rows),
            representative_pairs=representatives,
            by_horizon={str(h): {'decision_host_nanos': stats(d['host_nanos'] for d in decision_rows if d['horizon'] == h),
                'process_elapsed_seconds': stats(r['process_elapsed_seconds'] for r in runs
                    if r['phase'] == 'main' and r['tie_rule'] == rule and r['horizon'] == h and r.get('process_elapsed_seconds') is not None)} for h in (1, 2)})
    return output


def control_gate(runs, manifest, base):
    expected = {1: (2, D('38'), D('.12'), 160, 0), 2: (3, D('37'), D('.83'), 1240, 2)}
    controls = [r for r in runs if r['phase'] == 'control' and r['tie_rule'] == 'CONTINUE_WAIT']
    checks = []
    require(len(controls) == 2 and {r['horizon'] for r in controls} == {1, 2}, 'CW controls not uniquely registered')
    for row in controls:
        actual = tuple(row.get(k) for k in SCORES)
        checks.append(dict(run_id=row['run_id'], horizon=row['horizon'], status=row['status'],
            expected=list(expected[row['horizon']]), actual=list(actual),
            passed=row['status'] == 'FINISHED' and row['final_score_verified'] and actual == expected[row['horizon']]))
    gate = dict(status='PASS' if all(c['passed'] for c in checks) else 'FAIL',
        checkedUtc=datetime.now(timezone.utc).isoformat(), manifestSha256=sha(base / 'manifest.json'),
        analysisSha256=sha(__file__), checks=checks,
        scope='Explicit offline CONTINUE_WAIT control gate only; does not approve pilots or main series')
    save_json(base / 'control-gate.json', gate)
    return gate


def analyze(base=BASE, out=OUT, gate=False):
    manifest = load(base / 'manifest.json')
    require(manifest is not None, f'Missing manifest: {base / "manifest.json"}')
    cases = manifest['cases']
    require(len({c['run_id'] for c in cases}) == len(cases), 'Duplicate registered run ID')
    model_paths = {c['flags']['model'] for c in cases}
    require(len(model_paths) == 1, 'Cases use different physical models')
    scenario = load(ROOT / next(iter(model_paths)))['scenario']
    runs, contacts, decisions = [], [], []
    for case in cases:
        try:
            run, per_contact, per_decision = read_case(case, base, scenario)
        except Exception as error:
            raise AssertionError(f'{case["run_id"]}: {error}') from error
        runs.append(run)
        contacts.extend(per_contact)
        decisions.extend(per_decision)
    pairs = pair_rows(runs)
    summary = summarize(runs, pairs, decisions)
    summary.update(manifest_sha256=sha(base / 'manifest.json'), analysis_sha256=sha(__file__),
        input_model_sha256=sha(ROOT / next(iter(model_paths))), generated_utc=datetime.now(timezone.utc).isoformat(),
        final_scores='Only FINISHED; reconstructed from journal and checked against simulation and summary',
        time_precision='Integer nanoseconds; UTC fractions parsed without datetime microsecond truncation',
        ranking='Main-series S outcomes; only pairs with two FINISHED executions; positive deltas mean Multi minus Next')
    out.mkdir(parents=True, exist_ok=True)
    for name, rows, initial in [('runs', runs, META), ('contacts', contacts, META), ('decisions', decisions, META), ('pairs', pairs, ())]:
        save_csv(out / (name + '.csv'), rows, initial)
    save_json(out / 'summary.json', summary)
    if gate:
        outcome = control_gate(runs, manifest, base)
        require(outcome['status'] == 'PASS', 'Control gate failed; see control-gate.json')
    print(f'ANALYSIS PASS: {len(runs)} planned runs, {len(decisions)} decisions, {len(contacts)} contact rows; {out}')
    return runs, contacts, decisions, pairs, summary


def self_test():
    require(instant_ns('2026-01-01T00:00:00.123456789Z') - T0 == 123456789, 'Nanosecond parser test')
    require(duration_ns(D('0.000000001')) == 1, 'Nanosecond duration test')
    root = ROOT / 'target/aitu-revision-v2/controllers'
    for horizon, expected in [(1, (2, D(38), D('.12'), 160, 0)), (2, (3, D(37), D('.83'), 1240, 2))]:
        directory = root / f'controller-CONTINUE_WAIT-N150000-H{horizon}'
        result = load(directory / 'result.json')
        scenario = load(directory / 'initial-checkpoint.json')['snapshot']['scenario']
        score, contacts = journal_scores(load(directory / 'actual.events.json'), scenario)
        validate_finished(score, contacts, result)
        require(tuple(score[k] for k in SCORES) == expected, 'Archived control score test')
        rows = offline_rows({}, load(directory / 'actual.decisions.json'), load(directory / 'decision-timings.json'))
        add_portfolio(rows, load(directory / 'practical-portfolio.json'), load(directory / 'practical-searches.json'))
    action = {'kind': 'WAIT', 'at': '2026-01-01T00:00:00Z', 'until': '2026-01-01T00:00:20Z'}
    delay = dict(origin={'at': action['at']}, selection={'action': action, 'search': None, 'reason': 'test'},
        hostNanos=123456789, modeledLatency=D('.123456789'), availableAt='2026-01-01T00:00:00.123456789Z',
        deadlineMiss=False, disposition='REVALIDATED_FIRST_ACTION')
    execution = dict(at=delay['availableAt'], reason=delay['disposition'], executedAction=action, source='RULE', resumedAt=action['until'])
    rows = measured_rows({}, [delay], [execution], {'end': '2026-01-01T00:01:25Z'})
    require(rows[0]['physical_advance_nanos'] == 123456789, 'Measured charging test')
    bad = dict(execution, at='2026-01-01T00:00:00.123456788Z')
    try:
        measured_rows({}, [delay], [bad], {'end': '2026-01-01T00:01:25Z'})
    except AssertionError:
        pass
    else:
        raise AssertionError('Unmatched execution was accepted')
    replan = dict(delay, disposition='STALE_INTENT_REPLAN')
    require(not measured_rows({}, [replan], [], {'end': '2026-01-01T00:01:25Z'})[0]['executed'], 'Replan mapping test')
    print('SELF-TEST PASS: nanoseconds, archived H1/H2 journals, offline mappings, measured mapping/replan/rejection')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=BASE)
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--control-gate', action='store_true', help='Explicitly check and write CW control gate')
    parser.add_argument('--self-test', action='store_true', help='Run bounded stdlib/schema checks; no simulation')
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        analyze(args.base, args.out, args.control_gate)
