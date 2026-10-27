"""R1 counter/availability replay; historical SE2.1 default semantics remain unchanged."""
import recovery_metrics_se21 as old

class Replay(old.Replay):
    def __init__(self, scenario, disclosed):
        super().__init__(scenario, disclosed)
        self.down=set()

    def apply(self, event):
        super().apply(event)
        if event['type']=='CHANNEL_DOWN': self.down.add(event['contactId'])
        elif event['type']=='CHANNEL_UP': self.down.discard(event['contactId'])
        elif event['type']=='PAYLOAD_ABORTED':
            frame=old.se1.read_frame(event,self.contacts)
            self.keys[frame['keyId']]['nextNonceCounter']+=1
            context=self.contexts[old.context(frame['contextId'])]
            direction=frame['direction'].lower()
            if context[direction]['keyId']==frame['keyId']:
                context[direction]['nextSequenceNumber']+=1

    def sa_ready(self,cid,at):
        if not super().sa_ready(cid,at): return False
        return self.contexts[cid]['activeContact']['id'] not in self.down

    def conditions(self,cid,at):
        result=super().conditions(cid,at)
        result['target_channel_unavailable']=any(c['window']['id'] in self.down
            and old.context(c['window'])==cid and old.instant(c['window']['start'])<=at<old.instant(c['window']['end'])
            for c in self.contacts.values())
        return result

# Versioned extension of the preserved SE2.1 milestone algorithm; only Replay semantics differ.
from collections import defaultdict
from decimal import Decimal as D
from recovery_metrics_se21 import MILESTONES, DIRECTIONS, MAX_COUNTER, metric, utc, se1, require, instant, context

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

