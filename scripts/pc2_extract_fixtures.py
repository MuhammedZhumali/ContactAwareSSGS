"""Extract immutable PC2 pre-change witnesses from saved PC1/R1 JSON only.

No JVM, planner, simulation, or timing experiment is launched. Existing outputs
must be byte-identical; this script never replaces changed evidence.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "target/practical-controller-pc2/fixtures"
SCORES = ("servedContacts", "usefulSecureTime", "controlCpu", "managementBytes", "newKeys")
SOURCES = {}
OUTPUTS = {}
VERIFY = False


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source(name, pointer=None):
    name = str(name).replace("\\", "/")
    SOURCES[name] = sha(ROOT / name)
    result = {"path": name, "sha256": SOURCES[name]}
    if pointer is not None:
        result["jsonPointer"] = pointer
    return result


def load(name):
    source(name)
    return json.loads((ROOT / name).read_text(encoding="utf-8-sig"))


def save(name, value):
    path = OUT / name
    data = (json.dumps(value, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if path.exists():
        if path.read_bytes() != data:
            raise RuntimeError(f"Refusing to replace different fixture evidence: {path}")
    elif VERIFY:
        raise RuntimeError(f"Missing fixture evidence: {path}")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    OUTPUTS[name] = hashlib.sha256(data).hexdigest()


def score(value):
    assert set(value) == set(SCORES)
    return {key: value[key] for key in SCORES}


def whole_score(metrics):
    costs = metrics["controlCosts"]
    return dict(servedContacts=metrics["servedContacts"],
                usefulSecureTime=metrics["usefulSecureTime"],
                controlCpu=round(costs["groundCpu"] + costs["spacecraftCpu"], 9),
                managementBytes=costs["uplinkBytes"] + costs["downlinkBytes"],
                newKeys=metrics["createdKeys"])


def extract(fixture_id, run, decision_index, attempt_index, rule):
    trace_name = run + "/actual.full-trace.json"
    attempts_name = run + "/practical-searches.json"
    trace, attempts = load(trace_name), load(attempts_name)
    decision = trace["decisions"][decision_index]
    attempt = attempts[attempt_index]
    observation, search = decision["observation"], attempt["search"]
    assert observation["at"] == attempt["at"]
    assert decision["executedAction"] == attempt["selected"] == search["firstAction"]
    assert search["bestPlan"][0] == decision["executedAction"]
    assert search["bestPlan"][-1]["until"] == observation["evaluationEnd"]
    assert trace["snapshot"]["scenario"]["plan"] == []
    assert trace["snapshot"]["scenario"].get("disclosures", []) == []
    prefix = [{key: entry[key] for key in ("observation", "executedAction", "resumedAt")}
              for entry in trace["decisions"][:decision_index]]
    for index, entry in enumerate(prefix):
        assert entry["executedAction"] is not None
        following = trace["decisions"][index + 1]["observation"]
        assert entry["resumedAt"] == following["at"]
    checkpoint_name = run + "/checkpoint.json"
    checkpoint = source(checkpoint_name, "/snapshot") if (ROOT / checkpoint_name).exists() else None
    save(fixture_id + "/observation.json", observation)
    save(fixture_id + "/selected-plan.json", search["bestPlan"])
    save(fixture_id + "/saved-search.json", attempt)
    save(fixture_id + "/actual-snapshot-reconstruction.json", {
        "status": "RECONSTRUCTION_INPUT_ONLY_NOT_AN_EXECUTION_SNAPSHOT",
        "reason": "The saved final snapshot cannot be rewound. No intermediate actual snapshot was exported at this root.",
        "sourceFinalSnapshot": source(trace_name, "/snapshot"),
        "sourceCheckpoint": checkpoint,
        "sourceScenario": source(trace_name, "/snapshot/scenario"),
        "scenario": trace["snapshot"]["scenario"],
        "horizon": observation["horizon"],
        "decisionIndex": decision_index,
        "prefix": prefix,
        "expectedObservation": observation,
        "restoreAlgorithm": [
            "Use a package-local probe in org.contactaware.security.simulation; deserialize the exact scenario and prefix with ExecutionCheckpointFiles.convert.",
            "Set current = engine.controlledSnapshot(engine.start(scenario)); do not use a nominal planning snapshot as the actual snapshot.",
            "For each prefix record in order, require PlanningObservation.from(current, horizon).equals(record.observation). No extra policy wakeups were exported for these witnesses.",
            "Apply SecurityPolicyController.executeAction(current, record.observation, record.executedAction, OperationOutcomes.nominal()). Require current.at() equals record.resumedAt.",
            "Require PlanningObservation.from(current, horizon).equals(expectedObservation), including IDs, pending work, costs and payload progress; only then export current as the actual root snapshot.",
            "For planner evaluation, pass only expectedObservation through BranchEngine.create. Never expose the full scenario, final checkpoint, or future actual journal to the planner."
        ],
        "executionStatus": "NOT_RUN_BY_EXTRACTOR"
    })
    manifest = {
        "fixtureId": fixture_id,
        "evidenceKind": "PRE_CHANGE_SAVED_PC1_R1_ARTIFACT",
        "run": run,
        "decisionIndex": decision_index,
        "attemptIndex": attempt_index,
        "tieBreak": rule,
        "root": {"at": observation["at"], "horizon": observation["horizon"],
                 "evaluationEnd": observation["evaluationEnd"],
                 "visibleContactIds": [c["window"]["id"] for c in observation["visibleContacts"]],
                 "observationSource": source(trace_name, f"/decisions/{decision_index}/observation")},
        "selected": {"action": decision["executedAction"], "resumedAt": decision["resumedAt"],
                     "score": score(search["score"]), "scoreOrigin": "THIS_ROOT_INCREMENTAL_TO_EVALUATION_END",
                     "status": search["status"], "stopReason": search["diagnostics"]["stopReason"],
                     "exploredNodes": search["exploredNodes"],
                     "terminalPlans": search["diagnostics"]["terminalPlans"],
                     "source": source(attempts_name, f"/{attempt_index}/search")},
        "actualSnapshot": {"status": "RECONSTRUCTION_REQUIRED",
                           "input": "actual-snapshot-reconstruction.json",
                           "permittedPlannerInput": "observation.json"},
        "wholeRunScore": {"value": whole_score(trace["simulation"]["metrics"]),
                          "scope": "WHOLE_RUN_NOT_COMPARABLE_WITH_ROOT_SCORE"},
        "alternative": {"status": "UNKNOWN_AT_THIS_ROOT", "score": None, "optimalityClaim": False}
    }
    return manifest, trace, attempts


def main():
    global VERIFY
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", action="store_true", help="Check source and fixture bytes without writing")
    VERIFY = parser.parse_args().verify
    pc1 = load("target/practical-controller/manifest.json")
    protocol = load("target/practical-controller/full-iss-v1/protocol.json")
    for name in ("docs/practical-controller/decision-diagnosis.md", "docs/practical-controller/report.md"):
        source(name)
    assert sha(ROOT / protocol["binary"]["jar"]) == protocol["binary"]["sha256"]
    source(protocol["binary"]["jar"])
    assert sha(ROOT / protocol["model"]["file"]) == protocol["model"]["sha256"]
    source(protocol["model"]["file"])

    fixture_id = "post-prepare-known-retained-winner"
    run = "target/research-validation/practical-v2/runs/018-demand-none-practical-H2-CANONICAL"
    manifest, trace, attempts = extract(fixture_id, run, 41, 10, "CANONICAL")
    replay_base = "target/practical-controller/continuation-replay/"
    replay_input = load(replay_base + "input.json")
    replay_result = load(replay_base + "result.json")
    replay_protocol = load(replay_base + "protocol.json")
    registration = load(replay_base + "execution-registration.json")
    source("docs/practical-controller/probes/ObservedContinuationProbe.java")
    assert sha(ROOT / (replay_base + "input.json")) == replay_protocol["inputSha256"]
    for name, digest in replay_protocol["sourceHashes"].items():
        assert source(name)["sha256"] == digest
    assert replay_input["observation"] == trace["decisions"][41]["observation"]
    assert replay_input["retained"] == attempts[9]["search"]["bestPlan"][32:]
    assert replay_input["fresh"] == attempts[10]["search"]["bestPlan"]
    assert replay_result["fresh"]["score"] == attempts[10]["search"]["score"]
    assert replay_result["retainedVersusFresh"] < 0
    assert replay_result["retained"]["score"] == dict(zip(SCORES, (2, 100, 1.22, 2160, 2)))
    assert replay_result["fresh"]["score"] == dict(zip(SCORES, (2, 100, 1.93, 3240, 4)))
    save(fixture_id + "/alternative-plan.json", replay_input["retained"])
    save(fixture_id + "/continuation-input.json", replay_input)
    save(fixture_id + "/saved-replay-result.json", replay_result)
    manifest["alternative"] = {
        "status": "KNOWN_FEASIBLE_BETTER_AT_SAME_ROOT_FROM_SAVED_REPLAY",
        "score": score(replay_result["retained"]["score"]),
        "scoreOrigin": "THIS_ROOT_INCREMENTAL_TO_EVALUATION_END",
        "sourceAttemptIndex": 9, "sourcePlanSuffixOffset": 32,
        "plan": "alternative-plan.json", "optimalityClaim": False,
        "decisiveCriterion": replay_result["decisiveCriterion"],
        "savedReplaySource": source(replay_base + "result.json"),
        "savedReplayBinary": registration["binary"],
        "savedReplayProbeSourceSha256": registration["sourceSha256"]}
    manifest["witness"] = "PREPARE has started but not completed. Its pending operation and reserved IDs are part of the saved observation. The fresh continuation activates g2 before the return, while retained delays its activation."
    save(fixture_id + "/fixture.json", manifest)

    fixture_id = "iss46-earliest-skipped-contact"
    run = "target/practical-controller/full-iss-v1/runs/02-ISS46-H2-CANONICAL"
    manifest, trace, _ = extract(fixture_id, run, 1, 1, "CANONICAL")
    contacts = trace["snapshot"]["scenario"]["contacts"]
    first = min(contacts, key=lambda c: (c["window"]["start"], c["window"]["id"]))
    observation = trace["decisions"][1]["observation"]
    assert observation["currentContact"] == first
    assert observation["at"] == first["window"]["start"]
    assert trace["decisions"][1]["resumedAt"] == first["window"]["end"]
    assert manifest["selected"]["action"]["kind"] == "WAIT"
    assert len(contacts) == 46 and trace["simulation"]["metrics"]["servedContacts"] == 1
    metric = next(c for c in trace["simulation"]["metrics"]["contacts"] if c["contactId"] == first["window"]["id"])
    assert metric["served"] is False and metric["usefulTime"] == 0
    save(fixture_id + "/skipped-contact-evidence.json", {
        "contact": first, "actualContactMetric": metric,
        "actualEvents": [e for e in trace["simulation"]["events"] if e["contactId"] == first["window"]["id"]],
        "allContactCount": 46, "scheduleChronologyCheck": "This is the earliest contact by start time in the fixed full schedule."})
    manifest["witness"] = "The first contact of the saved PC1 offline H2 CANONICAL 1/46 run is skipped by decision 1 WAIT through its entire window."
    manifest["alternative"]["explanation"] = "No saved same-root better continuation has been established. A heuristic seed or exhaustive result would be new PC2 evidence, not a pre-change fixture fact."
    manifest["budget"] = protocol["budgets"]
    save(fixture_id + "/fixture.json", manifest)

    fixture_id = "demand-none-disadvantageous-wait"
    run = "target/research-validation/practical-v2/runs/020-demand-none-practical-H2-CONTINUE_WAIT"
    manifest, trace, attempts = extract(fixture_id, run, 1, 1, "CONTINUE_WAIT")
    previous = attempts[0]["search"]
    observation = trace["decisions"][1]["observation"]
    assert previous["status"] == "COMPLETE"
    assert trace["decisions"][0]["executedAction"] == previous["bestPlan"][0]
    assert trace["decisions"][1]["resumedAt"] == observation["currentContact"]["window"]["end"]
    assert manifest["selected"]["action"]["kind"] == "WAIT"
    save(fixture_id + "/prior-root-continuation.json", {
        "status": "SAVED_COMPLETE_AT_PRIOR_ROOT_REPLAY_AND_HORIZON_EXTENSION_NOT_PERFORMED",
        "observation": trace["decisions"][0]["observation"],
        "scoreAtPriorRoot": score(previous["score"]),
        "scoreAtFixtureRoot": None,
        "suffixAfterExecutedWait": previous["bestPlan"][1:],
        "source": source(run + "/practical-searches.json", "/0/search"),
        "boundary": "The prior root has two visible contacts and ends earlier. Its score is not a certificate or a rebased score for this three-contact root."})
    manifest["witness"] = "A TIME_LIMIT incumbent executes WAIT to the first contact end. Prior COMPLETE evidence contains a service-capable continuation, but the changed horizon requires replay/rebase and completion before comparison."
    manifest["alternative"]["explanation"] = "A prior-root candidate is supplied in prior-root-continuation.json. Its five-metric same-root terminal score is unknown; no dominance claim is made here."
    metric = next(c for c in trace["simulation"]["metrics"]["contacts"] if c["contactId"] == observation["currentContact"]["window"]["id"])
    assert metric["served"] is False and metric["usefulTime"] == 0
    save(fixture_id + "/skipped-contact-evidence.json", {
        "contact": observation["currentContact"], "actualContactMetric": metric,
        "actualEvents": [e for e in trace["simulation"]["events"] if e["contactId"] == metric["contactId"]]})
    save(fixture_id + "/fixture.json", manifest)

    save("source-hashes.json", SOURCES)
    manifest = {
        "schemaVersion": 1, "stage": "PRE_CHANGE_EXTRACTION_ONLY", "sourcePc1BaseCommit": pc1["baseCommit"],
        "fixtureIds": ["post-prepare-known-retained-winner", "iss46-earliest-skipped-contact", "demand-none-disadvantageous-wait"],
        "scoreOrder": list(SCORES), "objective": ["max", "max", "min", "min", "min"],
        "units": {"usefulSecureTime": "seconds", "controlCpu": "seconds", "managementBytes": "bytes", "newKeys": "directional keys"},
        "extractor": source("scripts/pc2_extract_fixtures.py"),
        "sourceFiles": len(SOURCES), "files": dict(OUTPUTS),
        "checks": ["Source registration hashes match", "Selected action equals attempt first action", "Full observation preserved", "Prefix resumedAt aligns with next observation", "Post-PREPARE saved replay equals extracted plans and same-root scores", "ISS witness is earliest contact of 46 and actually unserved"],
        "notRun": ["JVM", "build", "search", "actual snapshot reconstruction", "new continuation replay"],
        "boundary": "Source hashes and deterministic JSON extraction are verified. Intermediate actual snapshots require the supplied replay recipe and observation equality checks; no reconstructed snapshot or unknown alternative score is fabricated."
    }
    save("manifest.json", manifest)
    print(json.dumps({"status": "VERIFIED" if VERIFY else "EXTRACTED", "fixtures": 3,
                      "sourceFiles": len(SOURCES), "fixtureFiles": len(OUTPUTS),
                      "output": str(OUT)}, indent=2))


if __name__ == "__main__":
    main()
