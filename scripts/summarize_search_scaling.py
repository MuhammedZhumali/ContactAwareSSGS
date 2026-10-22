#!/usr/bin/env python3
"""Summarize bounded four-policy experiments without treating partial runs as scores.

python scripts/summarize_search_scaling.py --input target/run-a target/run-b --output target/scaling-summary --figures

The mandatory path uses only Python's standard library. --figures additionally
uses matplotlib (the repository's .tools/figure-python installation is supported).
No simulator or policy is executed. Final metrics are cross-checked against the
actual result; journal accounting remains the separate verify_policy_experiments.py audit.
Matching uses the full common scenario, handoff and execution environment. Actual
policy parameters identify separate variants; unrelated CLI flags are ignored.
Search budgets/options identify Next/Multi variants only. Replicates count once per matched cell;
inconsistent FINISHED replicate scores are excluded explicitly, never averaged.
"""
from __future__ import annotations

import argparse
import csv
from collections import defaultdict
from decimal import Decimal
import hashlib
from itertools import combinations
import json
import os
from pathlib import Path
import platform
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
POLICIES = ("FIXED_PERIOD", "PER_CONTACT", "NEXT", "MULTI")
METRICS = ("served_contacts", "useful_time_s", "control_cpu_s", "management_bytes", "new_keys")
DIAGNOSTICS = ("terminalPlans", "expandedNodes", "maxDepth", "generatedActions", "executedTransitions",
               "repeatedTransitions", "collapsedWaitTransitions", "preExecutionWaitSkips", "memoHits",
               "memoEntries", "memoStoreSkips", "boundPrunedNodes", "canonicalBoundPrunedNodes",
               "generationNanos", "executionNanos", "boundNanos", "rootCandidateCount")
D = Decimal


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value, label):
    require(value is not None and not isinstance(value, bool), f"{label}: missing/non-numeric value")
    result = D(str(value))
    require(result.is_finite() and result >= 0, f"{label}: expected finite nonnegative value")
    return result


def duration(value, label):
    if isinstance(value, str) and value.startswith("P"):
        match = re.fullmatch(r"P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?", value)
        require(match is not None and any(match.groups()), f"{label}: unsupported duration {value}")
        days, hours, minutes, seconds = (D(v or 0) for v in match.groups())
        return days * 86400 + hours * 3600 + minutes * 60 + seconds
    return number(value, label)


def canonical(value):
    if isinstance(value, dict):
        return {key: canonical(item) for key, item in sorted(value.items())}
    if isinstance(value, (list, tuple)):
        return [canonical(item) for item in value]
    if isinstance(value, (int, float, D)) and not isinstance(value, bool):
        return {"$number": format(D(str(value)).normalize(), "f")}
    return value


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), default=str)


def fingerprint(value):
    return hashlib.sha256(encoded(canonical(value)).encode("utf-8")).hexdigest()


def common_scenario(scenario):
    """Canonicalize only schema-declared sets; contact/phase/action order stays meaningful.

    Java Set iteration order may vary between JVMs. Sorting every JSON array would
    incorrectly equate different contact schedules or operation phase sequences.
    """
    result = {key: value for key, value in scenario.items() if key != "name"}
    constraints = dict(result["constraints"])
    for field in ("authorizedContexts", "blockedKeyIds"):
        if field in constraints:
            require(isinstance(constraints[field], list), f"constraints.{field}: expected serialized set array")
            constraints[field] = sorted(constraints[field], key=lambda value: encoded(canonical(value)))
    result["constraints"] = constraints
    return result


class Sources:
    def __init__(self):
        self.hashes = {}

    def path(self, root, name):
        relative = Path(name)
        path = (root / relative).resolve()
        require(not relative.is_absolute() and path.is_relative_to(root), f"Unsafe input reference: {name}")
        return path

    def record(self, path):
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        self.hashes[str(path)] = digest.hexdigest()

    def read(self, root, name):
        path = self.path(root, name)
        content = path.read_bytes()
        self.hashes[str(path)] = hashlib.sha256(content).hexdigest()
        return json.loads(content.decode("utf-8-sig"), parse_float=D)


