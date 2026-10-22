"""Regression checks for SE2 figure evidence selection and censor semantics."""
import unittest
from decimal import Decimal
import plot_security_evaluation_se2 as plots


class FigureEvidenceTest(unittest.TestCase):
    def test_censored_bound_remains_separate_from_measured_recovery(self):
        row = {"status": "FINISHED", "security": {"recoveryStatus": "CENSORED_NO_RECOVERY",
               "groundNoticeAt": "1767225612.000000001", "censorAt": "1767225685.000000001",
               "detectionToRecovery": None}}
        self.assertEqual(("CENSORED", 73.0), plots.recovery_point(row))
        row["security"] = {"recoveryStatus": "NO_TARGET", "detectionToRecovery": None}
        self.assertEqual(("NT", None), plots.recovery_point(row))
        row["status"] = "INCOMPLETE"
        self.assertEqual(("I", None), plots.recovery_point(row))
        row["status"] = "ERROR"
        self.assertEqual(("E", None), plots.recovery_point(row))

    def test_registration_retains_unrun_case_without_inventing_metrics(self):
        manifest = {"cases": [{"name": "done", "policy": "next"}, {"name": "missing", "policy": "multi"}]}
        audit = {"runs": {"done": {"status": "FINISHED", "five": {"servedContacts": 3}}}}
        rows = plots.registered_rows(manifest, audit)
        self.assertEqual(2, len(rows))
        self.assertEqual("NOT_RUN", rows[1]["status"])
        self.assertNotIn("five", rows[1])
        self.assertEqual(("NR", None), plots.recovery_point(rows[1]))

    def test_quality_pair_excludes_incomplete_and_no_target(self):
        rows = [{"policy": p, "status": "FINISHED", "security": {"recoveryStatus": "RECOVERED"}} for p in ("next", "multi")]
        self.assertIsNotNone(plots.matched_target_pair(rows))
        rows[1]["_controlFinished"] = False
        self.assertIsNone(plots.matched_target_pair(rows))
        rows[1]["_controlFinished"] = True
        rows[0]["security"]["recoveryStatus"] = "CENSORED_NO_RECOVERY"
        self.assertIsNotNone(plots.matched_target_pair(rows))
        rows[0]["security"]["recoveryStatus"] = "NO_TARGET"
        self.assertIsNone(plots.matched_target_pair(rows))
        rows[0]["status"] = "INCOMPLETE"
        self.assertIsNone(plots.matched_target_pair(rows))
        self.assertEqual("N: incomplete", plots.pair_exclusion_reason(rows))
        rows[0]["status"] = "FINISHED"
        rows[0]["_controlFinished"] = False
        rows[1]["status"] = "SEARCH_INCOMPLETE"
        self.assertEqual("M: incomplete\nN: control incomplete", plots.pair_exclusion_reason(rows))

    def test_attack_categories_sort_by_time_and_preserve_nanoseconds(self):
        rows = [{"fixture": "X", "mode": "detected", "attackPoint": p, "attackAt": t} for p, t in
                (("late", "2026-01-01T00:00:10.000000002Z"), ("early", "2026-01-01T00:00:10.000000001Z"))]
        self.assertEqual(["early", "late"], plots.attack_points(rows, "X"))
        self.assertEqual(Decimal("0.000000001"), plots.instant(rows[0]["attackAt"]) - plots.instant(rows[1]["attackAt"]))
        rows.append(rows[0] | {"attackAt": "2026-01-01T00:00:20Z"})
        with self.assertRaises(ValueError):
            plots.attack_points(rows, "X")


if __name__ == "__main__":
    unittest.main()
