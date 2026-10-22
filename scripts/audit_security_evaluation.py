#!/usr/bin/env python3
"""Independently audit se1 journals using exact decimal arithmetic.

No Java collector, fixture score or forecast is used. Partial runs remain partial.
This is an accounting/invariant audit, not an independent simulator or a proof of
cryptographic strength or search optimality.
"""
from __future__ import annotations
import argparse
import calendar
import csv
import datetime as dt
from decimal import Decimal, ROUND_FLOOR
import hashlib
import json
from pathlib import Path
import re

D = Decimal
USAGE = ("elapsed", "spacecraftCpu", "groundCpu", "radioOccupancy", "uplinkBytes", "downlinkBytes")
TERMINALS = {"STEP_COMPLETED", "STEP_FAILED", "STEP_INTERRUPTED"}
TIME_FIELDS = {"disclosureAt", "lastCompromisedEmissionAt", "groundNoticeAt", "spacecraftNoticeAt",
               "firstIndependentFrameAt", "censorAt"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), parse_float=D)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def instant(value):
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z", value)
    require(match is not None, "Expected exact UTC instant: " + str(value))
    seconds = calendar.timegm(dt.datetime.strptime(match[1], "%Y-%m-%dT%H:%M:%S").timetuple())
    return D(seconds) + (D("0." + match[2]) if match[2] else D(0))


def zero_usage():
    return dict.fromkeys(USAGE, D(0))


def context(value):
    return value["satelliteId"], value["groundStationId"]


def consumed(cost, elapsed):
    remaining, result = max(D(0), elapsed), zero_usage()
    for phase in cost["phases"]:
        part = min(remaining, D(phase["duration"]))
        result["elapsed"] += part
        resource = phase["resource"]
        if resource == "GROUND_CPU":
            result["groundCpu"] += part
        elif resource == "SPACECRAFT_CPU":
            result["spacecraftCpu"] += part
        elif resource in {"UPLINK", "DOWNLINK"}:
            result["radioOccupancy"] += part
            result["uplinkBytes" if resource == "UPLINK" else "downlinkBytes"] += (
                D(phase["bytes"]) * part / D(phase["duration"])).to_integral_value(rounding=ROUND_FLOOR)
        remaining -= part
        if remaining == 0:
            break
    return result


def read_frame(event, contacts):
    """Actual emission interval; aborted frames never deliver useful payload."""
    require(event["type"] in {"PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"}, "Not a frame event")
    contact = contacts[event["contactId"]]
    payload, window = contact["payload"], contact["window"]
    full_duration = D(payload["frameBytes"]) / D(payload["bytesPerSecond"])
    frame = event.get("frameEvidence")
    if frame is None:
        require(event["type"] == "PAYLOAD_TRANSFERRED", "Aborted frame missing physical evidence")
        require(event["uplinkFrames"] + event["downlinkFrames"] == 1, "Expected one complete frame")
        up = event["uplinkFrames"] == 1
        frame = {"from": instant(event["at"]) - D(event["usefulTime"]), "until": instant(event["at"]),
                 "direction": "UPLINK" if up else "DOWNLINK", "keyId": event["keyIds"]["uplink" if up else "downlink"],
                 "frameBytes": event["uplinkPayloadBytes"] + event["downlinkPayloadBytes"],
                 "emittedBytes": event["uplinkPayloadBytes"] + event["downlinkPayloadBytes"], "delivered": True}
    else:
        frame = dict(frame)
        frame["from"], frame["until"] = instant(frame["from"]), instant(frame["until"])
    frame["fullDuration"] = full_duration
    frame["contactId"], frame["contextId"] = event["contactId"], event["contextId"]
    require(frame["frameBytes"] == payload["frameBytes"], "Evidence size differs from physical frame")
    require(frame["direction"] in {"UPLINK", "DOWNLINK"}, "Unknown frame direction")
    require(instant(window["start"]) <= frame["from"] <= frame["until"] <= instant(window["end"]),
            "Frame emission outside contact")
    require(frame["until"] == instant(event["at"]), "Frame event not at actual emission end")
    require(context(event["contextId"]) == context(window), "Frame/context station mismatch")
    duration = frame["until"] - frame["from"]
    require(0 <= duration <= full_duration, "Invalid emission duration")
    expected = (D(frame["frameBytes"]) * duration / full_duration).to_integral_value(rounding=ROUND_FLOOR)
    require(frame["emittedBytes"] == expected, "Incorrect partial-frame byte integral")
    direction = frame["direction"].lower()
    require(event["keyIds"][direction] == frame["keyId"], "Evidence/journal key mismatch")
    delivered = frame["delivered"]
    require(delivered == (event["type"] == "PAYLOAD_TRANSFERRED"), "Delivered/aborted inconsistency")
    require(event["usefulTime"] == (full_duration if delivered else 0), "Wrong useful frame time")
    for d in ("uplink", "downlink"):
        count = int(delivered and d == direction)
        require(event[d + "Frames"] == count and event[d + "PayloadBytes"] == count * frame["frameBytes"],
                "Delivery counters disagree with physical evidence")
    if delivered:
        require(duration == full_duration, "Delivered frame incomplete")
    return frame


