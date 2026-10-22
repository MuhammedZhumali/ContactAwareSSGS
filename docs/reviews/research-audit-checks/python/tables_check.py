"""Recompute report tables (recovered/censored by policy, coverage) and ISS critical-window search time."""
import json, sys, re, calendar, datetime as dt
from pathlib import Path
from collections import Counter, defaultdict
ROOT, OUT = Path(sys.argv[1]), Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
R = json.load(open(OUT / "own_recompute_all.json", encoding="utf8"))
specs = {c["name"]: c for c in json.load(open(RAW / "manifest.json", encoding="utf8"))["cases"]}
def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    return calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple()) * 10**9 + int((m[2] or "0").ljust(9, "0"))
L = []
t = defaultdict(Counter)
for n, s in specs.items():
    if s["block"] == "attack-time" and s["mode"] == "detected":
        t[(s["fixture"], s["policy"])][R[n]["recoveryStatus_own"] if R[n]["status"] == "FINISHED" else R[n]["status"]] += 1
L.append("recovered/censored (attack-time block, detected):")
for k in sorted(t): L.append(f"  {k}: {dict(t[k])}")
cov = defaultdict(Counter); covb = defaultdict(Counter); covp = defaultdict(Counter)
for n, s in specs.items():
    cov[s["workload"]][R[n]["status"]] += 1; covb[s["block"]][R[n]["status"]] += 1; covp[s["policy"]][R[n]["status"]] += 1
L.append("coverage by workload: " + str({k: f"{v['FINISHED']}/{sum(v.values())}" for k, v in cov.items()}))
L.append("coverage by block: " + str({k: f"{v['FINISHED']}/{sum(v.values())}" for k, v in covb.items()}))
L.append("coverage by policy: " + str({k: f"{v['FINISHED']}/{sum(v.values())}" for k, v in covp.items()}))
# ISS critical-window search (decisions between GS notice and recovery) and AOS decisions
for n in ("iss3-cp5400-next-original-detected", "iss3-cp5400-multi-original-detected", "iss3-cp5400-next-p10-detected", "iss3-cp5400-multi-p10-detected"):
    s = specs[n]
    dec = [d for d in json.load(open(RAW / f"{n}.decisions.json", encoding="utf8")) if d["source"] == "SEARCH"]
    ev = json.load(open(RAW / f"{n}.events.json", encoding="utf8"))
    tg = ns(next(e for e in ev if e["type"] == "REVOCATION_RECEIVED_GROUND")["at"])
    trec = tg + int(round(R[n]["detectionToRecovery_own"] * 1e9))
    model = json.load(open(RAW / s["modelFile"], encoding="utf8"))["scenario"]
    aos = min(ns(c["window"]["start"]) for c in model["contacts"])
    w = [d for d in dec if tg <= ns(d["at"]) < trec]
    pre = [d for d in dec if ns(d["at"]) < tg]
    L.append(f"{n}: recovery {R[n]['detectionToRecovery_own']:.2f}s; decisions in [GS,recovery]={len(w)} sumSearch={sum(d['searchNanos'] for d in w)/1e9:.3f}s max={max((d['searchNanos'] for d in w), default=0)/1e9:.3f}s; decisions before GS notice={len(pre)} sumSearch={sum(d['searchNanos'] for d in pre)/1e9:.3f}s (C1 AOS->GS notice = {(tg-aos)/1e9:.1f}s physical)")
print("\n".join(L)); (OUT / "tables_check.txt").write_text("\n".join(L), encoding="utf8")
