"""Redesigned PDF/SVG/PNG versions of the six final-campaign figures.

Reads the same audited rows as plot_final_research.py and writes to a separate
folder, leaving docs/paper-final/figures untouched.
Incomplete executions are drawn as hatched/explicit "timeout" marks, never as zero.
"""
import sys
from pathlib import Path
from run_final_research import ROOT, OUT, load, save, sha
if (ROOT/'.tools/figure-python').exists() and sys.version_info[:2] == (3, 12):
    sys.path.insert(0, str(ROOT/'.tools/figure-python'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LinearSegmentedColormap, Normalize
from matplotlib.lines import Line2D
from matplotlib.patches import Patch, Rectangle
from matplotlib.ticker import FixedLocator, NullFormatter, NullLocator

DEST = ROOT/'docs/paper-final/figures-improved'
POLICIES = ['FixedPeriod', 'PerContact', 'Next', 'Multi', 'Seed H1', 'Seed H2']
COLORS = {'Next': '#2a78d6', 'Multi': '#eb6834', 'PerContact': '#1baf7a',
          'FixedPeriod': '#eda100', 'Seed H1': '#e87ba4', 'Seed H2': '#008300'}
MARKERS = {'FixedPeriod': 's', 'PerContact': 'D', 'Next': 'o', 'Multi': '^', 'Seed H1': 'v', 'Seed H2': 'P'}
INK, INK2, MUTED, GRID, AXIS = '#0b0b0b', '#52514e', '#898781', '#e1e0d9', '#c3c2b7'
DONE_FILL, MISSING_FILL = '#dbe9fb', '#f4f3f0'
MILESTONES = [('spacecraft_notice', 'SC notice'), ('independent_pair', 'Independent keys'),
              ('independent_sa', 'Open SA'), ('independent_frame', 'Delivered frame')]
plt.rcParams.update({'font.size': 8, 'axes.titlesize': 8.5, 'axes.titleweight': 'bold', 'legend.fontsize': 7.5,
                     'axes.edgecolor': AXIS, 'axes.labelcolor': INK2, 'xtick.color': INK2, 'ytick.color': INK2,
                     'xtick.labelcolor': INK, 'ytick.labelcolor': INK, 'text.color': INK,
                     'axes.spines.top': False, 'axes.spines.right': False, 'legend.frameon': False,
                     'pdf.fonttype': 42, 'svg.fonttype': 'none', 'savefig.dpi': 300})


def num(value):
    return None if value is None else float(value)


def render():
    rows = load(OUT/'tables.json')
    protocol = load(OUT/'protocol.json')
    validation = load(ROOT/'docs/final-research/validation.json')
    assert validation['status'] == 'PASS' and len(rows) == 108
    assert validation['tableSha256'] == sha(OUT/'tables.json')
    DEST.mkdir(parents=True, exist_ok=True)
    watchdog = protocol['budgets']['processSeconds']
    used = {}

    def done(r): return r is not None and r['status'] == 'FINISHED'
    def find(**fields): return next((r for r in rows if all(r.get(k) == v for k, v in fields.items())), None)

    def finish(fig, name, selected):
        for ext in ['pdf', 'svg', 'png']:
            fig.savefig(DEST/f'{name}.{ext}', bbox_inches='tight', pad_inches=.04)
        plt.close(fig)
        used[name] = [dict(id=r['id'], status=r['status']) for r in selected if r]

    core = [r for r in rows if r['block'] == 'core']
    missions = list(dict.fromkeys(r['scenario'] for r in core))
    loads = list(dict.fromkeys(r['load'] for r in core))
    columns = [(m, l) for m in missions for l in loads]

    def column_x(c):  # leave a gap between mission groups
        return c + .35*(c//len(loads))

    def matrix(ax, cell, title=None, spacing=.32, fontsize=7):
        """Draw a policy x (mission, load) grid; cell(r) returns (fill, lines) or None for missing."""
        selected = []
        for c, (mission, workload) in enumerate(columns):
            x = column_x(c)
            for i, policy in enumerate(POLICIES):
                r = find(block='core', scenario=mission, load=workload, policy=policy)
                selected.append(r)
                if done(r):
                    fill, lines = cell(r, mission, workload)
                    ax.add_patch(Rectangle((x+.03, i+.04), .94, .92, facecolor=fill, edgecolor='none'))
                else:
                    ax.add_patch(Rectangle((x+.03, i+.04), .94, .92, facecolor=MISSING_FILL,
                                           edgecolor=AXIS, hatch='/////', lw=0))
                    lines = [('timeout', INK2, 'normal')]
                for k, (text, color, weight) in enumerate(lines):
                    dy = (k-(len(lines)-1)/2)*spacing
                    box = None if done(r) else dict(facecolor=MISSING_FILL, edgecolor='none', pad=.8)
                    ax.text(x+.5, i+.5+dy, text, ha='center', va='center', fontsize=fontsize, color=color,
                            fontweight=weight, bbox=box)
        ax.set_xlim(-.05, column_x(len(columns)-1)+1.05)
        ax.set_ylim(len(POLICIES)+.02, -.02)
        ax.set_yticks([i+.5 for i in range(len(POLICIES))], POLICIES)
        ax.set_xticks([column_x(c)+.5 for c in range(len(columns))], [l for _, l in columns])
        ax.tick_params(length=0, pad=3)
        ax.tick_params(axis='x', labelcolor=INK2, labelsize=7)
        for spine in ax.spines.values():
            spine.set_visible(False)
        for g, mission in enumerate(missions):
            x0, x1 = column_x(g*len(loads)), column_x((g+1)*len(loads)-1)+1
            ax.text((x0+x1)/2, -.25, mission, ha='center', va='bottom', fontsize=8, fontweight='bold')
            ax.plot([x0+.05, x1-.05], [-.12, -.12], color=AXIS, lw=.8, clip_on=False)
        if title:
            ax.set_title(title, loc='left', pad=22)
        return selected

    timeout_patch = Patch(facecolor=MISSING_FILL, edgecolor=AXIS, hatch='/////', lw=.5,
                          label=f'timeout: {watchdog:.0f} s watchdog reached, no final result (not zero)')

    # 1. Service: every completed cell serves all contacts, so status is the message.
    fig, ax = plt.subplots(figsize=(7.2, 2.75))
    selected = matrix(ax, lambda r, m, l: (DONE_FILL, [
        (f"{int(num(r['servedContacts']))}/{int(num(r['contacts']))}", INK, 'bold'),
        (f"{num(r['usefulSecureTime']):,.0f} s", INK2, 'normal')]))
    fig.legend(handles=[Patch(facecolor=DONE_FILL, label='completed: served / selected contacts, useful payload time'),
                        timeout_patch], loc='lower center', ncol=2, bbox_to_anchor=(.5, -.07), handlelength=1.6)
    finish(fig, 'service_matrix', selected)

    # 2. Costs: value per cell, shaded by overhead relative to the cheapest completed policy.
    metrics = [('controlCpu', 'Modeled lifecycle-operation CPU (s)', lambda v: f'{v:.2f}'),
               ('managementBytes', 'Management bytes', lambda v: f'{v:,.0f}'),
               ('newKeys', 'New directional keys', lambda v: f'{v:.0f}')]
    cmap = LinearSegmentedColormap.from_list('overhead', ['#fcfcfb', '#f7c6ae', '#eb6834'])
    norm = Normalize(0, 40)
    fig, axes = plt.subplots(len(metrics), 1, figsize=(7.2, 8.6), gridspec_kw={'hspace': .55})
    fig.subplots_adjust(top=.9)
    selected = []
    for ax, (metric, title, fmt) in zip(axes, metrics):
        floor = {}
        for mission, workload in columns:
            values = [num(r[metric]) for r in core if r['scenario'] == mission and r['load'] == workload and done(r)]
            floor[mission, workload] = min(values)

        def cost_cell(r, mission, workload, metric=metric, fmt=fmt, floor=floor):
            value = num(r[metric])
            overhead = 100*(value/floor[mission, workload]-1) if floor[mission, workload] else 0
            lines = [(fmt(value), INK, 'bold')]
            if overhead > .05:
                lines.append((f'+{overhead:.0f}%', INK2, 'normal'))
            return cmap(norm(overhead)), lines
        selected += matrix(ax, cost_cell, title, spacing=.36, fontsize=6.5)
    sm = plt.cm.ScalarMappable(norm=norm, cmap=cmap)
    bar = fig.colorbar(sm, ax=axes, orientation='horizontal', fraction=.025, pad=.06, aspect=45, shrink=.6)
    bar.set_label('Overhead relative to the cheapest completed policy in the same column (%)', color=INK2)
    bar.outline.set_visible(False)
    bar.ax.tick_params(length=2, labelsize=7)
    fig.legend(handles=[timeout_patch], loc='upper center', bbox_to_anchor=(.5, .985), handlelength=1.6)
    finish(fig, 'cost_matrix', selected)

    # 3. Runtime: growth with workload, shared log scale, timeouts pinned at the watchdog.
    fig, axes = plt.subplots(1, len(missions), figsize=(7.2, 2.9), sharey=True)
    selected = []
    for ax, mission in zip(axes, missions):
        ax.axhline(watchdog, color=INK2, lw=.8, zorder=1)
        ax.text(-.25, watchdog*1.08, f'{watchdog:.0f} s process watchdog', fontsize=6.5, color=INK2, va='bottom')
        for i, policy in enumerate(POLICIES):
            series = [find(block='core', scenario=mission, load=w, policy=policy) for w in loads]
            selected += series
            jitter = (i-2.5)*.06
            xs = [k+jitter for k in range(len(loads))]
            ys = [num(r['processSeconds']) if r else None for r in series]
            finished = [(x, y) for x, y, r in zip(xs, ys, series) if done(r)]
            if finished:
                ax.plot(*zip(*finished), color=COLORS[policy], lw=1.4, zorder=2)
                ax.scatter(*zip(*finished), marker=MARKERS[policy], color=COLORS[policy], s=26,
                           edgecolors='white', linewidths=.8, zorder=3)
            for k, (x, y, r) in enumerate(zip(xs, ys, series)):
                if r and not done(r):
                    if k and done(series[k-1]):
                        ax.plot([xs[k-1], x], [ys[k-1], watchdog], color=COLORS[policy], lw=1.1, ls=(0, (1.5, 1.5)), zorder=2)
                    ax.scatter(x, watchdog, marker='X', color=COLORS[policy], s=48, edgecolors='white', linewidths=.8, zorder=4)
        ax.set_yscale('log')
        ax.set_ylim(5, 1000)
        ax.yaxis.set_major_locator(FixedLocator([5, 10, 20, 50, 100, 200, 600]))
        ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f'{v:g}'))
        ax.yaxis.set_minor_locator(NullLocator())
        ax.yaxis.set_minor_formatter(NullFormatter())
        ax.grid(axis='y', color=GRID, lw=.6)
        ax.set_axisbelow(True)
        ax.set_xticks(range(len(loads)), loads)
        ax.set_xlim(-.35, len(loads)-.65)
        ax.set_xlabel('Workload')
        ax.set_title(mission)
        ax.tick_params(axis='y', length=0)
    axes[0].set_ylabel('Process elapsed time (s, log scale)')
    handles = [Line2D([0], [0], color=COLORS[p], marker=MARKERS[p], lw=1.4, ms=5, mec='white', label=p) for p in POLICIES]
    handles.append(Line2D([0], [0], color=INK2, marker='X', ls=(0, (1.5, 1.5)), lw=1.1, ms=6, mec='white',
                          label='timeout: no final result'))
    fig.legend(handles=handles, loc='upper center', ncol=7, bbox_to_anchor=(.5, 1.06), columnspacing=1.1, handlelength=2)
    fig.tight_layout(w_pad=1.2)
    finish(fig, 'runtime_completion', selected)

    # 4. Measured ISS: the question is what changes vs offline, so plot the difference.
    measured = [r for r in rows if r['block'] == 'measured']
    mission = missions[0]
    entries = []
    for workload in loads:
        for policy in POLICIES:
            m = next((r for r in measured if r['load'] == workload and r['policy'] == policy), None)
            if m is None:
                continue
            entries.append((workload, policy, find(block='core', scenario=mission, load=workload, policy=policy), m))
    fig = plt.figure(figsize=(7.2, 4.3))
    grid = fig.add_gridspec(1, 2, width_ratios=[1.55, 1], wspace=.02)
    ax, table = fig.add_subplot(grid[0]), fig.add_subplot(grid[1])
    ypos, y, selected = [], 0, []
    for g, workload in enumerate(loads):
        group = [e for e in entries if e[0] == workload]
        ax.text(-133, y-.75, f'{workload} workload', fontsize=8, fontweight='bold', va='center')
        for _, policy, offline, live in group:
            selected += [offline, live]
            ypos.append((y, policy))
            if done(offline) and done(live):
                delta = num(live['usefulSecureTime'])-num(offline['usefulSecureTime'])
                pct = 100*delta/num(offline['usefulSecureTime'])
                if abs(delta) > 1e-9:
                    ax.barh(y, delta, height=.62, color=COLORS[policy], zorder=2)
                    label = f'{delta:+.0f} s ({pct:+.1f}%)'.replace('-', '\u2212')
                    if delta < -60:
                        ax.text(delta+3, y, label, ha='left', va='center', fontsize=7, color='white', fontweight='bold', zorder=3)
                    else:
                        ax.text(delta-2, y, label, ha='right', va='center', fontsize=7)
                else:
                    ax.scatter(0, y, marker=MARKERS[policy], color=COLORS[policy], s=26, edgecolors='white', linewidths=.8, zorder=3)
                    ax.text(3, y, 'no change', va='center', fontsize=7, color=INK2)
                served = f"{int(num(offline['servedContacts']))} → {int(num(live['servedContacts']))}"
                useful = f"{num(offline['usefulSecureTime']):,.0f} → {num(live['usefulSecureTime']):,.0f}"
            else:
                ax.add_patch(Rectangle((-135, y-.31), 135, .62, facecolor=MISSING_FILL, edgecolor=AXIS, hatch='/////', lw=0, zorder=1))
                state = 'timeout in both modes' if not done(offline) and not done(live) else \
                    'timeout offline' if not done(offline) else 'timeout measured'
                ax.text(-4, y, state, va='center', ha='right', fontsize=7, color=INK2, zorder=3,
                        bbox=dict(facecolor=MISSING_FILL, edgecolor='none', pad=.8))
                served = useful = '—'
            table.text(.08, y, served, va='center', ha='left', fontsize=7)
            table.text(.42, y, useful, va='center', ha='left', fontsize=7)
            y += 1
        y += 1.1
    ax.axvline(0, color=INK2, lw=.8, zorder=2)
    ax.set_yticks([p for p, _ in ypos], [policy for _, policy in ypos])
    ax.set_ylim(y-.9, -1.6)
    ax.set_xlim(-135, 40)
    ax.grid(axis='x', color=GRID, lw=.6)
    ax.set_axisbelow(True)
    ax.tick_params(axis='y', length=0)
    ax.spines['left'].set_visible(False)
    ax.set_xlabel('Measured − offline useful payload time (s)')
    ax.set_title(f'{mission}: effect of charging measured decision delay', loc='left')
    table.set_ylim(ax.get_ylim())
    table.set_xlim(0, 1)
    table.axis('off')
    table.text(.08, -1.6, 'Served\ncontacts', va='bottom', fontsize=7, color=INK2)
    table.text(.42, -1.6, 'Useful payload\ntime (s)', va='bottom', fontsize=7, color=INK2)
    table.text(.08, y-.6, 'columns: offline → measured', fontsize=6.5, color=MUTED)
    finish(fig, 'measured_iss', selected)

    # 5. Exposure: separate panels so the small post-disclosure bytes are not lost under a stack.
    security = [r for r in rows if r['block'] == 'security']
    scenarios = list(dict.fromkeys(r['scenario'] for r in security))
    panels = [('Decryptable bytes recorded\nbefore disclosure (B)', lambda r: num(r['decryptableBeforeBytes'])),
              ('Decryptable bytes emitted\nafter disclosure (B)', lambda r: num(r['decryptableAfterBytes'])),
              ('Decryptable share of all\nemitted bytes (%)',
               lambda r: 100*(num(r['decryptableBeforeBytes'])+num(r['decryptableAfterBytes']))/num(r['emittedBytes']))]
    fig, axes = plt.subplots(1, 3, figsize=(7.2, 3.3), sharey=True, gridspec_kw={'wspace': .12})
    order, y, selected = [], 0, []
    for scenario in scenarios:
        for policy in ['Next', 'Multi']:
            order.append((y, scenario, policy, find(block='security', scenario=scenario, policy=policy)))
            y += 1
        y += .6
    for ax, (title, value) in zip(axes, panels):
        finite = [value(r) for *_, r in order if done(r)]
        top = max(finite) if finite else 1
        for yy, scenario, policy, r in order:
            if not done(r):
                ax.text(0, yy, ' incomplete', va='center', fontsize=7, color=INK2)
                continue
            v = value(r)
            ax.barh(yy, v, height=.72, color=COLORS[policy], zorder=2)
            label = f'{v:.1f}%' if '%' in title else f'{v:,.0f}'
            ax.text(v+top*.02, yy, label, va='center', fontsize=7)
        ax.set_xlim(0, top*1.35)
        ax.set_title(title, loc='left', fontsize=8)
        ax.grid(axis='x', color=GRID, lw=.6)
        ax.set_axisbelow(True)
        ax.tick_params(axis='y', length=0)
        ax.spines['left'].set_visible(False)
        ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(
            lambda v, _: f'{v/1000:g}k' if v >= 1000 else f'{v:g}'))
    selected = [r for *_, r in order]
    centers = {s: sum(yy for yy, sc, *_ in order if sc == s)/2 for s in scenarios}
    axes[0].set_yticks(list(centers.values()), list(centers.keys()))
    axes[0].set_ylim(y-.4, -.8)
    fig.legend(handles=[Patch(color=COLORS[p], label=p) for p in ['Next', 'Multi']],
               loc='upper center', ncol=2, bbox_to_anchor=(.5, 1.06))
    finish(fig, 'detected_exposure', selected)

    # 6. Recovery: one lane per milestone so the ordered stages and censored ones read directly.
    recovery_scenarios = []
    for scenario in scenarios:
        case = next(r for r in security if r['scenario'] == scenario)
        model = load(ROOT/case['model'])['scenario']
        if any(i.get('detectionAt') is not None for i in model.get('disclosures', [])):
            recovery_scenarios.append(scenario)
    times = [num(r.get(m+'Seconds')) for r in security for m, _ in MILESTONES if done(r) and r.get(m+'Seconds') is not None]
    xmax = max(times)*1.18
    fig, axes = plt.subplots(len(recovery_scenarios), 1, figsize=(7.2, 1.25*len(recovery_scenarios)+.5), sharex=True)
    selected = []
    for ax, scenario in zip(axes, recovery_scenarios):
        pair = {p: find(block='security', scenario=scenario, policy=p) for p in ['Next', 'Multi']}
        selected += list(pair.values())
        for lane, (metric, label) in enumerate(MILESTONES):
            ax.axhline(lane, color=GRID, lw=.6, zorder=0)
            values = {p: (r.get(metric+'Status'), num(r.get(metric+'Seconds'))) if done(r) else ('incomplete', None)
                      for p, r in pair.items()}
            same = len({v for v in values.values()}) == 1
            for p, offset in [('Next', -.17), ('Multi', .17)]:
                status, value = values[p]
                yy = lane+offset
                if value is not None:
                    ax.scatter(value, yy, marker=MARKERS[p], color=COLORS[p], s=30, edgecolors='white', linewidths=.8, zorder=3)
                    if not same or p == 'Next':
                        seconds = f'{value:.2f}'.rstrip('0').rstrip('.')
                        ax.text(value+xmax*.015, lane if same else yy, f'{seconds} s', va='center', fontsize=6.5, color=INK2)
                elif not same or p == 'Next':
                    yy = lane if same else yy
                    text = {'censored': 'censored: not reached before run end', 'not-applicable': 'n/a (no target)'}.get(status, status)
                    ax.annotate('', xy=(xmax, yy), xytext=(xmax*.62, yy),
                                arrowprops=dict(arrowstyle='->', color=MUTED, lw=.9, ls=(0, (2, 1.5))))
                    ax.text(xmax*.62, yy-.18, text, fontsize=6.5, color=INK2, va='bottom')
        ax.set_yticks(range(len(MILESTONES)), [l for _, l in MILESTONES])
        ax.set_ylim(len(MILESTONES)-.45, -.55)
        ax.set_xlim(0, xmax)
        ax.tick_params(axis='y', length=0)
        ax.spines['left'].set_visible(False)
        ax.set_title(scenario, loc='left')
        ax.axvline(0, color=INK2, lw=.8)
        ax.xaxis.set_major_locator(matplotlib.ticker.MultipleLocator(2))
    axes[-1].set_xlabel('Seconds after ground-station (GS) notice')
    fig.legend(handles=[Line2D([0], [0], marker=MARKERS[p], color=COLORS[p], ls='', ms=5.5, mec='white', label=p)
                        for p in ['Next', 'Multi']],
               loc='upper center', ncol=2, bbox_to_anchor=(.5, 1.03))
    fig.tight_layout(h_pad=.6)
    finish(fig, 'recovery', selected)

    save(DEST/'provenance.json', dict(tableSha256=sha(OUT/'tables.json'), protocolSha256=sha(OUT/'protocol.json'),
                                    figures=used, interpretation='Redesigned figures from the same audited rows. '
                                    'Hatched or "timeout" cells are unavailable final results, not zero; censored recovery '
                                    'milestones are not reached before run end.'))


if __name__ == '__main__':
    render()
