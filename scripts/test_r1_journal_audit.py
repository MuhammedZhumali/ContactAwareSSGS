#!/usr/bin/env python3
"""Focused, read-only R1 journal audit regressions; no simulator is launched.

Three exact-series fixtures are read by their fixed paths. Mutations remain in
memory and test accounting rejection, not reachability of a simulated state.
This does not certify checkpoint restoration: audit() has no restored snapshot
input, so exporter/checkpoint equality remains a separate integration check.
Run with: python -B scripts/test_r1_journal_audit.py -v
"""
from copy import deepcopy
from decimal import Decimal as D
from pathlib import Path
import unittest

import analyze_research_validation as subject
import audit_security_evaluation as journal
import recovery_metrics_r1 as recovery
from recovery_metrics_se21 import utc


ROOT = Path(__file__).resolve().parents[1]
SAVED = ROOT / "target/research-validation"
FIXTURES = {
    "finished": ("X3", "001-X3-exact-H1-CANONICAL"),
    "prefix": ("X3", "002-X3-exact-H2-CANONICAL"),
    "aborted": ("demand-late-detected", "021-demand-late-detected-exact-H1-CANONICAL"),
}
REJECTION = (AssertionError, ValueError)


class JournalAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.saved = {}
        for name, (model, run) in FIXTURES.items():
            folder = SAVED / "exact/runs" / run
            paths = [SAVED / "models" / (model + ".json"),
                     folder / "actual.events.json", folder / "actual.actual.json",
                     folder / "result.json"]
            if all(p.is_file() for p in paths):
                scenario, events, actual, result = map(journal.load, paths)
                cls.saved[name] = (scenario["scenario"], events, actual, result)

    def case(self, name):
        if name not in self.saved:
            self.skipTest("Saved R1 fixture unavailable: " + FIXTURES[name][1])
        return deepcopy(self.saved[name])

    @staticmethod
    def first_frame(events, kind="PAYLOAD_TRANSFERRED"):
        return next(e for e in events if e["type"] == kind)

    @staticmethod
    def resequence(events):
        events.sort(key=lambda e: journal.instant(e["at"]))
        for sequence, event in enumerate(events):
            event["sequence"] = sequence

    @staticmethod
    def channel_event(template, kind, at):
        event = deepcopy(template)
        event.update(type=kind, at=utc(at), operationId=None, action=None,
                     usage=journal.zero_usage(), usefulTime=D(0), createdKeys=0,
                     uplinkPayloadBytes=0, downlinkPayloadBytes=0,
                     uplinkFrames=0, downlinkFrames=0, frameEvidence=None,
                     failureCode=None, detail="Controlled audit mutation")
        return event

    def pending_prefix(self):
        """Accounting-only mutation: a PREPARE has accrued 0.1 s, unrecorded."""
        scenario, events, actual, result = self.case("prefix")
        begin = journal.instant(actual["at"])
        elapsed = D("0.1")
        started = deepcopy(events[0])
        started.update(type="STEP_STARTED", operationId="audit-pending-prepare",
                       action="PREPARE", contactId=None)
        events.append(started)
        self.resequence(events)
        actual["at"] = utc(begin + elapsed)
        actual["partialProgress"]["at"] = actual["at"]
        actual["partialProgress"]["pendingOperations"] = 1
        actual["pendingOperations"] = [dict(
            operationId=started["operationId"], steps=["PREPARE"], stepIndex=0,
            stepStartedAt=started["at"])]
        actual["partialProgress"]["accruedCosts"] = journal.consumed(
            scenario["costs"]["operations"]["PREPARE"], elapsed)
        return scenario, events, actual, result

    def test_saved_finished_five_metrics_reconstructed(self):
        case = self.case("finished")
        checked = subject.audit(*case)
        metrics = case[3]["simulationMetrics"]
        self.assertEqual(checked["five"], dict(
            servedContacts=metrics["servedContacts"],
            usefulSecureTime=metrics["usefulSecureTime"],
            controlCpu=metrics["controlCosts"]["spacecraftCpu"]
                       + metrics["controlCosts"]["groundCpu"],
            managementBytes=metrics["controlCosts"]["uplinkBytes"]
                            + metrics["controlCosts"]["downlinkBytes"],
            newKeys=metrics["createdKeys"]))
        self.assertGreater(checked["frames"], 0)

    def test_saved_prefix_keeps_final_metrics_unavailable(self):
        checked = subject.audit(*self.case("prefix"))
        self.assertIsNone(checked["five"])
        self.assertIsNone(checked["security"])
        self.assertIsNone(checked["confidential"]["final"])
        self.assertEqual(checked["confidential"]["status"], "UNAVAILABLE")
        self.assertIsNotNone(checked["prefixFive"])

    def test_prefix_rejects_actual_final_metrics(self):
        scenario, events, actual, result = self.case("prefix")
        actual["finalMetrics"] = self.case("finished")[2]["finalMetrics"]
        with self.assertRaisesRegex(REJECTION, "Incomplete execution has final metrics"):
            subject.audit(scenario, events, actual, result)

    def test_prefix_rejects_result_final_metrics(self):
        scenario, events, actual, result = self.case("prefix")
        result["simulationMetrics"] = self.case("finished")[3]["simulationMetrics"]
        with self.assertRaises(REJECTION):
            subject.audit(scenario, events, actual, result)

    def test_corrupt_key_nonce_is_rejected(self):
        scenario, events, actual, result = self.case("finished")
        key_id = self.first_frame(events)["keyIds"]["uplink"]
        actual["securityState"]["keys"][key_id]["nextNonceCounter"] += 1
        with self.assertRaisesRegex(REJECTION, "Nonce conservation"):
            subject.audit(scenario, events, actual, result)

    def test_corrupt_bound_sa_nonce_is_rejected(self):
        scenario, events, actual, result = self.case("prefix")
        context = next(c for c in actual["securityState"]["contexts"].values()
                       if c["uplink"]["keyId"] is not None)
        context["uplink"]["nextSequenceNumber"] += 1
        with self.assertRaisesRegex(REJECTION, "SA nonce differs from bound key"):
            subject.audit(scenario, events, actual, result)

    def test_consistently_corrupted_final_costs_are_rejected(self):
        scenario, events, actual, result = self.case("finished")
        # Both exports agree, but neither agrees with the primitive journal.
        for metrics in (actual["finalMetrics"], result["simulationMetrics"]):
            metrics["controlCosts"]["groundCpu"] += D("0.01")
        with self.assertRaises(REJECTION):
            subject.audit(scenario, events, actual, result)

    def test_corrupt_terminal_phase_cost_is_rejected(self):
        scenario, events, actual, result = self.case("finished")
        terminal = next(e for e in events if e["type"] in journal.TERMINALS)
        terminal["usage"]["spacecraftCpu"] += D("0.01")
        with self.assertRaisesRegex(REJECTION, "Phase integral mismatch"):
            subject.audit(scenario, events, actual, result)

    def test_prefix_recorded_cost_corruption_is_rejected(self):
        scenario, events, actual, result = self.case("prefix")
        actual["partialProgress"]["recordedCosts"]["uplinkBytes"] += 1
        with self.assertRaises(REJECTION):
            subject.audit(scenario, events, actual, result)

    def test_pending_primitive_accrues_without_recorded_cost(self):
        scenario, events, actual, result = self.pending_prefix()
        self.assertEqual(actual["partialProgress"]["recordedCosts"], journal.zero_usage())
        self.assertGreater(actual["partialProgress"]["accruedCosts"]["elapsed"], 0)
        checked = subject.audit(scenario, events, actual, result)
        self.assertIsNone(checked["five"])
        self.assertEqual(checked["prefixFive"]["controlCpu"], 0)

    def test_pending_primitive_accrued_cost_corruption_is_rejected(self):
        scenario, events, actual, result = self.pending_prefix()
        actual["partialProgress"]["accruedCosts"]["spacecraftCpu"] += D("0.01")
        with self.assertRaises(REJECTION):
            subject.audit(scenario, events, actual, result)

    def test_saved_partial_abort_commits_nonce_without_delivery(self):
        scenario, events, actual, result = self.case("aborted")
        aborted = self.first_frame(events, "PAYLOAD_ABORTED")
        frame = journal.read_frame(aborted, {c["window"]["id"]: c for c in scenario["contacts"]})
        self.assertGreater(frame["emittedBytes"], 0)
        self.assertLess(frame["emittedBytes"], frame["frameBytes"])
        self.assertLess(frame["until"] - frame["from"], frame["fullDuration"])
        self.assertEqual(aborted["usefulTime"], 0)
        self.assertEqual(aborted["uplinkPayloadBytes"] + aborted["downlinkPayloadBytes"], 0)
        checked = subject.audit(scenario, events, actual, result)
        self.assertGreaterEqual(checked["abortedFrames"], 1)
        self.assertEqual(checked["nonceAccounting"], "delivered plus aborted committed frames")

    def test_omitted_abort_nonce_is_rejected(self):
        scenario, events, actual, result = self.case("aborted")
        key_id = self.first_frame(events, "PAYLOAD_ABORTED")["frameEvidence"]["keyId"]
        actual["securityState"]["keys"][key_id]["nextNonceCounter"] -= 1
        with self.assertRaisesRegex(REJECTION, "Nonce conservation"):
            subject.audit(scenario, events, actual, result)

    def test_corrupt_partial_emitted_bytes_are_rejected(self):
        scenario, events, actual, result = self.case("aborted")
        self.first_frame(events, "PAYLOAD_ABORTED")["frameEvidence"]["emittedBytes"] += 1
        with self.assertRaises(REJECTION):
            subject.audit(scenario, events, actual, result)

    def test_new_compromised_frame_at_ground_notice_is_rejected(self):
        scenario, events, actual, result = self.case("aborted")
        aborted = self.first_frame(events, "PAYLOAD_ABORTED")
        ground = next(e for e in events if e["type"] == "REVOCATION_RECEIVED_GROUND")
        self.assertEqual(aborted["at"], ground["at"])
        # Zero emission is allowed by frame accounting, but committing a new
        # frame at the already-known revocation boundary is forbidden.
        aborted["frameEvidence"]["from"] = aborted["at"]
        aborted["frameEvidence"]["emittedBytes"] = 0
        with self.assertRaisesRegex(REJECTION, "New compromised frame admitted after known ground revocation"):
            subject.audit(scenario, events, actual, result)

    def test_frame_overlapping_channel_outage_is_rejected(self):
        scenario, events, actual, result = self.case("finished")
        event = self.first_frame(events)
        frame = journal.read_frame(event, {c["window"]["id"]: c for c in scenario["contacts"]})
        events.extend([self.channel_event(event, "CHANNEL_DOWN", frame["from"]),
                       self.channel_event(event, "CHANNEL_UP", frame["until"])])
        self.resequence(events)
        with self.assertRaisesRegex(REJECTION, "Frame during outage"):
            subject.audit(scenario, events, actual, result)

    def test_recovery_readiness_respects_channel_down_and_up(self):
        scenario, events, _, _ = self.case("finished")
        replay = recovery.Replay(scenario, set())
        frame_event = self.first_frame(events)
        for event in events:
            replay.apply(event)
            if event is frame_event:
                break
        cid = journal.context(frame_event["contextId"])
        at = journal.instant(frame_event["at"])
        self.assertTrue(replay.sa_ready(cid, at))
        replay.apply(self.channel_event(frame_event, "CHANNEL_DOWN", at))
        self.assertFalse(replay.sa_ready(cid, at))
        self.assertTrue(replay.conditions(cid, at)["target_channel_unavailable"])
        replay.apply(self.channel_event(frame_event, "CHANNEL_UP", at))
        self.assertTrue(replay.sa_ready(cid, at))
        self.assertFalse(replay.conditions(cid, at)["target_channel_unavailable"])


if __name__ == "__main__":
    unittest.main()