def emitted_before(frame, boundary):
    elapsed = max(D(0), min(boundary, frame["until"]) - frame["from"])
    return min(frame["emittedBytes"], int((D(frame["frameBytes"]) * elapsed / frame["fullDuration"])
                                       .to_integral_value(rounding=ROUND_FLOOR)))


def overlap_after(frame, boundary):
    return max(D(0), frame["until"] - max(frame["from"], boundary))


def reconstruct_security(scenario, events, frames):
    incidents = scenario.get("disclosures", [])
    require(len(incidents) <= 1, "se1 permits one incident")
    incident = incidents[0] if incidents else None
    captures = [e for e in events if e["type"] in {"KEY_DISCLOSED", "DISCLOSURE_NO_TARGET"}]
    disclosed = [e for e in captures if e["type"] == "KEY_DISCLOSED"]
    require(len(captures) <= 1, "Repeated disclosure")
    pair = disclosed[0]["keyIds"] if disclosed else None
    known = set(pair.values()) if pair else set()
    grounds = [e for e in events if e["type"] == "REVOCATION_RECEIVED_GROUND"]
    spacecrafts = [e for e in events if e["type"] == "REVOCATION_RECEIVED_SPACECRAFT"]
    require(len(grounds) <= 1 and len(spacecrafts) <= 1, "Repeated endpoint notice")
    ground = instant(grounds[0]["at"]) if grounds else None
    remote = instant(spacecrafts[0]["at"]) if spacecrafts else None
    if captures:
        require(incident is not None and captures[0]["operationId"] == incident["id"]
                and instant(captures[0]["at"]) == instant(incident["at"])
                and context(captures[0]["contextId"]) == context(incident["contextId"]), "Wrong disclosure input")
    if grounds:
        require(pair is not None and grounds[0]["keyIds"] == pair, "Ground notice lacks disclosed pair")
        require(incident["detectionAt"] is not None and ground == instant(incident["detectionAt"]), "Wrong detection time")
    if spacecrafts:
        require(ground is not None and remote >= ground and spacecrafts[0]["keyIds"] == pair, "Invalid remote notice")
        contact = next(c for c in scenario["contacts"] if c["window"]["id"] == spacecrafts[0]["contactId"])
        require(context(contact["window"]) == context(incident["contextId"])
                and instant(contact["window"]["start"]) <= remote < instant(contact["window"]["end"]),
                "Remote notice without matching contact")
        require(any(e["type"] == "STEP_COMPLETED" and e["action"] == "NOTIFY_REVOCATION"
                    and instant(e["at"]) == remote and e["keyIds"] == pair for e in events),
                "Remote notice lacks completed management exchange")
    delivered = sum(f["frameBytes"] for f in frames if f["delivered"])
    emitted = sum(f["emittedBytes"] for f in frames)
    before = after = exposed_delivered = after_notice = after_remote = 0
    use = notice_use = remote_use = D(0)
    last = recovery = last_local = None
    for frame in frames:
        if frame["keyId"] in known:
            prior = emitted_before(frame, instant(incident["at"]))
            before += prior
            after += frame["emittedBytes"] - prior
            use += overlap_after(frame, instant(incident["at"]))
            last = frame["until"] if last is None else max(last, frame["until"])
            if frame["direction"] == "UPLINK":
                last_local = frame["until"] if last_local is None else max(last_local, frame["until"])
            exposed_delivered += frame["frameBytes"] if frame["delivered"] else 0
            if ground is not None:
                after_notice += frame["emittedBytes"] - emitted_before(frame, ground)
                notice_use += overlap_after(frame, ground)
                require(frame["from"] < ground, "New compromised frame admitted after known ground revocation")
                if frame["until"] > ground:
                    require(frame["direction"] == "DOWNLINK" and not frame["delivered"]
                            and frame["until"] - frame["from"] == frame["fullDuration"],
                            "Post-GS emission is not a committed, undelivered downlink tail")
                if remote is not None:
                    require(frame["until"] <= remote, "Disclosed material emitted after spacecraft notice")
            if remote is not None:
                after_remote += frame["emittedBytes"] - emitted_before(frame, remote)
                remote_use += overlap_after(frame, remote)
        elif ground is not None and frame["delivered"] and frame["until"] > ground \
                and context(frame["contextId"]) == context(incident["contextId"]):
            recovery = frame["until"] if recovery is None else min(recovery, frame["until"])
    status = ("NO_ATTACK" if incident is None else "NO_TARGET" if pair is None else "UNDETECTED" if ground is None
              else "CENSORED_NO_RECOVERY" if recovery is None else "RECOVERED")
    notification = zero_usage()
    for event in events:
        if event["action"] == "NOTIFY_REVOCATION":
            for field in USAGE:
                notification[field] += event["usage"][field]
    return {"incidentId": None if incident is None else incident["id"],
            "disclosureAt": None if incident is None else instant(incident["at"]), "disclosedKeys": pair,
            "deliveredBytes": delivered, "emittedBytes": emitted, "decryptableBeforeBytes": before,
            "decryptableAfterBytes": after, "compromisedDeliveredBytes": exposed_delivered,
            "confidentialDeliveredBytes": delivered - exposed_delivered, "compromisedUseAfter": use,
            "lastCompromisedEmissionAt": last, "groundNoticeAt": ground, "spacecraftNoticeAt": remote,
            "bytesAfterGroundNotice": after_notice, "useAfterGroundNotice": notice_use,
            "bytesAfterSpacecraftNotice": after_remote, "useAfterSpacecraftNotice": remote_use,
            "recoveryStatus": status, "firstIndependentFrameAt": recovery,
            "detectionToRecovery": None if recovery is None else recovery - ground,
            "detectionToLocalStop": None if ground is None else max(D(0), (last_local or ground) - ground),
            "groundToSpacecraftNotice": None if remote is None else remote - ground,
            "censorAt": instant(scenario["end"]) if status == "CENSORED_NO_RECOVERY" else None,
            "notificationCosts": notification}


