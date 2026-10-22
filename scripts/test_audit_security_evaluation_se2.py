"""SE2 independent oracles for new audit risks (no Java collectors)."""
import copy
from decimal import Decimal as D
import unittest

import audit_security_evaluation as se1
import audit_security_evaluation_se2 as audit
from test_audit_security_evaluation import CTX, KEYS, EPOCH, at, event, frame


def contact(start=0, end=10, station="A", cid="C1"):
    return {"window": {"id": cid, "satelliteId": "SC", "groundStationId": station,
                        "start": at(start), "end": at(end)}, "payload": {"frameBytes": 100, "bytesPerSecond": 100}}


def state_scenario():
    return {"name": "physical", "initialState": {"keys": {}, "contexts": {}},
            "contacts": [contact(), contact(20, 30, cid="C2")], "end": at(30),
            "constraints": {"maxMaterialAge": 60, "cryptoperiod": 30, "blockedKeyIds": []}, "disclosures": []}


def transition(sequence, when, action, pair=KEYS):
    return event("STEP_COMPLETED", when, pair) | {"sequence": sequence, "action": action}


def spec(name, policy="next", mode="detected"):
    return {"name": name, "fixture": "X", "block": "time", "workload": "LIGHT", "policy": policy,
            "mode": mode, "baselineCase": name if mode == "none" else "base", "fixedPeriodSeconds": 30}


def registration():
    config = {"id": "X", "block": "load", "workload": "LIGHT", "fixedPeriodSeconds": 30,
              "timingModel": "BASELINE", "noticeDurationScale": 1, "target": CTX,
              "originalAttackAt": at(5), "fullAttackGrid": False,
              "points": [{"id": "original", "at": at(5), "sources": ["original"]}]}
    specs, models = {}, {}
    for policy in ("fixed", "percontact", "next", "multi"):
        for mode in ("none", "detected", "undetected"):
            name = policy + "-" + mode
            specs[name] = spec(name, policy, mode) | {
                "block": "load", "timingModel": "BASELINE", "noticeDurationScale": 1,
                "baselineCase": policy + "-none", "attackPoint": None if mode == "none" else "original",
                "attackAt": None if mode == "none" else at(5)}
            models[name] = state_scenario() | {"start": at(0)}
            if mode != "none":
                models[name]["disclosures"] = [{"contextId": CTX, "at": at(5),
                                               "detectionAt": at(7) if mode == "detected" else None}]
    return {"configurations": [config]}, specs, models


class RegistrationTests(unittest.TestCase):
    def test_full_registration_checks_physics_target_grid_and_matrix(self):
        manifest, specs, models = registration()
        self.assertEqual(len(audit.validate_registration(manifest, specs, models)), 1)
        for change in ("time", "target", "delay", "physics", "missing-policy"):
            with self.subTest(change=change):
                m, s, inputs = copy.deepcopy((manifest, specs, models))
                if change == "time":
                    inputs["multi-detected"]["disclosures"][0]["at"] = at(6)
                elif change == "target":
                    inputs["multi-detected"]["disclosures"][0]["contextId"] = CTX | {"groundStationId": "B"}
                elif change == "delay":
                    inputs["multi-detected"]["disclosures"][0]["detectionAt"] = at(8)
                elif change == "physics":
                    inputs["multi-detected"]["contacts"][0]["payload"]["frameBytes"] = 50
                else:
                    del s["multi-detected"]
                with self.assertRaises(ValueError):
                    audit.validate_registration(m, s, inputs)

    def test_registration_rejects_unregistered_attack_in_full_grid(self):
        manifest, specs, models = registration()
        manifest["configurations"][0]["fullAttackGrid"] = True
        with self.assertRaisesRegex(ValueError, "grid size"):
            audit.validate_registration(manifest, specs, models)

    def test_schedule_grid_deduplicates_original_and_uses_physical_gap(self):
        schedule = [contact(), contact(20, 30, "B", "C2"), contact(40, 50, "A", "C3")]
        result = audit.attack_grid(schedule, CTX, at(5))
        self.assertEqual(result, {"original": EPOCH + 5, "p10": EPOCH + 1, "p30": EPOCH + 3,
                                  "p70": EPOCH + 7, "p90": EPOCH + 9, "gap-midpoint": EPOCH + 15})
        # No control or planner information is accepted by this function.
        self.assertEqual(result, audit.attack_grid(list(reversed(schedule)), CTX, at(5)))

    def test_nanoseconds_use_integer_floor_without_rounding_contact(self):
        grid = audit.attack_grid([contact(0, D("0.000000019"))], CTX, at(0))
        self.assertEqual(grid["p10"], EPOCH + D("0.000000001"))
        self.assertEqual(grid["p90"], EPOCH + D("0.000000017"))
        self.assertNotIn("gap-midpoint", grid)

    def test_control_ignores_only_name_and_incident(self):
        control = state_scenario()
        attacked = copy.deepcopy(control)
        attacked["name"] = "attack"
        attacked["disclosures"] = [{"id": "incident"}]
        audit.validate_control("case", spec("case"), attacked, spec("base", mode="none"), control)
        for change in ("contact", "history", "period"):
            with self.subTest(change=change):
                baseline, bs = copy.deepcopy(control), spec("base", mode="none")
                if change == "contact":
                    baseline["contacts"][0]["window"]["end"] = at(11)
                elif change == "history":
                    baseline["initialState"]["asOf"] = at(1)
                else:
                    bs["fixedPeriodSeconds"] = 31
                with self.assertRaises(ValueError):
                    audit.validate_control("case", spec("case"), attacked, bs, baseline)

    def test_existing_noninterference_rejects_future_attack_decision_leak(self):
        scenario = state_scenario()
        scenario["disclosures"] = [{"at": at(2), "detectionAt": at(4)}]
        case = {"_scenario": scenario, "policy": "next", "security": None, "status": "INCOMPLETE",
                "actualAt": at(6), "_decisions": [{"at": at(1), "executedAction": "INSTALL", "searchStatus": "COMPLETE"}]}
        baseline = copy.deepcopy(case)
        baseline["_scenario"]["disclosures"] = []
        baseline["_decisions"][0]["executedAction"] = "CONTINUE"
        with self.assertRaisesRegex(ValueError, "hidden incident"):
            se1.pair_checks("attack", {"pairedDelta": None}, case, baseline)
        baseline["_decisions"][0]["executedAction"] = "INSTALL"
        self.assertEqual(se1.pair_checks("attack", {"pairedDelta": None}, case, baseline), 1)