def final_metrics(summary, actual, name):
    finished = summary["status"] == "FINISHED"
    require(actual["status"] == summary["status"], f"{name}: actual/summary status mismatch")
    if not finished:
        require(summary.get("finalMetrics") is None and actual.get("finalMetrics") is None,
                f"{name}: incomplete run must not have final metrics")
        return dict.fromkeys(METRICS)
    require(summary.get("partialProgress") is None and actual.get("partialProgress") is None,
            f"{name}: FINISHED run has partial progress")
    stated = summary["finalMetrics"]
    result = {
        "served_contacts": number(stated["servedContacts"], name),
        "useful_time_s": duration(stated["usefulSecureTime"], name),
        "control_cpu_s": duration(stated["controlCpu"], name),
        "management_bytes": number(stated["managementBytes"], name),
        "new_keys": number(stated["newKeys"], name),
    }
    checked = actual["finalMetrics"]
    costs = checked["controlCosts"]
    expected = (number(checked["servedContacts"], name), duration(checked["usefulSecureTime"], name),
                duration(costs["groundCpu"], name) + duration(costs["spacecraftCpu"], name),
                number(costs["uplinkBytes"], name) + number(costs["downlinkBytes"], name),
                number(checked["createdKeys"], name))
    require(tuple(result.values()) == expected, f"{name}: summary/actual metric mismatch")
    for field in ("served_contacts", "management_bytes", "new_keys"):
        require(result[field] == result[field].to_integral_value(), f"{name}: fractional count {field}")
    return result


