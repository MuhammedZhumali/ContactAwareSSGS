#!/usr/bin/env python3
"""SE2 publication figures from independently audited factual execution.

Uses a frozen manifest as the denominator, including missing/incomplete runs.
Censor bounds and absence of a target are never substituted for recovery delay.
Exports editable-text SVG, vector PDF and 300 dpi PNG; no recovered-only means.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from datetime import datetime
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
if (ROOT / ".tools" / "figure-python").exists():
    sys.path.insert(0, str(ROOT / ".tools" / "figure-python"))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.ticker import MaxNLocator

POLICIES = ("fixed", "percontact", "next", "multi")
LABELS = {"fixed": "FixedPeriod", "percontact": "PerContact", "next": "Next H1", "multi": "Multi H2"}
COLORS = {"fixed": "#686868", "percontact": "#CC79A7", "next": "#0072B2", "multi": "#D55E00"}
PRIMARY = ("X", "Y", "iss3-cp5400", "iss3-cp7200")
TITLES = {"X": "X: A → B → A · CP 150 s", "Y": "Y: A → B → B · CP 150 s",
          "iss3-cp5400": "ISS first 3 · CP 5400 s", "iss3-cp7200": "ISS first 3 · CP 7200 s"}
INK = "#243746"


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def instant(value):
    """Audit uses decimal epoch seconds; manifest uses ISO Instants."""
    if isinstance(value, (int, float, Decimal)) or "T" not in str(value):
        return Decimal(str(value))
    whole, _, zone = str(value).partition(".")
    if zone:
        fraction = zone.removesuffix("Z")
        return Decimal(int(datetime.fromisoformat(whole + "+00:00").timestamp())) + Decimal("0." + fraction)
    return Decimal(int(datetime.fromisoformat(str(value).replace("Z", "+00:00")).timestamp()))


def registered_rows(manifest, audit):
    """Keep unexecuted cases in the same evidence set without fabricated metrics."""
    rows = []
    for spec in manifest["cases"]:
        row = dict(spec)
        row.update(audit["runs"].get(spec["name"], {"status": "NOT_RUN"}))
        row["name"] = spec["name"]
        rows.append(row)
    by_name = {r["name"]: r for r in rows}
    for row in rows:
        row["_controlFinished"] = finished(by_name.get(row.get("baselineCase", row["name"])))
    return rows


def finished(row):
    return row is not None and row.get("status") == "FINISHED"


def unfinished_label(row):
    if row is None or row.get("status") == "NOT_RUN":
        return "NR"
    return "E" if row.get("status") == "ERROR" else "I"


def recovery_point(row):
    """Return a measured delay or a separately typed right-censor bound."""
    if not finished(row):
        return unfinished_label(row), None
    security = row["security"]
    status = security["recoveryStatus"]
    if status == "RECOVERED":
        return "RECOVERED", float(security["detectionToRecovery"])
    if status == "CENSORED_NO_RECOVERY":
        if security.get("groundNoticeAt") is None or security.get("censorAt") is None:
            raise ValueError("Censored detected target requires a notice and observation boundary")
        return "CENSORED", float(instant(security["censorAt"]) - instant(security["groundNoticeAt"]))
    return {"NO_TARGET": "NT", "UNDETECTED": "U", "NO_ATTACK": "NA"}.get(status, status), None


def matched_target_pair(rows):
    by_policy = {row["policy"]: row for row in rows}
    a, b = by_policy.get("multi"), by_policy.get("next")
    if not finished(a) or not finished(b):
        return None
    if not all(r.get("_controlFinished", True) for r in (a, b)):
        return None
    if any(r["security"]["recoveryStatus"] in ("NO_TARGET", "NO_ATTACK") for r in (a, b)):
        return None
    return a, b


def pair_exclusion_reason(rows):
    by_policy = {row["policy"]: row for row in rows}
    reasons = []
    for policy in ("multi", "next"):
        row = by_policy.get(policy)
        reason = None
        if row is None or row.get("status") == "NOT_RUN":
            reason = "not run"
        elif row.get("status") == "ERROR":
            reason = "error"
        elif not finished(row):
            reason = "incomplete"
        elif not row.get("_controlFinished", True):
            reason = "control incomplete"
        elif row["security"]["recoveryStatus"] in ("NO_TARGET", "NO_ATTACK"):
            reason = "no target"
        if reason:
            reasons.append(policy[0].upper() + ": " + reason)
    return "\n".join(reasons)


def clean(ax, axis="y"):
    ax.spines[["top", "right"]].set_visible(False)
    ax.grid(axis=axis, color="#E5EAED", linewidth=.5, zorder=0)
    ax.set_axisbelow(True)
    ax.tick_params(length=3, width=.5)


def export(fig, output, stem, figures, caption):
    fig.savefig(output / (stem + ".pdf"), metadata={"Title": caption, "Creator": "ContactAwareSS SE2"})
    fig.savefig(output / (stem + ".svg"))
    fig.savefig(output / (stem + ".png"), dpi=300)
    figures.append({"name": stem, "caption": caption, "widthInches": fig.get_figwidth(), "heightInches": fig.get_figheight()})
    plt.close(fig)


def verify_artifacts(output, figures):
    """Structural publication QA; visual layout still requires human/agent inspection."""
    from PIL import Image
    from pypdf import PdfReader
    checks = []
    for figure in figures:
        stem = figure["name"]
        reader = PdfReader(output / (stem + ".pdf"))
        if len(reader.pages) != 1 or len(reader.pages[0].images):
            raise ValueError(stem + ": expected a single vector PDF page without raster images")
        fonts = reader.pages[0]["/Resources"].get("/Font", {}).get_object()
        embedded = 0
        for ref in fonts.values():
            font = ref.get_object()
            descriptors = [font.get("/FontDescriptor")]
            descriptors += [f.get_object().get("/FontDescriptor") for f in font.get("/DescendantFonts", [])]
            for descriptor in descriptors:
                if descriptor is not None and any(k in descriptor.get_object() for k in ("/FontFile", "/FontFile2", "/FontFile3")):
                    embedded += 1
        if not embedded:
            raise ValueError(stem + ": PDF has no embedded fonts")
        svg = ET.parse(output / (stem + ".svg")).getroot()
        text_count = sum(e.tag.rsplit("}", 1)[-1] == "text" for e in svg.iter())
        raster_count = sum(e.tag.rsplit("}", 1)[-1] == "image" for e in svg.iter())
        if not text_count or raster_count:
            raise ValueError(stem + ": SVG must retain editable text and vector content")
        with Image.open(output / (stem + ".png")) as png:
            dpi = png.info.get("dpi", (0, 0))
            if any(abs(value - 300) > .1 for value in dpi):
                raise ValueError(stem + ": PNG is not 300 dpi")
            dimensions = png.size
        checks.append({"name": stem, "pdfPages": 1, "pdfRasterImages": 0, "embeddedFontDescriptors": embedded,
                       "svgTextElements": text_count, "svgRasterImages": raster_count,
                       "pngSizePixels": dimensions, "pngDpi": dpi})
    (output / "artifact-qa.json").write_text(json.dumps({"status": "PASS", "checks": checks,
        "scope": "File structure, vector formats, embedded fonts, editable SVG text, PNG density. Visual layout checked separately."},
        indent=2) + "\n", encoding="utf-8")


def attack_points(rows, fixture):
    points = {}
    for row in rows:
        if row["fixture"] == fixture and row["mode"] != "none":
            key = row["attackPoint"]
            time = instant(row["attackAt"])
            if key in points and points[key] != time:
                raise ValueError("One attack label maps to multiple times")
            points[key] = time
    return sorted(points, key=lambda p: (points[p], p))


def point_label(label):
    if label == "original":
        return "SE1"
    if str(label).startswith("p") and str(label)[1:].isdigit():
        return str(label)[1:] + "%"
    return str(label).replace("FIRST_GAP_MIDPOINT", "gap mid").replace("gap-midpoint", "gap mid").replace("GAP_MIDPOINT", "gap mid").replace("pct-", "").replace("PERCENT_", "").replace("SE1_ORIGINAL", "SE1").replace("_", " ")


def primary_rows(rows, fixture, mode):
    return {(r["attackPoint"], r["policy"]): r for r in rows if r["fixture"] == fixture and r["mode"] == mode}


def primary_axes():
    return plt.subplots(2, 2, figsize=(7.16, 5.35), layout="constrained")


def policy_legend(fig, extras=()):
    handles = [Line2D([], [], marker="o", color=COLORS[p], linestyle="", label=LABELS[p], markersize=4) for p in POLICIES]
    fig.legend(handles=handles + list(extras), loc="outside upper center", ncol=3 if extras else 4, frameon=False, fontsize=8)


def recovery(rows, output, figures):
    fig, axes = primary_axes()
    for ax, fixture in zip(axes.flat, PRIMARY):
        points, lookup = attack_points(rows, fixture), primary_rows(rows, fixture, "detected")
        maximum = 1.0
        for i, point in enumerate(points):
            for j, policy in enumerate(POLICIES):
                kind, value = recovery_point(lookup.get((point, policy)))
                x = i + (j - 1.5) * .15
                if value is None:
                    ax.text(x, .03, kind, color=COLORS[policy], rotation=90, ha="center", va="bottom", fontsize=6,
                            transform=ax.get_xaxis_transform())
                else:
                    maximum = max(maximum, value)
                    ax.scatter(x, value, s=21 if kind == "RECOVERED" else 35, marker="o" if kind == "RECOVERED" else "^",
                               facecolors=COLORS[policy] if kind == "RECOVERED" else "none", edgecolors=COLORS[policy], zorder=3)
        ax.set_yscale("symlog", linthresh=1)
        ax.set_ylim(0, maximum * 1.6 + .2)
        ax.set_ylabel("Notice → independent frame (s)")
        ax.set_xticks(range(len(points)), [point_label(p) for p in points], rotation=32, ha="right", fontsize=7)
        ax.set_title(TITLES[fixture], loc="left", fontsize=9)
        clean(ax)
    policy_legend(fig, [Line2D([], [], marker="^", color=INK, markerfacecolor="none", linestyle="", label="Right-censored")])
    export(fig, output, "01_attack_time_recovery", figures,
           "Detected-mode recovery across preregistered attack times. Filled circles are time from GS notice to the first delivered "
           "frame on independent valid material. Open triangles mark the observation limit for right-censored outcomes and are lower "
           "bounds, not measured recovery. NT=no target, I=incomplete, E=error, NR=not run. Attack labels are equally spaced categories sorted "
           "by physical time; SE1 is the original instant (also 50% in X/Y), and gap is the next global gap midpoint. Vertical scale is linear from 0 to 1 s and logarithmic above 1 s; "
           "panel limits differ. LIGHT demand exhaustion can defer the next delivered frame until another contact despite earlier "
           "independent-key activation; this is not a continuous-unavailability measure. No recovered-only aggregate is shown.")


def exposure(rows, output, figures, mode, stem):
    fig, axes = primary_axes()
    for ax, fixture in zip(axes.flat, PRIMARY):
        points, lookup = attack_points(rows, fixture), primary_rows(rows, fixture, mode)
        maximum = .1
        for i, point in enumerate(points):
            for j, policy in enumerate(POLICIES):
                row, x = lookup.get((point, policy)), i + (j - 1.5) * .19
                if not finished(row):
                    ax.text(x, .02, unfinished_label(row), rotation=90,
                            transform=ax.get_xaxis_transform(), ha="center", fontsize=6, color=COLORS[policy])
                    continue
                s = row["security"]
                if s["recoveryStatus"] == "NO_TARGET":
                    ax.text(x, .02, "NT", rotation=90, transform=ax.get_xaxis_transform(), ha="center", fontsize=6, color=COLORS[policy])
                    continue
                before, after = float(s["decryptableBeforeBytes"]) / 1000, float(s["decryptableAfterBytes"]) / 1000
                maximum = max(maximum, before + after)
                ax.bar(x, before, .175, color=COLORS[policy], alpha=.45, zorder=3)
                ax.bar(x, after, .175, bottom=before, color=COLORS[policy], hatch="///", edgecolor="white", linewidth=.15, zorder=3)
        ax.set_ylim(0, maximum * 1.13)
        ax.set_ylabel("Disclosed traffic (kB)")
        ax.set_xticks(range(len(points)), [point_label(p) for p in points], rotation=32, ha="right", fontsize=7)
        ax.set_title(TITLES[fixture], loc="left", fontsize=9)
        clean(ax)
    policy_legend(fig, [Patch(facecolor=INK, alpha=.45, label="Sent before disclosure"),
                        Patch(facecolor=INK, hatch="///", edgecolor="white", label="Sent after disclosure")])
    export(fig, output, stem, figures,
           f"{mode.capitalize()} disclosure across registered attack times: intercepted decryptable bytes split at physical "
           "disclosure time. Light segments were emitted before disclosure; hatched segments after. Includes ciphertext fragments "
           "from aborted or rejected frames, so this is distinct from compromised delivered payload. kB=1000 B. NT=no target, "
           "I=incomplete, E=error, NR=not run; these outcomes are not plotted as zero exposure. Equally spaced attack categories are sorted "
           "by time; panel scales differ. Lower exposure alone can reflect less transmitted traffic; consult the delivery figure.")


def delivery(rows, output, figures):
    fig, axes = plt.subplots(2, 4, figsize=(7.16, 4.8), layout="constrained")
    for column, fixture in enumerate(PRIMARY):
        points = attack_points(rows, fixture)
        for row_index, mode in enumerate(("undetected", "detected")):
            ax, lookup = axes[row_index, column], primary_rows(rows, fixture, mode)
            maximum = .1
            for j, policy in enumerate(POLICIES):
                for i, point in enumerate(points):
                    row, x = lookup.get((point, policy)), i + (j - 1.5) * .13
                    if not finished(row):
                        ax.text(x, .02, unfinished_label(row), rotation=90, transform=ax.get_xaxis_transform(), ha="center", fontsize=5, color=COLORS[policy])
                        continue
                    s = row["security"]
                    total, confidential = float(s["deliveredBytes"]) / 1000, float(s["confidentialDeliveredBytes"]) / 1000
                    maximum = max(maximum, total)
                    ax.plot([x, x], [confidential, total], color=COLORS[policy], alpha=.5, linewidth=.6)
                    ax.scatter(x, total, s=16, marker="o", facecolors="none", edgecolors=COLORS[policy], linewidth=.65, zorder=3)
                    ax.scatter(x, confidential, s=10, marker="o", color=COLORS[policy], zorder=4)
                    if s["recoveryStatus"] == "NO_TARGET":
                        ax.scatter(x, confidential, s=20, marker="x", color=COLORS[policy], linewidth=.7, zorder=5)
            ax.set_ylim(0, maximum * 1.10)
            ax.set_xticks(range(len(points)), [point_label(p) for p in points], rotation=65, ha="right", fontsize=6)
            if row_index == 0:
                ax.set_title(TITLES[fixture].replace(" · ", "\n"), fontsize=8)
            if column == 0:
                ax.set_ylabel(mode.capitalize() + "\nDelivered payload (kB)")
            clean(ax)
    policy_legend(fig, [Line2D([], [], marker="o", color=INK, markerfacecolor="none", linestyle="", label="All delivered"),
                        Line2D([], [], marker="x", color=INK, linestyle="", label="No target")])
    export(fig, output, "04_attack_time_delivery", figures,
           "Payload actually delivered under the attack: filled dots are confidential delivered bytes, open dots are total delivered "
           "bytes, and the connecting segment is delivered traffic encrypted under disclosed material. A cross denotes absence of "
           "an active target at the fixed attack time, not a security win. I=incomplete, E=error, NR=not run. Top: undetected; bottom: detected. "
           "kB=1000 B; equally spaced attack categories and separate panel scales. This figure prevents reading exposure reductions "
           "caused by lost service as a pure security improvement. Own-policy no-attack losses are available in metrics.csv.")


def secondary(rows, output, figures):
    groups = defaultdict(list)
    for row in rows:
        if row["fixture"] not in PRIMARY and row["mode"] == "detected":
            groups[(row["block"], row["fixture"], row["attackPoint"])].append(row)
    groups = sorted(groups.items())
    if not groups:
        return
    fig, axes = plt.subplots(1, 4, figsize=(7.16, max(4.0, len(groups) * .25 + 1.25)), sharey=True, layout="constrained")
    metrics = ("served", "confidential", "exposure", "cpu")
    maximum_served_delta = 0
    for y, ((block, fixture, point), cases) in enumerate(groups):
        pair = matched_target_pair(cases)
        if pair is None:
            for ax in axes:
                ax.text(.5, y, pair_exclusion_reason(cases), transform=ax.get_yaxis_transform(), va="center", ha="center", fontsize=6, color="#777777")
            continue
        a, b = pair
        values = ((a["five"]["servedContacts"] - b["five"]["servedContacts"]),
                  (float(a["security"]["confidentialDeliveredBytes"]) - float(b["security"]["confidentialDeliveredBytes"])) / 1000,
                  sum(float(a["security"][k]) - float(b["security"][k]) for k in ("decryptableBeforeBytes", "decryptableAfterBytes")) / 1000,
                  float(a["five"]["controlCpu"]) - float(b["five"]["controlCpu"]))
        maximum_served_delta = max(maximum_served_delta, abs(values[0]))
        for ax, metric, value in zip(axes, metrics, values):
            favorable = value > 0 if metric in ("served", "confidential") else value < 0
            color = "#237F78" if favorable else ("#B34B6B" if value else "#686868")
            ax.scatter(value, y, s=19, color=color, zorder=3)
    for ax, label in zip(axes, ("Δ served contacts", "Δ confidential (kB)", "Δ disclosed (kB)", "Δ control CPU (s)")):
        ax.axvline(0, color="#AAB4BB", lw=.7)
        ax.set_xlabel(label, fontsize=7)
        ax.set_yticks(range(len(groups)))
        clean(ax, "x")
    axes[0].set_yticklabels([fixture for (_, fixture, _), _ in groups], fontsize=6.5)
    axes[0].invert_yaxis()
    if maximum_served_delta == 0:
        axes[0].set_xlim(-.5, .5)
        axes[0].set_xticks([0])
    else:
        axes[0].set_xlim(-maximum_served_delta - .2, maximum_served_delta + .2)
        axes[0].xaxis.set_major_locator(MaxNLocator(integer=True))
    fig.suptitle("Secondary blocks · detected mode · Multi minus Next", fontsize=10)
    export(fig, output, "05_secondary_paired_comparison", figures,
           "Prespecified load, contact, combination and model-assumption blocks at their fixed SE1 attack time. Each row is one "
           "physical configuration, not a replicate. Points compare Multi minus Next only when both executions and their controls "
           "finished and both had a target. Excluded rows name the cause for M=Multi and N=Next separately, including unfinished "
           "own controls. A finished attack with an unfinished control retains valid absolute metrics in metrics.csv, but its own-control "
           "delta is unavailable. "
           "Green indicates a favorable sign for that individual metric, rose an unfavorable sign, gray equality; metrics are not "
           "combined into a security ranking. Additional service can cost CPU. Full four-policy comparisons are in pairwise.csv; "
           "deterministic grid counts are not compromise or recovery probabilities.")


def coverage(rows, output, figures):
    groups = defaultdict(list)
    for row in rows:
        if row["mode"] == "detected":
            groups[(row["block"], row["workload"])].append(row)
    groups = sorted(groups.items())
    fig, axes = plt.subplots(1, 2, figsize=(7.16, max(4.1, len(groups) * .75 + .8)), sharey=True, layout="constrained")
    outcome_colors = {"RECOVERED": "#237F78", "CENSORED_NO_RECOVERY": "#D98D43", "NO_TARGET": "#C3CCD1", "INCOMPLETE": "#B34B6B", "ERROR": "#6D4A7D", "NOT_RUN": "#ECEFF1"}
    labels = []
    for i, ((block, workload), group) in enumerate(groups):
        labels.append(f"{block}\n{workload}")
        for j, policy in enumerate(POLICIES):
            cases = [r for r in group if r["policy"] == policy]
            if not cases:
                continue
            y = i + (j - 1.5) * .18
            count = sum(finished(r) for r in cases)
            axes[0].barh(y, count / len(cases), .15, color=COLORS[policy], zorder=3)
            axes[0].text(1.015, y, f"{count}/{len(cases)}", va="center", fontsize=6)
            counts = Counter((r["security"]["recoveryStatus"] if finished(r) else
                              ("NOT_RUN" if r["status"] == "NOT_RUN" else "ERROR" if r["status"] == "ERROR" else "INCOMPLETE")) for r in cases)
            left = 0
            for outcome, color in outcome_colors.items():
                width = counts[outcome] / len(cases)
                if width:
                    axes[1].barh(y, width, .15, left=left, color=color, edgecolor="white", linewidth=.3, zorder=3)
                    if width > .09:
                        axes[1].text(left + width / 2, y, str(counts[outcome]), va="center", ha="center", fontsize=6)
                    left += width
            if abs(left - 1) > 1e-10:
                raise ValueError("Unexpected detected outcome in registered coverage")
    for ax in axes:
        ax.set_yticks(range(len(labels)), labels, fontsize=7)
        ax.set_xlim(0, 1.19 if ax is axes[0] else 1)
        ax.set_xticks((0, .5, 1), ("0", "0.5", "1"))
        clean(ax, "x")
    axes[0].invert_yaxis()
    axes[0].set_title("Finished / registered", loc="left", fontsize=9)
    axes[1].set_title("Detected outcomes / registered", loc="left", fontsize=9)
    axes[0].set_xlabel("Completion fraction")
    axes[1].set_xlabel("Fraction of the deterministic grid")
    fig.legend(handles=[Patch(color=c, label={"CENSORED_NO_RECOVERY": "Censored", "NO_TARGET": "No target", "INCOMPLETE": "Incomplete", "ERROR": "Error", "NOT_RUN": "Not run", "RECOVERED": "Recovered"}[s]) for s, c in outcome_colors.items()],
               loc="outside upper center", ncol=3, frameon=False, fontsize=8)
    export(fig, output, "06_completion_recovery_coverage", figures,
           "Registered detected-mode coverage by block and workload. Within each group, rows are FixedPeriod, PerContact, Next H1, "
           "Multi H2 from top to bottom. Left colors identify policy; labels show finished/registered. Right stacks count recovered, "
           "censored, no-target, incomplete, error and not-run outcomes using the full registered denominator. These descriptive fractions "
           "of a deterministic parameter grid are not estimated recovery probabilities. Incomplete runs are excluded from outcome "
           "quality comparisons but retained here. A finished attack is counted here even when its own control was unfinished; "
           "the corresponding paired comparison is excluded separately. Per-mode and all-run coverage is provided in recovery-summary.csv.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    audit = json.loads(args.audit.read_text(encoding="utf-8"))
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    if audit.get("audit") != "PASS" or audit.get("independentOfJavaCollectors") is not True:
        raise ValueError("Figures require a successful independent factual audit")
    if audit.get("fileHashes", {}).get("manifest.json") != sha(args.manifest):
        raise ValueError("Manifest does not match the audited registration")
    if args.output.exists() and any(args.output.iterdir()):
        raise ValueError("Use a new or empty figure output directory")
    args.output.mkdir(parents=True, exist_ok=True)
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "axes.labelsize": 8, "text.color": INK,
                         "axes.labelcolor": INK, "xtick.color": INK, "ytick.color": INK, "axes.edgecolor": "#AAB4BB",
                         "pdf.fonttype": 42, "ps.fonttype": 42, "svg.fonttype": "none", "savefig.facecolor": "white"})
    rows, figures = registered_rows(manifest, audit), []
    recovery(rows, args.output, figures)
    exposure(rows, args.output, figures, "undetected", "02_attack_time_undetected_exposure")
    exposure(rows, args.output, figures, "detected", "03_attack_time_detected_exposure")
    delivery(rows, args.output, figures)
    secondary(rows, args.output, figures)
    coverage(rows, args.output, figures)
    verify_artifacts(args.output, figures)
    (args.output / "captions.txt").write_text("\n\n".join(f["name"] + "\n" + f["caption"] for f in figures) + "\n", encoding="utf-8")
    provenance = {"sourceAudit": str(args.audit.resolve()), "sourceAuditSha256": sha(args.audit),
                  "sourceManifest": str(args.manifest.resolve()), "sourceManifestSha256": sha(args.manifest),
                  "scriptSha256": sha(__file__), "matplotlib": matplotlib.__version__,
                  "protocolSha256": audit["protocolSha256"], "executableSha256": audit["executableSha256"],
                  "figures": figures, "files": {p.name: sha(p) for p in sorted(args.output.iterdir()) if p.is_file()},
                  "exclusions": {r["name"]: r["status"] for r in rows if not finished(r)},
                  "interpretation": "Synthetic deterministic model; independent generations and protected management assumed. "
                      "No compromise probability, recovered-only mean, or unconditional security-superiority claim."}
    (args.output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    print(f"Created {len(figures)} PDF/SVG/PNG figure sets in {args.output}")


if __name__ == "__main__":
    main()
