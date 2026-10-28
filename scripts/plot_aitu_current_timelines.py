"""Plot actual X01 timelines and the historical OFFLINE budget control.

Reads frozen journals/tables only; never runs the Java controller or changes inputs.
Run from the reproduction package root with matplotlib and numpy installed.
"""
import hashlib
import json
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/figure-python'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from analyze_measured_x01 import ns

BASE = ROOT / 'target/measured-delay-x01-20261028'
OUT = ROOT / 'docs/aitu-revised-final/figures'
EPOCH = ns('2026-01-01T00:00:00Z')
inputs = {}


def read(path):
    data = path.read_bytes()
    inputs[path.relative_to(ROOT).as_posix()] = hashlib.sha256(data).hexdigest()
    return json.loads(data)


def sec(value):
    return (ns(value) - EPOCH) / 1e9


def save(fig, name):
    OUT.mkdir(parents=True, exist_ok=True)
    for ext in ['png', 'svg']:
        fig.savefig(OUT / f'{name}.{ext}', dpi=600, facecolor='white', bbox_inches='tight', pad_inches=.06)
    plt.close(fig)


def timelines():
    manifest = read(BASE / 'manifest.json')
    pairs = {}
    for case in manifest['cases']:
        if case['phase'] == 'main':
            result = read(BASE / 'runs' / case['run_id'] / 'result.json')
            assert result['status'] == 'FINISHED'
            pairs.setdefault(case['pair_id'], {})[case['horizon']] = (case, result)
    groups = {}
    for pair, policies in pairs.items():
        assert set(policies) == {1, 2}
        a, b = [policies[h][1]['simulationMetrics'] for h in [1, 2]]
        ds = b['servedContacts'] - a['servedContacts']
        du = b['usefulSecureTime'] - a['usefulSecureTime']
        outcome = 'loss' if ds < 0 else 'tie' if ds == 0 else 'win'
        groups.setdefault((policies[1][0]['tie_rule'], outcome), []).append((pair, du, policies))
    chosen = {}
    for key, values in groups.items():
        median = statistics.median(v[1] for v in values)
        chosen[key] = (min(values, key=lambda v: (abs(v[1]-median), v[0])), median)
    order = [('CONTINUE_WAIT', 'loss'), ('CANONICAL', 'loss'), ('CANONICAL', 'tie')]
    assert [chosen[k][0][0] for k in order] == [
        'main-CONTINUE_WAIT-01', 'main-CANONICAL-13', 'main-CANONICAL-01']
    plt.rcParams.update({'font.family': 'Times New Roman', 'font.size': 9,
                         'axes.titlesize': 10, 'svg.fonttype': 'none'})
    fig, axes = plt.subplots(3, 1, figsize=(6.42, 6.6), sharex=True)
    fig.subplots_adjust(left=.19, right=.975, top=.90, bottom=.12, hspace=.52)
    evidence = []
    for index, (ax, key) in enumerate(zip(axes, order)):
        (pair, du, policies), median = chosen[key]
        for start, end, name in [(0, 20, 'C1 A [0,20)'), (30, 50, 'C2 B [30,50)'), (80, 85, 'C3 A')]:
            ax.axvspan(start, end, color='#edf1f5', zorder=0)
            ax.axvline(end, color='#9aa4af', lw=.55, ls=':')
            ax.text((start+end)/2, 5.85, name, ha='center', va='bottom', fontsize=8)
        ax.text(82.5, 5.47, '[80,85)', ha='center', va='bottom', fontsize=7)
        run_evidence = []
        scores = []
        for h, base in [(1, 3), (2, 0)]:
            case, result = policies[h]
            path = BASE / 'runs' / case['run_id']
            delays = read(path / 'latency-decisions.json')
            events = read(path / 'actual.events.json')
            intervals = [(sec(d['origin']['at']), sec(d['availableAt'])) for d in delays]
            assert min(a for a, b in intervals) == -2
            for start, end in intervals:
                if end > start:
                    ax.broken_barh([(start, end-start)], (base+1.65, .42),
                                   facecolors='#c98a26', edgecolors='none', zorder=2)
            started = {}
            operations = []
            for event in events:
                if event['type'] == 'STEP_STARTED':
                    started[event['operationId']] = event
                elif event['type'] == 'STEP_COMPLETED':
                    first = started.pop(event['operationId'])
                    start, end = sec(first['at']), sec(event['at'])
                    action = event['action']
                    operations.append({'action': action, 'start': start, 'end': end})
                    if end > start:
                        ax.broken_barh([(start, end-start)], (base+.85, .42),
                                       facecolors='#49698e', edgecolors='none', zorder=3)
                    elif action == 'STOP':
                        ax.plot(start, base+1.06, marker='v', color='#555d65', ms=3.8, zorder=4)
                    elif action == 'EXPIRE_CONTEXT':
                        ax.plot(start, base+1.06, marker='x', color='#8e3f51', ms=4.2, zorder=4)
            assert not started
            assert sorted(x['action'] for x in operations) == ['EXPIRE_CONTEXT', 'OPEN', 'OPEN', 'STOP', 'STOP']
            delivered = [e for e in events if e['type'] == 'PAYLOAD_TRANSFERRED']
            for event in delivered:
                end, duration = sec(event['at']), float(event['usefulTime'])
                assert duration == .25
                ax.broken_barh([(end-duration, duration)], (base+.05, .42),
                               facecolors='#287d69', edgecolors='white', linewidth=.12, zorder=3)
            metrics = result['simulationMetrics']
            assert sum(float(e['usefulTime']) for e in delivered) == metrics['usefulSecureTime']
            label = 'Next' if h == 1 else 'Multi'
            scores.append(f"{label}: S={metrics['servedContacts']}, U={metrics['usefulSecureTime']:g} s")
            run_evidence.append({'runId': case['run_id'], 'S': metrics['servedContacts'],
                                 'U': metrics['usefulSecureTime'], 'operations': operations,
                                 'chargedIntervals': len(intervals), 'framesDelivered': len(delivered),
                                 'firstChargedStart': min(a for a, b in intervals)})
        label = 'service loss' if key[1] == 'loss' else 'service tie'
        ax.set_title(f"({chr(97+index)}) {key[0]} · pair {pair[-2:]} · {label}\n" + '   |   '.join(scores),
                     loc='left', fontsize=9, pad=17, linespacing=1.4)
        ax.set_yticks([4.86, 4.06, 3.26, 1.86, 1.06, .26],
                     ['Next decision', 'operation', 'frames', 'Multi decision', 'operation', 'frames'])
        ax.set_ylim(-.18, 6.24)
        ax.set_xlim(-2, 85.5)
        ax.axhline(2.65, color='#c3c8ce', lw=.45)
        ax.tick_params(axis='y', length=0, pad=5, labelsize=8)
        ax.tick_params(axis='x', labelsize=8)
        for name in ['top', 'right', 'left']:
            ax.spines[name].set_visible(False)
        ax.grid(axis='x', alpha=.13, zorder=0)
        evidence.append({'pairId': pair, 'group': key, 'groupMedianDeltaU': median,
                         'deltaU': du, 'runs': run_evidence})
    axes[-1].set_xticks([-2, 10, 20, 30, 40, 50, 60, 70, 80, 85])
    axes[-1].set_xlabel('Model time from C1 start (s)', fontsize=9)
    handles = [Patch(facecolor='#c98a26', label='Charged decision'),
               Patch(facecolor='#49698e', label='Executed OPEN'),
               Patch(facecolor='#287d69', label='Delivered frame'),
               Line2D([], [], color='#555d65', marker='v', linestyle='None', markersize=4, label='STOP'),
               Line2D([], [], color='#8e3f51', marker='x', linestyle='None', markersize=4, label='Expiry')]
    fig.legend(handles=handles, loc='lower center', ncol=3, frameon=False,
               fontsize=8, columnspacing=1.5, bbox_to_anchor=(.52, .005))
    save(fig, 'fig3_measured_execution')
    return evidence


