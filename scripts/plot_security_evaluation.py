#!/usr/bin/env python3
"""Publication figures from PASS independent se1 audit, never forecast metrics.

Exports vector PDF, SVG with editable text, and 300 dpi PNG. Incomplete or
untargeted outcomes are labeled rather than silently converted to zero delay.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCAL = ROOT / ".tools" / "figure-python"
if LOCAL.exists():
    sys.path.insert(0, str(LOCAL))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import MaxNLocator

POLICIES = ("fixed", "percontact", "next", "multi")
LABELS = {"fixed": "FixedPeriod", "percontact": "PerContact", "next": "Next H1", "multi": "Multi H2"}
SHORT = {"fixed": "F", "percontact": "P", "next": "N", "multi": "M"}
COLORS = {"fixed": "#686868", "percontact": "#CC79A7", "next": "#0072B2", "multi": "#D55E00"}
FIXTURES = ("manual-cp30", "manual-cp60", "X-cp120", "X-cp150", "Y-cp120", "Y-cp150", "iss3-cp5400", "iss3-cp7200")
TITLES = {"manual-cp30": "Manual A→A · CP 30 s", "manual-cp60": "Manual A→A · CP 60 s",
          "X-cp120": "X: A→B→A · CP 120 s", "X-cp150": "X: A→B→A · CP 150 s",
          "Y-cp120": "Y: A→B→B · CP 120 s", "Y-cp150": "Y: A→B→B · CP 150 s",
          "iss3-cp5400": "ISS prefix (3) · CP 5400 s", "iss3-cp7200": "ISS prefix (3) · CP 7200 s"}
INK = "#243746"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def number(value):
    return float(value)


def clean(ax):
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis="y", color="#E5EAED", linewidth=.5, zorder=0)
    ax.set_axisbelow(True)
    ax.tick_params(length=3, width=.5)


def get(cases, fixture, mode, policy):
    row = cases.get((fixture, mode, policy))
    return row if row is not None and row["status"] == "FINISHED" else None


def export(fig, output, stem, figures, caption):
    fig.savefig(output / (stem + ".pdf"), metadata={"Title": caption, "Creator": "ContactAwareSS se1"})
    fig.savefig(output / (stem + ".svg"))
    fig.savefig(output / (stem + ".png"), dpi=300)
    figures.append({"name": stem, "caption": caption, "widthInches": float(fig.get_figwidth()),
                    "heightInches": float(fig.get_figheight())})
    plt.close(fig)


def no_attack(cases, output, figures):
    fig, axes = plt.subplots(2, 1, figsize=(7.16, 5.05), layout="constrained", sharex=True)
    for ax, metric, label in zip(axes, ("servedContacts", "controlCpu"), ("Served contacts", "Control CPU, GS + SC (s)")):
        for j, policy in enumerate(POLICIES):
            for i, fixture in enumerate(FIXTURES):
                row = get(cases, fixture, "none", policy)
                x = i + (j - 1.5) * .19
                if row is None:
                    ax.text(x, .03, "I", transform=ax.get_xaxis_transform(), ha="center", fontsize=7)
                else:
                    ax.bar(x, number(row["five"][metric]), width=.18, color=COLORS[policy], zorder=3)
        clean(ax)
        ax.set_ylabel(label)
    axes[0].yaxis.set_major_locator(MaxNLocator(integer=True))
    axes[1].set_xticks(range(len(FIXTURES)), [f.replace("-cp", "\nCP ") for f in FIXTURES], fontsize=8)
    fig.legend([Patch(color=COLORS[p]) for p in POLICIES], [LABELS[p] for p in POLICIES],
               loc="outside upper center", ncol=4, frameon=False)
    export(fig, output, "01_no_attack_service_cost", figures,
           "No-attack service and modeled security CPU at the registered cryptoperiods. "
           "FixedPeriod T=20 s (manual), 60 s (X/Y), 1800 s (ISS); PerContact uses paid just-in-time preparation. "
           "I denotes incomplete execution, not zero service. Costs are synthetic; solver wall time is excluded.")


def exposure_panel(ax, cases, fixture, mode):
    maximum = 0.0
    for i, policy in enumerate(POLICIES):
        row = get(cases, fixture, mode, policy)
        if row is None:
            ax.text(i, .04, "incomplete", rotation=90, transform=ax.get_xaxis_transform(), ha="center", fontsize=7)
            continue
        s = row["security"]
        before, after = number(s["decryptableBeforeBytes"])/1000, number(s["decryptableAfterBytes"])/1000
        confidential, exposed = number(s["confidentialDeliveredBytes"])/1000, number(s["compromisedDeliveredBytes"])/1000
        maximum = max(maximum, before + after, confidential + exposed)
        ax.bar(i - .17, before, .29, color="#B34B6B", zorder=3)
        ax.bar(i - .17, after, .29, bottom=before, color="#F2B48C", zorder=3)
        ax.bar(i + .17, confidential, .29, color="#237F78", zorder=3)
        ax.bar(i + .17, exposed, .29, bottom=confidential, color="#D7DDDF", edgecolor="#8E999F", linewidth=.3, zorder=3)
        ax.annotate(f'{row["five"]["servedContacts"]}/{row["contactCount"]}',
                    (i + .17, confidential + exposed), xytext=(0, 3), textcoords="offset points",
                    ha="center", fontsize=7, color=INK)
        if s["recoveryStatus"] == "NO_TARGET":
            ax.text(i - .17, .025, "NT", transform=ax.get_xaxis_transform(), ha="center", fontsize=7)
    ax.set_xticks(range(4), [SHORT[p] for p in POLICIES])
    ax.set_title(TITLES[fixture], loc="left", fontsize=9)
    ax.set_ylabel("Payload (kB)")
    clean(ax)
    # Explicit headroom: a zero-height stacked segment otherwise creates a
    # sticky upper edge and can push service annotations into the panel title.
    ax.set_ylim(0, max(.1, maximum*1.25))


def exposure(cases, output, figures, mode, stem, fixtures=FIXTURES):
    nrows = (len(fixtures) + 1)//2
    fig, axes = plt.subplots(nrows, 2, figsize=(7.16, 2.08*nrows + .55), layout="constrained", squeeze=False)
    for ax, fixture in zip(axes.flat, fixtures):
        exposure_panel(ax, cases, fixture, mode)
    handles = [Patch(color="#B34B6B"), Patch(color="#F2B48C"), Patch(color="#237F78"),
               Patch(facecolor="#D7DDDF", edgecolor="#8E999F")]
    fig.legend(handles, ["Exposed: sent before disclosure", "Exposed: sent after disclosure",
                         "Delivered: confidential", "Delivered: disclosed-key traffic"],
               loc="outside upper center", ncol=2, frameon=False, fontsize=8)
    export(fig, output, stem, figures,
           f"{mode} disclosure: paired bars show all decryptable intercepted bytes (left, including dropped-frame ciphertext) "
           "and delivered payload split by confidentiality (right). Labels above delivery bars are served/available contacts. "
           "F=FixedPeriod, P=PerContact, N=Next H1, M=Multi H2. NT means no active target at the fixed attack time. "
           "Panel scales differ; kB=1000 B. Disclosure capability and physical times are shared across policies. "
           "Silence and absence of a target are not interpreted as security superiority.")


def recovery_cost(cases, output, figures):
    fig, axes = plt.subplots(1, 2, figsize=(7.16, 5.0), layout="constrained", sharey=True)
    maximum, minimum = 1.0, float("inf")
    for i, fixture in enumerate(FIXTURES):
        for j, policy in enumerate(POLICIES):
            row = get(cases, fixture, "detected", policy)
            y = i + (j - 1.5)*.15
            if row is None:
                axes[0].text(.02, y, "I", transform=axes[0].get_yaxis_transform(), va="center", color=COLORS[policy])
                continue
            s = row["security"]
            if s["recoveryStatus"] == "RECOVERED":
                x = number(s["detectionToRecovery"])
                maximum = max(maximum, x)
                minimum = min(minimum, x)
                axes[0].scatter(x, y, s=22, color=COLORS[policy], zorder=3)
            elif s["recoveryStatus"] == "CENSORED_NO_RECOVERY":
                x = number(s["censorAt"]) - number(s["groundNoticeAt"])
                maximum = max(maximum, x)
                axes[0].scatter(x, y, marker=">", s=40, facecolors="none", edgecolors=COLORS[policy], zorder=3)
            else:
                axes[0].text(.02, y, "NT", transform=axes[0].get_yaxis_transform(), va="center", fontsize=7, color=COLORS[policy])
            if row["pairedDelta"] is not None:
                axes[1].scatter(number(row["pairedDelta"]["extraControlCpu"]), y, s=22, color=COLORS[policy], zorder=3)
    axes[0].set_xscale("log")
    axes[0].set_xlim(.1 if minimum < 1 else 1, maximum*1.5)
    axes[0].set_xlabel("Detection → independent frame (s, log)")
    axes[1].set_xlabel("Net extra control CPU vs no attack (s)")
    axes[1].axvline(0, color="#A0A7AB", lw=.6)
    for ax in axes:
        ax.set_yticks(range(len(FIXTURES)), [f.replace("-cp", " / ") for f in FIXTURES], fontsize=8)
        ax.grid(axis="x", color="#E5EAED", linewidth=.5)
        ax.spines[["top", "right"]].set_visible(False)
    axes[0].invert_yaxis()
    legend = [Line2D([], [], marker="o", color=COLORS[p], linestyle="", label=LABELS[p]) for p in POLICIES]
    legend.append(Line2D([], [], marker=">", markerfacecolor="none", color=INK, linestyle="", label="Right-censored"))
    fig.legend(handles=legend, loc="outside upper center", ncol=3, frameon=False, fontsize=8)
    export(fig, output, "04_detection_recovery_cost", figures,
           "Detected-mode recovery and net extra control CPU relative to each policy's matched no-attack control. "
           "Open right-pointing markers indicate no recovery before the observation limit, at the elapsed censoring time; "
           "they are lower bounds, not recovery measurements. NT=no target, I=incomplete. Negative CPU deltas represent "
           "suppressed normal work, not negative-cost operations. Precommitted downlink emission may continue until the "
           "frame boundary after ground notice; spacecraft notice is separately charged and contact-constrained.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = json.loads(args.audit.read_text(encoding="utf-8"))
    if report.get("audit") != "PASS":
        raise ValueError("Figures require a successful independent audit")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Use a new or empty figure output directory")
    args.output.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "axes.labelsize": 8, "text.color": INK,
                         "axes.labelcolor": INK, "xtick.color": INK, "ytick.color": INK, "axes.edgecolor": "#AAB4BB",
                         "pdf.fonttype": 42, "ps.fonttype": 42, "svg.fonttype": "none",
                         "savefig.facecolor": "white", "axes.titleweight": "normal"})
    cases = {(r["fixture"], r["mode"], r["policy"]): r for r in report["runs"].values()}
    figures = []
    no_attack(cases, args.output, figures)
    exposure(cases, args.output, figures, "undetected", "02_undetected_exposure_service")
    exposure(cases, args.output, figures, "detected", "03_detected_exposure_service")
    recovery_cost(cases, args.output, figures)
    manifest = {"sourceAudit": str(args.audit.resolve()), "sourceAuditSha256": sha(args.audit),
                "scriptSha256": sha(__file__), "matplotlib": matplotlib.__version__,
                "protocolSha256": report["protocolSha256"], "executableSha256": report["executableSha256"],
                "figures": figures, "files": {p.name: sha(p) for p in sorted(args.output.iterdir()) if p.is_file()},
                "exclusions": {n: r["status"] for n, r in report["runs"].items() if r["status"] != "FINISHED"},
                "interpretation": "Modelled stipulated disclosure; independent generations and management protection assumed. "
                                  "No cryptanalytic-strength or real-compromise-probability claim."}
    (args.output / "provenance.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (args.output / "captions.txt").write_text("\n\n".join(f["name"] + "\n" + f["caption"] for f in figures) + "\n", encoding="utf-8")
    print(f"Created {len(figures)} PDF/SVG/PNG figure sets in {args.output}")


if __name__ == "__main__":
    main()
