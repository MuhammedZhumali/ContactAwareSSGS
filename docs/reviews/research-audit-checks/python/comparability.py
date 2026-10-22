"""Task 2/3: pairing, attack-time independence, selection effects, censoring handling.
Independent of project scripts. Uses own_recompute_all.json from own_parser.py.
Usage: python -X utf8 -I comparability.py <repo_root> <out_dir>
"""
import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path
import re, calendar, datetime as dt

ROOT, OUT = Path(sys.argv[1]), Path(sys.argv[2])
RAW = ROOT / "target" / "security-evaluation-se2"
TAB = ROOT / "docs" / "security-evaluation-se2" / "tables"
R = json.load(open(OUT / "own_recompute_all.json", encoding="utf8"))
man = json.load(open(RAW / "manifest.json", encoding="utf8"))
specs = {c["name"]: c for c in man["cases"]}


def ns(ts):
    m = re.fullmatch(r"(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?Z", ts)
    return calendar.timegm(dt.datetime.strptime(m[1], "%Y-%m-%dT%H:%M:%S").timetuple()) * 10**9 + int((m[2] or "0").ljust(9, "0"))


def strip_scenario(s):
    s = json.loads(json.dumps(s))
    s.pop("disclosures", None)
    s.pop("name", None)
    return s


out = []
# ---- 2a. same model across the 4 policies of each comparison cell; control model == attack model minus disclosure
cells = defaultdict(list)
for n, s in specs.items():
    cells[(s["fixture"], s["block"], s["workload"], s["timingModel"], s["noticeDurationScale"], s["attackPoint"], s["mode"])].append(s)
bad_cells = [k for k, v in cells.items() if len({x["modelFile"] for x in v}) != 1 or sorted(x["policy"] for x in v) != ["fixed", "multi", "next", "percontact"]]
out.append(f"comparison cells: {len(cells)}; cells whose 4 policies do not share one model file / not exactly 4 policies: {len(bad_cells)}")
bad_ctrl = []
for n, s in specs.items():
    b = specs[s["baselineCase"]]
    if b["policy"] != s["policy"] or b["mode"] != "none" or b["fixture"] != s["fixture"] or b["timingModel"] != s["timingModel"] or b["noticeDurationScale"] != s["noticeDurationScale"]:
        bad_ctrl.append(n)
        continue
    if s["mode"] != "none":
        ma = json.load(open(RAW / s["modelFile"], encoding="utf8"))
        mb = json.load(open(RAW / b["modelFile"], encoding="utf8"))
        if strip_scenario(ma["scenario"]) != strip_scenario(mb["scenario"]) or ma.get("fixedPeriod") != mb.get("fixedPeriod"):
            bad_ctrl.append(n)
        d = ma["scenario"]["disclosures"]
        if len(d) != 1 or d[0]["at"] != s["attackAt"]:
            bad_ctrl.append(n + ":attackAt")
        if s["mode"] == "detected" and (ns(d[0]["detectionAt"]) - ns(d[0]["at"]) != 2 * 10**9):
            bad_ctrl.append(n + ":delay")
        if s["mode"] == "undetected" and d[0].get("detectionAt") is not None:
            bad_ctrl.append(n + ":undetected-has-detection")
out.append(f"attack cases whose control/model differs other than the disclosure (or wrong policy/attack/delay): {len(bad_ctrl)} {bad_ctrl[:5]}")