def normalize_security(record):
    return {k: (instant(v) if k in TIME_FIELDS and v is not None else v) for k, v in record.items()}


class Inputs:
    def __init__(self, root):
        self.root, self.hashes = root.resolve(), {}

    def read(self, name):
        path = (self.root / name).resolve()
        require(path.is_relative_to(self.root), "Referenced file leaves experiment directory")
        self.hashes[path.relative_to(self.root).as_posix()] = sha(path)
        return load(path)


def deadline(key, scenario):
    limit = min(instant(key["notAfter"]), instant(key["createdAt"]) + D(scenario["constraints"]["maxMaterialAge"]))
    if key["activatedAt"] is not None:
        limit = min(limit, instant(key["activatedAt"]) + D(scenario["constraints"]["cryptoperiod"]))
    return limit


def audit_case(inputs, name, row):
    scenario = inputs.read(row["modelFile"])["scenario"]
    events = inputs.read(row["eventsFile"])
    decisions = inputs.read(row["decisionsFile"])
    actual = inputs.read(row["actualFile"])
    require(actual["status"] == row["status"], name + ": summary/actual status mismatch")
    contacts = {c["window"]["id"]: c for c in scenario["contacts"]}
    final_keys = actual["securityState"]["keys"]
    counters = {k["id"]: k["nextNonceCounter"] for k in scenario["initialState"]["keys"].values()}
    active_ids = {k["id"] for k in scenario["initialState"]["keys"].values() if k["status"] == "ACTIVE"}
    bindings = {context(c["id"]): {"uplink": c["uplink"]["keyId"], "downlink": c["downlink"]["keyId"]}
                for c in scenario["initialState"]["contexts"].values()}
    frames, seen_frames, starts, active_steps, step_ends = [], set(), {}, {}, {}
    progress = {cid: {"uplinkBytes": 0, "downlinkBytes": 0, "usefulTime": D(0)} for cid in contacts}
    usage, useful, created, latest = zero_usage(), D(0), 0, instant(scenario["start"])
    for index, event in enumerate(events):
        at = instant(event["at"])
        require(event["sequence"] == index and at >= latest, name + ": journal order/identity failure")
        latest = at
        require(at <= instant(actual["at"]), name + ": event beyond observed execution")
        for field in USAGE:
            require(event["usage"][field] >= 0, name + ": negative cost")
            usage[field] += event["usage"][field]
        useful += event["usefulTime"]
        created += event["createdKeys"]
        identity = event["operationId"], event["action"]
        if event["type"] == "STEP_STARTED":
            require(identity not in active_steps, name + ": overlapping duplicate primitive start")
            occurrence = identity + (index,)
            starts[occurrence] = at
            active_steps[identity] = occurrence
        if event["type"] in TERMINALS:
            require(identity in active_steps, name + ": missing start or double-charged primitive")
            occurrence = active_steps.pop(identity)
            step_ends[occurrence] = at
            require(consumed(scenario["costs"]["operations"][event["action"]], at - starts[occurrence]) == event["usage"],
                    name + ": terminal cost differs from independent phase integral")
        else:
            require(event["usage"] == zero_usage(), name + ": cost outside terminal primitive")
        if event["type"] == "STEP_COMPLETED" and event["action"] == "PREPARE":
            require(event["createdKeys"] == 2, name + ": wrong directional creation count")
            for key_id in event["keyIds"].values():
                require(key_id not in counters, name + ": repeated created key ID")
                counters[key_id] = 0
        else:
            require(event["createdKeys"] == 0, name + ": keys created without completed PREPARE")
        if event["type"] == "STEP_COMPLETED" and event["action"] == "SWITCH":
            previous = bindings.get(context(event["contextId"]))
            if previous:
                active_ids.difference_update(previous.values())
            bindings[context(event["contextId"])] = event["keyIds"]
            active_ids.update(event["keyIds"].values())
        if event["type"] == "STEP_COMPLETED" and event["action"] == "EXPIRE_CONTEXT":
            bindings[context(event["contextId"])] = None
            if event["keyIds"]:
                active_ids.difference_update(event["keyIds"].values())
        if event["type"] == "KEY_DISCLOSED":
            require(bindings.get(context(event["contextId"])) == event["keyIds"], name + ": disclosure did not capture bound pair")
            for key_id in event["keyIds"].values():
                key = final_keys[key_id]
                require(key_id in active_ids and key["activatedAt"] is not None and instant(key["activatedAt"]) <= at < deadline(key, scenario),
                        name + ": disclosed pair not active and unexpired")
        if event["type"] == "DISCLOSURE_NO_TARGET":
            pair = bindings.get(context(event["contextId"]))
            valid = pair and all(key_id in active_ids and key_id in final_keys
                                 and final_keys[key_id]["activatedAt"] is not None
                                 and instant(final_keys[key_id]["activatedAt"]) <= at < deadline(final_keys[key_id], scenario)
                                 for key_id in pair.values())
            require(not valid, name + ": reported NO_TARGET despite an active bound pair")
        if event["type"] == "REVOCATION_RECEIVED_GROUND":
            active_ids.difference_update(event["keyIds"].values())
        if event["type"] not in {"PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"}:
            require(event["usefulTime"] == 0 and event["uplinkPayloadBytes"] == event["downlinkPayloadBytes"] == 0,
                    name + ": payload outside factual frame")
            continue
        frame = read_frame(event, contacts)
        frame_id = frame["contactId"], frame["from"], frame["direction"]
        require(frame_id not in seen_frames, name + ": duplicate factual frame")
        seen_frames.add(frame_id)
        key = final_keys[frame["keyId"]]
        require(context(key["contextId"]) == context(frame["contextId"]) and key["direction"] == frame["direction"],
                name + ": wrong directional/context key")
        require(key["activatedAt"] is not None and instant(key["activatedAt"]) <= frame["from"] < deadline(key, scenario)
                and frame["until"] <= deadline(key, scenario), name + ": frame exceeds activation/deadline")
        if key["deactivatedAt"] is not None and frame["until"] > instant(key["deactivatedAt"]):
            require(frame["direction"] == "DOWNLINK" and not frame["delivered"]
                    and frame["from"] < instant(key["deactivatedAt"])
                    and frame["until"] - frame["from"] == frame["fullDuration"]
                    and any(e["type"] == "REVOCATION_RECEIVED_GROUND" and frame["keyId"] in e["keyIds"].values()
                            and e["at"] == key["deactivatedAt"] for e in events),
                    name + ": retired key used outside precommitted downlink tail")
        require(bindings.get(context(event["contextId"])) == event["keyIds"], name + ": frame not tied to bound keys")
        if frame["delivered"]:
            counters[frame["keyId"]] += 1
            p = progress[frame["contactId"]]
            p[frame["direction"].lower() + "Bytes"] += frame["frameBytes"]
            p["usefulTime"] += frame["fullDuration"]
        else:
            # Legacy counters count confirmed complete frames. Partial ciphertext
            # cannot reuse a nonce because its affected key is irreversibly retired.
            require(key["status"] in {"DEACTIVATED", "DESTROYED"}, name + ": aborted key may be reused")
        frames.append(frame)
    previous_end = None
    for frame in sorted(frames, key=lambda f: (f["from"], f["until"])):
        require(previous_end is None or frame["from"] >= previous_end, name + ": overlapping payload emissions")
        previous_end = frame["until"]
    # Independently reconstruct physically occupied radio phases. WAIT/CPU
    # phases do not fabricate airtime; unfinished phases are clipped at actualAt.
    radio = []
    for identity, start in starts.items():
        cursor = start
        stop = step_ends.get(identity, instant(actual["at"]))
        for phase in scenario["costs"]["operations"][identity[1]]["phases"]:
            end = cursor + D(phase["duration"])
            actual_end = min(end, stop)
            if phase["resource"] in {"UPLINK", "DOWNLINK"} and actual_end > cursor:
                radio.append((cursor, actual_end, identity))
            cursor = end
    for frame in frames:
        require(all(frame["until"] <= start or frame["from"] >= end for start, end, _ in radio),
                name + ": payload overlaps management radio occupancy")
    for key_id, key in final_keys.items():
        require(counters[key_id] == key["nextNonceCounter"], name + ": confirmed-frame nonce conservation failure")
    for cid, p in progress.items():
        payload = contacts[cid]["payload"]
        require(p["uplinkBytes"] <= payload["offeredUplinkBytes"] and p["downlinkBytes"] <= payload["offeredDownlinkBytes"],
                name + ": payload exceeds offered demand")
        p["served"] = p["uplinkBytes"] >= payload["minimumUplinkBytes"] and p["downlinkBytes"] >= payload["minimumDownlinkBytes"]
    five = {"servedContacts": sum(p["served"] for p in progress.values()), "usefulSecureTime": useful,
            "controlCpu": usage["groundCpu"] + usage["spacecraftCpu"],
            "managementBytes": usage["uplinkBytes"] + usage["downlinkBytes"], "newKeys": created}
    security = reconstruct_security(scenario, events, frames)
    incidents = scenario.get("disclosures", [])
    expected_capture = int(bool(incidents) and instant(incidents[0]["at"]) <= instant(actual["at"]))
    require(sum(e["type"] in {"KEY_DISCLOSED", "DISCLOSURE_NO_TARGET"} for e in events) == expected_capture,
            name + ": missing or extra physical disclosure event")
    finished = row["status"] == "FINISHED"
    if finished:
        m = row["simulationMetrics"]
        require(m == actual["finalMetrics"], name + ": actual/summary simulation metrics mismatch")
        require(m["controlCosts"] == usage and m["servedContacts"] == five["servedContacts"]
                and m["usefulSecureTime"] == useful and m["createdKeys"] == created, name + ": five metrics differ from actual journal")
        for outcome in m["contacts"]:
            for field, value in progress[outcome["contactId"]].items():
                require(outcome[field] == value, name + ": contact progress differs")
        require(normalize_security(row["securityMetrics"]) == security, name + ": security metrics differ from independent frame reconstruction")
        require(sum(e["type"] == "SIMULATION_ENDED" for e in events) == 1 and actual["at"] == scenario["end"],
                name + ": missing factual completion")
    else:
        require(row["simulationMetrics"] is None and row["securityMetrics"] is None and row["pairedDelta"] is None,
                name + ": partial run presented as final")
        partial, accrued = actual["partialProgress"], dict(usage)
        for pending in actual["pendingOperations"]:
            step = pending["steps"][pending["stepIndex"]]
            work = consumed(scenario["costs"]["operations"][step], instant(actual["at"]) - instant(pending["stepStartedAt"]))
            for field in USAGE:
                accrued[field] += work[field]
        require(partial["recordedCosts"] == usage and partial["accruedCosts"] == accrued, name + ": partial costs double counted/lost")
        require(sum(e["type"] == "SIMULATION_ENDED" for e in events) == 0, name + ": unfinished run finalized")
    require(len(decisions) == row["decisions"], name + ": decision count mismatch")
    require(sum(d.get("searchNanos") or 0 for d in decisions) == row["searchNanos"]
            and sum(d.get("exploredNodes") or 0 for d in decisions) == row["exploredNodes"], name + ": search accounting mismatch")
    if row["policy"] in {"fixed", "percontact"}:
        require(row["searchNanos"] == row["exploredNodes"] == 0, name + ": baseline fabricated search effort")
    return {"status": row["status"], "fixture": row["fixture"], "policy": row["policy"], "mode": row["mode"],
            "five": five, "security": security if finished else None, "pairedDelta": row["pairedDelta"],
            "searchNanos": row["searchNanos"], "exploredNodes": row["exploredNodes"], "decisions": row["decisions"],
            "events": len(events), "frames": len(frames), "abortedFrames": sum(not f["delivered"] for f in frames),
            "recordedCosts": usage, "cryptoperiodSeconds": scenario["constraints"]["cryptoperiod"],
            "contactCount": len(contacts),
            "actualAt": actual["at"], "_scenario": scenario, "_decisions": decisions}


