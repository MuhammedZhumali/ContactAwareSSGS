#!/usr/bin/env python3
"""SE2 registration, paired-control and factual-journal audit.

The unchanged SE1 independent accounting engine audits every executed journal.
SE2 adds schedule-derived attack registration and event-prefix diagnostics; these
are post-run explanations, never observations supplied to a policy. Incomplete
runs have no final quality score. Grid fractions are descriptive, not probabilities.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import copy
from decimal import Decimal as D
import itertools
import json
from pathlib import Path

import audit_security_evaluation as se1

require, instant, context = se1.require, se1.instant, se1.context
FRACTIONS = (10, 30, 50, 70, 90)
METADATA = ("block", "workload", "attackPoint", "attackAt", "timingModel", "noticeDurationScale")
DELTA_FIELDS = ("lostServedContacts", "lostDeliveredBytes", "lostUsefulTime", "extraControlCpu",
                "extraManagementBytes", "extraCreatedKeys")


def attack_candidates(contacts, target, original):
    """Integer-nanosecond schedule construction, independent of all decisions.

    'Following gap' is between the selected first target window and the next
    physical window of any station. A gap midpoint is absent if no positive gap.
    Duplicate instants are retained here so registration can verify all aliases.
    """
    windows = sorted((c["window"] for c in contacts), key=lambda w: (instant(w["start"]), w["id"]))
    first = next(w for w in windows if context(w) == context(target))
    start, end = instant(first["start"]), instant(first["end"])
    duration_ns = int((end - start) * 10**9)
    points = [("original", instant(original))]
    points.extend(("p" + str(p), start + D(duration_ns * p // 100) / 10**9) for p in FRACTIONS)
    index = windows.index(first)
    following = windows[index + 1] if index + 1 < len(windows) else None
    if following and instant(following["start"]) > end:
        gap_ns = int((instant(following["start"]) - end) * 10**9)
        points.append(("gap-midpoint", end + D(gap_ns // 2) / 10**9))
    return points


def attack_grid(contacts, target, original):
    """Deduplicate instants, retaining the first label (original precedes p50)."""
    unique = {}
    points = attack_candidates(contacts, target, original)
    for label, when in points:
        unique.setdefault(when, label)
    return {label: when for when, label in unique.items()}


def comparison_key(spec):
    return tuple(spec.get(k) for k in ("fixture", "block", "workload", "timingModel", "noticeDurationScale", "attackPoint", "mode"))


def validate_registration(manifest, specs, models):
    """Rebuild all attack times and groups without reading a single result."""
    configs = {c["id"]: c for c in manifest["configurations"]}
    require(len(configs) == len(manifest["configurations"]), "Duplicate physical configuration")
    require({s["fixture"] for s in specs.values()} == set(configs), "Configuration/case coverage differs")
    rows = []
    for fixture, config in configs.items():
        registered = [s for s in specs.values() if s["fixture"] == fixture]
        sample = models[registered[0]["name"]]
        for spec in registered:
            require(physical(models[spec["name"]]) == physical(sample), fixture + ": policies/attack points have different physics")
            for field in ("block", "workload", "timingModel", "noticeDurationScale", "fixedPeriodSeconds"):
                require(spec[field] == config[field], spec["name"] + ": configuration field differs: " + field)
        candidates = (attack_candidates(sample["contacts"], config["target"], config["originalAttackAt"])
                      if config["fullAttackGrid"] else [("original", instant(config["originalAttackAt"]))])
        expected = {}
        for label, when in candidates:
            if when not in expected:
                expected[when] = {"id": label, "sources": []}
            expected[when]["sources"].append(label)
        points = {p["id"]: p for p in config["points"]}
        require(len(points) == len(config["points"]) == len(expected), fixture + ": wrong deduplicated attack grid size")
        require({instant(p["at"]) for p in points.values()} == set(expected), fixture + ": attack time is not schedule-derived")
        for point in points.values():
            when = instant(point["at"])
            require(point["id"] == expected[when]["id"] and set(point["sources"]) == set(expected[when]["sources"]),
                    fixture + ": attack label/deduplication sources differ")
            require(instant(sample["start"]) <= when < instant(sample["end"]), fixture + ": attack outside experiment")
            rows.append({"fixture": fixture, "block": config["block"], "workload": config["workload"],
                         "attackPoint": point["id"], "attackAt": point["at"], "sources": "|".join(point["sources"]),
                         "offsetFromSimulationStartSeconds": when - instant(sample["start"]),
                         "targetSatellite": config["target"]["satelliteId"], "targetStation": config["target"]["groundStationId"],
                         "timingModel": config["timingModel"], "noticeDurationScale": config["noticeDurationScale"]})
        expected_cells = {(p, "none", None) for p in ("fixed", "percontact", "next", "multi")}
        expected_cells.update((p, mode, point) for p in ("fixed", "percontact", "next", "multi")
                              for mode in ("detected", "undetected") for point in points)
        actual_cells = [(s["policy"], s["mode"], s["attackPoint"]) for s in registered]
        require(len(actual_cells) == len(set(actual_cells)) and set(actual_cells) == expected_cells,
                fixture + ": incomplete/duplicate registered policy-mode-point matrix")
        for spec in registered:
            scenario = models[spec["name"]]
            incidents = scenario.get("disclosures", [])
            if spec["mode"] == "none":
                require(not incidents and spec["attackAt"] is None and spec["baselineCase"] == spec["name"],
                        spec["name"] + ": invalid no-attack control registration")
                continue
            point = points[spec["attackPoint"]]
            require(len(incidents) == 1, spec["name"] + ": expected one incident")
            incident = incidents[0]
            require(context(incident["contextId"]) == context(config["target"])
                    and instant(spec["attackAt"]) == instant(incident["at"]) == instant(point["at"]),
                    spec["name"] + ": target/time differs from physical registration")
            if "attackSources" in spec:
                require(set(spec["attackSources"]) == set(point["sources"]), spec["name"] + ": source aliases differ")
            detection = incident.get("detectionAt")
            require(detection is None if spec["mode"] == "undetected"
                    else detection is not None and instant(detection) - instant(incident["at"]) == D(2),
                    spec["name"] + ": detection delay differs from preregistered SE1 two seconds")
    return rows


def physical(scenario):
    value = copy.deepcopy(scenario)
    value.pop("name", None)
    value.pop("disclosures", None)
    return value


def validate_control(name, spec, scenario, baseline_spec, baseline):
    require(physical(scenario) == physical(baseline), name + ": control physical scenario differs")
    require(not baseline.get("disclosures"), name + ": control has an attack")
    require(spec["policy"] == baseline_spec["policy"], name + ": control policy differs")
    require(D(spec["fixedPeriodSeconds"]) == D(baseline_spec["fixedPeriodSeconds"]),
            name + ": control FixedPeriod differs")


def key_inventory(scenario, events, target, before_sequence, when, disclosed):
    """State immediately before an event, built from factual prefix transitions.

    Installed status/activation from final key records are deliberately unused:
    they may describe operations that had not happened at the sampled instant.
    """
    keys = copy.deepcopy(scenario["initialState"]["keys"])
    bindings = {context(c["id"]): {d: c[d]["keyId"] for d in ("uplink", "downlink")}
                for c in scenario["initialState"]["contexts"].values()}
    material_age = D(scenario["constraints"]["maxMaterialAge"])
    cryptoperiod = D(scenario["constraints"]["cryptoperiod"])
    for key in keys.values():
        key["deadline"] = se1.deadline(key, scenario)
    for event in events:
        if event["sequence"] >= before_sequence:
            break
        pair = event.get("keyIds") or {}
        at = instant(event["at"])
        cid = context(event["contextId"]) if event.get("contextId") else None
        if event["type"] == "STEP_COMPLETED":
            action = event["action"]
            if action == "PREPARE":
                for direction, key_id in pair.items():
                    require(key_id not in keys, "Diagnostic generation ID reused")
                    keys[key_id] = {"id": key_id, "contextId": event["contextId"], "direction": direction.upper(),
                                    "status": "PRE_ACTIVE", "installedOnSpacecraft": False,
                                    "deadline": at + material_age}
            elif action == "INSTALL":
                for key_id in pair.values():
                    keys[key_id]["installedOnSpacecraft"] = True
            elif action == "SWITCH":
                for key_id in bindings.get(cid, {}).values():
                    if key_id in keys and key_id not in pair.values():
                        keys[key_id]["status"] = "DEACTIVATED"
                for key_id in pair.values():
                    keys[key_id]["status"] = "ACTIVE"
                    keys[key_id]["deadline"] = min(keys[key_id]["deadline"], at + cryptoperiod)
                bindings[cid] = dict(pair)
            elif action == "EXPIRE_CONTEXT":
                for key_id in pair.values():
                    keys[key_id]["status"] = "DEACTIVATED"
                bindings[cid] = {}
        elif event["type"] == "REVOCATION_RECEIVED_GROUND":
            for key_id in pair.values():
                keys[key_id]["status"] = "DEACTIVATED"
            if bindings.get(cid) == pair:
                bindings[cid] = {}
    blocked = set(scenario["constraints"].get("blockedKeyIds", []))
    independent = {key_id: key for key_id, key in keys.items()
                   if context(key["contextId"]) == context(target) and key_id not in disclosed
                   and key_id not in blocked and when < key["deadline"]}
    spare = {i: k for i, k in independent.items() if k["status"] == "PRE_ACTIVE"}
    installed = sorted(i for i, k in spare.items() if k["installedOnSpacecraft"])
    prepared = sorted(i for i, k in spare.items() if not k["installedOnSpacecraft"])
    current = bindings.get(context(target), {})
    active = len(current) == 2 and all(i in independent and independent[i]["status"] == "ACTIVE"
                                      and independent[i]["installedOnSpacecraft"] for i in current.values())
    return {"preparedIndependentKeys": len(prepared), "installedIndependentKeys": len(installed),
            "preparedIndependentKeyIds": "|".join(prepared), "installedIndependentKeyIds": "|".join(installed),
            "activeIndependentPair": active, "activeIndependentKeyIds": "|".join(sorted(current.values())) if active else ""}


def phase_at(event, frames):
    """Frame just before this event; preserve uplink aborted by this notice.

    The factual frame end can equal notice time for an aborted uplink. Its later
    event sequence proves it was still committed before notification. A frame
    completing at the same time *before* notification is correctly absent.
    """
    when = instant(event["at"])
    current = [f for f in frames if f["from"] < when < f["from"] + f["fullDuration"]
               and f["until"] >= when and f["sequence"] > event["sequence"]]
    require(len(current) <= 1, "Multiple in-flight frames at diagnostic instant")
    frame = current[0] if current else None
    return {"frameDirection": None if frame is None else frame["direction"],
            "frameKeyId": None if frame is None else frame["keyId"],
            "frameContactId": None if frame is None else frame["contactId"],
            "frameRemainingSeconds": None if frame is None else frame["from"] + frame["fullDuration"] - when}


def contact_geometry(scenario, target, when):
    windows = [c["window"] for c in scenario["contacts"] if context(c["window"]) == context(target)]
    future = [instant(w["start"]) for w in windows if instant(w["start"]) > when]
    current = [w for w in windows if instant(w["start"]) <= when < instant(w["end"])]
    return {"nextTargetContactDelaySeconds": min(future) - when if future else None,
            "remainingCurrentTargetContactSeconds": max((instant(w["end"]) - when for w in current), default=None)}


def diagnostics(scenario, events, actual):
    incidents = scenario.get("disclosures", [])
    if not incidents:
        return []
    incident = incidents[0]
    contacts = {c["window"]["id"]: c for c in scenario["contacts"]}
    frames = [se1.read_frame(e, contacts) | {"sequence": e["sequence"]}
              for e in events if e["type"] in {"PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"}]
    # A stopped search can retain an admitted frame without a terminal journal
    # event. This is actual executor evidence, not a forecast or search state.
    pending = actual.get("inFlightFrame")
    if pending:
        w = pending["contact"]["window"]
        payload = pending["contact"]["payload"]
        frames.append({"sequence": len(events), "from": instant(pending["from"]), "until": instant(pending["until"]),
                       "fullDuration": D(payload["frameBytes"]) / D(payload["bytesPerSecond"]),
                       "direction": "UPLINK" if pending["uplink"] else "DOWNLINK",
                       "keyId": pending["keys"]["uplink" if pending["uplink"] else "downlink"], "contactId": w["id"]})
    capture = next((e for e in events if e["type"] in {"KEY_DISCLOSED", "DISCLOSURE_NO_TARGET"}), None)
    disclosed = set((capture.get("keyIds") or {}).values()) if capture else set()
    result = []
    for stage, timestamp, kinds in (("DISCLOSURE", incident["at"], {"KEY_DISCLOSED", "DISCLOSURE_NO_TARGET"}),
                                     ("GROUND_NOTICE", incident.get("detectionAt"), {"REVOCATION_RECEIVED_GROUND"})):
        if timestamp is None:
            continue
        fact = next((e for e in events if e["type"] in kinds), None)
        when = instant(timestamp)
        row = {"stage": stage, "at": timestamp, "observed": fact is not None,
               "eventType": None if fact is None else fact["type"], "hadTarget": None if capture is None else bool(disclosed),
               "reasonAbsent": (None if fact else "NO_TARGET" if capture and capture["type"] == "DISCLOSURE_NO_TARGET"
                                else "NOT_REACHED" if instant(actual["at"]) < when else "NO_GROUND_NOTICE")}
        if fact:
            row.update(key_inventory(scenario, events, incident["contextId"], fact["sequence"], when, disclosed))
            row.update(phase_at(fact, frames))
        else:
            row.update(dict.fromkeys(("preparedIndependentKeys", "installedIndependentKeys", "preparedIndependentKeyIds",
                                      "installedIndependentKeyIds", "activeIndependentPair", "activeIndependentKeyIds",
                                      "frameDirection", "frameKeyId", "frameContactId", "frameRemainingSeconds")))
        row.update(contact_geometry(scenario, incident["contextId"], when))
        result.append(row)
    return result


def metadata(spec):
    return {k: spec.get(k) for k in METADATA} | {"fixture": spec["fixture"], "policy": spec["policy"],
            "mode": spec["mode"], "baselineCase": spec["baselineCase"], "fixedPeriodSeconds": spec["fixedPeriodSeconds"]}


def coverage(specs, results):
    groups = defaultdict(list)
    for spec in specs.values():
        groups[(spec.get("block"), spec.get("workload"), spec["policy"], spec["mode"])].append(spec)
    rows = []
    for (block, workload, policy, mode), group in sorted(groups.items(), key=lambda p: str(p[0])):
        status = Counter(results[s["name"]]["status"] if s["name"] in results else "NOT_RUN" for s in group)
        recovery = Counter(results[s["name"]]["security"]["recoveryStatus"] for s in group
                           if s["name"] in results and results[s["name"]]["status"] == "FINISHED")
        recovered = [results[s["name"]]["security"]["detectionToRecovery"] for s in group
                     if s["name"] in results and results[s["name"]].get("security")
                     and results[s["name"]]["security"]["recoveryStatus"] == "RECOVERED"]
        rows.append({"block": block, "workload": workload, "policy": policy, "mode": mode, "registered": len(group),
                     "finished": status["FINISHED"], "completionFraction": D(status["FINISHED"]) / len(group),
                     "notRun": status["NOT_RUN"], "unfinished": len(group) - status["FINISHED"] - status["NOT_RUN"],
                     "statusCounts": json.dumps(dict(sorted(status.items()))),
                     "recovered": recovery["RECOVERED"], "censored": recovery["CENSORED_NO_RECOVERY"],
                     "noTarget": recovery["NO_TARGET"], "undetected": recovery["UNDETECTED"], "noAttack": recovery["NO_ATTACK"],
                     "detectedTargetRuns": recovery["RECOVERED"] + recovery["CENSORED_NO_RECOVERY"],
                     "recoveryFractionOfDetectedTargets": (D(recovery["RECOVERED"]) / (recovery["RECOVERED"] + recovery["CENSORED_NO_RECOVERY"]))
                        if recovery["RECOVERED"] + recovery["CENSORED_NO_RECOVERY"] else None,
                     "recoveredDelayMinSeconds": min(recovered, default=None),
                     "recoveredDelayMaxSeconds": max(recovered, default=None)})
    return rows


def metric_row(name, spec, r):
    s = r["security"]
    return {"case": name} | metadata(spec) | {"status": r["status"], "cryptoperiodSeconds": r["cryptoperiodSeconds"],
            "searchSeconds": D(r["searchNanos"]) / 10**9, "exploredNodes": r["exploredNodes"], "decisions": r["decisions"],
            "contactCount": r["contactCount"], "pairedControlStatus": r["pairedControlStatus"]} | r["five"] | {k: s[k] for k in ("deliveredBytes", "emittedBytes",
            "decryptableBeforeBytes", "decryptableAfterBytes", "compromisedDeliveredBytes", "confidentialDeliveredBytes",
            "compromisedUseAfter", "bytesAfterGroundNotice", "useAfterGroundNotice", "bytesAfterSpacecraftNotice",
            "useAfterSpacecraftNotice", "recoveryStatus", "detectionToRecovery", "groundToSpacecraftNotice", "censorAt")} | {
            "notificationCpu": s["notificationCosts"]["groundCpu"] + s["notificationCosts"]["spacecraftCpu"],
            "notificationBytes": s["notificationCosts"]["uplinkBytes"] + s["notificationCosts"]["downlinkBytes"],
            "abortedFrames": r["abortedFrames"]} | (r["pairedDelta"] or dict.fromkeys(DELTA_FIELDS))


def pairwise(rows):
    groups = defaultdict(list)
    for row in rows:
        if row["pairedControlStatus"] != "FINISHED":
            continue
        groups[comparison_key(row)].append(row)
    pairs = []
    for group in groups.values():
        for left, right in itertools.combinations(sorted(group, key=lambda r: r["policy"]), 2):
            require(left["policy"] != right["policy"], "Duplicate policy in a physical comparison")
            lt = left["recoveryStatus"] not in {"NO_ATTACK", "NO_TARGET"}
            rt = right["recoveryStatus"] not in {"NO_ATTACK", "NO_TARGET"}
            pairs.append({k: left[k] for k in ("block", "workload", "fixture", "attackPoint", "attackAt", "mode", "timingModel", "noticeDurationScale")} |
                         {"left": left["policy"], "right": right["policy"], "leftCase": left["case"], "rightCase": right["case"],
                          "bothHadTarget": lt and rt, "targetAvailabilityMatches": lt == rt,
                          "leftRecoveryStatus": left["recoveryStatus"], "rightRecoveryStatus": right["recoveryStatus"],
                          "service": se1.outcome(left["servedContacts"], right["servedContacts"]),
                          "delivered": se1.outcome(left["deliveredBytes"], right["deliveredBytes"]),
                          "confidentialDelivered": se1.outcome(left["confidentialDeliveredBytes"], right["confidentialDeliveredBytes"]),
                          "exposure": (se1.outcome(left["decryptableBeforeBytes"] + left["decryptableAfterBytes"],
                                                 right["decryptableBeforeBytes"] + right["decryptableAfterBytes"], False)
                                       if lt and rt else "NOT_COMPARABLE"),
                          "exposureBefore": (se1.outcome(left["decryptableBeforeBytes"], right["decryptableBeforeBytes"], False)
                                             if lt and rt else "NOT_COMPARABLE"),
                          "exposureAfter": (se1.outcome(left["decryptableAfterBytes"], right["decryptableAfterBytes"], False)
                                            if lt and rt else "NOT_COMPARABLE"),
                          "recovery": (se1.outcome(left["detectionToRecovery"], right["detectionToRecovery"], False)
                                       if lt and rt else "NOT_COMPARABLE"),
                          "cpu": se1.outcome(left["controlCpu"], right["controlCpu"], False),
                          "managementBytes": se1.outcome(left["managementBytes"], right["managementBytes"], False),
                          "newKeys": se1.outcome(left["newKeys"], right["newKeys"], False)})
    return pairs


def write_tables(output, specs, results):
    rows = [metric_row(n, specs[n], r) for n, r in results.items() if r["status"] == "FINISHED"]
    se1.write_csv(output / "metrics.csv", rows)
    pairs = pairwise(rows)
    se1.write_csv(output / "pairwise.csv", pairs)
    grouped = Counter()
    for pair in pairs:
        for metric in ("service", "delivered", "confidentialDelivered", "exposure", "exposureBefore", "exposureAfter",
                       "recovery", "cpu", "managementBytes", "newKeys"):
            grouped[(pair["block"], pair["workload"], pair["left"], pair["right"], pair["mode"],
                     pair["bothHadTarget"], pair["targetAvailabilityMatches"], metric, pair[metric])] += 1
    fields = ("block", "workload", "left", "right", "mode", "bothHadTarget", "targetAvailabilityMatches", "metric", "outcome")
    se1.write_csv(output / "win-tie-loss.csv",
                  [dict(zip(fields, key)) | {"count": count} for key, count in sorted(grouped.items(), key=lambda p: str(p[0]))])
    se1.write_csv(output / "coverage.csv", coverage(specs, results))
    se1.write_csv(output / "recovery-summary.csv", coverage(specs, results))
    excluded = [{"case": n} | metadata(s) | {"status": results[n]["status"] if n in results else "NOT_RUN"}
                for n, s in specs.items() if n not in results or results[n]["status"] != "FINISHED"]
    se1.write_csv(output / "excluded.csv", excluded, ["case", *METADATA, "fixture", "policy", "mode", "baselineCase", "fixedPeriodSeconds", "status"])
    diagnostic_rows = [{"case": n} | metadata(specs[n]) | d for n, r in results.items() for d in r["diagnostics"]]
    se1.write_csv(output / "diagnostics.csv", diagnostic_rows)
    return rows


def search_timing_rows(specs, results, limits):
    """Preserve every recorded SEARCH decision; flags never alter eligibility.

    Search instrumentation measures elapsed time, not thread/process CPU. The
    elapsed limit is checked cooperatively and is not a hard interruption timer.
    Twice the configured limit is a post-hoc descriptive threshold only.
    """
    budget_ns = int(D(limits["maxElapsed"]) * 10**9)
    require(budget_ns > 0, "Expected positive registered search elapsed limit")
    rows = []
    for name, result in results.items():
        for index, decision in enumerate(result.get("_decisions", [])):
            if decision.get("source") != "SEARCH":
                continue
            elapsed = decision.get("searchNanos")
            require(elapsed is not None and elapsed >= 0, name + ": search decision lacks elapsed measurement")
            phases = decision.get("searchDiagnostics") or {}
            phase_values = [phases.get(k) for k in ("generationNanos", "executionNanos", "boundNanos")]
            phase_sum = sum(phase_values) if all(p is not None for p in phase_values) else None
            rows.append({"case": name} | metadata(specs[name]) |
                        {"runStatus": result["status"], "decisionIndex": index, "at": decision["at"],
                         "resumedAt": decision.get("resumedAt"), "searchStatus": decision.get("searchStatus"),
                         "stopReason": decision.get("searchStopReason") or phases.get("stopReason"),
                         "exploredNodes": decision.get("exploredNodes"), "elapsedNanos": elapsed,
                         "elapsedSeconds": D(elapsed) / 10**9, "budgetNanos": budget_ns,
                         "budgetSeconds": D(limits["maxElapsed"]), "nodeBudget": limits["maxNodes"],
                         "generationNanos": phase_values[0], "executionNanos": phase_values[1],
                         "boundNanos": phase_values[2], "sumReportedPhaseNanos": phase_sum,
                         "elapsedMinusReportedPhaseSumNanos": None if phase_sum is None else elapsed - phase_sum,
                         "elapsedToBudgetRatio": D(elapsed) / budget_ns, "diagnosticThresholdMultiplier": 2,
                         "exceedsTwiceBudget": elapsed > 2 * budget_ns})
    return rows


def write_search_timing(output, specs, results, limits):
    rows = search_timing_rows(specs, results, limits)
    flagged = [row for row in rows if row["exceedsTwiceBudget"]]
    se1.write_csv(output / "search-decisions.csv", rows)
    summary = {
        "allSearchDecisions": len(rows), "flaggedDecisionCount": len(flagged),
        "flaggedCases": sorted({row["case"] for row in flagged}),
        "flaggedSearchStatusCounts": dict(sorted(Counter(row["searchStatus"] for row in flagged).items())),
        "totalRecordedElapsedNanos": sum(row["elapsedNanos"] for row in rows),
        "maximumRecordedElapsedNanos": max((row["elapsedNanos"] for row in rows), default=None),
        "diagnosticThreshold": "recorded elapsedNanos > 2 * registered maxElapsed in nanoseconds",
        "thresholdPreregistered": False,
        "changesResultEligibilityOrBudget": False,
        "interpretation": "Post-hoc timing diagnostic, not an experiment-selection rule. Preserve all recorded durations. "
                          "These are elapsed measurements, not algorithm CPU consumption. The search limit is cooperative "
                          "and is not a strict hard timeout. Large elapsed or phase timings may reflect execution, host "
                          "scheduling, pauses, blocking, instrumentation or other causes; their cause is unverified.",
        "flaggedDecisions": flagged,
    }
    (output / "search-timing-anomalies.json").write_text(
        json.dumps(summary, indent=2, default=str) + "\n", encoding="utf-8")
    return {k: v for k, v in summary.items() if k != "flaggedDecisions"}


def compare_se1_overlap(reference, results):
    """Report the 48 original-condition replications, without forcing agreement."""
    legacy = se1.Inputs(reference)
    old_summary = legacy.read("summary.json")
    mapping = {"X": "X-cp150", "Y": "Y-cp150", "iss3-cp5400": "iss3-cp5400", "iss3-cp7200": "iss3-cp7200"}
    rows = []

    def non_wait(decisions):
        return [d["executedAction"] for d in decisions
                if d.get("executedAction") and d["executedAction"]["kind"] != "WAIT"]

    for fixture, old_fixture in mapping.items():
        for policy in ("fixed", "percontact", "next", "multi"):
            for mode in ("none", "undetected", "detected"):
                name = f"{fixture}-{policy}-" + ("none" if mode == "none" else "original-" + mode)
                old_name = f"{old_fixture}-{policy}-{mode}"
                require(old_name in old_summary, "Missing registered SE1 overlap reference: " + old_name)
                old, new = old_summary[old_name], results.get(name)
                row = {"case": name, "reference": old_name, "newStatus": new["status"] if new else "NOT_RUN",
                       "referenceStatus": old["status"], "fiveMatch": None, "securityMatch": None,
                       "nonWaitActionsMatch": None, "differences": []}
                if new and new["status"] == old["status"] == "FINISHED":
                    m = old["simulationMetrics"]
                    five = {"servedContacts": m["servedContacts"], "usefulSecureTime": m["usefulSecureTime"],
                            "controlCpu": m["controlCosts"]["groundCpu"] + m["controlCosts"]["spacecraftCpu"],
                            "managementBytes": m["controlCosts"]["uplinkBytes"] + m["controlCosts"]["downlinkBytes"],
                            "newKeys": m["createdKeys"]}
                    row["fiveMatch"] = five == new["five"]
                    old_sec = se1.normalize_security(old["securityMetrics"])
                    new_sec = dict(new["security"])
                    # Incident IDs label the same physical attack in separate registrations.
                    old_sec.pop("incidentId", None)
                    new_sec.pop("incidentId", None)
                    row["securityMatch"] = old_sec == new_sec
                    old_actions = non_wait(legacy.read(old["decisionsFile"]))
                    new_actions = non_wait(new["_decisions"])
                    row["nonWaitActionsMatch"] = old_actions == new_actions
                    for metric, a, b in (("five", five, new["five"]), ("security", old_sec, new_sec)):
                        row["differences"].extend({"metric": metric + "." + key, "se1": a.get(key), "se2": b.get(key)}
                                                  for key in sorted(set(a) | set(b)) if a.get(key) != b.get(key))
                    if not row["nonWaitActionsMatch"]:
                        first = next((i for i, pair in enumerate(itertools.zip_longest(old_actions, new_actions))
                                      if pair[0] != pair[1]), None)
                        row["differences"].append({"metric": "nonWaitActions", "se1Count": len(old_actions),
                                                   "se2Count": len(new_actions), "firstDifferentIndex": first,
                                                   "se1FirstDifference": old_actions[first] if first < len(old_actions) else None,
                                                   "se2FirstDifference": new_actions[first] if first < len(new_actions) else None})
                rows.append(row)
    return {"registeredComparisons": 48, "completedComparisons": sum(r["fiveMatch"] is not None for r in rows),
            "fiveMatches": sum(r["fiveMatch"] is True for r in rows),
            "securityMatches": sum(r["securityMatch"] is True for r in rows),
            "nonWaitActionMatches": sum(r["nonWaitActionsMatch"] is True for r in rows),
            "ignoredSecurityIdentityField": "incidentId",
            "referenceFileHashes": legacy.hashes, "comparisons": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--se1-reference", type=Path, help="Optional frozen raw SE1 directory for 48 original-condition comparisons")
    args = parser.parse_args()
    inputs = se1.Inputs(args.directory)
    manifest, summary = inputs.read("manifest.json"), inputs.read("summary.json")
    specs = {s["name"]: s for s in manifest["cases"]}
    require(len(specs) == manifest["registeredRuns"] == len(manifest["cases"]), "Duplicate/inconsistent registration")
    require(set(summary).issubset(specs), "Unregistered run")
    model_records = {n: inputs.read(s["modelFile"]) for n, s in specs.items()}
    if "modelSha256" in manifest:
        expected_files = {s["modelFile"] for s in specs.values()}
        require(set(manifest["modelSha256"]) == expected_files, "Frozen model file registry differs")
        require(all(inputs.hashes[name] == value for name, value in manifest["modelSha256"].items()),
                "Model bytes differ from preregistered hashes")
    models = {n: model["scenario"] for n, model in model_records.items()}
    for name, model in model_records.items():
        require(D(model["fixedPeriod"]) == D(specs[name]["fixedPeriodSeconds"]), name + ": model FixedPeriod differs")
    registered_attack_rows = validate_registration(manifest, specs, models)
    for name, spec in specs.items():
        require(spec["baselineCase"] in specs, name + ": unregistered baseline")
        validate_control(name, spec, models[name], specs[spec["baselineCase"]], models[spec["baselineCase"]])
    results = {}
    for name, row in summary.items():
        for k in ("fixture", "policy", "mode", "baselineCase", "modelFile", *METADATA):
            require(row[k] == specs[name][k], name + ": summary differs from registration: " + k)
        if row["status"] == "ERROR":
            require(row.get("error") and all(row.get(k) is None for k in
                    ("simulationMetrics", "securityMetrics", "pairedDelta", "eventsFile", "actualFile", "decisionsFile")),
                    name + ": ERROR fabricated journal or final metrics")
            result = {"status": "ERROR", "error": row["error"], "security": None, "five": None,
                      "diagnostics": [], "searchNanos": row.get("searchNanos"), "exploredNodes": row.get("exploredNodes"),
                      "decisions": row.get("decisions"), "actualAt": None}
        else:
            result = se1.audit_case(inputs, name, row)
            result["diagnostics"] = diagnostics(models[name], inputs.read(row["eventsFile"]), inputs.read(row["actualFile"]))
        result.update(metadata(specs[name]))
        result["pairedControlStatus"] = summary.get(row["baselineCase"], {}).get("status", "NOT_RUN")
        results[name] = result
    compared = 0
    unchecked_pairs = []
    for name, row in summary.items():
        if name != row["baselineCase"]:
            baseline = results.get(row["baselineCase"])
            if row["status"] == "ERROR" or baseline is None or baseline["status"] == "ERROR":
                require(row["pairedDelta"] is None, name + ": unavailable control used for paired final metrics")
                unchecked_pairs.append({"case": name, "baseline": row["baselineCase"], "reason": "JOURNAL_UNAVAILABLE"})
            else:
                compared += se1.pair_checks(name, row, results[name], baseline)
    args.out.mkdir(parents=True, exist_ok=True)
    se1.write_csv(args.out / "attack-times.csv", registered_attack_rows)
    write_tables(args.out, specs, results)
    timing_diagnostics = write_search_timing(args.out, specs, results, manifest["limits"])
    if args.se1_reference:
        overlap = compare_se1_overlap(args.se1_reference, results)
        (args.out / "se1-overlap.json").write_text(json.dumps(overlap, indent=2, default=str) + "\n", encoding="utf-8")
    for result in results.values():
        result.pop("_scenario", None)
        result.pop("_decisions", None)
    report = {"audit": "PASS", "independentOfJavaCollectors": True, "protocolSha256": manifest["protocolSha256"],
              "executableSha256": manifest["executableSha256"], "registeredRuns": len(specs), "auditedRuns": len(results),
              "notRun": sorted(set(specs) - set(results)), "finishedRuns": sum(r["status"] == "FINISHED" for r in results.values()),
              "preKnowledgeDecisionsCompared": compared, "unavailablePairChecks": unchecked_pairs,
              "journalAuditedRuns": sum(r["status"] != "ERROR" for r in results.values()),
              "searchTimingDiagnostics": timing_diagnostics,
              "fileHashes": inputs.hashes, "runs": results,
              "diagnosticSemantics": "Factual event-prefix inventory immediately before the incident/notice; frame at left limit. "
                "Next contact is strictly future target-window start; current target-window remainder is separate. "
                "Installed/prepared are disjoint PRE_ACTIVE counts. This audit is not a search-optimality or cryptography proof."}
    (args.out / "independent-audit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False, default=str) + "\n", encoding="utf-8")
    print(f"PASS: {len(results)}/{len(specs)} audited; {report['finishedRuns']} finished; {compared} pre-notification decisions")


if __name__ == "__main__":
    main()
