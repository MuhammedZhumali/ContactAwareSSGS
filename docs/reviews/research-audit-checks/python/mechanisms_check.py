"""Task 4: decompose Multi-vs-Next exposure differences and verify claimed mechanisms
against raw journal events. Own parsing only.
Usage: python -X utf8 -I mechanisms_check.py <repo_root> <out_dir>
"""
import csv, json, sys, re, calendar, datetime as dt
from pathlib import Path
from collections import Counter

ROOT, OUT = Path(sys.argv[1]), Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
R = json.load(open(OUT / "own_recompute_all.json", encoding="utf8"))
man = json.load(open(RAW / "manifest.json", encoding="utf8"))
specs = {c["name"]: c for c in man["cases"]}
pairs = list(csv.DictReader(open(OUT / "next_multi_pairs.csv", encoding="utf8")))


def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    return calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple()) * 10**9 + int((m[2] or "0").ljust(9, "0"))


def load(case):
    s = specs[case]
    model = json.load(open(RAW / s["modelFile"], encoding="utf8"))["scenario"]
    ev = json.load(open(RAW / f"{case}.events.json", encoding="utf8"))
    return model, ev


def frames(model, ev):
    pay = {c["window"]["id"]: c["payload"] for c in model["contacts"]}
    out = []
    for e in ev:
        if e["type"] in ("PAYLOAD_TRANSFERRED", "PAYLOAD_ABORTED"):
            fe = e["frameEvidence"]
            p = pay[e["contactId"]]
            full = p["frameBytes"] * 10**9 // p["bytesPerSecond"]
            if fe is None:
                up = e["uplinkFrames"] == 1
                t1 = ns(e["at"]); t0 = t1 - full
                fe = {"direction": "UPLINK" if up else "DOWNLINK", "keyId": e["keyIds"]["uplink" if up else "downlink"],
                      "frameBytes": p["frameBytes"], "emittedBytes": p["frameBytes"], "delivered": True}
            else:
                t0, t1 = ns(fe["from"]), ns(fe["until"])
            out.append(dict(t0=t0, t1=t1, full=full, gs=e["contextId"]["groundStationId"], key=fe["keyId"], dir=fe["direction"],
                            b=fe["frameBytes"], em=fe["emittedBytes"], dl=e["type"] == "PAYLOAD_TRANSFERRED", type=e["type"],
                            fail=e.get("failureCode"), contact=e["contactId"]))
    return out


