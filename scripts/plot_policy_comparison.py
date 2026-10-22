"""Plot completed four-policy X/Y experiments for research figures.

Run after FourPolicyExperimentMain has written its manifest and summary:
    python scripts/plot_policy_comparison.py --input target/four-policy-run --output target/policy-figures

The output must be new or empty. Each initialization mode needs all eight
FINISHED X/Y x FixedPeriod/PerContact/Next/Multi cases. An incomplete mode is
explicitly skipped and recorded in provenance.json; it is never plotted as zero.
Exports: vector PDF, editable-text SVG, PNG at 300 dpi, metrics.csv and captions.
The usefulSecureTime and controlCpu CSV columns are seconds; counts are exact.
No policy decisions, expected numerical results or simulation are implemented here.
"""
from __future__ import annotations

import argparse
import csv
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import sys
import textwrap

ROOT = Path(__file__).resolve().parents[1]
LOCAL_PACKAGES = ROOT / ".tools" / "figure-python"
if LOCAL_PACKAGES.exists():
    sys.path.insert(0, str(LOCAL_PACKAGES))
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch
from matplotlib.ticker import MaxNLocator

POLICIES = ("FIXED_PERIOD", "PER_CONTACT", "NEXT", "MULTI")
MODES = ("prepared", "autonomous")
COLORS = {"FIXED_PERIOD": "#737373", "PER_CONTACT": "#CC79A7", "NEXT": "#0072B2", "MULTI": "#D55E00"}
INK, GRAY = "#243746", "#6F7D87"
METRICS = (
    ("servedContacts", "(a) Service coverage", "Served contacts", True),
    ("usefulSecureTime", "(b) Useful transmission", "Useful secure time (s)", False),
    ("controlCpu", "(c) Security CPU", "Control CPU, GS + SC (s)", False),
    ("managementBytes", "(d) Management traffic", "Uplink + downlink (bytes)", True),
    ("newKeys", "(e) Material creation", "New directional keys", True),
)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def number(value, label: str) -> Decimal:
    if isinstance(value, bool) or value is None:
        raise ValueError(f"{label}: expected a nonnegative number")
    result = Decimal(str(value))
    if not result.is_finite() or result < 0:
        raise ValueError(f"{label}: expected a finite nonnegative number")
    return result


def duration(value, label: str) -> Decimal:
    """Jackson Duration is normally decimal seconds; ISO-8601 durations also work."""
    if isinstance(value, str) and value.startswith("P"):
        match = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?", value)
        if not match or not any(match.groups()):
            raise ValueError(f"{label}: unsupported duration {value!r}")
        days, hours, minutes, seconds = (Decimal(v or 0) for v in match.groups())
        return days * 86400 + hours * 3600 + minutes * 60 + seconds
    return number(value, label)


def equal(actual, expected, label):
    if actual != expected:
        raise ValueError(f"{label}: actual={actual}, expected={expected}")


class Inputs:
    def __init__(self, root: Path):
        self.root = root
        self.hashes = {}

    def read(self, name):
        relative = Path(name)
        path = (self.root / relative).resolve()
        if relative.is_absolute() or not path.is_relative_to(self.root):
            raise ValueError(f"Input reference must stay inside the experiment directory: {name}")
        content = path.read_bytes()
        self.hashes[path.relative_to(self.root).as_posix()] = hashlib.sha256(content).hexdigest()
        return json.loads(content.decode("utf-8"), parse_float=Decimal)


def collect_cases(summary):
    if not isinstance(summary, dict):
        raise ValueError("Expected FourPolicyExperimentMain summary.json object")
    cases = {}
    for key, row in summary.items():
        match = re.fullmatch(r"([XY])-(prepared|autonomous)", row.get("fixture", ""))
        if not match or row.get("policyName") not in POLICIES:
            continue
        sequence, mode = match.groups()
        identity = mode, sequence, row["policyName"]
        if identity in cases:
            raise ValueError(f"Duplicate experiment case: {identity}")
        cases[identity] = (key, row)
    return cases


