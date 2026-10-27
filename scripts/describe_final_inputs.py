#!/usr/bin/env python3
"""Exact descriptive statistics of frozen final core inputs; never reads run outcomes."""
from __future__ import annotations

import calendar
import datetime as dt
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / "target/final-research/protocol.json"
OUTPUT = ROOT / "target/final-research/workload-descriptors.json"
REPORT = ROOT / "docs/final-research/workload-descriptors.md"
D = Decimal


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8-sig"), parse_float=D)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def relative(path):
    return Path(path).resolve().relative_to(ROOT).as_posix()


def instant_ns(value):
    require(value.endswith("Z"), "Expected UTC instant")
    whole, separator, fraction = value[:-1].partition(".")
    require(not separator or 1 <= len(fraction) <= 9, "Expected nanosecond precision")
    seconds = calendar.timegm(dt.datetime.strptime(whole, "%Y-%m-%dT%H:%M:%S").timetuple())
    return seconds * 10**9 + int((fraction or "").ljust(9, "0"))


def seconds(ns):
    return D(ns) / D(10**9)


def summary_ns(values):
    numbers = [D(x) for x in values]
    return dict(count=len(numbers), minimumSeconds=seconds(min(numbers)) if numbers else None,
                medianSeconds=seconds(statistics.median(numbers)) if numbers else None,
                maximumSeconds=seconds(max(numbers)) if numbers else None,
                totalSeconds=seconds(sum(numbers, D(0))))


def context_id(contact):
    window = contact["window"]
    return window["satelliteId"], window["groundStationId"]


