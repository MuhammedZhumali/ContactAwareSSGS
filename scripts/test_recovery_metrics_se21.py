"""Focused semantic fixtures and four frozen-journal regression checks."""
import copy
from decimal import Decimal as D
import unittest
from pathlib import Path

import audit_security_evaluation as se1
import recovery_metrics_se21 as metrics


EPOCH = metrics.instant("2026-01-01T00:00:00Z")
CID = {"satelliteId": "S", "groundStationId": "A"}
OLD = {"uplink": "old-up", "downlink": "old-down"}
NEW = {"uplink": "new-up", "downlink": "new-down"}


def at(seconds):
    return metrics.utc(EPOCH + D(str(seconds)))


def event(seconds, kind, action=None, keys=None, contact=None):
    return {"at": at(seconds), "type": kind, "action": action, "keyIds": keys,
            "contextId": CID, "contactId": contact}


def fixture(open_before_origin=False, no_origin=False):
    keys = {v: {"id": v, "contextId": CID, "direction": d.upper(), "createdAt": at(-5),
        "activatedAt": at(-5), "notAfter": at(100), "status": "ACTIVE", "installedOnSpacecraft": True,
        "nextNonceCounter": 0, "lastSuccessfulContactEnd": None} for d, v in OLD.items()}
    ctx = {"id": CID, "activeContact": None, "openedAt": None,
           **{d: {"id": d, "keyId": k, "status": "KEYED", "peerSynchronized": True,
                   "nextSequenceNumber": 0} for d, k in OLD.items()}}
    contacts = [{"window": {"id": n, **CID, "start": at(s), "end": at(e)},
                 "payload": {"frameBytes": 1, "bytesPerSecond": 1,
                    "offeredUplinkBytes": 10, "offeredDownlinkBytes": 10}} for n, s, e in (("C1", 0, 10), ("C2", 12, 20))]
    scenario = {"start": at(0), "end": at(20), "initialState": {"keys": keys, "contexts": {"A": ctx}},
        "constraints": {"maxMaterialAge": D(100), "cryptoperiod": D(9), "maxReuseGap": D(10),
                        "authorizedContexts": [CID], "blockedKeyIds": []}, "contacts": contacts,
        "disclosures": [{"contextId": CID, "at": at(1), "detectionAt": at(6)}]}
    events = [event(1, "KEY_DISCLOSED", keys=OLD), event(3, "STEP_COMPLETED", "PREPARE", NEW),
              event(4, "STEP_COMPLETED", "INSTALL", NEW, "C1"), event(5, "STEP_COMPLETED", "SWITCH", NEW, "C1")]
    if open_before_origin:
        events.append(event("5.5", "STEP_COMPLETED", "OPEN", NEW, "C1"))
    if not no_origin:
        events.append(event(6, "REVOCATION_RECEIVED_GROUND", keys=OLD))
    events += [event(7, "STEP_COMPLETED", "NOTIFY_REVOCATION", OLD, "C1"),
               event(7, "REVOCATION_RECEIVED_SPACECRAFT", "NOTIFY_REVOCATION", OLD, "C1")]
    if not open_before_origin:
        events.append(event(8, "STEP_COMPLETED", "OPEN", NEW, "C1"))
    events += [event(10, "STEP_COMPLETED", "STOP", NEW, "C1"),
               event(13, "STEP_COMPLETED", "OPEN", NEW, "C2")]
    for i, e in enumerate(events):
        e["sequence"] = i
    return scenario, events, {"at": at(20)}


