import copy
from pathlib import Path
import tempfile
import unittest

from run_model_replay import checked_file, validate_video, phone_compare
from validate_round_replay import validate


class ValidationTest(unittest.TestCase):
    def setUp(self):
        self.expected = {"initial": ["A:RED", "A:RED"], "seats": ["NEXT"],
                         "plays": [{"seat": "NEXT", "cards": ["K:BLACK", "K:BLACK"]}],
                         "remaining": 7, "frames": 20}
        self.actual = {"initial": ["A:RED", "A:RED"], "events": self.expected["plays"],
                       "remaining": 7, "frames": 20, "failures": [], "lastKnownPhase": "FINISHED"}

    def test_exact_manual_cards_and_completion_pass(self):
        self.assertTrue(validate(self.actual, self.expected)["passed"])

    def test_missing_duplicate_card_is_detected(self):
        actual = copy.deepcopy(self.actual)
        actual["events"][0]["cards"].pop()
        self.assertIn("play_order_seat_or_cards_differ", validate(actual, self.expected)["problems"])

    def test_silent_lost_play_cannot_pass_just_because_round_finished(self):
        actual = copy.deepcopy(self.actual)
        actual["events"] = []
        self.assertIn("play_count_differs", validate(actual, self.expected)["problems"])

    def test_truncated_video_and_unfinished_round_fail(self):
        actual = copy.deepcopy(self.actual)
        actual.update(frames=19, lastKnownPhase="ACTIVE")
        result = validate(actual, self.expected)
        self.assertIn("frame_coverage_differs", result["problems"])
        self.assertIn("round_not_finished", result["problems"])

    def test_missing_error_field_is_not_a_pass(self):
        actual = copy.deepcopy(self.actual)
        del actual["failures"]
        self.assertFalse(validate(actual, self.expected)["passed"])

    def test_own_play_cannot_be_ignored_when_manually_annotated(self):
        expected = copy.deepcopy(self.expected)
        expected["ownPlays"] = [["A:RED"]]
        self.assertIn("own_play_cards_or_order_differ", validate(self.actual, expected)["problems"])

    def test_source_cannot_silently_change(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "source.txt"
            source.write_text("altered")
            with self.assertRaises(ValueError):
                checked_file({"path": str(source), "sha256": "0" * 64})

    def test_phone_raw_multiset_is_not_silently_filtered(self):
        glyph = {"rank": "A", "color": "RED", "bbox": [0, 0, .01, .01]}
        result = phone_compare([glyph], ["A:RED"])
        self.assertTrue(result["phoneColorMultisetMatches"])
        self.assertEqual(result["filteredCount"], 0)

    def test_scene_gaps_are_rejected_before_inference(self):
        import hashlib
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / "video.txt"
            source.write_bytes(b"placeholder")
            manifest = {"source": {"path": str(source), "sha256": hashlib.sha256(source.read_bytes()).hexdigest()},
                        "reviewedWindowSeconds": [0, 3], "safeReviewEndSeconds": 3, "expectedFrames": 10,
                        "segments": [{"start": 0, "end": 1, "scene": "TABLE", "selfFinished": False},
                                     {"start": 2, "end": 3, "scene": "RESULT", "selfFinished": True}]}
            with self.assertRaises(ValueError):
                validate_video(manifest)


if __name__ == "__main__":
    unittest.main()
