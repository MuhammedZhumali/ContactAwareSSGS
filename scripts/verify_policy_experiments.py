#!/usr/bin/env python3
"""Independently audit FourPolicyExperimentMain JSON with exact decimal arithmetic.

No Java classes or MetricsCollector are invoked. This checks event accounting,
directional service thresholds, frame/key bounds, nonce conservation, baseline
rules and incomplete-run separation. It is not an independent simulator or a
proof that a search result is optimal. Only cases published in summary.json are
read, so the script can inspect the completed prefix of an ongoing experiment.
"""

import argparse
import calendar
import datetime as dt
from decimal import Decimal, ROUND_FLOOR
import hashlib
import json
from pathlib import Path
import re

D = Decimal
USAGE = ("elapsed", "spacecraftCpu", "groundCpu", "radioOccupancy", "uplinkBytes", "downlinkBytes")
TERMINAL_STEPS = {"STEP_COMPLETED", "STEP_FAILED", "STEP_INTERRUPTED"}


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), parse_float=D)


def instant(value):
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z", value)
    if match is None:
        raise ValueError("Expected exact UTC instant: " + value)
    seconds = calendar.timegm(dt.datetime.strptime(match[1], "%Y-%m-%dT%H:%M:%S").timetuple())
    return D(seconds) + (D("0." + match[2]) if match[2] else D(0))