def pair_checks(name, row, case, baseline):
    a, b = dict(case["_scenario"]), dict(baseline["_scenario"])
    for value in (a, b):
        value.pop("name", None)
        value.pop("disclosures", None)
    require(a == b and case["policy"] == baseline["policy"], name + ": paired conditions differ")
    incident = case["_scenario"].get("disclosures", [])
    notice = case["security"]["groundNoticeAt"] if case["security"] else None
    if notice is None and case["status"] != "FINISHED" and incident and incident[0]["detectionAt"]:
        notice = instant(incident[0]["detectionAt"])
    cutoff = min(instant(case["actualAt"]), instant(baseline["actualAt"]))
    if notice is not None:
        cutoff = min(cutoff, notice)

    def prefix(c):
        return [(instant(d["at"]), d["executedAction"]) for d in c["_decisions"]
                if instant(d["at"]) < cutoff and d["searchStatus"] not in {"INCOMPLETE", "NO_FEASIBLE_PLAN"}]

    require(prefix(case) == prefix(baseline), name + ": hidden incident changed decisions before notice")
    if case["status"] == baseline["status"] == "FINISHED":
        x, y, sx, sy = baseline["five"], case["five"], baseline["security"], case["security"]
        expected = {"lostServedContacts": x["servedContacts"] - y["servedContacts"],
                    "lostDeliveredBytes": sx["deliveredBytes"] - sy["deliveredBytes"],
                    "lostUsefulTime": x["usefulSecureTime"] - y["usefulSecureTime"],
                    "extraControlCpu": y["controlCpu"] - x["controlCpu"],
                    "extraManagementBytes": y["managementBytes"] - x["managementBytes"],
                    "extraCreatedKeys": y["newKeys"] - x["newKeys"]}
        require(row["pairedDelta"] == expected, name + ": paired delta differs from independent totals")
    else:
        require(row["pairedDelta"] is None, name + ": paired final delta uses unfinished run")
    return len(prefix(case))