def collect(root, sources):
    manifest = sources.read(root, "manifest.json")
    summaries = sources.read(root, "summary.json")
    options = manifest["options"]
    models = {}
    for name in manifest["fixtureModels"]:
        model = sources.read(root, name)
        require(model["id"] not in models, f"Duplicate fixture model {model['id']}")
        models[model["id"]] = model
    runs, decisions, metadata = [], [], {}
    for name, summary in summaries.items():
        policy = summary["policyName"]
        require(policy in POLICIES, f"Unknown policy {policy}")
        model = models[summary["fixture"]]
        scenario = model["scenario"]
        physical_scenario = common_scenario(scenario)
        inputs = model.get("input", {})
        limits = options["limits"]
        configuration = summary["policyConfiguration"]
        fixed_period = duration(options["fixedPeriod"], "fixedPeriod")
        if policy == "FIXED_PERIOD":
            require(duration(configuration["period"], name) == fixed_period, f"{name}: FixedPeriod mismatch")
        if policy == "PER_CONTACT":
            require(configuration["advancePreparation"] == options["perContactPreparation"], f"{name}: PerContact mismatch")
        if policy in ("FIXED_PERIOD", "PER_CONTACT"):
            require(summary["horizon"] == 1, f"{name}: baseline admissibility horizon mismatch")
        if policy in ("NEXT", "MULTI"):
            require(summary["horizon"] == (1 if policy == "NEXT" else options["multiHorizon"]), f"{name}: horizon mismatch")
        matching = {
            "scenario": physical_scenario, "initialization": model["initialization"],
            "controllerHandoffAt": model["controllerHandoffAt"], "costsAtHandoff": model["costsAtHandoff"],
            "createdKeysAtHandoff": model["createdKeysAtHandoff"],
            "executionEnvironment": manifest.get("executionEnvironment"),
            "datasetSha256": inputs.get("datasetSha256"), "scheduleSha256": inputs.get("scheduleSha256"),
            "trafficWorkload": inputs.get("trafficWorkload", "SYNTHETIC_XY" if inputs.get("source") == "HorizonWitness" else None),
        }
        cell = fingerprint(matching)
        variant_factors = {"policy": policy, "configuration": configuration, "horizon": summary["horizon"]}
        if policy in ("NEXT", "MULTI"):
            variant_factors.update(limits=limits, plannerOptions=manifest["plannerOptions"])
        variant = fingerprint(variant_factors)
        policy_label = {"FIXED_PERIOD": "Fixed(T=" + format(fixed_period.normalize(), "f") + "s)",
                        "PER_CONTACT": "PerContact(" + options["perContactPreparation"] + ")",
                        "NEXT": "Next(H=1)", "MULTI": "Multi(H=" + str(summary["horizon"]) + ")"}[policy]
        actual = sources.read(root, summary["actualResultFile"])
        compact = sources.read(root, summary["compactDecisionsFile"])
        sources.record(sources.path(root, summary["actualEventsFile"]))
        metrics = final_metrics(summary, actual, name)
        run_id = root.name + "/" + name + "/" + fingerprint(str(root))[:8]
        shared = {
            "run_id": run_id, "input_directory": str(root), "case_name": name, "fixture": summary["fixture"],
            "matched_cell": cell, "policy": policy, "horizon": summary["horizon"],
            "policy_variant": variant, "policy_label": policy_label,
            "policy_configuration": encoded(configuration), "contacts": len(scenario["contacts"]),
            "known_contexts": len(physical_scenario["constraints"]["authorizedContexts"]),
            "traffic_workload": matching["trafficWorkload"], "initialization": model["initialization"],
            "cryptoperiod_s": duration(scenario["constraints"]["cryptoperiod"], name),
            "fixed_period_s": fixed_period if policy == "FIXED_PERIOD" else None,
            "per_contact_preparation": options["perContactPreparation"] if policy == "PER_CONTACT" else None,
            "multi_horizon": summary["horizon"] if policy == "MULTI" else None,
            "declared_fixed_period_s": fixed_period, "declared_per_contact_preparation": options["perContactPreparation"],
            "declared_multi_horizon": options["multiHorizon"], "cost_scale": inputs.get("costScale", options.get("costScale")),
            "search_node_limit": limits["maxNodes"], "search_time_limit_s": duration(limits["maxElapsed"], name),
            "planner_options_sha256": fingerprint(manifest["plannerOptions"]),
            "environment_sha256": fingerprint(manifest.get("executionEnvironment")),
            "model_sha256": fingerprint({"scenario": physical_scenario, "handoff": model["controllerHandoffAt"]}),
            "schedule_sha256": inputs.get("scheduleSha256"), "dataset_sha256": inputs.get("datasetSha256"),
        }
        search = [decision for decision in compact if decision["source"] == "SEARCH"]
        require(len(compact) == summary["decisions"], f"{name}: decision count mismatch")
        times = [number(d["searchNanos"], name) for d in search]
        nodes = [number(d["exploredNodes"], name) for d in search]
        require(sum(times) == number(summary["searchNanos"], name), f"{name}: search time mismatch")
        require(sum(nodes) == number(summary["exploredNodes"], name), f"{name}: node count mismatch")
        performance = summary.get("searchPerformance")
        if performance is not None:
            measured = {"searchDecisions": len(search), "totalSearchNanos": sum(times), "totalExploredNodes": sum(nodes),
                        "maximumSearchNanos": max(times) if times else 0, "maximumExploredNodes": max(nodes) if nodes else 0}
            for field, value in measured.items():
                require(number(performance[field], name) == value, f"{name}: searchPerformance.{field} mismatch")
        partial = summary.get("partialProgress") or {}
        row = dict(shared, status=summary["status"], stopped_at=summary["stoppedAt"], **metrics,
                   decisions=len(compact), search_decisions=len(search), rule_decisions=len(compact) - len(search),
                   total_search_s=sum(times) / D(10**9), total_nodes=sum(nodes),
                   maximum_search_s=max(times) / D(10**9) if times else None,
                   mean_search_s=sum(times) / len(times) / D(10**9) if times else None,
                   maximum_nodes=max(nodes) if nodes else None, mean_nodes=sum(nodes) / len(nodes) if nodes else None,
                   controller_wall_s=number(summary["controllerWallNanos"], name) / D(10**9),
                   payload_only_service_ceiling=(model.get("payloadFeasibility") or {}).get("payloadOnlyServiceCeiling"),
                   partial_served_contacts=partial.get("servedContactsSoFar"),
                   partial_useful_time_s=duration(partial["usefulSecureTimeSoFar"], name) if partial else None,
                   stop_reasons=encoded(summary.get("searchPerformance", {}).get("stopReasons"))
                   if summary.get("searchPerformance") else None)
        runs.append(row)
        for index, decision in enumerate(compact):
            action = decision.get("executedAction") or {}
            context = action.get("contextId") or {}
            diagnostics = decision.get("searchDiagnostics") or {}
            decision_row = dict(shared, decision_index=index, at=decision["at"], resumed_at=decision["resumedAt"],
                                source=decision["source"], reason=decision.get("reason"), action=action.get("kind"),
                                station=context.get("groundStationId"), action_until=action.get("until"),
                                search_status=decision.get("searchStatus"), explored_nodes=decision.get("exploredNodes"),
                                search_s=number(decision["searchNanos"], name) / D(10**9)
                                if decision.get("searchNanos") is not None else None,
                                visible_contacts=decision.get("visibleContacts"), evaluation_end=decision.get("evaluationEnd"),
                                stop_reason=decision.get("searchStopReason", diagnostics.get("stopReason")),
                                root_generated_by_action=encoded(diagnostics["rootGeneratedByAction"])
                                if "rootGeneratedByAction" in diagnostics else None)
            decision_row.update({"diagnostic_" + key: diagnostics.get(key) for key in DIAGNOSTICS})
            decisions.append(decision_row)
        metadata[run_id] = {"cell_factors": matching, "variant_factors": variant_factors, "experiment_options": options,
                            "provenance": model["provenance"],
                            "accounting": manifest["accounting"], "interpretation": manifest["interpretation"],
                            "model_input": inputs, "execution_environment": manifest.get("executionEnvironment")}
    return runs, decisions, metadata


