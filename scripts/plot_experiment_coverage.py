#!/usr/bin/env python3
"""Render registered experiment coverage, without substituting performance metrics.

python scripts/plot_experiment_coverage.py --plan target/search-scaling-grid-v2/plan.json --registry target/search-scaling-grid-v2/registry.json --output docs/search-scaling/coverage-figures

The input files are read-only. The output directory must be new or empty.
Only the completed registry's four explicit coverage statuses are accepted;
unknown, running, or unresolved statuses cause an error instead of a guess.
"""
from __future__ import annotations

import argparse
from collections import Counter
import csv
import hashlib
from itertools import product
import json
import os
from pathlib import Path
import platform
import sys

ROOT = Path(__file__).resolve().parents[1]
CONTACT_COUNTS = (3, 6, 12, 24, 46)
WORKLOADS = ("light", "moderate", "saturated")
CRYPTOPERIODS = (5400, 7200)
POLICY_COLUMNS = (("fixed", 1800, None), ("fixed", 3600, None), ("fixed", 7200, None),
                  ("percontact", None, None), ("next", None, 1),
                  ("multi", None, 2), ("multi", None, 3))
STATUSES = {
    "FINISHED": ("F", "#0072B2", "Finished"),
    "SEARCH_INCOMPLETE": ("L", "#E69F00", "Search incomplete (budget)"),
    "NOT_RUN_AFTER_BUDGET_LIMIT": ("S", "#CC79A7", "Not run after budget limit"),
    "NOT_REQUESTED": ("–", "#E5E7EB", "Not requested"),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def validated_rows(plan, registry):
    planned = plan["cases"]
    require(len(planned) == 210, "Expected the full registered grid of 210 configurations")
    ids = [case["id"] for case in planned]
    require(len(set(ids)) == len(ids), "Duplicate plan case IDs")
    require(set(registry["cases"]) == set(ids), "Registry and plan case IDs differ")
    expected = set(product(CRYPTOPERIODS, CONTACT_COUNTS, WORKLOADS, POLICY_COLUMNS))
    observed, rows = set(), []
    for case in planned:
        config = case["configuration"]
        column = (config["policy"], config["fixed_period"], config["horizon"])
        coordinate = (config["cryptoperiod"], config["contact_count"], config["traffic"], column)
        require(coordinate in expected and coordinate not in observed, f"Unexpected or duplicate grid cell: {case['id']}")
        observed.add(coordinate)
        actual = registry["cases"][case["id"]]
        status = actual["status"]
        require(status in STATUSES, f"Unresolved or unknown coverage status for {case['id']}: {status}")
        require(actual["firstStage"] == case["firstStage"], f"Stage mismatch for {case['id']}")
        attempts = actual["attempts"]
        if status in ("NOT_REQUESTED", "NOT_RUN_AFTER_BUDGET_LIMIT"):
            require(not attempts, f"Unrun case has attempts: {case['id']}")
        else:
            require(attempts and attempts[-1]["status"] == status, f"Final attempt/status mismatch: {case['id']}")
        require(all(attempt["status"] in ("FINISHED", "SEARCH_INCOMPLETE") for attempt in attempts),
                f"Unknown or failed attempt status for {case['id']}")
        rows.append({"case_id": case["id"], "cryptoperiod_s": config["cryptoperiod"],
                     "contacts": config["contact_count"], "workload": config["traffic"],
                     "policy": config["policy"], "fixed_period_s": config["fixed_period"],
                     "horizon": config["horizon"], "first_stage": case["firstStage"],
                     "status": status, "code": STATUSES[status][0], "attempts": len(attempts),
                     "last_attempt_tier": attempts[-1]["tier"] if attempts else None})
    require(observed == expected, "Missing registered grid coordinates")
    return rows


def coverage_counts(rows, registry):
    totals = Counter(row["status"] for row in rows)
    attempts = [attempt for case in registry["cases"].values() for attempt in case["attempts"]]
    return {"planned_configurations": len(rows), "requested_configurations": len(rows) - totals["NOT_REQUESTED"],
            "not_requested_configurations": totals["NOT_REQUESTED"],
            "status_counts": {status: totals[status] for status in STATUSES},
            "by_cryptoperiod": {str(cp): dict(Counter(row["status"] for row in rows if row["cryptoperiod_s"] == cp))
                                for cp in CRYPTOPERIODS},
            "attempts": len(attempts), "attempts_by_tier": dict(Counter(str(a["tier"]) for a in attempts)),
            "attempt_status_counts": dict(Counter(a["status"] for a in attempts))}


def render(rows, counts, output):
    local_packages = ROOT / ".tools" / "figure-python"
    if local_packages.exists():
        sys.path.insert(0, str(local_packages))
    os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.patches import Patch, Rectangle

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "axes.titlesize": 9,
                         "xtick.labelsize": 8, "ytick.labelsize": 8, "legend.fontsize": 8,
                         "pdf.fonttype": 42, "ps.fonttype": 42, "svg.fonttype": "none",
                         "savefig.bbox": None})
    fig, axes = plt.subplots(1, 2, figsize=(7.16, 6.3), sharey=True)
    fig.subplots_adjust(left=.145, right=.985, top=.88, bottom=.23, wspace=.10)
    fig.suptitle("Coverage of the registered experiment grid", y=.975, fontsize=9)
    fig.text(.5, .94, f"{counts['requested_configurations']} requested / {counts['planned_configurations']} registered; "
             f"{counts['not_requested_configurations']} not requested", ha="center", fontsize=8)
    row_coordinates = list(product(CONTACT_COUNTS, WORKLOADS))
    lookup = {(row["cryptoperiod_s"], row["contacts"], row["workload"],
               (row["policy"], row["fixed_period_s"], row["horizon"])): row for row in rows}
    column_labels = ("Fixed\nT =\n1800", "Fixed\nT =\n3600", "Fixed\nT =\n7200", "Per\ncontact\nJIT",
                     "Next\nH=1", "Multi\nH=2", "Multi\nH=3")
    for panel, (axis, cp) in enumerate(zip(axes, CRYPTOPERIODS)):
        cells = [[lookup[(cp, n, workload, column)] for column in POLICY_COLUMNS] for n, workload in row_coordinates]
        for y, row in enumerate(cells):
            for x, cell in enumerate(row):
                # Explicit vector patches keep the PDF/SVG grid free of embedded raster images.
                axis.add_patch(Rectangle((x - .5, y - .5), 1, 1,
                                         facecolor=STATUSES[cell["status"]][1], edgecolor="none"))
                axis.text(x, y, cell["code"], ha="center", va="center", fontsize=8,
                          color="white" if cell["status"] == "FINISHED" else "#17212B")
        axis.set_xlim(-.5, len(POLICY_COLUMNS) - .5)
        axis.set_ylim(len(row_coordinates) - .5, -.5)
        axis.set_title(f"({'ab'[panel]}) Cryptoperiod = {cp} s", pad=8)
        axis.set_xticks(range(len(POLICY_COLUMNS)), column_labels)
        axis.set_yticks(range(len(row_coordinates)), [f"{n} {workload}" for n, workload in row_coordinates])
        axis.tick_params(length=0, pad=5)
        axis.set_xticks([x - .5 for x in range(8)], minor=True)
        axis.set_yticks([y - .5 for y in range(16)], minor=True)
        axis.grid(which="minor", color="white", linewidth=.5)
        axis.tick_params(which="minor", length=0)
        for boundary in (2.5, 5.5, 8.5, 11.5):
            axis.axhline(boundary, color="white", linewidth=1.5)
        for spine in axis.spines.values():
            spine.set_visible(False)
    axes[0].set_ylabel("Contacts N / synthetic workload", fontsize=8)
    fig.legend(handles=[Patch(facecolor=color, label=f"{code}  {label}") for code, color, label in STATUSES.values()],
               loc="lower center", bbox_to_anchor=(.53, .078), ncol=2, frameon=False,
               handlelength=1.2, columnspacing=2.0, labelspacing=.7)
    fig.text(.5, .033, "Status only: unavailable performance results are not replaced by zero.", ha="center", fontsize=8)
    files = []
    for extension in ("pdf", "svg", "png"):
        filename = f"coverage.{extension}"
        fig.savefig(output / filename, dpi=300, bbox_inches=None)
        files.append(filename)
    plt.close(fig)
    return {"matplotlib": matplotlib.__version__, "files": files, "width_inches": 7.16,
            "height_inches": 6.3, "png_dpi": 300, "font_points": [8, 9],
            "pdf_fonttype": 42, "svg_text": "editable"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--registry", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    plan_path, registry_path, output = args.plan.resolve(), args.registry.resolve(), args.output.resolve()
    require(not output.exists() or not any(output.iterdir()), "Output directory must be new or empty")
    plan, registry = read(plan_path), read(registry_path)
    rows = validated_rows(plan, registry)
    counts = coverage_counts(rows, registry)
    output.mkdir(parents=True, exist_ok=True)
    rendering = render(rows, counts, output)
    with (output / "coverage.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    (output / "counts.json").write_text(json.dumps(counts, indent=2), encoding="utf-8")
    budgets = plan["identity"]["decisionBudgets"]
    budget_text = "; ".join(f"tier {b['tier']}: {b['maxNodes']:,} nodes / {b['maxSeconds']} s per decision" for b in budgets)
    caption = (f"Coverage of the registered 210-configuration experiment grid: {counts['requested_configurations']} requested "
               f"and {counts['not_requested_configurations']} not requested. Each panel fixes cryptoperiod (5400 or 7200 s); "
               "rows use the same increasing contact prefixes and synthetic light/moderate/saturated traffic workloads. "
               "Columns identify FixedPeriod T in seconds, PerContact with just-in-time preparation, and Next/Multi horizon H. "
               "F = FINISHED actual execution; L = SEARCH_INCOMPLETE after the registered decision budgets; "
               "S = NOT_RUN_AFTER_BUDGET_LIMIT, a longer prefix omitted after a predecessor's two-tier search failure; "
               "– = NOT_REQUESTED in the selected stages. "
               f"Final configuration counts: {counts['status_counts']}. Search budgets: {budget_text}. "
               "The map reports configuration coverage, not attempt count or performance. Unavailable metrics are not zeros; "
               "FINISHED does not assert global policy optimality or lower compromise risk.\n")
    (output / "captions.txt").write_text(caption, encoding="utf-8")
    provenance = {"schema_version": "1.0", "python_version": platform.python_version(),
                  "sources": {str(path): sha(path) for path in (plan_path, registry_path, Path(__file__).resolve())},
                  "build_identity": plan["identity"], "counts": counts, "rendering": rendering,
                  "caption": caption.strip(), "status_mapping": {status: {"code": values[0], "label": values[2]}
                                                                    for status, values in STATUSES.items()},
                  "validation": ["Exactly 210 unique full-grid coordinates and matching plan/registry IDs.",
                                 "Only the four explicit final coverage statuses are accepted.",
                                 "Unrun configurations have no attempts; executed configurations agree with their final attempt.",
                                 "No simulator, metric substitution, or search execution is performed."]}
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(counts, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
