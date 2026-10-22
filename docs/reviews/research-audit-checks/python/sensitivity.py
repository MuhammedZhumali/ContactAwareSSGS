"""Task 5: sensitivity evidence from frozen data only (no new simulation).
Usage: python -X utf8 -I sensitivity.py <repo_root> <out_dir>
"""
import json, sys, re, calendar, datetime as dt
from pathlib import Path
from collections import defaultdict

ROOT, OUT = Path(sys.argv[1]), Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
R = json.load(open(OUT / "own_recompute_all.json", encoding="utf8"))
man = json.load(open(RAW / "manifest.json", encoding="utf8"))
specs = {c["name"]: c for c in man["cases"]}
L = []


def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    return calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple()) * 10**9 + int((m[2] or "0").ljust(9, "0"))

# ---- (a) search time vs physical time
agg = defaultdict(lambda: {"runs": 0, "dec": 0, "tot": 0, "max": 0, "gt1": 0, "gt025": 0, "tot_over_span": 0})
crit = []
for n, s in specs.items():
    if s["policy"] not in ("next", "multi") or R[n]["status"] != "FINISHED":
        continue
    dec = json.load(open(RAW / f"{n}.decisions.json", encoding="utf8"))
    model = json.load(open(RAW / s["modelFile"], encoding="utf8"))["scenario"]
    span = (ns(model["end"]) - ns(model["start"])) / 1e9
    srch = [d for d in dec if d["source"] == "SEARCH"]
    secs = [d["searchNanos"] / 1e9 for d in srch]
    fam = ("X/Y" if s["workload"] == "WITNESS" else "ISS") + "/" + s["policy"]
    a = agg[fam]
    a["runs"] += 1; a["dec"] += len(secs); a["tot"] += sum(secs); a["max"] = max(a["max"], max(secs, default=0))
    a["gt1"] += sum(x > 1 for x in secs); a["gt025"] += sum(x > 0.25 for x in secs)
    a["tot_over_span"] += sum(secs) > span
    if s["mode"] == "detected" and R[n]["recoveryStatus_own"] == "RECOVERED" and s["workload"] == "WITNESS":
        ev = json.load(open(RAW / f"{n}.events.json", encoding="utf8"))
        tg = ns(next(e for e in ev if e["type"] == "REVOCATION_RECEIVED_GROUND")["at"])
        trec = tg + int(round(R[n]["detectionToRecovery_own"] * 1e9))
        S = sum(d["searchNanos"] for d in srch if tg <= ns(d["at"]) < trec) / 1e9
        # contact containing the recovery frame
        cend = None
        for c in model["contacts"]:
            w = c["window"]
            if ns(w["start"]) <= trec <= ns(w["end"]) and w["groundStationId"] == model["disclosures"][0]["contextId"]["groundStationId"]:
                cend = ns(w["end"])
        margin = None if cend is None else (cend - trec) / 1e9
        nd = sum(1 for d in srch if tg <= ns(d["at"]) < trec)
        crit.append((n, round(R[n]["detectionToRecovery_own"], 3), nd, round(S, 3), margin, (margin is not None and S > margin)))
L.append("(a) search wall-time per family (Next/Multi finished runs):")
for k, a in sorted(agg.items()):
    L.append(f"  {k}: runs={a['runs']} decisions={a['dec']} totalSearch={a['tot']:.1f}s maxDecision={a['max']:.3f}s decisions>0.25s={a['gt025']} >1s={a['gt1']} runsWhoseTotalSearch>simulatedSpan={a['tot_over_span']}")
L.append("  X/Y detected recovered runs: (case, detection->recovery s, #search decisions in [GS notice, recovery], their summed search s, slack to target-contact end after recovery frame, search>slack)")
for c in sorted(crit):
    L.append("   " + str(c))

# ---- (b) cold start: initial keys in ISS models
for f in ("iss3-cp5400-none.model.json", "iss-fragment3-cp5400-none.model.json", "iss-fragment6-cp5400-none.model.json", "X-none.model.json"):
    m = json.load(open(RAW / f, encoding="utf8"))["scenario"]
    st = m["initialState"]
    keys = st.get("keys", {}) if isinstance(st, dict) else {}
    L.append(f"(b) {f}: start={m['start']} first AOS={min(c['window']['start'] for c in m['contacts'])} initial keys={len(keys)} contacts={len(m['contacts'])} cryptoperiod={m['constraints']['cryptoperiod']}")

# Next vs Multi identity per fixture family
fields = ("servedContacts_own", "usefulSecureTime_own", "controlCpu_own", "managementBytes_own", "newKeys_own", "deliveredBytes_own",
          "decryptableBeforeBytes_own", "decryptableAfterBytes_own", "confidentialDeliveredBytes_own", "detectionToRecovery_own", "recoveryStatus_own")
fam = defaultdict(lambda: [0, 0, 0])
for n, s in specs.items():
    if s["policy"] != "next":
        continue
    m = n.replace("-next-", "-multi-", 1) if "-next-" in n else None
    if m not in specs or R[n]["status"] != "FINISHED" or R[m]["status"] != "FINISHED":
        fam[s["fixture"]][2] += 1
        continue
    same = all((round(R[n][f], 6) if isinstance(R[n][f], float) else R[n][f]) == (round(R[m][f], 6) if isinstance(R[m][f], float) else R[m][f]) for f in fields)
    fam[s["fixture"]][0 if same else 1] += 1
L.append("(b') Next vs Multi per fixture: [identical on all compared metrics, differing, not comparable(unfinished)]")
for k, v in sorted(fam.items()):
    L.append(f"   {k}: {v}")

# ---- (c) timing-model / notice-duration alternatives vs base (same policy, mode, original point)
L.append("(c) alternative vs base (original attack point): policy/mode: base -> alt [recovery s | conf B | useful s | cpu | served]")
for base in ("X", "Y", "iss3-cp5400", "iss3-cp7200"):
    for alt in ("alt-wait", "notice2"):
        for pol in ("fixed", "percontact", "next", "multi"):
            for mode in ("none", "original-undetected", "original-detected"):
                b, a = f"{base}-{pol}-{mode}", f"{base}-{alt}-{pol}-{mode}"
                if b not in R or a not in R:
                    continue
                def v(c):
                    r = R[c]
                    if r["status"] != "FINISHED":
                        return r["status"]
                    return f"{r['detectionToRecovery_own'] if r['detectionToRecovery_own'] is None else round(r['detectionToRecovery_own'],2)}|{r['confidentialDeliveredBytes_own']}|{r['usefulSecureTime_own']}|{round(r['controlCpu_own'],2)}|{r['servedContacts_own']}"
                vb, va = v(b), v(a)
                if vb != va:
                    L.append(f"   {base}/{alt}/{pol}/{mode}: {vb} -> {va}")
L.append("   (pairs not listed are identical on these 5 values)")
print("\n".join(L))
(OUT / "sensitivity.txt").write_text("\n".join(L), encoding="utf8")