class RecoveryMetricsTests(unittest.TestCase):
    def test_already_ready_pair_is_zero_but_not_an_activation_event(self):
        result = metrics.measure(*fixture())
        m = result["milestones"]["independent_pair"]
        self.assertEqual((m["status"], m["seconds"]), ("already-ready-at-origin", D(0)))
        self.assertEqual(result["milestones"]["independent_sa"]["seconds"], D(2))

    def test_already_open_at_origin(self):
        result = metrics.measure(*fixture(open_before_origin=True))
        self.assertEqual(result["milestones"]["independent_sa"]["status"], "already-ready-at-origin")

    def test_missing_frame_is_censored_never_zero(self):
        m = metrics.measure(*fixture())["milestones"]["independent_frame"]
        self.assertEqual(m["status"], "censored")
        self.assertIsNone(m["seconds"])
        self.assertEqual(m["censorAt"], at(20))

    def test_missing_origin_is_unavailable(self):
        result = metrics.measure(*fixture(no_origin=True))
        self.assertTrue(all(m["status"] == "unavailable" and m["seconds"] is None for m in result["milestones"].values()))

    def test_missing_sc_notice_is_censored(self):
        s, es, a = fixture()
        es = [e for e in es if e["type"] != "REVOCATION_RECEIVED_SPACECRAFT"]
        for i, e in enumerate(es): e["sequence"] = i
        self.assertEqual(metrics.measure(s, es, a)["milestones"]["spacecraft_notice"]["status"], "censored")

    def test_expiration_and_close_reopen_are_distinct_intervals(self):
        result = metrics.measure(*fixture())
        pair = [r for r in result["readinessIntervals"] if r["kind"] == "independent_pair"]
        sa = [r for r in result["readinessIntervals"] if r["kind"] == "independent_sa"]
        self.assertEqual([(r["from"], r["until"], r["endReason"]) for r in pair], [(at(6), at(14), "key-deadline")])
        self.assertEqual([(r["from"], r["until"], r["endReason"]) for r in sa],
                         [(at(8), at(10), "session-closed"), (at(13), at(14), "key-deadline")])

    def test_original_deadline_cannot_be_extended(self):
        s, es, a = fixture()
        state = metrics.Replay(s, OLD.values())
        state.keys["old-up"]["notAfter"] = at(1)
        state.keys["old-up"]["deadline"] = se1.deadline(state.keys["old-up"], s)
        self.assertEqual(state.keys["old-up"]["deadline"], EPOCH + 1)

    def test_counter_exhaustion_and_unconfirmed_peer_reject_pair(self):
        s, es, a = fixture()
        state = metrics.Replay(s, OLD.values())
        for e in es[:4]: state.apply(e)
        self.assertTrue(state.pair_ready(metrics.context(CID), EPOCH + 6))
        state.keys["new-up"]["nextNonceCounter"] = metrics.MAX_COUNTER
        state.contexts[metrics.context(CID)]["uplink"]["nextSequenceNumber"] = metrics.MAX_COUNTER
        self.assertFalse(state.pair_ready(metrics.context(CID), EPOCH + 6))
        state.keys["new-up"]["nextNonceCounter"] = 0
        state.contexts[metrics.context(CID)]["uplink"]["nextSequenceNumber"] = 0
        state.contexts[metrics.context(CID)]["downlink"]["peerSynchronized"] = False
        self.assertFalse(state.pair_ready(metrics.context(CID), EPOCH + 6))

    def test_prefix_censors_at_actual_observation_end(self):
        s, es, a = fixture()
        es = [e for e in es if metrics.instant(e["at"]) <= EPOCH + 7]
        result = metrics.measure(s, es, {"at": at(7)}, "SEARCH_INCOMPLETE")
        self.assertEqual(result["milestones"]["independent_sa"]["censorAt"], at(7))

    def test_overlapping_conditions_are_not_additive(self):
        r = metrics.measure(*fixture())
        self.assertGreater(sum(r["conditionSeconds"].values()), D(14))
        self.assertEqual(sum(x["durationSeconds"] for x in r["conditionIntervals"]), D(14))

    def test_no_attack_is_not_applicable(self):
        s, es, a = fixture()
        s["disclosures"] = []
        self.assertTrue(all(m["status"] == "not-applicable" for m in metrics.measure(s, es, a)["milestones"].values()))

    def test_four_frozen_journals(self):
        root = Path(__file__).resolve().parents[1] / "target/security-evaluation-se2"
        summary = se1.load(root / "summary.json")
        expected = {
            "X-next-original-detected": ("0.4", "7.4", "69", "69.25"),
            "X-multi-original-detected": ("0.4", "1.4", "2.4", "2.65"),
            "iss3-cp5400-next-p30-detected": ("0.4", "7.4", "5661.427337647", "5671.427337647"),
            "iss3-cp5400-multi-p30-detected": ("0.4", "1.4", "5657.427337647", "5667.427337647")}
        for name, delays in expected.items():
            with self.subTest(case=name):
                row = summary[name]
                r = metrics.measure(se1.load(root / row["modelFile"])["scenario"], se1.load(root / row["eventsFile"]), se1.load(root / row["actualFile"]))
                self.assertEqual(tuple(r["milestones"][k]["seconds"] for k in metrics.MILESTONES), tuple(map(D, delays)))
                if name.startswith("iss"):
                    pairs = [i for i in r["readinessIntervals"] if i["kind"] == "independent_pair"]
                    self.assertEqual(len(pairs), 2)
                    self.assertEqual(pairs[0]["durationSeconds"], D(5400))
                    self.assertEqual(pairs[0]["endReason"], "key-deadline")


if __name__ == "__main__":
    unittest.main()
