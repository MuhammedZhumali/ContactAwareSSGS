"""Independent re-parser for SE2 raw journals (does NOT import project scripts).

Reads <case>.events.json, <case>.actual.json and the model file referenced in
target/security-evaluation-se2/manifest.json, recomputes metrics from first
principles (integer nanoseconds, integer bytes) and compares to
docs/security-evaluation-se2/tables/metrics.csv.

Usage: python -X utf8 -I own_parser.py <repo_root> <out_dir>
"""
import csv
import json
import re
import sys
import calendar
import datetime as dt
from pathlib import Path
from fractions import Fraction

ROOT = Path(sys.argv[1])
OUT = Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
TAB = ROOT / "docs" / "security-evaluation-se2" / "tables"


def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    base = calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple())
    frac = int((m[2] or "0").ljust(9, "0"))
    return base * 10**9 + frac


def ctx(c):
    return (c["satelliteId"], c["groundStationId"]) if c else None


def frames_of(events, contacts, actual):
    out = []
    for e in events:
        if e["type"] not in ("PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"):
            continue
        fe = e["frameEvidence"]
        pay = contacts[e["contactId"]]["payload"]
        full = Fraction(pay["frameBytes"], pay["bytesPerSecond"])  # seconds
        if fe is None:  # complete frame without evidence: derive from counters
            up = e["uplinkFrames"] == 1
            fe = {"from": None, "until": e["at"], "direction": "UPLINK" if up else "DOWNLINK",
                  "keyId": e["keyIds"]["uplink" if up else "downlink"],
                  "frameBytes": e["uplinkPayloadBytes"] + e["downlinkPayloadBytes"],
                  "emittedBytes": e["uplinkPayloadBytes"] + e["downlinkPayloadBytes"], "delivered": True}
            t1 = ns(fe["until"])
            t0 = t1 - int(full * 10**9)
        else:
            t0, t1 = ns(fe["from"]), ns(fe["until"])
        out.append({"t0": t0, "t1": t1, "full_ns": int(full * 10**9), "dir": fe["direction"], "key": fe["keyId"],
                    "bytes": fe["frameBytes"], "emitted": fe["emittedBytes"],
                    "delivered": e["type"] == "PAYLOAD_TRANSFERRED" and fe["delivered"],
                    "ctx": ctx(e["contextId"]), "contact": e["contactId"], "useful": Fraction(str(e["usefulTime"])),
                    "type": e["type"], "failure": e.get("failureCode")})
    inflight = actual.get("inFlightFrame")
    if inflight:
        w = inflight["contact"]["window"]
        pay = inflight["contact"]["payload"]
        d = "UPLINK" if inflight["uplink"] else "DOWNLINK"
        t0, t1 = ns(inflight["from"]), ns(inflight["until"])
        full_ns = int(Fraction(pay["frameBytes"], pay["bytesPerSecond"]) * 10**9)
        out.append({"t0": t0, "t1": t1, "full_ns": full_ns, "dir": d,
                    "key": inflight["keys"][d.lower()], "bytes": pay["frameBytes"],
                    "emitted": pay["frameBytes"] * (t1 - t0) // full_ns, "delivered": False,
                    "ctx": (w["satelliteId"], w["groundStationId"]), "contact": w["id"], "useful": 0,
                    "type": "IN_FLIGHT", "failure": None})
    return out


