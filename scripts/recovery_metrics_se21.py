#!/usr/bin/env python3
"""Postprocess frozen SE2 journals; never execute policies or invent traffic.

Exact decimal seconds, prefix-only lifecycle reconstruction, explicit censoring.
See docs/security-evaluation-se21/metrics-spec.md for the measurement contract.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import copy
import datetime as dt
from decimal import Decimal as D
import json
from pathlib import Path

import audit_security_evaluation as se1

require, instant, context = se1.require, se1.instant, se1.context
DIRECTIONS = ("uplink", "downlink")
MAX_COUNTER = 2**63 - 1
MILESTONES = ("spacecraft_notice", "independent_pair", "independent_sa", "independent_frame")


def utc(value):
    if value is None:
        return None
    whole = int(value)
    base = dt.datetime.fromtimestamp(whole, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
    ns = int((value - whole) * 10**9)
    return base + ("." + f"{ns:09d}".rstrip("0") if ns else "") + "Z"


class Replay:
    """Only initial state plus completed, observed lifecycle facts are inputs."""

    def __init__(self, scenario, disclosed):
        self.scenario = scenario
        self.keys = copy.deepcopy(scenario["initialState"]["keys"])
        self.contexts = {context(c["id"]): copy.deepcopy(c)
                         for c in scenario["initialState"]["contexts"].values()}
        self.contacts = {c["window"]["id"]: c for c in scenario["contacts"]}
        self.disclosed = set(disclosed)
        self.blocked = set(scenario["constraints"].get("blockedKeyIds", []))
        self.authorized = {context(c) for c in scenario["constraints"]["authorizedContexts"]}
        self.delivered = defaultdict(lambda: dict.fromkeys(DIRECTIONS, 0))
        self.sc_notice = None
        self.prepared_pairs = []
        self.activation_evidence = {}
        self.open_evidence = {}
        for key in self.keys.values():
            key["deadline"] = se1.deadline(key, scenario)

    def pair_ids(self, cid):
        c = self.contexts[cid]
        return {d: c[d]["keyId"] for d in DIRECTIONS}

    def stop(self, cid, at):
        c = self.contexts[cid]
        if c["uplink"]["status"] != "OPERATIONAL":
            return
        for d in DIRECTIONS:
            if c.get(d + "FramesInSession", 0):
                self.keys[c[d]["keyId"]]["lastSuccessfulContactEnd"] = utc(at)
            c[d]["status"] = "KEYED"
            c[d + "FramesInSession"] = 0
        c["activeContact"], c["openedAt"] = None, None

    def retire_binding(self, cid, at):
        self.stop(cid, at)
        c = self.contexts[cid]
        for d in DIRECTIONS:
            key_id = c[d]["keyId"]
            if key_id:
                self.keys[key_id]["status"] = "DEACTIVATED"
            c[d].update(status="UNKEYED", keyId=None, peerSynchronized=False, nextSequenceNumber=0)

    def apply(self, event):
        at = instant(event["at"])
        kind, action = event["type"], event.get("action")
        pair = event.get("keyIds") or {}
        cid = context(event["contextId"]) if event.get("contextId") else None
        if kind == "REVOCATION_RECEIVED_GROUND":
            self.blocked.update(pair.values())
            if self.pair_ids(cid) == pair:
                self.retire_binding(cid, at)
            for key_id in pair.values():
                self.keys[key_id]["status"] = "DEACTIVATED"
        elif kind == "REVOCATION_RECEIVED_SPACECRAFT":
            self.sc_notice = at
        elif kind == "STEP_COMPLETED":
            c = self.contexts[cid]
            if action == "PREPARE":
                for d, key_id in pair.items():
                    require(key_id not in self.keys, "Repeated PREPARE ID")
                    limit = at + D(self.scenario["constraints"]["maxMaterialAge"])
                    self.keys[key_id] = dict(id=key_id, contextId=event["contextId"], direction=d.upper(),
                        status="PRE_ACTIVE", createdAt=event["at"], activatedAt=None, notAfter=utc(limit),
                        deadline=limit, installedOnSpacecraft=False, nextNonceCounter=0, lastSuccessfulContactEnd=None)
                self.prepared_pairs.append(dict(pair))
            elif action == "INSTALL":
                for key_id in pair.values():
                    self.keys[key_id]["installedOnSpacecraft"] = True
            elif action == "SWITCH":
                require(all(self.keys[k]["status"] == "PRE_ACTIVE" for k in pair.values()), "Reactivation of retired material")
                self.retire_binding(cid, at)
                for d, key_id in pair.items():
                    key = self.keys[key_id]
                    key.update(status="ACTIVE", activatedAt=event["at"])
                    key["deadline"] = min(key["deadline"], at + D(self.scenario["constraints"]["cryptoperiod"]))
                    c[d].update(status="KEYED", keyId=key_id, peerSynchronized=True,
                                nextSequenceNumber=key["nextNonceCounter"])
                self.activation_evidence[cid] = event["sequence"]
            elif action in {"OPEN", "REUSE"}:
                window = self.contacts[event["contactId"]]["window"]
                for d in DIRECTIONS:
                    key = self.keys[c[d]["keyId"]]
                    previous = key.get("lastSuccessfulContactEnd")
                    if previous is not None:
                        gap = instant(window["start"]) - instant(previous)
                        require(0 <= gap <= D(self.scenario["constraints"]["maxReuseGap"]), "Invalid factual OPEN reuse gap")
                    c[d]["status"] = "OPERATIONAL"
                    c[d + "FramesInSession"] = 0
                c["activeContact"], c["openedAt"] = window, event["at"]
                self.open_evidence[cid] = event["sequence"]
            elif action == "STOP":
                self.stop(cid, at)
            elif action == "EXPIRE_CONTEXT":
                self.retire_binding(cid, at)
            elif action != "NOTIFY_REVOCATION":
                raise ValueError("Unsupported completed transition: " + str(action))
        elif kind == "PAYLOAD_TRANSFERRED":
            for d in DIRECTIONS:
                n = event[d + "Frames"]
                if n:
                    key_id = pair[d]
                    self.keys[key_id]["nextNonceCounter"] += n
                    c = self.contexts[cid]
                    c[d]["nextSequenceNumber"] += n
                    c[d + "FramesInSession"] += n
                    self.delivered[event["contactId"]][d] += event[d + "PayloadBytes"]

    def pair_ready(self, cid, at):
        if cid not in self.authorized or cid not in self.contexts:
            return False
        c = self.contexts[cid]
        for d in DIRECTIONS:
            sa = c[d]
            key = self.keys.get(sa["keyId"])
            if (key is None or key["id"] in self.disclosed | self.blocked or context(key["contextId"]) != cid
                or key["direction"] != d.upper() or key["status"] != "ACTIVE"
                or not key["installedOnSpacecraft"] or at >= key["deadline"]
                or key["activatedAt"] is None or instant(key["activatedAt"]) > at
                or sa["status"] not in {"KEYED", "OPERATIONAL"} or not sa["peerSynchronized"]
                or sa["nextSequenceNumber"] != key["nextNonceCounter"]
                or not 0 <= key["nextNonceCounter"] < MAX_COUNTER):
                return False
        return True

    def sa_ready(self, cid, at):
        if not self.pair_ready(cid, at):
            return False
        c = self.contexts[cid]
        window = c.get("activeContact")
        return (all(c[d]["status"] == "OPERATIONAL" for d in DIRECTIONS) and window is not None
                and context(window) == cid and instant(window["start"]) <= at < instant(window["end"]))

    def conditions(self, cid, at):
        contacts = [c for c in self.contacts.values() if context(c["window"]) == cid
                    and instant(c["window"]["start"]) <= at < instant(c["window"]["end"])]
        offered = any(any(c["payload"]["offered" + d.title() + "Bytes"] - self.delivered[c["window"]["id"]][d]
                         >= c["payload"]["frameBytes"] for d in DIRECTIONS) for c in contacts)
        installed = all(any(context(k["contextId"]) == cid and k["direction"] == d.upper()
                            and k["id"] not in self.disclosed | self.blocked
                            and k["status"] in {"PRE_ACTIVE", "ACTIVE"} and k["installedOnSpacecraft"]
                            and at < k["deadline"] and k["nextNonceCounter"] < MAX_COUNTER
                            for k in self.keys.values()) for d in DIRECTIONS)
        return {"no_target_contact": not contacts, "spacecraft_notice_pending": self.sc_notice is None,
                "no_independent_installed_material_pair": not installed,
                "independent_pair_not_ready": not self.pair_ready(cid, at),
                "independent_sa_not_open": not self.sa_ready(cid, at),
                "no_offered_target_frame_in_current_contact": not offered}


def metric(status, *, at=None, origin=None, evidence=None, end=None, reason=None):
    return {"status": status, "at": utc(at), "seconds": None if at is None else at - origin,
            "eventSequence": evidence, "censorAt": utc(end) if status == "censored" else None,
            "reason": reason}


def measure(scenario, events, actual, run_status="FINISHED"):
    """Measure one observed run. Missing observations are never converted to zero."""
    end = instant(actual["at"])
    require(end <= instant(scenario["end"]), "Observed prefix extends past scenario")
    incidents = scenario.get("disclosures", [])
    require(len(incidents) <= 1, "Only the registered single-disclosure model is supported")
    default = {"originAt": None, "observationEnd": utc(end), "milestones": {}, "readinessIntervals": [],
               "conditionIntervals": [], "conditionSeconds": {}, "verifiedIndependentFrames": 0}
    capture = next((e for e in events if e["type"] == "KEY_DISCLOSED"), None)
    ground = next((e for e in events if e["type"] == "REVOCATION_RECEIVED_GROUND"), None)
    no_target = any(e["type"] == "DISCLOSURE_NO_TARGET" for e in events)
    if not incidents or no_target or incidents[0].get("detectionAt") is None:
        reason = "no-attack" if not incidents else "no-target" if no_target else "undetected"
        default["milestones"] = {k: metric("not-applicable", reason=reason) for k in MILESTONES}
        return default
    if capture is None or ground is None:
        default["milestones"] = {k: metric("unavailable", reason="ground-notice-origin-not-observed") for k in MILESTONES}
        return default
    origin, cid = instant(ground["at"]), context(incidents[0]["contextId"])
    require(capture["keyIds"] == ground["keyIds"], "Disclosed IDs differ from ground notice")
    default["originAt"] = utc(origin)
    replay = Replay(scenario, capture["keyIds"].values())
    timeline = defaultdict(list)
    timeline[instant(scenario["start"])] = []
    timeline[end] = []
    frame_starts = defaultdict(list)
    frames = {}
    for index, e in enumerate(events):
        require(e["sequence"] == index and instant(e["at"]) <= end, "Invalid journal sequence/end")
        if index:
            require(instant(e["at"]) >= instant(events[index - 1]["at"]), "Reordered journal")
        timeline[instant(e["at"])].append(e)
        if e["type"] in {"PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"}:
            f = se1.read_frame(e, replay.contacts)
            frames[index] = f
            frame_starts[f["from"]].append((e, f))
            timeline[f["from"]]
        if e["type"] == "STEP_COMPLETED" and e["action"] in {"PREPARE", "SWITCH"}:
            constraint = "maxMaterialAge" if e["action"] == "PREPARE" else "cryptoperiod"
            limit = instant(e["at"]) + D(scenario["constraints"][constraint])
            if limit <= end:
                timeline[limit]
    for key in replay.keys.values():
        if instant(scenario["start"]) <= key["deadline"] <= end:
            timeline[key["deadline"]]
    for c in scenario["contacts"]:
        for edge in ("start", "end"):
            at = instant(c["window"][edge])
            if instant(scenario["start"]) <= at <= end:
                timeline[at]
    milestones = {k: metric("censored", end=end, reason="not-observed-before-observation-end") for k in MILESTONES}
    intervals, conditions, valid_frames = [], [], {}
    open_intervals = {}
    noticed_origin = False

    def mark(name, at, sequence, ready_at_origin=False):
        if milestones[name]["status"] == "censored":
            milestones[name] = metric("already-ready-at-origin" if ready_at_origin else "event", at=at,
                                      origin=origin, evidence=sequence)

    times = sorted(timeline)
    for index, at in enumerate(times):
        for e in timeline[at]:
            if e["sequence"] == ground["sequence"]:
                noticed_origin = True
                for key, ready in (("independent_pair", replay.pair_ready(cid, at)),
                                   ("independent_sa", replay.sa_ready(cid, at))):
                    if ready:
                        mark(key, origin, None, True)
            if e["type"] == "PAYLOAD_TRANSFERRED" and e["sequence"] in valid_frames:
                valid = valid_frames[e["sequence"]]
                require(valid, "Delivered independent frame lacks admissible starting pair/SA")
                if at > origin and context(e["contextId"]) == cid:
                    mark("independent_frame", at, e["sequence"])
                    default["verifiedIndependentFrames"] += 1
            replay.apply(e)
            if noticed_origin:
                if e["type"] == "REVOCATION_RECEIVED_SPACECRAFT":
                    require(any(p["type"] == "STEP_COMPLETED" and p["action"] == "NOTIFY_REVOCATION"
                                and p["at"] == e["at"] and p["keyIds"] == e["keyIds"]
                                for p in timeline[at] if p["sequence"] < e["sequence"]), "Unconfirmed spacecraft notice")
                    mark("spacecraft_notice", at, e["sequence"])
                if replay.pair_ready(cid, at):
                    mark("independent_pair", at, e["sequence"])
                if replay.sa_ready(cid, at):
                    mark("independent_sa", at, e["sequence"])
        for e, frame in frame_starts[at]:
            if (frame["delivered"] and context(e["contextId"]) == cid
                    and all(k not in replay.disclosed for k in e["keyIds"].values())):
                valid_frames[e["sequence"]] = (replay.sa_ready(cid, at) and replay.pair_ids(cid) == e["keyIds"]
                    and all(frame["until"] <= replay.keys[k]["deadline"] for k in e["keyIds"].values()))
        if at < origin:
            continue
        for name, ready in (("independent_pair", replay.pair_ready(cid, at)), ("independent_sa", replay.sa_ready(cid, at))):
            pair = replay.pair_ids(cid) if ready else None
            previous = open_intervals.get(name)
            if previous and (not ready or previous["keyIds"] != pair or at == end):
                old_keys = [replay.keys[k] for k in previous["keyIds"].values()]
                reason = ("key-deadline" if any(at >= k["deadline"] for k in old_keys)
                          else "counter-exhausted" if any(k["nextNonceCounter"] >= MAX_COUNTER for k in old_keys)
                          else "binding-changed" if ready and previous["keyIds"] != pair
                          else "session-closed" if name == "independent_sa" and not ready and replay.pair_ready(cid, at)
                          else "readiness-lost" if not ready else "observation-end")
                previous.update(until=utc(at), durationSeconds=at - instant(previous["from"]),
                    endReason=reason)
                intervals.append(previous)
                del open_intervals[name]
            if ready and name not in open_intervals and at < end:
                open_intervals[name] = {"kind": name, "from": utc(at), "keyIds": pair,
                    "startEvidenceSequence": replay.activation_evidence.get(cid) if name == "independent_pair" else replay.open_evidence.get(cid),
                    "originalDeadlines": {d: utc(replay.keys[k]["deadline"]) for d, k in pair.items()}}
        if index + 1 < len(times) and times[index + 1] > at:
            until = times[index + 1]
            flags = replay.conditions(cid, at)
            if conditions and conditions[-1]["conditions"] == flags and conditions[-1]["until"] == utc(at):
                conditions[-1]["until"] = utc(until)
                conditions[-1]["durationSeconds"] += until - at
            else:
                conditions.append({"from": utc(at), "until": utc(until), "durationSeconds": until - at, "conditions": flags})
    default.update(milestones=milestones, readinessIntervals=intervals, conditionIntervals=conditions,
        conditionSeconds={k: sum((r["durationSeconds"] for r in conditions if r["conditions"][k]), D(0))
                          for k in replay.conditions(cid, origin)})
    default["disclosedKeyIds"] = capture["keyIds"]
    # Independent conservation check: final lifecycle metadata never supplied replay readiness.
    final = actual.get("securityState")
    if final:
        require(set(final["keys"]) == set(replay.keys), "Replayed key inventory differs from observed state")
        for key_id, key in final["keys"].items():
            require(key["nextNonceCounter"] == replay.keys[key_id]["nextNonceCounter"], "Replayed nonce counter mismatch")
            require(se1.deadline(key, scenario) == replay.keys[key_id]["deadline"], "Replayed original deadline mismatch")
    return default


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    inputs = se1.Inputs(args.directory)
    manifest, summary = inputs.read("manifest.json"), inputs.read("summary.json")
    specs = manifest["cases"]
    require(len(specs) == manifest["registeredRuns"] == len({s["name"] for s in specs}), "Invalid registration")
    results, rows, intervals, conditions = {}, [], [], []
    for spec in specs:
        name = spec["name"]
        row = summary.get(name, {})
        status = row.get("status", "NOT_RUN")
        provenance = {"modelFile": spec["modelFile"], "eventsFile": row.get("eventsFile"), "actualFile": row.get("actualFile")}
        if status in {"ERROR", "NOT_RUN"} or not all(provenance.values()):
            result = {"originAt": None, "observationEnd": None, "milestones": {k: metric("unavailable", reason="journal-unavailable") for k in MILESTONES},
                      "readinessIntervals": [], "conditionIntervals": [], "conditionSeconds": {}}
        else:
            model = inputs.read(spec["modelFile"])
            expected = manifest.get("modelSha256", {}).get(spec["modelFile"])
            require(expected is None or expected == inputs.hashes[spec["modelFile"]], "Frozen model hash mismatch")
            events, actual = inputs.read(row["eventsFile"]), inputs.read(row["actualFile"])
            require(actual["status"] == status, "Summary/actual status mismatch")
            result = measure(model["scenario"], events, actual, status)
            if status == "FINISHED" and result["originAt"] is not None:
                expected_frame = row["securityMetrics"]["firstIndependentFrameAt"]
                got = result["milestones"]["independent_frame"]["at"]
                require((None if got is None else instant(got)) == (None if expected_frame is None else instant(expected_frame)), name + ": SE2 frame recovery mismatch")
        result.update(case=name, runStatus=status, qualityEligible=status == "FINISHED",
                      evidenceScope="complete" if status == "FINISHED" else "observed-prefix" if status == "SEARCH_INCOMPLETE" else "unavailable",
                      provenance=provenance)
        results[name] = result
        flat = {"case": name, **{k: spec.get(k) for k in ("fixture", "block", "workload", "policy", "mode", "attackPoint")},
                "runStatus": status, "qualityEligible": result["qualityEligible"], "evidenceScope": result["evidenceScope"],
                "originAt": result["originAt"], "observationEnd": result["observationEnd"], **provenance}
        for k, m in result["milestones"].items():
            flat.update({k + "_" + field: value for field, value in m.items()})
        rows.append(flat)
        for r in result["readinessIntervals"]:
            intervals.append({"case": name, "qualityEligible": result["qualityEligible"], **r,
                              "keyIds": json.dumps(r["keyIds"], sort_keys=True), "originalDeadlines": json.dumps(r["originalDeadlines"], sort_keys=True)})
        for r in result["conditionIntervals"]:
            conditions.append({"case": name, "qualityEligible": result["qualityEligible"], **{k: v for k, v in r.items() if k != "conditions"}, **r["conditions"]})
    args.out.mkdir(parents=True, exist_ok=True)
    se1.write_csv(args.out / "recovery-milestones.csv", rows)
    se1.write_csv(args.out / "readiness-intervals.csv", intervals)
    se1.write_csv(args.out / "overlapping-conditions.csv", conditions)
    report = {"schemaVersion": "se21-recovery-v1", "source": "frozen-se2-postprocessing-only",
              "registeredAttempts": len(specs), "statusCounts": dict(Counter(r["runStatus"] for r in results.values())),
              "qualityEligible": sum(r["qualityEligible"] for r in results.values()),
              "excludedFinalQuality": [n for n, r in results.items() if not r["qualityEligible"]],
              "completeMilestoneStatusCounts": {m: dict(Counter(r["milestones"][m]["status"] for r in results.values() if r["qualityEligible"])) for m in MILESTONES},
              "fileHashes": inputs.hashes, "postprocessorSha256": se1.sha(Path(__file__)),
              "auditHelperSha256": se1.sha(Path(se1.__file__)),
              "runs": results}
    (args.out / "recovery-metrics.json").write_text(json.dumps(report, indent=2, default=str) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in {"runs", "fileHashes", "excludedFinalQuality"}}, indent=2))


if __name__ == "__main__":
    main()
