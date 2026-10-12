"""Synthetic checks for the evidence summary; no device or game input."""
import importlib.util
import json
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("live_trace", Path(__file__).with_name("analyze-live-trace.py"))
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)


class TraceSummaryTests(unittest.TestCase):
    def test_own_tag_only_and_malformed_json(self):
        record = {"type": "frame", "sessionId": "s", "elapsedMs": 10}
        rows, malformed = trace.records([
            "123 100 100 I JPQTrace: " + json.dumps(record),
            "123 100 100 I OtherApp: " + json.dumps(record),
            "123 100 100 I JPQTrace: {truncated",
            json.dumps(record),
        ])
        self.assertEqual(rows, [record, record])
        self.assertEqual(malformed, 1)

    def test_deduplicate_trace_copies_and_repeated_ledger_snapshots(self):
        frame = {"type": "frame", "sessionId": "s", "elapsedMs": 10, "inferenceMs": 51}
        ledger = {"type": "ledger", "sessionId": "s", "roundId": "r", "elapsedMs": 20,
                  "phase": "ACTIVE", "event": {"id": "e1", "sequence": 1, "seat": "NEXT", "cards": ["RED-FOUR"]}}
        repeated = {**ledger, "elapsedMs": 30}
        report = trace.summarize([frame, frame, ledger, ledger, repeated])["sessions"][0]
        self.assertEqual(report["frames"], 1)
        self.assertEqual(report["confirmedUniqueEvents"], 1)
        self.assertEqual(report["eventIdentityConflicts"], [])
        self.assertEqual(report["timingsMs"]["inferenceMs"]["median"], 51)

    def test_conflicting_event_identity_and_unavailable(self):
        one = {"type": "ledger", "sessionId": "s", "roundId": "r", "elapsedMs": 20,
               "phase": "ACTIVE", "event": {"id": "e1", "sequence": 1, "seat": "NEXT", "cards": ["RED-FOUR"]}}
        changed = {**one, "elapsedMs": 30, "event": {**one["event"], "sequence": 2, "seat": "PREVIOUS", "cards": ["BLACK-FOUR"]}}
        unavailable = {"type": "ledger", "sessionId": "s", "roundId": "r", "elapsedMs": 40,
                       "phase": "UNAVAILABLE", "reason": "test conflict"}
        report = trace.summarize([one, changed, unavailable])["sessions"][0]
        self.assertEqual(len(report["eventIdentityConflicts"]), 1)
        self.assertEqual(len(report["unavailable"]), 1)

    def test_current_changes_include_every_new_event(self):
        one = {"type": "ledger", "sessionId": "s", "roundId": "r", "elapsedMs": 20,
               "phase": "ACTIVE", "changes": [
                   {"id": "e1", "sequence": 1, "seat": "NEXT", "cards": ["RED-FOUR"]},
                   {"id": "e2", "sequence": 2, "seat": "OPPOSITE", "cards": ["BLACK-TEN", "RED-TEN"]}]}
        empty = {**one, "elapsedMs": 30, "changes": [],
                 "event": {"id": "stale", "sequence": 99, "seat": "SELF", "cards": ["RED-ACE"]}}
        report = trace.summarize([one, one, empty])["sessions"][0]
        self.assertEqual(report["confirmedUniqueEvents"], 2)
        self.assertEqual([e["id"] for e in report["events"]], ["e1", "e2"])
        self.assertEqual(report["eventRevisions"], 0)

    def test_same_track_expansion_preserves_first_confirmation_and_final_cards(self):
        event = {"id": "e1", "sequence": 1, "seat": "NEXT", "cards": ["RED-FOUR"]}
        one = {"type": "ledger", "sessionId": "s", "roundId": "r", "elapsedMs": 20,
               "phase": "ACTIVE", "changes": [event]}
        expanded = {**one, "elapsedMs": 50, "changes": [{**event, "cards": ["RED-FOUR", "BLACK-FOUR", "BLACK-FOUR"]}]}
        reordered = {**expanded, "elapsedMs": 60,
                     "changes": [{**event, "cards": ["BLACK-FOUR", "RED-FOUR", "BLACK-FOUR"]}]}
        report = trace.summarize([expanded, one, expanded, reordered])["sessions"][0]
        result = report["events"][0]
        self.assertEqual(report["confirmedUniqueEvents"], 1)
        self.assertEqual(report["eventIdentityConflicts"], [])
        self.assertEqual(report["revisedEvents"], 1)
        self.assertEqual(report["eventRevisions"], 1)
        self.assertEqual(result["firstConfirmedElapsedMs"], 20)
        self.assertEqual(result["elapsedMs"], 20)
        self.assertEqual(result["updatedAtElapsedMs"], 50)
        self.assertEqual(result["initialCards"], ["RED-FOUR"])
        self.assertEqual(result["finalCards"], ["RED-FOUR", "BLACK-FOUR", "BLACK-FOUR"])
        self.assertEqual(result["revisions"][0]["kind"], "extension")

    def test_round_isolation_and_legacy_card_revision(self):
        event = {"id": "same", "sequence": 1, "seat": "NEXT", "cards": ["RED-FOUR"]}
        one = {"type": "ledger", "sessionId": "s", "roundId": "r1", "elapsedMs": 20,
               "phase": "ACTIVE", "event": event}
        revision = {**one, "elapsedMs": 40, "event": {**event, "cards": ["BLACK-FOUR"]}}
        next_round = {**one, "roundId": "r2", "elapsedMs": 100,
                      "event": {**event, "sequence": 2, "seat": "PREVIOUS", "cards": ["RED-ACE"]}}
        report = trace.summarize([one, revision, next_round])["sessions"][0]
        self.assertEqual(report["confirmedUniqueEvents"], 2)
        self.assertEqual(report["eventIdentityConflicts"], [])
        self.assertEqual(report["eventRevisions"], 1)
        first, second = report["events"]
        self.assertEqual((first["roundId"], first["firstConfirmedElapsedMs"], first["updatedAtElapsedMs"]), ("r1", 20, 40))
        self.assertEqual(first["revisions"][0]["kind"], "card_change")
        self.assertEqual((second["roundId"], second["firstConfirmedElapsedMs"], second["updatedAtElapsedMs"]), ("r2", 100, 100))
        self.assertEqual(second["revisions"], [])

    def test_stage_timing_never_claims_game_accuracy(self):
        report = trace.summarize([])
        self.assertEqual(report["sessions"], [])
        self.assertIn("not assessed", report["accuracyVerdict"])
        self.assertIn("not visible-event-to-overlay latency", report["timingCaveat"])


if __name__ == "__main__":
    unittest.main()