# ---- 2b. attack grid recomputed from schedule only (own implementation)
def grid(model, cfg):
    contacts = sorted((c["window"] for c in model["contacts"]), key=lambda w: (ns(w["start"]), w["id"]))
    tgt = (cfg["target"]["satelliteId"], cfg["target"]["groundStationId"])
    first = next(w for w in contacts if (w["satelliteId"], w["groundStationId"]) == tgt)
    s0, e0 = ns(first["start"]), ns(first["end"])
    pts = [("original", ns(cfg["originalAttackAt"]))] + [(f"p{p}", s0 + (e0 - s0) * p // 100) for p in (10, 30, 50, 70, 90)]
    i = contacts.index(first)
    if i + 1 < len(contacts) and ns(contacts[i + 1]["start"]) > e0:
        pts.append(("gap-midpoint", e0 + (ns(contacts[i + 1]["start"]) - e0) // 2))
    uniq = {}
    for lab, t in pts:
        uniq.setdefault(t, lab)
    return {lab: t for t, lab in uniq.items()}

grid_bad = []
for cfg in man["configurations"]:
    nm = next(s for s in specs.values() if s["fixture"] == cfg["id"] and s["mode"] == "none")
    model = json.load(open(RAW / nm["modelFile"], encoding="utf8"))["scenario"]
    g = grid(model, cfg)
    if not cfg.get("fullAttackGrid"):
        g = {"original": g.get("original", ns(cfg["originalAttackAt"]))}
    reg = {p["id"]: ns(p["at"]) for p in cfg["points"]}
    if g != reg:
        grid_bad.append((cfg["id"], g, reg))
out.append(f"configurations: {len(man['configurations'])}; attack grids differing from own schedule-only recomputation: {len(grid_bad)}")
for gb in grid_bad[:5]:
    out.append("  " + str(gb))

# ---- 2c. no hidden influence: undetected journal == control journal except KEY_DISCLOSED; detected prefix before GS notice == control prefix
def norm(e):
    # sequence numbers shift by the inserted KEY_DISCLOSED event; frameEvidence is only
    # emitted in attack runs (null in controls), frame timing is still compared via at/usefulTime/bytes/keyIds
    return json.dumps({k: v for k, v in e.items() if k not in ("sequence", "frameEvidence")}, sort_keys=True)

undet_ok = undet_bad = det_ok = det_bad = skipped = 0
undet_bad_list, det_bad_list = [], []
for n, s in specs.items():
    if s["mode"] == "none":
        continue
    b = s["baselineCase"]
    if R[n]["status"] != "FINISHED" or R[b]["status"] != "FINISHED":
        skipped += 1
        continue
    ea = json.load(open(RAW / f"{n}.events.json", encoding="utf8"))
    eb = json.load(open(RAW / f"{b}.events.json", encoding="utf8"))
    if s["mode"] == "undetected":
        if [norm(e) for e in ea if e["type"] != "KEY_DISCLOSED"] == [norm(e) for e in eb]:
            undet_ok += 1
        else:
            undet_bad += 1; undet_bad_list.append(n)
    else:
        g = next((e for e in ea if e["type"] == "REVOCATION_RECEIVED_GROUND"), None)
        tg = ns(g["at"]) if g else None
        pa = [norm(e) for e in ea if e["type"] != "KEY_DISCLOSED" and (tg is None or ns(e["at"]) < tg)]
        pb = [norm(e) for e in eb if (tg is None or ns(e["at"]) < tg)]
        if pa == pb:
            det_ok += 1
        else:
            det_bad += 1; det_bad_list.append(n)
out.append(f"undetected runs whose full journal (minus KEY_DISCLOSED) equals own control: {undet_ok} ok / {undet_bad} differ {undet_bad_list[:6]}")
out.append(f"detected runs whose journal strictly before GS notice equals own control: {det_ok} ok / {det_bad} differ {det_bad_list[:6]}; skipped (unfinished run/control): {skipped}")

# ---- 3. status tallies and how censoring enters tables
met = {r["case"]: r for r in csv.DictReader(open(TAB / "metrics.csv", encoding="utf8"))}
st = Counter(R[c]["recoveryStatus_own"] for c in met)
out.append(f"finished-run recovery statuses (own): {dict(st)}")
cens = [c for c in met if met[c]["recoveryStatus"] == "CENSORED_NO_RECOVERY"]
out.append(f"censored rows with empty detectionToRecovery: {sum(met[c]['detectionToRecovery']=='' for c in cens)}/{len(cens)}; censorAt filled: {sum(met[c]['censorAt']!='' for c in cens)}")
sc_after = Counter((met[c]['bytesAfterSpacecraftNotice']) for c in met if R[c]['groundToSpacecraftNotice_own'] is not None)
out.append(f"runs with SC notice: {sum(1 for c in met if R[c]['groundToSpacecraftNotice_own'] is not None)}; bytesAfterSpacecraftNotice distribution {dict(sc_after)}")
gs_tail = [int(met[c]['bytesAfterGroundNotice']) for c in met if int(met[c]['bytesAfterGroundNotice']) > 0]
out.append(f"runs with bytes after GS notice >0: {len(gs_tail)} range {min(gs_tail)}-{max(gs_tail)}")
nosc = [c for c in met if met[c]['mode'] == 'detected' and R[c]['groundToSpacecraftNotice_own'] is None]
out.append(f"detected finished runs without SC notice: {len(nosc)} {nosc}")

# ---- pairs Next vs Multi: own construction
def key(s):
    return (s["fixture"], s["block"], s["workload"], s["timingModel"], s["noticeDurationScale"], s["attackPoint"], s["mode"])

pairs = []
excluded_pairs = []
for k, v in cells.items():
    by = {s["policy"]: s["name"] for s in v}
    n, m = by["next"], by["multi"]
    def fin(c):
        return R[c]["status"] == "FINISHED" and R[specs[c]["baselineCase"]]["status"] == "FINISHED"
    if fin(n) and fin(m):
        pairs.append((n, m))
    else:
        excluded_pairs.append((n, R[n]["status"], R[specs[n]["baselineCase"]]["status"], m, R[m]["status"], R[specs[m]["baselineCase"]]["status"]))
out.append(f"Next/Multi cells: {len(cells)}; both finished with finished controls: {len(pairs)}; excluded: {len(excluded_pairs)}")
for e in excluded_pairs:
    out.append("  excluded " + str(e))

def cmp(a, b, high=True):
    if a is None or b is None:
        return "NA"
    return "TIE" if a == b else ("WIN" if (a > b) == high else "LOSS")  # WIN = Multi better (a=Multi)

rows = []
wtl = defaultdict(Counter)
for n, m in pairs:
    rn, rm = R[n], R[m]
    attacked = rn["recoveryStatus_own"] not in ("NO_ATTACK", "NO_TARGET") and rm["recoveryStatus_own"] not in ("NO_ATTACK", "NO_TARGET")
    d = {"service": cmp(rm["servedContacts_own"], rn["servedContacts_own"]),
         "delivered": cmp(rm["deliveredBytes_own"], rn["deliveredBytes_own"]),
         "confidential": cmp(rm["confidentialDeliveredBytes_own"], rn["confidentialDeliveredBytes_own"]),
         "cpu": cmp(round(rm["controlCpu_own"], 9), round(rn["controlCpu_own"], 9), False),
         "mgmt": cmp(rm["managementBytes_own"], rn["managementBytes_own"], False),
         "keys": cmp(rm["newKeys_own"], rn["newKeys_own"], False)}
    if attacked:
        d["exposure"] = cmp(rm["decryptableBeforeBytes_own"] + rm["decryptableAfterBytes_own"], rn["decryptableBeforeBytes_own"] + rn["decryptableAfterBytes_own"], False)
        d["exposureAfter"] = cmp(rm["decryptableAfterBytes_own"], rn["decryptableAfterBytes_own"], False)
        d["exposureBefore"] = cmp(rm["decryptableBeforeBytes_own"], rn["decryptableBeforeBytes_own"], False)
        # recovery with censoring handled as ordering: recovered < censored
        sn, sm = rn["recoveryStatus_own"], rm["recoveryStatus_own"]
        if sn == "UNDETECTED":
            d["recoveryOrdered"] = "NA(undetected)"
        elif sn == "RECOVERED" and sm == "RECOVERED":
            d["recoveryOrdered"] = cmp(rm["detectionToRecovery_own"], rn["detectionToRecovery_own"], False)
        elif sn == sm == "CENSORED_NO_RECOVERY":
            d["recoveryOrdered"] = "TIE(both censored)"
        elif sm == "RECOVERED":
            d["recoveryOrdered"] = "WIN(Next censored)"
        else:
            d["recoveryOrdered"] = "LOSS(Multi censored)"
    for k2, v2 in d.items():
        wtl[k2][v2] += 1
    rows.append({"next": n, "multi": m, **d,
                 "multi_deliv": rm["deliveredBytes_own"], "next_deliv": rn["deliveredBytes_own"],
                 "multi_exp": rm["decryptableBeforeBytes_own"] + rm["decryptableAfterBytes_own"] if attacked else "",
                 "next_exp": rn["decryptableBeforeBytes_own"] + rn["decryptableAfterBytes_own"] if attacked else "",
                 "multi_tgt_emit_before": rm.get("targetCtxEmittedBeforeDisclosure", ""), "next_tgt_emit_before": rn.get("targetCtxEmittedBeforeDisclosure", ""),
                 "multi_conf": rm["confidentialDeliveredBytes_own"], "next_conf": rn["confidentialDeliveredBytes_own"]})
out.append("Own Multi-vs-Next win/tie/loss (WIN = Multi better):")
for k2, c in wtl.items():
    out.append(f"  {k2}: {dict(c)}")

with open(OUT / "next_multi_pairs.csv", "w", newline="", encoding="utf8") as fh:
    fields = sorted({k for r in rows for k in r}, key=lambda x: (x not in ("next", "multi"), x))
    w = csv.DictWriter(fh, fieldnames=fields)
    w.writeheader(); w.writerows(rows)

# pairwise.csv cross-check for the next/multi rows
pw = [r for r in csv.DictReader(open(TAB / "pairwise.csv", encoding="utf8")) if {r["left"], r["right"]} == {"next", "multi"}]
out.append(f"pairwise.csv next/multi rows: {len(pw)}; total pairwise rows: {sum(1 for _ in open(TAB / 'pairwise.csv', encoding='utf8')) - 1}")
print("\n".join(out))
(OUT / "comparability.txt").write_text("\n".join(out), encoding="utf8")