class DiagnosticTests(unittest.TestCase):
    def test_inventory_never_uses_future_install_or_activation(self):
        scenario = state_scenario()
        events = [transition(0, 1, "PREPARE"), transition(1, 3, "INSTALL"), transition(2, 4, "SWITCH")]
        before = audit.key_inventory(scenario, events, CTX, 1, EPOCH + 2, set())
        installed = audit.key_inventory(scenario, events, CTX, 2, EPOCH + D("3.5"), set())
        active = audit.key_inventory(scenario, events, CTX, 3, EPOCH + 5, set())
        self.assertEqual((before["preparedIndependentKeys"], before["installedIndependentKeys"]), (2, 0))
        self.assertEqual((installed["preparedIndependentKeys"], installed["installedIndependentKeys"]), (0, 2))
        self.assertTrue(active["activeIndependentPair"])
        self.assertEqual(active["installedIndependentKeys"], 0)
        compromised = audit.key_inventory(scenario, events, CTX, 3, EPOCH + 5, set(KEYS.values()))
        self.assertFalse(compromised["activeIndependentPair"])

    def test_expired_prepared_material_is_not_an_usable_spare(self):
        scenario = state_scenario()
        scenario["constraints"]["maxMaterialAge"] = 2
        inventory = audit.key_inventory(scenario, [transition(0, 1, "PREPARE")], CTX, 1, EPOCH + 3, set())
        self.assertEqual(inventory["preparedIndependentKeys"], 0)

    def test_notice_boundary_preserves_aborted_uplink_and_excludes_completed_frame(self):
        notice = event("REVOCATION_RECEIVED_GROUND", D("2.5")) | {"sequence": 4}
        uplink = frame(2, 2.5, "old-up", False) | {"sequence": 5, "contactId": "C1"}
        diagnostic = audit.phase_at(notice, [uplink])
        self.assertEqual((diagnostic["frameDirection"], diagnostic["frameRemainingSeconds"]), ("UPLINK", D("0.5")))
        completed = frame(1.5, 2.5, "old-up") | {"sequence": 3, "contactId": "C1"}
        self.assertIsNone(audit.phase_at(notice, [completed])["frameDirection"])
        starts_after_notice = frame(2.5, 3.5, "new-up") | {"sequence": 10, "contactId": "C1"}
        self.assertIsNone(audit.phase_at(notice, [starts_after_notice])["frameDirection"])

    def test_no_future_target_contact_is_null_not_zero(self):
        scenario = state_scenario()
        self.assertEqual(audit.contact_geometry(scenario, CTX, EPOCH + 5),
                         {"nextTargetContactDelaySeconds": D(15), "remainingCurrentTargetContactSeconds": D(5)})
        self.assertEqual(audit.contact_geometry(scenario, CTX, EPOCH + 25),
                         {"nextTargetContactDelaySeconds": None, "remainingCurrentTargetContactSeconds": D(5)})