def validate_case(inputs, name, row, model):
    metrics = row.get("finalMetrics")
    if not isinstance(metrics, dict) or row.get("partialProgress") is not None:
        raise ValueError(f"{name}: FINISHED case must have final metrics and no partial score")
    values = {}
    for field, _, _, integer in METRICS:
        parse = duration if field in ("usefulSecureTime", "controlCpu") else number
        values[field] = parse(metrics[field], f"{name}.{field}")
        if integer and values[field] != values[field].to_integral_value():
            raise ValueError(f"{name}.{field}: expected an integer count")

    actual = inputs.read(row["actualResultFile"])
    equal(actual["status"], "FINISHED", name + " actual status")
    if actual.get("partialProgress") is not None or not isinstance(actual.get("finalMetrics"), dict):
        raise ValueError(f"{name}: actual result is not finalized")
    collected = actual["finalMetrics"]
    costs = collected["controlCosts"]
    checks = {
        "servedContacts": number(collected["servedContacts"], name),
        "usefulSecureTime": duration(collected["usefulSecureTime"], name),
        "controlCpu": duration(costs["groundCpu"], name) + duration(costs["spacecraftCpu"], name),
        "managementBytes": number(costs["uplinkBytes"], name) + number(costs["downlinkBytes"], name),
        "newKeys": number(collected["createdKeys"], name),
    }
    events = inputs.read(row["actualEventsFile"])
    equal(sum(e["type"] == "SIMULATION_ENDED" for e in events), 1, name + " actual finalization count")
    event_checks = {
        "usefulSecureTime": sum((duration(e["usefulTime"], name) for e in events), Decimal(0)),
        "controlCpu": sum((duration(e["usage"]["groundCpu"], name) + duration(e["usage"]["spacecraftCpu"], name)
                           for e in events), Decimal(0)),
        "managementBytes": sum((number(e["usage"]["uplinkBytes"], name) + number(e["usage"]["downlinkBytes"], name)
                                for e in events), Decimal(0)),
        "newKeys": sum((number(e["createdKeys"], name) for e in events), Decimal(0)),
    }
    contacts = model["scenario"]["contacts"]
    served = 0
    for contact in contacts:
        contact_id = contact["window"]["id"]
        matching = [e for e in events if e.get("contactId") == contact_id]
        up = sum(number(e["uplinkPayloadBytes"], name) for e in matching)
        down = sum(number(e["downlinkPayloadBytes"], name) for e in matching)
        served += up >= number(contact["payload"]["minimumUplinkBytes"], name) and down >= number(
            contact["payload"]["minimumDownlinkBytes"], name)
    event_checks["servedContacts"] = Decimal(served)
    for field in values:
        equal(values[field], checks[field], f"{name}: summary vs actual {field}")
        equal(values[field], event_checks[field], f"{name}: summary vs journal {field}")
    return values


def formatted(value, integer=False):
    value = Decimal(str(value))
    if integer or value == value.to_integral_value():
        return str(int(value))
    return f"{float(value):.3g}"


def labels_from_configuration(manifest, rows):
    options = manifest["options"]
    period = duration(options["fixedPeriod"], "manifest.options.fixedPeriod")
    preparation = options["perContactPreparation"]
    preparation_label = {"JUST_IN_TIME": "JIT", "CONTACT_ONLY": "contact-only"}.get(preparation)
    if preparation_label is None:
        raise ValueError(f"Unknown PerContact preparation configuration: {preparation}")
    horizons = {}
    for policy in POLICIES:
        policy_rows = [r for r in rows if r["policyName"] == policy]
        configurations = [r["policyConfiguration"] for r in policy_rows]
        if any(c != configurations[0] for c in configurations):
            raise ValueError(f"X and Y use different {policy} configurations")
        observed_horizons = {r["horizon"] for r in policy_rows}
        if len(observed_horizons) != 1:
            raise ValueError(f"Inconsistent horizon for {policy}")
        horizons[policy] = observed_horizons.pop()
        if policy == "FIXED_PERIOD":
            equal(duration(configurations[0]["period"], policy), period, "FixedPeriod manifest/configuration")
        elif policy == "PER_CONTACT":
            equal(configurations[0]["advancePreparation"], preparation, "PerContact manifest/configuration")
        else:
            equal(configurations[0]["horizon"], horizons[policy], policy + " horizon configuration")
    equal(horizons["MULTI"], options["multiHorizon"], "Multi manifest horizon")
    labels = {
        "FIXED_PERIOD": f"Fixed period (T={formatted(period)} s)",
        "PER_CONTACT": f"Per-contact ({preparation_label})",
        "NEXT": f"Next (H={horizons['NEXT']})",
        "MULTI": f"Multi (H={horizons['MULTI']})",
    }
    return labels, {"fixedPeriodSeconds": str(period), "perContactPreparation": preparation, "horizons": horizons}


def configure_style():
    plt.rcParams.update({
        "font.family": "DejaVu Sans", "font.size": 8, "axes.titlesize": 9,
        "axes.labelsize": 8, "xtick.labelsize": 8, "ytick.labelsize": 7,
        "legend.fontsize": 7, "axes.spines.top": False, "axes.spines.right": False,
        "axes.edgecolor": GRAY, "axes.labelcolor": INK, "text.color": INK,
        "xtick.color": INK, "ytick.color": INK, "axes.linewidth": .6,
        "grid.color": "#E1E6EB", "grid.linewidth": .5, "axes.axisbelow": True,
        "pdf.fonttype": 42, "ps.fonttype": 42, "svg.fonttype": "none",
        "svg.hashsalt": "contactaware-four-policy-comparison", "savefig.facecolor": "white",
    })