def score_mission(name, model):
    scenario = model["scenario"]
    contacts = scenario["contacts"]
    cryptoperiod = D(scenario["constraints"]["cryptoperiod"])
    require(cryptoperiod == D(5400), "Unexpected base cryptoperiod")
    cryptoperiod_ns = int(cryptoperiod * D(10**9))
    configured = {(x["id"]["satelliteId"], x["id"]["groundStationId"])
                  for x in scenario["initialState"]["contexts"].values()}
    durations, records, recurrences, triples = [], [], [], []
    last = {}
    for index, contact in enumerate(contacts):
        window, context = contact["window"], context_id(contact)
        require(context in configured, "Contact has no existing initial-state context")
        start, end = instant_ns(window["start"]), instant_ns(window["end"])
        require(end > start, "Nonpositive window")
        if index:
            require(start >= instant_ns(contacts[index-1]["window"]["end"]), "Overlapping/unordered selected contacts")
        duration = end - start
        prior = last.get(context)
        row = dict(index=index, contactId=window["id"], satelliteId=context[0], groundStationId=context[1],
                   start=window["start"], end=window["end"], durationNanoseconds=duration,
                   durationSeconds=seconds(duration), previousSameContextIndex=prior)
        if prior is not None:
            gap = start - instant_ns(contacts[prior]["window"]["end"])
            recurrence = dict(returnIndex=index, previousIndex=prior, contactId=window["id"],
                previousContactId=contacts[prior]["window"]["id"], satelliteId=context[0],
                groundStationId=context[1], gapNanoseconds=gap, gapSeconds=seconds(gap),
                gapExceedsBaseCryptoperiod=gap > cryptoperiod_ns,
                gapMinusBaseCryptoperiodNanoseconds=gap - cryptoperiod_ns,
                returnWindowNanoseconds=duration, returnWindowSeconds=seconds(duration))
            recurrences.append(recurrence)
        records.append(row)
        durations.append(duration)
        last[context] = index
    for index in range(len(contacts)-2):
        a, b, returned = contacts[index:index+3]
        if context_id(a) == context_id(returned) and context_id(a) != context_id(b):
            gap = instant_ns(returned["window"]["start"]) - instant_ns(a["window"]["end"])
            triples.append(dict(indices=[index, index+1, index+2],
                contactIds=[x["window"]["id"] for x in (a, b, returned)],
                stationA=a["window"]["groundStationId"], stationB=b["window"]["groundStationId"],
                returnGapNanoseconds=gap, returnGapSeconds=seconds(gap),
                returnWindowNanoseconds=durations[index+2], returnWindowSeconds=seconds(durations[index+2])))
    costs = {}
    for action in ("INSTALL", "SWITCH", "OPEN"):
        phases = scenario["costs"]["operations"][action]["phases"]
        costs[action] = dict(phaseDurationsSeconds=[p["duration"] for p in phases],
                             elapsedSeconds=sum((D(p["duration"]) for p in phases), D(0)))
    require([costs[a]["elapsedSeconds"] for a in ("INSTALL", "SWITCH", "OPEN")] == [D(4), D(1), D(1)],
            "Frozen base operation costs differ from the requested timing descriptors")
    short = [row for row in records if 22 * 10**9 <= row["durationNanoseconds"] < 26 * 10**9]
    return dict(mission=name, contacts=len(contacts), initialConfiguredContexts=len(configured),
        distinctContactContexts=len(last), initialKeyRecords=len(scenario["initialState"]["keys"]),
        lengths=summary_ns(durations), windows=records,
        recurrenceDefinition="Gap = next occurrence start minus previous occurrence end for identical satelliteId/groundStationId; intervening stations may exist",
        sameContextRecurrenceGaps=summary_ns([x["gapNanoseconds"] for x in recurrences]),
        baseCryptoperiodSeconds=cryptoperiod, baseCryptoperiodNanoseconds=cryptoperiod_ns,
        recurrenceGapsGreaterThanBaseCryptoperiod=sum(x["gapNanoseconds"] > cryptoperiod_ns for x in recurrences),
        recurrenceGapsEqualToBaseCryptoperiod=sum(x["gapNanoseconds"] == cryptoperiod_ns for x in recurrences),
        recurrenceGapsLessThanBaseCryptoperiod=sum(x["gapNanoseconds"] < cryptoperiod_ns for x in recurrences),
        recurrenceCount=len(recurrences), firstOccurrenceCount=len(contacts)-len(recurrences), recurrences=recurrences,
        consecutiveABATripleCount=len(triples), consecutiveABATriples=triples,
        consecutiveABAReturnGaps=summary_ns([x["returnGapNanoseconds"] for x in triples]),
        verifiedBaseCosts=costs,
        conditionalThresholdSeconds=dict(payloadOnly=D(20), installedEntry=D(22), preparedEntry=D(26)),
        distinguishingWindowCount=len(short), distinguishingWindowsWithPreviousSameContext=sum(x["previousSameContextIndex"] is not None for x in short),
        distinguishingWindows=short)