class CoverageTests(unittest.TestCase):
    def test_elapsed_diagnostic_preserves_large_measurements_and_is_not_cpu_or_filter(self):
        decisions = [{"source": "SEARCH", "at": at(i), "searchNanos": ns, "exploredNodes": i + 1,
                      "searchStatus": status, "searchStopReason": "TIME_LIMIT" if status == "INCOMPLETE" else "NONE",
                      "searchDiagnostics": {"generationNanos": 10, "executionNanos": 20, "boundNanos": 30}}
                     for i, (ns, status) in enumerate(((10_000_000_000, "COMPLETE"), (42_157_912_700, "INCOMPLETE"),
                                                       (3_361_406_088_400, "INCOMPLETE")))]
        decisions.append({"source": "RULE", "at": at(4), "searchNanos": None})
        rows = audit.search_timing_rows({"case": spec("case")},
                                       {"case": {"status": "SEARCH_INCOMPLETE", "_decisions": decisions}},
                                       {"maxNodes": 100000, "maxElapsed": D(5)})
        self.assertEqual(len(rows), 3)
        self.assertEqual([r["exceedsTwiceBudget"] for r in rows], [False, True, True])
        self.assertEqual(rows[-1]["elapsedNanos"], 3_361_406_088_400)
        self.assertEqual(rows[-1]["sumReportedPhaseNanos"], 60)
        self.assertEqual(rows[-1]["elapsedMinusReportedPhaseSumNanos"], 3_361_406_088_340)
        self.assertEqual(rows[-1]["searchStatus"], "INCOMPLETE")
        self.assertNotIn("cpu", rows[-1])

    def test_no_target_zero_exposure_is_not_ranked_as_security_win(self):
        common = {"fixture": "X", "block": "time", "workload": "LIGHT", "attackPoint": "original",
                  "attackAt": at(5), "mode": "detected", "timingModel": "BASELINE", "noticeDurationScale": 1,
                  "pairedControlStatus": "FINISHED", "servedContacts": 1, "deliveredBytes": 100,
                  "confidentialDeliveredBytes": 100, "decryptableBeforeBytes": 0, "decryptableAfterBytes": 0,
                  "detectionToRecovery": None, "controlCpu": D(1), "managementBytes": 100, "newKeys": 2}
        left = common | {"case": "multi", "policy": "multi", "recoveryStatus": "NO_TARGET"}
        right = common | {"case": "next", "policy": "next", "recoveryStatus": "RECOVERED",
                          "decryptableAfterBytes": 100, "detectionToRecovery": D(2)}
        pair = audit.pairwise([left, right])[0]
        self.assertEqual(pair["exposure"], "NOT_COMPARABLE")
        self.assertEqual(pair["exposureBefore"], "NOT_COMPARABLE")
        self.assertEqual(pair["exposureAfter"], "NOT_COMPARABLE")
        self.assertEqual(pair["recovery"], "NOT_COMPARABLE")
        self.assertFalse(pair["bothHadTarget"])
        self.assertEqual(pair["service"], "TIE")

    def test_censor_no_target_incomplete_and_not_run_kept_separate(self):
        specs = {name: spec(name) for name in ("recovered", "censored", "no-target", "partial", "absent")}
        results = {"recovered": {"status": "FINISHED", "security": {"recoveryStatus": "RECOVERED", "detectionToRecovery": D(2)}},
                   "censored": {"status": "FINISHED", "security": {"recoveryStatus": "CENSORED_NO_RECOVERY", "detectionToRecovery": None}},
                   "no-target": {"status": "FINISHED", "security": {"recoveryStatus": "NO_TARGET", "detectionToRecovery": None}},
                   "partial": {"status": "INCOMPLETE", "security": None}}
        row = audit.coverage(specs, results)[0]
        self.assertEqual((row["registered"], row["finished"], row["notRun"], row["unfinished"]), (5, 3, 1, 1))
        self.assertEqual((row["recovered"], row["censored"], row["noTarget"]), (1, 1, 1))
        self.assertEqual(row["recoveryFractionOfDetectedTargets"], D("0.5"))
        self.assertEqual(row["completionFraction"], D("0.6"))
        self.assertNotIn("meanRecovery", row)


if __name__ == "__main__":
    unittest.main()