def emitted_until(f, t):
    """Ciphertext bytes physically emitted by instant t (linear, floored)."""
    el = max(0, min(t, f["t1"]) - f["t0"])
    return min(f["emitted"], f["bytes"] * el // f["full_ns"])


def analyse(case, spec):
    model = json.load(open(RAW / spec["modelFile"], encoding="utf8"))["scenario"]
    events = json.load(open(RAW / f"{case}.events.json", encoding="utf8"))
    actual = json.load(open(RAW / f"{case}.actual.json", encoding="utf8"))
    contacts = {c["window"]["id"]: c for c in model["contacts"]}
    frames = frames_of(events, contacts, actual)
    r = {"case": case, "status": actual["status"], "events": len(events), "frames": len(frames),
         "aborted": sum(1 for f in frames if f["type"] == "PAYLOAD_ABORTED")}
    # --- service: own rule = delivered payload per direction >= registered minimum
    served = 0
    per_contact = {}
    started = {e["contactId"] for e in events if e["type"] == "CONTACT_STARTED"}
    for cid, c in contacts.items():
        up = sum(f["bytes"] for f in frames if f["contact"] == cid and f["delivered"] and f["dir"] == "UPLINK")
        dn = sum(f["bytes"] for f in frames if f["contact"] == cid and f["delivered"] and f["dir"] == "DOWNLINK")
        ok = up >= c["payload"]["minimumUplinkBytes"] and dn >= c["payload"]["minimumDownlinkBytes"]
        per_contact[cid] = (up, dn, ok)
        served += ok
    r["servedContacts_own"] = served
    r["usefulSecureTime_own"] = float(sum(f["useful"] for f in frames if f["delivered"]))
    # --- management cost from STEP usage
    cpu = Fraction(0)
    mgmt = 0
    newkeys = 0
    for e in events:
        u = e["usage"]
        cpu += Fraction(str(u["spacecraftCpu"])) + Fraction(str(u["groundCpu"]))
        mgmt += u["uplinkBytes"] + u["downlinkBytes"]
        newkeys += e["createdKeys"]
    r["controlCpu_own"] = float(cpu)
    r["managementBytes_own"] = mgmt
    r["newKeys_own"] = newkeys
    # cross-check new keys against distinct keyIds introduced by PREPARE steps
    prepared = set()
    for e in events:
        if e["type"] == "STEP_COMPLETED" and e["action"] == "PREPARE" and e["keyIds"]:
            prepared |= set(e["keyIds"].values())
    r["preparedDistinctKeys"] = len(prepared)
    r["deliveredBytes_own"] = sum(f["bytes"] for f in frames if f["delivered"])
    r["emittedBytes_own"] = sum(f["emitted"] for f in frames)
    # --- security
    disc = [e for e in events if e["type"] == "KEY_DISCLOSED"]
    nodisc = [e for e in events if e["type"] == "DISCLOSURE_NO_TARGET"]
    gs = [e for e in events if e["type"] == "REVOCATION_RECEIVED_GROUND"]
    sc = [e for e in events if e["type"] == "REVOCATION_RECEIVED_SPACECRAFT"]
    inc = (model.get("disclosures") or [None])[0]
    keys = set(disc[0]["keyIds"].values()) if disc else set()
    td = ns(inc["at"]) if inc else None
    tg = ns(gs[0]["at"]) if gs else None
    tsc = ns(sc[0]["at"]) if sc else None
    before = after = comp_del = after_gs = after_sc = 0
    for f in frames:
        if f["key"] in keys:
            b = emitted_until(f, td)
            before += b
            after += f["emitted"] - b
            comp_del += f["bytes"] if f["delivered"] else 0
            if tg is not None:
                after_gs += f["emitted"] - emitted_until(f, tg)
            if tsc is not None:
                after_sc += f["emitted"] - emitted_until(f, tsc)
    r.update({"decryptableBeforeBytes_own": before, "decryptableAfterBytes_own": after,
              "compromisedDeliveredBytes_own": comp_del,
              "confidentialDeliveredBytes_own": r["deliveredBytes_own"] - comp_del,
              "bytesAfterGroundNotice_own": after_gs, "bytesAfterSpacecraftNotice_own": after_sc})
    # recovery: first delivered frame in target context on non-disclosed key ending after GS notice
    rec = rec_strict = None
    if inc and tg is not None:
        tctx = ctx(inc["contextId"])
        cands = [f for f in frames if f["delivered"] and f["ctx"] == tctx and f["key"] not in keys and f["t1"] > tg]
        if cands:
            rec = min(f["t1"] for f in cands)
        strict = [f for f in cands if f["t0"] >= (tsc if tsc is not None else tg)]
        if strict:
            rec_strict = min(f["t1"] for f in strict)
    if not inc:
        status = "NO_ATTACK"
    elif actual["status"] != "FINISHED" and not disc and not nodisc:
        status = "NOT_REACHED(INCOMPLETE)"
    elif nodisc or not disc:
        status = "NO_TARGET"
    elif tg is None:
        status = "UNDETECTED"
    elif rec is None:
        status = "CENSORED_NO_RECOVERY"
    else:
        status = "RECOVERED"
    end = ns(model["end"])
    r["recoveryStatus_own"] = status
    r["detectionToRecovery_own"] = None if rec is None else (rec - tg) / 1e9
    r["recoveryFrameStartAfterSCNotice_own"] = None if rec_strict is None else (rec_strict - tg) / 1e9
    r["censorDuration_own"] = (end - tg) / 1e9 if status == "CENSORED_NO_RECOVERY" else None
    r["groundToSpacecraftNotice_own"] = None if tsc is None else (tsc - tg) / 1e9
    r["observedUntil"] = actual["at"]
    # pre-disclosure delivered volume in the target context (for mechanism analysis)
    if inc:
        tctx = ctx(inc["contextId"])
        r["targetCtxEmittedBeforeDisclosure"] = sum(emitted_until(f, td) for f in frames if f["ctx"] == tctx)
        r["targetCtxDeliveredTotal"] = sum(f["bytes"] for f in frames if f["ctx"] == tctx and f["delivered"])
    # actual-file final metrics (executor's own counters) for a 3rd cross-check
    fm = actual.get("finalMetrics")
    if fm:
        r["servedContacts_actual"] = fm["servedContacts"]
        r["usefulSecureTime_actual"] = fm["usefulSecureTime"]
        r["newKeys_actual"] = fm["createdKeys"]
        cc = fm["controlCosts"]
        r["managementBytes_actual"] = cc["uplinkBytes"] + cc["downlinkBytes"]
        r["controlCpu_actual"] = round(cc["spacecraftCpu"] + cc["groundCpu"], 9)
    r["_per_contact"] = per_contact
    return r


def main():
    manifest = json.load(open(RAW / "manifest.json", encoding="utf8"))
    specs = {c["name"]: c for c in manifest["cases"]}
    table = {row["case"]: row for row in csv.DictReader(open(TAB / "metrics.csv", encoding="utf8"))}
    compare = [("servedContacts", "int"), ("usefulSecureTime", "float"), ("controlCpu", "float"),
               ("managementBytes", "int"), ("newKeys", "int"), ("deliveredBytes", "int"), ("emittedBytes", "int"),
               ("decryptableBeforeBytes", "int"), ("decryptableAfterBytes", "int"),
               ("compromisedDeliveredBytes", "int"), ("confidentialDeliveredBytes", "int"),
               ("bytesAfterGroundNotice", "int"), ("bytesAfterSpacecraftNotice", "int"),
               ("recoveryStatus", "str"), ("detectionToRecovery", "float"), ("groundToSpacecraftNotice", "float")]
    results, mismatches = {}, []
    for case, spec in specs.items():
        r = analyse(case, spec)
        results[case] = r
        if case not in table:
            continue
        row = table[case]
        for k, kind in compare:
            mine, theirs = r[k + "_own"], row[k]
            if kind == "int":
                ok = int(theirs) == mine
            elif kind == "float":
                ok = (theirs == "" and mine is None) or (theirs != "" and mine is not None and abs(float(theirs) - mine) < 1e-6)
            else:
                ok = theirs == mine
            if not ok:
                mismatches.append((case, k, theirs, mine))
        # executor counters vs own journal parse
        for k in ("servedContacts", "newKeys", "managementBytes"):
            if r.get(k + "_actual") is not None and r[k + "_actual"] != r[k + "_own"]:
                mismatches.append((case, k + "(actual.json)", r[k + "_actual"], r[k + "_own"]))
        if abs(r.get("usefulSecureTime_actual", r["usefulSecureTime_own"]) - r["usefulSecureTime_own"]) > 1e-9:
            mismatches.append((case, "usefulSecureTime(actual.json)", r["usefulSecureTime_actual"], r["usefulSecureTime_own"]))
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / "own_recompute_all.json", "w", encoding="utf8") as fh:
        json.dump({k: {kk: vv for kk, vv in v.items() if kk != "_per_contact"} for k, v in results.items()}, fh, indent=1)
    with open(OUT / "own_recompute_mismatches.csv", "w", newline="", encoding="utf8") as fh:
        w = csv.writer(fh)
        w.writerow(["case", "field", "table", "own"])
        w.writerows(mismatches)
    finished = [c for c in results if c in table]
    print("runs parsed:", len(results), "finished compared:", len(finished), "mismatches:", len(mismatches))
    from collections import Counter
    print(Counter(m[1] for m in mismatches))
    print("total events (finished):", sum(results[c]["events"] for c in finished),
          "frames:", sum(results[c]["frames"] for c in finished), "aborted:", sum(results[c]["aborted"] for c in finished))
    print("total events (all):", sum(r["events"] for r in results.values()))
    for m in mismatches[:40]:
        print(m)


if __name__ == "__main__":
    main()