def plot_mode(mode, metrics, labels, models, out):
    fig, axes = plt.subplots(2, 3, figsize=(7.16, 5.8))
    fig.subplots_adjust(left=.085, right=.985, bottom=.125, top=.82, hspace=.65, wspace=.43)
    mode_label = "Common preparation" if mode == "prepared" else "Autonomous preparation"
    title = f"Four-policy comparison: {mode_label.lower()}"
    fig.suptitle(title, x=.5, y=.975, fontsize=12, fontweight="bold")
    fig.legend([Patch(facecolor=COLORS[p]) for p in POLICIES], [labels[p] for p in POLICIES],
               ncol=2, loc="upper center", bbox_to_anchor=(.5, .94), frameon=False, columnspacing=2.5)
    maximum_contacts = max(len(m["scenario"]["contacts"]) for m in models)
    for ax, (field, panel, ylabel, integer) in zip(axes.flat, METRICS):
        maximum = max(float(metrics[sequence, p][field]) for sequence in ("X", "Y") for p in POLICIES)
        for index, policy in enumerate(POLICIES):
            positions = [x + (index - 1.5) * .18 for x in (0, 1)]
            exact = [metrics[sequence, policy][field] for sequence in ("X", "Y")]
            bars = ax.bar(positions, [float(v) for v in exact], width=.155, color=COLORS[policy],
                          edgecolor="white", linewidth=.35)
            # Narrow grouped bars need vertical labels for multi-character values.
            rotation = 90 if field in ("controlCpu", "managementBytes") or (integer and maximum >= 10) else 0
            ax.bar_label(bars, labels=[formatted(v, integer) for v in exact], padding=2,
                         fontsize=6.5, rotation=rotation)
        ax.set_title(panel, loc="left", pad=9, fontweight="bold")
        ax.set_ylabel(ylabel, labelpad=4)
        ax.set_xticks([0, 1], ["X", "Y"])
        ax.set_xlim(-.55, 1.55)
        ax.set_ylim(0, max(maximum_contacts * 1.22, maximum * 1.22) if field == "servedContacts" else max(1, maximum * 1.28))
        ax.grid(axis="y")
        ax.tick_params(axis="x", length=0)
        if integer:
            ax.yaxis.set_major_locator(MaxNLocator(nbins=4, integer=True))
        else:
            ax.yaxis.set_major_locator(MaxNLocator(nbins=4))
    note_ax = axes.flat[5]
    note_ax.axis("off")
    synthetic = all(m["scenario"]["costs"]["provenance"] == "SYNTHETIC_ASSUMPTION" for m in models)
    provenance_label = "Synthetic model inputs" if synthetic else "Reported model inputs"
    routes = {sequence: [c["window"]["groundStationId"] for c in model["scenario"]["contacts"]]
              for sequence, model in zip(("X", "Y"), models)}
    note = [f"{sequence}: {' → '.join(routes[sequence])}" for sequence in ("X", "Y")]
    note += ["", provenance_label,
            "All eight runs finished.", "One run per policy/sequence.", "",
            "CPU: security management.", "Search time is separate.",
            "All preparation is included.", "No compromise-risk claim."]
    note_ax.text(0, .98, "\n".join(note), va="top", fontsize=7.3, linespacing=1.5, color=GRAY)
    accounting = "Common PREPARE included once" if mode == "prepared" else "Policy-selected PREPARE included"
    fig.text(.5, .055, f"{provenance_label}  |  {accounting}", ha="center", fontsize=7.2, color=GRAY)
    fig.text(.5, .026, "FINISHED describes actual execution; it is not a global-optimality or security guarantee.",
             ha="center", fontsize=6.8, color=GRAY)
    stem = "01_prepared" if mode == "prepared" else "02_autonomous"
    files = []
    for extension in ("pdf", "svg", "png"):
        filename = f"{stem}.{extension}"
        metadata = {"Title": title, "Creator": "ContactAwareSS plot_policy_comparison.py"}
        if extension == "pdf":
            metadata.update(CreationDate=None, ModDate=None)
        elif extension == "svg":
            metadata.update(Date=None)
        fig.savefig(out / filename, dpi=300, metadata=metadata)
        files.append(filename)
    plt.close(fig)
    sequence_descriptions = " and ".join(f"{sequence} ({'-'.join(routes[sequence])})" for sequence in ("X", "Y"))
    profile_names = ", ".join(sorted({m["scenario"]["costs"]["name"] for m in models}))
    caption = (
        f"{mode_label} for {sequence_descriptions} contact sequences. "
        f"{provenance_label}; cost profile: {profile_names}. "
        f"Policies: {', '.join(labels[p] for p in POLICIES)}. "
        "Panels report served contacts, useful secure time, GS+SC security-management CPU, "
        "uplink+downlink management bytes and newly created directional keys. "
        f"{accounting}. Values are reconciled against the actual event journals and final results. "
        "Each bar represents one completed deterministic run; no statistical intervals are implied. "
        "Search time is excluded from security CPU. Finished execution is distinct from completeness "
        "of a finite horizon search, global policy optimality and compromise-risk reduction."
    )
    return files, caption


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", type=Path, required=True, help="FourPolicyExperimentMain output directory")
    parser.add_argument("--output", type=Path, required=True, help="New or empty figure directory")
    args = parser.parse_args()
    source, out = args.input.resolve(), args.output.resolve()
    if not source.is_dir():
        raise ValueError("Input must be an experiment directory")
    if out.exists() and (not out.is_dir() or any(out.iterdir())):
        raise ValueError("Output directory must be new or empty")
    inputs = Inputs(source)
    manifest, summary = inputs.read("manifest.json"), inputs.read("summary.json")
    if manifest.get("schemaVersion") != "1.0":
        raise ValueError("Unsupported FourPolicyExperimentMain manifest schema")
    cases = collect_cases(summary)
    models = {}
    for filename in manifest["fixtureModels"]:
        model = inputs.read(filename)
        if model["id"] in models:
            raise ValueError(f"Duplicate fixture model: {model['id']}")
        models[model["id"]] = model
    mode_reports, prepared, checks = {}, {}, {}
    for mode in MODES:
        missing, unfinished = [], []
        rows, values = [], {}
        for sequence in ("X", "Y"):
            for policy in POLICIES:
                entry = cases.get((mode, sequence, policy))
                if entry is None:
                    missing.append(f"{sequence}/{policy}")
                elif entry[1].get("status") != "FINISHED":
                    unfinished.append(f"{entry[0]}: {entry[1].get('status', 'MISSING_STATUS')}")
        if missing or unfinished:
            mode_reports[mode] = {"status": "SKIPPED", "reason": "Requires all eight FINISHED X/Y policy cases",
                                  "missing": missing, "unfinished": unfinished}
            continue
        for sequence in ("X", "Y"):
            for policy in POLICIES:
                name, row = cases[mode, sequence, policy]
                model = models.get(row["fixture"])
                if model is None:
                    raise ValueError(f"Missing fixture model for {name}")
                values[sequence, policy] = validate_case(inputs, name, row, model)
                rows.append(row)
                checks[name] = "All five metrics agree between summary, actual result and journal"
        labels, configuration = labels_from_configuration(manifest, rows)
        prepared[mode] = (values, labels, [models[f"{sequence}-{mode}"] for sequence in ("X", "Y")])
        mode_reports[mode] = {"status": "VALIDATED", "cases": 8, "configuration": configuration}
    out.mkdir(parents=True, exist_ok=True)
    configure_style()
    table, captions, files = [], [], []
    for mode, (values, labels, mode_models) in prepared.items():
        generated, caption = plot_mode(mode, values, labels, mode_models, out)
        mode_reports[mode].update(status="RENDERED", files=generated, caption=caption)
        files.extend(generated)
        captions.append(textwrap.fill(caption, width=100))
        for sequence in ("X", "Y"):
            for policy in POLICIES:
                table.append({"initialization": mode, "sequence": sequence, "policy": policy, **values[sequence, policy]})
    if table:
        with (out / "metrics.csv").open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(table[0]))
            writer.writeheader()
            writer.writerows(table)
        files.append("metrics.csv")
    (out / "captions.txt").write_text("\n\n".join(captions) + ("\n" if captions else "No complete mode was plotted.\n"), encoding="utf-8")
    files.append("captions.txt")
    provenance = {
        "schemaVersion": "1.0", "sourceDirectory": str(source), "modes": mode_reports,
        "inputSha256": inputs.hashes, "scriptSha256": sha(Path(__file__)),
        "outputSha256": {name: sha(out / name) for name in files},
        "environment": {"python": platform.python_version(), "matplotlib": matplotlib.__version__},
        "validation": checks, "accounting": manifest.get("accounting"),
        "interpretation": manifest.get("interpretation"),
        "formats": ["Vector PDF with embedded TrueType fonts", "SVG with editable text", "PNG 300 dpi"],
        "metricUnits": {"servedContacts": "contacts", "usefulSecureTime": "seconds", "controlCpu": "seconds",
                        "managementBytes": "bytes", "newKeys": "directional keys"},
        "incompleteDataRule": "Skip the entire initialization mode unless all eight runs are FINISHED; never substitute zero",
    }
    (out / "provenance.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=False, default=str) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(out), "figures": len(prepared), "modes": mode_reports}, indent=2, default=str))


if __name__ == "__main__":
    main()