def context(value):
    return value["satelliteId"], value["groundStationId"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def zero_usage():
    return dict.fromkeys(USAGE, D(0))


def consumed(cost, elapsed):
    """Integrate ordered model phases; incomplete radio bytes are floored once."""
    result = zero_usage()
    remaining = max(D(0), elapsed)
    for phase in cost["phases"]:
        part = min(remaining, phase["duration"])
        result["elapsed"] += part
        resource = phase["resource"]
        if resource == "GROUND_CPU":
            result["groundCpu"] += part
        elif resource == "SPACECRAFT_CPU":
            result["spacecraftCpu"] += part
        elif resource in ("UPLINK", "DOWNLINK"):
            result["radioOccupancy"] += part
            field = "uplinkBytes" if resource == "UPLINK" else "downlinkBytes"
            result[field] += (D(phase["bytes"]) * part / phase["duration"]).to_integral_value(rounding=ROUND_FLOOR)
        remaining -= part
        if remaining == 0:
            break
    return result


def audit_case(directory, name, summary):
    model_path = directory / (summary["fixture"] + ".model.json")
    events_path = directory / summary["actualEventsFile"]
    model = load(model_path)
    scenario = model["scenario"]
    events = load(events_path)
    actual = load(directory / summary["actualResultFile"])
    decisions = load(directory / summary["compactDecisionsFile"])
    contacts = {c["window"]["id"]: c for c in scenario["contacts"]}
    progress = {cid: {"up": 0, "down": 0, "time": D(0), "frames": 0} for cid in contacts}
    costs = zero_usage()
    new_keys = 0
    starts = {}
    nonces = {}
    final_keys = actual["securityState"]["keys"]
    anchors = {}
    for key in scenario["initialState"]["keys"].values():
        nonces[key["id"]] = key["nextNonceCounter"]
        if key["activatedAt"] is not None:
            ctx = context(key["contextId"])
            anchors[ctx] = max(anchors.get(ctx, instant(key["activatedAt"])), instant(key["activatedAt"]))
    prescribed = {p["id"] for p in scenario["plan"]}
    fixed = summary["policyName"] == "FIXED_PERIOD"
    per_contact = summary["policyName"] == "PER_CONTACT"
    period = summary["policyConfiguration"].get("period")
    renewal_starts = []
    latest = instant(scenario["start"])
    for index, event in enumerate(events):
        at = instant(event["at"])
        require(event["sequence"] == index, f"{name}: non-contiguous event sequence")
        require(at >= latest, f"{name}: event time moved backwards")
        latest = at
        for field in USAGE:
            require(event["usage"][field] >= 0, f"{name}: negative {field}")
            costs[field] += event["usage"][field]
        new_keys += event["createdKeys"]
        identity = event["operationId"], event["action"]
        if event["type"] == "STEP_STARTED":
            starts[identity] = at
        if event["type"] in TERMINAL_STEPS:
            expected = consumed(scenario["costs"]["operations"][event["action"]], at - starts.get(identity, at))
            require(expected == event["usage"], f"{name}: phase charge differs for {identity} at {event['at']}")
        else:
            require(event["usage"] == zero_usage(), f"{name}: charge outside terminal primitive step")
        if event["type"] == "ACTION_STARTED" and event["action"] in {"PREPARE", "INSTALL", "SWITCH"}:
            ctx = context(event["contextId"])
            anchor = anchors.get(ctx)
            if fixed and event["operationId"] not in prescribed:
                require(anchor is None or at >= anchor + period, f"{name}: renewal before its activation-relative timer")
            renewal_starts.append({"at": event["at"], "action": event["action"], "station": ctx[1],
                                   "secondsAfterAnchor": None if anchor is None else at - anchor})
        if event["type"] == "STEP_COMPLETED" and event["action"] == "PREPARE":
            require(event["createdKeys"] == 2, f"{name}: PREPARE did not create two directed keys")
            for key_id in event["keyIds"].values():
                require(key_id not in nonces, f"{name}: key ID reused")
                nonces[key_id] = 0
        elif event["createdKeys"]:
            raise ValueError(f"{name}: keys charged outside completed PREPARE")
        if event["type"] == "STEP_COMPLETED" and event["action"] == "SWITCH":
            anchors[context(event["contextId"])] = at
        if event["type"] != "PAYLOAD_TRANSFERRED":
            require(event["usefulTime"] == 0, f"{name}: useful time outside payload event")
            continue
        contact = contacts[event["contactId"]]
        window, payload = contact["window"], contact["payload"]
        duration = D(payload["frameBytes"]) / payload["bytesPerSecond"]
        frame_start = at - duration
        require(event["usefulTime"] == duration, f"{name}: wrong full-frame duration")
        require(event["uplinkFrames"] + event["downlinkFrames"] == 1, f"{name}: payload event is not one full frame")
        require(instant(window["start"]) <= frame_start < at <= instant(window["end"]), f"{name}: frame outside contact")
        require(context(event["contextId"]) == context(window), f"{name}: frame station mismatch")
        for direction in ("uplink", "downlink"):
            frames = event[direction + "Frames"]
            require(event[direction + "PayloadBytes"] == frames * payload["frameBytes"], f"{name}: frame byte count mismatch")
            key_id = event["keyIds"][direction]
            key = final_keys[key_id]
            require(context(key["contextId"]) == context(window), f"{name}: key reused across stations")
            require(key["activatedAt"] is not None and instant(key["activatedAt"]) <= frame_start, f"{name}: use before activation")
            deadline = min(instant(key["notAfter"]), instant(key["createdAt"]) + scenario["constraints"]["maxMaterialAge"],
                           instant(key["activatedAt"]) + scenario["constraints"]["cryptoperiod"])
            require(frame_start < deadline and at <= deadline, f"{name}: cryptoperiod/material deadline exceeded")
            require(key["deactivatedAt"] is None or at <= instant(key["deactivatedAt"]), f"{name}: retired material used")
            if per_contact:
                require(instant(window["start"]) <= instant(key["activatedAt"]) < instant(window["end"]),
                        f"{name}: per-contact payload used historical active material")
            nonces[key_id] += frames
        row = progress[event["contactId"]]
        row["up"] += event["uplinkPayloadBytes"]
        row["down"] += event["downlinkPayloadBytes"]
        row["time"] += duration
        row["frames"] += 1
    for key_id, key in final_keys.items():
        require(nonces[key_id] == key["nextNonceCounter"], f"{name}: nonce counter mismatch for {key_id}")
    served = 0
    for cid, row in progress.items():
        payload = contacts[cid]["payload"]
        require(row["up"] <= payload["offeredUplinkBytes"] and row["down"] <= payload["offeredDownlinkBytes"], f"{name}: offered payload exceeded")
        row["served"] = row["up"] >= payload["minimumUplinkBytes"] and row["down"] >= payload["minimumDownlinkBytes"]
        served += row["served"]
    five = {"servedContacts": served, "usefulSecureTime": sum((r["time"] for r in progress.values()), D(0)),
            "controlCpu": costs["spacecraftCpu"] + costs["groundCpu"],
            "managementBytes": costs["uplinkBytes"] + costs["downlinkBytes"], "newKeys": new_keys}
    finished = summary["status"] == "FINISHED"
    end_events = sum(e["type"] == "SIMULATION_ENDED" for e in events)
    require(actual["status"] == summary["status"], f"{name}: actual/summary status mismatch")
    if finished:
        require(summary["finalMetrics"] == five, f"{name}: summary differs from independent five metrics")
        require(summary["partialProgress"] is None and actual["partialProgress"] is None, f"{name}: finished run labeled partial")
        require(end_events == 1 and actual["at"] == scenario["end"], f"{name}: no actual experiment completion")
        metrics = actual["finalMetrics"]
        require(metrics["controlCosts"] == costs, f"{name}: actual costs differ from event sum")
        require(metrics["servedContacts"] == served and metrics["usefulSecureTime"] == five["usefulSecureTime"]
                and metrics["createdKeys"] == new_keys, f"{name}: actual metrics differ from independent totals")
        require(len(metrics["contacts"]) == len(contacts), f"{name}: missing contact outcome")
        for outcome in metrics["contacts"]:
            cid = outcome["contactId"]
            row, payload = progress[cid], contacts[cid]["payload"]
            require(outcome["served"] == row["served"] and outcome["usefulTime"] == row["time"]
                    and outcome["uplinkBytes"] == row["up"] and outcome["downlinkBytes"] == row["down"]
                    and outcome["uplinkFrames"] * payload["frameBytes"] == row["up"]
                    and outcome["downlinkFrames"] * payload["frameBytes"] == row["down"],
                    f"{name}: contact outcome differs from payload events for {cid}")
    else:
        require(summary["finalMetrics"] is None and actual["finalMetrics"] is None and end_events == 0, f"{name}: fabricated final metrics")
        partial = summary["partialProgress"]
        require(partial["recordedCosts"] == costs, f"{name}: partial recorded costs differ")
        accrued = costs.copy()
        for operation in actual["pendingOperations"]:
            action = operation["steps"][operation["stepIndex"]]
            remaining = consumed(scenario["costs"]["operations"][action], instant(actual["at"]) - instant(operation["stepStartedAt"]))
            for field in USAGE:
                accrued[field] += remaining[field]
        require(partial["accruedCosts"] == accrued, f"{name}: accrued costs double counted or pending work lost")
        require(partial["servedContactsSoFar"] == served and partial["createdKeysSoFar"] == new_keys
                and partial["usefulSecureTimeSoFar"] == five["usefulSecureTime"], f"{name}: wrong partial progress")
    if fixed or per_contact:
        require(not summary["searchStatuses"] and summary["exploredNodes"] == summary["searchNanos"] == 0,
                f"{name}: fabricated search certificate for rule policy")
        require(all(d["source"] == "RULE" and d["searchStatus"] is None for d in decisions), f"{name}: wrong rule trace")
    return {"status": summary["status"], "independentRecordedMetrics": five, "contacts": progress,
            "renewalStarts": renewal_starts, "events": len(events),
            "modelSha256": hashlib.sha256(model_path.read_bytes()).hexdigest(),
            "eventsSha256": hashlib.sha256(events_path.read_bytes()).hexdigest()}


def audit_directory(directory):
    return {name: audit_case(directory, name, summary) for name, summary in load(directory / "summary.json").items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directories", nargs="+", type=Path)
    parser.add_argument("--out", type=Path, help="Optional JSON report; decimal values are emitted as exact strings")
    args = parser.parse_args()
    report = {str(path): audit_directory(path) for path in args.directories}
    text = json.dumps({"audit": "PASS", "independentOfJavaMetricsCollector": True, "runs": report},
                      indent=2, ensure_ascii=False, default=str) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text, encoding="utf-8")
        print(f"PASS: {sum(len(cases) for cases in report.values())} cases; report: {args.out}")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()