def offline_budget():
    rows = read(ROOT / 'target/aitu-revision/stage3/tables.json')
    levels = [0, 1000, 5000, 20000, 100000]
    fig, axes = plt.subplots(2, 2, figsize=(6.42, 4.65), sharex='col')
    for col, label in enumerate(['X5', 'long']):
        for h, color in [(1, '#24618e'), (2, '#c45920')]:
            seq = [next(r for r in rows if r['caseLabel'] == label and r['nodes'] == n
                        and r['horizon'] == h and not r['measured']) for n in levels]
            assert all(r['status'] == 'FINISHED' for r in seq)
            for row, field in [(0, 'usefulSecureTime'), (1, 'controllerSeconds')]:
                axes[row, col].plot(range(5), [float(r[field]) for r in seq],
                    'o-' if h == 1 else 's-', color=color, lw=1.2,
                    markersize=3.5 if h == 1 else 6, markerfacecolor=color if h == 1 else 'none')
        axes[0, col].set_title('Autonomous X' if label == 'X5' else 'Long ISS control', fontsize=10)
        axes[1, col].set_xticks(range(5), ['No DFS', '1k', '5k', '20k', '100k'], rotation=20)
        axes[1, col].set_xlabel('Per-search node allowance')
        for ax in axes[:, col]:
            ax.grid(axis='y', alpha=.2)
            ax.spines[['top', 'right']].set_visible(False)
    axes[0, 0].set_ylabel('Useful payload time (s)')
    axes[1, 0].set_ylabel('Controller elapsed (s)')
    fig.suptitle('Historical offline budget control: one-second search cap', fontsize=10)
    fig.legend(handles=[Line2D([], [], color='#24618e', label='Next'),
                        Line2D([], [], color='#c45920', label='Multi')],
               loc='lower center', ncol=2, frameon=False)
    fig.tight_layout(rect=(0, .05, 1, .95))
    save(fig, 'figS1_offline_budget')


if __name__ == '__main__':
    evidence = timelines()
    offline_budget()
    record = {'selection': 'Within each rule and S-outcome group, nearest to median DeltaU; ties by pair ID.',
              'representatives': evidence, 'inputs': inputs,
              'scriptSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'pngDpi': 600, 'newExperiments': 0, 'frameBytes': 100,
              'serviceThresholdBytesEachDirection': 400,
              'geometry': [[0, 20, 'A'], [30, 50, 'B'], [80, 85, 'A']],
              'plotBoundary': 'Only charged delays, completed STEP operations and delivered frames from actual journals; separate lanes; no interval width inflation.'}
    (OUT / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print('PASS: three verified pairs; actual execution figure and offline-only supplement figure written.')
