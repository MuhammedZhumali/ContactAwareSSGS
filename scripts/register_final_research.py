#!/usr/bin/env python3
"""Freeze the agreed 108-case final matrix; no JVM, search, simulation, or retries.

prepare writes only new model bytes and their derivation/ordering manifest.
register requires the parent's fully tested final JAR identity.
verify is read-only and checks every registered input identity.
"""
from __future__ import annotations

import argparse
import calendar
import copy
import datetime as dt
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
from collections import Counter

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "target/final-research"
MODELS = OUT / "models"
HISTORICAL = ROOT / "target/research-validation/models"
DOC = ROOT / "docs/final-research/protocol.md"
EXPECTED_COUNTS = {"regression": 18, "core": 54, "measured": 15,
                   "sensitivity": 9, "security": 10, "management": 2}
BUDGETS = dict(nodes=5000, searchMilliseconds=1000, decisionMilliseconds=2000,
               seedStageMilliseconds=350, retainedStageMilliseconds=350,
               rolloutTransitionCap=10000, processSeconds=600,
               controllerSeconds=0, heapMiB=1024)
MISSING = object()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def relative(path):
    return Path(path).resolve().relative_to(ROOT).as_posix()


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), parse_float=Decimal)


def exact_json(value, depth=0):
    """Serialize Decimal as a JSON number, never through binary float."""
    if isinstance(value, Decimal):
        require(value.is_finite(), "Nonfinite decimal")
        return str(value)
    if isinstance(value, float):
        raise TypeError("Binary floats are forbidden in a frozen model")
    if isinstance(value, dict):
        if not value:
            return "{}"
        return "{\n" + ",\n".join("  " * (depth + 1) + json.dumps(k) + ": "
            + exact_json(v, depth + 1) for k, v in value.items()) + "\n" + "  " * depth + "}"
    if isinstance(value, list):
        if not value:
            return "[]"
        return "[\n" + ",\n".join("  " * (depth + 1) + exact_json(v, depth + 1)
            for v in value) + "\n" + "  " * depth + "]"
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def encoded(value):
    return (exact_json(value) + "\n").encode("utf-8")


def write_new_or_identical(path, content):
    path = Path(path)
    if path.exists():
        require(path.read_bytes() == content, "Refusing changed existing bytes: " + relative(path))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(content)


def instant_ns(value):
    match = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z", value)
    require(match is not None, "Invalid exact UTC timestamp: " + str(value))
    seconds = calendar.timegm(dt.datetime.strptime(match[1], "%Y-%m-%dT%H:%M:%S").timetuple())
    return seconds * 10**9 + int((match[2] or "").ljust(9, "0"))


def format_ns(value):
    seconds, nanos = divmod(value, 10**9)
    stamp = (dt.datetime(1970, 1, 1) + dt.timedelta(seconds=seconds)).strftime("%Y-%m-%dT%H:%M:%S")
    return stamp + (("." + f"{nanos:09d}".rstrip("0")) if nanos else "") + "Z"


def pointer(key):
    return str(key).replace("~", "~0").replace("/", "~1")


def differences(before, after, path=""):
    """Return exact changed leaves, including added/removed array elements."""
    if isinstance(before, dict) or isinstance(after, dict):
        require(before is MISSING or isinstance(before, dict), "Object type changed: " + path)
        require(after is MISSING or isinstance(after, dict), "Object type changed: " + path)
        left, right = ({} if before is MISSING else before), ({} if after is MISSING else after)
        return [d for key in sorted(left.keys() | right.keys())
                for d in differences(left.get(key, MISSING), right.get(key, MISSING), path + "/" + pointer(key))]
    if isinstance(before, list) or isinstance(after, list):
        require(before is MISSING or isinstance(before, list), "Array type changed: " + path)
        require(after is MISSING or isinstance(after, list), "Array type changed: " + path)
        left, right = ([] if before is MISSING else before), ([] if after is MISSING else after)
        return [d for i in range(max(len(left), len(right)))
                for d in differences(left[i] if i < len(left) else MISSING,
                                     right[i] if i < len(right) else MISSING, path + "/" + str(i))]
    if before is not MISSING and after is not MISSING and before == after:
        return []
    return [dict(path=path, beforePresent=before is not MISSING, afterPresent=after is not MISSING,
                 before=None if before is MISSING else before, after=None if after is MISSING else after)]


