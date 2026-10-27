"""Audit check: contacts counted as served whose delivered payload is decryptable under the disclosed pair.

Reads frozen SE2 journals (read-only). For every FINISHED attacked run, recomputes per-contact delivered
bytes per direction from PAYLOAD_TRANSFERRED frameEvidence, splits them into bytes protected by the
disclosed key pair (decryptable by a recording attacker under the SE2 threat model, before or after the
disclosure instant) and other bytes, and checks the scenario's served thresholds both ways.
"""
import collections, json, pathlib, sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "target/security-evaluation-se2")
summary = json.loads((root / "summary.json").read_text(encoding="utf-8"))
rows, by_policy = [], collections.Counter()
for name, s in summary.items():
    if s["status"] != "FINISHED" or s["mode"] == "none":
        continue
    model = json.loads((root / s["modelFile"]).read_text(encoding="utf-8"))
    contacts = {c["window"]["id"]: c["payload"] for c in model["scenario"]["contacts"]}
    events = json.loads((root / pathlib.Path(s["eventsFile"]).name).read_text(encoding="utf-8"))
    disclosed = set()
    for e in events:
        if e["type"] == "KEY_DISCLOSED" and e.get("keyIds"):
            disclosed |= {e["keyIds"]["uplink"], e["keyIds"]["downlink"]}
    total = collections.defaultdict(lambda: [0, 0])
    clean = collections.defaultdict(lambda: [0, 0])
    for e in events:
        fe = e.get("frameEvidence")
        if e["type"] != "PAYLOAD_TRANSFERRED" or not fe or not fe.get("delivered"):
            continue
        i = 0 if fe["direction"] == "UPLINK" else 1
        total[e["contactId"]][i] += fe["frameBytes"]
        if fe["keyId"] not in disclosed:
            clean[e["contactId"]][i] += fe["frameBytes"]
    def served(cid, b):
        p = contacts[cid]
        return b[cid][0] >= p["minimumUplinkBytes"] and b[cid][1] >= p["minimumDownlinkBytes"]
    served_all = [c for c in contacts if served(c, total)]
    served_clean = [c for c in contacts if served(c, clean)]
    lost = sorted(set(served_all) - set(served_clean))
    rows.append((name, s["policy"], s["mode"], len(served_all), len(served_clean), lost))
    by_policy[(s["policy"], "runs")] += 1
    by_policy[(s["policy"], "served")] += len(served_all)
    by_policy[(s["policy"], "servedOnlyViaDisclosedPair")] += len(lost)
    if s["simulationMetrics"]["servedContacts"] != len(served_all):
        print("MISMATCH served", name, s["simulationMetrics"]["servedContacts"], len(served_all))
affected = [r for r in rows if r[5]]
print(f"attacked FINISHED runs: {len(rows)}; runs with >=1 served contact unmet without disclosed-pair bytes: {len(affected)}")
for k in sorted(by_policy):
    print(k, by_policy[k])
for r in affected[:12]:
    print(r)
x = [r for r in rows if r[0].startswith(("X-next-original", "X-multi-original", "iss3-cp5400-next-original", "iss3-cp5400-multi-original"))]
for r in x:
    print("example", r)
