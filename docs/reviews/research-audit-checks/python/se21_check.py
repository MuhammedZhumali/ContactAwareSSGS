"""Task 6: independent milestone recomputation (simplified own logic) for selected runs and
SE2.1 table/claim checks. Does not import project scripts.
Usage: python -X utf8 -I se21_check.py <repo_root> <out_dir>
"""
import csv, json, sys, re, calendar, datetime as dt
from pathlib import Path
from collections import Counter

ROOT, OUT = Path(sys.argv[1]), Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
T21 = ROOT / "docs" / "security-evaluation-se21" / "tables"
R21 = ROOT / "target" / "security-evaluation-se21"
specs = {c["name"]: c for c in json.load(open(RAW / "manifest.json", encoding="utf8"))["cases"]}
L = []


def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    return calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple()) * 10**9 + int((m[2] or "0").ljust(9, "0"))


def own_milestones(case):
    s = specs[case]
    sc = json.load(open(RAW / s["modelFile"], encoding="utf8"))["scenario"]
    ev = json.load(open(RAW / f"{case}.events.json", encoding="utf8"))
    tgt = sc["disclosures"][0]["contextId"]["groundStationId"]
    disc = set(next(e for e in ev if e["type"] == "KEY_DISCLOSED")["keyIds"].values())
    g = next((e for e in ev if e["type"] == "REVOCATION_RECEIVED_GROUND"), None)
    if g is None:
        return None
    tg = ns(g["at"])
    windows = [(ns(c["window"]["start"]), ns(c["window"]["end"])) for c in sc["contacts"] if c["window"]["groundStationId"] == tgt]
    cp = int(sc["constraints"]["cryptoperiod"] * 1e9)
    age = int(sc["constraints"]["maxMaterialAge"] * 1e9)
    created, installed, activated = {}, set(), {}
    out = {"spacecraft_notice": None, "independent_pair": None, "independent_sa": None, "independent_frame": None}
    active_pair = None
    for e in ev:
        t = ns(e["at"])
        ctxok = e["contextId"] and e["contextId"]["groundStationId"] == tgt
        keys = set((e["keyIds"] or {}).values())
        if e["type"] == "STEP_COMPLETED" and ctxok:
            if e["action"] == "PREPARE":
                for k in keys: created[k] = t
            elif e["action"] == "INSTALL":
                installed |= keys
            elif e["action"] == "SWITCH":
                for k in keys: activated[k] = t
                active_pair = keys
                ok = keys.isdisjoint(disc) and keys <= installed and all(t < min(created[k] + age, activated[k] + cp) for k in keys)
                if ok and t >= tg and out["independent_pair"] is None:
                    out["independent_pair"] = (t - tg) / 1e9
            elif e["action"] in ("OPEN", "REUSE") and t >= tg and out["independent_sa"] is None:
                in_contact = any(a <= t < b for a, b in windows)
                if keys.isdisjoint(disc) and keys == active_pair and in_contact:
                    out["independent_sa"] = (t - tg) / 1e9
        if e["type"] == "REVOCATION_RECEIVED_SPACECRAFT" and out["spacecraft_notice"] is None:
            out["spacecraft_notice"] = (t - tg) / 1e9
        if e["type"] == "PAYLOAD_TRANSFERRED" and ctxok and keys.isdisjoint(disc) and t > tg and out["independent_frame"] is None:
            out["independent_frame"] = (t - tg) / 1e9
    return out


tab = {r["case"]: r for r in csv.DictReader(open(T21 / "recovery-milestones.csv", encoding="utf8"))}
check = ["X-next-original-detected", "X-multi-original-detected", "iss3-cp5400-next-p30-detected", "iss3-cp5400-multi-p30-detected",
         "iss3-cp5400-multi-original-detected", "X-next-p70-detected", "Y-multi-p90-detected"]
L.append("Own milestone recomputation vs recovery-milestones.csv (seconds from GS notice; None = not observed):")
for c in check:
    mine = own_milestones(c)
    row = tab[c]
    theirs = {k: (float(row[k + "_seconds"]) if row[k + "_seconds"] else None) for k in mine}
    agree = all((mine[k] is None and theirs[k] is None) or (mine[k] is not None and theirs[k] is not None and abs(mine[k] - theirs[k]) < 1e-6) for k in mine)
    L.append(f"  {c}: own={mine} table={theirs} statuses={[row[k + '_status'] for k in mine]} agree={agree}")

# all-run status counts among quality-eligible rows
fin = [r for r in tab.values() if r["qualityEligible"] == "True"]
L.append(f"rows={len(tab)} qualityEligible={len(fin)} evidenceScope counts={dict(Counter(r['evidenceScope'] for r in tab.values()))} runStatus={dict(Counter(r['runStatus'] for r in tab.values()))}")
for k in ("spacecraft_notice", "independent_pair", "independent_sa", "independent_frame"):
    L.append(f"  {k}: {dict(Counter(r[k + '_status'] for r in fin))}")
cens_zero = [r["case"] for r in tab.values() for k in ("spacecraft_notice", "independent_pair", "independent_sa", "independent_frame")
             if r[k + "_status"] in ("censored", "unavailable", "not-applicable") and r[k + "_seconds"] != ""]
L.append(f"censored/unavailable/not-applicable rows with a numeric seconds value: {len(cens_zero)}")
inc = [r for r in tab.values() if r["runStatus"] != "FINISHED"]
L.append("incomplete rows: " + str(Counter((r["evidenceScope"], r["qualityEligible"], r["independent_frame_status"]) for r in inc)))
# pair-active but frame censored -> where (pair 167 vs frame 148)
diff = [r["case"] for r in fin if r["independent_pair_status"] == "event" and r["independent_frame_status"] == "censored"]
L.append(f"finished rows with active independent pair but censored frame: {len(diff)}: {sorted(diff)}")

# cross check frame milestone vs SE2 metrics.csv detectionToRecovery for all finished detected rows
met = {r["case"]: r for r in csv.DictReader(open(ROOT / "docs/security-evaluation-se2/tables/metrics.csv", encoding="utf8"))}
mm = 0
for c, r in tab.items():
    if c in met and r["independent_frame_status"] in ("event", "censored"):
        a = r["independent_frame_seconds"]; b = met[c]["detectionToRecovery"]
        if (a == "") != (b == "") or (a and abs(float(a) - float(b)) > 1e-9):
            mm += 1
L.append(f"SE2.1 frame milestone vs SE2 detectionToRecovery mismatches: {mm}")

# repeat summary from raw target summary.json
sm = json.load(open(R21 / "summary.json", encoding="utf8"))
L.append("SE2.1 raw summary.json top-level keys: " + str(list(sm.keys()) if isinstance(sm, dict) else type(sm)))
rep = list(csv.DictReader(open(T21 / "search-repeats.csv", encoding="utf8")))
L.append(f"search-repeats.csv rows={len(rep)} status={dict(Counter(r['status'] for r in rep))} by variant={dict(Counter((r['variant'], r['status']) for r in rep))}")
print("\n".join(L))
(OUT / "se21_check.txt").write_text("\n".join(L), encoding="utf8")
