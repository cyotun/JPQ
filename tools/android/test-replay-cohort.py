import importlib.util
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("cohort", Path(__file__).with_name("audit-replay-cohort.py"))
cohort = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cohort)


class CohortGateTest(unittest.TestCase):
    def run_case(self, rounds, exclusions=None, source_sha=None):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            (path / "source.json").write_text(json.dumps({"frames": 100, "rounds": rounds}), encoding="utf-8")
            return cohort.audit([{"session": "source", "frames": 100, "sha256": source_sha}], path, exclusions,
                                expected_sources=1, minimum_rounds=2)

    def valid(self, name):
        return {"roundId": name, "confirmedEvents": 35, "endedAt": 1000,
                "lastPhase": "FINISHED", "countsVerified": True, "completionObserved": True}

    def test_continuous_different_complete_rounds_pass(self):
        self.assertTrue(self.run_case([self.valid("one"), self.valid("two")])["gatePassed"])

    def test_result_without_confirmed_completion_does_not_pass(self):
        bad = {**self.valid("two"), "completionObserved": False}
        self.assertFalse(self.run_case([self.valid("one"), bad])["gatePassed"])

    def test_duplicate_round_cannot_pad_a_passing_streak(self):
        with self.assertRaises(ValueError):
            self.run_case([self.valid("one"), self.valid("one")])

    def test_hiding_counts_or_losing_the_tail_breaks_the_streak(self):
        for change in [{"countsVerified": False}, {"endedAt": None}, {"lastPhase": "UNAVAILABLE"}]:
            result = self.run_case([self.valid("one"), {**self.valid("two"), **change}, self.valid("three")])
            self.assertFalse(result["gatePassed"])
            self.assertEqual(1, result["longestConsecutivePassingRounds"])

    def test_zero_event_opening_never_pads_the_pass_count(self):
        result = self.run_case([self.valid("one"), {**self.valid("two"), "confirmedEvents": 0}])
        self.assertEqual(1, result["passedRounds"])
        self.assertFalse(result["gatePassed"])

    def test_only_proved_allowed_occlusion_can_be_excluded(self):
        rounds = [self.valid("one"), {**self.valid("masked"), "countsVerified": False}, self.valid("three")]
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            artifact = path / "private-frame.txt"
            artifact.write_text("Test fixture for evidence integrity only", encoding="utf-8")
            source_sha = "a" * 64
            review = path / "review.json"
            body = {"session": "source", "roundId": "masked", "category": "screen_occluded",
                    "sourceSha256": source_sha, "frameRange": [20, 30], "confirmedOcclusion": True,
                    "reviewedBy": "test reviewer", "artifacts": [
                        {"path": str(artifact), "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()}]}

            def exclusion():
                review.write_text(json.dumps(body), encoding="utf-8")
                return {"session": "source", "roundId": "masked", "category": "screen_occluded",
                        "reason": "Reviewed occlusion", "evidence": {
                            "path": str(review), "sha256": hashlib.sha256(review.read_bytes()).hexdigest()}}

            reference = exclusion()
            result = self.run_case(rounds, [reference], source_sha)
            self.assertTrue(result["gatePassed"])
            self.assertEqual(2, result["passedRounds"])
            for change in [{"category": "recognizer_bug"}, {"evidence": "description only"}]:
                with self.assertRaises(ValueError):
                    self.run_case(rounds, [{**reference, **change}], source_sha)
            artifact.write_text("changed after review", encoding="utf-8")
            with self.assertRaises(ValueError):
                self.run_case(rounds, [reference], source_sha)
            body["sourceSha256"] = "b" * 64
            with self.assertRaises(ValueError):
                self.run_case(rounds, [exclusion()], source_sha)
            body["sourceSha256"] = source_sha
            body["frameRange"] = [20, 101]
            with self.assertRaises(ValueError):
                self.run_case(rounds, [exclusion()], source_sha)

    def test_missing_source_report_cannot_silently_shrink_the_cohort(self):
        with tempfile.TemporaryDirectory() as folder:
            result = cohort.audit([{"session": "missing", "frames": 100}], Path(folder), expected_sources=1)
            self.assertFalse(result["gatePassed"])
            self.assertEqual("missing_report", result["problems"][0]["reason"])


if __name__ == "__main__":
    unittest.main()