def describe_load(mission, level, model, base, descriptor):
    scenario, original = model["scenario"], base["scenario"]
    cryptoperiod = D(scenario["constraints"]["cryptoperiod"])
    require(cryptoperiod == D(5400), "Expected CP=5400 in every frozen core model")
    for field in ("start", "end", "initialState", "constraints", "costs", "plan", "disclosures"):
        require(scenario.get(field) == original.get(field), "Load changes more than offers: " + field)
    require(len(scenario["contacts"]) == len(original["contacts"]), "Changed contact count")
    rows = []
    for index, (contact, baseline) in enumerate(zip(scenario["contacts"], original["contacts"])):
        require(contact["window"] == baseline["window"], "Changed geometry")
        p = contact["payload"]
        require(p["bytesPerSecond"] == 100 and p["frameBytes"] == 1000 and
                p["minimumUplinkBytes"] == p["minimumDownlinkBytes"] == 1000,
                "Unexpected rate/frame/minimum")
        up_frames = (p["minimumUplinkBytes"] + p["frameBytes"] - 1) // p["frameBytes"]
        down_frames = (p["minimumDownlinkBytes"] + p["frameBytes"] - 1) // p["frameBytes"]
        frame_ns = p["frameBytes"] * 10**9 // p["bytesPerSecond"]
        require(frame_ns * p["bytesPerSecond"] == p["frameBytes"] * 10**9, "Fractional-nanosecond frame")
        minimum_ns = (up_frames + down_frames) * frame_ns
        require(minimum_ns == 20 * 10**9, "Unexpected minimum payload time")
        duration_ns = descriptor["windows"][index]["durationNanoseconds"]
        expected_offer = {"low": 1000, "medium": 3000, "high": max(3, duration_ns // 10**10) * 1000}[level]
        require(p["offeredUplinkBytes"] == p["offeredDownlinkBytes"] == expected_offer, "Offer differs from frozen registration rule")
        rows.append(dict(index=index, contactId=contact["window"]["id"], durationNanoseconds=duration_ns,
            offeredUplinkBytes=p["offeredUplinkBytes"], offeredDownlinkBytes=p["offeredDownlinkBytes"],
            minimumUplinkFrames=up_frames, minimumDownlinkFrames=down_frames,
            frameDurationNanoseconds=frame_ns, minimumPayloadNanoseconds=minimum_ns,
            fitsPayloadOnly=duration_ns >= minimum_ns,
            fitsInstalledEntry=duration_ns >= minimum_ns + 2 * 10**9,
            fitsPreparedEntry=duration_ns >= minimum_ns + 6 * 10**9))
    up = sum(x["offeredUplinkBytes"] for x in rows)
    down = sum(x["offeredDownlinkBytes"] for x in rows)
    return dict(mission=mission, load=level, contacts=len(rows), offeredUplinkBytes=up,
        offeredDownlinkBytes=down, offeredTotalBytes=up+down,
        verifiedBaseCryptoperiodSeconds=cryptoperiod,
        minimumPayloadSeconds=D(20), installedEntrySeconds=D(22), preparedEntrySeconds=D(26),
        fitsPayloadOnlyCount=sum(x["fitsPayloadOnly"] for x in rows),
        fitsInstalledEntryCount=sum(x["fitsInstalledEntry"] for x in rows),
        fitsPreparedEntryCount=sum(x["fitsPreparedEntry"] for x in rows), windows=rows)


def number(value):
    if value is None:
        return "—"
    if isinstance(value, Decimal):
        return format(value, "f").rstrip("0").rstrip(".") if value != value.to_integral() else str(int(value))
    return str(value)


def table(headers, rows):
    return "| " + " | ".join(headers) + " |\n| " + " | ".join(["---"]*len(headers)) + " |\n" + "\n".join(
        "| " + " | ".join(number(x) for x in row) + " |" for row in rows) + "\n"


def main():
    protocol = load(PROTOCOL)
    selected = {}
    for case in protocol["cases"]:
        if case["block"] != "core":
            continue
        key = (case["scenario"], case["load"])
        item = dict(file=case["model"], sha256=case["modelSha256"])
        require(key not in selected or selected[key] == item, "Core policies do not share model identity")
        selected[key] = item
    require(len(selected) == 9, "Expected exactly nine frozen core input models")
    models, identities = {}, []
    for (mission, level), entry in selected.items():
        path = ROOT / entry["file"]
        require(sha(path) == entry["sha256"], "Model hash mismatch: " + entry["file"])
        models[(mission, level)] = load(path)
        identities.append(dict(mission=mission, load=level, **entry))
    missions, loads = [], []
    for mission in ("ISS46", "NOAA38", "HST48"):
        base = models[(mission, "low")]
        descriptor = score_mission(mission, base)
        missions.append(descriptor)
        for level in ("low", "medium", "high"):
            loads.append(describe_load(mission, level, models[(mission, level)], base, descriptor))
    result = dict(schemaVersion=1, sourceProtocol=relative(PROTOCOL), protocolSha256=sha(PROTOCOL),
        generator=relative(Path(__file__)), generatorSha256=sha(__file__), models=identities,
        arithmetic="Integer UTC nanoseconds; Decimal seconds and medians. Decimal JSON values are exact strings; all byte/count fields remain integers.",
        scope="Input-only; no run outcome/checkpoint, attack assumption, JVM, policy execution or optimization",
        conditionalTiming="20s = two required complete payload frames; 22s = SWITCH1+OPEN1+payload20; 26s = INSTALL4+SWITCH1+OPEN1+payload20. Freshly prepared-at-AOS material/installed material and immediately available serial resources are conditional premises, not initial-state facts.",
        conditionalActivatedKeyDeduction="Let e be the previous same-context contact end, s the next start, and a a key's activation time. If a <= e, gap=s-e > CP and lifecycle deadline <= a+CP, then deadline <= a+CP <= e+CP < s: that activated key cannot be reused at the next occurrence. CP=5400 is verified separately in all nine core models. Unactivated staged candidates are not excluded; the CP=10800 sensitivity is not covered. Other lifecycle/resource constraints still apply, and this is not a general claim that extra horizon is useless.",
        limitations="No feasibility certificate: timing counts omit PREPARE2, reuse/open-only alternatives, expiry/material-age/reuse-gap constraints, authorization changes, ongoing work, delays and interruptions. Existing initial keys and station identity are read from frozen input only.",
        missions=missions, loads=loads)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2, default=str)+"\n", encoding="utf-8")
    text = "# Frozen core workload descriptors\n\n"
    text += "These are input-only descriptive calculations for the nine registered core models. No campaign results or checkpoints were read, and no JVM, simulation, new policy run or parameter adjustment was performed. Model hashes were checked against `target/final-research/protocol.json`. Every timestamp was converted to integer nanoseconds; all displayed seconds and medians use exact Decimal arithmetic. The JSON saves each window, recurrence, qualifying triple and payload calculation, with Decimal values represented as exact strings.\n\n"
    text += "## Contact geometry and existing state\n\n"
    text += table(["Mission", "Contacts", "Initial contexts / key records", "Minimum s", "Median s", "Maximum s", "Total selected visible s"],
        [[m["mission"],m["contacts"],f'{m["initialConfiguredContexts"]} / {m["initialKeyRecords"]}',
          m["lengths"]["minimumSeconds"],m["lengths"]["medianSeconds"],m["lengths"]["maximumSeconds"],m["lengths"]["totalSeconds"]] for m in missions])
    text += "\nTotal visible time sums the selected nonoverlapping contact lengths; it is not secure service or radio utilization. All three inputs start with zero key records. A repeated station identifier therefore does not imply preinstalled or still-valid material. Station equality uses the existing `(satelliteId, groundStationId)` context identity, checked against initial-state contexts.\n\n"
    text += "## Same-station recurrences\n\n"
    text += "A recurrence gap is the next occurrence's start minus the previous occurrence's end for the same context; other stations may lie between them. First occurrences have no recurrence gap and are excluded from gap medians. A consecutive A-B-A triple requires three adjacent selected contacts, equal first/third contexts and a distinct middle context. These are chronological descriptors, not selected successful handovers.\n\n"
    text += table(["Mission", "Recurrences", "First occurrences", "Gap minimum s", "Gap median s", "Gap maximum s", "Consecutive A-B-A triples"],
        [[m["mission"],m["recurrenceCount"],m["firstOccurrenceCount"],m["sameContextRecurrenceGaps"]["minimumSeconds"],
          m["sameContextRecurrenceGaps"]["medianSeconds"],m["sameContextRecurrenceGaps"]["maximumSeconds"],m["consecutiveABATripleCount"]] for m in missions])
    text += "\nThe base cryptoperiod (`scenario.constraints.cryptoperiod`) was read and verified as exactly 5400 s in all nine core models, including every load. The following comparisons use integer nanoseconds and strict `gap > CP`; equality is counted separately.\n\n"
    text += table(["Mission", "Base CP s", "Recurrence gaps > CP", "Gaps = CP", "Gaps < CP"],
        [[m["mission"],m["baseCryptoperiodSeconds"],m["recurrenceGapsGreaterThanBaseCryptoperiod"],
          m["recurrenceGapsEqualToBaseCryptoperiod"],m["recurrenceGapsLessThanBaseCryptoperiod"]] for m in missions])
    text += "\nConditional deduction: let `e` be the previous same-station contact end, `s` the next start, and `a` a key's activation time. For a key already activated by `e`, `a <= e`. Under the lifecycle deadline bound `deadline <= a + CP`, any recurrence with `s - e > CP` gives `deadline <= a + CP <= e + CP < s`. That activated key therefore cannot be reused at the next occurrence under the base CP. This applies to all listed recurrences. It does not exclude unactivated staged candidates, whose later activation may still be possible subject to the remaining lifecycle and resource constraints. The CP=10800 sensitivity is outside this deduction's verified input scope. Neither this exclusion nor the duration counts establish that extra horizon is generally useless.\n"
    text += "\n## Offered demand and conditional timing counts\n\n"
    text += "The frozen base costs were summed from their phases and checked separately for every mission: INSTALL=4 s, SWITCH=1 s and OPEN=1 s. Each model uses 100 B/s, 1000 B complete frames and a 1000 B minimum in each direction. Two required frames therefore occupy 20 s. The following counts test only window duration against 20 s, an installed-entry serial chain SWITCH+OPEN+payload=22 s, or a prepared-entry serial chain INSTALL+SWITCH+OPEN+payload=26 s. Equality fits; no security state is inferred from that arithmetic.\n\n"
    text += table(["Mission", "Load", "Offered UL B", "Offered DL B", "Offered total B", "L >= 20 s", "L >= 22 s", "L >= 26 s"],
        [[x["mission"],x["load"],x["offeredUplinkBytes"],x["offeredDownlinkBytes"],x["offeredTotalBytes"],
          x["fitsPayloadOnlyCount"],x["fitsInstalledEntryCount"],x["fitsPreparedEntryCount"]] for x in loads])
    text += "\nLow offers 1000 B/direction, medium 3000, and high `max(3, floor(window duration / 10 s))*1000` per direction. The service minimum is identical at every load, so timing-fit counts are identical even though offered volume changes. Offered UL+DL is demand, not jointly deliverable capacity on the shared serial radio.\n\n"
    text += table(["Mission", "Distinguishing windows: 22 <= L < 26 s", "Of these, prior same context exists"],
        [[m["mission"],m["distinguishingWindowCount"],m["distinguishingWindowsWithPreviousSameContext"]] for m in missions])
    text += "\nThe 22-to-26-second band describes windows where these two particular chains differ in their duration test. A matching earlier station is necessary only for the stated recurrence descriptor; it does not certify usable installed keys. These counts omit PREPARE's 2 s, valid-active-key OPEN/REUSE alternatives, key expiry/material-age/reuse-gap checks, authorization changes, pending work, resource conflicts, planning delays and channel interruption. They are neither achievable-service bounds nor optimum/service guarantees, and absence of a window in this band is not proof that extra horizon is useless. Wider horizons can change preparation timing, reuse, costs or execution even outside this narrow chain comparison. No attacks were added or inferred.\n\n"
    text += "## Exact input identities\n\n"
    text += table(["Mission", "Load", "Model", "SHA-256"],
        [[x["mission"],x["load"],"`"+x["file"]+"`","`"+x["sha256"]+"`"] for x in identities])
    text += "\nMachine calculations: `target/final-research/workload-descriptors.json`. Generator: `scripts/describe_final_inputs.py`. Reproduction command: `python -X utf8 scripts/describe_final_inputs.py`. This command reads only the registration and its nine core input models and writes only this report and its JSON companion.\n"
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(text, encoding="utf-8")
    print("PASS: 9 model hashes; exact window/cost/offer calculations; no run outputs read")
    print("JSON SHA-256:",sha(OUTPUT))
    for m in missions:
        print(m["mission"],"contacts",m["contacts"],"lengths",m["lengths"],"recurrences",m["recurrenceCount"],
              "ABA",m["consecutiveABATripleCount"],"22<=L<26",m["distinguishingWindowCount"],
              "prior same context",m["distinguishingWindowsWithPreviousSameContext"])


if __name__ == "__main__":
    main()