def metric_tuple(row):
    return (-row["served_contacts"], -row["useful_time_s"], row["control_cpu_s"], row["management_bytes"], row["new_keys"])


def pairwise(runs):
    cells = defaultdict(lambda: defaultdict(list))
    variants = {}
    for row in runs:
        cells[row["matched_cell"]][row["policy_variant"]].append(row)
        variants[row["policy_variant"]] = row
    comparisons, excluded, aggregate = [], [], defaultdict(lambda: {"win": 0, "tie": 0, "loss": 0})
    for cell, cell_variants in sorted(cells.items()):
        ordered = sorted(cell_variants, key=lambda key: (POLICIES.index(variants[key]["policy"]), variants[key]["policy_label"], key))
        for left, right in combinations(ordered, 2):
            available = [[r for r in cell_variants[key] if r["status"] == "FINISHED"] for key in (left, right)]
            identity = {"matched_cell": cell, "left_policy": variants[left]["policy"], "right_policy": variants[right]["policy"],
                        "left_variant": left, "right_variant": right,
                        "left_label": variants[left]["policy_label"], "right_label": variants[right]["policy_label"],
                        "left_search_node_limit": variants[left]["search_node_limit"] if variants[left]["policy"] in ("NEXT", "MULTI") else None,
                        "right_search_node_limit": variants[right]["search_node_limit"] if variants[right]["policy"] in ("NEXT", "MULTI") else None,
                        "left_search_time_limit_s": variants[left]["search_time_limit_s"] if variants[left]["policy"] in ("NEXT", "MULTI") else None,
                        "right_search_time_limit_s": variants[right]["search_time_limit_s"] if variants[right]["policy"] in ("NEXT", "MULTI") else None,
                        "left_run_ids": encoded([r["run_id"] for r in available[0]]),
                        "right_run_ids": encoded([r["run_id"] for r in available[1]])}
            if not all(available):
                excluded.append(dict(identity, reason="NO_FINISHED_MATCH_FOR_BOTH_POLICIES",
                                     observed_statuses=encoded({key: [r["status"] for r in cell_variants[key]] for key in (left, right)})))
                continue
            scores = [{metric_tuple(r) for r in group} for group in available]
            if any(len(values) != 1 for values in scores):
                excluded.append(dict(identity, reason="INCONSISTENT_FINISHED_REPLICATES", observed_statuses=None))
                continue
            a, b = (next(iter(values)) for values in scores)
            outcome = "win" if a < b else "loss" if a > b else "tie"
            decisive = next((METRICS[index] for index, (x, y) in enumerate(zip(a, b)) if x != y), "equal_five_metrics")
            first, second = available[0][0], available[1][0]
            row = dict(identity, outcome_for_left=outcome, decisive_criterion=decisive,
                       fixture=first["fixture"], contacts=first["contacts"], traffic_workload=first["traffic_workload"],
                       cryptoperiod_s=first["cryptoperiod_s"], cost_scale=first["cost_scale"],
                       left_configuration=first["policy_configuration"], right_configuration=second["policy_configuration"])
            for metric in METRICS:
                row["left_" + metric], row["right_" + metric] = first[metric], second[metric]
            comparisons.append(row)
            aggregate[(left, right)][outcome] += 1
    totals = [dict(left_policy=variants[left]["policy"], right_policy=variants[right]["policy"],
                   left_variant=left, right_variant=right, left_label=variants[left]["policy_label"], right_label=variants[right]["policy_label"],
                   left_search_node_limit=variants[left]["search_node_limit"] if variants[left]["policy"] in ("NEXT", "MULTI") else None,
                   right_search_node_limit=variants[right]["search_node_limit"] if variants[right]["policy"] in ("NEXT", "MULTI") else None,
                   left_search_time_limit_s=variants[left]["search_time_limit_s"] if variants[left]["policy"] in ("NEXT", "MULTI") else None,
                   right_search_time_limit_s=variants[right]["search_time_limit_s"] if variants[right]["policy"] in ("NEXT", "MULTI") else None,
                   matched_finished_cells=sum(counts.values()), **counts)
              for (left, right), counts in sorted(aggregate.items())]
    return comparisons, excluded, totals


