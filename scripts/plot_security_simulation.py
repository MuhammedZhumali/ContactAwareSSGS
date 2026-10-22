"""Reproduce the synthetic horizon-witness figures from Java simulator journals.

No simulation, policy selection or expected result values are implemented here.
The reference enumeration is used only to validate the supplied event results.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCAL_PACKAGES = ROOT / ".tools" / "figure-python"
if LOCAL_PACKAGES.exists():
    sys.path.insert(0, str(LOCAL_PACKAGES))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch, Rectangle
import numpy as np

ORDER = ("X-NEXT", "X-MULTI", "Y-NEXT", "Y-MULTI")
COLORS = {"NEXT": "#0072B2", "MULTI": "#D55E00"}
INK, GRAY, PALE = "#243746", "#74818D", "#E8EDF1"
NAMES = {
    "01_outcomes": "Service coverage and resource trade-offs",
    "02_timeline": "Executed operations and useful secure transmission",
    "03_cost_breakdown": "Security-management cost decomposition",
    "04_cumulative": "Cumulative service and useful secure time",
}
CAPTIONS = {
    "01_outcomes": (
        "Deterministic execution of predefined Next-contact-aware and Multi-contact plans "
        "for synthetic sequences X (A--B--A) and Y (A--B--B). Multi serves an additional "
        "contact in X while consuming more control CPU and management traffic and yielding "
        "slightly less total useful secure time. Both plans coincide in Y. CPU includes "
        "common ground preparation; these are model outputs, not measurements or "
        "automatically selected policy results."
    ),
    "02_timeline": (
        "Execution timelines reconstructed from operation and payload events. All gaps "
        "use the same linear time scale. Bars distinguish ground preparation, online "
        "security operations and completed secure payload frames. In X, Multi installs, "
        "switches and opens A during C1, allowing reuse in C3. Next leaves C3 unserved "
        "after the old A key deadline. In Y, both plans reuse B in C3. I denotes INSTALL, "
        "S denotes SWITCH, and O/R denotes OPEN or REUSE. The common PREPARE interval is "
        "included before C1. Local zero-duration STOP/EXPIRE events do not occupy bars."
    ),
    "03_cost_breakdown": (
        "Costs reconstructed by summing actual journal entries. Ground CPU is split "
        "between common PREPARE and subsequent online operations; spacecraft CPU is "
        "reported separately. Management bytes are split by direction. Radio occupancy "
        "and the sum of operation elapsed durations are distinct quantities. Elapsed "
        "duration includes waiting and is not the total observation horizon. Common "
        "PREPARE is charged to every plan, including when the prepared material is unused. "
        "Data-plane cryptographic CPU and decision-search CPU are excluded."
    ),
    "04_cumulative": (
        "Cumulative served contacts and useful secure transmission reconstructed from "
        "events. For this display, a served contact is credited at contact closure; "
        "payload time is credited at each completed frame. Shaded regions are contact "
        "windows. Y traces coincide; the dashed Multi trace is overlaid on Next. X "
        "illustrates why the primary objective of served-contact count must be reported "
        "separately from total useful time. No confidence intervals are shown because "
        "each trace is a deterministic execution, not a stochastic sample."
    ),
}


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"), parse_float=Decimal)


def decimal(value):
    return Decimal(str(value))


def equal(actual, expected, label):
    if decimal(actual) != decimal(expected):
        raise ValueError(f"{label}: actual={actual}, expected={expected}")


def stamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def origin(result):
    return min(stamp(e["at"]) for e in result["events"] if e["type"] == "CONTACT_STARTED")


def seconds(value, epoch):
    return (stamp(value) - epoch).total_seconds()


def windows(result):
    epoch = origin(result)
    found = {}
    for e in result["events"]:
        if e["type"] == "CONTACT_STARTED":
            found[e["contactId"]] = {
                "id": e["contactId"], "station": e["contextId"]["groundStationId"],
                "start": seconds(e["at"], epoch),
            }
        elif e["type"] == "CONTACT_ENDED":
            found[e["contactId"]]["end"] = seconds(e["at"], epoch)
    return sorted(found.values(), key=lambda c: c["start"])


def operation_intervals(result):
    epoch, started, intervals = origin(result), {}, []
    for e in result["events"]:
        key = e["operationId"], e["action"]
        if e["type"] == "STEP_STARTED":
            if key in started:
                raise ValueError(f"Duplicate unfinished operation {key}")
            started[key] = seconds(e["at"], epoch)
        elif e["type"] in ("STEP_COMPLETED", "STEP_FAILED", "STEP_INTERRUPTED"):
            begin = started.pop(key)
            finish = seconds(e["at"], epoch)
            equal(finish - begin, e["usage"]["elapsed"], f"{key} elapsed")
            intervals.append((begin, finish, e))
    if started:
        raise ValueError(f"Unclosed operations: {started}")
    return intervals


def validate(summary, results, reference):
    """Independent arithmetic reconciliation, including every common warmup."""
    rows = {r["scenario"]: r for r in summary["results"]}
    if set(rows) != set(ORDER) or set(results) != set(ORDER):
        raise ValueError("This figure set requires exactly X/Y x Next/Multi")
    report = []
    mapping = {
        "groundCpuSeconds": "groundCpu", "spacecraftCpuSeconds": "spacecraftCpu",
        "managementRadioSeconds": "radioOccupancy", "operationElapsedSeconds": "elapsed",
        "uplinkManagementBytes": "uplinkBytes", "downlinkManagementBytes": "downlinkBytes",
    }
    for name in ORDER:
        r, s = results[name], rows[name]
        if r["provenance"] != "SYNTHETIC_ASSUMPTION" or r["scenario"] != name:
            raise ValueError("Unexpected provenance/scenario")
        events, metrics = r["events"], r["metrics"]
        if metrics["operationFailures"] or r["uncertainContexts"]:
            raise ValueError("Canonical witness contains failures or uncertain contexts")
        for summary_key, event_key in mapping.items():
            total = sum((decimal(e["usage"][event_key]) for e in events), Decimal(0))
            equal(total, s[summary_key], f"{name} {summary_key}")
            equal(total, metrics["controlCosts"][event_key], f"{name} collector {event_key}")
        useful = sum((decimal(e["usefulTime"]) for e in events), Decimal(0))
        equal(useful, s["usefulSeconds"], name + " useful time")
        equal(useful, metrics["usefulSecureTime"], name + " collector useful time")
        equal(s["cpuSeconds"], s["groundCpuSeconds"] + s["spacecraftCpuSeconds"], name + " CPU")
        equal(s["managementBytes"], s["uplinkManagementBytes"] + s["downlinkManagementBytes"], name + " bytes")
        equal(sum(e["createdKeys"] for e in events), s["createdKeys"], name + " created keys")
        equal(sum(c["served"] for c in metrics["contacts"]), s["served"], name + " served")
        for c in metrics["contacts"]:
            matched = [e for e in events if e["contactId"] == c["contactId"]]
            equal(sum((e["usefulTime"] for e in matched), Decimal(0)), c["usefulTime"], name + c["contactId"])
            for src, dst in (("uplinkPayloadBytes", "uplinkBytes"), ("downlinkPayloadBytes", "downlinkBytes"),
                             ("uplinkFrames", "uplinkFrames"), ("downlinkFrames", "downlinkFrames")):
                equal(sum(e[src] for e in matched), c[dst], name + c["contactId"] + dst)
        warmup = [e for e in events if e["type"] == "STEP_COMPLETED" and e["action"] == "PREPARE"]
        if len(warmup) != 1:
            raise ValueError("Expected one common PREPARE")
        equal(warmup[0]["usage"]["groundCpu"] * 1000, reference["preparedWarmup"]["cpuGroundMs"], name + " warmup")
        equal(warmup[0]["createdKeys"], reference["preparedWarmup"]["newKeys"], name + " warmup keys")
        actions = (["REUSE", "REUSE", "REUSE"] if name.startswith("Y") else
                   ["ROTATE", "REUSE", "REUSE"] if name.endswith("MULTI") else ["REUSE", "REUSE", "SKIP"])
        expected = next(v for v in reference["full"][name[0]] if v["actions"] == actions)
        for field, ref_field, scale in (("served", "N", 1), ("usefulSeconds", "T", 1),
                                       ("groundCpuSeconds", "g", 1000), ("spacecraftCpuSeconds", "s", 1000),
                                       ("uplinkManagementBytes", "up", 1), ("downlinkManagementBytes", "down", 1),
                                       ("createdKeys", "newKeys", 1)):
            equal(s[field] * scale, expected[ref_field], name + " reference " + field)
        operation_intervals(r)
        report.append({"scenario": name, "journal_metrics_summary_reference_agree": True})
    return rows, report


def style():
    plt.rcParams.update({
        "font.family": "DejaVu Sans", "font.size": 9, "axes.titlesize": 10,
        "axes.labelsize": 9, "xtick.labelsize": 8, "ytick.labelsize": 8,
        "legend.fontsize": 8, "axes.spines.top": False, "axes.spines.right": False,
        "axes.edgecolor": GRAY, "axes.labelcolor": INK, "text.color": INK,
        "xtick.color": INK, "ytick.color": INK, "axes.linewidth": 0.65,
        "grid.color": "#DCE2E7", "grid.linewidth": 0.6, "axes.axisbelow": True,
        "pdf.fonttype": 42, "ps.fonttype": 42, "svg.fonttype": "none",
        "svg.hashsalt": "contactaware-witness", "savefig.facecolor": "white",
        "hatch.linewidth": 0.6,
    })


def save(fig, folder, name):
    for ext in ("pdf", "svg", "png"):
        meta = {"Title": NAMES[name], "Creator": "ContactAwareSS event-log plotting script"}
        if ext == "pdf":
            meta.update(CreationDate=None, ModDate=None)
        elif ext == "svg":
            meta.update(Date=None)
        fig.savefig(folder / f"{name}.{ext}", dpi=600, metadata=meta)
    plt.close(fig)


def footer(fig):
    fig.text(0.5, 0.015, "Synthetic witness | Predefined plans | Common PREPARE included",
             ha="center", fontsize=7.5, color=GRAY)


def outcomes(rows, folder):
    fig, axes = plt.subplots(2, 2, figsize=(7.16, 5.05))
    fig.subplots_adjust(left=.085, right=.985, bottom=.135, top=.87, hspace=.65, wspace=.34)
    specs = [("served", "Served contacts", "(a) Service coverage", 3.55, "{:.0f}"),
             ("usefulSeconds", "Useful secure time (s)", "(b) Useful transmission", 50, "{:.0f}"),
             ("cpuSeconds", "Control CPU, GS + SC (s)", "(c) CPU cost", 1.0, "{:.2f}"),
             ("managementBytes", "Management traffic (bytes)", "(d) Link overhead", 1500, "{:.0f}")]
    for ax, (field, ylabel, title, upper, fmt) in zip(axes.flat, specs):
        for j, plan in enumerate(("NEXT", "MULTI")):
            vals = [float(rows[f"{s}-{plan}"][field]) for s in ("X", "Y")]
            bars = ax.bar(np.arange(2) + (j - .5) * .32, vals, .29, color=COLORS[plan],
                          edgecolor=INK, linewidth=.45, hatch="" if j == 0 else "//")
            ax.bar_label(bars, labels=[fmt.format(v) for v in vals], padding=3, fontsize=9)
        ax.set(xticks=[0, 1], xticklabels=["X: A-B-A", "Y: A-B-B"], ylabel=ylabel, ylim=(0, upper))
        if field == "served":
            ax.set_yticks([0, 1, 2, 3])
        ax.set_title(title, loc="left", pad=9, fontweight="bold")
        ax.grid(axis="y")
    fig.legend([Patch(facecolor=COLORS["NEXT"], edgecolor=INK),
                Patch(facecolor=COLORS["MULTI"], edgecolor=INK, hatch="//")],
               ["Next-contact-aware plan", "Multi-contact plan"], loc="upper center",
               bbox_to_anchor=(.5, .98), ncol=2, frameon=False)
    footer(fig)
    save(fig, folder, "01_outcomes")


def payload_segments(result):
    epoch, segments = origin(result), []
    for e in result["events"]:
        if e["type"] != "PAYLOAD_TRANSFERRED":
            continue
        end = seconds(e["at"], epoch)
        begin = end - float(e["usefulTime"])
        if segments and segments[-1][2] == e["contactId"] and abs(segments[-1][1] - begin) < 1e-9:
            segments[-1] = (segments[-1][0], end, e["contactId"])
        else:
            segments.append((begin, end, e["contactId"]))
    return segments


def timeline(results, folder):
    fig, axes = plt.subplots(2, 1, figsize=(7.16, 4.75), sharex=True)
    fig.subplots_adjust(left=.095, right=.98, bottom=.185, top=.9, hspace=.48)
    for ax, seq in zip(axes, ("X", "Y")):
        ax.set_title(f"{seq}: A-B-{'A' if seq == 'X' else 'B'}", loc="left", fontweight="bold", pad=14)
        ws = windows(results[seq + "-NEXT"])
        for c in ws:
            ax.axvspan(c["start"], c["end"], color=PALE, zorder=0, alpha=.65)
            ax.text((c["start"] + c["end"]) / 2, 1.51, c["id"] + ": " + c["station"],
                    ha="center", fontsize=8, fontweight="bold")
        for plan, y in (("NEXT", 1), ("MULTI", 0)):
            r = results[seq + "-" + plan]
            for c in ws:
                ax.add_patch(Rectangle((c["start"], y - .2), c["end"] - c["start"], .4,
                                       facecolor="white", edgecolor=GRAY, linewidth=.5))
            for begin, end, _ in payload_segments(r):
                ax.barh(y, end - begin, left=begin, height=.4, color=COLORS[plan], zorder=3)
                # C3 is narrow: its useful interval is labelled below the track.
                if end - begin > 5:
                    ax.text((begin + end) / 2, y, f"{end-begin:g} s", ha="center", va="center",
                            color="white", fontsize=8, zorder=4)
                else:
                    ax.text((begin + end) / 2, y - .34, f"{end-begin:g} s",
                            ha="center", fontsize=8, color=COLORS[plan])
            for begin, end, e in operation_intervals(r):
                if begin == end:
                    continue
                ax.barh(y, end - begin, left=begin, height=.4,
                        color="#C9B7D8" if e["action"] == "PREPARE" else "#46515B",
                        edgecolor="white", linewidth=.35, zorder=4)
        if seq == "X":
            c3 = ws[-1]
            ax.add_patch(Rectangle((c3["start"], .8), c3["end"] - c3["start"], .4,
                                   facecolor="#F8E5DF", edgecolor="#A53E25", hatch="xxxx", linewidth=.6, zorder=4))
            ax.text((c3["start"] + c3["end"]) / 2, .55, "unserved", ha="center",
                    va="center", color="#A53E25", fontsize=8)
            deadline = next(seconds(e["at"], origin(results["X-NEXT"])) for e in results["X-NEXT"]["events"]
                            if e["type"] == "KEY_EXPIRED" and e["contextId"]["groundStationId"] == "A")
            ax.vlines(deadline, .7, 1.4, color="#A53E25", linestyle=":", linewidth=1)
            ax.text(deadline - 1, 1.03, f"Old A expires\nin Next: {deadline:g} s", ha="right",
                    va="center", fontsize=7.5, color="#A53E25")
            management = sum(end - begin for begin, end, e in operation_intervals(results["X-MULTI"])
                             if e["contactId"] == "C1" and e["action"] in ("INSTALL", "SWITCH", "OPEN"))
            ax.annotate(f"I + S + O: {management:g} s", xy=(management / 2, -.19), xytext=(10, -.60),
                        fontsize=8, arrowprops={"arrowstyle": "-", "lw": .65}, annotation_clip=False)
        ax.set(yticks=[0, 1], yticklabels=["Multi", "Next"], ylim=(-.72, 1.8), xlim=(-3, 89))
        ax.spines["left"].set_visible(False)
        ax.tick_params(axis="y", length=0)
        ax.set_xticks([0, 20, 30, 50, 70, 80, 85])
    axes[-1].set_xlabel("Time from C1 start (s); common ground PREPARE at [-2, 0]")
    handles = [Patch(facecolor=COLORS["NEXT"]), Patch(facecolor=COLORS["MULTI"]),
               Patch(facecolor="#46515B"), Patch(facecolor="#C9B7D8")]
    fig.legend(handles, ["Next payload", "Multi payload", "Online I / S / O / R", "PREPARE"],
               loc="lower center", bbox_to_anchor=(.5, .045), ncol=4, frameon=False, fontsize=7.5)
    footer(fig)
    save(fig, folder, "02_timeline")


def preparation(result, field):
    return sum(float(e["usage"][field]) for e in result["events"]
               if e["type"] == "STEP_COMPLETED" and e["action"] == "PREPARE")


def breakdown(rows, results, folder):
    fig, axes = plt.subplots(2, 2, figsize=(7.16, 5.5))
    fig.subplots_adjust(left=.09, right=.98, bottom=.13, top=.91, hspace=.75, wspace=.33)
    x, labels = np.arange(4), ["X\nNext", "X\nMulti", "Y\nNext", "Y\nMulti"]
    warm = np.array([preparation(results[k], "groundCpu") for k in ORDER])
    ground = np.array([float(rows[k]["groundCpuSeconds"]) for k in ORDER]) - warm
    space = np.array([float(rows[k]["spacecraftCpuSeconds"]) for k in ORDER])
    ax = axes[0, 0]
    ax.bar(x, warm, .60, color=PALE, edgecolor=GRAY, hatch="..", label="Common PREPARE (GS)")
    ax.bar(x, ground, .60, bottom=warm, color="#56B4E9", label="Online GS CPU")
    bars = ax.bar(x, space, .60, bottom=warm+ground, color="#D55E00", hatch="//", label="Spacecraft CPU")
    ax.bar_label(bars, labels=[f"{v:.2f}" for v in warm+ground+space], padding=3, fontsize=8)
    ax.set(ylabel="CPU time (s)", ylim=(0, 1.3))
    ax.set_title("(a) CPU by endpoint", loc="left", fontweight="bold")
    ax.legend(loc="upper right", frameon=False, fontsize=6.8)
    ax = axes[0, 1]
    up = np.array([float(rows[k]["uplinkManagementBytes"]) for k in ORDER])
    down = np.array([float(rows[k]["downlinkManagementBytes"]) for k in ORDER])
    ax.bar(x, up, .60, color="#0072B2", label="Uplink")
    bars = ax.bar(x, down, .60, bottom=up, color="#E69F00", hatch="//", label="Downlink")
    ax.bar_label(bars, labels=[f"{v:.0f}" for v in up+down], padding=3, fontsize=8)
    ax.set(ylabel="Management traffic (bytes)", ylim=(0, 1550))
    ax.set_title("(b) Bytes by direction", loc="left", fontweight="bold")
    ax.legend(loc="upper left", frameon=False, fontsize=7)
    ax = axes[1, 0]
    bars = ax.bar(x, [float(rows[k]["managementRadioSeconds"]) for k in ORDER], .60,
                  color=[COLORS[k.split("-")[1]] for k in ORDER], edgecolor=INK, linewidth=.4)
    for i, bar in enumerate(bars):
        if ORDER[i].endswith("MULTI"):
            bar.set_hatch("//")
    ax.bar_label(bars, fmt="%.1f", padding=3, fontsize=8)
    ax.set(ylabel="Management radio occupancy (s)", ylim=(0, 3.8))
    ax.set_title("(c) Radio occupancy", loc="left", fontweight="bold")
    ax = axes[1, 1]
    prep = np.array([preparation(results[k], "elapsed") for k in ORDER])
    other = np.array([float(rows[k]["operationElapsedSeconds"]) for k in ORDER]) - prep
    ax.bar(x, prep, .60, color=PALE, edgecolor=GRAY, hatch="..", label="Common PREPARE")
    bars = ax.bar(x, other, .60, bottom=prep, color="#74818D", label="Online operations")
    ax.bar_label(bars, labels=[f"{v:g}" for v in prep+other], padding=3, fontsize=8)
    ax.set(ylabel="Operation elapsed sum (s)", ylim=(0, 15))
    ax.set_title("(d) Operation duration", loc="left", fontweight="bold")
    ax.legend(loc="upper left", frameon=False, fontsize=6.8)
    for ax in axes.flat:
        ax.set(xticks=x, xticklabels=labels)
        ax.grid(axis="y")
    footer(fig)
    save(fig, folder, "03_cost_breakdown")


def cumulative(results, folder):
    fig, axes = plt.subplots(2, 2, figsize=(7.16, 5.0), sharex=True)
    fig.subplots_adjust(left=.09, right=.98, bottom=.13, top=.87, hspace=.38, wspace=.24)
    for col, seq in enumerate(("X", "Y")):
        for ax in axes[:, col]:
            for c in windows(results[seq + "-NEXT"]):
                ax.axvspan(c["start"], c["end"], color=PALE, zorder=0)
            ax.grid(axis="y")
            ax.set(xlim=(0, 89), xticks=[0, 20, 50, 85])
        axes[0, col].set_title(f"{seq}: A-B-{'A' if seq == 'X' else 'B'}", loc="left", fontweight="bold")
        axes[0, col].set(ylim=(-.08, 3.4), yticks=[0, 1, 2, 3])
        axes[1, col].set(ylim=(0, 48), xlabel="Time from C1 start (s)")
        for plan in ("NEXT", "MULTI"):
            r = results[seq + "-" + plan]
            epoch = origin(r)
            style_args = {"color": COLORS[plan], "linewidth": 2 if plan == "NEXT" else 1.5,
                          "linestyle": "-" if plan == "NEXT" else (0, (4, 3))}
            served = {c["contactId"]: c["served"] for c in r["metrics"]["contacts"]}
            t, n = [0], [0]
            for c in windows(r):
                t.append(c["end"])
                n.append(n[-1] + int(served[c["id"]]))
            axes[0, col].step(t, n, where="post", **style_args)
            axes[0, col].plot(t[-1], n[-1], "o" if plan == "NEXT" else "s", color=COLORS[plan], ms=4, fillstyle="none")
            t, total = [0], [0.0]
            for e in r["events"]:
                if e["type"] == "PAYLOAD_TRANSFERRED":
                    t.append(seconds(e["at"], epoch))
                    total.append(total[-1] + float(e["usefulTime"]))
            t.append(seconds(r["endedAt"], epoch))
            total.append(total[-1])
            axes[1, col].step(t, total, where="post", label=plan.title(), **style_args)
    axes[0, 0].set_ylabel("Served contacts at closure")
    axes[1, 0].set_ylabel("Cumulative useful time (s)")
    handles, _ = axes[1, 0].get_legend_handles_labels()
    fig.legend(handles, ["Next-contact-aware plan", "Multi-contact plan"], loc="upper center",
               bbox_to_anchor=(.5, .98), ncol=2, frameon=False)
    footer(fig)
    save(fig, folder, "04_cumulative")


def csv_file(path, rows):
    with path.open("w", newline="", encoding="utf-8") as out:
        writer = csv.DictWriter(out, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def export_tables(out, rows, results):
    csv_file(out / "data" / "metrics.csv", [rows[k] for k in ORDER])
    contacts, operations = [], []
    for k in ORDER:
        contacts.extend({"scenario": k, **{a: b for a, b in c.items() if a != "failureReasons"},
                         "failureReasons": ";".join(c["failureReasons"])} for c in results[k]["metrics"]["contacts"])
        for start, end, e in operation_intervals(results[k]):
            operations.append({"scenario": k, "operationId": e["operationId"], "action": e["action"],
                               "contactId": e["contactId"] or "", "startSeconds": start, "endSeconds": end,
                               "outcome": e["type"], **e["usage"], "createdKeys": e["createdKeys"]})
    csv_file(out / "data" / "contacts.csv", contacts)
    csv_file(out / "data" / "operations.csv", operations)
    lines = [
        r"% Requires no extra package; all values are loaded from simulator output.",
        r"\begin{table}[t]", r"\centering",
        r"\caption{Synthetic witness results with common preparation included.}",
        r"\label{tab:security-witness}", r"\small", r"\setlength{\tabcolsep}{3pt}",
        r"\begin{tabular}{llrrrr}", r"\hline",
        r"Case & Plan & Served & Useful (s) & CPU (s) & Bytes \\",
        r"\hline",
    ]
    for k in ORDER:
        s = rows[k]
        lines.append(f"{k[0]} & {k.split('-')[1].title()} & {s['served']} & "
                     f"{s['usefulSeconds']:g} & {s['cpuSeconds']:.2f} & {s['managementBytes']} " + r"\\")
    lines += [r"\hline", r"\end{tabular}", r"\end{table}", ""]
    (out / "latex" / "results_table.tex").write_text("\n".join(lines), encoding="utf-8")
    lines = [
        r"% Add \usepackage{graphicx} to the main document preamble.",
        r"% Upload the simulation-figures folder at the Overleaf project root.",
        r"% Include with \input{simulation-figures/latex/figures.tex}.",
        r"\providecommand{\securityfigdir}{simulation-figures/figures}",
    ]
    for name in NAMES:
        lines += [r"\begin{figure*}[t]", r"\centering",
                  rf"\includegraphics[width=\textwidth]{{\securityfigdir/{name}.pdf}}",
                  r"\caption{" + CAPTIONS[name] + "}",
                  r"\label{fig:security-" + name.replace("_", "-") + "}",
                  r"\end{figure*}", ""]
    (out / "latex" / "figures.tex").write_text("\n".join(lines), encoding="utf-8")
    (out / "captions.txt").write_text("\n\n".join(NAMES[n] + "\n" + CAPTIONS[n] for n in NAMES) + "\n", encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="WitnessSimulationMain output directory")
    parser.add_argument("--output", type=Path, default=ROOT / "docs" / "simulation-figures")
    parser.add_argument("--reference", type=Path, default=ROOT / "docs" / "formal-cost-model-and-horizon" / "verification.json")
    args = parser.parse_args()
    source, out = args.input.resolve(), args.output.resolve()
    summary = read_json(source / "summary.json")
    results = {name: read_json(source / f"{name}.json") for name in ORDER}
    rows, checks = validate(summary, results, read_json(args.reference))
    for part in ("figures", "data", "source", "latex"):
        (out / part).mkdir(parents=True, exist_ok=True)
    inputs = {}
    for name in ("summary.json", *(f"{k}.json" for k in ORDER)):
        destination = out / "source" / name
        if (source / name).resolve() != destination.resolve():
            shutil.copyfile(source / name, destination)
        inputs[name] = sha(destination)
    style()
    outcomes(rows, out / "figures")
    timeline(results, out / "figures")
    breakdown(rows, results, out / "figures")
    cumulative(results, out / "figures")
    export_tables(out, rows, results)
    manifest = {
        "schema": 1, "provenance": "SYNTHETIC_ASSUMPTION",
        "plans": "Predefined; no optimizer or automatic policies",
        "input_sha256": inputs, "reference_sha256": sha(args.reference),
        "plot_script_sha256": sha(Path(__file__)),
        "environment": {"python": platform.python_version(), "matplotlib": matplotlib.__version__, "numpy": np.__version__},
        "accounting": summary["accounting"], "validation": checks,
        "formats": ["vector PDF with embedded TrueType fonts", "SVG with editable text", "600 dpi PNG"],
        "cumulative_service_convention": "Credit a served contact at CONTACT_ENDED; not at first service threshold crossing",
        "limitations": ["Four deterministic executions; no statistical confidence intervals",
                        "Not ISS measurements and not a general policy benchmark",
                        "Early activation is one predefined plan; staged/deferred activation is not plotted",
                        "No compromise-risk or universal security-improvement claim"],
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"validated": checks, "output": str(out), "figures": len(NAMES)}, indent=2))


if __name__ == "__main__":
    main()