def write_csv(path, rows, fields=None):
    if fields is None:
        fields = list(rows[0]) if rows else []
    with path.open("w", newline="", encoding="utf-8") as out:
        writer = csv.DictWriter(out, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def outcome(a, b, high=True):
    if a is None or b is None:
        return "NOT_COMPARABLE"
    return "TIE" if a == b else "WIN" if ((a > b) == high) else "LOSS"


def publish_tables(output, results):
    rows, excluded = [], []
    for name, r in results.items():
        common = {"case": name, "fixture": r["fixture"], "policy": r["policy"], "mode": r["mode"], "status": r["status"],
                  "cryptoperiodSeconds": r["cryptoperiodSeconds"], "searchSeconds": D(r["searchNanos"]) / 10**9,
                  "exploredNodes": r["exploredNodes"], "decisions": r["decisions"], "contactCount": r["contactCount"]}
        if r["status"] != "FINISHED":
            excluded.append(common)
            continue
        s = r["security"]
        rows.append(common | r["five"] | {k: s[k] for k in ("deliveredBytes", "emittedBytes", "decryptableBeforeBytes",
                    "decryptableAfterBytes", "compromisedDeliveredBytes", "confidentialDeliveredBytes", "compromisedUseAfter",
                    "bytesAfterGroundNotice", "useAfterGroundNotice", "bytesAfterSpacecraftNotice", "useAfterSpacecraftNotice",
                    "recoveryStatus", "detectionToRecovery", "groundToSpacecraftNotice")} |
                    {"notificationCpu": s["notificationCosts"]["groundCpu"] + s["notificationCosts"]["spacecraftCpu"],
                     "notificationBytes": s["notificationCosts"]["uplinkBytes"] + s["notificationCosts"]["downlinkBytes"],
                     "abortedFrames": r["abortedFrames"]} | (r["pairedDelta"] or {k: None for k in (
                         "lostServedContacts", "lostDeliveredBytes", "lostUsefulTime", "extraControlCpu", "extraManagementBytes", "extraCreatedKeys")}))
    write_csv(output / "metrics.csv", rows)
    write_csv(output / "excluded.csv", excluded, ["case", "fixture", "policy", "mode", "status", "cryptoperiodSeconds",
                                                 "searchSeconds", "exploredNodes", "decisions", "contactCount"])
    pairs = []
    for left in rows:
        for right in rows:
            if left["fixture"] != right["fixture"] or left["mode"] != right["mode"] or left["policy"] >= right["policy"]:
                continue
            both_targeted = left["recoveryStatus"] not in {"NO_ATTACK", "NO_TARGET"} and right["recoveryStatus"] not in {"NO_ATTACK", "NO_TARGET"}
            pairs.append({"fixture": left["fixture"], "mode": left["mode"], "left": left["policy"], "right": right["policy"],
                          "service": outcome(left["servedContacts"], right["servedContacts"]),
                          "delivered": outcome(left["deliveredBytes"], right["deliveredBytes"]),
                          "confidentialDelivered": outcome(left["confidentialDeliveredBytes"], right["confidentialDeliveredBytes"]),
                          "exposure": outcome(left["decryptableBeforeBytes"] + left["decryptableAfterBytes"],
                                              right["decryptableBeforeBytes"] + right["decryptableAfterBytes"], False),
                          "bothHadTarget": both_targeted,
                          "recovery": outcome(left["detectionToRecovery"], right["detectionToRecovery"], False),
                          "cpu": outcome(left["controlCpu"], right["controlCpu"], False),
                          "extraCpu": outcome(left["extraControlCpu"], right["extraControlCpu"], False)})
    write_csv(output / "pairwise.csv", pairs)
    grouped = {}
    for p in pairs:
        for metric in ("service", "delivered", "confidentialDelivered", "exposure", "recovery", "cpu", "extraCpu"):
            key = p["left"], p["right"], p["mode"], metric, p[metric]
            grouped[key] = grouped.get(key, 0) + 1
    write_csv(output / "win-tie-loss.csv", [{"left": k[0], "right": k[1], "mode": k[2], "metric": k[3],
                                            "outcome": k[4], "count": v} for k, v in sorted(grouped.items())])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--out", type=Path, required=True, help="Audit/tables output directory")
    args = parser.parse_args()
    inputs = Inputs(args.directory)
    manifest, summary = inputs.read("manifest.json"), inputs.read("summary.json")
    registered = {s["name"] for s in manifest["cases"]}
    require(set(summary).issubset(registered), "Unregistered experiment case")
    results = {name: audit_case(inputs, name, row) for name, row in summary.items()}
    compared = 0
    for name, row in summary.items():
        if name != row["baselineCase"]:
            require(row["baselineCase"] in results, name + ": no paired baseline")
            compared += pair_checks(name, row, results[name], results[row["baselineCase"]])
    for r in results.values():
        r.pop("_scenario")
        r.pop("_decisions")
    args.out.mkdir(parents=True, exist_ok=True)
    publish_tables(args.out, results)
    report = {"audit": "PASS", "independentOfJavaCollectors": True, "protocolSha256": manifest["protocolSha256"],
              "executableSha256": manifest["executableSha256"], "registeredRuns": manifest["registeredRuns"],
              "auditedRuns": len(results), "notRun": sorted(registered - set(summary)),
              "finishedRuns": sum(r["status"] == "FINISHED" for r in results.values()),
              "preKnowledgeDecisionsCompared": compared, "fileHashes": inputs.hashes, "runs": results}
    (args.out / "independent-audit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False, default=str) + "\n", encoding="utf-8")
    print(f"PASS: {len(results)} cases; {report['finishedRuns']} finished; {compared} pre-notification decisions; {args.out}")


if __name__ == "__main__":
    main()
