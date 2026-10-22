#!/usr/bin/env python3
"""Independent factual accounting and hand-check of the opt-in timing experiment."""

import argparse
from decimal import Decimal
import json
from pathlib import Path
import verify_policy_experiments as audit


def verify(directory, v2_reference=None):
    counterexamples = audit.load(directory / "counterexample-results.json")
    expected = {
        ("V2_EVENT_BOUNDARIES", False): (2, 4, 7, 0, 2),
        ("V2_EVENT_BOUNDARIES", True): (1, 7, 2, 0, 0),
        ("PREPARE_DURATION_WAIT", False): (2, 4, 3, 0, 0),
        ("PREPARE_DURATION_WAIT", True): (2, 4, 3, 0, 0),
    }
    fields = ("servedContacts", "usefulSecureTime", "controlCpu", "managementBytes", "newKeys")
    durations = {"PREPARE": 4, "INSTALL": 3, "SWITCH": 1, "OPEN": 1, "REUSE": 1}
    plans = {}
    for cell in counterexamples:
        key = cell["timeModel"], cell["discardOffHorizonPreparation"]
        plan = cell["result"]["plan"]
        score = tuple(cell["result"]["evaluation"][field] for field in fields)
        audit.require(score == expected[key], "Unexpected independently calculated counterexample score")
        frames = [action for action in plan if action["kind"] == "CONTINUE"]
        cpu = sum(durations.get(action["kind"], 0) for action in plan)
        keys = 2 * sum(action["kind"] == "PREPARE" for action in plan)
        served = len({action["contactId"] for action in frames})
        audit.require((served, len(frames), cpu, 0, keys) == score,
                      "Manual action/frame accounting disagrees with counterexample score")
        switch = next(action for action in plan if action["kind"] == "SWITCH")
        activation = audit.instant(switch["at"]) + 1
        for frame in frames:
            start, finish = audit.instant(frame["at"]), audit.instant(frame["until"])
            audit.require(finish - start == 1 and activation <= start < finish <= activation + 9,
                          "Counterexample frame violates activation or cryptoperiod interval")
        audit.require(cell["exploredNodes"] > 0 and cell["terminalPlans"] > 0, "Missing exhaustive reference evidence")
        plans[key] = plan
    audit.require(set(plans) == set(expected), "Incomplete counterexample factorial")
    audit.require(plans["PREPARE_DURATION_WAIT", False] == plans["PREPARE_DURATION_WAIT", True],
                  "B ablation changed the canonical winner in the enlarged timing space")
    summaries = audit.load(directory / "summary.json")
    case_checks = [audit.audit_case(directory, name, row) for name, row in summaries.items()]
    comparisons = []
    regressions = []
    frozen = audit.load(v2_reference / "summary.json") if v2_reference else None
    for fixture in ("X-prepared", "Y-prepared", "X-autonomous", "Y-autonomous"):
        for policy in ("NEXT", "MULTI"):
            before_name = f"{fixture}-{policy}-V2_EVENT_BOUNDARIES"
            after_name = f"{fixture}-{policy}-PREPARE_DURATION_WAIT"
            before, after = summaries[before_name], summaries[after_name]
            before_actions = [d["executedAction"] for d in audit.load(directory / before["compactDecisionsFile"])]
            after_actions = [d["executedAction"] for d in audit.load(directory / after["compactDecisionsFile"])]
            complete = before["status"] == after["status"] == "FINISHED"
            comparisons.append({"fixture": fixture, "policy": policy,
                                "bothFinished": complete,
                                "fiveMetricsEqual": before["finalMetrics"] == after["finalMetrics"] if complete else None,
                                "executedActionsEqual": before_actions == after_actions if complete else None,
                                "nonWaitActionsEqual": [a for a in before_actions if a and a["kind"] != "WAIT"]
                                                       == [a for a in after_actions if a and a["kind"] != "WAIT"] if complete else None,
                                "v2Status": before["status"], "experimentalStatus": after["status"]})
            if frozen is not None:
                old = frozen[f"{fixture}-{policy.lower()}"]
                same_metrics = before["finalMetrics"] == old["finalMetrics"]
                same_events = audit.load(directory / before["actualEventsFile"]) == audit.load(v2_reference / old["actualEventsFile"])
                same_nodes = before["exploredNodes"] == old["exploredNodes"]
                audit.require(same_metrics and same_events and same_nodes, "Default v2 replay changed frozen metrics, events or node count")
                regressions.append({"fixture": fixture, "policy": policy, "sameMetrics": same_metrics,
                                    "sameEvents": same_events, "sameExploredNodes": same_nodes})
    return {"schemaVersion": "time-model-audit-1", "interpretation":
            "Independent Decimal journal/accounting checks and manual tiny-case calculation; not an independent search implementation.",
            "counterexampleCellsChecked": len(plans), "cases": case_checks, "xyComparisons": comparisons,
            "v2RegressionComparisons": regressions}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--v2-reference", type=Path)
    args = parser.parse_args()
    result = verify(args.directory, args.v2_reference)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, indent=2, ensure_ascii=False,
                                   default=lambda value: str(value) if isinstance(value, Decimal) else value) + "\n", encoding="utf-8")
    print(f"Checked {result['counterexampleCellsChecked']} tiny cells and {len(result['cases'])} actual journals")


if __name__ == "__main__":
    main()
