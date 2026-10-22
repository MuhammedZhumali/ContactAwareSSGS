#!/usr/bin/env python3
"""SE2.1 detected exposure: unchanged audited data, separate policy/hatch legends."""
import json
from pathlib import Path
import plot_security_evaluation_se2 as base

ROOT = Path(__file__).resolve().parents[1]

def separate_legends(fig, extras=()):
    # Reserve a common top band; legends must not overwrite each other or the axes.
    fig.set_layout_engine('constrained', rect=(0, 0, 1, .88))
    fig.legend(handles=[base.Patch(facecolor=base.COLORS[p], label=base.LABELS[p]) for p in base.POLICIES],
               title='Policy', loc='upper center', bbox_to_anchor=(.5, 1), ncol=4,
               frameon=False, fontsize=8, title_fontsize=8)
    fig.legend(handles=list(extras), title='Traffic emission relative to disclosure',
               loc='upper center', bbox_to_anchor=(.5, .94), ncol=2,
               frameon=False, fontsize=8, title_fontsize=8)

def main():
    audit_path = ROOT / 'docs/security-evaluation-se2/tables/independent-audit.json'
    manifest_path = ROOT / 'target/security-evaluation-se2/manifest.json'
    audit = json.loads(audit_path.read_text(encoding='utf-8'))
    manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    if audit.get('audit') != 'PASS' or audit.get('independentOfJavaCollectors') is not True:
        raise ValueError('Expected successful independent SE2 audit')
    if audit['fileHashes']['manifest.json'] != base.sha(manifest_path):
        raise ValueError('Audit/manifest mismatch')
    output = ROOT / 'docs/security-evaluation-se21/figures'
    if output.exists() and any(output.iterdir()):
        raise ValueError('Refusing to replace existing figure set')
    output.mkdir(parents=True, exist_ok=True)
    base.plt.rcParams.update({'font.family': 'DejaVu Sans', 'font.size': 8, 'axes.labelsize': 8,
        'text.color': base.INK, 'axes.labelcolor': base.INK, 'xtick.color': base.INK,
        'ytick.color': base.INK, 'axes.edgecolor': '#AAB4BB', 'pdf.fonttype': 42,
        'ps.fonttype': 42, 'svg.fonttype': 'none', 'savefig.facecolor': 'white'})
    base.policy_legend = separate_legends
    figures = []
    base.exposure(base.registered_rows(manifest, audit), output, figures, 'detected', 'detected_exposure_se21')
    base.verify_artifacts(output, figures)
    (output / 'captions.txt').write_text(figures[0]['caption'] + '\n', encoding='utf-8')
    (output / 'provenance.json').write_text(json.dumps({
        'figures': figures, 'sourceAuditSha256': base.sha(audit_path),
        'sourceManifestSha256': base.sha(manifest_path), 'scriptSha256': base.sha(__file__),
        'unchangedSE2PlotHelperSha256': base.sha(base.__file__),
        'legendPolicyLabels': [base.LABELS[p] for p in base.POLICIES],
        'legendHatchLabels': ['Sent before disclosure', 'Sent after disclosure'],
        'files': {p.name: base.sha(p) for p in output.iterdir() if p.is_file()},
        'scope': 'Same SE2 audited observations; presentation refinement only; no new attack runs.'
    }, indent=2) + '\n', encoding='utf-8')
    print(output)

if __name__ == '__main__':
    main()
