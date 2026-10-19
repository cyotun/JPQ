"""Local schema/provenance checks for prepare-session-replay; no device or Gradle."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("session_replay", Path(__file__).with_name("prepare-session-replay.py"))
replay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(replay)
TEMP_ROOT = Path(__file__).resolve().parents[2] / "captures" / ".test-session-replay"
TEMP_ROOT.mkdir(parents=True, exist_ok=True)


class SessionReplayTests(unittest.TestCase):
    def test_explicit_physical_single_keeps_its_own_source_and_no_legacy_inference(self):
        frame=self.frame(groups={"OPPOSITE":["A:BLACK"]},regionObserved={"OPPOSITE":True},
            singleCardIdentities={"OPPOSITE":{"card":"A:BLACK","suit":"CLUB","similarity":.86,"margin":.15,"sourceTimeMs":1234}})
        row,_=replay.frame_to_tape_row(frame)
        self.assertEqual("OPPOSITE,A,CLUB,0.86,0.15,1234",row.split(";")[14])
        frame["singleCardIdentities"]["OPPOSITE"]["sourceTimeMs"]=1235
        self.assertEqual("",replay.encode_single_identities(frame))
        frame["singleCardIdentities"]["OPPOSITE"]["sourceTimeMs"]=1234
        frame["regionObserved"]["OPPOSITE"]=False
        self.assertEqual("",replay.encode_single_identities(frame))

    def temporary_directory(self):
        return tempfile.TemporaryDirectory(dir=TEMP_ROOT)

    def frame(self, **changes):
        return {
            "type": "frame", "sessionId": "session-1", "elapsedMs": 1234,
            "scene": "TABLE", "selfFinished": False,
            "glyphs": [["FOUR:RED", .9, .1, .2, .3, .4]],
            "passing": ["PREVIOUS"],
            "overlayMasks": [[.1, .2, .3, .4]],
            "emptyEvidence": {
                "SELF": {"observed": True, "reason": "table_surface_visible"},
                "PREVIOUS": {"observed": True, "reason": "table_surface_visible"},
                "OPPOSITE": {"observed": False, "reason": "overlay_occlusion"},
            },
            "remainingHandCounts": {
                "PREVIOUS": {"count": 9, "sourceTimeMs": 1200},
                "NEXT": {"count": 27, "sourceTimeMs": 1210},
            },
            **changes,
        }

    def make_archive(self, folder, frames, include_probe=True):
        folder = Path(folder)
        archive = folder / "diagnostics.zip"
        trace = [{"type": "session", "sessionId": "session-1"}, *frames]
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("sessions/session-1/trace.jsonl", "".join(json.dumps(row) + "\n" for row in trace))
            zipped.writestr("diagnostic/source.jpg", b"archive-source-image")
            if include_probe:
                zipped.writestr("capture-probes/probe-a/probe.json", json.dumps({"imageTimestampNs": 88}))
                zipped.writestr("capture-probes/probe-a/legacy.png", b"source-image")
                zipped.writestr("capture-probes/probe-a/converted.png", b"converted-image")
        return archive

    def prepare(self, folder, frames, include_probe=True):
        archive = self.make_archive(folder, frames, include_probe)
        output = Path(folder) / "out"
        result = replay.prepare(archive, ["session-1"], output, output / "manifest.json")
        return archive, output, result

    def test_preserves_trace_semantics_masks_counts_and_provenance(self):
        with self.temporary_directory() as folder:
            frame = self.frame(imageTimestampNs=88)
            archive, output, result = self.prepare(folder, [frame])
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual(14, len(row))
            self.assertEqual(["1234", "TABLE", "false", "true,true,false,false", "true", "false"], row[:6])
            self.assertEqual("FOUR,RED,0.9,0.1,0.2,0.3,0.4", row[6])
            self.assertEqual("PREVIOUS", row[7])
            self.assertEqual("false", row[8])
            self.assertEqual("false", row[9])
            self.assertEqual("0.09990,0.19990,0.30010,0.40010", row[10])
            self.assertEqual("?", row[11])
            self.assertEqual("PREVIOUS,9,1200|NEXT,27,1210", row[12])
            self.assertEqual("table_surface_visible,table_surface_visible,overlay_occlusion,?", row[13])
            source = result["sources"][0]
            self.assertEqual(2, result["schemaVersion"])
            self.assertEqual(replay.sha256_file(archive), result["archiveSha256"])
            self.assertEqual(1, source["frameCount"])
            self.assertEqual(replay.sha256_bytes((output / "session-1.tape").read_bytes()), source["tapeSha256"])
            self.assertEqual(64, len(source["traceSha256"]))
            self.assertEqual(2, len(source["sourceImageSha256s"]))
            archive_image = next(image for image in result["archiveImages"]
                                 if image["member"] == "diagnostic/source.jpg")
            self.assertEqual(replay.sha256_bytes(b"archive-source-image"), archive_image["sha256"])

    def test_opening_proof_requires_its_source_timestamp_and_counts_drop_bad_sources(self):
        with self.temporary_directory() as folder:
            frame = self.frame(openingChoiceSourceTimeMs=1201, dealingChoicesObserved=True,
                               remainingHandCounts={"PREVIOUS": {"count": 6}})
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("1201", row[11])
            self.assertEqual("", row[12])
            self.assertEqual("table_surface_visible,table_surface_visible,overlay_occlusion,?", row[13])
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(1, manifest["sources"][0]["omittedMalformedCountEntries"])
            self.assertEqual([], manifest["sources"][0]["sourceImageSha256s"])

    def test_reason_proves_capture_gap_and_adjustment_without_glyph_inference(self):
        with self.temporary_directory() as folder:
            frame = self.frame(reason="adjustment_occlusion", glyphs=None, overlayMasks=None,
                               emptyEvidence=None, remainingHandCounts=None)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("false,false,false,false", row[3])
            self.assertEqual("false", row[4])
            self.assertEqual("true", row[5])
            self.assertEqual("", row[6])
            self.assertEqual("true", row[8])
            self.assertEqual("?", row[10])
            self.assertEqual("?", row[11])
            self.assertEqual("", row[12])

    def test_unconfirmed_adjusting_and_bad_opening_timestamp_stay_false_or_unknown(self):
        with self.temporary_directory() as folder:
            frame = self.frame(overlayMasks=[[.2, .2, .3, .3]], openingChoiceSourceTimeMs=True)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("false", row[5])
            self.assertEqual("?", row[11])

    def test_missing_glyphs_and_source_reason_force_unknown_capture_gap(self):
        with self.temporary_directory() as folder:
            frame = self.frame(glyphs=None, captureGap=False, reason="portrait_non_game")
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("true", row[8])

    def test_joker_color_keys_and_zero_detections_are_preserved(self):
        with self.temporary_directory() as folder:
            frame = self.frame(scene="LOBBY", glyphs=[
                ["BIG", .99, .1, .2, .2, .3], ["SMALL", .98, .3, .2, .4, .3]],
                emptyEvidence={"SELF": {"observed": False, "reason": "unrecognized;reason"}})
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("BIG,NONE,0.99,0.1,0.2,0.2,0.3|SMALL,NONE,0.98,0.3,0.2,0.4,0.3", row[6])
            self.assertEqual("LOBBY", row[1])
            self.assertEqual("false", row[8])
            self.assertEqual("?,?,?,?", row[13])

        with self.temporary_directory() as folder:
            frame = self.frame(scene="RESULT", glyphs=[])
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("RESULT", row[1])
            self.assertEqual("false", row[8])

        with self.temporary_directory() as folder:
            frame = self.frame(glyphs=None, reason=None)
            _, output, _ = self.prepare(folder, [frame], include_probe=False)
            row = (output / "session-1.tape").read_text(encoding="utf-8").strip().split(";")
            self.assertEqual("UNKNOWN", row[1])
            self.assertEqual("true", row[8])

    def test_missing_or_duplicate_trace_and_unsafe_session_are_rejected(self):
        with self.temporary_directory() as folder:
            archive = self.make_archive(folder, [self.frame()], include_probe=False)
            output = Path(folder) / "out"
            with self.assertRaises(ValueError):
                replay.prepare(archive, ["../escape"], output, output / "manifest.json")
        with self.temporary_directory() as folder:
            archive = Path(folder) / "duplicate.zip"
            trace = json.dumps({"type": "session", "sessionId": "session-1"}) + "\n"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("sessions/session-1/trace.jsonl", trace)
                zipped.writestr("sessions/session-1/trace.jsonl", trace)
            output = Path(folder) / "out"
            with self.assertRaises(ValueError):
                replay.prepare(archive, ["session-1"], output, output / "manifest.json")


if __name__ == "__main__":
    unittest.main()
