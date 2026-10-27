"""Audit check on frozen SE2.4 journals (read-only).

1. Critical path after the ground notice: which decisions between the notice and the first independent
   activation were rule decisions and which ran a search, with each search's own elapsed time.
2. Search possibly in progress at the notice instant (decision start + its search time beyond the notice).
3. First payload frame in the return contact C3, in attacked AND no-attack runs, with the C3 operations.
Run from the repository root: python -X utf8 -I docs/reviews/research-flow-audit-checks/python/se24_timing_readiness.py
"""
import glob, json, os
from datetime import datetime

def t(s):
    s = s.rstrip("Z"); base, frac = (s.split(".") + ["0"])[:2]
    return datetime.fromisoformat(base).timestamp() + float("0." + frac)

root = "target/security-evaluation-new-days/runs"
for run in sorted(glob.glob(root + "/*")):
    name = os.path.basename(run)
    try:
        dec = json.load(open(run + "/actual.decisions.json", encoding="utf-8"))
        ev = json.load(open(run + "/actual.events.json", encoding="utf-8"))
    except OSError:
        continue
    contacts = [e["contactId"] for e in ev if e["type"] == "CONTACT_STARTED"]
    c3 = contacts[2] if len(contacts) >= 3 else None
    first_c3 = next((e["at"] for e in ev if e["type"] == "PAYLOAD_TRANSFERRED" and e["contactId"] == c3), None)
    ops_c3 = [(e["action"], e["at"][11:21]) for e in ev if e["type"] == "ACTION_COMPLETED" and e.get("contactId") == c3]
    max_search = max(((d.get("searchNanos") or 0) / 1e9 for d in dec), default=0)
    print(f"{name}: decisions={len(dec)} maxSearch={max_search:.2f}s firstC3Frame={first_c3} C3ops={ops_c3}")
    notice = next((e["at"] for e in ev if e["type"] == "REVOCATION_RECEIVED_GROUND"), None)
    if not notice:
        continue
    g = t(notice)
    before = [d for d in dec if t(d["at"]) < g and (d.get("searchNanos") or 0) > 0]
    if before:
        d = before[-1]
        print(f"   last search before notice: {g - t(d['at']):.2f}s earlier, {d['searchNanos'] / 1e9:.2f}s long,"
              f" overlap after notice {max(0.0, t(d['at']) + d['searchNanos'] / 1e9 - g):.2f}s")
    activation = next((e["at"] for e in ev if e["type"] == "ACTION_COMPLETED" and e.get("action") == "SWITCH"
                       and e["at"] > notice), None)
    for d in dec:
        if notice <= d["at"] <= (activation or notice):
            print(f"   {d['at'][11:23]} {d['source']:12s} {(d.get('executedAction') or {}).get('kind')}"
                  f" search={((d.get('searchNanos') or 0) / 1e6):.0f}ms")