def upto(f, t):
    return min(f["em"], f["b"] * max(0, min(t, f["t1"]) - f["t0"]) // f["full"])


lines = []
rows = []
for p in pairs:
    if p["exposure"] not in ("WIN", "LOSS", "TIE") or p["exposure"] == "":
        continue
    rec = {"multi": p["multi"], "next": p["next"], "exposure": p["exposure"]}
    for side in ("next", "multi"):
        c = p[side]
        model, ev = load(c)
        inc = model["disclosures"][0]
        td = ns(inc["at"]); tgt = inc["contextId"]["groundStationId"]
        disc = next(e for e in ev if e["type"] == "KEY_DISCLOSED")
        keys = set(disc["keyIds"].values())
        fr = frames(model, ev)
        tgt_before = sum(upto(f, td) for f in fr if f["gs"] == tgt)
        cap_before = sum(upto(f, td) for f in fr if f["key"] in keys)
        other_before = tgt_before - cap_before
        cap_after = sum(f["em"] - upto(f, td) for f in fr if f["key"] in keys)
        rec[side + "_capturedKey"] = disc["keyIds"]["uplink"]
        rec[side + "_tgtTrafficBefore"] = tgt_before
        rec[side + "_otherKeyTrafficBefore"] = other_before
        rec[side + "_expBefore"] = cap_before
        rec[side + "_expAfter"] = cap_after
        rec[side + "_delivered"] = R[c]["deliveredBytes_own"]
        rec[side + "_served"] = R[c]["servedContacts_own"]
        rec[side + "_conf"] = R[c]["confidentialDeliveredBytes_own"]
    rec["sameCapturedKey"] = rec["next_capturedKey"] == rec["multi_capturedKey"]
    rec["dExpBefore(N-M)"] = rec["next_expBefore"] - rec["multi_expBefore"]
    rec["dTgtTrafficBefore(N-M)"] = rec["next_tgtTrafficBefore"] - rec["multi_tgtTrafficBefore"]
    rec["dExpAfter(N-M)"] = rec["next_expAfter"] - rec["multi_expAfter"]
    rec["dDelivered(N-M)"] = rec["next_delivered"] - rec["multi_delivered"]
    rows.append(rec)

with open(OUT / "exposure_decomposition.csv", "w", newline="", encoding="utf8") as fh:
    w = csv.DictWriter(fh, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)

wins = [r for r in rows if r["exposure"] == "WIN"]
lines.append(f"attacked Next/Multi pairs: {len(rows)}; Multi exposure WIN: {len(wins)}")
lines.append(f"  of wins, same captured key id: {sum(r['sameCapturedKey'] for r in wins)}")
lines.append(f"  of wins, before-disclosure reduction fully equal to reduction of TARGET-CONTEXT traffic volume before disclosure (no key-hygiene term): {sum(r['dExpBefore(N-M)'] == r['dTgtTrafficBefore(N-M)'] for r in wins)}")
lines.append(f"  of wins, Multi delivered fewer total bytes than Next: {sum(r['dDelivered(N-M)'] > 0 for r in wins)}; more: {sum(r['dDelivered(N-M)'] < 0 for r in wins)}; equal: {sum(r['dDelivered(N-M)'] == 0 for r in wins)}")
lines.append(f"  of wins, Multi confidential delivered >= Next: {sum(r['multi_conf'] >= r['next_conf'] for r in wins)}")
lines.append(f"  of wins, reduction located before disclosure only: {sum(r['dExpAfter(N-M)'] == 0 for r in wins)}; after only: {sum(r['dExpBefore(N-M)'] == 0 for r in wins)}")
tot_before = sum(r["dExpBefore(N-M)"] for r in wins); tot_after = sum(r["dExpAfter(N-M)"] for r in wins)
lines.append(f"  summed exposure reduction over wins: before={tot_before} B, after={tot_after} B")
for r in wins:
    lines.append("   " + ", ".join(f"{k}={r[k]}" for k in ("multi", "sameCapturedKey", "next_tgtTrafficBefore", "multi_tgtTrafficBefore", "dExpBefore(N-M)", "dExpAfter(N-M)", "next_delivered", "multi_delivered", "next_conf", "multi_conf")))

# ---- specific mechanism traces
def ops(ev, t_from=None, t_to=None):
    res = []
    for e in ev:
        if e["type"] in ("STEP_STARTED", "STEP_COMPLETED") and e["action"] not in ("STOP",) and e["detail"] in ("Admitted; no state checkpoint yet", "Confirmed checkpoint"):
            res.append((e["at"][11:], e["type"][5:], e["action"], e["contextId"]["groundStationId"] if e["contextId"] else None, ",".join(sorted((e["keyIds"] or {}).values()))[:60]))
        if e["type"] in ("KEY_DISCLOSED", "REVOCATION_RECEIVED_GROUND", "REVOCATION_RECEIVED_SPACECRAFT", "PAYLOAD_ABORTED", "CONTACT_STARTED", "CONTACT_ENDED"):
            res.append((e["at"][11:], e["type"], e.get("failureCode"), e["contactId"], (json.dumps(e["frameEvidence"]) if e["frameEvidence"] else "")[:200]))
    return res

for case in ("X-next-p10-detected", "X-multi-p10-detected", "X-next-original-detected", "X-multi-original-detected",
             "iss3-cp5400-next-original-detected", "iss3-cp5400-multi-original-detected"):
    model, ev = load(case)
    fr = frames(model, ev)
    lines.append(f"\n== {case}")
    for o in ops(ev):
        lines.append("   " + " | ".join(str(x) for x in o))
    # frames in A context grouped into contiguous runs, per key
    runs = []
    for f in sorted(fr, key=lambda f: f["t0"]):
        if runs and runs[-1]["key_dir"] == (f["key"].split(":")[-2] if "planner" in f["key"] else f["key"].rsplit("-", 1)[0], f["contact"]) and runs[-1]["t1"] == f["t0"]:
            runs[-1]["t1"] = f["t1"]; runs[-1]["n"] += 1; runs[-1]["b"] += f["em"]
        else:
            runs.append({"key_dir": (f["key"].split(":")[-2] if "planner" in f["key"] else f["key"].rsplit("-", 1)[0], f["contact"]), "t0": f["t0"], "t1": f["t1"], "n": 1, "b": f["em"]})
    t0 = ns(model["contacts"][0]["window"]["start"]) if False else None
    base = min(ns(c["window"]["start"]) for c in model["contacts"])
    for r in runs[:40]:
        lines.append(f"   traffic {r['key_dir']} [{(r['t0']-base)/1e9:.3f},{(r['t1']-base)/1e9:.3f}] frames={r['n']} bytes={r['b']}")

print("\n".join(lines))
(OUT / "mechanisms_check.txt").write_text("\n".join(lines), encoding="utf8")
