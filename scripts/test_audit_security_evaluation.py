"""Small independent accounting oracles; these do not invoke Java collectors."""
import copy
from decimal import Decimal as D
import unittest
import audit_security_evaluation as audit

CTX = {"satelliteId": "SC", "groundStationId": "A"}
KEYS = {"uplink": "old-up", "downlink": "old-down"}
EPOCH = audit.instant("2026-01-01T00:00:00Z")


def at(s):
    return "2026-01-01T00:00:" + (f"{s:012.9f}".rstrip("0").rstrip(".")).zfill(2) + "Z"


def event(kind, when, keys=KEYS):
    return {"type": kind, "at": at(when), "operationId": "incident", "keyIds": keys, "contextId": CTX,
            "action": None, "usage": audit.zero_usage()}


def frame(start, end, key, delivered=True, direction="UPLINK"):
    return {"from": EPOCH + D(str(start)), "until": EPOCH + D(str(end)), "fullDuration": D(1),
            "frameBytes": 100, "emittedBytes": int(D(str(end - start)) * 100),
            "delivered": delivered, "direction": direction, "keyId": key, "contextId": CTX}


def scenario(disclosure=2.5, detection=3.5):
    return {"disclosures": [{"id": "incident", "contextId": CTX, "at": at(disclosure),
                             "detectionAt": None if detection is None else at(detection)}], "end": at(10)}


class SecurityAuditTests(unittest.TestCase):
    def test_manual_350_exposed_400_delivered_100_confidential(self):
        frames = [frame(0, 1, "old-up"), frame(1, 2, "old-down", direction="DOWNLINK"),
                  frame(2, 3, "old-up"), frame(3, 3.5, "old-up", False), frame(5, 6, "new-down", direction="DOWNLINK")]
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        result = audit.reconstruct_security(scenario(), events, frames)
        self.assertEqual((result["decryptableBeforeBytes"], result["decryptableAfterBytes"]), (250, 100))
        self.assertEqual((result["deliveredBytes"], result["emittedBytes"], result["confidentialDeliveredBytes"]), (400, 450, 100))
        self.assertEqual(result["compromisedUseAfter"], D(1))
        self.assertEqual(result["detectionToRecovery"], D("2.5"))

    def test_byte_split_floors_once(self):
        f = frame(0, 1, "old-up")
        before = audit.emitted_before(f, EPOCH + D("0.333333333"))
        self.assertEqual((before, f["emittedBytes"] - before), (33, 67))

    def test_no_target_not_zero_delay(self):
        result = audit.reconstruct_security(scenario(), [event("DISCLOSURE_NO_TARGET", 2.5, None)], [])
        self.assertEqual(result["recoveryStatus"], "NO_TARGET")
        self.assertIsNone(result["detectionToRecovery"])

    def test_censored_not_zero_delay(self):
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        result = audit.reconstruct_security(scenario(), events, [frame(0, 1, "old-up")])
        self.assertEqual(result["recoveryStatus"], "CENSORED_NO_RECOVERY")
        self.assertIsNone(result["detectionToRecovery"])
        self.assertEqual(result["censorAt"], EPOCH + 10)

    def test_independent_inflight_at_late_notice_counts_when_complete(self):
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        result = audit.reconstruct_security(scenario(), events, [frame(3, 4, "new-down", direction="DOWNLINK")])
        self.assertEqual(result["detectionToRecovery"], D(".5"))

    def test_precommitted_downlink_tail_reported_not_delivered(self):
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        result = audit.reconstruct_security(scenario(), events, [frame(3, 4, "old-down", False, "DOWNLINK")])
        self.assertEqual(result["bytesAfterGroundNotice"], 50)
        self.assertEqual(result["useAfterGroundNotice"], D(".5"))
        self.assertEqual(result["deliveredBytes"], 0)

    def test_new_admission_after_ground_notice_is_rejected(self):
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        with self.assertRaisesRegex(ValueError, "New compromised frame"):
            audit.reconstruct_security(scenario(), events, [frame(4, 5, "old-down", False, "DOWNLINK")])

    def test_uplink_emission_after_ground_notice_is_rejected(self):
        events = [event("KEY_DISCLOSED", 2.5), event("REVOCATION_RECEIVED_GROUND", 3.5)]
        with self.assertRaisesRegex(ValueError, "Post-GS emission"):
            audit.reconstruct_security(scenario(), events, [frame(3, 4, "old-up", False)])


if __name__ == "__main__":
    unittest.main()