def offered_only(value, level):
    allowed = set()
    for i, contact in enumerate(value["scenario"]["contacts"]):
        payload, window = contact["payload"], contact["window"]
        require(payload["bytesPerSecond"] == 100 and payload["frameBytes"] == 1000,
                "Expected common 100 B/s and 1000 B frames")
        require(payload["minimumUplinkBytes"] == payload["minimumDownlinkBytes"] == 1000,
                "Service threshold must remain 1000 B/direction")
        duration = instant_ns(window["end"]) - instant_ns(window["start"])
        offered = {"low": 1000, "medium": 3000,
                   "high": max(3, duration // 10**10) * 1000}[level]
        for field in ("offeredUplinkBytes", "offeredDownlinkBytes"):
            payload[field] = offered
            allowed.add(f"/scenario/contacts/{i}/payload/{field}")
    return allowed


def create_models():
    original_manifest = load(HISTORICAL / "manifest.json")
    specs, audits = {}, []

    def add(name, historical_name, transform=None, meaning="Identical original bytes"):
        source = HISTORICAL / (historical_name + ".json")
        expected = original_manifest[historical_name]
        require(sha(source) == expected["sha256"], "Historical model hash changed: " + historical_name)
        original = load(source)
        value = copy.deepcopy(original)
        allowed = set() if transform is None else transform(value)
        diff = differences(original, value)
        actual = {d["path"] for d in diff}
        require(actual <= allowed, "Undeclared changes for " + name + ": " + str(actual - allowed))
        if transform is not None:
            require(actual, "Expected a real derivation: " + name)
        content = source.read_bytes() if transform is None else encoded(value)
        require(json.loads(content.decode("utf-8-sig"), parse_float=Decimal) == value,
                "Exact decimal serialization roundtrip failed")
        target = MODELS / (name + ".json")
        write_new_or_identical(target, content)
        specs[name] = dict(file=relative(target), sha256=sha(target), source=relative(source),
                           sourceSha256=sha(source), contacts=len(value["scenario"]["contacts"]),
                           copiedBytes=transform is None, derivation=meaning)
        audits.append(dict(model=name, **specs[name], allowedLeafPaths=sorted(allowed),
                           changedLeafCount=len(diff), changes=diff))
        return value

    for mission, source in (("ISS46", "ISS46"), ("NOAA38", "33591-base"), ("HST48", "20580-base")):
        for level in ("low", "medium", "high"):
            add(mission + "-" + level, source,
                None if level == "low" else lambda v, level=level: offered_only(v, level),
                "Identical LIGHT source bytes" if level == "low" else
                "Offer-only " + level + "; minimum1000/frame1000/rate100 retained; high=max(3,floor(durationNs/1e10))*1000 per direction")

    for name in ("X5", "Y", "demand-none", "X-undetected", "X-detected2", "X-gap",
                 "X-channel-temporary", "demand-late-detected"):
        add(name, name)

    for factor in (Decimal("0.5"), Decimal("2")):
        def install(value, factor=factor):
            allowed = offered_only(value, "medium")
            for i, phase in enumerate(value["scenario"]["costs"]["operations"]["INSTALL"]["phases"]):
                phase["duration"] *= factor
                allowed.add(f"/scenario/costs/operations/INSTALL/phases/{i}/duration")
            return allowed
        add("ISS46-medium-install" + str(factor), "ISS46", install,
            "Medium offers plus INSTALL phase durations x" + str(factor) + "; bytes fixed; all other operations unchanged")

    for cp in (2700, 10800):
        def cryptoperiod(value, cp=cp):
            allowed = offered_only(value, "medium")
            value["scenario"]["constraints"]["cryptoperiod"] = cp
            return allowed | {"/scenario/constraints/cryptoperiod"}
        add("ISS46-medium-cp" + str(cp), "ISS46", cryptoperiod,
            "Medium offers plus CP=" + str(cp) + "s; initial state and all other constraints unchanged")

    def management(value):
        scenario = value["scenario"]
        require(not scenario["initialState"]["keys"] and not scenario["plan"] and not scenario["disclosures"],
                "Management fixture requires empty keys, plan, disclosures")
        require(not scenario.get("channelInterruptions"), "Unexpected prior interruption")
        old_contacts = scenario["contacts"]
        first = old_contacts[0]["window"]
        aos = instant_ns(first["start"])
        scenario["contacts"] = old_contacts[:3]
        scenario["end"] = old_contacts[2]["window"]["end"]
        scenario["channelInterruptions"] = [dict(contactId=first["id"],
            **{"from": format_ns(aos + 10**9), "until": format_ns(aos + 3 * 10**9)})]
        require(aos + 3 * 10**9 < instant_ns(first["end"]), "Interruption outside C1")
        # Enumerate the exact leaves intentionally removed by deterministic prefix selection.
        removed = differences(old_contacts, old_contacts[:3], "/scenario/contacts")
        return {d["path"] for d in removed} | {"/scenario/end",
            "/scenario/channelInterruptions/0/contactId", "/scenario/channelInterruptions/0/from",
            "/scenario/channelInterruptions/0/until"}
    add("ISS3-low-management-interruption", "ISS46", management,
        "First three original windows; same full initial state/empty keys/authorization; end=C3end; channel off [C1AOS+1s,C1AOS+3s]")

    require(len(specs) == 22, "Expected 22 frozen model documents")
    return specs, audits


def cases_for(models):
    cases = []
    policies = (("FixedPeriod", "fixed", 1, "SEED_AND_RETENTION"),
                ("PerContact", "percontact", 1, "SEED_AND_RETENTION"),
                ("Next", "practical", 1, "SEED_AND_RETENTION"),
                ("Multi", "practical", 2, "SEED_AND_RETENTION"),
                ("Seed H1", "practical", 1, "SEED_WITHOUT_SEARCH"),
                ("Seed H2", "practical", 2, "SEED_WITHOUT_SEARCH"))

    def add(block, model, policy, measured=False, rule="CANONICAL", scenario=None, load_name=None, horizon=None):
        label, mode, default_horizon, portfolio = policy
        h = default_horizon if horizon is None else horizon
        scenario = scenario or model
        load_name = load_name or "historical"
        slug = label.lower().replace(" ", "-")
        identifier = f"{len(cases)+1:03d}-{block}-{model}-{'measured' if measured else 'offline'}-{slug}-H{h}-{rule.lower()}"
        cases.append(dict(id=identifier, model=models[model]["file"], modelSha256=models[model]["sha256"],
            mode=mode, rule=rule, horizon=h, portfolio=portfolio,
            period=60 if scenario in ("X5", "Y") else 3600, measured=measured,
            block=block, scenario=scenario, load=load_name, policy=label,
            metadata=dict(block=block, scenario=scenario, load=load_name, policy=label,
                          modelKey=model, contacts=models[model]["contacts"])))

    # C is deliberately first: fixed order before any new policy results.
    for model in ("X5", "Y", "demand-none"):
        for rule in ("CANONICAL", "CONTINUE_WAIT"):
            for policy in policies[2:4]:
                add("regression", model, policy, rule=rule)
    for model in ("X5", "Y", "demand-none"):
        for policy in policies[2:4]:
            add("regression", model, policy, measured=True)
    for mission in ("ISS46", "NOAA38", "HST48"):
        for level in ("low", "medium", "high"):
            for policy in policies:
                add("core", mission + "-" + level, policy, scenario=mission, load_name=level)
    for level in ("low", "medium", "high"):
        for policy in policies[:5]:
            add("measured", "ISS46-" + level, policy, measured=True, scenario="ISS46", load_name=level)
    for model in ("ISS46-medium-install0.5", "ISS46-medium-install2", "ISS46-medium-cp2700", "ISS46-medium-cp10800"):
        for policy in policies[2:4]:
            add("sensitivity", model, policy, scenario="ISS46", load_name="medium")
    add("sensitivity", "ISS46-medium", policies[3], scenario="ISS46", load_name="medium", horizon=3)
    for model in ("X-undetected", "X-detected2", "X-gap", "X-channel-temporary", "demand-late-detected"):
        for policy in policies[2:4]:
            add("security", model, policy)
    for policy in policies[2:4]:
        add("management", "ISS3-low-management-interruption", policy, scenario="ISS3", load_name="low")
    require(len(cases) == 108 and len({c["id"] for c in cases}) == 108, "Expected 108 unique cases")
    require(dict(Counter(c["block"] for c in cases)) == EXPECTED_COUNTS, "Block counts disagree")
    require(sum(c["measured"] for c in cases) == 21, "Expected 21 measured and 87 offline cases")
    return cases


def prepare():
    require(not (OUT / "protocol.json").exists(), "Already registered; use verify")
    models, audits = create_models()
    cases = cases_for(models)
    write_new_or_identical(MODELS / "derivation-audit.json", encoded(dict(status="PASS", models=audits,
        numericContract="Original copies retain bytes; derived floats parse as Decimal and serialize as exact JSON numbers",
        semanticContract="Only enumerated allowed leaves changed; initialState unchanged for every derived model")))
    write_new_or_identical(MODELS / "registration-plan.json", encoded(dict(models=models, cases=cases,
        counts=EXPECTED_COUNTS, total=108, measured=21, offline=87, budgets=BUDGETS)))
    print("PREPARED: 22 models, 108 cases; no JVM or policy execution")


def register(manifest_path):
    require(not (OUT / "protocol.json").exists(), "Refusing to replace an existing registration")
    manifest_path = manifest_path.resolve()
    manifest = load(manifest_path)
    require(manifest.get("jar") and manifest.get("jarSha256") and manifest.get("sourcesSha256"),
            "Final JAR/source identity unavailable; full-suite build must finish before registration")
    jar = (ROOT / manifest["jar"]).resolve()
    relative(jar)
    require(jar.is_file() and sha(jar) == manifest["jarSha256"], "Frozen JAR hash mismatch")
    require(manifest.get("sourcesFile"), "Frozen source manifest path unavailable")
    sources = (ROOT / manifest["sourcesFile"]).resolve()
    relative(sources)
    require(sources.is_file() and sha(sources) == manifest["sourcesSha256"], "Frozen source manifest hash mismatch")
    prepare()
    plan = load(MODELS / "registration-plan.json")
    require(DOC.is_file(), "Human-readable protocol missing")
    inputs = {relative(p): sha(p) for p in sorted(MODELS.glob("*.json"))}
    inputs.update({relative(DOC): sha(DOC), relative(Path(__file__)): sha(__file__), relative(sources): sha(sources)})
    protocol = dict(schemaVersion=1, series="final-research-v1", registeredAtUtc=dt.datetime.now(dt.timezone.utc).isoformat(),
        manifest=relative(manifest_path), manifestSha256=sha(manifest_path),
        binary=dict(identity=manifest.get("identity"), jar=relative(jar), jarSha256=sha(jar),
                    sources=relative(sources), sourcesSha256=sha(sources)),
        jar=relative(jar), jarSha256=sha(jar), sourcesSha256=sha(sources),
        generator=dict(file=relative(Path(__file__)), sha256=sha(__file__)), inputs=inputs,
        budgets=BUDGETS, measured=dict(fixedMilliseconds=0, hostScale=1, decisionMilliseconds=2000),
        counts=EXPECTED_COUNTS, totalCases=108, measuredCases=21, offlineCases=87,
        cases=plan["cases"], models=plan["models"],
        order="All listed cases in immutable order: regression18, core54, measured15, sensitivity9, security10, management2",
        objective=["servedContacts", "usefulSecureTime", "-controlCpu", "-managementBytes", "-newKeys"],
        fairness="Within each matched pair only H differs; same observations permitted by H, actions, constraints, costs, comparator, solver, portfolio and budgets",
        hypotheses=["Additional horizon can help a short return but is not expected to dominate all workloads",
                    "Y is a null mechanism control; previous demand disadvantages and incomplete outcomes remain reportable",
                    "Increased offers can expose demand-capped low-load results without changing service minima",
                    "Seed without DFS can match or outperform mission execution despite same-root portfolio dominance",
                    "Measured host delay and interruptions may reduce service or leave recovery censored"],
        stopping="One attempt per case; no automatic retries, budget increases, replacements, or outcome-based selection. Preserve technical failures/timeouts and continue independent registered cases. Identity/audit violations require inspection before dependent execution.",
        interpretation="Search COMPLETE, controller FINISHED, service and deadline compliance are distinct; no end-to-end optimum, universal horizon supremacy, cryptographic proof, or onboard performance claim",
        managementInterruption="First 3 ISS46 contacts; same original empty-key state; outage C1AOS+1 to +3 seconds. Intends to intersect initial INSTALL, but report actual operation; do not retune if intersection differs.",
        sourceBoundary="Public-orbit geometry on fixed historical dates; costs, traffic, authorization, lifecycle and attack assumptions synthetic; ISS day differs from NOAA/HST day",
        experimentalUnit="One deterministic scenario execution per configuration, not independent repeated statistical samples",
        exclusions=[])
    write_new_or_identical(OUT / "protocol.json", encoded(protocol))
    verify()
    print("REGISTERED protocol SHA-256:", sha(OUT / "protocol.json"))


def verify():
    protocol = load(OUT / "protocol.json")
    require(protocol["totalCases"] == len(protocol["cases"]) == 108, "Case count changed")
    require(dict(Counter(c["block"] for c in protocol["cases"])) == EXPECTED_COUNTS, "Block counts changed")
    require(protocol["cases"] == cases_for(protocol["models"]), "Registered case order/configuration changed")
    require(sha(ROOT / protocol["jar"]) == protocol["jarSha256"], "JAR changed")
    require(sha(ROOT / protocol["binary"]["sources"]) == protocol["sourcesSha256"], "Source identity changed")
    for name, expected in protocol["inputs"].items():
        require(sha(ROOT / name) == expected, "Registered input changed: " + name)
    for model in protocol["models"].values():
        require(sha(ROOT / model["source"]) == model["sourceSha256"], "Historical source changed")
    for case in protocol["cases"]:
        require(sha(ROOT / case["model"]) == case["modelSha256"], "Case model changed")
    print("VERIFIED: 108 cases, 22 models, source and JAR identities; no policy execution")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "register", "verify"))
    parser.add_argument("--manifest", type=Path, default=OUT / "manifest.json")
    args = parser.parse_args()
    if args.command == "register":
        register(args.manifest)
    else:
        globals()[args.command]()