def write_csv(path, rows, empty_fields=()):
    fields = list(dict.fromkeys(key for row in rows for key in row)) or list(empty_fields)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: format(value, "f") if isinstance(value, D) else value for key, value in row.items()})


def figure_group(row, metadata, workload=False):
    factors = metadata[row["run_id"]]["cell_factors"]
    # Prefix length and payload workload are the deliberately varied experimental axes.
    common = {key: factors[key] for key in ("initialization", "executionEnvironment", "datasetSha256", "scheduleSha256")}
    common.update(cryptoperiod=row["cryptoperiod_s"], costs=factors["scenario"]["costs"],
                  constraintLimits={key: value for key, value in factors["scenario"]["constraints"].items()
                                    if key not in ("authorizedContexts",)}, costScale=row["cost_scale"])
    if not workload:
        common["trafficWorkload"] = row["traffic_workload"]
    return fingerprint(common)[:12]


def plot(runs, metadata, output):
    local = ROOT / ".tools" / "figure-python"
    if local.exists():
        sys.path.insert(0, str(local))
    os.environ.setdefault("MPLCONFIGDIR", str(ROOT / ".tools" / "matplotlib-cache"))
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import MaxNLocator
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 8, "axes.labelsize": 8,
                         "axes.titlesize": 8.5, "xtick.labelsize": 8, "ytick.labelsize": 8,
                         "legend.fontsize": 8, "figure.titlesize": 9, "pdf.fonttype": 42,
                         "ps.fonttype": 42, "svg.fonttype": "none", "axes.spines.top": False,
                         "axes.spines.right": False, "savefig.bbox": None})
    colors = dict(zip(POLICIES, ("#737373", "#CC79A7", "#0072B2", "#D55E00")))
    captions = []

    def variant_rows(group):
        representatives = {r["policy_variant"]: r for r in group}
        return sorted(representatives.values(), key=lambda r: (POLICIES.index(r["policy"]), r["policy_label"], r["policy_variant"]))

    def label(row, group):
        same_label = {r["policy_variant"] for r in group if r["policy_label"] == row["policy_label"]}
        short = row["policy_label"].replace("PerContact(JUST_IN_TIME)", "PerContact (JIT)")
        short = short.replace("PerContact(CONTACT_ONLY)", "PerContact (contact-only)")
        return short + (" [" + row["policy_variant"][:6] + "]" if len(same_label) > 1 else "")

    def factor_text(group):
        cryptoperiod = format(D(str(group[0]["cryptoperiod_s"])).normalize(), "f")
        scale = group[0]["cost_scale"]
        scale = format(D(str(scale)).normalize(), "f") if scale is not None else "unrecorded"
        return f"CP = {cryptoperiod} s; security cost scale = {scale}"

    def save(fig, stem, caption, group):
        for extension in ("pdf", "svg", "png"):
            # Preserve the native IEEE double-column canvas; tight cropping changes its width.
            fig.savefig(output / f"{stem}.{extension}", dpi=300, bbox_inches=None)
        plt.close(fig)
        captions.append({"figure": stem, "caption": caption, "run_ids": [r["run_id"] for r in group]})

    growth = defaultdict(list)
    workloads = defaultdict(list)
    for row in runs:
        if row["schedule_sha256"] and row["traffic_workload"] == "LIGHT" and metadata[row["run_id"]]["execution_environment"] is not None:
            growth[figure_group(row, metadata)].append(row)
        if row["schedule_sha256"] and row["contacts"] == 3:
            workloads[figure_group(row, metadata, True)].append(row)
    for group_id, group in sorted(growth.items()):
        if len({r["contacts"] for r in group}) < 2:
            continue
        variants = variant_rows(group)
        fig = plt.figure(figsize=(7.16, 5.5 if len(variants) <= 4 else 6.0), layout="constrained")
        grid = fig.add_gridspec(2, 2, height_ratios=(1.2, 1.0))
        axes = [fig.add_subplot(grid[0, 0]), fig.add_subplot(grid[0, 1]), fig.add_subplot(grid[1, :])]
        fig.suptitle("Light workload | " + factor_text(group))
        axes[0].set_title("(a) Maximum search time per decision")
        axes[1].set_title("(b) Maximum explored nodes per decision")
        for y, variant in enumerate(variants):
            policy = variant["policy"]
            selected = sorted((r for r in group if r["policy_variant"] == variant["policy_variant"]), key=lambda r: r["contacts"])
            for ax, field, title in zip(axes[:2], ("maximum_search_s", "maximum_nodes"),
                                        ("Search time (s)", "Explored nodes")):
                search = [r for r in selected if r[field] is not None]
                if not search:
                    continue
                ax.scatter([r["contacts"] for r in search], [float(r[field]) for r in search], color=colors[policy], label=label(search[0], group))
                if len({r["contacts"] for r in search}) == len(search):
                    ax.plot([r["contacts"] for r in search], [float(r[field]) for r in search], color=colors[policy], alpha=.6)
                ax.set_ylabel(title)
                ax.set_yscale("log")
            for row in selected:
                axes[2].scatter(row["contacts"], y, marker="o" if row["status"] == "FINISHED" else "x",
                                color=colors[policy] if row["status"] == "FINISHED" else "#B2182B", s=35)
        for ax in axes:
            ax.set_xlabel("Contacts, N")
            ax.xaxis.set_major_locator(MaxNLocator(integer=True))
            ax.grid(alpha=.18)
        axes[2].set_yticks(range(len(variants)), [label(row, group) for row in variants])
        axes[2].set_ylim(-.6, len(variants) - .4)
        axes[2].set_xlabel("Original chronological contact count, N")
        axes[2].set_title("(c) Actual run status: circle = FINISHED; cross = stopped")
        handles, labels = axes[0].get_legend_handles_labels()
        if handles:
            axes[0].legend(handles, labels, fontsize=8)
        save(fig, "light-growth-" + group_id,
             "Light workload. " + factor_text(group) + ". Matched schedule/cryptoperiod/cost/environment factors. "
             "Each line has one actual policy configuration and search budget/solver variant. "
             "Search timing includes stopped decisions; "
             "rule baselines have no search observations. Circles are complete actual runs; crosses are non-final checkpoints. "
             "Increasing N also changes the configured known station/context catalog; known_contexts is exported in the CSV. "
             "This series does not isolate schedule length from catalog size. "
             "Lines join only a unique observation per N; these are measurements, not asymptotic complexity claims.", group)
    for group_id, group in sorted(workloads.items()):
        levels = [w for w in ("LIGHT", "MODERATE", "SATURATED") if any(r["traffic_workload"] == w for r in group)]
        if len(levels) < 2 or not any(r["status"] == "FINISHED" for r in group):
            continue
        fig, axes = plt.subplots(2, 3, figsize=(7.16, 5.8), constrained_layout=True)
        fig.suptitle("Three original contacts | " + factor_text(group))
        axes = list(axes.flat)
        variants = variant_rows(group)
        width = .8 / len(variants)
        titles = ("(a) Served contacts", "(b) Useful secure time", "(c) Security CPU\n(GS + SC)",
                  "(d) Management traffic", "(e) New directional keys")
        units = ("Contacts", "Time (s)", "CPU time (s)", "UL + DL (bytes)", "Keys")
        for metric_index, (field, title, unit) in enumerate(zip(METRICS, titles, units)):
            ax = axes[metric_index]
            for policy_index, variant in enumerate(variants):
                policy = variant["policy"]
                for level_index, level in enumerate(levels):
                    selected = [r for r in group if r["policy_variant"] == variant["policy_variant"]
                                and r["traffic_workload"] == level and r["status"] == "FINISHED"]
                    values = {r[field] for r in selected}
                    x = level_index + (policy_index - (len(variants) - 1) / 2) * width
                    if len(values) == 1:
                        value = float(next(iter(values)))
                        ax.bar(x, value, width=width * .9, color=colors[policy], label=label(variant, group) if level_index == 0 else None)
                        if len(variants) <= 4:
                            ax.annotate(f"{value:g}", (x, value), xytext=(0, 3), textcoords="offset points",
                                        ha="center", rotation=90, fontsize=8)
                    else:
                        ax.text(x, .02, "NA", transform=ax.get_xaxis_transform(), rotation=90, ha="center", fontsize=8)
            ax.set_xticks(range(len(levels)), [w.title() for w in levels])
            ax.set_title(title)
            ax.set_ylabel(unit)
            ax.margins(y=.28 if len(variants) <= 4 else .1)
            ax.grid(axis="y", alpha=.18)
            if field in ("served_contacts", "management_bytes", "new_keys"):
                ax.yaxis.set_major_locator(MaxNLocator(integer=True))
        axes[5].axis("off")
        from matplotlib.patches import Patch
        axes[5].legend(handles=[Patch(color=colors[r["policy"]], label=label(r, group)) for r in variants], loc="center", fontsize=8)
        save(fig, "three-contact-workloads-" + group_id,
             "Same three geometric contacts. " + factor_text(group) + ". Matched lifecycle/cost/environment factors; synthetic workload varies. "
             "Actual policy configurations and search variants have distinct labels. "
             "Only FINISHED actual metrics are bars. NA means absent, incomplete, or inconsistent finished replicate data and is not zero. "
             "Value labels are omitted when more than four variants share a panel; exact values remain in the CSV files. "
             "Security costs are synthetic; search CPU is reported separately. No compromise-risk comparison is implied.", group)
    (output / "figure-captions.json").write_text(json.dumps(captions, indent=2), encoding="utf-8")
    return {"matplotlib": matplotlib.__version__, "figures": captions}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--input", nargs="+", type=Path, required=True, help="FourPolicyExperimentMain output directories")
    parser.add_argument("--output", type=Path, required=True, help="New or empty output directory")
    parser.add_argument("--figures", action="store_true", help="Also export scientific PDF/SVG/300-dpi PNG figures")
    args = parser.parse_args(argv)
    roots = [p.resolve() for p in args.input]
    require(len(set(roots)) == len(roots), "Input directories must be unique")
    output = args.output.resolve()
    require(output not in roots, "Output cannot replace an input directory")
    require(not output.exists() or not any(output.iterdir()), "Output must be new or empty")
    sources = Sources()
    runs, decisions, metadata = [], [], {}
    for root in roots:
        current_runs, current_decisions, current_metadata = collect(root, sources)
        runs.extend(current_runs)
        decisions.extend(current_decisions)
        metadata.update(current_metadata)
    require(runs, "No cases published in the supplied summaries")
    comparisons, excluded, totals = pairwise(runs)
    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "runs.csv", runs)
    write_csv(output / "decisions.csv", decisions, ("run_id", "decision_index"))
    write_csv(output / "pairwise-cells.csv", comparisons, ("matched_cell", "left_policy", "right_policy", "outcome_for_left"))
    write_csv(output / "pairwise-win-tie-loss.csv", totals, ("left_policy", "right_policy", "matched_finished_cells", "win", "tie", "loss"))
    write_csv(output / "pairwise-excluded.csv", excluded, ("matched_cell", "left_policy", "right_policy", "reason"))
    figures = plot(runs, metadata, output) if args.figures else None
    sources.record(Path(__file__).resolve())
    provenance = {"schema_version": "1.0", "python_version": platform.python_version(), "input_directories": [str(p) for p in roots],
                  "source_sha256": sources.hashes, "run_metadata": metadata, "figures": figures,
                  "counts": {"runs": len(runs), "decisions": len(decisions), "finished_runs": sum(r["status"] == "FINISHED" for r in runs),
                             "matched_finished_pairs": len(comparisons), "excluded_pairs": len(excluded)},
                  "rules": ["Blank CSV final metrics are unavailable, never zero; partial checkpoint progress is separate.",
                            "Five-metric ordering: maximize served contacts, maximize useful time, minimize security CPU, bytes, then directional keys.",
                            "Pairwise rows require FINISHED on both sides and equal full common scenario/handoff, traffic/source and environment.",
                            "Only schema-declared constraint sets (authorizedContexts, blockedKeyIds) are order-normalized; contact, phase and operation arrays keep their order.",
                            "Actual policy configuration defines each variant: Fixed period, PerContact preparation, or Next/Multi H; limits/solver distinguish search variants only.",
                            "Irrelevant CLI flags do not split cells: a global Fixed period flag does not change Next/Multi; H2 and H3 remain different Multi variants.",
                            "Canonical action-plan tie-breaking is deliberately excluded from research win/tie/loss.",
                            "Repeated identical policy scores count once per matched cell; inconsistent finished replicates are excluded.",
                            "Per-decision diagnostics absent in older exports remain blank; baselines have no search decision or maximum search cost.",
                            "Search costs are measured algorithm work, not security CPU or simulated elapsed time.",
                            "This summary checks actual-result agreement; use verify_policy_experiments.py for independent journal accounting."]}
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2, ensure_ascii=False, default=str), encoding="utf-8")
    print(json.dumps(provenance["counts"], sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
